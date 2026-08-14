#include "t9_processor.h"
#include <rime/candidate.h>
#include <rime/common.h>
#include <rime/composition.h>
#include <rime/config.h>
#include <rime/context.h>
#include <rime/engine.h>
#include <rime/key_event.h>
#include <rime/key_table.h>
#include <rime/language.h>
#include <rime/menu.h>
#include <rime/schema.h>
#include <rime/ticket.h>
#include <rime/dict/dictionary.h>
#include <rime/dict/user_dictionary.h>
#include <algorithm>
#include <atomic>
#include <set>

#include "t9_log.h"
#include "t9_right_commit_utils.h"  // ParseSyllables

namespace rime {

// P1（2026-07-19）：全局活跃 T9Processor 指针改用 std::atomic。
//
// 解决的问题：裸指针的多线程读写存在数据竞争（torn read/write），
// std::atomic + acquire/release 内存序保证指针读写的原子性与可见性。
//
// 未解决（use-after-free）的说明：
//   JNI 线程获取指针后、调用方法前，RIME 线程可能析构 T9Processor。
//   完整修复需要 std::shared_mutex（有死锁风险：T9Processor 方法持读锁时
//   调用 RIME Engine 可能触发 schema 切换需要写锁）或 std::shared_ptr
//   （需重构 RIME Component 生命周期管理）。
//   当前由 Kotlin 层 RimeEngine.rimeLock 隐式保护：所有 RIME 操作
//   （destroy/switchSchema/processKey/t9*）均通过 rimeLock 串行化，
//   不会出现 JNI 调用与 T9Processor 析构并发的场景。
//   未来若引入多线程 RIME 操作（如后台词库更新触发 schema 切换），
//   需重新评估并引入 shared_mutex 或 shared_ptr 方案。
static std::atomic<T9Processor*> g_active_t9_processor{nullptr};

T9Processor* T9ProcessorRequire() {
    return g_active_t9_processor.load(std::memory_order_acquire);
}

// ════════════════════════════════════════
// 构造 / 析构
// ════════════════════════════════════════

T9Processor::T9Processor(const Ticket& ticket) : Processor(ticket) {
    g_active_t9_processor.store(this, std::memory_order_release);

    // 从方案配置读取 speller.delimiter 获取手动分隔符字符
    // delimiter 格式：" '"（空格 + 撇号），第二个字符为手动分隔符
    if (auto* schema = ticket.schema) {
        if (auto* config = schema->config()) {
            std::string delimiter;
            if (config->GetString("speller/delimiter", &delimiter) && delimiter.size() >= 2) {
                manual_delimiter_ = delimiter[1];
            }
            // 左侧候选区模式（英文九键适配 2026-08-07）：
            //   auto（默认）：engine/translators 含 script_translator（拼音方案）
            //     → 拼音音节消歧；否则（英文 table_translator，如 melt_eng_t9）
            //     → 无左栏候选（kNone）。
            //   显式 t9/left_panel_mode: pinyin|none 覆盖 auto 判定。
            std::string panel_mode;
            config->GetString("t9/left_panel_mode", &panel_mode);
            bool has_script_translator = false;
            if (auto translators = config->GetList("engine/translators")) {
                for (auto it = translators->begin(); it != translators->end(); ++it) {
                    auto value = As<ConfigValue>(*it);
                    if (value && value->str() == "script_translator") {
                        has_script_translator = true;
                        break;
                    }
                }
            }
            left_panel_mode_ = t9_panel_state::ResolveLeftPanelMode(
                has_script_translator, panel_mode);
        }
    }

    // 用户词典调频（对齐全键盘 Memory::Memorize 机制）：
    // 与主翻译器（script_translator）使用同一 name_space "translator"，
    // 通过组件池共享 Table/Prism/db（dict_name=rime_frost, prism_name=t9）。
    // 仅在右选候选时写入；创建失败（非拼音方案/未部署）时置空，调频自动跳过。
    if (auto dictionary = Dictionary::Require("dictionary")) {
        dict_.reset(dictionary->Create(Ticket(engine_, "translator")));
        if (dict_) dict_->Load();
    }
    if (auto user_dictionary = UserDictionary::Require("user_dictionary")) {
        user_dict_.reset(user_dictionary->Create(Ticket(engine_, "translator")));
        if (user_dict_) {
            user_dict_->Load();
            if (dict_) user_dict_->Attach(dict_->primary_table(), dict_->prism());
        }
    }
    T9LOG("T9Processor created (integrated T1-T6), manual_delimiter='%c', leftPanelMode=%d, userDict=%s",
          manual_delimiter_, static_cast<int>(left_panel_mode_),
          (user_dict_ && user_dict_->loaded()) ? "on" : "off");
}

T9Processor::~T9Processor() {
    // compare_exchange_strong 保证仅当当前指针仍指向 this 时才清空，
    // 避免误清新 T9Processor 的指针（极端竞态下可能发生）。
    T9Processor* expected = this;
    g_active_t9_processor.compare_exchange_strong(
        expected, nullptr,
        std::memory_order_release, std::memory_order_relaxed);
    T9LOG("T9Processor destroyed");
}

// ════════════════════════════════════════
// ProcessKeyEvent — 主入口
// ════════════════════════════════════════

ProcessResult T9Processor::ProcessKeyEvent(const KeyEvent& key_event) {
    T9_SCOPED_TIMER_TAG("T9Processor", "ProcessKeyEvent");
    T9_PERF_SCOPED_TIMER("[T9] ProcessKeyEvent");
    if (key_event.release() || key_event.ctrl() || key_event.alt())
        return kNoop;

    int ch = key_event.keycode();
    Context* ctx = engine_->context();
    string cur_input = ctx->input();

    T9LOG("ProcessKeyEvent: ch=%d('%c'), cur_input='%s', buf='%s'",
          ch, (ch >= 32 && ch < 127) ? (char)ch : '?',
          cur_input.c_str(), input_buffer_.ToBufferString().c_str());

    // 状态同步：composition 被外部清空但本地状态仍存在 → 清空本地
    // 异步 flush 模型下，ctx->input() 反映上次 FlushRimeInput 后的引擎状态；
    // 若本地仍有未 flush 的 pending（待发送内容），说明引擎状态将紧随更新，
    // 不视为"外部清空"。仅当无 pending 且引擎输入为空时，才同步清空本地状态。
    if (cur_input.empty() && !input_buffer_.is_empty() &&
        pending_action_ == RimePendingAction::kNone &&
        !undo_model_.HasPendingCommit()) {
        T9LOG("State sync: clearing stale local state");
        EnterIdle();
    }

    // 数字键 2-9
    if (ch >= '2' && ch <= '9') {
        return HandleDigitKey(static_cast<char>(ch));
    }

    // 分词键 1 或 '
    if (ch == '1') {
        return HandleSeparatorKey();
    }
    if (ch == '\'') {
        return HandleApostropheKey();
    }

    // Backspace
    if (ch == 0xff08 || ch == 0x08) {
        return HandleBackspace();
    }

    // Space: 有候选时交给 ExpressEditor 处理
    if (ch == ' ' && ctx->HasMenu()) {
        T9LOG("Space: hasMenu, passing to editor");
        return kNoop;
    }

    // Return: 交给 ExpressEditor commit
    if (ch == 0xff0d) {
        T9LOG("Return: passing to editor");
        return kNoop;
    }

    T9LOG("Key %d not handled, passing through", ch);
    return kNoop;
}

// ════════════════════════════════════════
// 按键处理子流程
// ════════════════════════════════════════

ProcessResult T9Processor::HandleDigitKey(char ch) {
    // 对应 Kotlin onDigitPressed
    T9_SCOPED_TIMER_TAG("T9Processor", "HandleDigitKey");
    T9_PERF_SCOPED_TIMER("[T9] HandleDigitKey");
    if (state_machine_.is_idle()) {
        state_machine_.EnterInput();
    }
    undo_model_.DigitPressed(ch);  // 段模型双写
    input_buffer_ = input_buffer_.AddDigit(ch);
    SendToRime();

    Context* ctx = engine_->context();
    T9LOG("Digit %c: buf='%s', rimeInput='%s', hasMenu=%d",
          ch, input_buffer_.ToBufferString().c_str(),
          ctx->input().c_str(), ctx->HasMenu() ? 1 : 0);
    LogPreeditState();
    return kAccepted;
}

ProcessResult T9Processor::HandleSeparatorKey() {
    // 纯分隔符模型：不推断音节，只记录分隔符位置并同步到 RIME。
    // 支持多个分隔符：每次分词键在 digit_sequence 末尾追加一个分隔符位置
    // （如 "5'43'6" → positions=[1,3]）。
    // 防抖范围：仅当分隔符位置未前进（两次分词键之间无数字输入）时才消费，
    // 保证有 2-9 数字参与的输入序列中每次分词都生效。
    T9_SCOPED_TIMER_TAG("T9Processor", "HandleSeparatorKey");
    T9_PERF_SCOPED_TIMER("[T9] HandleSeparatorKey");
    T9LOG(">> HandleSeparatorKey (pure separator): ENTER, buf='%s', sepPosCount=%zu",
          input_buffer_.ToBufferString().c_str(),
          input_buffer_.separator_positions.size());

    if (input_buffer_.is_empty()) {
        // 无输入序列 → 透传给 speller（'1' 在 alphabet 中，由 speller 处理为数字）
        // 连续按防抖：检查 RIME input 是否已经是 "1"
        if (engine_->context()->input() == "1") {
            T9LOG(">> HandleSeparatorKey: empty+consecutive, consume");
            return kAccepted;
        }
        T9LOG(">> HandleSeparatorKey: empty, pass through to speller");
        return kNoop;
    }

    // 有输入序列 → 追加分隔符。
    // 防抖：新分隔符位置 == 已有最后一个位置（两次分词键之间无数字输入）→ 消费。
    int new_pos = static_cast<int>(input_buffer_.digit_sequence.length());
    if (!input_buffer_.separator_positions.empty() &&
        input_buffer_.separator_positions.back() == new_pos) {
        T9LOG(">> HandleSeparatorKey: separator already at end (pos=%d), consume", new_pos);
        return kAccepted;
    }

    // 追加分隔符位置；后续数字键通过 separator_positions 在 ToRimeInputString 中插入分隔符
    input_buffer_.separator_positions.push_back(new_pos);
    undo_model_.SeparatorPressed(new_pos);  // 段模型双写（kSeparator op，回退按位置删除）
    // 锁定左侧面板为第一个分隔符前的数字段，后续按键不刷新左侧候选区
    separator_consumed_digits_ = input_buffer_.digit_sequence.substr(
        0, input_buffer_.separator_position());
    left_column_locked_ = true;
    // 通过 SendToRime 同步到 RIME 引擎，ToRimeInputString 会在所有 separator_positions 插入分隔符
    SendToRime();

    T9LOG(">> HandleSeparatorKey: set separator at pos %d, sepDigits='%s', locked=1, buf='%s'",
          input_buffer_.separator_position(),
          separator_consumed_digits_.value().c_str(),
          input_buffer_.ToBufferString().c_str());
    LogPreeditState();
    return kAccepted;
}

ProcessResult T9Processor::HandleApostropheKey() {
    // 直接 ' 键：与 HandleSeparatorKey 共用纯分隔符逻辑
    T9_SCOPED_TIMER_TAG("T9Processor", "HandleApostropheKey");
    T9_PERF_SCOPED_TIMER("[T9] HandleApostropheKey");

    if (input_buffer_.is_empty()) {
        // 无输入时 ' 无意义 → 消费按键
        T9LOG(">> HandleApostropheKey: empty, consume");
        return kAccepted;
    }

    // 有输入：与 HandleSeparatorKey 逻辑一致
    int new_pos = static_cast<int>(input_buffer_.digit_sequence.length());
    if (!input_buffer_.separator_positions.empty() &&
        input_buffer_.separator_positions.back() == new_pos) {
        T9LOG(">> HandleApostropheKey: separator already at end (pos=%d), consume", new_pos);
        return kAccepted;
    }

    input_buffer_.separator_positions.push_back(new_pos);
    undo_model_.SeparatorPressed(new_pos);  // 段模型双写（kSeparator op，回退按位置删除）
    // 锁定左侧面板为第一个分隔符前的数字段
    separator_consumed_digits_ = input_buffer_.digit_sequence.substr(
        0, input_buffer_.separator_position());
    left_column_locked_ = true;
    SendToRime();

    T9LOG(">> HandleApostropheKey: set separator at pos %d, sepDigits='%s', locked=1",
          input_buffer_.separator_position(),
          separator_consumed_digits_.value().c_str());
    LogPreeditState();
    return kAccepted;
}

ProcessResult T9Processor::HandleBackspace() {
    T9_SCOPED_TIMER_TAG("T9Processor", "HandleBackspace");
    T9_PERF_SCOPED_TIMER("[T9] HandleBackspace");

    // ── 段模型回退（两阶段状态机：段撤销优先 + 位置删除）──
    // 段模型为回退唯一真相源：Backspace() 统一处理段撤销（LC/RC）、数字删除、
    // 分词键删除（作为位置元素，位置从后往前，设计文档 §5/§7）。
    // 旧 P0（末尾分隔符清除）移除：段模型位置删除天然覆盖（"5'" 删 '，
    // "5'4" 删 '4' 保留 '，位置逻辑决定）。
    bool handled = undo_model_.Backspace();
    if (handled) {
        // 撤销 commit 操作计数累加，供 Kotlin 同步 t9PartialCommitTexts
        undone_right_commit_count_ += undo_model_.ConsumeUndoneCommitCount();
        input_buffer_ = undo_model_.ToBuffer();
        DeriveStateMachineFromUndoModel();
        // 分隔符删空后解锁左侧面板（分词键锁定状态）
        if (input_buffer_.separator_positions.empty()) {
            left_column_locked_ = false;
            separator_consumed_digits_.reset();
        }
        SendToRime();
    }

    LogPreeditState();
    return handled ? kAccepted : kNoop;
}

// ════════════════════════════════════════
// SelectPinyinDirect — LeftChoice 子流程（设计稿 §5.2）
// ════════════════════════════════════════

void T9Processor::SelectPinyinDirect(const std::string& pinyin, int digit_length) {
    // 英文/词级预测方案（kNone）：左侧无音节候选，左选点击是无效操作。
    // 防御性 no-op——UI 在 kNone 时本就不渲染左栏候选，此处兜底防旧状态误触。
    if (left_panel_mode_ == t9_panel_state::LeftPanelMode::kNone) {
        T9LOG("SelectPinyinDirect: left panel disabled (word-based schema), ignore '%s'",
              pinyin.c_str());
        return;
    }
    if (digit_length <= 0 || pinyin.empty()) {
        T9LOG("SelectPinyinDirect: invalid args pinyin='%s' len=%d",
              pinyin.c_str(), digit_length);
        return;
    }
    SyllableOption option(pinyin, digit_length);

    // SELECTION 态 + 无未分配数字 → 替换
    if (state_machine_.is_selection() && input_buffer_.unassigned().empty()) {
        HandleSelectionReplacementChoice(option);
        return;
    }
    HandleLeftSelectChoice(option);
}

void T9Processor::HandleLeftSelectChoice(const SyllableOption& option) {
    // 对应 Kotlin handleLeftSelectChoice
    if (option.digit_length > static_cast<int>(input_buffer_.unassigned().length())) {
        T9LOG("HandleLeftSelectChoice: digit_length %d > unassigned %zu",
              option.digit_length, input_buffer_.unassigned().length());
        return;
    }

    std::string consumed_digits;
    std::string confirmed_pinyin = input_buffer_.selected_pinyin();

    if (left_column_locked_) {
        last_choice_consumed_digits_ = separator_consumed_digits_;
        separator_consumed_digits_.reset();
        if (!input_buffer_.selections.empty()) {
            // 分词键确认拼音后替换
            input_buffer_ = input_buffer_.ReplaceLastSelection(option.pinyin, option.digit_length);
            undo_model_.ReplaceLastSelection(option);  // 段模型双写：替换最后段
        } else {
            // 分词键未确认拼音后首次选字
            // 使用 AddSelection 正常消费 digit（不剥离 digit_seq），保证 backspace undo 可恢复完整序列
            input_buffer_ = input_buffer_.AddSelection(option.pinyin, option.digit_length);
            undo_model_.LeftChoice(option);  // 段模型双写：新增段
        }
        left_column_locked_ = false;
        // digit_seq 保持完整（不再剥离），consumed_count 由 AddSelection 正确追踪
        EnterSelection(option, last_choice_consumed_digits_.value_or(""), "");
    } else {
        // 从 INPUT 态首次选字
        consumed_digits = input_buffer_.unassigned().substr(0, option.digit_length);
        last_choice_consumed_digits_ = consumed_digits;
        separator_consumed_digits_.reset();
        input_buffer_ = input_buffer_.AddSelection(option.pinyin, option.digit_length);
        undo_model_.LeftChoice(option);  // 段模型双写：新增段
        EnterSelection(option, consumed_digits, confirmed_pinyin);
    }

    left_column_locked_ = false;
    input_buffer_.separator_positions.clear();  // 左选后清除分隔符
    last_rime_input_.clear();
    SendToRime();
    T9LOG("HandleLeftSelectChoice: '%s'(%d), buf='%s'",
          option.pinyin.c_str(), option.digit_length,
          input_buffer_.ToBufferString().c_str());
}

void T9Processor::HandleSelectionReplacementChoice(const SyllableOption& option) {
    // 对应 Kotlin handleSelectionReplacementChoice
    if (!state_machine_.is_selection() || !state_machine_.selected_option().has_value()) {
        return;
    }
    std::string candidate_digits = state_machine_.selection_candidate_digits().value_or("");
    if (option.digit_length > static_cast<int>(candidate_digits.length())) {
        T9LOG("HandleSelectionReplacement: digit_length %d > candidate %zu",
              option.digit_length, candidate_digits.length());
        return;
    }

    auto prev_opt = state_machine_.selected_option();

    std::string new_consumed = candidate_digits.substr(0, option.digit_length);
    std::string remaining = candidate_digits.substr(option.digit_length);
    std::string confirmed_prefix = input_buffer_.selected_pinyin();
    if (prev_opt.has_value()) {
        size_t plen = prev_opt->pinyin.length();
        if (confirmed_prefix.length() >= plen) {
            confirmed_prefix = confirmed_prefix.substr(0, confirmed_prefix.length() - plen);
        }
    }

    if (!input_buffer_.selections.empty()) {
        input_buffer_ = input_buffer_.ReplaceLastSelection(option.pinyin, option.digit_length);
    } else {
        input_buffer_ = input_buffer_.AddSelection(option.pinyin, option.digit_length);
    }
    if (!state_machine_.selection_history().empty()) {
        state_machine_.RemoveLastSelectionHistoryEntry();
    }
    EnterSelection(option, new_consumed, confirmed_prefix);

    undo_model_.ReplaceLastSelection(option);  // 段模型双写：替换最后段
    if (!remaining.empty()) {
        last_choice_consumed_digits_ = new_consumed;
    }
    input_buffer_.separator_positions.clear();  // 替换选择后清除分隔符
    last_rime_input_.clear();
    SendToRime();
    T9LOG("HandleSelectionReplacement: '%s'(%d), buf='%s'",
          option.pinyin.c_str(), option.digit_length,
          input_buffer_.ToBufferString().c_str());
}

// ════════════════════════════════════════
// SelectCandidate — 右侧候选选词（委托给 T9RightCommitHandler）
// ════════════════════════════════════════

bool T9Processor::SelectCandidate(const std::string& candidate_pinyin,
                                   int candidate_text_length) {
    // ── 诊断日志：入口状态 ──
    T9_SCOPED_TIMER_TAG("T9Processor", "SelectCandidate");
    T9_PERF_SCOPED_TIMER("[T9] SelectCandidate");
    T9LOG(">> SelectCandidate ENTRY: pinyin='%s', textLen=%d",
          candidate_pinyin.c_str(), candidate_text_length);
    T9LOG(">>   buf: digitSeq='%s', selCount=%zu, consumedCount=%d, unassigned='%s'",
          input_buffer_.digit_sequence.c_str(),
          input_buffer_.selections.size(),
          input_buffer_.consumed_count,
          input_buffer_.unassigned().c_str());
    T9LOG(">>   selPinyin='%s', state=%d, leftLocked=%d",
          input_buffer_.selected_pinyin().c_str(),
          static_cast<int>(state_machine_.state()),
          left_column_locked_ ? 1 : 0);
    if (!input_buffer_.selections.empty()) {
        for (size_t i = 0; i < input_buffer_.selections.size(); ++i) {
            T9LOG(">>   sel[%zu]: '%s'(%d)",
                  i, input_buffer_.selections[i].pinyin.c_str(),
                  input_buffer_.selections[i].digit_length);
        }
    }
    T9LOG(">>   sepConsumed='%s', lastChoiceConsumed='%s'",
          separator_consumed_digits_.has_value() ? separator_consumed_digits_->c_str() : "(null)",
          last_choice_consumed_digits_.has_value() ? last_choice_consumed_digits_->c_str() : "(null)");

    if (input_buffer_.is_empty()) {
        T9LOG(">> SelectCandidate: buffer empty → return true");
        return true;
    }

    // 全简拼无候选注释 → enterLike 提交
    if (candidate_pinyin.empty() &&
        !state_machine_.selection_history().empty() &&
        std::all_of(state_machine_.selection_history().begin(),
                     state_machine_.selection_history().end(),
                     [](const SyllableOption& o) { return o.digit_length == 1; })) {
        T9LOG(">> SelectCandidate: all-abbrev no comment → EnterIdle");
        EnterIdle();
        return true;
    }

    T9RightCommitHandler::Context ctx;
    BuildHandlerContext(ctx);

    // 方案 A：优先用 RIME 候选 end 换算的消费位数（精确反映 schema 派生编码
    // 的匹配范围），无法确定时用 -1 fallback 到现有 AlignWithBuffer 算法。
    int rime_consumed = QueryRimeConsumedDigits(candidate_pinyin);
    T9LOG(">> SelectCandidate: rimeConsumedDigits=%d", rime_consumed);

    bool full_commit = right_commit_handler_.HandleRightCommit(
        ctx, candidate_pinyin, candidate_text_length, rime_consumed);

    // 必须先把 handler 消费结果回写 input_buffer_（consumed/unassigned 更新）。
    ApplyHandlerContext(ctx);

    // 调频不在此进行：由 Kotlin 在 full commit 上屏后经 MemorizeEntry 显式调用。

    // ── 诊断日志：出口状态 ──
    T9LOG(">> SelectCandidate EXIT: full_commit=%d", full_commit ? 1 : 0);
    T9LOG(">>   newBuf: digitSeq='%s', selCount=%zu, consumedCount=%d, unassigned='%s'",
          input_buffer_.digit_sequence.c_str(),
          input_buffer_.selections.size(),
          input_buffer_.consumed_count,
          input_buffer_.unassigned().c_str());
    T9LOG(">>   newBuf.selPinyin='%s', toBufferString='%s', state=%d",
          input_buffer_.selected_pinyin().c_str(),
          input_buffer_.ToBufferString().c_str(),
          static_cast<int>(state_machine_.state()));

    return full_commit;
}

// ════════════════════════════════════════
// MemorizeEntry / ForgetEntry — 用户词典调频（Kotlin 上屏路径为唯一真相源）
// ════════════════════════════════════════
// pinyin 拆音节转原生 Code（key 由 RIME 生成，避免手拼字符串依赖内部格式）；
// 撤销段时 Kotlin 调 ForgetEntry（commits=-1）回滚。

bool T9Processor::BuildEntryForPinyin(const std::string& text,
                                      const std::string& pinyin,
                                      DictEntry* entry) {
    if (!entry || text.empty() || pinyin.empty())
        return false;
    // 惰性构建 音节→SyllableId 映射（与 UserDictionary 的 RecruitEntry 一致：
    // GetSyllabary 返回按 id 排序的音节集合，遍历顺序即 id）。
    if (syllabary_map_.empty() && dict_ && dict_->primary_table()) {
        Syllabary syllabary;
        if (dict_->primary_table()->GetSyllabary(&syllabary)) {
            SyllableId sid = 0;
            for (const auto& s : syllabary)
                syllabary_map_[s] = sid++;
        }
    }
    if (syllabary_map_.empty())
        return false;
    auto syllables = ParseSyllables(pinyin);
    if (syllables.empty())
        return false;
    Code code;
    for (const auto& syl : syllables) {
        auto it = syllabary_map_.find(syl);
        if (it == syllabary_map_.end())
            return false;  // 音节不在词典（如英文/符号），放弃调频
        code.push_back(it->second);
    }
    entry->text = text;
    entry->code = code;
    return true;
}

bool T9Processor::UpdateDictEntry(const std::string& text,
                                  const std::string& pinyin,
                                  int commits) {
    if (!user_dict_ || !user_dict_->loaded() || user_dict_->readonly())
        return false;
    if (text.empty() || pinyin.empty())
        return false;
    DictEntry entry;
    if (!BuildEntryForPinyin(text, pinyin, &entry))
        return false;
    // 刷新 tick：与主翻译器的 UserDictionary 共享 db（db_pool_），
    // 但各实例独立缓存 tick_，写前同步防 tick 倒退（UpdateEntry 依赖 tick 计算权重）。
    user_dict_->Load();
    const char* op = commits > 0 ? "Memorize" : "Forget";
    if (user_dict_->UpdateEntry(entry, commits)) {
        T9LOG(">> %sEntry OK: '%s' pinyin='%s'", op, text.c_str(), pinyin.c_str());
        return true;
    }
    T9LOG(">> %sEntry FAIL: '%s' pinyin='%s'", op, text.c_str(), pinyin.c_str());
    return false;
}

bool T9Processor::MemorizeEntry(const std::string& text,
                                const std::string& pinyin) {
    return UpdateDictEntry(text, pinyin, 1);
}

bool T9Processor::ForgetEntry(const std::string& text,
                              const std::string& pinyin) {
    return UpdateDictEntry(text, pinyin, -1);
}

int T9Processor::QueryRimeConsumedDigits(
    const std::optional<std::string>& candidate_pinyin) const {
    // 方案 A：右选消费优先采用 RIME 候选的实际匹配范围。
    // RIME 已通过 schema 的 speller/algebra（含 derive/abbrev 派生规则）
    // 精确计算出候选在输入中的匹配结束位置（Candidate::end()），
    // 其坐标基于 RIME 引擎当前 input 字符串（可能含分隔符）。
    // 换算规则：消费数字位数 = input[0:end) 中数字字符的个数。
    if (!candidate_pinyin.has_value() || candidate_pinyin->empty()) return -1;
    auto* ctx = engine_->context();
    if (!ctx) return -1;
    const std::string& rime_input = ctx->input();
    const auto& comp = ctx->composition();
    for (const auto& seg : comp) {
        if (!seg.menu) continue;
        // 只遍历已生成的候选（candidate_count()），不调用 Prepare(n) 强制扩展——
        // Prepare 会逐候选触发 Translation::Next() 翻译，右选时若强制 64 个候选
        // 将引入毫秒级延迟。被右选候选必在当前显示页（getComposition 已生成），
        // GetCandidateAt(i) 在 i < candidate_count() 时不会触发 Prepare。
        size_t n = seg.menu->candidate_count();
        for (size_t i = 0; i < n; ++i) {
            auto cand = seg.menu->GetCandidateAt(i);
            auto genuine = Candidate::GetGenuineCandidate(cand);
            if (!genuine) continue;
            // T9PreeditCandidate 包装的 comment() 委托给内部候选，
            // 与 Kotlin 传入的 candidate_pinyin（spelling_hints 拼音）一致。
            if (genuine->comment() != *candidate_pinyin) continue;
            size_t end = genuine->end();
            int digits = 0;
            size_t limit = std::min(end, rime_input.size());
            for (size_t k = 0; k < limit; ++k) {
                if (rime_input[k] >= '0' && rime_input[k] <= '9') ++digits;
            }
            T9LOG(">> QueryRimeConsumedDigits: pinyin='%s' end=%zu inputLen=%zu -> digits=%d",
                  candidate_pinyin->c_str(), end, rime_input.size(), digits);
            return digits;
        }
    }
    return -1;
}

void T9Processor::BuildHandlerContext(T9RightCommitHandler::Context& out) {
    // 拷贝当前状态到 handler context
    out.input_buffer = input_buffer_;
    out.state_machine = state_machine_;
    out.undo_model = &undo_model_;  // 段模型同步（SyncRightCommit 差异推导）
    out.left_column_locked = left_column_locked_;
    out.separator_consumed_digits = separator_consumed_digits_;
    out.last_choice_consumed_digits = last_choice_consumed_digits_;
    out.manual_delimiter = manual_delimiter_;

    // 回调注入
    out.sync_state = [] {};  // T9Processor 直接持有 state_machine_，无需同步
    out.update_candidates = [](bool) {};  // Kotlin 端会从 RIME composition 读取
    // 对应 Kotlin rimeBridge.setLastRimeInput(it)：仅更新 last_rime_input_ 缓存，
    // 不直接修改 RIME 引擎的 input。RIME input 的真正同步留给 forceSendToRime
    // （即 ReplaceFullPinyin），由服务层在 partial commit 后调用。
    // 若在此处调 SyncRimeInput 修改 RIME input，会破坏后续 rimeEngine.selectCandidate
    // 的调用环境（RIME 候选词列表已变更），导致双重消费/异常上屏。
    out.set_rime_input = [this](const std::optional<std::string>& input) {
        if (input.has_value()) {
            last_rime_input_ = *input;
        } else {
            last_rime_input_.clear();
        }
    };
}

void T9Processor::ApplyHandlerContext(const T9RightCommitHandler::Context& ctx) {
    input_buffer_ = ctx.input_buffer;
    input_buffer_.separator_positions.clear();  // 右选后清除分隔符
    state_machine_ = ctx.state_machine;
    left_column_locked_ = ctx.left_column_locked;
    separator_consumed_digits_ = ctx.separator_consumed_digits;
    last_choice_consumed_digits_ = ctx.last_choice_consumed_digits;
}

// ════════════════════════════════════════
// InferFirstSyllableFromRime — 从 RIME 候选 comment 推断首音节
// ════════════════════════════════════════

std::optional<SyllableOption> T9Processor::InferFirstSyllableFromRime(const std::string& digits) {
    // 对应 Kotlin T9RimeBridge.inferFirstSyllableFromRime
    T9_SCOPED_TIMER_TAG("T9Processor", "InferFirstSyllableFromRime");
    T9_PERF_SCOPED_TIMER("[T9] InferFirstSyllableFromRime");
    Context* ctx = engine_->context();
    if (ctx && ctx->HasMenu()) {
        Menu* menu = ctx->composition().back().menu.get();
        if (menu) {
            size_t prepare_count = menu->Prepare(10);
            for (size_t i = 0; i < prepare_count; ++i) {
                an<Candidate> cand = menu->GetCandidateAt(i);
                if (!cand) continue;
                string comment = Candidate::GetGenuineCandidate(cand)->comment();
                if (comment.empty()) continue;
                // 取首音节
                size_t space_pos = comment.find(' ');
                string first_pinyin = (space_pos != string::npos)
                    ? comment.substr(0, space_pos) : comment;
                if (first_pinyin.empty()) continue;
                auto code = T9PinyinMap::Instance().PinyinToDigitCode(first_pinyin);
                if (code.has_value() &&
                    digits.size() >= code->size() &&
                    digits.compare(0, code->size(), *code) == 0) {
                    return SyllableOption(first_pinyin,
                                           static_cast<int>(code->size()));
                }
            }
        }
    }
    // 回退：本地贪婪最长匹配
    auto options = T9PinyinMap::Instance().FirstSyllableOptions(digits, 1);
    if (options.empty()) return std::nullopt;
    return options.front();
}

// ════════════════════════════════════════
// LogPreeditState — 预编辑状态日志（性能埋点）
// ════════════════════════════════════════

void T9Processor::LogPreeditState() {
    // 异步 flush 模型下，此处引擎 composition 为上次 FlushRimeInput 后的状态，
    // 读取/Prepare 候选意义有限且会引入额外翻译开销（menu->Prepare），
    // 因此仅记录本地 buffer 状态。候选区实际由 JNI 层在 flush 后统一拉取。
    T9_PERFLOG("[T9_PREEDIT] input='%s' buf='%s'",
               input_buffer_.ToBufferString(manual_delimiter_).c_str(),
               input_buffer_.digit_sequence.c_str());
}

// ════════════════════════════════════════
// SendToRime / SyncRimeInput — RIME 交互
// ════════════════════════════════════════

void T9Processor::SendToRime() {
    // 对应 Kotlin sendToRime。
    //
    // 异步 flush 模型（对标 Kotlin 版异步投递）：
    //   本方法只计算"待发送内容"并标记 pending_action_ / pending_input_，
    //   不再直接调用引擎（set_input / Clear 等延迟到 FlushRimeInput 执行，
    //   由应用层在 processKey 之后的后台线程触发）。
    //   埋点范围因此不含引擎 compose 耗时，与 Kotlin 版 t9_send_to_rime 口径一致。
    T9_SCOPED_TIMER_TAG("T9Processor", "SendToRime");
    T9_PERF_SCOPED_TIMER("[T9] SendToRime");
    if (input_buffer_.is_empty()) {
        // 空 buffer：统一 kClear（清 RIME input + composition）。
        // 修复（2026-08-05，设备实证"几4"）：旧逻辑在 pending commit（待撤销右选，
        // 如右选"几"的 TailConsume）时只清 composition 不清 input，回退删空数字后
        // RIME input 残留（partial 文本"几" + 残留 input '4' = "几4"）。
        // pending commit 的 input 恢复由 undo 时的 SendToRime 重新设置，无需保留旧 input。
        last_rime_input_.clear();
        pending_input_.clear();
        pending_action_ = RimePendingAction::kClear;
        return;
    }
    // 僵尸 RC 状态：consumed > 0 且无 unassigned 且无 selections 且有 pending RightCommit。
    // 所有未分配数字已删完，consumed 部分仍存在（来自 RightCommit）。
    // 不向 RIME 发送 digit_sequence，否则 RIME 会生成 preedit（如 "ce"），
    // 导致预编辑文本错误拼接（partialCommit="策" + rimePreedit="ce" = "策ce"）。
    // 正确做法：清 RIME composition 和 input，仅由 partial commit 文本驱动预编辑显示。
    // pending commit 判定用 undo_model_（回退真相源，对应原命令模式 HasPendingRightCommit）；
    // buffer 侧保持 IsZombieRCBufferState。
    if (input_buffer_.IsZombieRCBufferState() && undo_model_.HasPendingCommit()) {
        last_rime_input_.clear();
        pending_input_.clear();
        pending_action_ = RimePendingAction::kZombieClear;
        T9LOG("SendToRime: zombie RC, pending clear (digitSeq='%s')",
              input_buffer_.digit_sequence.c_str());
        return;
    }
    // 关键：RIME t9_pinyin 方案的 spelling_hints 基于数字码生成 comment。
    // 若发纯拼音（如 "gua"），RIME 无法生成 comment，导致右侧候选词
    // 无拼音注释 → C++ SelectCandidate 收到空 pinyin → 误判 full commit。
    //
    // 策略：
    //   - 用 ToPreeditString()（拼音格式）与 last_rime_input_ 比较，
    //     判断 RIME input 是否需要变化。拼音格式对 left choice 敏感
    //     （如 "b" vs "c"），避免 digit_sequence 相同时跳过更新。
    //   - 用 ToRimeInputString()（数字码格式）实际设置 RIME input，
    //     确保 spelling_hints 生成 comment。

    // ═══ 快速路径：无 selections + 无 consumed 时直接用 digit_sequence ═══
    // 跳过 ToPreeditString/ToRimeInputString 的完整字符串构建（含多次 string 拷贝），
    // 直接使用最原始的 digit_sequence。这是用户输入过程中最高频的路径。
    // 修复（2026-08-06）：分隔符越界位置钳制到末尾（与 ToBuffer 一致），
    // 消除"分隔符存在但越界 → 退化发送无分隔符序列"的边界分支。
    if (input_buffer_.selections.empty() && input_buffer_.consumed_count == 0) {
        std::string raw_input = input_buffer_.digit_sequence;
        if (!input_buffer_.separator_positions.empty()) {
            int len = static_cast<int>(raw_input.size());
            // 从后往前插入，避免位置偏移
            for (auto it = input_buffer_.separator_positions.rbegin();
                 it != input_buffer_.separator_positions.rend(); ++it) {
                int pos = std::min(*it, len);  // 越界钳制到末尾
                raw_input.insert(raw_input.begin() + pos, manual_delimiter_);
            }
        }
        if (raw_input == last_rime_input_) return;
        last_rime_input_ = raw_input;
        T9LOG("SendToRime (fast-path): '%s'", raw_input.c_str());
        pending_input_ = std::move(raw_input);
        pending_action_ = RimePendingAction::kSetInput;
        return;
    }

    std::string preedit = input_buffer_.ToPreeditString(manual_delimiter_);
    std::string rime_input = input_buffer_.ToRimeInputString(manual_delimiter_);

    // 分隔符已由 ToRimeInputString/ToPreeditString 通过 separator_positions 处理，
    // 不再需要在此处追加。

    if (preedit == last_rime_input_) return;
    last_rime_input_ = preedit;
    T9LOG("SendToRime: '%s' (preedit='%s', digitSeq='%s')",
          rime_input.c_str(), preedit.c_str(),
          input_buffer_.digit_sequence.c_str());
    pending_input_ = std::move(rime_input);
    pending_action_ = RimePendingAction::kSetInput;
}

void T9Processor::FlushRimeInput() {
    // 异步 flush：执行 SendToRime 标记的待发送动作，真正触发引擎 compose。
    // 由应用层在 processKey 之后的后台线程调用（持有 RimeEngine.rimeLock）。
    // 埋点体现引擎调用本身耗时（含 compose_total），供对比观察。
    T9_SCOPED_TIMER_TAG("T9Processor", "FlushRimeInput");
    T9_PERF_SCOPED_TIMER("[T9] FlushRimeInput");
    switch (pending_action_) {
        case RimePendingAction::kNone:
            return;
        case RimePendingAction::kSetInput:
            engine_->context()->set_input(pending_input_);
            break;
        case RimePendingAction::kClear:
            engine_->context()->Clear();
            break;
        case RimePendingAction::kZombieClear:
            engine_->context()->ClearNonConfirmedComposition();
            engine_->context()->set_input("");
            break;
    }
    pending_action_ = RimePendingAction::kNone;
    pending_input_.clear();
}

void T9Processor::SyncRimeInput(const std::optional<std::string>& input) {
    // 对应 Kotlin setRimeInput 回调
    if (input.has_value()) {
        last_rime_input_ = *input;
        if (!input->empty()) {
            engine_->context()->set_input(*input);
        }
    } else {
        last_rime_input_.clear();
    }
}

// ════════════════════════════════════════
// 状态转换
// ════════════════════════════════════════

void T9Processor::EnterIdle() {
    // 对应 Kotlin enterIdle
    // 必须在 state_machine_ 之前清空 input_buffer_，否则 state sync 后
    // stale buffer 会导致后续 backspace 等操作在残留数据上处理。
    input_buffer_ = T9Buffer();
    state_machine_.EnterIdle();
    left_column_locked_ = false;
    separator_consumed_digits_.reset();   // 修复（2026-08-06）：外部清空触发的
    last_choice_consumed_digits_.reset(); // EnterIdle 也重置分词键/左选临时状态
    // 段模型为回退唯一真相源：清空全部段状态（2026-08-06 起命令模式已移除，无残留栈）
    undo_model_.Clear();
}

void T9Processor::EnterSelection(const SyllableOption& option,
                                  const std::string& candidate_digits,
                                  const std::string& confirmed_pinyin) {
    state_machine_.EnterSelection(option, candidate_digits, confirmed_pinyin);
}

void T9Processor::DeriveStateMachineFromUndoModel() {
    // 段模型回退后派生 state_machine_（设计文档 §6 左侧候选规则）：
    //   SELECTION：存在 selected 段 → 高亮最后 selected 段（优先于 INPUT，
    //     实测确认：左选段存在时左侧候选显示最后选中段的候选）
    //   INPUT：无 selected 但存在 unassigned 段/tail → 显示其候选
    //   IDLE：全部 committed 或已删 → 空闲态（产品决策，与搜狗对齐）
    if (undo_model_.IsEmpty()) {
        state_machine_.EnterIdle();
        left_column_locked_ = false;
        separator_consumed_digits_.reset();
        last_choice_consumed_digits_.reset();
        return;
    }
    if (undo_model_.HasSelectedSegment()) {
        // 重建完整 selection_history（命令模式右选消费依赖它，如 letterBuffer 策略的
        // consumedPinyin 计算）。旧实现只 EnterSelection(最后段)：backspace（undo 右选）后
        // history 残留旧值（如场景32 undo 九宫格 后 history=[t,b,b]），再次右选"九宫格"
        // 时命令模式基于错误 history 走"首字母仅匹配"路径 → 消费 4 段（t 段被误消费，
        // 预编辑"九宫格a"）。修复：ClearSelectionHistory + 逐段 EnterSelection 重建
        // [j,g,g,t,b]（设备实证 2026-08-06）。
        const auto& segs = undo_model_.segments();
        state_machine_.ClearSelectionHistory();
        for (const auto& seg : segs) {
            if (seg.phase == T9Segment::kSelected) {
                state_machine_.EnterSelection(seg.option, seg.digits, "");
            }
        }
        return;
    }
    if (undo_model_.HasSelectableDigits()) {
        // 无 selected 段 → 进入 INPUT 态，必须清空 selection_history。
        // 修复（2026-08-11，设备实证）：退格撤销 LC 后若残留历史（如 [tiao]），
        // 再次左选同一拼音会累积重复条目 [tiao, tiao] → HSLBC 的
        // is_full_commit_without_boundaries（JoinPinyins(history)==selected_pinyin）
        // 判定失败 → 右选错误 partial commit（预编辑"洮条tiao"）。
        // 段模型是唯一真相源：无 selected 段时 history 必须为空。
        state_machine_.ClearSelectionHistory();
        state_machine_.EnterInput();
        return;
    }
    state_machine_.EnterIdle();
}

// ════════════════════════════════════════
// GetRemainingDigits / GetFirstSyllableOptions — 委托 t9_panel_state
// ════════════════════════════════════════

std::string T9Processor::GetRemainingDigits() const {
    return t9_panel_state::GetRemainingDigits(input_buffer_);
}

void T9Processor::GetFirstSyllableOptions(const std::string& digits, int max_results,
                                           std::vector<std::string>& out) const {
    // 英文/词级预测方案（kNone）：不生成拼音音节候选（Kotlin firstOptions 为空）。
    if (left_panel_mode_ == t9_panel_state::LeftPanelMode::kNone) {
        out.clear();
        return;
    }
    t9_panel_state::GetFirstSyllableOptions(digits, max_results, out);
}

// ════════════════════════════════════════
// GetAndConsumeUndoneRightCommitCount — 委托 t9_panel_state
// ════════════════════════════════════════

int T9Processor::GetAndConsumeUndoneRightCommitCount() {
    return t9_panel_state::ConsumeUndoneRightCommitCount(undone_right_commit_count_);
}

// ════════════════════════════════════════
// GetLeftPanelState — 委托 t9_panel_state
// ════════════════════════════════════════

void T9Processor::GetLeftPanelState(LeftPanelStateData& out) const {
    // 英文/词级预测方案（kNone）：左栏直接空闲（Kotlin 落到 "，。？！" 空闲态），
    // 不展示拼音音节候选。
    if (left_panel_mode_ == t9_panel_state::LeftPanelMode::kNone) {
        out = LeftPanelStateData();  // 默认构造：kIdle + 全空
        return;
    }
    T9PanelStateContext ctx(input_buffer_, state_machine_,
                            left_column_locked_, separator_consumed_digits_);
    t9_panel_state::GetLeftPanelState(ctx, out);
}

std::string T9Processor::GetLeftPanelState() const {
    if (left_panel_mode_ == t9_panel_state::LeftPanelMode::kNone) {
        return "IDLE;;;;;0";
    }
    T9PanelStateContext ctx(input_buffer_, state_machine_,
                            left_column_locked_, separator_consumed_digits_);
    return t9_panel_state::GetLeftPanelStateString(ctx);
}

// ════════════════════════════════════════
// T8: ReplaceFullPinyin / ClearComposition
// ════════════════════════════════════════

void T9Processor::ReplaceFullPinyin(const std::string& pinyin) {
    // 对应 Kotlin onT9ReplaceFullPinyin 回调
    // 调用方在 XimeInputMethodService 中根据 pinyin 值判断：
    //   pinyin == CLEAR_COMPOSITION_ONLY → ClearComposition(0)
    //   pinyin == CLEAR_ALL → ClearComposition(1)
    //   pinyin 为空 → ClearComposition(0)
    //   其他 → 直接设置 RIME input
    if (pinyin.empty()) {
        ClearComposition(0);
        return;
    }
    // 清除 RIME context 的残留 composition 状态（selectAndCommit 后
    // composition 处于 [confirmed, phony] 态。直接 set_input 触发 Reset()
    // 时 diff_pos 逻辑无法正确处理残留 segments 与新 input 的差异，
    // 导致 BuildSyllableGraph → CommonPrefixSearch 找不到候选项）。
    engine_->context()->Clear();
    SyncRimeInput(pinyin);
    T9LOG("ReplaceFullPinyin: '%s'", pinyin.c_str());
}

void T9Processor::ClearComposition(int mode) {
    // mode=0: CLEAR_COMPOSITION_ONLY — 仅清 RIME composition，保留 local state
    // mode=1: CLEAR_ALL — 清 composition + 重置 local state
    Context* ctx = engine_->context();
    if (mode == 0) {
        // CLEAR_COMPOSITION_ONLY
        ctx->ClearNonConfirmedComposition();
        last_rime_input_.clear();
        // 外部清理优先：作废未 flush 的 pending 内容
        pending_action_ = RimePendingAction::kNone;
        pending_input_.clear();
        T9LOG("ClearComposition: CLEAR_COMPOSITION_ONLY");
    } else {
        // CLEAR_ALL
        EnterIdle();
        input_buffer_ = T9Buffer();
        undo_model_.Clear();  // 段模型同步：清空全部段状态
        separator_consumed_digits_.reset();
        last_choice_consumed_digits_.reset();
        last_rime_input_.clear();
        pending_action_ = RimePendingAction::kNone;
        pending_input_.clear();
        ctx->Clear();
        T9LOG("ClearComposition: CLEAR_ALL");
    }
}

// ════════════════════════════════════════
// 辅助
// ════════════════════════════════════════

}  // namespace rime

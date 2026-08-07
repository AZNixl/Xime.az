#ifndef T9_PROCESSOR_H_
#define T9_PROCESSOR_H_

#include <rime/processor.h>
#include <rime/component.h>
#include <optional>
#include <string>
#include <vector>

#include "t9_buffer.h"
#include "t9_state_machine.h"
#include "t9_undo_model.h"
#include "t9_right_commit_handler.h"
#include "t9_pinyin_map.h"
#include "t9_panel_state.h"  // LeftPanelStateData, T9PanelStateContext, t9_panel_state::*

namespace rime {

// 九键拼音输入处理器（RIME Processor 组件）。
//
// 整合 T1-T6 组件，内聚全部状态与算法（设计稿 §13.1 模块分工）。
// 对应 Kotlin main 分支 T9InputController + T9RimeBridge 的全部逻辑。
//
// 设计决策（规划文档 §3.4 D4）：
//   - 单一 Processor 持有全部状态，避免跨类传递 Context
//   - 通过 RIME engine_->context() 直接读写 RIME 状态
//   - JNI 全局指针 g_active_t9_processor 暴露给 Kotlin 薄包装层
//
// 状态管理：
//   - input_buffer_：结构化输入模型（T9Buffer）
//   - state_machine_：左侧候选区三态状态机
//   - undo_model_：段模型（回退唯一真相源，两阶段状态机；2026-08-06 起命令模式已完全移除）
//   - right_commit_handler_：右侧选词三层消费算法
//   - left_column_locked_ / separator_consumed_digits_ / last_choice_consumed_digits_：
//     分词键与左选交互的临时状态
//   - last_rime_input_：发送去重缓存
class T9Processor : public Processor {
public:
    T9Processor(const Ticket& ticket);
    ~T9Processor() override;

    // ── RIME Processor 接口 ──
    ProcessResult ProcessKeyEvent(const KeyEvent& key_event) override;

    // ── JNI 暴露接口（供 T9InputController 薄包装层调用）──

    // 直接选择拼音（对应 Kotlin handleLeftSelectChoice / handleSelectionReplacement）
    // 内部实现 LeftChoice 双写（设计稿 §5.2）
    void SelectPinyinDirect(const std::string& pinyin, int digit_length);

    // 右侧候选选词，返回 true = 完整消费（full commit）
    // 委托给 T9RightCommitHandler 三层消费算法
    // 注：传入候选拼音注释（comment）和候选词字数，而非索引；
    //     RIME Menu::GetCandidateAt 使用绝对索引，而 Kotlin 层持有的是当前页
    //     相对索引，直接传拼音可避免翻页后索引错位导致消费计算错误。
    bool SelectCandidate(const std::string& candidate_pinyin, int candidate_text_length);

    // 获取 partial commit 后剩余的数字串
    std::string GetRemainingDigits() const;

    // 获取首音节候选列表（P3 方案 A：替代 Kotlin T9PinyinMap.firstSyllableOptions）
    // 委托给 T9PinyinMap::Instance().FirstSyllableOptions()
    // out 每项格式 "pinyin|digitLength"，供 JNI 序列化传输
    void GetFirstSyllableOptions(const std::string& digits, int max_results,
                                  std::vector<std::string>& out) const;

    // 获取并消费 RightCommit 撤销计数（Kotlin 调用以同步 t9PartialCommitTexts）
    int GetAndConsumeUndoneRightCommitCount();

    // 返回格式：
    // STATE;SELECTED_PINYIN;SELECTED_DIGIT_LENGTH;SELECTION_CANDIDATE_DIGITS;PANEL_DIGITS
    // 当 unassigned 非空时，SELECTED_PINYIN/SELECTED_DIGIT_LENGTH 置空（Kotlin 无需二次判断）
    // PANEL_DIGITS 已内含分词键锁定 + unassigned + selectionCandidateDigits + separatorConsumedDigits 回退逻辑
    //
    // P4/P5（2026-07-19）：保留用于向后兼容/调试日志，
    // 新 JNI 调用应使用 GetLeftPanelState(LeftPanelStateData&) 结构化重载。
    std::string GetLeftPanelState() const;

    // P4/P5：结构化状态查询，消除字符串序列化/解析。
    // 语义与 GetLeftPanelState() 完全一致，仅输出形式不同。
    void GetLeftPanelState(LeftPanelStateData& out) const;

    // ── T8: ReplaceFullPinyin / ClearComposition ──
    // 批量替换 RIME 输入为完整拼音（对应 Kotlin onT9ReplaceFullPinyin）
    void ReplaceFullPinyin(const std::string& pinyin);

    // 两种清理模式：mode=0 (CLEAR_COMPOSITION_ONLY), mode=1 (CLEAR_ALL)
    void ClearComposition(int mode);

    // ── 异步 flush（JNI 调用）──
    // 执行 SendToRime 标记的待发送引擎动作（set_input → compose / clear 等）。
    // 由应用层在 processKey 之后的后台线程调用，避免引擎 compose 阻塞 UI 线程。
    void FlushRimeInput();

private:
    // ── 按键处理子流程 ──
    ProcessResult HandleDigitKey(char ch);
    ProcessResult HandleSeparatorKey();      // 分词键 1
    ProcessResult HandleApostropheKey();     // 分词键 '
    // 回退：段模型两阶段状态机（T9UndoModel::Backspace，2026-08-06 起命令模式已移除）
    ProcessResult HandleBackspace();

    // ── LeftChoice 子流程（设计稿 §5.2）──
    void HandleLeftSelectChoice(const SyllableOption& option);
    void HandleSelectionReplacementChoice(const SyllableOption& option);

    // ── RIME 交互 ──
    // 异步 flush 模型（对标 Kotlin 版异步 sendToRime）：
    //   SendToRime()    只计算"待发送内容"并标记 pending，不直接调用引擎
    //                    （埋点范围不含引擎 compose，对标 Kotlin t9_send_to_rime）
    //   FlushRimeInput() 真正执行引擎调用（set_input → compose），
    //                    由应用层在 processKey 之后的后台线程调用
    //                    （公开声明见上方 JNI 暴露接口区）
    void SendToRime();
    void SyncRimeInput(const std::optional<std::string>& input);
    std::optional<SyllableOption> InferFirstSyllableFromRime(const std::string& digits);

    // ── 状态转换 ──
    void EnterIdle();
    void EnterSelection(const SyllableOption& option,
                        const std::string& candidate_digits,
                        const std::string& confirmed_pinyin = "");
    // 段模型回退后派生 state_machine_（设计文档 §6）：
    //   INPUT：存在 unassigned 段/tail → EnterInput
    //   SELECTION：存在 selected 段 → EnterSelection(最后 selected 段)
    //   IDLE：全 committed 或已删 → EnterIdle
    void DeriveStateMachineFromUndoModel();

    // ── RightCommit 上下文构建/应用 ──
    void BuildHandlerContext(T9RightCommitHandler::Context& out);
    void ApplyHandlerContext(const T9RightCommitHandler::Context& ctx);

    // ── 辅助 ──
    void LogPreeditState();

    // 方案 A（消费算法优化）：查询 RIME 候选的实际匹配结束位置，
    // 换算为 T9 应消费的数字位数（RIME input[0:end) 中数字字符数，跳过分隔符）。
    // 候选通过 comment（spelling_hints 拼音）匹配。
    // 返回 -1 表示无法确定（fallback 到现有 AlignWithBuffer 消费算法）。
    int QueryRimeConsumedDigits(
        const std::optional<std::string>& candidate_pinyin) const;

    // ════════════════════════════════════════
    // 状态成员（对应 Kotlin T9InputController 的私有字段）
    // ════════════════════════════════════════
    T9Buffer input_buffer_;                              // §2.2 结构化输入模型
    T9StateMachine state_machine_;                       // §3.2 三态状态机
    // 段模型（2026-08-06 起为回退唯一真相源）：输入操作双写
    // （Digit/LeftChoice/Separator/SyncRightCommit），backspace 全部走 T9UndoModel::Backspace。
    T9UndoModel undo_model_;
    T9RightCommitHandler right_commit_handler_;          // §6.2 三层消费算法

    bool left_column_locked_ = false;                    // 分词键锁定标记
    std::optional<std::string> separator_consumed_digits_;   // 分词键确认的数字段
    std::optional<std::string> last_choice_consumed_digits_; // 上次左选消费的数字段
    std::string last_rime_input_;                        // 发送去重缓存
    int undone_right_commit_count_ = 0;                  // RightCommit 撤销计数（供 Kotlin 同步）
    char manual_delimiter_ = '\'';                       // 分隔符字符（从 speller.delimiter 读入）
    // 左侧候选区模式（2026-08-07，英文九键适配）：
    // 构造时按 engine/translators 是否含 script_translator 判定（auto），
    // 可被 t9/left_panel_mode: pinyin|none 显式覆盖。
    // kNone（英文/词级预测方案）：左栏返回 IDLE、首音节候选为空、左选 no-op。
    t9_panel_state::LeftPanelMode left_panel_mode_ =
        t9_panel_state::LeftPanelMode::kPinyin;

    // ── 异步 flush 状态（SendToRime 标记，FlushRimeInput 消费）──
    // pending_action_ / pending_input_ 的读写都发生在 RimeEngine.rimeLock
    // 临界区内（processKey 与 t9FlushRimeInput 均持有该锁），无数据竞争。
    enum class RimePendingAction {
        kNone,             // 无待发送动作
        kSetInput,         // 发送 pending_input_ 到引擎（触发 compose）
        kClear,            // 空 buffer：ctx->Clear()
        kZombieClear,      // 僵尸 RC：清 composition + 置空 input
    };
    RimePendingAction pending_action_ = RimePendingAction::kNone;
    std::string pending_input_;                          // kSetInput 时的参数
};

// 全局活跃 T9Processor 指针（供 JNI 访问）
T9Processor* T9ProcessorRequire();

// P7（2026-07-19）：T9 方案 schema 注入所需的 patch 条目。
//
// C++ 作为组件名的单一真相源：注册名（t9_module.cc）与注入配置名
// 均由此函数返回，Kotlin 端不再硬编码组件名。
// 格式："search_pattern|patch_key|patch_value"
//   - search_pattern: 在 schema YAML 中搜索的文本，判断是否已注入
//   - patch_key: librime patch 语法的 YAML 路径
//   - patch_value: patch 的值
//
// 方案 A: 若 t9 模块未注册（.so 缺失），JNI 层返回空数组，
// Kotlin 端收到空列表后跳过注入，避免写无效配置到 custom.yaml。
std::vector<std::string> GetT9SchemaPatches();

}  // namespace rime

#endif  // T9_PROCESSOR_H_

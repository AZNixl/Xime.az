#include "t9_filter.h"

#include <cctype>
#include <sstream>
#include <vector>

#include "t9_log.h"

#ifndef T9_ALGO_ONLY_BUILD
#include <rime/engine.h>
#include <rime/schema.h>
#include <rime/config.h>
#include <rime/common.h>
#endif

namespace rime {

// ════════════════════════════════════════════════════════════════
// T9 Preedit Converter（无 RIME 依赖，纯字符串算法）
// ════════════════════════════════════════════════════════════════
// 原 t9_preedit_converter.cc 合并至此。Converter 逻辑无任何 RIME 依赖，
// 可在纯算法测试（t9-algo-objs + T9_ALGO_ONLY_BUILD）中独立编译。

// ── UTF-8 辅助 ──

static uint32_t DecodeUtf8(const char*& p, const char* end) {
    if (p >= end) return 0;
    unsigned char c = static_cast<unsigned char>(*p);
    if (c < 0x80) {
        uint32_t cp = c;
        ++p;
        return cp;
    }
    if ((c & 0xE0) == 0xC0) {
        if (p + 1 >= end) { ++p; return 0; }
        uint32_t cp = ((c & 0x1F) << 6) | (static_cast<unsigned char>(p[1]) & 0x3F);
        p += 2;
        return cp;
    }
    if ((c & 0xF0) == 0xE0) {
        if (p + 2 >= end) { ++p; return 0; }
        uint32_t cp = ((c & 0x0F) << 12)
                    | ((static_cast<unsigned char>(p[1]) & 0x3F) << 6)
                    | (static_cast<unsigned char>(p[2]) & 0x3F);
        p += 3;
        return cp;
    }
    if ((c & 0xF8) == 0xF0) {
        if (p + 3 >= end) { ++p; return 0; }
        uint32_t cp = ((c & 0x07) << 18)
                    | ((static_cast<unsigned char>(p[1]) & 0x3F) << 12)
                    | ((static_cast<unsigned char>(p[2]) & 0x3F) << 6)
                    | (static_cast<unsigned char>(p[3]) & 0x3F);
        p += 4;
        return cp;
    }
    ++p;
    return 0;
}

static bool IsChinese(uint32_t cp) {
    return cp >= 0x4E00 && cp <= 0x9FFF;
}

static bool IsDigit(char c) {
    return c >= '0' && c <= '9';
}

// ── 声调归一化 ──
// 带声调的拼音方案（如万象）词库编码使用 Unicode 预组合声调字符
// （如 jī huà），comment 经 spelling_hints 暴露时保留声调。
// 逐字节 ASCII 过滤会丢弃这些多字节字符的每个字节，导致元音丢失
// （jī→j, huà→hu）。此处逐 Unicode 码点解码，将声调元音归一化为
// 普通 ASCII 字母，与方案 speller algebra 的 xlit 语义一致。
// 映射表与万象 wanxiang_t9.schema.yaml 第 139 行 xlit 对齐（大小写声调
// 字符统一映射为小写 ASCII 字母）：
//   āáǎà/ĀǍÁÀ→a   ēéěè/ĒĚÉÈ→e   īíǐì/ĪǏÍÌ→i   ōóǒò/ŌǑÓÒ→o
//   ūúǔù/ŪǓÚÙ→u   ǖǘǚǜü/ǕǗǙǛÜ→v  ńňǹ/ŃŇǸ→n   ḿ/Ḿ→m
// 注 1：万象 xlit 中的 m̀（m+组合附加符号 U+0300）不在此表——组合标记由
//   NormalizePinyinComment 的"ASCII 字母保留 + 未识别多字节丢弃"路径隐式
//   归一化为 m，与 xlit 语义一致（下表 ḿ/Ḿ 为预组合形式）。
// 注 2：未识别的多字节字符（如组合用附加符号 U+0300）一律静默丢弃。
static char ToneToAscii(uint32_t cp) {
    switch (cp) {
        // a: ā ǎ á à  Ā Ǎ Á À (U+0101/U+01CE/U+00E1/U+00E0/U+0100/U+01CD/U+00C1/U+00C0)
        case 0x0101: case 0x01CE: case 0x00E1: case 0x00E0:
        case 0x0100: case 0x01CD: case 0x00C1: case 0x00C0: return 'a';
        // e: ē ě é è  Ē Ě É È (U+0113/U+011B/U+00E9/U+00E8/U+0112/U+011A/U+00C9/U+00C8)
        case 0x0113: case 0x011B: case 0x00E9: case 0x00E8:
        case 0x0112: case 0x011A: case 0x00C9: case 0x00C8: return 'e';
        // i: ī ǐ í ì  Ī Ǐ Í Ì (U+012B/U+01D0/U+00ED/U+00EC/U+012A/U+01CF/U+00CD/U+00CC)
        case 0x012B: case 0x01D0: case 0x00ED: case 0x00EC:
        case 0x012A: case 0x01CF: case 0x00CD: case 0x00CC: return 'i';
        // o: ō ǒ ó ò  Ō Ǒ Ó Ò (U+014D/U+01D2/U+00F3/U+00F2/U+014C/U+01D1/U+00D3/U+00D2)
        case 0x014D: case 0x01D2: case 0x00F3: case 0x00F2:
        case 0x014C: case 0x01D1: case 0x00D3: case 0x00D2: return 'o';
        // u: ū ǔ ú ù  Ū Ǔ Ú Ù (U+016B/U+01D4/U+00FA/U+00F9/U+016A/U+01D3/U+00DA/U+00D9)
        case 0x016B: case 0x01D4: case 0x00FA: case 0x00F9:
        case 0x016A: case 0x01D3: case 0x00DA: case 0x00D9: return 'u';
        // ü/v: ǖ ǘ ǚ ǜ ü  Ǖ Ǘ Ǚ Ǜ Ü (U+01D6/U+01D8/U+01DA/U+01DC/U+00FC/U+01D5/U+01D7/U+01D9/U+01DB/U+00DC)
        case 0x01D6: case 0x01D8: case 0x01DA: case 0x01DC: case 0x00FC:
        case 0x01D5: case 0x01D7: case 0x01D9: case 0x01DB: case 0x00DC: return 'v';
        // n: ń ň ǹ  Ń Ň Ǹ (U+0144/U+0148/U+01F9/U+0143/U+0147/U+01F8)
        case 0x0144: case 0x0148: case 0x01F9:
        case 0x0143: case 0x0147: case 0x01F8: return 'n';
        // m: ḿ Ḿ (U+1E3F/U+1E3E)
        case 0x1E3F: case 0x1E3E: return 'm';
        default: return 0;
    }
}

// 将 comment 中的带声调拼音归一化为纯 ASCII 小写拼音。
// 逐码点解码：声调字符 → 对应 ASCII 字母，ASCII 字母统一转小写，
// 其他字符（分隔符、括号等）丢弃。非 static：供 t9_processor 复用。
std::string NormalizePinyinComment(const std::string& comment) {
    std::string result;
    const char* p = comment.c_str();
    const char* end = p + comment.size();
    while (p < end) {
        const char* prev = p;
        uint32_t cp = DecodeUtf8(p, end);
        if (cp == 0) continue;
        if (cp < 0x80) {
            if ((cp >= 'a' && cp <= 'z') || (cp >= 'A' && cp <= 'Z')) {
                // 统一小写：归一化用于比较与音节选择，须大小写不敏感。
                result += static_cast<char>(std::tolower(static_cast<unsigned char>(cp)));
            }
        } else {
            char mapped = ToneToAscii(cp);
            if (mapped != 0) {
                result += mapped;
            }
            // 未识别的多字节字符（如组合用附加符号 U+0300）静默丢弃
        }
    }
    return result;
}

// ── 输入分段 ──

struct InputPart {
    std::string text;
    bool is_separator;
    bool is_all_digits;
    bool is_chinese;
};

static std::vector<InputPart> SplitPreedit(const std::string& preedit) {
    std::vector<InputPart> parts;
    const char* p = preedit.c_str();
    const char* end = p + preedit.size();

    std::string buf;
    bool buf_is_chinese = false;
    bool buf_has_digit = false;

    auto flush = [&]() {
        if (!buf.empty()) {
            InputPart part;
            part.text = buf;
            part.is_separator = false;
            part.is_all_digits = buf_has_digit && !buf_is_chinese;
            if (part.is_all_digits) {
                for (char c : buf) {
                    if (!IsDigit(c)) {
                        part.is_all_digits = false;
                        break;
                    }
                }
            }
            part.is_chinese = buf_is_chinese;
            parts.push_back(std::move(part));
            buf.clear();
            buf_is_chinese = false;
            buf_has_digit = false;
        }
    };

    while (p < end) {
        if (*p == ' ' || *p == '\'') {
            flush();
            InputPart sep;
            sep.text = std::string(1, *p);
            sep.is_separator = true;
            sep.is_all_digits = false;
            sep.is_chinese = false;
            parts.push_back(std::move(sep));
            ++p;
            continue;
        }

        const char* prev = p;
        uint32_t cp = DecodeUtf8(p, end);
        if (cp == 0) continue;

        bool is_chi = IsChinese(cp);
        std::string ch = std::string(prev, p);

        if (!buf.empty() && buf_is_chinese != is_chi) {
            flush();
        }

        if (is_chi) buf_is_chinese = true;
        if (cp >= '0' && cp <= '9') buf_has_digit = true;
        buf += ch;
    }
    flush();
    return parts;
}

// ── ConvertPreedit 算法 ──
// 移植自 T9PreeditConverter.kt 的 convertT9PreeditToPinyin()

std::string T9ConvertPreedit(const std::string& preedit,
                               const std::string& comment) {
    if (preedit.empty() || comment.empty()) return preedit;

    std::vector<std::string> pinyin_parts;
    {
        std::istringstream iss(comment);
        std::string word;
        while (iss >> word) {
            if (!word.empty()) {
                // 过滤 comment_format 引入的非字母字符（如雾凇方案的「」），
                // 同时归一化带声调的预组合字符（如万象方案的 jī huà），
                // 确保 pinyin_parts 只包含纯 ASCII 拼音。
                std::string filtered = NormalizePinyinComment(word);
                if (!filtered.empty()) {
                    pinyin_parts.push_back(filtered);
                }
            }
        }
    }
    if (pinyin_parts.empty()) return preedit;

    bool has_digit_or_separator = false;
    for (char c : preedit) {
        if (IsDigit(c) || c == '\'' || c == ' ') {
            has_digit_or_separator = true;
            break;
        }
    }
    if (!has_digit_or_separator) return preedit;

    auto input_parts = SplitPreedit(preedit);

    size_t pi = 0;
    for (size_t i = 0; i < input_parts.size(); ++i) {
        InputPart& part = input_parts[i];
        if (part.is_separator) {
            part.text = " ";
        } else if (part.is_all_digits) {
            if (pi < pinyin_parts.size()) {
                const std::string& py = pinyin_parts[pi];
                bool single_segment_multiple_pinyins =
                    (input_parts.size() == 1 && pinyin_parts.size() > 1);

                if (part.text.size() == 1) {
                    // 单数字段 → 判断是否要触发简拼
                    // 触发简拼条件：是末尾段（最后非分隔符段），或后面紧跟分隔符
                    bool is_last_non_separator = true;
                    bool next_is_separator = false;
                    for (size_t j = i + 1; j < input_parts.size(); ++j) {
                        if (input_parts[j].is_separator) {
                            next_is_separator = true;
                        } else {
                            is_last_non_separator = false;
                            break;
                        }
                    }
                    if (is_last_non_separator || next_is_separator) {
                        // 末尾单数字段或分隔符前的单数字段 → 使用首字母作为简拼
                        // 如 "5" → "j" (从 "jia" 取首字母)
                        std::string prefix = py.substr(0, 2);
                        for (auto& c : prefix) c = static_cast<char>(tolower(c));
                        if (prefix == "zh" || prefix == "ch" || prefix == "sh") {
                            part.text = prefix;
                        } else {
                            part.text = std::string(1, static_cast<char>(tolower(py[0])));
                        }
                    } else {
                        // 中间段单数字 → 使用完整拼音（如 "7公民" 中的 "7"→"shen"）
                        std::string lower = py;
                        for (auto& c : lower) c = static_cast<char>(tolower(c));
                        part.text = lower;
                    }
                } else if (single_segment_multiple_pinyins) {
                    std::string joined;
                    for (const auto& p : pinyin_parts) {
                        std::string lower = p;
                        for (auto& c : lower) c = static_cast<char>(tolower(c));
                        joined += lower;
                    }
                    part.text = joined;
                } else {
                    std::string lower = py;
                    for (auto& c : lower) c = static_cast<char>(tolower(c));
                    part.text = lower;
                }
                ++pi;
            }
        } else if (part.is_chinese) {
            // 中文 = 已提交文本，原样保留，不消耗拼音索引
        } else {
            ++pi;
        }
    }

    std::string result;
    for (const auto& part : input_parts) {
        result += part.text;
    }
    return result;
}

// ── 候选级 preedit 转换 ──
// 英文九键方案（如 melt_eng_t9，table_translator）候选无拼音注释：
//   - comment 为空 → 直接显示候选词文本（"8378" → "test"）
//   - comment 以 '~' 开头（librime 统一编码后缀标记 '~s'/'~ed'）→ 同上
// 中文九键方案（t9_pinyin，script_translator）候选带拼音注释 → 沿用数字→拼音转换。

std::string T9ConvertCandidatePreedit(const std::string& preedit,
                                      const std::string& comment,
                                      const std::string& candidate_text) {
    if (comment.empty() || comment[0] == '~') {
        return candidate_text;
    }
    return T9ConvertPreedit(preedit, comment);
}

// ════════════════════════════════════════════════════════════════
// T9Filter / T9Translation（RIME 依赖）
// ════════════════════════════════════════════════════════════════
// 编译守卫：纯算法测试（T9_ALGO_ONLY_BUILD）仅编译上方 converter，
// 不编译 RIME 依赖的 Filter/Translation 代码。

#ifndef T9_ALGO_ONLY_BUILD

// ── T9Translation ──

void T9Translation::ConvertCurrent() {
    T9_PERF_SCOPED_TIMER("[T9Filter] ConvertCurrent");
    if (!cand_) return;
    auto genuine = Candidate::GetGenuineCandidate(cand_);
    std::string converted = T9ConvertCandidatePreedit(genuine->preedit(),
                                                       genuine->comment(),
                                                       genuine->text());
    T9FLOG("ConvertCurrent: \"%s\" -> \"%s\"",
          genuine->preedit().c_str(), converted.c_str());
    if (converted != genuine->preedit()) {
        cand_ = New<T9PreeditCandidate>(cand_, converted);
    }
}

T9Translation::T9Translation(an<Translation> translation,
                               char auto_delim,
                               char manual_delim)
    : translation_(translation),
      auto_delim_(auto_delim),
      manual_delim_(manual_delim) {
    // 不调用 translation_->Next()：Translation 创建时已定位在第一个候选
    if (translation_->exhausted()) {
        set_exhausted(true);
        return;
    }
    cand_ = translation_->Peek();
    ConvertCurrent();
}

bool T9Translation::Next() {
    if (exhausted()) return false;
    if (!translation_->Next()) {
        set_exhausted(true);
        return false;
    }
    cand_ = translation_->Peek();
    ConvertCurrent();
    return true;
}

// ── T9Filter ──

T9Filter::T9Filter(const Ticket& ticket) : Filter(ticket) {
    if (auto* schema = ticket.schema) {
        if (auto* config = schema->config()) {
            bool display_original = false;
            config->GetBool("t9/isDisplayOriginalPreedit", &display_original);
            convert_preedit_ = !display_original;

            std::string delimiter;
            if (config->GetString("speller/delimiter", &delimiter)
                && delimiter.size() >= 2) {
                auto_delimiter_ = delimiter[0];
                manual_delimiter_ = delimiter[1];
            }
        }
    }
}

an<Translation> T9Filter::Apply(an<Translation> translation,
                                 CandidateList* candidates) {
    T9_PERF_SCOPED_TIMER("[T9Filter] Apply");
    if (!convert_preedit_) return translation;
    if (!translation) return translation;
    return New<T9Translation>(translation, auto_delimiter_, manual_delimiter_);
}

#endif  // T9_ALGO_ONLY_BUILD

}  // namespace rime

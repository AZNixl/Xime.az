#ifndef RIME_T9_FILTER_H_
#define RIME_T9_FILTER_H_

#include <string>

#ifndef T9_ALGO_ONLY_BUILD
#include <rime/filter.h>
#include <rime/translation.h>
#include <rime/candidate.h>
#include <rime/common.h>
#endif

namespace rime {

// 将 T9 九键的 preedit 从数字序列转换为拼音显示。
// 移植自 Kotlin T9PreeditConverter.kt 的 convertT9PreeditToPinyin()。
//
// 例如 "54482" + comment "ji gua" → "ji hua"
// "ji'43" + comment "ji kan" → "ji k"
// "5" + comment "le" → "l"
//
// 原 t9_preedit_converter.h 已合并至此，converter 实现在 t9_filter.cc 中。
std::string T9ConvertPreedit(const std::string& preedit,
                              const std::string& comment);

// 候选级 preedit 转换（英文九键适配，2026-08-07）：
//   - comment 为有效拼音（非空且不以 '~' 开头）→ 数字 → 拼音（T9ConvertPreedit）
//     '~' 是 librime 统一编码（unity encoder）后缀标记（如 melt_eng 的 '~s'/'~ed'），
//     非拼音，不应触发数字→拼音转换。
//   - 否则（英文九键方案无拼音注释，如 melt_eng_t9 的 "test"）→ 直接显示候选词文本。
std::string T9ConvertCandidatePreedit(const std::string& preedit,
                                      const std::string& comment,
                                      const std::string& candidate_text);

#ifndef T9_ALGO_ONLY_BUILD
// 包装候选，覆盖 preedit() 返回转换后的拼音，
// 不修改原始候选对象，避免跨 .so 边界的 dynamic_cast 失效问题。
class T9PreeditCandidate : public Candidate {
public:
    T9PreeditCandidate(an<Candidate> item, const string& preedit)
        : Candidate(item->type() + "'t9",
                    item->start(), item->end(), item->quality()),
          item_(item), preedit_(preedit) {}

    const string& text() const override { return item_->text(); }
    string comment() const override { return item_->comment(); }
    string preedit() const override { return preedit_; }

private:
    an<Candidate> item_;
    string preedit_;
};

class T9Translation : public Translation {
public:
    T9Translation(an<Translation> translation,
                   char auto_delim,
                   char manual_delim);
    bool Next() override;
    an<Candidate> Peek() override { return cand_; }

private:
    void ConvertCurrent();

    an<Translation> translation_;
    an<Candidate> cand_;
    char auto_delim_;
    char manual_delim_;
};

class T9Filter : public Filter {
public:
    explicit T9Filter(const Ticket& ticket);
    an<Translation> Apply(an<Translation> translation,
                           CandidateList* candidates) override;
private:
    bool convert_preedit_ = false;
    char auto_delimiter_ = ' ';
    char manual_delimiter_ = '\'';
};
#endif  // T9_ALGO_ONLY_BUILD

}  // namespace rime

#endif  // RIME_T9_FILTER_H_

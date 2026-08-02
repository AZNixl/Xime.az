#ifndef T9_PROCESSOR_H_
#define T9_PROCESSOR_H_

#include <rime/processor.h>
#include <rime/component.h>
#include "t9_digit_buffer.h"

namespace rime {

class T9Processor : public Processor {
public:
    T9Processor(const Ticket& ticket);
    ~T9Processor() override;

    ProcessResult ProcessKeyEvent(const KeyEvent& key_event) override;

    void SelectSyllable(int candidate_index);
    void SelectPinyinDirect(const std::string& pinyin, int digit_length);
    bool SelectCandidate(int candidate_index);  // true = full commit
    std::string GetRemainingDigits() const;
    void GetSyllableCandidates(std::vector<std::string>& out) const;

    // 第三方方案兼容：t9/isDisplayOriginalPreedit 控制 preedit 是否显示原始数字。
    // true  → 不处理 preedit，显示 rime 原始数字串（默认）。
    // false → 前端根据候选 comment 将 preedit 重建为拼音（由调用方处理）。
    bool IsDisplayOriginalPreedit() const { return display_original_preedit_; }

private:
    bool IsT9Schema() const;
    int DigitCode(char c) const;

    T9DigitBuffer digit_buffer_;
    bool display_original_preedit_ = true;
};

T9Processor* T9ProcessorRequire();

}  // namespace rime

#endif

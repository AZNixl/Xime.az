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

private:
    bool IsT9Schema() const;
    int DigitCode(char c) const;

    T9DigitBuffer digit_buffer_;
};

T9Processor* T9ProcessorRequire();

}  // namespace rime

#endif

#ifndef T9_DIGIT_BUFFER_H_
#define T9_DIGIT_BUFFER_H_

#include <string>
#include <vector>

struct SyllableSelection {
    std::string pinyin;
    int digit_length;
};

class T9DigitBuffer {
public:
    void AppendDigit(char digit);
    bool PopLastDigit();
    void AppendSeparator();

    bool SelectPinyin(const std::string& pinyin, int digit_length);
    bool UndoLastSelection();

    bool IsEmpty() const { return raw_digits_.empty(); }
    bool IsFullyConsumed() const;
    int ConsumedCount() const;
    std::string ToInput() const;
    std::string GetRemainingDigits() const;
    const std::vector<SyllableSelection>& selections() const { return selections_; }
    const std::string& raw_digits() const { return raw_digits_; }

    void Clear();

private:
    std::string raw_digits_;
    std::vector<SyllableSelection> selections_;
    bool separator_pending_ = false;
};

#endif

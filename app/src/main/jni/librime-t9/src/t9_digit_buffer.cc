#include "t9_digit_buffer.h"

void T9DigitBuffer::AppendDigit(char digit) {
    raw_digits_.push_back(digit);
    separator_pending_ = false;
}

bool T9DigitBuffer::PopLastDigit() {
    if (IsEmpty()) return false;
    if (!selections_.empty()) {
        auto& last = selections_.back();
        int sel_digits = last.digit_length;
        if (static_cast<int>(raw_digits_.size()) > sel_digits ||
            raw_digits_.size() == static_cast<size_t>(sel_digits)) {
            UndoLastSelection();
            return true;
        }
    }
    raw_digits_.pop_back();
    return true;
}

void T9DigitBuffer::AppendSeparator() {
    if (!raw_digits_.empty())
        separator_pending_ = true;
}

bool T9DigitBuffer::SelectPinyin(const std::string& pinyin, int digit_length) {
    if (static_cast<int>(raw_digits_.size()) < ConsumedCount() + digit_length)
        return false;
    selections_.push_back({pinyin, digit_length});
    separator_pending_ = false;
    return true;
}

bool T9DigitBuffer::UndoLastSelection() {
    if (selections_.empty()) return false;
    selections_.pop_back();
    return true;
}

void T9DigitBuffer::PushCommit(const std::string& consumed) {
    commits_.push_back(consumed);
}

bool T9DigitBuffer::UndoLastCommit() {
    if (commits_.empty()) return false;
    const std::string prefix = commits_.back();
    commits_.pop_back();
    raw_digits_ = prefix + raw_digits_;
    return true;
}

void T9DigitBuffer::ResetForPartial(const std::string& remaining) {
    raw_digits_ = remaining;
    selections_.clear();
    separator_pending_ = false;
}

bool T9DigitBuffer::IsFullyConsumed() const {
    return ConsumedCount() >= static_cast<int>(raw_digits_.size());
}

int T9DigitBuffer::ConsumedCount() const {
    int total = 0;
    for (const auto& sel : selections_)
        total += sel.digit_length;
    return total;
}

std::string T9DigitBuffer::ToInput() const {
    if (selections_.empty())
        return raw_digits_;

    std::string result;
    for (const auto& sel : selections_) {
        if (!result.empty())
            result += '\'';
        result += sel.pinyin;
    }

    int consumed = ConsumedCount();
    if (consumed < static_cast<int>(raw_digits_.size())) {
        std::string remaining = raw_digits_.substr(consumed);
        if (!result.empty())
            result += '\'';
        result += remaining;
    }

    return result;
}

std::string T9DigitBuffer::GetRemainingDigits() const {
    int consumed = ConsumedCount();
    if (consumed >= static_cast<int>(raw_digits_.size()))
        return "";
    return raw_digits_.substr(consumed);
}

void T9DigitBuffer::Clear() {
    raw_digits_.clear();
    selections_.clear();
    commits_.clear();
    separator_pending_ = false;
}

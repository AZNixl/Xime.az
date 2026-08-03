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

    // Partial commit 撤销支持：记录每次 partial commit 消费掉的数字前缀，
    // 供退格先撤销半提交（把该段数字恢复到预编辑），再删除末尾拼音。
    void PushCommit(const std::string& consumed);
    bool UndoLastCommit();          // 撤销最近一次 partial commit，返回是否成功
    bool HasCommits() const { return !commits_.empty(); }
    // 仅清空 raw_digits_ / selections_ / separator，保留 commits_（供 partial commit 重建 buffer 用）
    void ResetForPartial(const std::string& remaining);

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
    std::vector<std::string> commits_;
    bool separator_pending_ = false;
};

#endif

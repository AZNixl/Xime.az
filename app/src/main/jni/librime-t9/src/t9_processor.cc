#include "t9_processor.h"
#include <rime/common.h>
#include <rime/composition.h>
#include <rime/config.h>
#include <rime/context.h>
#include <rime/engine.h>
#include <rime/key_event.h>
#include <rime/key_table.h>
#include <rime/menu.h>
#include <rime/schema.h>
#include <set>
#include <algorithm>

#include <android/log.h>
#define T9LOG(...) __android_log_print(ANDROID_LOG_DEBUG, "T9Processor", __VA_ARGS__)

namespace rime {

static T9Processor* g_active_t9_processor = nullptr;

T9Processor* T9ProcessorRequire() {
    return g_active_t9_processor;
}

T9Processor::T9Processor(const Ticket& ticket) : Processor(ticket) {
    g_active_t9_processor = this;
    T9LOG("T9Processor created");
    Schema* schema = engine_ ? engine_->schema() : nullptr;
    if (schema && schema->config()) {
        Config* config = schema->config();
        bool display_original = false;
        if (config->GetBool("t9/isDisplayOriginalPreedit", &display_original)) {
            display_original_preedit_ = display_original;
            T9LOG("T9Processor: t9/isDisplayOriginalPreedit = %d", display_original_preedit_);
        }
    }
}

T9Processor::~T9Processor() {
    if (g_active_t9_processor == this)
        g_active_t9_processor = nullptr;
    T9LOG("T9Processor destroyed");
}

ProcessResult T9Processor::ProcessKeyEvent(const KeyEvent& key_event) {
    if (key_event.release() || key_event.ctrl() || key_event.alt())
        return kNoop;

    int ch = key_event.keycode();
    Context* ctx = engine_->context();
    string cur_input = ctx->input();

    T9LOG("ProcessKeyEvent: ch=%d('%c'), cur_input='%s'", ch, (ch >= 32 && ch < 127) ? (char)ch : '?', cur_input.c_str());

    // State sync: if composition was cleared but digit buffer still has state, clear it
    if (cur_input.empty() && !digit_buffer_.IsEmpty()) {
        T9LOG("State sync: clearing stale digit buffer ('%s')", digit_buffer_.ToInput().c_str());
        digit_buffer_.Clear();
    }

    // Digit keys: push to input through normal RIME pipeline
    if (ch >= '2' && ch <= '9') {
        digit_buffer_.AppendDigit(static_cast<char>(ch));
        // Use PushInput to go through RIME's normal speller/translator pipeline
        ctx->PushInput(static_cast<char>(ch));
        string after = ctx->input();
        bool has_menu = ctx->HasMenu();
        T9LOG("Digit %c pushed: input='%s', hasMenu=%d, digitBuffer='%s'",
              (char)ch, after.c_str(), has_menu, digit_buffer_.ToInput().c_str());
        return kAccepted;
    }

    // Apostrophe / key '1': syllable separator
    if (ch == '\'' || ch == '1') {
        if (!digit_buffer_.IsEmpty()) {
            digit_buffer_.AppendSeparator();
            T9LOG("Separator added, digitBuffer='%s'", digit_buffer_.ToInput().c_str());
            // Send apostrophe to RIME as syllable separator
            if (ch == '\'') {
                ctx->PushInput('\'');
            } else {
                // For '1' key, let it pass through (key_binder maps 1→apostrophe when has_menu)
                return kNoop;
            }
            return kAccepted;
        }
        return kNoop;
    }

    // BackSpace: remove last digit
    if (ch == 0xff08 || ch == 0x08) {
        if (digit_buffer_.IsEmpty()) {
            T9LOG("BackSpace: digit buffer empty, passing through");
            return kNoop;
        }
        if (digit_buffer_.PopLastDigit()) {
            string before = ctx->input();
            // Use set_input with the remaining digits to rebuild input
            string new_input = digit_buffer_.ToInput();
            if (new_input.empty()) {
                ctx->Clear();
                T9LOG("BackSpace: buffer empty, cleared");
            } else {
                ctx->set_input(new_input);
                T9LOG("BackSpace: '%s' -> set_input('%s')", before.c_str(), new_input.c_str());
            }
            return kAccepted;
        }
        return kNoop;
    }

    // Space: let ExpressEditor handle candidate selection + commit
    if (ch == ' ' && ctx->HasMenu()) {
        T9LOG("Space: hasMenu, passing to editor");
        return kNoop;
    }

    // Return: let ExpressEditor handle commit
    if (ch == 0xff0d) {
        T9LOG("Return: passing to editor");
        return kNoop;
    }

    T9LOG("Key %d not handled, passing through", ch);
    return kNoop;
}

void T9Processor::SelectSyllable(int candidate_index) {
    Context* ctx = engine_->context();
    if (!ctx->HasMenu()) {
        T9LOG("SelectSyllable(%d): no menu", candidate_index);
        return;
    }

    Menu* menu = ctx->composition().back().menu.get();
    if (!menu) {
        T9LOG("SelectSyllable(%d): no menu object", candidate_index);
        return;
    }

    an<Candidate> cand = menu->GetCandidateAt(candidate_index);
    if (!cand) {
        T9LOG("SelectSyllable(%d): null candidate", candidate_index);
        return;
    }

    string comment = Candidate::GetGenuineCandidate(cand)->comment();
    T9LOG("SelectSyllable(%d): text='%s' comment='%s'",
          candidate_index, cand->text().c_str(), comment.c_str());

    if (comment.empty()) {
        T9LOG("SelectSyllable(%d): empty comment", candidate_index);
        return;
    }

    string first_syllable;
    size_t space_pos = comment.find(' ');
    if (space_pos != string::npos)
        first_syllable = comment.substr(0, space_pos);
    else
        first_syllable = comment;

    T9LOG("SelectSyllable: first syllable='%s'", first_syllable.c_str());

    // Calculate digit length for this syllable
    int digit_length = 0;
    for (size_t i = 0; i < first_syllable.length(); ++i) {
        char c = first_syllable[i];
        char code = DigitCode(c);
        if (code == 0) break;
        digit_length += 1;
    }

    if (digit_length == 0) {
        T9LOG("SelectSyllable: zero digit length for '%s'", first_syllable.c_str());
        return;
    }

    T9LOG("SelectSyllable: digit_length=%d for syllable '%s'", digit_length, first_syllable.c_str());

    if (digit_buffer_.SelectPinyin(first_syllable, digit_length)) {
        string new_input = digit_buffer_.ToInput();
        T9LOG("SelectSyllable: set_input('%s')", new_input.c_str());
        ctx->set_input(new_input);
    } else {
        T9LOG("SelectSyllable: SelectPinyin failed (consumed=%d/%d, need=%d)",
              digit_buffer_.ConsumedCount(), (int)digit_buffer_.raw_digits().size(), digit_length);
    }
}

bool T9Processor::SelectCandidate(int candidate_index) {
    Context* ctx = engine_->context();
    if (!ctx->HasMenu()) {
        T9LOG("SelectCandidate(%d): no menu", candidate_index);
        return true;
    }

    Menu* menu = ctx->composition().back().menu.get();
    if (!menu) {
        T9LOG("SelectCandidate(%d): no menu object", candidate_index);
        return true;
    }

    an<Candidate> cand = menu->GetCandidateAt(candidate_index);
    if (!cand) {
        T9LOG("SelectCandidate(%d): null candidate", candidate_index);
        return true;
    }

    string comment = Candidate::GetGenuineCandidate(cand)->comment();
    T9LOG("SelectCandidate(%d): text='%s' comment='%s', fullyConsumed=%d",
          candidate_index, cand->text().c_str(), comment.c_str(), digit_buffer_.IsFullyConsumed());

    // Parse comment into syllables (needed for all paths below)
    vector<string> comment_syllables;
    size_t pos = 0;
    while (pos < comment.length()) {
        size_t space = comment.find(' ', pos);
        if (space == string::npos) {
            comment_syllables.push_back(comment.substr(pos));
            break;
        }
        comment_syllables.push_back(comment.substr(pos, space - pos));
        pos = space + 1;
    }

    T9LOG("SelectCandidate: comment_syllables=[%zu] buf_selections=[%zu] raw_digits=[%s] consumed=[%d]",
          comment_syllables.size(), digit_buffer_.selections().size(),
          digit_buffer_.raw_digits().c_str(), digit_buffer_.ConsumedCount());

    // Calculate total digit length of comment syllables
    int comment_digit_count = 0;
    for (const auto& syl : comment_syllables) {
        for (char c : syl) {
            if (DigitCode(c)) comment_digit_count++;
        }
    }
    int remaining_digits = static_cast<int>(digit_buffer_.raw_digits().size()) - digit_buffer_.ConsumedCount();
    T9LOG("  comment_digit_count=%d remaining_digits=%d", comment_digit_count, remaining_digits);

    // Full commit check: all digits consumed AND candidate covers all selections
    // Per design doc: candidateTextLength >= selectionHistory.size
    // (q+s+s consuming 3 digits but "确实" only has 2 chars → NOT full commit)
    if (digit_buffer_.IsFullyConsumed() &&
        comment_syllables.size() >= digit_buffer_.selections().size() &&
        cand->text().length() >= digit_buffer_.selections().size()) {
        bool full_commit = true;
        for (size_t i = 0; i < digit_buffer_.selections().size(); ++i) {
            char sel_initial = digit_buffer_.selections()[i].pinyin[0];
            char syl_initial = comment_syllables[i][0];
            if (DigitCode(sel_initial) != DigitCode(syl_initial)) {
                full_commit = false;
                break;
            }
        }
        if (full_commit) {
            T9LOG("SelectCandidate: full commit (all digits consumed, comment aligns)");
            digit_buffer_.Clear();
            return true;
        }
        // Comment doesn't cover all selections → release extra digits
        // Count how many selections the comment actually covers
        size_t covered = 0;
        size_t max_check = std::min(comment_syllables.size(), digit_buffer_.selections().size());
        for (size_t i = 0; i < max_check; ++i) {
            if (DigitCode(digit_buffer_.selections()[i].pinyin[0]) ==
                DigitCode(comment_syllables[i][0])) {
                covered++;
            } else {
                break;
            }
        }
        int consumed = 0;
        for (size_t i = 0; i < covered; ++i) {
            consumed += digit_buffer_.selections()[i].digit_length;
        }
        string remaining = digit_buffer_.raw_digits().substr(consumed);
        T9LOG("SelectCandidate: releasing excess, covered=%zu consumed=%d remaining='%s'",
              covered, consumed, remaining.c_str());
        digit_buffer_.Clear();
        for (char d : remaining) {
            digit_buffer_.AppendDigit(d);
        }
        return false;
    }

    // Jianpin alignment: selection initials match comment syllable initials
    // Full commit only when comment syllables cover ALL remaining digits
    if (comment_syllables.size() >= digit_buffer_.selections().size() &&
        comment_digit_count >= remaining_digits) {
        bool jianpin_aligned = true;
        for (size_t i = 0; i < digit_buffer_.selections().size(); ++i) {
            char sel_initial = digit_buffer_.selections()[i].pinyin[0];
            char syl_initial = comment_syllables[i][0];
            T9LOG("  jianpin check[%zu]: sel_initial='%c'(%d) syl_initial='%c'(%d)",
                  i, sel_initial, DigitCode(sel_initial), syl_initial, DigitCode(syl_initial));
            if (DigitCode(sel_initial) != DigitCode(syl_initial)) {
                jianpin_aligned = false;
                break;
            }
        }
        if (jianpin_aligned) {
            T9LOG("SelectCandidate: jianpin aligned, full commit");
            digit_buffer_.Clear();
            return true;
        }
    }

    // Partial commit: reset the buffer to only the remaining digits, clear selections.
    // GetRemainingDigits() alone is insufficient for the pure-digit case (no left-column
    // selections): it only accounts for selections_, so it keeps the whole digit sequence.
    // In that case the candidate's comment syllables actually consume digits from the front.
    string remaining;
    if (digit_buffer_.selections().empty()) {
        string unconsumed = digit_buffer_.raw_digits();
        int consumed_by_comment = ComputeConsumedDigitsFromSyllables(unconsumed, comment_syllables);
        if (consumed_by_comment < static_cast<int>(unconsumed.length())) {
            remaining = unconsumed.substr(consumed_by_comment);
            T9LOG("SelectCandidate: partial commit, consuming=%d resetting buffer to remaining='%s'",
                  consumed_by_comment, remaining.c_str());
        } else {
            // The candidate comment covers all digits → full commit.
            T9LOG("SelectCandidate: partial commit consumed all digits, full commit");
            digit_buffer_.Clear();
            return true;
        }
    } else {
        // Left-column selections exist: the candidate's comment covers the leading
        // selections. Keep the digits of the selections NOT covered by the comment,
        // plus any trailing unconsumed digits.
        // GetRemainingDigits() is wrong here: it assumes the candidate covers ALL
        // pinned selections, so when the selections consume every digit it returns ""
        // and wipes the whole buffer. E.g. pin die-ba-die-ba on "3432234322" then
        // select "跌"(die): only the first selection is covered, "ba-die-ba"(2234322)
        // must be preserved.
        size_t covered = 0;
        size_t max_check = std::min(comment_syllables.size(),
                                    digit_buffer_.selections().size());
        for (size_t i = 0; i < max_check; ++i) {
            if (DigitCode(digit_buffer_.selections()[i].pinyin[0]) ==
                DigitCode(comment_syllables[i][0])) {
                covered++;
            } else {
                break;
            }
        }
        int consumed = 0;
        for (size_t i = 0; i < covered; ++i)
            consumed += digit_buffer_.selections()[i].digit_length;
        remaining = digit_buffer_.raw_digits().substr(consumed);
        T9LOG("SelectCandidate: partial commit (selections), covered=%zu consumed=%d remaining='%s'",
              covered, consumed, remaining.c_str());
    }
    digit_buffer_.Clear();
    for (char d : remaining) {
        digit_buffer_.AppendDigit(d);
    }
    return false;
}

int T9Processor::ComputeConsumedDigitsFromSyllables(
    const std::string& segment,
    const std::vector<std::string>& syllables) const {
    if (segment.empty() || syllables.empty()) return 0;
    int consumed = 0;
    std::string remaining = segment;
    for (const auto& syl : syllables) {
        std::string syl_code;
        for (char c : syl) {
            int d = DigitCode(c);
            if (!d) { syl_code.clear(); break; }
            syl_code += static_cast<char>('0' + d);
        }
        if (syl_code.empty()) break;
        if (remaining.rfind(syl_code, 0) == 0) {
            consumed += static_cast<int>(syl_code.length());
            remaining = remaining.substr(syl_code.length());
        } else {
            int match_len = 0;
            if (static_cast<int>(remaining.length()) >= static_cast<int>(syl_code.length())) {
                for (int len = static_cast<int>(syl_code.length()) - 1; len >= 1; --len) {
                    if (remaining.rfind(syl_code.substr(0, len), 0) == 0) {
                        match_len = len;
                        break;
                    }
                }
                if (match_len == 0 && remaining.rfind(syl_code.substr(0, 1), 0) == 0)
                    match_len = 1;
            } else {
                for (int len = static_cast<int>(remaining.length()); len >= 1; --len) {
                    if (syl_code.rfind(remaining.substr(0, len), 0) == 0) {
                        match_len = len;
                        break;
                    }
                }
            }
            if (match_len > 0) {
                consumed += match_len;
                remaining = remaining.substr(match_len);
            } else {
                break;
            }
        }
    }
    if (consumed > 0) return consumed;
    // Comment doesn't match the digit segment at all → consume nothing.
    // Avoids misclassifying a candidate whose syllables exceed the segment length
    // as a full commit.
    return 0;
}

void T9Processor::SelectPinyinDirect(const std::string& pinyin, int digit_length) {
    if (digit_length <= 0 || pinyin.empty()) {
        T9LOG("SelectPinyinDirect: invalid args pinyin='%s' len=%d", pinyin.c_str(), digit_length);
        return;
    }
    if (digit_buffer_.SelectPinyin(pinyin, digit_length)) {
        string new_input = digit_buffer_.ToInput();
        T9LOG("SelectPinyinDirect: pinyin='%s' len=%d -> set_input('%s')",
              pinyin.c_str(), digit_length, new_input.c_str());
        engine_->context()->set_input(new_input);
    } else {
        T9LOG("SelectPinyinDirect: SelectPinyin failed (consumed=%d/%d, need=%d)",
              digit_buffer_.ConsumedCount(), (int)digit_buffer_.raw_digits().size(), digit_length);
    }
}

std::string T9Processor::GetRemainingDigits() const {
    return digit_buffer_.GetRemainingDigits();
}

void T9Processor::GetSyllableCandidates(std::vector<std::string>& out) const {
    Context* ctx = engine_->context();
    if (!ctx || !ctx->HasMenu()) return;

    Menu* menu = ctx->composition().back().menu.get();
    if (!menu) return;

    std::set<std::string> seen;
    for (size_t i = 0; i < menu->Prepare(15); ++i) {
        an<Candidate> cand = menu->GetCandidateAt(i);
        if (!cand) continue;

        string comment = Candidate::GetGenuineCandidate(cand)->comment();
        if (comment.empty()) continue;

        string first_syllable;
        size_t space_pos = comment.find(' ');
        first_syllable = (space_pos != string::npos)
            ? comment.substr(0, space_pos) : comment;

        if (seen.insert(first_syllable).second)
            out.push_back(first_syllable);
    }
}

bool T9Processor::IsT9Schema() const {
    Schema* schema = engine_->schema();
    if (!schema) return false;

    Config* config = schema->config();
    if (!config) return false;

    an<ConfigMap> t9_config = config->GetMap("t9");
    return t9_config != nullptr;
}

int T9Processor::DigitCode(char c) const {
    static const char* const groups[] = {
        "abc", "def", "ghi", "jkl", "mno", "pqrs", "tuv", "wxyz"
    };
    c = tolower(c);
    for (int i = 0; i < 8; ++i) {
        if (strchr(groups[i], c))
            return i + 2;
    }
    return 0;
}

}  // namespace rime

#include "t9_pinyin_map.h"

#include <algorithm>
#include <cctype>

namespace rime {

// 单字母→数字映射（对应 Kotlin LETTER_TO_DIGIT）
static const std::unordered_map<char, char>& LetterToDigitMap() {
    static const std::unordered_map<char, char> kMap = {
        {'a', '2'}, {'b', '2'}, {'c', '2'},
        {'d', '3'}, {'e', '3'}, {'f', '3'},
        {'g', '4'}, {'h', '4'}, {'i', '4'},
        {'j', '5'}, {'k', '5'}, {'l', '5'},
        {'m', '6'}, {'n', '6'}, {'o', '6'},
        {'p', '7'}, {'q', '7'}, {'r', '7'}, {'s', '7'},
        {'t', '8'}, {'u', '8'}, {'v', '8'},
        {'w', '9'}, {'x', '9'}, {'y', '9'}, {'z', '9'}
    };
    return kMap;
}

// 有效拼音列表（对应 Kotlin PINYIN_LIST）
const std::vector<std::string>& T9PinyinMap::PinyinList() {
    static const std::vector<std::string> kPinyinList = {
        "a", "ai", "an", "ang", "ao",
        "ba", "bai", "ban", "bang", "bao", "bei", "ben", "beng", "bi", "bian", "biao",
        "bie", "bin", "bing", "bo", "bu",
        "ca", "cai", "can", "cang", "cao", "ce", "cen", "ceng", "cha", "chai", "chan",
        "chang", "chao", "che", "chen", "cheng", "chi", "chong", "chou", "chu", "chua",
        "chuai", "chuan", "chuang", "chui", "chun", "chuo", "ci", "cong", "cou", "cu",
        "cuan", "cui", "cun", "cuo",
        "da", "dai", "dan", "dang", "dao", "de", "dei", "den", "deng", "di", "dia",
        "dian", "diao", "die", "ding", "diu", "dong", "dou", "du", "duan", "dui", "dun", "duo",
        "e", "ei", "en", "eng", "er",
        "fa", "fan", "fang", "fei", "fen", "feng", "fiao", "fo", "fou", "fu",
        "ga", "gai", "gan", "gang", "gao", "ge", "gei", "gen", "geng", "gong", "gou",
        "gu", "gua", "guai", "guan", "guang", "gui", "gun", "guo",
        "ha", "hai", "han", "hang", "hao", "he", "hei", "hen", "heng", "hong", "hou",
        "hu", "hua", "huai", "huan", "huang", "hui", "hun", "huo",
        "ji", "jia", "jian", "jiang", "jiao", "jie", "jin", "jing", "jiong", "jiu",
        "ju", "juan", "jue", "jun",
        "ka", "kai", "kan", "kang", "kao", "ke", "kei", "ken", "keng", "kong", "kou",
        "ku", "kua", "kuai", "kuan", "kuang", "kui", "kun", "kuo",
        "la", "lai", "lan", "lang", "lao", "le", "lei", "leng", "li", "lia", "lian",
        "liang", "liao", "lie", "lin", "ling", "liu", "lo", "long", "lou", "lu",
        "luan", "lve", "lun", "luo", "lv",
        "ma", "mai", "man", "mang", "mao", "me", "mei", "men", "meng", "mi", "mian",
        "miao", "mie", "min", "ming", "miu", "mo", "mou", "mu",
        "na", "nai", "nan", "nang", "nao", "ne", "nei", "nen", "neng", "ni", "nian",
        "niang", "niao", "nie", "nin", "ning", "niu", "nong", "nou", "nu", "nuan",
        "nve", "nun", "nuo", "nv",
        "o", "ou",
        "pa", "pai", "pan", "pang", "pao", "pei", "pen", "peng", "pi", "pian", "piao",
        "pie", "pin", "ping", "po", "pou", "pu",
        "qi", "qia", "qian", "qiang", "qiao", "qie", "qin", "qing", "qiong", "qiu",
        "qu", "quan", "que", "qun",
        "ran", "rang", "rao", "re", "ren", "reng", "ri", "rong", "rou", "ru", "rua",
        "ruan", "rui", "run", "ruo",
        "sa", "sai", "san", "sang", "sao", "se", "sen", "seng", "sha", "shai", "shan",
        "shang", "shao", "she", "shei", "shen", "sheng", "shi", "shou", "shu", "shua",
        "shuai", "shuan", "shuang", "shui", "shun", "shuo", "si", "song", "sou", "su",
        "suan", "sui", "sun", "suo",
        "ta", "tai", "tan", "tang", "tao", "te", "tei", "teng", "ti", "tian", "tiao",
        "tie", "ting", "tong", "tou", "tu", "tuan", "tui", "tun", "tuo",
        "wa", "wai", "wan", "wang", "wei", "wen", "weng", "wo", "wu",
        "xi", "xia", "xian", "xiang", "xiao", "xie", "xin", "xing", "xiong", "xiu",
        "xu", "xuan", "xue", "xun",
        "ya", "yan", "yang", "yao", "ye", "yi", "yin", "ying", "yo", "yong", "you",
        "yu", "yuan", "yue", "yun",
        "za", "zai", "zan", "zang", "zao", "ze", "zei", "zen", "zeng", "zha", "zhai",
        "zhan", "zhang", "zhao", "zhe", "zhei", "zhen", "zheng", "zhi", "zhong", "zhou",
        "zhu", "zhua", "zhuai", "zhuan", "zhuang", "zhui", "zhun", "zhuo", "zi", "zong",
        "zou", "zu", "zuan", "zui", "zun", "zuo"
    };
    return kPinyinList;
}

T9PinyinMap::T9PinyinMap() {
    // 数字→字母映射（对应 Kotlin digitToLetters）
    digit_to_letters_['2'] = {'a', 'b', 'c'};
    digit_to_letters_['3'] = {'d', 'e', 'f'};
    digit_to_letters_['4'] = {'g', 'h', 'i'};
    digit_to_letters_['5'] = {'j', 'k', 'l'};
    digit_to_letters_['6'] = {'m', 'n', 'o'};
    digit_to_letters_['7'] = {'p', 'q', 'r', 's'};
    digit_to_letters_['8'] = {'t', 'u', 'v'};
    digit_to_letters_['9'] = {'w', 'x', 'y', 'z'};

    letter_to_digit_ = LetterToDigitMap();

    BuildCodeToPinyins();
}

void T9PinyinMap::BuildCodeToPinyins() {
    // 对应 Kotlin codeToPinyins by lazy { PINYIN_LIST.groupBy { ... } }
    for (const auto& pinyin : PinyinList()) {
        std::string code;
        code.reserve(pinyin.size());
        for (char c : pinyin) {
            auto it = letter_to_digit_.find(c);
            if (it != letter_to_digit_.end()) {
                code.push_back(it->second);
            } else {
                code.push_back(c);  // 无法映射的字符原样保留
            }
        }
        code_to_pinyins_[code].push_back(pinyin);
    }
}

const T9PinyinMap& T9PinyinMap::Instance() {
    static const T9PinyinMap instance;
    return instance;
}

char T9PinyinMap::LetterToDigit(char c) {
    c = static_cast<char>(std::tolower(static_cast<unsigned char>(c)));
    const auto& map = LetterToDigitMap();
    auto it = map.find(c);
    return it != map.end() ? it->second : 0;
}

const std::vector<char>& T9PinyinMap::LettersForDigit(char digit) const {
    static const std::vector<char> kEmpty;
    auto it = digit_to_letters_.find(digit);
    return it != digit_to_letters_.end() ? it->second : kEmpty;
}

std::vector<SyllableOption> T9PinyinMap::FirstSyllableOptions(
    const std::string& digits, int max_results) const {
    // 对应 Kotlin firstSyllableOptions
    std::vector<SyllableOption> result;
    if (digits.empty()) return result;

    std::unordered_set<std::string> seen;

    // 从长到短搜索精确匹配的拼音
    int max_len = std::min(kMaxPinyinLen, static_cast<int>(digits.length()));
    for (int len = max_len; len >= 1; --len) {
        std::string code = digits.substr(0, len);
        auto it = code_to_pinyins_.find(code);
        if (it != code_to_pinyins_.end()) {
            for (const auto& p : it->second) {
                if (seen.count(p)) continue;
                seen.insert(p);
                result.emplace_back(p, len);
                if (static_cast<int>(result.size()) >= max_results)
                    return result;
            }
        }
    }

    // 首键字母回退
    if (static_cast<int>(result.size()) < max_results) {
        auto it = digit_to_letters_.find(digits[0]);
        if (it == digit_to_letters_.end()) return result;
        for (char l : it->second) {
            std::string ch(1, l);
            if (seen.count(ch)) continue;
            seen.insert(ch);
            result.emplace_back(ch, 1);
            if (static_cast<int>(result.size()) >= max_results)
                return result;
        }
    }

    return result;
}

std::vector<std::string> T9PinyinMap::Candidates(const std::string& digits,
                                                  int max_results) const {
    // 对应 Kotlin candidates
    auto options = FirstSyllableOptions(digits, max_results);
    std::vector<std::string> result;
    result.reserve(options.size());
    for (const auto& opt : options) {
        result.push_back(opt.pinyin);
    }
    return result;
}

std::optional<std::string> T9PinyinMap::PinyinToDigitCode(
    const std::string& pinyin) const {
    // 对应 Kotlin pinyinToDigitCode（含缓存）
    std::string key = pinyin;
    std::transform(key.begin(), key.end(), key.begin(),
                   [](unsigned char c) { return std::tolower(c); });

    auto cache_it = pinyin_code_cache_.find(key);
    if (cache_it != pinyin_code_cache_.end()) {
        return cache_it->second;
    }

    std::string code;
    code.reserve(key.size());
    for (char ch : key) {
        auto it = letter_to_digit_.find(ch);
        if (it == letter_to_digit_.end()) {
            return std::nullopt;
        }
        code.push_back(it->second);
    }
    pinyin_code_cache_[key] = code;
    return code;
}

std::vector<SyllableOption> T9PinyinMap::GreedySplit(
    const std::string& digits) const {
    // 对应 Kotlin greedySplit
    std::vector<SyllableOption> result;
    size_t pos = 0;
    while (pos < digits.length()) {
        std::string remaining = digits.substr(pos);
        auto options = FirstSyllableOptions(remaining, 1);
        if (options.empty()) break;
        result.push_back(options[0]);
        pos += options[0].digit_length;
    }
    return result;
}

bool T9PinyinMap::AreDigitCodesMatching(const std::string& a,
                                         const std::string& b) const {
    // 对应 Kotlin areDigitCodesMatching
    auto code_a = PinyinToDigitCode(a);
    auto code_b = PinyinToDigitCode(b);
    if (!code_a || !code_b) return false;
    return *code_a == *code_b ||
           code_a->compare(0, code_b->size(), *code_b) == 0 ||
           code_b->compare(0, code_a->size(), *code_a) == 0;
}

}  // namespace rime

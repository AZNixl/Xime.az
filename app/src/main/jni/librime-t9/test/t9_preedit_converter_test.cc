// T9ConvertPreedit 单元测试
//
// 对应 Kotlin T9PreeditConverter.kt 的 convertT9PreeditToPinyin()
// 覆盖场景：常规多音节、末尾简拼、单段多音节、中文混合、空 comment
#include <gtest/gtest.h>

#include "t9_filter.h"

using rime::T9ConvertPreedit;
using rime::T9ConvertCandidatePreedit;

// ── 常规场景 ──

TEST(T9PreeditConverterTest, RegularMultiSyllable) {
    // "54482" + "ji gua" → "jigua"
    EXPECT_EQ(T9ConvertPreedit("54482", "ji gua"), "jigua");
}

TEST(T9PreeditConverterTest, RegularThreeSyllable) {
    // "5482" + "ji hua" → "jihua"
    EXPECT_EQ(T9ConvertPreedit("5482", "ji hua"), "jihua");
}

TEST(T9PreeditConverterTest, SeparatorInPreedit) {
    // "ji'5" + "ji kan" → "ji k"（末尾单数字 5→k）
    EXPECT_EQ(T9ConvertPreedit("ji'5", "ji kan"), "ji k");
}

TEST(T9PreeditConverterTest, MultiDigitAfterSeparator) {
    // "ji'43" + "ji kan" → "ji kan"（末尾多数字→完整拼音）
    EXPECT_EQ(T9ConvertPreedit("ji'43", "ji kan"), "ji kan");
}

TEST(T9PreeditConverterTest, SpaceSeparatorInPreedit) {
    // "ji 5" + "ji kan" → "ji k"
    EXPECT_EQ(T9ConvertPreedit("ji 5", "ji kan"), "ji k");
}

// ── 末尾单数字简拼 ──

TEST(T9PreeditConverterTest, LastSingleDigitJianpin) {
    // "5" + "le" → "l"
    EXPECT_EQ(T9ConvertPreedit("5", "le"), "l");
}

TEST(T9PreeditConverterTest, LastSingleDigitZhChSh) {
    // "9" + "zhong" → "zh"
    EXPECT_EQ(T9ConvertPreedit("9", "zhong"), "zh");
    // "2" + "cheng" → "ch"
    EXPECT_EQ(T9ConvertPreedit("2", "cheng"), "ch");
    // "7" + "shen" → "sh"
    EXPECT_EQ(T9ConvertPreedit("7", "shen"), "sh");
}

TEST(T9PreeditConverterTest, LastSingleDigitAfterSeparator) {
    // "ji'4" + "ji guo" → "ji g"
    EXPECT_EQ(T9ConvertPreedit("ji'4", "ji guo"), "ji g");
}

// ── 单段多音节 ──

TEST(T9PreeditConverterTest, SingleSegmentMultiplePinyins) {
    // "564" + "le ming" → "leming"
    EXPECT_EQ(T9ConvertPreedit("564", "le ming"), "leming");
}

// ── 中文混合 ──

TEST(T9PreeditConverterTest, ChineseMixedPreedit) {
    // "公民7" + "gong min" → "公民g"（末尾单数字 7→g）
    EXPECT_EQ(T9ConvertPreedit("\xe5\x85\xac\xe6\xb0\x91" "7", "gong min"),
              "\xe5\x85\xac\xe6\xb0\x91" "g");
}

TEST(T9PreeditConverterTest, ChineseAtEnd) {
    // "7公民" + "shen" → "shen公民"（7不是末尾段，不触发简拼）
    EXPECT_EQ(T9ConvertPreedit("7\xe5\x85\xac\xe6\xb0\x91", "shen"),
              "shen\xe5\x85\xac\xe6\xb0\x91");
}

// ── 边界情况 ──

TEST(T9PreeditConverterTest, EmptyPreedit) {
    EXPECT_EQ(T9ConvertPreedit("", "ji gua"), "");
}

TEST(T9PreeditConverterTest, EmptyComment) {
    EXPECT_EQ(T9ConvertPreedit("54482", ""), "54482");
}

// ── 英文九键（无拼音注释）──
// 英文 table_translator 方案（如 melt_eng_t9）候选无拼音注释：
// 输入 8378 匹配 "test"，preedit 应显示候选词文本而非数字。

TEST(T9PreeditConverterTest, EnglishNoCommentUsesCandidateText) {
    EXPECT_EQ(T9ConvertCandidatePreedit("8378", "", "test"), "test");
    EXPECT_EQ(T9ConvertCandidatePreedit("8378", "", "vest"), "vest");
}

TEST(T9PreeditConverterTest, EnglishUnitySuffixCommentUsesCandidateText) {
    // '~' 为 librime 统一编码后缀标记（melt_eng '~s'/'~ed'），非拼音 → 显示候选词
    EXPECT_EQ(T9ConvertCandidatePreedit("8378", "~s", "tests"), "tests");
    EXPECT_EQ(T9ConvertCandidatePreedit("8378", "~ed", "tested"), "tested");
}

TEST(T9PreeditConverterTest, ChinesePinyinCommentStillConverts) {
    // 中文九键候选带拼音注释 → 仍走数字→拼音转换，行为不变
    EXPECT_EQ(T9ConvertCandidatePreedit("54482", "ji gua", "计划"), "jigua");
    EXPECT_EQ(T9ConvertCandidatePreedit("5", "le", "了"), "l");
}

TEST(T9PreeditConverterTest, NoDigits) {
    // preedit 无数字 → 原样返回
    EXPECT_EQ(T9ConvertPreedit("jigua", "ji gua"), "jigua");
}

TEST(T9PreeditConverterTest, CommentWhitespaceOnly) {
    EXPECT_EQ(T9ConvertPreedit("54482", "   "), "54482");
}

// ── 大小写处理 ──

TEST(T9PreeditConverterTest, UppercaseComment) {
    // comment 中的拼音大写时，转换结果应小写
    EXPECT_EQ(T9ConvertPreedit("54482", "JI GUA"), "jigua");
}

TEST(T9PreeditConverterTest, MixedCaseComment) {
    EXPECT_EQ(T9ConvertPreedit("54482", "Ji Gua"), "jigua");
}

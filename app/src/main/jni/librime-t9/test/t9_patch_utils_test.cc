// t9_patch_utils 单元测试
//
// 覆盖个人词库（translator/packs）补丁的纯判定逻辑：
//   - SanitizePackName：确定性 pack 名派生（杜绝 user_"" 畸形）
//   - EvaluatePacksState：合法保留 / 畸形修复 / 缺失补充
//   - StripPacksLines：只剔除畸形行，保留其他补丁（t9 四要素等）
#include <gtest/gtest.h>

#include "t9_patch_utils.h"

using rime::t9_patch_utils::EvaluatePacksState;
using rime::t9_patch_utils::PacksState;
using rime::t9_patch_utils::SanitizePackName;
using rime::t9_patch_utils::StripPacksLines;

// ── SanitizePackName ──

TEST(T9PatchUtilsTest, Sanitize_Keeps_Alnum_Underscore) {
  EXPECT_EQ(SanitizePackName("t9_pinyin"), "t9_pinyin");
  EXPECT_EQ(SanitizePackName("t9"), "t9");
}

TEST(T9PatchUtilsTest, Sanitize_Strips_Illegal_Chars) {
  EXPECT_EQ(SanitizePackName("rime_ice.t9"), "rime_icet9");
  EXPECT_EQ(SanitizePackName("a-b/c"), "abc");
}

TEST(T9PatchUtilsTest, Sanitize_Empty_When_All_Illegal) {
  EXPECT_EQ(SanitizePackName("\"\"'"), "");
  EXPECT_EQ(SanitizePackName(""), "");
}

// ── EvaluatePacksState ──

TEST(T9PatchUtilsTest, Evaluate_Keep_Valid_Pack) {
  const std::string content =
      "patch:\n"
      "  \"engine/processors/@before 0\": t9_processor\n"
      "  \"translator/packs\": [\"user_pinyin_simp\"]\n";
  EXPECT_EQ(EvaluatePacksState(content), PacksState::kKeep);
}

TEST(T9PatchUtilsTest, Evaluate_Keep_Unquoted_Pack) {
  const std::string content =
      "patch:\n"
      "  \"translator/packs\": [user_t9]\n";
  EXPECT_EQ(EvaluatePacksState(content), PacksState::kKeep);
}

TEST(T9PatchUtilsTest, Evaluate_Repair_Quoted_Empty_Value) {
  // 旧跨块正则捕获 custom_phrase.dictionary: "" → packName = user_"" → 畸形行
  const std::string content =
      "patch:\n"
      "  \"translator/packs\": [\"user_\"\"\"]\n";
  EXPECT_EQ(EvaluatePacksState(content), PacksState::kRepair);
}

TEST(T9PatchUtilsTest, Evaluate_Repair_Empty_List) {
  const std::string content =
      "patch:\n"
      "  \"translator/packs\": []\n";
  EXPECT_EQ(EvaluatePacksState(content), PacksState::kRepair);
}

TEST(T9PatchUtilsTest, Evaluate_Repair_Illegal_Name) {
  const std::string content =
      "patch:\n"
      "  \"translator/packs\": [\"user_\"\"]\n";
  EXPECT_EQ(EvaluatePacksState(content), PacksState::kRepair);
}

TEST(T9PatchUtilsTest, Evaluate_Missing_No_Packs_Line) {
  const std::string content =
      "patch:\n"
      "  \"engine/filters/@before 0\": t9_filter\n";
  EXPECT_EQ(EvaluatePacksState(content), PacksState::kMissing);
}

TEST(T9PatchUtilsTest, Evaluate_Missing_Empty_Content) {
  EXPECT_EQ(EvaluatePacksState(""), PacksState::kMissing);
}

// ── StripPacksLines ──

TEST(T9PatchUtilsTest, Strip_Removes_Only_Packs_Lines) {
  const std::string content =
      "patch:\n"
      "  \"engine/processors/@before 0\": t9_processor\n"
      "  \"translator/packs\": [\"user_\"\"\"]\n"
      "  \"t9/isDisplayOriginalPreedit\": false\n";
  const std::string stripped = StripPacksLines(content);
  EXPECT_EQ(stripped.find("translator/packs"), std::string::npos);
  EXPECT_NE(stripped.find("t9_processor"), std::string::npos);
  EXPECT_NE(stripped.find("isDisplayOriginalPreedit"), std::string::npos);
}

TEST(T9PatchUtilsTest, Strip_No_Packs_Line_Keeps_Content) {
  const std::string content =
      "patch:\n"
      "  \"t9/isDisplayOriginalPreedit\": false\n";
  const std::string stripped = StripPacksLines(content);
  EXPECT_NE(stripped.find("t9/isDisplayOriginalPreedit"), std::string::npos);
  EXPECT_EQ(stripped.find("translator/packs"), std::string::npos);
}

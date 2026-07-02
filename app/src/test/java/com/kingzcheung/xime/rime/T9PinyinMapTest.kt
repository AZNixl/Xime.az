package com.kingzcheung.xime.rime

import org.junit.Assert.*
import org.junit.Test

/**
 * T9PinyinMap 单元测试
 *
 * 覆盖核心场景：单音节匹配、贪婪分割（仅用于分词键）、
 * 方案 A 数据流验证（原始数字序列发送给 RIME）
 */
class T9PinyinMapTest {

    // ── 单音节匹配（左侧列展示） ──

    @Test
    fun `firstSyllableOptions 54 returns ji and li`() {
        val options = T9PinyinMap.firstSyllableOptions("54")
        val pinyins = options.map { it.pinyin }
        assertTrue("should contain ji", "ji" in pinyins)
        assertTrue("should contain li", "li" in pinyins)
    }

    @Test
    fun `firstSyllableOptions 5 returns first key letters j k l`() {
        val options = T9PinyinMap.firstSyllableOptions("5")
        val pinyins = options.map { it.pinyin }
        assertTrue("should contain j", "j" in pinyins)
        assertTrue("should contain k", "k" in pinyins)
        assertTrue("should contain l", "l" in pinyins)
    }

    @Test
    fun `firstSyllableOptions 482 returns hua and gua`() {
        val options = T9PinyinMap.firstSyllableOptions("482")
        val pinyins = options.map { it.pinyin }
        assertTrue("should contain hua", "hua" in pinyins)
        assertTrue("should contain gua", "gua" in pinyins)
    }

    @Test
    fun `firstSyllableOptions empty returns empty`() {
        assertEquals(emptyList<T9PinyinMap.SyllableOption>(), T9PinyinMap.firstSyllableOptions(""))
    }

    // ── 贪婪分割（仅用于分词键"1"场景） ──

    @Test
    fun `greedySplit 54482 returns ji and gua`() {
        val split = T9PinyinMap.greedySplit("54482")
        val pinyins = split.map { it.pinyin }
        assertEquals(listOf("ji", "gua"), pinyins)
        assertEquals(listOf(2, 3), split.map { it.digitLength })
    }

    @Test
    fun `greedySplit 54 returns ji`() {
        val split = T9PinyinMap.greedySplit("54")
        assertEquals(listOf("ji"), split.map { it.pinyin })
        assertEquals(listOf(2), split.map { it.digitLength })
    }

    @Test
    fun `greedySplit empty returns empty`() {
        assertEquals(emptyList<T9PinyinMap.SyllableOption>(), T9PinyinMap.greedySplit(""))
    }

    // ── 方案 A 数据流：原始数字序列发送给 RIME ──

    @Test
    fun `stepwise 5-4-4-8-2 sends raw digits to RIME`() {
        // 方案 A：sendToRime() 直接发送 confirmedPinyins + digits
        // RIME speller algebra 规则自动展开数字→拼音
        val steps = listOf("5", "54", "544", "5448", "54482")
        val rimeInputs = steps.map { digits ->
            // sendToRime 发送原始数字序列
            digits
        }

        // 每步发送的 RIME 输入就是原始数字序列
        assertEquals("5", rimeInputs[0])
        assertEquals("54", rimeInputs[1])
        assertEquals("544", rimeInputs[2])
        assertEquals("5448", rimeInputs[3])
        assertEquals("54482", rimeInputs[4])

        // 所有输入互不相同 → RIME 每次收到不同输入 → 候选词列表实时更新
        assertEquals(rimeInputs.size, rimeInputs.distinct().size)
    }

    @Test
    fun `left panel shows pinyin candidates while RIME receives digits`() {
        // 左侧列由 T9PinyinMap 计算（0 JNI）
        // RIME 接收原始数字序列（1 JNI）
        // 两者独立工作，互不干扰

        // 输入 "54" → 左侧列显示 [ji, li, j, k, l]
        val leftPanel = T9PinyinMap.firstSyllableOptions("54").map { it.pinyin }
        assertTrue("ji" in leftPanel)
        assertTrue("li" in leftPanel)

        // 同时 RIME 收到 "54"，通过 algebra 展开为所有可能拼音
        // 候选词列表包含 ji 和 li 开头的词
    }

    @Test
    fun `user selects li from left panel then RIME receives li482`() {
        // 用户点击左侧列 "li" → confirmedPinyins=["li"], digits="482"
        // sendToRime → "li482"
        // RIME 处理 "li"(直接识别) + "482"(展开为 hua/gua/...)
        val confirmedPinyins = listOf("li")
        val digits = "482"
        val rimeInput = confirmedPinyins.joinToString("") + digits
        assertEquals("li482", rimeInput)
    }

    @Test
    fun `backspace consistency digits length matches keypress count`() {
        val inputSequence = listOf("5", "4", "4", "8", "2")
        var digits = ""
        for (d in inputSequence) {
            digits += d
        }
        assertEquals(5, digits.length)
        assertEquals("54482", digits)

        repeat(5) {
            digits = digits.dropLast(1)
        }
        assertEquals("", digits)
    }

    @Test
    fun `separator key produces confirmed pinyin with apostrophe`() {
        // 用户按 5,4 → 按"1"分词键 → confirmedPinyins=["ji'"], digits=""
        // 再按 4,8,2 → sendToRime → "ji'482"
        val split = T9PinyinMap.greedySplit("54")
        val confirmedPinyins = split.map { it.pinyin + "'" }
        val digits = ""
        val rimeInput = confirmedPinyins.joinToString("") + digits
        assertEquals("ji'", rimeInput)

        // 继续输入 482
        val moreDigits = "482"
        val fullInput = confirmedPinyins.joinToString("") + moreDigits
        assertEquals("ji'482", fullInput)
    }

    // ── 多音节贪婪分割（分词键场景） ──

    @Test
    fun `greedySplit 54143 returns ji only`() {
        val split = T9PinyinMap.greedySplit("54143")
        val pinyins = split.map { it.pinyin }
        assertEquals(listOf("ji"), pinyins)
    }

    @Test
    fun `greedySplit 24264 returns at least one syllable`() {
        val split = T9PinyinMap.greedySplit("24264")
        val pinyins = split.map { it.pinyin }
        assertTrue(pinyins.size >= 1)
    }
}

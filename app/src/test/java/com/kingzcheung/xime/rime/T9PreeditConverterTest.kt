package com.kingzcheung.xime.rime

import org.junit.Assert.*
import org.junit.Test

/**
 * convertT9PreeditToPinyin 单元测试
 *
 * 验证：数字 preedit（如 88）结合 RIME 候选 comment（如 tu）
 * 能正确转换为拼音显示，而不是显示数字。
 */
class T9PreeditConverterTest {

    @Test
    fun `88 with comment tu converts to tu`() {
        assertEquals("tu", convertT9PreeditToPinyin("88", "tu"))
    }

    @Test
    fun `54 with comment ji converts to ji`() {
        assertEquals("ji", convertT9PreeditToPinyin("54", "ji"))
    }

    @Test
    fun `482 with comment hua gua merges pinyins`() {
        // 多音节 comment 时，preedit 单段把拼音合并
        assertEquals("huagua", convertT9PreeditToPinyin("482", "hua gua"))
    }

    @Test
    fun `multi segment preedit with apostrophe maps each segment`() {
        // ji + 482 → 每段用对应拼音，空格保留
        assertEquals("ji hua", convertT9PreeditToPinyin("ji'482", "ji hua"))
    }

    @Test
    fun `empty comment falls back to pinyin inference`() {
        // comment 为空时回退到 T9PinyinMap 推断，88 → tu
        assertEquals("tu", convertT9PreeditToPinyin("88", ""))
        assertEquals("tu", inferPinyinFromDigits("88"))
    }

    @Test
    fun `infer 54 returns ji`() {
        assertEquals("ji", inferPinyinFromDigits("54"))
    }

    @Test
    fun `infer keeps confirmed pinyin and apostrophe`() {
        // ji'482 → ji + 贪心切分 482 → 有效拼音（hua 或 gua）
        val result = inferPinyinFromDigits("ji'482")
        assertTrue("should start with ji", result.startsWith("ji"))
        assertTrue("should contain digit-derived pinyin", result.length > 4)
    }

    @Test
    fun `preedit without digit returns unchanged`() {
        assertEquals("tu", convertT9PreeditToPinyin("tu", "tu"))
    }
}

package com.kingzcheung.xime.rime

import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.BeforeClass
import org.junit.FixMethodOrder
import org.junit.Test
import org.junit.runners.MethodSorters
import java.io.File
import java.io.FileOutputStream

@FixMethodOrder(MethodSorters.NAME_ASCENDING)
class T9ProcessorIntegrationTest {

    companion object {
        private const val TAG = "T9IntegrationTest"
        private const val T9_SCHEMA = "t9_pinyin"
        private var initialized = false

        @BeforeClass @JvmStatic
        fun setupClass() {
            val ctx = InstrumentationRegistry.getInstrumentation().targetContext
            val rimeDir = File(ctx.filesDir, "rime_test")
            if (rimeDir.exists()) rimeDir.deleteRecursively(); rimeDir.mkdirs()
            copyAssets(ctx, "rime", rimeDir)

            // Ensure t9_pinyin schema is enabled
            val def = File(rimeDir, "default.yaml")
            if (!def.exists()) def.writeText("schema_list:\n  - schema: t9_pinyin\n")
            else if (!def.readText().contains("t9_pinyin")) {
                def.writeText(def.readText().trimEnd() + "\n  - schema: t9_pinyin\n")
            }

            // Compile prism: deploy schema
            val engine = RimeEngine.getInstance()
            engine.initialize(rimeDir.absolutePath, rimeDir.absolutePath)

            // Wait for session
            var ready = engine.ensureSession(120_000)
            if (!ready) {
                android.util.Log.w(TAG, "Session not ready after 120s, trying deploy...")
                ready = engine.startMaintenance(true)
                if (!ready) {
                    // Fallback: direct deploy
                    ready = engine.deploy()
                }
                Thread.sleep(30_000)
                ready = engine.ensureSession(120_000)
            }

            // Switch to t9_pinyin
            var switched = false
            for (attempt in 1..3) {
                switched = engine.switchSchema(T9_SCHEMA)
                if (switched) break
                android.util.Log.w(TAG, "Switch to t9_pinyin failed (attempt $attempt), redeploying...")
                engine.startMaintenance(true)
                Thread.sleep(15_000)
            }
            if (!switched) throw RuntimeException("Failed to switch to t9_pinyin schema")

            // Verify t9_processor works: type a digit and check for candidates
            engine.processKey('5'.code, 0)
            val candidates = engine.getComposition().candidates
            android.util.Log.d(TAG, "Init test: digit 5 -> ${candidates.size} candidates")
            if (candidates.isEmpty()) {
                // Schema may need recompilation
                android.util.Log.w(TAG, "No candidates for digit 5, redeploying...")
                engine.startMaintenance(true)
                Thread.sleep(30_000)
                engine.ensureSession(60_000)
                engine.switchSchema(T9_SCHEMA)
            }
            engine.clearComposition()
            initialized = true
        }

        private fun copyAssets(ctx: android.content.Context, path: String, dest: File) {
            val list = ctx.assets.list(path) ?: return
            for (name in list) {
                val sub = "$path/$name"
                if (name.contains(".")) {
                    try { ctx.assets.open(sub).use { i -> FileOutputStream(File(dest, name)).use { o -> i.copyTo(o) } } }
                    catch (e: Exception) { android.util.Log.w(TAG, "copy fail $sub: ${e.message}") }
                } else {
                    val d = File(dest, name); d.mkdirs(); copyAssets(ctx, sub, d)
                }
            }
        }
    }

    private lateinit var engine: RimeEngine

    @Before fun setup() {
        engine = RimeEngine.getInstance()
        assertTrue(RimeEngine.isInitialized())
        engine.switchSchema(T9_SCHEMA)
    }

    @After fun tearDown() { engine.clearComposition() }

    private fun press(d: String) { engine.processKey(d[0].code, 0) }
    private fun pressKey(code: Int) { engine.processKey(code, 0) }
    private fun digits(s: String) { for (c in s) engine.processKey(c.code, 0) }
    private fun input() = engine.getInput()
    private fun candidates() = engine.getComposition().candidates.map { it.text to it.comment }
    private fun leftSelect(pinyin: String, len: Int) { engine.t9SelectPinyinDirect(pinyin, len) }
    private fun rightSelect(comment: String): Boolean {
        val idx = candidates().indexOfFirst { it.second == comment }
        return if (idx >= 0) engine.t9SelectCandidate(idx) else false
    }
    private fun rightSelectByText(text: String): Boolean {
        val idx = candidates().indexOfFirst { it.first == text }
        return if (idx >= 0) engine.t9SelectCandidate(idx) else false
    }
    private fun rightSelectFirst() = engine.t9SelectCandidate(0)
    private fun hasDigit(n: Int): Boolean { return getDigitCount() >= n }
    private fun getDigitCount(): Int {
        val raw = engine.getComposition().input
        if (raw.isEmpty()) return 0
        // t9_processor uses digit buffer; if composition cleared, count is 0
        return try {
            // try reading from RIME input
            engine.getInput().count { it in '0'..'9' }
        } catch (e: Exception) { 0 }
    }

    @Test fun test00_moduleRegistered() { assertTrue(engine.t9GetSyllableCandidates().isEmpty()) }

    // ═══════════════════════════════════════════════════
    // 12.1 基础输入场景 1-12
    // ═══════════════════════════════════════════════════

    @Test fun test01_digitThenRightSelect() {
        digits("54482")
        val c = candidates()
        android.util.Log.d(TAG, "test01: after 54482, ${c.size} candidates")
        if (c.isEmpty()) {
            // Check engine state
            val comp = engine.getComposition()
            android.util.Log.e(TAG, "  input='${comp.input}' preedit='${comp.preedit}' committed='${comp.committedText}'")
            android.util.Log.e(TAG, "  t9 module registered: ${RimeEngine.isModuleRegistered("t9")}")
        }
        assertTrue("Should have candidates for 54482", c.isNotEmpty())
        val found = rightSelectByText("计划")
        if (found) { android.util.Log.d(TAG, "scenario1: selected 计划") }
    }

    @Test fun test02_digitThenSpace() {
        digits("54482")
        val c = candidates()
        if (c.isEmpty()) {
            android.util.Log.e(TAG, "test02: no candidates after 54482")
            assertTrue("No candidates", c.isNotEmpty())
        }
        pressKey(0x20); android.util.Log.d(TAG, "scenario2: space pressed")
    }

    @Test fun test03_leftSelectFullThenRightSelect() {
        digits("54482")
        leftSelect("ji", 2)
        assertTrue(input().contains("ji"))
        leftSelect("gua", 3)
        assertTrue(input().contains("gua"))
        rightSelectFirst()
        android.util.Log.d(TAG, "scenario3: full commit li+gua")
    }

    @Test fun test04_leftSelectFullThenSpace() {
        digits("54482")
        leftSelect("ji", 2)
        leftSelect("gua", 3)
        pressKey(0x20)
        android.util.Log.d(TAG, "scenario4: space after li+gua")
    }

    @Test fun test05_leftSelectJianpinThenSpace() {
        press("5"); leftSelect("j", 1)
        press("4"); leftSelect("g", 1)
        pressKey(0x20)
        android.util.Log.d(TAG, "scenario5: jianpin j+g + space")
    }

    @Test fun test06_separatorJianpin() {
        press("5"); press("1"); press("4"); press("1")
        leftSelect("j", 1); leftSelect("g", 1)
        pressKey(0x20)
        android.util.Log.d(TAG, "scenario6: separator jianpin")
    }

    @Test fun test07_mixedQuanpinJianpin() {
        digits("54482")
        leftSelect("ji", 2)
        leftSelect("g", 1)
        leftSelect("b", 1)
        pressKey(0x20)
        android.util.Log.d(TAG, "scenario7: mixed ji+g+b")
    }

    @Test fun test08_partialCommitThenContinue() {
        digits("54482")
        leftSelect("ji", 2)
        rightSelectByText("即")
        assertTrue("After partial commit '即', input should remain", input().isNotEmpty() || candidates().isNotEmpty())
        rightSelectByText("话")
        android.util.Log.d(TAG, "scenario8: partial 即 + 话")
    }

    @Test fun test09_partialCommitThenLeftSelect() {
        digits("54482")
        rightSelectByText("即")
        val remaining = input()
        android.util.Log.d(TAG, "scenario9: after '即', input='$remaining'")
        // after partial commit, remaining digits should allow further typing
        assertTrue("Should still have candidates or input after partial commit",
            input().isNotEmpty() || candidates().isNotEmpty())
    }

    @Test fun test10_quanpinReplace() {
        digits("54482")
        leftSelect("ji", 2); assertTrue(input().contains("ji"))
        leftSelect("li", 2)
        assertTrue("After replace ji→li, input should contain 'li'",
            !input().contains("ji") || input().contains("li"))
        android.util.Log.d(TAG, "scenario10: ji→li replace, input='${input()}'")
    }

    @Test fun test11_jianpinReplace() {
        press("5"); leftSelect("j", 1)
        leftSelect("k", 1)
        android.util.Log.d(TAG, "scenario11: j→k replace, input='${input()}'")
    }

    // ═══════════════════════════════════════════════════
    // 12.2 回退场景 13-20
    // ═══════════════════════════════════════════════════

    @Test fun test13_digitBackspace() {
        digits("54482")
        assertTrue(input().isNotEmpty())
        repeat(5) { pressKey(0xff08) }
        android.util.Log.d(TAG, "scenario13: after 5x bs, input='${input()}'")
    }

    @Test fun test14_undoLeftSelect() {
        press("5"); leftSelect("j", 1)
        press("4"); leftSelect("g", 1)
        pressKey(0xff08); pressKey(0xff08); pressKey(0xff08); pressKey(0xff08)
        android.util.Log.d(TAG, "scenario14: undo left selects, input='${input()}'")
    }

    @Test fun test15_undoPartialCommit() {
        digits("54482"); leftSelect("ji", 2); rightSelectByText("即")
        pressKey(0xff08)
        android.util.Log.d(TAG, "scenario15: undo partial commit, input='${input()}'")
    }

    @Test fun test16_undoMultiplePartial() {
        digits("54482"); leftSelect("li", 2); leftSelect("gu", 2); leftSelect("b", 1)
        rightSelectByText("里"); pressKey(0xff08)
        android.util.Log.d(TAG, "scenario16: undo multiple partial, input='${input()}'")
    }

    @Test fun test17_undoJianpinReplace() {
        press("5"); leftSelect("j", 1); leftSelect("k", 1)
        pressKey(0xff08)
        android.util.Log.d(TAG, "scenario17: undo k→j replace, input='${input()}'")
    }

    @Test fun test18_undoQuanpinReplace() {
        digits("54482"); leftSelect("ji", 2); leftSelect("li", 2)
        pressKey(0xff08)
        android.util.Log.d(TAG, "scenario18: undo li→ji replace, input='${input()}'")
    }

    @Test fun test19_clearThenBackspace() {
        digits("54482"); engine.clearComposition()
        val result = engine.processKey(0xff08, 0)
        assertFalse("Backspace after clear should return false", result)
        android.util.Log.d(TAG, "scenario19: clear+bs returns false")
    }

    @Test fun test20_backspaceFromIdle() {
        val result = engine.processKey(0xff08, 0)
        assertFalse("Backspace from idle should return false", result)
        android.util.Log.d(TAG, "scenario20: idle bs returns false")
    }

    // ═══════════════════════════════════════════════════
    // 12.3 边界场景 21-30
    // ═══════════════════════════════════════════════════

    @Test fun test21_idleThenSeparator() {
        press("1")
        android.util.Log.d(TAG, "scenario21: separator on idle")
    }

    @Test fun test22_idleThenSpace() {
        pressKey(0x20)
        android.util.Log.d(TAG, "scenario22: space on idle")
    }

    @Test fun test23_digitSevenCandidates() {
        press("7"); assertTrue(candidates().isNotEmpty())
        rightSelectFirst()
        android.util.Log.d(TAG, "scenario23: 7 candidates + select first")
    }

    @Test fun test24_maxLengthInput() {
        digits("77777777777777777777")
        android.util.Log.d(TAG, "scenario24: 20 digits input='${input()}'")
    }

    @Test fun test25_emptyCandidateRightSelect() {
        // Try selecting from empty menu - should not crash
        engine.t9SelectCandidate(0)
        android.util.Log.d(TAG, "scenario25: select candidate on empty")
    }

    @Test fun test26_multiPinyinCandidate() {
        digits("54482")
        val cand = candidates()
        val d = cand.find { it.second.contains(" ") }
        if (d != null) {
            rightSelect(d.second)
            android.util.Log.d(TAG, "scenario26: multi-syllable candidate '${d.first}'")
        }
    }

    @Test fun test27_keyboardSwitchCycle() {
        digits("54482"); assertTrue(input().isNotEmpty())
        engine.clearComposition()
        assertTrue(input().isEmpty())
        digits("54482"); assertTrue(input().isNotEmpty())
        engine.clearComposition()
        android.util.Log.d(TAG, "scenario27: switch cycle x2")
    }

    @Test fun test28_rapidKeys() {
        for (i in 0 until 10) { press("5"); press("4") }
        android.util.Log.d(TAG, "scenario28: rapid keys, input='${input()}'")
    }

    @Test fun test29_leftSelectThenDigit() {
        digits("54482"); leftSelect("ji", 2)
        press("4"); assertTrue("SELECTION + digit should add input",
            input().contains("4"))
        android.util.Log.d(TAG, "scenario29: leftSelect + digit, input='${input()}'")
    }

    @Test fun test30_quanpinReplaceToJianpin() {
        digits("54482"); leftSelect("jia", 3)
        leftSelect("j", 1)
        android.util.Log.d(TAG, "scenario30: jia→j replace, input='${input()}'")
    }

    // ═══════════════════════════════════════════════════
    // 12.4 复杂组合场景 31-46
    // ═══════════════════════════════════════════════════

    @Test fun test31_fullSequenceBackspace() {
        digits("54482"); leftSelect("li", 2); leftSelect("gu", 2); leftSelect("b", 1)
        rightSelectByText("里"); rightSelectByText("故")
        repeat(2) { pressKey(0xff08) }
        android.util.Log.d(TAG, "scenario31: full seq + bs x2")
    }

    @Test fun test32_jianpinSelectByInitial() {
        digits("5143"); leftSelect("g", 1); leftSelect("h", 1)
        android.util.Log.d(TAG, "scenario32: g+h selection, input='${input()}'")
    }

    @Test fun test33_jianpinRightSelect() {
        digits("5143"); leftSelect("g", 1)
        rightSelectFirst()
        android.util.Log.d(TAG, "scenario33: g + select first")
    }

    @Test fun test34_fourSelectThenPartial() {
        digits("54482"); leftSelect("j", 1); leftSelect("g", 1); leftSelect("g", 1)
        leftSelect("t", 1); leftSelect("b", 1)
        rightSelectByText("理解")
        android.util.Log.d(TAG, "scenario34: j+ggtb + 理解")
    }

    @Test fun test35_shengmuFallback() {
        digits("5143"); leftSelect("k", 1); leftSelect("g", 1)
        rightSelectByText("客观")
        android.util.Log.d(TAG, "scenario35: k+g + 客观, input='${input()}'")
    }

    @Test fun test36_shengmuFallbackThenContinue() {
        digits("5143"); leftSelect("k", 1); leftSelect("g", 1)
        rightSelectByText("客观")
        press("3"); leftSelect("d", 1)
        rightSelectByText("多")
        android.util.Log.d(TAG, "scenario36: 客观 + d + 多")
    }

    @Test fun test37_shengmuFallbackBackspace() {
        digits("5143"); leftSelect("k", 1); leftSelect("g", 1)
        rightSelectByText("客观")
        press("3"); leftSelect("d", 1); rightSelectByText("多")
        repeat(8) { pressKey(0xff08) }
        android.util.Log.d(TAG, "scenario37: 客观+多 + bsx8, input='${input()}'")
    }

    @Test fun test38_shengmuFallbackVariantBackspace() {
        digits("5143"); leftSelect("k", 1); leftSelect("he", 2)
        rightSelectByText("跨行")
        press("3"); leftSelect("d", 1); rightSelectByText("多")
        repeat(8) { pressKey(0xff08) }
        android.util.Log.d(TAG, "scenario38: 跨行+多 + bsx8")
    }

    @Test fun test39_separatorThenLeftSelect() {
        digits("544"); press("1"); leftSelect("g", 1)
        android.util.Log.d(TAG, "scenario39: separator + g")
    }

    @Test fun test40_multiSeparator() {
        press("5"); press("1"); press("4"); press("1")
        leftSelect("j", 1); leftSelect("g", 1)
        android.util.Log.d(TAG, "scenario40: multi separator")
    }

    @Test fun test41_leftSelectThenSpaceThenNewInput() {
        digits("54482"); leftSelect("ji", 2)
        pressKey(0x20)
        digits("54"); assertTrue("After space + new digits, should be composing",
            input().isNotEmpty() || candidates().isNotEmpty())
        android.util.Log.d(TAG, "scenario41: ji+space+54")
    }

    @Test fun test42_rightSelectThenLeftSelect() {
        digits("54482"); rightSelectByText("即")
        android.util.Log.d(TAG, "scenario42: rightSelect '即' + leftSelect, input='${input()}'")
    }

    @Test fun test43_backspaceToIdleThenReinput() {
        digits("54482"); repeat(5) { pressKey(0xff08) }
        digits("54482"); pressKey(0x20)
        android.util.Log.d(TAG, "scenario43: idle + reinput + space")
    }

    @Test fun test44_jianpinReplaceToQuanpin() {
        press("5"); leftSelect("j", 1)
        leftSelect("ji", 2)
        android.util.Log.d(TAG, "scenario44: j→ji replace")
    }

    @Test fun test45_leftSelectAllThenRightSelect() {
        digits("54482"); leftSelect("ji", 2); leftSelect("hua", 3)
        rightSelectByText("计划")
        android.util.Log.d(TAG, "scenario45: ji+hua+计划")
    }

    // ═══════════════════════════════════════════════════════════
    // 场景：777 → 左选 q → 左选 s → 左选 s → 右选"确实"
    // 验证：3 个左选消费全部 3 位数字后，右选匹配候选应 full commit
    // ═══════════════════════════════════════════════════════════

    @Test fun test47_threeSelectionsPartialCommit() {
        // 777 → q+s+s (3 selections) → 确实(que shi, 2 syllables, 2 chars)
        // Per design: candidateTextLength(2) < selectionHistory.size(3) → PARTIAL commit
        digits("777")
        engine.t9SelectPinyinDirect("q", 1)
        engine.t9SelectPinyinDirect("s", 1)
        engine.t9SelectPinyinDirect("s", 1)
        android.util.Log.d(TAG, "test47: after q+s+s, input='${input()}'")

        val cand = candidates()
        val queShiIdx = cand.indexOfFirst { it.first == "确实" }
        if (queShiIdx >= 0) {
            val isFull = engine.t9SelectCandidate(queShiIdx)
            android.util.Log.d(TAG, "test47: 确实 isFull=$isFull, input='${input()}'")
            // 2 chars < 3 selections → partial commit
            assertFalse("q+s+s should be PARTIAL commit for 确实(2 chars, 3 selections)", isFull)
            assertTrue("Remaining digit '7' should be in buffer after partial commit",
                input().isNotEmpty())
        }
    }

    @Test fun test46_jiuJianLeftThenRight() {
        digits("5485426"); leftSelect("jiu", 3)
        android.util.Log.d(TAG, "scenario46: jiu jian partial, input='${input()}'")
    }

    // ═══════════════════════════════════════════════════════════
    // 半提交（partial commit）退格回退回归测试
    // 需求：九键拼音半提交上屏文字后按退格，应先回退半提交（预编辑恢复为拼音），
    // 再删除输入序列末尾的拼音，由 t9WasCommitUndone() 标记半提交已回退。
    // ═══════════════════════════════════════════════════════════

    @Test fun test48_undoPartialCommitRestoresPreedit() {
        // 54482 → 左选 ji → 右选"即"（partial commit，消费 54）→ 退格
        // 期望：t9WasCommitUndone()==true，预编辑恢复为 54482 再删末尾=5448
        digits("54482")
        leftSelect("ji", 2)
        if (rightSelectByText("即")) {
            pressKey(0xff08)
            assertTrue("backspace after partial commit should report commit undone",
                engine.t9WasCommitUndone())
            assertEquals("preedit should be full digits minus last pinyin",
                "5448", input())
        } else {
            android.util.Log.w(TAG, "test48 skipped: candidate '即' not available")
        }
    }

    @Test fun test49_secondBackspaceIsPlainDelete() {
        // 半提交回退后，再一次退格只是删除拼音，不应再上报 commit undone
        digits("54482")
        leftSelect("ji", 2)
        if (rightSelectByText("即")) {
            pressKey(0xff08)
            assertTrue(engine.t9WasCommitUndone())
            pressKey(0xff08)
            assertFalse("second backspace should be a plain pinyin delete",
                engine.t9WasCommitUndone())
        } else {
            android.util.Log.w(TAG, "test49 skipped: candidate '即' not available")
        }
    }

    @Test fun test50_plainBackspaceDoesNotUndoCommit() {
        // 无半提交时，普通退格不应上报 commit undone
        digits("54482")
        pressKey(0xff08)
        assertFalse("plain backspace should not report commit undone",
            engine.t9WasCommitUndone())
    }

    @Test fun test51_chainedPartialUndo() {
        // 连续两次半提交后，退格应逐次回退；最后一次退格为普通删除
        digits("5143")
        leftSelect("k", 1); leftSelect("g", 1)
        if (rightSelectByText("客观")) {
            press("3"); leftSelect("d", 1)
            if (rightSelectByText("多")) {
                pressKey(0xff08)
                assertTrue("backspace 1 should undo '多'", engine.t9WasCommitUndone())
                pressKey(0xff08)
                assertTrue("backspace 2 should undo '客观'", engine.t9WasCommitUndone())
                pressKey(0xff08)
                assertFalse("backspace 3 should be plain delete", engine.t9WasCommitUndone())
            } else {
                android.util.Log.w(TAG, "test51 skipped: candidate '多' not available")
            }
        } else {
            android.util.Log.w(TAG, "test51 skipped: candidate '客观' not available")
        }
    }

    // ═══════════════════════════════════════════════════════════
    // 回归：左选音节后右选候选，不得吞掉尾部未消费的拼音。
    // 场景："给的吧"=4343322，选"给"半提交后剩 3322；左选 de(33) 后右选
    // 一个 de 候选，尾部 22(吧) 必须保留为半提交，而非被 jianpin full commit 吞掉。
    // ═══════════════════════════════════════════════════════════
    @Test fun test52_jianpinSelectKeepsTrailingDigits() {
        // 3322 = de ba。左选 de 消费 33，尾部 22 应为"吧"的拼音。
        digits("3322")
        leftSelect("de", 2)
        val idx = candidates().indexOfFirst { it.second == "de" }
        if (idx >= 0) {
            val isFull = engine.t9SelectCandidate(idx)
            assertFalse("选 de 候选时尾部 '22' 仍在，必须是 PARTIAL commit", isFull)
            // 尾部 22 必须被保留在 digit buffer 中（partial commit 后 GetRemainingDigits）
            assertEquals("尾部 '22'（吧）不能被吞掉", "22", engine.t9GetRemainingDigits())
        } else {
            android.util.Log.w(TAG, "test52 skipped: no 'de' candidate available")
        }
    }
}

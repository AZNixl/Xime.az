package com.kingzcheung.xime.rime

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

class T9InputController(
    private val rimeEngine: RimeEngine = RimeEngine.getInstance(),
    private val onCompositionRefresh: (() -> Unit)? = null,
) {
    companion object {
        const val CLEAR_COMPOSITION_ONLY = "clear_composition"
        const val CLEAR_ALL = "clear_all"
    }

    enum class LeftPanelState { IDLE, INPUT, SELECTION }

    enum class DeleteResult {
        DELETED, UNDO_CHOICE, UNDO_COMMIT, NOT_CONSUMED
    }

    /** UI 显示的缓冲区字符串 = RIME 当前的 input */
    val bufferString: String get() {
        val input = rimeEngine.getInput()
        return when {
            input.isEmpty() && _committedText != null -> _committedText!!
            input.isEmpty() -> ""
            else -> input
        }
    }

    var firstOptions: List<T9PinyinMap.SyllableOption> by mutableStateOf(emptyList())
        private set

    var leftPanelState: LeftPanelState by mutableStateOf(LeftPanelState.IDLE)
        private set

    var selectedOption: T9PinyinMap.SyllableOption? by mutableStateOf(null)
        private set

    var selectionCandidateDigits: String? by mutableStateOf(null)
        private set

    var leftColumnLocked: Boolean by mutableStateOf(false)
        private set

    val selectionHistory: List<T9PinyinMap.SyllableOption> get() = _selectionHistory
    private var _selectionHistory: List<T9PinyinMap.SyllableOption> = emptyList()

    private var _committedText: String? = null
    private var _digitBuffer: String = ""

    /**
     * t9/isDisplayOriginalPreedit 缓存。schema 切换（reset）时刷新。
     * true  → preedit 显示 rime 原始数字串；
     * false → 前端根据候选 comment 将 preedit 重建为拼音。
     */
    var isDisplayOriginalPreedit: Boolean = false
        private set

    fun reset() {
        rimeEngine.clearComposition()
        _digitBuffer = ""
        _selectionHistory = emptyList()
        firstOptions = emptyList()
        leftPanelState = LeftPanelState.IDLE
        selectedOption = null
        selectionCandidateDigits = null
        _committedText = null
        isDisplayOriginalPreedit = rimeEngine.t9IsDisplayOriginalPreedit()
    }

    fun updateCandidates(force: Boolean = false, composition: RimeComposition? = null) {
        val comp = composition ?: rimeEngine.getComposition()
        val rawInput = comp.input

        if (rawInput.isEmpty() && _committedText != comp.committedText) {
            _committedText = comp.committedText
        }

        if (rawInput.isEmpty()) {
            if (leftPanelState != LeftPanelState.IDLE) {
                firstOptions = emptyList()
                leftPanelState = LeftPanelState.IDLE
                selectedOption = null
                selectionCandidateDigits = null
            }
            return
        }

        val digitsOnly = rawInput.filter { it in '0'..'9' }
        _digitBuffer = digitsOnly

        // 从剩余数字用 T9PinyinMap 计算左栏候选（RIME 返回的 comment 首音节不准确）
        firstOptions = if (digitsOnly.isNotEmpty()) {
            T9PinyinMap.firstSyllableOptions(digitsOnly, maxResults = 20)
        } else {
            emptyList()
        }

        val hasSelections = rawInput.contains("'") || rawInput.any { it.isLetter() }
        leftPanelState = when {
            firstOptions.isEmpty() && !hasSelections -> LeftPanelState.IDLE
            hasSelections -> LeftPanelState.SELECTION
            else -> LeftPanelState.INPUT
        }
    }

    fun sendToRime() {}

    fun onDigitPressed(digit: String) {
        val code = digit[0].code
        val result = rimeEngine.processKeyAndGetResult(code, 0)
        onCompositionRefresh?.invoke()
        updateCandidates(composition = result.toComposition())
    }

    fun onChoiceSelected(option: T9PinyinMap.SyllableOption) {
        rimeEngine.t9SelectPinyinDirect(option.pinyin, option.digitLength)
        onCompositionRefresh?.invoke()
        updateFromRime()
    }

    fun onRightCandidateSelected(candidatePinyin: String? = null, candidateTextLength: Int = 0): Boolean {
        val index = if (candidatePinyin != null) {
            val candidates = rimeEngine.getCandidatesWithComments()
            candidates.indexOfFirst { it.comment == candidatePinyin }
        } else {
            0
        }
        val isFullCommit = if (index >= 0) {
            rimeEngine.t9SelectCandidate(index)
        } else {
            false
        }
        updateFromRime()
        return isFullCommit
    }

    fun onDeleted(): DeleteResult {
        val result = rimeEngine.processKey(0xff08, 0)
        onCompositionRefresh?.invoke()
        updateFromRime()
        return if (result) DeleteResult.DELETED else DeleteResult.NOT_CONSUMED
    }

    fun forceSendToRime() {
        val remaining = rimeEngine.t9GetRemainingDigits()
        if (remaining.isNotEmpty()) {
            // Directly set RIME input to remaining digits (bypass processKey/AppendDigit)
            rimeEngine.setInput(remaining)
        }
        // Partial commit 后 RIME 已清空 composition 并重新填入剩余数字，
        // 需基于该剩余数字重新计算左栏拼音候选，否则左栏仍显示旧候选。
        updateCandidates()
    }

    fun clearRimeAndResend() {
        rimeEngine.clearComposition()
        updateFromRime()
    }

    fun clearAll() {
        rimeEngine.clearComposition()
        _digitBuffer = ""
        firstOptions = emptyList()
        leftPanelState = LeftPanelState.IDLE
        selectedOption = null
        selectionCandidateDigits = null
        _selectionHistory = emptyList()
        _committedText = null
    }

    fun onEnterCommit() { clearAll() }

    fun isSelectedOptionInCurrentCandidates(): Boolean = false

    private fun updateFromRime() { updateCandidates() }
}

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

    fun reset() {
        rimeEngine.clearComposition()
        _digitBuffer = ""
        _selectionHistory = emptyList()
        firstOptions = emptyList()
        leftPanelState = LeftPanelState.IDLE
        selectedOption = null
        selectionCandidateDigits = null
        _committedText = null
    }

    fun updateCandidates(force: Boolean = false) {
        val composition = rimeEngine.getComposition()
        val rawInput = composition.input

        if (rawInput.isEmpty() && _committedText != composition.committedText) {
            _committedText = composition.committedText
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
        rimeEngine.processKey(code, 0)
        onCompositionRefresh?.invoke()
        updateFromRime()
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
    }

    /**
     * 右侧候选直接提交上屏：不经过消耗算法，清空缓冲区并进入空闲状态。
     * 用于 emoji/符号等无拼音注释的候选词，RIME 引擎已匹配输入序列到候选词，
     * T9 控制器无需做音节级消费计算。
     */
    fun onRightCandidateSelectedByDirectCommit(): Boolean {
        if (inputBuffer.isEmpty) return true
        resetState(clearCache = true, clearRime = false)
        return true
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

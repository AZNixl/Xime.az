package com.kingzcheung.xime.rime

import com.kingzcheung.xime.util.PreeditMergeHelper

/** T9 模式下 UI 展示状态：包含展示文本、候选列表和 composing 标志 */
data class T9DisplayState(
    val displayText: String,
    val displayCandidates: List<String>,
    val displayComments: List<String>,
    val isComposing: Boolean,
)

/**
 * 构建 T9 模式下的 UI 展示状态。
 *
 * 三种情形：
 * 1. 无 partial commit：优先 preedit（t9_preedit.lua 处理后的拼音），回退 input
 * 2. RightCommit 展示态（有 partial + preedit 为空）：
 *    displayText=partialTexts 拼接，候选列表=最近一次已提交文本
 * 3. 常规：mergePartialCommitText 合并
 */
fun buildT9DisplayState(
    partialTexts: List<String>,
    preeditText: String,
    inputText: String,
    candidates: List<String>,
    comments: List<String>,
): T9DisplayState {
    if (partialTexts.isEmpty()) {
        val text = if (preeditText.isNotEmpty()) preeditText else inputText
        return T9DisplayState(
            displayText = text,
            displayCandidates = candidates,
            displayComments = comments,
            isComposing = inputText.isNotEmpty(),
        )
    }
    // RightCommit 展示态：preedit 为空（composition 已清除），input 可能为残留值
    if (preeditText.isEmpty()) {
        val last = partialTexts.last()
        return T9DisplayState(
            displayText = partialTexts.joinToString(""),
            displayCandidates = listOf(last),
            displayComments = comments.firstOrNull()?.let { listOf(it) } ?: emptyList(),
            isComposing = true,
        )
    }
    return T9DisplayState(
        displayText = PreeditMergeHelper.mergePartialCommitText(partialTexts, preeditText),
        displayCandidates = candidates,
        displayComments = comments,
        isComposing = inputText.isNotEmpty() || partialTexts.isNotEmpty(),
    )
}

/** 候选词与选择历史的匹配级别（对齐 main 的 T9BufferManager.MatchLevel） */
enum class MatchLevel { FULL, PREFIX, NONE }

/**
 * 根据左侧选择历史过滤候选词，分两层返回：全匹配 + 前缀匹配。
 * FULL 在前、PREFIX 在后、NONE 排除，确保精确匹配优先，同时不丢失单字候选。
 * 与 main 的 filterCandidatesBySelectionHistory 一致。
 */
fun filterCandidatesBySelectionHistory(
    candidates: List<String>,
    comments: List<String>,
    selectionHistory: List<T9PinyinMap.SyllableOption>,
): Pair<List<String>, List<String>> {
    if (selectionHistory.isEmpty()) return candidates to comments

    val selCodes = selectionHistory.map { T9PinyinMap.pinyinToDigitCode(it.pinyin) }
    val fullTexts = mutableListOf<String>()
    val fullComments = mutableListOf<String>()
    val prefixTexts = mutableListOf<String>()
    val prefixComments = mutableListOf<String>()

    for (i in candidates.indices) {
        val comment = comments.getOrElse(i) { "" }
        when (matchCandidateComment(comment, selectionHistory, selCodes)) {
            MatchLevel.FULL -> {
                fullTexts.add(candidates[i])
                fullComments.add(comment)
            }
            MatchLevel.PREFIX -> {
                prefixTexts.add(candidates[i])
                prefixComments.add(comment)
            }
            MatchLevel.NONE -> { /* 排除 */ }
        }
    }

    val resultTexts = fullTexts + prefixTexts
    val resultComments = fullComments + prefixComments
    if (resultTexts.isEmpty()) return candidates to comments
    return resultTexts to resultComments
}

/**
 * 判断候选词的拼音注释与选择历史的匹配级别（逐音节对齐校验）。
 * 全拼要求数字码完全相等（允许 he 匹配 ge，因为同码 43），
 * 简拼只求数字码前缀匹配。
 */
private fun matchCandidateComment(
    comment: String,
    selectionHistory: List<T9PinyinMap.SyllableOption>,
    selCodes: List<String?>,
): MatchLevel {
    val syllables = comment.trim()
        .split("[\\s']+".toRegex())
        .filter { it.any { c -> c.isLetter() } }
    if (syllables.isEmpty()) return MatchLevel.FULL

    var syllableIdx = 0
    var matchedCount = 0
    for (idx in selectionHistory.indices) {
        val sel = selectionHistory[idx]
        val selCode = selCodes[idx] ?: return MatchLevel.FULL
        var matched = false
        while (syllableIdx < syllables.size) {
            val sylCode = T9PinyinMap.pinyinToDigitCode(syllables[syllableIdx])
                ?: run { syllableIdx++; continue }
            if (sylCode.startsWith(selCode)) {
                if (sel.pinyin.length > 1 && sylCode != selCode) return MatchLevel.NONE
                syllableIdx++
                matched = true
                break
            }
            syllableIdx++
        }
        if (matched) {
            matchedCount++
        } else {
            return if (matchedCount > 0) MatchLevel.PREFIX else MatchLevel.NONE
        }
    }
    return MatchLevel.FULL
}

/**
 * UI 候选列表经 filterCandidatesBySelectionHistory 过滤/重排后，其 index 可能与
 * RIME 原始候选 index 不对应。通过候选词文本反查 RIME 真实 index，避免上屏错词。
 * 与 main 的 resolveRimeCandidateIndex 一致。
 */
fun resolveRimeCandidateIndex(
    uiIndex: Int,
    selectedCandidate: String?,
    rawCandidates: List<String>,
): Int {
    if (selectedCandidate == null) return uiIndex
    val rawIdx = rawCandidates.indexOf(selectedCandidate)
    return if (rawIdx >= 0) rawIdx else uiIndex
}
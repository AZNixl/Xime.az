package com.kingzcheung.xime.rime

/**
 * 将 T9 九键的 preedit 从数字序列转换为拼音显示（替代 lua_filter@*t9_preedit）。
 *
 * 例如 "54482" + comment "ji gua" → "jigua"
 * "ji'43" + comment "ji kan" → "ji k"
 * "5" + comment "le" → "l"
 */
fun convertT9PreeditToPinyin(preedit: String, firstCandidateComment: String): String {
    if (preedit.isEmpty()) return preedit
    if (firstCandidateComment.isEmpty()) {
        // RIME 未提供拼音注释时，用 T9PinyinMap 从数字推断最可能的拼音，
        // 避免 preedit 直接显示数字（如 88 → tu）。
        return inferPinyinFromDigits(preedit)
    }

    val pinyinParts = firstCandidateComment.split(Regex("\\s+")).filter { it.isNotEmpty() }
    if (pinyinParts.isEmpty()) return preedit

    val hasDigit = preedit.any { it in '0'..'9' }
    if (!hasDigit) return preedit

    // 按字符类型拆分：分隔符、中文、非中文（数字/字母）各自独立成段
    // 避免 partial commit 后 "公民7"（中文+数字无分隔符）被当成一整段
    val inputParts = mutableListOf<String>()
    val buf = StringBuilder()
    var bufIsChinese: Boolean? = null

    fun flushBuf() {
        if (buf.isNotEmpty()) {
            inputParts.add(buf.toString())
            buf.clear()
            bufIsChinese = null
        }
    }

    for (char in preedit) {
        if (char == ' ' || char == '\'') {
            flushBuf()
            inputParts.add(char.toString())
        } else {
            val isChinese = char >= '\u4E00' && char <= '\u9FFF'
            if (bufIsChinese != null && bufIsChinese != isChinese) {
                flushBuf()
            }
            buf.append(char)
            bufIsChinese = isChinese
        }
    }
    flushBuf()

    var pi = 0
    for (i in inputParts.indices) {
        val part = inputParts[i]
        when {
            part == " " || part == "'" -> {
                inputParts[i] = " "
            }
            part.all { it in '0'..'9' } -> {
                if (pi < pinyinParts.size) {
                    val py = pinyinParts[pi]
                    when {
                        i == inputParts.lastIndex && part.length == 1 -> {
                            val prefix = py.take(2).lowercase()
                            inputParts[i] = if (prefix in listOf("zh", "ch", "sh")) prefix
                                else py.first().lowercase().toString()
                        }
                        inputParts.size == 1 && pinyinParts.size > 1 -> {
                            inputParts[i] = pinyinParts.joinToString("")
                        }
                        else -> inputParts[i] = py.lowercase()
                    }
                    pi++
                }
            }
            part.any { it >= '\u4E00' && it <= '\u9FFF' } -> {
                // 中文 = 已提交文本，原样保留，不消耗拼音索引
            }
            else -> pi++
        }
    }
    return inputParts.joinToString("")
}

/**
 * RIME 未提供候选拼音注释时，用 T9PinyinMap 从数字序列推断拼音显示。
 *
 * 逐段处理：中文段原样保留，纯数字段用 [T9PinyinMap.firstSyllableOptions]
 * 取最可能的拼音；已确认的字母段（含 '）原样保留。
 * 例如 "88" → "tu"，"ji'482" → "ji'hua"。
 */
fun inferPinyinFromDigits(preedit: String): String {
    if (preedit.isEmpty()) return preedit
    val hasDigit = preedit.any { it in '0'..'9' }
    if (!hasDigit) return preedit

    // 按字符类型分段：分隔符、中文、数字、字母各自成段
    val parts = mutableListOf<String>()
    val buf = StringBuilder()
    var bufKind: Char? = null

    fun flush() {
        if (buf.isNotEmpty()) {
            parts.add(buf.toString())
            buf.clear()
        }
        bufKind = null
    }

    for (char in preedit) {
        val kind = when {
            char == ' ' || char == '\'' -> 's'
            char >= '\u4E00' && char <= '\u9FFF' -> 'c'
            char in '0'..'9' -> 'd'
            else -> 'l'
        }
        if (bufKind != null && bufKind != kind) flush()
        buf.append(char)
        bufKind = kind
    }
    flush()

    val result = StringBuilder()
    for (part in parts) {
        when {
            part == " " || part == "'" -> result.append(part)
            part.all { it in '0'..'9' } -> {
                // 数字段：贪心切分为多个拼音，取最长匹配
                var rest = part
                var i = 0
                while (i < rest.length) {
                    val options = T9PinyinMap.firstSyllableOptions(rest.substring(i), maxResults = 1)
                    if (options.isEmpty()) {
                        // 无匹配，保留剩余数字
                        result.append(rest.substring(i))
                        break
                    }
                    val opt = options.first()
                    result.append(opt.pinyin)
                    i += opt.digitLength
                }
            }
            else -> result.append(part)
        }
    }
    return result.toString()
}

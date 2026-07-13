package com.kingzcheung.xime.rime

/**
 * 将 T9 九键的 preedit 从数字序列转换为拼音显示（替代 lua_filter@*t9_preedit）。
 *
 * 例如 "54482" + comment "ji gua" → "jigua"
 * "ji'43" + comment "ji kan" → "ji k"
 * "5" + comment "le" → "l"
 */
fun convertT9PreeditToPinyin(preedit: String, firstCandidateComment: String): String {
    if (preedit.isEmpty() || firstCandidateComment.isEmpty()) return preedit

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

package com.kingzcheung.xime.settings

import android.content.Context
import android.util.Log
import com.charleskorn.kaml.YamlList
import com.charleskorn.kaml.YamlMap
import com.charleskorn.kaml.YamlScalar
import kotlinx.coroutines.sync.withLock
import java.io.File

object PersonalDictManager {
    private const val TAG = "PersonalDictManager"
    private const val CUSTOM_PHRASE_FILE = "custom_phrase.txt"

    private const val DEFAULT_HEADER = """# Rime dict
---
name: %s
version: '1.0'
sort: original
use_preset_vocabulary: false
...
"""

    private fun packName(rimeDir: File, schemaId: String): String {
        // 方案自带 translator.packs 声明（如 pinyin_simp 自带 [user_simp]）时，
        // 个人词库沿用方案声明的名字，否则 librime 编译时找不到对应的 pack 源文件。
        val own = readSchemaPacks(rimeDir, schemaId)
        if (own.isNotEmpty()) return own.first()
        val dictName = schemaDictionaryName(rimeDir, schemaId)
        return "user_$dictName"
    }

    /** 从 schema 文件读取 translator.packs 中声明的 user_* 个人词库名，没有则返回空。 */
    internal fun readSchemaPacks(rimeDir: java.io.File, schemaId: String): List<String> {
        val schemaFile = java.io.File(rimeDir, "${schemaId}.schema.yaml")
        if (!schemaFile.exists()) return emptyList()
        val text = try { schemaFile.readText(Charsets.UTF_8).trimStart('\uFEFF') } catch (_: Exception) { return emptyList() }
        // kaml 解析（schema.yaml 是 YAML，避免脆弱的正则）；
        // 第三方方案的 .schema.yaml 可能含锚点/别名等非严格语法导致解析失败，此时回退正则。
        val viaKaml = readSchemaPacksKaml(text)
        if (viaKaml != null) return viaKaml
        return readSchemaPacksRegex(text)
    }

    private fun readSchemaPacksKaml(text: String): List<String>? {
        return try {
            val root = SchemaManager.yaml.parseToYamlNode(text) as? YamlMap ?: return emptyList()
            val packs = (root["translator"] as? YamlMap)?.get("packs") as? YamlList ?: return emptyList()
            packs.items.mapNotNull { (it as? YamlScalar)?.content }
                .map { it.trim().removeSurrounding("\"") }
                .filter { it.startsWith("user_") }
        } catch (_: Exception) {
            null
        }
    }

    private fun readSchemaPacksRegex(text: String): List<String> {
        return try {
            val block = Regex("""^translator:[\s\S]*?^  packs:\s*\n([\s\S]*?)(?=^\S|\Z)""", RegexOption.MULTILINE)
                .find(text)?.groupValues?.get(1) ?: return emptyList()
            Regex("""^\s*-\s*"?(\w+)"?""", RegexOption.MULTILINE).findAll(block)
                .map { it.groupValues[1] }
                .filter { it.startsWith("user_") }
                .toList()
        } catch (_: Exception) {
            emptyList()
        }
    }

    private fun packHeader(rimeDir: File, schemaId: String) = DEFAULT_HEADER.format(packName(rimeDir, schemaId))

    fun getPackFile(context: Context, schemaId: String): File {
        val rimeDir = SchemaManager.getRimeDir(context)
        return File(rimeDir, "${packName(rimeDir, schemaId)}.dict.yaml")
    }

    fun getCustomPhraseFile(context: Context, schemaId: String? = null): File {
        val rimeDir = SchemaManager.getRimeDir(context)
        val dictName = if (schemaId != null) getCustomPhraseDictName(rimeDir, schemaId)
                       else CUSTOM_PHRASE_FILE.removeSuffix(".txt")
        return File(rimeDir, "$dictName.txt")
    }

    /**
     * 从方案的 .custom.yaml 中读取 `custom_phrase.user_dict` 定义的文件名。
     * 例如 `user_dict: custom_phrase_double` → 对应 `custom_phrase_double.txt`。
     * 若无声明则返回默认的 `custom_phrase`。
     */
    internal fun getCustomPhraseDictName(rimeDir: File, schemaId: String): String {
        val customText = File(rimeDir, "${schemaId}.custom.yaml")
            .takeIf { it.exists() }
            ?.readText(Charsets.UTF_8)
        if (customText != null) {
            val fromCustom = parseCustomPhraseDictName(customText)
            if (fromCustom != null) return fromCustom
        }
        val schemaText = File(rimeDir, "${schemaId}.schema.yaml")
            .takeIf { it.exists() }
            ?.readText(Charsets.UTF_8)
        if (schemaText != null) {
            val fromSchema = parseCustomPhraseDictName(schemaText)
            if (fromSchema != null) return fromSchema
        }
        return CUSTOM_PHRASE_FILE.removeSuffix(".txt")
    }

    /** 从文本中解析 `custom_phrase.user_dict` 声明的文件名，没有则返回 null。 */
    private fun parseCustomPhraseDictName(text: String): String? {        for (cpKey in listOf("\"custom_phrase\"", "'custom_phrase'", "custom_phrase:")) {
            val idx = text.indexOf(cpKey)
            if (idx < 0) continue
            val after = text.substring(idx)
            val udIdx = after.indexOf("user_dict")
            if (udIdx < 0) continue
            val line = after.substring(udIdx).lineSequence().firstOrNull() ?: continue
            val value = line.substringAfter(":").trim().substringBefore(" #").substringBefore("\n")
            if (value.isNotBlank()) return value
        }
        return null
    }

    
    fun ensureCustomPhraseFileExists(context: Context, schemaId: String? = null) {
        val file = getCustomPhraseFile(context, schemaId)
        if (!file.exists()) {
            file.parentFile?.mkdirs()
            file.writeText("""# Rime table
# coding: utf-8
#@/db_name	custom_phrase
#@/db_type	tabledb
#
""", Charsets.UTF_8)
        }
    }

    fun loadCustomPhrases(context: Context, schemaId: String? = null): List<DictEntry> {
        val file = getCustomPhraseFile(context, schemaId)
        if (!file.exists()) return emptyList()
        return try {
            parseStableDbEntries(file.readText(Charsets.UTF_8))
        } catch (_: Exception) { emptyList() }
    }

    fun saveCustomPhrases(context: Context, schemaId: String? = null, entries: List<DictEntry>) {
        val file = getCustomPhraseFile(context, schemaId)
        file.parentFile?.mkdirs()
        file.writeText(buildStableDbText(STABLEDB_HEADER, entries), Charsets.UTF_8)
    }

    /** 清理 custom_phrase 文件（仅当文件为空时删除，避免丢失用户数据）。 */
    fun removeCustomPhraseFile(context: Context, schemaId: String? = null) {
        val file = getCustomPhraseFile(context, schemaId)
        if (file.exists() && file.readText(Charsets.UTF_8).lineSequence().count { it.isNotBlank() && !it.startsWith('#') } == 0) {
            file.delete()
        }
    }

    // ── 方案配置补丁 ──

    private val schemaPacksMutex = kotlinx.coroutines.sync.Mutex()

    suspend fun ensureSchemaPacks(context: Context) {
        schemaPacksMutex.withLock {
            val rimeDir = SchemaManager.getRimeDir(context)
            val enabledSchemas = SchemaManager.getEnabledSchemas(context)
            for (schemaId in enabledSchemas) {
                ensureSchemaPackInner(rimeDir, context, schemaId)
            }
        }
    }

    suspend fun ensureSchemaPack(context: Context, schemaId: String) {
        schemaPacksMutex.withLock {
            val rimeDir = SchemaManager.getRimeDir(context)
            ensureSchemaPackInner(rimeDir, context, schemaId)
        }
    }

    private suspend fun ensureSchemaPackInner(rimeDir: java.io.File, context: Context, schemaId: String) {
        val schemaFile = java.io.File(rimeDir, "${schemaId}.schema.yaml")
        if (!schemaFile.exists()) return
        // handwriting 手写识别方案不经过 librime 词典输入，跳过个人词库合并。
        if (schemaId == "handwriting") return
        // 确保个人词库空白文件存在，供 applyPackConfig / applyMergedDictConfig 引用
        val packFile = getPackFile(context, schemaId)
        if (!packFile.exists()) {
            val legacy = legacyPackFile(rimeDir, schemaId)
            if (legacy != null) {
                legacy.renameTo(packFile)
            } else {
                packFile.parentFile?.mkdirs()
                packFile.writeText(packHeader(rimeDir, schemaId), Charsets.UTF_8)
            }
        }
        if (hasReverseLookupTranslator(rimeDir, schemaId) && !hasTableTranslator(rimeDir, schemaId)) {
            // 纯反查方案（如反查拼音），无主翻译器，不需要个人词库
        } else if (hasSpellerAlgebra(rimeDir, schemaId)) {
            applyPackConfig(rimeDir, schemaId)
        } else {
            applyMergedDictConfig(rimeDir, schemaId)
        }
        val dictName = getCustomPhraseDictName(rimeDir, schemaId)
        val phraseFile = File(rimeDir, "$dictName.txt")
        if (!phraseFile.exists()) {
            phraseFile.parentFile?.mkdirs()
            phraseFile.writeText("""# Rime table
# coding: utf-8
#@/db_name	custom_phrase
#@/db_type	tabledb
#
""", Charsets.UTF_8)
        }
        // 有实际条目时才注入翻译器，避免 Rime 因空 .table.bin 报错
        if (phraseFile.readText(Charsets.UTF_8).lineSequence().count { it.isNotBlank() && !it.startsWith('#') } > 0) {
            applyCustomPhraseTranslator(rimeDir, schemaId, dictName)
        }
    }

    internal fun hasReverseLookupTranslator(rimeDir: java.io.File, schemaId: String): Boolean {
        val text = try {
            java.io.File(rimeDir, "${schemaId}.schema.yaml").readText(Charsets.UTF_8)
        } catch (_: Exception) { return false }
        return text.contains("reverse_lookup_translator")
    }

    internal fun hasTableTranslator(rimeDir: java.io.File, schemaId: String): Boolean {
        val text = try {
            java.io.File(rimeDir, "${schemaId}.schema.yaml").readText(Charsets.UTF_8)
        } catch (_: Exception) { return false }
        return text.contains("table_translator")
    }

    // 为方案添加 custom_phrase 翻译器（独立于主词典音节表）
    internal fun applyCustomPhraseTranslator(rimeDir: java.io.File, schemaId: String, dictName: String) {
        val customFile = java.io.File(rimeDir, "${schemaId}.custom.yaml")
        if (customFile.exists()) {
            val text = customFile.readText(Charsets.UTF_8)
            if (text.contains("table_translator@custom_phrase")) return
        }
        insertUnderPatch(customFile, """  "engine/translators/+":
    - table_translator@custom_phrase
  "custom_phrase":
    dictionary: ""
    user_dict: $dictName
    db_class: stabledb
    enable_completion: false
    enable_sentence: false
    initial_quality: 99
""")
    }

    internal fun hasSpellerAlgebra(rimeDir: java.io.File, schemaId: String): Boolean {
        return hasSpellerAlgebraFromFile(java.io.File(rimeDir, "${schemaId}.schema.yaml"))
    }

    private fun hasSpellerAlgebraFromFile(schemaFile: java.io.File): Boolean {
        val text = schemaFile.readText(Charsets.UTF_8).replace("\r\n", "\n")
        val idx = text.indexOf("\n  algebra:\n")
        if (idx < 0) return false
        val section = text.substring(idx, text.indexOf("\n\n", idx).let { if (it < 0) text.length else it })
        return section.contains("- ")
    }

    /** 在 YAML 的 `patch:` 块下增量插入内容，不覆盖已有配置。 */
    private fun insertUnderPatch(file: java.io.File, content: String) {
        if (!file.exists()) {
            file.writeText("patch:\n$content", Charsets.UTF_8)
            return
        }
        val text = file.readText(Charsets.UTF_8)
        val cleaned = text.trimEnd('\n', '\r', ' ').removeSuffix("...").trimEnd()
        val patchLine = Regex("^patch:", RegexOption.MULTILINE).find(cleaned)
        if (patchLine != null) {
            val at = patchLine.range.last + 1
            file.writeText(cleaned.substring(0, at) + "\n$content" + cleaned.substring(at) + "\n", Charsets.UTF_8)
        } else {
            file.writeText("$cleaned\n\npatch:\n$content", Charsets.UTF_8)
        }
    }

    // 方案有固定音节表：translator/packs via .custom.yaml
    internal fun applyPackConfig(rimeDir: java.io.File, schemaId: String) {
        val pkName = packName(rimeDir, schemaId)
        val customFile = java.io.File(rimeDir, "${schemaId}.custom.yaml")
        // 方案自带 translator.packs 声明（如 pinyin_simp 自带 [user_simp]）时，
        // 个人词库文件已按该声明命名（packName 优先采用它），无需再写 patch；
        // 仅清理本代码旧版本写入的 translator/packs patch，避免覆盖方案自带声明。
        if (readSchemaPacks(rimeDir, schemaId).isNotEmpty()) {
            if (customFile.exists() && extractOwnPatchKey(customFile.readText(Charsets.UTF_8), "translator/packs") != null) {
                val cleaned = removePatchKey(customFile.readText(Charsets.UTF_8), "translator/packs")
                customFile.writeText(cleaned, Charsets.UTF_8)
            }
            return
        }
        val entry = "  \"translator/packs\": [\"$pkName\"]\n"
        if (customFile.exists()) {
            val text = customFile.readText(Charsets.UTF_8)
            if (text.contains("\"$pkName\"")) return
            val existing = extractOwnPatchKey(text, "translator/packs")
            if (existing != null) {
                // 之前由本代码写入的旧名字 → 替换
                val cleaned = removePatchKey(text, "translator/packs")
                customFile.writeText(cleaned, Charsets.UTF_8)
            } else if (text.contains("translator/packs")) {
                // 用户手动配置的 translator/packs，不动
                return
            }
        }
        insertUnderPatch(customFile, entry)
    }

    /** 从 schema 文件中读取 translator.dictionary 名称，没有则用 schemaId 兜底。 */
    private fun schemaDictionaryName(rimeDir: File, schemaId: String): String {
        val schemaFile = File(rimeDir, "${schemaId}.schema.yaml")
        if (!schemaFile.exists()) return schemaId
        val regex = Regex("""translator:.*?dictionary:\s*(\S+)""", setOf(RegexOption.DOT_MATCHES_ALL))
        return regex.find(schemaFile.readText(Charsets.UTF_8))?.groupValues?.get(1) ?: schemaId
    }

    // 方案无固定音节表：import_tables 合并词典 + translator/dictionary via .custom.yaml
    internal fun applyMergedDictConfig(rimeDir: java.io.File, schemaId: String) {
        val dictName = schemaDictionaryName(rimeDir, schemaId)
        val pkName = packName(rimeDir, schemaId)
        val mergedId = "${schemaId}_merged"
        val dictFile = java.io.File(rimeDir, "${mergedId}.dict.yaml")
        // 验证源词典存在，否则 Rime 编译 ${schemaId}_merged 时会失败
        val srcDictFile = java.io.File(rimeDir, "$dictName.dict.yaml")
        if (!srcDictFile.exists()) {
            if (dictFile.exists()) dictFile.delete()
            return
        }
        // 源词典可能同时含内联词条与 import_tables（如 wubi86.dict.yaml 有 8w+ 行内联词条，
        // 又 import wubi86_user/wubi86_extra）。librime 不递归展开 import_tables，
        // 所以合并表必须显式列出：主词典自身（有编码时）+ 其叶子表 + 个人词库。
        // 若源词典本身是纯合并表/转发器（无内联词条即无编码），则只需展开其叶子表，
        // 不能引用其自身——否则方案自带合并表时会产生循环引用。
        val srcText = srcDictFile.readText(Charsets.UTF_8)
        val leafTables = readImportTables(srcDictFile)
        val base = if (dictTextHasInlineEntries(srcText)) listOf(dictName) + leafTables else leafTables
        val importTables = (base + pkName).distinct().filter { it != mergedId }
        writeMergedDictFile(dictFile, mergedId, importTables)
        val customFile = java.io.File(rimeDir, "${schemaId}.custom.yaml")
        val entry = "  \"translator/dictionary\": $mergedId\n"
        if (customFile.exists()) {
            val text = customFile.readText(Charsets.UTF_8)
            if (text.contains(mergedId)) return
            val existing = extractOwnPatchKey(text, "translator/dictionary")
            if (existing != null) {
                val cleaned = removePatchKey(text, "translator/dictionary")
                customFile.writeText(cleaned, Charsets.UTF_8)
            } else if (text.contains("translator/dictionary")) {
                return
            }
        }
        insertUnderPatch(customFile, entry)
    }

    /** 从源词典文件读取其 `import_tables` 列表（转发器场景）。没有则返回空。 */
    internal fun readImportTables(dictFile: java.io.File): List<String> {
        if (!dictFile.exists()) return emptyList()
        return try {
            val text = dictFile.readText(Charsets.UTF_8)
            val tables = mutableListOf<String>()
            var inImportTables = false
            for (raw in text.lineSequence()) {
                val trimmed = raw.trim()
                if (trimmed.isEmpty() || trimmed.startsWith("#")) continue
                if (trimmed.startsWith("import_tables:")) {
                    inImportTables = true
                    continue
                }
                if (inImportTables && trimmed.startsWith("- ")) {
                    val name = trimmed.removePrefix("- ").trim()
                        .removeSurrounding("\"").removeSurrounding("'")
                    if (name.isNotEmpty()) tables.add(name)
                } else if (inImportTables && !trimmed.startsWith("- ")) {
                    inImportTables = false
                }
            }
            tables
        } catch (_: Exception) {
            emptyList()
        }
    }

    /** 生成 merged dict 文件内容，import_tables 按给定列表展开。 */
    private fun buildMergedDictText(mergedId: String, importTables: List<String>): String {
        val tables = importTables.joinToString("\n") { "  - $it" }
        return """# Rime dict
---
name: $mergedId
version: "1.0"
sort: original
import_tables:
$tables
...
"""
    }

    /** 写合并表文件；文件已存在时保留其 front-matter，仅更新 import_tables 块。 */
    private fun writeMergedDictFile(file: java.io.File, mergedId: String, importTables: List<String>) {
        val tablesBlock = importTables.joinToString("\n") { "  - $it" }
        if (!file.exists()) {
            file.parentFile?.mkdirs()
            file.writeText(buildMergedDictText(mergedId, importTables), Charsets.UTF_8)
            return
        }
        val text = file.readText(Charsets.UTF_8).replace("\r\n", "\n")
        val updated = if (text.contains("import_tables:")) {
            text.replaceFirst(Regex("""(?m)^import_tables:.*(?:\n[ \t]+-.*)*"""), "import_tables:\n$tablesBlock")
        } else {
            val at = text.indexOf("\n...\n").let { if (it >= 0) it + 1 else text.length }
            text.substring(0, at) + "\nimport_tables:\n$tablesBlock\n" + text.substring(at)
        }
        file.writeText(updated, Charsets.UTF_8)
    }

    /** dict 文本是否含内联词条（即「有编码」）：存在 tab 分隔的词条行。 */
    internal fun dictTextHasInlineEntries(text: String): Boolean {
        for (raw in text.lineSequence()) {
            val line = raw.trim()
            if (line.isEmpty() || line.startsWith('#') || line.startsWith("- ")) continue
            if (line.contains('\t')) return true
        }
        return false
    }

    /** 提取 patch 块中本代码之前写入的 key 的值（user_* 或旧版 user_simp_*），没有则返回 null。 */
    private fun extractOwnPatchKey(text: String, key: String): String? {
        val regex = Regex("""^[ \t]+"?$key"?\s*:\s*(.+)$""", RegexOption.MULTILINE)
        return regex.find(text)?.let { m ->
            val value = m.groupValues[1].trim().let {
                if (it.startsWith("[")) it.removePrefix("[").removeSuffix("]") else it
            }.trim().removeSurrounding("\"")
            value.takeIf { it.startsWith("user_simp_") || it.startsWith("user_") }
        }
    }
    private fun removePatchKey(text: String, key: String): String {
        val escapedKey = Regex.escape(key)
        val regex = Regex("""^[ \t]+"?$escapedKey"?\s*:.*$""", RegexOption.MULTILINE)
        val removed = text.replace(regex, "")
        return removed.replace(Regex("\n{3,}"), "\n\n")
    }

    // ── 个人词库 ──

    /**
     * 解析方案实际引用的个人词库文件名。
     * 优先级：方案自带 translator.packs → custom.yaml 中的 translator/packs → translator/dictionary（一路追溯到 user_*） → 默认 packName。
     */
    fun resolvePersonalDictFile(rimeDir: File, schemaId: String): File {
        // 方案自带 translator.packs 声明（开源方案仓库常见，如 pinyin_simp 的 [user_simp]）：
        // 个人词库以方案声明为准，不适用我们自造的 user_${dictionary} 命名。
        val own = readSchemaPacks(rimeDir, schemaId)
        if (own.isNotEmpty()) return File(rimeDir, "${own.first()}.dict.yaml")
        val customFile = File(rimeDir, "${schemaId}.custom.yaml")
        if (customFile.exists()) {
            val text = customFile.readText(Charsets.UTF_8)
            // 1) translator/packs 中最前面的 user_* 或显式声明的名字
            val packs = Regex(""""?translator/packs"?\s*:\s*\[(.+?)]""", RegexOption.DOT_MATCHES_ALL)
                .find(text)?.groupValues?.get(1)?.split(',')?.firstOrNull { it.trim().removeSurrounding("\"").startsWith("user_") }
            if (packs != null) return File(rimeDir, "${packs.trim().removeSurrounding("\"")}.dict.yaml")
            // 2) translator/dictionary → 如果是 *_merged 则去合并文件中找 import_tables
            val dict = Regex(""""?translator/dictionary"?\s*:\s*"?(\w+)"?""").find(text)?.groupValues?.get(1)
            if (dict != null) {
                if (dict.endsWith("_merged")) {
                    val mergedFile = File(rimeDir, "$dict.dict.yaml")
                    if (mergedFile.exists()) {
                        val pk = Regex("""^[ \t]+-\s+(user_\S+)""", RegexOption.MULTILINE).findAll(mergedFile.readText(Charsets.UTF_8))
                            .map { it.groupValues[1] }.firstOrNull()
                        if (pk != null) return File(rimeDir, "$pk.dict.yaml")
                    }
                }
                return File(rimeDir, "$dict.dict.yaml")
            }
        }
        return getPackFile(rimeDir, schemaId)
    }

    /** 从 Context 获取 rime 目录下的个人词库文件。 */
    private fun getPackFile(rimeDir: File, schemaId: String): File =
        File(rimeDir, "${packName(rimeDir, schemaId)}.dict.yaml")

    /** 旧版文件名映射（兼容性），新文件不存在时用于兜底读取。 */
    private fun legacyPackFile(rimeDir: File, schemaId: String): File? {
        val oldName = when (schemaId) {
            "pinyin_simp", "t9_pinyin" -> "user_simp_pinyin"
            "wubi86", "wubi86_pinyin" -> "user_simp_wubi"
            else -> return null
        }
        val file = File(rimeDir, "$oldName.dict.yaml")
        return file.takeIf { it.exists() }
    }

    fun loadEntries(context: Context, schemaId: String): List<DictEntry> {
        val rimeDir = SchemaManager.getRimeDir(context)
        val file = resolvePersonalDictFile(rimeDir, schemaId)
        if (!file.exists()) {
            val legacy = legacyPackFile(rimeDir, schemaId)
            if (legacy != null) return try {
                parsePersonalDictEntries(legacy.readText(Charsets.UTF_8))
            } catch (_: Exception) { emptyList() }
            return emptyList()
        }
        return try {
            parsePersonalDictEntries(file.readText(Charsets.UTF_8))
        } catch (_: Exception) { emptyList() }
    }

    fun saveEntries(context: Context, schemaId: String, entries: List<DictEntry>) {
        val rimeDir = SchemaManager.getRimeDir(context)
        val file = resolvePersonalDictFile(rimeDir, schemaId)
        file.parentFile?.mkdirs()
        val dictName = file.nameWithoutExtension
        val defaultH = DEFAULT_HEADER.format(dictName)
        val header = if (file.exists()) extractHeader(file, defaultH) else defaultH
        file.writeText(buildDictText(header, entries), Charsets.UTF_8)
    }

    fun parsePersonalDictEntries(text: String): List<DictEntry> {
        val out = mutableListOf<DictEntry>()
        var inData = false
        for (raw in text.lineSequence()) {
            val line = raw.trim()
            if (!inData) { if (line == "...") inData = true; continue }
            if (line.isEmpty() || line.startsWith("#")) continue
            val parts = line.split('\t')
            if (parts.size >= 2) {
                out.add(DictEntry(parts[0], parts[1], parts.getOrNull(2)?.toIntOrNull()))
            } else {
                val spaceIdx = line.indexOf(' ')
                if (spaceIdx >= 0) {
                    out.add(DictEntry(line.substring(0, spaceIdx), line.substring(spaceIdx + 1).trim()))
                }
            }
        }
        return out
    }

    internal fun buildDictText(header: String, entries: List<DictEntry>): String {
        val sb = StringBuilder()
        sb.append(header.trimEnd('\n', '\r')).append('\n')
        for (e in entries) {
            sb.append(e.word).append('\t').append(e.code)
            if (e.weight != null) sb.append('\t').append(e.weight)
            sb.append('\n')
        }
        return sb.toString()
    }

    internal fun extractHeader(file: File, defaultH: String): String {
        if (!file.exists()) return defaultH
        val text = file.readText(Charsets.UTF_8)
        return extractHeaderFromText(text, defaultH)
    }

    internal fun extractHeaderFromText(text: String, defaultH: String): String {
        val norm = text.replace("\r\n", "\n")
        val idx = norm.indexOf("\n...\n")
        if (idx >= 0) return norm.substring(0, idx + 5)
        val start = norm.indexOf("...\n")
        if (start >= 0) return norm.substring(0, start + 4)
        return defaultH
    }

    private const val STABLEDB_HEADER = """# Rime table
# coding: utf-8
#@/db_name	custom_phrase
#@/db_type	tabledb
#
"""

    internal fun parseStableDbEntries(text: String): List<DictEntry> {
        val out = mutableListOf<DictEntry>()
        for (raw in text.lineSequence()) {
            val line = raw.trim()
            if (line.isEmpty() || line.startsWith('#')) continue
            val parts = line.split('\t')
            if (parts.size >= 2) {
                out.add(DictEntry(parts[0], parts[1], parts.getOrNull(2)?.toIntOrNull()))
            }
        }
        return out
    }

    internal fun buildStableDbText(header: String, entries: List<DictEntry>): String {
        val sb = StringBuilder()
        sb.append(header.trimEnd('\n', '\r')).append('\n')
        for (e in entries) {
            sb.append(e.word).append('\t').append(e.code)
            if (e.weight != null) sb.append('\t').append(e.weight)
            sb.append('\n')
        }
        return sb.toString()
    }
}

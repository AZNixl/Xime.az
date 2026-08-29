package com.kingzcheung.xime.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests for [SchemaManager.parseSchemaListIds].
 *
 * 回归背景：旧实现只认「- schema: xxx」块式写法，且不剥离行尾注释。
 * 用户手工导入的 default.custom.yaml 若使用内联写法（`- {schema: xxx}`）
 * 或带行尾注释，会解析出空列表或错误的 id，进而回落到 wubi86；
 * 后果是部署 hash 不含这些方案，RimeConfigHelper.ensureDeployment 认为
 * 配置未变化而跳过部署 —— 表现为「新方案必须手动点一次输入方案才生效」。
 */
class SchemaListParsingTest {

    private val inlineStyle = """
        patch:
          schema_list:
            - {schema: tiger_danzheng}  # 虎单整
            - {schema: tiger}
            - {schema: PY_c}
          "switcher/hotkeys":
            - "Control+grave"
            - "Control+F8"
    """.trimIndent()

    private val blockStyleWithComments = """
        patch:
          # 说明注释
          schema_list:
            - schema: tiger_danzheng  # 虎单整
            - schema: tiger
            - schema: PY_c
          "switcher/hotkeys":
            - "Control+grave"
    """.trimIndent()

    /** 项目实际采用的写法：块式 + 注释单独成行，兼容新旧解析器。 */
    private val recommendedStyle = """
        patch:
          # tiger_danzheng  虎单整
          # tiger           虎码单字
          schema_list:
            - schema: tiger_danzheng
            - schema: tiger
            - schema: PY_c
          "switcher/hotkeys":
            - "Control+grave"
    """.trimIndent()

    @Test
    fun `parses inline map style`() {
        assertEquals(
            listOf("tiger_danzheng", "tiger", "PY_c"),
            SchemaManager.parseSchemaListIds(inlineStyle)
        )
    }

    @Test
    fun `parses block style and strips trailing comments`() {
        assertEquals(
            listOf("tiger_danzheng", "tiger", "PY_c"),
            SchemaManager.parseSchemaListIds(blockStyleWithComments)
        )
    }

    @Test
    fun `parses recommended style`() {
        assertEquals(
            listOf("tiger_danzheng", "tiger", "PY_c"),
            SchemaManager.parseSchemaListIds(recommendedStyle)
        )
    }

    @Test
    fun `does not collect items from sibling blocks`() {
        val ids = SchemaManager.parseSchemaListIds(inlineStyle)
        assertTrue("hotkeys 不应被当成方案: $ids", ids.none { it.startsWith("Control") })
    }

    @Test
    fun `handles flow map with extra keys`() {
        val yaml = """
            patch:
              schema_list:
                - {schema: tiger, name: 虎单整}
        """.trimIndent()
        assertEquals(listOf("tiger"), SchemaManager.parseSchemaListIds(yaml))
    }

    @Test
    fun `handles quoted schema id`() {
        val yaml = """
            patch:
              schema_list:
                - schema: "tiger"
        """.trimIndent()
        assertEquals(listOf("tiger"), SchemaManager.parseSchemaListIds(yaml))
    }

    @Test
    fun `returns empty when no schema_list block`() {
        val yaml = """
            patch:
              "switcher/hotkeys":
                - "Control+grave"
        """.trimIndent()
        assertTrue(SchemaManager.parseSchemaListIds(yaml).isEmpty())
    }

    @Test
    fun `stops at first unparsable item`() {
        val yaml = """
            patch:
              schema_list:
                - schema: tiger
              "switcher/hotkeys":
                - "Control+grave"
        """.trimIndent()
        assertEquals(listOf("tiger"), SchemaManager.parseSchemaListIds(yaml))
    }
}

package com.kingzcheung.xime.plugin.funasr

import com.kingzcheung.xime.plugin.core.api.AsrInputMode
import com.kingzcheung.xime.plugin.core.config.PluginConfigStore
import com.kingzcheung.xime.plugin.core.config.PluginFieldType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FunAsrPluginTest {

    private class FakeConfigStore(private val map: MutableMap<String, String>) : PluginConfigStore {
        override fun get(key: String): String? = map[key]
        override fun set(key: String, value: String) {
            map[key] = value
        }
        override fun remove(key: String) {
            map.remove(key)
        }
        override fun keys(): Set<String> = map.keys.toSet()
    }

    @Test
    fun `providerId is funasr`() {
        assertEquals("funasr", FunAsrPlugin().providerId)
    }

    @Test
    fun `display name is 阿里百炼 FunAsr`() {
        assertEquals("阿里百炼 FunAsr", FunAsrPlugin().getDisplayName())
    }

    @Test
    fun `capabilities are streaming with partial results`() {
        val caps = FunAsrPlugin().getCapabilities()

        assertEquals(AsrInputMode.STREAMING, caps.inputMode)
        assertTrue(caps.supportsPartialResults)
        assertTrue(caps.requiresNetwork)
    }

    @Test
    fun `audio format is 16k mono pcm16le`() {
        val format = FunAsrPlugin().getAudioFormat()

        assertEquals(16000, format.sampleRate)
        assertEquals(1, format.channels)
        assertEquals("pcm16le", format.encoding)
    }

    @Test
    fun `schema contains SECRET apiKey field`() {
        val fields = FunAsrPlugin().getSettingsSchema()

        assertEquals(1, fields.size)
        val field = fields.first()
        assertEquals("apiKey", field.key)
        assertEquals("API Key", field.label)
        assertEquals(PluginFieldType.SECRET, field.type)
    }

    @Test
    fun `isConfigured false when apiKey missing`() {
        val plugin = FunAsrPlugin()
        plugin.configStore = FakeConfigStore(mutableMapOf())

        assertFalse(plugin.isConfigured())
    }

    @Test
    fun `isConfigured true when apiKey set`() {
        val plugin = FunAsrPlugin()
        plugin.configStore = FakeConfigStore(mutableMapOf("apiKey" to "sk-test"))

        assertTrue(plugin.isConfigured())
    }

    @Test
    fun `configStore writes through set`() {
        val map = mutableMapOf<String, String>()
        val store = FakeConfigStore(map)
        val plugin = FunAsrPlugin()
        plugin.configStore = store

        store.set("apiKey", "sk-123")

        assertEquals("sk-123", store.get("apiKey"))
        assertTrue(plugin.isConfigured())
    }
}

package com.kingzcheung.xime.plugin.core.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PluginInfoTest {
    
    @Test
    fun `PluginInfo should have correct default values`() {
        val pluginInfo = PluginInfo(
            id = "test_plugin",
            name = "Test Plugin",
            iconResId = 0,
            versionCode = 1,
            versionName = "1.0.0",
            path = "/path/to/plugin",
            entryClass = "com.example.Plugin",
            description = "A test plugin"
        )
        
        assertEquals("test_plugin", pluginInfo.id)
        assertEquals("Test Plugin", pluginInfo.name)
        assertEquals("1.0.0", pluginInfo.versionName)
        assertEquals("unknown", pluginInfo.type)
        assertTrue("Should be enabled by default", pluginInfo.enabled)
    }
    
    @Test
    fun `PluginInfo version property should return versionName`() {
        val pluginInfo = PluginInfo(
            id = "test",
            name = "Test",
            iconResId = 0,
            versionCode = 2,
            versionName = "2.0.0",
            path = "",
            entryClass = "",
            description = ""
        )
        
        assertEquals("2.0.0", pluginInfo.version)
    }
    
    @Test
    fun `PluginInfo can be disabled`() {
        val pluginInfo = PluginInfo(
            id = "test",
            name = "Test",
            iconResId = 0,
            versionCode = 1,
            versionName = "1.0",
            path = "",
            entryClass = "",
            description = "",
            enabled = false
        )
        
        assertFalse("Plugin can be disabled", pluginInfo.enabled)
    }
    
    @Test
    fun `PluginInfo can have different types`() {
        val emojiPlugin = PluginInfo(
            id = "emoji_plugin",
            name = "Emoji",
            iconResId = 0,
            versionCode = 1,
            versionName = "1.0",
            path = "",
            entryClass = "",
            description = "",
            type = "emoji"
        )
        
        assertEquals("emoji", emojiPlugin.type)
    }

    @Test
    fun `PluginInfo category is derived from type`() {
        assertEquals(
            PluginCategory.EMOJI,
            PluginInfo(id = "e", name = "E", iconResId = 0, versionCode = 1, versionName = "1", path = "", entryClass = "", description = "", type = "emoji").category
        )
        assertEquals(
            PluginCategory.ASR,
            PluginInfo(id = "a", name = "A", iconResId = 0, versionCode = 1, versionName = "1", path = "", entryClass = "", description = "", type = "speech").category
        )
        assertEquals(
            PluginCategory.PREDICTION,
            PluginInfo(id = "p", name = "P", iconResId = 0, versionCode = 1, versionName = "1", path = "", entryClass = "", description = "", type = "prediction").category
        )
        assertEquals(
            PluginCategory.UNKNOWN,
            PluginInfo(id = "u", name = "U", iconResId = 0, versionCode = 1, versionName = "1", path = "", entryClass = "", description = "").category
        )
    }
    
    @Test
    fun `PluginInfo copy should preserve values`() {
        val original = PluginInfo(
            id = "original",
            name = "Original",
            iconResId = 123,
            versionCode = 100,
            versionName = "10.0",
            path = "/original/path",
            entryClass = "com.original.Plugin",
            description = "Original plugin",
            type = "emoji",
            enabled = true
        )
        
        val copied = original.copy(enabled = false)
        
        assertEquals("original", copied.id)
        assertEquals("Original", copied.name)
        assertEquals(123, copied.iconResId)
        assertFalse("Copied should be disabled", copied.enabled)
        assertEquals("emoji", copied.type)
    }
    
    @Test
    fun `PluginInfo installTime can be set`() {
        val customTime = 1234567890L
        val pluginInfo = PluginInfo(
            id = "test",
            name = "Test",
            iconResId = 0,
            versionCode = 1,
            versionName = "1.0",
            path = "",
            entryClass = "",
            description = "",
            installTime = customTime
        )
        
        assertEquals(customTime, pluginInfo.installTime)
    }
    
    @Test
    fun `PluginInfo can have nativeLibPath`() {
        val pluginInfo = PluginInfo(
            id = "native_plugin",
            name = "Native",
            iconResId = 0,
            versionCode = 1,
            versionName = "1.0",
            path = "",
            entryClass = "",
            description = "",
            nativeLibPath = "/lib/path"
        )
        
        assertEquals("/lib/path", pluginInfo.nativeLibPath)
    }
    
    @Test
    fun `PluginInfo can have providers`() {
        val providers = listOf(
            ProviderInfo("com.example.Provider1", listOf("authority1")),
            ProviderInfo("com.example.Provider2", listOf("authority2"))
        )
        val pluginInfo = PluginInfo(
            id = "provider_plugin",
            name = "Provider",
            iconResId = 0,
            versionCode = 1,
            versionName = "1.0",
            path = "",
            entryClass = "",
            description = "",
            providers = providers
        )
        
        assertEquals(2, pluginInfo.providers.size)
        assertEquals("com.example.Provider1", pluginInfo.providers[0].className)
        assertEquals(listOf("authority1"), pluginInfo.providers[0].authorities)
    }
}

class ProviderInfoTest {
    
    @Test
    fun `ProviderInfo should have correct properties`() {
        val provider = ProviderInfo(
            className = "com.example.TestProvider",
            authorities = listOf("test_provider")
        )
        
        assertEquals("com.example.TestProvider", provider.className)
        assertEquals(listOf("test_provider"), provider.authorities)
    }
    
    @Test
    fun `ProviderInfo can have multiple authorities`() {
        val provider = ProviderInfo(
            className = "com.example.Provider",
            authorities = listOf("authority1", "authority2", "authority3")
        )
        
        assertEquals(3, provider.authorities.size)
    }
    
    @Test
    fun `ProviderInfo can be compared`() {
        val p1 = ProviderInfo("com.example.Provider", listOf("authority"))
        val p2 = ProviderInfo("com.example.Provider", listOf("authority"))
        
        assertEquals(p1, p2)
    }
    
    @Test
    fun `ProviderInfo exported and enabled defaults`() {
        val provider = ProviderInfo("com.example.Provider", listOf("authority"))
        
        assertFalse("Exported should default to false", provider.exported)
        assertTrue("Enabled should default to true", provider.enabled)
    }
    
    @Test
    fun `ProviderInfo can be exported`() {
        val provider = ProviderInfo(
            className = "com.example.Provider",
            authorities = listOf("authority"),
            exported = true
        )
        
        assertTrue("Provider can be exported", provider.exported)
    }
}
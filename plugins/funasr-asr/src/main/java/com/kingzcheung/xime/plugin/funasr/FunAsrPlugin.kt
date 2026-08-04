package com.kingzcheung.xime.plugin.funasr

import android.content.Context
import android.util.Log
import com.kingzcheung.xime.plugin.core.api.AsrAudioFormat
import com.kingzcheung.xime.plugin.core.api.AsrInputMode
import com.kingzcheung.xime.plugin.core.api.AsrPlugin
import com.kingzcheung.xime.plugin.core.api.AsrPluginBackend
import com.kingzcheung.xime.plugin.core.api.AsrPluginCapabilities
import com.kingzcheung.xime.plugin.core.config.PluginFieldType
import com.kingzcheung.xime.plugin.core.config.PluginSettingField
import com.kingzcheung.xime.plugin.core.model.PluginContext

class FunAsrPlugin : AsrPlugin {

    companion object {
        private const val TAG = "FunAsrPlugin"
        private const val KEY_API_KEY = "apiKey"
    }

    internal var configStore: com.kingzcheung.xime.plugin.core.config.PluginConfigStore =
        com.kingzcheung.xime.plugin.core.config.NoopPluginConfigStore

    override val providerId: String = "funasr"

    override fun getDisplayName(): String = "阿里百炼 FunAsr"

    override fun getCapabilities(): AsrPluginCapabilities = AsrPluginCapabilities(
        inputMode = AsrInputMode.STREAMING,
        supportsPartialResults = true,
        maxRecordDurationMillis = 10 * 60 * 1000,
        requiresNetwork = true
    )

    override fun getAudioFormat(): AsrAudioFormat = AsrAudioFormat()

    override fun getSettingsSchema(): List<PluginSettingField> = listOf(
        PluginSettingField(
            key = KEY_API_KEY,
            label = "API Key",
            type = PluginFieldType.SECRET,
            placeholder = "输入阿里百炼 API Key",
            helpText = "访问阿里云百炼平台获取 API Key"
        )
    )

    override fun isConfigured(): Boolean = !configStore.get(KEY_API_KEY).isNullOrBlank()

    override fun createBackend(context: Context): AsrPluginBackend {
        val apiKey = configStore.get(KEY_API_KEY).orEmpty()
        return FunAsrAsrBackend(apiKey)
    }

    override fun onLoad(context: PluginContext) {
        Log.d(TAG, "Plugin loaded: ${context.pluginInfo.id}")
        configStore = context.configStore
    }

    override fun onUnload() {
        Log.d(TAG, "Plugin unloaded")
    }
}

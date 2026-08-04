package com.kingzcheung.xime.plugin.core.model

import android.app.Application
import com.kingzcheung.xime.plugin.core.config.NoopPluginConfigStore
import com.kingzcheung.xime.plugin.core.config.PluginConfigStore

data class PluginContext(
    val application: Application,
    val pluginInfo: PluginInfo,
    val pluginId: String = pluginInfo.id,
    val configStore: PluginConfigStore = NoopPluginConfigStore
)

data class PluginInfo(
    val id: String,
    val name: String,
    val iconResId: Int,
    val versionCode: Long,
    val versionName: String,
    val path: String,
    val entryClass: String,
    val description: String,
    val type: String = "unknown",
    val enabled: Boolean = true,
    val installTime: Long = System.currentTimeMillis(),
    val nativeLibPath: String? = null,
    val providers: List<ProviderInfo> = emptyList(),
    val source: PluginSource = PluginSource.SYSTEM,
    val minHostVersion: String? = null,
    val maxHostVersion: String? = null
) {
    val version: String get() = versionName
    val category: PluginCategory get() = PluginCategory.fromId(type)
}
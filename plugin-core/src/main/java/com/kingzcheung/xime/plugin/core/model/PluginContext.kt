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
    val maxHostVersion: String? = null,
    val trustLevel: TrustLevel = TrustLevel.UNKNOWN
) {
    val version: String get() = versionName
    val category: PluginCategory get() = PluginCategory.fromId(type)
}

/**
 * 插件信任等级：宿主按插件 APK 签名证书与宿主自身签名是否一致来判定。
 * 仅作为信任标记，不构成强制门槛（避免挡住第三方插件）。
 */
enum class TrustLevel {
    /** 官方：插件签名证书与宿主一致 */
    TRUSTED,

    /** 第三方：有签名但证书与宿主不一致 */
    THIRD_PARTY,

    /** 未知：未签名或无法读取签名 */
    UNKNOWN
}
package com.kingzcheung.xime.plugin.core.util

import com.kingzcheung.xime.plugin.core.model.TrustLevel

/**
 * 插件信任等级判定。
 *
 * Lua 脚本插件无 APK 签名，信任由 UI 展示（官方/第三方/未知来源）与来源控制承担；
 * 后续可扩展为"脚本哈希白名单"判定官方插件。
 */
object PluginSignatureUtil {

    /**
     * Lua 脚本插件信任判定：脚本插件无 APK 签名，一律视为第三方，
     * 由插件中心展示信任标记并承担安全职责。
     */
    fun classifyLuaPlugin(pluginDir: java.io.File): TrustLevel {
        return TrustLevel.THIRD_PARTY
    }
}

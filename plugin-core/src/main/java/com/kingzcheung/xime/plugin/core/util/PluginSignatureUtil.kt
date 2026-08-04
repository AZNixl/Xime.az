package com.kingzcheung.xime.plugin.core.util

import android.content.Context
import android.content.pm.PackageManager
import com.kingzcheung.xime.plugin.core.model.TrustLevel
import java.security.MessageDigest

/**
 * 插件签名与信任等级判定。
 *
 * 插件不以系统 APK 安装，宿主用 DexClassLoader 直接加载，因此不校验签名。
 * 这里只读取插件 APK 的签名证书，与宿主自身签名比对，得出"信任标记"（TrustLevel），
 * 用于 UI 展示与"启用非官方插件"确认，不作为强制门槛。
 */
object PluginSignatureUtil {

    private const val SIGNING_FLAGS =
        PackageManager.GET_SIGNING_CERTIFICATES or PackageManager.GET_SIGNATURES

    /** 读取 APK 文件（未安装）的签名证书 sha256；失败返回 null。 */
    fun getApkCertSha256(context: Context, apkPath: String): String? {
        return try {
            val packageInfo = context.packageManager.getPackageArchiveInfo(apkPath, SIGNING_FLAGS)
                ?: return null
            extractCertSha256(packageInfo)
        } catch (e: Exception) {
            null
        }
    }

    /** 宿主应用自身签名证书 sha256；失败返回 null。 */
    fun getHostCertSha256(context: Context): String? {
        return try {
            val packageInfo = context.packageManager.getPackageInfo(
                context.packageName, SIGNING_FLAGS
            )
            extractCertSha256(packageInfo)
        } catch (e: Exception) {
            null
        }
    }

    /** 同时兼容 v1（GET_SIGNATURES）与 v2/v3（GET_SIGNING_CERTIFICATES）签名。 */
    private fun extractCertSha256(packageInfo: android.content.pm.PackageInfo): String? {
        val signer = packageInfo.signingInfo?.apkContentsSigners
            ?.takeIf { it.isNotEmpty() }
            ?.firstOrNull()
        val legacy = signer ?: packageInfo.signatures?.firstOrNull()
        return legacy?.let { sha256Hex(it.toByteArray()) }
    }

    /** 根据插件 APK 签名判定信任等级。 */
    fun classify(context: Context, apkPath: String): TrustLevel {
        val hostSha = getHostCertSha256(context) ?: return TrustLevel.UNKNOWN
        val pluginSha = getApkCertSha256(context, apkPath) ?: return TrustLevel.UNKNOWN
        return if (pluginSha.equals(hostSha, ignoreCase = true)) {
            TrustLevel.TRUSTED
        } else {
            TrustLevel.THIRD_PARTY
        }
    }

    private fun sha256Hex(data: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(data)
        return digest.joinToString("") { "%02x".format(it) }
    }
}

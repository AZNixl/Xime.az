package com.kingzcheung.xime.util

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.Settings
import java.io.File

/**
 * 统一数据目录提供者（自用定制版）。
 *
 * 把所有原本放在应用私有目录（filesDir）的数据重定向到共享存储
 * `/Documents/Xime/`，方便用户用任意文件管理器直接修改键盘符号配置
 * （rime/xime.custom.yaml）、备份模型和词库。
 *
 * 依赖 "所有文件访问" 权限（MANAGE_EXTERNAL_STORAGE）：
 * - 已授权 → 返回共享存储目录；
 * - 未授权 → 回退私有目录，输入法可正常使用，只是外部看不到文件。
 */
object XimeStorage {

    private const val ROOT_DIR_NAME = "Xime"

    /** 共享存储根目录：/Documents/Xime/；在 JVM 单元测试等无法调用 Android API 的环境下返回 null。 */
    private val sharedRoot: File?
        get() = try {
            File(Environment.getExternalStoragePublicDirectory(
                Environment.DIRECTORY_DOCUMENTS), ROOT_DIR_NAME)
        } catch (_: RuntimeException) {
            // JVM 单元测试：android.os.Environment 未 mock
            null
        }

    /** 是否已授予"所有文件访问"权限。Android 11 以下默认有完整存储权限。 */
    fun hasStorageAccess(): Boolean = try {
        Build.VERSION.SDK_INT < Build.VERSION_CODES.R || Environment.isExternalStorageManager()
    } catch (_: RuntimeException) {
        // JVM 单元测试：android.os.Environment 未 mock
        false
    }

    /**
     * 数据根目录。已授权时为 /Documents/Xime/（自动创建），未授权或单元测试时回退 filesDir。
     */
    fun root(context: Context): File {
        if (hasStorageAccess()) {
            val dir = sharedRoot
            if (dir != null) {
                if (!dir.exists()) dir.mkdirs()
                if (dir.exists()) return dir
            }
        }
        return context.filesDir
    }

    /** Rime 配置目录：<root>/rime/ */
    fun rimeDir(context: Context): File {
        val dir = File(root(context), "rime")
        if (!dir.exists()) dir.mkdirs()
        return dir
    }

    /** 模型目录：<root>/models/ */
    fun modelsDir(context: Context): File {
        val dir = File(root(context), "models")
        if (!dir.exists()) dir.mkdirs()
        return dir
    }

    /** 日志目录：<root>/logs/ */
    fun logsDir(context: Context): File {
        val dir = File(root(context), "logs")
        if (!dir.exists()) dir.mkdirs()
        return dir
    }

    /** 字体目录：<root>/fonts/（用户放入 .ttf/.otf 供键盘字体设置选用） */
    fun fontsDir(context: Context): File {
        val dir = File(root(context), "fonts")
        if (!dir.exists()) dir.mkdirs()
        return dir
    }

    /** 跳转系统设置页，引导用户授予"所有文件访问"权限。 */
    fun buildAccessIntent(context: Context): Intent {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            try {
                Intent(
                    Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                    Uri.parse("package:${context.packageName}")
                )
            } catch (_: Exception) {
                Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION)
            }
        } else {
            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                Uri.parse("package:${context.packageName}"))
        }
    }
}

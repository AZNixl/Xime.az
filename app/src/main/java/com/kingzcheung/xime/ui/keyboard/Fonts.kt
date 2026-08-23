package com.kingzcheung.xime.ui.keyboard

import android.content.Context
import android.content.res.AssetManager
import android.graphics.Typeface
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import com.kingzcheung.xime.settings.SettingsPreferences
import com.kingzcheung.xime.util.XimeStorage
import java.io.File

object AppFonts {
    private const val CHAI_PUA_FONT = "ChaiPUA-0.2.7-snow.ttf"

    private var initialized = false
    private lateinit var assetManager: AssetManager

    val chaiPuaTypeface: Typeface by lazy {
        Typeface.createFromAsset(assetManager, CHAI_PUA_FONT)
    }

    val chaiPuaFontFamily: FontFamily by lazy {
        FontFamily(Font(CHAI_PUA_FONT, assetManager))
    }

    fun initialize(context: Context) {
        if (initialized) return
        initialized = true
        assetManager = context.assets
    }

    // ── 键盘自定义字体（/Documents/Xime/fonts/，可多选，顺序即回退顺序）──

    /** 当前自定义键盘字体缓存（null = 未设置/加载失败，用系统默认字体） */
    @Volatile
    private var customKeyboardFontFamily: FontFamily? = null

    /** 已加载的字体文件名列表签名，用于判断是否需要重新加载 */
    @Volatile
    private var loadedFontSignature: String? = null

    /** 支持的字体文件扩展名 */
    private val FONT_EXTENSIONS = setOf("ttf", "otf")

    /** 列出 /Documents/Xime/fonts/ 下可用的字体文件名（便于设置页展示） */
    fun listAvailableFonts(context: Context): List<String> {
        val dir = XimeStorage.fontsDir(context)
        if (!dir.exists()) return emptyList()
        return dir.listFiles()
            ?.filter { it.isFile && it.extension.lowercase() in FONT_EXTENSIONS }
            ?.map { it.name }
            ?.sorted()
            ?: emptyList()
    }

    /**
     * 获取当前键盘字体（FontFamily 回退链）：
     * 用户选择的多个字体按顺序作为首选，缺字形依次回退；
     * 末尾自动追加内置 ChaiPUA（拆字/部首），最后回退系统默认。
     * 未选择任何字体时返回 null（调用方用默认字体）。
     * 按文件名列表缓存，变更时重新加载。
     */
    fun getKeyboardFontFamily(context: Context): FontFamily? {
        val selected = SettingsPreferences.getKeyboardFonts(context)
        if (selected.isEmpty()) {
            customKeyboardFontFamily = null
            loadedFontSignature = null
            return null
        }
        val signature = selected.joinToString(",")
        if (loadedFontSignature == signature && customKeyboardFontFamily != null) {
            return customKeyboardFontFamily
        }
        val fonts = mutableListOf<Font>()
        for (name in selected) {
            val file = File(XimeStorage.fontsDir(context), name)
            if (!file.exists()) continue
            try {
                fonts.add(Font(file))
            } catch (_: Exception) { /* 跳过损坏文件 */ }
        }
        if (fonts.isEmpty()) {
            customKeyboardFontFamily = null
            loadedFontSignature = null
            return null
        }
        return try {
            // 末尾追加内置拆字字体 ChaiPUA，确保部首/扩展字符可显示
            fonts.add(Font(CHAI_PUA_FONT, assetManager))
            val family = FontFamily(fonts)
            customKeyboardFontFamily = family
            loadedFontSignature = signature
            family
        } catch (e: Exception) {
            customKeyboardFontFamily = null
            loadedFontSignature = null
            null
        }
    }

    /** 字体设置变更后调用，强制下次使用时重新加载。 */
    fun invalidateCustomFont() {
        customKeyboardFontFamily = null
        loadedFontSignature = null
    }
}

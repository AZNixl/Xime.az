package com.kingzcheung.xime.ui.theme

import android.content.Context
import androidx.compose.ui.graphics.Color
import com.kingzcheung.xime.settings.BackgroundConfig
import com.kingzcheung.xime.settings.ColorSchemeEntry
import com.kingzcheung.xime.settings.KeysConfigHelper

data class KeyboardColorScheme(
    val id: String,
    val name: String,
    val specialKeyLight: Color,
    val specialKeyDark: Color,
    val accentLight: Color,
    val accentDark: Color,
    val primaryLight: Color = accentLight,
    val primaryDark: Color = accentDark,
    val primaryContainerLight: Color = specialKeyLight,
    val primaryContainerDark: Color = specialKeyDark,
    val surfaceLight: Color = Color.White,
    val surfaceDark: Color = Color(0xFF1C1B1F),
    // 键盘背景色
    val keyboardBgLight: Color = surfaceLight,
    val keyboardBgDark: Color = surfaceDark,
    // 按键颜色
    val keyBgLight: Color = Color.White,
    val keyBgDark: Color = Color(0xFF4A4A4A),
    // 候选栏颜色
    val candidateBarBgLight: Color = surfaceLight,
    val candidateBarBgDark: Color = surfaceDark,
    // 按键文字颜色
    val keyTextColorLight: Color = Color(0xFF202124),
    val keyTextColorDark: Color = Color(0xFFE8EAED),
    // 候选文字颜色
    val candidateTextColorLight: Color = Color(0xFF1A73E8),
    val candidateTextColorDark: Color = Color(0xFF8AB4F8),
    // 分隔线颜色
    val dividerColorLight: Color = Color(0xFFDADCE0),
    val dividerColorDark: Color = Color(0xFF3C4043),
    // 背景配置（纯色/渐变/图片），比上方 flat color 字段优先级更高
    val keyboardBackground: BackgroundConfig? = null,
    val keyBackground: BackgroundConfig? = null,
    val candidateBarBackground: BackgroundConfig? = null,
)

object KeyboardThemes {
    /** 从 xime.yaml 加载的配置覆盖项。 */
    private var configOverrides: Map<String, ColorSchemeEntry> = emptyMap()

    /** 预计算后的主题缓存（避免每次访问都重新计算颜色）。 */
    private var themesCache: List<KeyboardColorScheme> = emptyList()
    private var themesMapCache: Map<String, KeyboardColorScheme> = emptyMap()

    /** 硬编码的默认主题列表（兜底，其余主题由 xime.yaml color_schemes 提供）。 */
    private val defaultThemes = listOf(
        KeyboardColorScheme(
            id = "lavender_purple",
            name = "薰衣草紫",
            specialKeyLight = Color(0xFFE8DEF8),
            specialKeyDark = Color(0xFF6750A4),
            accentLight = Color(0xFF8F73E2),
            accentDark = Color(0xFFD0BCFF),
            primaryLight = Color(0xFF8F73E2),
            primaryDark = Color(0xFFD0BCFF),
            primaryContainerLight = Color(0xFFEADDFF),
            primaryContainerDark = Color(0xFF4F378B),
            surfaceLight = Color(0xFFFAF8FC),
            surfaceDark = Color(0xFF2B2930)
        )
    )

    init {
        themesCache = defaultThemes
        themesMapCache = defaultThemes.associateBy { it.id }
    }

    /** 从配置文件加载主题覆盖项。应在 Application.onCreate 中调用。 */
    fun initFromConfig(context: Context) {
        reload(context)
    }

    /** 重新加载 xime.yaml/xime.custom.yaml 中的配色方案并更新缓存。 */
    fun reload(context: Context) {
        configOverrides = KeysConfigHelper.loadColorSchemes(context)
        android.util.Log.d("KeyboardTheme", "reload: configOverrides=${configOverrides.keys}")
        // 1) 对硬编码主题应用配置覆盖
        val overridden = defaultThemes.map { applyConfigOverrides(it) }
        // 2) 把配置中有但硬编码列表中没有的新主题也加入缓存
        val existingIds = overridden.map { it.id }.toSet()
        val newThemes = configOverrides
            .filterKeys { it !in existingIds }
            .map { (id, entry) -> buildSchemeFromConfig(id, entry) }
        themesCache = overridden + newThemes
        themesMapCache = themesCache.associateBy { it.id }
        android.util.Log.d("KeyboardTheme", "reload: themesCache ids=${themesCache.map { it.id }}")
    }



    /** 根据配置项创建全新的 KeyboardColorScheme。 */
    private fun buildSchemeFromConfig(id: String, entry: ColorSchemeEntry): KeyboardColorScheme {
        val cfgColor = longToColor(entry.primaryColor)
        val lightened = lightenColor(cfgColor)
        val veryLight = lightenColor(cfgColor, 0.8f)
        val global = KeysConfigHelper.getKeyboardColors()

        val kbdBg = resolveBgColor(entry, isDark = false) ?: Color.White
        val kbdBgDark = resolveBgColor(entry, isDark = true) ?: Color(0xFF1C1B1F)
        val keyBg = resolveKeyBgColor(entry, isDark = false) ?: longToColor(global.keyBgColor)
        val keyBgDark = resolveKeyBgColor(entry, isDark = true) ?: longToColor(global.keyBgColorDark)
        val txtColor = entry.keyTextColor?.let { longToColor(it) } ?: longToColor(global.keyTextColor)
        val txtColorDark = entry.keyTextColorDark?.let { longToColor(it) }
            ?: longToColor(global.keyTextColorDark)
        val candColorLight = entry.candidateTextColor?.let { longToColor(it) }
            ?: longToColor(global.candidateTextColor)
        val candColorDark = entry.candidateTextColorDark?.let { longToColor(it) }
            ?: longToColor(global.candidateTextColorDark)

        return KeyboardColorScheme(
            id = id,
            name = entry.name.ifEmpty { id },
            specialKeyLight = veryLight,
            specialKeyDark = cfgColor,
            accentLight = cfgColor,
            accentDark = lightened,
            primaryLight = cfgColor,
            primaryDark = lightened,
            primaryContainerLight = veryLight,
            primaryContainerDark = cfgColor,
            surfaceLight = Color.White,
            surfaceDark = Color(0xFF1C1B1F),
            keyboardBgLight = kbdBg,
            keyboardBgDark = kbdBgDark,
            keyBgLight = keyBg,
            keyBgDark = keyBgDark,
            candidateBarBgLight = kbdBg,
            candidateBarBgDark = kbdBgDark,
            keyTextColorLight = txtColor,
            keyTextColorDark = txtColorDark,
            candidateTextColorLight = candColorLight,
            candidateTextColorDark = candColorDark,
            keyboardBackground = entry.keyboardBackground,
            keyBackground = entry.keyBackground,
            candidateBarBackground = entry.candidateBarBackground,
        )
    }

    /** 从 BackgroundConfig（solid/gradient fallback）或旧 keyboardBgColor 字段解析键盘背景色。 */
    private fun resolveBgColor(entry: ColorSchemeEntry, isDark: Boolean): Color? {
        val bg = entry.keyboardBackground
        if (bg != null) {
            when (bg.type) {
                "solid" -> {
                    val hex = if (isDark) bg.colorDark ?: bg.color else bg.color
                    if (hex != null) return longToColor(hex)
                }
                "gradient" -> {
                    val hexes = if (isDark) bg.colorsDark ?: bg.colors else bg.colors
                    if (!hexes.isNullOrEmpty()) return longToColor(hexes[0])
                }
            }
        }
        return entry.keyboardBgColor?.let {
            val c = longToColor(it)
            if (isDark) darkenColor(c) else c
        }
    }

    /** 从 BackgroundConfig 或旧 keyBgColor 字段解析按键背景色。 */
    private fun resolveKeyBgColor(entry: ColorSchemeEntry, isDark: Boolean): Color? {
        val bg = entry.keyBackground
        if (bg != null) {
            when (bg.type) {
                "solid" -> {
                    val hex = if (isDark) bg.colorDark ?: bg.color else bg.color
                    if (hex != null) return longToColor(hex)
                }
                "gradient" -> {
                    val hexes = if (isDark) bg.colorsDark ?: bg.colors else bg.colors
                    if (!hexes.isNullOrEmpty()) return longToColor(hexes[0])
                }
            }
        }
        if (isDark) {
            return entry.keyBgColorDark?.let { longToColor(it) }
        }
        return entry.keyBgColor?.let { longToColor(it) }
    }

    /** 将 hex long 转为 Color。0xRRGGBB 补上 FF alpha，0xAARRGGBB 保留 alpha。 */
    private fun longToColor(hex: Long): Color {
        return if (hex > 0xFFFFFF) Color(hex) else Color(0xFF000000 or (hex and 0xFFFFFF))
    }

    /** 将颜色调亮（向白色混合），用于生成暗色主题下的亮色变体。 */
    private fun lightenColor(color: Color, factor: Float = 0.45f): Color {
        val r = color.red + (1f - color.red) * factor
        val g = color.green + (1f - color.green) * factor
        val b = color.blue + (1f - color.blue) * factor
        return Color(r.coerceIn(0f, 1f), g.coerceIn(0f, 1f), b.coerceIn(0f, 1f))
    }

    /** 将颜色调暗（向黑色混合），用于从亮色生成暗色变体。 */
    private fun darkenColor(color: Color, factor: Float = 0.7f): Color {
        val r = color.red * factor
        val g = color.green * factor
        val b = color.blue * factor
        return Color(r.coerceIn(0f, 1f), g.coerceIn(0f, 1f), b.coerceIn(0f, 1f))
    }

    /** 应用配置覆盖，返回覆盖后的 KeyboardColorScheme。 */
    private fun applyConfigOverrides(scheme: KeyboardColorScheme): KeyboardColorScheme {
        val entry = configOverrides[scheme.id] ?: return scheme
        val cfgColor = longToColor(entry.primaryColor)
        val lightened = lightenColor(cfgColor)
        val global = KeysConfigHelper.getKeyboardColors()
        return scheme.copy(
            name = entry.name.ifEmpty { scheme.name },
            accentLight = cfgColor,
            accentDark = lightened,
            primaryLight = cfgColor,
            primaryDark = lightened,
            keyboardBgLight = resolveBgColor(entry, isDark = false) ?: scheme.keyboardBgLight,
            keyboardBgDark = resolveBgColor(entry, isDark = true) ?: scheme.keyboardBgDark,
            keyBgLight = resolveKeyBgColor(entry, isDark = false) ?: longToColor(global.keyBgColor),
            keyBgDark = resolveKeyBgColor(entry, isDark = true) ?: longToColor(global.keyBgColorDark),
            candidateBarBgLight = resolveBgColor(entry, isDark = false) ?: scheme.candidateBarBgLight,
            candidateBarBgDark = resolveBgColor(entry, isDark = true) ?: scheme.candidateBarBgDark,
            keyTextColorLight = entry.keyTextColor?.let { longToColor(it) } ?: longToColor(global.keyTextColor),
            keyTextColorDark = entry.keyTextColorDark?.let { longToColor(it) }
                ?: longToColor(global.keyTextColorDark),
            candidateTextColorLight = entry.candidateTextColor?.let { longToColor(it) }
                ?: longToColor(global.candidateTextColor),
            candidateTextColorDark = entry.candidateTextColorDark?.let { longToColor(it) }
                ?: longToColor(global.candidateTextColorDark),
            keyboardBackground = entry.keyboardBackground ?: scheme.keyboardBackground,
            keyBackground = entry.keyBackground ?: scheme.keyBackground,
            candidateBarBackground = entry.candidateBarBackground ?: scheme.candidateBarBackground,
        )
    }

    /** 预计算后的主题列表。 */
    val themes: List<KeyboardColorScheme>
        get() = themesCache

    fun getThemeById(id: String): KeyboardColorScheme {
        return themesMapCache[id] ?: themesCache[0]
    }

    fun getSpecialKeyColor(themeId: String, isDark: Boolean): Color {
        val theme = getThemeById(themeId)
        return if (isDark) theme.specialKeyDark else theme.specialKeyLight
    }

    fun getAccentColor(themeId: String, isDark: Boolean): Color {
        val theme = getThemeById(themeId)
        return if (isDark) theme.accentDark else theme.accentLight
    }

    /** 特殊键文字颜色：暗色用强调色，亮色用更深的版本以提高对比度。 */
    fun getSpecialKeyTextColor(themeId: String, isDark: Boolean): Color {
        val accent = getAccentColor(themeId, isDark)
        return if (isDark) accent else Color(
            red = accent.red * 0.6f, green = accent.green * 0.6f, blue = accent.blue * 0.6f
        )
    }

    fun getPrimaryColor(themeId: String, isDark: Boolean): Color {
        val theme = getThemeById(themeId)
        return if (isDark) theme.primaryDark else theme.primaryLight
    }

    fun getPrimaryContainerColor(themeId: String, isDark: Boolean): Color {
        val theme = getThemeById(themeId)
        return if (isDark) theme.primaryContainerDark else theme.primaryContainerLight
    }

    fun getSurfaceColor(themeId: String, isDark: Boolean): Color {
        val theme = getThemeById(themeId)
        return if (isDark) theme.surfaceDark else theme.surfaceLight
    }

    fun getKeyboardBackgroundColor(themeId: String, isDark: Boolean): Color {
        val theme = getThemeById(themeId)
        return if (isDark) theme.keyboardBgDark else theme.keyboardBgLight
    }

    fun getKeyBackgroundColor(themeId: String, isDark: Boolean): Color {
        val theme = getThemeById(themeId)
        return if (isDark) theme.keyBgDark else theme.keyBgLight
    }

    fun getCandidateBarBackgroundColor(themeId: String, isDark: Boolean): Color {
        val theme = getThemeById(themeId)
        return if (isDark) theme.candidateBarBgDark else theme.candidateBarBgLight
    }

    fun getKeyTextColor(themeId: String, isDark: Boolean): Color {
        val theme = getThemeById(themeId)
        return if (isDark) theme.keyTextColorDark else theme.keyTextColorLight
    }

    fun getCandidateTextColor(themeId: String, isDark: Boolean): Color {
        val theme = getThemeById(themeId)
        return if (isDark) theme.candidateTextColorDark else theme.candidateTextColorLight
    }

    fun getDividerColor(themeId: String, isDark: Boolean): Color {
        val theme = getThemeById(themeId)
        return if (isDark) theme.dividerColorDark else theme.dividerColorLight
    }

    /** 返回 color_schemes 中显式定义的按键背景色，未定义返回 null。 */
    fun getKeyBgColorOverride(themeId: String, isDark: Boolean): Color? {
        val entry = configOverrides[themeId] ?: return null
        return resolveKeyBgColor(entry, isDark)
    }

    /** 返回 color_schemes 中显式定义的按键文字色，未定义返回 null。 */
    fun getKeyTextColorOverride(themeId: String, isDark: Boolean): Color? {
        val entry = configOverrides[themeId] ?: return null
        return if (isDark) {
            entry.keyTextColorDark?.let { longToColor(it) }
        } else {
            entry.keyTextColor?.let { longToColor(it) }
        }
    }

    /** 返回 color_schemes 中显式定义的候选文字色，未定义返回 null。 */
    fun getCandidateTextColorOverride(themeId: String, isDark: Boolean): Color? {
        val entry = configOverrides[themeId] ?: return null
        return if (isDark) {
            entry.candidateTextColorDark?.let { longToColor(it) }
        } else {
            entry.candidateTextColor?.let { longToColor(it) }
        }
    }
}

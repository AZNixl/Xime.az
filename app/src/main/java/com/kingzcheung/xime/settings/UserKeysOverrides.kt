package com.kingzcheung.xime.settings

import android.content.Context
import android.util.Log
import com.kingzcheung.xime.keyboard.GestureAction
import org.json.JSONArray
import org.json.JSONObject

/**
 * 用户键盘手势覆盖层。
 *
 * 用户在「设置 → 键盘符号编辑」中对单个按键的 tap/上滑/下滑/长按做的修改，
 * 以 JSON 存到 SharedPreferences（kime_settings.user_key_overrides）。
 *
 * 加载时机：KeysConfigHelper.loadXimeConfig 在解析完 assets 的 xime.yaml 后调用
 * [applyTo]，把覆盖叠加上去，因此自定义改动随 configVersion 一并生效。
 *
 * JSON 结构：
 * {
 *   "qwerty": { "a": { "tap": {...}, "swipe_up": {...}, "swipe_down": {...},
 *                      "long_press": { "display": "bubble", "values": [ {...}, ... ] } } },
 *   "qwerty_en": { ... }
 * }
 *
 * 手势对象（tap/swipe_up/swipe_down/long_press.values[]）：
 *   { "label": "显示文字", "action": "commit|select_all|cut|copy|paste|...|none",
 *     "value": "上屏文本/命令名", "display": "key|bubble|both" }
 * 手势为 JSON null 表示「清除该手势」。
 * long_press.display: "none"=无长按, "key"=直发第一项, "bubble"=弹气泡(默认)
 */
object UserKeysOverrides {
    private const val TAG = "UserKeysOverrides"
    private const val KEY_OVERRIDES = "user_key_overrides"

    private fun prefs(context: Context) =
        context.getSharedPreferences("kime_settings", Context.MODE_PRIVATE)

    /** 读取整棵覆盖树；无则空 JSONObject。 */
    private fun readTree(context: Context): JSONObject {
        val raw = prefs(context).getString(KEY_OVERRIDES, null) ?: return JSONObject()
        return try { JSONObject(raw) } catch (e: Exception) {
            Log.w(TAG, "corrupt overrides, reset", e)
            JSONObject()
        }
    }

    private fun writeTree(context: Context, tree: JSONObject) {
        prefs(context).edit().putString(KEY_OVERRIDES, tree.toString()).apply()
    }

    // ── 读取（供设置页回显）──

    /** 取某区某键的覆盖对象（无覆盖返回 null）。section = "qwerty" | "qwerty_en"。 */
    fun getKeyOverride(context: Context, section: String, key: String): JSONObject? {
        val sec = readTree(context).optJSONObject(section) ?: return null
        return sec.optJSONObject(key)
    }

    /** 清除某键的全部覆盖（恢复默认）。 */
    fun clearKey(context: Context, section: String, key: String) {
        val tree = readTree(context)
        val sec = tree.optJSONObject(section) ?: return
        sec.remove(key)
        writeTree(context, tree)
    }

    // ── 写入（供设置页保存）──

    /** 设置单手势（tap/swipe_up/swipe_down）。gesture 为 null 表示清除。 */
    fun setGesture(
        context: Context,
        section: String,
        key: String,
        gestureName: String,
        gesture: JSONObject?,
    ) {
        val tree = readTree(context)
        val sec = tree.optJSONObject(section) ?: JSONObject().also { tree.put(section, it) }
        val keyObj = sec.optJSONObject(key) ?: JSONObject().also { sec.put(key, it) }
        if (gesture == null) keyObj.put(gestureName, JSONObject.NULL) else keyObj.put(gestureName, gesture)
        writeTree(context, tree)
    }

    /** 设置长按。display = none|key|bubble；values 仅在 key/bubble 时有意义。 */
    fun setLongPress(
        context: Context,
        section: String,
        key: String,
        display: String,
        values: List<JSONObject>,
    ) {
        val tree = readTree(context)
        val sec = tree.optJSONObject(section) ?: JSONObject().also { tree.put(section, it) }
        val keyObj = sec.optJSONObject(key) ?: JSONObject().also { sec.put(key, it) }
        val lp = JSONObject()
        lp.put("display", display)
        val arr = JSONArray()
        values.forEach { arr.put(it) }
        lp.put("values", arr)
        keyObj.put("long_press", lp)
        writeTree(context, tree)
    }

    // ── 应用（供 KeysConfigHelper 合并）──

    /**
     * 把用户覆盖叠加到已解析的默认配置上。
     * zh = qwerty（中文），en = qwerty_en（英文）。
     */
    fun applyTo(
        context: Context,
        zh: Map<String, KeyGestureConfig>,
        en: Map<String, KeyGestureConfig>,
    ): Pair<Map<String, KeyGestureConfig>, Map<String, KeyGestureConfig>> {
        val tree = readTree(context)
        if (tree.length() == 0) return Pair(zh, en)
        val newZh = applySection(tree.optJSONObject("qwerty"), zh)
        val newEn = applySection(tree.optJSONObject("qwerty_en"), en)
        // 英文键盘跟随中文键盘符号：中文区改动转半角后叠加到英文区（英文区已有独立覆盖的键除外）
        val finalEn = if (SettingsPreferences.isEnFollowZhSymbols(context)) {
            followZhOverrides(context, newZh, newEn)
        } else newEn
        return Pair(newZh, finalEn)
    }

    /** 把中文区有效手势转半角后叠加到英文区；英文区该键已有独立覆盖则跳过。 */
    private fun followZhOverrides(
        context: Context,
        zh: Map<String, KeyGestureConfig>,
        en: Map<String, KeyGestureConfig>,
    ): Map<String, KeyGestureConfig> {
        val enTree = readTree(context).optJSONObject("qwerty_en")
        val result = en.toMutableMap()
        for ((key, zhCfg) in zh) {
            if (enTree?.optJSONObject(key) != null) continue
            val base = en[key] ?: continue
            result[key] = KeyGestureConfig(
                tap = zhCfg.tap?.let { toHalfWidth(it) } ?: base.tap,
                swipeUp = zhCfg.swipeUp?.let { toHalfWidth(it) } ?: base.swipeUp,
                swipeDown = zhCfg.swipeDown?.let { toHalfWidth(it) } ?: base.swipeDown,
                longPress = zhCfg.longPress?.let { lp ->
                    LongPressConfig(lp.display, lp.values.map { toHalfWidth(it) })
                } ?: base.longPress,
            )
        }
        return result
    }

    /** 手势 label/value 全角→半角（动作与 display 不变）。 */
    private fun toHalfWidth(g: GestureDef): GestureDef =
        g.copy(label = fullToHalf(g.label), value = fullToHalf(g.value))

    /** 全角→半角（0xFF01-0xFF5E → ASCII，全角空格→空格）。 */
    private fun fullToHalf(s: String): String {
        if (s.isEmpty()) return s
        val sb = StringBuilder(s.length)
        for (c in s) {
            sb.append(
                when (c) {
                    '\u3000' -> ' '
                    in '\uFF01'..'\uFF5E' -> (c.code - 0xFEE0).toChar()
                    else -> c
                }
            )
        }
        return sb.toString()
    }

    private fun applySection(
        secObj: JSONObject?,
        base: Map<String, KeyGestureConfig>,
    ): Map<String, KeyGestureConfig> {
        if (secObj == null || secObj.length() == 0) return base
        val result = base.toMutableMap()
        for (key in secObj.keys()) {
            val keyObj = secObj.optJSONObject(key) ?: continue
            val existing = result[key] ?: KeyGestureConfig()
            result[key] = mergeKey(existing, keyObj)
        }
        return result
    }

    private fun mergeKey(base: KeyGestureConfig, o: JSONObject): KeyGestureConfig {
        val tap = if (o.has("tap")) parseGestureField(o.opt("tap"), base.tap) else base.tap
        val up = if (o.has("swipe_up")) parseGestureField(o.opt("swipe_up"), base.swipeUp) else base.swipeUp
        val down = if (o.has("swipe_down")) parseGestureField(o.opt("swipe_down"), base.swipeDown) else base.swipeDown
        val lp = if (o.has("long_press")) parseLongPress(o.optJSONObject("long_press")) else base.longPress
        return KeyGestureConfig(tap, up, down, lp)
    }

    /**
     * 解析手势字段，区分「清除」与「未设置」：
     *   JSONObject.NULL → 清除（返回 null，覆盖默认）
     *   { "cleared": true } → 清除（返回 null）
     *   JSONObject → 解析为手势
     *   其他 → 保留默认 base
     */
    private fun parseGestureField(node: Any?, base: GestureDef?): GestureDef? {
        if (node == JSONObject.NULL) return null
        val o = node as? JSONObject ?: return base
        if (o.optBoolean("cleared", false)) return null
        return gestureFromJson(o)
    }

    private fun gestureFromJson(o: JSONObject): GestureDef {
        val label = o.optString("label", "")
        val actionStr = o.optString("action", "commit")
        val action = if (actionStr == "null") null else GestureAction.fromValue(actionStr)
        val value = o.optString("value", "")
        val display = DisplayMode.fromValue(o.optString("display", "key"))
        return GestureDef(label = label, action = action, value = value, display = display)
    }

    private fun parseLongPress(o: JSONObject?): LongPressConfig? {
        o ?: return null
        val display = o.optString("display", "bubble")
        if (display == "none") return null
        val arr = o.optJSONArray("values")
        val values = mutableListOf<GestureDef>()
        if (arr != null) {
            for (i in 0 until arr.length()) {
                val item = arr.opt(i) ?: continue
                when (item) {
                    is JSONObject -> values.add(gestureFromJson(item))
                    is String -> values.add(GestureDef(label = item, action = GestureAction.COMMIT, value = item))
                }
            }
        }
        // display 直接沿用 key|bubble；key 模式（直发第一项）由 KeyboardLayout 侧消费
        return LongPressConfig(display = display, values = values)
    }

    // ── 工具：把设置页输入解析为手势 JSON ──

    /**
     * 构建一个手势 JSON。
     * text = 显示/上屏文本；action = 动作名（commit 时上屏 text）。
     */
    fun buildGesture(text: String, action: String, value: String = "", display: String = "key"): JSONObject {
        val o = JSONObject()
        o.put("label", text)
        o.put("action", action)
        if (value.isNotEmpty()) o.put("value", value)
        o.put("display", display)
        return o
    }

    /**
     * 把「空格分隔的多符号」解析为气泡 values 列表（每个符号一项 commit）。
     * 例如 "q Q à á" -> [q, Q, à, á]
     */
    fun buildBubbleValues(symbolsText: String): List<JSONObject> {
        return symbolsText.trim().split(Regex("\\s+"))
            .filter { it.isNotEmpty() }
            .map { buildGesture(it, "commit", it, "key") }
    }
}

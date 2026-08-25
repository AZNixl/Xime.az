package com.kingzcheung.xime.ui.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items as gridItems
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.kingzcheung.xime.settings.KeysConfigHelper
import com.kingzcheung.xime.settings.SettingsPreferences
import com.kingzcheung.xime.settings.UserKeysOverrides
import org.json.JSONObject

/** 可编辑的按键：字母 + 数字/符号 + 逗号 + 句号（earth） + 空格 + ?123 */
private val EDITABLE_KEYS = listOf(
    listOf("q", "w", "e", "r", "t", "y", "u", "i", "o", "p"),
    listOf("a", "s", "d", "f", "g", "h", "j", "k", "l"),
    listOf("z", "x", "c", "v", "b", "n", "m"),
    listOf("1", "2", "3", "4", "5", "6", "7", "8"),
    listOf("9", "0", ".", "'", "earth", "space", "mode_change"),
)

/** 键的显示名（编辑卡片/对话框标题用） */
private fun keyDisplayName(key: String): String = when (key) {
    "'" -> "，"
    "earth" -> "。"
    "space" -> "空格"
    "mode_change" -> "?123"
    else -> key.uppercase()
}

/** 手势动作下拉选项：动作值 -> 显示名 */
private val ACTION_OPTIONS = listOf(
    "commit" to "上屏文本",
    "select_all" to "全选",
    "select_2" to "次选",
    "cut" to "剪切",
    "copy" to "复制",
    "paste" to "粘贴",
    "line_start" to "行首",
    "line_end" to "行尾",
    "left" to "光标左移",
    "undo" to "撤销",
    "none" to "仅显示",
)

/** 长按显示模式下拉（"无"已去除：清空符号框即视为无长按） */
private val LONG_PRESS_DISPLAYS = listOf(
    "key" to "直发（不弹气泡）",
    "bubble" to "气泡多选",
)

/** 临时候选键（composing）选项 */
private val COMPOSING_OPTIONS = listOf(
    "" to "无",
    "select_2" to "第二候选",
    "select_3" to "第三候选",
    "Escape" to "取消编码",
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun KeySymbolEditContent(onBack: () -> Unit) {
    val context = LocalContext.current
    // 自 v2.6.2 起中英文键盘共用同一套符号配置（qwerty）
    val section = "qwerty"
    var editingKey by remember { mutableStateOf<String?>(null) }
    var refreshTick by remember { mutableStateOf(0) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("键盘符号编辑") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface
                )
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(16.dp)
        ) {
            // 符号显示总开关
            SymbolHintMasterSwitch(refreshTick)

            Spacer(modifier = Modifier.height(16.dp))

            Text(
                "自 v2.6.2 起，中英文键盘共用同一套符号配置。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Spacer(modifier = Modifier.height(12.dp))
            Text(
                "点击按键编辑其点按、上滑、下滑、长按（长按触发 180ms）",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                "长按/手势支持的功能动作（自行写入符号框）：select_all 全选 · select_2 次选 · cut 剪切 · copy 复制 · paste 粘贴 · line_start 行首 · line_end 行尾 · undo 撤销 · none 仅显示 · repeat 重复 · toggle_ascii 中英切换 · delete 退格 · command:clear_composition 清空输入 · command:show_ime_picker 选输入法",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.primary
            )
            Spacer(modifier = Modifier.height(8.dp))

            LazyVerticalGrid(
                columns = GridCells.Fixed(8),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
                modifier = Modifier.fillMaxSize()
            ) {
                gridItems(EDITABLE_KEYS.flatten()) { key ->
                    KeyEditCard(
                        key = key,
                        section = section,
                        isAscii = false,
                        refreshTick = refreshTick,
                        onClick = { editingKey = key }
                    )
                }
            }
        }
    }

    editingKey?.let { key ->
        KeyEditDialog(
            key = key,
            section = section,
            onDismiss = { editingKey = null },
            onSaved = {
                editingKey = null
                refreshTick++
                // 重新加载键盘配置使改动生效
                KeysConfigHelper.loadConfig(context)
            }
        )
    }
}

@Composable
private fun SymbolHintMasterSwitch(refreshTick: Int) {
    val context = LocalContext.current
    var enabled by remember(refreshTick) {
        mutableStateOf(SettingsPreferences.isSymbolHintsEnabled(context))
    }
    Card(
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow
        ),
        shape = RoundedCornerShape(12.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text("符号显示", fontWeight = FontWeight.Medium)
                Text(
                    "开启显示全部上滑/下滑符号，关闭则全部隐藏",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Switch(
                checked = enabled,
                onCheckedChange = {
                    enabled = it
                    SettingsPreferences.setSymbolHintsEnabled(context, it)
                }
            )
        }
    }
}

@Composable
private fun KeyEditCard(
    key: String,
    section: String,
    isAscii: Boolean,
    refreshTick: Int,
    onClick: () -> Unit,
) {
    val context = LocalContext.current
    // 读取当前生效配置（含用户覆盖）
    val gesture = remember(key, section, refreshTick) {
        KeysConfigHelper.getKeyGesture(key, isAscii)
    }
    val hasOverride = remember(key, section, refreshTick) {
        UserKeysOverrides.getKeyOverride(context, section, key) != null
    }
    val tapLabel = gesture?.tap?.label?.ifEmpty { null } ?: keyDisplayName(key)
    val upLabel = gesture?.swipeUp?.label ?: ""
    val downLabel = gesture?.swipeDown?.label ?: ""
    val lpCount = gesture?.longPress?.values?.size ?: 0
    val composingLabel = when (gesture?.composing) {
        "select_2" -> "2选"
        "select_3" -> "3选"
        "Escape" -> "取消"
        else -> ""
    }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        colors = CardDefaults.cardColors(
            containerColor = if (hasOverride)
                MaterialTheme.colorScheme.primaryContainer
            else
                MaterialTheme.colorScheme.surfaceContainerLow
        ),
        shape = RoundedCornerShape(8.dp)
    ) {
        Column(
            modifier = Modifier.padding(vertical = 6.dp, horizontal = 2.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(tapLabel, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleSmall)
            if (upLabel.isNotEmpty()) {
                Text("↑$upLabel", style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
            }
            if (downLabel.isNotEmpty()) {
                Text("↓${downLabel.take(2)}", style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
            }
            if (lpCount > 0) {
                Text("⋯$lpCount", style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary)
            }
            if (composingLabel.isNotEmpty()) {
                Text(composingLabel, style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.tertiary)
            }
        }
    }
}

@Composable
private fun KeyEditDialog(
    key: String,
    section: String,
    onDismiss: () -> Unit,
    onSaved: () -> Unit,
) {
    val context = LocalContext.current
    // 自 v2.6.2 起 section 固定为 qwerty，不再区分中英文
    val isAscii = false

    // 读取当前生效值作为编辑初值
    val current = remember(key, section) { KeysConfigHelper.getKeyGesture(key, isAscii) }

    var tapText by remember { mutableStateOf(current?.tap?.label ?: keyDisplayName(key)) }
    var upText by remember { mutableStateOf(current?.swipeUp?.label ?: "") }
    var downText by remember { mutableStateOf(current?.swipeDown?.label ?: "") }

    // 脏标记：只有用户实际改过的字段才写覆盖，未动的字段保留默认
    var tapDirty by remember { mutableStateOf(false) }
    var upDirty by remember { mutableStateOf(false) }
    var downDirty by remember { mutableStateOf(false) }
    var lpDirty by remember { mutableStateOf(false) }
    var composingDirty by remember { mutableStateOf(false) }

    val lpDisplay0 = current?.longPress?.display ?: ""
    var lpDisplay by remember {
        mutableStateOf(if (lpDisplay0 == "key" || lpDisplay0 == "bubble") lpDisplay0 else "bubble")
    }
    var lpValuesText by remember {
        mutableStateOf(current?.longPress?.values?.joinToString(" ") { it.label } ?: "")
    }

    val composing0 = current?.composing ?: ""
    var composingValue by remember {
        mutableStateOf(COMPOSING_OPTIONS.firstOrNull { it.first == composing0 }?.first ?: "")
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("编辑按键 ${keyDisplayName(key)}") },
        text = {
            LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                item {
                    OutlinedTextField(
                        value = tapText,
                        onValueChange = { tapText = it; tapDirty = true },
                        label = { Text("点按（tap）") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
                item {
                    OutlinedTextField(
                        value = upText,
                        onValueChange = { upText = it; upDirty = true },
                        label = { Text("上滑（留空清除）") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
                item {
                    OutlinedTextField(
                        value = downText,
                        onValueChange = { downText = it; downDirty = true },
                        label = { Text("下滑（留空清除）") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
                item {
                    Text("长按（long_press）", fontWeight = FontWeight.Medium)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        LONG_PRESS_DISPLAYS.forEach { (value, label) ->
                            FilterChip(
                                selected = lpDisplay == value,
                                onClick = { lpDisplay = value; lpDirty = true },
                                label = { Text(label, style = MaterialTheme.typography.bodySmall) }
                            )
                        }
                    }
                }
                item {
                    OutlinedTextField(
                        value = lpValuesText,
                        onValueChange = { lpValuesText = it; lpDirty = true },
                        label = { Text("长按符号/动作（空格分隔）") },
                        supportingText = {
                            Text(
                                if (lpDisplay == "bubble")
                                    "气泡内左右滑动选择；清空则去除长按"
                                else
                                    "直发模式仅首项生效；清空则去除长按"
                            )
                        },
                        modifier = Modifier.fillMaxWidth()
                    )
                }
                item {
                    Text("临时候选键（打字时切换显示）", fontWeight = FontWeight.Medium)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        COMPOSING_OPTIONS.forEach { (value, label) ->
                            FilterChip(
                                selected = composingValue == value,
                                onClick = { composingValue = value; composingDirty = true },
                                label = { Text(label, style = MaterialTheme.typography.bodySmall) }
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                saveKeyOverride(
                    context, section, key,
                    tapText, upText, downText,
                    tapDirty, upDirty, downDirty, lpDirty, composingDirty,
                    lpDisplay, lpValuesText, composingValue
                )
                onSaved()
            }) { Text("保存") }
        },
        dismissButton = {
            Row {
                TextButton(onClick = {
                    UserKeysOverrides.clearKey(context, section, key)
                    onSaved()
                }) { Text("恢复默认") }
                TextButton(onClick = onDismiss) { Text("取消") }
            }
        }
    )
}

/**
 * 解析符号框的一项（长按/上滑/下滑通用）：
 *  - 识别为动作名（select_all/copy/...）→ 该动作
 *  - "command:命令名" → command 动作
 *  - 其他 → 普通上屏符号（commit）
 */
private fun parseGestureItem(token: String): JSONObject {
    val actionNames = ACTION_OPTIONS.map { it.first }.toSet() +
        setOf("repeat", "toggle_ascii", "delete", "toggle_symbols", "switch_route", "left")
    return when {
        token.startsWith("command:") -> {
            val cmd = token.removePrefix("command:")
            UserKeysOverrides.buildGesture(cmd, "command", cmd, "key")
        }
        token in actionNames -> {
            val label = ACTION_OPTIONS.firstOrNull { it.first == token }?.second ?: token
            UserKeysOverrides.buildGesture(label, token, "", "key")
        }
        else -> UserKeysOverrides.buildGesture(token, "commit", token, "key")
    }
}

private fun saveKeyOverride(
    context: android.content.Context,
    section: String,
    key: String,
    tap: String,
    up: String,
    down: String,
    tapDirty: Boolean,
    upDirty: Boolean,
    downDirty: Boolean,
    lpDirty: Boolean,
    composingDirty: Boolean,
    lpDisplay: String,
    lpValuesText: String,
    composingValue: String,
) {
    // 仅保存用户实际改过的字段；留空 = 清除（覆盖默认符号）
    if (tapDirty) {
        if (tap.isNotEmpty()) {
            UserKeysOverrides.setGesture(context, section, key, "tap",
                UserKeysOverrides.buildGesture(tap, "commit", tap))
        } else {
            UserKeysOverrides.setGesture(context, section, key, "tap", null)
        }
    }
    if (upDirty) {
        if (up.isNotEmpty()) {
            UserKeysOverrides.setGesture(context, section, key, "swipe_up",
                parseGestureItem(up))
        } else {
            UserKeysOverrides.setGesture(context, section, key, "swipe_up", null)
        }
    }
    if (downDirty) {
        if (down.isNotEmpty()) {
            UserKeysOverrides.setGesture(context, section, key, "swipe_down",
                parseGestureItem(down).apply { put("display", "both") })
        } else {
            UserKeysOverrides.setGesture(context, section, key, "swipe_down", null)
        }
    }
    if (lpDirty) {
        if (lpValuesText.isBlank()) {
            // 清空符号框 = 去除长按
            UserKeysOverrides.setLongPress(context, section, key, "none", emptyList())
        } else {
            val values = lpValuesText.trim().split(Regex("\\s+"))
                .filter { it.isNotEmpty() }
                .map { parseGestureItem(it) }
            UserKeysOverrides.setLongPress(context, section, key, lpDisplay, values)
        }
    }
    if (composingDirty) {
        UserKeysOverrides.setComposing(context, section, key, composingValue)
    }
}

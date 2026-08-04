package com.kingzcheung.xime.ui.settings

import android.content.Intent
import android.graphics.BitmapFactory
import android.net.Uri
import android.util.Log
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.AddBox
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Extension
import androidx.compose.material.icons.filled.Face
import androidx.compose.material.icons.filled.FileOpen
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.kingzcheung.xime.plugin.core.api.PluginIcon
import com.kingzcheung.xime.plugin.core.model.Activation
import com.kingzcheung.xime.plugin.core.model.PluginCategory
import com.kingzcheung.xime.plugin.core.model.PluginSource
import com.kingzcheung.xime.plugin.core.model.PluginInfo
import com.kingzcheung.xime.plugin.core.model.TrustLevel
import com.kingzcheung.xime.plugin.core.runtime.PluginManager
import com.kingzcheung.xime.plugin.core.security.PluginErrorLog
import com.kingzcheung.xime.settings.SettingsPreferences
import com.kingzcheung.xime.viewmodel.PluginsSettingsViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PluginsSettingsContent(
    onBack: () -> Unit,
    onNavigateToPluginSettings: (String) -> Unit = {},
    onNavigateToSpeechToText: () -> Unit = {}
) {
    val context = LocalContext.current
    val viewModel: PluginsSettingsViewModel = viewModel()
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val loadedPlugins by viewModel.loadedPlugins.collectAsState()
    val importMessage by viewModel.importMessage.collectAsState()
    val groupedByCategory = remember(uiState.extensions) {
        uiState.extensions.groupBy { it.category }
    }

    val importLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenMultipleDocuments()
    ) { uris ->
        uris.forEach { viewModel.installPluginFromUri(it) }
    }

    var showWirelessSheet by remember { mutableStateOf(false) }
    var showMenu by remember { mutableStateOf(false) }

    LaunchedEffect(importMessage) {
        if (importMessage != null) {
            Toast.makeText(context, importMessage, Toast.LENGTH_SHORT).show()
            viewModel.consumeImportMessage()
        }
    }

    if (showWirelessSheet) {
        WirelessImportSheet(
            onDismiss = {
                showWirelessSheet = false
                viewModel.refreshPlugins()
            },
            onRefresh = { viewModel.refreshPlugins() }
        )
    }
    
    Scaffold(
        containerColor = MaterialTheme.colorScheme.surface,
        topBar = {
            TopAppBar(
                title = { Text("插件管理") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "返回"
                        )
                    }
                },
                actions = {
                    Box {
                        IconButton(onClick = { showMenu = true }) {
                            Icon(Icons.Default.MoreVert, contentDescription = "更多")
                        }
                        DropdownMenu(
                            expanded = showMenu,
                            onDismissRequest = { showMenu = false },
                            offset = DpOffset(0.dp, 4.dp),
                            shape = RoundedCornerShape(12.dp)
                        ) {
                            DropdownMenuItem(
                                text = { Text("从文件安装插件 (.xipk)") },
                                onClick = {
                                    showMenu = false
                                    importLauncher.launch(arrayOf("*/*"))
                                },
                                leadingIcon = {
                                    Icon(Icons.Default.FileOpen, null,
                                        tint = MaterialTheme.colorScheme.primary,
                                        modifier = Modifier.size(20.dp))
                                }
                            )
                            DropdownMenuItem(
                                text = { Text("浏览器导入") },
                                onClick = {
                                    showMenu = false
                                    showWirelessSheet = true
                                },
                                leadingIcon = {
                                    Icon(Icons.Default.Wifi, null,
                                        tint = MaterialTheme.colorScheme.primary,
                                        modifier = Modifier.size(20.dp))
                                }
                            )
                        }
                    }
                    IconButton(onClick = { viewModel.refreshPlugins() }) {
                        Icon(
                            imageVector = Icons.Default.Refresh,
                            contentDescription = "刷新"
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                    titleContentColor = MaterialTheme.colorScheme.onSurface
                ),
            )
        }
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .background(MaterialTheme.colorScheme.surface),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp)
        ) {
            if (uiState.isLoading) {
                item {
                    Box(
                        modifier = Modifier.fillMaxWidth(),
                        contentAlignment = Alignment.Center
                    ) {
                        CircularProgressIndicator()
                    }
                }
            } else if (uiState.errorMsg != null) {
                item {
                    Text(
                        text = "加载失败: ${uiState.errorMsg}",
                        color = MaterialTheme.colorScheme.error
                    )
                }
            } else {
                if (uiState.extensions.isEmpty()) {
                    item {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(32.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Column(
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.AddBox,
                                    contentDescription = null,
                                    modifier = Modifier.size(48.dp),
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
                                )
                                Text(
                                    text = "暂无已安装的插件",
                                    style = MaterialTheme.typography.bodyLarge,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                Text(
                                    text = "安装插件后将在此显示",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                                )
                            }
                        }
                    }
                } else {
                    val activeAsrPluginId = SettingsPreferences.getSttOnlinePluginId(context)
                    PluginCategory.entries.forEach { category ->
                        val plugins = groupedByCategory[category].orEmpty()
                        if (plugins.isEmpty()) return@forEach
                        val activeName = if (category == PluginCategory.ASR) {
                            plugins.firstOrNull { it.id == activeAsrPluginId }?.name
                        } else null
                        item(key = "header_${category.id}") {
                            PluginCategoryHeader(
                                category = category,
                                count = plugins.size,
                                activeName = activeName
                            )
                        }
                        items(plugins, key = { it.id }) { extension ->
                            val isRunning = loadedPlugins.containsKey(extension.id)
                            ExtensionItem(
                                extension = extension,
                                pluginInstance = PluginManager.getPluginInstance(extension.id),
                                icon = uiState.icons[extension.id],
                                isRunning = isRunning,
                                viewModel = viewModel,
                                onClick = { onNavigateToPluginSettings(extension.id) },
                                isActive = category == PluginCategory.ASR && extension.id == activeAsrPluginId,
                                onActivate = if (category.activation == Activation.SINGLE) {
                                    onNavigateToSpeechToText
                                } else null
                            )
                        }
                    }
                }
                
                item {
                    Spacer(modifier = Modifier.height(8.dp))
                    
                    Text(
                        text = "提示: 插件以独立 APK 形式安装，安装后点击右上角刷新按钮",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.outline.copy(alpha = 0.6f),
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)
                    )
                }
            }
        }
    }
}

@Composable
private fun ExtensionItem(
    extension: PluginInfo,
    pluginInstance: Any?,
    icon: PluginIcon?,
    isRunning: Boolean,
    viewModel: PluginsSettingsViewModel,
    onClick: () -> Unit = {},
    isActive: Boolean = false,
    onActivate: (() -> Unit)? = null
) {
    val context = LocalContext.current
    var isEnabled by remember { mutableStateOf(viewModel.isPluginEnabled(extension.id)) }
    var showErrorDialog by remember { mutableStateOf(false) }
    var isExpanded by remember { mutableStateOf(false) }
    var showDeleteConfirm by remember { mutableStateOf(false) }
    var showTrustConfirm by remember { mutableStateOf(false) }
    var trustConfirmAction by remember { mutableStateOf<(() -> Unit)?>(null) }
    
    val errors = PluginErrorLog.getErrors(extension.id)
    val hasErrors = errors.isNotEmpty()

    val hostCompatible = remember(extension.id) { viewModel.isHostCompatible(extension) }
    val hostRange = remember(extension) {
        buildString {
            if (!extension.minHostVersion.isNullOrBlank()) append("v${extension.minHostVersion}")
            if (!extension.maxHostVersion.isNullOrBlank()) {
                if (isNotEmpty()) append(" - ") else append("≤ ")
                append("v${extension.maxHostVersion}")
            }
        }
    }
    
    val hasSettings = pluginInstance?.let {
        (it as? com.kingzcheung.xime.plugin.core.config.IPluginConfigurable)
            ?.getSettingsSchema()?.isNotEmpty() == true ||
            (it as? com.kingzcheung.xime.plugin.core.api.EmojiPlugin)?.hasSettings() == true
    } ?: false
    
    if (showErrorDialog && hasErrors) {
        PluginErrorDialog(
            pluginId = extension.id,
            pluginName = extension.name,
            errors = errors,
            onDismiss = { showErrorDialog = false },
            onClear = { 
                PluginErrorLog.clearErrors(extension.id)
                showErrorDialog = false
            }
        )
    }
    
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
        shape = RoundedCornerShape(12.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { isExpanded = !isExpanded }
                .padding(12.dp)
        ) {
            // 第一行：图标 + 标题 + 状态指示器
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                PluginIconView(
                    icon = icon,
                    category = extension.category,
                    modifier = Modifier.padding(end = 10.dp)
                )

                Text(
                    text = extension.name,
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.Medium,
                    modifier = Modifier.weight(1f),
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
                
                // 状态指示器（固定在右侧）
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    val runningColor = Color(0xFF4CAF50)  // 绿色
                    Box(
                        modifier = Modifier
                            .size(6.dp)
                            .background(
                                if (isRunning) runningColor 
                                else MaterialTheme.colorScheme.outline,
                                shape = RoundedCornerShape(3.dp)
                            )
                    )
                    Text(
                        text = if (isRunning) "运行中" else "未运行",
                        style = MaterialTheme.typography.labelSmall,
                        color = if (isRunning) runningColor 
                               else MaterialTheme.colorScheme.outline
                    )
                    
                    if (hasErrors) {
                        Icon(
                            Icons.Default.Warning,
                            contentDescription = "有错误",
                            tint = MaterialTheme.colorScheme.error,
                            modifier = Modifier.size(14.dp)
                        )
                    }

                    if (!hostCompatible) {
                        Icon(
                            Icons.Default.Warning,
                            contentDescription = "与主应用版本不兼容",
                            tint = MaterialTheme.colorScheme.error,
                            modifier = Modifier.size(14.dp)
                        )
                    }
                    
                    // 展开指示器
                    Icon(
                        if (isExpanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                        contentDescription = if (isExpanded) "收起" else "展开",
                        modifier = Modifier.size(18.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
                    )
                }
            }
            
            // 第二行：类型 + 版本
            Row(
                modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Text(
                    text = extension.category.label,
                    style = MaterialTheme.typography.bodySmall,
                    color = if (isEnabled) MaterialTheme.colorScheme.primary 
                           else MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text("•", style = MaterialTheme.typography.bodySmall, 
                     color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f))
                Text(
                    text = "v${extension.versionName}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                
                if (hasSettings) {
                    Text("•", style = MaterialTheme.typography.bodySmall, 
                         color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f))
                    Text(
                        text = "可配置",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary.copy(alpha = 0.7f)
                    )
                }

                // 信任徽标
                val trustBadge = trustBadge(extension.trustLevel)
                Text("•", style = MaterialTheme.typography.bodySmall,
                     color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f))
                Text(
                    text = trustBadge.first,
                    style = MaterialTheme.typography.bodySmall,
                    color = trustBadge.second
                )
            }
            
            // 展开详情
            AnimatedVisibility(visible = isExpanded) {
                Column(
                    modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f))
                    
                    if (extension.description.isNotEmpty()) {
                        Text(
                            text = extension.description,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                        )
                    }

                    if (!hostCompatible) {
                        Text(
                            text = "该插件不支持当前主应用版本，已跳过加载" +
                                if (hostRange.isNotEmpty()) "（要求 $hostRange）" else "。请更新主应用后重试。",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error.copy(alpha = 0.8f)
                        )
                    }
                    
                    // 操作按钮行
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        if (extension.category.activation == Activation.SINGLE) {
                            // 单选分类：激活在对应功能设置页完成，插件中心不显示启用开关
                            if (!hostCompatible) {
                                Text(
                                    text = "与主应用版本不兼容",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.error
                                )
                            } else {
                                Text(
                                    text = if (isActive) "当前使用中" else "未使用",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = if (isActive) MaterialTheme.colorScheme.primary
                                    else MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }

                            Spacer(modifier = Modifier.weight(1f))

                            if (!hostCompatible && hostRange.isNotEmpty()) {
                                Text(
                                    text = "要求 $hostRange",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
                                )
                            } else if (!isActive && onActivate != null) {
                                OutlinedButton(
                                    onClick = {
                                        if (extension.trustLevel == TrustLevel.TRUSTED) {
                                            onActivate()
                                        } else {
                                            trustConfirmAction = { onActivate() }
                                            showTrustConfirm = true
                                        }
                                    },
                                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)
                                ) {
                                    Text("去选择", style = MaterialTheme.typography.labelMedium)
                                }
                            }
                        } else {
                            // 多选分类：启用开关
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Text(
                                    text = if (hostCompatible) "启用" else "不兼容",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = if (hostCompatible) MaterialTheme.colorScheme.onSurface
                                    else MaterialTheme.colorScheme.error
                                )
                                Switch(
                                    checked = isEnabled,
                                    enabled = hostCompatible,
                                    onCheckedChange = { enabled ->
                                        if (enabled && extension.trustLevel != TrustLevel.TRUSTED) {
                                            trustConfirmAction = {
                                                isEnabled = true
                                                viewModel.setPluginEnabled(extension.id, true)
                                            }
                                            showTrustConfirm = true
                                        } else {
                                            isEnabled = enabled
                                            viewModel.setPluginEnabled(extension.id, enabled)
                                        }
                                    },
                                    colors = SwitchDefaults.colors(
                                        checkedThumbColor = MaterialTheme.colorScheme.primary,
                                        checkedTrackColor = MaterialTheme.colorScheme.primaryContainer
                                    )
                                )
                            }

                            Spacer(modifier = Modifier.weight(1f))
                        }

                        // 设置按钮
                        if (hasSettings) {
                            OutlinedButton(
                                onClick = onClick,
                                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)
                            ) {
                                Icon(Icons.Default.Settings, contentDescription = null, modifier = Modifier.size(16.dp))
                                Spacer(modifier = Modifier.width(4.dp))
                                Text("设置", style = MaterialTheme.typography.labelMedium)
                            }
                        }
                        
                        // 删除按钮
                        if (extension.source == PluginSource.SYSTEM) {
                            IconButton(
                                onClick = {
                                    try {
                                        val intent = Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
                                        intent.data = Uri.parse("package:${extension.id}")
                                        context.startActivity(intent)
                                        Toast.makeText(context, "请在应用信息页面卸载", Toast.LENGTH_SHORT).show()
                                    } catch (e: Exception) {
                                        Toast.makeText(context, "无法打开: ${e.message}", Toast.LENGTH_SHORT).show()
                                    }
                                }
                            ) {
                                Icon(Icons.Default.Delete, contentDescription = "卸载",
                                     tint = MaterialTheme.colorScheme.error)
                            }
                        } else {
                            IconButton(
                                onClick = { showDeleteConfirm = true }
                            ) {
                                Icon(Icons.Default.Delete, contentDescription = "卸载",
                                     tint = MaterialTheme.colorScheme.error)
                            }
                        }
                    }
                }
            }
        }
    }

    if (showDeleteConfirm) {
        AlertDialog(
            onDismissRequest = { showDeleteConfirm = false },
            title = { Text("卸载插件") },
            text = { Text("确定要卸载「${extension.name}」吗？\n插件文件和配置将被删除，此操作不可恢复。") },
            confirmButton = {
                TextButton(
                    onClick = {
                        showDeleteConfirm = false
                        viewModel.uninstallPlugin(extension.id)
                    }
                ) {
                    Text("卸载", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteConfirm = false }) {
                    Text("取消")
                }
            }
        )
    }

    if (showTrustConfirm) {
        val badge = trustBadge(extension.trustLevel)
        AlertDialog(
            onDismissRequest = {
                showTrustConfirm = false
                trustConfirmAction = null
            },
            title = { Text("启用非官方插件") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("插件「${extension.name}」未被标记为官方（${badge?.first ?: "未知来源"}）。")
                    Text("非官方插件代码运行在主应用进程中，可能访问您输入的内容或网络。请确认来源可信后再启用。")
                    if (extension.description.isNotEmpty()) {
                        Text(
                            text = "描述：${extension.description}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        val action = trustConfirmAction
                        showTrustConfirm = false
                        trustConfirmAction = null
                        action?.invoke()
                    }
                ) {
                    Text("继续启用")
                }
            },
            dismissButton = {
                TextButton(onClick = {
                    showTrustConfirm = false
                    trustConfirmAction = null
                }) {
                    Text("取消")
                }
            }
        )
    }
}

// 信任徽标：返回 (标签, 颜色)
private fun trustBadge(level: TrustLevel): Pair<String, Color> {
    return when (level) {
        TrustLevel.TRUSTED -> Pair("官方", Color(0xFF4CAF50))
        TrustLevel.THIRD_PARTY -> Pair("第三方", Color(0xFFF57C00))
        TrustLevel.UNKNOWN -> Pair("未知来源", Color(0xFFE53935))
    }
}

// 渲染插件图标：优先用插件提供的本地图标（文字或已提取到本地的资源文件），否则用分类默认图标
@Composable
private fun PluginIconView(
    icon: PluginIcon?,
    category: PluginCategory,
    modifier: Modifier = Modifier
) {
    val iconText = icon?.text
    if (!iconText.isNullOrBlank()) {
        Box(
            modifier = modifier
                .size(36.dp)
                .background(
                    MaterialTheme.colorScheme.surfaceVariant,
                    shape = RoundedCornerShape(10.dp)
                ),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = iconText,
                style = MaterialTheme.typography.titleMedium,
                maxLines = 1
            )
        }
        return
    }

    val assetPath = icon?.assetName
    if (assetPath != null) {
        val bitmap = remember(assetPath) {
            runCatching { BitmapFactory.decodeFile(assetPath) }.getOrNull()
        }
        if (bitmap != null) {
            val imageBitmap = remember(assetPath) { bitmap.asImageBitmap() }
            Image(
                bitmap = imageBitmap,
                contentDescription = null,
                modifier = modifier
                    .size(36.dp)
                    .background(
                        MaterialTheme.colorScheme.surfaceVariant,
                        shape = RoundedCornerShape(10.dp)
                    ),
                contentScale = ContentScale.Crop
            )
            return
        }
    }

    Box(
        modifier = modifier
            .size(36.dp)
            .background(
                MaterialTheme.colorScheme.surfaceVariant,
                shape = RoundedCornerShape(10.dp)
            ),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            imageVector = getCategoryIcon(category),
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(20.dp)
        )
    }
}

@Composable
private fun PluginCategoryHeader(
    category: PluginCategory,
    count: Int,
    activeName: String? = null
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 8.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Icon(
            imageVector = getCategoryIcon(category),
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(18.dp)
        )
        Text(
            text = category.label,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold
        )
        Text(
            text = "($count)",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(modifier = Modifier.weight(1f))
        when {
            category.activation == Activation.SINGLE && !activeName.isNullOrBlank() -> {
                Text(
                    text = "当前使用：$activeName",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(0.6f, fill = false)
                )
            }
            category.activation == Activation.SINGLE -> {
                Text(
                    text = "未选择 · 去语音转文本设置",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
                )
            }
            category.activation == Activation.MULTI -> {
                Text(
                    text = "可多选 · 启用即生效",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
                )
            }
            else -> {}
        }
    }
}

private fun getCategoryIcon(category: PluginCategory): ImageVector = when (category) {
    PluginCategory.EMOJI -> Icons.Default.Face
    PluginCategory.ASR -> Icons.Default.Mic
    PluginCategory.PREDICTION -> Icons.Default.AutoAwesome
    PluginCategory.UNKNOWN -> Icons.Default.Extension
}

@Composable
private fun PluginErrorDialog(
    pluginId: String,
    pluginName: String,
    errors: List<PluginErrorLog.PluginError>,
    onDismiss: () -> Unit,
    onClear: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { 
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    Icons.Default.Warning,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.error,
                    modifier = Modifier.size(24.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text("$pluginName 错误日志")
            }
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(300.dp)
                    .verticalScroll(rememberScrollState())
            ) {
                errors.forEachIndexed { index, error ->
                    Card(
                        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.3f)
                        )
                    ) {
                        Column(modifier = Modifier.padding(8.dp)) {
                            Text(
                                text = "#${index + 1} ${error.operation}",
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.error
                            )
                            Text(
                                text = error.message,
                                style = MaterialTheme.typography.bodySmall
                            )
                            val stackTraceText = error.stackTrace
                            if (stackTraceText != null && stackTraceText.isNotEmpty()) {
                                Spacer(modifier = Modifier.height(4.dp))
                                Text(
                                    text = stackTraceText.take(200) + "...",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                                )
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onClear) {
                Text("清除日志", color = MaterialTheme.colorScheme.error)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("关闭")
            }
        }
    )
}
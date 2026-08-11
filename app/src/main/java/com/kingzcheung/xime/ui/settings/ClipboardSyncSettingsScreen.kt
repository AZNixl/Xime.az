package com.kingzcheung.xime.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.twotone.Refresh
import androidx.compose.material.icons.twotone.Sync
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.kingzcheung.xime.plugin.ExtensionManager
import com.kingzcheung.xime.settings.SettingsPreferences

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ClipboardSyncSettingsContent(
    onBack: () -> Unit,
    onNavigateToPlugins: () -> Unit
) {
    val context = LocalContext.current
    var enabled by remember {
        mutableStateOf(SettingsPreferences.isClipboardSyncEnabled(context))
    }
    var pullOnOpen by remember {
        mutableStateOf(SettingsPreferences.isClipboardSyncPullOnOpen(context))
    }
    val syncPlugins = remember { ExtensionManager.getEnabledClipboardSyncPlugins(context) }
    val installedPlugins = remember { ExtensionManager.getAllInstalledPlugins() }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.surface,
        topBar = {
            TopAppBar(
                title = { Text("剪贴板同步") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "返回"
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
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 16.dp)
                .imePadding()
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            SettingsSection(
                title = "同步开关",
                content = {
                    SettingsToggleItem(
                        icon = Icons.TwoTone.Sync,
                        title = "启用剪贴板同步",
                        subtitle = "将剪贴板文本与远端设备双向同步",
                        checked = enabled,
                        onCheckedChange = { checked ->
                            enabled = checked
                            SettingsPreferences.setClipboardSyncEnabled(context, checked)
                        }
                    )
                    SettingsToggleItem(
                        icon = Icons.TwoTone.Refresh,
                        title = "仅打开键盘时拉取",
                        subtitle = "开启后不持续轮询，仅在键盘弹出时从远端拉取一次",
                        checked = pullOnOpen,
                        onCheckedChange = { checked ->
                            pullOnOpen = checked
                            SettingsPreferences.setClipboardSyncPullOnOpen(context, checked)
                        }
                    )
                }
            )

            if (enabled) {
                val plugin = syncPlugins.firstOrNull()
                if (plugin == null) {
                    SettingsSection(
                        title = "同步服务",
                        content = {
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(16.dp),
                                verticalArrangement = Arrangement.spacedBy(12.dp)
                            ) {
                                Text(
                                    text = "未启用剪贴板同步插件，请先在插件中心启用后再配置。",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                Button(
                                    onClick = onNavigateToPlugins,
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Text("前往插件中心")
                                }
                            }
                        }
                    )
                } else {
                    val pluginId = plugin.first
                    val pluginName = installedPlugins.find { it.id == pluginId }?.name ?: pluginId
                    PluginConfigFormScreen(
                        pluginId = pluginId,
                        plugin = plugin.second,
                        pluginName = pluginName,
                        onBack = {},
                        embedded = true
                    )
                }
            }
        }
    }
}

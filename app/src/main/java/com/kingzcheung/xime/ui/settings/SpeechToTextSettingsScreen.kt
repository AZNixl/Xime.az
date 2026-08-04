package com.kingzcheung.xime.ui.settings

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.Extension
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults

import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.kingzcheung.xime.R
import com.kingzcheung.xime.model.ModelManager
import com.kingzcheung.xime.plugin.ExtensionManager
import com.kingzcheung.xime.plugin.core.api.AsrInputMode
import com.kingzcheung.xime.plugin.core.runtime.PluginManager
import com.kingzcheung.xime.settings.SettingsPreferences
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

data class AsrProvider(
    val id: String,
    val name: String,
    val description: String,
    val iconRes: Int? = null,
    val icon: ImageVector? = null,
    val isOnline: Boolean,
    val isConfigured: Boolean,
    val features: List<String> = emptyList(),
    val needsAutoPunctuation: Boolean = false
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SpeechToTextSettingsContent(
    onBack: () -> Unit,
    onNavigateToModelManagement: () -> Unit = {},
    onNavigateToPluginSettings: (String) -> Unit = {},
    onNavigateToPlugins: () -> Unit = {}
) {
    val context = LocalContext.current

    val onlineProviders = remember {
        val asrPlugins = ExtensionManager.getEnabledAsrPlugins(context)
        mutableStateListOf<AsrProvider>().apply {
            asrPlugins.forEach { (pluginId, plugin) ->
                val caps = plugin.getCapabilities()
                add(
                    AsrProvider(
                        id = pluginId,
                        name = plugin.getDisplayName(),
                        description = "在线语音识别插件",
                        isOnline = true,
                        isConfigured = plugin.isConfigured(),
                        features = buildList {
                            add(if (caps.inputMode == AsrInputMode.STREAMING) "实时流式" else "文件识别")
                            if (caps.supportsPartialResults) add("中间结果")
                            if (caps.requiresNetwork) add("在线")
                        }
                    )
                )
            }
        }
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.surface,
        topBar = {
            TopAppBar(
                title = { Text("语音转文本") },
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
        ) {
            Spacer(modifier = Modifier.height(8.dp))

            val scope = rememberCoroutineScope()
            OnlineAsrTab(
                providers = onlineProviders,
                onProviderClick = { provider ->
                    SettingsPreferences.setSttOnlinePluginId(context, provider.id)
                    // 单选激活：选中即启用并确保插件已加载，插件中心不再提供启用开关
                    SettingsPreferences.setPluginEnabled(context, provider.id, true)
                    scope.launch(Dispatchers.IO) {
                        PluginManager.launchPlugin(provider.id)
                    }
                    onNavigateToPluginSettings(provider.id)
                },
                onManagePlugins = onNavigateToPlugins
            )

            Spacer(modifier = Modifier.height(16.dp))

            PunctuationModelSection(
                onNavigateToModelManagement = onNavigateToModelManagement
            )
        }
    }
}

@Composable
fun OnlineAsrTab(
    providers: List<AsrProvider>,
    onProviderClick: (AsrProvider) -> Unit,
    onManagePlugins: () -> Unit = {}
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            Text(
                text = "选择在线语音识别服务商",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(bottom = 8.dp)
            )
        }

        items(providers) { provider ->
            AsrProviderCardModern(
                provider = provider,
                onClick = { onProviderClick(provider) }
            )
        }

        item {
            OutlinedButton(
                onClick = onManagePlugins,
                modifier = Modifier.fillMaxWidth()
            ) {
                Icon(Icons.Default.Extension, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(modifier = Modifier.width(4.dp))
                Text("管理插件")
            }
        }

        item {
            Spacer(modifier = Modifier.height(8.dp))

            Text(
                text = "在线 ASR 需要网络连接，适合需要高准确率的场景",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.outline.copy(alpha = 0.7f),
                modifier = Modifier.padding(top = 8.dp)
            )
        }
    }
}

@Composable
fun PunctuationModelSection(
    onNavigateToModelManagement: () -> Unit
) {
    val context = LocalContext.current
    var isEnabled by remember { mutableStateOf(SettingsPreferences.isPunctuationModelEnabled(context)) }
    val isDownloaded by remember { mutableStateOf(
        ModelManager.isModelDownloaded(context, "punctuation_int8")
    ) }

    Column(modifier = Modifier.fillMaxWidth()) {
        Text(
            text = "标点预测模型",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(bottom = 8.dp)
        )

        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surface
            )
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(
                        modifier = Modifier
                            .size(48.dp)
                            .clip(RoundedCornerShape(12.dp))
                            .background(
                                if (isDownloaded)
                                    MaterialTheme.colorScheme.primary.copy(alpha = 0.15f)
                                else
                                    MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.1f)
                            ),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Default.Storage,
                            contentDescription = null,
                            tint = if (isDownloaded)
                                MaterialTheme.colorScheme.primary
                            else
                                MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                            modifier = Modifier.size(24.dp)
                        )
                    }

                    Spacer(modifier = Modifier.width(16.dp))

                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "Transformer 标点模型",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold
                        )

                        Spacer(modifier = Modifier.height(2.dp))

                        Text(
                            text = "为语音识别结果添加标点符号",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )

                        Spacer(modifier = Modifier.height(4.dp))

                        Row(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Surface(
                                shape = RoundedCornerShape(4.dp),
                                color = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.5f)
                            ) {
                                Text(
                                    text = "2.2 MB",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.secondary,
                                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                )
                            }
                            if (isDownloaded) {
                                Surface(
                                    shape = RoundedCornerShape(4.dp),
                                    color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f)
                                ) {
                                    Text(
                                        text = "已下载",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.primary,
                                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                    )
                                }
                            } else {
                                Surface(
                                    shape = RoundedCornerShape(4.dp),
                                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
                                ) {
                                    Text(
                                        text = "未下载",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                    )
                                }
                            }
                        }
                    }
                }

                if (!isDownloaded) {
                    Spacer(modifier = Modifier.height(8.dp))
                    OutlinedButton(
                        onClick = onNavigateToModelManagement
                    ) {
                        Icon(
                            Icons.Default.CloudDownload,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(Modifier.width(4.dp))
                        Text("前往下载")
                    }
                }

                Spacer(modifier = Modifier.height(12.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "启用智能标点",
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.Medium
                        )
                        Text(
                            text = if (isEnabled) "使用 AI 模型预测标点" else "使用简单规则添加标点",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.outline.copy(alpha = 0.7f)
                        )
                    }
                    Switch(
                        checked = isEnabled,
                        onCheckedChange = {
                            isEnabled = it
                            SettingsPreferences.setPunctuationModelEnabled(context, it)
                        },
                        enabled = isDownloaded,
                        colors = SwitchDefaults.colors(
                            checkedThumbColor = MaterialTheme.colorScheme.primary,
                            checkedTrackColor = MaterialTheme.colorScheme.primaryContainer
                        )
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(8.dp))

        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(12.dp),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surface
            )
        ) {
            Row(
                modifier = Modifier.padding(12.dp),
                verticalAlignment = Alignment.Top,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Icon(
                    Icons.Default.Warning,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.outline,
                    modifier = Modifier.size(16.dp)
                )
                Text(
                    text = "标点预测模型基于 Transformer 架构，可自动为语音识别结果添加逗号、句号、问号、感叹号等标点符号",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.outline.copy(alpha = 0.7f)
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AsrProviderCardModern(
    provider: AsrProvider,
    onClick: () -> Unit,
    enabled: Boolean = true
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        onClick = { if (enabled) onClick() },
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier
                        .size(48.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(
                            if (provider.iconRes != null)
                                MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f)
                            else if (enabled && provider.isConfigured)
                                MaterialTheme.colorScheme.primary
                            else
                                MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.3f)
                        ),
                    contentAlignment = Alignment.Center
                ) {
                    if (provider.iconRes != null) {
                        Icon(
                            painter = painterResource(provider.iconRes),
                            contentDescription = null,
                            modifier = Modifier.size(32.dp)
                        )
                    } else if (provider.icon != null) {
                        Icon(
                            imageVector = provider.icon,
                            contentDescription = null,
                            tint = if (enabled && provider.isConfigured)
                                MaterialTheme.colorScheme.onPrimary
                            else
                                MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                            modifier = Modifier.size(24.dp)
                        )
                    }
                }

                Spacer(modifier = Modifier.width(16.dp))

                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = provider.name,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = if (enabled)
                            MaterialTheme.colorScheme.onSurface
                        else
                            MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                    )

                    Spacer(modifier = Modifier.height(4.dp))

                    Text(
                        text = provider.description,
                        style = MaterialTheme.typography.bodyMedium,
                        color = if (enabled)
                            MaterialTheme.colorScheme.onSurfaceVariant
                        else
                            MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
                    )
                }

                if (enabled) {
                    Surface(
                        shape = RoundedCornerShape(8.dp),
                        color = if (provider.isConfigured)
                            MaterialTheme.colorScheme.primary
                        else
                            MaterialTheme.colorScheme.surface.copy(alpha = 0.9f)
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            if (provider.isConfigured) {
                                Icon(
                                    Icons.Default.Check,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.onPrimary,
                                    modifier = Modifier.size(14.dp)
                                )
                                Text(
                                    text = "已配置",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onPrimary,
                                    fontWeight = FontWeight.Medium
                                )
                            } else {
                                Icon(
                                    Icons.Default.Key,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.size(14.dp)
                                )
                                Text(
                                    text = "待配置",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }

                    Spacer(modifier = Modifier.width(8.dp))

                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                        contentDescription = "进入设置",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            if (provider.features.isNotEmpty()) {
                Spacer(modifier = Modifier.height(12.dp))

                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    provider.features.forEach { feature ->
                        Surface(
                            shape = RoundedCornerShape(4.dp),
                            color = MaterialTheme.colorScheme.tertiaryContainer.copy(alpha = 0.3f)
                        ) {
                            Text(
                                text = feature,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.tertiary,
                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                            )
                        }
                    }
                }
            }
        }
    }
}

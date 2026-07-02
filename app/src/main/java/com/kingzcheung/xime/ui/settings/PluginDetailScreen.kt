package com.kingzcheung.xime.ui.settings

import android.widget.Toast
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.kingzcheung.xime.plugin.ExtensionManager

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PluginSettingsContent(
    pluginId: String,
    onBack: () -> Unit
) {
    val context = LocalContext.current
    val pluginInstance = remember(pluginId) { ExtensionManager.getPluginById(pluginId) }
    val pluginInfo = remember(pluginId) { ExtensionManager.getAllInstalledPlugins().find { it.id == pluginId } }
    
    if (pluginInstance == null || pluginInfo == null) {
        Scaffold(
            topBar = {
                TopAppBar(
                    title = { Text("插件设置") },
                    navigationIcon = {
                        IconButton(onClick = onBack) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                                contentDescription = "返回"
                            )
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = MaterialTheme.colorScheme.background,
                        titleContentColor = MaterialTheme.colorScheme.onBackground
                    )
                )
            }
        ) {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center
            ) {
                Text("插件未找到")
            }
        }
        return
    }
    
    val hasSettings = when (pluginInstance) {
        is com.kingzcheung.xime.plugin.core.api.EmojiPlugin -> pluginInstance.hasSettings()
        else -> false
    }
    
    if (!hasSettings) {
        Scaffold(
            topBar = {
                TopAppBar(
                    title = { Text(pluginInfo.name) },
                    navigationIcon = {
                        IconButton(onClick = onBack) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                                contentDescription = "返回"
                            )
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = MaterialTheme.colorScheme.background,
                        titleContentColor = MaterialTheme.colorScheme.onBackground
                    )
                )
            }
        ) {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center
            ) {
                Text("该插件没有设置界面")
            }
        }
        return
    }
    
    LaunchedEffect(Unit) {
        try {
            when (pluginInstance) {
                is com.kingzcheung.xime.plugin.core.api.EmojiPlugin -> pluginInstance.openSettings(context)
            }
            onBack()
        } catch (e: Exception) {
            Toast.makeText(context, "无法打开插件设置: ${e.message}", Toast.LENGTH_LONG).show()
            onBack()
        }
    }
}

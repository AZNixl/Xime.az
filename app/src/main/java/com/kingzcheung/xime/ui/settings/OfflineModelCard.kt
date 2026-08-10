package com.kingzcheung.xime.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.kingzcheung.xime.speech.AsrModelManager
import com.kingzcheung.xime.speech.AudioSink
import com.kingzcheung.xime.speech.OfflineAsrTestRunner
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * 集成在语音转文本设置页内的离线模型状态卡片。
 * 模型由"模型中心"下载（filesDir/models/zipformer-zh-int8/），
 * 本卡片仅展示安装状态、提供识别测试与调试信息。
 */
@Composable
internal fun OfflineModelCard() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val modelManager = remember { AsrModelManager(context) }

    var selectedModelId by remember {
        mutableStateOf(modelManager.getSelectedModelId())
    }
    var refreshTick by remember { mutableStateOf(0) }
    var testing by remember { mutableStateOf(false) }
    var testResult by remember { mutableStateOf<String?>(null) }
    var testPartial by remember { mutableStateOf<String?>(null) }
    var latestRecording by remember { mutableStateOf<File?>(null) }

    // 每次进入页面/刷新时读取最近一次识别录音
    LaunchedEffect(refreshTick) {
        latestRecording = AudioSink.latestRecording(context)
    }

    val model = AsrModelManager.AVAILABLE_MODELS.firstOrNull { it.id == selectedModelId }
        ?: AsrModelManager.AVAILABLE_MODELS.first()
    val downloaded = modelManager.isModelReady()

    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surface
            )
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Surface(
                        shape = RoundedCornerShape(12.dp),
                        color = if (downloaded)
                            MaterialTheme.colorScheme.primary.copy(alpha = 0.15f)
                        else
                            MaterialTheme.colorScheme.surfaceVariant
                    ) {
                        Icon(
                            Icons.Default.Memory,
                            contentDescription = null,
                            tint = if (downloaded)
                                MaterialTheme.colorScheme.primary
                            else
                                MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(12.dp).size(24.dp)
                        )
                    }

                    Spacer(modifier = Modifier.width(12.dp))

                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "离线语音识别",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold
                        )
                        Text(
                            text = "${model.name} · ${model.size}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }

                    if (downloaded) {
                        Surface(
                            shape = RoundedCornerShape(8.dp),
                            color = MaterialTheme.colorScheme.primary
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(4.dp)
                            ) {
                                Icon(
                                    Icons.Default.Check,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.onPrimary,
                                    modifier = Modifier.size(14.dp)
                                )
                                Text(
                                    text = "已安装",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onPrimary,
                                    fontWeight = FontWeight.Medium
                                )
                            }
                        }
                    }
                }

                Text(
                    text = if (downloaded)
                        "本地 Zipformer 流式识别，无网络也能用，识别在独立进程运行。"
                    else
                        "尚未安装模型，请前往「模型中心」下载「${model.name}」。",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                // ---- 测试识别（用真实录音或内置音频验证 C++ 推理链路）----
                if (downloaded && !testing) {
                    Button(
                        onClick = {
                            testing = true
                            testResult = null
                            testPartial = null
                            val file = latestRecording
                            scope.launch {
                                val result = if (file != null) {
                                    OfflineAsrTestRunner.runFile(
                                        context = context,
                                        wavFile = file,
                                        onPartial = { partial ->
                                            scope.launch { testPartial = partial }
                                        }
                                    )
                                } else {
                                    OfflineAsrTestRunner.run(
                                        context = context,
                                        onPartial = { partial ->
                                            scope.launch { testPartial = partial }
                                        }
                                    )
                                }
                                withContext(Dispatchers.Main) {
                                    testing = false
                                    testResult = if (result.success) {
                                        "识别结果：${result.text.ifEmpty { "（空）" }}" +
                                            (if (file != null) "（真实录音）" else "（内置音频）")
                                    } else {
                                        "测试失败：${result.error}"
                                    }
                                }
                            }
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("测试本地识别（最近录音）")
                    }
                } else if (testing) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Text(
                            text = "识别中…",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                testPartial?.let { partial ->
                    Text(
                        text = "中间结果：$partial",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                testResult?.let { result ->
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        if (result.startsWith("测试失败")) {
                            Icon(
                                Icons.Default.ErrorOutline,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.error,
                                modifier = Modifier.size(16.dp)
                            )
                        }
                        Text(
                            text = result,
                            style = MaterialTheme.typography.bodyMedium,
                            color = if (result.startsWith("测试失败"))
                                MaterialTheme.colorScheme.error
                            else
                                MaterialTheme.colorScheme.primary
                        )
                    }
                }

                // ---- 调试录音信息 ----
                latestRecording?.let { file ->
                    Text(
                        text = "最近录音：${file.name}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.outline.copy(alpha = 0.7f)
                    )
                    Text(
                        text = "路径：${file.absolutePath}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.outline.copy(alpha = 0.5f)
                    )
                }
            }
        }
    }
}

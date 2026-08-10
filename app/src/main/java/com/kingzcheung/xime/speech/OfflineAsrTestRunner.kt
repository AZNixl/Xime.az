package com.kingzcheung.xime.speech

import android.content.Context
import com.kingzcheung.xime.service.AsrInferenceClient
import com.kingzcheung.xime.util.FileLogger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.InputStream

/**
 * 离线 ASR 测试工具：读取 assets 中的 wav（16k mono 16bit），
 * 通过 [AsrInferenceService] 完整跑一遍识别链路，用于区分
 * "C++ 推理问题" 与 "录音/麦克风问题"。
 */
object OfflineAsrTestRunner {

    private const val TAG = "OfflineAsrTestRunner"
    private const val SAMPLE_RATE = 16000
    private const val CHUNK_SAMPLES = 1600  // 0.1s

    data class TestResult(
        val text: String,
        val success: Boolean,
        val error: String = ""
    )

    /**
     * 用 assets 中的 [assetName] 执行一次离线识别。
     * 返回最终文本；[onPartial] 回调中间结果（可选）。
     */
    suspend fun run(
        context: Context,
        assetName: String = "asr_test.wav",
        onPartial: (String) -> Unit = {}
    ): TestResult {
        // 优先使用 app 内部文件 files/asr_test/asr_test.wav（可 push 替换，支持长音频），
        // 不存在时回退到 assets 内置音频
        val internalFile = File(context.filesDir, "asr_test/asr_test.wav")
        if (internalFile.exists()) {
            return runFile(context, internalFile, onPartial)
        }
        return runCommon(context, onPartial) { callback ->
            context.assets.open(assetName).use { callback(it) }
        }
    }

    /**
     * 用 [wavFile]（16k mono 16bit）执行一次离线识别，
     * 绕开实时管线（VAD/缓冲），直接验证 C++ 推理对真实录音的效果。
     */
    suspend fun runFile(
        context: Context,
        wavFile: File,
        onPartial: (String) -> Unit = {}
    ): TestResult {
        return runCommon(context, onPartial) { callback ->
            wavFile.inputStream().use { callback(it) }
        }
    }

    private suspend fun runCommon(
        context: Context,
        onPartial: (String) -> Unit,
        feedAudio: suspend (suspend (InputStream) -> Unit) -> Unit
    ): TestResult {
        return try {
            val modelManager = AsrModelManager(context)
            if (!modelManager.isModelReady()) {
                return TestResult("", false, "模型未下载，请先下载离线模型")
            }
            val modelDir = modelManager.getSelectedModelDir().absolutePath

            val client = AsrInferenceClient(context)
            val bound = withContext(Dispatchers.IO) { client.ensureBound() }
            if (!bound) {
                return TestResult("", false, "无法绑定识别服务")
            }

            val ok = withContext(Dispatchers.IO) {
                client.startAsr(
                    modelDir,
                    object : AsrInferenceClient.AsrCallback {
                        override fun onPartialResult(text: String) {
                            onPartial(text)
                        }

                        override fun onFinalResult(text: String) {}

                        override fun onError(message: String) {
                            FileLogger.e(TAG, "ASR error: $message")
                        }
                    }
                )
            }
            if (!ok) {
                client.releaseAsr()
                return TestResult("", false, "识别器初始化失败，请检查模型文件")
            }

            // 读取 wav（16k mono 16bit），跳过 44 字节头，逐块喂给服务
            withContext(Dispatchers.IO) {
                feedAudio { input ->
                    skipWavHeader(input)
                    val chunk = ByteArray(CHUNK_SAMPLES * 2)
                    while (true) {
                        var filled = 0
                        while (filled < chunk.size) {
                            val r = input.read(chunk, filled, chunk.size - filled)
                            if (r < 0) break
                            filled += r
                        }
                        if (filled == 0) break
                        client.pushAsrAudio(chunk.copyOf(filled))
                    }
                }
            }

            val text = withContext(Dispatchers.IO) { client.stopAsr() }
            client.releaseAsr()
            TestResult(text.trim(), success = true)
        } catch (e: Exception) {
            FileLogger.e(TAG, "test failed", e)
            TestResult("", false, e.message ?: "未知错误")
        }
    }

    private fun skipWavHeader(input: InputStream) {
        val header = ByteArray(44)
        var read = 0
        while (read < 44) {
            val r = input.read(header, read, 44 - read)
            if (r < 0) break
            read += r
        }
    }
}

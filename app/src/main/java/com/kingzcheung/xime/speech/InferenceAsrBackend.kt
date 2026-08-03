package com.kingzcheung.xime.speech

import android.content.Context
import com.kingzcheung.xime.service.InferenceClient
import com.kingzcheung.xime.util.FileLogger
import kotlinx.coroutines.runBlocking

/**
 * 基于 :inference 独立进程的流式 ASR 后端。
 *
 * 模型加载与推理全部在 InferenceService（:inference 进程）中完成，
 * 主进程仅负责音频采集与结果回调，不占用输入法进程内存。
 */
class InferenceAsrBackend(private val context: Context) : AsrBackend {

    companion object {
        private const val TAG = "InferenceAsr"
    }

    override val name: String = "本地 Zipformer（独立进程）"

    private val client = InferenceClient(context)
    private var initialized = false

    private var resultCallback: ((String) -> Unit)? = null
    private var partialResultCallback: ((String) -> Unit)? = null
    private var stateCallback: ((RecognitionState) -> Unit)? = null
    private var errorCallback: ((String) -> Unit)? = null

    override fun setCallbacks(
        onResult: (String) -> Unit,
        onPartialResult: ((String) -> Unit)?,
        onStateChange: (RecognitionState) -> Unit,
        onError: (String) -> Unit
    ) {
        resultCallback = onResult
        partialResultCallback = onPartialResult
        stateCallback = onStateChange
        errorCallback = onError
    }

    private val asrCallback = object : InferenceClient.AsrCallback {
        override fun onPartialResult(text: String) {
            partialResultCallback?.invoke(text)
        }

        override fun onFinalResult(text: String) {
            resultCallback?.invoke(text)
        }

        override fun onError(message: String) {
            errorCallback?.invoke(message)
        }
    }

    override fun initialize(): Boolean {
        return try {
            val ok = runBlocking { client.ensureBound() }
            if (!ok) {
                FileLogger.e(TAG, "Failed to bind InferenceService")
                return false
            }
            val modelDir = AsrModelManager(context).getSelectedModelDir().absolutePath
            initialized = runBlocking { client.startAsr(modelDir, asrCallback) }
            FileLogger.i(TAG, "initialize result=$initialized")
            initialized
        } catch (e: Exception) {
            FileLogger.e(TAG, "initialize failed", e)
            false
        }
    }

    override fun start(): Boolean {
        if (!initialized) return false
        return try {
            // 每次会话开始都重新 startAsr：服务端会 nativeReset 并重设回调，
            // 否则 preload 预热时 stop() 清空的 callback 会导致 partial 结果丢失
            val modelDir = AsrModelManager(context).getSelectedModelDir().absolutePath
            runBlocking { client.startAsr(modelDir, asrCallback) }
        } catch (e: Exception) {
            FileLogger.e(TAG, "start failed", e)
            false
        }
    }

    override fun processAudioChunk(buffer: ByteArray) {
        if (!initialized) return
        client.pushAsrAudio(buffer)
    }

    override fun stop() {
        if (!initialized) return
        val text = try {
            runBlocking { client.stopAsr() }
        } catch (e: Exception) {
            FileLogger.e(TAG, "stop failed", e)
            ""
        }
        if (text.isNotEmpty()) {
            resultCallback?.invoke(text)
        }
        stateCallback?.invoke(RecognitionState.IDLE)
    }

    override fun cancel() {
        if (initialized) {
            client.cancelAsr()
        }
    }

    override fun release() {
        initialized = false
        try {
            runBlocking { client.unloadModel(InferenceClient.MODEL_ASR) }
        } catch (e: Exception) {
            FileLogger.e(TAG, "unloadModel failed", e)
        }
        client.unbind()
    }

    override fun getState(): RecognitionState = RecognitionState.IDLE

    override fun isAvailable(): Boolean = true
}

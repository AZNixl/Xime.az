package com.kingzcheung.xime.speech.sherpa

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.kingzcheung.xime.service.InferenceClient
import com.kingzcheung.xime.speech.AsrBackend
import com.kingzcheung.xime.speech.RecognitionState
import com.kingzcheung.xime.util.FileLogger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class IpcSherpaAsrBackend(private val context: Context) : AsrBackend {

    override val name: String = "本地模型 (IPC)"

    private var inferenceClient: InferenceClient? = null
    private val mainHandler = Handler(Looper.getMainLooper())
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

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

    override fun initialize(): Boolean {
        val client = InferenceClient(context)
        inferenceClient = client
        return true
    }

    override fun start(): Boolean {
        val client = inferenceClient ?: return false
        val modelDir = SherpaAsrEngine(context).getSelectedModelDir().absolutePath

        scope.launch {
            if (!client.ensureBound()) {
                FileLogger.e("IpcSherpaAsrBackend", "Failed to bind InferenceService")
                mainHandler.post { errorCallback?.invoke("无法连接推理服务") }
                return@launch
            }

            val asrStarted = client.startAsr(modelDir, object : InferenceClient.AsrCallback {
                override fun onPartialResult(text: String) {
                    mainHandler.post { partialResultCallback?.invoke(text) }
                }
                override fun onFinalResult(text: String) {
                    mainHandler.post { resultCallback?.invoke(text) }
                }
                override fun onError(message: String) {
                    mainHandler.post { errorCallback?.invoke(message) }
                }
            })

            if (asrStarted) {
                mainHandler.post { stateCallback?.invoke(RecognitionState.LISTENING) }
            } else {
                mainHandler.post {
                    errorCallback?.invoke("启动语音识别引擎失败")
                    stateCallback?.invoke(RecognitionState.ERROR)
                }
            }
        }
        return true
    }

    override fun processAudioChunk(buffer: ByteArray) {
        inferenceClient?.pushAsrAudio(buffer)
    }

    override fun stop() {
        scope.launch {
            inferenceClient?.stopAsr()
            mainHandler.post { stateCallback?.invoke(RecognitionState.IDLE) }
        }
    }

    override fun cancel() {
        inferenceClient?.cancelAsr()
        mainHandler.post { stateCallback?.invoke(RecognitionState.IDLE) }
    }

    override fun release() {
        inferenceClient?.unbind()
        inferenceClient = null
    }

    override fun getState(): RecognitionState {
        return RecognitionState.IDLE
    }

    override fun isAvailable(): Boolean {
        // We can't check JNI availability in main process anymore —
        // assume available if inference service can be bound
        return true
    }
}

package com.kingzcheung.xime.plugin.funasr

import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString.Companion.toByteString
import org.json.JSONObject
import java.time.Duration
import java.util.UUID

class FunAsrWebSocketManager(
    private val apiKey: String,
    private val onResult: (String, Boolean) -> Unit,
    private val onError: (String) -> Unit,
    private val onStateChanged: (State) -> Unit
) {

    enum class State {
        IDLE,
        CONNECTING,
        CONNECTED,
        LISTENING,
        PROCESSING,
        ERROR
    }

    private var client: OkHttpClient? = null
    private var webSocket: WebSocket? = null
    private var taskId: String = ""
    private var state: State = State.IDLE
    private val coroutineScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    private val prebuffer = java.util.ArrayDeque<ByteArray>()
    private val prebufferLock = Any()

    companion object {
        private const val TAG = "FunAsrWebSocket"
        private const val WS_URL = "wss://dashscope.aliyuncs.com/api-ws/v1/inference/"
        private const val MODEL = "qwen-audio-3.0-asr-flash-streaming"
        private const val SAMPLE_RATE = 16000
        private const val FORMAT = "pcm"
    }

    fun getState(): State = state

    fun connect(): Boolean {
        synchronized(prebufferLock) { prebuffer.clear() }

        // 已连接或连接中：幂等复用，避免重复发起连接导致时序冲突
        when (state) {
            State.CONNECTING, State.CONNECTED, State.LISTENING, State.PROCESSING -> {
                Log.d(TAG, "Already connected or connecting, state: $state, reusing connection")
                return true
            }
            State.ERROR -> {
                // 上次连接失败后允许重连
            }
            else -> {}
        }

        Log.i(TAG, "Starting WebSocket connection, API key length: ${apiKey.length}")

        try {
            state = State.CONNECTING
            onStateChanged(state)

            taskId = UUID.randomUUID().toString()

            client = OkHttpClient.Builder()
                .pingInterval(Duration.ofSeconds(30))
                .build()

            val request = Request.Builder()
                .url(WS_URL)
                .header("Authorization", "bearer $apiKey")
                .header("user-agent", "Xime-FunAsr/1.0")
                .build()

            webSocket = client?.newWebSocket(request, WebSocketListenerImpl())

            return true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to connect", e)
            state = State.ERROR
            onStateChanged(state)
            onError("连接失败: ${e.message}")
            return false
        }
    }

    fun sendRunTask(): Boolean {
        if (webSocket == null || state != State.CONNECTED) {
            Log.w(TAG, "WebSocket not ready, state: $state")
            return false
        }

        val runTaskMessage = JSONObject().apply {
            put("header", JSONObject().apply {
                put("action", "run-task")
                put("task_id", taskId)
                put("streaming", "duplex")
            })
            put("payload", JSONObject().apply {
                put("task_group", "audio")
                put("task", "asr")
                put("function", "recognition")
                put("model", MODEL)
                put("parameters", JSONObject().apply {
                    put("format", FORMAT)
                    put("sample_rate", SAMPLE_RATE)
                })
                put("input", JSONObject())
            })
        }

        webSocket?.send(runTaskMessage.toString())
        return true
    }

    fun sendAudioChunk(data: ByteArray) {
        when (state) {
            // 连接尚未就绪（task-started 未到）：缓冲音频帧，避免丢失开头语音
            State.CONNECTING, State.CONNECTED -> {
                synchronized(prebufferLock) {
                    prebuffer.addLast(data)
                    if (prebuffer.size > 300) prebuffer.removeFirst()
                }
            }
            State.LISTENING, State.PROCESSING -> {
                flushPrebuffer()
                webSocket?.send(data.toByteString())
            }
            else -> {
                Log.w(TAG, "Not in listening state, ignoring audio chunk")
            }
        }
    }

    private fun flushPrebuffer() {
        var frames: Array<ByteArray>? = null
        synchronized(prebufferLock) {
            if (prebuffer.isNotEmpty()) {
                frames = prebuffer.toTypedArray()
                prebuffer.clear()
            }
        }
        frames?.forEach { webSocket?.send(it.toByteString()) }
    }

    fun sendFinishTask() {
        if (webSocket == null) return

        val finishTaskMessage = JSONObject().apply {
            put("header", JSONObject().apply {
                put("action", "finish-task")
                put("task_id", taskId)
                put("streaming", "duplex")
            })
            put("payload", JSONObject().apply {
                put("input", JSONObject())
            })
        }

        webSocket?.send(finishTaskMessage.toString())
    }

    fun disconnect() {
        webSocket?.close(1000, "Normal closure")
        webSocket = null
        client?.dispatcher?.executorService?.shutdown()
        client = null
        coroutineScope.cancel()
        state = State.IDLE
        onStateChanged(state)
    }

    fun cancel() {
        disconnect()
    }

    private inner class WebSocketListenerImpl : WebSocketListener() {
        override fun onOpen(webSocket: WebSocket, response: Response) {
            Log.i(TAG, "WebSocket opened successfully")
            state = State.CONNECTED
            onStateChanged(state)
            sendRunTask()
        }

        override fun onMessage(webSocket: WebSocket, text: String) {
            try {
                val message = JSONObject(text)
                val header = message.getJSONObject("header")
                val event = header.getString("event")

                when (event) {
                    "task-started" -> {
                        Log.i(TAG, "ASR task started, ready for audio input")
                        state = State.LISTENING
                        onStateChanged(state)
                    }

                    "result-generated" -> {
                        val payload = message.getJSONObject("payload")
                        val output = payload.optJSONObject("output")
                        if (output != null) {
                            val sentence = output.optJSONObject("sentence")
                            if (sentence != null) {
                                val heartbeat = sentence.optBoolean("heartbeat", false)
                                if (heartbeat) {
                                    return
                                }

                                val resultText = sentence.optString("text", "")
                                val isFinal = sentence.optBoolean("sentence_end", false)

                                state = State.PROCESSING
                                onStateChanged(state)

                                // sentence.text 是当前句的完整累积文本，直接原样上抛，
                                // 由上层整段替换显示，避免增量/累积导致的重复文本。
                                if (resultText.isNotEmpty()) {
                                    onResult(resultText, isFinal)
                                }
                            } else {
                                Log.w(TAG, "No sentence in output")
                            }
                        } else {
                            Log.w(TAG, "No output in payload")
                        }
                    }

                    "task-finished" -> {
                        state = State.IDLE
                        onStateChanged(state)
                        disconnect()
                    }

                    "task-failed" -> {
                        val errorCode = header.optString("error_code", "UNKNOWN")
                        val errorMsg = header.optString("error_message", "Unknown error")
                        Log.e(TAG, "ASR task failed: code=$errorCode, message=$errorMsg")
                        state = State.ERROR
                        onStateChanged(state)
                        onError("识别失败 [$errorCode]: $errorMsg")
                        disconnect()
                    }

                    else -> {
                        Log.w(TAG, "Unknown event: $event")
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to parse WebSocket message: ${e.message}")
                onError("解析消息失败: ${e.message}")
            }
        }

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            val errorCode = response?.code ?: 0
            Log.e(TAG, "WebSocket failure: code=$errorCode, error=${t.message}")

            val errorMsg = when (errorCode) {
                401 -> "API Key 无效或未配置，请检查设置"
                403 -> "访问被拒绝，请检查 API Key 权限"
                429 -> "请求过于频繁，请稍后再试"
                500 -> "服务器错误，请稍后再试"
                502 -> "服务器网关错误，请稍后再试"
                503 -> "服务暂时不可用，请稍后再试"
                else -> "连接失败: ${t.message ?: "未知错误"}"
            }

            webSocket.close(1000, "Error cleanup")
            state = State.IDLE
            onStateChanged(state)
            onError(errorMsg)
        }

        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
            state = State.IDLE
            onStateChanged(state)
        }
    }
}

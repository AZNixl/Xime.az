package com.kingzcheung.xime.plugin.funasr

import android.util.Log
import com.kingzcheung.xime.plugin.core.api.AsrPluginBackend
import com.kingzcheung.xime.plugin.core.api.AsrPluginListener
import com.kingzcheung.xime.plugin.core.api.AsrPluginState

class FunAsrAsrBackend(
    private val apiKey: String
) : AsrPluginBackend {

    companion object {
        private const val TAG = "FunAsrAsrBackend"
    }

    private var wsManager: FunAsrWebSocketManager? = null
    private var listener: AsrPluginListener? = null

    override val isRunning: Boolean
        get() = wsManager?.getState()?.let { it != FunAsrWebSocketManager.State.IDLE } ?: false

    override fun setListener(listener: AsrPluginListener) {
        this.listener = listener
    }

    override fun initialize(): Boolean {
        if (apiKey.isEmpty()) {
            Log.e(TAG, "API Key not configured")
            return false
        }
        Log.i(TAG, "Initializing FunAsr backend with API key (length: ${apiKey.length})")
        wsManager = FunAsrWebSocketManager(
            apiKey = apiKey,
            onResult = { text, _ ->
                if (text.isNotEmpty()) {
                    listener?.onFinal(text)
                }
            },
            onError = { error ->
                Log.e(TAG, "FunAsr error: $error")
                listener?.onError(error)
            },
            onStateChanged = { wsState ->
                listener?.onStateChanged(wsState.toAsrPluginState())
            }
        )
        return true
    }

    override fun start(): Boolean {
        Log.i(TAG, "Starting FunAsr connection")
        return wsManager?.connect() ?: false
    }

    override fun processAudioChunk(pcm: ByteArray) {
        wsManager?.sendAudioChunk(pcm)
    }

    override fun stop() {
        Log.i(TAG, "Stopping FunAsr recognition")
        wsManager?.sendFinishTask()
    }

    override fun cancel() {
        Log.i(TAG, "Canceling FunAsr recognition")
        wsManager?.cancel()
    }

    override fun release() {
        Log.i(TAG, "Releasing FunAsr backend")
        wsManager?.disconnect()
        wsManager = null
    }

    override fun getState(): AsrPluginState {
        return wsManager?.getState()?.toAsrPluginState() ?: AsrPluginState.IDLE
    }

    private fun FunAsrWebSocketManager.State.toAsrPluginState(): AsrPluginState {
        return when (this) {
            FunAsrWebSocketManager.State.IDLE -> AsrPluginState.IDLE
            FunAsrWebSocketManager.State.CONNECTING,
            FunAsrWebSocketManager.State.CONNECTED,
            FunAsrWebSocketManager.State.LISTENING -> AsrPluginState.LISTENING
            FunAsrWebSocketManager.State.PROCESSING -> AsrPluginState.PROCESSING
            FunAsrWebSocketManager.State.ERROR -> AsrPluginState.ERROR
        }
    }
}

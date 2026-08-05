package com.kingzcheung.xime.service

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.inputmethod.InputConnection
import com.kingzcheung.xime.plugin.ExtensionManager
import com.kingzcheung.xime.speech.RecognitionState
import com.kingzcheung.xime.speech.SpeechRecognitionManager
import com.kingzcheung.xime.settings.SettingsPreferences
import com.kingzcheung.xime.util.FileLogger

class VoiceRecognitionHandler(
    private val context: Context,
    private val onStateChanged: (InputUIState) -> Unit,
    private val getState: () -> InputUIState,
    private val getInputConnection: () -> InputConnection?,
    private val onVoiceComplete: () -> Unit = {},
    private val onAmplitudeChanged: (Float) -> Unit = {}
) {
    companion object {
        private const val TAG = "VoiceRecognition"
    }

    private lateinit var speechRecognitionManager: SpeechRecognitionManager

    var textBeforeVoiceInput = ""
    var textLengthBeforeVoiceInput = 0

    fun initialize() {
        FileLogger.i(TAG, "Initializing speech recognition system")

        speechRecognitionManager = SpeechRecognitionManager(context)

        speechRecognitionManager.setCallbacks(
            onResult = { text ->
                handleSpeechResult(text)
            },
            onPartialResult = { text ->
                handlePartialResult(text)
            },
            onStateChange = { state ->
                handleSpeechStateChange(state)
            },
            onError = { error ->
                handleSpeechError(error)
            },
            onAmplitude = { amplitude ->
                handleAmplitudeUpdate(amplitude)
            }
        )

        val providerName = resolveProviderName()

        onStateChanged(getState().copy(voicePluginName = providerName))
        FileLogger.i(TAG, "STT provider: online")
        // ASR 插件按需加载：服务启动时不预加载，首次语音时由 startRecognition() 加载。
    }

    private val mainHandler by lazy { Handler(Looper.getMainLooper()) }
    private val delayedPreStartRunnable = Runnable {
        if (::speechRecognitionManager.isInitialized) {
            speechRecognitionManager.startPreStart()
        }
    }

    fun startDelayedPreStart(delayMs: Long = 150) {
        mainHandler.removeCallbacks(delayedPreStartRunnable)
        mainHandler.postDelayed(delayedPreStartRunnable, delayMs)
    }

    fun cancelPreStart() {
        mainHandler.removeCallbacks(delayedPreStartRunnable)
        if (::speechRecognitionManager.isInitialized) {
            speechRecognitionManager.cancelPreStart()
        }
    }

    fun startRecognition() {
        if (!::speechRecognitionManager.isInitialized) {
            Log.e(TAG, "speechRecognitionManager not initialized")
            onStateChanged(getState().copy(
                isVoiceMode = false,
                voiceRecognitionState = RecognitionState.ERROR
            ))
            return
        }

        textBeforeVoiceInput = getInputConnection()?.getTextBeforeCursor(1000, 0)?.toString() ?: ""
        textLengthBeforeVoiceInput = textBeforeVoiceInput.length

        val providerName = resolveProviderName()
        onStateChanged(getState().copy(voicePluginName = providerName))

        speechRecognitionManager.startRecognition()
    }

    fun stopRecognition() {
        if (::speechRecognitionManager.isInitialized) {
            speechRecognitionManager.stopRecognition()
        }
        // handleFinalResult is now called from within handleSpeechResult
        // when the final stopRecognition result arrives
    }

    fun release() {
        if (::speechRecognitionManager.isInitialized) {
            speechRecognitionManager.release()
        }
    }

    fun isInitialized(): Boolean = ::speechRecognitionManager.isInitialized

    private fun resolveProviderName(): String {
        val enabledPlugins = ExtensionManager.getEnabledAsrPlugins(context)
        if (enabledPlugins.isNotEmpty()) {
            val selectedId = SettingsPreferences.getSttOnlinePluginId(context)
            val plugin = enabledPlugins.firstOrNull { it.first == selectedId }?.second
                ?: enabledPlugins.firstOrNull()?.second
            if (plugin != null) return plugin.getDisplayName()
        }
        return "未配置"
    }

    private var lastPartialText = ""
    private var lastAmplitudeUpdate = 0L
    // 抬起时已提交当前识别文本后，置真以忽略随后可能迟到的重复最终结果
    private var suppressDuplicateFinal = false

    // 语音按钮长按抬起时调用：立即提交当前已识别的文本（不依赖可能被断连竞态吞掉的异步最终结果）
    fun commitPendingOnRelease() {
        val ic = getInputConnection() ?: return
        val partial = lastPartialText
        if (partial.isEmpty()) return
        val punctuatedText = addPunctuation(partial)
        commitFinal(ic, punctuatedText, partial)
        suppressDuplicateFinal = true
        lastPartialText = ""
    }

    private fun handleSpeechResult(text: String) {
        Log.d(TAG, "Speech result (final): $text")

        if (suppressDuplicateFinal) {
            // 抬起时已提交，忽略迟到的重复最终结果
            suppressDuplicateFinal = false
            lastPartialText = ""
            onVoiceComplete()
            return
        }

        val cleanText = text.replace(" ", "")
        val ic = getInputConnection()
        if (ic != null && cleanText.isNotEmpty() && !cleanText.startsWith("错误:")) {
            val punctuatedText = addPunctuation(cleanText)
            commitFinal(ic, punctuatedText, lastPartialText)
        }
        lastPartialText = ""
        onVoiceComplete()
    }
    
    // BiBi 模式：先结束 composing，再只提交增量，避免重复与整段重写。
    private fun commitFinal(ic: InputConnection, finalText: String, partial: String) {
        ic.finishComposingText()
        if (partial.isNotEmpty() && finalText.startsWith(partial)) {
            val remainder = finalText.substring(partial.length)
            if (remainder.isNotEmpty()) {
                ic.commitText(remainder, 1)
            }
        } else {
            // 最终结果与部分结果不一致：删除已上屏的部分，再提交完整结果
            if (partial.isNotEmpty()) {
                ic.deleteSurroundingText(partial.length, 0)
            }
            ic.commitText(finalText, 1)
        }
    }
    
    private fun addPunctuation(text: String): String {
        val cleanText = text.trim().replace(" ", "")
        if (cleanText.isEmpty()) return text

        // 若文本末尾已带句末标点（如 funasr/volc 等自带标点的后端），不再追加，避免"。。"
        if (cleanText.last() in "。！？；：，、；：,.!?;:，") return cleanText

        return "$cleanText${heuristicPunctuation(cleanText)}"
    }

    private fun heuristicPunctuation(text: String): String {
        return when {
            text.any { it in "吗呢么吧" } || text.contains("什么") || text.contains("怎么") || text.contains("为什么") || text.contains("如何") || text.contains("哪") -> "？"
            text.length < 4 -> "，"
            else -> "。"
        }
    }

    private fun handlePartialResult(text: String) {
        if (text == lastPartialText) return
        lastPartialText = text
        Log.d(TAG, "Speech result (partial): $text")
        
        // 过滤掉空格，避免显示空白
        val cleanText = text.replace(" ", "")
        if (cleanText.isEmpty()) return
        
        val ic = getInputConnection()
        if (ic != null) {
            ic.setComposingText(cleanText, 1)
        }
        onStateChanged(getState().copy(voiceRecognizedText = cleanText))
    }

    private fun handleSpeechStateChange(state: RecognitionState) {
        Log.d(TAG, "Speech state changed: $state")
        if (state == RecognitionState.LISTENING) {
            lastPartialText = ""
            suppressDuplicateFinal = false
        }
        onStateChanged(getState().copy(voiceRecognitionState = state))
    }

    private fun handleSpeechError(error: String) {
        Log.e(TAG, "Speech error: $error")
        FileLogger.e(TAG, "Speech error: $error")
        lastPartialText = ""
        onVoiceComplete()
    }

    private fun handleAmplitudeUpdate(amplitude: Float) {
        val now = System.currentTimeMillis()
        if (now - lastAmplitudeUpdate < 80) return
        lastAmplitudeUpdate = now
        onAmplitudeChanged(amplitude)
    }
}
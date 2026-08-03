package com.kingzcheung.xime.service

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.inputmethod.InputConnection
import com.kingzcheung.xime.model.ModelRuntime
import com.kingzcheung.xime.speech.RecognitionState
import com.kingzcheung.xime.speech.SpeechRecognitionManager
import com.kingzcheung.xime.speech.punctuation.PunctuationInference
import com.kingzcheung.xime.speech.punctuation.PunctuationModelManager
import com.kingzcheung.xime.speech.AsrModelManager
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
    private var punctuationInitialized = false

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

        val useLocal = SettingsPreferences.isSttUseLocal(context)
        val providerName = if (useLocal) {
            val sherpaEngine = AsrModelManager(context)
            sherpaEngine.getSelectedModelInfo()?.name ?: "本地模型"
        } else {
            val apiKey = SettingsPreferences.getFunAsrApiKey(context)
            if (apiKey.isNotEmpty()) "阿里百炼" else "未配置"
        }

        onStateChanged(getState().copy(voicePluginName = providerName))
        FileLogger.i(TAG, "STT provider: ${if (useLocal) "local" else "funasr"}")
        // ASR 模型按需加载：服务启动时不预加载，首次语音时由 startRecognition() 加载，
        // 避免本地 zipformer2 模型（约 150MB+）常驻输入法进程内存。
    }
    
    private fun initPunctuationModel() {
        if (punctuationInitialized) return
        
        val punctuationEnabled = SettingsPreferences.isPunctuationModelEnabled(context)
        if (!punctuationEnabled) {
            FileLogger.i(TAG, "Punctuation model not enabled in settings")
            return
        }
        
        val punctuationManager = PunctuationModelManager(context)
        if (!punctuationManager.isModelDownloaded()) {
            FileLogger.i(TAG, "Punctuation model not downloaded")
            return
        }
        
        val modelFile = punctuationManager.getModelFile()
        val vocabFile = punctuationManager.getVocabFile()
        if (PunctuationInference.initialize(context, modelFile.absolutePath, vocabFile.absolutePath)) {
            ModelRuntime.keepWarm("punctuation")
            punctuationInitialized = true
            FileLogger.i(TAG, "Punctuation model initialized successfully")
        } else {
            FileLogger.e(TAG, "Failed to initialize punctuation model")
        }
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

        val useLocal = SettingsPreferences.isSttUseLocal(context)
        val providerName = if (useLocal) {
            val sherpaEngine = AsrModelManager(context)
            sherpaEngine.getSelectedModelInfo()?.name ?: "本地模型"
        } else {
            val apiKey = SettingsPreferences.getFunAsrApiKey(context)
            if (apiKey.isNotEmpty()) "阿里百炼" else "未配置"
        }
        onStateChanged(getState().copy(voicePluginName = providerName))

        // 标点模型按需加载，避免启动时预加载占内存
        if (!punctuationInitialized) {
            Thread {
                try {
                    initPunctuationModel()
                } catch (_: Exception) { }
            }.start()
        }

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
        if (punctuationInitialized) {
            ModelRuntime.releaseWarm("punctuation")
            PunctuationInference.release()
            punctuationInitialized = false
        }
    }

    fun isInitialized(): Boolean = ::speechRecognitionManager.isInitialized

    /**
     * 设置驱动：STT 本地功能开启时预加载 ASR 模型到 :inference 进程。
     * 在后台线程执行，避免阻塞主线程。
     */
    fun ensureAsrLoaded() {
        if (!::speechRecognitionManager.isInitialized) return
        val useLocal = SettingsPreferences.isSttUseLocal(context)
        if (!useLocal) return
        Thread {
            try {
                speechRecognitionManager.preload()
            } catch (_: Exception) {
            }
        }.start()
    }

    /**
     * 设置驱动：STT 本地功能关闭时卸载 ASR 模型，释放 :inference 进程内存。
     * 在后台线程执行，避免阻塞主线程。
     */
    fun releaseAsr() {
        if (!::speechRecognitionManager.isInitialized) return
        Thread {
            try {
                speechRecognitionManager.release()
            } catch (_: Exception) {
            }
        }.start()
    }

    private var lastPartialText = ""
    private var lastAmplitudeUpdate = 0L

    private fun handleSpeechResult(text: String) {
        Log.d(TAG, "Speech result (final): $text")
        lastPartialText = ""

        val cleanText = text.replace(" ", "")
        if (cleanText.isNotEmpty() && !cleanText.startsWith("错误:")) {
            val ic = getInputConnection()
            if (ic != null) {
                val punctuatedText = addPunctuation(cleanText)
                ic.commitText(punctuatedText, 1)
            }
        }
        onVoiceComplete()
    }
    
    private fun addPunctuation(text: String): String {
        val useLocal = SettingsPreferences.isSttUseLocal(context)
        if (!useLocal) return text
        
        val sherpaEngine = AsrModelManager(context)
        val needsAutoPunctuation = sherpaEngine.getSelectedModelInfo()?.needsAutoPunctuation ?: true
        if (!needsAutoPunctuation) return text
        
        val cleanText = text.trim().replace(" ", "")
        if (cleanText.isEmpty()) return text
        
        val punctuationEnabled = SettingsPreferences.isPunctuationModelEnabled(context)
        if (punctuationEnabled && punctuationInitialized) {
            try {
                val result = PunctuationInference.predict(cleanText)
                FileLogger.d(TAG, "Punctuation model result: '$cleanText' -> '$result'")
                return result
            } catch (e: Exception) {
                FileLogger.e(TAG, "Punctuation model failed: ${e.message}")
            }
        }
        
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
package com.kingzcheung.xime.service

import android.app.Service
import android.content.Intent
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.util.Log
import com.k2fsa.sherpa.onnx.*
import com.kingzcheung.xime.association.AssociationCandidate
import com.kingzcheung.xime.association.NativeOnnxEngine
import com.kingzcheung.xime.handwriting.HandwritingNativeEngine
import com.kingzcheung.xime.speech.punctuation.PunctuationInference
import com.kingzcheung.xime.util.FileLogger
import org.json.JSONObject
import java.io.File

class InferenceService : Service() {

    companion object {
        private const val TAG = "InferenceService"
        private const val MODEL_PREDICTION = "predictive_text"
        private const val MODEL_ASR = "asr"
        private const val MODEL_PUNCTUATION = "punctuation"
        private const val MODEL_HANDWRITING = "handwriting"
        private const val SAMPLE_RATE = 16000
    }

    private var onnxLibsLoaded = false
    private var sherpaLibsLoaded = false

    private var recognizer: OnlineRecognizer? = null
    private var stream: OnlineStream? = null
    private var asrCallback: IInferenceCallback? = null
    private var predictionVocab: Map<String, Int>? = null

    private val asrHandlerThread = HandlerThread("asr-process").apply { start() }
    private val asrHandler = Handler(asrHandlerThread.looper)

    private val binder = object : IInferenceService.Stub() {

        override fun loadModel(modelId: String, modelPath: String, extraPath: String): Boolean {
            return try {
                when (modelId) {
                    MODEL_PREDICTION -> loadPredictionModel(modelPath, extraPath)
                    MODEL_ASR -> loadAsrModel(modelPath)
                    MODEL_PUNCTUATION -> loadPunctuationModel(modelPath, extraPath)
                    MODEL_HANDWRITING -> loadHandwritingModel(modelPath, extraPath)
                    else -> {
                        Log.e(TAG, "Unknown modelId: $modelId")
                        false
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "loadModel($modelId) failed", e)
                false
            }
        }

        override fun unloadModel(modelId: String) {
            try {
                when (modelId) {
                    MODEL_PREDICTION -> {
                        NativeOnnxEngine.release()
                        predictionVocab = null
                    }
                    MODEL_ASR -> releaseAsr()
                    MODEL_PUNCTUATION -> PunctuationInference.release()
                    MODEL_HANDWRITING -> HandwritingNativeEngine.release()
                }
            } catch (e: Exception) {
                Log.e(TAG, "unloadModel($modelId) failed", e)
            }
        }

        override fun isModelLoaded(modelId: String): Boolean {
            return try {
                when (modelId) {
                    MODEL_PREDICTION -> NativeOnnxEngine.isInitialized()
                    MODEL_ASR -> recognizer != null
                    MODEL_PUNCTUATION -> PunctuationInference.isInitialized()
                    MODEL_HANDWRITING -> HandwritingNativeEngine.isInitialized()
                    else -> false
                }
            } catch (e: Exception) {
                false
            }
        }

        override fun predict(modelId: String, text: String, topK: Int): MutableList<String> {
            if (modelId != MODEL_PREDICTION) return mutableListOf()
            val candidates = NativeOnnxEngine.predict(text, topK)
            val result = mutableListOf<String>()
            for (c in candidates) {
                result.add(c.text)
                result.add(c.score.toString())
            }
            return result
        }

        override fun startAsr(modelId: String, modelDir: String, callback: IInferenceCallback): Boolean {
            if (modelId != MODEL_ASR) return false
            if (!sherpaLibsLoaded) {
                if (!loadSherpaLibs()) return false
            }

            val dir = File(modelDir)
            if (!dir.exists()) {
                Log.e(TAG, "ASR model dir not found: $modelDir")
                return false
            }

            try {
                val tokens = findFile(dir, "tokens.txt")?.absolutePath
                    ?: return false
                val encoder = findFile(dir, "encoder.int8.onnx")?.absolutePath
                    ?: findFile(dir) { it.startsWith("encoder") }?.absolutePath
                    ?: return false
                val decoder = findFile(dir, "decoder.onnx")?.absolutePath
                    ?: return false
                val joiner = findFile(dir, "joiner.int8.onnx")?.absolutePath
                    ?: findFile(dir) { it.startsWith("joiner") }?.absolutePath
                    ?: return false

                val modelConfig = OnlineModelConfig(
                    transducer = OnlineTransducerModelConfig(
                        encoder = encoder, decoder = decoder, joiner = joiner
                    ),
                    tokens = tokens,
                    numThreads = 2,
                    provider = "cpu",
                    modelType = "zipformer2"
                )

                val config = OnlineRecognizerConfig(
                    featConfig = FeatureConfig(sampleRate = SAMPLE_RATE, featureDim = 80),
                    modelConfig = modelConfig,
                    endpointConfig = EndpointConfig(
                        rule1 = EndpointRule(false, 0f, 0f),
                        rule2 = EndpointRule(false, 0f, 0f),
                        rule3 = EndpointRule(false, 0f, 60f)
                    ),
                    enableEndpoint = false,
                    decodingMethod = "greedy_search"
                )

                recognizer = OnlineRecognizer(config = config)
                stream = recognizer?.createStream()
                asrCallback = callback
                Log.i(TAG, "ASR recognizer created")
                return true
            } catch (e: Exception) {
                Log.e(TAG, "Failed to create ASR recognizer", e)
                callback.onError(MODEL_ASR, "创建语音识别引擎失败: ${e.message}")
                return false
            }
        }

        override fun pushAsrAudio(modelId: String, audioData: ByteArray) {
            if (modelId != MODEL_ASR) return
            val currentRecognizer = recognizer ?: return
            val currentStream = stream ?: return

            asrHandler.post {
                try {
                    val samples = FloatArray(audioData.size / 2)
                    for (i in samples.indices) {
                        val low = audioData[i * 2].toInt() and 0xFF
                        val high = audioData[i * 2 + 1].toInt()
                        val sample = (high shl 8) or low
                        samples[i] = sample.toFloat() / 32768.0f
                    }

                    currentStream.acceptWaveform(samples, SAMPLE_RATE)

                    var decoded = false
                    while (currentRecognizer.isReady(currentStream)) {
                        currentRecognizer.decode(currentStream)
                        decoded = true
                    }

                    if (decoded) {
                        val text = currentRecognizer.getResult(currentStream).text
                        if (text.isNotEmpty()) {
                            asrCallback?.onPartialResult(MODEL_ASR, text)
                        }
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "pushAsrAudio error", e)
                }
            }
        }

        override fun stopAsr(modelId: String): String {
            if (modelId != MODEL_ASR) return ""
            val currentRecognizer = recognizer ?: return ""
            val currentStream = stream ?: run { releaseAsr(); return "" }

            try {
                val tailPaddings = FloatArray((0.6f * SAMPLE_RATE).toInt())
                currentStream.acceptWaveform(tailPaddings, SAMPLE_RATE)
                currentStream.inputFinished()

                while (currentRecognizer.isReady(currentStream)) {
                    currentRecognizer.decode(currentStream)
                }

                val finalText = currentRecognizer.getResult(currentStream).text
                Log.i(TAG, "ASR final result: '$finalText'")

                asrCallback?.onFinalResult(MODEL_ASR, finalText)
                return ""
            } catch (e: Exception) {
                Log.e(TAG, "stopAsr error", e)
                return ""
            } finally {
                releaseAsr()
            }
        }

        override fun cancelAsr(modelId: String) {
            if (modelId != MODEL_ASR) return
            releaseAsr()
        }

        override fun recognizeHandwriting(modelId: String, strokeData: FloatArray, mask: ByteArray, topK: Int): MutableList<String> {
            return mutableListOf()
        }

        override fun restorePunctuation(modelId: String, text: String): String {
            return try {
                PunctuationInference.predict(text)
            } catch (e: Exception) {
                text
            }
        }

        override fun processAudioBytes(input: ByteArray, sampleRate: Int): ByteArray {
            var maxSample = 0
            for (i in input.indices step 2) {
                val low = input[i].toInt() and 0xFF
                val high = input[i + 1].toInt()
                val sample = (high shl 8) or low
                val absSample = kotlin.math.abs(sample)
                if (absSample > maxSample) maxSample = absSample
            }

            if (maxSample == 0) return input

            val peak = maxSample.toFloat() / 32768.0f
            val gain = if (peak < 0.5f) {
                kotlin.math.min(4.0f, 0.5f / peak)
            } else {
                1.0f
            }

            if (gain <= 1.0f) return input

            val output = ByteArray(input.size)
            for (i in input.indices step 2) {
                val low = input[i].toInt() and 0xFF
                val high = input[i + 1].toInt()
                var sample = ((high shl 8) or low).toShort().toFloat() * gain
                sample = sample.coerceIn(-32768f, 32767f)
                val s = sample.toInt()
                output[i] = (s and 0xFF).toByte()
                output[i + 1] = ((s shr 8) and 0xFF).toByte()
            }
            return output
        }
    }

    override fun onBind(intent: Intent): IBinder = binder

    override fun onDestroy() {
        releaseAsr()
        asrHandlerThread.quitSafely()
        NativeOnnxEngine.releaseSharedEnv()
        super.onDestroy()
    }

    private fun loadOnnxLibs(): Boolean {
        if (onnxLibsLoaded) return true
        val success = NativeOnnxEngine.loadNativeLibrary(this)
        onnxLibsLoaded = success
        return success
    }

    private fun loadSherpaLibs(): Boolean {
        if (sherpaLibsLoaded) return true
        return try {
            System.loadLibrary("sherpa-onnx-jni")
            sherpaLibsLoaded = true
            true
        } catch (e: UnsatisfiedLinkError) {
            Log.e(TAG, "sherpa-onnx-jni not available", e)
            false
        }
    }

    private fun loadPredictionModel(modelPath: String, vocabPath: String): Boolean {
        if (!loadOnnxLibs()) return false

        val vocabFile = File(vocabPath)
        if (!vocabFile.exists()) {
            Log.e(TAG, "Vocab file not found: $vocabPath")
            return false
        }
        val vocabText = vocabFile.readText()
        val vocabJson = JSONObject(vocabText)
        val vocabMap = when {
            vocabJson.has("model") -> vocabJson.getJSONObject("model").getJSONObject("vocab")
            vocabJson.has("vocab") -> vocabJson.getJSONObject("vocab")
            else -> vocabJson
        }
        val vocab = vocabMap.keys().asSequence().associateWith { vocabMap.getInt(it) }
        predictionVocab = vocab

        val modelFile = File(modelPath)
        if (!modelFile.exists()) {
            Log.e(TAG, "Model file not found: $modelPath")
            return false
        }

        if (!NativeOnnxEngine.initialize(this, modelPath)) {
            Log.e(TAG, "NativeOnnxEngine.initialize failed")
            return false
        }
        NativeOnnxEngine.initVocab(vocab)
        Log.i(TAG, "Prediction model loaded: ${vocab.size} vocab")
        return true
    }

    private fun loadAsrModel(modelDir: String): Boolean {
        if (!loadSherpaLibs()) return false
        val dir = File(modelDir)
        if (!dir.exists()) {
            Log.e(TAG, "ASR model dir not found: $modelDir")
            return false
        }
        return findFile(dir, "tokens.txt") != null
    }

    private fun loadPunctuationModel(modelPath: String, vocabPath: String): Boolean {
        if (!loadOnnxLibs()) return false
        return PunctuationInference.initialize(this, modelPath, vocabPath)
    }

    private fun loadHandwritingModel(modelPath: String, charIndexPath: String): Boolean {
        if (!loadOnnxLibs()) return false
        return HandwritingNativeEngine.initialize(this, modelPath)
    }

    private fun releaseAsr() {
        try {
            stream?.release()
            recognizer?.release()
        } catch (_: Exception) {}
        stream = null
        recognizer = null
        asrCallback = null
    }

    private fun findFile(dir: File, fileName: String): File? {
        val direct = File(dir, fileName)
        if (direct.exists()) return direct
        dir.listFiles()?.forEach { child ->
            if (child.isDirectory) {
                val found = findFile(child, fileName)
                if (found != null) return found
            }
        }
        return null
    }

    private fun findFile(dir: File, predicate: (String) -> Boolean): File? {
        dir.listFiles()?.forEach { child ->
            if (child.isFile && predicate(child.name)) return child
            if (child.isDirectory) {
                val found = findFile(child, predicate)
                if (found != null) return found
            }
        }
        return null
    }
}

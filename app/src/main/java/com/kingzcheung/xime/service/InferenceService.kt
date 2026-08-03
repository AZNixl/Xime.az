package com.kingzcheung.xime.service

import android.app.Service
import android.content.Intent
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.util.Log
import com.kingzcheung.xime.association.AssociationCandidate
import com.kingzcheung.xime.association.NativeOnnxEngine
import com.kingzcheung.xime.handwriting.HandwritingNativeEngine
import com.kingzcheung.xime.speech.AsrNative
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

    private var predictionVocab: Map<String, Int>? = null

    // ---- 本地 ASR（流式 zipformer2）----
    private var asrHandle: Long = 0L
    private var asrCallback: IInferenceCallback? = null
    private val asrLock = Any()

    private val binder = object : IInferenceService.Stub() {

        override fun loadModel(modelId: String, modelPath: String, extraPath: String): Boolean {
            return try {
                when (modelId) {
                    MODEL_PREDICTION -> loadPredictionModel(modelPath, extraPath)
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
                    MODEL_PUNCTUATION -> PunctuationInference.release()
                    MODEL_HANDWRITING -> HandwritingNativeEngine.release()
                    MODEL_ASR -> {
                        synchronized(asrLock) {
                            if (asrHandle != 0L) {
                                AsrNative.nativeRelease(asrHandle)
                                asrHandle = 0L
                            }
                            asrCallback = null
                        }
                        FileLogger.i(TAG, "ASR recognizer released in inference process")
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "unloadModel($modelId) failed", e)
            }
        }

        override fun isModelLoaded(modelId: String): Boolean {
            return try {
                when (modelId) {
                    MODEL_PREDICTION -> NativeOnnxEngine.isInitialized()
                    MODEL_ASR -> synchronized(asrLock) { asrHandle != 0L }
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
            // 模型加载耗时较长，放在 asrLock 外执行，避免阻塞其它 ASR 控制操作
            if (synchronized(asrLock) { asrHandle } == 0L) {
                val handle = createAsrHandle(modelDir)
                if (handle == 0L) {
                    Log.e(TAG, "Failed to create ASR recognizer from $modelDir")
                    return false
                }
                synchronized(asrLock) {
                    if (asrHandle == 0L) {
                        asrHandle = handle
                        FileLogger.i(TAG, "ASR recognizer created in inference process, handle=$handle")
                    } else {
                        AsrNative.nativeRelease(handle)
                    }
                }
            }
            return try {
                synchronized(asrLock) {
                    asrCallback = callback
                    AsrNative.nativeReset(asrHandle)
                }
                true
            } catch (e: Exception) {
                Log.e(TAG, "startAsr failed", e)
                false
            }
        }

        override fun pushAsrAudio(modelId: String, audioData: ByteArray) {
            if (modelId != MODEL_ASR) return
            val partial: String
            val cb: IInferenceCallback?
            synchronized(asrLock) {
                if (asrHandle == 0L) return
                AsrNative.nativeAcceptPcm(asrHandle, audioData)
                partial = AsrNative.nativeGetPartial(asrHandle)
                cb = asrCallback
            }
            if (cb != null && partial.isNotEmpty()) {
                try {
                    cb.onPartialResult(MODEL_ASR, partial)
                } catch (_: Exception) {
                }
            }
        }

        override fun stopAsr(modelId: String): String {
            if (modelId != MODEL_ASR) return ""
            return try {
                synchronized(asrLock) {
                    if (asrHandle == 0L) return ""
                    val text = AsrNative.nativeFinalize(asrHandle)
                    asrCallback = null
                    text
                }
            } catch (e: Exception) {
                Log.e(TAG, "stopAsr failed", e)
                ""
            }
        }

        override fun cancelAsr(modelId: String) {
            if (modelId != MODEL_ASR) return
            synchronized(asrLock) {
                if (asrHandle != 0L) AsrNative.nativeReset(asrHandle)
                asrCallback = null
            }
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
        NativeOnnxEngine.releaseSharedEnv()
        super.onDestroy()
    }

    private fun loadOnnxLibs(): Boolean {
        if (onnxLibsLoaded) return true
        val success = NativeOnnxEngine.loadNativeLibrary(this)
        onnxLibsLoaded = success
        return success
    }

    /** 按 AsrModelInfo 权威清单定位模型文件并创建识别器（在 inference 进程加载模型）。 */
    private fun createAsrHandle(modelDir: String): Long {
        return try {
            val info = com.kingzcheung.xime.speech.AsrModelManager(this).getSelectedModelInfo()
                ?: run {
                    Log.e(TAG, "No selected ASR model")
                    return 0L
                }
            val dir = File(modelDir)
            val encoder = File(dir, info.encoderFile)
            val decoder = File(dir, info.decoderFile)
            val joiner = File(dir, info.joinerFile)
            val tokens = File(dir, "tokens.txt")
            if (!encoder.exists() || !decoder.exists() || !joiner.exists() || !tokens.exists()) {
                Log.e(TAG, "ASR model files incomplete in $modelDir")
                return 0L
            }
            AsrNative.nativeCreate(
                encoder.absolutePath,
                decoder.absolutePath,
                joiner.absolutePath,
                tokens.absolutePath
            )
        } catch (e: Exception) {
            Log.e(TAG, "createAsrHandle failed", e)
            0L
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

    private fun loadPunctuationModel(modelPath: String, vocabPath: String): Boolean {
        if (!loadOnnxLibs()) return false
        return PunctuationInference.initialize(this, modelPath, vocabPath)
    }

    private fun loadHandwritingModel(modelPath: String, charIndexPath: String): Boolean {
        if (!loadOnnxLibs()) return false
        return HandwritingNativeEngine.initialize(this, modelPath)
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

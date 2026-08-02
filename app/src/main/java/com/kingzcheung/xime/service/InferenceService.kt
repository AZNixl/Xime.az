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
                }
            } catch (e: Exception) {
                Log.e(TAG, "unloadModel($modelId) failed", e)
            }
        }

        override fun isModelLoaded(modelId: String): Boolean {
            return try {
                when (modelId) {
                    MODEL_PREDICTION -> NativeOnnxEngine.isInitialized()
                    MODEL_ASR -> false
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
            // 本地 ASR 已由主进程的 libasr_jni.so 直接处理，不再经 IPC。
            return false
        }

        override fun pushAsrAudio(modelId: String, audioData: ByteArray) {
            // no-op: ASR runs in the main process
        }

        override fun stopAsr(modelId: String): String {
            return ""
        }

        override fun cancelAsr(modelId: String) {
            // no-op: ASR runs in the main process
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

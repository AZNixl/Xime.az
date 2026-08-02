package com.kingzcheung.xime.speech

import android.content.Context
import com.kingzcheung.xime.speech.AsrModelManager
import com.kingzcheung.xime.util.FileLogger
import java.io.File

/**
 * Local streaming ASR backend backed by the self-implemented zipformer2
 * recognizer (libasr_jni.so). Runs directly in the input method process.
 */
class LocalZipformerAsrBackend(private val context: Context) : AsrBackend {

    companion object {
        private const val TAG = "LocalZipformerAsr"
    }

    override val name: String = "本地 Zipformer"

    private var handle: Long = 0L

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
        val engine = AsrModelManager(context)
        val modelDir = engine.getSelectedModelDir()
        val info = engine.getSelectedModelInfo() ?: return false

        val encoder = File(modelDir, info.encoderFile)
        val decoder = File(modelDir, info.decoderFile)
        val joiner = File(modelDir, info.joinerFile)
        val tokens = File(modelDir, "tokens.txt")

        if (!encoder.exists() || !decoder.exists() || !joiner.exists() || !tokens.exists()) {
            FileLogger.e(TAG, "ASR model files incomplete in ${modelDir.absolutePath}")
            return false
        }

        handle = AsrNative.nativeCreate(
            encoder.absolutePath,
            decoder.absolutePath,
            joiner.absolutePath,
            tokens.absolutePath
        )
        FileLogger.i(TAG, "nativeCreate handle=$handle")
        return handle != 0L
    }

    override fun start(): Boolean {
        if (handle == 0L) return false
        AsrNative.nativeReset(handle)
        return true
    }

    override fun processAudioChunk(buffer: ByteArray) {
        if (handle == 0L) return
        AsrNative.nativeAcceptPcm(handle, buffer)
        val text = AsrNative.nativeGetPartial(handle)
        if (text.isNotEmpty()) {
            partialResultCallback?.invoke(text)
        }
    }

    override fun stop() {
        if (handle == 0L) return
        val text = AsrNative.nativeFinalize(handle)
        if (text.isNotEmpty()) {
            resultCallback?.invoke(text)
        }
        stateCallback?.invoke(RecognitionState.IDLE)
    }

    override fun cancel() {
        // reset next start
    }

    override fun release() {
        if (handle != 0L) {
            AsrNative.nativeRelease(handle)
            handle = 0L
        }
    }

    override fun getState(): RecognitionState = RecognitionState.IDLE

    override fun isAvailable(): Boolean = true
}

package com.kingzcheung.xime.speech

import android.content.Context
import com.kingzcheung.xime.util.FileLogger
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 调试工具：把语音识别期间的原始 PCM 写入 wav 文件，
 * 便于核对录音质量（是否为引擎问题/录音问题）。
 *
 * 文件写入 [getRecordingsDir]，文件名带时间戳，每次识别会话一个文件。
 */
class AudioSink(private val context: Context) {

    companion object {
        private const val TAG = "AudioSink"
        private const val SAMPLE_RATE = 16000

        /** 录音文件目录：filesDir/debug_asr/ */
        fun getRecordingsDir(context: Context): File {
            return File(context.filesDir, "debug_asr").apply { mkdirs() }
        }

        /** 最近的录音文件（无则 null） */
        fun latestRecording(context: Context): File? {
            return getRecordingsDir(context)
                .listFiles { f -> f.isFile && f.name.endsWith(".wav") }
                ?.maxByOrNull { it.lastModified() }
        }

        /** 删除全部调试录音 */
        fun clearRecordings(context: Context) {
            getRecordingsDir(context).listFiles()
                ?.filter { it.isFile && it.name.endsWith(".wav") }
                ?.forEach { it.delete() }
        }
    }

    private var out: FileOutputStream? = null
    private var file: File? = null
    private var dataSizeOffset = 0L

    fun start() {
        stop()
        try {
            val time = SimpleDateFormat("yyyyMMdd_HHmmss_SSS", Locale.US).format(Date())
            val f = File(getRecordingsDir(context), "rec_$time.wav")
            val os = FileOutputStream(f)
            file = f
            out = os
            writeWavHeader(os)
            FileLogger.i(TAG, "recording to ${f.absolutePath}")
        } catch (e: Exception) {
            FileLogger.e(TAG, "start failed", e)
        }
    }

    /** 写入一个 PCM 块（byte，16bit little endian，mono） */
    fun write(pcm: ByteArray) {
        try {
            out?.write(pcm)
        } catch (e: Exception) {
            FileLogger.e(TAG, "write failed", e)
        }
    }

    fun stop() {
        try {
            val os = out ?: return
            finalizeWavHeader(os)
            os.flush()
            os.close()
            val f = file
            if (f != null) {
                FileLogger.i(TAG, "saved ${f.absolutePath}, size=${f.length()}")
            }
        } catch (e: Exception) {
            FileLogger.e(TAG, "stop failed", e)
        } finally {
            out = null
            file = null
        }
    }

    private fun writeWavHeader(os: FileOutputStream) {
        val header = ByteArray(44)
        // RIFF
        header[0] = 'R'.code.toByte(); header[1] = 'I'.code.toByte()
        header[2] = 'F'.code.toByte(); header[3] = 'F'.code.toByte()
        // chunk size（先写 0，最终化时回填）
        header[4] = 0; header[5] = 0; header[6] = 0; header[7] = 0
        // WAVE
        header[8] = 'W'.code.toByte(); header[9] = 'A'.code.toByte()
        header[10] = 'V'.code.toByte(); header[11] = 'E'.code.toByte()
        // fmt
        header[12] = 'f'.code.toByte(); header[13] = 'm'.code.toByte()
        header[14] = 't'.code.toByte(); header[15] = ' '.code.toByte()
        // fmt chunk size = 16
        header[16] = 16; header[17] = 0; header[18] = 0; header[19] = 0
        // audio format = 1 (PCM)
        header[20] = 1; header[21] = 0
        // num channels = 1
        header[22] = 1; header[23] = 0
        // sample rate = 16000
        writeIntLE(header, 24, SAMPLE_RATE)
        // byte rate = 16000 * 1 * 2
        writeIntLE(header, 28, SAMPLE_RATE * 2)
        // block align = 1 * 2
        header[32] = 2; header[33] = 0
        // bits per sample = 16
        header[34] = 16; header[35] = 0
        // data
        header[36] = 'd'.code.toByte(); header[37] = 'a'.code.toByte()
        header[38] = 't'.code.toByte(); header[39] = 'a'.code.toByte()
        // data size（先写 0）
        header[40] = 0; header[41] = 0; header[42] = 0; header[43] = 0
        os.write(header)
        dataSizeOffset = 44L
    }

    private fun finalizeWavHeader(os: FileOutputStream) {
        val f = file ?: return
        try {
            val raf = java.io.RandomAccessFile(f, "rw")
            val dataSize = f.length() - 44
            if (dataSize < 0) {
                raf.close()
                return
            }
            raf.seek(4)
            writeIntLEBytes(raf, (36 + dataSize).toInt())
            raf.seek(40)
            writeIntLEBytes(raf, dataSize.toInt())
            raf.close()
        } catch (e: Exception) {
            FileLogger.e(TAG, "finalize header failed", e)
        }
    }

    private fun writeIntLE(b: ByteArray, offset: Int, value: Int) {
        b[offset] = (value and 0xFF).toByte()
        b[offset + 1] = ((value shr 8) and 0xFF).toByte()
        b[offset + 2] = ((value shr 16) and 0xFF).toByte()
        b[offset + 3] = ((value shr 24) and 0xFF).toByte()
    }

    private fun writeIntLEBytes(raf: java.io.RandomAccessFile, value: Int) {
        raf.write(byteArrayOf(
            (value and 0xFF).toByte(),
            ((value shr 8) and 0xFF).toByte(),
            ((value shr 16) and 0xFF).toByte(),
            ((value shr 24) and 0xFF).toByte()
        ))
    }
}

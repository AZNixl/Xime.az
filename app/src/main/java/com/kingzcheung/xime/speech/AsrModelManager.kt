package com.kingzcheung.xime.speech

import android.content.Context
import com.kingzcheung.xime.model.ModelStorage
import java.io.File

/**
 * ASR 模型管理与选择。
 *
 * 模型推理由自研的 streaming zipformer2 实现（libasr_jni.so）负责，
 * 本类仅提供模型信息、目录与选择逻辑，供设置页与本地语音后端使用。
 */
class AsrModelManager(private val context: Context) {

    companion object {
        val AVAILABLE_MODELS = listOf(
            AsrModelInfo(
                id = "zipformer-zh-int8",
                name = "中文 Zipformer int8",
                description = "Zipformer 架构，适合实时语音识别，int8 量化",
                language = "zh",
                size = "36 MB",
                downloadUrl = "https://www.modelscope.cn/models/bikeand/asr/resolve/master/sherpa-onnx-streaming-zipformer-zh-int8-2025-06-30.tar.bz2",
                modelType = "transducer",
                files = listOf("encoder.int8.onnx", "decoder.onnx", "joiner.int8.onnx", "tokens.txt"),
                encoderFile = "encoder.int8.onnx",
                decoderFile = "decoder.onnx",
                joinerFile = "joiner.int8.onnx"
            )
        )
    }

    data class AsrModelInfo(
        val id: String,
        val name: String,
        val description: String = "",
        val language: String,
        val size: String,
        val downloadUrl: String,
        val modelType: String = "transducer",
        val files: List<String>,
        val encoderFile: String = "",
        val decoderFile: String = "",
        val joinerFile: String = "",
        val needsAutoPunctuation: Boolean = true
    )

    fun isModelReady(): Boolean {
        val modelDir = getSelectedModelDir()
        if (!modelDir.exists()) return false
        val files = modelDir.listFiles()
        return files != null && files.isNotEmpty()
    }

    fun getSelectedModelDir(): File {
        val modelId = getSelectedModelId()
        val dir = ModelStorage.getModelDir(context, modelId)
        // 兼容旧版：自动迁移 asr_models/<id>/ 下的模型文件
        ModelStorage.migrateLegacyForModel(context, modelId)
        return dir
    }

    fun getSelectedModelId(): String {
        val sharedPrefs = context.getSharedPreferences("asr_model", Context.MODE_PRIVATE)
        return sharedPrefs.getString("selected_model", "zipformer-zh-int8") ?: "zipformer-zh-int8"
    }

    fun getSelectedModelInfo(): AsrModelInfo? {
        val modelId = getSelectedModelId()
        return AVAILABLE_MODELS.find { it.id == modelId }
    }

    fun setModel(modelId: String) {
        val sharedPrefs = context.getSharedPreferences("asr_model", Context.MODE_PRIVATE)
        sharedPrefs.edit().putString("selected_model", modelId).apply()
    }

    fun findFile(dir: File, fileName: String): File? {
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
}

package com.kingzcheung.xime.rime

import android.content.Context
import android.util.Log
import com.kingzcheung.xime.BuildConfig
import com.kingzcheung.xime.settings.PersonalDictManager
import com.kingzcheung.xime.settings.SchemaConfigHelper
import com.kingzcheung.xime.settings.SchemaManifestManager
import com.kingzcheung.xime.settings.SchemaManager
import com.kingzcheung.xime.settings.SettingsPreferences
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.io.File
import java.io.FileOutputStream
import java.io.IOException

object RimeConfigHelper {
    private const val TAG = "RimeConfigHelper"
    private const val ASSETS_RIME_DIR = "rime"

    /** 部署互斥：Application 预初始化与输入法服务初始化可能并发触发部署，串行化避免重复/并发全量编译。 */
    private val deploymentLock = Any()
    
    suspend fun initializeRimeDataAsync(context: Context): Pair<String, String> {
        val rimeDir = File(context.filesDir, "rime")
        
        // 迁移旧目录结构 (rime/shared/ + rime/user/) → 单一 rime/ 目录
        migrateOldStructure(context, rimeDir)
        
        // 迁移旧版 market 目录（rime/market/ → market/）
        migrateOldMarketDir(context)
        
        if (!rimeDir.exists()) {
            rimeDir.mkdirs()
        }
        
        copyAssetsToRimeDir(context, rimeDir)
        // F1: assets 会用内置 default.yaml 覆盖，这里把启用方案重新写回 schema_list
        SchemaManager.applyEnabledSchemasToDefaultYaml(context)
        // 为所有启用方案打个人词库补丁
        PersonalDictManager.ensureSchemaPacks(context)
        invalidateBuildIfConfigChanged(context)
        // 部署统一由 ensureDeployment() 在 engine 初始化后执行（本方法不持有 rimeLock）。
        
        return Pair(rimeDir.absolutePath, rimeDir.absolutePath)
    }

    /**
     * 统一部署入口（进程内互斥）：
     * - 部署 hash 一致 → build 已是最新，对齐 deploymentDone 标记，跳过编译；
     * - hash 缺失/不一致 → 清空 build 全量编译，成功后统一记录 hash 与 deploymentDone。
     *
     * 必须由调用方保证 engine 已 initialize（deploy() 未初始化时返回 false）。
     * 该入口被 Application 预初始化与输入法服务共享，配合 deploymentLock
     * 避免两者并发触发两次 24 秒级别的全量编译。
     */
    fun ensureDeployment(context: Context): Boolean {
        synchronized(deploymentLock) {
            val currentHash = computeDeploymentHash(context)
            if (currentHash.isNotEmpty() && currentHash == SettingsPreferences.getDeploymentHash(context)) {
                SettingsPreferences.setDeploymentDone(context, true)
                return true
            }
            Log.i(TAG, "Deployment hash mismatch or missing, clearing build for full deploy")
            val buildDir = File(context.filesDir, "rime/build")
            if (buildDir.exists()) {
                buildDir.deleteRecursively()
                buildDir.mkdirs()
            }
            if (RimeEngine.getInstance().deploy()) {
                storeDeploymentHash(context)
                SettingsPreferences.setDeploymentDone(context, true)
                return true
            }
            return false
        }
    }
    
    fun initializeRimeData(context: Context): Pair<String, String> {
        val rimeDir = File(context.filesDir, "rime")
        
        migrateOldStructure(context, rimeDir)
        
        if (!rimeDir.exists()) {
            rimeDir.mkdirs()
        }
        
        copyAssetsToRimeDir(context, rimeDir)
        // F1: 同步初始化路径也写回 default.yaml 的 schema_list
        SchemaManager.applyEnabledSchemasToDefaultYaml(context)
        runBlocking { PersonalDictManager.ensureSchemaPacks(context) }
        invalidateBuildIfConfigChanged(context)
        
        return Pair(rimeDir.absolutePath, rimeDir.absolutePath)
    }
    
    fun storeDeploymentHash(context: Context) {
        val hash = computeDeploymentHash(context)
        if (hash.isNotEmpty()) {
            SettingsPreferences.setDeploymentHash(context, hash)
        }
    }

    fun isDeploymentComplete(context: Context): Boolean {
        val rimeDir = File(context.filesDir, "rime")
        val buildDir = File(rimeDir, "build")
        if (!buildDir.exists()) return false

        val enabledSchemas = SchemaManager.getEnabledSchemas(context)
        if (enabledSchemas.isEmpty()) return false

        for (schemaId in enabledSchemas) {
            if (!File(buildDir, "$schemaId.prism.bin").exists() &&
                !File(buildDir, "$schemaId.schema.yaml").exists()) {
                return false
            }
        }

        val currentHash = computeDeploymentHash(context)
        if (currentHash.isEmpty()) return false

        val storedHash = SettingsPreferences.getDeploymentHash(context)
        if (storedHash.isEmpty()) {
            SettingsPreferences.setDeploymentHash(context, currentHash)
            return true
        }

        if (currentHash != storedHash) {
            return false
        }

        return true
    }

    private fun fileUpdateDigest(digest: java.security.MessageDigest, file: File) {
        if (!file.exists()) return
        java.io.FileInputStream(file).use { input ->
            java.security.DigestInputStream(input, digest).use { dis ->
                val buffer = ByteArray(8192)
                while (dis.read(buffer) != -1) { }
            }
        }
    }

    private fun computeDeploymentHash(context: Context): String {
        val rimeDir = File(context.filesDir, "rime")
        val digest = java.security.MessageDigest.getInstance("SHA-256")

        val enabledSchemas = SchemaManager.getEnabledSchemas(context)
        for (schemaId in enabledSchemas.sorted()) {
            val schemaFile = File(rimeDir, "$schemaId.schema.yaml")
            if (schemaFile.exists()) {
                digest.update(schemaId.toByteArray())
                fileUpdateDigest(digest, schemaFile)
            }
            val customFile = File(rimeDir, "$schemaId.custom.yaml")
            if (customFile.exists()) {
                fileUpdateDigest(digest, customFile)
            }
            // merged dict 由 app 生成、librime 实际编译，计入 hash 以便其变更（如转发器展开修复）触发重编译
            val mergedDictFile = File(rimeDir, "${schemaId}_merged.dict.yaml")
            if (mergedDictFile.exists()) {
                digest.update("${schemaId}_merged".toByteArray())
                fileUpdateDigest(digest, mergedDictFile)
            }
        }

        // 所有词典文件（内置词典与个人词库）纳入 hash：
        // 否则词典新增/变更（如 pinyin_simp.dict.yaml）不会改变 hash，
        // build 目录不重建、不重新部署，导致 table.bin 缺失（运行时反复报错）。
        rimeDir.listFiles()
            ?.filter { it.isFile && it.name.endsWith(".dict.yaml") }
            ?.sortedBy { it.name }
            ?.forEach { dictFile ->
                digest.update(dictFile.name.toByteArray())
                fileUpdateDigest(digest, dictFile)
            }

        val defaultYaml = File(rimeDir, "default.yaml")
        if (defaultYaml.exists()) {
            digest.update("default".toByteArray())
            fileUpdateDigest(digest, defaultYaml)
        }

        return digest.digest().joinToString("") { String.format("%02x", it) }
    }

    private fun invalidateBuildIfConfigChanged(context: Context) {
        val rimeDir = File(context.filesDir, "rime")
        val buildDir = File(rimeDir, "build")
        if (!buildDir.exists()) return
        val currentHash = computeDeploymentHash(context)
        val storedHash = SettingsPreferences.getDeploymentHash(context)
        if (!currentHash.isNullOrEmpty() && currentHash != storedHash) {
            Log.i(TAG, "Config hash changed, clearing build dir for fresh deploy")
            buildDir.deleteRecursively()
            buildDir.mkdirs()
        }
    }
    
    private fun copyAssetsToRimeDir(context: Context, targetDir: File): Boolean {
        // assets 编译进 APK，同一 versionCode 内不会变化。记录上次同步的版本号，
        // 一致时跳过全量拷贝：避免每次启动都覆盖 default.yaml 等文件（assets 默认压缩，
        // openFd 抛异常导致 size 判断失效），从而保证部署 hash 稳定、不再每次启动全量重编译。
        if (SettingsPreferences.getRimeAssetsVersion(context) == BuildConfig.VERSION_CODE) {
            return false
        }
        val copied = try {
            copyAssetsRecursively(context, ASSETS_RIME_DIR, targetDir)
        } catch (e: IOException) {
            Log.e(TAG, "Failed to copy assets", e)
            false
        }
        SettingsPreferences.setRimeAssetsVersion(context, BuildConfig.VERSION_CODE)
        return copied
    }
    
    private fun copyAssetsRecursively(context: Context, assetPath: String, targetDir: File): Boolean {
        val files = context.assets.list(assetPath)
        
        if (files.isNullOrEmpty()) {
            return false
        }
        
        var copiedAny = false
        
        for (fileName in files) {
            val fullAssetPath = "$assetPath/$fileName"
            val targetFile = File(targetDir, fileName)
            
            try {
                val subFiles = context.assets.list(fullAssetPath)
                if (!subFiles.isNullOrEmpty()) {
                    if (!targetFile.exists()) {
                        targetFile.mkdirs()
                    }
                    if (copyAssetsRecursively(context, fullAssetPath, targetFile)) {
                        copiedAny = true
                    }
                } else if (fileName.endsWith(".yaml") || fileName.endsWith(".lua")) {
                    val needsCopy = try {
                        if (targetFile.exists()) {
                            val fd = context.assets.openFd(fullAssetPath)
                            val sameSize = targetFile.length() == fd.length
                            fd.close()
                            !sameSize
                        } else true
                    } catch (_: Exception) {
                        true
                    }
                    if (needsCopy) {
                        copyAssetFile(context, fullAssetPath, targetFile)
                        copiedAny = true
                    }
                }
            } catch (e: IOException) {
                Log.e(TAG, "Failed to process: $fullAssetPath", e)
            }
        }
        
        return copiedAny
    }
    
    private fun copyAssetFile(context: Context, assetPath: String, targetFile: File) {
        try {
            if (targetFile.exists() && targetFile.name.contains("custom")) {
                return
            }

            targetFile.parentFile?.mkdirs()
            context.assets.open(assetPath).use { input ->
                FileOutputStream(targetFile).use { output ->
                    input.copyTo(output)
                }
            }
        } catch (e: IOException) {
            Log.e(TAG, "Failed to copy: $assetPath", e)
        }
    }

    private fun migrateOldStructure(context: Context, rimeDir: File) {
        val oldSharedDir = File(context.filesDir, "rime/shared")
        val oldUserDir = File(context.filesDir, "rime/user")
        
        if (!oldSharedDir.exists() && !oldUserDir.exists()) return
        
        Log.i(TAG, "Migrating old rime directory structure to single rime/ dir...")
        
        if (!rimeDir.exists()) rimeDir.mkdirs()
        
        // 迁移 user 数据（用户配置、build 产物、userdb）
        if (oldUserDir.exists()) {
            oldUserDir.listFiles()?.forEach { file ->
                val target = File(rimeDir, file.name)
                if (!target.exists()) {
                    file.renameTo(target)
                }
            }
        }
        
        // 迁移 shared 数据（方案文件）
        if (oldSharedDir.exists()) {
            oldSharedDir.listFiles()?.forEach { file ->
                val target = File(rimeDir, file.name)
                if (!target.exists()) {
                    file.renameTo(target)
                }
            }
        }
        
        // 删除旧目录
        oldSharedDir.deleteRecursively()
        oldUserDir.deleteRecursively()
        
        Log.i(TAG, "Migration complete")
    }

    /** 迁移旧版 market 目录（rime/market/ → market/）。 */
    private fun migrateOldMarketDir(context: Context) {
        val oldMarket = File(context.filesDir, "rime/market")
        if (!oldMarket.exists()) return

        val newMarket = SchemaManager.getMarketDir(context)
        if (!newMarket.exists()) {
            // 新位置不存在，直接重命名
            if (oldMarket.renameTo(newMarket)) {
                Log.i(TAG, "Migrated rime/market/ -> market/")
            } else {
                Log.w(TAG, "Failed to rename rime/market/ to market/")
            }
        } else {
            // 新位置已存在，逐项合并
            oldMarket.listFiles()?.forEach { sub ->
                val target = File(newMarket, sub.name)
                if (!target.exists()) {
                    sub.renameTo(target)
                }
            }
            oldMarket.deleteRecursively()
            Log.i(TAG, "Merged rime/market/ into market/")
        }
    }

}
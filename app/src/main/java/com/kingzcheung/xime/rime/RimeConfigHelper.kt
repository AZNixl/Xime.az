package com.kingzcheung.xime.rime

import android.content.Context
import android.util.Log
import com.kingzcheung.xime.BuildConfig
import com.kingzcheung.xime.settings.PersonalDictManager
import com.kingzcheung.xime.settings.SchemaConfigHelper
import com.kingzcheung.xime.settings.SchemaManifestManager
import com.kingzcheung.xime.settings.SchemaManager
import com.kingzcheung.xime.settings.SettingsPreferences
import com.kingzcheung.xime.util.XimeStorage
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
        val rimeDir = XimeStorage.rimeDir(context)
        
        // 迁移旧目录结构 (rime/shared/ + rime/user/) → 单一 rime/ 目录
        migrateOldStructure(context, rimeDir)
        
        // 迁移旧版 market 目录（rime/market/ → market/）
        migrateOldMarketDir(context)
        
        if (!rimeDir.exists()) {
            rimeDir.mkdirs()
        }
        
        copyAssetsToRimeDir(context, rimeDir)
        exportKeyboardConfigFiles(context, rimeDir)
        // F1: assets 会用内置 default.yaml 覆盖，这里把启用方案重新写回 schema_list
        SchemaManager.applyEnabledSchemasToDefaultYaml(context)
        // 为所有启用方案打个人词库补丁
        PersonalDictManager.ensureSchemaPacks(context)
        // 不再在初始化阶段删 build：build 是否重建统一由 ensureDeployment()
        // 按增量优先策略决定，避免配置变化即全量重编译（60MB 词库持锁 30s+）。
        
        return Pair(rimeDir.absolutePath, rimeDir.absolutePath)
    }

    /**
     * 统一部署入口（进程内互斥）：
     * - 部署 hash 一致 → build 已是最新，对齐 deploymentDone 标记，跳过编译；
     * - hash 缺失/不一致 → 优先增量维护（librime 按文件时间戳只编译变更），
     *   仅当 build 缺失/为空或增量失败时才清空全量编译。
     *
     * 必须由调用方保证 engine 已 initialize（deploy() 未初始化时返回 false）。
     * 该入口被 Application 预初始化与输入法服务共享，配合 deploymentLock
     * 避免两者并发触发两次全量编译。
     */
    fun ensureDeployment(context: Context): Boolean {
        synchronized(deploymentLock) {
            val currentHash = computeDeploymentHash(context)
            val appVersion = BuildConfig.VERSION_CODE
            val lastAppVersion = SettingsPreferences.getDeploymentAppVersion(context)
            // app 升级后必须重编译一次，即使方案文件没变（deployment hash 一致）。
            // 部署逻辑本身可能被修复过（如 custom.yaml 补丁合并），若只比对文件
            // hash，build 里会一直留着旧版编译出的产物，新修复永远落不到设备上
            // —— 表现为「改了代码但用户侧毫无变化」，只有手动点一次方案
            // （会改写 default.yaml 使 hash 变化）才临时生效。
            val appUpgraded = lastAppVersion != 0 && lastAppVersion != appVersion
            val hashMatches = currentHash.isNotEmpty() &&
                currentHash == SettingsPreferences.getDeploymentHash(context)
            if (hashMatches && !appUpgraded) {
                SettingsPreferences.setDeploymentDone(context, true)
                if (lastAppVersion == 0) {
                    SettingsPreferences.setDeploymentAppVersion(context, appVersion)
                }
                return true
            }
            if (appUpgraded) {
                Log.i(TAG, "App upgraded $lastAppVersion -> $appVersion, forcing schema recompile")
            } else {
                Log.i(TAG, "Deployment hash mismatch or missing")
            }
            val buildDir = File(XimeStorage.rimeDir(context), "build")
            val buildExists = buildDir.exists() && buildDir.listFiles()?.isNotEmpty() == true
            val engine = RimeEngine.getInstance()
            val deployed: Boolean
            if (buildExists) {
                // build 已就位但配置有变化：增量维护，只编译变更的 schema/dict，
                // 避免 custom.yaml 补丁等小幅改动触发 60MB 词库全量重编译（持锁 30s+）。
                //
                // 关键：librime 的增量检测对 `<schema>.custom.yaml` 的变化不可靠，
                // 实测会出现「custom 里挂的 translator / dependencies 没合并进 build」，
                // 表现为新方案必须手动点一次输入方案才生效。
                // 这里主动删掉启用方案在 build 中的 schema 编译产物，强制 librime
                // 重跑一遍 schema 合并（该过程会应用 custom.yaml 的 patch）。
                // 只删 schema 产物、保留 *.table.bin，词典仍可复用，不会触发 30s+ 全量编译。
                invalidateSchemaBuilds(context, buildDir)
                Log.i(TAG, "Build exists, running incremental maintenance")
                if (engine.deployIncremental()) {
                    deployed = true
                } else {
                    Log.w(TAG, "Incremental maintenance failed, falling back to full deploy")
                    buildDir.deleteRecursively()
                    buildDir.mkdirs()
                    deployed = engine.deploy()
                }
            } else {
                Log.i(TAG, "Build directory missing or empty, running full deploy")
                buildDir.mkdirs()
                deployed = engine.deploy()
            }
            if (deployed) {
                storeDeploymentHash(context)
                SettingsPreferences.setDeploymentAppVersion(context, appVersion)
                SettingsPreferences.setDeploymentDone(context, true)
                return true
            }
            return false
        }
    }
    
    /**
     * 删除启用方案在 build 目录中的 schema 编译产物，强制 librime 重新执行
     * `<schema>.schema.yaml` + `<schema>.custom.yaml` 的合并。
     *
     * 只删 schema/prism 产物，保留词典的 *.table.bin —— 这样 custom 里的
     * translators / filters / dependencies 一定能进 build，又不会退化成
     * 全量重编译（60MB 词库 30s+）。
     */
    private fun invalidateSchemaBuilds(context: Context, buildDir: File) {
        val schemas = try {
            SchemaManager.getEnabledSchemas(context)
        } catch (e: Exception) {
            Log.w(TAG, "Failed to read enabled schemas for invalidation", e)
            emptyList<String>()
        }
        if (schemas.isEmpty()) return
        var removed = 0
        for (sid in schemas) {
            for (name in listOf("$sid.schema.yaml", "$sid.prism.bin")) {
                val f = File(buildDir, name)
                if (f.exists() && f.delete()) removed++
            }
        }
        Log.i(TAG, "Invalidated $removed schema build artifact(s) for ${schemas.size} enabled schema(s)")
    }

    fun initializeRimeData(context: Context): Pair<String, String> {
        val rimeDir = XimeStorage.rimeDir(context)
        
        migrateOldStructure(context, rimeDir)
        
        if (!rimeDir.exists()) {
            rimeDir.mkdirs()
        }
        
        copyAssetsToRimeDir(context, rimeDir)
        exportKeyboardConfigFiles(context, rimeDir)
        // F1: 同步初始化路径也写回 default.yaml 的 schema_list
        SchemaManager.applyEnabledSchemasToDefaultYaml(context)
        runBlocking { PersonalDictManager.ensureSchemaPacks(context) }
        // build 重建统一由 ensureDeployment() 增量优先决策，此处不删 build
        
        return Pair(rimeDir.absolutePath, rimeDir.absolutePath)
    }
    
    fun storeDeploymentHash(context: Context) {
        val hash = computeDeploymentHash(context)
        if (hash.isNotEmpty()) {
            SettingsPreferences.setDeploymentHash(context, hash)
        }
    }

    fun isDeploymentComplete(context: Context): Boolean {
        val rimeDir = XimeStorage.rimeDir(context)
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
        val rimeDir = XimeStorage.rimeDir(context)
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

    private fun copyAssetsToRimeDir(context: Context, targetDir: File): Boolean {
        // assets 编译进 APK，同一 versionCode 内不会变化。记录上次同步的版本号，
        // 一致时跳过全量拷贝：避免每次启动都覆盖 default.yaml 等文件（assets 默认压缩，
        // openFd 抛异常导致 size 判断失效），从而保证部署 hash 稳定、不再每次启动全量重编译。
        // 版本匹配时必须确认关键文件确实已落地：若上次拷贝失败（如 assets 缺失/为空），
        // 仅记版本号而未拷入文件，覆盖安装后仍会误跳过导致方案文件永远缺失。
        val versionMatched = SettingsPreferences.getRimeAssetsVersion(context) == BuildConfig.VERSION_CODE
        if (versionMatched && File(targetDir, "default.yaml").exists()) {
            return false
        }
        val copied = try {
            copyAssetsRecursively(context, ASSETS_RIME_DIR, targetDir)
        } catch (e: IOException) {
            Log.e(TAG, "Failed to copy assets", e)
            false
        }
        // 仅当真正拷贝成功才记录版本，避免失败时留下"已同步"假象
        if (copied) {
            SettingsPreferences.setRimeAssetsVersion(context, BuildConfig.VERSION_CODE)
        }
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
                } else if (fileName.endsWith(".yaml") || fileName.endsWith(".lua") ||
                    fileName.endsWith(".txt") || fileName.endsWith(".bin")) {
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

    /**
     * 键盘配置导出已停用：键盘符号/手势统一在设置页「键盘符号编辑」维护，
     * 不再向 /Documents/Xime/rime/ 导出 xime.yaml / xime.custom.yaml.example。
     */
    private fun exportKeyboardConfigFiles(context: Context, rimeDir: File) {
        // no-op
    }

    private fun migrateOldStructure(context: Context, rimeDir: File) {
        val oldSharedDir = File(XimeStorage.rimeDir(context), "shared")
        val oldUserDir = File(XimeStorage.rimeDir(context), "user")
        
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
        val oldMarket = File(XimeStorage.rimeDir(context), "market")
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
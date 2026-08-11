package com.kingzcheung.xime.clipboard.sync

import android.content.Context
import android.content.IntentFilter
import android.os.PowerManager
import android.util.Log
import com.kingzcheung.xime.clipboard.ClipboardManager
import com.kingzcheung.xime.plugin.core.api.ClipboardProfile
import com.kingzcheung.xime.plugin.core.api.ClipboardSyncPlugin
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * 剪贴板同步引擎（仿 ximed SyncEngine）。
 *
 * 规则（与 ximed 设计一致的三通道去重语义）：
 * - 本地剪贴板变化 → 与上次推送 hash 不同且非"自写内容" → 推送到远端
 * - 轮询远端 → 与本地 hash 及"自写 hash"比对 → 不同才写回本地剪贴板
 * - 自写抑制：引擎写回本地后记录 hash，监听到同一 hash 判定为回声，跳过推送
 *
 * 宿主只持有引擎与剪贴板桥，具体传输协议（WebDAV/S3/ximed）由 [ClipboardSyncPlugin]
 * 的 Lua 实现承载。
 */
class ClipboardSyncBridge(
    private val context: Context,
    private val clipboardManager: ClipboardManager,
    private val plugin: ClipboardSyncPlugin
) {
    companion object {
        private const val TAG = "ClipboardSync"
        private const val DEFAULT_POLL_MS = 3000L
        private const val BATTERY_SAVER_POLL_MS = 60_000L
        private const val PUSH_RETRY_BACKOFF_MS = 5_000L
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** 上次推送/写入的 hash（去重）。 */
    @Volatile
    private var lastHash: String? = null

    /** 自写 hash：引擎写入本地剪贴板的内容 hash，用于回声抑制。 */
    @Volatile
    private var selfWritten: String? = null

    @Volatile
    private var running = false

    /** 推送失败后的退避截止时间。 */
    @Volatile
    private var retryUntil = 0L

    private var pollJob: Job? = null
    private var collectJob: Job? = null

    private val powerManager by lazy {
        context.getSystemService(Context.POWER_SERVICE) as PowerManager
    }

    fun start() {
        if (running) return
        running = true
        Log.d(TAG, "Sync started")

        // 1. 订阅本地剪贴板变化 → push（回声抑制：selfWritten 命中的跳过）
        collectJob = clipboardManager.clipboardChanged
            .filter { it.text.isNotBlank() }
            .onEach { item ->
                val hash = ClipboardProfile.sha256Hex(item.text.toByteArray(Charsets.UTF_8))
                if (hash == selfWritten) {
                    Log.d(TAG, "Echo suppressed (self-written)")
                    return@onEach
                }
                pushLocal(item.text, hash)
            }
            .launchIn(scope)

        // 2. 轮询远端 → 拉取 → 写回（hash 去重）
        pollJob = scope.launch {
            while (isActive) {
                try {
                    val interval = currentPollInterval()
                    pullRemote()
                    delay(interval)
                } catch (e: kotlinx.coroutines.CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Log.e(TAG, "Poll error", e)
                    delay(5000)
                }
            }
        }
    }

    fun stop() {
        if (!running) return
        running = false
        collectJob?.cancel()
        pollJob?.cancel()
        collectJob = null
        pollJob = null
        Log.d(TAG, "Sync stopped")
    }

    fun release() {
        stop()
        scope.cancel()
    }

    private fun currentPollInterval(): Long {
        return if (powerManager.isPowerSaveMode) BATTERY_SAVER_POLL_MS else DEFAULT_POLL_MS
    }

    private suspend fun pushLocal(text: String, hash: String) {
        if (!running) return
        if (hash == lastHash) {
            Log.d(TAG, "No change, skip push")
            return
        }
        if (System.currentTimeMillis() < retryUntil) {
            Log.d(TAG, "Push backoff active, skip")
            return
        }
        val profile = ClipboardProfile.fromText(text)
        val ok = try {
            plugin.push(profile)
        } catch (e: Exception) {
            Log.e(TAG, "push failed", e)
            false
        }
        if (ok) {
            lastHash = hash
            retryUntil = 0L
        } else {
            retryUntil = System.currentTimeMillis() + PUSH_RETRY_BACKOFF_MS
        }
    }

    private suspend fun pullRemote() {
        val remote = try {
            plugin.pull()
        } catch (e: Exception) {
            Log.e(TAG, "pull failed", e)
            null
        }
        if (remote == null) return

        val remoteHash = remote.hash
        val currentClipboard = clipboardManager.getCurrentClipboardText()
        val currentHash = currentClipboard?.let {
            ClipboardProfile.sha256Hex(it.toByteArray(Charsets.UTF_8))
        }

        // 与本地当前内容相同 或 与自己写回的内容相同 → 跳过（避免循环）
        if (remoteHash == currentHash || remoteHash == selfWritten) {
            Log.d(TAG, "Remote unchanged vs local, skip write")
            return
        }

        Log.d(TAG, "Remote changed, writing to local clipboard")
        lastHash = remoteHash
        selfWritten = remoteHash
        clipboardManager.copyToSystemClipboard(remote.text)
    }
}

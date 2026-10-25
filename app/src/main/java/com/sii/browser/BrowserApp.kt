package com.sii.browser

import android.app.ActivityManager
import android.app.Application
import android.app.ApplicationExitInfo
import android.content.Context
import android.os.Build
import com.sii.browser.di.AppServices

class BrowserApp : Application() {
    override fun onCreate() {
        super.onCreate()
        AppServices.init(this)
        com.sii.browser.core.privacy.CrashLog.init(this)
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            runCatching { com.sii.browser.core.privacy.CrashLog.record("Java", throwable) }
            if (previous != null) {
                previous.uncaughtException(thread, throwable)
            } else {
                android.os.Process.killProcess(android.os.Process.myPid())
                kotlin.system.exitProcess(10)
            }
        }
        recordPreviousExit()
    }

    /**
     * 补录上次进程的非正常退出：ANR、native 崩溃、低内存被杀。
     * 系统历史接口只在 API 30+ 提供；后台读取避免冷启动多一次 Binder 调用。
     * 首次启用只取最近一条，避免把安装前的旧记录一次性写进日志。
     */
    private fun recordPreviousExit() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return
        val reasons = setOf(
            ApplicationExitInfo.REASON_LOW_MEMORY,
            ApplicationExitInfo.REASON_CRASH_NATIVE,
            ApplicationExitInfo.REASON_ANR
        )
        Thread({
            runCatching<Unit> {
                val prefs = getSharedPreferences(EXIT_PREFS, Context.MODE_PRIVATE)
                val lastSeen = prefs.getLong(EXIT_LAST_SEEN, 0L)
                val manager = getSystemService(ActivityManager::class.java) ?: return@runCatching
                val candidates = manager.getHistoricalProcessExitReasons(packageName, 0, 16)
                    .orEmpty()
                    .filter { it.reason in reasons && it.timestamp > lastSeen }
                    .sortedBy { it.timestamp }
                val selected = if (lastSeen == 0L && candidates.size > 1) {
                    listOf(candidates.last())
                } else {
                    candidates
                }
                selected.forEach { info ->
                    val type = when (info.reason) {
                        ApplicationExitInfo.REASON_ANR -> "ANR"
                        ApplicationExitInfo.REASON_CRASH_NATIVE -> "Native"
                        ApplicationExitInfo.REASON_LOW_MEMORY -> "LowMem"
                        else -> "Exit"
                    }
                    val message = when (info.reason) {
                        ApplicationExitInfo.REASON_ANR -> "系统判定应用无响应（ANR）"
                        ApplicationExitInfo.REASON_CRASH_NATIVE -> "应用发生原生崩溃"
                        ApplicationExitInfo.REASON_LOW_MEMORY -> "系统因内存不足结束进程"
                        else -> "进程异常退出"
                    }
                    com.sii.browser.core.privacy.CrashLog.record(
                        type,
                        message,
                        buildString {
                            append("reason=").append(info.reason)
                            info.description?.takeIf { it.isNotEmpty() }?.let {
                                append("\ndescription=").append(it)
                            }
                            append("\nimportance=").append(info.importance)
                            append("\nstatus=").append(info.status)
                        }
                    )
                }
                prefs.edit().putLong(
                    EXIT_LAST_SEEN,
                    maxOf(lastSeen, maxOf(System.currentTimeMillis(), candidates.lastOrNull()?.timestamp ?: 0L))
                ).commit()
            }
        }, "sii-exit-info").start()
    }

    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        if (level >= TRIM_MEMORY_RUNNING_LOW) {
            com.sii.browser.data.SiteIconCache.trim()
            com.sii.browser.core.webview.SniffStore.trim()
            com.sii.browser.di.AppServices.trimThumbnails?.invoke()
        }
    }

    companion object {
        private const val EXIT_PREFS = "sii_exit_info"
        private const val EXIT_LAST_SEEN = "last_seen"
    }
}

package com.sii.browser.core.privacy

import android.content.Context
import java.io.File

data class CrashEntry(
    val time: Long,
    val type: String,
    val message: String,
    val stack: String
)

/**
 * 本地崩溃记录：只写应用私有目录，不上传。
 * 记录 Java 未捕获异常和 WebView 渲染进程崩溃事件，ANR/native crash 不在覆盖范围内。
 */
object CrashLog {

    private const val FILE_NAME = "sii_crash.log"
    private const val MAX_ENTRIES = 50
    private const val MAX_STACK = 12_000
    private val lock = Any()
    private var appContext: Context? = null
    private val entries = java.util.ArrayDeque<CrashEntry>()

    fun init(context: Context) {
        synchronized(lock) {
            if (appContext == null) {
                appContext = context.applicationContext
                load()
            }
        }
    }

    fun record(type: String, message: String?, stack: String? = null) {
        val entry = CrashEntry(
            System.currentTimeMillis(),
            type.take(32),
            message.orEmpty().take(500),
            stack.orEmpty().take(MAX_STACK)
        )
        synchronized(lock) {
            entries.addFirst(entry)
            while (entries.size > MAX_ENTRIES) entries.removeLast()
            write()
        }
    }

    fun record(type: String, throwable: Throwable) {
        record(type, throwable.message, android.util.Log.getStackTraceString(throwable))
    }

    fun snapshot(): List<CrashEntry> = synchronized(lock) { entries.toList() }

    fun clear() {
        synchronized(lock) {
            entries.clear()
            file()?.delete()
        }
    }

    private fun file(): File? = appContext?.let { File(it.filesDir, FILE_NAME) }

    private fun load() {
        val f = file() ?: return
        if (!f.exists()) return
        runCatching {
            f.readLines().forEach { line ->
                val parts = line.split('|', limit = 4)
                if (parts.size < 3) return@forEach
                val time = parts[0].toLongOrNull() ?: return@forEach
                entries.addLast(
                    CrashEntry(
                        time = time,
                        type = decode(parts[1]),
                        message = decode(parts[2]),
                        stack = if (parts.size > 3) decode(parts[3]) else ""
                    )
                )
            }
            while (entries.size > MAX_ENTRIES) entries.removeFirst()
        }
    }

    private fun write() {
        val f = file() ?: return
        runCatching {
            f.writeText(
                entries.joinToString("\n") { entry ->
                    listOf(
                        entry.time.toString(),
                        encode(entry.type),
                        encode(entry.message),
                        encode(entry.stack)
                    ).joinToString("|")
                }
            )
        }
    }

    private fun encode(value: String): String =
        android.util.Base64.encodeToString(value.toByteArray(), android.util.Base64.NO_WRAP)

    private fun decode(value: String): String =
        runCatching {
            String(android.util.Base64.decode(value, android.util.Base64.NO_WRAP))
        }.getOrDefault("")
}

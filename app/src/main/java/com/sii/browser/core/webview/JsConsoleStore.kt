// SPDX-License-Identifier: LicenseRef-Sii-NC
// Copyright (c) 2026 wanwuwww
package com.sii.browser.core.webview

import android.os.Handler
import android.os.Looper

/** 内置 JS 控制台的日志缓存（最多 300 条，避免长时间调试占内存）。 */
object JsConsoleStore {

    data class Entry(
        val time: Long,
        val level: String,
        val source: String,
        val line: Int,
        val message: String
    )

    private const val MAX = 300
    private val entries = ArrayDeque<Entry>()
    private val lock = Any()
    private val main = Handler(Looper.getMainLooper())
    private var pending = false

    var onChange: (() -> Unit)? = null

    fun add(level: String, source: String, line: Int, message: String) {
        synchronized(entries) {
            entries.addLast(
                Entry(
                    time = System.currentTimeMillis(),
                    level = level.take(16),
                    source = source.take(160),
                    line = line,
                    message = message.take(4000)
                )
            )
            while (entries.size > MAX) entries.removeFirst()
        }
        notifyChange()
    }

    fun snapshot(): List<Entry> = synchronized(entries) { entries.toList() }

    fun clear() {
        synchronized(entries) { entries.clear() }
        notifyChange()
    }

    private fun notifyChange() {
        synchronized(lock) {
            if (pending) return
            pending = true
        }
        main.postDelayed({
            synchronized(lock) { pending = false }
            onChange?.invoke()
        }, 120L)
    }
}

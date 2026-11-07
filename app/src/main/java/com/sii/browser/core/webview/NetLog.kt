// SPDX-License-Identifier: LicenseRef-Sii-NC
// Copyright (c) 2026 wanwuwww
package com.sii.browser.core.webview

/** 内存网络日志：只记录主文档与被广告拦截的请求，最多 200 条。 */
object NetLog {
    data class Entry(
        val time: Long,
        val url: String,
        val blocked: Boolean,
        val mainFrame: Boolean
    )

    private const val MAX = 200
    private val entries = ArrayDeque<Entry>()

    fun add(url: String, blocked: Boolean, mainFrame: Boolean) {
        synchronized(entries) {
            entries.addFirst(Entry(System.currentTimeMillis(), url, blocked, mainFrame))
            while (entries.size > MAX) entries.removeLast()
        }
    }

    fun snapshot(): List<Entry> = synchronized(entries) { entries.toList() }

    fun clear() = synchronized(entries) { entries.clear() }
}

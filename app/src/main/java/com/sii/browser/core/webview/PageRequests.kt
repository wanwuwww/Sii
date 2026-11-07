// SPDX-License-Identifier: LicenseRef-Sii-NC
// Copyright (c) 2026 wanwuwww
package com.sii.browser.core.webview

import java.util.WeakHashMap

/**
 * 「查看本页请求」用的元数据记录：只存 URL / 方法 / 类型 / 是否被拦截，
 * 仅内存、按标签限额、导航时清空。与 MCP 抓包无关，也不注入任何页面脚本。
 */
object PageRequests {

    data class Entry(
        val time: Long,
        val method: String,
        val url: String,
        val kind: String,
        val blocked: Boolean,
        val mainFrame: Boolean
    )

    private const val MAX_ENTRIES = 200
    private const val MAX_URL = 1024
    private val store = WeakHashMap<Any, ArrayDeque<Entry>>()
    private val lock = Any()

    fun record(owner: Any, method: String?, url: String, mainFrame: Boolean, blocked: Boolean) {
        if (url.isEmpty() || url.length > MAX_URL * 4) return
        val entry = Entry(
            time = System.currentTimeMillis(),
            method = (method ?: "GET").uppercase(),
            url = url.take(MAX_URL),
            kind = kindOf(url, mainFrame),
            blocked = blocked,
            mainFrame = mainFrame
        )
        synchronized(lock) {
            val queue = store.getOrPut(owner) { ArrayDeque() }
            queue.addLast(entry)
            while (queue.size > MAX_ENTRIES) queue.removeFirst()
        }
    }

    /** 最新在前。 */
    fun list(owner: Any): List<Entry> = synchronized(lock) {
        store[owner]?.toList().orEmpty().asReversed()
    }

    /**
     * 导航开始清空旧记录。[keepMainFrameFor] 给出本次地址时保留同站主文档条目：
     * 主文档在 onPageStarted 之前就已记录，直接清空会丢掉这次导航本身。
     */
    fun clear(owner: Any, keepMainFrameFor: String? = null) {
        synchronized(lock) {
            val queue = store[owner] ?: return
            if (keepMainFrameFor.isNullOrEmpty()) {
                store.remove(owner)
                return
            }
            val host = hostOf(keepMainFrameFor)
            val kept = queue.lastOrNull { it.mainFrame && hostOf(it.url) == host }
            queue.clear()
            if (kept != null) queue.addLast(kept)
        }
    }

    fun clearAll() {
        synchronized(lock) { store.clear() }
    }

    private fun hostOf(url: String): String? =
        runCatching { android.net.Uri.parse(url).host?.lowercase() }.getOrNull()

    /** 类型键由界面映射成文案，避免在核心层写死中文。 */
    private fun kindOf(url: String, mainFrame: Boolean): String {
        if (mainFrame) return "document"
        val path = url.substringBefore('?').substringBefore('#')
        return when (path.substringAfterLast('.', "").lowercase()) {
            "js", "mjs" -> "script"
            "css" -> "style"
            "png", "jpg", "jpeg", "webp", "gif", "svg", "avif", "ico" -> "image"
            "woff", "woff2", "ttf", "otf" -> "font"
            "mp4", "webm", "m3u8", "mp3", "m4a", "ts" -> "media"
            "html", "htm" -> "document"
            "json", "xml" -> "data"
            else -> "other"
        }
    }
}

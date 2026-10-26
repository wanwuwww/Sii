// SPDX-License-Identifier: LicenseRef-Sii-NC
// Copyright (c) 2026 wanwuwww
package com.sii.browser.core.webview

/** 嗅探到的资源类型（用于列表分类）。 */
enum class SniffType { HLS, VIDEO, AUDIO, IMAGE, DOC, MSE, OTHER }

/** 一条嗅探到的资源。 */
data class SniffedResource(
    val url: String,
    val type: SniffType,
    val mime: String? = null,
    val size: Long = 0L
)

/**
 * 资源嗅探结果暂存（按标签页 WebView 分组，内存态）。
 * 原生拦截与页面 JS 采集共用；页面切换/刷新时清空对应分组。
 */
object SniffStore {

    private val store = java.util.WeakHashMap<Any, LinkedHashMap<String, SniffedResource>>()
    private val lock = Any()
    private val main = android.os.Handler(android.os.Looper.getMainLooper())
    private val pendingNotify = java.util.LinkedHashSet<Any>()
    private val notifyRunnable = Runnable {
        val keys = synchronized(lock) { pendingNotify.toList().also { pendingNotify.clear() } }
        keys.forEach { onChange?.invoke(it) }
    }

    /** 列表变化回调（在调用线程触发，UI 侧自行切主线程）。 */
    var onChange: ((Any) -> Unit)? = null

    /** 新资源回调：用于浏览器页的“发现视频”提示。 */
    var onResource: ((Any, SniffedResource) -> Unit)? = null

    fun add(key: Any, resource: SniffedResource) {
        if (resource.url.isBlank()) return
        var changed = false
        var notify: SniffedResource? = null
        synchronized(lock) {
            val map = store.getOrPut(key) { LinkedHashMap() }
            val existing = map[resource.url]
            val merged = if (existing == null) {
                resource
            } else {
                existing.copy(
                    type = if (resource.type != SniffType.OTHER) resource.type else existing.type,
                    mime = resource.mime ?: existing.mime,
                    size = if (resource.size > 0L) resource.size else existing.size
                )
            }
            if (existing != merged) {
                map[resource.url] = merged
                changed = true
                val path = merged.url.substringBefore('?').substringBefore('#').lowercase()
                val segment = path.endsWith(".ts") || path.endsWith(".m4s")
                if ((existing == null || existing.type == SniffType.OTHER) &&
                    (merged.type == SniffType.HLS || merged.type == SniffType.VIDEO) &&
                    !segment
                ) {
                    notify = merged
                }
            }
        }
        if (changed) {
            scheduleNotify(key)
            notify?.let { onResource?.invoke(key, it) }
        }
    }

    /** 页面批量请求时合并 UI 通知，避免每个资源都触发一次列表重建。 */
    private fun scheduleNotify(key: Any) {
        synchronized(lock) {
            if (pendingNotify.isEmpty()) main.postDelayed(notifyRunnable, 120L)
            pendingNotify.add(key)
        }
    }

    fun list(key: Any): List<SniffedResource> =
        synchronized(lock) { store[key]?.values?.toList().orEmpty() }

    fun clear(key: Any) {
        synchronized(lock) { store[key]?.clear() }
        onChange?.invoke(key)
    }

    fun remove(key: Any) {
        synchronized(lock) { store.remove(key) }
        onChange?.invoke(key)
    }

    /** 低内存时清空所有标签的嗅探结果。 */
    fun trim() {
        synchronized(lock) { store.clear() }
    }

    /** 按 URL 扩展名（必要时结合 MIME）判断资源类型。 */
    fun typeOf(url: String, mime: String? = null): SniffType {
        if (url.startsWith("blob:", ignoreCase = true)) return SniffType.MSE
        val path = url.substringBefore('?').substringBefore('#').lowercase()
        val ext = path.substringAfterLast('.', "")
        return when (ext) {
            "m3u8", "m3u" -> SniffType.HLS
            "mp4", "m4v", "webm", "mkv", "flv", "avi", "mov", "ts", "m4s", "f4v", "3gp",
            "mpg", "mpeg", "wmv", "rmvb", "rm" -> SniffType.VIDEO
            "mp3", "m4a", "aac", "ogg", "oga", "wav", "flac", "opus", "wma" -> SniffType.AUDIO
            "jpg", "jpeg", "png", "gif", "webp", "bmp", "svg", "ico", "avif", "heic" -> SniffType.IMAGE
            "pdf", "doc", "docx", "xls", "xlsx", "ppt", "pptx", "txt", "zip", "rar", "7z",
            "apk", "epub", "mobi", "json", "xml", "csv", "torrent" -> SniffType.DOC
            else -> {
                val m = mime.orEmpty().lowercase()
                when {
                    m.contains("mpegurl") -> SniffType.HLS
                    m.startsWith("video/") -> SniffType.VIDEO
                    m.startsWith("audio/") -> SniffType.AUDIO
                    m.startsWith("image/") -> SniffType.IMAGE
                    else -> SniffType.OTHER
                }
            }
        }
    }
}

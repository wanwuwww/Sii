// SPDX-License-Identifier: LicenseRef-Sii-NC
// Copyright (c) 2026 wanwuwww
package com.sii.browser.ui

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.LruCache
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.Semaphore

/**
 * 资源嗅探列表的图片缩略图：按需下载、降采样到列表尺寸、内存 LRU 缓存。
 * 只处理图片类资源，不落盘；无痕模式下由调用方直接跳过。
 */
object SniffThumbs {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val client = OkHttpClient.Builder()
        .dns(com.sii.browser.core.dns.DohDns)
        .build()
    private val permits = Semaphore(3)
    private val videoPermits = Semaphore(2)
    private val frameExecutor = java.util.concurrent.Executors.newFixedThreadPool(2) { runnable ->
        Thread(runnable, "sii-thumb-frame").apply { isDaemon = true }
    }
    private val cache = object : LruCache<String, Bitmap>(8 * 1024) {
        override fun sizeOf(key: String, value: Bitmap): Int =
            (value.byteCount / 1024).coerceAtLeast(1)
    }

    fun cached(url: String): Bitmap? = cache.get(url)

    fun load(url: String, userAgent: String?, referer: String?, onReady: (Bitmap?) -> Unit) {
        cached(url)?.let {
            onReady(it)
            return
        }
        scope.launch {
            permits.acquire()
            val bitmap = try {
                runCatching { fetch(url, userAgent, referer) }.getOrNull()
            } finally {
                permits.release()
            }
            if (bitmap != null) cache.put(url, bitmap)
            withContext(Dispatchers.Main) { onReady(bitmap) }
        }
    }

    /** 普通视频文件取首帧；.ts/.m4s 分片、HLS、blob 直接返回 null（保留类型占位）。 */
    fun loadVideoFrame(
        url: String,
        userAgent: String?,
        referer: String?,
        onReady: (Bitmap?) -> Unit
    ) {
        if (!isProgressiveVideo(url)) {
            onReady(null)
            return
        }
        cached(url)?.let {
            onReady(it)
            return
        }
        scope.launch {
            videoPermits.acquire()
            val bitmap = try {
                frameOf(url, userAgent, referer)
            } finally {
                videoPermits.release()
            }
            if (bitmap != null) cache.put(url, bitmap)
            withContext(Dispatchers.Main) { onReady(bitmap) }
        }
    }

    private fun isProgressiveVideo(url: String): Boolean {
        if (!url.startsWith("http", true)) return false
        val ext = url.substringBefore('?').substringBefore('#').substringAfterLast('.', "").lowercase()
        return ext in VIDEO_EXTENSIONS
    }

    private fun frameOf(url: String, userAgent: String?, referer: String?): Bitmap? {
        val future = frameExecutor.submit<Bitmap?> {
            val retriever = android.media.MediaMetadataRetriever()
            try {
                val headers = HashMap<String, String>()
                userAgent?.takeIf { it.isNotBlank() }?.let { headers["User-Agent"] = it }
                referer?.takeIf { it.isNotBlank() }?.let { headers["Referer"] = it }
                android.webkit.CookieManager.getInstance().getCookie(url)
                    ?.takeIf { it.isNotBlank() }?.let { headers["Cookie"] = it }
                retriever.setDataSource(url, headers)
                retriever.getFrameAtTime(0, android.media.MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
            } finally {
                runCatching { retriever.release() }
            }
        }
        val frame = runCatching {
            future.get(FRAME_TIMEOUT_MS, java.util.concurrent.TimeUnit.MILLISECONDS)
        }.getOrNull()
        if (frame == null) {
            if (!future.isDone) future.cancel(true)
            return null
        }
        return scaleDown(frame)
    }

    private fun scaleDown(bitmap: Bitmap): Bitmap {
        val maxSide = maxOf(bitmap.width, bitmap.height)
        if (maxSide <= TARGET_PX) return bitmap
        val ratio = TARGET_PX.toFloat() / maxSide
        val scaled = Bitmap.createScaledBitmap(
            bitmap,
            (bitmap.width * ratio).toInt().coerceAtLeast(1),
            (bitmap.height * ratio).toInt().coerceAtLeast(1),
            true
        )
        if (scaled !== bitmap) bitmap.recycle()
        return scaled
    }

    private fun fetch(url: String, userAgent: String?, referer: String?): Bitmap? {
        val builder = Request.Builder().url(url)
        userAgent?.takeIf { it.isNotBlank() }?.let { builder.header("User-Agent", it) }
        referer?.takeIf { it.isNotBlank() }?.let { builder.header("Referer", it) }
        android.webkit.CookieManager.getInstance().getCookie(url)
            ?.takeIf { it.isNotBlank() }?.let { builder.header("Cookie", it) }
        client.newCall(builder.build()).execute().use { response ->
            if (!response.isSuccessful) return null
            val body = response.body ?: return null
            if (body.contentLength() > MAX_BYTES) return null
            val bytes = body.bytes()
            if (bytes.size > MAX_BYTES) return null
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
            var sample = 1
            while (bounds.outWidth / sample > TARGET_PX || bounds.outHeight / sample > TARGET_PX) {
                sample *= 2
            }
            val options = BitmapFactory.Options().apply { inSampleSize = sample }
            return BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
        }
    }

    private const val MAX_BYTES = 4L * 1024 * 1024
    private const val TARGET_PX = 160
    private const val FRAME_TIMEOUT_MS = 8_000L
    private val VIDEO_EXTENSIONS = setOf(
        "mp4", "m4v", "webm", "mkv", "mov", "flv", "avi", "3gp", "mpg", "mpeg", "wmv"
    )
}

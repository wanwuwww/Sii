// SPDX-License-Identifier: LicenseRef-Sii-NC
// Copyright (c) 2026 wanwuwww
package com.sii.browser.download

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream
import java.net.URI
import java.util.concurrent.TimeUnit
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * HLS（m3u8）视频下载：解析播放列表（含多码率选择与 AES-128 分片解密），
 * 顺序下载分片并拼接输出 `.ts` 文件（播放器可直接播放，不做 mp4 重封装）。
 */
object HlsDownloader {

    private val client = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    data class Progress(val total: Int, val done: Int, val bytes: Long)

    suspend fun download(
        playlistUrl: String,
        referer: String?,
        userAgent: String?,
        outFile: File,
        onProgress: (Progress) -> Unit
    ): Boolean = withContext(Dispatchers.IO) {
        runCatching {
            val mediaPlaylist = resolveMediaPlaylist(playlistUrl, referer, userAgent)
            val segments = parseSegments(mediaPlaylist)
            if (segments.isEmpty()) return@runCatching false
            outFile.parentFile?.mkdirs()
            var bytes = 0L
            var done = 0
            onProgress(Progress(segments.size, 0, 0))
            FileOutputStream(outFile).use { out ->
                segments.forEach { seg ->
                    val data = fetchSegment(seg, referer, userAgent)
                    out.write(data)
                    bytes += data.size
                    done++
                    onProgress(Progress(segments.size, done, bytes))
                }
            }
            true
        }.getOrDefault(false)
    }

    /** 多码率主播放列表时选带宽最高的一路，返回媒体播放列表 URL。 */
    private fun resolveMediaPlaylist(url: String, referer: String?, ua: String?): String {
        var current = url
        repeat(3) {
            val text = fetchText(current, referer, ua)
            if (!text.contains("#EXT-X-STREAM-INF")) return current
            var bestBandwidth = -1L
            var bestUri: String? = null
            val lines = text.lineSequence().map { it.trim() }.toList()
            lines.forEachIndexed { i, line ->
                if (line.startsWith("#EXT-X-STREAM-INF")) {
                    val bw = Regex("BANDWIDTH=(\\d+)").find(line)?.groupValues?.get(1)?.toLongOrNull() ?: 0L
                    val uri = lines.getOrNull(i + 1)?.takeIf { it.isNotEmpty() && !it.startsWith("#") }
                    if (uri != null && bw > bestBandwidth) {
                        bestBandwidth = bw
                        bestUri = uri
                    }
                }
            }
            val next = bestUri ?: return current
            current = URI(current).resolve(next).toString()
        }
        return current
    }

    private data class Segment(val url: String, val key: ByteArray?, val iv: ByteArray?)

    private fun parseSegments(playlistUrl: String): List<Segment> {
        val text = fetchText(playlistUrl, null, null)
        val base = URI(playlistUrl)
        val segments = mutableListOf<Segment>()
        var key: ByteArray? = null
        var iv: ByteArray? = null
        var mediaSequence = 0L
        var index = 0L
        text.lineSequence().map { it.trim() }.forEach { line ->
            when {
                line.startsWith("#EXT-X-MEDIA-SEQUENCE:") ->
                    mediaSequence = line.substringAfter(':').toLongOrNull() ?: 0L
                line.startsWith("#EXT-X-KEY:") -> {
                    val method = Regex("METHOD=([^,]*)").find(line)?.groupValues?.get(1).orEmpty()
                    if (method.equals("AES-128", true)) {
                        val uri = Regex("URI=\"([^\"]*)\"").find(line)?.groupValues?.get(1)
                        key = uri?.let { fetchBytes(base.resolve(it).toString(), null, null) }
                        val ivHex = Regex("IV=0x([0-9A-Fa-f]+)").find(line)?.groupValues?.get(1)
                        iv = ivHex?.let { hexToBytes(it) }
                    } else {
                        key = null
                        iv = null
                    }
                }
                line.isEmpty() || line.startsWith("#") -> Unit
                else -> {
                    val segmentIv = iv ?: if (key != null) sequenceToIv(mediaSequence + index) else null
                    segments.add(Segment(base.resolve(line).toString(), key, segmentIv))
                    index++
                }
            }
        }
        return segments
    }

    private fun fetchSegment(seg: Segment, referer: String?, ua: String?): ByteArray {
        var lastError: Exception? = null
        repeat(3) {
            try {
                val data = fetchBytes(seg.url, referer, ua)
                val key = seg.key
                return if (key != null && seg.iv != null) decrypt(data, key, seg.iv) else data
            } catch (e: Exception) {
                lastError = e
            }
        }
        throw lastError ?: IllegalStateException("segment failed")
    }

    private fun decrypt(data: ByteArray, key: ByteArray, iv: ByteArray): ByteArray {
        val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), IvParameterSpec(iv))
        return cipher.doFinal(data)
    }

    private fun sequenceToIv(sequence: Long): ByteArray {
        val iv = ByteArray(16)
        var value = sequence
        for (i in 15 downTo 0) {
            iv[i] = (value and 0xFF).toByte()
            value = value shr 8
        }
        return iv
    }

    private fun hexToBytes(hex: String): ByteArray =
        ByteArray(hex.length / 2) { ((Character.digit(hex[it * 2], 16) shl 4) +
            Character.digit(hex[it * 2 + 1], 16)).toByte() }

    private fun request(url: String, referer: String?, ua: String?): Request =
        Request.Builder().url(url).apply {
            if (!referer.isNullOrEmpty()) header("Referer", referer)
            if (!ua.isNullOrEmpty()) header("User-Agent", ua)
        }.build()

    private fun fetchText(url: String, referer: String?, ua: String?): String =
        client.newCall(request(url, referer, ua)).execute().use { resp ->
            require(resp.isSuccessful) { "HTTP ${resp.code}" }
            resp.body?.string().orEmpty()
        }

    private fun fetchBytes(url: String, referer: String?, ua: String?): ByteArray =
        client.newCall(request(url, referer, ua)).execute().use { resp ->
            require(resp.isSuccessful) { "HTTP ${resp.code}" }
            resp.body?.bytes() ?: ByteArray(0)
        }
}

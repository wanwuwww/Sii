// SPDX-License-Identifier: LicenseRef-Sii-NC
// Copyright (c) 2026 wanwuwww
package com.sii.browser.download

import java.io.ByteArrayOutputStream

/**
 * 下载文件名推导（三处入口共用：DownloadListener、JS 桥、服务兜底）。
 *
 * 顺序：Content-Disposition（含 RFC 5987 的 filename*）→ URL 的
 * response-content-disposition 查询参数 → 原始链接 → 最终链接 → downloadfile。
 * 服务器没给扩展名时只按小型 MIME 表补；**不追加 .bin**（猜不到就保持无后缀）。
 */
object DownloadNaming {

    private val hashPattern = Regex("^[0-9a-fA-F]{32,64}$")

    private val mimeExtensions = mapOf(
        "application/pdf" to "pdf",
        "application/zip" to "zip",
        "application/vnd.android.package-archive" to "apk",
        "application/x-apk" to "apk",
        "video/mp4" to "mp4",
        "audio/mpeg" to "mp3",
        "image/jpeg" to "jpg",
        "image/png" to "png",
        "image/gif" to "gif",
        "image/webp" to "webp",
        "text/plain" to "txt",
        "application/json" to "json"
    )

    fun resolve(
        url: String,
        disposition: String?,
        mime: String?,
        originalUrl: String? = null
    ): String {
        val fromHeader = parseDisposition(disposition)
        val fromQuery = parseDisposition(queryDisposition(url))
        val candidates = listOfNotNull(
            fromHeader,
            fromQuery,
            originalUrl?.let { nameFromUrl(it) },
            nameFromUrl(url)
        )
        var name = candidates.firstOrNull { it.isNotBlank() && !isHash(it) } ?: "downloadfile"
        name = sanitize(name)
        if (name.isBlank()) name = "downloadfile"

        val lowerUrl = url.substringBefore('?').lowercase()
        val lowerMime = mime.orEmpty().lowercase()
        val lowerDisposition = disposition.orEmpty().lowercase()
        val isApk = lowerMime.contains("android.package-archive") ||
            lowerMime == "application/x-apk" ||
            lowerUrl.endsWith(".apk") ||
            lowerDisposition.contains(".apk")
        if (isApk && !name.lowercase().endsWith(".apk")) {
            name = name.substringBeforeLast('.', name) + ".apk"
        }
        // 没有扩展名时才按 MIME 补；未知 MIME 不补 .bin。
        if (!name.contains('.')) {
            val ext = mimeExtensions[lowerMime.substringBefore(';').trim()]
            if (!ext.isNullOrBlank()) name = "$name.$ext"
        }
        return name
    }

    /** 解析 Content-Disposition；filename*（RFC 5987）优先于 filename。 */
    private fun parseDisposition(raw: String?): String? {
        if (raw.isNullOrBlank()) return null
        val star = Regex("""filename\*\s*=\s*([^;]+)""", RegexOption.IGNORE_CASE)
            .find(raw)?.groupValues?.getOrNull(1)
        val plain = Regex("""filename\s*=\s*([^;]+)""", RegexOption.IGNORE_CASE)
            .find(raw)?.groupValues?.getOrNull(1)
        var value = (star ?: plain)?.trim() ?: return null
        // RFC 5987：charset'lang'value
        val marker = value.indexOf("''")
        if (marker > 0) value = value.substring(marker + 2)
        value = value.trim().trim('"', '\'')
        if (value.isBlank()) return null
        return percentDecode(value)
    }

    /** HF/S3/CDN 会把 disposition 放进查询参数：response-content-disposition。 */
    private fun queryDisposition(url: String): String? = runCatching {
        android.net.Uri.parse(url).getQueryParameter("response-content-disposition")
    }.getOrNull()

    private fun nameFromUrl(url: String): String? {
        val path = url.substringBefore('?').substringBefore('#')
        val segment = path.substringAfterLast('/', "")
        if (segment.isBlank()) return null
        val decoded = percentDecode(segment)
        return if (isHash(decoded.substringBeforeLast('.'))) null else decoded
    }

    /** 32/40/64 位十六进制 = 内容哈希，不能当文件名。 */
    private fun isHash(value: String): Boolean = hashPattern.matches(value)

    private fun sanitize(raw: String): String =
        raw.trim().replace(Regex("[\\\\/:*?\"<>|\\u0000-\\u001F]"), "_").take(120)

    /** 只解 %XX，保留字面量 '+'（文件名里的加号不应变成空格）。 */
    private fun percentDecode(value: String): String {
        if (!value.contains('%')) return value
        val bytes = ByteArrayOutputStream()
        val out = StringBuilder()
        var i = 0
        while (i < value.length) {
            val c = value[i]
            if (c == '%' && i + 2 < value.length) {
                val hex = value.substring(i + 1, i + 3)
                val decoded = hex.toIntOrNull(16)
                if (decoded != null) {
                    bytes.write(decoded)
                    i += 3
                    continue
                }
            }
            if (bytes.size() > 0) {
                out.append(String(bytes.toByteArray(), Charsets.UTF_8))
                bytes.reset()
            }
            out.append(c)
            i++
        }
        if (bytes.size() > 0) out.append(String(bytes.toByteArray(), Charsets.UTF_8))
        return out.toString()
    }
}

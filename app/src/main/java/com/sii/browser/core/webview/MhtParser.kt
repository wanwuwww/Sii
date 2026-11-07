// SPDX-License-Identifier: LicenseRef-Sii-NC
// Copyright (c) 2026 wanwuwww
package com.sii.browser.core.webview

import android.util.Base64
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.charset.Charset

/** MHT（multipart/related）解包：还原成资源内嵌的 HTML，交给 WebView 直接渲染。 */
object MhtParser {

    fun toHtml(file: File): String? {
        val text = runCatching { String(file.readBytes(), Charsets.ISO_8859_1) }.getOrNull() ?: return null
        val boundary = Regex("(?i)boundary\\s*=\\s*\"?([^\";\\r\\n]+)\"?")
            .find(text)?.groupValues?.get(1)?.trim() ?: return null
        var mainHtml: String? = null
        val resources = mutableMapOf<String, String>()
        for (chunk in text.split("--$boundary")) {
            val normalized = chunk.replace("\r\n", "\n")
            val headerEnd = normalized.indexOf("\n\n")
            if (headerEnd < 0) continue
            val headers = normalized.substring(0, headerEnd)
            val body = normalized.substring(headerEnd + 2).trimEnd('\n')
            val type = Regex("(?i)content-type:\\s*([^;\\r\\n]+)")
                .find(headers)?.groupValues?.get(1)?.trim()?.lowercase() ?: continue
            val encoding = Regex("(?i)content-transfer-encoding:\\s*(\\S+)")
                .find(headers)?.groupValues?.get(1)?.trim()?.lowercase() ?: "8bit"
            val location = Regex("(?i)content-location:\\s*(\\S+)")
                .find(headers)?.groupValues?.get(1)?.trim()
                ?: Regex("(?i)content-id:\\s*<?([^>\\r\\n]+)>?")
                    .find(headers)?.groupValues?.get(1)?.trim()
            val bytes = when (encoding) {
                "base64" -> runCatching {
                    Base64.decode(body.filterNot { it.isWhitespace() }, Base64.DEFAULT)
                }.getOrElse { body.toByteArray(Charsets.ISO_8859_1) }
                "quoted-printable" -> decodeQuotedPrintable(body)
                else -> body.toByteArray(Charsets.ISO_8859_1)
            }
            if (mainHtml == null && type.startsWith("text/html")) {
                val charset = Regex("(?i)charset=([^;\\r\\n]+)")
                    .find(headers)?.groupValues?.get(1)?.trim()?.trim('"')
                    ?.let { runCatching { Charset.forName(it) }.getOrNull() } ?: Charsets.UTF_8
                mainHtml = String(bytes, charset)
            } else if (location != null) {
                resources[location] =
                    "data:$type;base64," + Base64.encodeToString(bytes, Base64.NO_WRAP)
            }
        }
        var html = mainHtml ?: return null
        // 长 URL 先替换，避免短 URL 是长 URL 前缀时误伤。
        for ((location, data) in resources.entries.sortedByDescending { it.key.length }) {
            html = html.replace(location, data)
        }
        return html.replace(
            Regex("(?i)charset\\s*=\\s*[\"']?[\\w-]+[\"']?"),
            "charset=utf-8"
        )
    }

    /** 解包到缓存目录，返回可直接用 file:// 打开的 HTML 文件。 */
    fun toCachedHtml(dir: File, file: File): File? {
        val html = toHtml(file) ?: return null
        return runCatching {
            dir.mkdirs()
            val target = File(dir, "${file.name.hashCode().toUInt()}.html")
            target.writeText(html)
            target.takeIf { it.length() > 0 }
        }.getOrNull()
    }

    private fun decodeQuotedPrintable(body: String): ByteArray {
        val out = ByteArrayOutputStream()
        var i = 0
        while (i < body.length) {
            val c = body[i]
            if (c == '=' && i + 2 < body.length && body[i + 1] == '\r' && body[i + 2] == '\n') {
                i += 3
            } else if (c == '=' && i + 1 < body.length && body[i + 1] == '\n') {
                i += 2
            } else if (c == '=' && i + 2 < body.length) {
                val value = body.substring(i + 1, i + 3).toIntOrNull(16)
                if (value != null) {
                    out.write(value)
                    i += 3
                } else {
                    out.write(c.code)
                    i++
                }
            } else {
                out.write(c.code)
                i++
            }
        }
        return out.toByteArray()
    }
}

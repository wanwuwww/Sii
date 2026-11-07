// SPDX-License-Identifier: LicenseRef-Sii-NC
// Copyright (c) 2026 wanwuwww
package com.sii.browser.download

import android.content.Context
import android.webkit.JavascriptInterface
import com.sii.browser.di.AppServices
import kotlinx.coroutines.launch

/**
 * 网页下载 JS 桥（window.sii.download）。
 * 页面内资源（含 blob 下载场景）通过此桥触发下载服务。
 */
class DownloadJsBridge(
    private val context: Context,
    private val secret: String
) {

    private val scope = kotlinx.coroutines.CoroutineScope(
        kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.IO
    )

    companion object {
        /** 单次 blob 下载上限，避免网页用超大 base64 占满内存。 */
        private const val MAX_BLOB_BYTES = 64L * 1024 * 1024
        private const val MAX_BASE64_LENGTH = MAX_BLOB_BYTES * 4 / 3
    }

    @JavascriptInterface
    fun download(secret: String, url: String, data: String?) {
        if (secret != this.secret) return
        if (url.isBlank() || url.length > 100_000) return
        val uri = runCatching { android.net.Uri.parse(url) }.getOrNull() ?: return
        if (uri.scheme != "http" && uri.scheme != "https") return
        val name = DownloadNaming.resolve(url, null, null)
        com.sii.browser.download.DownloadDispatcher.start(context, url, name, null)
    }

    @JavascriptInterface
    fun isDownloaderEnabled(): Boolean = true

    /** 保存页面内 blob 资源（blob: 无法走网络下载，由页面读出 base64 后回传）。 */
    @JavascriptInterface
    fun saveBlob(name: String, data: String, secret: String) {
        if (secret != this.secret) return
        if (name.isBlank() || data.isBlank()) return
        if (data.length > MAX_BASE64_LENGTH) return
        val safeName = name.replace(Regex("""[\\/:*?"<>|]"""), "_")
            .trim().take(120).ifBlank { "download" }
        scope.launch {
            runCatching {
                val dir = context.getExternalFilesDir(android.os.Environment.DIRECTORY_DOWNLOADS)
                    ?: java.io.File(context.filesDir, "Download").apply { mkdirs() }
                if (!dir.exists()) dir.mkdirs()
                val target = java.io.File(dir, safeName)
                require(target.canonicalPath.startsWith(dir.canonicalPath + java.io.File.separator))
                target.writeBytes(android.util.Base64.decode(data, android.util.Base64.DEFAULT))
            }
        }
    }
}

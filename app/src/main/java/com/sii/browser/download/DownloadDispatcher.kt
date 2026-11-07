// SPDX-License-Identifier: LicenseRef-Sii-NC
// Copyright (c) 2026 wanwuwww
package com.sii.browser.download

import android.app.DownloadManager
import android.content.Context
import android.net.Uri
import android.os.Environment
import android.webkit.CookieManager
import android.webkit.MimeTypeMap
import android.webkit.WebSettings
import com.sii.browser.di.AppServices
import java.io.File

/**
 * 下载入口分发：普通 http/https 文件按设置选择系统下载器或内建引擎。
 * blob / HLS / data 等特殊类型不走这里，仍由各自入口处理。
 */
object DownloadDispatcher {

    fun start(
        context: Context,
        url: String,
        name: String?,
        mime: String? = null,
        referer: String? = null,
        userAgent: String? = null
    ): Boolean {
        if (url.isBlank()) return false
        val uri = runCatching { Uri.parse(url) }.getOrNull() ?: return false
        val scheme = uri.scheme?.lowercase()
        if (scheme != "http" && scheme != "https") return false
        if (isWifiOnlyBlocked(context)) {
            showWifiOnlyToast(context)
            return false
        }
        // 无痕模式不把 Cookie/URL 交给系统下载器。
        val useSystem = AppServices.settings.downloader() == 1 &&
            !AppServices.privacyManager.isIncognito
        if (!useSystem) {
            DownloadService.startDownload(
                context, url, name, mime,
                incognito = AppServices.privacyManager.isIncognito
            )
            return true
        }
        return enqueueSystem(context, uri, name, mime, referer, userAgent)
    }

    /** 仅 Wi-Fi 下载：当前不是 Wi-Fi/以太网/非计费 VPN 时返回 true。 */
    fun isWifiOnlyBlocked(context: Context): Boolean {
        if (!runCatching { AppServices.settings.downloadWifiOnly() }.getOrDefault(false)) {
            return false
        }
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? android.net.ConnectivityManager
            ?: return false
        val caps = cm.activeNetwork?.let { cm.getNetworkCapabilities(it) }
        val onWifi = caps?.hasTransport(android.net.NetworkCapabilities.TRANSPORT_WIFI) == true ||
            caps?.hasTransport(android.net.NetworkCapabilities.TRANSPORT_ETHERNET) == true ||
            (caps?.hasTransport(android.net.NetworkCapabilities.TRANSPORT_VPN) == true &&
                !cm.isActiveNetworkMetered)
        return !onWifi
    }

    fun showWifiOnlyToast(context: Context) {
        android.os.Handler(android.os.Looper.getMainLooper()).post {
            android.widget.Toast.makeText(
                context.applicationContext,
                com.sii.browser.R.string.download_wifi_only_hint,
                android.widget.Toast.LENGTH_SHORT
            ).show()
        }
    }

    private fun enqueueSystem(
        context: Context,
        uri: Uri,
        name: String?,
        mime: String?,
        referer: String?,
        userAgent: String?
    ): Boolean = runCatching {
        val url = uri.toString()
        val fileName = uniqueFileName(url, name)
        val resolvedMime = mime?.takeIf { it.isNotBlank() } ?: mimeForName(fileName)
        val request = DownloadManager.Request(uri)
            .setNotificationVisibility(
                DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED
            )
            .setTitle(fileName)
            .setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, fileName)
        resolvedMime?.let { request.setMimeType(it) }
        CookieManager.getInstance().getCookie(url)
            ?.takeIf { it.isNotBlank() }
            ?.let { request.addRequestHeader("Cookie", it) }
        referer?.takeIf { it.startsWith("http") }?.let {
            request.addRequestHeader("Referer", it)
        }
        val ua = userAgent?.takeIf { it.isNotBlank() }
            ?: WebSettings.getDefaultUserAgent(context)
        request.addRequestHeader("User-Agent", ua)
        val manager = context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
        manager.enqueue(request)
        true
    }.getOrDefault(false)

    private fun uniqueFileName(url: String, name: String?): String {
        val raw = name?.trim().takeUnless { it.isNullOrEmpty() }
            ?: url.substringAfterLast('/').substringBefore('?').ifBlank { "download" }
        val cleaned = raw.replace(Regex("""[\\/:*?"<>|]"""), "_")
            .trim()
            .take(120)
            .ifBlank { "download" }
        val dir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
        val file = File(dir, cleaned)
        if (!file.exists()) return cleaned
        val dot = cleaned.lastIndexOf('.')
        val base = if (dot > 0) cleaned.substring(0, dot) else cleaned
        val ext = if (dot > 0) cleaned.substring(dot) else ""
        return "${base}-${System.currentTimeMillis()}$ext"
    }

    private fun mimeForName(name: String): String? {
        val ext = name.substringAfterLast('.', "").lowercase()
        return MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext)
    }
}

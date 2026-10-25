package com.sii.browser.core.webview

import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse

/**
 * 资源嗅探拦截器：不拦截请求，只按 URL/MIME 记录到 [SniffStore]。
 * 只记录媒体与文档类资源，避免把 JS/CSS/普通请求也塞进列表。
 */
class SniffInterceptor(private val key: Any) : ResourceInterceptor {

    override fun shouldIntercept(
        url: String,
        request: WebResourceRequest?,
        pageUrl: String?
    ): WebResourceResponse? {
        if (!com.sii.browser.di.AppServices.settings.sniffEnabled()) return null
        if (!url.startsWith("http")) return null
        val headers = request?.requestHeaders
        val accept = headers?.get("Accept").orEmpty()
        val dest = headers?.get("Sec-Fetch-Dest").orEmpty().lowercase()
        val type = when (dest) {
            "video" -> SniffType.VIDEO
            "audio" -> SniffType.AUDIO
            else -> SniffStore.typeOf(url, accept)
        }
        val interesting = type != SniffType.OTHER ||
            accept.contains("mpegurl", true) ||
            accept.contains("video", true) ||
            accept.contains("audio", true) ||
            dest == "video" || dest == "audio"
        if (interesting) {
            SniffStore.add(key, SniffedResource(url, type))
        }
        return null
    }
}

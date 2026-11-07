// SPDX-License-Identifier: LicenseRef-Sii-NC
// Copyright (c) 2026 wanwuwww
package com.sii.browser.core.adblock

import android.util.Base64
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import com.sii.browser.core.webview.ResourceInterceptor

/**
 * 广告拦截器（实现 webview 层的 [ResourceInterceptor]）。
 * 命中规则时返回空响应（普通资源）或 1×1 gif（图片资源），阻断加载。
 */
class AdBlockInterceptor(private val engine: AdBlockEngine) : ResourceInterceptor {

    override fun shouldIntercept(
        url: String,
        request: WebResourceRequest?,
        pageUrl: String?
    ): WebResourceResponse? {
        // 没有请求上下文时无法判断类型，放行；主文档规则由引擎按 $document 放行。
        if (request == null) return null
        if (!engine.shouldBlock(url, request, pageUrl)) return null
        return if (looksLikeImage(url, request)) BlockResponses.EMPTY_GIF else BlockResponses.EMPTY_TEXT
    }

    private fun looksLikeImage(url: String, request: WebResourceRequest?): Boolean {
        val accept = request?.requestHeaders?.get("Accept").orEmpty().lowercase()
        if ("image/" in accept) return true
        val path = url.substringBefore('?').lowercase()
        return IMAGE_EXTENSIONS.any { path.endsWith(it) }
    }

    private companion object {
        val IMAGE_EXTENSIONS = listOf(
            ".png", ".jpg", ".jpeg", ".gif", ".webp", ".bmp", ".svg", ".ico"
        )
    }
}

/** 拦截响应常量。 */
object BlockResponses {
    private const val GIF_BASE64 =
        "R0lGODlhAQABAID/AMDAwAAAACH5BAEAAAAALAAAAAABAAEAAAICRAEAOw=="

    val EMPTY_TEXT: WebResourceResponse =
        WebResourceResponse("text/plain", "UTF-8", java.io.ByteArrayInputStream(ByteArray(0)))

    val EMPTY_GIF: WebResourceResponse = WebResourceResponse(
        "image/gif", "UTF-8", java.io.ByteArrayInputStream(Base64.decode(GIF_BASE64, Base64.DEFAULT))
    )
}

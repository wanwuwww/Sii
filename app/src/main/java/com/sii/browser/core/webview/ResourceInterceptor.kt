// SPDX-License-Identifier: LicenseRef-Sii-NC
// Copyright (c) 2026 wanwuwww
package com.sii.browser.core.webview

import android.content.Context
import android.webkit.WebResourceResponse
import android.webkit.WebView

/**
 * 资源拦截器。
 * 实现返回非空 [WebResourceResponse] 即拦截该请求；返回 null 放行。
 * 广告拦截实现见 core/adblock 模块。
 */
interface ResourceInterceptor {
    fun shouldIntercept(
        url: String,
        request: android.webkit.WebResourceRequest?,
        pageUrl: String? = null
    ): WebResourceResponse?
}

/**
 * 拦截链：按注册顺序逐个尝试，第一个非空响应生效。
 */
class ResourceInterceptorChain(interceptors: List<ResourceInterceptor> = emptyList()) {

    private val list = interceptors.toMutableList()

    fun add(interceptor: ResourceInterceptor) {
        list.add(interceptor)
    }

    fun intercept(
        url: String,
        request: android.webkit.WebResourceRequest?,
        pageUrl: String? = null
    ): WebResourceResponse? {
        for (interceptor in list) {
            interceptor.shouldIntercept(url, request, pageUrl)?.let {
                NetLog.add(url, blocked = true, mainFrame = request?.isForMainFrame == true)
                return it
            }
        }
        if (request?.isForMainFrame == true) {
            NetLog.add(url, blocked = false, mainFrame = true)
        }
        return null
    }
}

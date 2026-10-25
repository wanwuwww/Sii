package com.sii.browser.core.webview

/**
 * URL 加载决策器。
 * 返回 true 表示本次加载已被处理（如新标签、外部应用打开），WebView 不再自行加载。
 */
interface UrlLoadDecision {
    fun shouldLoad(
        webView: SiiWebView?,
        request: android.webkit.WebResourceRequest?,
        url: String,
        action: Int
    ): Boolean
}

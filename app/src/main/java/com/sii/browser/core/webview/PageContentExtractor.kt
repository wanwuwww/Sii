package com.sii.browser.core.webview

/**
 * 页面文本提取/注入接口（约束 5：为离线翻译等功能预留）。
 * 由 [SiiWebView] 实现，基于 evaluateJavascript 与页面 DOM 交互。
 */
interface PageContentExtractor {
    /** 提取当前页面纯文本，结果回调（失败为 null）。 */
    fun extractPageText(onResult: (String?) -> Unit)

    /** 注入 JavaScript 到当前页面，可选结果回调。 */
    fun injectJavaScript(script: String, onResult: ((String?) -> Unit)? = null)
}

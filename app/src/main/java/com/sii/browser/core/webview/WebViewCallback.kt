package com.sii.browser.core.webview

import android.graphics.Bitmap
import android.net.http.SslError
import android.os.Message
import android.view.View
import android.webkit.ConsoleMessage
import android.webkit.GeolocationPermissions
import android.webkit.HttpAuthHandler
import android.webkit.JsPromptResult
import android.webkit.JsResult
import android.webkit.PermissionRequest
import android.webkit.SslErrorHandler
import android.webkit.SafeBrowsingResponse
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse

/**
 * 浏览器 WebView 回调基类。
 * 所有方法默认空实现/返回 false，浏览器核心模块继承本类按需覆写。
 * 由 [WebViewClientBridge] 与 [WebChromeClientBridge] 转发调用。
 */
open class WebViewCallback {

    // ---- WebChromeClient 回调 ----

    open fun onProgressChanged(webView: SiiWebView?, progress: Int) {}

    open fun onReceivedTitle(webView: SiiWebView?, title: String) {}

    open fun onReceivedIcon(webView: SiiWebView?, icon: Bitmap) {}

    open fun onReceivedTouchIconUrl(webView: SiiWebView?, url: String, precomposed: Boolean) {}

    open fun onConsoleMessage(message: ConsoleMessage): Boolean = false

    open fun onConsoleMessage(text: String, lineNumber: Int, sourceId: String): Boolean = false

    open fun onJsAlert(webView: SiiWebView?, url: String, message: String, result: JsResult): Boolean = false

    open fun onJsConfirm(webView: SiiWebView?, url: String, message: String, result: JsResult): Boolean = false

    open fun onJsPrompt(
        webView: SiiWebView?,
        url: String,
        message: String,
        defaultValue: String,
        result: JsPromptResult
    ): Boolean = false

    open fun onJsBeforeUnload(webView: SiiWebView?, url: String, message: String, result: JsResult): Boolean = false

    open fun onCreateWindow(webView: SiiWebView?, userGesture: Boolean, isDialog: Boolean, resultMsg: Message): Boolean = false

    open fun onCloseWindow(webView: SiiWebView?): Boolean = false

    open fun onShowCustomView(view: View, callback: WebChromeClient.CustomViewCallback) {}

    open fun onHideCustomView(): Boolean = false

    open fun onGeolocationPermissionsShowPrompt(origin: String, callback: GeolocationPermissions.Callback): Boolean = false

    open fun onGeolocationPermissionsHidePrompt(): Boolean = false

    open fun onPermissionRequest(request: PermissionRequest): Boolean = false

    open fun onPermissionRequestCanceled(request: PermissionRequest): Boolean = false

    open fun onShowFileChooser(
        webView: SiiWebView?,
        valueCallback: ValueCallback<Array<android.net.Uri>>,
        params: WebChromeClient.FileChooserParams
    ): Boolean = false

    open fun onOpenFileChooserLegacy(
        valueCallback: ValueCallback<android.net.Uri>,
        acceptType: String?,
        capture: String?
    ) {}

    open fun getVideoLoadingProgressView(): View? = null

    // ---- WebViewClient 回调 ----

    open fun onPageStarted(webView: SiiWebView?, url: String, favicon: Bitmap?) {}

    open fun onPageFinished(webView: SiiWebView?, url: String) {}

    open fun onPageCommitVisible(webView: SiiWebView?, url: String) {}

    /** 导航提交/重定向即回调。 */
    open fun doUpdateVisitedHistory(webView: SiiWebView?, url: String) {}

    open fun onReceivedErrorMainFrame(webView: SiiWebView?, errorCode: Int, description: String?, url: String) {}

    open fun onRenderProcessGone(
        webView: SiiWebView?,
        detail: android.webkit.RenderProcessGoneDetail?
    ): Boolean = false

    open fun onReceivedHttpAuthRequest(
        webView: SiiWebView?,
        handler: HttpAuthHandler,
        host: String,
        realm: String
    ): Boolean = false

    open fun onReceivedSslError(webView: SiiWebView?, handler: SslErrorHandler, error: SslError): Boolean = false

    @androidx.annotation.RequiresApi(27)
    open fun onSafeBrowsingHit(
        webView: SiiWebView?,
        request: WebResourceRequest,
        threatType: Int,
        callback: SafeBrowsingResponse
    ): Boolean = false

    open fun onFormResubmission(webView: SiiWebView?, resendMessage: Message, dontResendMessage: Message): Boolean = false

    /**
     * 资源请求拦截入口（shouldInterceptRequest）。
     * 返回非空响应即拦截；拦截链在 [WebViewClientBridge] 中先于本方法执行。
     */
    open fun interceptResource(
        webView: SiiWebView?,
        request: WebResourceRequest?,
        url: String,
        referer: String?
    ): WebResourceResponse? = null

    /**
     * 评估 URL 加载动作（shouldOverrideUrlLoading 前置）。
     * 返回动作码由浏览器核心定义（新标签/外部应用/继续加载等）。
     */
    open fun evaluateUrlAction(webView: SiiWebView?, url: String, isLinkHit: Boolean, isNewWindow: Boolean): Int = 0
}

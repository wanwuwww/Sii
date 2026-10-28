// SPDX-License-Identifier: LicenseRef-Sii-NC
// Copyright (c) 2026 wanwuwww
package com.sii.browser.core.webview

import android.graphics.Bitmap
import android.net.http.SslError
import android.os.Build
import android.os.Message
import android.webkit.HttpAuthHandler
import android.webkit.SslErrorHandler
import android.webkit.SafeBrowsingResponse
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient

/**
 * WebViewClient 桥接层。
 * 回调全部转给 [WebViewCallback]；资源请求先过 [ResourceInterceptorChain]；
 * URL 加载决策委托 [UrlLoadDecision]。
 */
@Suppress("DEPRECATION", "OVERRIDE_DEPRECATION")
class WebViewClientBridge(
    private val callback: WebViewCallback,
    private val urlDecision: UrlLoadDecision? = null,
    private val interceptors: ResourceInterceptorChain,
    private val captureEnabled: () -> Boolean = { false },
    private val pageRequestsEnabled: () -> Boolean = { false }
) : WebViewClient() {

    private var currentUrl: String? = null

    override fun onPageStarted(view: WebView, url: String, favicon: Bitmap?) {
        currentUrl = url
        (view as? SiiWebView)?.rememberReferer(url)
        callback.onPageStarted(view as? SiiWebView, url, favicon)
    }

    override fun onPageFinished(view: WebView, url: String) {
        callback.onPageFinished(view as? SiiWebView, url)
    }

    override fun onPageCommitVisible(view: WebView, url: String) {
        callback.onPageCommitVisible(view as? SiiWebView, url)
    }

    override fun doUpdateVisitedHistory(view: WebView, url: String, isReload: Boolean) {
        callback.doUpdateVisitedHistory(view as? SiiWebView, url)
    }

    override fun onFormResubmission(view: WebView, dontResend: Message, resend: Message) {
        callback.onFormResubmission(view as? SiiWebView, dontResend, resend)
    }

    override fun onRenderProcessGone(
        view: WebView,
        detail: android.webkit.RenderProcessGoneDetail?
    ): Boolean {
        callback.onRenderProcessGone(view as? SiiWebView, detail)
        return true
    }

    override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
        if (Build.VERSION.SDK_INT < 23 || !request.isForMainFrame || request.url == null) return
        callback.onReceivedErrorMainFrame(
            view as? SiiWebView,
            error.errorCode,
            error.description?.toString(),
            request.url.toString()
        )
    }

    override fun onReceivedHttpAuthRequest(view: WebView, handler: HttpAuthHandler, host: String, realm: String) {
        callback.onReceivedHttpAuthRequest(view as? SiiWebView, handler, host, realm)
    }

    override fun onReceivedSslError(view: WebView, handler: SslErrorHandler, error: SslError) {
        callback.onReceivedSslError(view as? SiiWebView, handler, error)
    }

    @androidx.annotation.RequiresApi(27)
    override fun onSafeBrowsingHit(
        view: WebView,
        request: WebResourceRequest,
        threatType: Int,
        callback: SafeBrowsingResponse
    ) {
        if (!this.callback.onSafeBrowsingHit(view as? SiiWebView, request, threatType, callback)) {
            callback.backToSafety(true)
        }
    }

    override fun shouldInterceptRequest(view: WebView, url: String): WebResourceResponse? {
        val response = interceptors.intercept(url, null, currentUrl)
            ?: callback.interceptResource(view as? SiiWebView, null, url, (view as? SiiWebView)?.getReferer())
        if (captureEnabled()) {
            val target = view as? SiiWebView
            if (target != null) {
                if (response != null) NetworkCapture.recordBlocked(target, url, null, false)
                else NetworkCapture.recordRequest(target, null, url, false)
            }
        }
        return response ?: super.shouldInterceptRequest(view, url)
    }

    override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? {
        val url = request.url.toString()
        val response = interceptors.intercept(url, request, currentUrl)
            ?: callback.interceptResource(view as? SiiWebView, request, url, (view as? SiiWebView)?.getReferer())
        val target = view as? SiiWebView
        if (pageRequestsEnabled() && target != null) {
            PageRequests.record(
                target, request.method, url, request.isForMainFrame, response != null
            )
        }
        if (captureEnabled()) {
            if (target != null) {
                if (response != null) {
                    NetworkCapture.recordBlocked(target, url, request.method, request.isForMainFrame)
                } else {
                    NetworkCapture.recordRequest(
                        target, request.method, url, request.isForMainFrame
                    )
                }
            }
        }
        return response ?: super.shouldInterceptRequest(view, request)
    }

    override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
        val url = request.url.toString()
        val isLinkHit = view.hitTestResult?.type ?: 0
        val action = callback.evaluateUrlAction(
            view as? SiiWebView, url, isLinkHit == 0, false, request.isForMainFrame
        )
        return urlDecision?.shouldLoad(view as? SiiWebView, request, url, action) ?: false
    }

    @Suppress("DEPRECATION")
    override fun shouldOverrideUrlLoading(view: WebView, url: String): Boolean {
        val action = callback.evaluateUrlAction(
            view as? SiiWebView, url, view.hitTestResult?.type == 0, false, true
        )
        return urlDecision?.shouldLoad(view as? SiiWebView, null, url, action) ?: false
    }

    /** 当前主框架 URL（供桥接层内部与拦截链使用）。 */
    fun currentUrl(): String? = currentUrl
}

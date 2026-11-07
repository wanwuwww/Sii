// SPDX-License-Identifier: LicenseRef-Sii-NC
// Copyright (c) 2026 wanwuwww
package com.sii.browser.core.webview

import android.graphics.Bitmap
import android.net.Uri
import android.os.Message
import android.view.View
import android.webkit.ConsoleMessage
import android.webkit.GeolocationPermissions
import android.webkit.JsPromptResult
import android.webkit.JsResult
import android.webkit.PermissionRequest
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebView

/**
 * WebChromeClient 桥接层。
 * 全部回调转给 [WebViewCallback]。
 */
@Suppress("DEPRECATION", "OVERRIDE_DEPRECATION")
class WebChromeClientBridge(private val callback: WebViewCallback) : WebChromeClient() {

    override fun getDefaultVideoPoster(): Bitmap {
        val poster = Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_4444)
        poster.eraseColor(0)
        return poster
    }

    override fun getVideoLoadingProgressView(): View? = callback.getVideoLoadingProgressView()

    override fun onCloseWindow(window: WebView) {
        callback.onCloseWindow(window as? SiiWebView)
    }

    override fun onConsoleMessage(message: ConsoleMessage): Boolean = callback.onConsoleMessage(message)

    @Suppress("DEPRECATION")
    override fun onConsoleMessage(message: String, lineNumber: Int, sourceId: String) {
        callback.onConsoleMessage(message, lineNumber, sourceId)
    }

    override fun onCreateWindow(view: WebView, isDialog: Boolean, isUserGesture: Boolean, resultMsg: Message): Boolean =
        callback.onCreateWindow(view as? SiiWebView, isUserGesture, isDialog, resultMsg)

    override fun onGeolocationPermissionsHidePrompt() {
        callback.onGeolocationPermissionsHidePrompt()
    }

    override fun onGeolocationPermissionsShowPrompt(origin: String, callback: GeolocationPermissions.Callback) {
        this.callback.onGeolocationPermissionsShowPrompt(origin, callback)
    }

    override fun onHideCustomView() {
        callback.onHideCustomView()
    }

    override fun onJsAlert(view: WebView, url: String, message: String, result: JsResult): Boolean =
        callback.onJsAlert(view as? SiiWebView, url, message, result)

    override fun onJsBeforeUnload(view: WebView, url: String, message: String, result: JsResult): Boolean =
        callback.onJsBeforeUnload(view as? SiiWebView, url, message, result)

    override fun onJsConfirm(view: WebView, url: String, message: String, result: JsResult): Boolean =
        callback.onJsConfirm(view as? SiiWebView, url, message, result)

    override fun onJsPrompt(view: WebView, url: String, message: String, defaultValue: String, result: JsPromptResult): Boolean =
        callback.onJsPrompt(view as? SiiWebView, url, message, defaultValue, result)

    override fun onPermissionRequest(request: PermissionRequest) {
        callback.onPermissionRequest(request)
    }

    override fun onPermissionRequestCanceled(request: PermissionRequest) {
        callback.onPermissionRequestCanceled(request)
    }

    override fun onProgressChanged(view: WebView, newProgress: Int) {
        callback.onProgressChanged(view as? SiiWebView, newProgress)
    }

    override fun onReceivedIcon(view: WebView, icon: Bitmap) {
        callback.onReceivedIcon(view as? SiiWebView, icon)
    }

    override fun onReceivedTitle(view: WebView, title: String) {
        callback.onReceivedTitle(view as? SiiWebView, title)
    }

    override fun onReceivedTouchIconUrl(view: WebView, url: String, precomposed: Boolean) {
        callback.onReceivedTouchIconUrl(view as? SiiWebView, url, precomposed)
    }

    override fun onShowCustomView(view: View, callback: WebChromeClient.CustomViewCallback) {
        this.callback.onShowCustomView(view, callback)
    }

    @Suppress("DEPRECATION")
    override fun onShowCustomView(view: View, requestedOrientation: Int, callback: WebChromeClient.CustomViewCallback) {
        this.callback.onShowCustomView(view, callback)
    }

    override fun onShowFileChooser(
        webView: WebView,
        filePathCallback: ValueCallback<Array<Uri>>,
        fileChooserParams: FileChooserParams
    ): Boolean = callback.onShowFileChooser(webView as? SiiWebView, filePathCallback, fileChooserParams)
}

// SPDX-License-Identifier: LicenseRef-Sii-NC
// Copyright (c) 2026 wanwuwww
package com.sii.browser.core.privacy

import android.content.Context
import android.webkit.CookieManager
import android.webkit.WebStorage
import android.webkit.WebView
import android.webkit.WebViewDatabase
import kotlinx.coroutines.launch

/**
 * 隐私管理。
 *
 * 职责：清除浏览数据（缓存/Cookie/表单/网页存储）、无痕模式标记、
 * 退出时清除。历史数据由数据层仓库负责。
 */
class PrivacyManager(private val context: Context) {

    /** 无痕模式（运行时状态，不持久化）：浏览不写入历史。 */
    var isIncognito: Boolean = false

    /** 当前无痕会话开始时间，用于只清理本次会话创建的下载。 */
    var incognitoSessionStartedAt: Long = 0L

    /** 清除全部浏览数据。 */
    fun clearAll(clearHistory: () -> Unit) {
        clearCache()
        clearCookies()
        clearFormData()
        clearWebStorage()
        clearHistory()
    }

    /** 应用缓存（WebView 缓存 + 缓存目录）；避免主线程删目录和遗留 WebView。 */
    fun clearCache() {
        val cacheDir = context.cacheDir
        kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.IO).launch {
            runCatching { cacheDir?.listFiles()?.forEach { it.deleteRecursively() } }
        }
        android.os.Handler(android.os.Looper.getMainLooper()).post {
            runCatching {
                val webView = WebView(context)
                webView.clearCache(true)
                webView.destroy()
            }
        }
    }

    fun clearCookies() {
        CookieManager.getInstance().removeAllCookies(null)
        CookieManager.getInstance().flush()
    }

    @Suppress("DEPRECATION")
    fun clearFormData() {
        WebViewDatabase.getInstance(context).clearFormData()
        WebViewDatabase.getInstance(context).clearHttpAuthUsernamePassword()
    }

    /** 网页存储（localStorage/IndexedDB 等）。 */
    fun clearWebStorage() {
        WebStorage.getInstance().deleteAllData()
    }
}

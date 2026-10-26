// SPDX-License-Identifier: LicenseRef-Sii-NC
// Copyright (c) 2026 wanwuwww
package com.sii.browser.core.mcp

import com.sii.browser.core.webview.NetworkCapture

/** 浏览器状态摘要。 */
data class BrowserState(
    val url: String,
    val title: String,
    val homeVisible: Boolean,
    val incognito: Boolean,
    val tabCount: Int,
    val activeTab: Int,
    val canGoBack: Boolean,
    val canGoForward: Boolean
)

/** 标签摘要（index 为面板顺序）。 */
data class TabSummary(
    val index: Int,
    val id: Long,
    val title: String,
    val url: String,
    val active: Boolean
)

data class FindOutcome(val current: Int, val total: Int)

data class BookmarkHit(val title: String, val url: String, val folder: String?)

data class HistoryHit(val title: String, val url: String, val time: Long)

/**
 * 浏览器侧能力。实现必须把 WebView 操作切到主线程；
 * [uiReady] 为 false 时写操作应返回失败，避免视图销毁后访问控件。
 */
interface McpHost {
    suspend fun state(): BrowserState

    suspend fun tabs(): List<TabSummary>

    suspend fun pageText(tab: Int?, maxChars: Int): String

    suspend fun pageHtml(tab: Int?, maxChars: Int): String

    suspend fun selection(tab: Int?): String

    suspend fun links(tab: Int?, limit: Int): String

    suspend fun findInPage(tab: Int?, query: String): FindOutcome

    suspend fun openUrl(url: String): Boolean

    suspend fun newTab(url: String): Boolean

    suspend fun switchTab(index: Int): Boolean

    suspend fun closeTab(index: Int): Boolean

    suspend fun navigate(action: String): Boolean

    suspend fun click(tab: Int?, selector: String): String

    suspend fun fill(tab: Int?, selector: String, text: String): String

    suspend fun scrollTo(
        tab: Int?,
        selector: String?,
        x: Int?,
        y: Int?,
        position: String?
    ): String

    suspend fun evaluateJavascript(tab: Int?, code: String): String?

    suspend fun captureList(
        tab: Int?,
        limit: Int,
        kind: String?,
        onlyBlocked: Boolean
    ): List<NetworkCapture.Entry>

    suspend fun captureGet(tab: Int?, id: Long): NetworkCapture.Entry?

    suspend fun captureClear(tab: Int?): Boolean

    suspend fun searchBookmarks(query: String, limit: Int): List<BookmarkHit>

    suspend fun searchHistory(query: String, limit: Int): List<HistoryHit>

    suspend fun addBookmark(url: String, title: String): Boolean

    suspend fun deleteBookmark(url: String): Boolean

    suspend fun deleteHistory(url: String): Boolean

    /** 当前是否无痕模式。 */
    suspend fun incognito(): Boolean

    /** 浏览器界面是否可执行写操作（Fragment 视图存在）。 */
    suspend fun uiReady(): Boolean
}

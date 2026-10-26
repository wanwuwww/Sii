// SPDX-License-Identifier: LicenseRef-Sii-NC
// Copyright (c) 2026 wanwuwww
package com.sii.browser.ui

import com.sii.browser.core.webview.SiiWebView

/**
 * 标签页。
 */
class Tab(
    val id: Long,
    var url: String,
    var title: String,
    val webView: SiiWebView
) {
    /** 地址栏安全状态：普通、HTTP 明文、证书错误、安全浏览威胁。 */
    var security: SiteSecurity = SiteSecurity.SAFE

    /** 标签卡片缩略图，仅内存保留。 */
    var thumbnail: android.graphics.Bitmap? = null
}

enum class SiteSecurity {
    SAFE,
    HTTP,
    CERT_ERROR,
    THREAT
}

/** 已关闭标签的记录（只保留用于重新打开的地址与标题）。 */
class ClosedTab(val url: String, val title: String)

/**
 * 标签管理。
 *
 * 职责：新建/关闭/切换/关闭全部/恢复关闭栈。标签持久化（tabs 表）
 * 由数据模块（Room）实现，本类仅内存管理。
 */
class TabManager(private val webViewFactory: () -> SiiWebView) {

    private val _tabs = mutableListOf<Tab>()
    private val closedTabs = ArrayDeque<ClosedTab>()
    private var nextId = 0L
    private val maxTabs = 30

    /** 当前标签列表（只读视图）。 */
    val tabs: List<Tab> get() = _tabs

    /** 标签变化（新建/关闭/切换）时回调，用于刷新标签栏。 */
    var onChanged: (() -> Unit)? = null

    val size: Int get() = _tabs.size

    val current: Tab? get() = _tabs.getOrNull(currentIndex)

    val currentWebView: SiiWebView? get() = current?.webView

    var currentIndex: Int = -1
        private set

    fun newTab(url: String): Tab {
        if (_tabs.size >= maxTabs) {
            val victim = _tabs.indexOfFirst { it !== current }
            if (victim >= 0) closeTab(victim)
        }
        val tab = Tab(nextId++, url, "", webViewFactory())
        _tabs.add(tab)
        currentIndex = _tabs.lastIndex
        notifyChanged()
        return tab
    }

    fun switchTab(index: Int): Boolean {
        if (index !in _tabs.indices || index == currentIndex) return false
        currentIndex = index
        notifyChanged()
        return true
    }

    /** 关闭指定标签；关闭当前标签时自动切到相邻标签。 */
    fun closeTab(index: Int): Tab? {
        if (index !in _tabs.indices) return null
        val removed = _tabs.removeAt(index)
        removed.thumbnail?.recycle()
        removed.thumbnail = null
        closedTabs.addLast(ClosedTab(removed.url, removed.title))
        while (closedTabs.size > 10) closedTabs.removeFirst()
        (removed.webView.parent as? android.view.ViewGroup)?.removeView(removed.webView)
        removed.webView.destroy()
        if (_tabs.isEmpty()) {
            currentIndex = -1
        } else if (index < currentIndex) {
            currentIndex--
        } else if (index == currentIndex) {
            currentIndex = minOf(index, _tabs.lastIndex)
        }
        notifyChanged()
        return removed
    }

    fun closeAll() {
        while (_tabs.isNotEmpty()) {
            _tabs.removeAt(0).let { removed ->
                removed.thumbnail?.recycle()
                removed.thumbnail = null
                removed.webView.let { wv ->
                    (wv.parent as? android.view.ViewGroup)?.removeView(wv)
                    wv.destroy()
                }
            }
        }
        currentIndex = -1
        notifyChanged()
    }

    /** 内存紧张时释放缩略图；默认保留当前标签，避免面板闪空。 */
    fun trimThumbnails(keepCurrent: Boolean = true) {
        _tabs.forEach { tab ->
            if (!keepCurrent || tab !== current) {
                tab.thumbnail?.recycle()
                tab.thumbnail = null
            }
        }
    }

    /** 最近关闭的标签（新的在前）。 */
    fun recentlyClosed(): List<ClosedTab> = closedTabs.reversed()

    /** 取出最近关闭的一条记录；恢复时由调用方新建标签重新加载。 */
    fun restoreLastClosed(): ClosedTab? = closedTabs.removeLastOrNull()

    fun removeClosed(tab: ClosedTab): Boolean = closedTabs.remove(tab)

    /** 关闭除当前标签外的其它标签，返回关闭数量。 */
    fun closeOthers(): Int {
        val current = current ?: return 0
        var count = 0
        for (index in _tabs.lastIndex downTo 0) {
            if (_tabs[index] !== current) {
                closeTab(index)
                count++
            }
        }
        return count
    }

    /** 拖动重排：把 from 位置的标签移到 to。 */
    fun move(from: Int, to: Int): Boolean {
        if (from !in _tabs.indices || to !in _tabs.indices || from == to) return false
        val current = current
        val item = _tabs.removeAt(from)
        _tabs.add(to, item)
        currentIndex = _tabs.indexOf(current)
        notifyChanged()
        return true
    }

    fun hasClosedTabs(): Boolean = closedTabs.isNotEmpty()

    private fun notifyChanged() {
        onChanged?.invoke()
    }
}

// SPDX-License-Identifier: LicenseRef-Sii-NC
// Copyright (c) 2026 wanwuwww
package com.sii.browser.core.webview

import android.content.Context
import android.util.AttributeSet
import android.view.ActionMode
import android.view.Menu
import android.view.MenuItem
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebView.HitTestResult

/**
 * 浏览器 WebView 容器。
 *
 * 职责：统一的基础设置（缓存/缩放/UA）、referer 记录、JS 值回调解析、
 * 页面文本提取/注入接口（约束 5）。文本选择菜单定制与手势由浏览器核心模块扩展。
 */
class SiiWebView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : WebView(context, attrs), PageContentExtractor {

    private var referer: String? = null
    private val registeredBridges = mutableSetOf<String>()

    /** 长按命中回调（链接/图片）；未命中时返回 false，保留 WebView 默认文本选择。 */
    var onLongPressHit: ((HitTestResult) -> Unit)? = null

    /** 下拉刷新：开关与回调（在页面顶部向下拉动超阀值时触发）。 */
    var pullRefreshEnabled: Boolean = false
    var onPullRefresh: (() -> Unit)? = null
    private var pullAccum = 0
    private var pullTriggered = false

    /** 最近一次按下位置（长按菜单据此跟随手指位置）。 */
    var lastTouchPoint: android.graphics.PointF? = null

    /** 当前 WebView 的下载桥随机密钥。 */
    var downloadSecret: String = ""

    /** 当前页面是否处于阅读模式（退出时刷新页面）。 */
    var readerMode: Boolean = false

    /** 最近一次应用的站点配置签名，用于配置变化/换站时恢复全局设置。 */
    var siteConfigSignature: String? = null
    /** 最近一次应用站点配置的 host，用于离开时清理站点级隐身数据。 */
    var siteConfigHost: String? = null

    /** 滚动回调（dy = 本次滚动位移；用于工具栏自动隐藏）。 */
    var onScrollDy: ((Int) -> Unit)? = null

    /** 双击网页回调（动作由浏览器按设置决定）。 */
    var onDoubleTap: (() -> Unit)? = null

    private val doubleTapDetector = android.view.GestureDetector(
        context,
        object : android.view.GestureDetector.SimpleOnGestureListener() {
            override fun onDoubleTap(e: android.view.MotionEvent): Boolean {
                val action = onDoubleTap ?: return false
                action()
                return true
            }
        }
    )

    override fun onScrollChanged(l: Int, t: Int, oldl: Int, oldt: Int) {
        super.onScrollChanged(l, t, oldl, oldt)
        onScrollDy?.invoke(t - oldt)
    }

    /** 文本选择菜单附加项（文案 + 回调，参数为选中文本）。 */
    var selectionMenuExtras: List<Pair<String, (String) -> Unit>> = emptyList()

    /** 在系统文本选择菜单后追加自定义项（保留原菜单行为）。 */
    override fun startActionMode(callback: ActionMode.Callback?, type: Int): ActionMode? {
        if (callback == null || selectionMenuExtras.isEmpty()) {
            return super.startActionMode(callback, type)
        }
        val extras = selectionMenuExtras
        var selected = ""
        val wrapped = object : ActionMode.Callback {
            override fun onCreateActionMode(mode: ActionMode?, menu: Menu?): Boolean {
                evaluateJavascript(
                    "(function(){try{return String(window.getSelection())}catch(e){return ''}})()"
                ) { value -> selected = value?.trim('"') ?: "" }
                return callback.onCreateActionMode(mode, menu)
            }

            override fun onPrepareActionMode(mode: ActionMode?, menu: Menu?): Boolean {
                val result = callback.onPrepareActionMode(mode, menu)
                menu?.let { m ->
                    extras.forEachIndexed { index, pair ->
                        m.add(Menu.NONE, SELECTION_EXTRA_BASE + index, 100 + index, pair.first)
                    }
                }
                return result
            }

            override fun onActionItemClicked(mode: ActionMode?, item: MenuItem?): Boolean {
                val extra = extras.getOrNull((item?.itemId ?: -1) - SELECTION_EXTRA_BASE)
                if (extra != null) {
                    extra.second(selected)
                    mode?.finish()
                    return true
                }
                return callback.onActionItemClicked(mode, item)
            }

            override fun onDestroyActionMode(mode: ActionMode?) = callback.onDestroyActionMode(mode)
        }
        return super.startActionMode(wrapped, type)
    }

    private companion object {
        const val SELECTION_EXTRA_BASE = 0x7F00
    }

    override fun onTouchEvent(event: android.view.MotionEvent): Boolean {
        if (event.actionMasked == android.view.MotionEvent.ACTION_DOWN) {
            lastTouchPoint = android.graphics.PointF(event.x, event.y)
        }
        doubleTapDetector.onTouchEvent(event)
        return super.onTouchEvent(event)
    }

    init {
        setOnLongClickListener {
            val result = hitTestResult
            when (result?.type) {
                HitTestResult.SRC_ANCHOR_TYPE,
                HitTestResult.SRC_IMAGE_ANCHOR_TYPE,
                HitTestResult.IMAGE_TYPE -> {
                    onLongPressHit?.invoke(result)
                    true
                }
                else -> false
            }
        }
    }

    /** 应用统一基础设置（构造时调用一次）。 */
    fun applyBaseSettings(settings: WebSettings) {        settings.javaScriptEnabled = true
        settings.javaScriptCanOpenWindowsAutomatically = false
        settings.cacheMode = WebSettings.LOAD_DEFAULT
        settings.loadWithOverviewMode = false
        settings.domStorageEnabled = true
        // Android 11+ 对 targetSdk R+ 默认禁止 WebView 访问 file://，
        // 稍后读等离线存档需要显式打开。
        settings.allowFileAccess = true
        settings.allowContentAccess = true
        settings.setSupportMultipleWindows(true)
        settings.mediaPlaybackRequiresUserGesture = false
        settings.mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
        settings.textZoom = 100
    }

    /** 原生 WebView 暗色：API 33+ 用算法暗色，API 29–32 用 Force Dark。 */
    @Suppress("DEPRECATION")
    fun applyNativeDarkMode(enabled: Boolean) {
        runCatching {
            if (android.os.Build.VERSION.SDK_INT >= 33) {
                settings.setAlgorithmicDarkeningAllowed(enabled)
            } else if (android.os.Build.VERSION.SDK_INT >= 29) {
                settings.forceDark =
                    if (enabled) WebSettings.FORCE_DARK_ON else WebSettings.FORCE_DARK_OFF
            }
        }
    }

    /** 下拉刷新：在页面顶部继续下拉超过阀值时触发一次。 */
    override fun overScrollBy(
        deltaX: Int, deltaY: Int, scrollX: Int, scrollY: Int,
        scrollRangeX: Int, scrollRangeY: Int,
        maxOverScrollX: Int, maxOverScrollY: Int, isTouchEvent: Boolean
    ): Boolean {
        if (isTouchEvent && pullRefreshEnabled) {
            if (scrollY <= 0 && deltaY < 0) {
                pullAccum += -deltaY
                val threshold = (70 * resources.displayMetrics.density).toInt()
                if (pullAccum > threshold && !pullTriggered) {
                    pullTriggered = true
                    onPullRefresh?.invoke()
                }
            } else if (deltaY > 0) {
                pullAccum = 0
                pullTriggered = false
            }
        }
        return super.overScrollBy(
            deltaX, deltaY, scrollX, scrollY,
            scrollRangeX, scrollRangeY,
            maxOverScrollX, maxOverScrollY, isTouchEvent
        )
    }

    /** 页面开始加载时由桥接层调用，记录 referer。 */
    fun rememberReferer(url: String?) {
        if (url.isNullOrEmpty()) return
        referer = url
    }

    fun getReferer(): String? = referer

    // ---- 页内查找 ----

    /** 开始页内查找，回调（当前序号, 总数）。 */
    fun findInPage(keyword: String, onResult: (Int, Int) -> Unit) {
        setFindListener { activeMatch, total, _ -> onResult(activeMatch, total) }
        findAllAsync(keyword)
    }

    /** 查找下一个/上一个。 */
    fun findNextMatch(forward: Boolean) {
        super.findNext(forward)
    }

    /** 结束查找（清除高亮）。 */
    fun clearFind() {
        clearMatches()
        setFindListener(null)
    }

    /**
     * 注册页面 JS 桥（如 window.sii / window.sii_gm）。
     * 同名桥只注册一次，避免重复注入。
     */
    fun registerJsBridge(name: String, bridge: Any) {
        if (!registeredBridges.add(name)) return
        addJavascriptInterface(bridge, name)
    }

    // ---- PageContentExtractor ----

    override fun extractPageText(onResult: (String?) -> Unit) {
        val js = "(function(){try{" +
            "var sels=['#novelcontent','.novelcontent','#content','.content','article','main'];" +
            "for(var i=0;i<sels.length;i++){var e=document.querySelector(sels[i]);" +
            "if(e&&e.innerText&&e.innerText.trim())return e.innerText;}" +
            "var t=document.body?document.body.innerText:'';" +
            "if(!t||!t.trim())t=document.body?document.body.textContent:'';" +
            "if(!t||!t.trim())t=document.documentElement?document.documentElement.innerText:'';" +
            "return t||'';" +
            "}catch(e){return 'ERR:'+e}})()"
        evaluateJavascript(js) { raw ->
            onResult(raw?.trimQuotes())
        }
    }

    override fun injectJavaScript(script: String, onResult: ((String?) -> Unit)?) {
        evaluateJavascript(script) { value ->
            onResult?.invoke(value?.trimQuotes())
        }
    }

    private fun String.trimQuotes(): String? {
        val s = this
        if (s.length <= 2 || s.first() != '"') return null
        return s.substring(1, s.length - 1)
            .replace("\\n", "\n")
            .replace("\\r", "\r")
            .replace("\\t", "\t")
            .replace("\\\"", "\"")
            .replace("\\\\", "\\")
    }
}

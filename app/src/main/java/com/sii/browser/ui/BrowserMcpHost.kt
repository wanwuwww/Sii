package com.sii.browser.ui

import com.sii.browser.core.mcp.BookmarkHit
import com.sii.browser.core.mcp.BrowserState
import com.sii.browser.core.mcp.FindOutcome
import com.sii.browser.core.mcp.HistoryHit
import com.sii.browser.core.mcp.McpHost
import com.sii.browser.core.mcp.TabSummary
import com.sii.browser.core.webview.NetworkCapture
import com.sii.browser.core.webview.SiiWebView
import com.sii.browser.di.AppServices
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume
import kotlinx.coroutines.suspendCancellableCoroutine
import org.json.JSONObject

/**
 * MCP 工具到浏览器能力的适配层。
 * 页面读写统一走主线程；写操作在浏览器视图不存在时返回失败，避免访问已销毁控件。
 */
class BrowserMcpHost(
    private val tabManager: TabManager,
    private val onOpenUrl: (String) -> Unit,
    private val onNewTab: (String) -> Unit,
    private val onSwitchTab: (Int) -> Unit,
    private val onCloseTab: (Int) -> Unit,
    private val onNavigate: (String) -> Boolean,
    private val isUiReady: () -> Boolean
) : McpHost {

    private suspend fun <T> main(block: suspend () -> T): T =
        withContext(Dispatchers.Main.immediate) { block() }

    private fun tabAt(index: Int?): Tab? =
        if (index == null || index < 0) tabManager.current
        else tabManager.tabs.getOrNull(index)

    private suspend fun eval(webView: SiiWebView, script: String, timeoutMs: Long = 8000L): String? =
        withTimeoutOrNull(timeoutMs) {
            suspendCancellableCoroutine { cont ->
                var resumed = false
                webView.injectJavaScript(script) { value ->
                    if (!resumed) {
                        resumed = true
                        if (cont.isActive) cont.resume(value)
                    }
                }
            }
        }

    override suspend fun state(): BrowserState = main {
        val tab = tabManager.current
        val webView = tab?.webView
        BrowserState(
            url = tab?.url.orEmpty(),
            title = tab?.title.orEmpty(),
            homeVisible = AppServices.lastHomeVisible,
            incognito = AppServices.privacyManager.isIncognito,
            tabCount = tabManager.size,
            activeTab = tabManager.currentIndex,
            canGoBack = webView?.canGoBack() == true,
            canGoForward = webView?.canGoForward() == true
        )
    }

    override suspend fun tabs(): List<TabSummary> = main {
        tabManager.tabs.mapIndexed { index, tab ->
            TabSummary(
                index = index,
                id = tab.id,
                title = tab.title,
                url = tab.url,
                active = index == tabManager.currentIndex
            )
        }
    }

    override suspend fun pageText(tab: Int?, maxChars: Int): String = main {
        val target = tabAt(tab) ?: return@main ""
        val webView = target.webView
        val text = withTimeoutOrNull(8000L) {
            suspendCancellableCoroutine { cont ->
                var resumed = false
                webView.extractPageText { value ->
                    if (!resumed) {
                        resumed = true
                        if (cont.isActive) cont.resume(value.orEmpty())
                    }
                }
            }
        }.orEmpty()
        if (text.length <= maxChars) text else text.take(maxChars)
    }

    override suspend fun pageHtml(tab: Int?, maxChars: Int): String = main {
        val webView = tabAt(tab)?.webView ?: return@main ""
        val limit = maxChars.coerceAtMost(200_000)
        val raw = eval(
            webView,
            "(function(){try{var h=document.documentElement?document.documentElement.outerHTML:'';" +
                "return h.slice(0,$limit);}catch(e){return ''}})()"
        )
        raw.orEmpty()
    }

    override suspend fun selection(tab: Int?): String = main {
        val webView = tabAt(tab)?.webView ?: return@main ""
        eval(
            webView,
            "(function(){try{return String(window.getSelection()||'');}catch(e){return ''}})()"
        ).orEmpty()
    }

    override suspend fun links(tab: Int?, limit: Int): String = main {
        val webView = tabAt(tab)?.webView ?: return@main "[]"
        val cap = limit.coerceIn(1, 500)
        eval(
            webView,
            "(function(){try{var out=[],as=document.querySelectorAll('a[href]');" +
                "for(var i=0;i<as.length&&out.length<$cap;i++){var h=as[i].href||'';" +
                "if(h.indexOf('http')!==0)continue;" +
                "out.push({url:h,txt:String(as[i].innerText||'').slice(0,200)});}" +
                "return JSON.stringify(out);}catch(e){return '[]'}})()"
        ) ?: "[]"
    }

    override suspend fun findInPage(tab: Int?, query: String): FindOutcome = main {
        val webView = tabAt(tab)?.webView ?: return@main FindOutcome(0, 0)
        withTimeoutOrNull(5000L) {
            suspendCancellableCoroutine { cont ->
                var resumed = false
                webView.findInPage(query) { current, total ->
                    if (!resumed) {
                        resumed = true
                        if (cont.isActive) cont.resume(FindOutcome(current, total))
                    }
                }
            }
        } ?: FindOutcome(0, 0)
    }

    override suspend fun openUrl(url: String): Boolean = main {
        if (!isUiReady()) return@main false
        onOpenUrl(url)
        true
    }

    override suspend fun newTab(url: String): Boolean = main {
        if (!isUiReady()) return@main false
        onNewTab(url)
        true
    }

    override suspend fun switchTab(index: Int): Boolean = main {
        if (!isUiReady()) return@main false
        if (index !in 0 until tabManager.size) return@main false
        onSwitchTab(index)
        true
    }

    override suspend fun closeTab(index: Int): Boolean = main {
        if (!isUiReady()) return@main false
        if (index !in 0 until tabManager.size) return@main false
        onCloseTab(index)
        true
    }

    override suspend fun navigate(action: String): Boolean = main {
        if (!isUiReady()) return@main false
        onNavigate(action)
    }

    override suspend fun click(tab: Int?, selector: String): String = main {
        val webView = tabAt(tab)?.webView ?: return@main "NO_TAB"
        val quoted = JSONObject.quote(selector)
        eval(
            webView,
            "(function(){try{var e=document.querySelector($quoted);if(!e)return 'NOT_FOUND';" +
                "e.scrollIntoView({block:'center'});e.click();return 'OK';}catch(e){return 'ERR:'+e}})()"
        ).orEmpty().ifBlank { "NO_RESULT" }
    }

    override suspend fun fill(tab: Int?, selector: String, text: String): String = main {
        val webView = tabAt(tab)?.webView ?: return@main "NO_TAB"
        val quotedSelector = JSONObject.quote(selector)
        val quotedText = JSONObject.quote(text)
        eval(
            webView,
            "(function(){try{var e=document.querySelector($quotedSelector);if(!e)return 'NOT_FOUND';" +
                "var p=e.tagName==='TEXTAREA'?HTMLTextAreaElement.prototype:HTMLInputElement.prototype;" +
                "var d=Object.getOwnPropertyDescriptor(p,'value');" +
                "if(d&&d.set){d.set.call(e,$quotedText);}else{e.value=$quotedText;}" +
                "e.dispatchEvent(new Event('input',{bubbles:true}));" +
                "e.dispatchEvent(new Event('change',{bubbles:true}));" +
                "e.focus();return 'OK';}catch(e){return 'ERR:'+e}})()"
        ).orEmpty().ifBlank { "NO_RESULT" }
    }

    override suspend fun scrollTo(
        tab: Int?,
        selector: String?,
        x: Int?,
        y: Int?,
        position: String?
    ): String = main {
        val webView = tabAt(tab)?.webView ?: return@main "NO_TAB"
        val quotedSelector = JSONObject.quote(selector.orEmpty())
        val quotedPosition = JSONObject.quote(position.orEmpty())
        val safeX = (x ?: 0).coerceIn(0, 2_000_000)
        val safeY = (y ?: 0).coerceIn(0, 2_000_000)
        eval(
            webView,
            "(function(){try{var sel=$quotedSelector,pos=$quotedPosition;" +
                "if(sel){var e=document.querySelector(sel);if(!e)return 'NOT_FOUND';" +
                "e.scrollIntoView({block:'center'});return 'OK';}" +
                "if(pos==='top'){window.scrollTo(0,0);return 'OK';}" +
                "if(pos==='bottom'){window.scrollTo(0,document.body?document.body.scrollHeight:0);return 'OK';}" +
                "window.scrollTo($safeX,$safeY);return 'OK';}catch(e){return 'ERR:'+e}})()"
        ).orEmpty().ifBlank { "NO_RESULT" }
    }

    override suspend fun evaluateJavascript(tab: Int?, code: String): String? = main {
        val webView = tabAt(tab)?.webView ?: return@main null
        eval(webView, code)
    }

    override suspend fun captureList(
        tab: Int?,
        limit: Int,
        kind: String?,
        onlyBlocked: Boolean
    ): List<NetworkCapture.Entry> = main {
        val webView = tabAt(tab)?.webView ?: return@main emptyList()
        NetworkCapture.list(webView, limit, kind, onlyBlocked)
    }

    override suspend fun captureGet(tab: Int?, id: Long): NetworkCapture.Entry? = main {
        val webView = tabAt(tab)?.webView ?: return@main null
        NetworkCapture.get(webView, id)
    }

    override suspend fun captureClear(tab: Int?): Boolean = main {
        val webView = tabAt(tab)?.webView ?: return@main false
        NetworkCapture.clear(webView)
        true
    }

    override suspend fun searchBookmarks(query: String, limit: Int): List<BookmarkHit> =
        withContext(Dispatchers.IO) {
            val needle = query.trim()
            if (needle.isEmpty()) return@withContext emptyList()
            val folders = runCatching {
                AppServices.bookmarkRepository.allFoldersOnce().associate { it.id to it.title }
            }.getOrDefault(emptyMap())
            runCatching {
                AppServices.bookmarkRepository.allItemsOnce()
                    .asSequence()
                    .filter {
                        it.title.contains(needle, ignoreCase = true) ||
                            it.url.contains(needle, ignoreCase = true)
                    }
                    .take(limit.coerceIn(1, 100))
                    .map { BookmarkHit(it.title, it.url, it.folderId?.let(folders::get)) }
                    .toList()
            }.getOrDefault(emptyList())
        }

    override suspend fun searchHistory(query: String, limit: Int): List<HistoryHit> =
        withContext(Dispatchers.IO) {
            val needle = query.trim()
            if (needle.isEmpty()) return@withContext emptyList()
            runCatching {
                AppServices.historyRepository.search(needle, limit.coerceIn(1, 100))
                    .map { HistoryHit(it.title, it.url, it.updatedAt) }
            }.getOrDefault(emptyList())
        }

    override suspend fun addBookmark(url: String, title: String): Boolean =
        withContext(Dispatchers.IO) {
            if (url.isBlank()) return@withContext false
            runCatching {
                val existing = AppServices.bookmarkRepository.findItemByUrl(url)
                if (existing != null) {
                    if (title.isNotBlank() && title != existing.title) {
                        AppServices.bookmarkRepository.updateTitle(existing, title)
                    }
                    true
                } else {
                    AppServices.bookmarkRepository.addItem(
                        url = url,
                        title = title.ifBlank { url },
                        folderId = null
                    )
                    true
                }
            }.getOrDefault(false)
        }

    override suspend fun deleteBookmark(url: String): Boolean =
        withContext(Dispatchers.IO) {
            runCatching {
                val existing = AppServices.bookmarkRepository.findItemByUrl(url)
                    ?: return@withContext false
                AppServices.bookmarkRepository.deleteItem(existing.id)
                true
            }.getOrDefault(false)
        }

    override suspend fun deleteHistory(url: String): Boolean =
        withContext(Dispatchers.IO) {
            runCatching {
                val existing = AppServices.historyRepository.findByUrl(url)
                    ?: return@withContext false
                AppServices.historyRepository.delete(existing.id)
                true
            }.getOrDefault(false)
        }

    override suspend fun incognito(): Boolean =
        AppServices.privacyManager.isIncognito

    override suspend fun uiReady(): Boolean = isUiReady()
}

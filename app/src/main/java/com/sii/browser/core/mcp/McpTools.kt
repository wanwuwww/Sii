// SPDX-License-Identifier: LicenseRef-Sii-NC
// Copyright (c) 2026 wanwuwww
package com.sii.browser.core.mcp

import com.sii.browser.data.Settings
import org.json.JSONArray
import org.json.JSONObject

/** 工具权限分组，对应设置里的独立开关。 */
enum class McpGroup {
    READ,
    TABS,
    PAGE_ACTION,
    JS,
    BOOKMARK_WRITE,
    HISTORY_WRITE,
    CAPTURE,
    DIAG
}

data class ToolResult(
    val text: String,
    val isError: Boolean = false,
    val imagePng: ByteArray? = null
)

/**
 * MCP 工具表。工具是否暴露由设置里的分组开关决定；
 * 无痕模式在未显式授权时拒绝所有工具。
 */
class McpTools(
    private val settings: Settings,
    private val hostProvider: () -> McpHost?
) {

    private data class ToolSpec(
        val name: String,
        val title: String,
        val description: String,
        val group: McpGroup,
        val schema: JSONObject,
        val run: suspend (McpHost, JSONObject) -> ToolResult
    )

    private val tools: List<ToolSpec> = buildList {
        add(
            ToolSpec(
                "sii_get_browser_state",
                "Browser state",
                "Current tab URL/title, home visibility, incognito flag, tab count, back/forward availability.",
                McpGroup.READ,
                schema(JSONObject())
            ) { host, _ ->
                val state = host.state()
                ToolResult(
                    JSONObject()
                        .put("url", state.url)
                        .put("title", state.title)
                        .put("home_visible", state.homeVisible)
                        .put("incognito", state.incognito)
                        .put("tab_count", state.tabCount)
                        .put("active_tab", state.activeTab)
                        .put("can_go_back", state.canGoBack)
                        .put("can_go_forward", state.canGoForward)
                        .toString()
                )
            }
        )
        add(
            ToolSpec(
                "sii_list_tabs",
                "List tabs",
                "List open tabs in panel order with index, title, URL and active flag.",
                McpGroup.READ,
                schema(JSONObject())
            ) { host, _ ->
                val tabs = JSONArray()
                host.tabs().forEach { tab ->
                    tabs.put(
                        JSONObject()
                            .put("index", tab.index)
                            .put("id", tab.id)
                            .put("title", tab.title)
                            .put("url", tab.url)
                            .put("active", tab.active)
                    )
                }
                ToolResult(tabs.toString())
            }
        )
        add(
            ToolSpec(
                "sii_get_ui_state",
                "UI state",
                "Native UI and navigation state: address bar text/focus, home overlay, tab and WebView URL, UA, site config host, loading.",
                McpGroup.DIAG,
                schema(JSONObject())
            ) { host, _ ->
                ToolResult(host.uiState())
            }
        )
        add(
            ToolSpec(
                "sii_get_logs",
                "Navigation logs",
                "Recent navigation and UI events as a JSON array (oldest first).",
                McpGroup.DIAG,
                schema(
                    JSONObject().put("limit", prop("integer", "Max entries, 1-300; default 100.")),
                )
            ) { host, args ->
                val limit = args.optInt("limit", 100).coerceIn(1, 300)
                ToolResult(host.logs(limit))
            }
        )
        add(
            ToolSpec(
                "sii_get_page_text",                "Read page text",
                "Extract visible text of the page (default 20000 chars, max 50000).",
                McpGroup.READ,
                schema(
                    JSONObject()
                        .put("tab", prop("integer", "Tab index; omit for current tab."))
                        .put("max_chars", prop("integer", "Max characters, 100-50000.")),
                )
            ) { host, args ->
                val max = args.optInt("max_chars", 20000).coerceIn(100, 50000)
                val text = host.pageText(tab(args), max)
                if (text.isBlank()) ToolResult("(empty page or no tab)") else ToolResult(text)
            }
        )
        add(
            ToolSpec(
                "sii_get_page_html",
                "Read page HTML",
                "Return document.documentElement.outerHTML (default 20000 chars, max 200000).",
                McpGroup.READ,
                schema(
                    JSONObject()
                        .put("tab", prop("integer", "Tab index; omit for current tab."))
                        .put("max_chars", prop("integer", "Max characters, 100-200000.")),
                )
            ) { host, args ->
                val max = args.optInt("max_chars", 20000).coerceIn(100, 200000)
                ToolResult(host.pageHtml(tab(args), max))
            }
        )
        add(
            ToolSpec(
                "sii_get_selection",
                "Read selection",
                "Return the currently selected text on the page.",
                McpGroup.READ,
                schema(
                    JSONObject().put("tab", prop("integer", "Tab index; omit for current tab."))
                )
            ) { host, args ->
                ToolResult(host.selection(tab(args)))
            }
        )
        add(
            ToolSpec(
                "sii_get_page_links",
                "List page links",
                "Return HTTP(S) links on the page as JSON array of {url, txt}.",
                McpGroup.READ,
                schema(
                    JSONObject()
                        .put("tab", prop("integer", "Tab index; omit for current tab."))
                        .put("limit", prop("integer", "Max links, 1-500.")),
                )
            ) { host, args ->
                val limit = args.optInt("limit", 100).coerceIn(1, 500)
                ToolResult(host.links(tab(args), limit))
            }
        )
        add(
            ToolSpec(
                "sii_find_in_page",
                "Find in page",
                "Highlight matches for a keyword and return {current, total}.",
                McpGroup.READ,
                schema(
                    JSONObject()
                        .put("query", prop("string", "Keyword to find."))
                        .put("tab", prop("integer", "Tab index; omit for current tab.")),
                    listOf("query")
                )
            ) { host, args ->
                val query = args.optString("query").take(200)
                if (query.isBlank()) {
                    ToolResult("missing query", true)
                } else {
                    val outcome = host.findInPage(tab(args), query)
                    ToolResult(
                        JSONObject()
                            .put("current", outcome.current)
                            .put("total", outcome.total)
                            .toString()
                    )
                }
            }
        )
        add(
            ToolSpec(
                "sii_search_bookmarks",
                "Search bookmarks",
                "Search bookmarks by title or URL. Returns JSON array of {title, url, folder}.",
                McpGroup.READ,
                schema(
                    JSONObject()
                        .put("query", prop("string", "Title or URL fragment."))
                        .put("limit", prop("integer", "Max results, 1-100.")),
                    listOf("query")
                )
            ) { host, args ->
                val query = args.optString("query").trim().take(200)
                if (query.isEmpty()) {
                    ToolResult("missing query", true)
                } else {
                    val limit = args.optInt("limit", 20).coerceIn(1, 100)
                    val array = JSONArray()
                    host.searchBookmarks(query, limit).forEach { hit ->
                        array.put(
                            JSONObject()
                                .put("title", hit.title)
                                .put("url", hit.url)
                                .put("folder", hit.folder ?: "")
                        )
                    }
                    ToolResult(array.toString())
                }
            }
        )
        add(
            ToolSpec(
                "sii_search_history",
                "Search history",
                "Search browsing history by title or URL. Returns JSON array of {title, url, time}.",
                McpGroup.READ,
                schema(
                    JSONObject()
                        .put("query", prop("string", "Title or URL fragment."))
                        .put("limit", prop("integer", "Max results, 1-100.")),
                    listOf("query")
                )
            ) { host, args ->
                val query = args.optString("query").trim().take(200)
                if (query.isEmpty()) {
                    ToolResult("missing query", true)
                } else {
                    val limit = args.optInt("limit", 20).coerceIn(1, 100)
                    val array = JSONArray()
                    host.searchHistory(query, limit).forEach { hit ->
                        array.put(
                            JSONObject()
                                .put("title", hit.title)
                                .put("url", hit.url)
                                .put("time", hit.time)
                        )
                    }
                    ToolResult(array.toString())
                }
            }
        )
        add(
            ToolSpec(
                "sii_open_url",
                "Open URL",
                "Navigate the current tab to an HTTP(S) URL.",
                McpGroup.TABS,
                schema(JSONObject().put("url", prop("string", "Absolute http(s) URL.")), listOf("url"))
            ) { host, args ->
                val url = normalizeUrl(args.optString("url"))
                    ?: return@ToolSpec ToolResult("invalid url", true)
                okOrNot(host.openUrl(url))
            }
        )
        add(
            ToolSpec(
                "sii_new_tab",
                "New tab",
                "Open a new tab, optionally with an HTTP(S) URL.",
                McpGroup.TABS,
                schema(JSONObject().put("url", prop("string", "Optional absolute http(s) URL.")))
            ) { host, args ->
                val raw = args.optString("url").trim()
                val url = if (raw.isEmpty()) "about:home" else normalizeUrl(raw)
                if (url == null) return@ToolSpec ToolResult("invalid url", true)
                okOrNot(host.newTab(url))
            }
        )
        add(
            ToolSpec(
                "sii_switch_tab",
                "Switch tab",
                "Switch to a tab by index from sii_list_tabs.",
                McpGroup.TABS,
                schema(JSONObject().put("index", prop("integer", "Tab index.")), listOf("index"))
            ) { host, args ->
                val index = args.optInt("index", -1)
                okOrNot(host.switchTab(index))
            }
        )
        add(
            ToolSpec(
                "sii_close_tab",
                "Close tab",
                "Close a tab by index; omitted index closes the current tab.",
                McpGroup.TABS,
                schema(JSONObject().put("index", prop("integer", "Tab index; omit for current.")))
            ) { host, args ->
                val index = args.optInt("index", -1)
                val target = if (index < 0) host.state().activeTab else index
                okOrNot(host.closeTab(target))
            }
        )
        add(
            ToolSpec(
                "sii_navigate",
                "Navigate",
                "Go back, go forward, or reload the current tab.",
                McpGroup.TABS,
                schema(
                    JSONObject().put("action", prop("string", "back | forward | reload")),
                    listOf("action")
                )
            ) { host, args ->
                val action = args.optString("action").lowercase()
                if (action !in setOf("back", "forward", "reload")) {
                    ToolResult("invalid action", true)
                } else {
                    okOrNot(host.navigate(action))
                }
            }
        )
        add(
            ToolSpec(
                "sii_click",
                "Click element",
                "Click the first element matching a CSS selector.",
                McpGroup.PAGE_ACTION,
                schema(
                    JSONObject()
                        .put("selector", prop("string", "CSS selector."))
                        .put("tab", prop("integer", "Tab index; omit for current tab.")),
                    listOf("selector")
                )
            ) { host, args ->
                val selector = args.optString("selector").take(1024)
                if (selector.isBlank()) ToolResult("missing selector", true)
                else ToolResult(host.click(tab(args), selector))
            }
        )
        add(
            ToolSpec(
                "sii_fill",
                "Fill input",
                "Set the value of an input/textarea matched by CSS selector and fire input/change events.",
                McpGroup.PAGE_ACTION,
                schema(
                    JSONObject()
                        .put("selector", prop("string", "CSS selector."))
                        .put("text", prop("string", "Text to fill (max 20000 chars)."))
                        .put("tab", prop("integer", "Tab index; omit for current tab.")),
                    listOf("selector", "text")
                )
            ) { host, args ->
                val selector = args.optString("selector").take(1024)
                val text = args.optString("text").take(20000)
                if (selector.isBlank()) ToolResult("missing selector", true)
                else ToolResult(host.fill(tab(args), selector, text))
            }
        )
        add(
            ToolSpec(
                "sii_scroll_to",
                "Scroll",
                "Scroll to a CSS-selected element, absolute coordinates, top or bottom.",
                McpGroup.PAGE_ACTION,
                schema(
                    JSONObject()
                        .put("selector", prop("string", "Optional CSS selector."))
                        .put("x", prop("integer", "Scroll X in CSS pixels."))
                        .put("y", prop("integer", "Scroll Y in CSS pixels."))
                        .put("position", prop("string", "top | bottom"))
                        .put("tab", prop("integer", "Tab index; omit for current tab."))
                )
            ) { host, args ->
                ToolResult(
                    host.scrollTo(
                        tab(args),
                        args.optString("selector").takeIf { it.isNotBlank() },
                        if (args.has("x")) args.optInt("x") else null,
                        if (args.has("y")) args.optInt("y") else null,
                        args.optString("position").takeIf { it.isNotBlank() }
                    )
                )
            }
        )
        add(
            ToolSpec(
                "sii_execute_javascript",
                "Execute JavaScript",
                "Run JavaScript in the page and return the last expression value (max 50000 chars).",
                McpGroup.JS,
                schema(
                    JSONObject()
                        .put("code", prop("string", "JavaScript source (max 100000 chars)."))
                        .put("tab", prop("integer", "Tab index; omit for current tab.")),
                    listOf("code")
                )
            ) { host, args ->
                val code = args.optString("code").take(100000)
                if (code.isBlank()) {
                    ToolResult("missing code", true)
                } else {
                    val result = host.evaluateJavascript(tab(args), code)
                    ToolResult((result ?: "(no return value)").take(50000))
                }
            }
        )
        add(
            ToolSpec(
                "sii_add_bookmark",
                "Add bookmark",
                "Add or update a bookmark. Defaults to the current page when url is omitted.",
                McpGroup.BOOKMARK_WRITE,
                schema(
                    JSONObject()
                        .put("url", prop("string", "Optional absolute http(s) URL."))
                        .put("title", prop("string", "Optional title."))
                )
            ) { host, args ->
                val state = host.state()
                val url = args.optString("url").trim()
                    .ifEmpty { state.url }
                    .let { normalizeUrl(it) }
                    ?: return@ToolSpec ToolResult("invalid url", true)
                val title = args.optString("title").trim().take(500)
                    .ifEmpty { state.title }
                okOrNot(host.addBookmark(url, title))
            }
        )
        add(
            ToolSpec(
                "sii_delete_bookmark",
                "Delete bookmark",
                "Delete a bookmark by exact URL. Omit url to delete the current page bookmark.",
                McpGroup.BOOKMARK_WRITE,
                schema(JSONObject().put("url", prop("string", "Optional absolute http(s) URL.")))
            ) { host, args ->
                val state = host.state()
                val url = args.optString("url").trim().ifEmpty { state.url }
                if (url.isBlank()) ToolResult("invalid url", true)
                else okOrNot(host.deleteBookmark(url))
            }
        )
        add(
            ToolSpec(
                "sii_delete_history",
                "Delete history",
                "Delete history entries matching the exact URL. Omit url to delete the current page entry.",
                McpGroup.HISTORY_WRITE,
                schema(JSONObject().put("url", prop("string", "Optional absolute http(s) URL.")))
            ) { host, args ->
                val state = host.state()
                val url = args.optString("url").trim().ifEmpty { state.url }
                if (url.isBlank()) ToolResult("invalid url", true)
                else okOrNot(host.deleteHistory(url))
            }
        )
        add(
            ToolSpec(
                "sii_list_requests",
                "List captured requests",
                "List captured network requests for a tab (newest first). " +
                    "kind: all | xhr | fetch | document | image | script | css | media | font | resource | blocked.",
                McpGroup.CAPTURE,
                schema(
                    JSONObject()
                        .put("tab", prop("integer", "Tab index; omit for current tab."))
                        .put("limit", prop("integer", "Max entries, 1-500."))
                        .put("kind", prop("string", "Filter by request kind."))
                        .put("only_blocked", prop("boolean", "Only rules-blocked requests."))
                )
            ) { host, args ->
                val limit = args.optInt("limit", 100).coerceIn(1, 500)
                val kind = args.optString("kind").takeIf { it.isNotBlank() }
                val onlyBlocked = args.optBoolean("only_blocked", false)
                val array = JSONArray()
                host.captureList(tab(args), limit, kind, onlyBlocked).forEach { entry ->
                    array.put(
                        JSONObject()
                            .put("id", entry.id)
                            .put("time", entry.time)
                            .put("method", entry.method)
                            .put("url", entry.url)
                            .put("kind", entry.kind)
                            .put("status", entry.status)
                            .put("duration_ms", entry.durationMs)
                            .put("mime", entry.mime ?: "")
                            .put("blocked", entry.blocked)
                            .put("main_frame", entry.mainFrame)
                            .put("request_body", entry.requestBody ?: "")
                            .put("has_response_body", entry.responseBody != null)
                    )
                }
                ToolResult(array.toString())
            }
        )
        add(
            ToolSpec(
                "sii_get_request",
                "Captured request detail",
                "Return full detail (including captured bodies) of one request by id from sii_list_requests.",
                McpGroup.CAPTURE,
                schema(
                    JSONObject()
                        .put("id", prop("integer", "Request id."))
                        .put("tab", prop("integer", "Tab index; omit for current tab.")),
                    listOf("id")
                )
            ) { host, args ->
                val id = args.optLong("id", -1L)
                val entry = if (id < 0) null else host.captureGet(tab(args), id)
                if (entry == null) {
                    ToolResult("request not found", true)
                } else {
                    ToolResult(
                        JSONObject()
                            .put("id", entry.id)
                            .put("time", entry.time)
                            .put("method", entry.method)
                            .put("url", entry.url)
                            .put("kind", entry.kind)
                            .put("status", entry.status)
                            .put("duration_ms", entry.durationMs)
                            .put("mime", entry.mime ?: "")
                            .put("blocked", entry.blocked)
                            .put("main_frame", entry.mainFrame)
                            .put("request_body", entry.requestBody ?: "")
                            .put("response_body", entry.responseBody ?: "")
                            .put("truncated", entry.truncated)
                            .toString()
                    )
                }
            }
        )
        add(
            ToolSpec(
                "sii_clear_requests",
                "Clear captured requests",
                "Clear captured network requests of a tab.",
                McpGroup.CAPTURE,
                schema(
                    JSONObject().put("tab", prop("integer", "Tab index; omit for current tab."))
                )
            ) { host, args ->
                okOrNot(host.captureClear(tab(args)))
            }
        )
        add(
            ToolSpec(
                "sii_wait_for",
                "Wait for element/text",
                "Poll until a CSS selector or text appears (or disappears). Useful after clicks on SPA pages.",
                McpGroup.PAGE_ACTION,
                schema(
                    JSONObject()
                        .put("tab", prop("integer", "Tab index; omit for current tab."))
                        .put("selector", prop("string", "CSS selector to watch."))
                        .put("text", prop("string", "Text to watch when selector is absent."))
                        .put("state", prop("string", "present (default) | absent"))
                        .put("timeout_ms", prop("integer", "Default 8000, max 15000.")),
                    listOf()
                )
            ) { host, args ->
                val selector = args.optString("selector")
                val text = args.optString("text")
                if (selector.isBlank() && text.isBlank()) {
                    return@ToolSpec ToolResult("selector or text required", true)
                }
                val want = args.optString("state", "present").lowercase() != "absent"
                val timeout = args.optInt("timeout_ms", 8000).coerceIn(500, 15000)
                val script = if (selector.isNotBlank()) {
                    "(function(){try{return document.querySelector(" + JSONObject.quote(selector) +
                        ")?'1':'0';}catch(e){return '0'}})()"
                } else {
                    "(function(){try{return document.body&&document.body.innerText.indexOf(" +
                        JSONObject.quote(text) + ")>=0?'1':'0';}catch(e){return '0'}})()"
                }
                val deadline = System.currentTimeMillis() + timeout
                while (System.currentTimeMillis() < deadline) {
                    val value = host.evaluateJavascript(tab(args), script)
                        ?.trim()?.trim('"')
                    val present = value == "1"
                    if (present == want) return@ToolSpec ToolResult("ok")
                    kotlinx.coroutines.delay(150)
                }
                ToolResult("timeout after " + timeout + "ms", true)
            }
        )
        add(
            ToolSpec(
                "sii_get_dom_outline",
                "DOM outline",
                "List visible interactive elements (links, buttons, inputs) with text and position.",
                McpGroup.READ,
                schema(
                    JSONObject()
                        .put("tab", prop("integer", "Tab index; omit for current tab."))
                        .put("limit", prop("integer", "Default 60, max 200."))
                )
            ) { host, args ->
                val limit = args.optInt("limit", 60).coerceIn(1, 200)
                val script = "(function(){try{var out=[];var els=document.querySelectorAll('a,button,input,select,textarea,[role=button],[onclick]');" +
                    "for(var i=0;i<els.length&&out.length<" + limit + ";i++){var e=els[i];var r=e.getBoundingClientRect();" +
                    "if(r.width<=0||r.height<=0)continue;var c=e.className;if(c&&c.baseVal!==undefined)c=c.baseVal;" +
                    "out.push({tag:e.tagName.toLowerCase(),id:e.id||'',cls:(c||'').toString().slice(0,80)," +
                    "text:(e.innerText||e.value||e.getAttribute('aria-label')||'').trim().slice(0,80)," +
                    "type:e.getAttribute('type')||'',href:(e.getAttribute('href')||'').slice(0,200)," +
                    "x:Math.round(r.left),y:Math.round(r.top),w:Math.round(r.width),h:Math.round(r.height)});}" +
                    "return JSON.stringify({count:out.length,items:out});}catch(e){return 'ERR:'+e}})()"
                ToolResult(host.evaluateJavascript(tab(args), script) ?: "NO_RESULT")
            }
        )
        add(
            ToolSpec(
                "sii_element_info",
                "Element info",
                "Inspect one element by CSS selector or visible text: attributes, rect, visibility, key styles.",
                McpGroup.READ,
                schema(
                    JSONObject()
                        .put("tab", prop("integer", "Tab index; omit for current tab."))
                        .put("selector", prop("string", "CSS selector."))
                        .put("text", prop("string", "Visible text to locate when selector is absent.")),
                    listOf()
                )
            ) { host, args ->
                val selector = args.optString("selector")
                val text = args.optString("text")
                if (selector.isBlank() && text.isBlank()) {
                    return@ToolSpec ToolResult("selector or text required", true)
                }
                val script = "(function(){try{var sel=" + JSONObject.quote(selector) +
                    ";var txt=" + JSONObject.quote(text) +
                    ";var el=null;if(sel){el=document.querySelector(sel);}else{" +
                    "var all=document.querySelectorAll('*');var best=null;for(var i=0;i<all.length&&i<3000;i++){" +
                    "var e=all[i];var t=e.innerText;if(!t||t.trim().indexOf(txt)<0)continue;" +
                    "var deeper=false;for(var j=0;j<e.children.length;j++){var ct=e.children[j].innerText;" +
                    "if(ct&&ct.trim().indexOf(txt)>=0){deeper=true;break;}}" +
                    "if(!deeper){el=e;break;}if(!best)best=e;}" +
                    "if(!el)el=best;}" +
                    "if(!el)return 'NOT_FOUND';var r=el.getBoundingClientRect();var cs=getComputedStyle(el);" +
                    "var c=el.className;if(c&&c.baseVal!==undefined)c=c.baseVal;" +
                    "return JSON.stringify({tag:el.tagName.toLowerCase(),id:el.id||'',cls:(c||'').toString()," +
                    "text:(el.innerText||'').slice(0,200),href:(el.getAttribute('href')||'')," +
                    "src:(el.getAttribute('src')||''),type:(el.getAttribute('type')||'')," +
                    "name:(el.getAttribute('name')||''),value:(el.value||'').toString().slice(0,100)," +
                    "rect:{x:Math.round(r.left),y:Math.round(r.top),w:Math.round(r.width),h:Math.round(r.height)}," +
                    "visible:!!(r.width>0&&r.height>0&&cs.visibility!=='hidden'&&cs.display!=='none')," +
                    "style:{display:cs.display,position:cs.position,color:cs.color,fontSize:cs.fontSize,opacity:cs.opacity}});" +
                    "}catch(e){return 'ERR:'+e}})()"
                ToolResult(host.evaluateJavascript(tab(args), script) ?: "NO_RESULT")
            }
        )
        add(
            ToolSpec(
                "sii_press_key",
                "Press key",
                "Dispatch a keyboard event (Enter, Escape, Tab, arrows, single char) to the focused element or a selector.",
                McpGroup.PAGE_ACTION,
                schema(
                    JSONObject()
                        .put("tab", prop("integer", "Tab index; omit for current tab."))
                        .put("key", prop("string", "Key name, e.g. Enter / Escape / Tab / a"))
                        .put("selector", prop("string", "Optional target element.")),
                    listOf("key")
                )
            ) { host, args ->
                val key = args.optString("key")
                if (key.isBlank()) return@ToolSpec ToolResult("key required", true)
                val selector = args.optString("selector")
                val script = "(function(){try{var key=" + JSONObject.quote(key) +
                    ";var sel=" + JSONObject.quote(selector) +
                    ";var el=sel?document.querySelector(sel):document.activeElement;if(!el)return 'NOT_FOUND';" +
                    "var codes={Enter:13,Escape:27,Tab:9,Backspace:8,ArrowUp:38,ArrowDown:40,ArrowLeft:37,ArrowRight:39};" +
                    "var kc=codes[key]||(key.length===1?key.toUpperCase().charCodeAt(0):0);" +
                    "['keydown','keypress','keyup'].forEach(function(t){el.dispatchEvent(new KeyboardEvent(t," +
                    "{key:key,code:key,keyCode:kc,which:kc,bubbles:true,cancelable:true}));});return 'OK';}catch(e){return 'ERR:'+e}})()"
                ToolResult(host.evaluateJavascript(tab(args), script) ?: "NO_RESULT")
            }
        )
        add(
            ToolSpec(
                "sii_select_option",
                "Select option",
                "Choose an option in a native <select> by value or visible text.",
                McpGroup.PAGE_ACTION,
                schema(
                    JSONObject()
                        .put("tab", prop("integer", "Tab index; omit for current tab."))
                        .put("selector", prop("string", "CSS selector of the <select>."))
                        .put("value", prop("string", "Option value."))
                        .put("text", prop("string", "Option label when value is absent.")),
                    listOf("selector")
                )
            ) { host, args ->
                val selector = args.optString("selector")
                val value = args.optString("value")
                val text = args.optString("text")
                if (selector.isBlank()) return@ToolSpec ToolResult("selector required", true)
                val script = "(function(){try{var sel=document.querySelector(" + JSONObject.quote(selector) +
                    ");if(!sel)return 'NOT_FOUND';if(sel.tagName!=='SELECT')return 'NOT_SELECT';" +
                    "var v=" + JSONObject.quote(value) + ";var t=" + JSONObject.quote(text) +
                    ";var opt=null;if(v){opt=Array.prototype.slice.call(sel.options).filter(function(o){return o.value===v})[0]||null;}" +
                    "if(!opt&&t){opt=Array.prototype.slice.call(sel.options).filter(function(o){return (o.text||'').trim()===t})[0]||null;}" +
                    "if(!opt)return 'OPTION_NOT_FOUND';sel.value=opt.value;" +
                    "sel.dispatchEvent(new Event('input',{bubbles:true}));" +
                    "sel.dispatchEvent(new Event('change',{bubbles:true}));return 'OK:'+sel.value;}catch(e){return 'ERR:'+e}})()"
                ToolResult(host.evaluateJavascript(tab(args), script) ?: "NO_RESULT")
            }
        )
        add(
            ToolSpec(
                "sii_screenshot",
                "Screenshot",
                "Capture the current tab viewport as PNG.",
                McpGroup.READ,
                schema(JSONObject())
            ) { host, _ ->
                val png = host.screenshot()
                if (png == null) ToolResult("screenshot unavailable", true)
                else ToolResult("screenshot " + png.size + " bytes", imagePng = png)
            }
        )
        add(
            ToolSpec(
                "sii_highlight_element",
                "Highlight element",
                "Outline an element and scroll it into view (same logic as the in-app devtools).",
                McpGroup.PAGE_ACTION,
                schema(
                    JSONObject()
                        .put("tab", prop("integer", "Tab index; omit for current tab."))
                        .put("selector", prop("string", "CSS selector."))
                        .put("clear", prop("boolean", "true to remove the outline instead.")),
                    listOf("selector")
                )
            ) { host, args ->
                val selector = args.optString("selector")
                if (selector.isBlank()) return@ToolSpec ToolResult("selector required", true)
                val clear = args.optBoolean("clear", false)
                val script = if (clear) {
                    "(function(){try{var e=document.querySelector(" + JSONObject.quote(selector) +
                        ");if(!e)return 'NOT_FOUND';e.style.outline='';return 'OK';}catch(x){return 'ERR:'+x}})()"
                } else {
                    "(function(){try{var e=document.querySelector(" + JSONObject.quote(selector) +
                        ");if(!e)return 'NOT_FOUND';e.style.outline='2px solid #2F7CF6';" +
                        "e.style.outlineOffset='1px';e.scrollIntoView({block:'center'});return 'OK';" +
                        "}catch(x){return 'ERR:'+x}})()"
                }
                ToolResult(host.evaluateJavascript(tab(args), script) ?: "NO_RESULT")
            }
        )
        add(
            ToolSpec(
                "sii_edit_element",
                "Edit element",
                "Edit id / class / text and inline CSS of an element (same logic as the in-app devtools).",
                McpGroup.PAGE_ACTION,
                schema(
                    JSONObject()
                        .put("tab", prop("integer", "Tab index; omit for current tab."))
                        .put("selector", prop("string", "CSS selector."))
                        .put("id", prop("string", "New id (omit to keep)."))
                        .put("class_name", prop("string", "New class attribute (omit to keep)."))
                        .put("text", prop("string", "New text content (omit to keep)."))
                        .put("styles", prop("object", "Inline CSS properties, e.g. {\"color\":\"#f00\"}.")),
                    listOf("selector")
                )
            ) { host, args ->
                val selector = args.optString("selector")
                if (selector.isBlank()) return@ToolSpec ToolResult("selector required", true)
                val styles = args.optJSONObject("styles")?.toString() ?: "{}"
                val script = "(function(){try{var e=document.querySelector(" + JSONObject.quote(selector) +
                    ");if(!e)return 'NOT_FOUND';var changed=[];" +
                    (if (args.has("id")) "e.id=" + JSONObject.quote(args.optString("id")) + ";changed.push('id');" else "") +
                    (if (args.has("class_name")) "e.className=" + JSONObject.quote(args.optString("class_name")) + ";changed.push('class');" else "") +
                    (if (args.has("text")) "e.textContent=" + JSONObject.quote(args.optString("text")) + ";changed.push('text');" else "") +
                    "var st=" + styles + ";for(var k in st){e.style.setProperty(k,st[k]);changed.push(k);}" +
                    "return 'OK:'+changed.join(',');}catch(x){return 'ERR:'+x}})()"
                ToolResult(host.evaluateJavascript(tab(args), script) ?: "NO_RESULT")
            }
        )
        add(
            ToolSpec(
                "sii_open_devtools",
                "Open devtools",
                "Toggle the in-app developer tools panel.",
                McpGroup.PAGE_ACTION,
                schema(JSONObject())
            ) { host, _ ->
                okOrNot(host.openDevTools())
            }
        )
    }

    fun availableTools(): List<JSONObject> = tools
        .filter { isAllowed(it.group) }
        .map { spec ->
            JSONObject()
                .put("name", spec.name)
                .put("title", spec.title)
                .put("description", spec.description)
                .put("inputSchema", spec.schema)
        }

    fun enabledCount(): Int = tools.count { isAllowed(it.group) }

    fun totalCount(): Int = tools.size

    suspend fun call(name: String, arguments: JSONObject): ToolResult {
        val spec = tools.firstOrNull { it.name == name }
            ?: return ToolResult("unknown tool: $name", true)
        if (!isAllowed(spec.group)) {
            return ToolResult(
                "$name is disabled. Enable it in Settings > Advanced > MCP service.",
                true
            )
        }
        val host = hostProvider() ?: return ToolResult("browser is not ready", true)
        if (host.incognito() && !settings.mcpAllowIncognito()) {
            return ToolResult("browser is in incognito mode; MCP access is blocked", true)
        }
        return runCatching { spec.run(host, arguments) }
            .getOrElse { ToolResult("tool failed: ${it.message ?: it.javaClass.simpleName}", true) }
    }

    private fun isAllowed(group: McpGroup): Boolean = when (group) {
        McpGroup.READ -> settings.mcpAllowPageRead()
        McpGroup.TABS -> settings.mcpAllowTabs()
        McpGroup.PAGE_ACTION -> settings.mcpAllowPageAction()
        McpGroup.JS -> settings.mcpAllowJs()
        McpGroup.BOOKMARK_WRITE -> settings.mcpAllowBookmarkWrite()
        McpGroup.HISTORY_WRITE -> settings.mcpAllowHistoryWrite()
        McpGroup.CAPTURE -> settings.mcpAllowCapture()
        McpGroup.DIAG -> settings.mcpAllowDiag()
    }

    private fun tab(args: JSONObject): Int? =
        if (args.has("tab")) args.optInt("tab", -1) else null

    private fun normalizeUrl(raw: String): String? {
        val url = raw.trim()
        if (url.length > 8192) return null
        val scheme = runCatching { android.net.Uri.parse(url).scheme }.getOrNull()
        return if (scheme.equals("http", true) || scheme.equals("https", true)) url else null
    }

    private fun okOrNot(value: Boolean): ToolResult =
        ToolResult(if (value) "{\"ok\":true}" else "{\"ok\":false,\"reason\":\"browser UI not ready or tab missing\"}")

    private fun prop(type: String, description: String): JSONObject =
        JSONObject().put("type", type).put("description", description)

    private fun schema(properties: JSONObject, required: List<String> = emptyList()): JSONObject =
        JSONObject()
            .put("type", "object")
            .put("properties", properties)
            .put("required", JSONArray(required))
}

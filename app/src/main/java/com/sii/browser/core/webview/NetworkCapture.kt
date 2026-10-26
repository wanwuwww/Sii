package com.sii.browser.core.webview

import android.webkit.JavascriptInterface
import org.json.JSONObject
import java.util.WeakHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * 内存抓包：按 WebView 记录页面网络请求。
 *
 * 两个来源互补：
 * - 拦截层（[recordRequest]）能看到所有资源请求的方法与 URL，但拿不到状态和响应体；
 * - 页面 hook（[CaptureJsBridge]）补上 fetch/XHR 的状态、耗时与响应体。
 *
 * 数据只在内存保留，页面重新加载时清空；无痕模式由 MCP 层拒绝读取。
 */
object NetworkCapture {

    data class Entry(
        val id: Long,
        val time: Long,
        val method: String,
        val url: String,
        val kind: String,
        val status: Int,
        val durationMs: Long,
        val mime: String?,
        val requestBody: String?,
        val responseBody: String?,
        val truncated: Boolean,
        val blocked: Boolean,
        val mainFrame: Boolean
    )

    private const val MAX_ENTRIES = 300
    private const val MAX_URL = 8 * 1024
    private const val MAX_BODY = 64 * 1024
    private const val MAX_REQUEST_BODY = 16 * 1024

    private val store = WeakHashMap<Any, LinkedHashMap<Long, Entry>>()
    private val lock = Any()
    private val nextId = AtomicLong(1L)

    fun recordRequest(
        owner: Any,
        method: String?,
        url: String,
        mainFrame: Boolean,
        mime: String? = null
    ) {
        if (url.isBlank() || url.length > MAX_URL) return
        add(
            owner,
            Entry(
                id = nextId.getAndIncrement(),
                time = System.currentTimeMillis(),
                method = (method ?: "GET").uppercase(),
                url = url,
                kind = kindOf(url, mime, mainFrame),
                status = 0,
                durationMs = 0,
                mime = mime,
                requestBody = null,
                responseBody = null,
                truncated = false,
                blocked = false,
                mainFrame = mainFrame
            )
        )
    }

    /** 拦截层命中的请求（广告/隐私规则）。 */
    fun recordBlocked(owner: Any, url: String, method: String?, mainFrame: Boolean) {
        if (url.isBlank() || url.length > MAX_URL) return
        add(
            owner,
            Entry(
                id = nextId.getAndIncrement(),
                time = System.currentTimeMillis(),
                method = (method ?: "GET").uppercase(),
                url = url,
                kind = kindOf(url, null, mainFrame),
                status = 0,
                durationMs = 0,
                mime = null,
                requestBody = null,
                responseBody = null,
                truncated = false,
                blocked = true,
                mainFrame = mainFrame
            )
        )
    }

    /** 页面 hook 上报（fetch/XHR）。 */
    fun recordJs(owner: Any, json: String) {
        val obj = runCatching { JSONObject(json) }.getOrNull() ?: return
        val url = obj.optString("u")
        if (url.isBlank() || url.length > MAX_URL) return
        val response = obj.optString("b").takeIf { it.isNotEmpty() }?.let { trimBody(it) }
        add(
            owner,
            Entry(
                id = nextId.getAndIncrement(),
                time = System.currentTimeMillis(),
                method = obj.optString("m", "GET").uppercase().ifBlank { "GET" },
                url = url,
                kind = obj.optString("rt", "fetch").ifBlank { "fetch" },
                status = obj.optInt("s", 0),
                durationMs = obj.optLong("t", 0L).coerceAtLeast(0L),
                mime = obj.optString("ct").takeIf { it.isNotEmpty() },
                requestBody = obj.optString("rb").takeIf { it.isNotEmpty() }?.let {
                    if (it.length > MAX_REQUEST_BODY) it.take(MAX_REQUEST_BODY) else it
                },
                responseBody = response?.first,
                truncated = response?.second == true,
                blocked = false,
                mainFrame = false
            )
        )
    }

    private fun trimBody(text: String): Pair<String, Boolean> {
        if (text.length <= MAX_BODY) return text to false
        return text.take(MAX_BODY) to true
    }

    private fun add(owner: Any, entry: Entry) {
        synchronized(lock) {
            val map = store.getOrPut(owner) { LinkedHashMap() }
            map[entry.id] = entry
            while (map.size > MAX_ENTRIES) {
                val oldest = map.keys.firstOrNull() ?: break
                map.remove(oldest)
            }
        }
    }

    fun list(
        owner: Any,
        limit: Int = 100,
        kind: String? = null,
        onlyBlocked: Boolean = false
    ): List<Entry> {
        val snapshot = synchronized(lock) { store[owner]?.values?.toList().orEmpty() }
        val filter = kind?.lowercase()?.takeIf { it.isNotBlank() && it != "all" }
        return snapshot.asReversed().asSequence()
            .filter { !onlyBlocked || it.blocked }
            .filter { filter == null || matches(it, filter) }
            .take(limit.coerceIn(1, 500))
            .toList()
    }

    fun get(owner: Any, id: Long): Entry? = synchronized(lock) { store[owner]?.get(id) }

    fun clear(owner: Any) {
        synchronized(lock) { store.remove(owner) }
    }

    fun clearAll() {
        synchronized(lock) { store.clear() }
    }

    fun count(owner: Any): Int = synchronized(lock) { store[owner]?.size ?: 0 }

    private fun matches(entry: Entry, filter: String): Boolean = when (filter) {
        "xhr", "fetch" -> entry.kind == filter
        "document", "image", "script", "css", "media", "font" -> entry.kind == filter
        "blocked" -> entry.blocked
        "resource" -> entry.kind !in setOf("document", "xhr", "fetch")
        else -> entry.kind.contains(filter, ignoreCase = true)
    }

    private fun kindOf(url: String, mime: String?, mainFrame: Boolean): String {
        if (mainFrame) return "document"
        val path = url.substringBefore('?').substringBefore('#').lowercase()
        val ext = path.substringAfterLast('.', "")
        return when (ext) {
            "js", "mjs" -> "script"
            "css" -> "css"
            "png", "jpg", "jpeg", "gif", "webp", "svg", "ico", "bmp", "avif" -> "image"
            "mp4", "m3u8", "ts", "m4s", "webm", "mp3", "m4a", "aac", "ogg", "flac", "wav" -> "media"
            "woff", "woff2", "ttf", "otf", "eot" -> "font"
            else -> {
                val m = mime.orEmpty().lowercase()
                when {
                    m.startsWith("image/") -> "image"
                    m.startsWith("audio/") || m.startsWith("video/") -> "media"
                    m.contains("javascript") -> "script"
                    m.contains("css") -> "css"
                    m.contains("font") -> "font"
                    m.contains("json") || m.contains("xml") -> "xhr"
                    else -> "other"
                }
            }
        }
    }
}

/** 页面 hook → 原生抓包记录。 */
class CaptureJsBridge(private val webView: SiiWebView) {

    @JavascriptInterface
    fun record(json: String) {
        NetworkCapture.recordJs(webView, json)
    }
}

/** 页面侧 fetch/XHR hook；仅在抓包开启时注入。 */
object CaptureScript {

    fun js(captureBody: Boolean): String = "(function(){" +
        "if(window.__siiCapture)return;window.__siiCapture=true;" +
        "var MAX=" + (64 * 1024) + ";var BODY=" + captureBody + ";" +
        "function send(o){try{window.SiiCapture.record(JSON.stringify(o));}catch(e){}}" +
        "function cut(s){s=String(s);return s.length>MAX?s.slice(0,MAX):s;}" +
        "try{var of=window.fetch;" +
        "if(of){window.fetch=function(input,init){" +
        "var u=(typeof input==='string')?input:(input&&input.url)||'';" +
        "var m=(init&&init.method)||(input&&input.method)||'GET';" +
        "var rb=null;try{if(init&&typeof init.body==='string')rb=init.body;" +
        "else if(init&&init.body&&typeof init.body.toString==='function')rb=String(init.body);}catch(e){}" +
        "var st=Date.now();" +
        "return of.apply(this,arguments).then(function(r){" +
        "var rec={u:u,m:m,s:r.status,t:Date.now()-st,rt:'fetch',ct:r.headers?r.headers.get('content-type'):'',rb:rb};" +
        "if(BODY&&r.clone){try{r.clone().text().then(function(x){rec.b=cut(x);send(rec);})" +
        ".catch(function(){send(rec);});return r;}catch(e){}}" +
        "send(rec);return r;" +
        "}).catch(function(e){send({u:u,m:m,t:Date.now()-st,rt:'fetch',e:String(e)});throw e;});};}" +
        "}catch(e){}" +
        "try{var XO=XMLHttpRequest.prototype.open,XS=XMLHttpRequest.prototype.send;" +
        "XMLHttpRequest.prototype.open=function(m,u){this.__siiCap={m:m,u:u};return XO.apply(this,arguments);};" +
        "XMLHttpRequest.prototype.send=function(b){" +
        "var x=this,st=Date.now();var c=x.__siiCap||{};" +
        "var rec={u:c.u||'',m:(c.m||'GET'),rt:'xhr',rb:(typeof b==='string')?b:null};" +
        "x.addEventListener('loadend',function(){" +
        "rec.s=x.status;rec.t=Date.now()-st;" +
        "try{rec.ct=x.getResponseHeader('content-type')||'';}catch(e){}" +
        "if(BODY){try{var ct=(rec.ct||'').toLowerCase();" +
        "if(ct.indexOf('json')>=0||ct.indexOf('text')>=0||ct.indexOf('xml')>=0||ct.indexOf('javascript')>=0){" +
        "rec.b=cut(x.responseText||'');}}catch(e){}}" +
        "send(rec);});" +
        "return XS.apply(this,arguments);};}catch(e){}" +
        "})();"
}

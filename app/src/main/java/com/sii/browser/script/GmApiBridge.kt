// SPDX-License-Identifier: LicenseRef-Sii-NC
// Copyright (c) 2026 wanwuwww
package com.sii.browser.script

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.webkit.JavascriptInterface
import com.sii.browser.di.AppServices
import kotlinx.coroutines.launch

/**
 * GM_* API 桥。
 *
 * 页面侧由 [GM_WRAPPER_JS] 包装为 window.GM_* 全局函数，
 * 通过 window.sii_gm 与本桥通信。变量存储使用独立 SharedPreferences。
 */
class GmApiBridge(private val context: Context) {

    /** 只有当前页匹配到用户脚本并由浏览器注入时才开放。 */
    @Volatile
    var allowed: Boolean = false

    /** 当前已授权的页面 URL；只有 URL 真正变化时才撤销授权。 */
    @Volatile
    var allowedUrl: String? = null

    @Volatile
    private var allowedGrants: Set<String> = emptySet()

    fun allowFor(url: String, grants: Set<String>) {
        allowedUrl = url
        allowedGrants = grants
        allowed = grants.isNotEmpty()
    }

    fun clearIfNavigated(url: String) {
        if (allowedUrl != url) {
            allowed = false
            allowedUrl = null
            allowedGrants = emptySet()
        }
    }

    private fun isGranted(grant: String): Boolean = grant in allowedGrants

    private fun isAllowed(): Boolean = allowed && allowedUrl?.startsWith("http") == true

    private val prefs = context.getSharedPreferences("sii_gm_values", Context.MODE_PRIVATE)
    private val gmClient by lazy { okhttp3.OkHttpClient() }

    @JavascriptInterface
    fun getValue(key: String, defaultValue: String?): String? =
        if (isAllowed() && isGranted("GM_getValue")) prefs.getString(key, defaultValue) else null

    @JavascriptInterface
    fun setValue(key: String, value: String?) {
        if (!isAllowed() || !isGranted("GM_setValue")) return
        prefs.edit().putString(key, value).apply()
    }

    @JavascriptInterface
    fun deleteValue(key: String) {
        if (!isAllowed() || !isGranted("GM_deleteValue")) return
        prefs.edit().remove(key).apply()
    }

    @JavascriptInterface
    fun listValues(): String =
        if (isAllowed() && isGranted("GM_listValues")) prefs.all.keys.joinToString(",") else ""

    @JavascriptInterface
    fun setClipboard(text: String) {
        if (!isAllowed() || !isGranted("GM_setClipboard")) return
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText(null, text))
    }

    @JavascriptInterface
    fun download(url: String, name: String?) {
        if (!isAllowed() || !isGranted("GM_download")) return
        if (url.startsWith("http")) {
            com.sii.browser.download.DownloadDispatcher.start(
                context, url, name, null,
                webView?.url,
                webView?.settings?.userAgentString
            )
        }
    }

    /** 当前挂接的 WebView（弱引用，避免关闭标签后阻止回收）。 */
    private var webViewRef: java.lang.ref.WeakReference<android.webkit.WebView>? = null

    var webView: android.webkit.WebView?
        get() = webViewRef?.get()
        set(value) {
            webViewRef = value?.let { java.lang.ref.WeakReference(it) }
        }

    /** GM_xmlhttpRequest：原生代发请求，结果通过 window.__sii_gmResponse 回页。 */
    @JavascriptInterface
    fun xmlHttpRequest(id: String, method: String, url: String, headersJson: String?, data: String?) {
        if (!isAllowed() || !isGranted("GM_xmlhttpRequest") || !url.startsWith("http") || url.length > 100_000) return
        val upper = method.uppercase()
        if (upper !in setOf("GET", "POST", "PUT", "PATCH", "DELETE", "HEAD", "OPTIONS")) return
        kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.IO).launch {
            var status = 0
            var body = ""
            runCatching {
                val client = gmClient
                val builder = okhttp3.Request.Builder().url(url)
                headersJson?.let { json ->
                    val obj = org.json.JSONObject(json)
                    obj.keys().forEach { key -> builder.addHeader(key, obj.optString(key)) }
                }
                val requestBody = if (data != null && upper != "GET" && upper != "HEAD") {
                    okhttp3.RequestBody.create(null, data)
                } else null
                client.newCall(builder.method(upper, requestBody).build()).execute().use { response ->
                    status = response.code
                    val bytes = response.body?.source()?.readByteArray(MAX_GM_RESPONSE_BYTES.toLong())
                    body = bytes?.toString(Charsets.UTF_8).orEmpty()
                }
            }
            val js = "window.__sii_gmResponse && window.__sii_gmResponse('" +
                id.replace("'", "") + "'," + status + "," +
                org.json.JSONObject.quote(body) + ")"
            withMain { webView?.evaluateJavascript(js, null) }
        }
    }

    private fun withMain(block: () -> Unit) {
        android.os.Handler(android.os.Looper.getMainLooper()).post(block)
    }

    /** GM_registerMenuCommand：注册脚本菜单项（名字存偏好，供浏览器菜单展示）。 */
    @JavascriptInterface
    fun registerMenuCommand(id: String, name: String) {
        if (!isAllowed() || !isGranted("GM_registerMenuCommand")) return
        val existing = prefs.getStringSet(KEY_MENU_IDS, emptySet()).orEmpty().toMutableSet()
        existing.add(id)
        prefs.edit().putStringSet(KEY_MENU_IDS, existing).putString("menu_name_$id", name).apply()
        commands[id] = name
    }

    companion object {
        private const val MAX_GM_RESPONSE_BYTES = 4 * 1024 * 1024
        const val KEY_MENU_IDS = "menu_ids"

        /** 当前页面注册的脚本菜单项（id → 名称），页面开始时清空。 */
        private val commands = java.util.Collections.synchronizedMap(LinkedHashMap<String, String>())

        fun menuCommands(): List<Pair<String, String>> =
            synchronized(commands) { commands.entries.map { it.key to it.value } }

        fun clearMenuCommands() = commands.clear()

        /** 页面侧 GM_* 包装脚本（注入在用户脚本之前）。 */
        const val GM_WRAPPER_JS: String = """
            (function(){
              if (window.__sii_gm_ready__) return;
              window.__sii_gm_ready__ = true;
              var api = window.sii_gm;
              if (!api) return;
              window.GM_getValue = function(k, d){ var v = api.getValue(String(k), null); return (v === null || v === undefined) ? d : JSON.parse(v); };
              window.GM_setValue = function(k, v){ api.setValue(String(k), JSON.stringify(v)); };
              window.GM_deleteValue = function(k){ api.deleteValue(String(k)); };
              window.GM_listValues = function(){ return api.listValues().split(',').filter(function(x){return x;}); };
              window.GM_setClipboard = function(t){ api.setClipboard(String(t)); };
              window.GM_download = function(opt){ if(opt && opt.url) api.download(String(opt.url), opt.name ? String(opt.name) : null); };
              window.GM_addStyle = function(css){ var s = document.createElement('style'); s.textContent = css; (document.head || document.documentElement).appendChild(s); return s; };
              window.GM_addElement = function(parent, el){ (typeof parent === 'string' ? document.querySelector(parent) : parent).appendChild(el); };
              window.GM_xmlhttpRequest = function(opt){
                opt = opt || {};
                var id = 'r' + (window.__sii_gm_seq__ = (window.__sii_gm_seq__ || 0) + 1);
                (window.__sii_gm_pending__ = window.__sii_gm_pending__ || {})[id] = opt;
                var headers = opt.headers ? JSON.stringify(opt.headers) : null;
                api.xmlHttpRequest(id, opt.method || 'GET', String(opt.url || ''), headers, opt.data != null ? String(opt.data) : null);
                return { abort: function(){} };
              };
              window.__sii_gmResponse = function(id, status, responseText){
                var opt = (window.__sii_gm_pending__ || {})[id];
                if (!opt) return;
                delete window.__sii_gm_pending__[id];
                var res = { status: status, responseText: responseText, readyState: 4, response: responseText };
                if (opt.onload && status >= 200 && status < 400) opt.onload(res);
                else if (opt.onerror) opt.onerror(res);
                if (opt.onloadend) opt.onloadend(res);
              };
              window.GM_registerMenuCommand = function(name, fn){
                var id = 'm' + (window.__sii_gm_mseq__ = (window.__sii_gm_mseq__ || 0) + 1);
                (window.__sii_gm_menus__ = window.__sii_gm_menus__ || {})[id] = fn;
                api.registerMenuCommand(id, String(name));
              };
              window.__sii_runMenuCommand = function(id){
                var fn = (window.__sii_gm_menus__ || {})[id];
                if (fn) fn();
              };
            })();
        """
    }
}

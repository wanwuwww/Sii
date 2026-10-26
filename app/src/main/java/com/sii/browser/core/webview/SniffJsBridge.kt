// SPDX-License-Identifier: LicenseRef-Sii-NC
// Copyright (c) 2026 wanwuwww
package com.sii.browser.core.webview

import android.os.Handler
import android.os.Looper
import android.webkit.JavascriptInterface
import com.sii.browser.di.AppServices
import org.json.JSONArray

/**
 * 资源嗅探 JS 桥（window.SiiSniff）。
 * 页面脚本把采集到的资源列表回传原生，写入 [SniffStore]（转主线程）。
 */
class SniffJsBridge(private val webView: SiiWebView, private val key: Any) {

    private val main = Handler(Looper.getMainLooper())
    private val pending = java.util.ArrayDeque<SniffedResource>()
    private var flushScheduled = false
    private val flush = Runnable {
        val batch = synchronized(pending) {
            flushScheduled = false
            pending.toList().also { pending.clear() }
        }
        batch.forEach { SniffStore.add(key, it) }
    }

    @JavascriptInterface
    fun push(payload: String) {
        if (!AppServices.settings.sniffEnabled()) return
        if (payload.isBlank()) return
        val parsed = runCatching {
            val arr = JSONArray(payload)
            (0 until arr.length()).mapNotNull { i ->
                val obj = arr.optJSONObject(i) ?: return@mapNotNull null
                val url = obj.optString("url")
                if (url.isBlank()) return@mapNotNull null
                val mime = obj.optString("mime").ifBlank { null }
                SniffedResource(
                    url = url,
                    type = SniffStore.typeOf(url, mime),
                    mime = mime,
                    size = obj.optLong("size", 0L)
                )
            }
        }.getOrDefault(emptyList())
        if (parsed.isEmpty()) return
        synchronized(pending) {
            pending.addAll(parsed)
            if (!flushScheduled) {
                flushScheduled = true
                main.postDelayed(flush, 120L)
            }
        }
    }

    companion object {
        /**
         * 采集脚本：DOM 媒体元素 + Resource Timing + hook fetch/XHR + MutationObserver。
         * 文档开始时注入（尽早 hook fetch/XHR），加载完成后重复扫描。
         */
        const val SNIFF_JS: String = "(function(){" +
            "if(window.__siiSniffInit){try{window.__siiSniffScan();}catch(e){}return;}" +
            "window.__siiSniffInit=true;" +
            "var seen={};" +
            "function push(u,size,mime){" +
            "if(!u||!/^(https?|blob):/i.test(u))return;" +
            "var m=(mime||'').toLowerCase();" +
            "if(seen[u]&&(!m||seen[u]===m))return;" +
            "seen[u]=m||seen[u]||1;" +
            "try{window.SiiSniff&&window.SiiSniff.push(JSON.stringify([{url:u,size:size||0,mime:m}]));}catch(e){}" +
            "}" +
            "var DOCS=['mp4','m4v','webm','mkv','flv','avi','mov','m3u8','ts','m4s','mp3','m4a','aac','flac','wav','ogg'," +
            "'pdf','doc','docx','xls','xlsx','ppt','pptx','zip','rar','7z','apk','epub','torrent'];" +
            "function ext(u){try{var m=u.split('?')[0].split('#')[0].match(/\\.([a-z0-9]+)$/i);return m?m[1].toLowerCase():'';}catch(e){return '';}}" +
            "try{var ocu=URL.createObjectURL;if(ocu){URL.createObjectURL=function(o){" +
            "var u=ocu.apply(this,arguments);" +
            "try{if(o&&o.__siiMime)push(u,0,o.__siiMime);}catch(e){}" +
            "return u;};}}catch(e){}" +
            "try{if(window.MediaSource&&MediaSource.prototype){var oas=MediaSource.prototype.addSourceBuffer;" +
            "MediaSource.prototype.addSourceBuffer=function(m){try{this.__siiMime=m;}catch(e){}return oas.apply(this,arguments);};}}catch(e){}" +
            "window.__siiSniffScan=function(){" +
            "try{document.querySelectorAll('video,audio,source').forEach(function(e){" +
            "if(e.src)push(e.src);if(e.currentSrc)push(e.currentSrc);" +
            "var ss=e.querySelectorAll?e.querySelectorAll('source[src]'):[];for(var i=0;i<ss.length;i++)push(ss[i].src,0,ss[i].type);" +
            "});}catch(e){}" +
            "try{document.querySelectorAll('a[href]').forEach(function(a){" +
            "var t=ext(a.href);if(a.hasAttribute('download')||DOCS.indexOf(t)>=0)push(a.href);" +
            "});}catch(e){}" +
            "try{performance.getEntriesByType('resource').forEach(function(r){" +
            "var ty=(r.initiatorType||'');var n=r.name;" +
            "if(ty==='video'||ty==='audio'||ty==='media'||DOCS.indexOf(ext(n))>=0)push(n,r.transferSize||r.decodedBodySize||0);" +
            "});}catch(e){}" +
            "try{document.querySelectorAll('iframe').forEach(function(f){try{var d=f.contentDocument;if(!d)return;" +
            "d.querySelectorAll('video,audio,source').forEach(function(e){if(e.src)push(e.src);if(e.currentSrc)push(e.currentSrc);" +
            "var ss=e.querySelectorAll?e.querySelectorAll('source[src]'):[];for(var i=0;i<ss.length;i++)push(ss[i].src,0,ss[i].type);});" +
            "}catch(e){}});}catch(e){}" +
            "};" +
            "try{var of=window.fetch;if(of){window.fetch=function(){" +
            "var u='';try{var a=arguments[0];u=(typeof a==='string')?a:(a&&a.url)||'';if(u)push(u);}catch(e){}" +
            "var p=of.apply(this,arguments);" +
            "try{p.then(function(r){try{var m=r.headers.get('content-type');if(m)push(r.url||u,0,m);}catch(e){}});}catch(e){}" +
            "return p;};}}catch(e){}" +
            "try{var ox=XMLHttpRequest.prototype.open;XMLHttpRequest.prototype.open=function(m,u){" +
            "try{this.__siiUrl=u;if(u)push(u);}catch(e){}return ox.apply(this,arguments);};" +
            "var os=XMLHttpRequest.prototype.send;XMLHttpRequest.prototype.send=function(){" +
            "try{var x=this;x.addEventListener('loadend',function(){try{var m=x.getResponseHeader('content-type');if(m)push(x.__siiUrl,0,m);}catch(e){}});}catch(e){}" +
            "return os.apply(this,arguments);};}catch(e){}" +
            "window.__siiSniffScan();" +
            "var pend=false;function sch(){if(pend)return;pend=true;setTimeout(function(){pend=false;try{window.__siiSniffScan();}catch(e){}},300);}" +
            "try{new MutationObserver(sch).observe(document.documentElement,{childList:true,subtree:true});}catch(e){}" +
            "})();"
    }
}

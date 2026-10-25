package com.sii.browser.core.webview

import android.webkit.JavascriptInterface

/**
 * 网页 theme-color 回传桥：页面脚本读取 meta[name=theme-color] 后调用 post。
 * 原生侧只负责解析和配色，不向页面注入任何样式。
 */
class ThemeColorJsBridge(private val onColor: (String?) -> Unit) {

    @JavascriptInterface
    fun post(color: String?) {
        onColor(color)
    }

    companion object {
        /** 注入脚本：读取当前匹配的 theme-color，并在 head 变化时重新上报。 */
        val SCRIPT = """
            (function(){
              if (window.__siiThemeColorReady) return;
              window.__siiThemeColorReady = true;
              var last = '';
              function pick(){
                try {
                  var metas = document.querySelectorAll('meta[name="theme-color"]');
                  for (var i = 0; i < metas.length; i++) {
                    var m = metas[i];
                    var media = m.getAttribute('media');
                    if (media && window.matchMedia && !window.matchMedia(media).matches) continue;
                    var c = (m.getAttribute('content') || '').trim();
                    if (c) return c;
                  }
                } catch (e) {}
                return '';
              }
              function post(){
                var c = pick();
                if (c === last) return;
                last = c;
                try {
                  if (window.SiiThemeColor && window.SiiThemeColor.post) {
                    window.SiiThemeColor.post(c);
                  }
                } catch (e) {}
              }
              var started = false;
              function start(){
                if (started) return;
                started = true;
                wait.stopped = true;
                post();
                try {
                  var target = document.head || document.documentElement;
                  if (target && window.MutationObserver) {
                    new MutationObserver(post).observe(
                      target,
                      {childList:true, subtree:true, attributes:true,
                       attributeFilter:['content','media','name']}
                    );
                  }
                } catch (e) {}
              }
              function wait(){
                if (wait.stopped) return;
                if (document.head || document.documentElement) {
                  if (document.querySelector('meta[name="theme-color"]')) {
                    start();
                  } else if (!wait.pending) {
                    wait.pending = true;
                    setTimeout(start, 500);
                  } else {
                    setTimeout(wait, 30);
                  }
                } else {
                  setTimeout(wait, 30);
                }
              }
              setTimeout(start, 2000);
              wait();
            })();
        """.trimIndent()
    }
}

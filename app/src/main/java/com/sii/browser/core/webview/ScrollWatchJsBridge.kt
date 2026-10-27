// SPDX-License-Identifier: LicenseRef-Sii-NC
// Copyright (c) 2026 wanwuwww
package com.sii.browser.core.webview

/**
 * 页内滚动监听：页面把滚动放在内层容器（`overflow-y:auto`、固定高度布局）时，
 * WebView 自身不滚动、onScrollChanged 不触发，这里补上纵向位移信号。
 * 文档级滚动仍交给原生回调，避免重复计数。
 */
class ScrollWatchJsBridge(private val onScroll: (Int) -> Unit) {

    @android.webkit.JavascriptInterface
    fun scroll(dy: Int) {
        if (dy == 0) return
        onScroll(dy.coerceIn(-3000, 3000))
    }

    companion object {

        /** 幂等注入：捕获阶段监听页面内所有元素的滚动。 */
        val SCRIPT = """
            (function(){
              var bridge = window.SiiScroll;
              if (!bridge) return;
              if (window.__siiScrollWatch) return;
              window.__siiScrollWatch = true;
              var last = new WeakMap();
              var acc = 0, lastTouch = 0, flushing = false;
              function isDoc(t){
                return !t || t === document || t === document.documentElement || t === document.body;
              }
              function flush(){
                flushing = false;
                if (!acc) return;
                var v = acc;
                acc = 0;
                try { bridge.scroll(v); } catch (e) {}
              }
              function push(v){
                if (!v) return;
                if ((v > 0) !== (acc > 0)) acc = 0;
                acc += v;
                if (!flushing) {
                  flushing = true;
                  setTimeout(flush, 60);
                }
              }
              window.addEventListener('scroll', function(e){
                var t = e.target;
                if (isDoc(t) || !t || t.nodeType !== 1) return;
                if (Date.now() - lastTouch < 120) return;
                var top = t.scrollTop || 0;
                var prev = last.get(t);
                last.set(t, top);
                if (typeof prev !== 'number') return;
                push(Math.round(top - prev));
              }, true);
              window.addEventListener('touchstart', function(){
                lastTouch = Date.now();
                acc = 0;
              }, true);
            })();
        """.trimIndent()
    }
}

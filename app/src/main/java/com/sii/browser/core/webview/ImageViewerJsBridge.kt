// SPDX-License-Identifier: LicenseRef-Sii-NC
// Copyright (c) 2026 wanwuwww
package com.sii.browser.core.webview

import android.webkit.JavascriptInterface

/**
 * 网页自绘图片查看器检测桥：站点用铺满视口的浮层看大图，没有全屏 API 也没有滚动信号，
 * 原生侧据此隐藏悬浮工具栏，避免挡住图片。
 */
class ImageViewerJsBridge(private val onActive: (Boolean) -> Unit) {

    @JavascriptInterface
    fun post(active: Boolean) {
        onActive(active)
    }

    companion object {
        /** 幂等注入：取视口中心点命中的元素，判断它或祖辈是否为铺满视口的图片浮层。 */
        val SCRIPT = """
            (function(){
              if (window.__siiViewerReady) return;
              window.__siiViewerReady = true;
              var last = null;
              function active(){
                try {
                  var w = window.innerWidth || 1;
                  var h = window.innerHeight || 1;
                  var stack = document.elementsFromPoint(w / 2, h / 2) || [];
                  for (var i = 0; i < stack.length && i < 12; i++) {
                    var el = stack[i];
                    if (!el || el === document.body || el === document.documentElement) break;
                    var cs = getComputedStyle(el);
                    if (cs.position !== 'fixed' && cs.position !== 'absolute') continue;
                    var r = el.getBoundingClientRect();
                    if (r.width < w * 0.85 || r.height < h * 0.8) continue;
                    var tag = String(el.tagName || '').toUpperCase();
                    var hasImg = tag === 'IMG' || !!el.querySelector('img');
                    var bg = cs.backgroundImage && cs.backgroundImage !== 'none';
                    if (!hasImg && !bg) continue;
                    return true;
                  }
                } catch (e) {}
                return false;
              }
              function push(){
                var v = active();
                if (v === last) return;
                last = v;
                try { if (window.SiiViewer && window.SiiViewer.post) window.SiiViewer.post(v); } catch (e) {}
              }
              window.addEventListener('resize', push);
              document.addEventListener('click', function(){ setTimeout(push, 350); }, true);
              document.addEventListener('popstate', function(){ setTimeout(push, 350); });
              setInterval(push, 900);
              setTimeout(push, 600);
            })();
        """.trimIndent()
    }
}

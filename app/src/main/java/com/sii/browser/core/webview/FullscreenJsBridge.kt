// SPDX-License-Identifier: LicenseRef-Sii-NC
// Copyright (c) 2026 wanwuwww
package com.sii.browser.core.webview

import android.webkit.JavascriptInterface

/** 网页视频全屏检测桥：覆盖 Fullscreen API 与网页自己铺满视口的“网页全屏”。 */
class FullscreenJsBridge(private val onFullscreen: (Boolean) -> Unit) {

    @JavascriptInterface
    fun post(active: Boolean) {
        onFullscreen(active)
    }

    companion object {
        val SCRIPT = """
            (function(){
              if (window.__siiFsReady) return;
              window.__siiFsReady = true;
              var last = null;
              function active(){
                try {
                  var el = document.fullscreenElement || document.webkitFullscreenElement;
                  if (el) {
                    if (String(el.tagName).toUpperCase() === 'VIDEO') return true;
                    if (el.querySelector && el.querySelector('video')) return true;
                  }
                  var w = window.innerWidth || 1;
                  var h = window.innerHeight || 1;
                  var list = document.querySelectorAll('video');
                  for (var i = 0; i < list.length; i++) {
                    var r = list[i].getBoundingClientRect();
                    if (r.width >= w * 0.9 && r.height >= h * 0.7 && r.top <= h * 0.1) return true;
                  }
                } catch (e) {}
                return false;
              }
              function push(force){
                var v = active();
                if (!force && v === last) return;
                last = v;
                try {
                  if (window.SiiFullscreen && window.SiiFullscreen.post) {
                    window.SiiFullscreen.post(v);
                  }
                } catch (e) {}
              }
              document.addEventListener('fullscreenchange', function(){ push(false); }, true);
              document.addEventListener('webkitfullscreenchange', function(){ push(false); }, true);
              window.addEventListener('resize', function(){ push(false); });
              setInterval(function(){ push(false); }, 1000);
              push(true);
            })();
        """.trimIndent()
    }
}

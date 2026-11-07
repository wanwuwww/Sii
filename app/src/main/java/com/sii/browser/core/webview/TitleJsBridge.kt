// SPDX-License-Identifier: LicenseRef-Sii-NC
// Copyright (c) 2026 wanwuwww
package com.sii.browser.core.webview

import android.webkit.JavascriptInterface

/**
 * 页面标题回传桥：SPA 单页应用通过 pushState 换页时不会触发 onReceivedTitle，
 * 这里监听 <title> 变化并回报，让标签卡与地址栏（标题模式）跟随页面。
 */
class TitleJsBridge(private val onTitle: (String) -> Unit) {

    @JavascriptInterface
    fun post(title: String?) {
        onTitle(title.orEmpty().take(300))
    }

    companion object {
        /** 幂等注入：<title> 变化、历史变化与定时兜底都上报一次。 */
        val SCRIPT = """
            (function(){
              if (window.__siiTitleReady) return;
              window.__siiTitleReady = true;
              var last = '';
              function push(){
                var t = '';
                try { t = (document.title || '').trim(); } catch (e) {}
                if (!t || t === last) return;
                last = t;
                try { if (window.SiiTitle && window.SiiTitle.post) window.SiiTitle.post(t); } catch (e) {}
              }
              function watchHead(){
                try {
                  var head = document.head;
                  if (!head) { setTimeout(watchHead, 500); return; }
                  new MutationObserver(push).observe(head, { childList: true, subtree: true, characterData: true });
                } catch (e) {}
              }
              watchHead();
              window.addEventListener('popstate', function(){ setTimeout(push, 120); });
              window.addEventListener('hashchange', function(){ setTimeout(push, 120); });
              setInterval(push, 1200);
              setTimeout(push, 400);
            })();
        """.trimIndent()
    }
}

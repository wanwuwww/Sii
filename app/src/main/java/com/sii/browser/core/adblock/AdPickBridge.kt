// SPDX-License-Identifier: LicenseRef-Sii-NC
// Copyright (c) 2026 wanwuwww
package com.sii.browser.core.adblock

import android.os.Handler
import android.os.Looper
import android.webkit.JavascriptInterface
import com.sii.browser.di.AppServices
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * 「标记广告」选取模式：页面注入脚本高亮手指下的元素，点击后回传 CSS 选择器，
 * 原生生成元素隐藏规则（`域名##选择器`）写入自定义规则并立即生效。
 */
class AdPickBridge {

    /** 开始选取时的域名（由浏览器在进入选取模式时设置）。 */
    var host: String = ""

    /** 只在用户主动进入标记广告模式时允许提交。 */
    @Volatile
    var active: Boolean = false

    @JavascriptInterface
    fun pick(selector: String) {
        if (!active || selector.isBlank() || host.isBlank()) return
        active = false
        val line = "${host.lowercase()}##$selector"
        Handler(Looper.getMainLooper()).post {
            AppServices.adBlock.addCustomRule(line)
            CoroutineScope(Dispatchers.IO).launch {
                val existing = AppServices.ruleRepository.customRulesText()
                if (existing.lines().none { it.trim() == line }) {
                    AppServices.ruleRepository.saveCustomRules(existing.trimEnd() + "\n" + line)
                }
            }
        }
    }

    companion object {
        /**
         * 选取脚本：触摸跟随高亮 + 点击拦截并回传选择器。
         * 选择器优先使用 id，否则生成 tag.class:nth-child 路径（最多 5 层）。
         */
        const val PICK_JS: String = "(function(){" +
            "if(window.__siiAdPick)return;window.__siiAdPick=true;" +
            "var cur=null;" +
            "function selector(el){" +
            "if(!el||!el.tagName)return '';" +
            "if(el.id)return '#'+el.id;" +
            "var path=[];var e=el;var depth=0;" +
            "while(e&&e.tagName&&e.tagName.toLowerCase()!=='body'&&depth<5){" +
            "var s=e.tagName.toLowerCase();" +
            "var cls=(typeof e.className==='string'&&e.className.trim())?e.className.trim().split(/\\s+/)[0]:'';" +
            "if(cls)s+='.'+cls;" +
            "var p=e.parentElement;if(p){var idx=Array.prototype.indexOf.call(p.children,e);s+=':nth-child('+(idx+1)+')';}" +
            "path.unshift(s);e=p;depth++;}" +
            "return path.join(' > ');}" +
            "function hl(el){if(cur)cur.style.outline='';cur=el;if(cur)cur.style.outline='2px solid #f44336';}" +
            "function onMove(ev){try{var t=ev.touches[0];hl(document.elementFromPoint(t.clientX,t.clientY));}catch(e){}}" +
            "function onClick(ev){try{ev.preventDefault();ev.stopPropagation();" +
            "var s=selector(ev.target);if(window.SiiAdPick&&s)window.SiiAdPick.pick(s);" +
            "if(cur)cur.style.outline='';}catch(e){}" +
            "document.removeEventListener('touchmove',onMove,true);" +
            "document.removeEventListener('click',onClick,true);" +
            "window.__siiAdPick=false;}" +
            "document.addEventListener('touchmove',onMove,{capture:true,passive:true});" +
            "document.addEventListener('click',onClick,true);" +
            "})();"
    }
}

// SPDX-License-Identifier: LicenseRef-Sii-NC
// Copyright (c) 2026 wanwuwww
package com.sii.browser.core.webview

import android.os.Handler
import android.os.Looper
import android.webkit.JavascriptInterface
import com.sii.browser.di.AppServices

/**
 * 密码库 JS 桥（window.SiiPwd）：页面提交表单时回传账号密码，原生弹「保存到密码库？」；
 * 自动填充由原生注入 [fillJs] 完成。
 */
class PasswordJsBridge {

    /** 当前页面域名（页面加载时由浏览器设置）。 */
    var host: String = ""

    /** 当前页面 URL，用于拒绝 HTTP 页面保存密码。 */
    @Volatile
    var pageUrl: String = ""

    /** 收到提交的账号密码（主线程回调）。 */
    var onOfferSave: ((host: String, username: String, password: String) -> Unit)? = null

    @JavascriptInterface
    fun offerSave(username: String, password: String) {
        if (!AppServices.settings.passwordEnabled() || AppServices.privacyManager.isIncognito) return
        if (!pageUrl.startsWith("https://")) return
        if (host.isBlank() || password.isBlank()) return
        val currentHost = host
        Handler(Looper.getMainLooper()).post {
            onOfferSave?.invoke(currentHost, username, password)
        }
    }

    companion object {

        /** 监听表单提交/提交按钮点击，抓取密码框与用户名框的值回传原生。 */
        const val DETECT_JS: String = "(function(){" +
            "if(window.__siiPwdHooked)return;window.__siiPwdHooked=true;" +
            "function userField(pw){try{var s=pw.form||document;" +
            "var c=s.querySelectorAll('input[type=text],input[type=email],input[name*=user i],input[id*=user i],input[name*=mail i],input[id*=mail i]');" +
            "return c.length?c[0]:null;}catch(e){return null;}}" +
            "function report(){try{var pws=document.querySelectorAll('input[type=password]');" +
            "for(var i=0;i<pws.length;i++){var pw=pws[i];var v=pw.value;if(!v)continue;" +
            "var u=userField(pw);var uv=u?u.value:'';" +
            "if(window.SiiPwd)window.SiiPwd.offerSave(uv||'',v);return;}}catch(e){}}" +
            "document.addEventListener('submit',function(){setTimeout(report,300);},true);" +
            "document.addEventListener('click',function(ev){var t=ev.target;" +
            "try{if(t&&(t.type==='submit'||t.tagName==='BUTTON'||t.getAttribute&&t.getAttribute('type')==='submit'))" +
            "setTimeout(report,600);}catch(e){}},true);" +
            "})();"

        /** 生成填充脚本：填用户名与密码并派发 input 事件（触发站点 JS 校验）。 */
        fun fillJs(username: String, password: String): String {
            fun esc(value: String) = value
                .replace("\\", "\\\\")
                .replace("'", "\\'")
                .replace("\n", "\\n")
            val u = esc(username)
            val p = esc(password)
            return "(function(){try{" +
                "var pws=document.querySelectorAll('input[type=password]');if(!pws.length)return;" +
                "var pw=pws[0];var scope=pw.form||document;" +
                "var users=scope.querySelectorAll('input[type=text],input[type=email]," +
                "input[name*=user i],input[id*=user i],input[name*=mail i],input[id*=mail i]');" +
                "if(users.length&&'$u'){users[0].value='$u';" +
                "users[0].dispatchEvent(new Event('input',{bubbles:true}));}" +
                "pw.value='$p';pw.dispatchEvent(new Event('input',{bubbles:true}));" +
                "}catch(e){}})();"
        }
    }
}

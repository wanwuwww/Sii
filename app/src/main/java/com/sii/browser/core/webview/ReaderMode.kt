package com.sii.browser.core.webview

/**
 * 阅读模式与「自动展开全文」的注入脚本（含站点规则表）。
 *
 * 阅读模式：优先用站点规则的正文容器，没有则按可见文本量挑最大容器，
 * 用固定浮层重排为易读版式；退出即刷新页面（不改原 DOM）。
 * 自动展开：通用文案/类名匹配 + 站点专属「展开」按钮选择器。
 */
object ReaderMode {

    /** 站点规则：正文容器候选 + 展开按钮选择器。 */
    data class SiteRule(val container: List<String>, val expand: List<String>)

    /** 站点规则表（按域名后缀匹配，靠前的优先）。 */
    private val SITE_RULES: List<Pair<String, SiteRule>> = listOf(
        "csdn.net" to SiteRule(
            listOf("#content_views", "article"),
            listOf(".read_more_btn", ".more-btn", ".btn-readmore", ".hide-article-box")
        ),
        "zhihu.com" to SiteRule(
            listOf(".RichText", ".Post-RichText", ".AnswerItem"),
            listOf(".RichText-collapse", "button.Button--plain", ".ContentItem-expandButton")
        ),
        "jianshu.com" to SiteRule(
            listOf("article", ".show-content"),
            listOf(".show-more", ".collapse-free-content")
        ),
        "baijiahao.baidu.com" to SiteRule(
            listOf("#article", ".article-content"),
            listOf(".showMore", ".expand-btn")
        ),
        "mp.weixin.qq.com" to SiteRule(listOf("#js_content"), emptyList()),
        "weibo.com" to SiteRule(
            listOf(".detail_wbtext", ".WB_text"),
            listOf(".woo-box-justifyContentCenter")
        ),
        "toutiao.com" to SiteRule(
            listOf("article", ".article-content"),
            listOf(".more-btn", ".expand")
        )
    )

    private const val DEFAULT_CONTAINERS =
        "'article','main','[role=main]','.article','.article-content','.content','#content','.post','.entry'"

    fun ruleFor(host: String?): SiteRule? {
        val normalized = host?.lowercase() ?: return null
        return SITE_RULES.firstOrNull { normalized.endsWith(it.first) }?.second
    }

    /** 阅读配色：0=跟随夜间，1=白底黑字，2=米黄护眼，3=深色。 */
    private fun readerColors(theme: Int, night: Boolean): Pair<String, String> = when (theme) {
        1 -> "#ffffff" to "#1a1a1e"
        2 -> "#f5efe1" to "#3b342a"
        3 -> "#121417" to "#e8eaed"
        else -> if (night) "#121417" to "#e8eaed" else "#ffffff" to "#1a1a1e"
    }

    /** 进入阅读模式的脚本（字号、配色来自设置；host 用于站点规则）。 */
    fun enterJs(
        fontSize: Int,
        night: Boolean,
        host: String? = null,
        theme: Int = 0,
        customCss: String? = null
    ): String {
        val size = if (fontSize > 0) fontSize else 18
        val (bg, fg) = readerColors(theme, night)
        val siteSelectors = ruleFor(host)?.container
            ?.joinToString(",") { "'${it}'" }
            .orEmpty()
        val selectors = if (siteSelectors.isBlank()) DEFAULT_CONTAINERS
        else "$siteSelectors,$DEFAULT_CONTAINERS"

        return "(function(){" +
            "if(document.getElementById('sii_reader'))return;" +
            "function score(el){var t=(el.innerText||'').trim();" +
            "if(t.length<200)return 0;var link=0;" +
            "try{el.querySelectorAll('a').forEach(function(a){link+=(a.innerText||'').length;});}catch(e){}" +
            "var ps=el.querySelectorAll('p').length;" +
            "var density=link/Math.max(1,t.length);" +
            "return t.length*(1-density*2)+ps*50;}" +
            "var best=null,bestScore=0;" +
            "[$selectors].forEach(function(sel){try{document.querySelectorAll(sel).forEach(function(el){" +
            "var s=score(el);if(s>bestScore){bestScore=s;best=el;}});}catch(e){}});" +
            "if(!best){try{document.querySelectorAll('div,section').forEach(function(el){" +
            "if(el.children.length<=6){var s=score(el);if(s>bestScore&&s>600){bestScore=s;best=el;}}});}catch(e){}}" +
            "if(!best)return;" +
            "var c=document.createElement('div');c.id='sii_reader';" +
            "c.style.cssText='position:fixed;inset:0;z-index:2147483647;overflow:auto;background:${bg};" +
            "color:${fg};padding:18px 16px 48px;font-size:${size}px;line-height:1.75;" +
            "-webkit-text-size-adjust:100%;';" +
            "var t=(document.title||'').replace(/</g,'&lt;');" +
            "c.innerHTML='<div style=\"font-size:1.3em;font-weight:700;line-height:1.4;margin:0 0 16px\">'+t+'</div>'+best.innerHTML;" +
            "try{c.querySelectorAll('script,style,iframe,ins,aside,nav,footer,form').forEach(function(n){n.remove();});}catch(e){}" +
            "try{c.querySelectorAll('img').forEach(function(i){i.style.maxWidth='100%';i.style.height='auto';});}catch(e){}" +
            (if (!customCss.isNullOrBlank()) {
                "try{var st=document.createElement('style');st.textContent=${org.json.JSONObject.quote(customCss)};c.appendChild(st);}catch(e){}"
            } else "") +
            "document.body.appendChild(c);" +
            "})();"
    }

    /** 自动展开全文：通用文案/类名 + 站点专属展开按钮（延迟执行两次）。 */
    fun autoExpandJs(host: String? = null): String {
        val siteSelectors = ruleFor(host)?.expand
            ?.joinToString(",")
            .orEmpty()
        val extra = if (siteSelectors.isBlank()) "" else ",$siteSelectors"
        return "(function(){" +
            "if(window.__siiAutoExpand)return;window.__siiAutoExpand=true;" +
            "var KEYS=['展开全文','展开阅读全文','继续阅读','阅读全文','查看全文','展开剩余','显示全部'];" +
            "var SEL='[class*=expand],[class*=unfold],[id*=expand],[class*=more-btn],[class*=readmore],[class*=read-more]$extra';" +
            "function tryClick(){" +
            "try{var nodes=document.querySelectorAll('a,button,span,div,p');" +
            "for(var i=0;i<nodes.length;i++){var n=nodes[i];var txt=(n.innerText||'').trim();" +
            "if(txt.length===0||txt.length>12)continue;" +
            "var hit=KEYS.some(function(k){return txt.indexOf(k)>=0;});" +
            "if(hit){try{n.click();}catch(e){}return true;}}}catch(e){}" +
            "try{var cand=document.querySelectorAll(SEL);for(var j=0;j<cand.length;j++){" +
            "var el=cand[j];if(el.offsetHeight>0){try{el.click();return true;}catch(e){}}}}catch(e){}" +
            "return false;}" +
            "setTimeout(tryClick,1200);setTimeout(tryClick,3000);" +
            "})();"
    }

    @Deprecated("使用 autoExpandJs(host) 以应用站点规则")
    val AUTO_EXPAND_JS: String get() = autoExpandJs(null)
}

package com.sii.browser.data

import android.net.Uri

/** 内置搜索引擎：设置页与地址栏共用，避免两处硬编码漂移。 */
object SearchEngines {
    val DEFAULT = listOf(
        "百度" to "https://www.baidu.com/s?wd=",
        "Google" to "https://www.google.com/search?q=",
        "必应" to "https://cn.bing.com/search?q=",
        "DuckDuckGo" to "https://duckduckgo.com/?q=",
        "搜狗" to "https://www.sogou.com/web?query=",
        "360 搜索" to "https://www.so.com/s?q=",
        "神马" to "https://m.sm.cn/s?q=",
        "头条搜索" to "https://so.toutiao.com/search?keyword=",
        "Yandex" to "https://yandex.com/search/?text=",
        "Brave" to "https://search.brave.com/search?q=",
        "Startpage" to "https://www.startpage.com/sp/search?query=",
        "Ecosia" to "https://www.ecosia.org/search?q="
    )

    /** 已知首页地址自动补成搜索模板，避免用户填写完整查询参数。 */
    fun normalize(raw: String): String {
        val value = raw.trim()
        if (value.isEmpty() || value.contains("%s") || value.contains("{q}")) return value
        val uri = runCatching { Uri.parse(value) }.getOrNull()
        val host = uri?.host?.lowercase().orEmpty()
        return when {
            host == "sogou.com" || host.endsWith(".sogou.com") ->
                "https://www.sogou.com/web?query=%s"
            host == "so.com" || host.endsWith(".so.com") ->
                "https://www.so.com/s?q=%s"
            host == "sm.cn" || host.endsWith(".sm.cn") ->
                "https://m.sm.cn/s?q=%s"
            host == "so.toutiao.com" ->
                "https://so.toutiao.com/search?keyword=%s"
            host == "search.brave.com" ->
                "https://search.brave.com/search?q=%s"
            host == "yandex.com" || host.endsWith(".yandex.com") ->
                "https://yandex.com/search/?text=%s"
            host == "startpage.com" || host.endsWith(".startpage.com") ->
                "https://www.startpage.com/sp/search?query=%s"
            host == "ecosia.org" || host.endsWith(".ecosia.org") ->
                "https://www.ecosia.org/search?q=%s"
            else -> value
        }
    }

    /** 支持 %s、{q} 或兼容旧的末尾追加写法。 */
    fun build(template: String, query: String): String {
        val encoded = Uri.encode(query)
        return when {
            template.contains("%s") -> template.replace("%s", encoded)
            template.contains("{q}") -> template.replace("{q}", encoded)
            else -> template + encoded
        }
    }
}

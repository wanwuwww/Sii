// SPDX-License-Identifier: LicenseRef-Sii-NC
// Copyright (c) 2026 wanwuwww
package com.sii.browser.core.adblock

import android.net.Uri
import android.webkit.WebResourceRequest

/**
 * 广告过滤引擎。
 *
 * 规则按域名/URL 片段建索引；网络规则在匹配时带上资源类型、页面域名和第三方标记，
 * 主文档只有显式 `$document` 规则才会参与。元素隐藏规则单独处理 `#@#` 例外。
 */
class AdBlockEngine {

    private val customRules = mutableListOf<AdBlockRule>()
    private val subscriptionRules = mutableListOf<AdBlockRule>()
    private val siteEnabled = mutableSetOf<String>()
    private val siteDisabled = mutableSetOf<String>()

    private val domainBlock = HashSet<String>()
    private val domainAllow = HashSet<String>()
    private val blockIndex = HashMap<Int, MutableList<AdBlockRule>>()
    private val allowIndex = HashMap<Int, MutableList<AdBlockRule>>()
    private val shortBlock = ArrayList<AdBlockRule>()
    private val shortAllow = ArrayList<AdBlockRule>()
    private val cosmeticRules = ArrayList<AdBlockRule>()

    private var indexDirty = true

    @Volatile
    var blockedCount: Int = 0
        private set

    private val blockedHosts = LinkedHashMap<String, Int>()

    @Synchronized
    fun blockedHostStats(): List<Pair<String, Int>> =
        blockedHosts.entries.sortedByDescending { it.value }.take(10).map { it.key to it.value }

    @Volatile
    var enabled: Boolean = true
        set(value) {
            synchronized(this) {
                field = value
                if (!value && !privacyEnabled) {
                    blockedCount = 0
                    blockedHosts.clear()
                }
            }
        }

    /** 隐私规则独立开关：关闭广告拦截不影响隐私拦截。 */
    @Volatile
    var privacyEnabled: Boolean = false
        set(value) {
            synchronized(this) {
                field = value
                if (!value) blockedByCategory.clear()
            }
        }

    private val blockedByCategory = LinkedHashMap<String, Int>()

    @Synchronized
    fun blockedCategoryStats(): List<Pair<String, Int>> =
        blockedByCategory.entries.sortedByDescending { it.value }.map { it.key to it.value }

    @Synchronized
    fun resetPrivacyStats() {
        blockedByCategory.clear()
    }

    val customRuleCount: Int get() = customRules.size
    val subscriptionRuleCount: Int get() = subscriptionRules.size

    @Synchronized
    fun addCustomRule(line: String) {
        AdBlockRule.parse(line)?.let {
            customRules.add(it)
            indexDirty = true
        }
    }

    @Synchronized
    fun removeCustomRule(raw: String) {
        if (customRules.removeAll { it.raw == raw }) indexDirty = true
    }

    @Synchronized
    fun clearCustomRules() {
        customRules.clear()
        indexDirty = true
    }

    @Synchronized
    fun customRuleTexts(): List<String> = customRules.map { it.raw }

    @Synchronized
    fun setSubscriptionRules(lines: List<String>) {
        subscriptionRules.removeAll { it.privacyCategory == null }
        lines.forEach { AdBlockRule.parse(it)?.let { rule -> subscriptionRules.add(rule) } }
        indexDirty = true
    }

    /** 用分类标签替换某一类隐私规则；规则仍与广告规则共用索引。 */
    @Synchronized
    fun setPrivacyRules(category: String, lines: List<String>) {
        subscriptionRules.removeAll { it.privacyCategory == category }
        lines.forEach { line ->
            AdBlockRule.parse(line, category)?.let { subscriptionRules.add(it) }
        }
        indexDirty = true
    }

    @Synchronized
    fun clearPrivacyRules() {
        if (subscriptionRules.removeAll { it.privacyCategory != null }) indexDirty = true
    }

    @Synchronized
    fun hasRules(): Boolean = customRules.isNotEmpty() || subscriptionRules.isNotEmpty()

    /** 单站覆盖：显式开启优先于显式关闭，更长域名优先于父域名。 */
    @Synchronized
    fun setSiteOverrides(enabledHosts: Set<String>, disabledHosts: Set<String>) {
        siteEnabled.clear()
        siteDisabled.clear()
        enabledHosts.mapTo(siteEnabled) { it.lowercase() }
        disabledHosts.mapTo(siteDisabled) { it.lowercase() }
    }

    /** 旧入口：只有 URL 时按普通子资源匹配。 */
    fun shouldBlock(url: String): Boolean =
        shouldBlock(url, AdBlockRule.TYPE_OTHER, hostOf(url), false)

    /** WebView 请求入口：带资源类型、页面域名和第三方标记。 */
    @Synchronized
    fun shouldBlock(
        url: String,
        request: WebResourceRequest,
        pageUrl: String?
    ): Boolean {
        // Referer 更接近实际发起请求的文档（iframe/中转页），用于规则判断；
        // 单站开关仍按标签页的顶层域名生效。
        val referer = request.requestHeaders?.get("Referer")
        val pageHost = hostOf(referer ?: pageUrl)
        val siteHost = hostOf(pageUrl)
        val requestHost = hostOf(url)
        val thirdParty = pageHost.isNotEmpty() && requestHost.isNotEmpty() &&
            !isSameSite(requestHost, pageHost)
        return shouldBlock(url, requestType(request), pageHost, thirdParty, siteHost)
    }

    @Synchronized
    fun shouldBlock(
        url: String,
        type: Int,
        pageHost: String?,
        thirdParty: Boolean
    ): Boolean = shouldBlock(url, type, pageHost, thirdParty, pageHost)

    @Synchronized
    private fun shouldBlock(
        url: String,
        type: Int,
        pageHost: String?,
        thirdParty: Boolean,
        siteHost: String?
    ): Boolean {
        if (!isAdBlockActive(siteHost) && !privacyEnabled) return false
        if (customRules.isEmpty() && subscriptionRules.isEmpty()) return false
        rebuildIndexIfNeeded()
        if (domainBlock.isEmpty() && blockIndex.isEmpty() && shortBlock.isEmpty()) return false

        val adActive = isAdBlockActive(siteHost)
        val privacyActive = privacyEnabled
        val host = hostOf(url)
        if (matchesAllow(url, type, pageHost, thirdParty, host, adActive, privacyActive)) {
            return false
        }
        val category = matchesBlockCategory(
            url, type, pageHost, thirdParty, host, adActive, privacyActive
        ) ?: return false
        blockedCount++
        if (category.isNotEmpty()) {
            blockedByCategory[category] = (blockedByCategory[category] ?: 0) + 1
        }
        val key = host.ifEmpty { pageHost.orEmpty() }
        if (key.isNotEmpty() && (blockedHosts.size < 50 || blockedHosts.containsKey(key))) {
            blockedHosts[key] = (blockedHosts[key] ?: 0) + 1
        }
        return true
    }

    private fun matchesAllow(
        url: String,
        type: Int,
        pageHost: String?,
        thirdParty: Boolean,
        host: String,
        adActive: Boolean,
        privacyActive: Boolean
    ): Boolean {
        if (adActive && domainAllow.isNotEmpty() && matchesDomain(domainAllow, host)) return true
        val candidates = candidates(allowIndex, shortAllow, url.lowercase())
        return candidates.any {
            isRuleActive(it, adActive, privacyActive) &&
                it.matchesRequest(url, type, pageHost, thirdParty)
        }
    }

    private fun matchesBlockCategory(
        url: String,
        type: Int,
        pageHost: String?,
        thirdParty: Boolean,
        host: String,
        adActive: Boolean,
        privacyActive: Boolean
    ): String? {
        if (adActive && domainBlock.isNotEmpty() && matchesDomain(domainBlock, host)) return ""
        val candidates = candidates(blockIndex, shortBlock, url.lowercase())
        for (rule in candidates) {
            if (!isRuleActive(rule, adActive, privacyActive)) continue
            if (rule.matchesRequest(url, type, pageHost, thirdParty)) {
                return rule.privacyCategory ?: ""
            }
        }
        return null
    }

    private fun isRuleActive(rule: AdBlockRule, adActive: Boolean, privacyActive: Boolean): Boolean =
        if (rule.privacyCategory == null) adActive else privacyActive

    private fun matchesDomain(set: Set<String>, host: String): Boolean {
        if (host.isEmpty()) return false
        var current = host
        while (true) {
            if (current in set) return true
            val dot = current.indexOf('.')
            if (dot < 0) return false
            current = current.substring(dot + 1)
        }
    }

    /** 全局开关 + 单站覆盖：返回当前页面最终是否启用广告拦截。 */
    private fun isAdBlockActive(pageHost: String?): Boolean {
        val host = pageHost.orEmpty().lowercase()
        if (host.isEmpty()) return enabled
        var bestLength = -1
        var override: Boolean? = null
        for (candidate in siteEnabled) {
            if (matchesSite(host, candidate) && candidate.length > bestLength) {
                bestLength = candidate.length
                override = true
            }
        }
        for (candidate in siteDisabled) {
            if (matchesSite(host, candidate) && candidate.length > bestLength) {
                bestLength = candidate.length
                override = false
            }
        }
        return override ?: enabled
    }

    private fun matchesSite(host: String, site: String): Boolean =
        host == site || host.endsWith(".$site") || site.endsWith(".$host")

    private fun candidates(
        index: Map<Int, List<AdBlockRule>>,
        short: List<AdBlockRule>,
        lowerUrl: String
    ): Set<AdBlockRule> {
        val out = LinkedHashSet<AdBlockRule>()
        out.addAll(short)
        var i = 0
        while (i + 5 <= lowerUrl.length) {
            index[hash5(lowerUrl, i)]?.let { out.addAll(it) }
            i++
        }
        return out
    }

    /** 5 字符窗口哈希，避免每个请求生成大量 substring。 */
    private fun hash5(s: String, start: Int): Int {
        var h = 0
        var i = start
        val end = start + 5
        while (i < end) {
            h = h * 31 + s[i].code
            i++
        }
        return h
    }

    private fun rebuildIndexIfNeeded() {
        if (!indexDirty) return
        indexDirty = false
        domainBlock.clear()
        domainAllow.clear()
        blockIndex.clear()
        allowIndex.clear()
        shortBlock.clear()
        shortAllow.clear()
        cosmeticRules.clear()
        customRules.forEach(::indexRule)
        subscriptionRules.forEach(::indexRule)
    }

    private fun indexRule(rule: AdBlockRule) {
        if (rule.isCosmetic) {
            cosmeticRules.add(rule)
            return
        }
        val unconstrained = rule.typeMask == 0 && rule.excludedTypes == 0 &&
            rule.thirdPartyOnly == null && rule.domainInclude.isEmpty() &&
            rule.domainExclude.isEmpty()
        if (rule.isSimpleDomain && unconstrained && rule.privacyCategory == null) {
            if (rule.isException) domainAllow.add(rule.patternLower)
            else domainBlock.add(rule.patternLower)
            return
        }
        val index = if (rule.isException) allowIndex else blockIndex
        val short = if (rule.isException) shortAllow else shortBlock
        val chunks = rule.patternLower.split('*', '^', '|').filter { it.length >= 5 }
        if (chunks.isEmpty()) {
            short.add(rule)
            return
        }
        chunks.forEach { chunk ->
            var i = 0
            while (i + 5 <= chunk.length) {
                index.getOrPut(hash5(chunk, i)) { ArrayList() }.add(rule)
                i++
            }
        }
    }

    private fun hostOf(url: String?): String {
        if (url.isNullOrBlank()) return ""
        return runCatching { Uri.parse(url).host }.getOrNull()?.lowercase().orEmpty()
    }

    private fun isSameSite(host: String, pageHost: String): Boolean =
        host == pageHost || host.endsWith(".$pageHost") || pageHost.endsWith(".$host")

    private fun requestType(request: WebResourceRequest): Int {
        if (request.isForMainFrame) return AdBlockRule.TYPE_DOCUMENT
        val headers = request.requestHeaders
        val dest = headers?.get("Sec-Fetch-Dest")?.lowercase()
        when (dest) {
            "script" -> return AdBlockRule.TYPE_SCRIPT
            "style" -> return AdBlockRule.TYPE_STYLESHEET
            "image" -> return AdBlockRule.TYPE_IMAGE
            "font" -> return AdBlockRule.TYPE_FONT
            "audio", "video" -> return AdBlockRule.TYPE_MEDIA
            "iframe", "frame" -> return AdBlockRule.TYPE_SUBDOCUMENT
            "empty" -> return AdBlockRule.TYPE_XHR
            "document" -> return AdBlockRule.TYPE_DOCUMENT
            "websocket" -> return AdBlockRule.TYPE_WEBSOCKET
        }
        if (headers?.get("X-Requested-With") == "XMLHttpRequest") return AdBlockRule.TYPE_XHR
        val path = request.url.path.orEmpty().lowercase()
        val ext = path.substringAfterLast('.', "")
        when (ext) {
            "js", "mjs" -> return AdBlockRule.TYPE_SCRIPT
            "css" -> return AdBlockRule.TYPE_STYLESHEET
            "png", "jpg", "jpeg", "gif", "webp", "bmp", "svg", "ico", "avif" ->
                return AdBlockRule.TYPE_IMAGE
            "woff", "woff2", "ttf", "otf", "ttc" -> return AdBlockRule.TYPE_FONT
            "mp4", "webm", "mp3", "m4a", "ogg", "wav", "flac", "m3u8", "ts" ->
                return AdBlockRule.TYPE_MEDIA
            "json" -> return AdBlockRule.TYPE_XHR
            "html", "htm" -> return AdBlockRule.TYPE_SUBDOCUMENT
        }
        if (request.url.toString().startsWith("ws")) return AdBlockRule.TYPE_WEBSOCKET
        val accept = headers?.get("Accept")?.lowercase().orEmpty()
        return when {
            accept.startsWith("image/") -> AdBlockRule.TYPE_IMAGE
            accept.contains("text/css") -> AdBlockRule.TYPE_STYLESHEET
            accept.contains("javascript") -> AdBlockRule.TYPE_SCRIPT
            accept.startsWith("font/") -> AdBlockRule.TYPE_FONT
            accept.startsWith("audio/") || accept.startsWith("video/") -> AdBlockRule.TYPE_MEDIA
            accept.contains("application/json") -> AdBlockRule.TYPE_XHR
            else -> AdBlockRule.TYPE_OTHER
        }
    }

    @Synchronized
    fun allRules(): List<AdBlockRule> = customRules + subscriptionRules

    /** 生成当前域名的元素隐藏 CSS；`#@#` 例外优先。 */
    @Synchronized
    fun cosmeticCssFor(host: String?): String {
        rebuildIndexIfNeeded()
        if (cosmeticRules.isEmpty()) return ""
        val exceptions = cosmeticRules
            .filter { (it.privacyCategory == null || privacyEnabled) && it.isException && it.appliesTo(host) }
            .map { it.selector }
            .toSet()
        val selectors = cosmeticRules
            .filter { (it.privacyCategory == null || privacyEnabled) && !it.isException && it.appliesTo(host) }
            .map { it.selector }
            .filter { it !in exceptions }
            .distinct()
        if (selectors.isEmpty()) return ""
        return selectors.chunked(100).joinToString("\n") { chunk ->
            chunk.joinToString(",") + "{display:none!important}"
        }
    }
}

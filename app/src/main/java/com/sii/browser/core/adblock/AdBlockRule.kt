// SPDX-License-Identifier: LicenseRef-Sii-NC
// Copyright (c) 2026 wanwuwww
package com.sii.browser.core.adblock

import android.net.Uri

/**
 * ABP 网络过滤规则 / 元素隐藏规则。
 *
 * 网络规则保留 `$` 选项语义：资源类型、第三方、domain 包含/排除，以及 `*`、`^`、`|`
 * 通配符和锚点。主文档只有显式 `$document` 规则才参与匹配。
 */
class AdBlockRule private constructor(
    val raw: String,
    val pattern: String,
    val isException: Boolean,
    val isDomainRule: Boolean,
    val isCosmetic: Boolean = false,
    val domains: List<String> = emptyList(),
    val excludeDomains: List<String> = emptyList(),
    val selector: String = "",
    val typeMask: Int = 0,
    val excludedTypes: Int = 0,
    val thirdPartyOnly: Boolean? = null,
    val domainInclude: List<String> = emptyList(),
    val domainExclude: List<String> = emptyList(),
    val startAnchor: Boolean = false,
    val endAnchor: Boolean = false,
    val privacyCategory: String? = null
) {
    val patternLower: String = pattern.lowercase()
    val hasWildcard: Boolean = pattern.any { it == '*' || it == '^' }
    val isSimpleDomain: Boolean =
        isDomainRule && !pattern.contains('/') && !pattern.contains('*') && !pattern.contains('^')

    /** 元素隐藏规则是否适用于当前域名。 */
    fun appliesTo(host: String?): Boolean {
        if (!isCosmetic) return false
        val target = host?.lowercase().orEmpty()
        if (excludeDomains.any { matchesDomain(target, it) }) return false
        return domains.isEmpty() || domains.any { matchesDomain(target, it) }
    }

    fun matchesRequest(
        url: String,
        type: Int,
        pageHost: String?,
        thirdParty: Boolean
    ): Boolean {
        if (isCosmetic) return false
        if (type == TYPE_DOCUMENT) {
            if (typeMask and TYPE_DOCUMENT == 0) return false
        } else if (typeMask != 0 && typeMask and type == 0) {
            return false
        }
        if (excludedTypes and type != 0) return false
        if (thirdPartyOnly != null && thirdParty != thirdPartyOnly) return false
        if (domainInclude.isNotEmpty() &&
            !domainInclude.any { matchesDomain(pageHost.orEmpty(), it) }
        ) return false
        if (domainExclude.any { matchesDomain(pageHost.orEmpty(), it) }) return false
        if (isSimpleDomain) {
            val host = Uri.parse(url).host?.lowercase() ?: return false
            return host == patternLower || host.endsWith(".$patternLower")
        }
        if (isDomainRule) return url.contains(pattern, ignoreCase = true)
        return matchesUrl(url)
    }

    private fun matchesUrl(url: String): Boolean {
        if (!hasWildcard) {
            if (startAnchor && endAnchor) return url.equals(pattern, true)
            if (startAnchor) return url.startsWith(pattern, true)
            if (endAnchor) return url.endsWith(pattern, true)
            return url.contains(pattern, true)
        }
        var p = patternLower
        if (!startAnchor && !p.startsWith("*")) p = "*$p"
        if (!endAnchor && !p.endsWith("*")) p = "$p*"
        return wildcardMatch(url.lowercase(), p)
    }

    private fun wildcardMatch(text: String, pattern: String): Boolean {
        var ti = 0
        var pi = 0
        var star = -1
        var mark = 0
        while (ti < text.length) {
            if (pi < pattern.length) {
                val pc = pattern[pi]
                if (pc == '^') {
                    if (isSeparator(text[ti])) {
                        ti++
                        pi++
                        continue
                    }
                } else if (pc == text[ti]) {
                    ti++
                    pi++
                    continue
                }
            }
            if (pi < pattern.length && pattern[pi] == '*') {
                star = pi
                pi++
                mark = ti
                continue
            }
            if (star < 0) return false
            pi = star + 1
            ti = ++mark
        }
        while (pi < pattern.length && (pattern[pi] == '*' || pattern[pi] == '^')) pi++
        return pi == pattern.length
    }

    private fun isSeparator(c: Char): Boolean =
        !(c.isLetterOrDigit() || c == '_' || c == '-' || c == '.' || c == '%')

    private fun matchesDomain(host: String, domain: String): Boolean {
        if (host.isEmpty() || domain.isEmpty()) return false
        return host == domain || host.endsWith(".$domain")
    }

    companion object {
        const val TYPE_SCRIPT = 1 shl 0
        const val TYPE_IMAGE = 1 shl 1
        const val TYPE_STYLESHEET = 1 shl 2
        const val TYPE_OBJECT = 1 shl 3
        const val TYPE_XHR = 1 shl 4
        const val TYPE_SUBDOCUMENT = 1 shl 5
        const val TYPE_DOCUMENT = 1 shl 6
        const val TYPE_MEDIA = 1 shl 7
        const val TYPE_FONT = 1 shl 8
        const val TYPE_WEBSOCKET = 1 shl 9
        const val TYPE_POPUP = 1 shl 10
        const val TYPE_OTHER = 1 shl 11

        fun parse(line: String): AdBlockRule? = parse(line, null)

        fun parse(line: String, privacyCategory: String?): AdBlockRule? {
            val trimmed = line.trim()
            if (trimmed.isEmpty() || trimmed.startsWith("!") || trimmed.startsWith("[")) return null

            val cosmeticAt = trimmed.indexOf("##")
            val exceptionAt = trimmed.indexOf("#@#")
            if (cosmeticAt >= 0 || exceptionAt >= 0) return parseCosmetic(trimmed, privacyCategory)

            val isException = trimmed.startsWith("@@")
            val body = if (isException) trimmed.substring(2) else trimmed
            if (body.isEmpty()) return null

            val optionAt = body.indexOf('$')
            val patternPart = if (optionAt >= 0) body.substring(0, optionAt) else body
            val options = if (optionAt >= 0) {
                body.substring(optionAt + 1).split(',').map { it.trim().lowercase() }
                    .filter { it.isNotEmpty() }
            } else emptyList()

            var typeMask = 0
            var excludedTypes = 0
            var thirdPartyOnly: Boolean? = null
            val domainInclude = mutableListOf<String>()
            val domainExclude = mutableListOf<String>()
            for (option in options) {
                when {
                    option == "third-party" -> thirdPartyOnly = true
                    option == "~third-party" -> thirdPartyOnly = false
                    option.startsWith("domain=") -> {
                        option.substring(7).split('|').filter { it.isNotEmpty() }.forEach {
                            if (it.startsWith("~")) domainExclude.add(it.substring(1))
                            else domainInclude.add(it)
                        }
                    }
                    option.startsWith("~") -> {
                        val bit = typeBit(option.substring(1)) ?: return null
                        excludedTypes = excludedTypes or bit
                    }
                    else -> {
                        val bit = typeBit(option) ?: return null
                        typeMask = typeMask or bit
                    }
                }
            }

            var pattern = patternPart
            var isDomain = false
            var startAnchor = false
            if (pattern.startsWith("||")) {
                isDomain = true
                startAnchor = true
                pattern = pattern.substring(2)
            } else if (pattern.startsWith("|")) {
                startAnchor = true
                pattern = pattern.substring(1)
            }
            var endAnchor = false
            if (pattern.endsWith("|")) {
                endAnchor = true
                pattern = pattern.dropLast(1)
            }
            if (isDomain && pattern.endsWith("^")) pattern = pattern.dropLast(1)
            if (pattern.isEmpty()) return null

            return AdBlockRule(
                raw = trimmed,
                pattern = pattern,
                isException = isException,
                isDomainRule = isDomain,
                typeMask = typeMask,
                excludedTypes = excludedTypes,
                thirdPartyOnly = thirdPartyOnly,
                domainInclude = domainInclude,
                domainExclude = domainExclude,
                startAnchor = startAnchor,
                endAnchor = endAnchor,
                privacyCategory = privacyCategory
            )
        }

        private fun parseCosmetic(trimmed: String, privacyCategory: String?): AdBlockRule? {
            val exception = trimmed.contains("#@#")
            val marker = if (exception) "#@#" else "##"
            val at = trimmed.indexOf(marker)
            if (at < 0) return null
            val selector = trimmed.substring(at + marker.length).trim()
            if (!validSelector(selector)) return null
            val rawDomains = trimmed.substring(0, at)
                .split(',')
                .map { it.trim() }
                .filter { it.isNotEmpty() }
            val domains = rawDomains.filter { !it.startsWith("~") }
            val excludes = rawDomains.filter { it.startsWith("~") }.map { it.substring(1) }
            return AdBlockRule(
                raw = trimmed,
                pattern = "",
                isException = exception,
                isDomainRule = false,
                isCosmetic = true,
                domains = domains,
                excludeDomains = excludes,
                selector = selector,
                privacyCategory = privacyCategory
            )
        }

        private fun validSelector(selector: String): Boolean {
            if (selector.isEmpty()) return false
            if (selector.any { it == '{' || it == '}' || it == ';' || it == '!' || it == '&' || it == '?' }) {
                return false
            }
            val unsupported = listOf(
                ":has-text(", ":matches-attr(", ":matches-css(", ":matches-css-before(",
                ":matches-css-after(", ":matches-media(", ":matches-path(", ":min-text-length(",
                ":upward(", ":watch-attr(", ":xpath(", ":remove(", ":style(", ":remove-attr(",
                ":remove-class("
            )
            if (unsupported.any { selector.contains(it) }) return false
            var square = 0
            var round = 0
            var quote = 0.toChar()
            var i = 0
            while (i < selector.length) {
                val c = selector[i]
                if (c == '\\') {
                    i += 2
                    continue
                }
                if (quote != 0.toChar()) {
                    if (c == quote) quote = 0.toChar()
                } else {
                    when (c) {
                        '\'', '"' -> quote = c
                        '[' -> square++
                        ']' -> if (square == 0) return false else square--
                        '(' -> round++
                        ')' -> if (round == 0) return false else round--
                    }
                }
                i++
            }
            return quote == 0.toChar() && square == 0 && round == 0
        }

        private fun typeBit(option: String): Int? = when (option) {
            "script" -> TYPE_SCRIPT
            "image", "background" -> TYPE_IMAGE
            "stylesheet" -> TYPE_STYLESHEET
            "object", "object-subrequest" -> TYPE_OBJECT
            "xmlhttprequest" -> TYPE_XHR
            "subdocument" -> TYPE_SUBDOCUMENT
            "document" -> TYPE_DOCUMENT
            "media" -> TYPE_MEDIA
            "font" -> TYPE_FONT
            "websocket" -> TYPE_WEBSOCKET
            "popup" -> TYPE_POPUP
            "other", "dtd", "xbl", "ping", "csp", "elemhide", "generichide", "genericblock" ->
                TYPE_OTHER
            else -> null
        }
    }
}

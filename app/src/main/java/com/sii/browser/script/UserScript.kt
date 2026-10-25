package com.sii.browser.script

/**
 * UserScript 元数据解析。
 *
 * 支持：@name、@namespace、@author、@version、@description、
 * @match/@include、@exclude-match/@exclude、@run-at、@downloadURL。
 */
data class UserScript(
    val name: String,
    val author: String?,
    val downloadUrl: String?,
    val matches: List<String>,
    val excludes: List<String>,
    val runAt: String,
    val requires: List<String>,
    val grants: List<String>,
    val code: String
) {
    companion object {
        private const val RUN_AT_START = "document-start"
        private const val RUN_AT_END = "document-end"

        fun parse(source: String): UserScript {
            val name = metaValue(source, "@name") ?: "未命名脚本"
            val author = metaValue(source, "@author")
            val downloadUrl = metaValue(source, "@downloadURL")
            val runAt = metaValue(source, "@run-at") ?: RUN_AT_END
            val matches = metaValues(source, "@match") + metaValues(source, "@include")
            val excludes = metaValues(source, "@exclude-match") + metaValues(source, "@exclude")
            val requires = metaValues(source, "@require")
            val grants = metaValues(source, "@grant")
            return UserScript(
                name = name,
                author = author,
                downloadUrl = downloadUrl,
                matches = matches,
                excludes = excludes,
                runAt = runAt,
                requires = requires,
                grants = grants,
                code = source
            )
        }

        private fun metaValue(source: String, key: String): String? =
            metaValues(source, key).firstOrNull()

        private fun metaValues(source: String, key: String): List<String> =
            source.lineSequence()
                .map { it.trim() }
                .filter { it.startsWith("//") && it.contains(key) }
                .mapNotNull { line ->
                    val index = line.indexOf(key)
                    val value = line.substring(index + key.length).trim()
                    value.ifEmpty { null }
                }
                .toList()
    }

    /** 是否在指定 URL 生效（@match 为空视为全部生效）。 */
    fun matchesUrl(url: String): Boolean {
        if (excludes.any { matchPattern(it, url) }) return false
        if (matches.isEmpty()) return true
        return matches.any { matchPattern(it, url) }
    }

    private fun matchPattern(pattern: String, url: String): Boolean {
        if (pattern == "*" || pattern.isEmpty()) return true
        // 简化通配：* 转 .*，其余按字面匹配
        val regex = pattern
            .replace(".", "\\.")
            .replace("*", ".*")
        return Regex(regex).containsMatchIn(url)
    }
}

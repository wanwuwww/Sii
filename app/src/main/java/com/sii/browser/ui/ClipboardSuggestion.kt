// SPDX-License-Identifier: LicenseRef-Sii-NC
// Copyright (c) 2026 wanwuwww
package com.sii.browser.ui

import android.content.ClipboardManager
import android.content.Context

/** 剪贴板建议的解析：纯文字、纯链接、文字+链接三种。 */
object ClipboardSuggestion {
    private val urlPattern = Regex("""https?://[^\s<>"']+""", RegexOption.IGNORE_CASE)
    private val whitespace = Regex("\\s+")

    fun readAll(context: Context): List<Suggestion> {
        val manager = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
            ?: return emptyList()
        val clip = manager.primaryClip?.takeIf { it.itemCount > 0 } ?: return emptyList()
        val raw = clip.getItemAt(0).coerceToText(context)?.toString()?.trim().orEmpty()
        if (raw.isEmpty() || raw.length > 4096) return emptyList()

        val links = urlPattern.findAll(raw)
            .map { it.value.trimEnd('.', ',', ';', '!', '?', ')', ']', '}', '。', '，', '；') }
            .filter { it.isNotBlank() }
            .distinct()
            .take(8)
            .toList()
        if (links.isEmpty()) {
            return listOf(
                Suggestion(
                    title = raw.replace(whitespace, " ").take(500),
                    value = raw,
                    kind = SuggestionKind.CLIPBOARD_TEXT
                )
            )
        }

        val textPart = urlPattern.replace(raw, " ")
            .replace(whitespace, " ")
            .trim()
            .take(500)
        val result = mutableListOf<Suggestion>()
        if (textPart.isNotEmpty()) {
            result.add(
                Suggestion(
                    title = textPart,
                    value = raw,
                    kind = SuggestionKind.CLIPBOARD_MIXED,
                    subtitle = "文字 + ${links.size} 个链接"
                )
            )
            result.add(
                Suggestion(
                    title = textPart,
                    value = textPart,
                    kind = SuggestionKind.CLIPBOARD_TEXT,
                    subtitle = "只填入文字"
                )
            )
        }
        links.forEachIndexed { index, link ->
            result.add(
                Suggestion(
                    title = if (links.size == 1) link else "打开链接 ${index + 1}",
                    value = link,
                    kind = SuggestionKind.CLIPBOARD_LINK,
                    subtitle = if (links.size == 1) null else link
                )
            )
        }
        return result
    }
}

/** 按“离搜索框最近”的分类顺序合并建议，并限制每类数量。 */
fun sortSuggestions(items: List<Suggestion>, orderRaw: String, limit: Int): List<Suggestion> {
    val defaults = listOf("clipboard", "history", "bookmark", "search")
    val order = (orderRaw.split(',').map { it.trim() } + defaults).distinct()
    val result = ArrayList<Suggestion>(limit)
    for (category in order) {
        val categoryItems = items.filter { it.kind.categoryKey == category }
        if (categoryItems.isEmpty()) continue
        val cap = when (category) {
            "clipboard" -> 8
            "history", "bookmark" -> 3
            else -> limit
        }
        result.addAll(categoryItems.take(cap))
        if (result.size >= limit) break
    }
    return result.take(limit)
}

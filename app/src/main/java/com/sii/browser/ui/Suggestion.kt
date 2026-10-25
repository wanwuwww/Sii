package com.sii.browser.ui

enum class SuggestionKind {
    HISTORY,
    BOOKMARK,
    SEARCH,
    CLIPBOARD_TEXT,
    CLIPBOARD_LINK,
    CLIPBOARD_MIXED;

    val categoryKey: String
        get() = when (this) {
            HISTORY -> "history"
            BOOKMARK -> "bookmark"
            SEARCH -> "search"
            CLIPBOARD_TEXT, CLIPBOARD_LINK, CLIPBOARD_MIXED -> "clipboard"
        }
}

/** 搜索建议：title 用于展示，value 用于填入/跳转。 */
data class Suggestion(
    val title: String,
    val value: String,
    val kind: SuggestionKind = SuggestionKind.SEARCH,
    val subtitle: String? = null
)

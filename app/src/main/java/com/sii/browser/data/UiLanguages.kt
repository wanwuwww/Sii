// SPDX-License-Identifier: LicenseRef-Sii-NC
// Copyright (c) 2026 wanwuwww
package com.sii.browser.data

/**
 * 界面语言预设：只有已补齐资源的语言可选，其余标为翻译中。
 */
object UiLanguages {

    data class Entry(val tag: String, val label: String, val translated: Boolean)

    val ALL: List<Entry> = listOf(
        Entry("", "", true),
        Entry("zh-CN", "简体中文", true),
        Entry("en", "English", true),
        Entry("zh-TW", "繁體中文", true),
        Entry("ja", "日本語", true),
        Entry("ko", "한국어", false),
        Entry("fr", "Français", false),
        Entry("de", "Deutsch", false),
        Entry("es", "Español", false)
    )
}

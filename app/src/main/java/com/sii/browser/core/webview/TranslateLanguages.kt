// SPDX-License-Identifier: LicenseRef-Sii-NC
// Copyright (c) 2026 wanwuwww
package com.sii.browser.core.webview

import com.google.mlkit.nl.translate.TranslateLanguage

/**
 * 翻译语言表：界面显示名、BCP-47 tag、ML Kit 模型码、百度语言码的唯一来源。
 * [toolVisible] 控制是否出现在文本翻译页（繁體中文与简体共用 ML Kit 模型，不在工具页单列）。
 */
object TranslateLanguages {

    data class Lang(
        val label: String,
        val tag: String,
        val mlkit: String,
        val baidu: String,
        val toolVisible: Boolean = true
    )

    val ALL: List<Lang> = listOf(
        Lang("中文", "zh-CN", TranslateLanguage.CHINESE, "zh"),
        Lang("繁體中文", "zh-TW", TranslateLanguage.CHINESE, "cht", toolVisible = false),
        Lang("English", "en", TranslateLanguage.ENGLISH, "en"),
        Lang("日本語", "ja", TranslateLanguage.JAPANESE, "jp"),
        Lang("한국어", "ko", TranslateLanguage.KOREAN, "kor"),
        Lang("Français", "fr", TranslateLanguage.FRENCH, "fra"),
        Lang("Deutsch", "de", TranslateLanguage.GERMAN, "de"),
        Lang("Español", "es", TranslateLanguage.SPANISH, "spa"),
        Lang("Русский", "ru", TranslateLanguage.RUSSIAN, "ru"),
        Lang("Português", "pt", TranslateLanguage.PORTUGUESE, "pt"),
        Lang("Italiano", "it", TranslateLanguage.ITALIAN, "it"),
        Lang("ไทย", "th", TranslateLanguage.THAI, "th"),
        Lang("Tiếng Việt", "vi", TranslateLanguage.VIETNAMESE, "vie"),
        Lang("العربية", "ar", TranslateLanguage.ARABIC, "ara"),
        Lang("हिन्दी", "hi", TranslateLanguage.HINDI, "hi"),
        Lang("Türkçe", "tr", TranslateLanguage.TURKISH, "tr"),
        Lang("Nederlands", "nl", TranslateLanguage.DUTCH, "nl"),
        Lang("Polski", "pl", TranslateLanguage.POLISH, "pl"),
        Lang("Bahasa Indonesia", "id", TranslateLanguage.INDONESIAN, "id"),
        Lang("Українська", "uk", TranslateLanguage.UKRAINIAN, "uk"),
        Lang("Română", "ro", TranslateLanguage.ROMANIAN, "ro")
    )

    val TOOL: List<Lang> = ALL.filter { it.toolVisible }

    fun byTag(tag: String?): Lang? {
        val value = tag?.trim()?.lowercase().orEmpty()
        if (value.isEmpty()) return null
        ALL.firstOrNull { it.tag.lowercase() == value }?.let { return it }
        return ALL.firstOrNull { it.tag.substringBefore('-') == value.substringBefore('-') }
    }

    fun mlkit(tag: String?): String? = byTag(tag)?.mlkit

    fun baidu(tag: String?): String? = byTag(tag)?.baidu

    fun label(tag: String?): String = byTag(tag)?.label ?: tag.orEmpty()
}

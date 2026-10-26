// SPDX-License-Identifier: LicenseRef-Sii-NC
// Copyright (c) 2026 wanwuwww
package com.sii.browser.ui

import com.sii.browser.R

/** 菜单项目录：设置页、定制预览和实际菜单共用。 */
object MenuCatalog {
    data class Item(val id: String, val labelRes: Int, val iconRes: Int = R.drawable.ic_m_settings)

    val ITEMS = listOf(
        Item("refresh", R.string.menu_refresh, R.drawable.ic_m_refresh),
        Item("home", R.string.menu_home, R.drawable.ic_m_home),
        Item("addbookmark", R.string.menu_add_bookmark, R.drawable.ic_m_bookmark_add),
        Item("bookmarks", R.string.menu_bookmarks, R.drawable.ic_m_bmlist),
        Item("downloads", R.string.menu_downloads, R.drawable.ic_m_download),
        Item("netlog", R.string.settings_netlog, R.drawable.ic_m_netlog),
        Item("jsconsole", R.string.menu_js_console, R.drawable.ic_m_code),
        Item("history", R.string.menu_history, R.drawable.ic_m_history),
        Item("adblockstats", R.string.menu_adblock_stats, R.drawable.ic_m_shield_check),
        Item("find", R.string.menu_find, R.drawable.ic_m_find),
        Item("font", R.string.menu_font_size, R.drawable.ic_m_font),
        Item("uiscale", R.string.menu_ui_scale, R.drawable.ic_m_zoom),
        Item("share", R.string.menu_share, R.drawable.ic_m_share),
        Item("copylink", R.string.menu_copy_link, R.drawable.ic_m_copy),
        Item("settings", R.string.menu_settings, R.drawable.ic_m_settings),
        Item("reader", R.string.menu_reader, R.drawable.ic_m_reader),
        Item("sniff", R.string.menu_sniff, R.drawable.ic_m_sniff),
        Item("scan", R.string.menu_scan, R.drawable.ic_m_scan),
        Item("readaloud", R.string.menu_read_aloud, R.drawable.ic_m_speaker),
        Item("savepage", R.string.menu_save_page, R.drawable.ic_m_save_page),
        Item("readlaterlist", R.string.menu_read_later, R.drawable.ic_m_read_later),
        Item("translatepage", R.string.menu_translate_page, R.drawable.ic_m_translate),
        Item("source", R.string.menu_source, R.drawable.ic_m_code),
        Item("print", R.string.menu_print, R.drawable.ic_m_print),
        Item("passwords", R.string.menu_passwords, R.drawable.ic_m_key),
        Item("scriptmenu", R.string.menu_script_commands, R.drawable.ic_m_script),
        Item("addhome", R.string.menu_add_home, R.drawable.ic_m_home_add),
        Item("cleardata", R.string.menu_clear_data, R.drawable.ic_m_clear_data),
        Item("adpick", R.string.menu_ad_pick, R.drawable.ic_m_pick),
        Item("closetabs", R.string.menu_close_all_tabs, R.drawable.ic_close),
        Item("dark", R.string.menu_no_night, R.drawable.ic_m_dark),
        Item("incog", R.string.menu_incognito, R.drawable.ic_m_incog),
        Item("noimg", R.string.menu_no_img, R.drawable.ic_m_noimg),
        Item("adblock", R.string.adblock_master, R.drawable.ic_m_shield),
        Item("desktop", R.string.menu_desktop, R.drawable.ic_m_desktop),
        Item("newtab", R.string.menu_new_tab, R.drawable.ic_m_new_tab),
        Item("closeothers", R.string.menu_close_others, R.drawable.ic_close),
        Item("recentclosed", R.string.menu_recent_closed, R.drawable.ic_m_history)
    )

    val DEFAULT_FIXED = listOf("dark", "incog", "noimg", "desktop")

    fun label(context: android.content.Context, id: String): String =
        ITEMS.firstOrNull { it.id == id }?.let { context.getString(it.labelRes) } ?: id
}

// SPDX-License-Identifier: LicenseRef-Sii-NC
// Copyright (c) 2026 wanwuwww
package com.sii.browser.data

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.json.JSONObject

private val Context.siiDataStore by preferencesDataStore(name = "sii_settings")

/**
 * 设置实现（DataStore Preferences + 内存缓存）。
 * 读走同步内存缓存（界面生命周期点需要同步值）；写立即更新缓存并异步持久化。
 */
class DataStoreSettings(private val context: Context) : Settings {

    private val dataStore = context.siiDataStore
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val cache = java.util.concurrent.ConcurrentHashMap<String, Any>()
    private val mirror = context.getSharedPreferences("sii_settings_mirror", Context.MODE_PRIVATE)

    /** 首帧/启动路由依赖的键：用 SharedPreferences 镜像，避免每次冷启动同步读 DataStore。 */
    private val mirrorKeys = setOf(
        SettingsKeys.AGREEMENT,
        SettingsKeys.LANGUAGE,
        SettingsKeys.NIGHT_MODE,
        SettingsKeys.UI_SCALE,
        SettingsKeys.HOME,
        SettingsKeys.RESTORE_CLOSED_TABS,
        SettingsKeys.INCOG_ACTIVE,
        SettingsKeys.INCOG_SESSION_START,
        SettingsKeys.INCOG_CLEAN_DOWNLOADS,
        SettingsKeys.INCOG_CLEAN_TRANSLATION
    )

    init {
        if (!mirror.getBoolean("migrated", false)) {
            // 首次安装通常还没有 DataStore 文件，直接使用默认值，避免 Application 启动同步阻塞。
            val dataFile = java.io.File(context.filesDir, "datastore/sii_settings.preferences_pb")
            if (dataFile.isFile) {
                runBlocking {
                    dataStore.data.first().asMap().forEach { (key, value) -> cache[key.name] = value }
                }
            }
            mirror.edit().putBoolean("migrated", true).apply()
            writeMirror()
        } else {
            mirror.all.forEach { (key, value) -> if (value != null) cache[key] = value }
            scope.launch {
                runCatching {
                    dataStore.data.first().asMap().forEach { (key, value) ->
                        cache.putIfAbsent(key.name, value)
                    }
                    writeMirror()
                }
            }
        }
    }

    private fun writeMirror() {
        val editor = mirror.edit()
        mirrorKeys.forEach { key ->
            when (val value = cache[key]) {
                is String -> editor.putString(key, value)
                is Int -> editor.putInt(key, value)
                is Boolean -> editor.putBoolean(key, value)
                is Long -> editor.putLong(key, value)
                else -> editor.remove(key)
            }
        }
        editor.apply()
    }

    // ---- 基础读写（缓存 + DataStore）----

    private fun str(key: String, def: String?): String? = cache[key] as? String ?: def

    private fun putStr(key: String, value: String?) {
        if (value == null) cache.remove(key) else cache[key] = value
        if (key in mirrorKeys) writeMirror()
        val prefKey = stringPreferencesKey(key)
        scope.launch {
            dataStore.edit { if (value == null) it.remove(prefKey) else it[prefKey] = value }
        }
    }

    private fun int(key: String, def: Int): Int = cache[key] as? Int ?: def

    private fun putInt(key: String, value: Int) {
        cache[key] = value
        if (key in mirrorKeys) writeMirror()
        val prefKey = intPreferencesKey(key)
        scope.launch { dataStore.edit { it[prefKey] = value } }
    }

    private fun bool(key: String, def: Boolean): Boolean = cache[key] as? Boolean ?: def

    private fun putBool(key: String, value: Boolean) {
        cache[key] = value
        if (key in mirrorKeys) writeMirror()
        val prefKey = booleanPreferencesKey(key)
        scope.launch { dataStore.edit { it[prefKey] = value } }
    }

    private fun long(key: String, def: Long): Long = cache[key] as? Long ?: def

    private fun putLong(key: String, value: Long) {
        cache[key] = value
        if (key in mirrorKeys) writeMirror()
        val prefKey = longPreferencesKey(key)
        scope.launch { dataStore.edit { it[prefKey] = value } }
    }

    // ---- 通用 ----

    override fun language(): String? = str(SettingsKeys.LANGUAGE, null)

    override fun setLanguage(language: String?) {
        putStr(SettingsKeys.LANGUAGE, language)
    }

    /** 1=跟随系统，2=强制亮，3=强制暗。 */
    private fun nightMode(): Int = int(SettingsKeys.NIGHT_MODE, SettingsDefaults.NIGHT_MODE)

    override fun isNightMode(): Boolean = when (nightMode()) {
        3 -> true
        2 -> false
        else -> systemNight()
    }

    override fun nightFollowSystem(): Boolean = nightMode() == 1

    override fun nightForceDark(): Boolean = nightMode() == 3

    override fun setNightFollowSystem(follow: Boolean) {
        putInt(
            SettingsKeys.NIGHT_MODE,
            if (follow) 1 else if (systemNight()) 3 else 2
        )
    }

    override fun setNightMode(value: Int) {
        putInt(SettingsKeys.NIGHT_MODE, value.coerceIn(1, 3))
    }

    override fun toggleNightMode(): Boolean {
        val next = !isNightMode()
        // 手动开关只在「强制暗 3」与「强制亮 2」之间切换，永不回落到「跟随系统 1」；
        // 跟随系统只由设置页「自动跟随系统」开关控制，手动按钮优先级最高。
        putInt(SettingsKeys.NIGHT_MODE, if (next) 3 else 2)
        return next
    }

    private fun systemNight(): Boolean =
        (context.resources.configuration.uiMode and
            android.content.res.Configuration.UI_MODE_NIGHT_MASK) ==
            android.content.res.Configuration.UI_MODE_NIGHT_YES

    override fun agreementVersion(): Int = int(SettingsKeys.AGREEMENT, 0)

    override fun markAgreementDone() {
        putInt(SettingsKeys.AGREEMENT, 1)
    }

    override fun dataVersion(): Int = int(SettingsKeys.DATA_VERSION, 0)

    override fun setDataVersion(version: Int) {
        putInt(SettingsKeys.DATA_VERSION, version)
    }

    override fun ignoredSslWarning(): Int = int(SettingsKeys.IGNORED_SSL_WARNING, 0)

    override fun setIgnoredSslWarning(value: Int) {
        putInt(SettingsKeys.IGNORED_SSL_WARNING, value)
    }

    override fun adBlockedTimes(): Int = int(SettingsKeys.ADBLOCKED_TIMES, 0)

    override fun addAdBlockedTimes(delta: Int) {
        putInt(SettingsKeys.ADBLOCKED_TIMES, adBlockedTimes() + delta)
    }

    override fun adBlockEnabled(): Boolean =
        bool(SettingsKeys.ADBLOCK_ENABLED, SettingsDefaults.ADBLOCK_ENABLED)

    override fun setAdBlockEnabled(value: Boolean) =
        putBool(SettingsKeys.ADBLOCK_ENABLED, value)

    override fun pageRequests(): Boolean =
        bool(SettingsKeys.PAGE_REQUESTS, SettingsDefaults.PAGE_REQUESTS)

    override fun setPageRequests(value: Boolean) =
        putBool(SettingsKeys.PAGE_REQUESTS, value)

    override fun builtinRulesEnabled(): Boolean =
        bool(SettingsKeys.ADBLOCK_BUILTIN, true)

    override fun setBuiltinRulesEnabled(value: Boolean) =
        putBool(SettingsKeys.ADBLOCK_BUILTIN, value)

    override fun subscriptionIntervalDays(): Int = int(SettingsKeys.SUB_INTERVAL, 15)

    override fun setSubscriptionIntervalDays(value: Int) =
        putInt(SettingsKeys.SUB_INTERVAL, value)

    override fun tabBarPosition(): Int = int(SettingsKeys.TAB_POS, 2)

    override fun setTabBarPosition(value: Int) = putInt(SettingsKeys.TAB_POS, value)

    override fun tabCardGrid(): Boolean =
        bool(SettingsKeys.TAB_CARD_GRID, SettingsDefaults.TAB_CARD_GRID)

    override fun tabGridColumns(): Int =
        int(SettingsKeys.TAB_GRID_COLUMNS, SettingsDefaults.TAB_GRID_COLUMNS).coerceIn(2, 3)

    override fun setTabGridColumns(value: Int) =
        putInt(SettingsKeys.TAB_GRID_COLUMNS, value.coerceIn(2, 3))

    override fun tabShowThumb(): Boolean =
        bool(SettingsKeys.TAB_SHOW_THUMB, SettingsDefaults.TAB_SHOW_THUMB)

    override fun setTabShowThumb(value: Boolean) =
        putBool(SettingsKeys.TAB_SHOW_THUMB, value)

    override fun setTabCardGrid(value: Boolean) = putBool(SettingsKeys.TAB_CARD_GRID, value)

    override fun uiStyle(): Int =
        int(SettingsKeys.UI_STYLE, SettingsDefaults.UI_STYLE).coerceIn(0, 1)

    override fun setUiStyle(value: Int) {
        putInt(SettingsKeys.UI_STYLE, value.coerceIn(0, 1))
        // 悬浮胶囊工具栏下不支持主页搜索框置底，切过去时拉回居中。
        if (value == 1 && homeLayoutPos() == 2) {
            putInt(SettingsKeys.HOME_LAYOUT_POS, 1)
        }
    }

    override fun settingsCardAlpha(): Int =
        int(SettingsKeys.SETTINGS_CARD_ALPHA, 100).coerceIn(0, 100)

    override fun setSettingsCardAlpha(value: Int) =
        putInt(SettingsKeys.SETTINGS_CARD_ALPHA, value.coerceIn(0, 100))

    override fun glassStrength(): Int =
        int(SettingsKeys.GLASS_STRENGTH, SettingsDefaults.GLASS_STRENGTH).coerceIn(0, 100)

    override fun setGlassStrength(value: Int) =
        putInt(SettingsKeys.GLASS_STRENGTH, value.coerceIn(0, 100))

    override fun glassBlur(): Boolean =
        bool(SettingsKeys.GLASS_BLUR, SettingsDefaults.GLASS_BLUR)

    override fun setGlassBlur(value: Boolean) =
        putBool(SettingsKeys.GLASS_BLUR, value)

    override fun homeStyle(): Int =
        int(SettingsKeys.HOME_STYLE, SettingsDefaults.HOME_STYLE).coerceIn(0, 2)

    override fun setHomeStyle(value: Int) =
        putInt(SettingsKeys.HOME_STYLE, value.coerceIn(0, 2))

    override fun homeSearchShow(): Boolean =
        bool(SettingsKeys.HOME_SEARCH_SHOW, SettingsDefaults.HOME_SEARCH_SHOW)

    override fun setHomeSearchShow(value: Boolean) =
        putBool(SettingsKeys.HOME_SEARCH_SHOW, value)

    override fun tabsPanelTransparency(): Int =
        int(SettingsKeys.TABS_PANEL_TRANSPARENCY, SettingsDefaults.TABS_PANEL_TRANSPARENCY)
            .coerceIn(0, 100)

    override fun setTabsPanelTransparency(value: Int) =
        putInt(SettingsKeys.TABS_PANEL_TRANSPARENCY, value.coerceIn(0, 100))

    override fun cardBorder(): Boolean = bool(SettingsKeys.CARD_BORDER, true)

    override fun setCardBorder(value: Boolean) = putBool(SettingsKeys.CARD_BORDER, value)

    override fun cardBg(): Int =
        int(SettingsKeys.CARD_BG, SettingsDefaults.CARD_BG).coerceIn(0, 3)

    override fun setCardBg(value: Int) =
        putInt(SettingsKeys.CARD_BG, value.coerceIn(0, 3))

    override fun lastUpdaterCheck(name: String): Long =
        long(SettingsKeys.UPDATER_PREFIX + name, 0L)

    override fun setLastUpdaterCheck(name: String, time: Long) {
        putLong(SettingsKeys.UPDATER_PREFIX + name, time)
    }

    override fun exportToJson(includePrivate: Boolean): JSONObject {
        val json = JSONObject()
        boolKeys().forEach { if (cache.containsKey(it)) json.put(it, bool(it, false)) }
        stringKeys().forEach {
            if (cache.containsKey(it)) {
                val value = str(it, "") ?: ""
                if (!includePrivate || value.length <= 4096) json.put(it, value)
            }
        }
        intKeys().forEach { if (cache.containsKey(it)) json.put(it, int(it, 0)) }
        longKeys().forEach { if (cache.containsKey(it)) json.put(it, long(it, 0L)) }
        return json
    }

    // ---- 外观 ----

    override fun nightCssEnabled(): Boolean = bool(SettingsKeys.NIGHT_CSS, false)

    override fun setNightCssEnabled(enabled: Boolean) {
        putBool(SettingsKeys.NIGHT_CSS, enabled)
    }

    override fun nightFilter(): Int = int(SettingsKeys.NIGHT_FILTER, 0)

    override fun setNightFilter(value: Int) {
        putInt(SettingsKeys.NIGHT_FILTER, value)
    }

    override fun cssTheme(): String? = str(SettingsKeys.CSS_THEME, null)

    override fun setCssTheme(value: String?) {
        putStr(SettingsKeys.CSS_THEME, value)
    }

    override fun textSize(): Int {
        val value = int(SettingsKeys.TEXT_SIZE, SettingsDefaults.TEXT_SIZE)
        if (value > 5) return value
        val mapped = intArrayOf(130, 115, 100, 85, 70).getOrElse(value.coerceIn(1, 3) - 1) { 100 }
        setTextSize(mapped)
        return mapped
    }

    override fun setTextSize(value: Int) {
        putInt(SettingsKeys.TEXT_SIZE, value)
    }

    override fun readerTextSize(): Int = int(SettingsKeys.READER_TEXT_SIZE, 0)

    override fun setReaderTextSize(value: Int) {
        putInt(SettingsKeys.READER_TEXT_SIZE, value)
    }

    override fun readerThemeColor(): Int = int(SettingsKeys.READER_THEME_COLOR, 0)

    override fun setReaderThemeColor(value: Int) {
        putInt(SettingsKeys.READER_THEME_COLOR, value)
    }

    override fun readerCustomCss(): String? = str(SettingsKeys.READER_CUSTOM_CSS, null)

    override fun setReaderCustomCss(value: String?) {
        putStr(SettingsKeys.READER_CUSTOM_CSS, value)
    }

    override fun urlBarColor(): Int = int(SettingsKeys.URL_BAR_COLOR, 0)

    override fun setUrlBarColor(value: Int) {
        putInt(SettingsKeys.URL_BAR_COLOR, value)
    }

    override fun logoChoice(): Int = int(SettingsKeys.LOGO_CHOICE, 0)

    override fun setLogoChoice(value: Int) {
        putInt(SettingsKeys.LOGO_CHOICE, value)
    }

    override fun appUi(): Int = int(SettingsKeys.APP_UI, 0)

    override fun setAppUi(value: Int) {
        putInt(SettingsKeys.APP_UI, value)
    }

    override fun skin(): String? = str(SettingsKeys.SKIN, SettingsDefaults.SKIN)

    override fun setSkin(value: String?) {
        putStr(SettingsKeys.SKIN, value)
    }

    override fun webTextColor(): Int =
        int(SettingsKeys.WEB_TEXT_COLOR, SettingsDefaults.WEB_TEXT_COLOR).coerceIn(0, 3)

    override fun setWebTextColor(value: Int) =
        putInt(SettingsKeys.WEB_TEXT_COLOR, value.coerceIn(0, 3))

    override fun webViewDark(): Boolean =
        bool(SettingsKeys.WEBVIEW_DARK, true)

    override fun setWebViewDark(value: Boolean) =
        putBool(SettingsKeys.WEBVIEW_DARK, value)

    override fun addressStyle(): Int =
        int(SettingsKeys.ADDRESS_STYLE, 1).coerceIn(0, 2)

    override fun setAddressStyle(value: Int) =
        putInt(SettingsKeys.ADDRESS_STYLE, value.coerceIn(0, 2))

    override fun autoFillClipboard(): Boolean =
        bool(SettingsKeys.AUTO_FILL_CLIPBOARD, SettingsDefaults.AUTO_FILL_CLIPBOARD)

    override fun setAutoFillClipboard(value: Boolean) =
        putBool(SettingsKeys.AUTO_FILL_CLIPBOARD, value)

    override fun menuColumns(): Int = int(SettingsKeys.MENU_COLUMNS, 5).coerceIn(4, 5)
    override fun setMenuColumns(value: Int) = putInt(SettingsKeys.MENU_COLUMNS, value.coerceIn(4, 5))
    override fun menuScrollStyle(): Int = int(SettingsKeys.MENU_SCROLL_STYLE, 0).coerceIn(0, 1)
    override fun setMenuScrollStyle(value: Int) =
        putInt(SettingsKeys.MENU_SCROLL_STYLE, value.coerceIn(0, 1))
    override fun menuIconSize(): Int = int(SettingsKeys.MENU_ICON_SIZE, 29).coerceIn(16, 36)
    override fun setMenuIconSize(value: Int) = putInt(SettingsKeys.MENU_ICON_SIZE, value.coerceIn(16, 36))
    override fun menuTextSize(): Int = int(SettingsKeys.MENU_TEXT_SIZE, 9).coerceIn(8, 18)
    override fun setMenuTextSize(value: Int) = putInt(SettingsKeys.MENU_TEXT_SIZE, value.coerceIn(8, 18))
    override fun fixedMenuIconSize(): Int = int(SettingsKeys.FIXED_MENU_ICON_SIZE, 25).coerceIn(16, 36)
    override fun setFixedMenuIconSize(value: Int) = putInt(SettingsKeys.FIXED_MENU_ICON_SIZE, value.coerceIn(16, 36))
    override fun fixedMenuTextSize(): Int = int(SettingsKeys.FIXED_MENU_TEXT_SIZE, 10).coerceIn(8, 18)
    override fun setFixedMenuTextSize(value: Int) = putInt(SettingsKeys.FIXED_MENU_TEXT_SIZE, value.coerceIn(8, 18))
    override fun menuShowLabels(): Boolean = bool(SettingsKeys.MENU_SHOW_LABELS, true)
    override fun setMenuShowLabels(value: Boolean) = putBool(SettingsKeys.MENU_SHOW_LABELS, value)
    override fun fixedMenuShowLabels(): Boolean = bool(SettingsKeys.FIXED_MENU_SHOW_LABELS, true)
    override fun setFixedMenuShowLabels(value: Boolean) = putBool(SettingsKeys.FIXED_MENU_SHOW_LABELS, value)

    override fun fixedMenuHeight(): Int = int(SettingsKeys.FIXED_MENU_HEIGHT, 51).coerceIn(44, 80)
    override fun setFixedMenuHeight(value: Int) = putInt(SettingsKeys.FIXED_MENU_HEIGHT, value.coerceIn(44, 80))
    override fun homeLogoIconShow(): Boolean = bool(SettingsKeys.HOME_LOGO_ICON_SHOW, true)
    override fun setHomeLogoIconShow(value: Boolean) = putBool(SettingsKeys.HOME_LOGO_ICON_SHOW, value)

    override fun homeLogoMode(): Int {
        val stored = int(SettingsKeys.HOME_LOGO_MODE, SettingsDefaults.HOME_LOGO_MODE)
        if (stored >= 0) return stored.coerceIn(0, 2)
        // 旧版只有「文字 / 图标」开关，未设置过的设备按旧开关推断。
        return if (homeLogoIconShow()) 1 else 0
    }

    override fun setHomeLogoMode(value: Int) =
        putInt(SettingsKeys.HOME_LOGO_MODE, value.coerceIn(0, 2))
    override fun homeLogoIconSize(): Int = int(SettingsKeys.HOME_LOGO_ICON_SIZE, 80).coerceIn(24, 120)
    override fun setHomeLogoIconSize(value: Int) = putInt(SettingsKeys.HOME_LOGO_ICON_SIZE, value.coerceIn(24, 80))

    override fun suggestOrder(): String =
        str(SettingsKeys.SUGGEST_ORDER, SettingsDefaults.SUGGEST_ORDER)
            ?: SettingsDefaults.SUGGEST_ORDER

    override fun setSuggestOrder(value: String) =
        putStr(SettingsKeys.SUGGEST_ORDER, value)

    override fun themeColorEnabled(): Boolean = bool(SettingsKeys.THEME_COLOR, false)

    override fun setThemeColorEnabled(value: Boolean) {
        putBool(SettingsKeys.THEME_COLOR, value)
        // 与「主题色优先」互斥，打开识别时主题色不再压它。
        if (value) putBool(SettingsKeys.THEME_OVER_DETECT, false)
    }

    override fun themeColorToolbar(): Boolean = bool(SettingsKeys.THEME_COLOR_TOOLBAR, true)

    override fun setThemeColorToolbar(value: Boolean) =
        putBool(SettingsKeys.THEME_COLOR_TOOLBAR, value)

    override fun themeColorAddress(): Boolean = bool(SettingsKeys.THEME_COLOR_ADDRESS, true)

    override fun setThemeColorAddress(value: Boolean) =
        putBool(SettingsKeys.THEME_COLOR_ADDRESS, value)

    override fun themeColorStatus(): Boolean = bool(SettingsKeys.THEME_COLOR_STATUS, true)

    override fun setThemeColorStatus(value: Boolean) =
        putBool(SettingsKeys.THEME_COLOR_STATUS, value)

    override fun themeColorMenu(): Boolean = bool(SettingsKeys.THEME_COLOR_MENU, true)

    override fun setThemeColorMenu(value: Boolean) =
        putBool(SettingsKeys.THEME_COLOR_MENU, value)

    override fun themePartToolbar(): Boolean =
        bool(SettingsKeys.THEME_PART_TOOLBAR, SettingsDefaults.THEME_PART_TOOLBAR)

    override fun setThemePartToolbar(value: Boolean) =
        putBool(SettingsKeys.THEME_PART_TOOLBAR, value)

    override fun themePartTabs(): Boolean =
        bool(SettingsKeys.THEME_PART_TABS, SettingsDefaults.THEME_PART_TABS)

    override fun setThemePartTabs(value: Boolean) =
        putBool(SettingsKeys.THEME_PART_TABS, value)

    override fun themePartSites(): Boolean =
        bool(SettingsKeys.THEME_PART_SITES, SettingsDefaults.THEME_PART_SITES)

    override fun setThemePartSites(value: Boolean) =
        putBool(SettingsKeys.THEME_PART_SITES, value)

    override fun themePartHomeBg(): Boolean =
        bool(SettingsKeys.THEME_PART_HOME_BG, SettingsDefaults.THEME_PART_HOME_BG)

    override fun setThemePartHomeBg(value: Boolean) =
        putBool(SettingsKeys.THEME_PART_HOME_BG, value)

    override fun themePartMenu(): Boolean =
        bool(SettingsKeys.THEME_PART_MENU, SettingsDefaults.THEME_PART_MENU)

    override fun setThemePartMenu(value: Boolean) =
        putBool(SettingsKeys.THEME_PART_MENU, value)

    override fun themePartStatus(): Boolean =
        bool(SettingsKeys.THEME_PART_STATUS, SettingsDefaults.THEME_PART_STATUS)

    override fun setThemePartStatus(value: Boolean) =
        putBool(SettingsKeys.THEME_PART_STATUS, value)

    override fun themeOverDetect(): Boolean =
        bool(SettingsKeys.THEME_OVER_DETECT, SettingsDefaults.THEME_OVER_DETECT)

    override fun setThemeOverDetect(value: Boolean) {
        putBool(SettingsKeys.THEME_OVER_DETECT, value)
        // 与「识别网站主题色」互斥：打开优先位时直接关掉识别。
        if (value) putBool(SettingsKeys.THEME_COLOR, false)
    }

    override fun themePartsNight(): Boolean =
        bool(SettingsKeys.THEME_PARTS_NIGHT, SettingsDefaults.THEME_PARTS_NIGHT)

    override fun setThemePartsNight(value: Boolean) =
        putBool(SettingsKeys.THEME_PARTS_NIGHT, value)

    override fun themeColorTabs(): Boolean =
        bool(SettingsKeys.THEME_COLOR_TABS, SettingsDefaults.THEME_COLOR_TABS)

    override fun setThemeColorTabs(value: Boolean) =
        putBool(SettingsKeys.THEME_COLOR_TABS, value)

    override fun themeColorLongPress(): Boolean = bool(SettingsKeys.THEME_COLOR_LONG_PRESS, true)

    override fun setThemeColorLongPress(value: Boolean) =
        putBool(SettingsKeys.THEME_COLOR_LONG_PRESS, value)

    override fun themeColorForceBackground(): Boolean =
        bool(SettingsKeys.THEME_COLOR_FORCE_BG, false)

    override fun setThemeColorForceBackground(value: Boolean) =
        putBool(SettingsKeys.THEME_COLOR_FORCE_BG, value)

    // ---- 主页与搜索 ----

    override fun homeUrl(): String =
        str(SettingsKeys.HOME, null)?.takeIf { it.isNotBlank() } ?: SettingsDefaults.HOME

    override fun setHomeUrl(url: String) {
        putStr(SettingsKeys.HOME, url)
    }

    override fun homeKey(): Int = int(SettingsKeys.KEY_HOME, 4)

    override fun setHomeKey(value: Int) {
        putInt(SettingsKeys.KEY_HOME, value)
    }

    override fun tagHomeUrl(): String? = str(SettingsKeys.TAG_HOME, null)

    override fun setTagHomeUrl(value: String?) {
        putStr(SettingsKeys.TAG_HOME, value)
    }

    override fun searchEngineUrl(): String? {
        val value = str(SettingsKeys.SEARCH_URL, "https://www.bing.com/search?q=")
            ?.takeIf { it.isNotBlank() }
            ?: SettingsDefaults.SEARCH_ENGINE
        // cn.bing.com 的 /search 会被 301 到 www 首页，丢掉结果页；统一走 www。
        return if (value == "https://cn.bing.com/search?q=") {
            "https://www.bing.com/search?q="
        } else value
    }

    override fun setSearchEngineUrl(value: String?) {
        putStr(SettingsKeys.SEARCH_URL, value)
    }

    override fun searchMode(): Int = int(SettingsKeys.SEARCH_MODE, 0)

    override fun setSearchMode(value: Int) {
        putInt(SettingsKeys.SEARCH_MODE, value)
    }

    override fun searchSuggestion(): Int = int(SettingsKeys.SEARCH_SUGGESTION, 1)

    override fun setSearchSuggestion(value: Int) {
        putInt(SettingsKeys.SEARCH_SUGGESTION, value)
    }

    override fun searchSuggestHistory(): Boolean =
        bool(SettingsKeys.SEARCH_SUGGEST_HISTORY, true)

    override fun setSearchSuggestHistory(value: Boolean) =
        putBool(SettingsKeys.SEARCH_SUGGEST_HISTORY, value)

    override fun searchSuggestBookmarks(): Boolean =
        bool(SettingsKeys.SEARCH_SUGGEST_BOOKMARKS, true)

    override fun setSearchSuggestBookmarks(value: Boolean) =
        putBool(SettingsKeys.SEARCH_SUGGEST_BOOKMARKS, value)

    override fun searchSuggestOnline(): Boolean =
        bool(SettingsKeys.SEARCH_SUGGEST_ONLINE, true)

    override fun setSearchSuggestOnline(value: Boolean) =
        putBool(SettingsKeys.SEARCH_SUGGEST_ONLINE, value)

    override fun searchToolbarDisabled(): String? = str(SettingsKeys.SEARCH_TOOLBAR_DISABLED, null)

    override fun setSearchToolbarDisabled(value: String?) {
        putStr(SettingsKeys.SEARCH_TOOLBAR_DISABLED, value)
    }

    override fun searchToolbarOrder(): String? = str(SettingsKeys.SEARCH_TOOLBAR_ORDER, "")

    override fun setSearchToolbarOrder(value: String?) {
        putStr(SettingsKeys.SEARCH_TOOLBAR_ORDER, value)
    }

    override fun searchShortcuts(): String? = str(SettingsKeys.SEARCH_SHORTCUTS, null)

    override fun setSearchShortcuts(value: String?) {
        putStr(SettingsKeys.SEARCH_SHORTCUTS, value)
    }

    // ---- UA ----

    override fun userAgent(): String? = str(SettingsKeys.UA_STRING, null)

    override fun setUserAgent(value: String?) {
        putStr(SettingsKeys.UA_STRING, value)
    }

    override fun desktopUserAgent(): String? =
        str(SettingsKeys.DUA_STRING, null)?.takeIf { it.isNotBlank() } ?: SettingsDefaults.DESKTOP_UA

    override fun setDesktopUserAgent(value: String?) {
        putStr(SettingsKeys.DUA_STRING, value)
    }

    override fun desktopSites(): String =
        str(SettingsKeys.DESKTOP_SITES, null).orEmpty()

    override fun setDesktopSites(value: String) {
        putStr(SettingsKeys.DESKTOP_SITES, value)
    }


    override fun uaChoice(): Int = int(SettingsKeys.UA_CHOICE, 0)

    override fun setUaChoice(value: Int) {
        putInt(SettingsKeys.UA_CHOICE, value)
    }

    override fun duaChoice(): Int = int(SettingsKeys.DUA_CHOICE, 0)

    override fun setDuaChoice(value: Int) {
        putInt(SettingsKeys.DUA_CHOICE, value)
    }

    // ---- 行为 ----

    override fun urlBoxBehavior(): Int = int(SettingsKeys.URL_BOX_BEHAVIOR, 1)

    override fun setUrlBoxBehavior(value: Int) {
        putInt(SettingsKeys.URL_BOX_BEHAVIOR, value)
    }

    override fun suggestionDirection(): Int =
        int(SettingsKeys.SUGGESTION_DIRECTION, SettingsDefaults.SUGGESTION_DIRECTION)

    override fun setSuggestionDirection(value: Int) =
        putInt(SettingsKeys.SUGGESTION_DIRECTION, value)

    override fun suggestionCount(): Int =
        int(SettingsKeys.SUGGESTION_COUNT, 5)

    override fun setSuggestionCount(value: Int) =
        putInt(SettingsKeys.SUGGESTION_COUNT, value)

    override fun homeLayoutPos(): Int =
        int(SettingsKeys.HOME_LAYOUT_POS, 1)

    override fun setHomeLayoutPos(value: Int) {
        // 悬浮胶囊工具栏下不支持主页搜索框置底。
        val target = if (value == 2 && uiStyle() == 1) 1 else value
        putInt(SettingsKeys.HOME_LAYOUT_POS, target)
        // 搜索框已在顶部时，不再叠加主页顶部搜索入口。
        if (target == 0) putBool(SettingsKeys.HOME_TOP_ADDRESS, false)
    }

    override fun homeLogoText(): String =
        str(SettingsKeys.HOME_LOGO_TEXT, null) ?: SettingsDefaults.HOME_LOGO_TEXT

    override fun setHomeLogoText(value: String) =
        putStr(SettingsKeys.HOME_LOGO_TEXT, value)

    override fun homeGridColumns(): Int =
        int(SettingsKeys.HOME_GRID_COLUMNS, 4)

    override fun setHomeGridColumns(value: Int) =
        putInt(SettingsKeys.HOME_GRID_COLUMNS, value)

    override fun homeIconSize(): Int =
        int(SettingsKeys.HOME_ICON_SIZE, SettingsDefaults.HOME_ICON_SIZE)

    override fun setHomeIconSize(value: Int) =
        putInt(SettingsKeys.HOME_ICON_SIZE, value)

    override fun homeIconShape(): Int =
        int(SettingsKeys.HOME_ICON_SHAPE, 0)

    override fun setHomeIconShape(value: Int) =
        putInt(SettingsKeys.HOME_ICON_SHAPE, value)

    override fun homeShowLabels(): Boolean =
        bool(SettingsKeys.HOME_SHOW_LABELS, true)

    override fun setHomeShowLabels(value: Boolean) =
        putBool(SettingsKeys.HOME_SHOW_LABELS, value)

    override fun homeTopAddress(): Boolean =
        bool(SettingsKeys.HOME_TOP_ADDRESS, SettingsDefaults.HOME_TOP_ADDRESS)

    override fun setHomeTopAddress(value: Boolean) {
        putBool(SettingsKeys.HOME_TOP_ADDRESS, value)
        // 开启顶部搜索入口时，把主页搜索框从顶部挪回居中，两者互斥。
        if (value && homeLayoutPos() == 0) {
            putInt(SettingsKeys.HOME_LAYOUT_POS, 1)
        }
    }

    override fun homeIconRadius(): Int =
        int(SettingsKeys.HOME_ICON_RADIUS, SettingsDefaults.HOME_ICON_RADIUS)

    override fun setHomeIconRadius(value: Int) =
        putInt(SettingsKeys.HOME_ICON_RADIUS, value)

    override fun homeSearchRadius(): Int =
        int(SettingsKeys.HOME_SEARCH_RADIUS, 3)

    override fun setHomeSearchRadius(value: Int) =
        putInt(SettingsKeys.HOME_SEARCH_RADIUS, value)

    override fun homeSearchHeight(): Int =
        int(SettingsKeys.HOME_SEARCH_HEIGHT, SettingsDefaults.HOME_SEARCH_HEIGHT)

    override fun setHomeSearchHeight(value: Int) =
        putInt(SettingsKeys.HOME_SEARCH_HEIGHT, value)

    override fun homeBgBlur(): Int =
        int(SettingsKeys.HOME_BG_BLUR, SettingsDefaults.HOME_BG_BLUR)

    override fun setHomeBgBlur(value: Int) =
        putInt(SettingsKeys.HOME_BG_BLUR, value)

    override fun homeLogoSize(): Int =
        int(SettingsKeys.HOME_LOGO_SIZE, SettingsDefaults.HOME_LOGO_SIZE)

    override fun setHomeLogoSize(value: Int) = putInt(SettingsKeys.HOME_LOGO_SIZE, value)

    override fun homeLogoWeight(): Int =
        int(SettingsKeys.HOME_LOGO_WEIGHT, SettingsDefaults.HOME_LOGO_WEIGHT)

    override fun setHomeLogoWeight(value: Int) = putInt(SettingsKeys.HOME_LOGO_WEIGHT, value)

    override fun homeLogoColor(): String? = str(SettingsKeys.HOME_LOGO_COLOR, "accent")

    override fun setHomeLogoColor(value: String?) = putStr(SettingsKeys.HOME_LOGO_COLOR, value)

    override fun homeLabelSize(): Int =
        int(SettingsKeys.HOME_LABEL_SIZE, SettingsDefaults.HOME_LABEL_SIZE)

    override fun setHomeLabelSize(value: Int) = putInt(SettingsKeys.HOME_LABEL_SIZE, value)

    override fun homeCellGap(): Int =
        int(SettingsKeys.HOME_CELL_GAP, 12)

    override fun setHomeCellGap(value: Int) = putInt(SettingsKeys.HOME_CELL_GAP, value)

    override fun homeIconAccent(): Boolean =
        bool(SettingsKeys.HOME_ICON_ACCENT, false)

    override fun setHomeIconAccent(value: Boolean) =
        putBool(SettingsKeys.HOME_ICON_ACCENT, value)

    override fun homeBgBlurPercent(): Int =
        int(SettingsKeys.HOME_BG_BLUR_PERCENT, 0)

    override fun setHomeBgBlurPercent(value: Int) =
        putInt(SettingsKeys.HOME_BG_BLUR_PERCENT, value.coerceIn(0, 100))

    override fun homeBgOverlay(): Int =
        int(SettingsKeys.HOME_BG_OVERLAY, SettingsDefaults.HOME_BG_OVERLAY)

    override fun setHomeBgOverlay(value: Int) = putInt(SettingsKeys.HOME_BG_OVERLAY, value)

    override fun homeShowSitesTitle(): Boolean =
        bool(SettingsKeys.HOME_SHOW_SITES_TITLE, false)

    override fun setHomeShowSitesTitle(value: Boolean) =
        putBool(SettingsKeys.HOME_SHOW_SITES_TITLE, value)

    override fun exitClearEnabled(): Boolean =
        bool(SettingsKeys.EXIT_CLEAR, SettingsDefaults.EXIT_CLEAR)

    override fun setExitClearEnabled(value: Boolean) = putBool(SettingsKeys.EXIT_CLEAR, value)

    override fun exitClearItems(): String =
        str(SettingsKeys.EXIT_CLEAR_ITEMS, null) ?: SettingsDefaults.EXIT_CLEAR_ITEMS

    override fun setExitClearItems(value: String) = putStr(SettingsKeys.EXIT_CLEAR_ITEMS, value)

    override fun gestureDoubleTap(): Int =
        int(SettingsKeys.GESTURE_DOUBLE_TAP, SettingsDefaults.GESTURE_DOUBLE_TAP)

    override fun setGestureDoubleTap(value: Int) =
        putInt(SettingsKeys.GESTURE_DOUBLE_TAP, value)

    override fun gameMode(): Boolean = bool(SettingsKeys.GAME_MODE, SettingsDefaults.GAME_MODE)

    override fun setGameMode(value: Boolean) = putBool(SettingsKeys.GAME_MODE, value)

    override fun translateProvider(): Int =
        int(SettingsKeys.TRANSLATE_PROVIDER, SettingsDefaults.TRANSLATE_PROVIDER)

    override fun translateMode(): Int =
        int(SettingsKeys.TRANSLATE_MODE, SettingsDefaults.TRANSLATE_MODE).coerceIn(0, 2)

    override fun setTranslateMode(value: Int) =
        putInt(SettingsKeys.TRANSLATE_MODE, value.coerceIn(0, 2))

    override fun setTranslateProvider(value: Int) =
        putInt(SettingsKeys.TRANSLATE_PROVIDER, value)

    override fun translateEndpoint(): String =
        str(SettingsKeys.TRANSLATE_ENDPOINT, null) ?: SettingsDefaults.TRANSLATE_ENDPOINT

    override fun setTranslateEndpoint(value: String) =
        putStr(SettingsKeys.TRANSLATE_ENDPOINT, value)

    override fun translateTarget(): String =
        str(SettingsKeys.TRANSLATE_TARGET, null) ?: SettingsDefaults.TRANSLATE_TARGET

    override fun setTranslateTarget(value: String) =
        putStr(SettingsKeys.TRANSLATE_TARGET, value)

    override fun translateDetect(): Boolean =
        bool(SettingsKeys.TRANSLATE_DETECT, SettingsDefaults.TRANSLATE_DETECT)

    override fun setTranslateDetect(value: Boolean) =
        putBool(SettingsKeys.TRANSLATE_DETECT, value)

    override fun translateDual(): Boolean =
        bool(SettingsKeys.TRANSLATE_DUAL, SettingsDefaults.TRANSLATE_DUAL)

    override fun setTranslateDual(value: Boolean) =
        putBool(SettingsKeys.TRANSLATE_DUAL, value)

    override fun translateCollect(): Int =
        int(SettingsKeys.TRANSLATE_COLLECT, SettingsDefaults.TRANSLATE_COLLECT)

    override fun setTranslateCollect(value: Int) =
        putInt(SettingsKeys.TRANSLATE_COLLECT, value.coerceIn(1, 2000))

    override fun translateScan(): Int =
        int(SettingsKeys.TRANSLATE_SCAN, SettingsDefaults.TRANSLATE_SCAN)

    override fun setTranslateScan(value: Int) =
        putInt(SettingsKeys.TRANSLATE_SCAN, value.coerceIn(1, 500))

    override fun translateIntervalMs(): Int =
        int(SettingsKeys.TRANSLATE_INTERVAL_MS, SettingsDefaults.TRANSLATE_INTERVAL_MS).coerceIn(100, 10000)

    override fun setTranslateIntervalMs(value: Int) =
        putInt(SettingsKeys.TRANSLATE_INTERVAL_MS, value.coerceIn(100, 10000))

    override fun translateChunk(): Int =
        int(SettingsKeys.TRANSLATE_CHUNK, SettingsDefaults.TRANSLATE_CHUNK)

    override fun setTranslateChunk(value: Int) =
        putInt(SettingsKeys.TRANSLATE_CHUNK, value.coerceIn(500, 8000))

    override fun translateLimit(): Int {
        val raw = int(SettingsKeys.TRANSLATE_LIMIT, SettingsDefaults.TRANSLATE_LIMIT)
        // 旧版上限 100000 视为无上限（0）。
        return if (raw >= 100000) 0 else raw.coerceAtLeast(0)
    }

    override fun setTranslateLimit(value: Int) =
        putInt(SettingsKeys.TRANSLATE_LIMIT, value.coerceIn(0, 1000000))

    override fun translateCustomMethod(): Int =
        int(SettingsKeys.TRANSLATE_CUSTOM_METHOD, SettingsDefaults.TRANSLATE_CUSTOM_METHOD)

    override fun setTranslateCustomMethod(value: Int) =
        putInt(SettingsKeys.TRANSLATE_CUSTOM_METHOD, value.coerceIn(0, 1))

    override fun translateCustomHeaders(): String =
        str(SettingsKeys.TRANSLATE_CUSTOM_HEADERS, null)
            ?: SettingsDefaults.TRANSLATE_CUSTOM_HEADERS

    override fun setTranslateCustomHeaders(value: String) =
        putStr(SettingsKeys.TRANSLATE_CUSTOM_HEADERS, value)

    override fun translateCustomBody(): String =
        str(SettingsKeys.TRANSLATE_CUSTOM_BODY, null)
            ?: SettingsDefaults.TRANSLATE_CUSTOM_BODY

    override fun setTranslateCustomBody(value: String) =
        putStr(SettingsKeys.TRANSLATE_CUSTOM_BODY, value)

    override fun translateCustomPath(): String =
        str(SettingsKeys.TRANSLATE_CUSTOM_PATH, null)
            ?: SettingsDefaults.TRANSLATE_CUSTOM_PATH

    override fun setTranslateCustomPath(value: String) =
        putStr(SettingsKeys.TRANSLATE_CUSTOM_PATH, value)

    override fun translateBaiduAppId(): String =
        str(SettingsKeys.TRANSLATE_BAIDU_APPID, null)
            ?: SettingsDefaults.TRANSLATE_BAIDU_APPID

    override fun setTranslateBaiduAppId(value: String) =
        putStr(SettingsKeys.TRANSLATE_BAIDU_APPID, value)

    override fun translateBaiduKey(): String =
        str(SettingsKeys.TRANSLATE_BAIDU_KEY, null)
            ?: SettingsDefaults.TRANSLATE_BAIDU_KEY

    override fun setTranslateBaiduKey(value: String) =
        putStr(SettingsKeys.TRANSLATE_BAIDU_KEY, value)

    override fun siteBlockPopup(): Boolean = bool(SettingsKeys.SITE_BLOCK_POPUP, false)

    override fun setSiteBlockPopup(value: Boolean) = putBool(SettingsKeys.SITE_BLOCK_POPUP, value)

    override fun siteBlockDb(): Boolean = bool(SettingsKeys.SITE_BLOCK_DB, false)

    override fun setSiteBlockDb(value: Boolean) = putBool(SettingsKeys.SITE_BLOCK_DB, value)

    override fun siteBlockDom(): Boolean = bool(SettingsKeys.SITE_BLOCK_DOM, false)

    override fun setSiteBlockDom(value: Boolean) = putBool(SettingsKeys.SITE_BLOCK_DOM, value)

    override fun siteBlockFile(): Boolean = bool(SettingsKeys.SITE_BLOCK_FILE, false)

    override fun setSiteBlockFile(value: Boolean) = putBool(SettingsKeys.SITE_BLOCK_FILE, value)

    override fun siteBlockJs(): Boolean = bool(SettingsKeys.SITE_BLOCK_JS, false)

    override fun setSiteBlockJs(value: Boolean) = putBool(SettingsKeys.SITE_BLOCK_JS, value)

    override fun openAppPolicy(): Int = int(SettingsKeys.OPEN_APP_POLICY, 0)

    override fun setOpenAppPolicy(value: Int) =
        putInt(SettingsKeys.OPEN_APP_POLICY, value.coerceIn(0, 2))

    override fun closeTabToast(): Boolean = bool(SettingsKeys.CLOSETAB_TOAST, true)

    override fun setCloseTabToast(value: Boolean) = putBool(SettingsKeys.CLOSETAB_TOAST, value)

    override fun saveDataEnabled(): Boolean = bool(SettingsKeys.SAVE_DATA, false)

    override fun setSaveDataEnabled(value: Boolean) = putBool(SettingsKeys.SAVE_DATA, value)

    override fun customFont(): String? = str(SettingsKeys.CUSTOM_FONT, null)

    override fun setCustomFont(value: String?) = putStr(SettingsKeys.CUSTOM_FONT, value)

    override fun permissionGeo(): Int = int(SettingsKeys.PERMISSION_GEO, 0)

    override fun setPermissionGeo(value: Int) =
        putInt(SettingsKeys.PERMISSION_GEO, value.coerceIn(0, 2))

    override fun permissionCam(): Int = int(SettingsKeys.PERMISSION_CAM, 0)

    override fun setPermissionCam(value: Int) =
        putInt(SettingsKeys.PERMISSION_CAM, value.coerceIn(0, 2))

    override fun permissionMic(): Int = int(SettingsKeys.PERMISSION_MIC, 0)

    override fun setPermissionMic(value: Int) =
        putInt(SettingsKeys.PERMISSION_MIC, value.coerceIn(0, 2))

    override fun scriptUpdateDays(): Int = int(SettingsKeys.SCRIPT_UPDATE_DAYS, 0)

    override fun setScriptUpdateDays(value: Int) = putInt(SettingsKeys.SCRIPT_UPDATE_DAYS, value)

    override fun scriptLastUpdate(): Long = long(SettingsKeys.SCRIPT_LAST_UPDATE, 0L)

    override fun setScriptLastUpdate(value: Long) = putLong(SettingsKeys.SCRIPT_LAST_UPDATE, value)

    override fun homeLogoShow(): Boolean = bool(SettingsKeys.HOME_LOGO_SHOW, false)

    override fun setHomeLogoShow(value: Boolean) = putBool(SettingsKeys.HOME_LOGO_SHOW, value)

    override fun homeSearchRadiusCustom(): Int =
        int(SettingsKeys.HOME_SEARCH_RADIUS_CUSTOM, 28)

    override fun setHomeSearchRadiusCustom(value: Int) =
        putInt(SettingsKeys.HOME_SEARCH_RADIUS_CUSTOM, value.coerceIn(0, 48))

    override fun homeSearchAlpha(): Int = int(SettingsKeys.HOME_SEARCH_ALPHA, 50)

    override fun setHomeSearchAlpha(value: Int) =
        putInt(SettingsKeys.HOME_SEARCH_ALPHA, value.coerceIn(0, 100))

    override fun siteNoImage(): Boolean = bool(SettingsKeys.SITE_NO_IMAGE, false)

    override fun setSiteNoImage(value: Boolean) = putBool(SettingsKeys.SITE_NO_IMAGE, value)

    override fun siteBackNoReload(): Boolean = bool(SettingsKeys.SITE_BACK_NO_RELOAD, false)

    override fun setSiteBackNoReload(value: Boolean) =
        putBool(SettingsKeys.SITE_BACK_NO_RELOAD, value)

    override fun siteAllowRedirect(): Boolean = bool(SettingsKeys.SITE_ALLOW_REDIRECT, true)

    override fun setSiteAllowRedirect(value: Boolean) =
        putBool(SettingsKeys.SITE_ALLOW_REDIRECT, value)

    override fun siteRedirectPolicy(): Int = int(SettingsKeys.SITE_REDIRECT_POLICY, 1)

    override fun setSiteRedirectPolicy(value: Int) =
        putInt(SettingsKeys.SITE_REDIRECT_POLICY, value.coerceIn(0, 2))

    override fun tabActionSwap(): Boolean = bool(SettingsKeys.TAB_ACTION_SWAP, true)

    override fun setTabActionSwap(value: Boolean) = putBool(SettingsKeys.TAB_ACTION_SWAP, value)

    override fun tabUndoVisible(): Boolean = bool(SettingsKeys.TAB_UNDO_VISIBLE, true)

    override fun setTabUndoVisible(value: Boolean) = putBool(SettingsKeys.TAB_UNDO_VISIBLE, value)

    override fun exitRestorePrompt(): Boolean =
        bool(SettingsKeys.EXIT_RESTORE_PROMPT, SettingsDefaults.EXIT_RESTORE_PROMPT)

    override fun setExitRestorePrompt(value: Boolean) =
        putBool(SettingsKeys.EXIT_RESTORE_PROMPT, value)

    override fun exitConfirm(): Boolean =
        bool(SettingsKeys.EXIT_CONFIRM, SettingsDefaults.EXIT_CONFIRM)

    override fun setExitConfirm(value: Boolean) =
        putBool(SettingsKeys.EXIT_CONFIRM, value)

    override fun sniffEnabled(): Boolean = bool(SettingsKeys.SNIFF_ENABLED, true)

    override fun setSniffEnabled(value: Boolean) = putBool(SettingsKeys.SNIFF_ENABLED, value)

    override fun startupBehavior(): Int =
        int(SettingsKeys.STARTUP_BEHAVIOR, if (restoreClosedTabs() == 1) 1 else 0).coerceIn(0, 2)

    override fun setStartupBehavior(value: Int) =
        putInt(SettingsKeys.STARTUP_BEHAVIOR, value.coerceIn(0, 2))

    override fun snapshot(): Map<String, Any> = cache.toMap()

    override fun restore(values: Map<String, Any>) {
        values.forEach { (key, value) ->
            when (value) {
                is Boolean -> putBool(key, value)
                is Int -> putInt(key, value)
                is String -> putStr(key, value)
            }
        }
    }

    override fun fullScreenMode(): Int = int(SettingsKeys.FULL_SCREEN_MODE, 0)

    override fun setFullScreenMode(value: Int) {
        putInt(SettingsKeys.FULL_SCREEN_MODE, value)
    }

    override fun screenOrientation(): Int = int(SettingsKeys.SCREEN_ORIENTATION, 0)

    override fun setScreenOrientation(value: Int) {
        putInt(SettingsKeys.SCREEN_ORIENTATION, value)
    }

    override fun restoreClosedTabs(): Int = int(SettingsKeys.RESTORE_CLOSED_TABS, 0)

    override fun setRestoreClosedTabs(value: Int) {
        putInt(SettingsKeys.RESTORE_CLOSED_TABS, value)
    }

    override fun fab(): Int = int(SettingsKeys.FAB, 0)

    override fun setFab(value: Int) {
        putInt(SettingsKeys.FAB, value)
    }

    override fun keyAction(key: String): Int = int(key, SettingsDefaults.keyAction(key))

    override fun setKeyAction(key: String, value: Int) {
        putInt(key, value)
    }

    override fun gestureToolbarLeft(): Int = int(SettingsKeys.GESTURE_TOOLBAR_LEFT, 0)

    override fun setGestureToolbarLeft(value: Int) {
        putInt(SettingsKeys.GESTURE_TOOLBAR_LEFT, value)
    }

    override fun gestureToolbarRight(): Int = int(SettingsKeys.GESTURE_TOOLBAR_RIGHT, 0)

    override fun setGestureToolbarRight(value: Int) {
        putInt(SettingsKeys.GESTURE_TOOLBAR_RIGHT, value)
    }

    override fun displayedMenus(): String? = str(SettingsKeys.DISPLAYED_MENUS, "refresh,home,addbookmark,bookmarks,history,closeothers,adblock,sniff,adblockstats,readlaterlist,find,font,netlog,jsconsole,share,reader,uiscale,copylink,translatepage,readaloud,scan,savepage,source,print,passwords,scriptmenu,addhome,cleardata,recentclosed,noimg,adpick,closetabs,desktop,newtab")

    override fun setDisplayedMenus(value: String?) {
        putStr(SettingsKeys.DISPLAYED_MENUS, value)
    }

    override fun hiddenMenus(): String? = str(SettingsKeys.HIDDEN_MENUS, "")

    override fun setHiddenMenus(value: String?) {
        putStr(SettingsKeys.HIDDEN_MENUS, value)
    }

    override fun fixedItems(): String? = str(SettingsKeys.FIXED_ITEMS, "downloads,settings,dark,incog")

    override fun setFixedItems(value: String?) {
        putStr(SettingsKeys.FIXED_ITEMS, value)
    }

    override fun fixedMenuPosition(): Int =
        int(SettingsKeys.FIXED_MENU_POSITION, SettingsDefaults.FIXED_MENU_POSITION).coerceIn(0, 1)

    override fun setFixedMenuPosition(value: Int) =
        putInt(SettingsKeys.FIXED_MENU_POSITION, value.coerceIn(0, 1))

    override fun videoOrientation(): Int = int(SettingsKeys.VIDEO_ORIENTATION, 0)

    override fun setVideoOrientation(value: Int) {
        putInt(SettingsKeys.VIDEO_ORIENTATION, value)
    }

    // ---- 数据 ----

    override fun clearDataOnExit(): Int = int(SettingsKeys.CLEAR_DATA_ON_EXIT, 0)

    override fun setClearDataOnExit(value: Int) {
        putInt(SettingsKeys.CLEAR_DATA_ON_EXIT, value)
    }

    override fun clearData(): Int = int(SettingsKeys.CLEAR_DATA, 0)

    override fun setClearData(value: Int) {
        putInt(SettingsKeys.CLEAR_DATA, value)
    }

    override fun bookmarksOrder(): Int = int(SettingsKeys.BOOKMARKS_ORDER, 0)

    override fun setBookmarksOrder(value: Int) {
        putInt(SettingsKeys.BOOKMARKS_ORDER, value)
    }

    override fun bookmarkSort(): String = str(SettingsKeys.BOOKMARK_SORT, "az") ?: "az"

    override fun setBookmarkSort(value: String) = putStr(SettingsKeys.BOOKMARK_SORT, value)

    override fun bookmarkSize(): String = str(SettingsKeys.BOOKMARK_SIZE, "md") ?: "md"

    override fun setBookmarkSize(value: String) = putStr(SettingsKeys.BOOKMARK_SIZE, value)

    override fun bookmarkIcon(): String = str(SettingsKeys.BOOKMARK_ICON, "initial") ?: "initial"

    override fun setBookmarkIcon(value: String) = putStr(SettingsKeys.BOOKMARK_ICON, value)

    override fun bookmarksViewMode(): Int = int(SettingsKeys.BOOKMARKS_VIEW_MODE, 0)

    override fun setBookmarksViewMode(value: Int) {
        putInt(SettingsKeys.BOOKMARKS_VIEW_MODE, value)
    }

    override fun downloadManager(): String? = str(SettingsKeys.DL_MANAGER, null)

    override fun setDownloadManager(value: String?) {
        putStr(SettingsKeys.DL_MANAGER, value)
    }

    override fun videoPlayer(): String? = str(SettingsKeys.VIDEO_PLAYER, null)

    override fun setVideoPlayer(value: String?) {
        putStr(SettingsKeys.VIDEO_PLAYER, value)
    }

    // ---- 网页行为 ----

    override fun webPageBehavior(): WebPageBehavior = WebPageBehavior(int(SettingsKeys.WEB_FLAG, 0))

    override fun setWebPageBehavior(behavior: WebPageBehavior) {
        putInt(SettingsKeys.WEB_FLAG, behavior.value)
    }

    override fun appFlag(): Int = int(SettingsKeys.APP_FLAG, 0)

    override fun setAppFlag(value: Int) {
        putInt(SettingsKeys.APP_FLAG, value)
    }

    override fun webFlag2(): Int = int(SettingsKeys.WEB_FLAG_2, 0)

    override fun setWebFlag2(value: Int) {
        putInt(SettingsKeys.WEB_FLAG_2, value)
    }

    override fun labFlag(): Int = int(SettingsKeys.LAB_FLAG, 0)

    override fun setLabFlag(value: Int) {
        putInt(SettingsKeys.LAB_FLAG, value)
    }

    override fun softInputResizeEnabled(): Boolean =
        webPageBehavior().isEnabled(WebPageBehavior.BIT_AUTO_RESIZE)

    override fun setSoftInputResizeEnabled(enabled: Boolean) {
        val behavior = webPageBehavior()
        behavior.setEnabled(WebPageBehavior.BIT_AUTO_RESIZE, enabled)
        setWebPageBehavior(behavior)
    }

    override fun httpsOnly(): Boolean = bool(SettingsKeys.HTTPS_ONLY, false)

    override fun setHttpsOnly(value: Boolean) = putBool(SettingsKeys.HTTPS_ONLY, value)

    override fun ignoreSslError(): Boolean = bool(SettingsKeys.IGNORE_SSL, false)

    override fun setIgnoreSslError(value: Boolean) = putBool(SettingsKeys.IGNORE_SSL, value)

    override fun thirdPartyCookiesBlocked(): Boolean =
        bool(SettingsKeys.BLOCK_THIRD_PARTY_COOKIES, false)

    override fun setThirdPartyCookiesBlocked(value: Boolean) =
        putBool(SettingsKeys.BLOCK_THIRD_PARTY_COOKIES, value)

    override fun downloadConfirm(): Boolean = bool(SettingsKeys.DOWNLOAD_CONFIRM, true)

    override fun setDownloadConfirm(value: Boolean) = putBool(SettingsKeys.DOWNLOAD_CONFIRM, value)

    override fun toolbarHidden(): String? = str(SettingsKeys.TOOLBAR_HIDDEN, null)

    override fun setToolbarHidden(value: String?) = putStr(SettingsKeys.TOOLBAR_HIDDEN, value)

    override fun longPressHidden(): String? = str(SettingsKeys.LONG_PRESS_HIDDEN, "")

    override fun setLongPressHidden(value: String?) = putStr(SettingsKeys.LONG_PRESS_HIDDEN, value)

    override fun uaSimplified(): Boolean = bool(SettingsKeys.UA_SIMPLIFY, false)

    override fun setUaSimplified(value: Boolean) = putBool(SettingsKeys.UA_SIMPLIFY, value)

    override fun desktopSitesDisabled(): String =
        str(SettingsKeys.DESKTOP_SITES_DISABLED, null).orEmpty()

    override fun setDesktopSitesDisabled(value: String) =
        putStr(SettingsKeys.DESKTOP_SITES_DISABLED, value)

    override fun desktopMode(): Boolean = bool(SettingsKeys.DESKTOP_MODE, false)

    override fun setDesktopMode(value: Boolean) = putBool(SettingsKeys.DESKTOP_MODE, value)

    override fun wifiImagesOnly(): Boolean = bool(SettingsKeys.WIFI_IMG, false)

    override fun setWifiImagesOnly(value: Boolean) = putBool(SettingsKeys.WIFI_IMG, value)

    override fun toolbarNoLabel(): Boolean = bool(SettingsKeys.TOOL_NO_LABEL, true)

    override fun setToolbarNoLabel(value: Boolean) = putBool(SettingsKeys.TOOL_NO_LABEL, value)

    override fun showSites(): Boolean = bool(SettingsKeys.SHOW_SITES, true)

    override fun setShowSites(value: Boolean) = putBool(SettingsKeys.SHOW_SITES, value)

    override fun homeSitesCardBg(): Boolean =
        bool(SettingsKeys.HOME_SITES_CARD_BG, SettingsDefaults.HOME_SITES_CARD_BG)

    override fun setHomeSitesCardBg(value: Boolean) =
        putBool(SettingsKeys.HOME_SITES_CARD_BG, value)

    override fun webDebug(): Boolean = bool(SettingsKeys.WEB_DEBUG, false)

    override fun setWebDebug(value: Boolean) = putBool(SettingsKeys.WEB_DEBUG, value)

    override fun jsConsoleLog(): Boolean = bool(SettingsKeys.JS_CONSOLE_LOG, false)

    override fun setJsConsoleLog(value: Boolean) = putBool(SettingsKeys.JS_CONSOLE_LOG, value)

    override fun incognitoNoHistory(): Boolean = bool(SettingsKeys.INCOG_NO_HISTORY, true)

    override fun setIncognitoNoHistory(value: Boolean) = putBool(SettingsKeys.INCOG_NO_HISTORY, value)

    override fun incognitoNoRestore(): Boolean = bool(SettingsKeys.INCOG_NO_RESTORE, true)

    override fun setIncognitoNoRestore(value: Boolean) = putBool(SettingsKeys.INCOG_NO_RESTORE, value)

    override fun incognitoNoForm(): Boolean = bool(SettingsKeys.INCOG_NO_FORM, false)

    override fun setIncognitoNoForm(value: Boolean) = putBool(SettingsKeys.INCOG_NO_FORM, value)

    override fun incognitoNoGeo(): Boolean = bool(SettingsKeys.INCOG_NO_GEO, false)

    override fun setIncognitoNoGeo(value: Boolean) = putBool(SettingsKeys.INCOG_NO_GEO, value)

    override fun incognitoDenyCam(): Boolean = bool(SettingsKeys.INCOG_DENY_CAM, false)

    override fun setIncognitoDenyCam(value: Boolean) = putBool(SettingsKeys.INCOG_DENY_CAM, value)

    override fun incognitoDenyMic(): Boolean = bool(SettingsKeys.INCOG_DENY_MIC, false)

    override fun setIncognitoDenyMic(value: Boolean) = putBool(SettingsKeys.INCOG_DENY_MIC, value)

    override fun incognitoSecure(): Boolean = bool(SettingsKeys.INCOG_SECURE, false)

    override fun setIncognitoSecure(value: Boolean) = putBool(SettingsKeys.INCOG_SECURE, value)

    override fun incognitoClearOnExit(): Boolean = bool(SettingsKeys.INCOG_CLEAR_ON_EXIT, false)

    override fun setIncognitoClearOnExit(value: Boolean) = putBool(SettingsKeys.INCOG_CLEAR_ON_EXIT, value)

    override fun incognitoCleanDownloads(): Boolean = bool(SettingsKeys.INCOG_CLEAN_DOWNLOADS, false)

    override fun setIncognitoCleanDownloads(value: Boolean) {
        putBool(SettingsKeys.INCOG_CLEAN_DOWNLOADS, value)
        mirror.edit().putBoolean(SettingsKeys.INCOG_CLEAN_DOWNLOADS, value).commit()
    }

    override fun incognitoCleanTranslation(): Boolean = bool(SettingsKeys.INCOG_CLEAN_TRANSLATION, false)

    override fun setIncognitoCleanTranslation(value: Boolean) {
        putBool(SettingsKeys.INCOG_CLEAN_TRANSLATION, value)
        mirror.edit().putBoolean(SettingsKeys.INCOG_CLEAN_TRANSLATION, value).commit()
    }

    override fun incognitoActive(): Boolean = bool(SettingsKeys.INCOG_ACTIVE, false)

    override fun setIncognitoActive(value: Boolean) {
        putBool(SettingsKeys.INCOG_ACTIVE, value)
        mirror.edit().putBoolean(SettingsKeys.INCOG_ACTIVE, value).commit()
    }

    override fun incognitoSessionStartedAt(): Long = long(SettingsKeys.INCOG_SESSION_START, 0L)

    override fun setIncognitoSessionStartedAt(value: Long) {
        putLong(SettingsKeys.INCOG_SESSION_START, value)
        mirror.edit().putLong(SettingsKeys.INCOG_SESSION_START, value).commit()
    }

    override fun privacyLevel(): Int = int(SettingsKeys.PRIVACY_LEVEL, 0)

    override fun setPrivacyLevel(value: Int) = putInt(SettingsKeys.PRIVACY_LEVEL, value)

    override fun privacyTracking(): Boolean = bool(SettingsKeys.PRIVACY_TRACKING, true)

    override fun setPrivacyTracking(value: Boolean) = putBool(SettingsKeys.PRIVACY_TRACKING, value)

    override fun privacySocial(): Boolean = bool(SettingsKeys.PRIVACY_SOCIAL, true)

    override fun setPrivacySocial(value: Boolean) = putBool(SettingsKeys.PRIVACY_SOCIAL, value)

    override fun privacyMiners(): Boolean = bool(SettingsKeys.PRIVACY_MINERS, true)

    override fun setPrivacyMiners(value: Boolean) = putBool(SettingsKeys.PRIVACY_MINERS, value)

    override fun privacyFingerprint(): Boolean = bool(SettingsKeys.PRIVACY_FINGERPRINT, true)

    override fun setPrivacyFingerprint(value: Boolean) = putBool(SettingsKeys.PRIVACY_FINGERPRINT, value)

    override fun privacyContent(): Boolean = bool(SettingsKeys.PRIVACY_CONTENT, false)

    override fun setPrivacyContent(value: Boolean) = putBool(SettingsKeys.PRIVACY_CONTENT, value)

    override fun privacyCookies(): Boolean = bool(SettingsKeys.PRIVACY_COOKIES, false)

    override fun setPrivacyCookies(value: Boolean) = putBool(SettingsKeys.PRIVACY_COOKIES, value)

    override fun privacyCleanUrls(): Boolean = bool(SettingsKeys.PRIVACY_CLEAN_URLS, false)

    override fun setPrivacyCleanUrls(value: Boolean) = putBool(SettingsKeys.PRIVACY_CLEAN_URLS, value)

    override fun privacyGpc(): Boolean = bool(SettingsKeys.PRIVACY_GPC, true)

    override fun setPrivacyGpc(value: Boolean) = putBool(SettingsKeys.PRIVACY_GPC, value)

    override fun privacyHttpsOnly(): Boolean = bool(SettingsKeys.PRIVACY_HTTPS_ONLY, false)

    override fun setPrivacyHttpsOnly(value: Boolean) = putBool(SettingsKeys.PRIVACY_HTTPS_ONLY, value)

    override fun clipboardGuard(): Boolean = bool(SettingsKeys.CLIPBOARD_GUARD, true)

    override fun setClipboardGuard(value: Boolean) = putBool(SettingsKeys.CLIPBOARD_GUARD, value)

    override fun subscriptionAutoUpdate(): Boolean = bool(SettingsKeys.SUB_AUTO_UPDATE, true)

    override fun setSubscriptionAutoUpdate(value: Boolean) = putBool(SettingsKeys.SUB_AUTO_UPDATE, value)

    override fun searchEngines(): String? = str(SettingsKeys.SEARCH_ENGINES, "[]")

    override fun setSearchEngines(value: String?) = putStr(SettingsKeys.SEARCH_ENGINES, value)

    override fun searchToolbar(): Boolean =
        bool(SettingsKeys.SEARCH_TOOLBAR, SettingsDefaults.SEARCH_TOOLBAR)

    override fun setSearchToolbar(value: Boolean) = putBool(SettingsKeys.SEARCH_TOOLBAR, value)

    override fun quickEnginePosition(): Int = int(SettingsKeys.QUICK_ENGINE_POS, 0)

    override fun setQuickEnginePosition(value: Int) = putInt(SettingsKeys.QUICK_ENGINE_POS, value)

    override fun gestureSwipeNav(): Boolean = bool(SettingsKeys.GESTURE_SWIPE, true)

    override fun setGestureSwipeNav(value: Boolean) = putBool(SettingsKeys.GESTURE_SWIPE, value)

    override fun gestureVolumeKey(): Boolean = bool(SettingsKeys.GESTURE_VOLUME, false)

    override fun setGestureVolumeKey(value: Boolean) = putBool(SettingsKeys.GESTURE_VOLUME, value)

    override fun pullRefresh(): Boolean = bool(SettingsKeys.PULL_REFRESH, true)

    override fun setPullRefresh(value: Boolean) = putBool(SettingsKeys.PULL_REFRESH, value)

    override fun keepOn(): Boolean = bool(SettingsKeys.KEEP_ON, false)

    override fun setKeepOn(value: Boolean) = putBool(SettingsKeys.KEEP_ON, value)

    override fun videoGesture(): Boolean = bool(SettingsKeys.VIDEO_GESTURE, true)

    override fun setVideoGesture(value: Boolean) = putBool(SettingsKeys.VIDEO_GESTURE, value)

    override fun dntEnabled(): Boolean = bool(SettingsKeys.DNT, false)

    override fun setDntEnabled(value: Boolean) = putBool(SettingsKeys.DNT, value)

    override fun autoExpand(): Boolean = bool(SettingsKeys.AUTO_EXPAND, true)

    override fun setAutoExpand(value: Boolean) = putBool(SettingsKeys.AUTO_EXPAND, value)

    override fun longPressOrder(): String? = str(SettingsKeys.LONG_PRESS_ORDER, null)

    override fun setLongPressOrder(value: String?) {
        putStr(SettingsKeys.LONG_PRESS_ORDER, value)
    }

    override fun passwordEnabled(): Boolean = bool(SettingsKeys.PASSWORD_ENABLED, true)

    override fun setPasswordEnabled(value: Boolean) = putBool(SettingsKeys.PASSWORD_ENABLED, value)

    override fun passwordAutofill(): Boolean = bool(SettingsKeys.PASSWORD_AUTOFILL, false)

    override fun setPasswordAutofill(value: Boolean) = putBool(SettingsKeys.PASSWORD_AUTOFILL, value)

    override fun ignoredPasswordHosts(): String? = str(SettingsKeys.PASSWORD_IGNORED, null)

    override fun setIgnoredPasswordHosts(value: String?) {
        putStr(SettingsKeys.PASSWORD_IGNORED, value)
    }

    override fun homeBackground(): String? = str(SettingsKeys.HOME_BACKGROUND, null)

    override fun setHomeBackground(value: String?) {
        putStr(SettingsKeys.HOME_BACKGROUND, value)
    }

    override fun customCss(): String? = str(SettingsKeys.CUSTOM_CSS, null)

    override fun setCustomCss(value: String?) {
        putStr(SettingsKeys.CUSTOM_CSS, value)
    }

    override fun uiScale(): Int = int(SettingsKeys.UI_SCALE, 100)

    override fun setUiScale(value: Int) = putInt(SettingsKeys.UI_SCALE, value)

    override fun safeBrowsing(): Boolean = bool(SettingsKeys.SAFE_BROWSING, true)

    override fun setSafeBrowsing(value: Boolean) = putBool(SettingsKeys.SAFE_BROWSING, value)

    override fun updatePrompt(): Int {
        // 两档：0 关闭自动检查 / 1 启动时提醒；旧值（仅设置页、通知栏）统一并为 1。
        return int(SettingsKeys.UPDATE_PROMPT, 1).coerceIn(0, 1)
    }

    override fun setUpdatePrompt(value: Int) = putInt(SettingsKeys.UPDATE_PROMPT, value)

    override fun updateIgnoredVersion(): String? =
        str(SettingsKeys.UPDATE_IGNORED, null)?.takeIf { it.isNotBlank() }

    override fun setUpdateIgnoredVersion(value: String?) {
        putStr(SettingsKeys.UPDATE_IGNORED, value)
    }

    override fun updateLastCheck(): Long = long(SettingsKeys.UPDATE_LAST_CHECK, 0L)

    override fun setUpdateLastCheck(value: Long) = putLong(SettingsKeys.UPDATE_LAST_CHECK, value)

    override fun updateLatest(): String? = str(SettingsKeys.UPDATE_LATEST, null)

    override fun setUpdateLatest(value: String?) {
        putStr(SettingsKeys.UPDATE_LATEST, value)
    }

    override fun cctDisabled(): Boolean = bool(SettingsKeys.CCT_DISABLED, false)

    override fun setCctDisabled(value: Boolean) = putBool(SettingsKeys.CCT_DISABLED, value)

    override fun selectionMenuItems(): String? = str(SettingsKeys.SELECTION_MENU_ITEMS, "search,share,copy")

    override fun setSelectionMenuItems(value: String?) {
        putStr(SettingsKeys.SELECTION_MENU_ITEMS, value)
    }

    override fun downloader(): Int = int(SettingsKeys.DOWNLOADER, 0)

    override fun setDownloader(value: Int) = putInt(SettingsKeys.DOWNLOADER, value)

    override fun downloadProtocol(): Int =
        int(SettingsKeys.DOWNLOAD_PROTOCOL, 0).coerceIn(0, 2)

    override fun setDownloadProtocol(value: Int) =
        putInt(SettingsKeys.DOWNLOAD_PROTOCOL, value.coerceIn(0, 2))

    override fun downloadSafDirect(): Boolean =
        bool(SettingsKeys.DOWNLOAD_SAF_DIRECT, true)

    override fun setDownloadSafDirect(value: Boolean) =
        putBool(SettingsKeys.DOWNLOAD_SAF_DIRECT, value)

    override fun downloadParallel(): Int =
        int(SettingsKeys.DOWNLOAD_PARALLEL, 3).coerceIn(1, 4)

    override fun setDownloadParallel(value: Int) =
        putInt(SettingsKeys.DOWNLOAD_PARALLEL, value.coerceIn(1, 4))

    override fun downloadAutoOpen(): Boolean = bool(SettingsKeys.DOWNLOAD_AUTO_OPEN, false)

    override fun setDownloadAutoOpen(value: Boolean) = putBool(SettingsKeys.DOWNLOAD_AUTO_OPEN, value)

    override fun hlsToMp4(): Boolean = bool(SettingsKeys.HLS_TO_MP4, true)

    override fun setHlsToMp4(value: Boolean) = putBool(SettingsKeys.HLS_TO_MP4, value)

    override fun downloadRetry(): Boolean = bool(SettingsKeys.DOWNLOAD_RETRY, true)

    override fun setDownloadRetry(value: Boolean) = putBool(SettingsKeys.DOWNLOAD_RETRY, value)

    override fun downloadDir(): String? = str(SettingsKeys.DOWNLOAD_DIR, null)

    override fun setDownloadDir(value: String?) {
        putStr(SettingsKeys.DOWNLOAD_DIR, value)
    }

    override fun downloadWifiOnly(): Boolean = bool(SettingsKeys.DOWNLOAD_WIFI_ONLY, false)

    override fun setDownloadWifiOnly(value: Boolean) =
        putBool(SettingsKeys.DOWNLOAD_WIFI_ONLY, value)

    override fun webrtcProtect(): Boolean = bool(SettingsKeys.WEBRTC_PROTECT, false)

    override fun setWebrtcProtect(value: Boolean) = putBool(SettingsKeys.WEBRTC_PROTECT, value)

    override fun updateAutoCheck(): Boolean = bool(SettingsKeys.UPDATE_AUTO, false)

    override fun setUpdateAutoCheck(value: Boolean) = putBool(SettingsKeys.UPDATE_AUTO, value)

    override fun updateLastNotified(): Long = long(SettingsKeys.UPDATE_LAST_NOTIFIED, 0L)

    override fun setUpdateLastNotified(value: Long) = putLong(SettingsKeys.UPDATE_LAST_NOTIFIED, value)

    override fun passwordBiometric(): Boolean = bool(SettingsKeys.PASSWORD_BIOMETRIC, false)

    override fun setPasswordBiometric(value: Boolean) =
        putBool(SettingsKeys.PASSWORD_BIOMETRIC, value)

    override fun toolbarPosition(): Int = int(SettingsKeys.TOOLBAR_POS, 0)

    override fun setToolbarPosition(value: Int) = putInt(SettingsKeys.TOOLBAR_POS, value)

    override fun toolbarAutoHide(): Boolean =
        bool(SettingsKeys.TOOLBAR_AUTOHIDE, SettingsDefaults.TOOLBAR_AUTOHIDE)

    override fun setToolbarAutoHide(value: Boolean) =
        putBool(SettingsKeys.TOOLBAR_AUTOHIDE, value)

    override fun toolbarOrder(): String? = str(SettingsKeys.TOOLBAR_ORDER, "back,forward,home,tabs,menu")

    override fun setToolbarOrder(value: String?) = putStr(SettingsKeys.TOOLBAR_ORDER, value)

    // ---- MCP 服务 ----

    override fun mcpEnabled(): Boolean =
        bool(SettingsKeys.MCP_ENABLED, SettingsDefaults.MCP_ENABLED)

    override fun setMcpEnabled(value: Boolean) = putBool(SettingsKeys.MCP_ENABLED, value)

    override fun mcpPort(): Int = int(SettingsKeys.MCP_PORT, SettingsDefaults.MCP_PORT)

    override fun setMcpPort(value: Int) = putInt(SettingsKeys.MCP_PORT, value)

    override fun mcpToken(): String? = str(SettingsKeys.MCP_TOKEN, null)

    override fun setMcpToken(value: String?) = putStr(SettingsKeys.MCP_TOKEN, value)

    override fun mcpAllowLan(): Boolean =
        bool(SettingsKeys.MCP_ALLOW_LAN, SettingsDefaults.MCP_ALLOW_LAN)

    override fun setMcpAllowLan(value: Boolean) = putBool(SettingsKeys.MCP_ALLOW_LAN, value)

    override fun mcpForeground(): Boolean =
        bool(SettingsKeys.MCP_FOREGROUND, SettingsDefaults.MCP_FOREGROUND)

    override fun setMcpForeground(value: Boolean) = putBool(SettingsKeys.MCP_FOREGROUND, value)

    override fun mcpAllowPageRead(): Boolean =
        bool(SettingsKeys.MCP_ALLOW_PAGE_READ, SettingsDefaults.MCP_ALLOW_PAGE_READ)

    override fun setMcpAllowPageRead(value: Boolean) =
        putBool(SettingsKeys.MCP_ALLOW_PAGE_READ, value)

    override fun mcpAllowTabs(): Boolean =
        bool(SettingsKeys.MCP_ALLOW_TABS, SettingsDefaults.MCP_ALLOW_TABS)

    override fun setMcpAllowTabs(value: Boolean) = putBool(SettingsKeys.MCP_ALLOW_TABS, value)

    override fun mcpAllowPageAction(): Boolean =
        bool(SettingsKeys.MCP_ALLOW_PAGE_ACTION, SettingsDefaults.MCP_ALLOW_PAGE_ACTION)

    override fun setMcpAllowPageAction(value: Boolean) =
        putBool(SettingsKeys.MCP_ALLOW_PAGE_ACTION, value)

    override fun mcpAllowJs(): Boolean =
        bool(SettingsKeys.MCP_ALLOW_JS, SettingsDefaults.MCP_ALLOW_JS)

    override fun setMcpAllowJs(value: Boolean) = putBool(SettingsKeys.MCP_ALLOW_JS, value)

    override fun mcpAllowBookmarkWrite(): Boolean =
        bool(SettingsKeys.MCP_ALLOW_BOOKMARK_WRITE, SettingsDefaults.MCP_ALLOW_BOOKMARK_WRITE)

    override fun setMcpAllowBookmarkWrite(value: Boolean) =
        putBool(SettingsKeys.MCP_ALLOW_BOOKMARK_WRITE, value)

    override fun mcpAllowHistoryWrite(): Boolean =
        bool(SettingsKeys.MCP_ALLOW_HISTORY_WRITE, SettingsDefaults.MCP_ALLOW_HISTORY_WRITE)

    override fun setMcpAllowHistoryWrite(value: Boolean) =
        putBool(SettingsKeys.MCP_ALLOW_HISTORY_WRITE, value)

    override fun mcpAllowIncognito(): Boolean =
        bool(SettingsKeys.MCP_ALLOW_INCOGNITO, SettingsDefaults.MCP_ALLOW_INCOGNITO)

    override fun setMcpAllowIncognito(value: Boolean) =
        putBool(SettingsKeys.MCP_ALLOW_INCOGNITO, value)

    override fun mcpAllowCapture(): Boolean =
        bool(SettingsKeys.MCP_ALLOW_CAPTURE, SettingsDefaults.MCP_ALLOW_CAPTURE)

    override fun setMcpAllowCapture(value: Boolean) =
        putBool(SettingsKeys.MCP_ALLOW_CAPTURE, value)

    override fun mcpCaptureBody(): Boolean =
        bool(SettingsKeys.MCP_CAPTURE_BODY, SettingsDefaults.MCP_CAPTURE_BODY)

    override fun setMcpCaptureBody(value: Boolean) =
        putBool(SettingsKeys.MCP_CAPTURE_BODY, value)

    override fun mcpAllowDiag(): Boolean =
        bool(SettingsKeys.MCP_ALLOW_DIAG, SettingsDefaults.MCP_ALLOW_DIAG)

    override fun setMcpAllowDiag(value: Boolean) =
        putBool(SettingsKeys.MCP_ALLOW_DIAG, value)

    // ---- 导出分组 ----

    private fun boolKeys() = arrayOf(
        SettingsKeys.NIGHT_CSS,
        SettingsKeys.TAB_CARD_GRID,
        SettingsKeys.CARD_BORDER,
        SettingsKeys.HLS_TO_MP4,
        SettingsKeys.JS_CONSOLE_LOG,
        SettingsKeys.UI_STYLE,
        SettingsKeys.HOME_SEARCH_SHOW
    )

    private fun intKeys() = arrayOf(
        SettingsKeys.APP_FLAG, SettingsKeys.WEB_FLAG, SettingsKeys.FULL_SCREEN_MODE,
        SettingsKeys.SEARCH_MODE, SettingsKeys.TEXT_SIZE, SettingsKeys.UA_CHOICE,
        SettingsKeys.SCREEN_ORIENTATION, SettingsKeys.KEY_BACK, SettingsKeys.KEY_FORWARD,
        SettingsKeys.KEY_HOME, SettingsKeys.KEY_TAB, SettingsKeys.KEY_MENU,
        SettingsKeys.GESTURE_TOOLBAR_LEFT, SettingsKeys.GESTURE_TOOLBAR_RIGHT,
        SettingsKeys.URL_BOX_BEHAVIOR, SettingsKeys.APP_UI, SettingsKeys.LOGO_CHOICE,
        SettingsKeys.URL_BAR_COLOR, SettingsKeys.FAB, SettingsKeys.RESTORE_CLOSED_TABS,
        SettingsKeys.CLEAR_DATA, SettingsKeys.CLEAR_DATA_ON_EXIT, SettingsKeys.VERSION,
        SettingsKeys.NIGHT_FILTER, SettingsKeys.BOOKMARKS_ORDER, SettingsKeys.FAV_INFO,
        SettingsKeys.LOGO_INFO, SettingsKeys.SEARCH_INFO, SettingsKeys.CUSTOM_INFO,
        SettingsKeys.SEARCH_SUGGESTION, SettingsKeys.READER_TEXT_SIZE,
        SettingsKeys.READER_THEME_COLOR, SettingsKeys.VIDEO_ORIENTATION,
        SettingsKeys.BOOKMARKS_VIEW_MODE, SettingsKeys.LAB_FLAG, SettingsKeys.DUA_CHOICE,
        SettingsKeys.WEB_FLAG_2, SettingsKeys.QUICK_ENGINE_POS, SettingsKeys.SETTINGS_CARD_ALPHA,
        SettingsKeys.TABS_PANEL_TRANSPARENCY, SettingsKeys.MENU_SCROLL_STYLE,
        SettingsKeys.UPDATE_PROMPT
    )

    private fun longKeys() = arrayOf(
        SettingsKeys.UPDATER_FILTER_SUBSCRIPTIONS, SettingsKeys.UPDATER_SCRIPTS,
        SettingsKeys.UPDATE_LAST_CHECK
    )

    private fun stringKeys() = arrayOf(
        SettingsKeys.HOME, SettingsKeys.SEARCH_URL, SettingsKeys.UA_STRING,
        SettingsKeys.CSS_THEME, SettingsKeys.TAG_HOME, SettingsKeys.DL_MANAGER,
        SettingsKeys.LANGUAGE, SettingsKeys.VIDEO_PLAYER, SettingsKeys.DISPLAYED_MENUS,
        SettingsKeys.READER_CUSTOM_CSS, SettingsKeys.SEARCH_TOOLBAR_DISABLED,
        SettingsKeys.DUA_STRING, SettingsKeys.SEARCH_TOOLBAR_ORDER,
        SettingsKeys.HIDDEN_MENUS, SettingsKeys.SEARCH_SHORTCUTS, SettingsKeys.TOOLBAR_ORDER,
        SettingsKeys.BOOKMARK_SORT, SettingsKeys.BOOKMARK_SIZE, SettingsKeys.BOOKMARK_ICON,
        SettingsKeys.UPDATE_IGNORED, SettingsKeys.UPDATE_LATEST
    )
}

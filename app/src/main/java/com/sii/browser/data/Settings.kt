// SPDX-License-Identifier: LicenseRef-Sii-NC
// Copyright (c) 2026 wanwuwww
package com.sii.browser.data

import org.json.JSONObject

/**
 * 设置访问接口。
 * 实现：[SharedPreferencesSettings]。
 */
interface Settings :
    AppearanceSettings,
    HomeSearchSettings,
    UserAgentSettings,
    BehaviorSettings,
    DataSettings,
    WebPageSettings,
    McpSettings {

    /** 语言。 */
    fun language(): String?

    fun setLanguage(language: String?)

    /** 当前是否暗色主题。 */
    fun isNightMode(): Boolean

    /** 是否自动跟随系统夜间模式。 */
    fun nightFollowSystem(): Boolean

    /** 菜单强制暗色是否开启（NIGHT_MODE==3，独立于跟随系统开关）。 */
    fun nightForceDark(): Boolean

    fun setNightFollowSystem(follow: Boolean)

    /** 直接设置夜间模式：1=跟随系统，2=强制亮，3=强制暗。 */
    fun setNightMode(value: Int)

    /** 菜单手动切换夜间模式：开=强制暗色（优先于自动跟随系统），关=恢复跟随系统。返回切换后是否为暗色。 */
    fun toggleNightMode(): Boolean

    /** 首次启动协议版本。 */
    fun agreementVersion(): Int

    /** 标记协议已同意。 */
    fun markAgreementDone()

    /** 设置数据版本（用于迁移）。 */
    fun dataVersion(): Int

    fun setDataVersion(version: Int)

    /** 忽略 SSL 警告次数。 */
    fun ignoredSslWarning(): Int

    fun setIgnoredSslWarning(value: Int)

    /** 广告拦截累计次数。 */
    fun adBlockedTimes(): Int

    fun addAdBlockedTimes(delta: Int)

    /** 广告拦截总开关。 */
    fun adBlockEnabled(): Boolean

    fun setAdBlockEnabled(value: Boolean)

    /** 「查看本页请求」的元数据记录（仅内存，与 MCP 抓包无关）。 */
    fun pageRequests(): Boolean

    fun setPageRequests(value: Boolean)

    /** 是否启用内建广告规则。 */
    fun builtinRulesEnabled(): Boolean

    fun setBuiltinRulesEnabled(value: Boolean)

    /** 订阅自动更新间隔（天）。 */
    fun subscriptionIntervalDays(): Int

    fun setSubscriptionIntervalDays(value: Int)

    /** 标签栏位置：0 = 地址栏上方，1 = 工具栏上方。 */
    fun tabBarPosition(): Int

    fun setTabBarPosition(value: Int)

    /** 标签页卡片网格；false = 简约列表。 */
    fun tabCardGrid(): Boolean

    fun setTabCardGrid(value: Boolean)

    /** 标签网格列数：2 或 3。 */
    fun tabGridColumns(): Int

    fun setTabGridColumns(value: Int)

    /** 标签网格是否显示网页缩略图。 */
    fun tabShowThumb(): Boolean

    fun setTabShowThumb(value: Boolean)

    /** 界面风格：0 = 经典，1 = 新版。 */
    fun uiStyle(): Int

    fun setUiStyle(value: Int)

    /** 设置页卡片背景透明度，100 = 不透明。 */
    fun settingsCardAlpha(): Int

    fun setSettingsCardAlpha(value: Int)

    /** 玻璃质感强度：0 不透明 → 100 最通透。 */
    fun glassStrength(): Int

    fun setGlassStrength(value: Int)

    /** 实时模糊（Android 12+）。 */
    fun glassBlur(): Boolean

    fun setGlassBlur(value: Boolean)

    /** 主页风格：0 玻璃 / 1 极简 / 2 杂志。 */
    fun homeStyle(): Int

    fun setHomeStyle(value: Int)

    /** 主页搜索框是否显示。 */
    fun homeSearchShow(): Boolean

    fun setHomeSearchShow(value: Boolean)

    /** 标签页背景透明度，0 = 不透明，100 = 全透明。 */
    fun tabsPanelTransparency(): Int

    fun setTabsPanelTransparency(value: Int)

    /** 卡片外框开关。 */
    fun cardBorder(): Boolean

    /** 卡片背景色：0 跟随主题色 / 1 默认 / 2 浅灰 / 3 深灰。 */
    fun cardBg(): Int

    fun setCardBg(value: Int)

    fun setCardBorder(value: Boolean)

    /** 规则订阅/脚本的最近检查时间。 */
    fun lastUpdaterCheck(name: String): Long

    fun setLastUpdaterCheck(name: String, time: Long)

    /** 全部设置导出为 JSON。 */
    fun exportToJson(includePrivate: Boolean): JSONObject
}

/** 外观设置。 */
interface AppearanceSettings {
    /** 网页强制暗色 CSS（nightcss）。 */
    fun nightCssEnabled(): Boolean

    fun setNightCssEnabled(enabled: Boolean)

    /** 夜间滤镜（nightfilter）。 */
    fun nightFilter(): Int

    fun setNightFilter(value: Int)

    /** CSS 主题（csstheme）。 */
    fun cssTheme(): String?

    fun setCssTheme(value: String?)

    /** 字体大小百分比（textsize，100 为默认）。 */
    fun textSize(): Int

    fun setTextSize(value: Int)

    /** 阅读模式字号（readertextsize）。 */
    fun readerTextSize(): Int

    fun setReaderTextSize(value: Int)

    /** 阅读模式主题色（readerthemecolor）。 */
    fun readerThemeColor(): Int

    fun setReaderThemeColor(value: Int)

    /** 阅读模式自定义 CSS（readercustomcss）。 */
    fun readerCustomCss(): String?

    fun setReaderCustomCss(value: String?)

    /** 地址栏颜色（urlbarcolor）。 */
    fun urlBarColor(): Int

    fun setUrlBarColor(value: Int)

    /** Logo 选择（logochioce）。 */
    fun logoChoice(): Int

    fun setLogoChoice(value: Int)

    /** 应用 UI 布局（appui2）。 */
    fun appUi(): Int

    fun setAppUi(value: Int)

    /** 皮肤（skin）。 */
    fun skin(): String?

    fun setSkin(value: String?)

    /** 网页文字颜色：0 原色 / 1 跟随主色 / 2 仅白天 / 3 仅夜晚。 */
    fun webTextColor(): Int

    fun setWebTextColor(value: Int)

    /** 原生 WebView 暗色。 */
    fun webViewDark(): Boolean

    fun setWebViewDark(value: Boolean)

    /** 地址栏样式：0 无外框 / 1 精简横线 / 2 普通圆角外框。 */
    fun addressStyle(): Int

    fun setAddressStyle(value: Int)

    /** 主页/空白页聚焦地址栏时自动填入剪贴板链接。 */
    fun autoFillClipboard(): Boolean

    fun setAutoFillClipboard(value: Boolean)

    /** 搜索建议分类顺序，靠近搜索框的分类在前。 */
    fun menuColumns(): Int
    fun setMenuColumns(value: Int)
    /** 菜单页滚动方向：0 上下，1 左右。 */
    fun menuScrollStyle(): Int
    fun setMenuScrollStyle(value: Int)
    fun menuIconSize(): Int
    fun setMenuIconSize(value: Int)
    fun menuTextSize(): Int
    fun setMenuTextSize(value: Int)
    fun fixedMenuIconSize(): Int
    fun setFixedMenuIconSize(value: Int)
    fun fixedMenuTextSize(): Int
    fun setFixedMenuTextSize(value: Int)
    fun menuShowLabels(): Boolean
    fun setMenuShowLabels(value: Boolean)
    fun fixedMenuShowLabels(): Boolean
    fun setFixedMenuShowLabels(value: Boolean)

    fun fixedMenuHeight(): Int
    fun setFixedMenuHeight(value: Int)
    fun homeLogoIconShow(): Boolean

    /** Logo 显示内容：0 = 文字，1 = 图标，2 = 时间。 */
    fun homeLogoMode(): Int

    fun setHomeLogoMode(value: Int)
    fun setHomeLogoIconShow(value: Boolean)
    fun homeLogoIconSize(): Int
    fun setHomeLogoIconSize(value: Int)

    fun suggestOrder(): String

    fun setSuggestOrder(value: String)

    /** 识别网站主题色总开关。 */
    fun themeColorEnabled(): Boolean

    fun setThemeColorEnabled(value: Boolean)

    /** 网站主题色作用于底部工具栏。 */
    fun themeColorToolbar(): Boolean

    fun setThemeColorToolbar(value: Boolean)

    /** 网站主题色作用于地址栏。 */
    fun themeColorAddress(): Boolean

    fun setThemeColorAddress(value: Boolean)

    /** 网站主题色作用于手机状态栏。 */
    fun themeColorStatus(): Boolean

    fun setThemeColorStatus(value: Boolean)

    /** 网站主题色作用于底部菜单栏。 */
    fun themeColorMenu(): Boolean

    fun setThemeColorMenu(value: Boolean)

    /** 网站主题色作用于长按菜单。 */
    fun themeColorLongPress(): Boolean

    fun setThemeColorLongPress(value: Boolean)

    /** 夜间模式也强制使用网站主题色作为背景。 */
    fun themeColorForceBackground(): Boolean

    fun setThemeColorForceBackground(value: Boolean)
}

/** 主页与搜索设置。 */
interface HomeSearchSettings {
    /** 主页 URL（home，含 about:home / about:blank / about:bookmarks / about:links 特例）。 */
    fun homeUrl(): String

    fun setHomeUrl(url: String)

    /** 主页按键动作（keyhome）。 */
    fun homeKey(): Int

    fun setHomeKey(value: Int)

    /** 新标签主页（taghome）。 */
    fun tagHomeUrl(): String?

    fun setTagHomeUrl(value: String?)

    /** 搜索引擎 URL（searchurl）。 */
    fun searchEngineUrl(): String?

    fun setSearchEngineUrl(value: String?)

    /** 搜索模式（search2）。 */
    fun searchMode(): Int

    fun setSearchMode(value: Int)

    /** 搜索建议开关（searchsuggestion）。 */
    fun searchSuggestion(): Int

    fun setSearchSuggestion(value: Int)

    /** 搜索建议来源：本地历史。 */
    fun searchSuggestHistory(): Boolean

    fun setSearchSuggestHistory(value: Boolean)

    /** 搜索建议来源：书签。 */
    fun searchSuggestBookmarks(): Boolean

    fun setSearchSuggestBookmarks(value: Boolean)

    /** 搜索建议来源：联网联想词。 */
    fun searchSuggestOnline(): Boolean

    fun setSearchSuggestOnline(value: Boolean)

    /** 搜索工具栏禁用项（searchtoolbardisabled，格式 "-1,-10"）。 */
    fun searchToolbarDisabled(): String?

    fun setSearchToolbarDisabled(value: String?)

    /** 搜索工具栏顺序（searchtoolbarorder）。 */
    fun searchToolbarOrder(): String?

    fun setSearchToolbarOrder(value: String?)

    /** 搜索快捷词（searchshortcuts）。 */
    fun searchShortcuts(): String?

    fun setSearchShortcuts(value: String?)
}

/** 浏览器标识（UA）设置。 */
interface UserAgentSettings {
    /** 自定义 UA（uastring）。 */
    fun userAgent(): String?

    fun setUserAgent(value: String?)

    /** 桌面模式 UA（duastring）。 */
    fun desktopUserAgent(): String?

    fun setDesktopUserAgent(value: String?)

    /** 默认电脑模式网站列表（desktopsites，逗号分隔 host）。 */
    fun desktopSites(): String

    fun setDesktopSites(value: String)

    /** UA 选择（uachoice）。 */
    fun uaChoice(): Int

    fun setUaChoice(value: Int)

    /** 桌面 UA 选择（duachoice）。 */
    fun duaChoice(): Int

    fun setDuaChoice(value: Int)

}

/** 行为设置（按键/手势/菜单/窗口）。 */
interface BehaviorSettings {
    /** 地址栏行为（urlbox）。 */
    fun urlBoxBehavior(): Int

    fun setUrlBoxBehavior(value: Int)

    /** 搜索建议展开方向：0 搜索框上方（默认）、1 搜索框下方。 */
    fun suggestionDirection(): Int

    fun setSuggestionDirection(value: Int)

    /** 搜索建议显示条数（默认 6）。 */
    fun suggestionCount(): Int

    fun setSuggestionCount(value: Int)

    /** 主页搜索框位置：0 顶部、1 居中（默认）、2 底部。 */
    fun homeLayoutPos(): Int

    fun setHomeLayoutPos(value: Int)

    /** 主页 Logo 文案。 */
    fun homeLogoText(): String

    fun setHomeLogoText(value: String)

    /** 常用网站列数（4/5/6）。 */
    fun homeGridColumns(): Int

    fun setHomeGridColumns(value: Int)

    /** 网站图标大小：0 小 / 1 中 / 2 大。 */
    fun homeIconSize(): Int

    fun setHomeIconSize(value: Int)

    /** 网站图标形状：0 圆形 / 1 圆角方形。 */
    fun homeIconShape(): Int

    fun setHomeIconShape(value: Int)

    /** 是否显示网站名称。 */
    fun homeShowLabels(): Boolean

    fun setHomeShowLabels(value: Boolean)

    /** 新版悬浮风格下，主页是否把地址栏显示在顶部。 */
    fun homeTopAddress(): Boolean

    fun setHomeTopAddress(value: Boolean)

    /** 网站图标圆角：0 无 / 1 小 / 2 中 / 3 大。 */
    fun homeIconRadius(): Int

    fun setHomeIconRadius(value: Int)

    /** 搜索框圆角：0 小 / 1 中 / 2 大。 */
    fun homeSearchRadius(): Int

    fun setHomeSearchRadius(value: Int)

    /** 搜索框高度：0 小 / 1 中 / 2 大。 */
    fun homeSearchHeight(): Int

    fun setHomeSearchHeight(value: Int)

    /** 背景图模糊：0 关 / 1 小 / 2 中 / 3 大。 */
    fun homeBgBlur(): Int

    fun setHomeBgBlur(value: Int)

    /** 背景图模糊百分比，-1 表示尚未从旧档位迁移。 */
    fun homeBgBlurPercent(): Int

    fun setHomeBgBlurPercent(value: Int)

    /** Logo 字号（sp，0 = 默认 34）。 */
    fun homeLogoSize(): Int

    fun setHomeLogoSize(value: Int)

    /** Logo 字重：0 常规 / 1 加粗。 */
    fun homeLogoWeight(): Int

    fun setHomeLogoWeight(value: Int)

    /** Logo 颜色：null 跟随主题，accent 主题色，#RRGGBB 自定义。 */
    fun homeLogoColor(): String?

    fun setHomeLogoColor(value: String?)

    /** 网站名称字号（sp，0 = 默认 12）。 */
    fun homeLabelSize(): Int

    fun setHomeLabelSize(value: Int)

    /** 网站行间距（dp，-1 = 默认 8）。 */
    fun homeCellGap(): Int

    fun setHomeCellGap(value: Int)

    /** 图标统一主题色。 */
    fun homeIconAccent(): Boolean

    fun setHomeIconAccent(value: Boolean)

    /** 背景图遮罩（0~100）。 */
    fun homeBgOverlay(): Int

    fun setHomeBgOverlay(value: Int)

    /** 显示“常用网站”标题。 */
    fun homeShowSitesTitle(): Boolean

    fun setHomeShowSitesTitle(value: Boolean)

    /** 退出时清理。 */
    fun exitClearEnabled(): Boolean

    fun setExitClearEnabled(value: Boolean)

    /** 退出时清理的类目（逗号分隔）。 */
    fun exitClearItems(): String

    fun setExitClearItems(value: String)

    /** 双击网页动作：0 无 / 1 回到顶部 / 2 刷新 / 3 打开主页。 */
    fun gestureDoubleTap(): Int

    fun setGestureDoubleTap(value: Int)

    /** 游戏模式。 */
    fun gameMode(): Boolean

    fun setGameMode(value: Boolean)

    /** 翻译服务：0 MyMemory / 1 LibreTranslate / 2 Google / 3 自定义。 */
    fun translateProvider(): Int

    fun setTranslateProvider(value: Int)

    /** 翻译模式：0 关闭 / 1 云翻译 / 2 离线翻译。 */
    fun translateMode(): Int

    fun setTranslateMode(value: Int)

    /** 翻译接口地址（自定义 / LibreTranslate 实例）。 */
    fun translateEndpoint(): String

    fun setTranslateEndpoint(value: String)

    /** 目标语言（空 = 跟随界面）。 */
    fun translateTarget(): String

    fun setTranslateTarget(value: String)

    /** 检测到英文页面时提示翻译。 */
    fun translateDetect(): Boolean

    fun setTranslateDetect(value: Boolean)

    /** 双语对照：保留原文，在原文后插入译文。 */
    fun translateDual(): Boolean

    fun setTranslateDual(value: Boolean)

    /** 初次翻译收集的元素数量。 */
    fun translateCollect(): Int

    fun setTranslateCollect(value: Int)

    /** 每次动态扫描处理的元素数量。 */
    fun translateScan(): Int

    fun setTranslateScan(value: Int)

    /** 动态扫描间隔（秒）。 */
    fun translateIntervalMs(): Int

    fun setTranslateIntervalMs(value: Int)

    /** 单次请求翻译的字符数。 */
    fun translateChunk(): Int

    fun setTranslateChunk(value: Int)

    /** 自动翻译总量上限（文本节点数）。 */
    fun translateLimit(): Int

    fun setTranslateLimit(value: Int)

    /** 自定义翻译请求方法：0 GET、1 POST。 */
    fun translateCustomMethod(): Int

    fun setTranslateCustomMethod(value: Int)

    /** 自定义请求头，每行 Key: Value。 */
    fun translateCustomHeaders(): String

    fun setTranslateCustomHeaders(value: String)

    /** 自定义 POST 请求体模板。 */
    fun translateCustomBody(): String

    fun setTranslateCustomBody(value: String)

    /** 自定义响应字段路径，如 data.translations[0].text。 */
    fun translateCustomPath(): String

    fun setTranslateCustomPath(value: String)

    fun translateBaiduAppId(): String

    fun setTranslateBaiduAppId(value: String)

    fun translateBaiduKey(): String

    fun setTranslateBaiduKey(value: String)

    /** 普通模式网站限制：阻止弹窗。 */
    fun siteBlockPopup(): Boolean

    fun setSiteBlockPopup(value: Boolean)

    /** 普通模式网站限制：禁用数据库。 */
    fun siteBlockDb(): Boolean

    fun setSiteBlockDb(value: Boolean)

    /** 普通模式网站限制：禁用 DOM Storage。 */
    fun siteBlockDom(): Boolean

    fun setSiteBlockDom(value: Boolean)

    /** 普通模式网站限制：禁止文件访问。 */
    fun siteBlockFile(): Boolean

    fun setSiteBlockFile(value: Boolean)

    /** 普通模式网站限制：禁用 JavaScript。 */
    fun siteBlockJs(): Boolean

    fun setSiteBlockJs(value: Boolean)

    /** 网页跳转外部应用：0 每次询问、1 始终允许、2 禁止。 */
    fun openAppPolicy(): Int

    fun setOpenAppPolicy(value: Int)

    /** 关闭标签后是否提示可在“最近关闭”恢复。 */
    fun closeTabToast(): Boolean

    fun setCloseTabToast(value: Boolean)

    /** 流量节省请求头（Save-Data）。 */
    fun saveDataEnabled(): Boolean

    fun setSaveDataEnabled(value: Boolean)

    /** 自定义字体文件路径（应用私有目录）。 */
    fun customFont(): String?

    fun setCustomFont(value: String?)

    /** 全部网站默认权限：0 询问、1 允许、2 拒绝。 */
    fun permissionGeo(): Int

    fun setPermissionGeo(value: Int)

    fun permissionCam(): Int

    fun setPermissionCam(value: Int)

    fun permissionMic(): Int

    fun setPermissionMic(value: Int)

    /** 用户脚本自动更新间隔天数，0 表示关闭。 */
    fun scriptUpdateDays(): Int

    fun setScriptUpdateDays(value: Int)

    /** 上次脚本自动更新时间（毫秒）。 */
    fun scriptLastUpdate(): Long

    fun setScriptLastUpdate(value: Long)

    fun homeLogoShow(): Boolean

    fun setHomeLogoShow(value: Boolean)

    fun homeSearchRadiusCustom(): Int

    fun setHomeSearchRadiusCustom(value: Int)

    fun homeSearchAlpha(): Int

    fun setHomeSearchAlpha(value: Int)

    fun siteNoImage(): Boolean

    fun setSiteNoImage(value: Boolean)

    fun siteBackNoReload(): Boolean

    fun setSiteBackNoReload(value: Boolean)

    fun siteAllowRedirect(): Boolean

    fun setSiteAllowRedirect(value: Boolean)

    /** 全局页面重定向策略：0 询问、1 允许、2 拒绝。 */
    fun siteRedirectPolicy(): Int

    fun setSiteRedirectPolicy(value: Int)

    /** 新建标签与撤销按钮是否交换：false 新建在左，true 撤销在左。 */
    fun tabActionSwap(): Boolean

    fun setTabActionSwap(value: Boolean)

    fun tabUndoVisible(): Boolean

    fun setTabUndoVisible(value: Boolean)

    /** 非正常退出后是否提醒恢复标签。 */
    fun exitRestorePrompt(): Boolean

    fun setExitRestorePrompt(value: Boolean)

    /** 返回键退出/最小化前的二次确认。 */
    fun exitConfirm(): Boolean

    fun setExitConfirm(value: Boolean)

    fun sniffEnabled(): Boolean

    fun setSniffEnabled(value: Boolean)

    /** 启动行为：0 打开主页 / 1 恢复上次页面 / 2 空白页。 */
    fun startupBehavior(): Int

    fun setStartupBehavior(value: Int)

    /** 设置快照（导出用）。 */
    fun snapshot(): Map<String, Any>

    /** 恢复设置快照。 */
    fun restore(values: Map<String, Any>)

    /** 全屏模式（fullscreenmode）。 */
    fun fullScreenMode(): Int

    fun setFullScreenMode(value: Int)

    /** 屏幕方向（screenOrientation）。 */
    fun screenOrientation(): Int

    fun setScreenOrientation(value: Int)

    /** 恢复关闭标签（restoreclosedtabs）。 */
    fun restoreClosedTabs(): Int

    fun setRestoreClosedTabs(value: Int)

    /** 悬浮按钮（fab）。 */
    fun fab(): Int

    fun setFab(value: Int)

    /** 物理按键动作（keyback/keyforward/keyhome/keytab/keymenu）。 */
    fun keyAction(key: String): Int

    fun setKeyAction(key: String, value: Int)

    /** 工具栏左右滑动手势动作。 */
    fun gestureToolbarLeft(): Int

    fun setGestureToolbarLeft(value: Int)

    fun gestureToolbarRight(): Int

    fun setGestureToolbarRight(value: Int)

    /** 菜单显示/隐藏项（displayedmenus / hiddenmenus，逗号分隔 id）。 */
    fun displayedMenus(): String?

    fun setDisplayedMenus(value: String?)

    fun hiddenMenus(): String?

    fun setHiddenMenus(value: String?)

    /** 固定工具栏项（fixeditems，逗号分隔 id，对齐 index.html fixedItems）。 */
    fun fixedItems(): String?

    fun setFixedItems(value: String?)

    /** 固定菜单位置：0 = 移动项上方，1 = 移动项下方。 */
    fun fixedMenuPosition(): Int

    fun setFixedMenuPosition(value: Int)

    /** 视频方向（videoorientation）。 */
    fun videoOrientation(): Int

    fun setVideoOrientation(value: Int)
}

/** 数据与标签设置。 */
interface DataSettings {
    /** 退出时清除数据（cleardataonexit2）。 */
    fun clearDataOnExit(): Int

    fun setClearDataOnExit(value: Int)

    /** 清除数据范围（cleardata2）。 */
    fun clearData(): Int

    fun setClearData(value: Int)

    /** 书签排序（bookmarksorder）。 */
    fun bookmarksOrder(): Int

    fun setBookmarksOrder(value: Int)

    /** 书签排序：az / za / recent / manual。 */
    fun bookmarkSort(): String

    fun setBookmarkSort(value: String)

    /** 书签尺寸：lg / md / sm。 */
    fun bookmarkSize(): String

    fun setBookmarkSize(value: String)

    /** 书签图标：initial / favicon / none。 */
    fun bookmarkIcon(): String

    fun setBookmarkIcon(value: String)

    /** 书签视图模式（bookmarksviewmode）。 */
    fun bookmarksViewMode(): Int

    fun setBookmarksViewMode(value: Int)

    /** 下载管理器（dlmanager）。 */
    fun downloadManager(): String?

    fun setDownloadManager(value: String?)

    /** 视频播放器（videoplayer）。 */
    fun videoPlayer(): String?

    fun setVideoPlayer(value: String?)
}

/** 网页行为与标志位。 */
interface WebPageSettings {
    /** 网页行为位掩码（webflag）。 */
    fun webPageBehavior(): WebPageBehavior

    fun setWebPageBehavior(behavior: WebPageBehavior)

    /** 应用标志（appflag）。 */
    fun appFlag(): Int

    fun setAppFlag(value: Int)

    /** 网页标志 2（webflag2）。 */
    fun webFlag2(): Int

    fun setWebFlag2(value: Int)

    /** 实验标志（labflag）。 */
    fun labFlag(): Int

    fun setLabFlag(value: Int)

    /** 软键盘弹出时自动缩放内容。 */
    fun softInputResizeEnabled(): Boolean

    fun setSoftInputResizeEnabled(enabled: Boolean)

    /** HTTPS 优先。 */
    fun httpsOnly(): Boolean

    fun setHttpsOnly(value: Boolean)

    /** 忽略 SSL 证书错误。 */
    fun ignoreSslError(): Boolean

    fun setIgnoreSslError(value: Boolean)

    /** 阻止第三方 Cookie。 */
    fun thirdPartyCookiesBlocked(): Boolean

    fun setThirdPartyCookiesBlocked(value: Boolean)

    /** 下载前询问。 */
    fun downloadConfirm(): Boolean

    fun setDownloadConfirm(value: Boolean)

    /** 底部工具栏隐藏按钮。 */
    fun toolbarHidden(): String?

    fun setToolbarHidden(value: String?)

    /** 长按菜单隐藏项（逗号分隔 id）。 */
    fun longPressHidden(): String?

    fun setLongPressHidden(value: String?)

    /** 简化浏览器标识。 */
    fun uaSimplified(): Boolean

    fun setUaSimplified(value: Boolean)

    /** 默认电脑模式网站中被关闭的条目（逗号分隔）。 */
    fun desktopSitesDisabled(): String

    fun setDesktopSitesDisabled(value: String)

    /** 全局电脑模式开关（菜单弹窗控制，持久化）。 */
    fun desktopMode(): Boolean

    fun setDesktopMode(value: Boolean)

    /** 仅 Wi-Fi 加载图片。 */
    fun wifiImagesOnly(): Boolean

    fun setWifiImagesOnly(value: Boolean)

    /** 工具栏无字。 */
    fun toolbarNoLabel(): Boolean

    fun setToolbarNoLabel(value: Boolean)

    /** 主页显示常用网站。 */
    fun showSites(): Boolean

    fun setShowSites(value: Boolean)

    /** 常用网站卡片背景（关闭后站点直接浮在主页背景上）。 */
    fun homeSitesCardBg(): Boolean

    fun setHomeSitesCardBg(value: Boolean)

    /** 网页调试（WebView 远程调试）。 */
    fun webDebug(): Boolean

    /** JS 控制台是否收集网页 console 输出。 */
    fun jsConsoleLog(): Boolean

    fun setJsConsoleLog(value: Boolean)

    fun setWebDebug(value: Boolean)

    /** 无痕：不记录浏览历史。 */
    fun incognitoNoHistory(): Boolean

    fun setIncognitoNoHistory(value: Boolean)

    /** 无痕：不恢复未关闭标签。 */
    fun incognitoNoRestore(): Boolean

    fun setIncognitoNoRestore(value: Boolean)

    /** 无痕：不保存表单/密码。 */
    fun incognitoNoForm(): Boolean

    fun setIncognitoNoForm(value: Boolean)

    /** 无痕：禁用定位。 */
    fun incognitoNoGeo(): Boolean

    fun setIncognitoNoGeo(value: Boolean)

    /** 无痕：拒绝摄像头。 */
    fun incognitoDenyCam(): Boolean

    fun setIncognitoDenyCam(value: Boolean)

    /** 无痕：拒绝麦克风。 */
    fun incognitoDenyMic(): Boolean

    fun setIncognitoDenyMic(value: Boolean)

    /** 无痕：禁止截屏/任务预览。 */
    fun incognitoSecure(): Boolean

    fun setIncognitoSecure(value: Boolean)

    /** 关闭无痕时清除本次会话数据（因无痕与普通浏览共用存储）。 */
    fun incognitoClearOnExit(): Boolean

    fun setIncognitoClearOnExit(value: Boolean)

    /** 退出无痕时删除本次下载文件。 */
    fun incognitoCleanDownloads(): Boolean

    fun setIncognitoCleanDownloads(value: Boolean)

    /** 退出无痕时清理翻译缓存和日志。 */
    fun incognitoCleanTranslation(): Boolean

    fun setIncognitoCleanTranslation(value: Boolean)

    /** 无痕状态是否跨进程保留。 */
    fun incognitoActive(): Boolean

    fun setIncognitoActive(value: Boolean)

    /** 当前无痕会话开始时间，用于退出清理。 */
    fun incognitoSessionStartedAt(): Long

    fun setIncognitoSessionStartedAt(value: Long)

    /** 隐私保护等级：0 关闭 / 1 标准 / 2 严格 / 3 自定义。 */
    fun privacyLevel(): Int

    fun setPrivacyLevel(value: Int)

    fun privacyTracking(): Boolean

    fun setPrivacyTracking(value: Boolean)

    fun privacySocial(): Boolean

    fun setPrivacySocial(value: Boolean)

    fun privacyMiners(): Boolean

    fun setPrivacyMiners(value: Boolean)

    fun privacyFingerprint(): Boolean

    fun setPrivacyFingerprint(value: Boolean)

    fun privacyContent(): Boolean

    fun setPrivacyContent(value: Boolean)

    fun privacyCookies(): Boolean

    fun setPrivacyCookies(value: Boolean)

    fun privacyCleanUrls(): Boolean

    fun setPrivacyCleanUrls(value: Boolean)

    fun privacyGpc(): Boolean

    fun setPrivacyGpc(value: Boolean)

    fun privacyHttpsOnly(): Boolean

    fun setPrivacyHttpsOnly(value: Boolean)

    /** 剪贴板劫持防护（阻止网站静默写入剪贴板）。 */
    fun clipboardGuard(): Boolean

    fun setClipboardGuard(value: Boolean)

    /** 规则订阅自动更新（启动时超过 24 小时则更新）。 */
    fun subscriptionAutoUpdate(): Boolean

    fun setSubscriptionAutoUpdate(value: Boolean)

    /** 自定义搜索引擎列表（JSON 数组：[{name,url}]）。 */
    fun searchEngines(): String?

    fun setSearchEngines(value: String?)

    /** 快捷引擎栏（地址栏下方或工具栏下方的引擎切换按钮）。 */
    fun searchToolbar(): Boolean

    fun setSearchToolbar(value: Boolean)

    /** 快捷引擎栏位置：0=地址栏下方，1=工具栏下方。 */
    fun quickEnginePosition(): Int

    fun setQuickEnginePosition(value: Int)

    /** 水平滑动前进/后退手势。 */
    fun gestureSwipeNav(): Boolean

    fun setGestureSwipeNav(value: Boolean)

    /** 音量键翻页。 */
    fun gestureVolumeKey(): Boolean

    fun setGestureVolumeKey(value: Boolean)

    /** 下拉刷新。 */
    fun pullRefresh(): Boolean

    fun setPullRefresh(value: Boolean)

    /** 屏幕常亮。 */
    fun keepOn(): Boolean

    fun setKeepOn(value: Boolean)

    /** 视频手势（左亮度/右音量）。 */
    fun videoGesture(): Boolean

    fun setVideoGesture(value: Boolean)

    /** 发送 DNT（Do Not Track）请求头。 */
    fun dntEnabled(): Boolean

    fun setDntEnabled(value: Boolean)

    /** 自动展开网页全文（自动点击「展开全文」类按钮）。 */
    fun autoExpand(): Boolean

    fun setAutoExpand(value: Boolean)

    /** 长按菜单项顺序（逗号分隔 id）。 */
    fun longPressOrder(): String?

    fun setLongPressOrder(value: String?)

    /** 密码库：启用保存/自动填充/不提示的域名名单。 */
    fun passwordEnabled(): Boolean

    fun setPasswordEnabled(value: Boolean)

    fun passwordAutofill(): Boolean

    fun setPasswordAutofill(value: Boolean)

    fun ignoredPasswordHosts(): String?

    fun setIgnoredPasswordHosts(value: String?)

    /** 主页背景图（content URI，空 = 无背景）。 */
    fun homeBackground(): String?

    fun setHomeBackground(value: String?)

    /** 自定义 CSS（注入到所有网页）。 */
    fun customCss(): String?

    fun setCustomCss(value: String?)

    /** 界面缩放（百分比，默认 100）。 */
    fun uiScale(): Int

    fun setUiScale(value: Int)

    /** 安全浏览（WebView 内置恶意网址拦截）。 */
    fun safeBrowsing(): Boolean

    fun setSafeBrowsing(value: Boolean)

    /** 更新提醒方式：0 关闭自动检查 / 1 启动时提醒。 */
    fun updatePrompt(): Int

    fun setUpdatePrompt(value: Int)

    /** 用户选择忽略的版本名；空表示未忽略。 */
    fun updateIgnoredVersion(): String?

    fun setUpdateIgnoredVersion(value: String?)

    /** 上次自动检查时间（毫秒），用于 24 小时节流。 */
    fun updateLastCheck(): Long

    fun setUpdateLastCheck(value: Long)

    /** 最近一次发现的新版本，格式 `versionName|url|notes`，供关于页静默展示。 */
    fun updateLatest(): String?

    fun setUpdateLatest(value: String?)

    /** 禁用 Custom Tabs（外部链接一律用 Sii 主界面打开）。 */
    fun cctDisabled(): Boolean

    fun setCctDisabled(value: Boolean)

    /** 文本选择菜单附加项（逗号分隔：search,share,copy）。 */
    fun selectionMenuItems(): String?

    fun setSelectionMenuItems(value: String?)

    /** 下载器：0 = Sii 内建，1 = 系统下载管理器。 */
    fun downloader(): Int

    fun setDownloader(value: Int)

    /** 下载连接模式：0 自动 / 1 HTTP/2 / 2 HTTP/1.1。 */
    fun downloadProtocol(): Int

    fun setDownloadProtocol(value: Int)

    /** 指定下载目录时是否直接写入 SAF（关闭则先写沙箱再复制）。 */
    fun downloadSafDirect(): Boolean

    fun setDownloadSafDirect(value: Boolean)

    /** 同时下载的任务数（1–4，默认 3）。 */
    fun downloadParallel(): Int

    fun setDownloadParallel(value: Int)

    /** 下载完成后自动打开文件。 */
    fun downloadAutoOpen(): Boolean

    /** HLS 下载完成后重封装为 MP4。 */
    fun hlsToMp4(): Boolean

    fun setHlsToMp4(value: Boolean)

    fun setDownloadAutoOpen(value: Boolean)

    /** 下载失败自动重试。 */
    fun downloadRetry(): Boolean

    fun setDownloadRetry(value: Boolean)

    /** 下载目录（SAF 树 URI；空 = 默认应用目录）。 */
    fun downloadDir(): String?

    fun setDownloadDir(value: String?)

    /** 仅 Wi-Fi 下载（移动网络下阻止新建下载）。 */
    fun downloadWifiOnly(): Boolean

    fun setDownloadWifiOnly(value: Boolean)

    /** WebRTC 防泄露（禁用 RTCPeerConnection，防止真实 IP 泄露）。 */
    fun webrtcProtect(): Boolean

    fun setWebrtcProtect(value: Boolean)

    /** 启动时自动检查更新。 */
    fun updateAutoCheck(): Boolean

    fun setUpdateAutoCheck(value: Boolean)

    /** 上次提醒过的版本号（避免重复提醒）。 */
    fun updateLastNotified(): Long

    fun setUpdateLastNotified(value: Long)

    /** 密码库指纹锁。 */
    fun passwordBiometric(): Boolean

    fun setPasswordBiometric(value: Boolean)

    /** 工具栏位置：0=底部，1=顶部，2=隐藏。 */
    fun toolbarPosition(): Int

    fun setToolbarPosition(value: Int)

    /** 滚动时自动隐藏工具栏。 */
    fun toolbarAutoHide(): Boolean

    fun setToolbarAutoHide(value: Boolean)

    /** 底部工具栏按钮顺序（逗号分隔 id）。 */
    fun toolbarOrder(): String?

    fun setToolbarOrder(value: String?)
}

/**
 * 本地 MCP 服务（浏览器作为服务端，外部 AI 客户端连接）。
 * 总开关默认关；高危能力独立开关，默认关。
 */
interface McpSettings {
    fun mcpEnabled(): Boolean

    fun setMcpEnabled(value: Boolean)

    /** 监听端口（默认 8765）。 */
    fun mcpPort(): Int

    fun setMcpPort(value: Int)

    /** 访问令牌；空表示尚未生成。 */
    fun mcpToken(): String?

    fun setMcpToken(value: String?)

    /** 允许局域网访问（false = 仅 127.0.0.1）。 */
    fun mcpAllowLan(): Boolean

    fun setMcpAllowLan(value: Boolean)

    /** 后台常驻（前台服务 + 通知）。 */
    fun mcpForeground(): Boolean

    fun setMcpForeground(value: Boolean)

    /** 读取页面文本、链接、选区、书签与历史搜索。 */
    fun mcpAllowPageRead(): Boolean

    fun setMcpAllowPageRead(value: Boolean)

    /** 新建/切换/关闭标签与页面导航。 */
    fun mcpAllowTabs(): Boolean

    fun setMcpAllowTabs(value: Boolean)

    /** 点击、填表、滚动。 */
    fun mcpAllowPageAction(): Boolean

    fun setMcpAllowPageAction(value: Boolean)

    /** 在页面中执行任意 JavaScript。 */
    fun mcpAllowJs(): Boolean

    fun setMcpAllowJs(value: Boolean)

    /** 新增/删除书签。 */
    fun mcpAllowBookmarkWrite(): Boolean

    fun setMcpAllowBookmarkWrite(value: Boolean)

    /** 删除历史记录。 */
    fun mcpAllowHistoryWrite(): Boolean

    fun setMcpAllowHistoryWrite(value: Boolean)

    /** 无痕模式下仍允许 MCP 读取。 */
    fun mcpAllowIncognito(): Boolean

    fun setMcpAllowIncognito(value: Boolean)

    /** 抓包：采集网络请求供 MCP 读取。 */
    fun mcpAllowCapture(): Boolean

    fun setMcpAllowCapture(value: Boolean)

    /** 抓包时附带响应体（更敏感）。 */
    fun mcpCaptureBody(): Boolean

    fun setMcpCaptureBody(value: Boolean)

    /** 诊断接口：界面状态与导航事件日志。 */
    fun mcpAllowDiag(): Boolean

    fun setMcpAllowDiag(value: Boolean)
}

package com.sii.browser.data

/**
 * 设置默认值（出厂预设）。
 */
object SettingsDefaults {
    /** 默认主页。 */
    const val HOME = "about:home"

    /** 默认搜索引擎（百度，国内可用）。 */
    const val SEARCH_ENGINE = "https://www.baidu.com/s?wd="

    /** 默认桌面 UA（Chrome PC）。 */
    const val DESKTOP_UA =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"

    /** 默认字号百分比。 */
    const val TEXT_SIZE = 100

    /** 夜间模式：跟随系统。 */
    const val NIGHT_MODE = 2

    /** 地址栏行为：显示完整地址。 */
    const val URL_BOX_BEHAVIOR = 1

    /** 搜索建议展开方向：搜索框上方。 */
    const val SUGGESTION_DIRECTION = 0

    /** 搜索建议数量。 */
    const val SUGGESTION_COUNT = 5

    /** 主页搜索框位置：居中。 */
    const val HOME_LAYOUT_POS = 1

    /** 主页 Logo 文案。 */
    const val HOME_LOGO_TEXT = "Sii"

    /** 常用网站列数。 */
    const val HOME_GRID_COLUMNS = 4

    /** 网站图标大小：0 小 / 1 中 / 2 大。 */
    const val HOME_ICON_SIZE = 1

    /** 网站图标形状：0 圆形 / 1 圆角方形。 */
    const val HOME_ICON_SHAPE = 0

    /** 显示网站名称。 */
    const val HOME_SHOW_LABELS = true

    /** 网站图标圆角：0 无 / 1 小 / 2 中 / 3 大。 */
    const val HOME_ICON_RADIUS = 2

    /** 搜索框圆角：0 小 / 1 中 / 2 大。 */
    const val HOME_SEARCH_RADIUS = 3

    /** 搜索框高度：0 小 / 1 中 / 2 大。 */
    const val HOME_SEARCH_HEIGHT = 1

    /** 主页背景色：0 跟随主题。 */
    const val HOME_BG_COLOR = 0

    /** 背景图模糊：0 关。 */
    const val HOME_BG_BLUR = 0

    /** Logo 字号（sp，0 = 默认 34）。 */
    const val HOME_LOGO_SIZE = 0

    /** Logo 字重：0 常规 / 1 加粗。 */
    const val HOME_LOGO_WEIGHT = 1

    /** 网站名称字号（sp，0 = 默认 12）。 */
    const val HOME_LABEL_SIZE = 0

    /** 网站行间距（dp，-1 = 默认 8）。 */
    const val HOME_CELL_GAP = 12

    /** 图标统一主题色。 */
    const val HOME_ICON_ACCENT = false

    /** 背景图遮罩（0~100）。 */
    const val HOME_BG_OVERLAY = 0

    /** 显示“常用网站”标题。 */
    const val HOME_SHOW_SITES_TITLE = false

    /** 常用网站卡片背景。 */
    const val HOME_SITES_CARD_BG = true

    /** 网页文字颜色：0 原色 / 1 跟随主色 / 2 仅白天 / 3 仅夜晚。 */
    const val WEB_TEXT_COLOR = 0

    /** 原生 WebView 暗色（与网页强制暗色 CSS 互斥）。 */
    const val WEBVIEW_DARK = true

    /** 地址栏样式：0 无外框 / 1 精简横线 / 2 普通圆角外框。 */
    const val ADDRESS_STYLE = 1

    /** 主页/空白页聚焦地址栏时自动填入剪贴板链接。 */
    const val AUTO_FILL_CLIPBOARD = true

    /** 搜索建议分类顺序，越靠前越靠近搜索框。 */
    const val SUGGEST_ORDER = "clipboard,history,bookmark,search"
    const val MENU_COLUMNS = 5
    const val MENU_SCROLL_STYLE = 0
    const val MENU_ICON_SIZE = 29
    const val MENU_TEXT_SIZE = 9
    const val FIXED_MENU_HEIGHT = 51
    const val FIXED_MENU_ICON_SIZE = 25
    const val FIXED_MENU_TEXT_SIZE = 10
    /** 固定菜单位置：0 = 移动项上方，1 = 移动项下方。 */
    const val FIXED_MENU_POSITION = 0
    const val MENU_SHOW_LABELS = true
    const val FIXED_MENU_SHOW_LABELS = true
    const val HOME_LOGO_ICON_SHOW = true
    const val HOME_LOGO_MODE = -1
    const val HOME_LOGO_ICON_SIZE = 80

    /** 退出时清理。 */
    const val EXIT_CLEAR = false

    /** 退出时清理的类目（逗号分隔）。 */
    const val EXIT_CLEAR_ITEMS = "cache,cookies,form,webstorage,history"

    /** 双击网页动作：0 无 / 1 回到顶部 / 2 刷新 / 3 打开主页。 */
    const val GESTURE_DOUBLE_TAP = 0

    /** 游戏模式。 */
    const val GAME_MODE = false

    /** 翻译服务：0 MyMemory / 1 LibreTranslate / 2 Google / 3 自定义。 */
    const val TRANSLATE_PROVIDER = 0

    /** 翻译模式：0 关闭 / 1 云翻译 / 2 离线翻译。 */
    const val TRANSLATE_MODE = 2

    /** 翻译接口地址（自定义 / LibreTranslate 实例）。 */
    const val TRANSLATE_ENDPOINT = ""

    /** 目标语言（空 = 跟随界面）。 */
    const val TRANSLATE_TARGET = ""
    const val TRANSLATE_DETECT = true
    const val TRANSLATE_DUAL = false
    const val TRANSLATE_COLLECT = 200
    const val TRANSLATE_SCAN = 150
    const val TRANSLATE_INTERVAL_MS = 500
    const val TRANSLATE_CHUNK = 3000
    const val TRANSLATE_LIMIT = 0
    const val TRANSLATE_CUSTOM_METHOD = 0
    const val TRANSLATE_CUSTOM_HEADERS = ""
    const val TRANSLATE_CUSTOM_BODY = ""
    const val TRANSLATE_CUSTOM_PATH = ""
    const val TRANSLATE_BAIDU_APPID = ""
    const val TRANSLATE_BAIDU_KEY = ""

    /** 屏幕方向：自动。 */
    const val SCREEN_ORIENTATION = 0

    /** 标签页使用卡片网格；false = 当前简约列表。 */
    const val TAB_CARD_GRID = true
    const val TAB_GRID_COLUMNS = 2
    const val TAB_SHOW_THUMB = true

    /** 广告拦截总开关。 */
    const val ADBLOCK_ENABLED = false

    /** 滚动自动隐藏工具栏。 */
    const val TOOLBAR_AUTOHIDE = true

    /** 快捷引擎栗。 */
    const val SEARCH_TOOLBAR = true

    /** 界面风格：0 = 经典，1 = 新版。 */
    const val UI_STYLE = 1

    /** 标签页背景透明度，0 = 不透明，100 = 全透明。 */
    const val TABS_PANEL_TRANSPARENCY = 15

    /** HLS 下载完成后是否重封装为 MP4。 */
    const val HLS_TO_MP4 = true

    /** JS 控制台是否收集网页 console 输出。 */
    const val JS_CONSOLE_LOG = false

    /** 设置页卡片背景透明度，100 = 不透明。 */
    const val SETTINGS_CARD_ALPHA = 100
    const val CARD_BORDER = true
}

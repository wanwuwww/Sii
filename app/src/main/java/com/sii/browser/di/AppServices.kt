// SPDX-License-Identifier: LicenseRef-Sii-NC
// Copyright (c) 2026 wanwuwww
package com.sii.browser.di

import android.app.Application
import com.sii.browser.core.adblock.AdBlockEngine
import com.sii.browser.core.privacy.PrivacyPacks
import com.sii.browser.core.privacy.PrivacyManager
import com.sii.browser.data.BookmarkRepository
import com.sii.browser.data.DataStoreSettings
import com.sii.browser.data.FavoriteRepository
import com.sii.browser.data.HistoryRepository
import com.sii.browser.data.PasswordRepository
import com.sii.browser.data.ReadLaterRepository
import com.sii.browser.data.RuleRepository
import com.sii.browser.data.ScriptRepository
import com.sii.browser.data.Settings
import com.sii.browser.data.TabRepository
import com.sii.browser.data.db.RuleType
import com.sii.browser.data.SiteConfigRepository
import com.sii.browser.data.db.SiiDatabase
import com.sii.browser.download.DownloadEngine
import com.sii.browser.download.DownloadRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import org.json.JSONObject

/**
 * 全局服务门面。
 * 依赖注入入口统一在此，后续模块的单例按需扩展。
 */
object AppServices {
    lateinit var settings: Settings
        private set

    lateinit var adBlock: AdBlockEngine
        private set

    lateinit var privacyManager: PrivacyManager
        private set

    lateinit var bookmarkRepository: BookmarkRepository
        private set

    lateinit var historyRepository: HistoryRepository
        private set

    lateinit var favoriteRepository: FavoriteRepository
        private set

    lateinit var ruleRepository: RuleRepository
        private set

    lateinit var scriptRepository: ScriptRepository
        private set

    lateinit var siteConfigRepository: SiteConfigRepository
        private set

    lateinit var tabRepository: TabRepository
        private set

    lateinit var downloadRepository: DownloadRepository
        private set

    lateinit var downloadEngine: DownloadEngine
        private set

    lateinit var passwordRepository: PasswordRepository
        private set

    lateinit var readLaterRepository: ReadLaterRepository
        private set

    /** 本地 MCP 服务端；浏览器侧能力由 BrowserFragment 注册。 */
    lateinit var mcpServer: com.sii.browser.core.mcp.McpServer
        private set

    var mcpHost: com.sii.browser.core.mcp.McpHost? = null

    /** 跨 Fragment 打开 URL 回调（书签/历史列表点击 → 浏览器加载）。 */
    var openUrl: ((String) -> Unit)? = null

    /** 日夜切换前捕获当前网页滚动位置，重建后恢复。 */
    var captureScrollForRecreate: (() -> Unit)? = null

    @Volatile
    var pendingScrollY: Int = -1

    /** 当前是否停留在主页；Fragment 重建（设置返回、日夜切换）后用它恢复显示状态。 */
    @Volatile
    var lastHomeVisible: Boolean = false

    /** 列表页点击后待浏览器消费的 URL；不依赖某个 Fragment 实例。 */
    var pendingOpenUrl: String? = null

    /** 最近一次标签快照：设置页 recreate 前可同步落库，避免按旧快照恢复。 */
    var lastTabsSnapshot: List<Pair<String, String>>? = null

    /** 最近一次已应用的隐私设置快照，跨 Fragment 重建保留。 */
    var lastPrivacySignature: String? = null

    /** 设置页离开时通知浏览器重新应用隐私设置（内部比较签名，变化才刷新）。 */
    var reapplyPrivacyIfChanged: (() -> Unit)? = null

    /** 单站设置保存后通知浏览器重新应用并刷新。 */
    var reapplySiteSettings: (() -> Unit)? = null

    /** 隐私设置已变化，等待浏览器回到前台后刷新当前页。 */
    var privacyRefreshPending: Boolean = false

    /** 最近一次创建的无痕 WebView 的隐私设置实际值（供「隐私自检」展示）。 */
    var lastWebViewSnapshot: String? = null

    /** 主页 WebView → 原生动作接口。 */
    var uiActions: UiActions? = null

    /** 设置页调整字号/界面缩放后，通知浏览器实时应用（不重建 Activity）。 */
    var applyPageZoom: (() -> Unit)? = null

    /** 重新应用网页文字主色（链接/强调文字）。 */
    var applyWebTextAccent: (() -> Unit)? = null

    /** 切换原生 WebView 暗色后重新应用并刷新。 */
    var applyWebViewDark: (() -> Unit)? = null

    /** 设置页调整工具栏/标签栏位置后，通知浏览器实时重排（不重建 Activity）。 */
    var applyLayout: (() -> Unit)? = null

    /** 内存紧张时释放浏览器标签缩略图。 */
    var trimThumbnails: (() -> Unit)? = null

    /** 主页搜索聚焦时不让根窗口为键盘预留高度，让键盘直接盖住悬浮工具栏。 */
    var homeSearchImeSuspend: Boolean = false

    /** 地址栏编辑时也不缩页面，只把悬浮底栏抬到键盘上方。 */
    var addressEditImeSuspend: Boolean = false

    /** 密码库列表点击 → 填充到当前页面（由 BrowserFragment 注册）。 */
    var fillPassword: ((String, String) -> Unit)? = null

    /** 当前激活的 WebView（资源嗅探列表据此读取对应标签的资源）。 */
    var sniffTarget: com.sii.browser.core.webview.SiiWebView? = null

    /** 原生动作集（BrowserFragment 实现注册）。 */
    interface UiActions {
        fun loadInBrowser(url: String)
    }

    fun init(application: Application) {
        if (::settings.isInitialized) return
        settings = DataStoreSettings(application)
        adBlock = AdBlockEngine().apply { enabled = settings.adBlockEnabled() }
        privacyManager = PrivacyManager(application)
        privacyManager.isIncognito = settings.incognitoActive()
        privacyManager.incognitoSessionStartedAt = settings.incognitoSessionStartedAt()
        val db = SiiDatabase.create(application)
        bookmarkRepository = BookmarkRepository(db.bookmarkDao())
        historyRepository = HistoryRepository(db.historyDao())
        favoriteRepository = FavoriteRepository(db.favoriteDao())
        ruleRepository = RuleRepository(db.ruleDao())
        scriptRepository = ScriptRepository(db.scriptDao())
        siteConfigRepository = SiteConfigRepository(db.siteConfigDao())
        tabRepository = TabRepository(db.tabDao())
        downloadRepository = DownloadRepository(db.downloadDao())
        downloadEngine = DownloadEngine(application, downloadRepository)
        if (settings.privacyLevel() > 0) {
            CoroutineScope(Dispatchers.IO).launch {
                val categories = buildSet {
                    if (settings.privacyTracking()) add(PrivacyPacks.TRACKING)
                    if (settings.privacySocial()) add(PrivacyPacks.SOCIAL)
                    if (settings.privacyMiners()) add(PrivacyPacks.MINERS)
                    if (settings.privacyFingerprint()) add(PrivacyPacks.FINGERPRINT)
                    if (settings.privacyContent()) add(PrivacyPacks.CONTENT)
                }
                if (categories.isNotEmpty()) {
                    ruleRepository.ensurePrivacyPacks(categories)
                    ruleRepository.loadIntoEngine(adBlock)
                }
            }
        }
        if (privacyManager.isIncognito &&
            (settings.incognitoCleanDownloads() || settings.incognitoCleanTranslation())
        ) {
            val previousStart = privacyManager.incognitoSessionStartedAt
            val cleanDownloads = settings.incognitoCleanDownloads()
            val cleanTranslation = settings.incognitoCleanTranslation()
            CoroutineScope(Dispatchers.IO).launch {
                if (cleanDownloads) {
                    com.sii.browser.download.IncognitoCleanup.cleanDownloads(
                        application, previousStart
                    )
                    val now = System.currentTimeMillis()
                    privacyManager.incognitoSessionStartedAt = now
                    settings.setIncognitoSessionStartedAt(now)
                }
                if (cleanTranslation) {
                    com.sii.browser.download.IncognitoCleanup.cleanTranslation(application)
                }
            }
        }
        passwordRepository = PasswordRepository(db.passwordDao())
        readLaterRepository = ReadLaterRepository(db.readLaterDao())
        mcpServer = com.sii.browser.core.mcp.McpServer({ settings }, { mcpHost })
        CoroutineScope(Dispatchers.IO).launch {
            kotlinx.coroutines.delay(3000)
            val now = System.currentTimeMillis()
            downloadRepository.allTasks().first()
                .filter {
                    (it.state == com.sii.browser.data.db.DownloadState.DOWNLOADING ||
                        it.state == com.sii.browser.data.db.DownloadState.PENDING) &&
                        it.createdAt < now - 10_000
                }
                .forEach {
                    downloadRepository.setState(
                        it.id, com.sii.browser.data.db.DownloadState.PAUSED
                    )
                }
        }
        CoroutineScope(Dispatchers.IO).launch {
            ruleRepository.loadIntoEngine(adBlock)
            siteConfigRepository.refresh()
            refreshAdBlockWhitelist()
            seedDefaultFavorites()
            if (settings.subscriptionAutoUpdate()) autoUpdateSubscriptions()
        }
        // 订阅定时检查：应用存活期间每 6 小时检查一次（超过 24 小时未更新的订阅会被刷新）
        CoroutineScope(Dispatchers.IO).launch {
            while (true) {
                kotlinx.coroutines.delay(6 * 60 * 60 * 1000L)
                if (settings.subscriptionAutoUpdate()) {
                    runCatching { autoUpdateSubscriptions() }
                }
            }
        }
        // 启动时自动检查更新（仅当配置地址且开关开启）
        CoroutineScope(Dispatchers.IO).launch { runCatching { autoCheckUpdate(application) } }
    }

    /** 启动时自动检查更新：按提示档位处理，24 小时最多一次，失败静默。 */
    private suspend fun autoCheckUpdate(app: Application) {
        val prompt = settings.updatePrompt()
        if (prompt <= 0) return
        val now = System.currentTimeMillis()
        if (now - settings.updateLastCheck() < 24L * 60 * 60 * 1000) return
        // 先落时间戳：失败也算一次，避免网络异常时反复要请求耗尽 GitHub 限流。
        settings.setUpdateLastCheck(now)
        val info = com.sii.browser.data.UpdateChecker.check() ?: return
        val local = runCatching {
            val packageInfo = app.packageManager.getPackageInfo(app.packageName, 0)
            val code = if (android.os.Build.VERSION.SDK_INT >= 28) packageInfo.longVersionCode
            else @Suppress("DEPRECATION") packageInfo.versionCode.toLong()
            packageInfo.versionName.orEmpty() to code
        }.getOrNull() ?: return
        if (!com.sii.browser.data.UpdateChecker.isUpdate(info, local.first, local.second)) {
            settings.setUpdateLatest(null)
            return
        }
        val name = info.versionName.ifBlank { info.versionCode.toString() }
        if (name == settings.updateIgnoredVersion()) return
        settings.setUpdateLatest(
            listOf(name, info.url, info.notes.orEmpty()).joinToString("|")
        )
        if (prompt >= 2) notifyUpdate(app, info)
    }

    private fun notifyUpdate(app: Application, info: com.sii.browser.data.UpdateChecker.UpdateInfo) {
        if (info.url.isBlank()) return
        val channelId = "updates"
        val manager = app.getSystemService(android.app.NotificationManager::class.java) ?: return
        if (android.os.Build.VERSION.SDK_INT >= 26) {
            manager.createNotificationChannel(
                android.app.NotificationChannel(
                    channelId,
                    app.getString(com.sii.browser.R.string.update_channel),
                    android.app.NotificationManager.IMPORTANCE_DEFAULT
                )
            )
        }
        val intent = android.content.Intent(
            android.content.Intent.ACTION_VIEW,
            android.net.Uri.parse(info.url)
        ).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
        val pending = android.app.PendingIntent.getActivity(
            app, 0, intent,
            android.app.PendingIntent.FLAG_IMMUTABLE or android.app.PendingIntent.FLAG_UPDATE_CURRENT
        )
        val notification = android.app.Notification.Builder(app, channelId)
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .setContentTitle(
                app.getString(
                    com.sii.browser.R.string.update_available,
                    info.versionName.ifEmpty { info.versionCode.toString() }
                )
            )
            .setContentText(info.notes ?: app.getString(com.sii.browser.R.string.update_tap_to_download))
            .setContentIntent(pending)
            .setAutoCancel(true)
            .build()
        runCatching { manager.notify(1001, notification) }
    }

    /** 启动时更新超过 24 小时的规则订阅。 */
    private suspend fun autoUpdateSubscriptions() {
        val dayMs = 24 * 60 * 60 * 1000L
        val intervalMs = settings.subscriptionIntervalDays().coerceAtLeast(1) * dayMs
        val now = System.currentTimeMillis()
        val stale = ruleRepository.all().first()
            .filter {
                (it.type == RuleType.SUBSCRIPTION || it.type == RuleType.PRIVACY) &&
                    it.enabled && now - it.updatedAt > intervalMs
            }
        if (stale.isEmpty()) return
        var changed = false
        stale.forEach { rule ->
            val updated = if (rule.type == RuleType.PRIVACY) {
                ruleRepository.updatePrivacyPack(rule.id, rule.title)
            } else {
                ruleRepository.updateSubscription(rule.id, rule.title)
            }
            if (updated) changed = true
        }
        if (changed) ruleRepository.loadIntoEngine(adBlock)
    }

    /** 首次运行填充主页常用网站默认值（对齐设计稿 DEFAULT_SITES）。 */
    private suspend fun seedDefaultFavorites() {
        if (favoriteRepository.all().first().isNotEmpty()) return
        DEFAULT_FAVORITES.forEach { (name, url) -> favoriteRepository.add(url, name) }
    }

    private val DEFAULT_FAVORITES = listOf(
        "百度" to "https://www.baidu.com",
        "微博" to "https://weibo.com",
        "知乎" to "https://www.zhihu.com",
        "哔哩哔哩" to "https://www.bilibili.com",
        "GitHub" to "https://github.com",
        "少数派" to "https://sspai.com",
        "虎扑" to "https://www.hupu.com",
        "豆瓣" to "https://www.douban.com"
    )

    /** 依据站点规则里的「广告拦截」开关刷新单站覆盖（读内存缓存，主线程可调）。 */
    fun refreshAdBlockWhitelist() {
        val enabledHosts = mutableSetOf<String>()
        val disabledHosts = mutableSetOf<String>()
        siteConfigRepository.cachedAll().forEach { (host, config) ->
            val json = com.sii.browser.data.SiteConfig.parse(config)
            if (!com.sii.browser.data.SiteConfig.enabled(json)) return@forEach
            when (com.sii.browser.data.SiteConfig.tri(json, "ab")) {
                1 -> enabledHosts.add(host)
                2 -> disabledHosts.add(host)
            }
        }
        adBlock.setSiteOverrides(enabledHosts, disabledHosts)
    }
}

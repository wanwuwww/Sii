// SPDX-License-Identifier: LicenseRef-Sii-NC
// Copyright (c) 2026 wanwuwww
package com.sii.browser.ui

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.graphics.Bitmap
import android.view.animation.DecelerateInterpolator
import android.net.http.SslError
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.webkit.SslErrorHandler
import android.webkit.URLUtil
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.PopupMenu
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.core.content.ContextCompat
import com.sii.browser.R
import com.sii.browser.core.privacy.PrivacyUrlCleaner
import com.sii.browser.core.webview.ResourceInterceptorChain
import com.sii.browser.core.webview.SiiWebView
import com.sii.browser.core.webview.UrlLoadDecision
import com.sii.browser.core.webview.WebChromeClientBridge
import com.sii.browser.core.webview.WebViewCallback
import com.sii.browser.core.webview.WebViewClientBridge
import com.sii.browser.data.SiteConfig
import com.sii.browser.data.WebPageBehavior
import com.sii.browser.di.AppServices
import com.sii.browser.download.DownloadJsBridge
import com.sii.browser.download.DownloadService
import com.sii.browser.script.GmApiBridge
import com.sii.browser.script.UserScript
import kotlinx.coroutines.Dispatchers
import kotlin.coroutines.resume
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject

/**
 * 浏览器主界面。
 *
 * 本模块实现：多标签管理（TabManager + 标签栏 UI）、WebView 挂载与桥接、
 * 地址栏导航、进度/标题回显、返回键回退。工具栏菜单、手势等在后续模块扩展。
 */
class BrowserFragment : Fragment(), ThemeAware, SoftInputAware, IntentAware, FocusAware, GlobalTouchAware, KeyEventSink, AppServices.UiActions {

    private lateinit var tabManager: TabManager
    private var addressInput: EditText? = null
    private var siteInfoButton: View? = null
    private var refreshButton: android.widget.ImageButton? = null
    private var toolbarIconColor: Int? = null
    private var addressIconColor: Int? = null
    private var progressBar: ProgressBar? = null
    /** 进度条平滑动画：新进度到来时取消旧动画、从当前值继续追，避免瞬跳。 */
    private var progressAnimator: ValueAnimator? = null
    private var progressFinishPending: Runnable? = null
    /** 防止带隐私头的 loadUrl 触发重复 shouldOverride。 */
    private var privacyHeaderLoadUrl: String? = null
    /** 程序主动加载后短暂抑制主框架回调，避免必应等站点加载两次。 */
    private var programmaticPrivacyLoadAt: Long = 0L
    private val pageLoadFinished = java.util.WeakHashMap<SiiWebView, Boolean>()
    private val errorPageLoading = java.util.WeakHashMap<SiiWebView, Boolean>()
    private val errorPageUrl = java.util.WeakHashMap<SiiWebView, String>()
    private var toolbarAutoHideAnimator: android.animation.ValueAnimator? = null
    private val toolbarAutoHideHeights = java.util.WeakHashMap<View, Int>()
    private var tabStrip: LinearLayout? = null
    private var tabBar: View? = null
    private var homePanel: HomePanel? = null
    private var latestFavorites: List<com.sii.browser.data.db.FavoriteItem> = emptyList()
    /** 正在抓取的 .user.js 地址，避免重复弹窗。 */
    private var allowRedirectOnce: String? = null
    private val installingScripts = mutableSetOf<String>()
    private val scriptInstallClient by lazy { OkHttpClient() }
    private var permissionDialogShowing = false
    private var homeContainer: FrameLayout? = null
    private var tabsOverlay: View? = null
    private var tabsList: LinearLayout? = null
    private var pageLoadingCover: View? = null
    private var homeOverlayActive = false
    private var homeForwardAvailable = false
    private var sniffPrompt: View? = null
    private var sniffPromptResource: com.sii.browser.core.webview.SniffedResource? = null
    private var sniffPromptKey: Any? = null
    private val sniffPrompted = java.util.WeakHashMap<Any, Boolean>()
    private val sniffPromptDismissed = java.util.WeakHashMap<Any, Boolean>()
    private var menuOverlay: View? = null
    private var menuToggles: LinearLayout? = null
    private var menuGrid: LinearLayout? = null
    private var menuGridHorizontal: LinearLayout? = null
    private var backButton: View? = null
    private var forwardButton: View? = null
    private var homeButton: View? = null
    private var tabCountButton: View? = null
    private var menuButton: View? = null
    private var tabCountText: TextView? = null
    private var webViewContainer: FrameLayout? = null
    private val adPickBridges =
        java.util.WeakHashMap<SiiWebView, com.sii.browser.core.adblock.AdPickBridge>()
    private var tts: android.speech.tts.TextToSpeech? = null
    private var speaking = false
    private var ttsRate = 1.0f

    private data class PendingRender(
        val format: String,
        val base: String,
        val originalUrl: String,
        val tmpHtml: java.io.File
    )
    private var pendingRender: PendingRender? = null
    private var ttsBar: View? = null
    private var toolbarHidden = false
    /** 快捷引擎栏随页面滚动收起/出现的状态。 */
    private var quickEngineHidden = false
    private var quickEngineAnimator: ValueAnimator? = null
    private var quickEngineFullHeight = 0
    /** 新版界面：地址栏处于编辑态（此时底栏不能因键盘让位）。 */
    private var addressEditing = false
    private val passwordBridges =
        java.util.WeakHashMap<SiiWebView, com.sii.browser.core.webview.PasswordJsBridge>()
    private val gmBridges =
        java.util.WeakHashMap<SiiWebView, GmApiBridge>()
    /** 每个标签最近一次识别到的网站主题色（空值 = 无主题色）。 */
    private val themeColors = java.util.WeakHashMap<SiiWebView, Int?>()
    private var menuThemeColor: Int? = null
    private var longPressThemeColor: Int? = null

    /** 相机扫码结果。 */
    private val scanLauncher = registerForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == android.app.Activity.RESULT_OK) {
            val text = result.data?.getStringExtra(ScanActivity.EXTRA_TEXT).orEmpty()
            if (text.isNotBlank()) handleScanResult(text)
        }
    }
    private var interceptorChain: ResourceInterceptorChain? = null
    private var findBar: View? = null
    private var findInput: EditText? = null
    private var findCount: TextView? = null
    private var fontCachePath: String? = null
    private var fontCacheMtime: Long = 0L
    private var fontCacheBase64: String? = null

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View = inflater.inflate(R.layout.fragment_browser, container, false)

    private val isAddedToDownloadPage: Boolean
        get() = parentFragmentManager.fragments.lastOrNull() is DownloadFragment

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        addressEditing = false
        observeDownloadBar()
        addressInput = view.findViewById(R.id.address_input)
        siteInfoButton = view.findViewById(R.id.site_info_button)
        translateStatus = view.findViewById(R.id.translate_status)
        translateStatus?.setOnClickListener { onTranslateStatusClick() }
        translateToggle = view.findViewById(R.id.translate_toggle)
        translateToggle?.setOnClickListener {
            when (translateFabState) {
                1 -> translatePage()
                2 -> cancelTranslate()
                3 -> toggleTranslation()
            }
        }
        applyTranslateFabPosition()
        view.post { restoreTranslateUi() }
        siteInfoButton?.setOnClickListener { anchor -> showSiteInfoMenu(anchor) }
        progressBar = view.findViewById(R.id.progress_bar)
        tabStrip = view.findViewById(R.id.tab_strip)
        tabBar = view.findViewById(R.id.tab_bar)
        applyUiStyle()
        androidx.core.view.ViewCompat.setOnApplyWindowInsetsListener(view) { v, insets ->
            imeInsetVisible = insets.isVisible(
                androidx.core.view.WindowInsetsCompat.Type.ime()
            )
            imeInsetBottom = insets.getInsets(
                androidx.core.view.WindowInsetsCompat.Type.ime()
            ).bottom
            if (!imeInsetVisible && !addressEditing && AppServices.addressEditImeSuspend) {
                AppServices.addressEditImeSuspend = false
                (activity as? ShellActivity)?.refreshImeInsets()
            }
            applyDockImeOffset()
            if (imeInsetVisible && addressEditing && pendingAddressClipboard) {
                showPendingAddressClipboard()
            }
            insets
        }
        view.findViewById<View>(R.id.modern_chrome)?.addOnLayoutChangeListener { _, _, top, _, bottom, _, oldTop, _, oldBottom ->
            if (bottom - top != oldBottom - oldTop) applyFloatingBarLift()
        }
        webViewContainer = view.findViewById(R.id.webview_container)
        homeContainer = view.findViewById(R.id.home_container)
        tabsOverlay = view.findViewById(R.id.tabs_overlay)
        tabsList = view.findViewById(R.id.tabs_list)
        pageLoadingCover = view.findViewById(R.id.page_loading_cover)
        applyTabsPanelAlpha()
        sniffPrompt = view.findViewById(R.id.sniff_prompt)
        sniffPrompt?.findViewById<View>(R.id.sniff_prompt_play)?.setOnClickListener { playSniffResource() }
        sniffPrompt?.findViewById<View>(R.id.sniff_prompt_download)?.setOnClickListener { downloadSniffResource() }
        sniffPrompt?.findViewById<View>(R.id.sniff_prompt_close)?.setOnClickListener { dismissSniffPrompt() }
        com.sii.browser.core.webview.SniffStore.onResource = { key, res ->
            view?.post { maybeShowSniffPrompt(key, res) }
        }
        menuOverlay = view.findViewById(R.id.menu_overlay)
        view.findViewById<View>(R.id.menu_open_settings)?.setOnClickListener {
            hideMenuSheet()
            openSettings()
        }
        setupSheetDrag(R.id.menu_handle, R.id.menu_panel) { hideMenuSheet() }
        setupSheetDrag(R.id.tabs_handle, R.id.tabs_panel) { hideTabsPanel() }
        menuToggles = view.findViewById(R.id.menu_toggles)
        menuGrid = view.findViewById(R.id.menu_grid)
        menuGridHorizontal = view.findViewById(R.id.menu_grid_horizontal)
        findBar = view.findViewById(R.id.find_bar)
        fullscreenContainer = view.findViewById(R.id.fullscreen_container)
        findInput = view.findViewById(R.id.find_input)
        findCount = view.findViewById(R.id.find_count)
        setupFindBar(view)
        setupTtsBar(view)
        ensureHomePanel()

        val refreshButton = view.findViewById<View>(R.id.refresh_button)
        this.refreshButton = refreshButton as? android.widget.ImageButton
        val homeButton = view.findViewById<View>(R.id.home_button)
        val tabCountButton = view.findViewById<View>(R.id.tab_count_button)
        val menuButton = view.findViewById<View>(R.id.menu_button)
        this.homeButton = homeButton
        this.tabCountButton = tabCountButton
        this.menuButton = menuButton
        backButton = view.findViewById(R.id.back_button)
        forwardButton = view.findViewById(R.id.forward_button)
        tabCountText = view.findViewById(R.id.tab_count)

        // 用 Fragment 生命周期而不是 viewLifecycleOwner：从历史/书签页返回时，
        // BrowserFragment 的 view 已销毁，绑定 view 的监听器会收不到结果。
        parentFragmentManager.setFragmentResultListener("sii_open_url", this) { _, bundle ->
            val url = bundle.getString("url") ?: return@setFragmentResultListener
            loadUrlFor(url)
        }
        setupAddressBar()
        setupSearchToolbar()
        refreshButton.setOnClickListener { toggleRefresh() }
        backButton?.setOnClickListener {
            blurAddressInput()
            if (homeContainer?.visibility == View.VISIBLE) {
                if (homeOverlayActive) {
                    homeOverlayActive = false
                    homeForwardAvailable = true
                    showWeb()
                    return@setOnClickListener
                }
                val webView = tabManager.currentWebView
                if (webView != null && webView.canGoBack()) {
                    webView.goBack()
                    showWeb()
                }
                return@setOnClickListener
            }
            tabManager.currentWebView?.let { webView ->
                if (webView.canGoBack()) {
                    webView.goBack()
                    updateNavigationState()
                } else {
                    backToHomeWithoutHistory()
                }
            }
        }
        forwardButton?.setOnClickListener {
            blurAddressInput()
            if (homeForwardAvailable && homeContainer?.visibility != View.VISIBLE) {
                homeForwardAvailable = false
                homeOverlayActive = true
                showHome(createTab = false)
                return@setOnClickListener
            }
            if (homeContainer?.visibility == View.VISIBLE) return@setOnClickListener
            val webView = tabManager.currentWebView
            if (webView?.canGoForward() == true) {
                webView.goForward()
                updateNavigationState()
            } else {
                Toast.makeText(
                    requireContext(),
                    R.string.nav_forward_unavailable,
                    Toast.LENGTH_SHORT
                ).show()
            }
        }
        homeButton.setOnClickListener { loadUrlFor(AppServices.settings.homeUrl()) }
        tabCountButton.setOnClickListener {
            if (tabsOverlay?.visibility == View.VISIBLE) hideTabsPanel() else showTabsPanel()
        }
        menuButton.setOnClickListener { showMenuSheet() }
        setupToolbarHoldActions()
        setupToolbarSwipeActions()
        view.findViewById<View>(R.id.tabs_dim).setOnClickListener { hideTabsPanel() }
        view.findViewById<View>(R.id.menu_dim).setOnClickListener { hideMenuSheet() }
        view.findViewById<View>(R.id.tabs_new).setOnClickListener {
            hideTabsPanel()
            openNewTab()
        }
        view.findViewById<View>(R.id.tabs_undo).setOnClickListener {
            tabManager.restoreLastClosed()?.let { openNewTab(it.url) }
            renderTabCards()
            scrollTabsToCurrent()
        }
        applyTabActionLayout()
        com.sii.browser.script.ScriptUpdater.maybeAutoCheck(requireContext())
        viewLifecycleOwner.lifecycleScope.launch {
            AppServices.favoriteRepository.all().collect { items ->
                latestFavorites = items
                homePanel?.bind(items)
            }
        }

        viewLifecycleOwner.lifecycleScope.launch {
            if (tabManager.size > 0) {
                // 从设置/书签等页面返回：复用已有标签与 WebView，并按离开时的页面状态恢复。
                renderTabs()
                if (AppServices.lastHomeVisible && tabManager.current != null) {
                    showHome(createTab = false)
                } else {
                    showWeb()
                }
                updateAddressBarForCurrent()
                AppServices.pendingOpenUrl?.let { url ->
                    AppServices.pendingOpenUrl = null
                    loadUrlFor(url)
                }
                return@launch
            }
            val restoreForRecreate = ShellActivity.consumeRestoreTabsAfterRecreate()
            val savedFromState = savedInstanceState?.getStringArrayList(KEY_TABS).orEmpty()
            val startupBehavior = AppServices.settings.startupBehavior()
            val abnormalExit = com.sii.browser.data.StartupExit.consumeAbnormal()
            val shouldRestore = restoreForRecreate || savedInstanceState != null ||
                abnormalExit ||
                startupBehavior == 1 ||
                (startupBehavior == 0 && AppServices.settings.restoreClosedTabs() == 1)
            val saved = if (shouldRestore) AppServices.tabRepository.restore() else emptyList()
            if (saved.isEmpty() && savedFromState.isNotEmpty()) {
                savedFromState.forEach { url ->
                    tabManager.newTab(url)
                    if (url != ABOUT_HOME) tabManager.currentWebView?.loadUrl(url)
                    else tabManager.currentWebView?.loadUrl("about:blank")
                }
                if (AppServices.lastHomeVisible || tabManager.current?.url == ABOUT_HOME) {
                    showHome()
                } else {
                    showWeb()
                }
            } else if (saved.isEmpty()) {
                val home = if (startupBehavior == 2) ABOUT_BLANK else restoredUrl(savedInstanceState)
                if (home == ABOUT_HOME) {
                    // 主页态先不创建 WebView，首帧后再建，避免渲染进程初始化拖慢启动。
                    showHome(createTab = false)
                    view?.post {
                        if (!isAdded || view == null || tabManager.current != null) return@post
                        tabManager.newTab(ABOUT_HOME)
                        tabManager.currentWebView?.loadUrl("about:blank")
                    }
                } else {
                    openNewTab(home)
                }
            } else if (abnormalExit && !AppServices.privacyManager.isIncognito &&
                saved.any { it.url != ABOUT_HOME }
            ) {
                if (AppServices.settings.exitRestorePrompt()) {
                    promptRestoreAfterAbnormalExit(saved)
                } else {
                    restoreSavedTabs(saved)
                }
            } else {
                restoreSavedTabs(saved)
            }
            AppServices.pendingOpenUrl?.let { url ->
                AppServices.pendingOpenUrl = null
                loadUrlFor(url)
            }
        }
        requireActivity().onBackPressedDispatcher.addCallback(
            viewLifecycleOwner,
            object : androidx.activity.OnBackPressedCallback(true) {
                override fun handleOnBackPressed() {
                    if (!handleBack()) {
                        isEnabled = false
                        requireActivity().onBackPressedDispatcher.onBackPressed()
                    }
                }
            }
        )
        if (savedInstanceState == null) {
            val startIntent = activity?.intent
            if (startIntent != null && startIntent.action != android.content.Intent.ACTION_MAIN) {
                handleNewIntent(startIntent)
            }
        }
    }

    /** 返回键/返回手势统一处理：游戏模式 → 全屏 → 浮层 → 网页后退 → 关标签 → 交还系统。 */
    private fun handleBack(): Boolean {
        if (AppServices.settings.gameMode()) {
            if (gameExitArmed) {
                gameExitArmed = false
                setGameMode(false)
            } else {
                gameExitArmed = true
                Toast.makeText(requireContext(), R.string.game_mode_exit_hint, Toast.LENGTH_SHORT).show()
                view?.postDelayed({ gameExitArmed = false }, 2000)
            }
            return true
        }
        if (immersiveFullscreen) {
            immersiveFullscreen = false
            applyFullscreen()
            return true
        }
        if (fullscreenContainer?.visibility == View.VISIBLE) {
            tabManager.currentWebView?.webChromeClient?.onHideCustomView()
            return true
        }
        if (addressEditing) {
            blurAddressInput()
            return true
        }
        if (menuOverlay?.visibility == View.VISIBLE) {
            hideMenuSheet()
            return true
        }
        if (tabsOverlay?.visibility == View.VISIBLE) {
            hideTabsPanel()
            return true
        }
        if (findBar?.visibility == View.VISIBLE) {
            hideFindBar()
            return true
        }
        if (homeContainer?.visibility == View.VISIBLE && homePanel?.isSearchActive() == true) {
            homePanel?.exitSearchInput()
            return true
        }
        if (homeContainer?.visibility == View.VISIBLE) {
            val webView = tabManager.currentWebView
            if (homeOverlayActive) {
                homeOverlayActive = false
                homeForwardAvailable = true
                showWeb()
                return true
            }
            if (webView != null && webView.canGoBack()) {
                webView.goBack()
                showWeb()
                return true
            }
            val url = webView?.url
            if (!url.isNullOrEmpty() && url != "about:blank") {
                showWeb()
                return true
            }
            requireActivity().moveTaskToBack(true)
            return true
        }
        if (AppServices.settings.keyAction("back") == 1) {
            requireActivity().moveTaskToBack(true)
            return true
        }
        val webView = tabManager.currentWebView
        if (webView?.canGoBack() == true) {
            webView.goBack()
            return true
        }
        // 网页没有历史（从主页直达）：先回主页，主页再按返回才退出/最小化。
        backToHomeWithoutHistory()
        return true
    }

    /** 无历史可退时把当前网页收成主页起点，避免按返回直接退出应用。 */
    private fun backToHomeWithoutHistory() {
        homeOverlayActive = false
        homeForwardAvailable = false
        tabManager.current?.let { tab ->
            if (tab.url != ABOUT_HOME) {
                tab.url = ABOUT_HOME
                tab.webView.loadUrl("about:blank")
            }
        }
        showHome(createTab = false)
    }

    /** 保存当前标签列表（无痕时不保存）。 */
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // 标签与 WebView 绑定在 Fragment 实例上（而非视图生命周期），
        // 这样打开设置等页面再返回时不会丢失标签。
        tabManager = TabManager(::createWebView).also {
            it.onChanged = {
                renderTabs()
                persistTabs()
            }
        }
        AppServices.trimThumbnails = { tabManager.trimThumbnails() }
        AppServices.mcpHost = BrowserMcpHost(
            tabManager = tabManager,
            onOpenUrl = { loadInBrowser(it) },
            onNewTab = { openNewTab(it) },
            onSwitchTab = { index -> switchToTab(index) },
            onCloseTab = { index -> closeTabAndHint(index) },
            onNavigate = { action ->
                val webView = tabManager.currentWebView
                when {
                    webView == null -> false
                    action == "back" && webView.canGoBack() -> {
                        webView.goBack()
                        updateNavigationState()
                        true
                    }
                    action == "forward" && webView.canGoForward() -> {
                        webView.goForward()
                        updateNavigationState()
                        true
                    }
                    action == "reload" -> {
                        webView.reload()
                        true
                    }
                    else -> false
                }
            },
            isUiReady = { isAdded && view != null }
        )
    }

    private fun persistTabs() {
        if (AppServices.privacyManager.isIncognito &&
            AppServices.settings.incognitoNoRestore()
        ) {
            AppServices.lastTabsSnapshot = null
            return
        }
        val snapshot = tabManager.tabs.map { it.url to it.title }
        AppServices.lastTabsSnapshot = snapshot
        lifecycleScope.launch {
            AppServices.tabRepository.save(snapshot)
        }
    }

    private fun restoredUrl(savedInstanceState: Bundle?): String {
        val restored = savedInstanceState?.getString(KEY_URL)
        if (!restored.isNullOrEmpty()) return restored
        return AppServices.settings.homeUrl()
    }

    /** 恢复一组持久化标签。 */
    private fun restoreSavedTabs(
        saved: List<com.sii.browser.data.db.TabEntity>,
        preferHome: Boolean = AppServices.lastHomeVisible
    ) {
        val restoredTabs = mutableListOf<Tab>()
        saved.forEach { entity ->
            if (entity.url == ABOUT_HOME) {
                tabManager.newTab(ABOUT_HOME).also {
                    restoredTabs.add(it)
                    tabManager.currentWebView?.loadUrl("about:blank")
                }
            } else {
                tabManager.newTab(entity.url).also {
                    restoredTabs.add(it)
                    tabManager.currentWebView?.loadUrl(entity.url)
                }
            }
        }
        loadSavedThumbnails(restoredTabs)
        val webIndex = tabManager.tabs.indexOfFirst { it.url != ABOUT_HOME }
        if (!preferHome && webIndex >= 0) {
            // 用户点了「恢复」：直接切到恢复出来的网页标签。
            tabManager.switchTab(webIndex)
            showWeb()
        } else if (tabManager.current?.url == ABOUT_HOME) {
            showHome()
        } else {
            showWeb()
        }
        updateAddressBarForCurrent()
    }

    /** 启动恢复标签后从磁盘读回缩略图，不阻塞界面。 */
    private fun loadSavedThumbnails(tabs: List<Tab>) {
        if (tabs.isEmpty()) return
        val context = requireContext().applicationContext
        viewLifecycleOwner.lifecycleScope.launch {
            tabs.forEach { tab ->
                if (tab.url == ABOUT_HOME) return@forEach
                val url = tab.url
                val bitmap = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                    com.sii.browser.data.TabThumbStore.load(context, url)
                }
                if (bitmap != null && tab.thumbnail == null) {
                    tab.thumbnail = bitmap
                    tab.thumbnailUrl = url
                    if (tabsOverlay?.visibility == View.VISIBLE) renderTabCards()
                }
            }
        }
    }

    /** 上次非正常退出：底部低调提示，5 秒自动消失。 */
    private fun promptRestoreAfterAbnormalExit(
        saved: List<com.sii.browser.data.db.TabEntity>
    ) {
        val root = view as? android.widget.FrameLayout
        if (root == null) {
            restoreSavedTabs(saved)
            return
        }
        // 先在主页上展示提示，用户可直接使用主页上的其他功能。
        if (tabManager.size == 0) showHome(createTab = false)
        val context = requireContext()
        val dark = AppServices.settings.isNightMode()
        val bar = android.widget.LinearLayout(context).apply {
            orientation = android.widget.LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
            setPadding(dp(16), dp(10), dp(6), dp(10))
            background = GlassStyle.background(
                context, dark, dp(22).toFloat(),
                AppServices.settings.glassStrength(), minAlpha = 220
            )
            elevation = dp(8).toFloat()
            alpha = 0f
            translationY = dp(14).toFloat()
        }
        bar.addView(android.widget.TextView(context).apply {
            text = getString(R.string.restore_tabs_message)
            setTextSize(13f)
            setTextColor(
                if (dark) android.graphics.Color.WHITE
                else context.getColor(R.color.text_primary)
            )
            layoutParams = android.widget.LinearLayout.LayoutParams(
                0, android.view.ViewGroup.LayoutParams.WRAP_CONTENT, 1f
            )
        })
        bar.addView(android.widget.TextView(context).apply {
            text = getString(R.string.restore_tabs_confirm)
            setTextSize(13f)
            setTextColor(ThemeAccent.color(context))
            setPadding(dp(14), dp(8), dp(14), dp(8))
            setOnClickListener {
                root.removeView(bar)
                restoreSavedTabs(saved, preferHome = false)
            }
        })
        fun clearSnapshot() {
            lifecycleScope.launch {
                kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                    AppServices.tabRepository.clear()
                }
            }
        }
        val autoDismiss = Runnable {
            if (bar.parent == null) return@Runnable
            bar.animate().alpha(0f).translationY(dp(12).toFloat()).setDuration(180)
                .withEndAction { root.removeView(bar) }
                .start()
            // 自动忽略：清空快照，主页保持可操作，避免下次启动重复提示。
            clearSnapshot()
        }
        bar.addView(android.widget.TextView(context).apply {
            text = "×"
            setTextSize(18f)
            setTextColor(
                if (dark) 0x99FFFFFF.toInt()
                else context.getColor(R.color.text_secondary)
            )
            setPadding(dp(10), dp(6), dp(10), dp(6))
            setOnClickListener {
                bar.removeCallbacks(autoDismiss)
                root.removeView(bar)
                clearSnapshot()
            }
        })
        root.addView(
            bar,
            android.widget.FrameLayout.LayoutParams(
                android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                android.view.ViewGroup.LayoutParams.WRAP_CONTENT,
                android.view.Gravity.BOTTOM
            ).apply {
                marginStart = dp(12)
                marginEnd = dp(12)
                bottomMargin = dp(96)
            }
        )
        bar.animate().alpha(1f).translationY(0f).setDuration(180).start()
        bar.postDelayed(autoDismiss, 5000)
    }


    private fun showDownloadConfirmDialog(url: String, suggestedName: String) {
        val context = requireContext()
        val input = android.widget.EditText(context).apply {
            setText(suggestedName)
            setSingleLine()
        }
        val sizeText = android.widget.TextView(context).apply {
            text = getString(R.string.download_size_fetching)
            setTextSize(12f)
            setTextColor(context.getColor(R.color.text_secondary))
            setPadding(0, dpToPx(8), 0, 0)
        }
        val linkText = android.widget.TextView(context).apply {
            text = url
            setTextSize(11f)
            setTextColor(context.getColor(R.color.text_secondary))
            maxLines = 2
        }
        val layout = android.widget.LinearLayout(context).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            setPadding(dpToPx(20), dpToPx(4), dpToPx(20), 0)
            addView(android.widget.TextView(context).apply {
                text = getString(R.string.download_file_name)
                setTextSize(12f)
                setTextColor(context.getColor(R.color.text_secondary))
            })
            addView(input)
            addView(sizeText)
            addView(android.widget.TextView(context).apply {
                text = getString(R.string.download_link_label)
                setTextSize(12f)
                setTextColor(context.getColor(R.color.text_secondary))
                setPadding(0, dpToPx(10), 0, 0)
            })
            addView(linkText)
        }
        android.app.AlertDialog.Builder(context)
            .setTitle(R.string.menu_download_page)
            .setView(layout)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                val finalName = sanitizeDownloadName(input.text.toString())
                    .ifBlank { suggestedName }
                startDownload(url, finalName)
                notifyDownloadBarReset()
            }
            .setNeutralButton(R.string.download_copy_link) { _, _ ->
                val clipboard = context.getSystemService(android.content.Context.CLIPBOARD_SERVICE)
                    as android.content.ClipboardManager
                clipboard.setPrimaryClip(android.content.ClipData.newPlainText("url", url))
                Toast.makeText(context, R.string.link_copied, Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
        viewLifecycleOwner.lifecycleScope.launch {
            val size = runCatching { AppServices.downloadEngine.probeSize(url) }
                .getOrDefault(0L)
            sizeText.text = if (size > 0) {
                getString(R.string.download_size_only, formatSize(size))
            } else {
                getString(R.string.download_size_unknown)
            }
        }
    }

    private fun sanitizeDownloadName(raw: String): String =
        raw.trim().replace(Regex("[\\\\/:*?\"<>|]"), "_").take(120)

    private fun formatSize(bytes: Long): String {
        if (bytes <= 0) return "0 B"
        val units = arrayOf("B", "KB", "MB", "GB", "TB")
        var value = bytes.toDouble()
        var index = 0
        while (value >= 1024 && index < units.lastIndex) {
            value /= 1024
            index++
        }
        return if (index == 0) "${value.toLong()} ${units[index]}"
        else String.format(java.util.Locale.getDefault(), "%.1f %s", value, units[index])
    }

    private fun startDownload(url: String, name: String?) {
        val referer = tabManager.current?.url
        val ua = tabManager.currentWebView?.settings?.userAgentString
        com.sii.browser.download.DownloadDispatcher.start(
            requireContext(), url, name, null, referer, ua
        )
    }

    /** 保存离线网页（MHTML 存到下载目录）。 */
    @Suppress("DEPRECATION")
    private fun savePageOffline() {
        val webView = tabManager.currentWebView ?: return
        val context = requireContext()
        val defaultName = safeFileName(tabManager.current?.title?.ifEmpty { null } ?: "page")
        val layout = android.widget.LinearLayout(context).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            setPadding(dp(20), dp(4), dp(20), 0)
        }
        val nameInput = android.widget.EditText(context).apply {
            setText(defaultName)
            setSingleLine()
            hint = getString(R.string.save_file_name)
        }
        layout.addView(nameInput)
        val group = android.widget.RadioGroup(context).apply {
            orientation = android.widget.RadioGroup.VERTICAL
            setPadding(0, dp(8), 0, 0)
        }
        val mht = android.widget.RadioButton(context).apply {
            id = View.generateViewId()
            text = getString(R.string.save_format_mht)
            isChecked = true
        }
        val html = android.widget.RadioButton(context).apply {
            id = View.generateViewId()
            text = getString(R.string.save_format_html)
        }
        val pdf = android.widget.RadioButton(context).apply {
            id = View.generateViewId()
            text = getString(R.string.save_format_pdf)
        }
        group.addView(mht)
        group.addView(html)
        group.addView(pdf)
        layout.addView(group)
        android.app.AlertDialog.Builder(context)
            .setTitle(R.string.menu_save_page)
            .setView(layout)
            .setPositiveButton(R.string.save) { _, _ ->
                val base = safeFileName(nameInput.text.toString().ifBlank { defaultName })
                val format = when {
                    pdf.isChecked -> "pdf"
                    html.isChecked -> "html"
                    else -> "mht"
                }
                startSaveAllPages(webView, base, format)
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    /** 抓取全部分页生成合并 HTML，再按格式输出。 */
    private fun startSaveAllPages(webView: SiiWebView, base: String, format: String) {
        val url = tabManager.current?.url.orEmpty()
        if (!url.startsWith("http")) {
            Toast.makeText(requireContext(), R.string.page_save_failed, Toast.LENGTH_SHORT).show()
            return
        }
        Toast.makeText(requireContext(), R.string.save_collect_hint, Toast.LENGTH_SHORT).show()
        viewLifecycleOwner.lifecycleScope.launch {
            val merged = collectMergedHtml(webView, url)
            if (merged == null) {
                Toast.makeText(requireContext(), R.string.page_save_failed, Toast.LENGTH_SHORT).show()
                return@launch
            }
            if (format == "html") {
                val file = java.io.File(offlineDir(), "$base.html")
                val ok = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                    runCatching {
                        file.writeText(merged)
                        file.length() > 0
                    }.getOrDefault(false)
                }
                if (ok) addToOfflineLibrary(file)
                else Toast.makeText(requireContext(), R.string.page_save_failed, Toast.LENGTH_SHORT).show()
                return@launch
            }
            val tmp = java.io.File(offlineDir(), "render_${System.currentTimeMillis()}.html")
            val written = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                runCatching {
                    tmp.writeText(merged)
                    tmp.length() > 0
                }.getOrDefault(false)
            }
            if (!written) {
                Toast.makeText(requireContext(), R.string.page_save_failed, Toast.LENGTH_SHORT).show()
                return@launch
            }
            pendingRender = PendingRender(format, base, url, tmp)
            Toast.makeText(requireContext(), R.string.save_render_hint, Toast.LENGTH_SHORT).show()
            webView.loadUrl(android.net.Uri.fromFile(tmp).toString())
        }
    }

    /** 合并 HTML 渲染完成后生成 MHT/PDF。 */
    private fun handlePendingRender(webView: SiiWebView, url: String): Boolean {
        val pending = pendingRender ?: return false
        if (!url.startsWith("file://")) return false
        pendingRender = null
        if (pending.format == "mht") {
            // 给图片/样式留出加载时间，否则存档里只有文字。
            webView.postDelayed({
                val tmp = java.io.File(offlineDir(), "tmp_${System.currentTimeMillis()}.mht")
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O_MR1) {
                    webView.saveWebArchive(tmp.absolutePath, false) { path ->
                        finishMhtSave(
                            tmp, pending.base, path != null && tmp.exists() && tmp.length() > 0
                        )
                        cleanupRender(webView, pending)
                    }
                } else {
                    webView.saveWebArchive(tmp.absolutePath)
                    finishMhtSave(tmp, pending.base, tmp.exists() && tmp.length() > 0)
                    cleanupRender(webView, pending)
                }
            }, 1500L)
        } else {
            webView.postDelayed({
                saveRenderedPdf(webView, pending.base)
                cleanupRender(webView, pending)
            }, 600L)
        }
        return true
    }

    private fun cleanupRender(webView: SiiWebView, pending: PendingRender) {
        runCatching { pending.tmpHtml.delete() }
        if (pending.originalUrl.isNotBlank()) webView.loadUrl(pending.originalUrl)
    }

    /** 逐页抓取并合并正文；20 页时询问，最多 100 页。 */
    private suspend fun collectMergedHtml(webView: SiiWebView, startUrl: String): String? {
        val firstHtml = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
            kotlinx.coroutines.suspendCancellableCoroutine<String?> { cont ->
                webView.evaluateJavascript(
                    "(function(){return document.documentElement.outerHTML})()"
                ) { raw ->
                    cont.resume(
                        runCatching {
                            org.json.JSONTokener(raw).nextValue() as? String
                        }.getOrNull()
                    )
                }
            }
        } ?: return null
        val base = firstHtml.replace(
            Regex("(?i)charset\\s*=\\s*[\"']?gb(k|2312)[\"']?"),
            "charset=utf-8"
        )
        val contents = mutableListOf<String>()
        var currentHtml = firstHtml
        var currentUrl = startUrl
        var page = 1
        val seen = mutableSetOf(startUrl)
        while (page < 100) {
            val next = nextPageUrl(currentHtml, currentUrl) ?: break
            if (!seen.add(next)) break
            if (hostOf(next) != hostOf(startUrl)) break
            if (page == 20) {
                val goOn = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                    askContinue(page)
                }
                if (!goOn) break
            }
            val html = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                fetchPage(next)
            } ?: break
            val content = extractNovelContent(html) ?: break
            contents.add(content)
            currentHtml = html
            currentUrl = next
            page++
            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                Toast.makeText(
                    requireContext(), getString(R.string.save_page_progress, page), Toast.LENGTH_SHORT
                ).show()
            }
        }
        val withBase = if (Regex("(?i)<base\\s").containsMatchIn(base)) {
            base
        } else {
            val tag = "<base href=\"${startUrl.replace("\"", "")}\">"
            val head = Regex("(?i)<head[^>]*>").find(base)
            if (head != null) {
                base.substring(0, head.range.last + 1) + tag + base.substring(head.range.last + 1)
            } else {
                base
            }
        }
        if (contents.isEmpty()) return withBase
        val more = contents.joinToString("") { "<div class=\"sii-more-page\">$it</div>" }
        return withBase.replaceFirst("</body>", "$more</body>")
    }

    private suspend fun askContinue(count: Int): Boolean =
        kotlinx.coroutines.suspendCancellableCoroutine { cont ->
            android.app.AlertDialog.Builder(requireContext())
                .setMessage(getString(R.string.save_page_limit_ask, count))
                .setPositiveButton(R.string.save_continue) { _, _ -> cont.resume(true) }
                .setNegativeButton(R.string.save_stop) { _, _ -> cont.resume(false) }
                .setOnCancelListener { cont.resume(false) }
                .show()
        }

    private fun hostOf(url: String): String? =
        runCatching { android.net.Uri.parse(url).host?.lowercase() }.getOrNull()

    private fun finishMhtSave(tmp: java.io.File, base: String, ok: Boolean) {
        if (!ok) {
            runCatching { tmp.delete() }
            Toast.makeText(requireContext(), R.string.page_save_failed, Toast.LENGTH_SHORT).show()
            return
        }
        val file = java.io.File(offlineDir(), "$base.mht")
        val moved = runCatching { tmp.renameTo(file) && file.length() > 0 }.getOrDefault(false)
        if (!moved) {
            runCatching { tmp.delete() }
            Toast.makeText(requireContext(), R.string.page_save_failed, Toast.LENGTH_SHORT).show()
            return
        }
        addToOfflineLibrary(file)
    }

    /** 用 WebView 打印适配器直接生成 PDF 文件，由系统自动分页。 */
    private fun saveRenderedPdf(webView: SiiWebView, base: String) {
        val file = java.io.File(offlineDir(), "$base.pdf")
        val attrs = android.print.PrintAttributes.Builder()
            .setMediaSize(android.print.PrintAttributes.MediaSize.ISO_A4)
            .setResolution(android.print.PrintAttributes.Resolution("pdf", "pdf", 600, 600))
            .setMinMargins(android.print.PrintAttributes.Margins.NO_MARGINS)
            .build()
        PagePdfPrinter.print(webView, attrs, file) { ok ->
            view?.post {
                if (ok) addToOfflineLibrary(file)
                else Toast.makeText(requireContext(), R.string.page_save_failed, Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun offlineDir(): java.io.File =
        java.io.File(requireContext().filesDir, "offline").apply { mkdirs() }


    /** 保存成功后进入离线库；message 为空时用默认提示。 */
    private fun addToOfflineLibrary(file: java.io.File, message: String? = null) {
        val url = tabManager.current?.url.orEmpty()
        val title = tabManager.current?.title?.ifEmpty { url } ?: url
        viewLifecycleOwner.lifecycleScope.launch {
            AppServices.readLaterRepository.add(url, title, file.absolutePath)
            Toast.makeText(
                requireContext(), message ?: getString(R.string.offline_saved), Toast.LENGTH_SHORT
            ).show()
        }
    }

    /** 标题转安全文件名。 */
    private fun safeFileName(title: String): String =
        title.replace(Regex("[\\\\/:*?\"<>|\n\r]"), "_").take(60).ifBlank { "page" }

    /** 提取 DOM 保存为 HTML（UTF-8 声明，避免原站 GBK 导致乱码）。 */

    private fun targetFile(type: String, name: String): java.io.File {
        val dir = requireContext().getExternalFilesDir(type) ?: requireContext().filesDir
        if (!dir.exists()) dir.mkdirs()
        return java.io.File(dir, name)
    }

    private fun openDownloads() {
        NavAnim.apply(parentFragmentManager.beginTransaction())
            .replace(R.id.shell_root, DownloadFragment())
            .addToBackStack(null)
            .commit()
    }

    /** 底部下载提示条：显示文案，确认按钮跳下载页并消失。 */
    private fun showDownloadBar(text: String) {
        val bar = view?.findViewById<View>(R.id.download_bar) ?: return
        view?.findViewById<TextView>(R.id.download_bar_text)?.text = text
        bar.visibility = View.VISIBLE
    }

    private fun hideDownloadBar() {
        view?.findViewById<View>(R.id.download_bar)?.visibility = View.GONE
    }

    /** 用户点击“查看”后不再因 Flow 刷新重新弹出；新下载任务发起时重置。 */
    private var downloadBarDismissed = false

    fun notifyDownloadBarReset() {
        downloadBarDismissed = false
    }

    /** 订阅下载任务：有活动任务时在工具栏上方显示提示条，点击确认跳下载页。 */
    private fun observeDownloadBar() {
        viewLifecycleOwner.lifecycleScope.launch {
            AppServices.downloadRepository.allTasks().collect { tasks ->
                val active = tasks.firstOrNull {
                    it.state == com.sii.browser.data.db.DownloadState.DOWNLOADING ||
                        it.state == com.sii.browser.data.db.DownloadState.PENDING
                }
                if (active != null && !isAddedToDownloadPage && !downloadBarDismissed) {
                    showDownloadBar(
                        getString(R.string.download_bar_downloading, active.name)
                    )
                } else {
                    hideDownloadBar()
                }
            }
        }
        view?.findViewById<View>(R.id.download_bar_action)?.setOnClickListener {
            downloadBarDismissed = true
            hideDownloadBar()
            openDownloads()
        }
    }

    /** 默认电脑模式网站设置（从小狗图标菜单直达）。 */
    private fun openDesktopSitesSettings() {
        NavAnim.apply(parentFragmentManager.beginTransaction())
            .replace(
                R.id.shell_root,
                SettingsDetailFragment().apply {
                    arguments = android.os.Bundle().apply {
                        putString(SettingsDetailFragment.ARG_VIEW, SettingsDetailFragment.VIEW_DESKTOP_SITES)
                    }
                }
            )
            .addToBackStack(null)
            .commit()
    }

    /** 资源嗅探列表（当前标签）。 */
    private fun openSniff() {        NavAnim.apply(parentFragmentManager.beginTransaction())
            .replace(R.id.shell_root, SniffFragment())
            .addToBackStack(null)
            .commit()
    }

    /** 稍后读列表。 */
    private fun openReadLater() {
        NavAnim.apply(parentFragmentManager.beginTransaction())
            .replace(R.id.shell_root, ReadLaterFragment())
            .addToBackStack(null)
            .commit()
    }

    /** 把同章节的后续分页合并进存档，返回实际页数（最多 10 页）。 */

    private fun chapterKey(url: String): String {
        val path = runCatching { android.net.Uri.parse(url).path }.getOrNull().orEmpty()
        val name = path.substringAfterLast('/')
        return name.substringBefore('_').substringBefore('.')
    }

    private fun nextPageUrl(html: String, baseUrl: String): String? {
        val href = Regex("(?is)<a[^>]*href=[\"']([^\"']+)[\"'][^>]*>[^<]{0,8}下一页")
            .find(html)?.groupValues?.get(1) ?: return null
        return runCatching { java.net.URL(java.net.URL(baseUrl), href).toString() }.getOrNull()
    }

    private fun fetchPage(url: String): String? = runCatching {
        val request = okhttp3.Request.Builder()
            .url(url)
            .header("User-Agent", DEFAULT_MOBILE_UA)
            .build()
        scriptInstallClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) null else decodeHtml(response)
        }
    }.getOrNull()

    /** 按 Content-Type 或 meta 声明的编码解码（小说站常见 GBK）。 */
    private fun decodeHtml(response: okhttp3.Response): String? {
        val bytes = response.body?.bytes() ?: return null
        val declared = response.body?.contentType()?.charset(null)
        if (declared != null) return String(bytes, declared)
        val head = String(bytes, 0, minOf(bytes.size, 2048), Charsets.ISO_8859_1)
        val name = Regex("(?i)charset\\s*=\\s*[\"']?([a-z0-9_-]+)")
            .find(head)?.groupValues?.get(1)
        val charset = name?.let {
            runCatching { java.nio.charset.Charset.forName(it) }.getOrNull()
        } ?: Charsets.UTF_8
        return String(bytes, charset)
    }

    /** 提取常见小说站的正文容器；取不到就不合并。 */
    private fun extractNovelContent(html: String): String? {
        val idStart = Regex("(?i)id=[\"']novelcontent[\"']").find(html)?.range?.first ?: return null
        val contentStart = html.indexOf('>', idStart).takeIf { it > 0 }?.plus(1) ?: return null
        val end = html.indexOf("<ul class=\"novelbutton\"", contentStart)
        if (end <= contentStart) return null
        return html.substring(contentStart, end)
    }

    /** 密码库列表。 */
    private fun openPasswords() {        NavAnim.apply(parentFragmentManager.beginTransaction())
            .replace(R.id.shell_root, PasswordFragment())
            .addToBackStack(null)
            .commit()
    }

    /** 将当前页添加到桌面（系统固定快捷方式）。 */
    private fun addToHomeScreen() {
        val url = tabManager.current?.url ?: return
        if (!url.startsWith("http")) return
        val context = requireContext()
        val label = tabManager.current?.title?.ifEmpty {
            runCatching { android.net.Uri.parse(url).host }.getOrNull() ?: url
        } ?: url
        val shortcut = androidx.core.content.pm.ShortcutInfoCompat.Builder(
            context, "sii_" + url.hashCode()
        )
            .setShortLabel(label.take(12))
            .setLongLabel(label)
            .setIcon(
                androidx.core.graphics.drawable.IconCompat.createWithResource(
                    context, R.drawable.ic_launcher_sii
                )
            )
            .setIntent(
                Intent(Intent.ACTION_VIEW, android.net.Uri.parse(url))
                    .setClass(context, ShellActivity::class.java)
            )
            .build()
        val ok = runCatching {
            androidx.core.content.pm.ShortcutManagerCompat.requestPinShortcut(context, shortcut, null)
        }.getOrDefault(false)
        if (!ok) {
            Toast.makeText(context, R.string.shortcut_unsupported, Toast.LENGTH_SHORT).show()
        }
    }

    /** 关闭全部标签（带确认，至少保留一个主页标签）。 */
    private fun closeAllTabs() {
        if (tabManager.size <= 1) return
        android.app.AlertDialog.Builder(requireContext())
            .setMessage(R.string.close_all_tabs_confirm)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                tabManager.closeAll()
                clearThumbnailCache()
                openNewTab(ABOUT_HOME)
                renderTabs()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun clearThumbnailCache() {
        val context = requireContext().applicationContext
        viewLifecycleOwner.lifecycleScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            com.sii.browser.data.TabThumbStore.clear(context)
        }
    }

    /** 朗读网页：提取正文交给系统 TTS，再点一次停止。 */
    private fun toggleReadAloud() {
        if (speaking) {
            stopReadAloud()
            return
        }
        val webView = tabManager.currentWebView ?: return
        webView.extractPageText { text ->
            val content = text?.trim().orEmpty()
            if (content.isEmpty()) {
                Toast.makeText(requireContext(), R.string.read_aloud_empty, Toast.LENGTH_SHORT).show()
                return@extractPageText
            }
            ensureTts { engine ->
                speaking = true
                showTtsBar(true)
                engine.speak(content, android.speech.tts.TextToSpeech.QUEUE_FLUSH, null, "sii_read_aloud")
            }
        }
    }

    private fun ensureTts(onReady: (android.speech.tts.TextToSpeech) -> Unit) {
        tts?.let { onReady(it); return }
        val engine = android.speech.tts.TextToSpeech(
            requireContext().applicationContext
        ) { status ->
            // 部分厂商引擎 onInit 可能同步触发，此时 tts 字段尚未赋值；
            // post 到主线程队列，保证构造返回、字段赋值后再处理。
            view?.post { handleTtsInit(status, onReady) }
        }     
        tts = engine
    }

    private fun handleTtsInit(status: Int, onReady: (android.speech.tts.TextToSpeech) -> Unit) {
        val created = tts
        if (status == android.speech.tts.TextToSpeech.SUCCESS && created != null) {
            prepareTts(created)
            onReady(created)
        } else {
            created?.shutdown()
            tts = null
            initTtsWithEngineList(0, onReady)
        }
    }

    /** 语言不支持只记日志，不阻止朗读。 */
    private fun prepareTts(engine: android.speech.tts.TextToSpeech) {
        engine.setSpeechRate(ttsRate)
        engine.setLanguage(java.util.Locale.getDefault())
    }

    /** 默认引擎不可用后，按已安装引擎列表逐一尝试。 */
    private fun initTtsWithEngineList(index: Int, onReady: (android.speech.tts.TextToSpeech) -> Unit) {
        val engines = runCatching {
            android.speech.tts.TextToSpeech(requireContext(), null).engines
        }.getOrNull() ?: emptyList()
        if (index >= engines.size) {
            speaking = false
            Toast.makeText(
                requireContext(), R.string.read_aloud_no_engine, Toast.LENGTH_SHORT
            ).show()
            return
        }
        val name = engines[index].name
        val engine = android.speech.tts.TextToSpeech(
            requireContext().applicationContext,
            { status ->
                view?.post { handleEngineTry(name, status, index, onReady) }
            },
            name
        )
        tts = engine
    }

    private fun handleEngineTry(
        name: String,
        status: Int,
        index: Int,
        onReady: (android.speech.tts.TextToSpeech) -> Unit
    ) {
        val created = tts
        if (status == android.speech.tts.TextToSpeech.SUCCESS && created != null) {
            prepareTts(created)
            onReady(created)
        } else {
            created?.shutdown()
            tts = null
            initTtsWithEngineList(index + 1, onReady)
        }
    }

    /** 朗读浮条：语速循环 + 停止。 */
    private fun setupTtsBar(view: View) {
        ttsBar = view.findViewById(R.id.tts_bar)
        view.findViewById<View>(R.id.tts_rate).setOnClickListener {
            val rates = floatArrayOf(1.0f, 1.25f, 1.5f, 0.75f)
            val index = (rates.indexOfFirst { it == ttsRate }.takeIf { it >= 0 } ?: 0)
            ttsRate = rates[(index + 1) % rates.size]
            tts?.setSpeechRate(ttsRate)
            Toast.makeText(requireContext(), "${ttsRate}x", Toast.LENGTH_SHORT).show()
        }
        view.findViewById<View>(R.id.tts_stop).setOnClickListener { stopReadAloud() }
    }

    private fun showTtsBar(show: Boolean) {
        ttsBar?.visibility = if (show) View.VISIBLE else View.GONE
    }

    private fun stopReadAloud() {
        tts?.stop()
        speaking = false
        showTtsBar(false)
        Toast.makeText(requireContext(), R.string.read_aloud_stopped, Toast.LENGTH_SHORT).show()
    }

    /** 相机扫码入口。 */
    private fun startScan() {
        scanLauncher.launch(android.content.Intent(requireContext(), ScanActivity::class.java))
    }

    /** 识别图片中的二维码（拉取图片后本地解码）。 */
    private fun scanImageUrl(url: String) {
        viewLifecycleOwner.lifecycleScope.launch {
            val bitmap = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                runCatching {
                    if (DataUriTools.isDataImage(url)) {
                        DataUriTools.bitmap(url)
                    } else {
                        val client = okhttp3.OkHttpClient()
                        val request = okhttp3.Request.Builder().url(url).build()
                        client.newCall(request).execute().use { response ->
                            response.body?.byteStream()?.let {
                                android.graphics.BitmapFactory.decodeStream(it)
                            }
                        }
                    }
                }.getOrNull()
            }
            val text = bitmap?.let { com.sii.browser.core.webview.QrCodeDecoder.decode(it) }
            if (text.isNullOrBlank()) {
                Toast.makeText(requireContext(), R.string.scan_failed, Toast.LENGTH_SHORT).show()
            } else {
                handleScanResult(text)
            }
        }
    }

    /** 扫码结果：URL 直接打开，其余复制到剪贴板。 */
    private fun handleScanResult(text: String) {
        if (URLUtil.isNetworkUrl(text.trim())) {
            loadUrlFor(text.trim())
        } else {
            copyToClipboard(text)
            Toast.makeText(requireContext(), R.string.scan_result_copied, Toast.LENGTH_SHORT).show()
        }
    }

    /** 脚本菜单：执行当前页面脚本通过 GM_registerMenuCommand 注册的命令。 */
    private fun showScriptMenu() {
        val commands = com.sii.browser.script.GmApiBridge.menuCommands()
        if (commands.isEmpty()) {
            Toast.makeText(requireContext(), R.string.script_menu_empty, Toast.LENGTH_SHORT).show()
            return
        }
        android.app.AlertDialog.Builder(requireContext())
            .setTitle(R.string.menu_script_commands)
            .setItems(commands.map { it.second }.toTypedArray()) { _, which ->
                val id = commands[which].first
                tabManager.currentWebView?.injectJavaScript(
                    "(function(){try{window.__sii_runMenuCommand && window.__sii_runMenuCommand('$id')}catch(e){}})();"
                )
            }
            .show()
    }

    /** Android 13+ 首次下载时动态申请通知权限（用户方案兼容底线）。 */
    @Suppress("DEPRECATION")
    private fun ensureNotificationPermission() {
        if (Build.VERSION.SDK_INT < 33) return
        if (ContextCompat.checkSelfPermission(
                requireContext(), android.Manifest.permission.POST_NOTIFICATIONS
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(
                arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), REQ_POST_NOTIFICATIONS
            )
        }
    }

    private fun addCurrentBookmark() {
        val tab = tabManager.current ?: return
        viewLifecycleOwner.lifecycleScope.launch {
            val exists = AppServices.bookmarkRepository.findItemByUrl(tab.url) != null
            if (!exists) {
                AppServices.bookmarkRepository.addItem(tab.url, tab.title, null)
            }
            Toast.makeText(requireContext(), R.string.bookmark_added, Toast.LENGTH_SHORT).show()
        }
    }

    private fun openBookmarks() {
        NavAnim.apply(parentFragmentManager.beginTransaction())
            .replace(R.id.shell_root, BookmarkFragment())
            .addToBackStack(null)
            .commit()
    }

    private fun openHistory() {
        NavAnim.apply(parentFragmentManager.beginTransaction())
            .replace(R.id.shell_root, HistoryFragment())
            .addToBackStack(null)
            .commit()
    }

    /** 广告拦截统计（拦截总数 + 域名明细，复用规则页）。 */
    private fun openAdblockStats() {
        NavAnim.apply(parentFragmentManager.beginTransaction())
            .replace(R.id.shell_root, RulesFragment())
            .addToBackStack(null)
            .commit()
    }

    /** 长按菜单：按命中类型（链接/图片）展示对应操作。 */
    private fun showLongPressMenu(result: android.webkit.WebView.HitTestResult) {
        val url = result.extra ?: return
        val hidden = AppServices.settings.longPressHidden().orEmpty()
            .split(",").filter { it.isNotEmpty() }.toSet()
        val order = AppServices.settings.longPressOrder().orEmpty()
            .split(",").filter { it.isNotEmpty() }
        val items = mutableListOf<Triple<String, Int, () -> Unit>>()
        fun add(id: String, titleRes: Int, action: () -> Unit) {
            if (id !in hidden) items.add(Triple(id, titleRes, action))
        }
        when (result.type) {
            android.webkit.WebView.HitTestResult.SRC_ANCHOR_TYPE -> {
                add("background", R.string.menu_open_background) { openInBackground(url) }
                add("newtab", R.string.menu_open_new_tab) { openNewTab(url) }
                add("copy", R.string.menu_copy_link) { copyToClipboard(url) }
                add("copylinktext", R.string.menu_copy_link_text) { copyLinkText() }
                add("qrcode", R.string.scan_generate) { QrCodeDialog.show(requireContext(), url) }
                add("share", R.string.menu_share_link) { shareUrl(url) }
                add("download", R.string.menu_download_link) {
                    ensureNotificationPermission()
                    com.sii.browser.download.DownloadDispatcher.start(
                        requireContext(), url, null, null,
                        tabManager.current?.url,
                        tabManager.currentWebView?.settings?.userAgentString
                    )
                }
                add("play", R.string.menu_open_with_app) { openWithApp(url) }
                add("scanqr", R.string.menu_scan_qr) { startScan() }
                add("pageinfo", R.string.menu_page_info) { showPageInfo() }
                add("markad", R.string.menu_mark_ad) { markAsAd() }
            }
            android.webkit.WebView.HitTestResult.SRC_IMAGE_ANCHOR_TYPE,
            android.webkit.WebView.HitTestResult.IMAGE_TYPE -> {
                add("viewimage", R.string.menu_view_image) { ImagePreviewDialog.show(requireContext(), url) }
                add("saveimage", R.string.menu_save_image) { saveImageUrl(url) }
                add("downloadimage", R.string.menu_download_image) { downloadImageUrl(url) }
                add("shareimage", R.string.menu_share_image) { shareImage(url) }
                add("reverseimage", R.string.menu_reverse_image) { reverseImageSearch(url) }
                add("imagemode", R.string.menu_image_mode) { ImagePreviewDialog.show(requireContext(), url) }
                add("scanimage", R.string.menu_scan_image) { scanImageUrl(url) }
                add("qrcode", R.string.scan_generate) {
                    val qrText = if (DataUriTools.isDataImage(url)) {
                        tabManager.current?.url.orEmpty()
                    } else url
                    if (qrText.startsWith("http://") || qrText.startsWith("https://")) {
                        QrCodeDialog.show(requireContext(), qrText)
                    } else {
                        Toast.makeText(
                            requireContext(),
                            "当前图片没有可生成二维码的链接",
                            Toast.LENGTH_SHORT
                        ).show()
                    }
                }
                add("scanqr", R.string.menu_scan_qr) { startScan() }
                add("pageinfo", R.string.menu_page_info) { showPageInfo() }
                add("markad", R.string.menu_mark_ad) { markAsAd() }
                add("copy", R.string.menu_copy_link) { copyToClipboard(url) }
            }
            else -> return
        }
        if (items.isEmpty()) return
        // 按配置顺序排列（未配置的排在后面，保持默认相对顺序）
        val ordered = if (order.isEmpty()) {
            items
        } else {
            items.sortedBy { entry ->
                val idx = order.indexOf(entry.first)
                if (idx < 0) Int.MAX_VALUE else idx
            }
        }
        showContextMenuAtTouch(ordered.map { it.second to it.third })
    }

    /** 在长按位置弹出菜单（跟随手指，并做屏幕边界收敛）。 */
    private fun showContextMenuAtTouch(items: List<Pair<Int, () -> Unit>>) {
        val context = requireContext()
        val themeBg = longPressThemeColor
        val foreground = themeBg?.let { contrastOn(it) } ?: context.getColor(R.color.text_primary)
        val container = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundResource(R.drawable.bg_card)
            if (themeBg != null) {
                backgroundTintList = android.content.res.ColorStateList.valueOf(themeBg)
            }
            clipToOutline = true
        }
        var popup: android.widget.PopupWindow? = null
        items.forEach { (titleRes, action) ->
            container.addView(TextView(context).apply {
                text = getString(titleRes)
                setTextSize(13f)
                setTextColor(foreground)
                setPadding(dp(18), dp(12), dp(18), dp(12))
                setBackgroundResource(android.R.drawable.list_selector_background)
                isClickable = true
                setOnClickListener {
                    popup?.dismiss()
                    action()
                }
            })
        }
        val window = android.widget.PopupWindow(
            container,
            LinearLayout.LayoutParams.WRAP_CONTENT,
            LinearLayout.LayoutParams.WRAP_CONTENT,
            true
        ).apply {
            elevation = dp(10).toFloat()
            isOutsideTouchable = true
            setBackgroundDrawable(android.graphics.drawable.ColorDrawable(android.graphics.Color.TRANSPARENT))
        }
        popup = window

        val anchor = tabManager.currentWebView ?: view
        val point = tabManager.currentWebView?.lastTouchPoint
        val location = IntArray(2)
        anchor?.getLocationOnScreen(location)
        var x = location[0] + (point?.x?.toInt() ?: dp(24))
        var y = location[1] + (point?.y?.toInt() ?: dp(24))

        container.measure(
            android.view.View.MeasureSpec.UNSPECIFIED,
            android.view.View.MeasureSpec.UNSPECIFIED
        )
        val metrics = resources.displayMetrics
        x = x.coerceIn(0, (metrics.widthPixels - container.measuredWidth).coerceAtLeast(0))
        y = y.coerceIn(0, (metrics.heightPixels - container.measuredHeight).coerceAtLeast(0))

        val root = view ?: return
        window.showAtLocation(root, android.view.Gravity.NO_GRAVITY, x, y)
    }

    /** 以图搜图：选引擎后用图片 URL 发起搜索（多引擎入口）。 */
    private fun reverseImageSearch(imageUrl: String) {
        val engines = listOf(
            getString(R.string.reverse_engine_lens) to "https://lens.google.com/uploadbyurl?url=",
            getString(R.string.reverse_engine_tineye) to "https://www.tineye.com/search?url=",
            getString(R.string.reverse_engine_saucenao) to "https://saucenao.com/search.php?url=",
            getString(R.string.reverse_engine_iqdb) to "https://iqdb.org/?url="
        )
        android.app.AlertDialog.Builder(requireContext())
            .setTitle(R.string.menu_reverse_image)
            .setItems(engines.map { it.first }.toTypedArray()) { _, which ->
                loadUrlFor(engines[which].second + android.net.Uri.encode(imageUrl))
            }
            .show()
    }

    /** 阅读模式：进入则注入易读浮层，已进入则刷新页面退出。 */
    private fun toggleReaderMode() {
        val webView = tabManager.currentWebView ?: return
        if (webView.readerMode) {
            webView.readerMode = false
            webView.reload()
            return
        }
        val size = AppServices.settings.readerTextSize()
        val host = tabManager.current?.url
            ?.let { runCatching { android.net.Uri.parse(it).host }.getOrNull() }
        webView.readerMode = true
        webView.injectJavaScript(
            com.sii.browser.core.webview.ReaderMode.enterJs(
                size,
                AppServices.settings.isNightMode(),
                host,
                AppServices.settings.readerThemeColor(),
                AppServices.settings.readerCustomCss()
            )
        )
    }

    /** 查看当前页面源码：读取 outerHTML，等宽字体对话框展示，可复制。 */
    private fun showPageSource() {
        val webView = tabManager.currentWebView ?: return
        webView.evaluateJavascript("document.documentElement.outerHTML") { raw ->
            if (!isAdded) return@evaluateJavascript
            val html = runCatching {
                org.json.JSONTokener(raw).nextValue() as? String
            }.getOrNull().orEmpty()
            if (html.isBlank()) return@evaluateJavascript
            val text = TextView(requireContext()).apply {
                this.text = html
                setTextIsSelectable(true)
                setTextSize(11f)
                setTypeface(android.graphics.Typeface.MONOSPACE)
                setPadding(dpToPx(12), dpToPx(12), dpToPx(12), dpToPx(12))
            }
            val scroll = android.widget.ScrollView(requireContext()).apply {
                addView(text)
            }
            android.app.AlertDialog.Builder(requireContext())
                .setTitle(R.string.menu_source)
                .setView(scroll)
                .setPositiveButton(android.R.string.ok, null)
                .setNeutralButton(R.string.sel_menu_copy) { _, _ ->
                    val clipboard = requireContext()
                        .getSystemService(android.content.Context.CLIPBOARD_SERVICE)
                        as android.content.ClipboardManager
                    clipboard.setPrimaryClip(
                        android.content.ClipData.newPlainText("html", html)
                    )
                    Toast.makeText(
                        requireContext(),
                        R.string.source_copied,
                        Toast.LENGTH_SHORT
                    ).show()
                }
                .show()
        }
    }

    /** 打印/PDF：打开系统打印界面，可选择打印机或另存为 PDF。 */
    private fun printPage() {
        val webView = tabManager.currentWebView ?: return
        val activity = activity ?: return
        val title = tabManager.current?.title?.ifEmpty { getString(R.string.menu_print) }
            ?: getString(R.string.menu_print)
        val adapter = runCatching {
            webView.createPrintDocumentAdapter(title)
        }.getOrNull() ?: return
        val attrs = android.print.PrintAttributes.Builder()
            .setMediaSize(android.print.PrintAttributes.MediaSize.ISO_A4)
            .build()
        // Activity 的 base context 被语言配置包装过，系统不认作 Activity；
        // 改用 attachBaseContext 传入的原始上下文。
        val printContext = (activity as? ShellActivity)
            ?.let { ShellActivity.printServiceContext() } ?: activity
        val manager = printContext.getSystemService(Context.PRINT_SERVICE)
            as? android.print.PrintManager ?: return
        val error = runCatching { manager.print(title, adapter, attrs) }.exceptionOrNull()
        if (error == null) return
        Toast.makeText(requireContext(), R.string.page_save_failed, Toast.LENGTH_SHORT).show()
    }

    /** 进入「标记广告」选取模式：点击页面元素即生成隐藏规则。 */
    private fun startAdPick() {
        val webView = tabManager.currentWebView ?: return
        val host = tabManager.current?.url
            ?.let { runCatching { android.net.Uri.parse(it).host }.getOrNull() }
            ?: return
        val bridge = adPickBridges[webView] ?: return
        bridge.host = host
        bridge.active = true
        webView.injectJavaScript(com.sii.browser.core.adblock.AdPickBridge.PICK_JS)
        Toast.makeText(requireContext(), R.string.ad_pick_hint, Toast.LENGTH_SHORT).show()
    }

    /** 把当前站点加入自定义拦截规则。 */
    private fun markAsAd() {
        val host = tabManager.current?.url
            ?.let { runCatching { android.net.Uri.parse(it).host }.getOrNull() }
            ?: return
        val line = "||${host.lowercase()}^"
        viewLifecycleOwner.lifecycleScope.launch {
            val existing = AppServices.ruleRepository.customRulesText()
            if (existing.lines().none { it.trim() == line }) {
                AppServices.ruleRepository.saveCustomRules(existing.trimEnd() + "\n" + line)
            }
            AppServices.adBlock.addCustomRule(line)
        }
        Toast.makeText(requireContext(), getString(R.string.ad_marked, host), Toast.LENGTH_SHORT).show()
    }

    private fun copyToClipboard(url: String) {
        val clipboard = requireContext().getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText(null, url))
        Toast.makeText(requireContext(), R.string.link_copied, Toast.LENGTH_SHORT).show()
    }

    private fun shareUrl(url: String) {
        val share = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, url)
        }
        startActivity(Intent.createChooser(share, null))
    }

    private fun openInBackground(url: String) {
        val target = if (httpsOnlyEnabled()) upgradeToHttps(cleanUrl(url)) else cleanUrl(url)
        val previous = tabManager.currentIndex
        val tab = tabManager.newTab(target)
        val headers = privacyRequestHeaders(target)
        if (headers.isEmpty()) tab.webView.loadUrl(target)
        else {
            programmaticPrivacyLoadAt = android.os.SystemClock.elapsedRealtime()
            tab.webView.loadUrl(target, headers)
        }
        if (previous >= 0) tabManager.switchTab(previous)
        renderTabs()
    }

    private fun openWithApp(url: String) {
        runCatching {
            val intent = Intent(Intent.ACTION_VIEW, android.net.Uri.parse(url))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            startActivity(intent)
        }.onFailure {
            Toast.makeText(requireContext(), "没有可用的应用", Toast.LENGTH_SHORT).show()
        }
    }

    private fun showPageInfo() {
        val tab = tabManager.current ?: return
        val url = tab.url
        val host = runCatching { android.net.Uri.parse(url).host }.getOrNull().orEmpty()
        val ssl = if (url.startsWith("https://")) "HTTPS" else "HTTP"
        android.app.AlertDialog.Builder(requireContext())
            .setTitle(R.string.menu_page_info)
            .setMessage(
                "标题：${tab.title.ifBlank { host }}\n\n" +
                    "地址：$url\n\n" +
                    "域名：$host\n\n" +
                    "连接：$ssl"
            )
            .setPositiveButton(android.R.string.ok, null)
            .show()
    }

    private fun saveImageUrl(url: String) {
        if (DataUriTools.isDataImage(url)) {
            viewLifecycleOwner.lifecycleScope.launch {
                val saved = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                    DataUriTools.saveImage(requireContext(), url)
                }
                Toast.makeText(
                    requireContext(),
                    if (saved) "图片已保存" else "图片保存失败",
                    Toast.LENGTH_SHORT
                ).show()
            }
        } else {
            ensureNotificationPermission()
            com.sii.browser.download.DownloadDispatcher.start(
                requireContext(), url, null, null,
                tabManager.current?.url,
                tabManager.currentWebView?.settings?.userAgentString
            )
        }
    }

    private fun downloadImageUrl(url: String) {
        if (DataUriTools.isDataImage(url)) {
            viewLifecycleOwner.lifecycleScope.launch {
                val saved = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                    DataUriTools.saveImage(requireContext(), url)
                }
                Toast.makeText(
                    requireContext(),
                    if (saved) "图片已下载" else "图片下载失败",
                    Toast.LENGTH_SHORT
                ).show()
            }
        } else {
            ensureNotificationPermission()
            com.sii.browser.download.DownloadDispatcher.start(
                requireContext(), url, null, null,
                tabManager.current?.url,
                tabManager.currentWebView?.settings?.userAgentString
            )
        }
    }

    private fun shareImage(url: String) {
        if (DataUriTools.isDataImage(url)) {
            viewLifecycleOwner.lifecycleScope.launch {
                val file = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                    DataUriTools.saveTemp(requireContext(), url)
                }
                if (file == null) {
                    Toast.makeText(requireContext(), "图片分享失败", Toast.LENGTH_SHORT).show()
                    return@launch
                }
                val uri = androidx.core.content.FileProvider.getUriForFile(
                    requireContext(), "${requireContext().packageName}.fileprovider", file
                )
                val share = Intent(Intent.ACTION_SEND).apply {
                    type = "image/*"
                    putExtra(Intent.EXTRA_STREAM, uri)
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
                startActivity(Intent.createChooser(share, null))
            }
        } else {
            shareUrl(url)
        }
    }

    private fun copyLinkText() {
        val webView = tabManager.currentWebView ?: return
        val point = webView.lastTouchPoint
        if (point == null) {
            Toast.makeText(requireContext(), "未找到链接文本", Toast.LENGTH_SHORT).show()
            return
        }
        val js = "(function(){var e=document.elementFromPoint(${point.x},${point.y});" +
            "while(e&&e.tagName!='A')e=e.parentElement;" +
            "return e?e.innerText:'';})()"
        webView.evaluateJavascript(js) { raw ->
            val text = runCatching {
                (org.json.JSONTokener(raw).nextValue() as? String).orEmpty()
            }.getOrDefault("")
            if (text.isBlank()) {
                Toast.makeText(requireContext(), "未找到链接文本", Toast.LENGTH_SHORT).show()
            } else {
                copyToClipboard(text)
            }
        }
    }

    /** 打开底部查找条。 */
    private fun showFindBar() {
        val bar = findBar ?: return
        bar.visibility = View.VISIBLE
        findInput?.requestFocus()
        findInput?.post {
            val imm = requireContext().getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
            imm.showSoftInput(findInput, InputMethodManager.SHOW_IMPLICIT)
        }
    }

    /** 关闭查找条并清除页面高亮。 */
    private fun hideFindBar() {
        if (findBar?.visibility != View.VISIBLE) return
        findBar?.visibility = View.GONE
        findInput?.setText("")
        findCount?.text = ""
        tabManager.currentWebView?.clearFind()
    }

    private fun setupFindBar(view: View) {
        findInput?.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                val keyword = s?.toString().orEmpty()
                if (keyword.isEmpty()) {
                    tabManager.currentWebView?.clearFind()
                    findCount?.text = ""
                } else {
                    tabManager.currentWebView?.findInPage(keyword) { current, total ->
                        findCount?.text = if (total > 0) {
                            getString(R.string.find_result, current, total)
                        } else {
                            getString(R.string.find_no_result)
                        }
                    }
                }
            }
        })
        view.findViewById<View>(R.id.find_prev).setOnClickListener {
            tabManager.currentWebView?.findNextMatch(false)
        }
        view.findViewById<View>(R.id.find_next).setOnClickListener {
            tabManager.currentWebView?.findNextMatch(true)
        }
        view.findViewById<View>(R.id.find_close).setOnClickListener { hideFindBar() }
    }

    /**
     * 菜单电脑模式：列表启用中的站弹窗（关闭 / 仅关闭一次），
     * 其余站直接切换全局开关（无弹窗）。
     */
    private fun onDesktopMenuClick() {
        val host = currentDesktopHost()
        if (host == null) {
            // 主页等无域名页面：直接切换全局开关。
            AppServices.settings.setDesktopMode(!AppServices.settings.desktopMode())
            applyDesktopModeToAllTabs()
            return
        }
        val normalized = stripHostPrefix(host.lowercase())
        if (!inDesktopSites(host)) {
            // 不在列表启用中（未加入 / 被禁用）：直接开关全局电脑模式。
            val target = !AppServices.settings.desktopMode()
            temporaryDesktopOff.remove(normalized)
            AppServices.settings.setDesktopMode(target)
            applyDesktopModeToAllTabs()
            reloadCurrent()
            return
        }
        if (normalized in temporaryDesktopOff) {
            // 临时关闭后再次点击：直接恢复电脑模式。
            temporaryDesktopOff.remove(normalized)
            applyDesktopModeToAllTabs()
            reloadCurrent()
            return
        }
        android.app.AlertDialog.Builder(requireContext())
            .setTitle(R.string.menu_desktop)
            .setMessage(getString(R.string.desktop_mode_off_message, host))
            .setPositiveButton(R.string.desktop_mode_close) { _, _ ->
                val disabled = (disabledDesktopHosts() + normalized).distinct()
                AppServices.settings.setDesktopSitesDisabled(disabled.joinToString(","))
                applyDesktopModeToAllTabs()
                reloadCurrent()
            }
            .setNegativeButton(R.string.desktop_mode_close_once) { _, _ ->
                temporaryDesktopOff.add(normalized)
                applyDesktopModeToAllTabs()
                reloadCurrent()
            }
            .show()
    }

    private fun currentDesktopHost(): String? = runCatching {
        android.net.Uri.parse(tabManager.current?.url.orEmpty()).host
    }.getOrNull()

    private var toolbarPosBeforeGame: Int? = null
    private var gameExitArmed = false
    private var immersiveFullscreen = false
    /** IME 是否可见，用于悬浮底栏与键盘同步下移。 */
    private var imeInsetVisible = false
    private var imeInsetBottom = 0
    /** 地址栏聚焦后等键盘、底栏就位再弹剪贴板建议。 */
    private var pendingAddressClipboard = false
    /** 每个标签的网页自绘全屏状态。 */
    private val pageFullscreenStates = java.util.WeakHashMap<SiiWebView, Boolean>()
    private var translatePolling: kotlinx.coroutines.Job? = null
    private var translateStatus: android.widget.TextView? = null
    private var translateStatusFade: Runnable? = null
    private var translateToggle: android.widget.TextView? = null
    private var translateFabState = 0
    /** 翻译按钮自动隐藏计时与已隐藏过的页面，避免同一页面反复弹。 */
    private var translateFabAutoHide: Runnable? = null
    private var translateFabMutedUrl: String? = null
    private var translateShowOriginal = false
    private var translateJob: kotlinx.coroutines.Job? = null
    /** 已翻译完成的页面 URL：刷新/返回时重新套用译文，避免变回原文。 */
    private var translatedPageUrl: String? = null

    private data class TranslateTabState(
        val fab: Int,
        val showOriginal: Boolean,
        val paused: Boolean,
        val target: String,
        val source: String
    )

    /** 每个标签独立的翻译状态：切标签后恢复按钮、提示与补翻。 */
    private val translateTabStates =
        java.util.WeakHashMap<SiiWebView, TranslateTabState>()

    /** 当前页面实际使用的目标语言（站点覆盖或全局）。 */
    private var activeTranslateTarget: String = ""
    /** 当前页面识别出的源语言（离线翻译用）。 */
    private var activeTranslateSource: String = ""
    /** 源语言识别缓存：页面 URL → BCP-47 语言码。 */
    private val sourceLangCache = HashMap<String, String>()

    /** 刷新/重开后待自动重译的标签。 */
    private val pendingAutoRetranslate = java.util.WeakHashMap<SiiWebView, Boolean>()
    /** 菜单 UI 译名缓存：原文 → 译文（浏览器菜单/弹窗与网页共用本地引擎）。 */
    private val uiLabelCache = java.util.concurrent.ConcurrentHashMap<String, String>()
    private var autoTranslatePaused = false
    private var autoEmptyRounds = 0
    private var translatedTotal = 0

    /** 全屏：隐藏地址栏、标签栏、底部栏与状态栏，返回键退出。 */
    private fun toggleFullscreen() {
        immersiveFullscreen = !immersiveFullscreen
        applyFullscreen()
        renderMenuSheet()
    }

    /** 网页自己铺满视口的全屏（不走 WebChromeClient）也要收起悬浮底栏。 */
    private fun applyPageFullscreenChrome() {
        if (AppServices.settings.uiStyle() != 1) return
        if (fullscreenContainer?.visibility == View.VISIBLE) return
        val dock = view?.findViewById<View>(R.id.modern_chrome) ?: return
        val active = pageFullscreenStates[tabManager.currentWebView] == true
        dock.visibility = if (active) View.GONE else View.VISIBLE
    }

    /** 网页全屏时收起浏览器控件，退出时恢复。 */
    private fun setBrowserChromeVisible(visible: Boolean) {
        val root = view ?: return
        val modern = AppServices.settings.uiStyle() == 1
        root.findViewById<View>(R.id.modern_chrome)?.visibility =
            if (modern && visible) View.VISIBLE else View.GONE
        root.findViewById<View>(R.id.address_bar)?.visibility =
            if (visible) View.VISIBLE else View.GONE
        root.findViewById<View>(R.id.bottom_bar)?.visibility =
            if (visible) View.VISIBLE else View.GONE
        root.findViewById<View>(R.id.progress_bar)?.visibility = View.GONE
        if (visible) {
            updateQuickEngineVisibility()
            updateTranslateFab()
        }
    }

    /** 网页全屏视频隐藏状态栏与导航栏，让播放控件不被系统栏压住。 */
    private fun setVideoFullscreenSystemBars(hide: Boolean) {
        val window = activity?.window ?: return
        val controller = androidx.core.view.WindowCompat.getInsetsController(window, window.decorView)
        if (hide) {
            controller.hide(androidx.core.view.WindowInsetsCompat.Type.systemBars())
            controller.systemBarsBehavior =
                androidx.core.view.WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        } else {
            controller.show(androidx.core.view.WindowInsetsCompat.Type.systemBars())
        }
    }

    private fun applyFullscreen() {
        val hide = immersiveFullscreen
        view?.findViewById<View>(R.id.address_bar)?.visibility =
            if (hide) View.GONE else View.VISIBLE
        view?.findViewById<View>(R.id.search_toolbar_scroll)?.visibility =
            if (hide) View.GONE else View.VISIBLE
        view?.findViewById<View>(R.id.bottom_bar)?.visibility =
            if (hide) View.GONE else View.VISIBLE
        if (hide) {
            tabBar?.visibility = View.GONE
        } else {
            tabBar?.visibility = when {
                AppServices.settings.tabBarPosition() == 2 -> View.GONE
                tabManager.size > 1 -> View.VISIBLE
                else -> View.GONE
            }
        }
        val window = activity?.window ?: return
        val controller = androidx.core.view.WindowCompat.getInsetsController(window, window.decorView)
        if (hide) {
            controller.hide(androidx.core.view.WindowInsetsCompat.Type.statusBars())
        } else {
            controller.show(androidx.core.view.WindowInsetsCompat.Type.statusBars())
        }
    }

    /** 最近关闭的标签列表：选择后重新打开。 */
    private fun showRecentlyClosed() {
        val items = tabManager.recentlyClosed()
        if (items.isEmpty()) {
            Toast.makeText(requireContext(), R.string.recent_closed_empty, Toast.LENGTH_SHORT).show()
            return
        }
        val labels = items.map { item ->
            val host = runCatching { android.net.Uri.parse(item.url).host }.getOrNull().orEmpty()
            val name = item.title.ifBlank { item.url }
            if (host.isNotEmpty()) "$name · $host" else name
        }.toTypedArray()
        android.app.AlertDialog.Builder(requireContext())
            .setTitle(R.string.menu_recent_closed)
            .setItems(labels) { _, which ->
                val item = items[which]
                tabManager.removeClosed(item)
                openNewTab(item.url)
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    /** 游戏模式：隐藏工具栏/状态栏、锁横屏，按两次返回键退出。 */
    private fun setGameMode(on: Boolean) {
        val s = AppServices.settings
        s.setGameMode(on)
        if (on) {
            if (toolbarPosBeforeGame == null) toolbarPosBeforeGame = s.toolbarPosition()
            s.setToolbarPosition(2)
            activity?.requestedOrientation =
                android.content.pm.ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
            hideSystemBars(true)
            Toast.makeText(requireContext(), R.string.game_mode_on_hint, Toast.LENGTH_SHORT).show()
        } else {
            val restore = toolbarPosBeforeGame
            if (restore != null) {
                s.setToolbarPosition(restore)
            } else if (s.toolbarPosition() == 2) {
                // 从旧版或上次会话进入的游戏模式没有保存原位置，恢复到底部。
                s.setToolbarPosition(0)
            }
            toolbarPosBeforeGame = null
            gameExitArmed = false
            (activity as? ShellActivity)?.applyOrientation()
            hideSystemBars(false)
            // 沉浸式退出后布局层可能未刷新，强制重建一次（与夜间切换同路径）。
            view?.post {
                activity?.let { act -> ShellActivity.smoothRecreateForNightSwitch(act) }
            }
        }
        AppServices.applyLayout?.invoke()
        applyGameModeToWebView()
        renderMenuSheet()
    }

    private fun hideSystemBars(hide: Boolean) {
        val window = activity?.window ?: return
        val controller = androidx.core.view.WindowCompat.getInsetsController(window, window.decorView)
        if (hide) {
            controller.hide(androidx.core.view.WindowInsetsCompat.Type.systemBars())
            controller.systemBarsBehavior =
                androidx.core.view.WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        } else {
            controller.show(androidx.core.view.WindowInsetsCompat.Type.systemBars())
        }
    }

    private fun applyGameModeToWebView() {
        val on = AppServices.settings.gameMode()
        tabManager.tabs.forEach { tab ->
            tab.webView.pullRefreshEnabled = AppServices.settings.pullRefresh() && !on
            tab.webView.isLongClickable = !on
            tab.webView.settings.setSupportZoom(!on)
        }
    }

    /** 把站点加入默认电脑模式列表并启用（永久）。 */
    private fun enableDesktopSite(host: String) {
        val normalized = stripHostPrefix(host.lowercase())
        temporaryDesktopOff.remove(normalized)
        val disabled = disabledDesktopHosts() - normalized
        AppServices.settings.setDesktopSitesDisabled(disabled.joinToString(","))
        val sites = desktopSiteEntries()
        if (sites.none { stripHostPrefix(it.lowercase()) == normalized }) {
            AppServices.settings.setDesktopSites((sites + host).joinToString(","))
        }
        applyDesktopModeToAllTabs()
        reloadCurrent()
    }

    private fun reloadCurrent() {
        val webView = tabManager.currentWebView ?: return
        val url = tabManager.current?.url
        // 强制回源重载，让服务器按桌面 UA 302 到电脑版（缓存里的手机页不会自己变）。
        if (!url.isNullOrEmpty() && url.startsWith("http")) {
            webView.loadUrl(
                url,
                mapOf("Cache-Control" to "no-cache", "Pragma" to "no-cache")
            )
        } else {
            webView.reload()
        }
    }

    /** 按每个标签自己的 URL 重新落 UA（电脑模式只对命中站点生效）。 */
    private fun applyDesktopModeToAllTabs() {
        tabManager.tabs.forEach { tab ->
            val ua = if (isDesktopForUrl(tab.url)) desktopUserAgent() else globalUserAgent()
            if (tab.webView.settings.userAgentString != ua) {
                tab.webView.settings.userAgentString = ua
            }
            tab.webView.siteConfigSignature = null
        }
    }

    /** 地址栏样式：0 无外框 / 1 底部横线 / 2 圆角外框。 */
    private fun applyAddressStyle() {
        val input = view?.findViewById<EditText>(R.id.address_input) ?: return
        val density = resources.displayMetrics.density
        fun dp(value: Int) = (value * density).toInt()
        // 新版地址输入在胶囊内，不再套用经典的内层外框。
        if (AppServices.settings.uiStyle() == 1) {
            input.background = null
            input.setPadding(dp(4), 0, dp(8), 0)
            return
        }
        when (AppServices.settings.addressStyle().coerceIn(0, 2)) {
            1 -> {
                input.setBackgroundResource(R.drawable.bg_address_underline)
                input.setPadding(dp(4), 0, dp(8), 0)
            }
            2 -> {
                input.setBackgroundResource(R.drawable.bg_address_field)
                input.setPadding(dp(14), 0, dp(14), 0)
            }
            else -> {
                input.background = null
                input.setPadding(dp(4), 0, dp(8), 0)
            }
        }
    }

    /** 当前是否电脑模式（站点级：临时关闭 > 禁用 > 默认列表 > 手机）。 */
    private fun isDesktopMode(): Boolean = isDesktopForUrl(tabManager.current?.url)

    /** 指定 URL 是否按电脑模式处理。 */
    private fun isDesktopForUrl(url: String?): Boolean {
        val host = runCatching { android.net.Uri.parse(url.orEmpty()).host }.getOrNull() ?: return false
        return desktopForHost(host)
    }

    /** 电脑模式判定：会话临时关闭 > 全局开关 > 默认列表启用 > 映射域。 */
    private fun desktopForHost(host: String?): Boolean {
        val h = host?.lowercase() ?: return false
        if (stripHostPrefix(h) in temporaryDesktopOff) return false
        if (AppServices.settings.desktopMode()) return true
        return inDesktopSites(h) || h in desktopAliasHosts
    }

    /** 加载失败页：错误页也用 WebView 渲染，页面由 Sii 自己写。 */
    private fun errorPageHtml(url: String, description: String?): String {
        fun esc(s: String) = s.replace("&", "&amp;").replace("<", "&lt;").replace("\"", "&quot;")
        val target = esc(url)
        val desc = esc(description.orEmpty())
        return "<!DOCTYPE html><html lang=\"zh-CN\"><head><meta charset=\"utf-8\">" +
            "<meta name=\"viewport\" content=\"width=device-width,initial-scale=1,viewport-fit=cover\">" +
            "<title>无法打开该网页</title><style>" +
            "body{margin:0;background:#f6f7f9;color:#1a1a1e;text-align:center;padding:72px 24px;" +
            "font-family:-apple-system,system-ui,sans-serif}" +
            ".title{font-size:17px;font-weight:600;margin-bottom:16px}" +
            ".code{background:#ececf0;font-size:13px;color:#8a8f99;word-break:break-all;" +
            "border-radius:10px;padding:8px 10px;margin:0 0 12px;text-align:left}" +
            ".retry{display:inline-block;margin-top:20px;padding:11px 26px;background:#2f7cf6;color:#fff;" +
            "border-radius:10px;text-decoration:none;font-size:14px}" +
            "</style></head><body>" +
            "<div class=\"title\">无法打开该网页</div>" +
            "<div class=\"code\">$target</div>" +
            (if (desc.isEmpty()) "" else "<div class=\"code\">$desc</div>") +
            "<a class=\"retry\" href=\"$target\">重试</a>" +
            "</body></html>"
    }

    private fun openNetlog() {
        NavAnim.apply(parentFragmentManager.beginTransaction())
            .replace(
                R.id.shell_root,
                SettingsDetailFragment().apply {
                    arguments = android.os.Bundle().apply {
                        putString(
                            SettingsDetailFragment.ARG_VIEW,
                            SettingsDetailFragment.VIEW_V2_NETLOG
                        )
                    }
                }
            )
            .addToBackStack(null)
            .commit()
    }

    /** 内置 JS 控制台：查看 console 输出并执行 JS。 */
    private fun openJsConsole() {
        val webView = tabManager.currentWebView ?: return
        val context = requireContext()
        val density = resources.displayMetrics.density
        fun dp(value: Int): Int = (value * density).toInt()

        val output = TextView(context).apply {
            typeface = android.graphics.Typeface.MONOSPACE
            setTextSize(10f)
            setTextColor(context.getColor(R.color.text_primary))
            setPadding(dp(8), dp(8), dp(8), dp(8))
        }
        val scroll = android.widget.ScrollView(context).apply { addView(output) }
        val input = android.widget.EditText(context).apply {
            hint = getString(R.string.js_console_hint)
            setSingleLine(true)
            setTextSize(13f)
        }
        val execute = android.widget.Button(context).apply {
            text = getString(R.string.js_console_run)
            setOnClickListener {
                val code = input.text.toString().trim()
                if (code.isEmpty()) return@setOnClickListener
                com.sii.browser.core.webview.JsConsoleStore.add("INPUT", "", 0, code)
                webView.evaluateJavascript(code) { result ->
                    com.sii.browser.core.webview.JsConsoleStore.add(
                        "RESULT", "", 0, result.orEmpty()
                    )
                }
                input.setText("")
            }
        }
        val clear = android.widget.Button(context).apply {
            text = getString(R.string.js_console_clear)
            setOnClickListener { com.sii.browser.core.webview.JsConsoleStore.clear() }
        }
        val actions = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
            addView(input, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            addView(execute)
            addView(clear)
        }
        val root = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            addView(
                scroll,
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    dp(320)
                )
            )
            addView(TextView(context).apply {
                text = getString(R.string.js_console_long_press_hint)
                setTextSize(11f)
                setTextColor(context.getColor(R.color.text_secondary))
                setPadding(dp(4), dp(4), dp(4), dp(2))
            })
            addView(actions)
        }
        val dialog = android.app.AlertDialog.Builder(context)
            .setTitle(R.string.menu_js_console)
            .setView(root)
            .setNegativeButton(android.R.string.cancel, null)
            .create()

        fun render() {
            val logEnabled = AppServices.settings.jsConsoleLog()
            output.text = com.sii.browser.core.webview.JsConsoleStore.snapshot()
                .joinToString("\n") { entry ->
                    val source = if (entry.source.isBlank()) "" else " (${entry.source}:${entry.line})"
                    "[${entry.level}]$source ${entry.message}"
                }
                .ifEmpty {
                    getString(
                        if (logEnabled) R.string.js_console_empty
                        else R.string.js_console_log_disabled
                    )
                }
            scroll.post { scroll.fullScroll(android.view.View.FOCUS_DOWN) }
        }
        com.sii.browser.core.webview.JsConsoleStore.onChange = { render() }
        dialog.setOnDismissListener { com.sii.browser.core.webview.JsConsoleStore.onChange = null }
        render()
        dialog.show()
    }

    private fun openSettings() {
        NavAnim.apply(parentFragmentManager.beginTransaction())
            .replace(R.id.shell_root, SettingsFragment())
            .addToBackStack(null)
            .commit()
    }

    private fun shareCurrentUrl() {
        val url = tabManager.current?.url ?: return
        shareText(url)
    }

    /** 分享一段文本（链接/选中文字共用）。 */
    private fun shareText(text: String) {
        if (text.isBlank()) return
        val share = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, text)
        }
        startActivity(Intent.createChooser(share, null))
    }

    /** 文本选择菜单附加项（按设置启用）。 */
    private fun buildSelectionExtras(): List<Pair<String, (String) -> Unit>> {
        val enabled = AppServices.settings.selectionMenuItems().orEmpty()
            .split(",").filter { it.isNotEmpty() }.toSet()
        val list = mutableListOf<Pair<String, (String) -> Unit>>()
        if ("search" in enabled) {
            list.add(getString(R.string.sel_menu_search) to { text ->
                if (text.isNotBlank()) loadUrlFor(resolveInput(text))
            })
        }
        if ("share" in enabled) {
            list.add(getString(R.string.sel_menu_share) to { text -> shareText(text) })
        }
        if ("copy" in enabled) {
            list.add(getString(R.string.sel_menu_copy) to { text -> copyToClipboard(text) })
        }
        list.add(getString(R.string.sel_menu_translate) to { text ->
            if (text.isNotBlank()) translateSelection(text)
        })
        return list
    }

    /** 划词翻译：弹窗显示结果，可复制。 */
    private fun translateSelection(text: String) {
        if (AppServices.settings.translateMode() == 0) {
            Toast.makeText(requireContext(), R.string.translate_disabled, Toast.LENGTH_SHORT).show()
            return
        }
        val dialog = android.app.AlertDialog.Builder(requireContext())
            .setTitle(R.string.sel_menu_translate)
            .setMessage(R.string.translate_loading)
            .setNegativeButton(android.R.string.cancel, null)
            .create()
        dialog.show()
        viewLifecycleOwner.lifecycleScope.launch {
            val offline = AppServices.settings.translateMode() == 2
            val from = if (offline) {
                com.sii.browser.core.webview.LanguageId.identify(requireContext(), text)
            } else null
            if (offline && from.isNullOrBlank()) {
                dialog.setMessage(getString(R.string.translate_source_unknown))
                return@launch
            }
            val to = effectiveTranslateTarget(currentPageHost())
            val result = com.sii.browser.core.webview.Translator.translate(
                requireContext(), text, to, from
            )
            if (!isAdded) return@launch
            dialog.setMessage(result.text ?: getString(R.string.translate_failed))
            if (!result.text.isNullOrBlank()) {
                dialog.setButton(
                    android.content.DialogInterface.BUTTON_POSITIVE,
                    getString(R.string.sel_menu_copy)
                ) { _, _ -> copyToClipboard(result.text) }
            }
        }
    }

    /** 站点翻译按钮策略：0 跟随全局 / 1 总是显示 / 2 不显示。 */
    private fun siteTranslateButtonMode(host: String?): Int =
        SiteConfig.tri(siteConfigFor(host), "trbtn") ?: 0

    /** 站点翻译方式：0 跟随全局 / 1 替换原文 / 2 双语对照。 */
    private fun siteTranslateMode(host: String?): Int =
        SiteConfig.tri(siteConfigFor(host), "trmode") ?: 0

    private fun effectiveTranslateDual(host: String?): Boolean =
        when (siteTranslateMode(host)) {
            1 -> false
            2 -> true
            else -> AppServices.settings.translateDual()
        }

    private fun effectiveTranslateTarget(host: String?): String {
        val site = SiteConfig.text(siteConfigFor(host), "trlang")
        if (!site.isNullOrBlank()) {
            return if (site.startsWith("zh")) "zh-CN" else site
        }
        return com.sii.browser.core.webview.Translator.targetLang(requireContext())
    }

    /** 从网站设定返回后，按最新站点配置重新计算翻译按钮。 */
    private fun refreshTranslateFabForSite() {
        if (!isAdded) return
        val wv = tabManager.currentWebView ?: return
        val url = tabManager.current?.url ?: return
        if (!url.startsWith("http")) return
        val host = runCatching { android.net.Uri.parse(url).host }.getOrNull()?.lowercase()
        if (siteTranslateButtonMode(host) == 2) {
            translateToggle?.visibility = View.GONE
            return
        }
        if (translateFabState == 1) {
            translateFabState = 0
            updateTranslateFab()
            saveTranslateTabState(wv)
        }
        maybeShowTranslateFab(wv, url)
    }

    /** 检测页面语言，与目标语言不同时显示翻译悬浮按钮。 */
    private fun maybeShowTranslateFab(
        webView: SiiWebView?,
        url: String,
        allowRetry: Boolean = true
    ) {
        if (webView == null || webView !== tabManager.currentWebView) return
        if (AppServices.settings.translateMode() == 0) return
        if (!url.startsWith("http")) return
        if (isDesktopMode()) return
        if (homeContainer?.visibility == View.VISIBLE) return
        val host = runCatching { android.net.Uri.parse(url).host }.getOrNull()?.lowercase()
        val buttonMode = siteTranslateButtonMode(host)
        if (buttonMode == 2) return
        if (buttonMode == 1) {
            if (!isAdded || translateFabState != 0) return
            translateFabState = 1
            updateTranslateFab()
            saveTranslateTabState(webView)
            return
        }
        if (!AppServices.settings.translateDetect()) return
        if (url == translateFabMutedUrl) return
        webView.evaluateJavascript(TRANSLATE_DETECT_JS) { raw ->
            val obj = parseJsonObject(raw) ?: return@evaluateJavascript
            if (obj.optBoolean("needRetry")) {
                if (allowRetry) {
                    view?.postDelayed({
                        maybeShowTranslateFab(webView, url, allowRetry = false)
                    }, 1000L)
                }
                return@evaluateJavascript
            }
            if (!isDifferentLanguage(obj, effectiveTranslateTarget(host))) return@evaluateJavascript
            if (!isAdded || translateFabState != 0) return@evaluateJavascript
            translateFabState = 1
            updateTranslateFab()
            saveTranslateTabState(webView)
        }
    }

    /** 页面语言与目标语言是否不同；lang 与字符集冲突时以字符集为准。 */
    private fun isDifferentLanguage(obj: org.json.JSONObject?, targetLang: String): Boolean {
        if (obj == null) return false
        val lang = obj.optString("lang").lowercase()
        val script = obj.optString("script").lowercase()
        if (lang.isBlank() && script.isBlank()) return false
        val target = targetLang.lowercase()
        fun matches(code: String): Boolean = when {
            target.startsWith("zh") -> code.startsWith("zh")
            target.startsWith("en") -> code.startsWith("en") || code == "latin"
            else -> code.startsWith(target.substringBefore('-')) || code == "latin"
        }
        if (lang.isNotBlank()) return !matches(lang)
        if (script.isNotBlank()) return !matches(script)
        return false
    }

    /** 整页翻译：合并文本批量翻译，新增内容由 Kotlin 定时扫描补翻。 */
    private fun translatePage() {
        translateFabMutedUrl = null
        if (AppServices.settings.translateMode() == 0) {
            showTranslateStatus(getString(R.string.translate_disabled), autoHide = true)
            return
        }
        if (translateFabState == 2) return
        if (translateJob?.isActive == true) return
        val wv = tabManager.currentWebView ?: return
        val host = runCatching { android.net.Uri.parse(wv.url).host }.getOrNull()?.lowercase()
        val target = effectiveTranslateTarget(host)
        val dual = effectiveTranslateDual(host)
        activeTranslateTarget = target
        com.sii.browser.core.webview.TranslationTrace.log(
            requireContext(),
            "page translate start mode=${AppServices.settings.translateMode()} " +
                "target=$target dual=$dual " +
                "collect=${AppServices.settings.translateCollect()} " +
                "scan=${AppServices.settings.translateScan()} " +
                "total=${AppServices.settings.translateLimit()}"
        )
        translateFabState = 2
        updateTranslateFab()
        saveTranslateTabState(wv)
        val collectLimit = AppServices.settings.translateCollect().coerceIn(1, 2000)
        val configuredTotal = AppServices.settings.translateLimit()
        val jsTotalLimit = if (configuredTotal <= 0) 1_000_000_000 else configuredTotal
        wv.evaluateJavascript(
            TRANSLATE_COLLECT_JS
                .replace("__COLLECT_LIMIT__", collectLimit.toString())
                .replace("__TOTAL_LIMIT__", jsTotalLimit.toString())
                .replace("__DUAL__", if (dual) "true" else "false")
        ) { raw ->
            val obj = parseJsonObject(raw) ?: run {
                translateFabState = 0
                updateTranslateFab()
                return@evaluateJavascript
            }
            if (obj.optBoolean("done")) {
                com.sii.browser.core.webview.TranslationTrace.log(requireContext(), "page translate restore")
                translatePolling?.cancel()
                translateJob?.cancel()
                wv.evaluateJavascript(TRANSLATE_RESTORE_JS, null)
                translateFabState = 0
                autoTranslatePaused = false
                autoEmptyRounds = 0
                translatedTotal = 0
                uiLabelCache.clear()
                updateTranslateFab()
                showTranslateStatus(getString(R.string.translate_restored), autoHide = true)
                return@evaluateJavascript
            }
            val texts = obj.optJSONArray("texts") ?: return@evaluateJavascript
            com.sii.browser.core.webview.TranslationTrace.log(
                requireContext(),
                "collect texts=${texts.length()} visible=${obj.optInt("visible", -1)} hidden=${obj.optInt("hidden", -1)}"
            )
            if (texts.length() == 0) {
                Toast.makeText(requireContext(), R.string.translate_no_text, Toast.LENGTH_SHORT).show()
                translateFabState = 0
                updateTranslateFab()
                return@evaluateJavascript
            }
            autoTranslatePaused = false
            autoEmptyRounds = 0
            translatedTotal = 0
            showTranslateStatus(
                getString(R.string.translate_in_progress),
                initialAlpha = 1f
            )
            translateShowOriginal = false
            translateJob = translateAndApply(wv, texts, dynamic = false, target = target)
        }
    }

    private fun parseJsonObject(raw: String?): org.json.JSONObject? {
        val parsed = runCatching { org.json.JSONTokener(raw).nextValue() }.getOrNull()
        return when (parsed) {
            is org.json.JSONObject -> parsed
            is String -> runCatching { org.json.JSONObject(parsed) }.getOrNull()
            else -> null
        }
    }

    private fun parseJsonArray(raw: String?): org.json.JSONArray? {
        val parsed = runCatching { org.json.JSONTokener(raw).nextValue() }.getOrNull()
        return when (parsed) {
            is org.json.JSONArray -> parsed
            is String -> runCatching { org.json.JSONArray(parsed) }.getOrNull()
            else -> null
        }
    }

    private fun sampleTranslateText(texts: org.json.JSONArray): String {
        val sb = StringBuilder()
        for (i in 0 until texts.length()) {
            val value = texts.optString(i)
            if (value.isBlank()) continue
            sb.append(value).append(' ')
            if (sb.length >= 200) break
        }
        return sb.toString().take(200)
    }

    private fun failTranslate(wv: SiiWebView, messageRes: Int) {
        wv.evaluateJavascript(TRANSLATE_RESTORE_JS, null)
        translateFabState = 1
        updateTranslateFab()
        saveTranslateTabState(wv)
        hideTranslateStatus()
        Toast.makeText(requireContext(), messageRes, Toast.LENGTH_SHORT).show()
    }

    private fun translateAndApply(
        wv: SiiWebView,
        texts: org.json.JSONArray,
        dynamic: Boolean,
        target: String
    ): kotlinx.coroutines.Job {
        return viewLifecycleOwner.lifecycleScope.launch {
            var source = activeTranslateSource
            if (!dynamic && AppServices.settings.translateMode() == 2) {
                val url = wv.url.orEmpty()
                source = sourceLangCache[url]
                    ?: com.sii.browser.core.webview.LanguageId.identify(
                        requireContext(), sampleTranslateText(texts)
                    ).orEmpty()
                if (source.isBlank()) {
                    failTranslate(wv, R.string.translate_source_unknown)
                    return@launch
                }
                if (!source.startsWith("zh") && !source.startsWith("en")) {
                    failTranslate(wv, R.string.translate_source_unsupported)
                    return@launch
                }
                val targetLower = target.lowercase()
                if ((source.startsWith("zh") && targetLower.startsWith("zh")) ||
                    (source.startsWith("en") && targetLower.startsWith("en"))
                ) {
                    failTranslate(wv, R.string.translate_same_language)
                    return@launch
                }
                sourceLangCache[url] = source
                activeTranslateSource = source
            }
            var lastProgressAt = 0L
            val onProgress: (Int, Int) -> Unit = { done, total ->
                if (!dynamic && total > 0) {
                    val now = android.os.SystemClock.elapsedRealtime()
                    if (now - lastProgressAt >= 200 || done >= total) {
                        lastProgressAt = now
                        view?.post {
                            if (isAdded && translateFabState == 2) {
                                showTranslateStatus(
                                    getString(R.string.translate_progress, done, total),
                                    initialAlpha = 1f
                                )
                            }
                        }
                    }
                }
            }
            val (results, failed) = translateBatch(texts, target, source, onProgress)
            if (!isAdded) return@launch
            val nonEmpty = (0 until results.length()).count {
                results.optString(it).isNotBlank()
            }
            com.sii.browser.core.webview.TranslationTrace.log(
                requireContext(),
                "apply texts=${texts.length()} out=${results.length()} nonEmpty=$nonEmpty " +
                    "failed=$failed dynamic=$dynamic"
            )
            if (!dynamic && nonEmpty == 0) {
                // 全部失败：区分离线语言包缺失与网络/接口错误。
                val offlineMissing = AppServices.settings.translateMode() == 2 &&
                    !runCatching {
                        com.sii.browser.core.webview.OfflineTranslator.isReady(requireContext())
                    }.getOrDefault(false)
                val messageRes = if (offlineMissing) {
                    R.string.translate_failed_offline_pack
                } else {
                    R.string.translate_failed
                }
                com.sii.browser.core.webview.TranslationTrace.log(
                    requireContext(), "translate failed all offlineMissing=$offlineMissing"
                )
                wv.evaluateJavascript(TRANSLATE_RESTORE_JS, null)
                translateFabState = 1
                updateTranslateFab()
                saveTranslateTabState(wv)
                hideTranslateStatus()
                Toast.makeText(requireContext(), messageRes, Toast.LENGTH_SHORT).show()
                return@launch
            }
            if (!dynamic && nonEmpty < results.length()) {
                Toast.makeText(requireContext(), R.string.translate_partial, Toast.LENGTH_SHORT).show()
            }
            val json = results.toString()
            val applyJs = if (dynamic) {
                "(function(){try{window.__siiApplyPending($json);}catch(e){}})()"
            } else {
                "(function(){var t=window.__siiTranslate;if(!t)return;var v=$json;" +
                    "window.__siiAll=window.__siiAll||[];window.__siiTCache=window.__siiTCache||{};" +
                    "for(var i=0;i<t.els.length;i++){if(v[i]){var e=t.els[i];" +
                    "window.__siiSetTranslated(e,v[i]);window.__siiAll.push(e);" +
                    "window.__siiTCache[e.__siiOrig]=v[i];}}" +
                    "window.__siiMode='translated';if(window.__siiPersist)window.__siiPersist();})()"
            }
            wv.evaluateJavascript(applyJs, null)
            if (!dynamic) {
                translatedPageUrl = wv.url?.takeIf { it.startsWith("http") }
                translateUiLabels(target)
                translateFabState = 3
                translateShowOriginal = false
                updateTranslateFab()
                updateAutoTranslateStatus()
                saveTranslateTabState(wv)
                // 初次翻译全部回填后再开始补翻，避免后面的内容先出现译文。
                startTranslatePolling(wv)
            }
        }
    }

    /** 合并多段文本成 1～3 次请求；分隔符被翻译器破坏时逐段回退。 */
    private suspend fun translateBatch(
        texts: org.json.JSONArray,
        target: String,
        source: String,
        onProgress: ((Int, Int) -> Unit)? = null
    ): Pair<org.json.JSONArray, Boolean> {
        val results = org.json.JSONArray()
        var failed = false
        // 离线：逐条并发翻译，不合并文本，避免分隔符被模型改坏导致字符错位。
        if (AppServices.settings.translateMode() == 2) {
            val sourceTexts = (0 until texts.length()).map { texts.optString(it).take(1000) }
            val resultsList = com.sii.browser.core.webview.OfflineTranslator.translateBatch(
                requireContext(), sourceTexts, target, source, onProgress
            )
            resultsList.forEach { value ->
                if (value == null) failed = true
                results.put(value ?: "")
            }
            return results to failed
        }
        var index = 0
        val chunk = AppServices.settings.translateChunk().coerceIn(500, 8000)
        while (index < texts.length()) {
            val startIndex = index
            val builder = StringBuilder()
            var count = 0
            while (index < texts.length() && builder.length < chunk) {
                if (count > 0) builder.append("\n[[SII").append(count).append("]]\n")
                builder.append(texts.optString(index))
                index++
                count++
            }
            val translated = com.sii.browser.core.webview.Translator.translate(
                requireContext(), builder.toString(), target
            ).text
            val parts = translated?.split(Regex("\\[\\[\\s*SII\\s*\\d+\\s*\\]\\]"))
            if (parts != null && parts.size == count) {
                parts.forEach { results.put(it.trim()) }
                onProgress?.invoke(index.coerceAtMost(texts.length()), texts.length())
            } else {
                // 分隔符被翻译服务改坏：并发逐条重试，避免串行等待。
                val gate = Semaphore(4)
                val singles = kotlinx.coroutines.coroutineScope {
                    (startIndex until startIndex + count).map { j ->
                        async {
                            j to gate.withPermit {
                                com.sii.browser.core.webview.Translator.translate(
                                    requireContext(), texts.optString(j).take(1000), target
                                ).text
                            }
                        }
                    }.awaitAll()
                }
                singles.forEach { (j, value) ->
                    if (value == null) failed = true
                    results.put(j, value ?: "")
                }
                onProgress?.invoke(index.coerceAtMost(texts.length()), texts.length())
            }
        }
        return results to failed
    }

    /** 页面翻译完成后同步翻译浏览器菜单/弹窗文案（与网页共用本地引擎与缓存）。 */
    private fun translateUiLabels(target: String = "") {
        if (AppServices.settings.translateMode() == 0) return
        val to = target.ifBlank {
            com.sii.browser.core.webview.Translator.targetLang(requireContext())
        }
        viewLifecycleOwner.lifecycleScope.launch {
            val uiSource = requireContext().resources.configuration.locales[0].language
            val labels = menuCatalog().map { it.label } +
                listOf(
                    getString(R.string.menu_font_size),
                    getString(R.string.menu_ui_scale),
                    getString(R.string.site_menu_cookies),
                    getString(R.string.site_menu_settings),
                    getString(R.string.site_menu_settings_enabled),
                    getString(R.string.site_menu_scripts),
                    getString(R.string.site_menu_scripts_matched),
                    getString(R.string.site_menu_add_desktop),
                    getString(R.string.menu_recent_closed),
                    getString(R.string.translate_show_original),
                    getString(R.string.translate_show_translated)
                )
            val pending = labels.distinct().filter { it.isNotBlank() && !uiLabelCache.containsKey(it) }
            com.sii.browser.core.webview.TranslationTrace.log(
                requireContext(), "ui labels total=${labels.size} pending=${pending.size}"
            )
            // 并行翻译，互不阻塞；共用 OfflineTranslator 的 LRU 缓存。
            kotlinx.coroutines.coroutineScope {
                pending.map { text ->
                    launch {
                        val result = com.sii.browser.core.webview.Translator.translate(
                            requireContext(), text, to, uiSource
                        )
                        result.text?.let { uiLabelCache[text] = it }
                    }
                }
            }
            if (pending.isNotEmpty() && menuOverlay?.visibility == View.VISIBLE) renderMenuSheet()
            com.sii.browser.core.webview.TranslationTrace.log(
                requireContext(), "ui labels done hits=${uiLabelCache.size}"
            )
        }
    }

    /** UI 文案译文：页面已翻译且缓存命中时返回译文，否则原文。 */
    private fun uiText(raw: String): String {
        if (translateFabState != 3) return raw
        return uiLabelCache[raw] ?: raw
    }

    /** 中央提示按钮：初次翻译中取消；自动补翻中暂停/继续。 */
    private fun onTranslateStatusClick() {
        when {
            translateFabState == 2 -> cancelTranslate()
            autoTranslatePaused -> {
                autoTranslatePaused = false
                updateAutoTranslateStatus()
                saveTranslateTabState()
                // 视图重建后轮询可能已被取消，继续时重新拉起。
                tabManager.currentWebView?.let { wv ->
                    if (translatePolling?.isActive != true) startTranslatePolling(wv)
                }
            }
            translateFabState == 3 -> {
                autoTranslatePaused = true
                updateAutoTranslateStatus()
                saveTranslateTabState()
            }
        }
    }

    private fun updateAutoTranslateStatus() {
        val status = translateStatus ?: return
        if (autoTranslatePaused) {
            val text = getString(R.string.translate_resume_auto)
            showTranslateStatus(text)
            status.alpha = 0.5f
            scheduleStatusFade(status, text)
        } else {
            cancelStatusFade()
            showTranslateStatus(getString(R.string.translate_stop_auto))
            status.alpha = 1f
        }
    }

    /** 暂停提示 3 秒后降到 0.2；点击恢复或其它状态会取消。 */
    private fun scheduleStatusFade(status: android.widget.TextView, text: String) {
        cancelStatusFade()
        val runnable = Runnable {
            if (!isAdded) return@Runnable
            if (autoTranslatePaused && translateFabState == 3 && status.text == text) {
                status.animate().alpha(0.2f).setDuration(300).start()
            }
        }
        translateStatusFade = runnable
        status.postDelayed(runnable, 3000L)
    }

    private fun cancelStatusFade() {
        translateStatusFade?.let { runnable ->
            translateStatus?.removeCallbacks(runnable)
        }
        translateStatusFade = null
    }

    /** 翻译进度提示：完成后自动淡出。 */
    private fun showTranslateStatus(
        text: String,
        autoHide: Boolean = false,
        initialAlpha: Float = 1f
    ) {
        val status = translateStatus ?: return
        if (text != getString(R.string.translate_resume_auto)) cancelStatusFade()
        status.animate().cancel()
        status.text = text
        status.alpha = initialAlpha
        status.visibility = View.VISIBLE
        if (autoHide) {
            status.postDelayed({
                if (status.text == text) hideTranslateStatus()
            }, 1600)
        }
    }

    private fun hideTranslateStatus() {
        cancelStatusFade()
        val status = translateStatus ?: return
        status.animate().cancel()
        status.animate().alpha(0f).setDuration(150).withEndAction {
            status.visibility = View.GONE
        }.start()
    }

    /** 取消翻译：停止任务与轮询，还原已翻译内容。 */
    private fun cancelTranslate() {
        translateJob?.cancel()
        translateJob = null
        translatePolling?.cancel()
        tabManager.currentWebView?.evaluateJavascript(TRANSLATE_RESTORE_JS, null)
        translateFabState = 1
        translateShowOriginal = false
        autoTranslatePaused = false
        autoEmptyRounds = 0
        translatedTotal = 0
        uiLabelCache.clear()
        updateTranslateFab()
        hideTranslateStatus()
        saveTranslateTabState()
    }

    /** 原文/译文切换，使用本地缓存的译文，不重新请求。 */
    private fun toggleTranslation() {
        val wv = tabManager.currentWebView ?: return
        wv.evaluateJavascript(
            "(function(){return window.__siiToggle?window.__siiToggle():'none';})()"
        ) { raw ->
            translateShowOriginal = raw?.trim('"') == "original"
            updateTranslateFab()
            saveTranslateTabState()
        }
    }

    /** 从设置页返回后恢复翻译按钮、中央提示与自动补翻。 */
    private fun saveTranslateTabState(wv: SiiWebView? = tabManager.currentWebView) {
        val target = wv ?: return
        translateTabStates[target] = TranslateTabState(
            translateFabState, translateShowOriginal, autoTranslatePaused,
            activeTranslateTarget, activeTranslateSource
        )
    }

    /** 切换标签后恢复该标签的翻译按钮、中央提示与轮询。 */
    private fun applyTranslateTabState(wv: SiiWebView?) {
        val state = wv?.let { translateTabStates[it] }
            ?: TranslateTabState(0, false, false, "", "")
        translateFabState = state.fab
        translateShowOriginal = state.showOriginal
        autoTranslatePaused = state.paused
        activeTranslateTarget = state.target
        activeTranslateSource = state.source
        autoEmptyRounds = 0
        translatedTotal = 0
        translatePolling?.cancel()
        translatePolling = null
        updateTranslateFab()
        if (state.fab == 3) {
            updateAutoTranslateStatus()
            if (!state.paused && wv != null) startTranslatePolling(wv)
        } else {
            hideTranslateStatus()
        }
    }

    private fun restoreTranslateUi() {
        if (!isAdded || view == null) return
        if (!::tabManager.isInitialized) return
        if (AppServices.settings.translateMode() == 0) return
        val wv = tabManager.currentWebView ?: return
        translateTabStates[wv]?.let {
            translateFabState = it.fab
            translateShowOriginal = it.showOriginal
            autoTranslatePaused = it.paused
        }
        if (translateFabState != 3) {
            updateTranslateFab()
            hideTranslateStatus()
            return
        }
        updateTranslateFab()
        updateAutoTranslateStatus()
        if (!autoTranslatePaused && translatePolling?.isActive != true) {
            startTranslatePolling(wv)
        }
    }

    private fun updateTranslateFab() {
        val fab = translateToggle ?: return
        if (AppServices.settings.translateMode() == 0) {
            cancelTranslateFabAutoHide()
            fab.visibility = View.GONE
            return
        }
        when (translateFabState) {
            0 -> {
                cancelTranslateFabAutoHide()
                fab.visibility = View.GONE
            }
            1 -> {
                fab.visibility = View.VISIBLE
                fab.alpha = 1f
                fab.text = getString(R.string.translate_fab_start)
                scheduleTranslateFabAutoHide()
            }
            2 -> {
                cancelTranslateFabAutoHide()
                fab.visibility = View.VISIBLE
                fab.alpha = 1f
                fab.text = getString(R.string.translate_fab_loading)
            }
            else -> {
                cancelTranslateFabAutoHide()
                fab.visibility = View.VISIBLE
                fab.alpha = 0.5f
                fab.text = getString(
                    if (translateShowOriginal) R.string.translate_show_translated
                    else R.string.translate_show_original
                )
            }
        }
    }

    /** 翻译按钮长时间不操作就收起，本次页面不再自动出现。 */
    private fun scheduleTranslateFabAutoHide() {
        val fab = translateToggle ?: return
        translateFabAutoHide?.let { fab.removeCallbacks(it) }
        val runnable = Runnable {
            if (translateFabState != 1) return@Runnable
            translateFabState = 0
            translateFabMutedUrl = tabManager.current?.url
            updateTranslateFab()
            saveTranslateTabState(tabManager.currentWebView)
        }
        translateFabAutoHide = runnable
        fab.postDelayed(runnable, TRANSLATE_FAB_IDLE_MS)
    }

    private fun cancelTranslateFabAutoHide() {
        val fab = translateToggle
        translateFabAutoHide?.let { fab?.removeCallbacks(it) }
        translateFabAutoHide = null
    }

    /** 按设置摆放翻译按钮：左下/右下/底部居中/左上/右上。 */
    private fun applyTranslateFabPosition() {
        val fab = translateToggle ?: return
        val lp = fab.layoutParams as? FrameLayout.LayoutParams ?: return
        // 新版界面底栏悬浮在内容之上，底部位置的按钮要抬到底栏上方。
        val bottomLift = if (AppServices.settings.uiStyle() == 1) {
            val dockHeight = view?.findViewById<View>(R.id.modern_chrome)?.height
                ?.takeIf { it > 0 } ?: dp(56)
            dockHeight + dp(14)
        } else dp(72)
        when (AppServices.settings.translateFabPosition().coerceIn(0, 4)) {
            0 -> {
                lp.gravity = android.view.Gravity.BOTTOM or android.view.Gravity.START
                lp.marginStart = dp(16); lp.marginEnd = 0
                lp.topMargin = 0; lp.bottomMargin = bottomLift
            }
            1 -> {
                lp.gravity = android.view.Gravity.BOTTOM or android.view.Gravity.END
                lp.marginStart = 0; lp.marginEnd = dp(16)
                lp.topMargin = 0; lp.bottomMargin = bottomLift
            }
            2 -> {
                lp.gravity = android.view.Gravity.BOTTOM or android.view.Gravity.CENTER_HORIZONTAL
                lp.marginStart = 0; lp.marginEnd = 0
                lp.topMargin = 0; lp.bottomMargin = bottomLift
            }
            3 -> {
                lp.gravity = android.view.Gravity.TOP or android.view.Gravity.START
                lp.marginStart = dp(16); lp.marginEnd = 0
                lp.topMargin = dp(74); lp.bottomMargin = 0
            }
            else -> {
                lp.gravity = android.view.Gravity.TOP or android.view.Gravity.END
                lp.marginStart = 0; lp.marginEnd = dp(16)
                lp.topMargin = dp(74); lp.bottomMargin = 0
            }
        }
        fab.layoutParams = lp
    }

    /** 新版底栏悬浮在内容之上，页面上的浮动条统一抬到底栏上方。 */
    private fun applyFloatingBarLift() {
        val root = view ?: return
        val modern = AppServices.settings.uiStyle() == 1
        val lift = if (modern) {
            val dockHeight = root.findViewById<View>(R.id.modern_chrome)?.height
                ?.takeIf { it > 0 } ?: dp(56)
            dockHeight + dp(14)
        } else 0
        fun setBottom(id: Int, classic: Int) {
            val target = root.findViewById<View>(id) ?: return
            val lp = target.layoutParams as? FrameLayout.LayoutParams ?: return
            val value = if (modern) lift else dp(classic)
            if (lp.bottomMargin == value) return
            lp.bottomMargin = value
            target.layoutParams = lp
        }
        setBottom(R.id.find_bar, 8)
        setBottom(R.id.tts_bar, 8)
        setBottom(R.id.download_bar, 20)
        setBottom(R.id.sniff_prompt, 10)
        setBottom(R.id.translate_status, 72)
        applyFloatingBarStyle(modern)
        applyTranslateFabPosition()
    }

    /** 新版浮动条跟随日夜：白天浅色药丸，夜间深色药丸。 */
    private fun applyFloatingBarStyle(modern: Boolean) {
        val root = view ?: return
        val context = requireContext()
        val dark = modern && AppServices.settings.isNightMode()
        val barBg = if (dark) R.drawable.bg_float_dark else R.drawable.bg_find_bar
        val textColor = if (dark) android.graphics.Color.WHITE
        else context.getColor(R.color.text_primary)
        val secondary = if (dark) 0x99FFFFFF.toInt()
        else context.getColor(R.color.text_secondary)
        root.findViewById<View>(R.id.find_bar)?.setBackgroundResource(barBg)
        root.findViewById<View>(R.id.tts_bar)?.setBackgroundResource(barBg)
        root.findViewById<View>(R.id.download_bar)?.setBackgroundResource(barBg)
        root.findViewById<View>(R.id.sniff_prompt)?.setBackgroundResource(barBg)
        root.findViewById<View>(R.id.translate_status)?.setBackgroundResource(barBg)
        root.findViewById<View>(R.id.translate_toggle)?.setBackgroundResource(
            if (dark) R.drawable.bg_translate_fab_dark else R.drawable.bg_translate_fab
        )
        root.findViewById<android.widget.EditText>(R.id.find_input)?.apply {
            setTextColor(textColor)
            setHintTextColor(secondary)
        }
        listOf(
            R.id.find_count, R.id.find_prev, R.id.find_next, R.id.find_close,
            R.id.tts_status, R.id.tts_stop, R.id.download_bar_text,
            R.id.sniff_prompt_title, R.id.sniff_prompt_download, R.id.translate_status
        ).forEach { id ->
            root.findViewById<TextView>(id)?.setTextColor(textColor)
        }
        root.findViewById<ImageView>(R.id.sniff_prompt_close)?.imageTintList =
            android.content.res.ColorStateList.valueOf(secondary)
    }

    /** Kotlin 侧定时调用页面扫描函数，新增内容合并后补翻。 */
    private fun startTranslatePolling(wv: SiiWebView) {
        translatePolling?.cancel()
        translatePolling = viewLifecycleOwner.lifecycleScope.launch {
            val interval = AppServices.settings.translateIntervalMs().coerceIn(100, 10000).toLong()
            val scanLimit = AppServices.settings.translateScan().coerceIn(1, 500)
            val configuredTotal = AppServices.settings.translateLimit()
            val totalLimit = if (configuredTotal <= 0) Int.MAX_VALUE else configuredTotal
            val pollTarget = activeTranslateTarget.ifBlank {
                com.sii.browser.core.webview.Translator.targetLang(requireContext())
            }
            // 连续空扫后拉长间隔而不是退出：页面后续展开的菜单/折叠区也要能翻到。
            var idle = false
            var doneShown = false
            var tick = 0
            var forceScan = true
            while (true) {
                kotlinx.coroutines.delay(if (idle) interval * 4 else interval)
                if (autoTranslatePaused) continue
                tick++
                // 上一轮还有新文本时强制续扫；另外每 6 轮全量扫一次兜底。
                val force = forceScan || tick % 6 == 0
                val raw = kotlinx.coroutines.suspendCancellableCoroutine<String?> { cont ->
                    wv.evaluateJavascript(
                        "(function(){try{return window.__siiScan?window.__siiScan($scanLimit,$force):'[]';}" +
                            "catch(e){return '[]';}})()"
                    ) { cont.resume(it) }
                }
                val texts = parseJsonArray(raw) ?: continue
                forceScan = texts.length() > 0
                if (texts.length() == 0) {
                    autoEmptyRounds++
                    if (autoEmptyRounds >= 3) {
                        // 完成提示只弹一次；待命中不再反复弹出。
                        if (!doneShown) {
                            doneShown = true
                            com.sii.browser.core.webview.TranslationTrace.log(
                                requireContext(), "poll idle after empty rounds"
                            )
                            showTranslateStatus(getString(R.string.translate_done), autoHide = true)
                        }
                        idle = true
                        continue
                    }
                    continue
                }
                autoEmptyRounds = 0
                doneShown = false
                val scanInfo = kotlinx.coroutines.suspendCancellableCoroutine<String?> { cont ->
                    wv.evaluateJavascript(
                        "(function(){try{return JSON.stringify(window.__siiLastScanInfo||{});}catch(e){return '{}';}})()"
                    ) { cont.resume(it) }
                }
                com.sii.browser.core.webview.TranslationTrace.log(
                    requireContext(), "poll new texts=${texts.length()} idle=$idle force=$force info=$scanInfo"
                )
                if (idle) {
                    idle = false
                    showTranslateStatus(getString(R.string.translate_in_progress), initialAlpha = 1f)
                }
                val (results, _) = translateBatch(texts, pollTarget, activeTranslateSource)
                if (!isAdded) return@launch
                wv.evaluateJavascript(
                    "(function(){try{window.__siiApplyPending($results);}catch(e){}})()",
                    null
                )
                translatedTotal += texts.length()
                if (translatedTotal >= totalLimit) {
                    showTranslateStatus(getString(R.string.translate_limit_reached), autoHide = true)
                    return@launch
                }
            }
        }
    }

    private fun copyCurrentUrl() {
        val url = tabManager.current?.url ?: return
        val clipboard = requireContext().getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText(null, url))
        Toast.makeText(requireContext(), R.string.link_copied, Toast.LENGTH_SHORT).show()
    }

    /** 关闭当前标签（最后一个标签时新建空白页，避免无标签可显示）。 */
    /** 工具栏长按动作：每个按钮可在操作设定里单独绑定。 */
    private fun setupToolbarHoldActions() {
        val keys = mapOf(
            R.id.refresh_button to "refresh",
            R.id.home_button to "home",
            R.id.tab_count_button to "tab",
            R.id.menu_button to "menu",
            R.id.back_button to "back"
        )
        keys.forEach { (id, key) ->
            view?.findViewById<View>(id)?.setOnLongClickListener {
                val action = AppServices.settings.keyAction("toolbar_hold_$key")
                performToolbarAction(action)
                action != 0
            }
        }
    }

    /** 工具栏水平滑动：左滑/右滑可分别绑定动作，不拦截按钮点击。 */
    private fun setupToolbarSwipeActions() {
        val bar = view?.findViewById<SwipeBarLayout>(R.id.bottom_bar) ?: return
        bar.onSwipeLeft = {
            performToolbarAction(AppServices.settings.keyAction("toolbar_swipe_left"))
        }
        bar.onSwipeRight = {
            performToolbarAction(AppServices.settings.keyAction("toolbar_swipe_right"))
        }
    }

    private fun performToolbarAction(action: Int) {
        when (action) {
            1 -> toggleRefresh()
            2 -> loadUrlFor(AppServices.settings.homeUrl())
            3 -> openNewTab()
            4 -> showMenuSheet()
            5 -> tabManager.currentWebView?.goBack()
            6 -> tabManager.currentWebView?.goForward()
        }
    }

    private fun closeCurrentTab() {
        val index = tabManager.currentIndex
        if (index !in 0 until tabManager.size) return
        closeTabAndHint(index)
        if (tabManager.size == 0) {
            openNewTab(AppServices.settings.homeUrl())
        }
        renderTabs()
    }

    /** 所有用户主动关闭标签的入口共用提示。 */
    private fun closeTabAndHint(index: Int) {
        if (index !in 0 until tabManager.size) return
        val closingTab = tabManager.tabs[index]
        val closingUrl = closingTab.url
        val closingThumbUrl = closingTab.thumbnailUrl
        val wasCurrent = index == tabManager.currentIndex
        if (wasCurrent) {
            saveTranslateTabState(tabManager.currentWebView)
            translateJob?.cancel()
            translateJob = null
            translatePolling?.cancel()
            translatePolling = null
        }
        tabManager.closeTab(index)
        deleteThumbnail(closingUrl)
        if (!closingThumbUrl.isNullOrBlank() && closingThumbUrl != closingUrl) {
            deleteThumbnail(closingThumbUrl)
        }
        showCloseTabHint()
        if (wasCurrent) applyTranslateTabState(tabManager.currentWebView)
    }

    /** 关闭标签后删掉对应的磁盘缩略图，避免缓存文件越积越多。 */
    private fun deleteThumbnail(url: String) {
        if (url.isBlank()) return
        val context = requireContext().applicationContext
        viewLifecycleOwner.lifecycleScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            com.sii.browser.data.TabThumbStore.delete(context, url)
        }
    }

    private fun showCloseTabHint() {
        if (!AppServices.settings.closeTabToast()) return
        Toast.makeText(
            requireContext(),
            R.string.closetab_toast_text,
            Toast.LENGTH_SHORT
        ).show()
    }

    /** 菜单「清除数据」：确认后清除缓存、Cookie 与网页存储（保留书签/历史）。 */
    private fun clearMenuData() {
        android.app.AlertDialog.Builder(requireContext())
            .setTitle(R.string.menu_clear_data)
            .setMessage(R.string.clear_menu_data_message)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                val pm = AppServices.privacyManager
                pm.clearCache()
                pm.clearCookies()
                pm.clearWebStorage()
                Toast.makeText(requireContext(), R.string.data_cleared, Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun openNewTab(url: String? = null) {
        val target = url ?: restoredUrl(null)
        tabManager.newTab(target)
        applySiteThemeColor(currentThemeColor())
        applyTranslateFabPosition()
        if (target == ABOUT_HOME) {
            tabManager.currentWebView?.loadUrl("about:blank")
            showHome()
        } else {
            showWeb()
            loadUrlFor(target)
        }
        updateAddressBarForCurrent()
        applyTranslateTabState(tabManager.currentWebView)
    }

    /** 统一加载入口：about:home 显示主页，其余走 WebView（无当前标签时先创建）。 */
    private fun loadUrlFor(rawUrl: String) {
        homePanel?.dismissSuggestions()
        blurAddressInput()
        // 任何主动导航都收起搜索建议，防止结果页上残留。
        hideSuggestions()
        // 用户主动导航：允许重新尝试手机站→电脑版映射。
        aliasMappedTargets.clear()
        aliasRedirectPending = null
        if (rawUrl.startsWith("http", ignoreCase = true) && isUserScriptUrl(rawUrl)) {
            installUserScript(rawUrl)
            return
        }
        if (rawUrl == ABOUT_HOME) {
            val webView = tabManager.currentWebView
            val hasWebPage = webView?.url?.startsWith("http") == true ||
                tabManager.current?.url?.startsWith("http") == true
            if (hasWebPage) {
                // 保留当前网页，只覆盖显示主页，返回时无需重新加载。
                homeOverlayActive = true
                homeForwardAvailable = false
            } else {
                homeOverlayActive = false
                homeForwardAvailable = false
                tabManager.current?.let { tab ->
                    if (tab.url != ABOUT_HOME) {
                        tab.url = ABOUT_HOME
                        tab.webView.loadUrl("about:blank")
                    }
                }
            }
            showHome()
            return
        }
        if (rawUrl == ABOUT_BOOKMARKS) {
            openBookmarks()
            return
        }
        if (rawUrl == ABOUT_LINKS) {
            openLinksPage()
            return
        }
        if (rawUrl == ABOUT_HISTORY) {
            openHistory()
            return
        }
        if (rawUrl == ABOUT_DOWNLOADS) {
            openDownloads()
            return
        }
        if (rawUrl == ABOUT_ABOUT) {
            openAboutPage()
            return
        }
        if (rawUrl == ABOUT_LOG) {
            openLogPage()
            return
        }
        if (rawUrl == ABOUT_CATALOG) {
            openCatalogPage()
            return
        }
        if (rawUrl == ABOUT_SAVE) {
            showWeb()
            savePageOffline()
            return
        }
        if (rawUrl == ABOUT_BLANK) {
            if (tabManager.current == null) tabManager.newTab(ABOUT_BLANK)
            showWeb()
            setAddressText(ABOUT_BLANK)
            tabManager.currentWebView?.loadDataWithBaseURL(
                null, listPageHtml(getString(R.string.about_blank), emptyList()), "text/html", "utf-8", null
            )
            return
        }
        if (rawUrl == ABOUT_RES) {
            openLogPage()
            return
        }
        if (rawUrl == ABOUT_IMAGES) {
            openImagesPage()
            return
        }
        val url = if (httpsOnlyEnabled()) upgradeToHttps(cleanUrl(rawUrl)) else cleanUrl(rawUrl)
        homeOverlayActive = false
        homeForwardAvailable = false
        if (tabManager.current == null) {
            tabManager.newTab(url)
        }
        // 提前落目标地址：从主页导航时，WebView 会先回调 about:blank 完成，
        // 若 tab.url 仍是 about:home 会被误判为“回到主页”。
        tabManager.current?.url = url
        showWeb()
        showPageLoadingCover()
        val wv = tabManager.currentWebView
        if (wv != null) {
            // 电脑模式下手机专用站换成电脑版域名（如 m.weibo.cn → weibo.com）。
            val target = if (isDesktopForUrl(url)) desktopAliasUrl(url, null) ?: url else url
            applySiteSettings(wv, target)
            // 主动导航前同步落 UA（不在导航回调栈内），保证首个请求就带正确 UA。
            val host = runCatching { android.net.Uri.parse(target).host }.getOrNull()
            val ua = targetUserAgent(host)
            if (wv.settings.userAgentString != ua) wv.settings.userAgentString = ua
            tabManager.current?.url = target
            val privacyHeaders = privacyRequestHeaders(target)
            if (privacyHeaders.isEmpty()) {
                wv.post { wv.loadUrl(target) }
            } else {
                programmaticPrivacyLoadAt = android.os.SystemClock.elapsedRealtime()
                wv.post { wv.loadUrl(target, privacyHeaders) }
            }
        }
        // 主动导航立即回显目标地址；重定向后由 doUpdateVisitedHistory 替换。
        setAddressText(addressDisplayUrl(tabManager.current?.url ?: url))
        // DNS/连接阶段的慢站没有 onPageStarted 回调，主动导航时就立即显示 0% 进度条。
        if (homeContainer?.visibility != View.VISIBLE) {
            progressBar?.let { bar ->
                bar.animate().cancel()
                progressAnimator?.cancel()
                bar.alpha = 1f
                bar.progress = 0
                bar.visibility = View.VISIBLE
            }
            setRefreshLoading(true)
        }
        tabManager.currentWebView?.let { pageLoadFinished[it] = false }
    }

    /** 自定义字体：把私有目录字体转 data URI 注入，失败静默回退系统字体。 */
    private fun injectCustomFont(webView: SiiWebView?) {
        val path = AppServices.settings.customFont() ?: return
        val file = java.io.File(path)
        if (!file.exists() || file.length() <= 0L) return
        val mtime = file.lastModified()
        val cached = fontCacheBase64
        if (path == fontCachePath && mtime == fontCacheMtime && cached != null) {
            applyFontCss(webView, cached)
            return
        }
        viewLifecycleOwner.lifecycleScope.launch {
            val base64 = withContext(Dispatchers.IO) {
                runCatching {
                    android.util.Base64.encodeToString(
                        file.readBytes(), android.util.Base64.NO_WRAP
                    )
                }.getOrNull()
            } ?: return@launch
            fontCachePath = path
            fontCacheMtime = mtime
            fontCacheBase64 = base64
            applyFontCss(webView, base64)
        }
    }

    private fun applyFontCss(webView: SiiWebView?, base64: String) {
        val css = "@font-face{font-family:'SiiCustomFont';" +
            "src:url(data:font/ttf;base64,$base64) format('truetype');}" +
            "body,body *{font-family:'SiiCustomFont',sans-serif !important}"
        webView?.evaluateJavascript(
            "(function(){var s=document.getElementById('sii_custom_font');" +
                "if(!s){s=document.createElement('style');s.id='sii_custom_font';" +
                "document.documentElement.appendChild(s);}s.textContent=" +
                org.json.JSONObject.quote(css) + ";})();",
            null
        )
    }

    private fun cleanUrl(url: String): String =
        if (AppServices.settings.privacyCleanUrls()) PrivacyUrlCleaner.clean(url) else url

    private fun httpsOnlyEnabled(): Boolean =
        AppServices.settings.httpsOnly() || AppServices.settings.privacyHttpsOnly()

    /** 主文档隐私信号：DNT、GPC、Save-Data。 */
    private fun privacyRequestHeaders(url: String?): Map<String, String> {
        val host = url?.let {
            runCatching { android.net.Uri.parse(it).host }.getOrNull()
        }
        fun enabled(key: String, global: Boolean): Boolean =
            when (SiteConfig.tri(siteConfigFor(host), key)) {
                1 -> true
                2 -> false
                else -> global
            }
        val headers = mutableMapOf<String, String>()
        if (enabled("dnt", AppServices.settings.dntEnabled())) headers["DNT"] = "1"
        if (enabled("gpc", AppServices.settings.privacyGpc())) headers["Sec-GPC"] = "1"
        if (enabled("savedata", AppServices.settings.saveDataEnabled())) {
            headers["Save-Data"] = "on"
        }
        return headers
    }

    /** 内建 about:bookmarks 页：WebView 渲染，页面由 Sii 自写。 */
    private fun openBookmarksPage() {
        if (tabManager.current == null) tabManager.newTab(ABOUT_BOOKMARKS)
        showWeb()
        setAddressText(ABOUT_BOOKMARKS)
        viewLifecycleOwner.lifecycleScope.launch {
            val items = AppServices.bookmarkRepository.itemsInFolder(null).first()
            tabManager.currentWebView?.loadDataWithBaseURL(
                null, bookmarksHtml(items), "text/html", "utf-8", null
            )
        }
    }

    private fun bookmarksHtml(items: List<com.sii.browser.data.db.BookmarkItem>): String {
        fun esc(s: String) = s.replace("&", "&amp;").replace("<", "&lt;").replace("\"", "&quot;")
        val rows = if (items.isEmpty()) {
            "<p class=\"empty\">还没有书签</p>"
        } else {
            items.joinToString("") { item ->
                "<a class=\"item\" href=\"${esc(item.url)}\">" +
                    "<b>${esc(item.title.ifEmpty { item.url })}</b>" +
                    "<span>${esc(item.url)}</span></a>"
            }
        }
        return "<!DOCTYPE html><html lang=\"zh-CN\"><head><meta charset=\"utf-8\">" +
            "<meta name=\"viewport\" content=\"width=device-width,initial-scale=1,viewport-fit=cover\">" +
            "<title>书签</title><style>" +
            "body{margin:0;background:#f6f7f9;font-family:-apple-system,system-ui,sans-serif;padding:12px}" +
            "h2{font-size:16px;margin:8px 4px 14px}" +
            ".item{display:block;background:#fff;border:1px solid #ececf0;border-radius:14px;" +
            "padding:12px 14px;margin-bottom:8px;text-decoration:none;color:#1a1a1e}" +
            ".item b{display:block;font-size:14px;font-weight:500}" +
            ".item span{display:block;font-size:11px;color:#8a8f99;margin-top:2px;" +
            "overflow:hidden;text-overflow:ellipsis;white-space:nowrap}" +
            ".empty{color:#8a8f99;font-size:13px;text-align:center;padding:24px}" +
            "</style></head><body><h2>书签</h2>$rows</body></html>"
    }

    /** 内建 about:links 页（常用网站列表，WebView 渲染自写 HTML）。 */
    private fun openLinksPage() {
        if (tabManager.current == null) tabManager.newTab(ABOUT_LINKS)
        showWeb()
        setAddressText(ABOUT_LINKS)
        viewLifecycleOwner.lifecycleScope.launch {
            val items = AppServices.favoriteRepository.all().first()
            tabManager.currentWebView?.loadDataWithBaseURL(
                null, linksHtml(items), "text/html", "utf-8", null
            )
        }
    }

    private fun linksHtml(items: List<com.sii.browser.data.db.FavoriteItem>): String {
        fun esc(s: String) = s.replace("&", "&amp;").replace("<", "&lt;").replace("\"", "&quot;")
        val rows = if (items.isEmpty()) {
            "<p class=\"empty\">还没有常用网站</p>"
        } else {
            items.joinToString("") { item ->
                "<a class=\"item\" href=\"${esc(item.url)}\">" +
                    "<b>${esc(item.title.ifEmpty { item.url })}</b>" +
                    "<span>${esc(item.url)}</span></a>"
            }
        }
        return "<!DOCTYPE html><html lang=\"zh-CN\"><head><meta charset=\"utf-8\">" +
            "<meta name=\"viewport\" content=\"width=device-width,initial-scale=1,viewport-fit=cover\">" +
            "<title>常用网站</title><style>" +
            "body{margin:0;background:#f6f7f9;font-family:-apple-system,system-ui,sans-serif;padding:12px}" +
            "h2{font-size:16px;margin:8px 4px 14px}" +
            ".item{display:block;background:#fff;border:1px solid #ececf0;border-radius:14px;" +
            "padding:12px 14px;margin-bottom:8px;text-decoration:none;color:#1a1a1e}" +
            ".item b{display:block;font-size:14px;font-weight:500}" +
            ".item span{display:block;font-size:11px;color:#8a8f99;margin-top:2px;" +
            "overflow:hidden;text-overflow:ellipsis;white-space:nowrap}" +
            ".empty{color:#8a8f99;font-size:13px;text-align:center;padding:24px}" +
            "</style></head><body><h2>常用网站</h2>$rows</body></html>"
    }

    /** 内建 about:history / about:downloads / about:about（WebView 渲染自写 HTML）。 */
    private fun openListPage(type: String) {
        if (tabManager.current == null) tabManager.newTab(type)
        showWeb()
        setAddressText(type)
        viewLifecycleOwner.lifecycleScope.launch {
            val rows = when (type) {
                ABOUT_HISTORY -> AppServices.historyRepository.recent(100).first().map {
                    Triple(it.url, it.title.ifEmpty { it.url }, it.url)
                }
                ABOUT_DOWNLOADS -> AppServices.downloadRepository.allTasks().first().map {
                    Triple(it.url, it.name, it.fileUri ?: it.path.orEmpty())
                }
                else -> emptyList()
            }
            val title = when (type) {
                ABOUT_HISTORY -> getString(R.string.about_history)
                ABOUT_DOWNLOADS -> getString(R.string.about_downloads)
                else -> type
            }
            tabManager.currentWebView?.loadDataWithBaseURL(
                null, listPageHtml(title, rows), "text/html", "utf-8", null
            )
        }
    }

    private fun openHistoryPage() = openListPage(ABOUT_HISTORY)

    private fun openDownloadsPage() = openListPage(ABOUT_DOWNLOADS)

    /** about 页：显示应用与版本信息。 */
    private fun openAboutPage() {
        if (tabManager.current == null) tabManager.newTab(ABOUT_ABOUT)
        showWeb()
        setAddressText(ABOUT_ABOUT)
        val version = runCatching {
            requireContext().packageManager.getPackageInfo(requireContext().packageName, 0).versionName
        }.getOrNull().orEmpty()
        val rows = listOf(
            Triple("Sii", "版本 $version", ""),
            Triple(getString(R.string.about_blocked), "${AppServices.adBlock.blockedCount}", ""),
            Triple(getString(R.string.rules_custom), "${AppServices.adBlock.customRuleCount}", ""),
            Triple(getString(R.string.rules_subscriptions), "${AppServices.adBlock.subscriptionRuleCount}", "")
        )
        tabManager.currentWebView?.loadDataWithBaseURL(
            null, listPageHtml(getString(R.string.about_sii), rows), "text/html", "utf-8", null
        )
    }

    private fun listPageHtml(title: String, rows: List<Triple<String, String, String>>): String {
        fun esc(s: String) = s.replace("&", "&amp;").replace("<", "&lt;").replace("\"", "&quot;")
        val body = if (rows.isEmpty()) {
            "<p class=\"empty\">暂无内容</p>"
        } else {
            rows.joinToString("") { (primary, secondary, link) ->
                if (link.startsWith("http")) {
                    "<a class=\"item\" href=\"${esc(link)}\"><b>${esc(primary)}</b>" +
                        "<span>${esc(secondary)}</span></a>"
                } else {
                    "<div class=\"item\"><b>${esc(primary)}</b><span>${esc(secondary)}</span></div>"
                }
            }
        }
        return "<!DOCTYPE html><html lang=\"zh-CN\"><head><meta charset=\"utf-8\">" +
            "<meta name=\"viewport\" content=\"width=device-width,initial-scale=1,viewport-fit=cover\">" +
            "<title>${esc(title)}</title><style>" +
            "body{margin:0;background:#f6f7f9;font-family:-apple-system,system-ui,sans-serif;padding:12px}" +
            "h2{font-size:16px;margin:8px 4px 14px}" +
            ".item{display:block;background:#fff;border:1px solid #ececf0;border-radius:14px;" +
            "padding:12px 14px;margin-bottom:8px;text-decoration:none;color:#1a1a1e}" +
            ".item b{display:block;font-size:14px;font-weight:500}" +
            ".item span{display:block;font-size:11px;color:#8a8f99;margin-top:2px;" +
            "overflow:hidden;text-overflow:ellipsis;white-space:nowrap}" +
            ".empty{color:#8a8f99;font-size:13px;text-align:center;padding:24px}" +
            "</style></head><body><h2>${esc(title)}</h2>$body</body></html>"
    }

    /** about:log：拦截统计（Sii 只统计总量）。 */
    private fun openLogPage() {
        if (tabManager.current == null) tabManager.newTab(ABOUT_LOG)
        showWeb()
        setAddressText(ABOUT_LOG)
        val rows = listOf(
            Triple(getString(R.string.about_blocked), "${AppServices.adBlock.blockedCount}", ""),
            Triple(getString(R.string.rules_custom), "${AppServices.adBlock.customRuleCount}", ""),
            Triple(getString(R.string.rules_subscriptions), "${AppServices.adBlock.subscriptionRuleCount}", "")
        )
        tabManager.currentWebView?.loadDataWithBaseURL(
            null, listPageHtml(getString(R.string.about_log), rows), "text/html", "utf-8", null
        )
    }

    /** about:catalog：书签目录（树形展开为列表）。 */
    private fun openCatalogPage() {
        if (tabManager.current == null) tabManager.newTab(ABOUT_CATALOG)
        showWeb()
        setAddressText(ABOUT_CATALOG)
        viewLifecycleOwner.lifecycleScope.launch {
            val rows = mutableListOf<Triple<String, String, String>>()
            collectBookmarks(null, rows, 0)
            tabManager.currentWebView?.loadDataWithBaseURL(
                null, listPageHtml(getString(R.string.about_catalog), rows), "text/html", "utf-8", null
            )
        }
    }

    private suspend fun collectBookmarks(
        folderId: String?,
        rows: MutableList<Triple<String, String, String>>,
        depth: Int
    ) {
        val prefix = "　".repeat(depth)
        AppServices.bookmarkRepository.childFolders(folderId).first().forEach { folder ->
            rows.add(Triple("$prefix${folder.title}/", "", ""))
            collectBookmarks(folder.id, rows, depth + 1)
        }
        AppServices.bookmarkRepository.itemsInFolder(folderId).first().forEach { item ->
            rows.add(Triple("$prefix${item.title.ifEmpty { item.url }}", item.url, item.url))
        }
    }

    /** about:images：列出当前页面里的图片（注入脚本收集后渲染）。 */
    private fun openImagesPage() {
        val webView = tabManager.currentWebView ?: return
        showWeb()
        setAddressText(ABOUT_IMAGES)
        webView.evaluateJavascript(IMAGES_COLLECT_JS) { result ->
            val rows = runCatching {
                val text = org.json.JSONTokener(result).nextValue().toString()
                val array = org.json.JSONArray(text)
                (0 until array.length()).map { i ->
                    val obj = array.optJSONObject(i) ?: return@map Triple("", "", "")
                    val url = obj.optString("u")
                    Triple(obj.optString("a").ifEmpty { url }, url, url)
                }.filter { it.second.isNotEmpty() }
            }.getOrDefault(emptyList())
            webView.loadDataWithBaseURL(
                null,
                listPageHtml(getString(R.string.about_images), rows),
                "text/html",
                "utf-8",
                null
            )
        }
    }

    private fun upgradeToHttps(url: String): String =
        if (url.startsWith("http://")) "https://" + url.substring(7) else url

    /** 主页背景色应用到工具栏/状态栏/菜单面板；离开主页或使用背景图时恢复。 */
    private fun applyHomeChromeTint() {
        val isHome = homeContainer?.visibility == View.VISIBLE
        val bar = view?.findViewById<View>(R.id.bottom_bar)
        val panel = view?.findViewById<View>(R.id.menu_panel)
        val shell = activity as? ShellActivity
        val s = AppServices.settings
        val color = if (isHome && s.homeBackground() == null) homePanel?.backgroundColor() else null
        if (color != null) {
            if (s.homeBgApplyToolbar() && AppServices.settings.uiStyle() != 1) {
                bar?.setBackgroundColor(color)
            }
            if (s.homeBgApplyMenu() && AppServices.settings.uiStyle() != 1) {
                panel?.backgroundTintList =
                    android.content.res.ColorStateList.valueOf(color)
            } else if (AppServices.settings.uiStyle() == 1) {
                panel?.backgroundTintList = null
            }
            if (s.homeBgApplyStatus()) shell?.applyStatusBarColor(color)
        } else {
            panel?.backgroundTintList = null
            shell?.applyStatusBarColor(null)
        }
    }

    private fun showHome(createTab: Boolean = true) {
        AppServices.lastHomeVisible = true
        hidePageLoadingCover()
        hideSniffPrompt()
        val wasWebVisible = homeContainer?.visibility != View.VISIBLE
        if (tabManager.current == null && createTab) {
            tabManager.newTab(ABOUT_HOME)
        }
        // 回主页时进度条可能还在淡出动画中，直接取消并隐藏，避免叠在主页上方。
        progressAnimator?.cancel()
        progressBar?.let {
            it.visibility = View.GONE
            it.progress = 0
            it.alpha = 1f
        }
        tabManager.currentWebView?.visibility = View.GONE
        ensureHomePanel()
        homePanel?.clearSearch()
        // 设置中改了搜索框位置后，返回主页立即生效。
        homePanel?.applyHomeStyle()
        homeContainer?.let {
            it.visibility = View.VISIBLE
            webViewContainer?.bringChildToFront(it)
        }
        // 从网站退出到主页：主页面板轻淡入（仅一次，页面切换不重复动画）。
        if (wasWebVisible) {
            homeContainer?.apply {
                alpha = 0f
                animate().alpha(1f).setDuration(150).withEndAction { alpha = 1f }.start()
            }
        }
        AppServices.uiActions = this
        updateAddressBarForCurrent()
        updateQuickEngineVisibility()
        updateSiteInfoVisibility()
        applySiteThemeColor(null)
        applyHomeChrome(true)
        // 最后应用主页背景色，避免被主题色/主页框架色覆盖。
        applyHomeChromeTint()
        updateToolbarActiveState()
        updateNavigationState()
        pageFullscreenStates.remove(tabManager.currentWebView)
        applyPageFullscreenChrome()
        if (AppServices.settings.uiStyle() != 1) renderTabs()
        // 同步启动，避免先画出一帧完整内容再重置透明导致的闪一下。
        if (wasWebVisible) homePanel?.playIntro()
    }

    private fun showWeb() {
        AppServices.lastHomeVisible = false
        homePanel?.exitSearchInput()
        homeContainer?.visibility = View.GONE
        attachCurrentWebView()
        tabManager.currentWebView?.visibility = View.VISIBLE
        updateQuickEngineVisibility()
        updateSiteInfoVisibility()
        applyHomeChrome(false)
        applySiteThemeColor(currentThemeColor())
        applyHomeChromeTint()
        updateToolbarActiveState()
        updateAddressBarForCurrent()
        updateNavigationState()
        applyPageFullscreenChrome()
        if (AppServices.settings.uiStyle() != 1) renderTabs()
    }

    /** 主页背景图铺到浏览器外框；网页页恢复纯色工具栏。 */
    private fun applyHomeChrome(enabled: Boolean) {
        val uri = if (enabled) AppServices.settings.homeBackground() else null
        val hasImage = !uri.isNullOrBlank()
        val toolbarColor = ContextCompat.getColor(requireContext(), R.color.toolbar_bg)
        val shell = activity as? ShellActivity
        val overlay = AppServices.settings.homeBgOverlay().coerceIn(0, 100)
        shell?.applyChromeBackground(
            if (hasImage) uri else null,
            if (hasImage) overlay else 0
        )
        val menuPanel = view?.findViewById<View>(R.id.menu_panel)
        menuPanel?.foreground = if (hasImage && overlay > 0) {
            android.graphics.drawable.ColorDrawable(
                android.graphics.Color.argb(overlay * 255 / 100, 0, 0, 0)
            )
        } else null

        val modern = AppServices.settings.uiStyle() == 1
        val barIds = intArrayOf(
            R.id.address_bar, R.id.bottom_bar, R.id.tab_bar, R.id.search_toolbar_scroll
        )
        if (modern) {
            // 新版底栏自己管理颜色：主页也保留地址栏，工具行保持透明。
            view?.findViewById<View>(R.id.address_bar)?.visibility = View.VISIBLE
            view?.findViewById<View>(R.id.tab_bar)?.setBackgroundColor(
                if (hasImage) android.graphics.Color.TRANSPARENT else toolbarColor
            )
            view?.findViewById<View>(R.id.search_toolbar_scroll)
                ?.setBackgroundColor(android.graphics.Color.TRANSPARENT)
            addressInput?.setShadowLayer(0f, 0f, 0f, 0)
        } else {
            view?.findViewById<View>(R.id.address_bar)?.visibility =
                if (enabled) View.GONE else View.VISIBLE
            barIds.forEach { id ->
                view?.findViewById<View>(id)?.setBackgroundColor(
                    if (hasImage) android.graphics.Color.TRANSPARENT else toolbarColor
                )
            }
            if (hasImage) {
                val light = shell?.chromeImageIsLight() ?: !AppServices.settings.isNightMode()
                val foreground = if (light) android.graphics.Color.BLACK else android.graphics.Color.WHITE
                val shadow = if (light) android.graphics.Color.WHITE else android.graphics.Color.BLACK
                addressInput?.setTextColor(foreground)
                addressInput?.setHintTextColor(withAlpha(foreground, 0.65f))
                addressInput?.setShadowLayer(6f, 0f, 0f, shadow)
                barIds.forEach { id ->
                    applyChromeForeground(view?.findViewById<View>(id), foreground, shadow)
                }
            } else {
                addressInput?.setShadowLayer(0f, 0f, 0f, 0)
                barIds.forEach { id -> clearChromeShadow(view?.findViewById<View>(id)) }
            }
        }
        view?.findViewById<View>(R.id.browser_root)?.setBackgroundColor(
            if (hasImage) android.graphics.Color.TRANSPARENT else toolbarColor
        )
    }

    /** 背景图上方的工具栏前景：文字加描边阴影，图标按明暗取反色。 */
    private fun applyChromeForeground(target: View?, color: Int, shadow: Int) {
        when (target) {
            is TextView -> {
                target.setTextColor(color)
                target.setShadowLayer(6f, 0f, 0f, shadow)
            }
            is ImageView -> target.imageTintList =
                android.content.res.ColorStateList.valueOf(color)
            is ViewGroup -> {
                for (i in 0 until target.childCount) {
                    applyChromeForeground(target.getChildAt(i), color, shadow)
                }
            }
        }
    }

    private fun clearChromeShadow(target: View?) {
        when (target) {
            is TextView -> target.setShadowLayer(0f, 0f, 0f, 0)
            is ViewGroup -> {
                for (i in 0 until target.childCount) clearChromeShadow(target.getChildAt(i))
            }
        }
    }

    /** 切换标签并按目标页类型显示主页/网页（修复主页时切标签看不到变化）。 */
    private fun showPageLoadingCover() {
        val cover = pageLoadingCover ?: return
        cover.bringToFront()
        cover.visibility = View.VISIBLE
    }

    private fun hidePageLoadingCover() {
        pageLoadingCover?.visibility = View.GONE
    }

    /** 新嗅探到 HLS/非分片视频时，延迟弹一次“发现视频”浮条。 */
    private fun maybeShowSniffPrompt(
        key: Any,
        res: com.sii.browser.core.webview.SniffedResource
    ) {
        if (!AppServices.settings.sniffEnabled()) return
        if (key !== tabManager.currentWebView) return
        if (homeContainer?.visibility == View.VISIBLE) return
        if (sniffPrompted[key] == true || sniffPromptDismissed[key] == true) return
        if (res.type != com.sii.browser.core.webview.SniffType.HLS &&
            res.type != com.sii.browser.core.webview.SniffType.VIDEO
        ) return
        if (res.type == com.sii.browser.core.webview.SniffType.VIDEO && isSniffSegment(res.url)) return
        sniffPrompted[key] = true
        view?.postDelayed({
            if (!isAdded || key !== tabManager.currentWebView) return@postDelayed
            if (sniffPrompted[key] != true) return@postDelayed
            val list = com.sii.browser.core.webview.SniffStore.list(key)
            val best = list.firstOrNull { it.type == com.sii.browser.core.webview.SniffType.HLS }
                ?: list.firstOrNull {
                    it.type == com.sii.browser.core.webview.SniffType.VIDEO &&
                        !isSniffSegment(it.url)
                }
                ?: return@postDelayed
            showSniffPrompt(key, best)
        }, 1200L)
    }

    private fun isSniffSegment(url: String): Boolean {
        val path = url.substringBefore('?').substringBefore('#').lowercase()
        return path.endsWith(".ts") || path.endsWith(".m4s")
    }

    private fun showSniffPrompt(key: Any, res: com.sii.browser.core.webview.SniffedResource) {
        val prompt = sniffPrompt ?: return
        sniffPromptKey = key
        sniffPromptResource = res
        val label = if (res.type == com.sii.browser.core.webview.SniffType.HLS) {
            getString(R.string.sniff_hls)
        } else {
            getString(R.string.sniff_video)
        }
        val name = runCatching { android.net.Uri.parse(res.url).lastPathSegment }
            .getOrNull().orEmpty().ifEmpty { res.url }
        prompt.findViewById<TextView>(R.id.sniff_prompt_title)?.text =
            getString(R.string.sniff_prompt_found) + " · " + label + " · " + name
        prompt.bringToFront()
        prompt.visibility = View.VISIBLE
    }

    private fun hideSniffPrompt() {
        sniffPrompt?.visibility = View.GONE
        sniffPromptKey = null
        sniffPromptResource = null
    }

    private fun dismissSniffPrompt() {
        sniffPromptKey?.let { sniffPromptDismissed[it] = true }
        hideSniffPrompt()
    }

    private fun playSniffResource() {
        val res = sniffPromptResource ?: return
        val isHls = res.type == com.sii.browser.core.webview.SniffType.HLS
        hideSniffPrompt()
        parentFragmentManager.beginTransaction()
            .replace(
                R.id.shell_root,
                PlayerFragment.newInstance(
                    res.url,
                    isHls,
                    tabManager.current?.url,
                    tabManager.currentWebView?.settings?.userAgentString
                )
            )
            .addToBackStack(null)
            .commit()
    }

    private fun downloadSniffResource() {
        val res = sniffPromptResource ?: return
        val context = requireContext()
        val referer = tabManager.current?.url
        val ua = tabManager.currentWebView?.settings?.userAgentString
        if (res.type == com.sii.browser.core.webview.SniffType.HLS) {
            com.sii.browser.download.DownloadService.startHlsDownload(
                context, res.url, null, referer, ua
            )
        } else {
            com.sii.browser.download.DownloadService.startDownload(
                context, res.url, null, res.mime
            )
        }
        Toast.makeText(context, R.string.sniff_download_started, Toast.LENGTH_SHORT).show()
        hideSniffPrompt()
    }

    /** 标签页背景透明度：0 = 不透明，100 = 全透明。 */
    private fun applyTabsPanelAlpha() {
        val panel = view?.findViewById<View>(R.id.tabs_panel) ?: return
        val transparency = AppServices.settings.tabsPanelTransparency().coerceIn(0, 100)
        val alpha = 255 - transparency * 255 / 100
        val modern = AppServices.settings.uiStyle() == 1
        val dark = modern && AppServices.settings.isNightMode()
        val strength = AppServices.settings.glassStrength()
        val fill = if (modern) GlassStyle.fill(requireContext(), dark, strength, 210)
        else requireContext().getColor(R.color.bg)
        val mergedAlpha = ((((fill ushr 24) and 0xFF) * alpha) / 255).coerceIn(0, 255)
        val radius = dp(28).toFloat()
        panel.background = android.graphics.drawable.GradientDrawable().apply {
            shape = android.graphics.drawable.GradientDrawable.RECTANGLE
            // 新版面板与底栏之间留了距离，四角都做圆角；经典模式仍贴底，只圆上方两角。
            cornerRadii = if (modern) {
                floatArrayOf(radius, radius, radius, radius, radius, radius, radius, radius)
            } else {
                floatArrayOf(radius, radius, radius, radius, 0f, 0f, 0f, 0f)
            }
            setColor((fill and 0x00FFFFFF) or (mergedAlpha shl 24))
            if (modern) setStroke(dp(1), GlassStyle.stroke(dark, strength))
        }
        val actionText = if (dark) android.graphics.Color.WHITE
        else if (modern) 0xFF1A1A1A.toInt()
        else requireContext().getColor(R.color.text_primary)
        listOf(R.id.tabs_new, R.id.tabs_undo).forEach { id ->
            view?.findViewById<TextView>(id)?.apply {
                if (modern) {
                    background = android.graphics.drawable.GradientDrawable().apply {
                        cornerRadius = dp(22).toFloat()
                        setColor(GlassStyle.fill(requireContext(), dark, strength, 200))
                        setStroke(dp(1), GlassStyle.stroke(dark, strength))
                    }
                } else {
                    setBackgroundResource(R.drawable.bg_card)
                }
                setTextColor(actionText)
            }
        }
    }

    private fun switchToTab(index: Int) {
        val previous = tabManager.currentWebView
        cancelProgressFinish()
        // 翻译进行中切走：取消任务，避免返回时停留在“翻译中”。
        if (translateFabState == 2) {
            translateJob?.cancel()
            translateJob = null
            translateFabState = 0
            updateTranslateFab()
            hideTranslateStatus()
        }
        saveTranslateTabState(previous)
        translateJob?.cancel()
        translateJob = null
        if (!tabManager.switchTab(index)) return
        homeOverlayActive = false
        homeForwardAvailable = false
        hideSniffPrompt()
        previous?.onPause()
        if (tabManager.current?.url == ABOUT_HOME) {
            showHome(createTab = false)
        } else {
            showWeb()
        }
        tabManager.currentWebView?.onResume()
        updateAddressBarForCurrent()
        applyTranslateTabState(tabManager.currentWebView)
    }

    /** 主页原生视图懒创建（直接加入 home_container）。 */
    private fun ensureHomePanel() {
        val host = view?.findViewById<FrameLayout>(R.id.home_container) ?: return
        // Fragment 视图销毁重建后旧 panel 的 parent 仍指向已销毁的旧容器（非 null），
        // 只有 parent 是当前视图的 home_container 才能复用。
        if (homePanel != null && homePanel?.view?.parent === host) return
        homePanel = null
        val panel = HomePanel(
            requireContext(),
            onSearch = { navigateFromHome(it) },
            onOpen = { loadUrlFor(it) },
            onScan = { startScan() },
            onSuggest = { query, callback -> loadSuggestions(query, callback) }
        )
        host.addView(
            panel.view,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        )
        panel.bind(latestFavorites)
        homePanel = panel
    }

    // ---- 标签面板（原生底部浮层） ----

    private fun showTabsPanel() {
        blurAddressInput()
        captureCurrentTabThumbnail {
            if (!isAdded) return@captureCurrentTabThumbnail
            renderTabCards()
            updateTabsOverlayBounds()
            tabsOverlay?.visibility = View.VISIBLE
            tabsOverlay?.alpha = 0f
            tabsOverlay?.animate()?.alpha(1f)?.setDuration(160)?.start()
            updateToolbarActiveState()
            tabsPanel()?.post {
                val panel = tabsPanel() ?: return@post
                panel.translationY = panel.height.toFloat()
                panel.animate().translationY(0f).setDuration(200).start()
                scrollTabsToCurrent()
            }
        }
    }

    private fun hideTabsPanel() {
        animateOverlayDismiss(tabsOverlay, tabsPanel())
    }

    private fun hideMenuSheet() {
        animateOverlayDismiss(menuOverlay, view?.findViewById(R.id.menu_panel))
    }

    /** 浮层收起：面板下滑 160ms + 遮罩淡出，结束后再 GONE，与打开动画对称。 */
    private fun animateOverlayDismiss(overlay: View?, panel: View?) {
        if (overlay == null || overlay.visibility != View.VISIBLE) return
        val target = panel ?: return
        target.animate().cancel()
        overlay.animate().cancel()
        val fromTop = overlay === menuOverlay &&
            AppServices.settings.uiStyle() != 1 &&
            AppServices.settings.toolbarPosition() == 1
        target.animate()
            .translationY(if (fromTop) -target.height.toFloat() else target.height.toFloat())
            .setDuration(160)
            .start()
        overlay.animate()
            .alpha(0f)
            .setDuration(160)
            .withEndAction {
                overlay.visibility = View.GONE
                overlay.alpha = 1f
                target.translationY = 0f
                updateToolbarActiveState()
            }
            .start()
    }

    private fun tabsPanel(): View? = view?.findViewById(R.id.tabs_panel)

    /** 底部面板拖拽条：下拉超过 90dp 关闭，否则回弹。 */
    private fun setupSheetDrag(handleId: Int, panelId: Int, onClose: () -> Unit) {
        val handle = view?.findViewById<View>(handleId) ?: return
        val panel = view?.findViewById<View>(panelId) ?: return
        var startY = 0f
        handle.setOnTouchListener { _, event ->
            when (event.actionMasked) {
                android.view.MotionEvent.ACTION_DOWN -> {
                    startY = event.rawY
                    panel.animate().cancel()
                    true
                }
                android.view.MotionEvent.ACTION_MOVE -> {
                    panel.translationY = (event.rawY - startY).coerceAtLeast(0f)
                    true
                }
                android.view.MotionEvent.ACTION_UP,
                android.view.MotionEvent.ACTION_CANCEL -> {
                    val dy = (event.rawY - startY).coerceAtLeast(0f)
                    if (dy > dp(90)) {
                        onClose()
                    } else {
                        panel.animate().translationY(0f).setDuration(140).start()
                    }
                    true
                }
                else -> false
            }
        }
    }

    private fun renderTabCards() {
        val list = tabsList ?: return
        view?.findViewById<TextView>(R.id.tabs_title)?.let { title ->
            title.text = getString(R.string.tabs_title) + " · " + tabManager.size
            title.setTextColor(
                if (AppServices.settings.uiStyle() == 1 && AppServices.settings.isNightMode()) {
                    android.graphics.Color.WHITE
                } else {
                    requireContext().getColor(R.color.text_primary)
                }
            )
        }
        list.removeAllViews()
        if (AppServices.settings.tabCardGrid()) {
            renderGridTabCards(list)
        } else {
            tabManager.tabs.forEachIndexed { index, tab ->
                list.addView(buildTabCard(tab, index))
            }
            list.setOnDragListener { _, event ->
                when (event.action) {
                    android.view.DragEvent.ACTION_DROP -> {
                        val from = event.localState as? Int ?: return@setOnDragListener false
                        val to = tabIndexAt(list, event.y)
                        if (from in 0 until tabManager.size && to in 0 until tabManager.size && from != to) {
                            tabManager.move(from, to)
                            persistTabs()
                            renderTabCards()
                        }
                        true
                    }
                    else -> true
                }
            }
        }
        applyTabsScrollLimit()
    }

    /** 按卡片数量自适应：内容少时只占内容高度，内容多时限高到面板可用区域并可滚动。 */
    private fun applyTabsScrollLimit() {
        val scroll = view?.findViewById<android.widget.ScrollView>(R.id.tabs_scroll) ?: return
        val list = tabsList ?: return
        val overlay = tabsOverlay ?: return
        val actionsHeight = view?.findViewById<View>(R.id.tabs_actions)?.height
            ?.takeIf { it > 0 } ?: dpToPx(62)
        val headerHeight = (view?.findViewById<View>(R.id.tabs_title)?.height
            ?.takeIf { it > 0 } ?: dpToPx(30)) +
            (view?.findViewById<View>(R.id.tabs_handle)?.height
                ?.takeIf { it > 0 } ?: dpToPx(20))
        val margins = (overlay.layoutParams as? android.widget.FrameLayout.LayoutParams)
            ?.let { it.topMargin + it.bottomMargin } ?: 0
        // 面板未显示过时 overlay 高度为 0，用浏览器内容区高度回退。
        val overlayHeight = overlay.height.takeIf { it > 0 }
            ?: ((view?.height ?: resources.displayMetrics.heightPixels) - margins)
        // 新版面板底部要让出悬浮底栏高度，限高时一并扣除，避免多标签时面板顶出屏幕。
        val modernLift = if (AppServices.settings.uiStyle() == 1) {
            (view?.findViewById<View>(R.id.modern_chrome)?.height?.takeIf { it > 0 } ?: dpToPx(56)) +
                dpToPx(14)
        } else 0
        val maxHeight = (overlayHeight - actionsHeight - headerHeight - modernLift)
            .coerceAtLeast(dpToPx(160))
        scroll.post {
            if (!isAdded) return@post
            val width = scroll.width.takeIf { it > 0 }
                ?: view?.width
                ?: resources.displayMetrics.widthPixels
            val contentWidth = (width - scroll.paddingLeft - scroll.paddingRight).coerceAtLeast(1)
            list.measure(
                View.MeasureSpec.makeMeasureSpec(contentWidth, View.MeasureSpec.AT_MOST),
                View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
            )
            val target = (list.measuredHeight + scroll.paddingTop + scroll.paddingBottom)
                .coerceAtMost(maxHeight)
            if (scroll.layoutParams.height != target) {
                scroll.layoutParams.height = target
                scroll.requestLayout()
            }
        }
    }

    /** 新建/恢复标签后把当前行滚进视野。 */
    private fun scrollTabsToCurrent() {
        val scroll = view?.findViewById<android.widget.ScrollView>(R.id.tabs_scroll) ?: return
        val list = tabsList ?: return
        val index = tabManager.currentIndex
        if (index < 0) return
        val row = if (AppServices.settings.tabCardGrid()) {
            index / AppServices.settings.tabGridColumns().coerceIn(2, 3)
        } else index
        scroll.postOnAnimation {
            val child = list.getChildAt(row) ?: return@postOnAnimation
            scroll.smoothScrollTo(0, child.top)
        }
    }

    private fun renderGridTabCards(list: LinearLayout) {
        list.setOnDragListener(null)
        val density = resources.displayMetrics.density
        val columns = AppServices.settings.tabGridColumns().coerceIn(2, 3)
        tabManager.tabs.chunked(columns).forEachIndexed { rowIndex, pair ->
            val row = LinearLayout(requireContext()).apply {
                orientation = LinearLayout.HORIZONTAL
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply { bottomMargin = (10 * density).toInt() }
            }
            pair.forEachIndexed { cell, tab ->
                val index = rowIndex * columns + cell
                val card = buildTabCard(tab, index, grid = true)
                card.layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                    .apply { marginEnd = if (cell < columns - 1) (8 * density).toInt() else 0 }
                row.addView(card)
            }
            repeat(columns - pair.size) {
                row.addView(View(requireContext()).apply {
                    layoutParams = LinearLayout.LayoutParams(0, 1, 1f)
                })
            }
            list.addView(row)
        }
    }

    /** 当前网页缩略图只放内存，供卡片网格使用。 */
    private fun captureCurrentTabThumbnail(onDone: () -> Unit) {
        val tab = tabManager.current ?: run { onDone(); return }
        val webView = tab.webView
        if (webView.width <= 0 || webView.height <= 0) {
            onDone()
            return
        }
        val window = activity?.window
        if (window != null && android.os.Build.VERSION.SDK_INT >= 26) {
            val root = window.decorView
            val webLocation = IntArray(2)
            val rootLocation = IntArray(2)
            webView.getLocationOnScreen(webLocation)
            root.getLocationOnScreen(rootLocation)
            val x = (webLocation[0] - rootLocation[0]).coerceAtLeast(0)
            val y = (webLocation[1] - rootLocation[1]).coerceAtLeast(0)
            val width = webView.width.coerceAtMost(root.width - x)
            val height = webView.height.coerceAtMost(root.height - y)
            if (width > 0 && height > 0) {
                val windowBitmap = android.graphics.Bitmap.createBitmap(
                    width.coerceAtLeast(1),
                    height.coerceAtLeast(1),
                    android.graphics.Bitmap.Config.ARGB_8888
                )
                runCatching {
                    android.view.PixelCopy.request(
                        window,
                        android.graphics.Rect(x, y, x + width, y + height),
                        windowBitmap,
                        { result ->
                            if (result == android.view.PixelCopy.SUCCESS && isAdded) {
                                adoptThumbnail(tab, windowBitmap)
                            } else {
                                windowBitmap.recycle()
                            }
                            onDone()
                        },
                        android.os.Handler(android.os.Looper.getMainLooper())
                    )
                }.onFailure {
                    windowBitmap.recycle()
                    drawCurrentTabThumbnail(tab, webView)
                    onDone()
                }
                return
            }
            drawCurrentTabThumbnail(tab, webView)
            onDone()
            return
        }
        drawCurrentTabThumbnail(tab, webView)
        onDone()
    }

    private fun drawCurrentTabThumbnail(tab: Tab, webView: android.webkit.WebView) {
        val width = webView.width.coerceAtLeast(1)
        val height = webView.height.coerceAtLeast(1)
        val bitmap = android.graphics.Bitmap.createBitmap(
            width, height, android.graphics.Bitmap.Config.ARGB_8888
        )
        webView.draw(android.graphics.Canvas(bitmap))
        adoptThumbnail(tab, bitmap)
    }

    /** 缩略图缩到卡片需要的宽度，同时写入磁盘供进程重启后恢复。 */
    private fun adoptThumbnail(tab: Tab, bitmap: android.graphics.Bitmap) {
        val small = if (bitmap.width > THUMB_MAX_WIDTH) {
            val height = (bitmap.height * THUMB_MAX_WIDTH / bitmap.width).coerceAtLeast(1)
            android.graphics.Bitmap.createScaledBitmap(bitmap, THUMB_MAX_WIDTH, height, true)
        } else bitmap
        if (small !== bitmap) bitmap.recycle()
        tab.thumbnail = small
        if (AppServices.privacyManager.isIncognito) return
        if (view == null) return
        val url = tab.url
        if (url.isBlank()) return
        val previous = tab.thumbnailUrl
        tab.thumbnailUrl = url
        val context = requireContext().applicationContext
        viewLifecycleOwner.lifecycleScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            if (!previous.isNullOrBlank() && previous != url) {
                com.sii.browser.data.TabThumbStore.delete(context, previous)
            }
            com.sii.browser.data.TabThumbStore.save(context, url, small)
        }
    }

    /** 拖动落点对应的标签索引（按卡片上下边界判断）。 */
    private fun tabIndexAt(list: LinearLayout, y: Float): Int {
        for (i in 0 until list.childCount) {
            val child = list.getChildAt(i)
            if (y >= child.top && y <= child.bottom) return i
        }
        return -1
    }

    /** 新版网格卡片的缩略图区：图片铺满，无图时用主色淡底 + 首字母占位。 */
    private fun modernTabThumb(tab: Tab): View {
        val context = requireContext()
        val accent = ThemeAccent.color(context)
        return android.widget.FrameLayout(context).apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(112)
            )
            val thumb = tab.thumbnail
            if (thumb != null) {
                addView(ImageView(context).apply {
                    setImageBitmap(thumb)
                    scaleType = ImageView.ScaleType.CENTER_CROP
                    layoutParams = android.widget.FrameLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT
                    )
                })
            } else {
                addView(TextView(context).apply {
                    text = tabDisplayTitle(tab).take(1).uppercase()
                    setTextSize(30f)
                    setTextColor(accent)
                    gravity = android.view.Gravity.CENTER
                    setBackgroundColor((accent and 0x00FFFFFF) or 0x1F000000)
                    layoutParams = android.widget.FrameLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT
                    )
                })
            }
        }
    }

    /** 标签卡片。 */
    private fun buildTabCard(tab: Tab, index: Int, grid: Boolean = false): View {
        val context = requireContext()
        val active = index == tabManager.currentIndex
        val modern = AppServices.settings.uiStyle() == 1
        val dark = modern && AppServices.settings.isNightMode()
        val title = TextView(context).apply {
            text = tabDisplayTitle(tab)
            setTextSize(13f)
            setTextColor(
                if (active) ThemeAccent.color(context)
                else if (dark) android.graphics.Color.WHITE
                else context.getColor(R.color.text_primary)
            )
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        val head = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
            setPadding(dp(12), dp(10), dp(6), dp(10))
            addView(TextView(context).apply {
                text = (tab.title.ifEmpty { tab.url.ifEmpty { "?" } }).take(1).uppercase()
                setTextSize(10f)
                setTextColor(0xFFFFFFFF.toInt())
                gravity = android.view.Gravity.CENTER
                setBackgroundResource(R.drawable.bg_favicon)
                layoutParams = LinearLayout.LayoutParams(dp(18), dp(18)).apply { marginEnd = dp(8) }
            })
            addView(title)
            addView(TextView(context).apply {
                text = "≡"
                setTextSize(15f)
                setTextColor(
                    if (dark) 0x99FFFFFF.toInt()
                    else context.getColor(R.color.text_secondary)
                )
                gravity = android.view.Gravity.CENTER
                setPadding(dp(6), 0, dp(6), 0)
                setOnTouchListener { v, event ->
                    if (event.actionMasked == android.view.MotionEvent.ACTION_DOWN) {
                        val data = android.content.ClipData.newPlainText("tab", index.toString())
                        v.startDragAndDrop(data, android.view.View.DragShadowBuilder(v), index, 0)
                        true
                    } else false
                }
            })
            addView(ImageView(context).apply {
                setImageResource(R.drawable.ic_close)
                imageTintList = if (dark) {
                    android.content.res.ColorStateList.valueOf(android.graphics.Color.WHITE)
                } else null
                layoutParams = LinearLayout.LayoutParams(
                    dp(28),
                    dp(28)
                )
                setOnClickListener {
                    closeTabAndHint(index)
                    if (tabManager.size == 0) hideTabsPanel() else renderTabCards()
                }
            })
        }
        return LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            if (modern) {
                background = android.graphics.drawable.GradientDrawable().apply {
                    cornerRadius = dp(18).toFloat()
                    setColor(if (dark) 0xFF262A31.toInt() else 0xFFFFFFFF.toInt())
                }
            } else {
                setBackgroundResource(
                    if (active) R.drawable.bg_tab_card_active else R.drawable.bg_tab_card
                )
            }
            clipToOutline = true
            // 边框画在前景，避免缩略图覆盖左侧/底部描边。
            foreground = android.graphics.drawable.GradientDrawable().apply {
                shape = android.graphics.drawable.GradientDrawable.RECTANGLE
                cornerRadius = dp(if (modern) 18 else 16).toFloat()
                setColor(android.graphics.Color.TRANSPARENT)
                setStroke(
                    dp(1),
                    when {
                        active -> ThemeAccent.color(context)
                        modern && dark -> 0x24FFFFFF
                        else -> context.getColor(R.color.line)
                    }
                )
            }
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(10) }
            isClickable = true
            if (modern && grid && AppServices.settings.tabShowThumb()) addView(modernTabThumb(tab))
            addView(head)
            if (grid && !modern && AppServices.settings.tabShowThumb()) {
                val thumb = tab.thumbnail
                if (thumb != null) {
                    addView(android.widget.FrameLayout(context).apply {
                        layoutParams = LinearLayout.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT, dp(100)
                        )
                        addView(ImageView(context).apply {
                            setImageBitmap(thumb)
                            scaleType = ImageView.ScaleType.CENTER_CROP
                            alpha = 0.32f
                            layoutParams = android.widget.FrameLayout.LayoutParams(
                                ViewGroup.LayoutParams.MATCH_PARENT,
                                ViewGroup.LayoutParams.MATCH_PARENT
                            )
                        })
                        addView(ImageView(context).apply {
                            setImageBitmap(thumb)
                            scaleType = ImageView.ScaleType.MATRIX
                            layoutParams = android.widget.FrameLayout.LayoutParams(
                                ViewGroup.LayoutParams.MATCH_PARENT,
                                ViewGroup.LayoutParams.MATCH_PARENT
                            )
                            fun fitWidth() {
                                val drawable = drawable ?: return
                                val bitmapWidth = drawable.intrinsicWidth
                                if (bitmapWidth <= 0 || width <= 0) return
                                val scale = width.toFloat() / bitmapWidth
                                imageMatrix = android.graphics.Matrix().apply {
                                    setScale(scale, scale)
                                    postTranslate(0f, 0f)
                                }
                            }
                            addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> fitWidth() }
                            post { fitWidth() }
                        })
                    })
                } else {
                    addView(TextView(context).apply {
                        text = tabDisplayTitle(tab)
                        setTextSize(11f)
                        setTextColor(context.getColor(R.color.text_secondary))
                        gravity = android.view.Gravity.CENTER
                        layoutParams = LinearLayout.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT, dp(100)
                        )
                    })
                }
            }

            // 轻点切换、长按操作交给标准点击机制；触摸监听只接管横向滑动，纵向留给 ScrollView。
            val density = resources.displayMetrics.density
            val swipeSlop = 12 * density
            var startX = 0f
            var startY = 0f
            var swiping = false
            setOnClickListener {
                if (index != tabManager.currentIndex) switchToTab(index)
                hideTabsPanel()
            }
            setOnLongClickListener {
                showTabOpsDialog(index)
                true
            }
            setOnTouchListener { v, event ->
                when (event.actionMasked) {
                    android.view.MotionEvent.ACTION_DOWN -> {
                        startX = event.rawX
                        startY = event.rawY
                        swiping = false
                        v.animate().cancel()
                        v.translationX = 0f
                        false
                    }
                    android.view.MotionEvent.ACTION_MOVE -> {
                        if (!swiping) {
                            val dx = event.rawX - startX
                            val dy = event.rawY - startY
                            if (kotlin.math.abs(dx) > swipeSlop &&
                                kotlin.math.abs(dx) > kotlin.math.abs(dy) * 1.4f
                            ) {
                                swiping = true
                                v.isPressed = false
                                v.cancelLongPress()
                                (v.parent as? ViewGroup)?.requestDisallowInterceptTouchEvent(true)
                            }
                        }
                        if (swiping) {
                            v.translationX = event.rawX - startX
                            true
                        } else {
                            false
                        }
                    }
                    android.view.MotionEvent.ACTION_UP,
                    android.view.MotionEvent.ACTION_CANCEL -> {
                        if (!swiping) return@setOnTouchListener false
                        val width = v.width.coerceAtLeast(1)
                        if (event.actionMasked == android.view.MotionEvent.ACTION_UP &&
                            kotlin.math.abs(v.translationX) > width * 0.35f
                        ) {
                            val direction = if (v.translationX > 0) 1f else -1f
                            v.animate().translationX(direction * width).setDuration(160)
                                .withEndAction {
                                    closeTabAndHint(index)
                                    if (tabManager.size == 0) hideTabsPanel() else renderTabCards()
                                }
                                .start()
                        } else {
                            v.animate().translationX(0f).setDuration(140).start()
                        }
                        (v.parent as? ViewGroup)?.requestDisallowInterceptTouchEvent(false)
                        true
                    }
                    else -> false
                }
            }
        }
    }

    /** 长按卡片：关闭其他/左侧/右侧、复制标签。 */
    private fun showTabOpsDialog(index: Int) {
        val ops = arrayOf(
            getString(R.string.tab_op_close_others),
            getString(R.string.tab_op_close_left),
            getString(R.string.tab_op_close_right),
            getString(R.string.tab_op_duplicate)
        )
        android.app.AlertDialog.Builder(requireContext())
            .setItems(ops) { _, which ->
                when (which) {
                    0 -> closeOthersThan(index)
                    1 -> closeRange(0, index - 1)
                    2 -> closeRange(index + 1, tabManager.size - 1)
                    3 -> duplicateTab(index)
                }
                renderTabCards()
            }
            .show()
    }

    private fun closeOthersThan(keep: Int) {
        var closed = false
        for (i in tabManager.size - 1 downTo 0) {
            if (i != keep) {
                tabManager.closeTab(i)
                closed = true
            }
        }
        if (closed) showCloseTabHint()
    }

    private fun closeRange(from: Int, to: Int) {
        if (from > to) return
        var closed = false
        for (i in to downTo from) {
            if (i in 0 until tabManager.size) {
                tabManager.closeTab(i)
                closed = true
            }
        }
        if (closed) showCloseTabHint()
    }

    private fun duplicateTab(index: Int) {
        val tab = tabManager.tabs.getOrNull(index) ?: return
        if (tab.url.isBlank()) return
        openNewTab(tab.url)
        tabManager.current?.title = tab.title
    }

    // ---- 菜单浮层（原生底部 sheet） ----

    private fun showMenuSheet() {
        blurAddressInput()
        // 菜单文案缓存被清空时（如页面切换后），打开菜单自动补翻，不必重翻整页。
        if (translateFabState == 3 && uiLabelCache.isEmpty()) translateUiLabels(activeTranslateTarget)
        menuOverlay?.bringToFront()
        applyHomeChromeTint()
        renderMenuSheet()
        menuOverlay?.visibility = View.VISIBLE
        menuOverlay?.alpha = 0f
        menuOverlay?.animate()?.alpha(1f)?.setDuration(160)?.start()
        updateToolbarActiveState()
        val panel = view?.findViewById<View>(R.id.menu_panel)
        val fromTop = AppServices.settings.uiStyle() != 1 &&
            AppServices.settings.toolbarPosition() == 1
        (panel?.layoutParams as? FrameLayout.LayoutParams)?.let { params ->
            params.gravity = if (fromTop) android.view.Gravity.TOP else android.view.Gravity.BOTTOM
            panel.layoutParams = params
        }
        panel?.post {
            val target = view?.findViewById<View>(R.id.menu_panel) ?: return@post
            target.translationY = if (fromTop) -target.height.toFloat() else target.height.toFloat()
            target.animate().translationY(0f).setDuration(200).start()
        }
    }

    /** 固定菜单栏位置：0 = 移动项上方，1 = 移动项下方（分隔线跟随）。 */
    private fun applyFixedMenuPosition() {
        val panel = view?.findViewById<LinearLayout>(R.id.menu_panel) ?: return
        val fixedBar = view?.findViewById<View>(R.id.menu_fixed_bar) ?: return
        val divider = view?.findViewById<View>(R.id.menu_divider) ?: return
        panel.removeView(fixedBar)
        panel.removeView(divider)
        if (AppServices.settings.fixedMenuPosition() == 1) {
            panel.addView(divider)
            panel.addView(fixedBar)
        } else {
            panel.addView(fixedBar, minOf(2, panel.childCount))
            panel.addView(divider, minOf(3, panel.childCount))
        }
    }

    private fun renderMenuSheet() {
        val catalog = menuCatalog()
        applyFixedMenuPosition()
        val byId = catalog.associateBy { it.id }
        val fixedOrder = AppServices.settings.fixedItems()?.split(",")?.filter { it.isNotEmpty() }
            ?.takeIf { it.isNotEmpty() } ?: DEFAULT_FIXED
        val fixedItems = fixedOrder.mapNotNull { byId[it] }
        val fixedColumns = fixedItems.size.coerceAtLeast(1)
        val menuBg = menuThemeColor
        val modern = AppServices.settings.uiStyle() == 1
        val dark = modern && AppServices.settings.isNightMode()
        view?.findViewById<View>(R.id.menu_panel)?.apply {
            if (modern) {
                background = GlassStyle.background(
                    requireContext(), dark, dp(28).toFloat(),
                    AppServices.settings.glassStrength(), topOnly = true, minAlpha = 225
                )
                backgroundTintList = null
            } else {
                setBackgroundResource(R.drawable.bg_modern_sheet)
                backgroundTintList = android.content.res.ColorStateList.valueOf(
                    menuBg ?: requireContext().getColor(R.color.card)
                )
            }
        }
        view?.findViewById<TextView>(R.id.menu_title)?.setTextColor(
            if (dark) android.graphics.Color.WHITE
            else requireContext().getColor(R.color.text_primary)
        )
        val menuForeground = if (dark) android.graphics.Color.WHITE
        else menuBg?.let { contrastOn(it) }

        val toggles = menuToggles ?: return
        toggles.removeAllViews()
        val menuColumns = AppServices.settings.menuColumns().coerceIn(4, 5)
        val horizontalMode = AppServices.settings.menuScrollStyle() == 1
        view?.findViewById<View>(R.id.menu_scroll)?.visibility =
            if (horizontalMode) View.GONE else View.VISIBLE
        view?.findViewById<View>(R.id.menu_horizontal)?.visibility =
            if (horizontalMode) View.VISIBLE else View.GONE
        val fixedWidth = ((resources.displayMetrics.widthPixels - dp(28)) / fixedColumns)
            .coerceAtLeast(dp(48))
        fixedItems.forEach { entry ->
            val cell = buildFixedMenuEntry(entry, menuForeground, columns = fixedColumns)
            cell.layoutParams = LinearLayout.LayoutParams(
                fixedWidth,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
            toggles.addView(cell)
        }

        val grid = menuGrid ?: return
        grid.removeAllViews()
        val hidden = AppServices.settings.hiddenMenus().orEmpty()
            .split(",").filter { it.isNotEmpty() }.toSet()
        val customOrder = AppServices.settings.displayedMenus().orEmpty()
            .split(",").filter { it.isNotEmpty() }
        val ordered = if (customOrder.isEmpty()) catalog else {
            customOrder.mapNotNull { byId[it] } + catalog.filter { it.id !in customOrder }
        }
        // 同时出现在固定栏的项不再显示在网格区（固定栏优先）。
        val items = ordered.filter { it.id !in hidden && it.id !in fixedOrder }
        if (horizontalMode) {
            val gridH = menuGridHorizontal ?: return
            gridH.removeAllViews()
            gridH.setPadding(dp(8), dp(4), dp(8), dp(4))
            val rows = (0 until 2).map {
                LinearLayout(requireContext()).apply {
                    orientation = LinearLayout.HORIZONTAL
                    layoutParams = LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.WRAP_CONTENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT
                    )
                }
            }
            items.forEachIndexed { index, item ->
                rows[index % 2].addView(
                    buildMenuEntry(item, menuForeground, columns = menuColumns)
                )
            }
            rows.forEach { gridH.addView(it) }
            return
        }
        // 4 列纵向网格，使用等分权重保证每列间距一致；不足一行用占位补齐。
        grid.setPadding(dp(8), dp(4), dp(8), dp(4))
        items.chunked(menuColumns).forEach { chunk ->
            val row = LinearLayout(requireContext()).apply {
                orientation = LinearLayout.HORIZONTAL
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                )
            }
            chunk.forEach { item ->
                val cell = buildMenuEntry(item, menuForeground, columns = menuColumns)
                cell.layoutParams = LinearLayout.LayoutParams(
                    0,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    1f
                )
                row.addView(cell)
            }
            repeat(menuColumns - chunk.size) {
                row.addView(
                    View(requireContext()),
                    LinearLayout.LayoutParams(0, 1, 1f)
                )
            }
            grid.addView(row)
        }
        applyMenuPanelHeight()
    }

    /** 菜单网格区域按内容高度自适应，最多占屏幕高度一半。 */
    private fun applyMenuPanelHeight() {
        val scroll = view?.findViewById<android.widget.ScrollView>(R.id.menu_scroll) ?: return
        val grid = menuGrid ?: return
        scroll.post {
            if (!isAdded) return@post
            val width = scroll.width.takeIf { it > 0 }
                ?: resources.displayMetrics.widthPixels
            grid.measure(
                View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.AT_MOST),
                View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
            )
            val max = (resources.displayMetrics.heightPixels * 0.5f).toInt()
            val target = grid.measuredHeight.coerceAtMost(max)
            if (target > 0 && scroll.layoutParams.height != target) {
                scroll.layoutParams.height = target
                scroll.requestLayout()
            }
        }
    }

    private fun hasWebPage(): Boolean {
        val url = tabManager.current?.url.orEmpty()
        return homeContainer?.visibility != View.VISIBLE &&
            (url.startsWith("http://") || url.startsWith("https://"))
    }

    private fun hasAnyPage(): Boolean {
        val url = tabManager.current?.url.orEmpty()
        return url.startsWith("http://") || url.startsWith("https://") || url.startsWith("file://")
    }

    /** 菜单项目录：常用项优先，上方可移动区与下方固定栏共用。 */
    private fun menuCatalog(): List<MenuEntry> = listOf(
        MenuEntry("refresh", getString(R.string.menu_refresh), R.drawable.ic_m_refresh, false, { false }, { hasWebPage() }) { toggleRefresh() },
        MenuEntry("home", getString(R.string.menu_home), R.drawable.ic_m_home, false, { false }, { homeContainer?.visibility != View.VISIBLE }) { loadUrlFor(AppServices.settings.homeUrl()) },
        MenuEntry("addbookmark", getString(R.string.menu_add_bookmark), R.drawable.ic_m_bookmark_add, false, { false }, { hasWebPage() }) { addCurrentBookmark() },
        MenuEntry("bookmarks", getString(R.string.menu_bookmarks), R.drawable.ic_m_bmlist, false, { false }) { openBookmarks() },
        MenuEntry("downloads", getString(R.string.menu_downloads), R.drawable.ic_m_download, false, { false }) { openDownloads() },
        MenuEntry("netlog", getString(R.string.settings_netlog), R.drawable.ic_m_netlog, false, { false }) { openNetlog() },
        MenuEntry("jsconsole", getString(R.string.menu_js_console), R.drawable.ic_m_code, false, { false }, { hasWebPage() }) { openJsConsole() },
        MenuEntry("history", getString(R.string.menu_history), R.drawable.ic_m_history, false, { false }) { openHistory() },
        MenuEntry("adblockstats", getString(R.string.menu_adblock_stats), R.drawable.ic_m_shield_check, false, { false }) { openAdblockStats() },
        MenuEntry("find", getString(R.string.menu_find), R.drawable.ic_m_find, false, { false }, { hasWebPage() }) { showFindBar() },
        MenuEntry("font", getString(R.string.menu_font_size), R.drawable.ic_m_font, false, { false }, { hasWebPage() }) { showFontSizeSlider() },
        MenuEntry("uiscale", getString(R.string.menu_ui_scale), R.drawable.ic_m_zoom, false, { false }, { true }) { showUiScaleSlider() },
        MenuEntry("share", getString(R.string.menu_share), R.drawable.ic_m_share, false, { false }, { hasWebPage() }) { shareCurrentUrl() },
        MenuEntry("copylink", getString(R.string.menu_copy_link), R.drawable.ic_m_copy, false, { false }, { hasWebPage() }) { copyCurrentUrl() },
        MenuEntry("settings", getString(R.string.menu_settings), R.drawable.ic_m_settings, false, { false }) { openSettings() },
        MenuEntry("reader", getString(R.string.menu_reader), R.drawable.ic_m_reader, false, { false }, { hasWebPage() }) { toggleReaderMode() },
        MenuEntry("sniff", getString(R.string.menu_sniff), R.drawable.ic_m_sniff, false, { false }, { hasWebPage() }) { openSniff() },
        MenuEntry("scan", getString(R.string.menu_scan), R.drawable.ic_m_scan, false, { false }) { startScan() },
        MenuEntry("readaloud", getString(R.string.menu_read_aloud), R.drawable.ic_m_speaker, false, { false }, { hasWebPage() }) { toggleReadAloud() },
        MenuEntry("savepage", getString(R.string.menu_save_page), R.drawable.ic_m_save_page, false, { false }, { hasWebPage() }) { savePageOffline() },
        MenuEntry("readlaterlist", getString(R.string.menu_read_later), R.drawable.ic_m_read_later, false, { false }) { openReadLater() },
        MenuEntry("translatepage", getString(R.string.menu_translate_page), R.drawable.ic_m_translate, false, { false }, { hasWebPage() }) { translatePage() },
        MenuEntry("source", getString(R.string.menu_source), R.drawable.ic_m_code, false, { false }, { hasWebPage() }) { showPageSource() },
        MenuEntry("print", getString(R.string.menu_print), R.drawable.ic_m_print, false, { false }, { hasWebPage() }) { printPage() },
        MenuEntry("passwords", getString(R.string.menu_passwords), R.drawable.ic_m_key, false, { false }) { openPasswords() },
        MenuEntry("scriptmenu", getString(R.string.menu_script_commands), R.drawable.ic_m_script, false, { false }) { showScriptMenu() },
        MenuEntry("addhome", getString(R.string.menu_add_home), R.drawable.ic_m_home_add, false, { false }) { addToHomeScreen() },
        MenuEntry("cleardata", getString(R.string.menu_clear_data), R.drawable.ic_m_clear_data, false, { false }) { clearMenuData() },
        MenuEntry("adpick", getString(R.string.menu_ad_pick), R.drawable.ic_m_pick, false, { false }, { hasWebPage() }) { startAdPick() },
        MenuEntry("closetabs", getString(R.string.menu_close_all_tabs), R.drawable.ic_close, false, { false }) { closeAllTabs() },
        MenuEntry("dark", getString(R.string.menu_no_night), R.drawable.ic_m_dark, true, { AppServices.settings.isNightMode() }) {
            AppServices.settings.toggleNightMode()
            // 先把当前标签落库，recreate 后按快照恢复，避免切夜间丢标签。
            persistTabs()
            // recreate 会吞掉浮层关闭动画，直接 GONE（无动画，避免卡顿感）再重建。
            menuOverlay?.visibility = View.GONE
            view?.post { (activity as? ShellActivity)?.let { ShellActivity.smoothRecreateForNightSwitch(it) } }
        },
        MenuEntry("nightauto", getString(R.string.menu_night_auto), R.drawable.ic_m_night_auto, true, { AppServices.settings.nightFollowSystem() }) {
            AppServices.settings.setNightFollowSystem(!AppServices.settings.nightFollowSystem())
            persistTabs()
            menuOverlay?.visibility = View.GONE
            view?.post { (activity as? ShellActivity)?.let { ShellActivity.smoothRecreateForNightSwitch(it) } }
        },
        MenuEntry("incog", getString(R.string.menu_incognito), R.drawable.ic_m_incog, true, { AppServices.privacyManager.isIncognito }) {
            val pm = AppServices.privacyManager
            pm.isIncognito = !pm.isIncognito
            AppServices.settings.setIncognitoActive(pm.isIncognito)
            if (pm.isIncognito) {
                pm.incognitoSessionStartedAt = System.currentTimeMillis()
                AppServices.settings.setIncognitoSessionStartedAt(pm.incognitoSessionStartedAt)
            }
            if (!pm.isIncognito) {
                if (AppServices.settings.incognitoClearOnExit()) {
                    pm.clearCache()
                    pm.clearCookies()
                    pm.clearWebStorage()
                }
                cleanupIncognitoData()
                AppServices.settings.setIncognitoSessionStartedAt(0L)
            }
            applyIncognitoBadge()
            // 无痕切换后立即重新应用 WebView 限制并刷新当前页（JS、图片、存储等）。
            reapplyPrivacySettingsIfChanged()
        },
        MenuEntry("noimg", getString(R.string.menu_no_img), R.drawable.ic_m_noimg, true, { noImageEnabled() }) {
            setNoImage(!noImageEnabled())
        },
        MenuEntry("adblock", getString(R.string.adblock_master), R.drawable.ic_m_shield, true, { AppServices.adBlock.enabled }) {
            val enabled = !AppServices.adBlock.enabled
            AppServices.adBlock.enabled = enabled
            AppServices.settings.setAdBlockEnabled(enabled)
            AppServices.refreshAdBlockWhitelist()
        },
        MenuEntry("desktop", getString(R.string.menu_desktop), R.drawable.ic_m_desktop, true, {
            // 全局开关开着时菜单也应为开（主页等非网页页面 URL 为空，否则会误显示为关）。
            AppServices.settings.desktopMode() || isDesktopMode()
        }) {
            onDesktopMenuClick()
        },
        MenuEntry("game", getString(R.string.menu_game_mode), R.drawable.ic_m_game, true, {
            AppServices.settings.gameMode()
        }) {
            setGameMode(!AppServices.settings.gameMode())
        },
        MenuEntry("newtab", getString(R.string.menu_new_tab), R.drawable.ic_m_new_tab, false, { false }) {
            openNewTab()
        },
        MenuEntry("closeothers", getString(R.string.menu_close_others), R.drawable.ic_close, false, { false }, { tabManager.size > 1 }) {
            tabManager.closeOthers()
            renderTabs()
        },
        MenuEntry("recentclosed", getString(R.string.menu_recent_closed), R.drawable.ic_m_restore, false, { false }, { tabManager.recentlyClosed().isNotEmpty() }) {
            showRecentlyClosed()
        },
        MenuEntry("fullscreen", getString(if (immersiveFullscreen) R.string.menu_exit_fullscreen else R.string.menu_fullscreen), R.drawable.ic_m_fullscreen, true, { immersiveFullscreen }, { hasAnyPage() }) {
            toggleFullscreen()
        }
    )

    private inner class MenuEntry(
        val id: String,
        val label: String,
        val icon: Int,
        val isToggle: Boolean,
        val active: () -> Boolean,
        val enabled: () -> Boolean = { true },
        val action: () -> Unit
    )

    /** 单个菜单格（上方 grid 与固定栏共用）：图标默认灰、开启态为主题色；单元格按面板可用宽度/4 居中。 */
    private fun buildMenuEntry(entry: MenuEntry, foreground: Int? = null, columns: Int = 4, fixed: Boolean = false): View {
        val context = requireContext()
        val cellWidth = ((context.resources.displayMetrics.widthPixels - dp(8)) / columns)
            .coerceAtLeast(1)
        val iconSize = dp(AppServices.settings.menuIconSize().coerceIn(16, 36))
        val textSize = AppServices.settings.menuTextSize().coerceIn(8, 18).toFloat()
        val available = entry.enabled()
        val on = entry.isToggle && entry.active()
        val iconColor = when {
            on -> ThemeAccent.color(context)
            foreground != null -> foreground
            else -> context.getColor(R.color.text_primary)
        }
        val labelColor = when {
            on -> ThemeAccent.color(context)
            foreground != null -> foreground
            else -> context.getColor(R.color.text_primary)
        }
        return LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = android.view.Gravity.CENTER
            setPadding(dp(2), dp(14), dp(2), dp(14))
            layoutParams = LinearLayout.LayoutParams(cellWidth, if (fixed) dp(AppServices.settings.fixedMenuHeight()) else ViewGroup.LayoutParams.WRAP_CONTENT)
            isClickable = true
            setBackgroundResource(android.R.drawable.list_selector_background)
            addView(ImageView(context).apply {
                setImageResource(entry.icon)
                imageTintList = android.content.res.ColorStateList.valueOf(iconColor)
                layoutParams = LinearLayout.LayoutParams(iconSize, iconSize)
            })
            addView(TextView(context).apply {
                text = uiText(entry.label)
                setTextSize(textSize)
                setTextColor(labelColor)
                gravity = android.view.Gravity.CENTER
                maxLines = 1
                ellipsize = android.text.TextUtils.TruncateAt.END
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply { topMargin = dp(6) }
                visibility = if (AppServices.settings.menuShowLabels()) View.VISIBLE else View.GONE
            })
            alpha = if (available) 1f else 0.35f
            setOnClickListener {
                if (!available) return@setOnClickListener
                if (entry.isToggle) {
                    entry.action()
                    renderMenuSheet()
                } else {
                    hideMenuSheet()
                    entry.action()
                }
            }
            menuSettingsView(entry.id)?.let { viewId ->
                setOnLongClickListener {
                    openMenuSettings(viewId)
                    true
                }
            }
        }
    }

    /** 固定菜单栏独立单元格：不裁文字，图标和文字独立尺寸。 */
    private fun buildFixedMenuEntry(entry: MenuEntry, foreground: Int? = null, columns: Int = 4): View {
        val context = requireContext()
        val cellWidth = ((context.resources.displayMetrics.widthPixels - dp(8)) / columns).coerceAtLeast(1)
        val available = entry.enabled()
        val on = entry.isToggle && entry.active()
        val color = when {
            on -> ThemeAccent.color(context)
            foreground != null -> foreground
            else -> context.getColor(R.color.text_primary)
        }
        val iconSize = dp(AppServices.settings.fixedMenuIconSize().coerceIn(16, 36))
        val showLabel = AppServices.settings.fixedMenuShowLabels()
        return LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = android.view.Gravity.CENTER
            setPadding(dp(2), dp(3), dp(2), dp(3))
            minimumHeight = dp(AppServices.settings.fixedMenuHeight())
            layoutParams = LinearLayout.LayoutParams(cellWidth, ViewGroup.LayoutParams.WRAP_CONTENT)
            isClickable = true
            setBackgroundResource(android.R.drawable.list_selector_background)
            addView(ImageView(context).apply {
                setImageResource(entry.icon)
                imageTintList = android.content.res.ColorStateList.valueOf(color)
                layoutParams = LinearLayout.LayoutParams(iconSize, iconSize)
            })
            addView(TextView(context).apply {
                text = uiText(entry.label)
                setTextSize(AppServices.settings.fixedMenuTextSize().coerceIn(8, 18).toFloat())
                setTextColor(color)
                gravity = android.view.Gravity.CENTER
                maxLines = 1
                ellipsize = android.text.TextUtils.TruncateAt.END
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply { topMargin = dp(3) }
                visibility = if (showLabel) View.VISIBLE else View.GONE
            })
            alpha = if (available) 1f else 0.35f
            setOnClickListener {
                if (!available) return@setOnClickListener
                if (entry.isToggle) {
                    entry.action()
                    renderMenuSheet()
                } else {
                    hideMenuSheet()
                    entry.action()
                }
            }
            menuSettingsView(entry.id)?.let { viewId ->
                setOnLongClickListener {
                    openMenuSettings(viewId)
                    true
                }
            }
        }
    }

    /** 菜单项对应的设置页；null 表示没有独立设置。 */
    private fun menuSettingsView(id: String): String? = when (id) {
        "home" -> SettingsDetailFragment.VIEW_V2_HOME
        "downloads" -> SettingsDetailFragment.VIEW_V2_DOWNLOAD
        "netlog" -> SettingsDetailFragment.VIEW_V2_NETLOG
        "jsconsole" -> SettingsDetailFragment.VIEW_V2_NETWORK
        "reader" -> SettingsDetailFragment.VIEW_V2_READING_MODE
        "sniff" -> SettingsDetailFragment.VIEW_V2_BROWSE
        "translatepage" -> SettingsDetailFragment.VIEW_V2_TRANSLATE
        "passwords" -> SettingsDetailFragment.VIEW_V2_PASSWORD
        "cleardata" -> SettingsDetailFragment.VIEW_V2_CLEAR
        "adblock", "adpick" -> "rules"
        "dark", "nightauto" -> SettingsDetailFragment.VIEW_V2_THEME
        "incog" -> SettingsDetailFragment.VIEW_V2_INCOG
        "newtab" -> SettingsDetailFragment.VIEW_V2_OPERATE
        else -> null
    }

    private fun openMenuSettings(viewId: String) {
        hideMenuSheet()
        val target: androidx.fragment.app.Fragment = if (viewId == "rules") {
            RulesFragment()
        } else {
            SettingsDetailFragment().apply {
                arguments = android.os.Bundle().apply {
                    putString(SettingsDetailFragment.ARG_VIEW, viewId)
                }
            }
        }
        NavAnim.apply(parentFragmentManager.beginTransaction())
            .replace(R.id.shell_root, target)
            .addToBackStack(null)
            .commit()
    }
    private fun noImageEnabled(): Boolean =
        AppServices.settings.webPageBehavior().isEnabled(WebPageBehavior.BIT_NO_IMAGE)

    private fun setNoImage(enabled: Boolean) {
        val behavior = AppServices.settings.webPageBehavior()
        behavior.setEnabled(WebPageBehavior.BIT_NO_IMAGE, enabled)
        AppServices.settings.setWebPageBehavior(behavior)
        tabManager.tabs.forEach { it.webView.settings.loadsImagesAutomatically = !enabled }
        tabManager.currentWebView?.reload()
    }

    /** 字体大小竖滑竿浮层（拖动实时生效）。 */
    private fun showUiScaleSlider() {
        val context = requireContext()
        val density = resources.displayMetrics.density
        fun dp(value: Int) = (value * density).toInt()
        val valueText = TextView(context).apply {
            text = "${AppServices.settings.uiScale()}%"
            setTextSize(12f)
            setTextColor(context.getColor(R.color.text_secondary))
            gravity = android.view.Gravity.CENTER
        }
        val seek = android.widget.SeekBar(context).apply {
            max = (150 - 50) / 5
            progress = ((AppServices.settings.uiScale() - 50) / 5).coerceIn(0, max)
            rotation = -90f
        }
        val holder = android.widget.FrameLayout(context).apply {
            addView(
                seek,
                android.widget.FrameLayout.LayoutParams(dp(150), dp(36), android.view.Gravity.CENTER)
            )
        }
        val container = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = android.view.Gravity.CENTER
            setBackgroundResource(R.drawable.bg_card)
            setPadding(dp(10), dp(12), dp(10), dp(10))
            addView(TextView(context).apply {
                text = getString(R.string.menu_ui_scale)
                setTextSize(12f)
                setTextColor(context.getColor(R.color.text_secondary))
            })
            addView(holder, LinearLayout.LayoutParams(dp(60), dp(160)))
            addView(valueText)
        }
        val popup = android.widget.PopupWindow(
            container,
            ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
            true
        ).apply {
            isOutsideTouchable = true
            elevation = dp(8).toFloat()
            setBackgroundDrawable(
                android.graphics.drawable.ColorDrawable(android.graphics.Color.TRANSPARENT)
            )
        }
        seek.setOnSeekBarChangeListener(object : android.widget.SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(
                seekBar: android.widget.SeekBar?,
                progress: Int,
                fromUser: Boolean
            ) {
                val scale = 50 + progress * 5
                valueText.text = "$scale%"
                AppServices.settings.setUiScale(scale)
            }

            override fun onStartTrackingTouch(seekBar: android.widget.SeekBar?) = Unit

            override fun onStopTrackingTouch(seekBar: android.widget.SeekBar?) {
                activity?.recreate()
            }
        })
        val root = view ?: return
        popup.showAtLocation(
            root,
            android.view.Gravity.BOTTOM or android.view.Gravity.END,
            dp(12),
            dp(120)
        )
    }

    private fun showFontSizeSlider() {
        val context = requireContext()
        val density = resources.displayMetrics.density
        fun dp(value: Int) = (value * density).toInt()
        val valueText = TextView(context).apply {
            text = "${AppServices.settings.textSize()}%"
            setTextSize(12f)
            setTextColor(context.getColor(R.color.text_secondary))
            gravity = android.view.Gravity.CENTER
        }
        val seek = android.widget.SeekBar(context).apply {
            max = (200 - 50) / 5
            progress = ((AppServices.settings.textSize() - 50) / 5).coerceIn(0, max)
            rotation = -90f
        }
        val holder = android.widget.FrameLayout(context).apply {
            addView(
                seek,
                android.widget.FrameLayout.LayoutParams(dp(150), dp(36), android.view.Gravity.CENTER)
            )
        }
        val container = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = android.view.Gravity.CENTER
            setBackgroundResource(R.drawable.bg_card)
            setPadding(dp(10), dp(12), dp(10), dp(10))
            addView(TextView(context).apply {
                text = getString(R.string.menu_font_size)
                setTextSize(12f)
                setTextColor(context.getColor(R.color.text_secondary))
            })
            addView(holder, LinearLayout.LayoutParams(dp(60), dp(160)))
            addView(valueText)
        }
        val popup = android.widget.PopupWindow(
            container,
            ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
            true
        ).apply {
            isOutsideTouchable = true
            elevation = dp(8).toFloat()
            setBackgroundDrawable(
                android.graphics.drawable.ColorDrawable(android.graphics.Color.TRANSPARENT)
            )
        }
        seek.setOnSeekBarChangeListener(object : android.widget.SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(
                seekBar: android.widget.SeekBar?,
                progress: Int,
                fromUser: Boolean
            ) {
                val size = 50 + progress * 5
                valueText.text = "$size%"
                AppServices.settings.setTextSize(size)
                AppServices.applyPageZoom?.invoke()
            }

            override fun onStartTrackingTouch(seekBar: android.widget.SeekBar?) = Unit

            override fun onStopTrackingTouch(seekBar: android.widget.SeekBar?) = Unit
        })
        val root = view ?: return
        popup.showAtLocation(
            root,
            android.view.Gravity.BOTTOM or android.view.Gravity.END,
            dp(12),
            dp(120)
        )
    }

    private fun navigateFromHome(text: String) {
        val url = resolveInput(text)
        loadUrlFor(url)
    }


    private fun setAddressText(text: String) {
        val input = addressInput ?: return
        addressTextProgrammatic = true
        input.setText(text)
        addressTextProgrammatic = false
    }

    private fun setupAddressBar() {
        view?.findViewById<View>(R.id.modern_cancel)?.setOnClickListener { blurAddressInput() }
        addressInput?.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_GO) {
                hideSuggestions()
                navigateToInput()
                true
            } else false
        }
        addressInput?.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                if (addressTextProgrammatic) return
                val query = s?.toString()?.trim().orEmpty()
                if (AppServices.settings.uiStyle() != 1 || query.isEmpty() ||
                    URLUtil.isNetworkUrl(query)
                ) {
                    hideSuggestions()
                    return
                }
                suggestionsSuppressed = false
                val seq = ++suggestSeq
                suggestDebounceJob?.cancel()
                suggestDebounceJob = viewLifecycleOwner.lifecycleScope.launch {
                    kotlinx.coroutines.delay(180)
                    if (!isAdded || seq != suggestSeq || suggestionsSuppressed) return@launch
                    if (addressInput?.hasFocus() != true) return@launch
                    loadSuggestions(query) { items ->
                        if (seq != suggestSeq || suggestionsSuppressed) return@loadSuggestions
                        if (addressInput?.hasFocus() == true &&
                            addressInput?.text?.toString()?.trim() == query
                        ) {
                            showSuggestions(items, preferAbove = true)
                        }
                    }
                }
            }
        })
        addressInput?.setOnFocusChangeListener { _, hasFocus ->
            setModernEditing(hasFocus)
            if (hasFocus) {
                addressInput?.animate()
                    ?.scaleX(1.025f)
                    ?.scaleY(1.025f)
                    ?.setDuration(180L)
                    ?.setInterpolator(android.view.animation.DecelerateInterpolator())
                    ?.start()
                val url = tabManager.current?.url.orEmpty()
                setAddressText(
                    if (url == ABOUT_HOME || url == "about:blank") "" else url
                )
                addressInput?.setSelection(addressInput?.text?.length ?: 0)
                hideSuggestions()
                if (AppServices.settings.uiStyle() == 1 &&
                    AppServices.settings.searchSuggestion() != 0 &&
                    AppServices.settings.autoFillClipboard() &&
                    addressInput?.text?.toString().orEmpty().isBlank()
                ) {
                    suggestionsSuppressed = false
                    pendingAddressClipboard = true
                    if (imeInsetVisible) {
                        showPendingAddressClipboard()
                    } else {
                        view?.postDelayed({ if (addressEditing) showPendingAddressClipboard() }, 420L)
                    }
                }
            } else {
                pendingAddressClipboard = false
                addressInput?.animate()
                    ?.scaleX(1f)
                    ?.scaleY(1f)
                    ?.setDuration(140L)
                    ?.setInterpolator(android.view.animation.DecelerateInterpolator())
                    ?.start()
                hideSuggestions()
                updateAddressBarForCurrent()
            }
        }
    }

    /** 地址栏搜索建议：本地（历史/书签）+ 搜索引擎建议合并，仅在「搜索建议」开启且非 URL 时请求。 */
    /** 地址栏与主页搜索框共用：本地历史/书签 + 联网联想词。 */
    private fun loadSuggestions(query: String, onLoaded: (List<Suggestion>) -> Unit) {
        val settings = AppServices.settings
        val useHistory = settings.searchSuggestHistory()
        val useBookmarks = settings.searchSuggestBookmarks()
        val useOnline = settings.searchSuggestOnline()
        if (settings.searchSuggestion() == 0 || query.isBlank() ||
            URLUtil.isNetworkUrl(query) || (!useHistory && !useBookmarks && !useOnline)
        ) {
            onLoaded(emptyList())
            return
        }
        val engine = settings.searchEngineUrl().orEmpty()
        val targets = suggestionApis(engine).map {
            it + android.net.Uri.encode(query)
        }
        viewLifecycleOwner.lifecycleScope.launch {
            val local = if (useHistory || useBookmarks) {
                kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                    runCatching {
                        val history = if (useHistory) {
                            AppServices.historyRepository.recent(500).first()
                                .filter { it.url.contains(query, true) || it.title.contains(query, true) }
                                .map { Suggestion(it.title.ifEmpty { it.url }, it.url, SuggestionKind.HISTORY, it.url) }
                        } else emptyList()
                        val bookmarks = if (useBookmarks) {
                            AppServices.bookmarkRepository.allItemsOnce()
                                .filter { it.url.contains(query, true) || it.title.contains(query, true) }
                                .map { Suggestion(it.title.ifEmpty { it.url }, it.url, SuggestionKind.BOOKMARK, it.url) }
                        } else emptyList()
                        (bookmarks + history).distinctBy { it.value }
                    }.getOrDefault(emptyList<Suggestion>())
                }
            } else emptyList()
            // 本地结果先显示，联网结果到达后再合并，避免网络慢时看不到历史/书签。
            onLoaded(local.distinctBy { it.value })
            if (!useOnline) return@launch
            val online = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                coroutineScope {
                    val results = targets.map { target ->
                        async {
                            runCatching { fetchSuggestions(target) }.getOrDefault(emptyList())
                        }
                    }
                    awaitAll(*results.toTypedArray())
                        .firstOrNull { it.isNotEmpty() } ?: emptyList()
                }
            }
            onLoaded(
                (local + online.map { Suggestion(it, it, SuggestionKind.SEARCH) })
                    .distinctBy { it.value }
            )
        }
    }

    /** 联想词接口：优先当前引擎，失败时回退到其他可用接口。 */
    private fun suggestionApis(engine: String): List<String> = when {
        engine.contains("baidu") -> listOf("https://www.baidu.com/sugrec?prod=pc&wd=")
        engine.contains("bing") -> listOf(
            "https://api.bing.com/osjson.aspx?query=",
            "https://www.baidu.com/sugrec?prod=pc&wd="
        )
        else -> listOf(
            "https://suggestqueries.google.com/complete/search?client=firefox&q=",
            "https://api.bing.com/osjson.aspx?query=",
            "https://www.baidu.com/sugrec?prod=pc&wd="
        )
    }

    private val suggestionClient by lazy {
        okhttp3.OkHttpClient.Builder()
            .connectTimeout(1500, java.util.concurrent.TimeUnit.MILLISECONDS)
            .readTimeout(1500, java.util.concurrent.TimeUnit.MILLISECONDS)
            .callTimeout(2500, java.util.concurrent.TimeUnit.MILLISECONDS)
            .build()
    }

    private fun fetchSuggestions(url: String): List<String> {
        val uri = runCatching { android.net.Uri.parse(url) }.getOrNull()
        val referer = when {
            uri?.host?.contains("baidu") == true -> "https://www.baidu.com/"
            uri?.host?.contains("bing") == true -> "https://www.bing.com/"
            uri?.host?.contains("google") == true -> "https://www.google.com/"
            else -> null
        }
        val request = okhttp3.Request.Builder()
            .url(url)
            .header(
                "User-Agent",
                "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 " +
                    "(KHTML, like Gecko) Chrome/120.0 Mobile Safari/537.36"
            )
            .header("Accept", "application/json,text/javascript,*/*;q=0.8")
            .header("Accept-Language", "zh-CN,zh;q=0.9,en;q=0.8")
            .apply { if (referer != null) header("Referer", referer) }
            .build()
        val response = suggestionClient.newCall(request).execute()
        response.use {
            if (!it.isSuccessful) return emptyList()
            val body = it.body?.string().orEmpty().trim()
            if (body.isEmpty()) return emptyList()
            val out = mutableListOf<String>()
            // 百度 sugrec：{"g":[{"q":"…"}]}；Google/Bing：["q",["…"]]
            runCatching {
                val g = if (body.startsWith("{")) {
                    org.json.JSONObject(body).optJSONArray("g")
                } else null
                if (g != null) {
                    for (i in 0 until g.length()) {
                        g.optJSONObject(i)?.optString("q")
                            ?.takeIf { it.isNotEmpty() }?.let { out.add(it) }
                    }
                } else if (body.startsWith("[")) {
                    val arr = org.json.JSONArray(body).optJSONArray(1)
                    if (arr != null) {
                        for (i in 0 until arr.length()) {
                            arr.optString(i).takeIf { it.isNotEmpty() }?.let { out.add(it) }
                        }
                    }
                }
            }
            return out.distinct()
        }
    }

    private fun showPendingAddressClipboard() {
        if (!pendingAddressClipboard) return
        pendingAddressClipboard = false
        val input = addressInput ?: return
        if (!input.hasFocus() || input.text.toString().isNotBlank()) return
        val clips = ClipboardSuggestion.readAll(requireContext())
        if (clips.isNotEmpty() && input.hasFocus()) {
            showSuggestions(clips, preferAbove = true)
        }
    }

    private fun showSuggestions(items: List<Suggestion>, preferAbove: Boolean = false) {
        val input = addressInput
        if (input == null || !input.hasFocus()) {
            hideSuggestions()
            return
        }
        // 本地结果为空时只收起弹层，不要标记 suppressed，否则后续联网结果会被丢弃。
        if (items.isEmpty()) {
            suggestionPopup?.dismiss()
            return
        }
        val count = AppServices.settings.suggestionCount().coerceIn(3, 10)
        val sorted = sortSuggestions(items, AppServices.settings.suggestOrder(), count)
        if (sorted.isEmpty()) {
            suggestionPopup?.dismiss()
            return
        }
        val popup = suggestionPopup ?: SuggestionPopup(
            requireContext(),
            view?.findViewById<View>(R.id.address_bar) ?: input
        ) { item ->
            pickSuggestion(item)
        }.also { suggestionPopup = it }
        popup.show(sorted, preferAbove)
    }

    private fun pickSuggestion(item: Suggestion) {
        val input = addressInput ?: return
        when (item.kind) {
            SuggestionKind.CLIPBOARD_TEXT, SuggestionKind.CLIPBOARD_MIXED -> {
                input.setText(item.value)
                input.setSelection(item.value.length)
                hideSuggestions()
                input.requestFocus()
            }
            SuggestionKind.CLIPBOARD_LINK -> {
                hideSuggestions()
                hideKeyboard()
                input.clearFocus()
                loadUrlFor(item.value)
            }
            else -> {
                input.setText(item.value)
                input.setSelection(item.value.length)
                hideSuggestions()
                hideKeyboard()
                navigateToInput()
            }
        }
    }

    private fun hideKeyboard() {
        val imm = requireContext().getSystemService(Context.INPUT_METHOD_SERVICE)
            as? android.view.inputmethod.InputMethodManager ?: return
        val token = addressInput?.windowToken ?: view?.windowToken ?: return
        imm.hideSoftInputFromWindow(token, 0)
    }

    private fun blurAddressInput() {
        addressInput?.clearFocus()
        hideKeyboard()
        hideSuggestions()
        if (AppServices.settings.uiStyle() == 1) {
            // 取消编辑时键盘还在收起，底栏先复位；等键盘完全收起再校正一次。
            val dock = view?.findViewById<View>(R.id.modern_chrome)
            if (dock != null) {
                dock.animate().cancel()
                dock.translationY = 0f
                view?.postDelayed({ if (isAdded) applyDockImeOffset() }, 260L)
            }
        }
    }

    private fun hideSuggestions() {
        // 递增序号并标记抑制，让尚未返回的建议请求全部失效，避免导航后浮层又冒出来。
        suggestSeq++
        suggestionsSuppressed = true
        suggestDebounceJob?.cancel()
        suggestionPopup?.dismiss()
    }

    private fun dpToPx(value: Int): Int =
        (value * resources.displayMetrics.density).toInt()

    /** 下载文件名：优先 Content-Disposition；APK MIME/地址强制 .apk，避免被猜成 .bin。 */
    private fun guessDownloadName(
        url: String,
        contentDisposition: String?,
        mimeType: String?
    ): String {
        var name = URLUtil.guessFileName(url, contentDisposition, mimeType)
        val lowerUrl = url.substringBefore('?').lowercase()
        val lowerDisposition = contentDisposition.orEmpty().lowercase()
        val lowerMime = mimeType.orEmpty().lowercase()
        val isApk = lowerMime.contains("android.package-archive") ||
            lowerMime == "application/x-apk" ||
            lowerUrl.endsWith(".apk") ||
            lowerDisposition.contains(".apk")
        if (isApk && !name.lowercase().endsWith(".apk")) {
            name = if (name.contains('.')) {
                name.substringBeforeLast('.') + ".apk"
            } else {
                "$name.apk"
            }
        }
        return name
    }

    /** 快捷引擎栏：一排可横向滚动的引擎按钮，点击用该引擎搜索当前输入。 */
    private fun setupSearchToolbar() {
        val scroll = view?.findViewById<HorizontalScrollView>(R.id.search_toolbar_scroll) ?: return
        val bar = view?.findViewById<LinearLayout>(R.id.search_toolbar) ?: return
        bar.removeAllViews()
        if (!AppServices.settings.searchToolbar()) {
            scroll.visibility = View.GONE
            return
        }
        configuredSearchEngines().forEach { (name, url) ->
            bar.addView(TextView(requireContext()).apply {
                    text = name
                    setTextSize(13f)
                    setTextColor(requireContext().getColor(R.color.text_primary))
                    gravity = android.view.Gravity.CENTER
                    setPadding(dpToPx(12), 0, dpToPx(12), 0)
                    layoutParams = LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.WRAP_CONTENT,
                        dpToPx(32)
                    ).apply { marginEnd = dpToPx(8) }
                    setBackgroundResource(R.drawable.bg_engine_chip)
                setOnClickListener {
                    val query = searchQueryFromCurrent()
                        ?: addressInput?.text?.toString()?.trim().orEmpty()
                    if (query.isNotEmpty()) {
                        loadUrlFor(
                            com.sii.browser.data.SearchEngines.build(
                                com.sii.browser.data.SearchEngines.normalize(url), query
                            )
                        )
                    }
                }
            })
        }
        applyQuickEnginePosition()
    }

    /** 内置 + 自定义搜索引擎，按名称/地址去重。 */
    private fun configuredSearchEngines(): List<Pair<String, String>> {
        val engines = com.sii.browser.data.SearchEngines.DEFAULT.toMutableList()
        runCatching {
            val array = org.json.JSONArray(AppServices.settings.searchEngines().orEmpty())
            for (i in 0 until array.length()) {
                val item = array.optJSONObject(i) ?: continue
                engines.add(
                    item.optString("name") to
                        com.sii.browser.data.SearchEngines.normalize(item.optString("url"))
                )
            }
        }
        val seen = mutableSetOf<String>()
        return engines.map { it.first to com.sii.browser.data.SearchEngines.normalize(it.second) }
            .filter { it.second.isNotBlank() }
            .filter { seen.add(it.first + "|" + it.second) }
    }

    /** 当前 URL 是否为所配置搜索引擎的搜索结果页。 */
    private fun isSearchResultPage(url: String?): Boolean {
        val uri = runCatching { android.net.Uri.parse(url) }.getOrNull() ?: return false
        if (uri.scheme != "http" && uri.scheme != "https") return false
        val host = uri.host?.lowercase() ?: return false
        val path = uri.path.orEmpty()
        val params = uri.queryParameterNames
        if (configuredSearchEngines().any { matchesSearchEngine(it.second, host, path, params) }) {
            return true
        }
        return when {
            host.startsWith("www.google.") || host.startsWith("google.") -> path.startsWith("/search")
            host.endsWith("bing.com") -> path.startsWith("/search")
            host.endsWith("baidu.com") -> path.startsWith("/s")
            host.endsWith("duckduckgo.com") -> params.contains("q")
            host.endsWith("sogou.com") -> path.startsWith("/web")
            host.endsWith("so.com") -> path.startsWith("/s")
            host.endsWith("sm.cn") -> path.startsWith("/s")
            host == "so.toutiao.com" -> path.startsWith("/search")
            host.endsWith("brave.com") -> path.startsWith("/search")
            host.endsWith("yandex.com") -> path.startsWith("/search")
            host.endsWith("startpage.com") -> path.contains("/search")
            host.endsWith("ecosia.org") -> path.startsWith("/search")
            else -> false
        }
    }

    private fun matchesSearchEngine(
        base: String,
        host: String,
        path: String,
        params: Set<String>
    ): Boolean {
        val baseUri = runCatching { android.net.Uri.parse(base) }.getOrNull() ?: return false
        val baseHost = baseUri.host?.lowercase() ?: return false
        val basePath = baseUri.path.orEmpty()
        val baseParams = baseUri.queryParameterNames
        if (host != baseHost && !host.endsWith(".$baseHost")) return false
        val pathOk = if (basePath.isBlank() || basePath == "/") {
            baseParams.isEmpty() || params.any { it in baseParams }
        } else {
            path == basePath || path.startsWith(basePath.trimEnd('/') + "/")
        }
        if (!pathOk) return false
        return baseParams.isEmpty() || params.any { it in baseParams }
    }

    /** 从当前搜索结果页提取查询词，供切换引擎时复用。 */
    private fun searchQueryFromCurrent(): String? {
        val uri = runCatching { android.net.Uri.parse(tabManager.current?.url) }.getOrNull()
            ?: return null
        val host = uri.host?.lowercase() ?: return null
        val path = uri.path.orEmpty()
        val params = uri.queryParameterNames
        configuredSearchEngines().forEach { (_, base) ->
            if (!matchesSearchEngine(base, host, path, params)) return@forEach
            val baseUri = runCatching { android.net.Uri.parse(base) }.getOrNull()
                ?: return@forEach
            baseUri.queryParameterNames.firstOrNull { it in params }?.let { key ->
                uri.getQueryParameter(key)?.takeIf { it.isNotBlank() }?.let { return it }
            }
        }
        for (key in listOf("q", "wd", "query", "keyword", "p")) {
            uri.getQueryParameter(key)?.takeIf { it.isNotBlank() }?.let { return it }
        }
        return null
    }

    /** 快捷引擎栏：新版并入悬浮底栏（地址胶囊上方），经典模式按设置放地址栏/工具栏旁。 */
    private fun applyQuickEnginePosition() {
        val scroll = view?.findViewById<View>(R.id.search_toolbar_scroll) ?: return
        if (!AppServices.settings.searchToolbar()) {
            scroll.visibility = View.GONE
            return
        }
        if (AppServices.settings.uiStyle() == 1) {
            quickEngineFullHeight = dp(40)
            val quickHost = view?.findViewById<LinearLayout>(R.id.modern_quick_host) ?: return
            if (scroll.parent !== quickHost) {
                (scroll.parent as? ViewGroup)?.removeView(scroll)
                quickHost.removeAllViews()
                quickHost.addView(
                    scroll,
                    LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        dp(40)
                    ).apply { bottomMargin = dp(2) }
                )
            } else {
                scroll.layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    dp(40)
                ).apply { bottomMargin = dp(2) }
            }
            scroll.setBackgroundColor(android.graphics.Color.TRANSPARENT)
            updateQuickEngineVisibility()
            return
        }
        val content = view?.findViewById<LinearLayout>(R.id.browser_content) ?: return
        quickEngineFullHeight = dp(44)
        (scroll.parent as? ViewGroup)?.removeView(scroll)
        scroll.layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            dp(44)
        )
        if (AppServices.settings.quickEnginePosition() == 1) {
            val bottom = view?.findViewById<View>(R.id.bottom_bar)
            if (bottom?.parent === content) {
                content.addView(scroll, content.indexOfChild(bottom))
            } else {
                content.addView(scroll)
            }
        } else {
            val progress = view?.findViewById<View>(R.id.progress_bar)
            val address = view?.findViewById<View>(R.id.address_bar)
            val anchor = if (progress?.parent === content) progress else address
            val index = if (anchor?.parent === content) content.indexOfChild(anchor) + 1 else 0
            content.addView(scroll, index)
        }
        scroll.setBackgroundColor(requireContext().getColor(R.color.toolbar_bg))
        updateQuickEngineVisibility()
    }

    /** 地址栏左侧页面信息图标只在 http(s) 网页显示。 */
    private fun updateSiteInfoVisibility() {
        val button = siteInfoButton ?: return
        updateSiteInfoIcon()
        val url = tabManager.current?.url
        // 新标签尚未导航：保持图标原状，避免闪没。
        if (url.isNullOrEmpty()) return
        val visible = homeContainer?.visibility != View.VISIBLE &&
            (url.startsWith("http://") || url.startsWith("https://"))
        button.visibility = if (visible) View.VISIBLE else View.GONE
    }

    /** 当前页面是否无痕：全局开关，或单站设置为无痕。 */
    private fun currentIncognito(): Boolean {
        if (AppServices.privacyManager.isIncognito) return true
        val config = siteConfigFor(currentPageHost())
        return SiteConfig.enabled(config) && SiteConfig.tri(config, "incog") == 1
    }

    /** 无痕时左侧固定图标换成无痕图标，点击行为不变。 */
    private fun updateSiteInfoIcon() {
        val button = siteInfoButton as? android.widget.ImageButton ?: return
        val tab = tabManager.current
        val incognito = currentIncognito()
        when {
            incognito -> {
                button.setImageResource(R.drawable.ic_m_incog)
                button.imageTintList = android.content.res.ColorStateList.valueOf(
                    addressIconColor ?: requireContext().getColor(R.color.icon_primary)
                )
                button.contentDescription = getString(R.string.incognito_badge)
            }
            tab?.security == SiteSecurity.THREAT -> {
                button.setImageResource(R.drawable.ic_site_warning)
                button.imageTintList = android.content.res.ColorStateList.valueOf(
                    requireContext().getColor(R.color.danger)
                )
                button.contentDescription = "不安全网站"
            }
            tab?.security == SiteSecurity.CERT_ERROR -> {
                button.setImageResource(R.drawable.ic_site_warning)
                button.imageTintList = android.content.res.ColorStateList.valueOf(
                    requireContext().getColor(R.color.danger)
                )
                button.contentDescription = "证书错误"
            }
            tab?.security == SiteSecurity.HTTP -> {
                button.setImageResource(R.drawable.ic_site_warning)
                button.imageTintList = android.content.res.ColorStateList.valueOf(
                    requireContext().getColor(R.color.accent_orange)
                )
                button.contentDescription = "不安全连接"
            }
            else -> {
                button.setImageResource(R.drawable.ic_site_info)
                button.imageTintList = android.content.res.ColorStateList.valueOf(
                    addressIconColor ?: requireContext().getColor(R.color.icon_primary)
                )
                button.contentDescription = getString(R.string.site_info_title)
            }
        }
    }

    /** 地址栏左侧图标菜单：页面信息、证书、Cookies、网站设定与匹配脚本。 */
    private fun showSiteInfoMenu(anchor: View) {
        val tab = tabManager.current ?: return
        val url = tab.url.takeIf { it.startsWith("http://") || it.startsWith("https://") } ?: return
        val host = runCatching { android.net.Uri.parse(url).host }.getOrNull() ?: return
        val title = tab.title.ifEmpty { host }
        lifecycleScope.launch {
            val scripts = runCatching {
                AppServices.scriptRepository.matchingScripts(url)
            }.getOrDefault(emptyList())
            if (!isAdded) return@launch
            val entries = mutableListOf<Pair<String, () -> Unit>>()
            val certificate = tabManager.currentWebView?.certificate
            if (certificate != null) {
                entries.add(getString(R.string.ssl_view_cert) to { showCertificateInfo(certificate) })
            }
            entries.add(getString(R.string.site_menu_cookies) to { showCookies(url) })
            entries.add(
                getString(
                    if (siteSettingsEnabled(host)) R.string.site_menu_settings_enabled
                    else R.string.site_menu_settings
                ) to { openSiteSettings(host) }
            )
            if (scripts.isNotEmpty()) {
                entries.add(getString(R.string.site_menu_scripts) to { showMatchingScripts(scripts) })
            }
            entries.add(
                getString(R.string.site_menu_add_desktop) to {
                    // 手机模式下：把当前站加入默认电脑模式网站并启用，再直达设置页。
                    if (!isDesktopForUrl(url)) enableDesktopSite(host)
                    openDesktopSitesSettings()
                }
            )
            showSiteInfoPopup(anchor, title, url, entries)
        }
    }

    private fun siteSettingsEnabled(host: String): Boolean {
        val raw = AppServices.siteConfigRepository.cachedConfig(host) ?: return false
        return runCatching { org.json.JSONObject(raw).optBoolean("on", true) }.getOrDefault(false)
    }

    private fun showSiteInfoPopup(
        anchor: View,
        title: String,
        url: String,
        entries: List<Pair<String, () -> Unit>>
    ) {
        val context = requireContext()
        val modern = AppServices.settings.uiStyle() == 1
        val dark = modern && AppServices.settings.isNightMode()
        val titleColor = if (dark) android.graphics.Color.WHITE
        else context.getColor(R.color.text_primary)
        val subColor = if (dark) 0xFF9AA0A8.toInt()
        else context.getColor(R.color.text_secondary)
        val container = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(4), dp(6), dp(4), dp(6))
        }
        container.addView(LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), dp(8), dp(12), dp(10))
            addView(TextView(context).apply {
                text = title
                setTextSize(13f)
                setTextColor(titleColor)
                maxLines = 2
                ellipsize = android.text.TextUtils.TruncateAt.END
            })
            addView(TextView(context).apply {
                text = url
                setTextSize(11.5f)
                setTextColor(subColor)
                maxLines = 1
                ellipsize = android.text.TextUtils.TruncateAt.END
                setPadding(0, dp(3), 0, 0)
            })
        })
        container.addView(View(context).apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                dp(1)
            ).apply { topMargin = dp(2); bottomMargin = dp(2) }
            setBackgroundColor(if (dark) 0x33FFFFFF else context.getColor(R.color.line))
        })
        var popup: android.widget.PopupWindow? = null
        entries.forEach { (label, action) ->
            container.addView(TextView(context).apply {
                text = uiText(label)
                setTextSize(13f)
                setTextColor(titleColor)
                setPadding(dp(12), dp(12), dp(12), dp(12))
                setBackgroundResource(R.drawable.bg_list_item)
                isClickable = true
                setOnClickListener {
                    popup?.dismiss()
                    action()
                }
            })
        }
        container.measure(
            View.MeasureSpec.UNSPECIFIED,
            View.MeasureSpec.UNSPECIFIED
        )
        val width = container.measuredWidth.coerceAtMost(
            resources.displayMetrics.widthPixels - dp(24)
        ).coerceAtLeast(dp(200))
        container.measure(
            View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
        )
        val contentHeight = container.measuredHeight
        val scroll = android.widget.ScrollView(context).apply {
            isVerticalScrollBarEnabled = true
            setBackgroundResource(if (dark) R.drawable.bg_dialog_dark else R.drawable.bg_card)
            clipToOutline = true
            addView(
                container,
                ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                )
            )
        }
        val anchorLocation = IntArray(2)
        anchor.getLocationInWindow(anchorLocation)
        val decorLocation = IntArray(2)
        anchor.rootView.getLocationOnScreen(decorLocation)
        val frame = android.graphics.Rect()
        anchor.getWindowVisibleDisplayFrame(frame)
        val visibleTop = frame.top - decorLocation[1]
        val spaceAbove = (anchorLocation[1] - visibleTop - dp(12)).coerceAtLeast(dp(160))
        val popupHeight = if (modern) contentHeight.coerceAtMost(spaceAbove).coerceAtLeast(dp(80))
        else contentHeight
        popup = android.widget.PopupWindow(
            scroll,
            width,
            popupHeight,
            true
        ).apply {
            isOutsideTouchable = true
            elevation = dp(10).toFloat()
            setBackgroundDrawable(android.graphics.drawable.ColorDrawable(android.graphics.Color.TRANSPARENT))
        }
        if (modern) {
            val screenX = decorLocation[0] + anchorLocation[0]
            val screenY = decorLocation[1] + anchorLocation[1]
            val x = screenX.coerceIn(
                frame.left + dp(8),
                (frame.right - width - dp(8)).coerceAtLeast(frame.left + dp(8))
            )
            val y = (screenY - dp(8) - popupHeight)
                .coerceAtLeast(frame.top + dp(8))
            popup?.showAtLocation(
                anchor,
                android.view.Gravity.TOP or android.view.Gravity.START,
                x,
                y
            )
        } else {
            popup?.showAsDropDown(anchor, 0, dp(4))
        }
    }

    private fun showCookies(url: String) {
        val cookies = android.webkit.CookieManager.getInstance().getCookie(url)
        android.app.AlertDialog.Builder(requireContext())
            .setTitle(R.string.site_menu_cookies)
            .setMessage(if (cookies.isNullOrBlank()) getString(R.string.site_menu_cookies_empty) else cookies)
            .setPositiveButton(android.R.string.ok, null)
            .show()
    }

    private fun showMatchingScripts(scripts: List<com.sii.browser.data.db.ScriptEntity>) {
        val names = scripts.joinToString("\n") { "• ${it.name}" }
        android.app.AlertDialog.Builder(requireContext())
            .setTitle(R.string.site_menu_scripts_matched)
            .setMessage(names.ifEmpty { getString(R.string.site_menu_scripts_empty) })
            .setPositiveButton(android.R.string.ok, null)
            .show()
    }

    private fun showCertificateInfo(certificate: android.net.http.SslCertificate) {
        val text = listOf(
            getString(R.string.ssl_cert_issued_to) + ": " + certificate.issuedTo?.toString().orEmpty(),
            getString(R.string.ssl_cert_issued_by) + ": " + certificate.issuedBy?.toString().orEmpty(),
            getString(R.string.ssl_cert_valid_from) + ": " + certificate.validNotBefore.orEmpty(),
            getString(R.string.ssl_cert_valid_to) + ": " + certificate.validNotAfter.orEmpty()
        ).joinToString("\n")
        android.app.AlertDialog.Builder(requireContext())
            .setTitle(R.string.ssl_view_cert)
            .setMessage(text)
            .setPositiveButton(android.R.string.ok, null)
            .show()
    }

    private fun openSiteSettings(host: String) {
        NavAnim.apply(parentFragmentManager.beginTransaction())
            .replace(
                R.id.shell_root,
                SiteRuleDetailFragment().apply {
                    arguments = android.os.Bundle().apply {
                        putString(SiteRuleDetailFragment.ARG_HOST, host)
                    }
                }
            )
            .addToBackStack(null)
            .commit()
    }

    /** 主页不显示快捷引擎栏，只在实际网页页显示。 */
    private fun updateQuickEngineVisibility() {
        val scroll = view?.findViewById<View>(R.id.search_toolbar_scroll) ?: return
        val hiddenWithToolbar = toolbarHidden &&
            AppServices.settings.toolbarPosition() == 0 &&
            AppServices.settings.quickEnginePosition() == 1
        val visible = !hiddenWithToolbar &&
            AppServices.settings.searchToolbar() &&
            homeContainer?.visibility != View.VISIBLE &&
            isSearchResultPage(tabManager.current?.url)
        quickEngineAnimator?.cancel()
        quickEngineAnimator = null
        quickEngineHidden = false
        scroll.alpha = 1f
        if (quickEngineFullHeight > 0) {
            scroll.layoutParams = scroll.layoutParams.apply { height = quickEngineFullHeight }
        }
        scroll.visibility = if (visible) View.VISIBLE else View.GONE
    }

    /**
     * 应用网站主题色。raw 为页面声明的原始颜色；是否真正生效取决于总开关、
     * 夜间模式与「强制为背景色」：夜间默认保持深色外观，开启强制后才套用。
     */
    private fun currentThemeColor(): Int? =
        tabManager.currentWebView?.let { themeColors[it] }

    private fun applySiteThemeColor(raw: Int?, previous: Int? = null) {
        val settings = AppServices.settings
        // 电脑模式页面（如微博桌面版）主题色常为纯白，且这类页面 JS 频繁改写 theme-color，
        // 完全短路：不染色也不重置，避免工具栏/状态栏闪烁。
        if (isDesktopMode()) {
            return
        }
        // 网站主题色只在白天生效（含其下所有设置），夜间使用默认夜间配色。
        if (settings.isNightMode()) {
            if (settings.uiStyle() != 1) {
                applyToolbarTheme(null)
                applyAddressTheme(null)
            }
            menuThemeColor = null
            longPressThemeColor = null
            (activity as? ShellActivity)?.applyStatusBarColor(null)
            return
        }
        // B 站等站点 JS 会反复删改 theme-color meta（暗亮轮播），同一 WebView 收到相同值时忽略，
        // 避免 null(清色)→色→null 的循环渐变。
        if (raw != null && raw == previous) {
            return
        }
        val effective = raw?.takeIf { settings.themeColorEnabled() }
        val modern = settings.uiStyle() == 1
        if (!modern) {
            applyToolbarTheme(if (settings.themeColorToolbar()) effective else null)
            applyAddressTheme(if (settings.themeColorAddress()) effective else null)
        }
        menuThemeColor = if (!modern && settings.themeColorMenu()) effective else null
        longPressThemeColor = if (settings.themeColorLongPress()) effective else null
        (activity as? ShellActivity)?.applyStatusBarColor(
            if (settings.themeColorStatus()) effective else null
        )
        if (homeContainer?.visibility == View.VISIBLE) applyHomeChrome(true)
        if (menuOverlay?.visibility == View.VISIBLE) renderMenuSheet()
        // 白天：强制为背景色时，把识别到的主题色应用到整页。
        if (raw != null && settings.themeColorEnabled() &&
            settings.themeColorForceBackground()
        ) {
            tabManager.currentWebView?.let { injectThemeBackground(it, raw) }
        } else if (!settings.themeColorEnabled() || !settings.themeColorForceBackground()) {
            tabManager.currentWebView?.injectJavaScript(
                "(function(){var s=document.getElementById('sii_theme_bg');if(s)s.remove();})();"
            )
        }
    }

    /** 整页背景改为主题色，文字用反色，图片/视频等媒体保持原样。 */
    private fun injectThemeBackground(webView: SiiWebView, color: Int) {
        val text = contrastOn(color)
        val bg = String.format("#%06X", 0xFFFFFF and color)
        val fg = String.format("#%06X", 0xFFFFFF and text)
        val css = "html,body{background-color:$bg!important}" +
            "body,body *{background-color:transparent!important;color:$fg!important;" +
            "-webkit-text-fill-color:$fg!important}" +
            "img,video,picture,canvas,iframe,svg{background-color:transparent!important}"
        val quoted = org.json.JSONObject.quote(css)
        webView.injectJavaScript(
            "(function(){var s=document.getElementById('sii_theme_bg');" +
                "if(!s){s=document.createElement('style');s.id='sii_theme_bg';" +
                "document.documentElement.appendChild(s);}" +
                "s.textContent=$quoted;" +
                "requestAnimationFrame(function(){s.textContent=$quoted;});" +
                "setTimeout(function(){s.textContent=$quoted;},40);})();"
        )
    }

    private fun applyToolbarTheme(color: Int?) {
        val context = requireContext()
        val bar = view?.findViewById<View>(R.id.bottom_bar) ?: return
        val fallback = ContextCompat.getColor(context, R.color.toolbar_bg)
        val foreground = color?.let { contrastOn(it) } ?: context.getColor(R.color.text_primary)
        toolbarIconColor = foreground
        // 与状态栏同帧直接变色，三者同步，不做逐帧动画。
        bar.setBackgroundColor(color ?: fallback)
        tintToolbarContent(bar, foreground)
    }

    private fun tintToolbarContent(target: View, color: Int) {
        when (target) {
            is TextView -> target.setTextColor(color)
            is ImageView -> target.imageTintList =
                android.content.res.ColorStateList.valueOf(color)
        }
        if (target is ViewGroup) {
            for (i in 0 until target.childCount) tintToolbarContent(target.getChildAt(i), color)
        }
    }

    private fun applyAddressTheme(color: Int?) {
        val context = requireContext()
        val bar = view?.findViewById<View>(R.id.address_bar) ?: return
        val fallback = ContextCompat.getColor(context, R.color.toolbar_bg)
        val foreground = color?.let { contrastOn(it) } ?: context.getColor(R.color.text_primary)
        // 与状态栏、工具栏同帧直接变色。
        bar.setBackgroundColor(color ?: fallback)
        addressInput?.setTextColor(foreground)
        addressInput?.setHintTextColor(
            if (color != null) withAlpha(foreground, 0.6f)
            else context.getColor(R.color.text_secondary)
        )
        val iconColor = if (color != null) foreground else context.getColor(R.color.text_secondary)
        addressIconColor = iconColor
        view?.findViewById<ImageView>(R.id.refresh_button)?.imageTintList =
            android.content.res.ColorStateList.valueOf(iconColor)
        view?.findViewById<ImageView>(R.id.site_info_button)?.imageTintList =
            android.content.res.ColorStateList.valueOf(iconColor)
        updateSiteInfoIcon()
    }

    /** 解析 CSS 颜色（#RGB/#RGBA/#RRGGBB/#RRGGBBAA、rgb()/rgba()），输出不透明色。 */
    private fun parseThemeColor(raw: String?): Int? {
        val text = raw?.trim().orEmpty()
        if (text.isEmpty()) return null
        val base = runCatching {
            when {
                text.startsWith("#") -> {
                    val hex = text.substring(1)
                    val rgb = when (hex.length) {
                        3 -> hex.map { "$it$it" }.joinToString("")
                        4 -> hex.take(3).map { "$it$it" }.joinToString("")
                        6 -> hex
                        8 -> hex.take(6)
                        else -> return null
                    }
                    android.graphics.Color.parseColor("#$rgb")
                }
                text.startsWith("rgb", ignoreCase = true) -> {
                    val values = text.substringAfter('(').substringBefore(')')
                        .split(',', ' ', '/')
                        .filter { it.isNotBlank() }
                        .mapNotNull { part ->
                            val trimmed = part.trim()
                            val number = trimmed.removeSuffix("%").toFloatOrNull()
                                ?: return@mapNotNull null
                            val scaled = if (trimmed.endsWith("%")) number * 2.55f else number
                            scaled.toInt().coerceIn(0, 255)
                        }
                    if (values.size < 3) return null
                    android.graphics.Color.rgb(values[0], values[1], values[2])
                }
                else -> return null
            }
        }.getOrNull() ?: return null
        return base or 0xFF000000.toInt()
    }

    /** 主题色背景上的前景色：亮底用深色字，暗底用浅色字。 */
    private fun contrastOn(color: Int): Int {
        val r = android.graphics.Color.red(color) / 255.0
        val g = android.graphics.Color.green(color) / 255.0
        val b = android.graphics.Color.blue(color) / 255.0
        val luminance = 0.2126 * r + 0.7152 * g + 0.0722 * b
        return if (luminance > 0.6) 0xFF1A1A1E.toInt() else 0xFFE8EAED.toInt()
    }

    private fun withAlpha(color: Int, alpha: Float): Int {
        val a = (alpha.coerceIn(0f, 1f) * 255).toInt()
        return (color and 0x00FFFFFF) or (a shl 24)
    }

    /** 刷新/停止切换（加载中点击停止）。 */
    private fun toggleRefresh() {
        blurAddressInput()
        val webView = tabManager.currentWebView ?: return
        if (isCurrentPageLoading()) {
            webView.stopLoading()
            pageLoadFinished[webView] = true
            markPageFinished(webView)
            finishProgressBar()
            setRefreshLoading(false)
            hidePageLoadingCover()
        } else {
            // 错误页把 WebView 换成了 about:blank，reload 只会重载空白页；
            // 改为重新请求出错前的原网址。
            errorPageUrl[webView]?.takeIf { it.startsWith("http") }?.let {
                webView.loadUrl(it)
                return
            }
            webView.reload()
        }
    }

    private fun isCurrentPageLoading(): Boolean {
        val webView = tabManager.currentWebView ?: return false
        return pageLoadFinished[webView] == false || progressBar?.visibility == View.VISIBLE
    }

    private fun setRefreshLoading(loading: Boolean) {
        val button = refreshButton ?: return
        val url = if (::tabManager.isInitialized) tabManager.current?.url else null
        val onHome = homeContainer?.visibility == View.VISIBLE ||
            url.isNullOrBlank() || url == ABOUT_HOME
        val showStop = loading && !onHome
        button.setImageResource(if (showStop) R.drawable.ic_stop else R.drawable.ic_tb_refresh)
        button.contentDescription = getString(if (showStop) R.string.menu_stop else R.string.menu_refresh)
    }

    /** 页面开始加载：显示 0 进度，等待 WebView 后续回调。 */
    private fun markPageLoading(webView: SiiWebView?) {
        val wv = webView ?: return
        // 同一轮加载中的重定向/跳转：保留当前进度，不清零。
        val continuing = progressFinishPending != null || pageLoadFinished[wv] == false
        cancelProgressFinish()
        pageLoadFinished[wv] = false
        if (wv === tabManager.currentWebView) {
            setRefreshLoading(true)
            if (homeContainer?.visibility != View.VISIBLE) {
                progressBar?.let { bar ->
                    bar.animate().cancel()
                    progressAnimator?.cancel()
                    bar.alpha = 1f
                    if (!continuing) bar.progress = 0
                    bar.visibility = View.VISIBLE
                }
            }
        }
    }

    private fun markPageFinished(webView: SiiWebView?) {
        val wv = webView ?: return
        pageLoadFinished[wv] = true
        if (wv === tabManager.currentWebView) {
            setRefreshLoading(false)
            restorePendingScroll(wv)
        }
    }

    /** 日夜重建后把网页滚回原来的位置。 */
    private fun restorePendingScroll(webView: android.webkit.WebView) {
        val target = AppServices.pendingScrollY
        if (target < 0) return
        AppServices.pendingScrollY = -1
        webView.postDelayed({
            if (webView.height > 0) webView.scrollTo(0, target)
        }, 260L)
    }

    /** 进度平滑追到目标值；WebView 只报少量进度时也会缓慢向 80% 靠近。 */
    private fun animateProgressTo(target: Int) {
        val bar = progressBar ?: return
        if (homeContainer?.visibility == View.VISIBLE) {
            bar.visibility = View.GONE
            return
        }
        if (bar.visibility != View.VISIBLE) {
            bar.alpha = 1f
            bar.progress = 0
            bar.visibility = View.VISIBLE
        }
        val current = bar.progress
        val desired = when {
            target <= 0 -> 0
            target >= 90 -> 95
            else -> maxOf(target, 80)
        }
        if (desired <= current) return
        progressAnimator?.cancel()
        val duration = ((desired - current) * 60L).coerceIn(600L, 5000L)
        val animator = ValueAnimator.ofInt(current, desired).apply {
            this.duration = duration
            interpolator = DecelerateInterpolator()
            addUpdateListener {
                bar.progress = it.animatedValue as Int
            }
        }
        progressAnimator = animator
        animator.start()
    }

    /** 页面加载完成：先补齐到 100%，再淡出后隐藏（避免进度条硬消失）。主页不显示进度条。 */
    private fun finishProgressBar() {
        cancelProgressFinish()
        val bar = progressBar
        if (bar != null) {
            progressAnimator?.cancel()
            progressAnimator = null
            bar.animate().cancel()
            bar.progress = 100
            bar.visibility = View.GONE
            bar.progress = 0
            bar.alpha = 1f
        }
        setRefreshLoading(false)
    }

    /** 延迟隐藏，给重定向的下一轮 onPageStarted 留出接续时间。 */
    private fun scheduleProgressFinish() {
        cancelProgressFinish()
        val runnable = Runnable {
            progressFinishPending = null
            finishProgressBar()
        }
        progressFinishPending = runnable
        view?.postDelayed(runnable, 250L)
    }

    private fun cancelProgressFinish() {
        progressFinishPending?.let { view?.removeCallbacks(it) }
        progressFinishPending = null
    }

    /** 同步后退/前进按钮可用状态。 */
    private fun updateNavigationState() {
        backButton?.alpha = 1f
        forwardButton?.alpha = 1f
        setRefreshLoading(isCurrentPageLoading())
        updateToolbarActiveState()
        if (AppServices.settings.uiStyle() == 1) applyModernToolVisibility()
    }

    private fun tintToolbarIcon(view: View?, color: Int) {
        when (view) {
            is android.widget.ImageView -> view.setColorFilter(color)
            is android.view.ViewGroup -> {
                for (i in 0 until view.childCount) tintToolbarIcon(view.getChildAt(i), color)
            }
        }
    }

    /** 激活态跟随主色：可用的前进/后退、主页、标签、菜单按钮。 */
    private fun updateToolbarActiveState() {
        val ctx = context ?: return
        val normal = if (AppServices.settings.uiStyle() == 1) {
            if (AppServices.settings.isNightMode()) android.graphics.Color.WHITE
            else ctx.getColor(R.color.text_primary)
        } else {
            toolbarIconColor ?: ctx.getColor(R.color.text_primary)
        }
        val webView = if (::tabManager.isInitialized) tabManager.currentWebView else null
        tintToolbarIcon(backButton, normal)
        tintToolbarIcon(forwardButton, normal)
        tintToolbarIcon(homeButton, normal)
        tintToolbarIcon(tabCountButton, normal)
        tintToolbarIcon(menuButton, normal)
    }

    /** 标签列表：点击切换，提供新建入口。 */
    private fun showTabsDialog() {
        val titles = tabManager.tabs.mapIndexed { index, tab ->
            val name = tabDisplayTitle(tab)
            if (index == tabManager.currentIndex) "· $name" else name
        }.toTypedArray()
        android.app.AlertDialog.Builder(requireContext())
            .setTitle(R.string.tabs_title)
            .setItems(titles) { _, which -> switchToTab(which) }
            .setPositiveButton(R.string.menu_new_tab) { _, _ -> openNewTab() }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun createWebView(): SiiWebView {
        val appSettings = AppServices.settings
        return SiiWebView(requireContext()).apply {
            downloadSecret = java.util.UUID.randomUUID().toString()
            setBackgroundColor(
                if (AppServices.settings.isNightMode()) 0xFF000000.toInt() else 0xFFFFFFFF.toInt()
            )
            applyBaseSettings(settings)
            applyNativeDarkMode(
                AppServices.settings.isNightMode() && AppServices.settings.webViewDark()
            )
            if (appSettings.siteBlockPopup()) settings.javaScriptCanOpenWindowsAutomatically = false
            if (appSettings.siteBlockDb()) settings.databaseEnabled = false
            if (appSettings.siteBlockDom()) settings.domStorageEnabled = false
            if (appSettings.siteBlockFile()) settings.allowFileAccess = false
            if (appSettings.siteBlockJs()) settings.javaScriptEnabled = false
            // 全局电脑模式开着时，新标签（含 target=_blank）直接以桌面 UA 启动。
            settings.userAgentString = if (appSettings.desktopMode()) {
                appSettings.desktopUserAgent() ?: DESKTOP_UA
            } else if (appSettings.uaSimplified()) {
                simplifyUserAgent(appSettings.userAgent() ?: settings.userAgentString)
            } else {
                appSettings.userAgent() ?: DEFAULT_MOBILE_UA
            }
            settings.textZoom = effectiveTextZoom()
            // 缩放与宽视口恒开：UA 只管身份，视口不随模式切，
            // 手机页面会用自己的 viewport meta 声明，不受影响。
            settings.setSupportZoom(!AppServices.settings.gameMode())
            settings.builtInZoomControls = true
            settings.displayZoomControls = false
            settings.useWideViewPort = true
            settings.loadWithOverviewMode = true
            // 安全浏览（WebView 内置恶意/钓鱼网址拦截）
            settings.safeBrowsingEnabled = appSettings.safeBrowsing()
            val privacy = AppServices.privacyManager
            val block3p = appSettings.thirdPartyCookiesBlocked() || appSettings.privacyCookies()
            android.webkit.CookieManager.getInstance().setAcceptThirdPartyCookies(this, !block3p)
            if (privacy.isIncognito) {
                if (appSettings.incognitoNoForm()) settings.saveFormData = false
                AppServices.lastWebViewSnapshot = settingsSnapshot(this)
            }
            if (appSettings.wifiImagesOnly() && isMeteredNetwork()) {
                settings.loadsImagesAutomatically = false
            }
            if (appSettings.webDebug()) {
                android.webkit.WebView.setWebContentsDebuggingEnabled(true)
            }
            val chain = ResourceInterceptorChain()
            chain.add(com.sii.browser.core.adblock.AdBlockInterceptor(AppServices.adBlock))
            chain.add(com.sii.browser.core.webview.SniffInterceptor(this))
            interceptorChain = chain
            val callback = BrowserCallback()
            webViewClient = WebViewClientBridge(
                callback,
                UrlDecision(),
                chain,
                captureEnabled = { AppServices.settings.mcpAllowCapture() }
            )
            webChromeClient = WebChromeClientBridge(callback)
            // 水平滑动前进/后退（横向浏览占少数场景，不干扰竖向滚动）
            setOnTouchListener { _, event ->
                when (event.action) {
                    android.view.MotionEvent.ACTION_DOWN -> {
                        this@BrowserFragment.blurAddressInput()
                        this@BrowserFragment.swipeStartX = event.x
                        this@BrowserFragment.swipeStartY = event.y
                    }
                    android.view.MotionEvent.ACTION_UP -> {
                        val dx = event.x - this@BrowserFragment.swipeStartX
                        val dy = event.y - this@BrowserFragment.swipeStartY
                        if (AppServices.settings.gestureSwipeNav() &&
                            kotlin.math.abs(dx) > this@BrowserFragment.dpToPx(80) &&
                            kotlin.math.abs(dx) > kotlin.math.abs(dy) * 1.5f
                        ) {
                            val view = this@BrowserFragment.tabManager.currentWebView
                            if (dx > 0) view?.goBack() else view?.goForward()
                        }
                    }
                }
                false
            }
            setDownloadListener { url, _, contentDisposition, mimeType, _ ->
                if (url.isNullOrBlank()) return@setDownloadListener
                val name = guessDownloadName(url, contentDisposition, mimeType)
                if (isUserScriptUrl(url) || name.lowercase().endsWith(".user.js")) {
                    installUserScript(url)
                    return@setDownloadListener
                }
                if (url.startsWith("blob:")) {
                    val quotedName = org.json.JSONObject.quote(name)
                    val quotedUrl = org.json.JSONObject.quote(url)
                    val quotedSecret = org.json.JSONObject.quote(downloadSecret)
                    evaluateJavascript(
                        "(function(){var n=" + quotedName + ";fetch(" + quotedUrl +
                            ").then(function(r){return r.blob();})" +
                            ".then(function(b){var f=new FileReader();f.onload=function(){var i=f.result.indexOf(',');" +
                            "window.sii.saveBlob(n,f.result.substring(i+1)," + quotedSecret + ");};f.readAsDataURL(b);});})();",
                        null
                    )
                    return@setDownloadListener
                }
                if (appSettings.downloadConfirm()) {
                    showDownloadConfirmDialog(url, name)
                } else {
                    startDownload(url, name)
                    notifyDownloadBarReset()
                }
            }
            registerJsBridge("sii", DownloadJsBridge(requireContext().applicationContext, downloadSecret))
            GmApiBridge(requireContext().applicationContext).also { bridge ->
                bridge.webView = this
                gmBridges[this] = bridge
                registerJsBridge("sii_gm", bridge)
            }
            registerJsBridge(
                "SiiSniff",
                com.sii.browser.core.webview.SniffJsBridge(this, this)
            )
            registerJsBridge(
                "SiiCapture",
                com.sii.browser.core.webview.CaptureJsBridge(this)
            )
            com.sii.browser.core.adblock.AdPickBridge().also {
                registerJsBridge("SiiAdPick", it)
                adPickBridges[this] = it
            }
            com.sii.browser.core.webview.PasswordJsBridge().also { bridge ->
                bridge.onOfferSave = { host, username, password ->
                    offerSavePassword(host, username, password)
                }
                registerJsBridge("SiiPwd", bridge)
                passwordBridges[this] = bridge
            }
            val themeColorWebView = this
            com.sii.browser.core.webview.ThemeColorJsBridge { raw ->
                view?.post {
                    val parsed = parseThemeColor(raw)
                    val previous = themeColors[themeColorWebView]
                    themeColors[themeColorWebView] = parsed
                    if (themeColorWebView === tabManager.currentWebView) {
                        applySiteThemeColor(parsed, previous)
                    }
                }
            }.also { registerJsBridge("SiiThemeColor", it) }
            val fullscreenWebView = this
            com.sii.browser.core.webview.FullscreenJsBridge { active ->
                view?.post {
                    pageFullscreenStates[fullscreenWebView] = active
                    if (fullscreenWebView === tabManager.currentWebView) {
                        applyPageFullscreenChrome()
                    }
                }
            }.also { registerJsBridge("SiiFullscreen", it) }
            onLongPressHit = { result ->
                if (!AppServices.settings.gameMode()) showLongPressMenu(result)
            }
            onScrollDy = { dy -> handleToolbarAutoHide(dy) }
            onDoubleTap = {
                if (!AppServices.settings.gameMode()) {
                    when (AppServices.settings.gestureDoubleTap()) {
                        1 -> scrollTo(0, 0)
                        2 -> reload()
                        3 -> showHome()
                    }
                }
            }
            // 下拉刷新（在页面顶部下拉超阀值触发 reload）
            pullRefreshEnabled = AppServices.settings.pullRefresh() &&
                !AppServices.settings.gameMode()
            onPullRefresh = {
                if (AppServices.settings.pullRefresh()) {
                    animateProgressTo(10)
                    reload()
                }
            }
            selectionMenuExtras = buildSelectionExtras()
        }
    }

    private fun renderTabs() {
        val strip = tabStrip ?: return
        strip.removeAllViews()
        for (index in tabManager.tabs.indices) {
            strip.addView(buildTabView(tabManager.tabs[index], index))
        }
        tabBar?.visibility = when {
            AppServices.settings.uiStyle() == 1 -> View.GONE
            AppServices.settings.tabBarPosition() == 2 -> View.GONE
            tabManager.size > 1 -> View.VISIBLE
            else -> View.GONE
        }
        tabCountText?.text = tabManager.size.toString()
        tabCountText?.visibility =
            if (AppServices.settings.uiStyle() == 1 && tabManager.size <= 1) View.INVISIBLE
            else View.VISIBLE
        attachCurrentWebView()
        syncContentVisibility()
        updateAddressBarForCurrent()
        updateNavigationState()
    }

    /** 主页与网页互斥显示（切标签/新建标签后同步）。 */
    private fun syncContentVisibility() {
        val homeVisible = homeContainer?.visibility == View.VISIBLE
        tabManager.currentWebView?.visibility = if (homeVisible) View.GONE else View.VISIBLE
        updateQuickEngineVisibility()
        updateSiteInfoVisibility()
    }

    /** 把当前标签的 WebView 挂进容器，其余标签的 WebView 从容器移除（保留状态不销毁）。 */
    private fun attachCurrentWebView() {
        val container = webViewContainer ?: return
        val current = tabManager.currentWebView ?: return
        AppServices.sniffTarget = current
        hideFindBar()
        // 只管理 WebView 子项，不动主页容器（home_container）
        for (i in container.childCount - 1 downTo 0) {
            val child = container.getChildAt(i)
            if (child is android.webkit.WebView && child !== current) {
                container.removeView(child)
            }
        }
        if (current.parent !== container) {
            (current.parent as? ViewGroup)?.removeView(current)
            container.addView(
                current,
                FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT
                )
            )
        }
    }

    /** 主页覆盖在网页上时，标签卡显示“主页”而不是被覆盖的网站。 */
    private fun tabDisplayTitle(tab: Tab): String {
        if (homeOverlayActive && homeContainer?.visibility == View.VISIBLE &&
            tab === tabManager.current
        ) {
            return getString(R.string.menu_home)
        }
        return tab.title.ifEmpty { tab.url.ifEmpty { getString(R.string.menu_new_tab) } }
    }

    private fun buildTabView(tab: Tab, index: Int): View {
        val context = requireContext()
        return LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
            setPadding(dp(12), dp(6), dp(4), dp(6))
            setBackgroundResource(
                if (index == tabManager.currentIndex) R.drawable.bg_tab_selected else R.drawable.bg_tab_normal
            )
            if (index == tabManager.currentIndex) {
                // 选中态背景跟随主色（半透明强调色）
                val accent = ThemeAccent.color(context)
                backgroundTintList = android.content.res.ColorStateList.valueOf(
                    (accent and 0x00FFFFFF) or 0x22000000
                )
            }
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { marginEnd = dp(6) }
            isClickable = true
            setOnClickListener { switchToTab(index) }
            addView(TextView(context).apply {
                text = tabDisplayTitle(tab)
                setTextSize(12f)
                setTextColor(
                    if (index == tabManager.currentIndex) 0xFF1A73E8.toInt() else 0xFF5F6368.toInt()
                )
                maxLines = 1
                maxWidth = dp(140)
                ellipsize = android.text.TextUtils.TruncateAt.END
            })
            addView(ImageButton(context).apply {
                setImageResource(R.drawable.ic_close)
                contentDescription = getString(R.string.close_tab)
                background = null
                layoutParams = LinearLayout.LayoutParams(
                    dp(28),
                    dp(28)
                ).apply { marginStart = dp(4) }
                setOnClickListener { closeTabAndHint(index) }
            })
        }
    }

    private fun dp(value: Int): Int =
        (value * resources.displayMetrics.density).toInt()

    private fun navigateToInput() {
        hideSuggestions()
        hideKeyboard()
        val text = addressInput?.text?.toString()?.trim().orEmpty()
        if (text.isEmpty()) return
        val url = resolveInput(text)
        loadUrlFor(url)
        addressInput?.clearFocus()
    }

    /** 输入解析：完整 URL 直访，域名样式补 https 直访，其余走搜索引擎。 */
    private fun resolveInput(text: String): String {
        if (URLUtil.isNetworkUrl(text)) return text
        if (DOMAIN_PATTERN.matches(text)) return "https://$text"
        val engine = AppServices.settings.searchEngineUrl().orEmpty()
        val base = com.sii.browser.data.SearchEngines.normalize(
            engine.ifEmpty { DEFAULT_SEARCH_URL }
        )
        return com.sii.browser.data.SearchEngines.build(base, text)
    }

    private fun updateAddressBarForCurrent() {
        if (homeContainer?.visibility == View.VISIBLE) {
            setAddressText("")
            return
        }
        if (addressInput?.hasFocus() == true) return
        // 导航开始前 tab.url 已是目标地址，先显示它；重定向/提交后由 doUpdateVisitedHistory 替换。
        val url = tabManager.current?.url?.takeIf { it.isNotBlank() && it != "about:blank" }
            ?: tabManager.currentWebView?.url?.takeIf { it.isNotBlank() && it != "about:blank" }
            ?: ""
        setAddressText(addressDisplayUrl(url))
    }

    /** 地址栏未聚焦时的显示内容：地址 / 标题 / 域名。主页显示设置的主页网址。 */
    private fun addressDisplay(tab: Tab): String {
        if (tab.url == ABOUT_HOME) {
            return AppServices.settings.homeUrl().takeIf { it != ABOUT_HOME }.orEmpty()
        }
        return addressDisplayUrl(tab.url, tab.title)
    }

    private fun addressDisplayUrl(url: String, title: String = ""): String {
        if (url == ABOUT_HOME) {
            return AppServices.settings.homeUrl().takeIf { it != ABOUT_HOME }.orEmpty()
        }
        if (url.startsWith("about:")) return url
        return when (AppServices.settings.urlBoxBehavior()) {
            1 -> title.ifEmpty { url }
            2 -> runCatching { android.net.Uri.parse(url).host }.getOrNull()
                ?.ifEmpty { url } ?: url
            else -> url
        }
    }

    /** 浏览器回调实现：进度/标题/错误回显，更新对应标签数据。 */
    private inner class BrowserCallback : WebViewCallback() {
        override fun onProgressChanged(webView: SiiWebView?, progress: Int) {
            if (webView !== tabManager.currentWebView) return
            if (webView != null && pageLoadFinished[webView] == true) return
            progressBar?.let {
                animateProgressTo(progress.coerceAtMost(95))
            }
            // 加载中地址栏若仍是空的，直接回显目标地址；任何入口都不允许空地址栏。
            if (homeContainer?.visibility == View.VISIBLE) return
            val tab = tabManager.current
            val url = tab?.url?.takeIf { it.isNotBlank() && it != "about:blank" }
                ?: webView?.url?.takeIf { it.isNotBlank() && it != "about:blank" }
                ?: return
            val display = addressDisplayUrl(url)
            val input = addressInput ?: return
            val focused = input.hasFocus()
            val currentText = input.text?.toString().orEmpty()
            if (currentText == display) return
            if (!focused || currentText.isEmpty()) {
                setAddressText(display)
                if (focused) input.setSelection(display.length)
            }
        }

        override fun doUpdateVisitedHistory(webView: SiiWebView?, url: String) {
            homeForwardAvailable = false
            // 重定向/导航提交即更新标签与地址栏，不等 onPageStarted。
            if (webView == null || url.isBlank() || url == "about:blank") return
            tabManager.tabs.firstOrNull { it.webView === webView }?.let {
                it.url = url
                persistTabs()
            }
            if (webView === tabManager.currentWebView) {
                updateSiteInfoIcon()
                updateAddressBarForCurrent()
            }
        }

        override fun onConsoleMessage(message: android.webkit.ConsoleMessage): Boolean {
            if (!AppServices.settings.jsConsoleLog()) return true
            com.sii.browser.core.webview.JsConsoleStore.add(
                message.messageLevel().name,
                message.sourceId().orEmpty(),
                message.lineNumber(),
                message.message().orEmpty()
            )
            return true
        }

        override fun onReceivedTitle(webView: SiiWebView?, title: String) {
            tabManager.tabs.firstOrNull { it.webView === webView }?.let {
                it.title = title
                if (webView === tabManager.currentWebView) {
                    activity?.title = title
                    renderTabs()
                    updateAddressBarForCurrent()
                }
            }
        }

        override fun onPageStarted(webView: SiiWebView?, url: String, favicon: Bitmap?) {
            homeForwardAvailable = false
            webView?.let {
                sniffPrompted.remove(it)
                sniffPromptDismissed.remove(it)
                if (sniffPromptKey === it) hideSniffPrompt()
            }
            markPageLoading(webView)
            // 尽早 hook fetch/XHR，否则页面初始化阶段的媒体请求会错过。
            if (AppServices.settings.sniffEnabled()) {
                webView?.injectJavaScript(com.sii.browser.core.webview.SniffJsBridge.SNIFF_JS)
            }
            val startedUrl = url.ifBlank { webView?.url.orEmpty() }
            // 抓包：新导航清空该标签旧记录（保留本次主文档），再注入 fetch/XHR hook。
            webView?.let { target ->
                com.sii.browser.core.webview.NetworkCapture.clear(target, startedUrl)
                if (AppServices.settings.mcpAllowCapture()) {
                    target.injectJavaScript(
                        com.sii.browser.core.webview.CaptureScript.js(
                            AppServices.settings.mcpCaptureBody()
                        )
                    )
                }
            }
            if (startedUrl != "about:blank") {
                errorPageLoading.remove(webView)
                errorPageUrl.remove(webView)
            }
            // 页面开始加载时收起搜索建议与输入法，避免结果页残留浮层。
            hideSuggestions()
            hideKeyboard()
            val isCurrentTab = webView === tabManager.currentWebView
            val autoRetranslate = isCurrentTab && startedUrl == translatedPageUrl && startedUrl.isNotBlank()
            translateTabStates.remove(webView)
            if (isCurrentTab) {
                translatePolling?.cancel()
                translateJob?.cancel()
                hideTranslateStatus()
                translateFabState = 0
                translateShowOriginal = false
                autoTranslatePaused = false
                autoEmptyRounds = 0
                translatedTotal = 0
                translatedPageUrl = null
                activeTranslateSource = ""
                sourceLangCache.remove(startedUrl)
                uiLabelCache.clear()
                updateTranslateFab()
            }
            // 刷新/重开时先用 sessionStorage 译文缓存回填，避免闪回原文。
            if (startedUrl.startsWith("http")) {
                val restoreHost = runCatching { android.net.Uri.parse(startedUrl).host }
                    .getOrNull()?.lowercase()
                webView?.injectJavaScript(
                    TRANSLATE_CACHE_RESTORE_JS.replace(
                        "__DUAL__", if (effectiveTranslateDual(restoreHost)) "true" else "false"
                    )
                )
            }
            // 已翻译过的页面刷新/重开：标记待重译，等 onPageFinished 再执行。
            if (autoRetranslate) pendingAutoRetranslate[webView] = true
            else pendingAutoRetranslate.remove(webView)
            tabManager.tabs.firstOrNull { it.webView === webView }?.let {
                if (!(startedUrl == "about:blank" && errorPageLoading[webView] == true) && startedUrl.isNotBlank()) {
                    it.url = startedUrl
                    it.title = ""
                    it.security = when {
                        startedUrl.startsWith("http://") -> SiteSecurity.HTTP
                        startedUrl.startsWith("https://") -> SiteSecurity.SAFE
                        else -> SiteSecurity.SAFE
                    }
                    persistTabs()
                }
                if (webView === tabManager.currentWebView) {
                    // 从主页前进/后退到真实网站时，把原生主页面板收起来。
                    if (startedUrl.startsWith("http") &&
                        homeContainer?.visibility == View.VISIBLE
                    ) {
                        showWeb()
                    }
                    updateSiteInfoIcon()
                    if (addressInput?.hasFocus() == true) setAddressText(it.url)
                    else updateAddressBarForCurrent()
                    renderTabs()
                    hideFindBar()
                }
            }
            // 新页面开始：清掉上一页的网站主题色。
            webView?.let { themeColors[it] = null }
            if (webView === tabManager.currentWebView) applySiteThemeColor(null)
            // 新页面开始：清空该标签的嗅探结果（采集脚本在页面完成时注入一次）
            if (url.startsWith("http")) {
                webView?.let { com.sii.browser.core.webview.SniffStore.remove(it) }
            }
            webView?.readerMode = false
            gmBridges[webView]?.clearIfNavigated(url)
            passwordBridges[webView]?.let { bridge ->
                bridge.host = ""
                bridge.pageUrl = ""
            }
            adPickBridges[webView]?.let { bridge ->
                bridge.active = false
                bridge.host = ""
            }
            if (speaking) {
                tts?.stop()
                speaking = false
                showTtsBar(false)
            }
            com.sii.browser.script.GmApiBridge.clearMenuCommands()
            // 电脑模式：文档一开始就覆盖 viewport 宽度，避免先渲染手机布局再重排。
            if (isDesktopForUrl(url)) {
                webView?.injectJavaScript(VIEWPORT_DESKTOP_JS)
            }
            injectCustomFont(webView)
            // WebRTC 防泄露：文档开始时禁用 RTCPeerConnection
            val webrtcHost = runCatching { android.net.Uri.parse(url).host }.getOrNull()
            val webrtcOn = when (SiteConfig.tri(siteConfigFor(webrtcHost), "webrtc")) {
                1 -> true
                2 -> false
                else -> AppServices.settings.webrtcProtect()
            }
            if (webrtcOn) {
                webView?.injectJavaScript(WEBRTC_BLOCK_JS)
            }
            injectUserScripts(webView, url, "document-start")
            // 主题色识别尽早开始；脚本内部等待 DOM，ui 颜色不必等 onPageFinished。
            if (!AppServices.settings.isNightMode()) {
                webView?.injectJavaScript(com.sii.browser.core.webview.ThemeColorJsBridge.SCRIPT)
            }
            webView?.injectJavaScript(com.sii.browser.core.webview.FullscreenJsBridge.SCRIPT)
            // 暗色样式尽早注入；原生暗色开启时不再叠加 CSS。
            applyNightCss(webView)
            injectWebTextAccent(webView)
        }

        override fun onPageCommitVisible(webView: SiiWebView?, url: String) {
            if (webView === tabManager.currentWebView) hidePageLoadingCover()
        }

        override fun onPageFinished(webView: SiiWebView?, url: String) {
            if (webView === tabManager.currentWebView) hidePageLoadingCover()
            markPageFinished(webView)
            // 补注入抓包 hook：onPageStarted 的注入可能落在旧文档上，
            // 加载完成后补一次，确保后续 XHR/接口请求被记录（脚本自身幂等）。
            if (webView != null && AppServices.settings.mcpAllowCapture()) {
                webView.injectJavaScript(
                    com.sii.browser.core.webview.CaptureScript.js(
                        AppServices.settings.mcpCaptureBody()
                    )
                )
            }
            if (webView === tabManager.currentWebView) scheduleProgressFinish()
            if (webView != null && errorPageLoading[webView] == true) return
            if (webView != null && webView === tabManager.currentWebView &&
                handlePendingRender(webView, url)
            ) {
                return
            }
            // 后退到 about:home/about:blank 时切回主页面板（否则返回后主页空白）。
            // 新标签占位（url 为空）或已发起真实导航时，空白页回调不能把 UI 切回主页，
            // 否则 B 站新标签视频、微博首次加载会闪回主页。
            if (webView != null && webView === tabManager.currentWebView &&
                (url == "about:blank" || url == ABOUT_HOME)
            ) {
                val tab = tabManager.tabs.firstOrNull { it.webView === webView }
                if (tab != null &&
                    (tab.url == ABOUT_HOME || tab.url == "about:blank" || webView.canGoBack())
                ) {
                    tab.url = ABOUT_HOME
                    showHome()
                    return
                }
            }
            // 刷新/重开完成：自动重译（命中译文缓存，速度很快）。
            if (webView != null && pendingAutoRetranslate.remove(webView) == true) {
                com.sii.browser.core.webview.TranslationTrace.log(
                    requireContext(), "auto retranslate after load"
                )
                view?.postDelayed({
                    if (webView === tabManager.currentWebView && translateFabState == 0) {
                        translatePage()
                    }
                }, 200L)
            }
            // 读取网页 theme-color，并监听后续变化（夜间主题色不生效，不注入）。
            if (!AppServices.settings.isNightMode()) {
                webView?.injectJavaScript(com.sii.browser.core.webview.ThemeColorJsBridge.SCRIPT)
                // 已缓存的颜色直接应用，避免桥脚本在 SPA 中不重复上报导致设置延迟生效。
                val cached = webView?.let { themeColors[it] }
                if (cached != null && AppServices.settings.themeColorEnabled() &&
                    AppServices.settings.themeColorForceBackground()
                ) {
                    webView?.let { injectThemeBackground(it, cached) }
                }
            }
            webView?.injectJavaScript(com.sii.browser.core.webview.FullscreenJsBridge.SCRIPT)
            // 电脑模式：去掉页面 viewport 的缩放锁（user-scalable=no / maximum-scale），恢复双指缩放。
            if (isDesktopForUrl(url)) {
                webView?.injectJavaScript(VIEWPORT_DESKTOP_JS)
            }
            if (!AppServices.privacyManager.isIncognito ||
                !AppServices.settings.incognitoNoHistory()
            ) {
                val title = webView?.title
                val historyUrl = cleanUrl(url)
                lifecycleScope.launch {
                    AppServices.historyRepository.recordVisit(historyUrl, title)
                }
            }
            injectUserScripts(webView, url, "document-end")
            // document-idle：延迟注入，等页面脚本初始化完成
            view?.postDelayed({ injectUserScripts(webView, url, "document-idle") }, 800L)
            view?.postDelayed({ maybeShowTranslateFab(webView, url) }, 900L)
            // 仅夜间模式才给网页注入暗色 CSS；原生暗色开启时不再叠加。
            applyNightCss(webView)
            injectWebTextAccent(webView)
            if (!url.isNullOrEmpty() && url.contains("bilibili.com")) {
                webView?.injectJavaScript(BILIBILI_OVERLAY_JS)
            }
            if (siteClipboardBlocked(url)) webView?.injectJavaScript(CLIPBOARD_GUARD_JS)
            // 自定义 CSS（设置中的全局样式）
            val customCss = runCatching { AppServices.settings.customCss() }.getOrNull()
            if (!customCss.isNullOrBlank()) {
                webView?.injectJavaScript(
                    "(function(){if(document.getElementById('sii_custom_css'))return;" +
                        "var s=document.createElement('style');s.id='sii_custom_css';s.textContent=" +
                        org.json.JSONObject.quote(customCss) +
                        ";document.documentElement.appendChild(s);})();"
                )
            }
            // 密码库：记录域名、注入提交监听、按设置自动填充。
            webView?.let { applyPasswordFeatures(it, url) }
            injectCosmeticCss(webView, url)
            // 资源嗅探：页面加载完成后再次扫描（DOM/Resource Timing 此时已就绪）。
            if (AppServices.settings.sniffEnabled()) {
                webView?.injectJavaScript(com.sii.browser.core.webview.SniffJsBridge.SNIFF_JS)
            }
            // a[download] 链接不会被 DownloadListener 接管，这里统一代理
            webView?.downloadSecret?.let { webView.injectJavaScript(aDownloadJs(it)) }
            // 自动展开全文（按设置，含站点规则）。
            if (AppServices.settings.autoExpand()) {
                val host = runCatching { android.net.Uri.parse(url).host }.getOrNull()
                webView?.injectJavaScript(com.sii.browser.core.webview.ReaderMode.autoExpandJs(host))
            }
            updateNavigationState()
        }

        override fun onCreateWindow(
            webView: SiiWebView?,
            userGesture: Boolean,
            isDialog: Boolean,
            resultMsg: android.os.Message
        ): Boolean {
            val transport = resultMsg.obj as? android.webkit.WebView.WebViewTransport ?: return false
            // 新标签在发起导航前继承当前页的电脑模式（此时 current 已切到新标签，不能用 URL 判定）。
            val inheritDesktop = webView?.settings?.userAgentString == desktopUserAgent()
            // target=_blank / window.open：新建标签承载，下载类 URL 会继续走该 WebView 的下载监听。
            // 占位用空 url：真实地址由首次 onPageStarted 写入，避免地址栏闪现 about:home 与联想。
            val tab = tabManager.newTab("")
            if (inheritDesktop) tab.webView.settings.userAgentString = desktopUserAgent()
            transport.webView = tab.webView
            resultMsg.sendToTarget()
            showWeb()
            updateAddressBarForCurrent()
            // 移除旧 WebView 会把焦点递给地址栏，显式收回，避免弹出光标与建议列表。
            view?.post { addressInput?.clearFocus() }
            return true
        }

        override fun onGeolocationPermissionsShowPrompt(
            origin: String,
            callback: android.webkit.GeolocationPermissions.Callback
        ): Boolean {
            val host = runCatching { android.net.Uri.parse(origin).host }.getOrNull()?.lowercase()
            when (sitePermissionDecision(host, "geo")) {
                1, 3 -> callback.invoke(origin, true, false)
                2 -> callback.invoke(origin, false, false)
                else -> {
                    if (permissionDialogShowing) {
                        callback.invoke(origin, false, false)
                        return true
                    }
                    showPermissionDialog(
                        host.orEmpty(),
                        listOf(getString(R.string.site_rule_geo)),
                        onAllowOnce = { callback.invoke(origin, true, false) },
                        onAlways = {
                            persistSitePermission(host.orEmpty(), listOf("geo"))
                            callback.invoke(origin, true, false)
                        },
                        onDeny = { callback.invoke(origin, false, false) },
                        onAlwaysDeny = {
                            persistSitePermission(host.orEmpty(), listOf("geo"), 2)
                            callback.invoke(origin, false, false)
                        }
                    )
                }
            }
            return true
        }

        override fun onPermissionRequest(request: android.webkit.PermissionRequest): Boolean {
            val host = request.origin?.host?.lowercase()
            val resources = request.resources.toList()
            if (resources.isEmpty()) {
                request.deny()
                return true
            }
            val decisions = resources.associateWith { resource ->
                when (resource) {
                    android.webkit.PermissionRequest.RESOURCE_VIDEO_CAPTURE ->
                        sitePermissionDecision(host, "cam", "perm")
                    android.webkit.PermissionRequest.RESOURCE_AUDIO_CAPTURE ->
                        sitePermissionDecision(host, "mic", "perm")
                    else -> sitePermissionDecision(host, "perm")
                }
            }
            if (decisions.values.any { it == 2 }) {
                request.deny()
                return true
            }
            if (decisions.values.none { it == 0 || it == 4 }) {
                request.grant(resources.toTypedArray())
                return true
            }
            if (permissionDialogShowing) {
                request.deny()
                return true
            }
            val labels = resources.map { resourceLabel(it) }
            val alwaysKeys = resources.map { resource ->
                when (resource) {
                    android.webkit.PermissionRequest.RESOURCE_VIDEO_CAPTURE -> "cam"
                    android.webkit.PermissionRequest.RESOURCE_AUDIO_CAPTURE -> "mic"
                    else -> "perm"
                }
            }.distinct()
            showPermissionDialog(
                host.orEmpty(),
                labels,
                onAllowOnce = { request.grant(resources.toTypedArray()) },
                onAlways = {
                    persistSitePermission(host.orEmpty(), alwaysKeys)
                    request.grant(resources.toTypedArray())
                },
                onDeny = { request.deny() },
                onAlwaysDeny = {
                    persistSitePermission(host.orEmpty(), alwaysKeys, 2)
                    request.deny()
                }
            )
            return true
        }

        override fun onShowCustomView(view: View, callback: android.webkit.WebChromeClient.CustomViewCallback) {
            val container = fullscreenContainer
            if (container == null) {
                callback.onCustomViewHidden()
                return
            }
            container.removeAllViews()
            container.addView(view)
            // 全屏视频要盖住所有带 elevation 的浮层，否则底栏会压在播放器进度条上。
            container.elevation = dp(200).toFloat()
            container.visibility = View.VISIBLE
            setBrowserChromeVisible(false)
            setVideoFullscreenSystemBars(true)
            setupVideoGesture(container)
            activity?.requestedOrientation =
                android.content.pm.ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        }

        override fun onHideCustomView(): Boolean {
            fullscreenContainer?.removeAllViews()
            fullscreenContainer?.setOnTouchListener(null)
            fullscreenContainer?.visibility = View.GONE
            fullscreenContainer?.elevation = 0f
            setBrowserChromeVisible(true)
            setVideoFullscreenSystemBars(false)
            applyPageFullscreenChrome()
            (activity as? ShellActivity)?.applyOrientation()
            return true
        }

        override fun onShowFileChooser(
            webView: SiiWebView?,
            valueCallback: android.webkit.ValueCallback<Array<android.net.Uri>>,
            params: android.webkit.WebChromeClient.FileChooserParams
        ): Boolean {
            filePathCallback?.onReceiveValue(null)
            filePathCallback = valueCallback
            return try {
                fileChooserLauncher.launch(params.createIntent())
                true
            } catch (e: Exception) {
                filePathCallback = null
                false
            }
        }

        override fun onReceivedSslError(webView: SiiWebView?, handler: SslErrorHandler, error: SslError): Boolean {
            tabManager.tabs.firstOrNull { it.webView === webView }?.let { tab ->
                tab.security = SiteSecurity.CERT_ERROR
                if (webView === tabManager.currentWebView) updateSiteInfoIcon()
            }
            if (AppServices.settings.ignoreSslError()) {
                android.app.AlertDialog.Builder(requireContext())
                    .setTitle(R.string.ssl_error_title)
                    .setMessage(
                        getString(
                            R.string.ssl_error_message,
                            error.url.orEmpty(),
                            sslErrorText(error.primaryError)
                        ) + "\n\n" + getString(R.string.ssl_ignore_warning)
                    )
                    .setPositiveButton(R.string.ssl_continue) { _, _ -> handler.proceed() }
                    .setNegativeButton(android.R.string.cancel) { _, _ -> handler.cancel() }
                    .setOnCancelListener { handler.cancel() }
                    .show()
                return true
            }
            android.app.AlertDialog.Builder(requireContext())
                .setTitle(R.string.ssl_error_title)
                .setMessage(
                    getString(
                        R.string.ssl_error_message,
                        error.url.orEmpty(),
                        sslErrorText(error.primaryError)
                    )
                )
                .setPositiveButton(R.string.ssl_continue) { _, _ -> handler.proceed() }
                .setNegativeButton(android.R.string.cancel) { _, _ -> handler.cancel() }
                .setNeutralButton(R.string.ssl_view_cert) { _, _ -> showCertificate(error) }
                .setOnCancelListener { handler.cancel() }
                .show()
            return true
        }

        @androidx.annotation.RequiresApi(27)
        override fun onSafeBrowsingHit(
            webView: SiiWebView?,
            request: android.webkit.WebResourceRequest,
            threatType: Int,
            callback: android.webkit.SafeBrowsingResponse
        ): Boolean {
            tabManager.tabs.firstOrNull { it.webView === webView }?.let { tab ->
                tab.security = SiteSecurity.THREAT
                if (webView === tabManager.currentWebView) updateSiteInfoIcon()
            }
            android.app.AlertDialog.Builder(requireContext())
                .setTitle("不安全网站")
                .setMessage("此网站可能包含恶意软件、钓鱼内容或其他威胁。")
                .setPositiveButton("返回安全页面") { _, _ -> callback.backToSafety(true) }
                .setNegativeButton("继续访问") { _, _ -> callback.proceed(false) }
                .setOnCancelListener { callback.backToSafety(true) }
                .show()
            return true
        }

        override fun onReceivedErrorMainFrame(webView: SiiWebView?, errorCode: Int, description: String?, url: String) {
            if (webView != null && webView === tabManager.currentWebView) {
                // 错误页自身再报错时不再递归加载。
                if (errorPageLoading[webView] == true) return
                hidePageLoadingCover()
                markPageFinished(webView)
                finishProgressBar()
                errorPageLoading[webView] = true
                if (url.startsWith("http")) errorPageUrl[webView] = url
                val wv = webView
                val html = errorPageHtml(url, description)
                // 错误回调内同步导航会重入 WebView，post 到下一帧再加载。
                wv.post {
                    if (wv === tabManager.currentWebView) {
                        wv.loadDataWithBaseURL(null, html, "text/html", "utf-8", null)
                    }
                }
            }
        }

        override fun onRenderProcessGone(
            webView: SiiWebView?,
            detail: android.webkit.RenderProcessGoneDetail?
        ): Boolean {
            val crashed = webView ?: return true
            com.sii.browser.core.privacy.CrashLog.record(
                "Render",
                "页面渲染进程崩溃",
                detail?.toString()
            )
            val index = tabManager.tabs.indexOfFirst { it.webView === crashed }
            if (index >= 0) tabManager.closeTab(index)
            if (view != null) {
                if (tabManager.size == 0) {
                    tabManager.newTab(ABOUT_HOME)
                    showHome()
                } else if (tabManager.current?.url == ABOUT_HOME) {
                    showHome()
                } else {
                    showWeb()
                }
            }
            context?.let {
                android.widget.Toast.makeText(
                    it, R.string.render_process_crashed, android.widget.Toast.LENGTH_SHORT
                ).show()
            }
            return true
        }
    }

    /** SSL 错误码 → 文案（SslError 未提供公开的字符串映射）。 */
    private fun sslErrorText(code: Int): String = when (code) {
        SslError.SSL_NOTYETVALID -> getString(R.string.ssl_err_not_yet_valid)
        SslError.SSL_EXPIRED -> getString(R.string.ssl_err_expired)
        SslError.SSL_IDMISMATCH -> getString(R.string.ssl_err_id_mismatch)
        SslError.SSL_UNTRUSTED -> getString(R.string.ssl_err_untrusted)
        SslError.SSL_DATE_INVALID -> getString(R.string.ssl_err_date_invalid)
        SslError.SSL_INVALID -> getString(R.string.ssl_err_invalid)
        else -> getString(R.string.ssl_err_unknown)
    }

    /** 注入元素隐藏规则（cosmetic）生成的 CSS。 */
    private fun injectCosmeticCss(webView: SiiWebView?, url: String) {
        val host = runCatching { android.net.Uri.parse(url).host }.getOrNull()
        val css = AppServices.adBlock.cosmeticCssFor(host)
        if (css.isEmpty() || webView == null) return
        val escaped = css.replace("\\", "\\\\").replace("'", "\\'")
        webView.evaluateJavascript(
            "(function(){var s=document.getElementById('sii_cosmetic');" +
                "if(!s){s=document.createElement('style');s.id='sii_cosmetic';" +
                "(document.head||document.documentElement).appendChild(s);}" +
                "s.textContent='$escaped';})();",
            null
        )
    }

    /** 展示证书信息。 */
    private fun showCertificate(error: SslError) {
        val cert = error.certificate
        val text = if (cert == null) {
            getString(R.string.ssl_cert_unavailable)
        } else {
            listOf(
                getString(R.string.ssl_cert_issued_to) + ": " + cert.issuedTo?.toString().orEmpty(),
                getString(R.string.ssl_cert_issued_by) + ": " + cert.issuedBy?.toString().orEmpty(),
                getString(R.string.ssl_cert_valid_from) + ": " + cert.validNotBefore.orEmpty(),
                getString(R.string.ssl_cert_valid_to) + ": " + cert.validNotAfter.orEmpty()
            ).joinToString("\n")
        }
        android.app.AlertDialog.Builder(requireContext())
            .setTitle(R.string.ssl_view_cert)
            .setMessage(text)
            .setPositiveButton(android.R.string.ok, null)
            .show()
    }

    /** 主页 WebView → 原生：加载 URL（UiActions）。 */
    override fun loadInBrowser(url: String) {
        loadUrlFor(url)
    }

    /** 注入匹配当前 URL 的用户脚本及 GM_* 包装。 */
    private fun injectUserScripts(webView: SiiWebView?, url: String, runAt: String) {
        if (webView == null) return
        lifecycleScope.launch {
            val scripts = AppServices.scriptRepository.scriptsFor(url, runAt)
            val bridge = gmBridges[webView]
            val parsedScripts = scripts.map { com.sii.browser.script.UserScript.parse(it) }
            val grants = parsedScripts.flatMap { it.grants }.toSet()
            if (scripts.isNotEmpty()) {
                bridge?.allowFor(url, grants)
            } else if (runAt == "document-start") {
                bridge?.allowed = false
                bridge?.allowedUrl = null
            }
            if (scripts.isEmpty()) return@launch
            if (runAt == "document-end" && grants.isNotEmpty()) {
                webView.injectJavaScript(GmApiBridge.GM_WRAPPER_JS)
            }
            parsedScripts.forEach { parsed ->
                val prefix = parsed.requires.mapNotNull { requireUrl ->
                    com.sii.browser.script.ScriptRequireCache.cached(requireContext(), requireUrl)
                }.joinToString("\n")
                parsed.requires.forEach {
                    com.sii.browser.script.ScriptRequireCache.ensure(requireContext(), it)
                }
                webView.injectJavaScript(if (prefix.isBlank()) parsed.code else prefix + "\n" + parsed.code)
            }
        }
    }

    /**
     * 按站点规则应用 WebView 设置：换站或配置变化时先恢复全局基线，
     * 再只应用显式覆盖项，避免站点设置泄漏到其他网站。
     */
    private fun applySiteSettings(webView: SiiWebView?, url: String) {
        val wv = webView ?: return
        // 全局网站限制每次应用都生效，不依赖站点签名变化（设置修改后无需重建页面）。
        val globalSettings = AppServices.settings
        wv.applyNativeDarkMode(
            globalSettings.isNightMode() && globalSettings.webViewDark()
        )
        wv.settings.javaScriptEnabled = !globalSettings.siteBlockJs()
        wv.settings.javaScriptCanOpenWindowsAutomatically = !globalSettings.siteBlockPopup()
        wv.settings.domStorageEnabled = !globalSettings.siteBlockDom()
        wv.settings.databaseEnabled = !globalSettings.siteBlockDb()
        wv.settings.allowFileAccess = !globalSettings.siteBlockFile()
        wv.settings.mixedContentMode = if (globalSettings.privacyHttpsOnly()) {
            android.webkit.WebSettings.MIXED_CONTENT_NEVER_ALLOW
        } else {
            android.webkit.WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
        }
        wv.settings.safeBrowsingEnabled = globalSettings.safeBrowsing()
        val host = android.net.Uri.parse(url).host?.lowercase()
        val raw = host?.let { AppServices.siteConfigRepository.cachedConfig(it) }
        val config = SiteConfig.parse(raw)

        val previousHost = wv.siteConfigHost
        if (previousHost != null && previousHost != host) {
            val previous = SiteConfig.parse(AppServices.siteConfigRepository.cachedConfig(previousHost))
            if (SiteConfig.enabled(previous) && SiteConfig.tri(previous, "incog") == 1) {
                clearIncognitoSiteData(previousHost)
            }
        }
        // 签名用归一化 host（m./www. 等价）并纳入 UA/宽视口终态：
        // 双域站（如 B 站 m/www）跳转时签名保持稳定，避免每条导航回调都重设 UA（曾致主线程 SIGTRAP）。
        val targetUa = targetUserAgent(host)
        val signature = signatureOf(host, raw, targetUa, true)
        if (wv.siteConfigSignature != signature) {
            wv.siteConfigHost = host
            resetSiteWebSettings(wv, host)
        } else {
            wv.siteConfigHost = host
        }
        if (config == null || !SiteConfig.enabled(config)) {
            // 无站点规则的站：重置到全局后同样落签名，避免回调风暴反复 reset。
            wv.siteConfigSignature = signatureOf(host, raw, targetUa, true)
            return
        }

        val s = wv.settings
        SiteConfig.tri(config, "img")?.let { s.loadsImagesAutomatically = it == 1 }
        SiteConfig.tri(config, "js")?.let { s.javaScriptEnabled = it == 1 }
        SiteConfig.tri(config, "pop")?.let { s.javaScriptCanOpenWindowsAutomatically = it == 1 }
        SiteConfig.intOpt(config, "fs")?.let { s.textZoom = it }
        val backNoreload = SiteConfig.tri(config, "backnr") ?: if (AppServices.settings.siteBackNoReload()) 1 else 0
        s.cacheMode = if (backNoreload == 1) android.webkit.WebSettings.LOAD_CACHE_ELSE_NETWORK
        else android.webkit.WebSettings.LOAD_DEFAULT
        SiteConfig.tri(config, "dom")?.let { s.domStorageEnabled = it == 1 }
        SiteConfig.tri(config, "db")?.let { s.databaseEnabled = it == 1 }
        SiteConfig.tri(config, "file")?.let { s.allowFileAccess = it == 1 }
        when (SiteConfig.tri(config, "safebrowsing")) {
            1 -> s.safeBrowsingEnabled = true
            2 -> s.safeBrowsingEnabled = false
        }
        val cookieBlock3p = when (SiteConfig.tri(config, "3p")) {
            1 -> false
            2 -> true
            else -> AppServices.settings.thirdPartyCookiesBlocked() ||
                AppServices.settings.privacyCookies()
        }
        android.webkit.CookieManager.getInstance()
            .setAcceptThirdPartyCookies(wv, !cookieBlock3p)
        // 用稳定后的最终状态落签名：下条回调起签名恒定，不再触发 reset/UA 重设。
        wv.siteConfigSignature = signatureOf(host, raw, targetUa, true)
    }

    /** 站点设置签名：归一化 host + 配置原文 + 夜间/隐身 + UA/宽视口终态。 */
    private fun signatureOf(
        host: String?,
        raw: String?,
        userAgent: String,
        wideViewport: Boolean
    ): String = listOf(
        stripHostPrefix(host.orEmpty()),
        raw.orEmpty(),
        AppServices.settings.isNightMode().toString(),
        AppServices.privacyManager.isIncognito.toString(),
        userAgent,
        wideViewport.toString()
    ).joinToString("|")

    private fun resetSiteWebSettings(wv: SiiWebView, host: String?) {
        val app = AppServices.settings
        val s = wv.settings
        // 电脑模式判定：全局开关 > 默认电脑模式网站列表 > 全局手机 UA。
        // host 用本次导航的实参（siteConfigHost 可能尚未赋值）。
        val desktop = desktopForHost(host)
        val targetUa = if (desktop) desktopUserAgent() else globalUserAgent()
        // 导航回调栈内同步调用 setUserAgentString 会触发 Chromium 断言（SIGTRAP），
        // 延后到当前消息之后执行；值不变则不写。
        if (s.userAgentString != targetUa) {
            wv.post {
                if (wv.settings.userAgentString != targetUa) {
                    wv.settings.userAgentString = targetUa
                }
            }
        }
        // 宽视口恒开：切 UA 不切视口，页面 viewport meta 自行声明。
        s.useWideViewPort = true
        s.loadWithOverviewMode = true
        s.textZoom = effectiveTextZoom()
        s.loadsImagesAutomatically = !noImageEnabled() &&
            !app.siteNoImage() &&
            !(app.wifiImagesOnly() && isMeteredNetwork())
        s.javaScriptEnabled = !app.siteBlockJs()
        s.javaScriptCanOpenWindowsAutomatically = !app.siteBlockPopup()
        s.domStorageEnabled = !app.siteBlockDom()
        s.databaseEnabled = !app.siteBlockDb()
        s.allowFileAccess = !app.siteBlockFile()
        s.mixedContentMode = if (AppServices.settings.privacyHttpsOnly()) {
            android.webkit.WebSettings.MIXED_CONTENT_NEVER_ALLOW
        } else {
            android.webkit.WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
        }
        // 电脑模式下 m./mobile. 站不走缓存，服务器才能按桌面 UA 302 到电脑版。
        val mobileHost = host?.lowercase()?.let { it.startsWith("m.") || it.startsWith("mobile.") } == true
        val targetCache = if (desktop && mobileHost) android.webkit.WebSettings.LOAD_NO_CACHE
        else android.webkit.WebSettings.LOAD_DEFAULT
        if (s.cacheMode != targetCache) s.cacheMode = targetCache
    }

    /** 当前 host 应使用的 UA（电脑模式判定集中在此，供签名与设置共用）。 */
    private fun targetUserAgent(host: String?): String {
        if (desktopForHost(host)) return desktopUserAgent()
        val base = globalUserAgent()
        // 必应对完整移动 UA 会做一次页面内跳转；仅对 Bing 使用简化 UA，
        // 不改变用户全局的“简化浏览器标识”选择。
        if (!AppServices.settings.uaSimplified() &&
            host?.lowercase()?.let { it == "bing.com" || it.endsWith(".bing.com") } == true
        ) {
            return simplifyUserAgent(base)
        }
        return base
    }

    /** 手机专用站 → 电脑版域名的映射（如微博 m 站）。 */
    private val desktopHostAliases = mapOf("m.weibo.cn" to "weibo.com")

    /** 由映射进入的电脑版 host，会话内继续按电脑模式处理。 */
    private val desktopAliasHosts = mutableSetOf<String>()

    /** 会话级「仅关闭一次」：归一化 host，不写设置。 */
    private val temporaryDesktopOff = mutableSetOf<String>()

    /** 正在等待加载的电脑版 host（映射后置位，到达即清）。 */
    private var aliasRedirectPending: String? = null

    /** 已映射过的手机站 host，避免微博跳回 m 站后反复映射造成重载循环。 */
    private val aliasMappedTargets = mutableSetOf<String>()

    /** 电脑模式下把手机专用站换成电脑版域名；当前已在电脑版域时不再换（避免循环）。 */
    private fun desktopAliasUrl(url: String, currentHost: String?): String? {
        val uri = runCatching { android.net.Uri.parse(url) }.getOrNull() ?: return null
        val host = uri.host?.lowercase() ?: return null
        val alias = desktopHostAliases[host] ?: return null
        if (currentHost?.lowercase() == alias) return null
        desktopAliasHosts.add(alias)
        return uri.buildUpon().authority(alias).build().toString()
    }

    private fun globalUserAgent(): String {
        val app = AppServices.settings
        val base = app.userAgent() ?: DEFAULT_MOBILE_UA
        return if (app.uaSimplified()) simplifyUserAgent(base) else base
    }

    /** host 是否在「默认电脑模式网站列表」内且未被关闭（归一化 m/www）。 */
    private fun inDesktopSites(host: String?): Boolean {
        if (host.isNullOrBlank()) return false
        val normalized = stripHostPrefix(host.lowercase())
        if (normalized in disabledDesktopHosts()) return false
        return desktopSiteEntries().any { stripHostPrefix(it) == normalized }
    }

    /** 默认电脑模式网站条目（去空、去协议与尾斜杠）。 */
    private fun desktopSiteEntries(): List<String> =
        AppServices.settings.desktopSites()
            .split(',', '，', ';', '；', ' ', '\n')
            .map { it.trim().lowercase().removePrefix("http://").removePrefix("https://").removeSuffix("/") }
            .filter { it.isNotBlank() }

    /** 被关闭的站点（归一化 host，m/www 等价）。 */
    private fun disabledDesktopHosts(): List<String> =
        AppServices.settings.desktopSitesDisabled()
            .split(',', '，', ';', '；', ' ', '\n')
            .map { it.trim().lowercase().removePrefix("http://").removePrefix("https://").removeSuffix("/") }
            .filter { it.isNotBlank() }
            .map { stripHostPrefix(it) }

    private fun removeFromDesktopSites(host: String) {
        val normalized = stripHostPrefix(host.lowercase())
        val kept = desktopSiteEntries().filter { stripHostPrefix(it) != normalized }
        AppServices.settings.setDesktopSites(kept.joinToString(","))
        AppServices.settings.setDesktopSitesDisabled(
            (disabledDesktopHosts() - normalized).joinToString(",")
        )
    }

    private fun desktopUserAgent(): String =
        AppServices.settings.desktopUserAgent() ?: DESKTOP_UA

    private fun siteConfigFor(host: String?): JSONObject? =
        SiteConfig.parse(host?.let { AppServices.siteConfigRepository.cachedConfig(it) })

    private fun currentPageHost(): String? = runCatching {
        android.net.Uri.parse(tabManager.current?.url).host?.lowercase()
    }.getOrNull()

    /** 站点权限值：0 跟随全局、1 允许、2 拒绝、3 始终允许。 */
    private fun sitePermissionDecision(
        host: String?,
        key: String,
        legacyKey: String? = null
    ): Int {
        val config = siteConfigFor(host)
        val site = if (SiteConfig.enabled(config)) {
            SiteConfig.permission(config, key, legacyKey)
        } else 0
        if (site != 0) return site
        if (AppServices.privacyManager.isIncognito) {
            val deny = when (key) {
                "geo" -> AppServices.settings.incognitoNoGeo()
                "cam" -> AppServices.settings.incognitoDenyCam()
                "mic" -> AppServices.settings.incognitoDenyMic()
                else -> false
            }
            if (deny) return 2
        }
        return when (key) {
            "geo" -> AppServices.settings.permissionGeo()
            "cam" -> AppServices.settings.permissionCam()
            "mic" -> AppServices.settings.permissionMic()
            else -> 0
        }
    }

    private fun sitePermissionAllowed(
        host: String?,
        key: String,
        legacyKey: String? = null
    ): Boolean = sitePermissionDecision(host, key, legacyKey) != 2

    private fun resourceLabel(resource: String): String = when (resource) {
        android.webkit.PermissionRequest.RESOURCE_VIDEO_CAPTURE ->
            getString(R.string.site_rule_cam)
        android.webkit.PermissionRequest.RESOURCE_AUDIO_CAPTURE ->
            getString(R.string.site_rule_mic)
        else -> getString(R.string.site_permission_generic)
    }

    /** 首次请求时询问：允许一次 / 始终允许 / 拒绝 / 始终拒绝。 */
    private fun showPermissionDialog(
        host: String,
        labels: List<String>,
        onAllowOnce: () -> Unit,
        onAlways: () -> Unit,
        onDeny: () -> Unit,
        onAlwaysDeny: () -> Unit
    ) {
        if (!isAdded) {
            onDeny()
            return
        }
        permissionDialogShowing = true
        var handled = false
        fun finish(action: () -> Unit) {
            if (handled) return
            handled = true
            permissionDialogShowing = false
            action()
        }
        val actions = arrayOf(onAllowOnce, onAlways, onDeny, onAlwaysDeny)
        val dialog = dialogWithOptions(
            R.string.permission_request_title,
            getString(
                R.string.permission_request_message,
                host.ifEmpty { "-" },
                labels.distinct().joinToString("、")
            ),
            arrayOf(
                getString(R.string.permission_allow_once),
                getString(R.string.site_always_allow),
                getString(R.string.site_deny),
                getString(R.string.site_always_deny)
            )
        ) { which -> finish(actions[which]) }
        dialog.setOnDismissListener {
            if (!handled) finish(onDeny)
        }
        dialog.show()
    }

    /**
     * 说明文字 + 选项列表的弹窗。
     * AlertDialog 同时设置 message 与 items 时只显示 message，列表会被隐去，因此用自定义视图。
     */
    private fun dialogWithOptions(
        titleRes: Int,
        message: String,
        labels: Array<String>,
        onPick: (Int) -> Unit
    ): android.app.AlertDialog {
        val context = requireContext()
        val accent = dialogThemeColor(android.R.attr.colorAccent)
        val primary = dialogThemeColor(android.R.attr.textColorPrimary)
        val column = android.widget.LinearLayout(context).apply {
            orientation = android.widget.LinearLayout.VERTICAL
        }
        column.addView(TextView(context).apply {
            text = message
            setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 14f)
            setTextColor(primary)
            setLineSpacing(0f, 1.3f)
            setPadding(dpToPx(24), 0, dpToPx(24), dpToPx(6))
        })
        val dialog = android.app.AlertDialog.Builder(context)
            .setTitle(titleRes)
            .setView(column)
            .create()
        labels.forEachIndexed { index, label ->
            column.addView(TextView(context).apply {
                text = label
                setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 15f)
                setTextColor(accent)
                gravity = android.view.Gravity.CENTER_VERTICAL
                setPadding(dpToPx(24), dpToPx(14), dpToPx(24), dpToPx(14))
                isClickable = true
                setOnClickListener {
                    dialog.dismiss()
                    onPick(index)
                }
            })
        }
        return dialog
    }

    /** 按当前对话框主题取颜色（说明文字 / 选项强调色）。 */
    private fun dialogThemeColor(attr: Int): Int {
        val value = android.util.TypedValue()
        requireContext().theme.resolveAttribute(attr, value, true)
        return if (value.resourceId != 0) {
            androidx.core.content.ContextCompat.getColor(requireContext(), value.resourceId)
        } else value.data
    }

    private fun persistSitePermission(host: String, keys: List<String>, value: Int = 3) {
        if (host.isBlank()) return
        val config = SiteConfig.parse(AppServices.siteConfigRepository.cachedConfig(host))
            ?: JSONObject()
        config.put("on", true)
        keys.forEach { config.put(it, value) }
        val text = config.toString()
        AppServices.siteConfigRepository.cachePut(host, text)
        AppServices.refreshAdBlockWhitelist()
        lifecycleScope.launch {
            withContext(Dispatchers.IO) {
                AppServices.siteConfigRepository.put(host, text)
            }
        }
    }

    private fun siteClipboardBlocked(url: String): Boolean {
        val host = runCatching { android.net.Uri.parse(url).host?.lowercase() }.getOrNull()
        val config = siteConfigFor(host)
        if (!SiteConfig.enabled(config)) return AppServices.settings.clipboardGuard()
        return when (SiteConfig.permission(config, "clip")) {
            1, 3 -> false
            2 -> true
            else -> AppServices.settings.clipboardGuard()
        }
    }

    /** 站点级隐身：离开站点时清理该域的 Cookie 与 WebStorage。 */
    private fun clearIncognitoSiteData(host: String) {
        val cookieManager = android.webkit.CookieManager.getInstance()
        listOf("https://$host", "http://$host").forEach { origin ->
            val raw = runCatching { cookieManager.getCookie(origin) }.getOrNull().orEmpty()
            raw.split(";").map { it.trim() }.filter { it.contains("=") }.forEach { pair ->
                val name = pair.substringBefore("=")
                runCatching {
                    cookieManager.setCookie(origin, "$name=; Max-Age=0; path=/")
                }
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                runCatching { android.webkit.WebStorage.getInstance().deleteOrigin(origin) }
            }
        }
        cookieManager.flush()
    }

    /** URL 加载决策：当前模块放行（后续模块处理新标签/外部应用）。 */
    private inner class UrlDecision : UrlLoadDecision {
        override fun shouldLoad(
            webView: SiiWebView?,
            request: android.webkit.WebResourceRequest?,
            url: String,
            action: Int
        ): Boolean {
            applySiteSettings(webView, url)
            val recentlyProgrammatic =
                android.os.SystemClock.elapsedRealtime() - programmaticPrivacyLoadAt < 1200L
            if (recentlyProgrammatic) return false
            val mainFrame = request == null || request.isForMainFrame
            if (mainFrame && webView != null &&
                (url.startsWith("http://") || url.startsWith("https://"))
            ) {
                val isRedirect = request?.isRedirect == true
                val cleaned = if (isRedirect) url else cleanUrl(url)
                val target = if (!isRedirect && httpsOnlyEnabled()) {
                    upgradeToHttps(cleaned)
                } else cleaned
                val headers = privacyRequestHeaders(target)
                val needHeaderLoad = request != null && !isRedirect && headers.isNotEmpty()
                if (target != url || needHeaderLoad) {
                    if (privacyHeaderLoadUrl == target) {
                        privacyHeaderLoadUrl = null
                        return false
                    } else {
                        privacyHeaderLoadUrl = target
                        view?.postDelayed({
                            if (privacyHeaderLoadUrl == target) privacyHeaderLoadUrl = null
                        }, 1000L)
                        webView.post {
                            if (headers.isEmpty()) webView.loadUrl(target)
                            else webView.loadUrl(target, headers)
                        }
                        return true
                    }
                }
            }
            // 电脑模式：手机专用站直接换成电脑版域名（当前已在电脑版域时不换，避免循环）。
            if (isDesktopForUrl(url)) {
                val currentHost = runCatching { android.net.Uri.parse(webView?.url).host }
                    .getOrNull()?.lowercase()
                val targetHost = runCatching { android.net.Uri.parse(url).host }
                    .getOrNull()?.lowercase()
                if (targetHost != null) {
                    if (targetHost == aliasRedirectPending) {
                        // 已到达电脑版域，解除等待。
                        aliasRedirectPending = null
                    } else if (targetHost in aliasMappedTargets) {
                        // 刚映射到电脑版又跳回手机站：拦下，否则会无限重载。
                        return true
                    }
                }
                // 已在电脑版域（如 weibo.com）时，拦下它主动跳回手机站（jumpfrom=weibocom）。
                if (currentHost != null && targetHost != null &&
                    desktopHostAliases[targetHost] == currentHost
                ) {
                    return true
                }
                val alias = desktopAliasUrl(url, currentHost)
                if (alias != null) {
                    aliasRedirectPending = runCatching { android.net.Uri.parse(alias).host }
                        .getOrNull()?.lowercase()
                    targetHost?.let { aliasMappedTargets.add(it) }
                    webView?.let { wv -> wv.post { wv.loadUrl(alias) } }
                    return true
                }
            }
            // 电脑模式站（全局/默认列表）：首个导航前 UA 未落定时拦截一次，
            // 待 UA 设置后重新加载，避免用手机 UA 请求导致手机版或打不开。
            if ((request == null || request.isForMainFrame) && webView != null &&
                (url.startsWith("http://") || url.startsWith("https://"))
            ) {
                val host = runCatching { android.net.Uri.parse(url).host }.getOrNull()
                val target = targetUserAgent(host)
                if (webView.settings.userAgentString != target) {
                    webView.post {
                        if (webView.settings.userAgentString != target) {
                            webView.settings.userAgentString = target
                        }
                        webView.loadUrl(url)
                    }
                    return true
                }
            }
            if (request?.isRedirect == true) {
                if (allowRedirectOnce == url) {
                    allowRedirectOnce = null
                } else {
                    val host = currentPageHost()
                    val siteRedirect = SiteConfig.permission(
                        siteConfigFor(host), "redirect"
                    )
                    val policy = when (siteRedirect) {
                        1, 3 -> 1
                        2 -> 2
                        4 -> 0
                        else -> AppServices.settings.siteRedirectPolicy()
                    }
                    when (policy) {
                        1 -> Unit
                        2 -> return true
                        else -> {
                            if (permissionDialogShowing) return true
                            showPermissionDialog(
                                host.orEmpty(),
                                listOf(getString(R.string.site_rule_redirect)),
                                onAllowOnce = {
                                    allowRedirectOnce = url
                                    tabManager.currentWebView?.loadUrl(url)
                                },
                                onAlways = {
                                    persistSitePermission(host.orEmpty(), listOf("redirect"))
                                    allowRedirectOnce = url
                                    tabManager.currentWebView?.loadUrl(url)
                                },
                                onDeny = {},
                                onAlwaysDeny = {
                                    persistSitePermission(host.orEmpty(), listOf("redirect"), 2)
                                }
                            )
                            return true
                        }
                    }
                }
            }
            if ((request == null || request.isForMainFrame) && isUserScriptUrl(url)) {
                installUserScript(url)
                return true
            }
            // 当前标签内的主框架导航：立即显示进度条与叉号，不等 onPageStarted。
            if ((request == null || request.isForMainFrame) &&
                webView != null && webView === tabManager.currentWebView &&
                (url.startsWith("http://") || url.startsWith("https://")) &&
                !handleExternalScheme(url)
            ) {
                tabManager.current?.let { it.url = url }
                markPageLoading(webView)
                updateSiteInfoIcon()
                updateAddressBarForCurrent()
            }
            return handleExternalScheme(url)
        }
    }

    /** 非 http(s) 地址：已知深链映射为网页地址，其余交给系统 App；不再丢给 WebView 报错。 */
    private fun handleExternalScheme(url: String): Boolean {
        val uri = runCatching { android.net.Uri.parse(url) }.getOrNull() ?: return false
        val scheme = uri.scheme?.lowercase() ?: return false
        if (scheme in setOf("http", "https", "about", "file", "blob", "data", "javascript")) {
            return false
        }
        mappedWebUrl(scheme, uri)?.let {
            loadUrlFor(it)
            return true
        }
        val host = currentPageHost()
        val intent = buildExternalIntent(url, uri, scheme)
        val fallback = externalFallback(intent, uri)
        when (sitePermissionDecision(host, "openapp")) {
            1, 3 -> {
                if (intent == null) denyExternal(uri, null) else startExternal(intent, fallback)
            }
            2 -> denyExternal(uri, intent)
            4 -> {
                if (intent == null) {
                    denyExternal(uri, null)
                } else {
                    showOpenAppDialog(intent, host, fallback) { denyExternal(uri, intent) }
                }
            }
            else -> when (AppServices.settings.openAppPolicy()) {
                1 -> {
                    if (intent == null) denyExternal(uri, null) else startExternal(intent, fallback)
                }
                2 -> denyExternal(uri, intent)
                else -> {
                    if (intent == null) {
                        denyExternal(uri, null)
                    } else {
                        showOpenAppDialog(intent, host, fallback) { denyExternal(uri, intent) }
                    }
                }
            }
        }
        return true
    }

    private fun buildExternalIntent(
        url: String,
        uri: android.net.Uri,
        scheme: String
    ): android.content.Intent? = runCatching {
        if (scheme == "intent") {
            val parsed = android.content.Intent.parseUri(
                url, android.content.Intent.URI_INTENT_SCHEME
            )
            // 不允许网页指定具体组件，保留 package 以维持正常 App 跳转。
            parsed.component = null
            parsed.selector = null
            parsed
        } else {
            android.content.Intent(android.content.Intent.ACTION_VIEW, uri)
        }
    }.getOrNull()

    private fun externalFallback(
        intent: android.content.Intent?,
        uri: android.net.Uri
    ): String? {
        val fallback = intent?.getStringExtra("browser_fallback_url")
            ?: uri.getQueryParameter("browser_fallback_url")
        return fallback?.takeIf { it.startsWith("http://") || it.startsWith("https://") }
    }

    /** 拒绝打开应用时优先回到网页回退地址，避免页面停在深链上无法继续。 */
    private fun denyExternal(uri: android.net.Uri, intent: android.content.Intent?) {
        val fallback = externalFallback(intent, uri)
        if (fallback != null) {
            loadUrlFor(fallback)
        } else {
            Toast.makeText(requireContext(), R.string.open_app_blocked, Toast.LENGTH_SHORT).show()
        }
    }

    private fun startExternal(intent: android.content.Intent, fallback: String? = null) {
        val started = runCatching {
            intent.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
            requireContext().startActivity(intent)
        }
        if (started.isFailure) {
            if (fallback != null) {
                loadUrlFor(fallback)
            } else {
                Toast.makeText(requireContext(), R.string.open_app_failed, Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun showOpenAppDialog(
        intent: android.content.Intent,
        host: String?,
        fallback: String?,
        onDeny: () -> Unit
    ) {
        if (!isAdded) return
        val actions = arrayOf<() -> Unit>(
            { startExternal(intent, fallback) },
            {
                if (!host.isNullOrBlank()) persistSitePermission(host, listOf("openapp"))
                startExternal(intent, fallback)
            },
            onDeny,
            {
                if (!host.isNullOrBlank()) persistSitePermission(host, listOf("openapp"), 2)
                onDeny()
            }
        )
        dialogWithOptions(
            R.string.open_app_title,
            getString(
                R.string.open_app_message,
                host ?: intent.data?.scheme ?: ""
            ),
            arrayOf(
                getString(R.string.open_app_allow),
                getString(R.string.open_app_always),
                getString(R.string.open_app_deny),
                getString(R.string.site_always_deny)
            )
        ) { which -> actions[which]() }.show()
    }

    /** 是否为 UserScript 安装地址（.user.js）。 */
    private fun isUserScriptUrl(url: String?): Boolean {
        val path = runCatching { android.net.Uri.parse(url).path }.getOrNull().orEmpty()
        return path.lowercase().endsWith(".user.js")
    }

    /** 抓取 .user.js 内容并弹出安装确认，确认后写入脚本库。 */
    private fun installUserScript(url: String) {
        if (!isAdded) return
        synchronized(installingScripts) {
            if (!installingScripts.add(url)) return
        }
        Toast.makeText(requireContext(), R.string.scripts_install_fetching, Toast.LENGTH_SHORT).show()
        lifecycleScope.launch {
            val code = withContext(Dispatchers.IO) { fetchUserScript(url) }
            synchronized(installingScripts) { installingScripts.remove(url) }
            if (code.isNullOrBlank()) {
                if (isAdded) {
                    Toast.makeText(requireContext(), R.string.scripts_install_failed, Toast.LENGTH_SHORT).show()
                }
                return@launch
            }
            if (!isAdded) return@launch
            val parsed = UserScript.parse(code)
            val matchText = parsed.matches.take(5).joinToString("\n").ifEmpty { "*" }
            val message = buildString {
                append(getString(R.string.scripts_install_name, parsed.name))
                append("\n")
                append(getString(R.string.scripts_install_author, parsed.author ?: "-"))
                append("\n")
                append(getString(R.string.scripts_install_match, matchText))
                append("\n")
                append(getString(R.string.scripts_install_source, url))
            }
            android.app.AlertDialog.Builder(requireContext())
                .setTitle(R.string.scripts_install_title)
                .setMessage(message)
                .setPositiveButton(R.string.scripts_install_confirm) { _, _ ->
                    lifecycleScope.launch {
                        runCatching { AppServices.scriptRepository.add(code, url) }
                        Toast.makeText(requireContext(), R.string.scripts_installed, Toast.LENGTH_SHORT).show()
                    }
                }
                .setNegativeButton(android.R.string.cancel, null)
                .show()
        }
    }

    private fun fetchUserScript(url: String): String? = runCatching {
        val request = Request.Builder().url(url).build()
        scriptInstallClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) return@use null
            response.body?.string()
        }
    }.getOrNull()

    /** 常见 App 深链 → 可访问的网页地址。 */
    private fun mappedWebUrl(scheme: String, uri: android.net.Uri): String? {
        val segments = uri.pathSegments.orEmpty()
        return when {
            scheme == "zhihu" && segments.size >= 2 && segments[0] == "articles" ->
                "https://zhuanlan.zhihu.com/p/${segments[1]}"
            scheme == "zhihu" && segments.size >= 2 && segments[0] == "questions" ->
                "https://www.zhihu.com/question/${segments[1]}"
            scheme == "zhihu" && segments.size >= 2 && segments[0] == "answers" ->
                "https://www.zhihu.com/answer/${segments[1]}"
            else -> null
        }
    }

    /** 隐私相关设置快照：从设置页返回时据此判断是否需要重新应用并刷新页面。 */
    private fun privacySignature(): String {
        val s = AppServices.settings
        return buildString {
            append(s.siteBlockJs()).append('|')
            append(s.siteBlockPopup()).append('|')
            append(s.siteBlockDom()).append('|')
            append(s.siteBlockDb()).append('|')
            append(s.siteBlockFile()).append('|')
            append(s.safeBrowsing()).append('|')
            append(s.thirdPartyCookiesBlocked()).append('|')
            append(s.privacyCookies()).append('|')
            append(s.privacyLevel()).append('|')
            append(s.privacyTracking()).append('|')
            append(s.privacySocial()).append('|')
            append(s.privacyMiners()).append('|')
            append(s.privacyFingerprint()).append('|')
            append(s.privacyContent()).append('|')
            append(s.privacyCleanUrls()).append('|')
            append(s.privacyGpc()).append('|')
            append(s.privacyHttpsOnly()).append('|')
            append(s.dntEnabled()).append('|')
            append(s.webrtcProtect()).append('|')
            append(AppServices.privacyManager.isIncognito)
        }
    }

    /** 隐私设置变化后重新应用到全部标签，并刷新当前页面让 JS/DNT 等立即生效。 */
    private fun reapplyPrivacySettingsIfChanged(reloadNow: Boolean = true) {
        val signature = privacySignature()
        val changed = AppServices.lastPrivacySignature != null &&
            AppServices.lastPrivacySignature != signature
        AppServices.lastPrivacySignature = signature
        if (!changed || !::tabManager.isInitialized) return
        val s = AppServices.settings
        val block3p = s.thirdPartyCookiesBlocked() || s.privacyCookies()
        val cookieManager = android.webkit.CookieManager.getInstance()
        tabManager.tabs.forEach { tab ->
            cookieManager.setAcceptThirdPartyCookies(tab.webView, !block3p)
            tab.webView.settings.safeBrowsingEnabled = s.safeBrowsing()
            if (tab.url.isNotEmpty()) applySiteSettings(tab.webView, tab.url)
        }
        if (reloadNow) {
            tabManager.currentWebView?.reload()
        } else {
            AppServices.privacyRefreshPending = true
        }
    }

    /** 夜间滤镜：按设置强度在网页上叠加黑色遮罩。 */
    private fun applyNightFilter() {
        val overlay = view?.findViewById<View>(R.id.night_filter) ?: return
        val value = AppServices.settings.nightFilter().coerceIn(0, 255)
        if (!AppServices.settings.isNightMode() || value <= 0) {
            overlay.visibility = View.GONE
        } else {
            overlay.visibility = View.VISIBLE
            overlay.alpha = value / 255f
        }
    }

    override fun onResume() {
        super.onResume()
        (activity as? ShellActivity)?.refreshDialogTheme()
        applyKeepScreenOn()
        applyIncognitoBadge()
        applyTabActionLayout()
        applyToolbarPosition()
        applyTabsPanelAlpha()
        applyTabBarPosition()
        applyUiStyle(true)
        if (!::tabManager.isInitialized) return
        // 从设置页返回时重新应用主题色与强制背景色，避免开关要等切后台/截图才生效。
        applySiteThemeColor(currentThemeColor())
        view?.post { refreshTranslateFabForSite() }
        AppServices.pendingOpenUrl?.let { url ->
            AppServices.pendingOpenUrl = null
            loadUrlFor(url)
        }
        AppServices.applyPageZoom = {
            tabManager.tabs.forEach { tab ->
                tab.webView.settings.textZoom = effectiveTextZoom()
                if (tab.url.isNotEmpty()) applySiteSettings(tab.webView, tab.url)
            }
        }
        AppServices.applyWebTextAccent = { applyWebTextAccentToAllTabs() }
        applyWebTextAccentToAllTabs()
        tabManager.tabs.forEach {
            it.webView.applyNativeDarkMode(
                AppServices.settings.isNightMode() && AppServices.settings.webViewDark()
            )
        }
        AppServices.applyWebViewDark = {
            tabManager.tabs.forEach { tab ->
                tab.webView.applyNativeDarkMode(
                    AppServices.settings.isNightMode() && AppServices.settings.webViewDark()
                )
                tab.webView.reload()
            }
        }
        AppServices.reapplyPrivacyIfChanged = { reapplyPrivacySettingsIfChanged(reloadNow = false) }
        AppServices.reapplySiteSettings = {
            val wv = tabManager.currentWebView
            val url = tabManager.current?.url
            if (wv != null && url != null && url.startsWith("http")) {
                applySiteSettings(wv, url)
                wv.reload()
            }
        }
        AppServices.applyLayout = {
            applyToolbarPosition()
            applyTabBarPosition()
            applyQuickEnginePosition()
            applyAddressStyle()
        }
        applyAddressStyle()
        AppServices.fillPassword = { username, password ->
            tabManager.currentWebView?.injectJavaScript(
                com.sii.browser.core.webview.PasswordJsBridge.fillJs(username, password)
            )
        }
        AppServices.openUrl = { url ->
            // tabManager 与 WebView 绑定在 Fragment 实例上，view 销毁期间也直接加载，
            // 视图重建后 showWeb 会重新挂载。
            loadUrlFor(url)
        }
        AppServices.captureScrollForRecreate = {
            AppServices.pendingScrollY = tabManager.currentWebView?.scrollY ?: -1
        }
        val textZoom = effectiveTextZoom()
        tabManager.tabs.forEach { it.webView.settings.textZoom = textZoom }
        applyToolbarCustom()
        applyQuickEnginePosition()
        applySiteThemeColor(currentThemeColor())
        val currentWebView = tabManager.currentWebView
        val currentUrl = tabManager.current?.url
        if (currentWebView != null && currentUrl != null &&
            (currentUrl.startsWith("http://") || currentUrl.startsWith("https://"))
        ) {
            // tab.url 只在 onPageStarted 更新；若 WebView 已朝新站点发起导航（尚未提交），
            // 这里若按 tab.url 重算签名会把签名翻回旧站并触发 reload，取消掉未提交的导航。
            if (currentWebView.siteConfigHost == null ||
                currentUrl.hostEquals(currentWebView.siteConfigHost)
            ) {
                val oldSignature = currentWebView.siteConfigSignature
                applySiteSettings(currentWebView, currentUrl)
                if (oldSignature != null && currentWebView.siteConfigSignature != oldSignature) {
                    currentWebView.reload()
                }
            }
        }
        applyIncognitoSecure()
        applyHomeChromeTint()
        applyNightFilter()
        reapplyPrivacySettingsIfChanged()
        if (AppServices.privacyRefreshPending && ::tabManager.isInitialized) {
            AppServices.privacyRefreshPending = false
            tabManager.currentWebView?.reload()
        }
        // 设置页改了主页搜索框位置后，返回浏览器立即生效。
        homePanel?.applyHomeStyle()
        if (::tabManager.isInitialized) {
            tabManager.tabs.forEach { tab ->
                if (tab.webView !== tabManager.currentWebView) tab.webView.onPause()
            }
        }
        tabManager.currentWebView?.onResume()
    }

    /** 主机名等价（www./m. 前缀差异视为同站，与 SiteConfig 的同域回退口径一致）。 */
    private fun String.hostEquals(other: String?): Boolean {
        if (other == null) return false
        if (this == other) return true
        return stripHostPrefix(this) == stripHostPrefix(other)
    }

    private fun stripHostPrefix(host: String): String =
        host.removePrefix("www.").removePrefix("m.").removePrefix("mobile.")

    /** 当前是否为计费网络（移动网络）——「仅 Wi-Fi 加载图片」用。 */
    private fun isMeteredNetwork(): Boolean {
        val cm = requireContext().getSystemService(Context.CONNECTIVITY_SERVICE)
            as? android.net.ConnectivityManager ?: return false
        return cm.isActiveNetworkMetered
    }

    /** 当前 WebView 隐私相关设置的实际值（供设置页「隐私自检」展示，验证是否真的生效）。 */
    private fun settingsSnapshot(webView: SiiWebView): String {
        val s = webView.settings
        val cookie = android.webkit.CookieManager.getInstance()
        return """
            隐身：${if (AppServices.privacyManager.isIncognito) "开" else "关"}
            JavaScript 启用：${s.javaScriptEnabled}
            自动加载图片：${s.loadsImagesAutomatically}
            DOM 存储：${s.domStorageEnabled}
            数据库存储：${s.databaseEnabled}
            表单保存：${s.saveFormData}
            定位：${if (AppServices.settings.incognitoNoGeo()) "已禁用" else "未限制"}
            file:// 访问：${s.allowFileAccess}
            允许弹窗：${s.javaScriptCanOpenWindowsAutomatically}
            接受 Cookie：${cookie.acceptCookie()}
            接受第三方 Cookie：${cookie.acceptThirdPartyCookies(webView)}
        """.trimIndent()
    }

    private fun cleanupIncognitoData() {
        val cleanDownloads = AppServices.settings.incognitoCleanDownloads()
        val cleanTranslation = AppServices.settings.incognitoCleanTranslation()
        if (!cleanDownloads && !cleanTranslation) return
        val ctx = requireContext().applicationContext
        val sessionStart = AppServices.privacyManager.incognitoSessionStartedAt
        kotlinx.coroutines.CoroutineScope(
            kotlinx.coroutines.SupervisorJob() + Dispatchers.IO
        ).launch {
            if (cleanDownloads) {
                com.sii.browser.download.IncognitoCleanup.cleanDownloads(ctx, sessionStart)
            }
            if (cleanTranslation) {
                com.sii.browser.download.IncognitoCleanup.cleanTranslation(ctx)
            }
        }
    }

    /** 无痕 + 开启该项时禁止截屏/最近任务预览。 */
    private fun applyIncognitoSecure() {        val secure = AppServices.privacyManager.isIncognito &&
            AppServices.settings.incognitoSecure()
        if (secure) {
            activity?.window?.setFlags(
                android.view.WindowManager.LayoutParams.FLAG_SECURE,
                android.view.WindowManager.LayoutParams.FLAG_SECURE
            )
        } else {
            activity?.window?.clearFlags(android.view.WindowManager.LayoutParams.FLAG_SECURE)
        }
    }

    private var swipeStartX = 0f
    private var swipeStartY = 0f
    private var fullscreenContainer: android.widget.FrameLayout? = null
    private var filePathCallback: android.webkit.ValueCallback<Array<android.net.Uri>>? = null

    private val fileChooserLauncher = registerForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val callback = filePathCallback
        filePathCallback = null
        callback?.onReceiveValue(
            android.webkit.WebChromeClient.FileChooserParams.parseResult(result.resultCode, result.data)
        )
    }

    /** 按设置重排并显隐底部工具栏按钮。 */
    private fun applyToolbarCustom() {
        val hidden = AppServices.settings.toolbarHidden().orEmpty()
            .split(",").filter { it.isNotEmpty() }.toSet()
        val map = mapOf(
            "tabs" to R.id.tab_count_button,
            "back" to R.id.back_button,
            "forward" to R.id.forward_button,
            "home" to R.id.home_button,
            "menu" to R.id.menu_button
        )
        val bar = view?.findViewById<LinearLayout>(R.id.bottom_bar)
        if (bar != null && AppServices.settings.uiStyle() != 1) {
            val order = AppServices.settings.toolbarOrder().orEmpty()
                .split(",").filter { it.isNotEmpty() }
            val ordered = (order + DEFAULT_TOOLBAR_ORDER).distinct()
            val buttons = ordered.mapNotNull { id ->
                map[id]?.let { view?.findViewById<View>(it) }
            }
            if (buttons.size == map.size) {
                bar.removeAllViews()
                buttons.forEach { target ->
                    val lp = (target.layoutParams as? LinearLayout.LayoutParams)
                        ?: LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f)
                    bar.addView(target, lp)
                }
            }
        }
        if (AppServices.settings.uiStyle() == 1) {
            applyModernToolVisibility()
        } else {
            map.forEach { (id, viewId) ->
                view?.findViewById<View>(viewId)?.visibility =
                    if (id in hidden) View.GONE else View.VISIBLE
            }
        }
        if (AppServices.settings.uiStyle() == 1) {
            view?.findViewById<android.widget.LinearLayout>(R.id.bottom_bar)
                ?.let { hideLabels(it) }
        } else if (AppServices.settings.toolbarNoLabel()) {
            view?.findViewById<android.widget.LinearLayout>(R.id.bottom_bar)
                ?.let { hideLabels(it) }
        } else {
            view?.findViewById<android.widget.LinearLayout>(R.id.bottom_bar)
                ?.let { showLabels(it) }
        }
    }

    private fun hideLabels(bar: android.view.ViewGroup) {
        for (i in 0 until bar.childCount) {
            val child = bar.getChildAt(i)
            if (child is android.view.ViewGroup) {
                for (j in 0 until child.childCount) {
                    (child.getChildAt(j) as? TextView)?.visibility = View.GONE
                }
            }
        }
    }

    private fun showLabels(bar: android.view.ViewGroup) {
        for (i in 0 until bar.childCount) {
            val child = bar.getChildAt(i)
            if (child is android.view.ViewGroup) {
                for (j in 0 until child.childCount) {
                    (child.getChildAt(j) as? TextView)?.visibility = View.VISIBLE
                }
            }
        }
    }

    override fun onPause() {
        // 离开浏览器页时状态栏恢复默认，避免设置页继续显示网站主题色。
        (activity as? ShellActivity)?.applyStatusBarColor(null)
        if (::tabManager.isInitialized) {
            persistTabs()
            tabManager.tabs.forEach { it.webView.onPause() }
        }
        super.onPause()
    }

    /** 视频手势：全屏播放时左侧边缘上下滑调窗内亮度、右侧边缘调媒体音量（中间区域透传，不影响原生控件）。 */
    private fun setupVideoGesture(container: View) {
        if (!AppServices.settings.videoGesture()) return
        val audio = requireContext().getSystemService(Context.AUDIO_SERVICE) as android.media.AudioManager
        var startY = 0f
        var startVolume = 0
        var startBrightness = -1f
        var adjustVolume = false
        var captured = false
        container.setOnTouchListener { v, event ->
            when (event.actionMasked) {
                android.view.MotionEvent.ACTION_DOWN -> {
                    val edge = v.width * 0.25f
                    val inLeft = event.x < edge
                    val inRight = event.x > v.width - edge
                    captured = AppServices.settings.videoGesture() && (inLeft || inRight)
                    if (captured) {
                        startY = event.y
                        adjustVolume = inRight
                        startVolume = audio.getStreamVolume(android.media.AudioManager.STREAM_MUSIC)
                        startBrightness = activity?.window?.attributes?.screenBrightness ?: -1f
                    }
                    captured
                }
                android.view.MotionEvent.ACTION_MOVE -> {
                    if (!captured) return@setOnTouchListener false
                    val delta = (startY - event.y) / (v.height.coerceAtLeast(1) / 2f)
                    if (adjustVolume) {
                        val max = audio.getStreamMaxVolume(android.media.AudioManager.STREAM_MUSIC)
                        val target = (startVolume + delta * max).toInt().coerceIn(0, max)
                        audio.setStreamVolume(android.media.AudioManager.STREAM_MUSIC, target, 0)
                        Toast.makeText(requireContext(), "音量 $target", Toast.LENGTH_SHORT).show()
                    } else {
                        val window = activity?.window ?: return@setOnTouchListener false
                        val base = if (startBrightness < 0f) 0.5f else startBrightness
                        val target = (base + delta).coerceIn(0.02f, 1f)
                        window.attributes = window.attributes.apply { screenBrightness = target }
                    }
                    true
                }
                android.view.MotionEvent.ACTION_UP,
                android.view.MotionEvent.ACTION_CANCEL -> {
                    val wasCaptured = captured
                    captured = false
                    wasCaptured
                }
                else -> false
            }
        }
    }

    /** 无痕标识：地址栏提示切为「无痕」；否则恢复默认提示。 */
    private fun applyIncognitoBadge() {
        addressInput?.hint = getString(
            if (AppServices.privacyManager.isIncognito) R.string.incognito_badge else R.string.address_hint
        )
        updateSiteInfoIcon()
    }

    /** 密码库：记录域名、注入提交监听，并按设置尝试自动填充。 */
    private fun applyPasswordFeatures(webView: SiiWebView, url: String) {
        if (!AppServices.settings.passwordEnabled()) return
        if (AppServices.privacyManager.isIncognito) return
        val host = runCatching { android.net.Uri.parse(url).host?.lowercase() }.getOrNull() ?: return
        passwordBridges[webView]?.let { bridge ->
            bridge.pageUrl = url
            bridge.host = host
        }
        webView.injectJavaScript(com.sii.browser.core.webview.PasswordJsBridge.DETECT_JS)
        if (!AppServices.settings.passwordAutofill()) return
        lifecycleScope.launch {
            val list = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                AppServices.passwordRepository.byHost(host)
            }
            val first = list.firstOrNull() ?: return@launch
            val plain = AppServices.passwordRepository.decrypt(first) ?: return@launch
            webView.injectJavaScript(
                com.sii.browser.core.webview.PasswordJsBridge.fillJs(first.username, plain)
            )
        }
    }

    /** 站点提交账号密码后询问是否保存（含「不再提示」）。 */
    private fun offerSavePassword(host: String, username: String, password: String) {
        if (!AppServices.settings.passwordEnabled() || AppServices.privacyManager.isIncognito) return
        val ignored = AppServices.settings.ignoredPasswordHosts().orEmpty()
            .split(",").filter { it.isNotEmpty() }.toSet()
        if (host.lowercase() in ignored) return
        android.app.AlertDialog.Builder(requireContext())
            .setTitle(R.string.pwd_save_title)
            .setMessage("$host\n${username.ifEmpty { "-" }}")
            .setPositiveButton(R.string.pwd_save) { _, _ ->
                viewLifecycleOwner.lifecycleScope.launch {
                    val saved = AppServices.passwordRepository.save(host, username, password)
                    if (!saved) {
                        Toast.makeText(
                            requireContext(), R.string.password_save_failed, Toast.LENGTH_SHORT
                        ).show()
                    }
                    Toast.makeText(requireContext(), R.string.pwd_saved, Toast.LENGTH_SHORT).show()
                }
            }
            .setNeutralButton(R.string.pwd_never) { _, _ ->
                AppServices.settings.setIgnoredPasswordHosts(
                    (ignored + host.lowercase()).joinToString(",")
                )
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    /** 简化 UA：去掉机型/系统版本细节，只保留 Chrome 主版本（隐私与兼容用）。 */
    private fun simplifyUserAgent(ua: String): String {
        val major = Regex("Chrome/(\\d+)").find(ua)?.groupValues?.get(1)
        return if (major != null) {
            "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 " +
                "(KHTML, like Gecko) Chrome/$major.0.0.0 Mobile Safari/537.36"
        } else {
            "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 " +
                "(KHTML, like Gecko) Version/4.0 Mobile Safari/537.36"
        }
    }

    /** 实际页面字号：字号设置 × 界面缩放。 */
    /** 网页链接/强调文字是否应在当前日夜状态使用主色。 */
    private fun webTextAccentActive(): Boolean {
        val s = AppServices.settings
        val night = s.isNightMode()
        return when (s.webTextColor()) {
            1 -> true
            2 -> !night
            3 -> night
            else -> false
        }
    }

    private fun injectWebTextAccent(webView: SiiWebView?) {
        val wv = webView ?: return
        if (!webTextAccentActive()) {
            wv.injectJavaScript(
                "(function(){var e=document.getElementById('sii_text_accent');if(e)e.remove();})();"
            )
            return
        }
        val color = java.lang.String.format(
            "#%06X",
            0xFFFFFF and ThemeAccent.color(requireContext())
        )
        val css = "html body a,html body a *,html body a[href],html body a[href] *" +
            "{color:$color!important;-webkit-text-fill-color:$color!important}"
        wv.injectJavaScript(
            "(function(){function apply(){if(!document.documentElement)return false;" +
                "var e=document.getElementById('sii_text_accent');" +
                "if(!e){e=document.createElement('style');e.id='sii_text_accent';" +
                "document.documentElement.appendChild(e);}" +
                "e.textContent='" + css + "';return true;}" +
                "if(!apply()){var t=setInterval(function(){if(apply())clearInterval(t);},30);" +
                "setTimeout(function(){clearInterval(t);},3000);}})();"
        )
    }

    private fun applyWebTextAccentToAllTabs() {
        tabManager.tabs.forEach { injectWebTextAccent(it.webView) }
    }

    /** 强制暗色 CSS 与原生 WebView 暗色互斥，原生开启时移除旧 CSS。 */
    private fun applyNightCss(webView: SiiWebView?) {
        val wv = webView ?: return
        val s = AppServices.settings
        if (s.isNightMode() && s.nightCssEnabled() && !s.webViewDark()) {
            wv.injectJavaScript(NIGHT_CSS_JS)
        } else {
            wv.injectJavaScript(
                "(function(){var e=document.getElementById('sii_night_css');if(e)e.remove();})();"
            )
        }
    }

    private fun effectiveTextZoom(): Int {
        val text = AppServices.settings.textSize().coerceIn(5, 400)
        val ui = AppServices.settings.uiScale().coerceIn(50, 150)
        return (text * ui / 100).coerceIn(5, 400)
    }

    /** 工具栏位置：底部/顶部/隐藏（设置项）。 */
    /**
     * 经典 / 新版界面。新版使用悬浮底栏：地址胶囊 + 图标工具行；
     * 复用现有 address_bar / bottom_bar / progress_bar，只改父容器与样式，WebView 不重建。
     */
    private fun applyUiStyle(animate: Boolean = false) {
        val root = view ?: return
        val content = root.findViewById<LinearLayout>(R.id.browser_content) ?: return
        val address = root.findViewById<View>(R.id.address_bar) ?: return
        val bottom = root.findViewById<View>(R.id.bottom_bar) ?: return
        val progress = root.findViewById<View>(R.id.progress_bar)
        val dock = root.findViewById<LinearLayout>(R.id.modern_chrome) ?: return
        val addressHost = root.findViewById<FrameLayout>(R.id.modern_address_host) ?: return
        val toolRow = root.findViewById<LinearLayout>(R.id.modern_tool_row) ?: return
        val modernStyle = AppServices.settings.uiStyle() == 1
        val already = if (modernStyle) address.parent === addressHost else address.parent === content
        if (already) {
            if (modernStyle) {
                applyModernChrome(root, address, bottom, progress, dock, addressHost, toolRow)
            } else {
                applyClassicChrome(root, address, bottom, progress, dock)
            }
            applyQuickEnginePosition()
            root.post { applyFloatingBarLift() }
            applyPageFullscreenChrome()
            return
        }
        (address.parent as? ViewGroup)?.removeView(address)
        (bottom.parent as? ViewGroup)?.removeView(bottom)
        progress?.let { (it.parent as? ViewGroup)?.removeView(it) }
        if (modernStyle) {
            addressHost.removeAllViews()
            toolRow.removeAllViews()
            addressHost.addView(
                address,
                FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    dp(44)
                )
            )
            progress?.let {
                addressHost.addView(
                    it,
                    FrameLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        dp(2),
                        android.view.Gravity.BOTTOM
                    )
                )
            }
            toolRow.addView(
                bottom,
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(44))
            )
            applyModernChrome(root, address, bottom, progress, dock, addressHost, toolRow)
            dock.visibility = View.VISIBLE
            if (animate) {
                dock.alpha = 0f
                dock.translationY = dp(18).toFloat()
                dock.animate().alpha(1f).translationY(0f).setDuration(220)
                    .setInterpolator(android.view.animation.DecelerateInterpolator()).start()
            } else {
                dock.alpha = 1f
                dock.translationY = 0f
            }
        } else {
            addressHost.removeAllViews()
            toolRow.removeAllViews()
            dock.visibility = View.GONE
            content.addView(
                address,
                1.coerceAtMost(content.childCount),
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                )
            )
            progress?.let {
                content.addView(
                    it,
                    2.coerceAtMost(content.childCount),
                    LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(3))
                )
            }
            applyClassicChrome(root, address, bottom, progress, dock)
            applyToolbarPosition()
            applyTabBarPosition()
        }
        updateAddressBarForCurrent()
        updateNavigationState()
        applyQuickEnginePosition()
        root.post { applyFloatingBarLift() }
        applyPageFullscreenChrome()
    }

    /**
     * 新版编辑态：隐藏工具行与站点图标/刷新，显示「取消」，让地址输入条贴近键盘。
     * 站点图标与刷新隐藏在 [applyModernChrome] 中根据 addressEditing 恢复。
     */
    private fun setModernEditing(editing: Boolean) {
        if (AppServices.settings.uiStyle() != 1) return
        if (addressEditing == editing) return
        addressEditing = editing
        AppServices.addressEditImeSuspend = editing
        (activity as? ShellActivity)?.refreshImeInsets()
        val root = view ?: return
        val cancel = root.findViewById<View>(R.id.modern_cancel) ?: return
        val tools = root.findViewById<View>(R.id.modern_tool_row) ?: return
        cancel.visibility = if (editing) View.VISIBLE else View.GONE
        if (editing) {
            root.findViewById<View>(R.id.site_info_button)?.visibility = View.GONE
        } else {
            updateSiteInfoVisibility()
        }
        root.findViewById<View>(R.id.refresh_button)?.visibility =
            if (editing) View.GONE else View.VISIBLE
        if (editing) {
            root.findViewById<View>(R.id.search_toolbar_scroll)?.visibility = View.GONE
        } else {
            updateQuickEngineVisibility()
        }
        tools.animate().cancel()
        val dock = root.findViewById<View>(R.id.modern_chrome)
        dock?.animate()?.cancel()
        dock?.translationY = 0f
        applyDockImeOffset()
        if (editing) {
            toolbarHidden = false
            tools.animate().alpha(0f).translationY(dp(10).toFloat()).setDuration(140)
                .withEndAction {
                    if (addressEditing) tools.visibility = View.GONE
                }.start()
        } else {
            tools.visibility = View.VISIBLE
            tools.animate().alpha(1f).translationY(0f).setDuration(160)
                .setInterpolator(android.view.animation.DecelerateInterpolator()).start()
        }
    }

    /** 新版底栏样式：单行深色玻璃胶囊，地址区透明，工具行只留图标。 */
    private fun applyModernChrome(
        root: View,
        address: View,
        bottom: View,
        progress: View?,
        dock: LinearLayout,
        addressHost: FrameLayout,
        toolRow: LinearLayout
    ) {
        dock.visibility = View.VISIBLE
        dock.alpha = 1f
        address.visibility = View.VISIBLE
        val night = AppServices.settings.isNightMode()
        val primary = if (night) android.graphics.Color.WHITE
        else requireContext().getColor(R.color.text_primary)
        val secondary = if (night) 0x99FFFFFF.toInt()
        else requireContext().getColor(R.color.text_secondary)
        if (!addressEditing) {
            toolbarHidden = false
            dock.translationY = 0f
        }
        val screenWidth = resources.displayMetrics.widthPixels
        val dockWidth = minOf(screenWidth - dp(20), dp(560)).coerceAtLeast(dp(240))
        (dock.layoutParams as? FrameLayout.LayoutParams)?.let { params ->
            params.width = dockWidth
            params.gravity = android.view.Gravity.BOTTOM or android.view.Gravity.CENTER_HORIZONTAL
            dock.layoutParams = params
        }
        tabBar?.visibility = View.GONE
        val glassStrength = AppServices.settings.glassStrength()
        dock.background = GlassStyle.background(
            requireContext(), night, dp(28).toFloat(), glassStrength,
            minAlpha = DOCK_GLASS_MIN_ALPHA
        )
        dock.clipToOutline = true
        applyDockGlass()
        dock.elevation = dp(12).toFloat()
        addressHost.layoutParams = LinearLayout.LayoutParams(0, dp(48), 1f)
        address.layoutParams = FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            dp(44),
            android.view.Gravity.CENTER_VERTICAL
        )
        address.setPadding(dp(8), 0, dp(6), 0)
        address.background = null
        address.elevation = 0f
        address.clipToOutline = false
        addressIconColor = primary
        addressInput?.setTextColor(primary)
        addressInput?.setHintTextColor(secondary)
        val iconTint = android.content.res.ColorStateList.valueOf(primary)
        root.findViewById<ImageView>(R.id.refresh_button)?.imageTintList = iconTint
        root.findViewById<ImageView>(R.id.site_info_button)?.imageTintList = iconTint
        updateSiteInfoIcon()
        root.findViewById<TextView>(R.id.modern_cancel)?.setTextColor(
            ThemeAccent.color(requireContext())
        )
        progress?.let {
            if (it.parent !== addressHost) {
                (it.parent as? ViewGroup)?.removeView(it)
                addressHost.addView(
                    it,
                    FrameLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        dp(2),
                        android.view.Gravity.BOTTOM
                    )
                )
            } else {
                it.layoutParams = FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    dp(2),
                    android.view.Gravity.BOTTOM
                )
            }
        }
        root.findViewById<View>(R.id.modern_cancel)?.visibility =
            if (addressEditing) View.VISIBLE else View.GONE
        if (addressEditing) {
            root.findViewById<View>(R.id.site_info_button)?.visibility = View.GONE
        } else {
            updateSiteInfoVisibility()
        }
        root.findViewById<View>(R.id.refresh_button)?.visibility =
            if (addressEditing) View.GONE else View.VISIBLE
        toolRow.layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT,
            dp(48)
        )
        toolRow.visibility = if (addressEditing) View.GONE else View.VISIBLE
        toolRow.alpha = if (addressEditing) 0f else 1f
        toolRow.translationY = 0f
        if (addressEditing) {
            root.findViewById<View>(R.id.search_toolbar_scroll)?.visibility = View.GONE
        }
        applyBottomBarStyle(root, modern = true)
        applyModernToolVisibility()
    }

    /** 经典样式还原：地址栏回顶部、工具栏回底部并恢复标签与顺序。 */
    private fun applyClassicChrome(
        root: View,
        address: View,
        bottom: View,
        progress: View?,
        dock: LinearLayout
    ) {
        dock.visibility = View.GONE
        dock.background = null
        address.visibility = View.VISIBLE
        addressEditing = false
        address.layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        )
        address.setPadding(dp(8), dp(6), dp(8), dp(6))
        address.setBackgroundColor(requireContext().getColor(R.color.toolbar_bg))
        address.elevation = 0f
        address.clipToOutline = false
        addressIconColor = null
        addressInput?.setTextColor(requireContext().getColor(R.color.text_primary))
        addressInput?.setHintTextColor(requireContext().getColor(R.color.text_secondary))
        val iconTint = android.content.res.ColorStateList.valueOf(
            requireContext().getColor(R.color.icon_primary)
        )
        root.findViewById<ImageView>(R.id.refresh_button)?.imageTintList = iconTint
        root.findViewById<ImageView>(R.id.site_info_button)?.imageTintList = iconTint
        updateSiteInfoIcon()
        root.findViewById<View>(R.id.modern_cancel)?.visibility = View.GONE
        updateSiteInfoVisibility()
        root.findViewById<View>(R.id.refresh_button)?.visibility = View.VISIBLE
        progress?.layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            dp(3)
        )
        applyBottomBarStyle(root, modern = false)
    }

    /** 工具行：新版只留图标并按导航优先排序，经典恢复标签与顺序。 */
    private fun applyBottomBarStyle(root: View, modern: Boolean) {
        val bar = root.findViewById<LinearLayout>(R.id.bottom_bar) ?: return
        if (modern) {
            bar.layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
            bar.setBackgroundColor(android.graphics.Color.TRANSPARENT)
            bar.visibility = View.VISIBLE
        } else {
            bar.layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                dp(52)
            )
            bar.setBackgroundColor(requireContext().getColor(R.color.toolbar_bg))
        }
        val order = if (modern) {
            listOf(
                R.id.back_button,
                R.id.forward_button,
                R.id.tab_count_button,
                R.id.menu_button,
                R.id.home_button
            )
        } else {
            listOf(
                R.id.tab_count_button,
                R.id.back_button,
                R.id.forward_button,
                R.id.home_button,
                R.id.menu_button
            )
        }
        val views = order.mapNotNull { bar.findViewById<View>(it) }
        if (views.size == order.size) {
            val same = bar.childCount == views.size && (0 until views.size).all {
                bar.getChildAt(it) === views[it]
            }
            if (!same) {
                bar.removeAllViews()
                views.forEach { bar.addView(it) }
            }
        }
        for (i in 0 until bar.childCount) {
            val item = bar.getChildAt(i)
            item.layoutParams = if (modern) {
                LinearLayout.LayoutParams(dp(40), ViewGroup.LayoutParams.MATCH_PARENT)
            } else {
                item.visibility = View.VISIBLE
                LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f)
            }
            val group = item as? ViewGroup ?: continue
            for (j in 0 until group.childCount) {
                val child = group.getChildAt(j)
                if (child is TextView) {
                    child.visibility = if (modern) View.GONE else View.VISIBLE
                }
            }
        }
        applyModernTabBadge(root, modern)
    }

    /** 新版工具行：返回 / 前进（有历史时显示）/ 首页 / 标签 / 菜单。 */
    private fun applyModernToolVisibility() {
        if (AppServices.settings.uiStyle() != 1) return
        val hidden = AppServices.settings.toolbarHidden().orEmpty()
            .split(",").filter { it.isNotEmpty() }.toSet()
        val canForward = if (::tabManager.isInitialized) {
            tabManager.currentWebView?.canGoForward() == true
        } else false
        fun apply(id: Int, key: String, on: Boolean) {
            view?.findViewById<View>(id)?.visibility =
                if (on && key !in hidden) View.VISIBLE else View.GONE
        }
        apply(R.id.back_button, "back", true)
        // 主页始终保留；前进有历史时额外显示，不再二选一。
        apply(R.id.forward_button, "forward", canForward)
        apply(R.id.home_button, "home", true)
        apply(R.id.tab_count_button, "tabs", true)
        apply(R.id.menu_button, "menu", true)
        // 前进出现时五个图标并排，稍微收窄保证地址可用宽度。
        val bar = view?.findViewById<LinearLayout>(R.id.bottom_bar) ?: return
        val iconWidth = dp(if (canForward && "forward" !in hidden) 38 else 40)
        for (i in 0 until bar.childCount) {
            val child = bar.getChildAt(i)
            val lp = child.layoutParams as? LinearLayout.LayoutParams ?: continue
            if (lp.width != iconWidth) {
                lp.width = iconWidth
                child.layoutParams = lp
            }
        }
    }

    /** 新版标签数做成右上角主色角标；经典还原为图标上的数字。 */
    private fun applyModernTabBadge(root: View, modern: Boolean) {
        val count = root.findViewById<TextView>(R.id.tab_count) ?: return
        val parent = count.parent as? FrameLayout ?: return
        parent.clipChildren = false
        parent.clipToPadding = false
        count.typeface = android.graphics.Typeface.DEFAULT_BOLD
        count.gravity = android.view.Gravity.CENTER
        if (modern) {
            count.setTextSize(9f)
            count.setTextColor(android.graphics.Color.WHITE)
            count.setPadding(0, 0, 0, 0)
            count.background = android.graphics.drawable.GradientDrawable().apply {
                shape = android.graphics.drawable.GradientDrawable.OVAL
                setColor(ThemeAccent.color(requireContext()))
            }
            count.layoutParams = FrameLayout.LayoutParams(
                dp(16),
                dp(16),
                android.view.Gravity.TOP or android.view.Gravity.END
            )
            val tabs = if (::tabManager.isInitialized) tabManager.size else 1
            count.visibility = if (tabs > 1) View.VISIBLE else View.INVISIBLE
        } else {
            count.setTextSize(8.6f)
            count.setTextColor(requireContext().getColor(R.color.text_primary))
            count.setPadding(0, dp(5), 0, 0)
            count.background = null
            count.layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
            count.visibility = View.VISIBLE
        }
    }

    private fun applyToolbarPosition() {
        if (AppServices.settings.uiStyle() == 1) return
        val content = view?.findViewById<LinearLayout>(R.id.browser_content) ?: return
        val bottomBar = view?.findViewById<View>(R.id.bottom_bar) ?: return
        val position = AppServices.settings.toolbarPosition()
        (bottomBar.parent as? ViewGroup)?.removeView(bottomBar)
        when (position) {
            1 -> content.addView(bottomBar, 0)
            2 -> Unit
            else -> content.addView(bottomBar)
        }
        toolbarAutoHideAnimator?.cancel()
        toolbarHidden = false
        listOfNotNull(bottomBar, view?.findViewById<View>(R.id.search_toolbar_scroll), tabBar)
            .forEach { target ->
                toolbarAutoHideHeights[target]?.let { height ->
                    target.layoutParams = target.layoutParams.apply { this.height = height }
                }
                target.translationY = 0f
                target.alpha = 1f
            }
        toolbarAutoHideHeights.clear()
        bottomBar.visibility = if (position == 2) View.GONE else View.VISIBLE
        updateQuickEngineVisibility()
        applyTabBarPosition()
        updateTabsOverlayBounds()
    }

    /** 标签面板的蒙层与滑动区域避开工具栏，面板从工具栏上沿出现/消失。 */
    private fun updateTabsOverlayBounds() {
        val overlay = view?.findViewById<View>(R.id.tabs_overlay) ?: return
        val dim = view?.findViewById<View>(R.id.tabs_dim) ?: return
        val panel = view?.findViewById<View>(R.id.tabs_panel) ?: return
        val toolbar = view?.findViewById<View>(R.id.bottom_bar) ?: return
        val barHeight = toolbar.height.takeIf { it > 0 } ?: dp(56)
        val position = AppServices.settings.toolbarPosition()
        val modern = AppServices.settings.uiStyle() == 1
        val overlayParams = overlay.layoutParams as? FrameLayout.LayoutParams ?: return
        val dimParams = dim.layoutParams as? FrameLayout.LayoutParams ?: return
        val panelParams = panel.layoutParams as? FrameLayout.LayoutParams ?: return
        // 新版面板贴底、遮罩全覆盖；经典模式仍止于底部工具栏边缘。
        overlayParams.topMargin = if (!modern && position == 1) barHeight else 0
        overlayParams.bottomMargin = if (!modern && position == 0) barHeight else 0
        dimParams.topMargin = 0
        dimParams.bottomMargin = 0
        // 新版底栏悬浮在内容之上，面板底部要让出同样高度，否则底部按钮被压在底栏下面。
        panelParams.bottomMargin = if (modern) {
            (view?.findViewById<View>(R.id.modern_chrome)?.height?.takeIf { it > 0 } ?: dp(56)) +
                dp(14)
        } else 0
        // 新版面板两侧留白，浮在胶囊底栏上方；经典模式仍贴边。
        val sideInset = if (modern) dp(12) else 0
        panelParams.leftMargin = sideInset
        panelParams.rightMargin = sideInset
        overlay.layoutParams = overlayParams
        dim.layoutParams = dimParams
        panel.layoutParams = panelParams
    }

    /** 标签栏位置：地址栏上方或工具栏上方（工具栏不在底部时回落到地址栏上方）。 */
    /** 标签面板底部：新建/撤销顺序与撤销显隐，撤销隐藏时新建居中。 */
    private fun applyTabActionLayout() {
        val newButton = view?.findViewById<View>(R.id.tabs_new) ?: return
        val undoButton = view?.findViewById<View>(R.id.tabs_undo) ?: return
        val parent = newButton.parent as? LinearLayout ?: return
        val height = if (AppServices.settings.uiStyle() == 1) dpToPx(44) else dpToPx(48)
        parent.removeAllViews()
        val showUndo = AppServices.settings.tabUndoVisible()
        if (!showUndo) {
            undoButton.visibility = View.GONE
            newButton.visibility = View.VISIBLE
            newButton.layoutParams = LinearLayout.LayoutParams(0, height, 1f)
            parent.addView(newButton)
            return
        }
        undoButton.visibility = View.VISIBLE
        val first = if (AppServices.settings.tabActionSwap()) undoButton else newButton
        val second = if (AppServices.settings.tabActionSwap()) newButton else undoButton
        first.layoutParams = LinearLayout.LayoutParams(0, height, 1f)
        second.layoutParams = LinearLayout.LayoutParams(0, height, 1f).apply {
            marginStart = dpToPx(8)
        }
        parent.addView(first)
        parent.addView(second)
    }

    private fun applyTabBarPosition() {
        val content = view?.findViewById<LinearLayout>(R.id.browser_content) ?: return
        val tabBar = tabBar ?: return
        val bottomBar = view?.findViewById<View>(R.id.bottom_bar) ?: return
        if (AppServices.settings.uiStyle() == 1) {
            tabBar.visibility = View.GONE
            return
        }
        if (AppServices.settings.tabBarPosition() == 2) {
            tabBar.visibility = View.GONE
            return
        }
        val hiddenWithToolbar = toolbarHidden &&
            AppServices.settings.toolbarPosition() == 0 &&
            AppServices.settings.tabBarPosition() == 1
        tabBar.visibility = if (tabManager.size > 1 && !hiddenWithToolbar) View.VISIBLE else View.GONE
        val aboveToolbar = AppServices.settings.tabBarPosition() == 1 &&
            AppServices.settings.toolbarPosition() == 0
        val params = tabBar.layoutParams
        content.removeView(tabBar)
        if (aboveToolbar) {
            val index = content.indexOfChild(bottomBar).takeIf { it >= 0 } ?: content.childCount
            content.addView(tabBar, index, params)
        } else {
            content.addView(tabBar, 0, params)
        }
    }

    /** 快捷引擎在工具栏上时，下滚收起、上滚展开。 */
    private fun handleQuickEngineAutoHide(dy: Int) {
        val scroll = view?.findViewById<View>(R.id.search_toolbar_scroll) ?: return
        if (scroll.visibility != View.VISIBLE && !quickEngineHidden) return
        val settings = AppServices.settings
        val onToolbar = settings.uiStyle() == 1 ||
            (settings.quickEnginePosition() == 1 && settings.toolbarPosition() != 2)
        if (!onToolbar) return
        if (dy > 6 && !quickEngineHidden) {
            quickEngineHidden = true
            animateQuickEngine(show = false)
        } else if (dy < -6 && quickEngineHidden) {
            quickEngineHidden = false
            animateQuickEngine(show = true)
        }
    }

    private fun animateQuickEngine(show: Boolean) {
        val scroll = view?.findViewById<View>(R.id.search_toolbar_scroll) ?: return
        quickEngineAnimator?.cancel()
        if (quickEngineFullHeight <= 0) quickEngineFullHeight = dp(40)
        scroll.animate().cancel()
        if (!ValueAnimator.areAnimatorsEnabled()) {
            if (show) {
                scroll.layoutParams = scroll.layoutParams.apply { height = quickEngineFullHeight }
                scroll.visibility = View.VISIBLE
                scroll.alpha = 1f
                scroll.translationY = 0f
            } else {
                scroll.visibility = View.GONE
                scroll.alpha = 0f
            }
            return
        }
        if (show) {
            // 高度一次恢复，随后只做淡入与位移，避免逐帧布局造成卡顿。
            scroll.layoutParams = scroll.layoutParams.apply { height = quickEngineFullHeight }
            scroll.visibility = View.VISIBLE
            scroll.alpha = 0f
            scroll.translationY = -dp(8).toFloat()
            scroll.animate().alpha(1f).translationY(0f).setDuration(160)
                .setInterpolator(android.view.animation.DecelerateInterpolator()).start()
        } else {
            scroll.animate().alpha(0f).translationY(-dp(8).toFloat()).setDuration(140)
                .withEndAction {
                    scroll.visibility = View.GONE
                    scroll.alpha = 0f
                    scroll.translationY = 0f
                }.start()
        }
    }
    private fun applyDockGlass() {
        val dock = view?.findViewById<LinearLayout>(R.id.modern_chrome) ?: return
        val night = AppServices.settings.isNightMode()
        val strength = AppServices.settings.glassStrength()
        val radius = dp(28).toFloat()
        dock.background = GlassStyle.background(
            requireContext(), night, radius, strength, minAlpha = DOCK_GLASS_MIN_ALPHA
        )
    }

    private var scrollAccum = 0
    private var pendingScrollDir = 0
    private var lastToggleAt = 0L
    private val applyScrollDirection = Runnable {
        val dir = pendingScrollDir
        pendingScrollDir = 0
        if (dir == 0) return@Runnable
        // 同一方向的收展保持 220ms 冷却，避免滚动中反方向抖动。
        val now = System.currentTimeMillis()
        if (now - lastToggleAt < 220L) return@Runnable
        lastToggleAt = now
        handleQuickEngineAutoHide(dir * 10)
        applyToolbarScrollHidden(dir * 10)
    }

    private fun handleToolbarAutoHide(rawDy: Int) {
        if (rawDy == 0) return
        // 累计 40dp 后短暂停顿（60ms）即执行，兼顾跟手与去抖。
        if ((rawDy > 0) != (scrollAccum > 0)) scrollAccum = 0
        scrollAccum += rawDy
        if (kotlin.math.abs(scrollAccum) < 40) return
        pendingScrollDir = if (scrollAccum > 0) 1 else -1
        scrollAccum = 0
        view?.removeCallbacks(applyScrollDirection)
        view?.postDelayed(applyScrollDirection, 60L)
    }

    private fun applyToolbarScrollHidden(dy: Int) {
        if (!AppServices.settings.toolbarAutoHide()) return
        if (AppServices.settings.uiStyle() == 1) {
            if (addressEditing) return
            val dock = view?.findViewById<View>(R.id.modern_chrome) ?: return
            val hidden = (dock.height.takeIf { it > 0 } ?: dp(56)) + dp(12)
            if (dy > 0 && !toolbarHidden) {
                toolbarHidden = true
                dock.animate().translationY(hidden.toFloat()).setDuration(180)
                    .setInterpolator(android.view.animation.DecelerateInterpolator()).start()
            } else if (dy < 0 && toolbarHidden) {
                toolbarHidden = false
                dock.animate().translationY(0f).setDuration(180)
                    .setInterpolator(android.view.animation.DecelerateInterpolator()).start()
            }
            return
        }
        if (AppServices.settings.toolbarPosition() != 0) return
        val views = toolbarAutoHideViews()
        if (dy > 0 && !toolbarHidden) {
            toolbarHidden = true
            views.forEach { target ->
                if (target.visibility == View.VISIBLE && target.height > 0) {
                    toolbarAutoHideHeights[target] = target.height
                }
            }
            animateToolbarGroup(views, show = false)
        } else if (dy < 0 && toolbarHidden) {
            toolbarHidden = false
            animateToolbarGroup(views, show = true)
        }
    }

    /** 用高度动画收起/展开整组视图，背景和文字一起被裁剪。 */
    private fun animateToolbarGroup(views: List<View>, show: Boolean) {
        if (views.isEmpty()) return
        toolbarAutoHideAnimator?.cancel()
        views.forEach { target ->
            target.alpha = 1f
            target.translationY = 0f
            if (show) {
                target.visibility = View.VISIBLE
                val height = toolbarAutoHideHeights[target] ?: target.height
                if (height > 0) {
                    target.layoutParams = target.layoutParams.apply { this.height = 0 }
                }
            }
        }
        val animator = android.animation.ValueAnimator.ofFloat(if (show) 0f else 1f, if (show) 1f else 0f)
        animator.duration = 180
        animator.addUpdateListener { animation ->
            val fraction = animation.animatedValue as Float
            views.forEach { target ->
                val original = toolbarAutoHideHeights[target]
                    ?: if (show) target.height else 0
                if (original > 0) {
                    target.layoutParams = target.layoutParams.apply {
                        height = (original * fraction).toInt()
                    }
                }
            }
        }
        animator.addListener(object : android.animation.AnimatorListenerAdapter() {
            override fun onAnimationEnd(animation: android.animation.Animator) {
                if (show) {
                    views.forEach { target ->
                        val original = toolbarAutoHideHeights[target]
                        if (original != null) {
                            target.layoutParams = target.layoutParams.apply { height = original }
                        }
                        target.visibility = View.VISIBLE
                    }
                    view?.post {
                        updateQuickEngineVisibility()
                        applyTabBarPosition()
                    }
                } else if (toolbarHidden) {
                    views.forEach { target ->
                        target.layoutParams = target.layoutParams.apply { height = 0 }
                        target.visibility = View.GONE
                    }
                }
            }
        })
        toolbarAutoHideAnimator = animator
        animator.start()
    }

    /** 底部工具栏及紧贴其上方的快捷引擎/标签栏。 */
    private fun toolbarAutoHideViews(): List<View> {
        val content = view?.findViewById<LinearLayout>(R.id.browser_content) ?: return emptyList()
        val bar = view?.findViewById<View>(R.id.bottom_bar) ?: return emptyList()
        val index = content.indexOfChild(bar)
        if (index < 0) return emptyList()
        val views = mutableListOf<View>()
        for (i in index - 1 downTo 0) {
            val child = content.getChildAt(i) ?: break
            if (child.id == R.id.search_toolbar_scroll || child === tabBar) {
                views.add(child)
            } else {
                break
            }
        }
        views.add(bar)
        return views
    }

    /** 屏幕常亮：按设置开关窗口标志。 */
    private fun applyKeepScreenOn() {
        val window = activity?.window ?: return
        if (AppServices.settings.keepOn()) {
            window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        } else {
            window.clearFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
    }

    override fun onDestroyView() {
        view?.removeCallbacks(applyScrollDirection)
        MenuPopup.dismiss()
        tts?.shutdown()
        tts = null
        speaking = false
        progressAnimator?.cancel()
        progressAnimator = null
        cancelProgressFinish()
        cancelTranslateFabAutoHide()
        homePanel?.release()
        homePanel = null
        // 只断开父容器，不销毁 WebView：TabManager 仍需复用页面状态。
        webViewContainer?.removeAllViews()
        homeContainer?.removeAllViews()
        addressInput = null
        siteInfoButton = null
        progressBar = null
        tabStrip = null
        tabBar = null
        homeContainer = null
        tabsOverlay = null
        tabsList = null
        pageLoadingCover = null
        com.sii.browser.core.webview.SniffStore.onResource = null
        sniffPrompt = null
        sniffPromptResource = null
        sniffPromptKey = null
        menuOverlay = null
        menuToggles = null
        menuGrid = null
        backButton = null
        forwardButton = null
        tabCountText = null
        webViewContainer = null
        ttsBar = null
        findBar = null
        findInput = null
        findCount = null
        translateStatus = null
        translateToggle = null
        fontCachePath = null
        fontCacheMtime = 0L
        fontCacheBase64 = null
        interceptorChain = null
        adPickBridges.clear()
        AppServices.openUrl = null
        AppServices.captureScrollForRecreate = null
        AppServices.applyPageZoom = null
        AppServices.applyWebTextAccent = null
        AppServices.applyWebViewDark = null
        AppServices.reapplyPrivacyIfChanged = null
        AppServices.reapplySiteSettings = null
        AppServices.applyLayout = null
        AppServices.uiActions = null
        super.onDestroyView()
    }

    override fun onDestroy() {
        if (::tabManager.isInitialized) {
            tabManager.closeAll()
        }
        AppServices.openUrl = null
        AppServices.captureScrollForRecreate = null
        AppServices.applyPageZoom = null
        AppServices.applyWebTextAccent = null
        AppServices.applyWebViewDark = null
        AppServices.reapplyPrivacyIfChanged = null
        AppServices.reapplySiteSettings = null
        AppServices.applyLayout = null
        AppServices.fillPassword = null
        AppServices.trimThumbnails = null
        AppServices.sniffTarget = null
        AppServices.uiActions = null
        AppServices.mcpHost = null
        super.onDestroy()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        if (!::tabManager.isInitialized) return
        tabManager.current?.url?.takeIf { it.isNotBlank() }?.let {
            outState.putString(KEY_URL, it)
        }
        if (!AppServices.privacyManager.isIncognito ||
            !AppServices.settings.incognitoNoRestore()
        ) {
            outState.putStringArrayList(KEY_TABS, ArrayList(tabManager.tabs.map { it.url }))
        }
    }

    // ---- HostCallback 实现 ----

    override fun onThemeChanged(night: Boolean) {
        if (AppServices.settings.uiStyle() == 1 && isAdded && view != null) {
            applyUiStyle()
        }
    }

    override fun onSoftInputChanged(visible: Boolean) {
        if (AppServices.settings.uiStyle() != 1) return
        applyDockImeOffset()
    }

    /** 悬浮底栏跟随 IME：直接赋值而不补动画，避免键盘先上滑、底栏再慢慢沉下去。 */
    private fun applyDockImeOffset() {
        if (AppServices.settings.uiStyle() != 1) return
        val dock = view?.findViewById<View>(R.id.modern_chrome) ?: return
        // 地址栏编辑时页面不缩，只把底栏抬到键盘上方。
        if (AppServices.addressEditImeSuspend) {
            val target = -imeInsetBottom.toFloat()
            if (dock.translationY != target) {
                dock.animate().cancel()
                dock.translationY = target
            }
            return
        }
        // 主页搜索聚焦时键盘直接盖住底栏，不做收起动画。
        if (AppServices.homeSearchImeSuspend) {
            if (dock.translationY != 0f) {
                dock.animate().cancel()
                dock.translationY = 0f
            }
            return
        }
        val hidden = (dock.height.takeIf { it > 0 } ?: dp(56)) + dp(12)
        val hide = !addressEditing && (imeInsetVisible || toolbarHidden)
        val target = if (hide) hidden.toFloat() else 0f
        if (dock.translationY == target) return
        dock.animate().cancel()
        dock.animate()
            .translationY(target)
            .setDuration(140L)
            .setInterpolator(android.view.animation.DecelerateInterpolator())
            .start()
    }

    private fun isSafeExternalUrl(url: String?): Boolean {
        if (url.isNullOrBlank() || url.length > 100_000) return false
        val scheme = runCatching { android.net.Uri.parse(url).scheme }.getOrNull()
        return scheme.equals("http", true) || scheme.equals("https", true)
    }

    override fun handleNewIntent(intent: android.content.Intent): Boolean {
        when (intent.action) {
            android.content.Intent.ACTION_VIEW -> {
                val data = intent.dataString ?: return false
                if (!isSafeExternalUrl(data)) return false
                loadUrlFor(data)
                return true
            }
            android.content.Intent.ACTION_SEND -> {
                val text = intent.getStringExtra(android.content.Intent.EXTRA_TEXT) ?: return false
                if (text.length > 100_000) return false
                loadUrlFor(textToTarget(text))
                return true
            }
            android.content.Intent.ACTION_PROCESS_TEXT -> {
                val text = intent.getCharSequenceExtra(android.content.Intent.EXTRA_PROCESS_TEXT)
                    ?.toString() ?: return false
                if (text.length > 100_000) return false
                loadUrlFor(resolveInput(text))
                return true
            }
            android.content.Intent.ACTION_WEB_SEARCH -> {
                val query = intent.getStringExtra(android.app.SearchManager.QUERY) ?: return false
                if (query.length > 100_000) return false
                loadUrlFor(resolveInput(query))
                return true
            }
        }
        when (intent.getStringExtra("sii_action")) {
            "new_tab" -> {
                openNewTab()
                return true
            }
            "search" -> {
                openNewTab()
                return true
            }
            "downloads" -> {
                openDownloads()
                return true
            }
        }
        return false
    }

    /** 分享文本：内含链接则打开链接，否则当搜索词。 */
    private fun textToTarget(text: String): String {
        val url = URL_PATTERN.find(text)?.value
        return if (url != null) url else resolveInput(text)
    }

    override fun onWindowFocusGained(hasFocus: Boolean) = Unit

    override fun onGlobalTouch(event: android.view.MotionEvent) {
        if (homeContainer?.visibility != View.VISIBLE) return
        homePanel?.onGlobalTouch(event)
    }

    override fun dispatchKeyEventInternal(event: KeyEvent): Boolean {
        if (!::tabManager.isInitialized) return false
        if (event.action == KeyEvent.ACTION_DOWN && homeContainer?.visibility != View.VISIBLE &&
            AppServices.settings.gestureVolumeKey()
        ) {
            val webView = tabManager.currentWebView
            if (webView != null) {
                when (event.keyCode) {
                    KeyEvent.KEYCODE_VOLUME_UP -> {
                        webView.pageUp(false)
                        return true
                    }
                    KeyEvent.KEYCODE_VOLUME_DOWN -> {
                        webView.pageDown(false)
                        return true
                    }
                }
            }
        }
        return false
    }

    private var suggestionPopup: SuggestionPopup? = null
    private var suggestSeq = 0
    private var suggestionsSuppressed = false
    private var suggestDebounceJob: kotlinx.coroutines.Job? = null
    private var addressTextProgrammatic = false

    private companion object {
        /** 胶囊底栏不透明度下限：强度再高也要压住网页原文，否则透出的色块与文字会被读成渲染异常。 */
        const val DOCK_GLASS_MIN_ALPHA = 225
        const val KEY_URL = "sii_browser_url"
        const val KEY_TABS = "sii_browser_tabs"
        /** 固定栏默认项：夜间/隐身/无图/电脑模式。 */
        val DEFAULT_FIXED = listOf("dark", "incog", "noimg", "desktop")
        /** 底部工具栏默认按钮顺序。 */
        val DEFAULT_TOOLBAR_ORDER = listOf("tabs", "back", "forward", "home", "menu")
        const val DEFAULT_SEARCH_URL = "https://www.baidu.com/s?wd="
        const val ABOUT_HOME = "about:home"
        const val ABOUT_BOOKMARKS = "about:bookmarks"
        const val ABOUT_LINKS = "about:links"
        const val ABOUT_HISTORY = "about:history"
        const val ABOUT_DOWNLOADS = "about:downloads"
        const val ABOUT_ABOUT = "about:about"
        const val ABOUT_LOG = "about:log"
        const val ABOUT_CATALOG = "about:catalog"
        const val ABOUT_SAVE = "about:save"
        const val ABOUT_BLANK = "about:blank"
        const val TRANSLATE_FAB_IDLE_MS = 8_000L
        const val THUMB_MAX_WIDTH = 360
        const val ABOUT_RES = "about:res"
        const val ABOUT_IMAGES = "about:images"

        /** 收集页面图片的注入脚本（返回 JSON 数组）。 */
        /** a[download] / blob 链接的下载代理（DownloadListener 不接管这些链接）。 */
        fun aDownloadJs(secret: String): String {
            val quoted = org.json.JSONObject.quote(secret)
            return "(function(){if(window.__siiDlHooked)return;window.__siiDlHooked=true;" +
            "document.addEventListener('click',function(e){var t=e.target;" +
            "var a=t&&t.closest?t.closest('a[download]'):null;if(!a)return;" +
            "var href=a.href||'';if(!href)return;e.preventDefault();" +
            "var name=a.getAttribute('download')||'';" +
            "if(href.indexOf('blob:')===0){fetch(href).then(function(r){return r.blob();})" +
            ".then(function(b){var f=new FileReader();f.onload=function(){var i=f.result.indexOf(',');" +
            "window.sii.saveBlob(name||'download',f.result.substring(i+1)," + quoted + ");};f.readAsDataURL(b);});" +
            "}else if(href.indexOf('http')===0){try{window.sii.download(" + quoted + ",href,null);}catch(err){}}" +
            "},true);})();"
        }

        /** WebRTC 防泄露：文档开始时禁用 RTCPeerConnection 系列 API。 */        const val WEBRTC_BLOCK_JS = "(function(){try{" +
            "window.RTCPeerConnection=undefined;" +
            "window.webkitRTCPeerConnection=undefined;" +
            "window.RTCDataChannel=undefined;" +
            "window.RTCIceCandidate=undefined;" +
            "window.RTCSessionDescription=undefined;" +
            "}catch(e){}})();"

        private const val IMAGES_COLLECT_JS = "(function(){" +
            "var out=[];var imgs=document.images;" +
            "for(var i=0;i<imgs.length;i++){var s=imgs[i].currentSrc||imgs[i].src;" +
            "if(s&&s.indexOf('http')===0){out.push({u:s,a:imgs[i].alt||''});}}" +
            "return JSON.stringify(out);})()"

        /** 剪贴板劫持防护：阻止网站静默写入剪贴板。 */
        const val CLIPBOARD_GUARD_JS = "(function(){" +
            "if(window.__siiClipGuard)return;window.__siiClipGuard=true;" +
            "try{var o=document.execCommand;document.execCommand=function(c){" +
            "var v=String(c).toLowerCase();" +
            "if(v==='copy'||v==='paste')return false;return o.apply(document,arguments);};}catch(e){}" +
            "try{if(navigator.clipboard){" +
            "navigator.clipboard.writeText=function(){return Promise.reject(new Error('blocked'));};" +
            "navigator.clipboard.readText=function(){return Promise.reject(new Error('blocked'));};" +
            "navigator.clipboard.read=function(){return Promise.reject(new Error('blocked'));};" +
            "}}catch(e){}" +
            "})();"

        /** 本地判断页面语言：以可见文本统计为主，声明语言只作兜底。 */
        val TRANSLATE_DETECT_JS = """
            (function(){
              try{
                var declared=(document.documentElement.lang||'').toLowerCase();
                var han=0,kana=0,hangul=0,cyr=0,latin=0,total=0,scanned=0;
                var walker=document.createTreeWalker(document.body, NodeFilter.SHOW_TEXT, null);
                var node;
                while((node=walker.nextNode()) && scanned<4000 && total<1500){
                  scanned++;
                  var p=node.parentElement;
                  if(!p) continue;
                  var tag=p.tagName;
                  if(tag==='SCRIPT'||tag==='STYLE'||tag==='NOSCRIPT'||tag==='TEXTAREA'||tag==='CODE'||tag==='PRE') continue;
                  var r=p.getBoundingClientRect();
                  if(r.width<=0||r.height<=0) continue;
                  var t=node.nodeValue||'';
                  total+=t.length;
                  han+=(t.match(/[\u4e00-\u9fff]/g)||[]).length;
                  kana+=(t.match(/[\u3040-\u30ff]/g)||[]).length;
                  hangul+=(t.match(/[\uac00-\ud7af]/g)||[]).length;
                  cyr+=(t.match(/[\u0400-\u04ff]/g)||[]).length;
                  latin+=(t.match(/[A-Za-z]/g)||[]).length;
                }
                if(total<20) return JSON.stringify({lang:'',script:'',needRetry:true});
                var script='';
                if(kana/total>=0.05) script='ja';
                else if(hangul/total>=0.2) script='ko';
                else if(han/total>=0.1 && han>=latin) script='zh';
                else if(cyr/total>=0.2) script='ru';
                else if(latin/total>=0.6) script='latin';
                if(!script) script=declared;
                return JSON.stringify({lang:'',script:script,needRetry:false});
              }catch(e){return '{}';}
            })();
        """.trimIndent()

        /** 刷新/重开时按 sessionStorage 译文缓存先回填，避免闪回原文。 */
        val TRANSLATE_CACHE_RESTORE_JS = """
            (function(){
              try{
                var key='__siiT:'+location.origin+location.pathname+location.search;
                var cache=JSON.parse(sessionStorage.getItem(key)||'{}');
                if(!cache||!Object.keys(cache).length) return;
                var DUAL=__DUAL__;
                function fill(){
                  if(!document.body) return;
                  var w=document.createTreeWalker(document.body,NodeFilter.SHOW_TEXT,null);
                  var n;
                  while(n=w.nextNode()){
                    if(n.__siiTranslated) continue;
                    var v=cache[n.nodeValue];
                    if(v===undefined) continue;
                    n.__siiOrig=n.nodeValue;
                    n.__siiTrans=v;
                    if(DUAL){
                      var p=n.parentNode;
                      if(!p) continue;
                      var span=document.createElement('span');
                      span.setAttribute('data-sii-tr','1');
                      span.style.cssText='display:inline;background:rgba(127,127,127,.16);border-radius:4px;padding:0 3px;margin:0 2px;color:inherit;font:inherit;';
                      span.textContent=' '+v;
                      p.insertBefore(span,n.nextSibling);
                      n.__siiSpan=span;
                    }else{
                      n.nodeValue=v;
                    }
                    n.__siiTranslated=true;
                    window.__siiAll=window.__siiAll||[];
                    window.__siiAll.push(n);
                  }
                }
                function start(){
                  fill();
                  if(window.__siiRestoreObs) window.__siiRestoreObs.disconnect();
                  window.__siiRestoreObs=new MutationObserver(fill);
                  window.__siiRestoreObs.observe(document.body,{childList:true,subtree:true});
                }
                if(document.body) start(); else document.addEventListener('DOMContentLoaded',start);
              }catch(e){}
            })();
        """.trimIndent()

        /** 整页翻译：按文档顺序遍历文本节点，保留内联标签结构。 */
        val TRANSLATE_COLLECT_JS = """
            (function(){
              if(window.__siiTranslate) return JSON.stringify({done:true});
              window.__siiTranslate={els:[],texts:[],pending:[],all:[],count:0,mode:'translated'};
              // 译文缓存（原文→译文）：菜单收起再展开时节点被重建，直接命中回填。
              window.__siiTCache={};
              window.__siiDual=__DUAL__;
              try{
                var saved=sessionStorage.getItem('__siiT:'+location.origin+location.pathname+location.search);
                if(saved) window.__siiTCache=JSON.parse(saved)||{};
              }catch(e){}
              window.__siiPersist=function(){
                try{
                  var c=window.__siiTCache||{};
                  var keys=Object.keys(c);
                  if(keys.length>1500) keys=keys.slice(keys.length-1500);
                  var out={};
                  for(var i=0;i<keys.length;i++) out[keys[i]]=c[keys[i]];
                  sessionStorage.setItem('__siiT:'+location.origin+location.pathname+location.search,JSON.stringify(out));
                }catch(e){}
              };
              window.__siiDirty=true;
              window.__siiScanCount={visible:0,hidden:0};
              var SKIP={SCRIPT:1,STYLE:1,NOSCRIPT:1,CODE:1,PRE:1,TEXTAREA:1,INPUT:1,SELECT:1,SVG:1,CANVAS:1,IFRAME:1,MATH:1,VIDEO:1,AUDIO:1};
              function hasLetter(s){
                for(var i=0;i<s.length;i++){
                  var c=s.charCodeAt(i);
                  if((c>=65&&c<=90)||(c>=97&&c<=122)||(c>=0x00C0&&c<=0x024F)||
                     (c>=0x0370&&c<=0x04FF)||(c>=0x3040&&c<=0x30FF)||
                     (c>=0x4E00&&c<=0x9FFF)||(c>=0xAC00&&c<=0xD7AF)||
                     (c>=0x0600&&c<=0x06FF)||(c>=0x0E00&&c<=0x0E7F)) return true;
                }
                return false;
              }
              function accept(node){
                if(!node.nodeValue||!node.nodeValue.trim()) return false;
                if(!hasLetter(node.nodeValue)) return false;
                var p=node.parentElement;
                if(!p) return false;
                if(SKIP[p.tagName]) return false;
                try{if(p.closest&&p.closest('[data-sii-tr]')) return false;}catch(e){}
                // 不排除隐藏元素：站点的二级菜单/折叠面板默认 display:none，
                // 隐藏文本一并翻译，展开即是译文。
                return true;
              }
              // 双语对照：原文保留，译文插在节点后；关闭时直接替换原文。
              window.__siiSetTranslated=function(node,text){
                if(!node||text==null) return;
                node.__siiTrans=text;
                if(window.__siiDual){
                  var p=node.parentNode;
                  if(!p) return;
                  var span=node.__siiSpan;
                  if(!span||span.parentNode!==p){
                    span=document.createElement('span');
                    span.setAttribute('data-sii-tr','1');
                    span.style.cssText='display:inline;background:rgba(127,127,127,.16);border-radius:4px;padding:0 3px;margin:0 2px;color:inherit;font:inherit;';
                    p.insertBefore(span,node.nextSibling);
                    node.__siiSpan=span;
                  }
                  span.textContent=' '+text;
                  span.style.display='';
                }else{
                  node.nodeValue=text;
                }
              };
              window.__siiObserve=function(root){
                if(!root||!window.__siiObserver||root.__siiObserved) return;
                try{window.__siiObserver.observe(root,{childList:true,subtree:true});root.__siiObserved=true;}catch(e){}
              };
              // 扫描根：document.body + 开放 Shadow DOM + 同源 iframe。
              window.__siiRoots=function(){
                var roots=[];
                function add(r){if(r&&roots.indexOf(r)<0) roots.push(r);}
                add(document.body);
                try{
                  var els=document.querySelectorAll('*');
                  for(var i=0;i<els.length;i++){var sr=els[i].shadowRoot;if(sr) add(sr);}
                }catch(e){}
                try{
                  var frames=document.querySelectorAll('iframe');
                  for(var i=0;i<frames.length;i++){
                    try{var doc=frames[i].contentDocument;if(doc&&doc.body) add(doc.body);}catch(e){}
                  }
                }catch(e){}
                return roots;
              };
              window.__siiScan=function(limit,force){
                limit=limit||150;
                var t=window.__siiTranslate;
                if(!t) return '[]';
                if(t.count>=__TOTAL_LIMIT__) return '[]';
                if(!force&&!window.__siiDirty) return '[]';
                var added=[];
                var roots=window.__siiRoots();
                var info={roots:roots.length,cacheHits:0,visible:0,hidden:0};
                var processed=0;
                var exhausted=true;
                for(var r=0;r<roots.length&&processed<limit;r++){
                  window.__siiObserve(roots[r]);
                  var walker;
                  try{walker=document.createTreeWalker(roots[r], NodeFilter.SHOW_TEXT, null);}catch(e){continue;}
                  var node;
                  while(processed<limit && (node=walker.nextNode())){
                    if(node.__siiTranslated) continue;
                    if(!accept(node)) continue;
                    node.__siiOrig=node.nodeValue;
                    var rect=node.parentElement.getBoundingClientRect();
                    if(rect.width>0&&rect.height>0){window.__siiScanCount.visible++;info.visible++;}
                    else {window.__siiScanCount.hidden++;info.hidden++;}
                    var cached=window.__siiTCache[node.__siiOrig];
                    if(cached!==undefined){
                      // 缓存命中：直接回填译文，不进翻译队列。
                      node.__siiTrans=cached;
                      window.__siiSetTranslated(node,cached);
                      node.__siiTranslated=true;
                      window.__siiAll=window.__siiAll||[];
                      window.__siiAll.push(node);
                      t.count++;
                      info.cacheHits++;
                      continue;
                    }
                    node.__siiTranslated=true;
                    t.pending.push(node);
                    added.push(node.__siiOrig);
                    t.count++;
                    processed++;
                    if(t.count>=__TOTAL_LIMIT__){exhausted=false;break;}
                  }
                  if(processed>=limit||t.count>=__TOTAL_LIMIT__) exhausted=false;
                }
                info.added=added.length;
                info.exhausted=exhausted;
                window.__siiLastScanInfo=info;
                // 没扫完就保持 dirty，下一轮继续，避免长文只翻开头一段。
                window.__siiDirty=!exhausted;
                return JSON.stringify(added);
              };
              window.__siiApplyPending=function(v){
                var t=window.__siiTranslate;
                if(!t) return;
                window.__siiAll=window.__siiAll||[];
                var p=t.pending||[];
                var n=Math.min(v.length,p.length);
                for(var i=0;i<n;i++){
                  if(v[i]){var e=p[i];window.__siiSetTranslated(e,v[i]);window.__siiAll.push(e);window.__siiTCache[e.__siiOrig]=v[i];}
                }
                p.splice(0,n);
                if(n>0&&window.__siiPersist) window.__siiPersist();
              };
              window.__siiToggle=function(){
                var list=window.__siiAll||[];
                var showOrig=(window.__siiMode!=='original');
                window.__siiMode=showOrig?'original':'translated';
                for(var i=0;i<list.length;i++){
                  var e=list[i];
                  if(window.__siiDual){
                    if(e.__siiSpan) e.__siiSpan.style.display=showOrig?'none':'';
                  }else if(e.__siiTrans!==undefined&&e.__siiOrig!==undefined){
                    e.nodeValue=showOrig?e.__siiOrig:e.__siiTrans;
                  }
                }
                return window.__siiMode;
              };
              // 节点重建时立即用译文缓存回填，不等 2 秒轮询。
              window.__siiFillCached=function(){
                var t=window.__siiTranslate,c=window.__siiTCache;
                if(!t||!c) return;
                var roots=window.__siiRoots?window.__siiRoots():[document.body];
                var filled=0;
                for(var r=0;r<roots.length&&filled<2000;r++){
                  var walker;
                  try{walker=document.createTreeWalker(roots[r],NodeFilter.SHOW_TEXT,null);}catch(e){continue;}
                  var node;
                  while((node=walker.nextNode())&&filled<2000){
                    if(node.__siiTranslated) continue;
                    if(!accept(node)) continue;
                    var cached=c[node.nodeValue];
                    if(cached===undefined) continue;
                    node.__siiOrig=node.nodeValue;
                    node.__siiTrans=cached;
                    window.__siiSetTranslated(node,cached);
                    node.__siiTranslated=true;
                    window.__siiAll=window.__siiAll||[];
                    window.__siiAll.push(node);
                    t.count++;
                    filled++;
                  }
                }
                if(filled>0) window.__siiLastFill=filled;
              };
              var fillPending=false;
              var first=JSON.parse(window.__siiScan(__COLLECT_LIMIT__,true));
              window.__siiTranslate.els=window.__siiTranslate.pending.slice();
              window.__siiTranslate.texts=first;
              window.__siiTranslate.pending=[];
              window.__siiObserver=new MutationObserver(function(){
                window.__siiDirty=true;
                if(fillPending) return;
                fillPending=true;
                requestAnimationFrame(function(){
                  fillPending=false;
                  try{window.__siiFillCached();}catch(e){}
                });
              });
              window.__siiObserver.observe(document.body,{childList:true,subtree:true});
              return JSON.stringify({texts:first,visible:window.__siiScanCount.visible,hidden:window.__siiScanCount.hidden});
            })();
        """.trimIndent()

        /** 恢复原文并清理扫描函数。 */
        val TRANSLATE_RESTORE_JS = """
            (function(){
              var t=window.__siiTranslate;
              if(t){
                (window.__siiAll||[]).forEach(function(e){if(e.__siiSpan&&e.__siiSpan.parentNode)e.__siiSpan.parentNode.removeChild(e.__siiSpan);e.__siiSpan=null;if(e.__siiOrig!==undefined)e.nodeValue=e.__siiOrig;});
                t.els.forEach(function(e){if(e.__siiSpan&&e.__siiSpan.parentNode)e.__siiSpan.parentNode.removeChild(e.__siiSpan);e.__siiSpan=null;if(e.__siiOrig!==undefined)e.nodeValue=e.__siiOrig;});
                (t.pending||[]).forEach(function(e){if(e.__siiSpan&&e.__siiSpan.parentNode)e.__siiSpan.parentNode.removeChild(e.__siiSpan);e.__siiSpan=null;if(e.__siiOrig!==undefined)e.nodeValue=e.__siiOrig;});
              }
              if(window.__siiObserver){window.__siiObserver.disconnect();window.__siiObserver=null;}
              if(window.__siiRestoreObs){window.__siiRestoreObs.disconnect();window.__siiRestoreObs=null;}
              try{sessionStorage.removeItem('__siiT:'+location.origin+location.pathname+location.search);}catch(e){}
              window.__siiDirty=false;
              window.__siiAll=null;
              window.__siiMode=null;
              window.__siiTranslate=null;
              window.__siiTCache=null;
              window.__siiScan=null;
              window.__siiApplyPending=null;
              window.__siiRoots=null;
              window.__siiObserve=null;
              window.__siiLastScanInfo=null;
              window.__siiFillCached=null;
              window.__siiPersist=null;
            })();
        """.trimIndent()

        /** B 站跳转客户端失败遮罩清理；所有 B 站页面注入，点击后短时多次清理。 */
        const val BILIBILI_OVERLAY_JS = "(function(){" +
            "if(window.__siiBiliClean)return;window.__siiBiliClean=true;" +
            "function clean(){" +
            "var list=document.querySelectorAll('div,section');" +
            "for(var i=0;i<list.length;i++){" +
            "var el=list[i];" +
            "if(el.childElementCount>40)continue;" +
            "var t=el.textContent||'';" +
            "if(t.indexOf('未能成功跳转客户端')<0&&t.indexOf('下载哔哩哔哩App')<0)continue;" +
            "var root=el;" +
            "for(var j=0;j<6&&root.parentElement;j++){" +
            "root=root.parentElement;" +
            "var cs=getComputedStyle(root);" +
            "if(cs.position==='fixed'||(cs.position==='absolute'&&root.offsetWidth>window.innerWidth*0.7))break;}" +
            "var close=root.querySelector('[class*=close],[aria-label*=关闭],[aria-label*=close]');" +
            "if(close&&close.click)close.click();" +
            "root.style.setProperty('display','none','important');" +
            "var all=document.querySelectorAll('body *');" +
            "for(var k=0;k<all.length;k++){" +
            "var n=all[k];" +
            "if(n===root||root.contains(n))continue;" +
            "var ns=getComputedStyle(n);" +
            "if((ns.position==='fixed'||ns.position==='absolute')&&" +
            "n.offsetWidth>=window.innerWidth*0.9&&n.offsetHeight>=window.innerHeight*0.9&&" +
            "!n.querySelector('video,canvas')&&!(n.textContent||'').trim()){" +
            "n.style.setProperty('display','none','important');}}" +
            "if(document.body)document.body.style.removeProperty('overflow');" +
            "return true;}" +
            "return false;}" +
            "clean();" +
            "setTimeout(clean,1000);setTimeout(clean,3000);" +
            "document.addEventListener('click',function(){" +
            "setTimeout(clean,300);setTimeout(clean,1000);setTimeout(clean,2500);setTimeout(clean,5000);" +
            "},true);" +
            "})();"

        /** 网页强制暗色：注入自写样式（暗色 CSS 注入）。 */
        const val NIGHT_CSS_JS = "(function(){" +
            "function apply(){" +
            "if(!document.documentElement)return false;" +
            "var s=document.getElementById('sii_night_css');" +
            "if(!s){s=document.createElement('style');s.id='sii_night_css';document.documentElement.appendChild(s);}" +
            "s.textContent='html{background-color:#000!important;color-scheme:dark}" +
            "body{background-color:transparent!important}" +
            "*{color:#ddd!important;background-color:transparent!important;box-shadow:none!important;border-color:#444!important}" +
            ":before,:after{background-color:transparent!important;border-color:#444!important}" +
            "h1,h2,h3,h4,h5,h6,strong,b,[class*=header],[id*=header],[class*=headline]{color:#ddd!important;-webkit-text-fill-color:#ddd!important}" +
            "a,a *{color:#ddd!important;-webkit-text-fill-color:#ddd!important;text-decoration:none!important}" +
            "a:visited,a:visited *{color:#ddd!important;-webkit-text-fill-color:#ddd!important}" +
            "input,textarea,select,button,[role=button]{background-color:#060606!important;color:#bbb!important;border-color:#333!important}" +
            "input:not([type=checkbox]):not([type=radio]):hover,textarea:hover,select:hover,button:hover,[role=button]:hover{background-color:#151515!important;color:#fff!important}" +
            "input:not([type=checkbox]):not([type=radio]):active,textarea:active,select:active,button:active,[role=button]:active{background-color:#292929!important;color:#fff!important}" +
            "button:disabled,input:disabled,select:disabled,textarea:disabled{opacity:.5!important;border-color:#333!important}" +
            "input::placeholder,textarea::placeholder{color:#777!important}" +
            "input:focus,textarea:focus,select:focus,button:focus{outline:0!important;border-color:#555!important;box-shadow:0 0 5px #077!important}" +
            "input[type=checkbox],input[type=radio],input[type=range]{accent-color:#377!important}" +
            "input[type=checkbox]:checked,input[type=radio]:checked{border-color:#077!important;box-shadow:0 0 5px #077!important}" +
            "select option{background-color:#222!important;color:#ddd!important}" +
            "select option:checked{background-color:#333!important;color:#fff!important}" +
            "a:hover,a:hover *{color:#fff!important;-webkit-text-fill-color:#fff!important}" +
            ".selected,.selected *,.active,.active *,[aria-selected=true]{color:#ddd!important}" +
            "[role=menu] [role=menuitem]:hover,[class*=menu] a:hover,[class*=dropdown] a:hover{background-color:#151515!important;color:#fff!important}" +
            "table{background-color:rgba(40,30,30,.6)!important}" +
            "tr:nth-child(even),td:nth-child(even){background-color:rgba(0,0,0,.2)!important}" +
            "pre,code{background-color:rgba(5,5,5,.5)!important;color:#ddd!important}" +
            "[role=dialog],[class*=modal],[class*=dialog],[class*=popup],[class*=menu],nav,header,footer{background-color:rgba(19,19,19,.9)!important}" +
            "::selection{background-color:#377!important;color:#000!important}" +
            "img{opacity:.7!important}img:hover{opacity:1!important}" +
            "video,canvas,iframe,svg,picture{background-color:transparent!important;opacity:1!important}';" +
            "setTimeout(function(){var hs=document.querySelectorAll('h1,h2,h3,h4,h5,h6,[class*=title],[class*=headline]');for(var i=0;i<hs.length;i++){var cs=getComputedStyle(hs[i]);if(cs.webkitTextFillColor==='rgba(0, 0, 0, 0)'||cs.color==='rgba(0, 0, 0, 0)'){hs[i].style.setProperty('-webkit-text-fill-color','#ddd','important');hs[i].style.setProperty('color','#ddd','important');}}},400);" +
            "return true;}" +
            "if(!apply()){var t=setInterval(function(){if(apply())clearInterval(t);},30);" +
            "setTimeout(function(){clearInterval(t);},3000);}})();"
        val DOMAIN_PATTERN = Regex("^[a-zA-Z0-9-]+(\\.[a-zA-Z0-9-]+)+(/.*)?$")
        const val MENU_NEW_TAB = 1
        const val MENU_REFRESH = 2
        const val MENU_STOP = 3
        const val MENU_NIGHT_MODE = 4
        const val MENU_SHARE = 5
        const val MENU_COPY = 6
        const val MENU_SETTINGS = 7
        const val MENU_DOWNLOAD_PAGE = 8
        const val MENU_DOWNLOADS = 9
        const val MENU_ADD_BOOKMARK = 10
        const val MENU_BOOKMARKS = 11
        const val MENU_HISTORY = 12
        const val MENU_FIND = 13
        const val MENU_DESKTOP = 14
        const val REQ_POST_NOTIFICATIONS = 100

        /** 从分享文本里提取首个链接。 */
        private val URL_PATTERN = Regex("https?://\\S+")

        const val DESKTOP_UA =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
                "(KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"
        const val DEFAULT_MOBILE_UA =
            "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 " +
                "(KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36"

        /**
         * 电脑模式：把响应式页面的 viewport 宽度改成 1280。
         * 只处理本来就有 viewport meta 的页面（移动/响应式站，如 sspai.com）；
         * 没有 viewport 的桌面页面（如 bilibili.com）完全不干预，避免触发重排闪烁。
         * 同时移除 user-scalable/maximum-scale 限制，恢复双指缩放。
         */
        val VIEWPORT_DESKTOP_JS = """
            (function(){
              function apply(){
                try {
                  var head = document.getElementsByTagName('head')[0];
                  if (!head) return;
                  var old = document.querySelectorAll('meta[name="viewport"]');
                  var meta = null;
                  for (var i = 0; i < old.length; i++) {
                    if (old[i].hasAttribute('data-sii-width')) meta = old[i];
                  }
                  if (!meta && old.length === 0) return;
                  if (!meta) {
                    meta = old[0];
                    meta.setAttribute('data-sii-width', window.innerWidth);
                  }
                  for (var i = 0; i < old.length; i++) {
                    if (old[i] !== meta) old[i].remove();
                  }
                  var w = parseInt(meta.getAttribute('data-sii-width')) || window.innerWidth;
                  var next = 'width=' + Math.max(1280, w) + ', user-scalable=1';
                  if (meta.getAttribute('content') !== next) meta.setAttribute('content', next);
                  if (meta.parentNode !== head) head.appendChild(meta);
                } catch (e) {}
              }
              apply();
              window.addEventListener('load', apply);
            })();
        """.trimIndent()
    }
}

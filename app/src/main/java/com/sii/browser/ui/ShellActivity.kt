// SPDX-License-Identifier: LicenseRef-Sii-NC
// Copyright (c) 2026 wanwuwww
package com.sii.browser.ui

import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.app.Activity
import android.os.Build
import android.os.Bundle
import android.view.KeyEvent
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.appcompat.app.AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM
import androidx.appcompat.app.AppCompatDelegate.MODE_NIGHT_NO
import androidx.appcompat.app.AppCompatDelegate.MODE_NIGHT_YES
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.fragment.app.Fragment
import androidx.lifecycle.Lifecycle
import kotlinx.coroutines.launch
import com.sii.browser.R
import com.sii.browser.data.ExitCleanup
import com.sii.browser.data.Settings
import com.sii.browser.di.AppServices

/**
 * 主窗口容器。
 *
 * 职责：主题路由、首次启动引导路由、按键/Intent 分发、软键盘观察、
 * 配置变更重算主题。浏览器 UI 全部委托给 [BrowserFragment]。
 */
class ShellActivity : AppCompatActivity() {

    private lateinit var settings: Settings
    private var softInputAssist: SoftInputAssistObserver? = null
    private var firstWindowFocus = false
    private var chromeImageActive = false

    companion object {
        /** 夜间切换 recreate 前置位；新 Activity onCreate 消费后清除（进程内标志，冷启动不淡入）。 */
        private var nightSwitchPending = false

        /** attachBaseContext 的原始上下文（outer context 是 Activity，供打印等系统服务使用）。 */
        private var printBaseRef: java.lang.ref.WeakReference<Context>? = null

        fun printServiceContext(): Context? = printBaseRef?.get()

        private var restoreTabsAfterRecreate = false

        /** 切换前旧主题的底色，用于新界面的过渡遮罩。 */
        private var pendingFromColor = 0

        /** 夜间/白天切换：置位后 recreate，新实例恢复全部标签并淡入。 */
        fun smoothRecreateForNightSwitch(activity: Activity) {
            nightSwitchPending = true
            restoreTabsAfterRecreate = true
            // 设置已写入切换后的状态，这里取切换前的底色。
            pendingFromColor = if (AppServices.settings.isNightMode()) {
                0xFFFFFFFF.toInt()
            } else {
                0xFF000000.toInt()
            }
            AppServices.captureScrollForRecreate?.invoke()
            activity.recreate()
        }

        fun consumeRestoreTabsAfterRecreate(): Boolean {
            val value = restoreTabsAfterRecreate
            restoreTabsAfterRecreate = false
            return value
        }
    }
    private var chromeImageLight = false
    private var chromeImageUri: String? = null
    private var chromeImageDrawable: android.graphics.drawable.BitmapDrawable? = null
    private var appliedDialogTheme = 0

    override fun attachBaseContext(newBase: Context) {
        printBaseRef = java.lang.ref.WeakReference(newBase)
        val config = Configuration(newBase.resources.configuration)
        val language = AppServices.settings.language()
        if (!language.isNullOrEmpty()) {
            config.setLocale(java.util.Locale(language))
        }
        // AppCompat 1.7 会在 attachBaseContext2 里按 delegate 的夜间模式重算 uiMode，
        // 手动覆写 uiMode 会被覆盖；必须把应用夜间状态先同步给 delegate，
        // 强制暗色才能作用于 values-night 资源解析。
        AppCompatDelegate.setDefaultNightMode(
            when {
                AppServices.settings.nightForceDark() -> MODE_NIGHT_YES
                AppServices.settings.nightFollowSystem() -> MODE_NIGHT_FOLLOW_SYSTEM
                else -> MODE_NIGHT_NO
            }
        )
        val uiScale = AppServices.settings.uiScale().coerceIn(50, 150)
        if (uiScale != 100) {
            config.densityDpi = (config.densityDpi * uiScale / 100).coerceIn(72, 640)
        }
        super.attachBaseContext(newBase.createConfigurationContext(config))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        settings = AppServices.settings
        applyTheme()
        super.onCreate(savedInstanceState)
        com.sii.browser.data.StartupExit.readAndMarkRunning(this)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            window.attributes.layoutInDisplayCutoutMode =
                android.view.WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
        }
        setContentView(R.layout.activity_shell)
        applySystemBarInsets()
        applyStatusBarColor()
        applyOrientation()
        // 夜间/白天切换后：旧主题底色盖住新界面再淡出，同时内容淡入，避免黑白突变。
        if (nightSwitchPending) {
            nightSwitchPending = false
            val content = findViewById<View>(R.id.shell_root)
            content.alpha = 0f
            content.animate()
                .alpha(1f)
                .setDuration(480)
                .setInterpolator(android.view.animation.AccelerateInterpolator())
                .start()
            val root = findViewById<android.view.ViewGroup>(android.R.id.content)
            if (root != null) {
                val cover = View(this).apply { setBackgroundColor(pendingFromColor) }
                root.addView(
                    cover,
                    android.view.ViewGroup.LayoutParams(
                        android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                        android.view.ViewGroup.LayoutParams.MATCH_PARENT
                    )
                )
                cover.animate()
                    .alpha(0f)
                    .setDuration(480)
                    .setInterpolator(android.view.animation.DecelerateInterpolator())
                    .withEndAction { root.removeView(cover) }
                    .start()
            }
        }

        if (savedInstanceState == null) {
            if (settings.agreementVersion() >= 1) {
                showBrowser()
            } else {
                showFirstLaunch()
            }
        }

        // MCP 开关开启时恢复监听；配置未变不会重启已运行的服务。
        runCatching { McpRuntime.sync(this) }

        softInputAssist = SoftInputAssistObserver(this) { visible ->
            notifyFragments<SoftInputAware>().forEach { it.onSoftInputChanged(visible) }
        }.also { observer ->
            lifecycle.addObserver(observer)
        }
        if (savedInstanceState == null) {
            window.decorView.post {
                supportFragmentManager.executePendingTransactions()
                notifyFragments<IntentAware>().forEach { it.handleNewIntent(intent) }
            }
        }
    }

    /**
     * 状态栏与地址栏同色：传入 null 时用默认工具栏底色（白天白、夜间黑）。
     * 网站主题色由浏览器页传入具体颜色。
     */
    fun applyStatusBarColor(color: Int? = null) {
        if (chromeImageActive) {
            window.statusBarColor = android.graphics.Color.TRANSPARENT
            WindowCompat.getInsetsController(window, window.decorView)
                .isAppearanceLightStatusBars = chromeImageLight
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                window.isStatusBarContrastEnforced = false
            }
            return
        }
        val colorInt = color ?: ContextCompat.getColor(this, R.color.toolbar_bg)
        // 状态栏由系统单独绘制，逐帧动画会有闪烁感，统一直接赋值。
        window.statusBarColor = colorInt
        findViewById<View>(R.id.shell_root)?.setBackgroundColor(colorInt)
        WindowCompat.getInsetsController(window, window.decorView)
            .isAppearanceLightStatusBars =
            if (color == null) !settings.isNightMode() else isLightColor(colorInt)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            window.isStatusBarContrastEnforced = false
        }
    }

    /** 主页背景图：铺到窗口根容器并延伸到状态栏；传 null 恢复纯色系统栏。 */
    fun applyChromeBackground(uri: String?, overlayPercent: Int = 0) {
        val root = findViewById<View>(R.id.shell_root) ?: return
        if (uri.isNullOrBlank()) {
            chromeImageActive = false
            chromeImageUri = null
            chromeImageDrawable = null
            root.setBackgroundColor(ContextCompat.getColor(this, R.color.toolbar_bg))
            applyStatusBarColor(null)
            return
        }
        val drawable = if (uri == chromeImageUri && chromeImageDrawable != null) {
            chromeImageDrawable
        } else {
            decodeChromeBackground(uri)?.let {
                android.graphics.drawable.BitmapDrawable(resources, it).apply {
                    gravity = android.view.Gravity.FILL
                }
            }
        }
        if (drawable == null) {
            applyChromeBackground(null)
            return
        }
        chromeImageActive = true
        chromeImageUri = uri
        chromeImageDrawable = drawable
        chromeImageLight = isLightBitmap(drawable.bitmap)
        val overlay = overlayPercent.coerceIn(0, 100)
        root.background = if (overlay > 0) {
            android.graphics.drawable.LayerDrawable(
                arrayOf(
                    drawable,
                    android.graphics.drawable.ColorDrawable(
                        android.graphics.Color.argb(overlay * 255 / 100, 0, 0, 0)
                    )
                )
            )
        } else drawable
        window.statusBarColor = android.graphics.Color.TRANSPARENT
        WindowCompat.getInsetsController(window, window.decorView)
            .isAppearanceLightStatusBars = chromeImageLight
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            window.isStatusBarContrastEnforced = false
        }
    }

    /** 背景图是否为浅色；未启用图片时返回 null。 */
    fun chromeImageIsLight(): Boolean? = if (chromeImageActive) chromeImageLight else null

    private fun decodeChromeBackground(uri: String): android.graphics.Bitmap? = runCatching {
        val bounds = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
        contentResolver.openInputStream(android.net.Uri.parse(uri))?.use {
            android.graphics.BitmapFactory.decodeStream(it, null, bounds)
        }
        var sample = 1
        val maxW = resources.displayMetrics.widthPixels.coerceAtLeast(1)
        while (bounds.outWidth / sample > maxW * 2 && sample < 8) sample *= 2
        val options = android.graphics.BitmapFactory.Options().apply { inSampleSize = sample }
        contentResolver.openInputStream(android.net.Uri.parse(uri))?.use {
            android.graphics.BitmapFactory.decodeStream(it, null, options)
        }
    }.getOrNull()

    private fun isLightBitmap(bitmap: android.graphics.Bitmap): Boolean {
        val stepX = (bitmap.width / 24).coerceAtLeast(1)
        val stepY = (bitmap.height / 24).coerceAtLeast(1)
        var sum = 0.0
        var count = 0
        var y = 0
        while (y < bitmap.height) {
            var x = 0
            while (x < bitmap.width) {
                val color = bitmap.getPixel(x, y)
                sum += 0.2126 * android.graphics.Color.red(color) +
                    0.7152 * android.graphics.Color.green(color) +
                    0.0722 * android.graphics.Color.blue(color)
                count++
                x += stepX
            }
            y += stepY
        }
        return count == 0 || sum / count > 153
    }

    /** 按相对亮度判断状态栏图标用深色还是浅色。 */
    private fun isLightColor(color: Int): Boolean {
        val r = android.graphics.Color.red(color) / 255.0
        val g = android.graphics.Color.green(color) / 255.0
        val b = android.graphics.Color.blue(color) / 255.0
        return (0.2126 * r + 0.7152 * g + 0.0722 * b) > 0.6
    }

    /** 弹窗主题跟随新版/经典、主色和日夜；只改皮肤，不动任何弹窗逻辑。 */
    fun refreshDialogTheme() = applyDialogTheme()

    private fun applyDialogTheme() {
        val modern = settings.uiStyle() == 1
        val night = settings.isNightMode()
        val style = when {
            modern && night -> when (settings.skin()) {
                "green" -> R.style.ThemeOverlay_Sii_DialogModernGreen
                "orange" -> R.style.ThemeOverlay_Sii_DialogModernOrange
                "purple" -> R.style.ThemeOverlay_Sii_DialogModernPurple
                else -> R.style.ThemeOverlay_Sii_DialogModern
            }
            night -> when (settings.skin()) {
                "green" -> R.style.ThemeOverlay_Sii_DialogClassicNightGreen
                "orange" -> R.style.ThemeOverlay_Sii_DialogClassicNightOrange
                "purple" -> R.style.ThemeOverlay_Sii_DialogClassicNightPurple
                else -> R.style.ThemeOverlay_Sii_DialogClassicNight
            }
            else -> when (settings.skin()) {
                "green" -> R.style.ThemeOverlay_Sii_DialogClassicGreen
                "orange" -> R.style.ThemeOverlay_Sii_DialogClassicOrange
                "purple" -> R.style.ThemeOverlay_Sii_DialogClassicPurple
                else -> R.style.ThemeOverlay_Sii_DialogClassic
            }
        }
        if (style == appliedDialogTheme) return
        appliedDialogTheme = style
        theme.applyStyle(style, true)
    }

    /**
     * 处理系统栏与输入法 insets（targetSdk 35+ 强制 edge-to-edge，避免内容被状态栏/导航栏/键盘遮挡）。
     * adjustResize 在 edge-to-edge 下不再生效，键盘高度只能从 insets 获取。
     */
    private fun applySystemBarInsets() {
        val root = findViewById<View>(R.id.shell_root) ?: return
        ViewCompat.setOnApplyWindowInsetsListener(root) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            val ime = insets.getInsets(WindowInsetsCompat.Type.ime())
            // 经典工具栏不随键盘上移；悬浮胶囊只在地址栏编辑等场景让出键盘高度。
            val bottom = if (settings.uiStyle() == 1 && !
                com.sii.browser.di.AppServices.homeSearchImeSuspend
            ) {
                maxOf(bars.bottom, ime.bottom)
            } else {
                bars.bottom
            }
            view.setPadding(
                bars.left,
                bars.top,
                bars.right,
                bottom
            )
            insets
        }
    }

    /** 主页搜索聚焦状态变化后重新计算窗口 insets。 */
    fun refreshImeInsets() {
        findViewById<View>(R.id.shell_root)?.let {
            androidx.core.view.ViewCompat.requestApplyInsets(it)
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        notifyFragments<IntentAware>().forEach {
            if (it.handleNewIntent(intent)) return
        }
    }

    override fun dispatchTouchEvent(ev: android.view.MotionEvent): Boolean {
        val handled = super.dispatchTouchEvent(ev)
        // 事件先交给视图树，控制器层只用同一份事件做“点击搜索框外部收起焦点”的判定。
        notifyFragments<GlobalTouchAware>().forEach { it.onGlobalTouch(ev) }
        return handled
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        val fragment = supportFragmentManager.findFragmentById(R.id.shell_root)
        if (fragment is KeyEventSink && fragment.dispatchKeyEventInternal(event)) {
            return true
        }
        return super.dispatchKeyEvent(event)
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        // 系统日夜变化会重建 Activity 重取颜色资源，这里只通知已存在的页面。
        notifyFragments<ThemeAware>().forEach { it.onThemeChanged(settings.isNightMode()) }
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus && !firstWindowFocus) {
            firstWindowFocus = true
            window.decorView.post {
                windowManager.updateViewLayout(window.decorView, window.attributes)
            }
        }
        notifyFragments<FocusAware>().forEach { it.onWindowFocusGained(hasFocus) }
    }

    override fun onResume() {
        super.onResume()
        applyDialogTheme()
        // 任务被移除时由该服务接收 onTaskRemoved；只放后台不会清理。
        // 始终启动，才能区分「从最近任务划掉」（正常退出）与「被系统杀掉」（异常退出）。
        runCatching {
            startService(Intent(this, SessionCleanupService::class.java))
        }
    }

    override fun onDestroy() {
        if (isFinishing && !isChangingConfigurations) {
            ExitCleanup.clearNow()
            com.sii.browser.data.StartupExit.markClean(this)
        }
        super.onDestroy()
        softInputAssist?.let { lifecycle.removeObserver(it) }
    }

    private fun applyTheme() {
        val night = settings.isNightMode()
        val style = when (settings.skin()) {
            "green" -> if (night) R.style.Theme_Sii_Night_Green else R.style.Theme_Sii_Green
            "orange" -> if (night) R.style.Theme_Sii_Night_Orange else R.style.Theme_Sii_Orange
            "purple" -> if (night) R.style.Theme_Sii_Night_Purple else R.style.Theme_Sii_Purple
            else -> if (night) R.style.Theme_Sii_Night else R.style.Theme_Sii
        }
        setTheme(style)
        applyDialogTheme()
    }

    /** 应用「屏幕方向」设置（0 自动 / 1 横屏 / 2 竖屏）。 */
    fun applyOrientation() {
        requestedOrientation = when (settings.screenOrientation()) {
            1 -> android.content.pm.ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
            2 -> android.content.pm.ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
            else -> android.content.pm.ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        }
    }

    private fun showBrowser() {
        supportFragmentManager.beginTransaction()
            .replace(R.id.shell_root, BrowserFragment())
            .commit()
    }

    private fun showFirstLaunch() {
        supportFragmentManager.beginTransaction()
            .replace(R.id.shell_root, FirstLaunchFragment())
            .commit()
    }

    private inline fun <reified T> notifyFragments(): List<T> =
        supportFragmentManager.fragments.filterIsInstance<T>()
}

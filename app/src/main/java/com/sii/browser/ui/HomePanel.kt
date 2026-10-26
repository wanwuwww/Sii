package com.sii.browser.ui

import android.app.AlertDialog
import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.text.Editable
import android.text.TextUtils
import android.text.TextWatcher
import android.view.Gravity
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.GridLayout
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.PopupWindow
import android.widget.TextView
import com.sii.browser.R
import com.sii.browser.data.db.FavoriteItem
import com.sii.browser.di.AppServices
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 主页（about:home）原生实现，照着设计稿的 homeView：
 * logo 文字 + 搜索框 + 提示 + 常用网站网格（4 列，可编辑增删）。
 */
class HomePanel(
    private val context: Context,
    private val onSearch: (String) -> Unit,
    private val onOpen: (String) -> Unit,
    private val onScan: () -> Unit,
    private val onSuggest: (String, (List<Suggestion>) -> Unit) -> Unit,
    private val previewMode: Boolean = false
) {

    val view: View = LayoutInflater.from(context).inflate(R.layout.view_home, null, false)

    private val searchInput = view.findViewById<EditText>(R.id.home_search_input)
    private val searchBox = view.findViewById<View>(R.id.home_search_box)
    private val searchScan = view.findViewById<ImageButton>(R.id.home_scan)
    private val searchBoxElevation = searchBox.elevation
    private val grid = view.findViewById<GridLayout>(R.id.home_sites)
    private val editButton = view.findViewById<TextView>(R.id.home_edit)

    private var editing = false
    private var sites: List<FavoriteItem> = emptyList()
    private var suggestionPopup: SuggestionPopup? = null
    private var suggestionsSuppressed = false
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var bgJob: Job? = null
    private var bgKey: String? = null
    private var suggestJob: Job? = null
    private var siteDragItem: FavoriteItem? = null
    private var siteDragView: View? = null
    private var siteDragStartX = 0f
    private var siteDragStartY = 0f
    private var siteDragMode = false

    private val clockHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private val clockTick = object : Runnable {
        override fun run() {
            updateClock()
            if (view.isAttachedToWindow && AppServices.settings.homeLogoMode() == 2) {
                clockHandler.postDelayed(this, 15_000L)
            }
        }
    }

    init {
        searchInput.setOnEditorActionListener { _, actionId, event ->            if (actionId == EditorInfo.IME_ACTION_GO ||
                (event != null && event.keyCode == KeyEvent.KEYCODE_ENTER)
            ) {
                submitSearch()
                true
            } else false
        }
        searchInput.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                val query = s?.toString().orEmpty().trim()
                if (query.isEmpty()) {
                    showClipboardSuggestion()
                } else {
                    suggestionsSuppressed = false
                    suggestJob?.cancel()
                    suggestJob = scope.launch {
                        kotlinx.coroutines.delay(300)
                        if (suggestionsSuppressed) return@launch
                        onSuggest(query) { items ->
                            if (suggestionsSuppressed) return@onSuggest
                            if (searchInput.text.toString().trim() == query) showSuggestions(items)
                        }
                    }
                }
            }
        })
        view.findViewById<ImageButton>(R.id.home_search_go).setOnClickListener { submitSearch() }
        view.findViewById<ImageButton>(R.id.home_scan).setOnClickListener {
            exitSearchInput()
            onScan()
        }
        grid.setOnDragListener { _, event -> handleFavoriteDrag(event) }
        view.setOnClickListener {
            if (siteDragMode) exitSiteDragMode()
        }
        if (previewMode) {
            searchInput.isFocusable = false
            searchInput.isClickable = false
            searchInput.isLongClickable = false
            searchInput.setOnTouchListener { _, _ -> true }
            view.findViewById<View>(R.id.home_search_go).visibility = View.GONE
            view.findViewById<View>(R.id.home_scan).visibility = View.GONE
            editButton.visibility = View.GONE
        }
        view.addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
            override fun onViewAttachedToWindow(v: View) = startClock()
            override fun onViewDetachedFromWindow(v: View) = stopClock()
        })
        searchInput.setOnFocusChangeListener { _, hasFocus ->
            if (hasFocus) {
                searchBox.animate()
                    .scaleX(1.02f)
                    .scaleY(1.02f)
                    .setDuration(180L)
                    .setInterpolator(android.view.animation.DecelerateInterpolator())
                    .start()
                if (AppServices.settings.uiStyle() == 1) {
                    applySearchStyle(focused = true)
                } else {
                    searchBox.elevation = dp(8).toFloat()
                    searchBox.backgroundTintList = android.content.res.ColorStateList.valueOf(
                        context.getColor(R.color.accent_soft)
                    )
                }
                searchScan.imageTintList = android.content.res.ColorStateList.valueOf(
                    ThemeAccent.color(context)
                )
                if (searchInput.text.isEmpty()) showClipboardSuggestion()
            } else {
                searchBox.animate()
                    .scaleX(1f)
                    .scaleY(1f)
                    .setDuration(140L)
                    .setInterpolator(android.view.animation.DecelerateInterpolator())
                    .start()
                searchBox.elevation = searchBoxElevation
                searchBox.backgroundTintList = null
                applySearchStyle()
                hideKeyboard()
                hideSuggestions()
            }
        }
        view.isClickable = true
        view.setOnClickListener { exitSearchInput() }
        editButton.setOnClickListener {
            exitSearchInput()
            editing = !editing
            editButton.text = context.getString(
                if (editing) R.string.home_sites_done else R.string.home_sites_edit
            )
            render()
        }
        applyHomeStyle()
    }

    /** 按设置调整主页搜索框（连同 logo）的垂直位置。 */
    fun applyLayoutPos() {
        val root = view.findViewById<LinearLayout>(R.id.home_content) ?: return
        val pos = AppServices.settings.homeLayoutPos()
        root.gravity = when (pos) {
            0 -> android.view.Gravity.TOP
            2 -> android.view.Gravity.BOTTOM
            else -> android.view.Gravity.CENTER_VERTICAL
        }
        val logo = view.findViewById<TextView>(R.id.home_logo) ?: return
        val modern = AppServices.settings.uiStyle() == 1
        val baseTop = when (pos) {
            0 -> dp(36)
            2 -> dp(0)
            else -> dp(72)
        }
        val top = (baseTop - if (modern) dp(20) else 0).coerceAtLeast(0)
        (logo.layoutParams as? LinearLayout.LayoutParams)?.let { lp ->
            lp.topMargin = top
            logo.layoutParams = lp
        }
        view.findViewById<ImageView>(R.id.home_logo_icon)?.let { icon ->
            (icon.layoutParams as? LinearLayout.LayoutParams)?.let { lp ->
                lp.topMargin = top
                icon.layoutParams = lp
            }
        }
        view.findViewById<View>(R.id.home_clock)?.let { clock ->
            (clock.layoutParams as? LinearLayout.LayoutParams)?.let { lp ->
                lp.topMargin = top
                clock.layoutParams = lp
            }
        }
        applySearchBoxOrder(pos)
    }

    /** 顶部布局时搜索框与 Logo 互换：搜索框置顶，Logo/图标/时间位于其下。 */
    private fun applySearchBoxOrder(pos: Int) {
        val content = view.findViewById<LinearLayout>(R.id.home_content) ?: return
        val box = view.findViewById<View>(R.id.home_search_box) ?: return
        if (pos == 0) {
            if (content.indexOfChild(box) != 0) {
                content.removeView(box)
                content.addView(box, 0)
            }
            return
        }
        val anchor = listOfNotNull(
            view.findViewById<View>(R.id.home_logo),
            view.findViewById<View>(R.id.home_logo_icon),
            view.findViewById<View>(R.id.home_clock)
        ).maxOfOrNull { content.indexOfChild(it) } ?: return
        if (content.indexOfChild(box) != anchor + 1) {
            content.removeView(box)
            content.addView(box, (anchor + 1).coerceAtMost(content.childCount))
        }
    }

    /** 应用主页样式：位置、Logo、列数、图标、搜索框、背景与名称。 */
    fun applyHomeStyle() {
        applyLayoutPos()
        val modern = AppServices.settings.uiStyle() == 1
        val s = AppServices.settings
        val style = s.homeStyle()
        // 专注样式没有 Logo，横向留白加大，让搜索框像一张独立卡片。
        val side = if (style == 3) 32 else 20
        view.findViewById<View>(R.id.home_content)?.setPadding(
            dp(side), dp(20), dp(side), if (modern) dp(96) else dp(20)
        )
        // 专注样式：搜索框下方给一行操作提示。
        view.findViewById<View>(R.id.home_tip)?.visibility =
            if (style == 3 && s.homeFocusTip()) View.VISIBLE else View.GONE
        val logo = view.findViewById<TextView>(R.id.home_logo)
        val logoIcon = view.findViewById<ImageView>(R.id.home_logo_icon)
        // 专注（3）与快捷宫格（4）不显示 Logo，把空间留给搜索框与图标。
        val mode = when (style) {
            2 -> if (s.homeMagazineClock()) 2 else -1
            3, 4 -> -1
            else -> s.homeLogoMode()
        }
        logo?.visibility = if (mode == 0) View.VISIBLE else View.GONE
        logoIcon?.visibility = if (mode == 1) View.VISIBLE else View.GONE
        view.findViewById<View>(R.id.home_clock)?.visibility =
            if (mode == 2) View.VISIBLE else View.GONE
        if (logoIcon != null) {
            val size = dp(s.homeLogoIconSize().coerceIn(24, 120))
            (logoIcon.layoutParams as? LinearLayout.LayoutParams)?.let { lp ->
                lp.width = size
                lp.height = size
                lp.bottomMargin = dp(22)
                lp.gravity = Gravity.CENTER_HORIZONTAL
                logoIcon.layoutParams = lp
            }
        }
        if (mode == 2) updateClock() else stopClock()
        logo?.text = s.homeLogoText().ifBlank { "Sii" }
        val logoSize = s.homeLogoSize().takeIf { it > 0 } ?: 34
        logo?.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, logoSize.coerceIn(16, 64).toFloat())
        logo?.setTypeface(
            null,
            if (s.homeLogoWeight() == 1) android.graphics.Typeface.BOLD
            else android.graphics.Typeface.NORMAL
        )
        val colorValue = s.homeLogoColor()
        when {
            colorValue == "accent" -> logo?.setTextColor(ThemeAccent.color(context))
            !colorValue.isNullOrBlank() -> parseColorValue(colorValue)?.let { logo?.setTextColor(it) }
            else -> logo?.setTextColor(context.getColor(R.color.text_primary))
        }
        grid.removeAllViews()
        grid.columnCount = when (style) {
            1 -> 4
            2 -> s.homeMagazineColumns().coerceIn(2, 3)
            3 -> 4
            4 -> s.homeGridColumns().coerceIn(4, 6)
            5 -> 1
            else -> s.homeGridColumns().coerceIn(4, 6)
        }
        applyListSurface(style)
        applySearchStyle()
        applyBackground()
        applySitesVisibility()
        render()
    }

    private fun parseColorValue(value: String?): Int? {
        if (value.isNullOrBlank()) return null
        return runCatching { Color.parseColor(value) }.getOrNull()
    }

    /** 列表样式：常用网站整体包在圆角卡片里，行间用细线分隔。 */
    private fun applyListSurface(style: Int) {
        if (style != 5) {
            grid.background = null
            grid.setPadding(0, 0, 0, 0)
            return
        }
        val dark = AppServices.settings.isNightMode()
        grid.background = android.graphics.drawable.GradientDrawable().apply {
            shape = android.graphics.drawable.GradientDrawable.RECTANGLE
            cornerRadius = dp(18).toFloat()
            setColor(if (dark) 0xFF1B1E23.toInt() else 0xFFFFFFFF.toInt())
            setStroke(dp(1), if (dark) 0x1FFFFFFF else 0x14000000)
        }
        grid.setPadding(0, dp(4), 0, dp(4))
    }

    private fun applySitesVisibility() {
        // 专注样式只留搜索入口，不显示常用网站。
        val show = AppServices.settings.showSites() &&
            AppServices.settings.homeStyle() != 3
        val card = view.findViewById<View>(R.id.home_sites_card)
        card?.visibility = if (show) View.VISIBLE else View.GONE
        applySitesCardBg(card)
        view.findViewById<View>(R.id.home_sites_title)?.visibility =
            if (show && AppServices.settings.homeShowSitesTitle()) View.VISIBLE else View.GONE
        grid.visibility = if (show) View.VISIBLE else View.GONE
    }

    /** 常用网站卡片背景：关闭后站点直接浮在主页背景上。 */
    private fun applySitesCardBg(card: View?) {
        card ?: return
        val on = AppServices.settings.homeSitesCardBg()
        val modern = AppServices.settings.uiStyle() == 1
        val dark = AppServices.settings.isNightMode()
        val style = AppServices.settings.homeStyle()
        card.background = when {
            style != 0 -> null
            !on -> null
            modern -> GlassStyle.background(
                context, dark, dp(24).toFloat(), AppServices.settings.glassStrength(), minAlpha = 200
            )
            else -> context.getDrawable(R.drawable.bg_home_card)
        }
        card.elevation = if (on && style == 0) dp(2).toFloat() else 0f
    }

    /** 搜索框圆角与高度（高度用上下内边距实现，不挤压内部按钮）。 */
    private fun applySearchStyle(focused: Boolean = false) {
        val box = view.findViewById<LinearLayout>(R.id.home_search_box) ?: return
        val homeStyle = AppServices.settings.homeStyle()
        val modern = AppServices.settings.uiStyle() == 1
        val dark = modern && AppServices.settings.isNightMode()
        val baseRadius = when (AppServices.settings.homeSearchRadius()) {
            0 -> 8
            2 -> 16
            3 -> AppServices.settings.homeSearchRadiusCustom().coerceIn(0, 48)
            else -> 12
        }
        val radius = if (modern) baseRadius.coerceAtLeast(22) else baseRadius
        val baseColor = if (dark) 0xFF1B1E23.toInt() else context.getColor(R.color.card)
        val alpha = (AppServices.settings.homeSearchAlpha().coerceIn(0, 100) * 255 / 100)
        val color = (alpha shl 24) or (baseColor and 0x00FFFFFF)
        box.background = GradientDrawable().apply {
            cornerRadius = dp(radius).toFloat()
            setColor(color)
            setStroke(
                dp(1),
                when {
                    modern && focused -> ThemeAccent.color(context)
                    dark -> 0x24FFFFFF
                    else -> context.getColor(R.color.line)
                }
            )
        }
        box.elevation = if (modern) {
            dp(if (focused) 10 else 4).toFloat()
        } else if (focused) {
            dp(8).toFloat()
        } else {
            searchBoxElevation
        }
        val padV = if (homeStyle == 3) {
            9
        } else {
            when (AppServices.settings.homeSearchHeight()) {
                0 -> 2
                2 -> 8
                else -> 4
            }
        }
        box.setPadding(dp(8), dp(padV), dp(4), dp(padV))
        val normalIcon = if (dark) 0xCCFFFFFF.toInt()
        else context.getColor(R.color.text_secondary)
        searchScan.imageTintList = android.content.res.ColorStateList.valueOf(
            if (focused) ThemeAccent.color(context) else normalIcon
        )
        view.findViewById<ImageButton>(R.id.home_search_go)?.imageTintList =
            if (modern) android.content.res.ColorStateList.valueOf(ThemeAccent.color(context))
            else null
        if (dark) {
            searchInput.setTextColor(android.graphics.Color.WHITE)
            searchInput.setHintTextColor(0x99FFFFFF.toInt())
        } else {
            searchInput.setTextColor(context.getColor(R.color.text_primary))
            searchInput.setHintTextColor(context.getColor(R.color.text_secondary))
        }
        // 极简 / 杂志：细边框无阴影；专注：大圆角悬浮胶囊，作为页面唯一主角。
        if (homeStyle != 0) {
            val focus = homeStyle == 3
            box.background = GradientDrawable().apply {
                cornerRadius = dp(if (focus) 26 else 12).toFloat()
                setColor(if (focus) color else 0x00000000)
                setStroke(dp(1), if (dark) 0x33FFFFFF else 0x26000000)
            }
            box.elevation = if (focus) dp(6).toFloat() else 0f
        }
    }

    /** 应用主页背景：有图时异步采样解码并缓存，避免每次回主页阻塞主线程。 */
    private fun applyBackground() {
        val uri = runCatching { AppServices.settings.homeBackground() }.getOrNull()
        if (uri.isNullOrBlank()) {
            bgJob?.cancel()
            bgJob = null
            bgKey = null
            view.background = null
            applyBgColor()
            return
        }
        val blur = blurPercent()
        val overlay = AppServices.settings.homeBgOverlay().coerceIn(0, 100)
        val targetWidth = context.resources.displayMetrics.widthPixels.coerceAtLeast(1)
        val key = "$uri|$blur|$overlay|$targetWidth"
        if (key == bgKey && view.background != null) return
        bgKey = key
        applyBgColor()
        bgJob?.cancel()
        bgJob = scope.launch {
            val drawable = withContext(Dispatchers.IO) {
                decodeBackground(uri, blur, overlay, targetWidth)
            }
            if (drawable != null && bgKey == key) view.background = drawable
        }
    }

    private fun decodeBackground(
        uri: String,
        blur: Int,
        overlay: Int,
        targetWidth: Int
    ): android.graphics.drawable.Drawable? {
        val resolver = context.contentResolver
        val bounds = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
        runCatching {
            resolver.openInputStream(android.net.Uri.parse(uri))?.use {
                android.graphics.BitmapFactory.decodeStream(it, null, bounds)
            }
        }
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        var sample = 1
        while (bounds.outWidth / (sample * 2) >= targetWidth) sample *= 2
        val options = android.graphics.BitmapFactory.Options().apply { inSampleSize = sample }
        val decoded = runCatching {
            resolver.openInputStream(android.net.Uri.parse(uri))?.use {
                android.graphics.BitmapFactory.decodeStream(it, null, options)
            }
        }.getOrNull() ?: return null
        val shown = blurBitmap(decoded, blur, targetWidth)
        val layers = mutableListOf<android.graphics.drawable.Drawable>(
            android.graphics.drawable.BitmapDrawable(context.resources, shown)
        )
        if (overlay > 0) {
            layers.add(
                android.graphics.drawable.ColorDrawable(
                    Color.argb(overlay * 255 / 100, 0, 0, 0)
                )
            )
        }
        return android.graphics.drawable.LayerDrawable(layers.toTypedArray())
    }

    /** 当前主页背景色；0 = 跟随主题。 */
    fun backgroundColor(): Int {
        val index = AppServices.settings.homeBgColor()
        if (index <= 0) return context.getColor(R.color.bg_page)
        val colors = intArrayOf(
            0xFFFFFFFF.toInt(),
            0xFFF3F4F6.toInt(),
            0xFFEAF2FF.toInt(),
            0xFFEAF7EF.toInt(),
            0xFF2B2F36.toInt(),
            0xFF000000.toInt()
        )
        return colors[(index - 1).coerceIn(0, colors.size - 1)]
    }

    /** 无背景图时的背景色；0 = 跟随主题。 */
    private fun applyBgColor() {
        view.setBackgroundColor(backgroundColor())
    }

    /** 背景图模糊：缩小再放大，档位越高越糊。 */
    private fun blurPercent(): Int {
        val percent = AppServices.settings.homeBgBlurPercent()
        if (percent >= 0) return percent.coerceIn(0, 100)
        return when (AppServices.settings.homeBgBlur()) {
            1 -> 33
            2 -> 66
            3 -> 100
            else -> 0
        }
    }

    /** 先缩到屏幕宽度，再按模糊档位缩小；BitmapDrawable 负责拉伸，避免全尺寸副本。 */
    private fun blurBitmap(src: android.graphics.Bitmap, percent: Int, targetWidth: Int): android.graphics.Bitmap {
        val base = if (src.width > targetWidth) {
            android.graphics.Bitmap.createScaledBitmap(
                src, targetWidth, (src.height * targetWidth / src.width).coerceAtLeast(1), true
            )
        } else src
        if (base !== src) src.recycle()
        val p = percent.coerceIn(0, 100)
        if (p <= 0) return base
        val scale = 1f + p / 100f * 19f
        val w = (base.width / scale).toInt().coerceAtLeast(1)
        val h = (base.height / scale).toInt().coerceAtLeast(1)
        val small = android.graphics.Bitmap.createScaledBitmap(base, w, h, true)
        if (small !== base) base.recycle()
        return small
    }

    private fun submitSearch() {
        suggestionsSuppressed = true
        exitSearchInput()
        val text = searchInput.text.toString().trim()
        if (text.isNotEmpty()) onSearch(text)
    }

    private fun exitSearchInput() {
        searchInput.clearFocus()
        hideKeyboard()
        hideSuggestions()
    }

    private fun hideKeyboard() {
        val imm = context.getSystemService(Context.INPUT_METHOD_SERVICE)
            as? android.view.inputmethod.InputMethodManager ?: return
        imm.hideSoftInputFromWindow(searchInput.windowToken, 0)
    }

    /** 返回主页时清掉上一次搜索留下的网址。 */
    fun clearSearch() {
        suggestionsSuppressed = true
        hideSuggestions()
        searchInput.setText("")
    }

    private fun showSuggestions(items: List<Suggestion>, preferAbove: Boolean = false) {
        // 本地结果为空时只收起弹层，不置 suppressed，保持后续联网结果可用。
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
            context, searchBox
        ) { item ->
            pickSuggestion(item)
        }.also { suggestionPopup = it }
        popup.show(sorted, preferAbove)
    }

    private fun showClipboardSuggestion() {
        if (!searchInput.hasFocus() || AppServices.settings.searchSuggestion() == 0 ||
            !AppServices.settings.autoFillClipboard()
        ) {
            hideSuggestions()
            return
        }
        val clips = ClipboardSuggestion.readAll(context)
        if (clips.isEmpty()) {
            hideSuggestions()
            return
        }
        showSuggestions(clips, preferAbove = true)
    }

    private fun pickSuggestion(item: Suggestion) {
        when (item.kind) {
            SuggestionKind.CLIPBOARD_TEXT, SuggestionKind.CLIPBOARD_MIXED -> {
                searchInput.setText(item.value)
                searchInput.setSelection(item.value.length)
                hideSuggestions()
                searchInput.requestFocus()
            }
            SuggestionKind.CLIPBOARD_LINK -> {
                hideSuggestions()
                hideKeyboard()
                searchInput.clearFocus()
                onOpen(item.value)
            }
            else -> {
                searchInput.setText(item.value)
                searchInput.setSelection(item.value.length)
                submitSearch()
            }
        }
    }

    private fun hideSuggestions() {
        suggestionsSuppressed = true
        suggestionPopup?.dismiss()
    }

    /** 浏览器离开主页时收起主页建议浮层。 */
    fun dismissSuggestions() = hideSuggestions()

    /** View 销毁时释放浮层、背景解码任务与图片引用。 */
    fun release() {
        hideSuggestions()
        stopClock()
        bgJob?.cancel()
        bgJob = null
        scope.cancel()
        bgKey = null
        view.background = null
    }

    /** 时间 Logo 刷新：只在时间模式且页面可见时计时。 */
    private fun startClock() {
        clockHandler.removeCallbacks(clockTick)
        if (AppServices.settings.homeLogoMode() != 2) return
        updateClock()
        clockHandler.postDelayed(clockTick, 15_000L)
    }

    private fun stopClock() {
        clockHandler.removeCallbacks(clockTick)
    }

    private fun updateClock() {
        val timeView = view.findViewById<TextView>(R.id.home_clock_time) ?: return
        val dateView = view.findViewById<TextView>(R.id.home_clock_date)
        val now = java.util.Calendar.getInstance()
        timeView.text = String.format(
            java.util.Locale.getDefault(),
            "%02d:%02d",
            now.get(java.util.Calendar.HOUR_OF_DAY),
            now.get(java.util.Calendar.MINUTE)
        )
        dateView?.text = String.format(
            java.util.Locale.getDefault(),
            "%d月%d日 %s",
            now.get(java.util.Calendar.MONTH) + 1,
            now.get(java.util.Calendar.DAY_OF_MONTH),
            now.getDisplayName(
                java.util.Calendar.DAY_OF_WEEK,
                java.util.Calendar.SHORT,
                java.util.Locale.getDefault()
            )
        )
        val colorValue = AppServices.settings.homeLogoColor()
        val color = when {
            colorValue == "accent" -> ThemeAccent.color(context)
            !colorValue.isNullOrBlank() -> parseColorValue(colorValue)
                ?: context.getColor(R.color.text_primary)
            else -> context.getColor(R.color.text_primary)
        }
        timeView.setTextColor(color)
        dateView?.setTextColor(context.getColor(R.color.text_secondary))
    }

    fun bind(items: List<FavoriteItem>) {
        sites = items
        applyBackground()
        applySitesVisibility()
        render()
    }

    /** 主页入场动画：Logo、搜索框、站点卡逐个出现，站点错峰上浮。 */
    fun playIntro() {
        if (previewMode || !view.isAttachedToWindow) return
        val logoText = view.findViewById<TextView>(R.id.home_logo)
        val logoIcon = view.findViewById<ImageView>(R.id.home_logo_icon)
        val logoView: View? = if (logoIcon?.visibility == View.VISIBLE) logoIcon else logoText
        val card = view.findViewById<View>(R.id.home_sites_card)
        val targets = listOfNotNull(logoView, searchBox, card)
        targets.forEach { it.animate().cancel() }
        val cells = (0 until grid.childCount).map { grid.getChildAt(it) }
        if (!android.animation.ValueAnimator.areAnimatorsEnabled()) {
            targets.forEach { it.alpha = 1f; it.translationY = 0f }
            searchBox.scaleX = 1f
            searchBox.scaleY = 1f
            cells.forEach { it.alpha = 1f; it.translationY = 0f }
            return
        }
        logoView?.alpha = 0f
        logoView?.translationY = -dp(14).toFloat()
        searchBox.alpha = 0f
        searchBox.translationY = dp(10).toFloat()
        searchBox.scaleX = 0.97f
        searchBox.scaleY = 0.97f
        card?.alpha = 0f
        card?.translationY = dp(18).toFloat()
        cells.forEach { it.alpha = 0f; it.translationY = dp(10).toFloat() }

        val interpolator = android.view.animation.DecelerateInterpolator()
        logoView?.animate()?.alpha(1f)?.translationY(0f)?.setDuration(320L)
            ?.setInterpolator(interpolator)?.start()
        searchBox.animate().alpha(1f).translationY(0f).scaleX(1f).scaleY(1f)
            .setDuration(320L).setStartDelay(70L).setInterpolator(interpolator).start()
        card?.animate()?.alpha(1f)?.translationY(0f)?.setDuration(320L)
            ?.setStartDelay(140L)?.setInterpolator(interpolator)?.start()
        cells.forEachIndexed { index, cell ->
            cell.animate().alpha(1f).translationY(0f).setDuration(260L)
                .setStartDelay(200L + index * 35L)
                .setInterpolator(interpolator).start()
        }
    }

    /** 站点按下缩放，不拦截原有点击/长按/拖拽。 */
    private fun pressable(target: View) {
        if (previewMode) return
        target.setOnTouchListener { v, event ->
            when (event.actionMasked) {
                android.view.MotionEvent.ACTION_DOWN -> v.animate()
                    .scaleX(0.94f).scaleY(0.94f).setDuration(90L).start()
                android.view.MotionEvent.ACTION_UP,
                android.view.MotionEvent.ACTION_CANCEL -> v.animate()
                    .scaleX(1f).scaleY(1f).setDuration(130L).start()
            }
            false
        }
    }

    private fun render() {
        grid.removeAllViews()
        if (AppServices.settings.homeStyle() == 5) {
            sites.forEachIndexed { index, item ->
                grid.addView(listRow(item, showDivider = index > 0))
            }
            if (!previewMode) grid.addView(listRowAdd(showDivider = sites.isNotEmpty()))
            return
        }
        sites.forEach { grid.addView(siteView(it)) }
        if (!previewMode) grid.addView(addView())
    }

    private fun listRow(item: FavoriteItem, showDivider: Boolean): View =
        LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = GridLayout.LayoutParams().apply {
                width = 0
                columnSpec = GridLayout.spec(GridLayout.UNDEFINED, 1f)
            }
            if (showDivider) addView(listDivider())
            addView(listRowContent(item))
        }

    private fun listRowAdd(showDivider: Boolean): View =
        LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = GridLayout.LayoutParams().apply {
                width = 0
                columnSpec = GridLayout.spec(GridLayout.UNDEFINED, 1f)
            }
            if (showDivider) addView(listDivider())
            addView(listAddContent())
        }

    private fun listDivider(): View = View(context).apply {
        layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, dp(1)
        ).apply { marginStart = dp(64) }
        setBackgroundColor(context.getColor(R.color.line))
    }

    private fun listRowContent(item: FavoriteItem): View {
        val name = item.title.ifEmpty { item.url }
        return LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            minimumHeight = dp(56)
            setPadding(dp(16), dp(8), dp(16), dp(8))
            isClickable = true
            tag = item.id
            pressable(this)
            addView(iconView(name.take(1), colorOf(item.url)).apply {
                layoutParams = LinearLayout.LayoutParams(dp(36), dp(36))
            })
            addView(labelView(name).apply {
                gravity = Gravity.START or Gravity.CENTER_VERTICAL
                textSize = 14f
                layoutParams = LinearLayout.LayoutParams(
                    0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f
                ).apply { marginStart = dp(12) }
            })
            addView(TextView(context).apply {
                text = "›"
                textSize = 18f
                gravity = Gravity.CENTER
                setTextColor(context.getColor(R.color.text_secondary))
            })
            if (editing) {
                addView(removeBadge(item.id))
            } else if (!previewMode) {
                setOnClickListener {
                    exitSearchInput()
                    onOpen(item.url)
                }
                setOnLongClickListener {
                    showReorderDialog(item)
                    true
                }
            }
        }
    }

    private fun listAddContent(): View = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        minimumHeight = dp(56)
        setPadding(dp(16), dp(8), dp(16), dp(8))
        isClickable = true
        pressable(this)
        addView(iconView("＋", null).apply {
            layoutParams = LinearLayout.LayoutParams(dp(36), dp(36))
        })
        addView(labelView(context.getString(R.string.home_sites_add)).apply {
            gravity = Gravity.START or Gravity.CENTER_VERTICAL
            textSize = 14f
            layoutParams = LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f
            ).apply { marginStart = dp(12) }
        })
        setOnClickListener { showAddDialog() }
    }

    private fun handleFavoriteDrag(event: android.view.DragEvent): Boolean {
        when (event.action) {
            android.view.DragEvent.ACTION_DRAG_STARTED -> {
                val id = event.clipData?.getItemAt(0)?.text?.toString()?.toLongOrNull()
                if (id != null) {
                    for (i in 0 until grid.childCount) {
                        val child = grid.getChildAt(i)
                        if ((child.tag as? Long) == id) child.alpha = 0.25f
                    }
                }
                return true
            }
            android.view.DragEvent.ACTION_DRAG_LOCATION,
            android.view.DragEvent.ACTION_DRAG_ENTERED -> return true
            android.view.DragEvent.ACTION_DROP -> {
                val id = event.clipData?.getItemAt(0)?.text?.toString()?.toLongOrNull()
                    ?: return false
                val from = sites.indexOfFirst { it.id == id }
                if (from < 0) return false
                val target = favoriteTargetIndex(event.x, event.y)
                if (target != from) {
                    val list = sites.toMutableList()
                    list.add(target, list.removeAt(from))
                    sites = list
                    AppServices.favoriteRepository.let { repo ->
                        scope.launch(Dispatchers.IO) { repo.reorder(list.map { it.id }) }
                    }
                    render()
                    showSiteDeleteBadges()
                }
                return true
            }
            android.view.DragEvent.ACTION_DRAG_ENDED -> {
                for (i in 0 until grid.childCount) grid.getChildAt(i).alpha = 1f
                return true
            }
        }
        return false
    }

    private fun favoriteTargetIndex(x: Float, y: Float): Int {
        var best = 0
        var bestDistance = Float.MAX_VALUE
        var siteIndex = 0
        for (i in 0 until grid.childCount) {
            val child = grid.getChildAt(i)
            if (child.tag !is Long) continue
            val cx = child.left + child.width / 2f
            val cy = child.top + child.height / 2f
            val distance = (cx - x) * (cx - x) + (cy - y) * (cy - y)
            if (distance < bestDistance) {
                bestDistance = distance
                best = siteIndex
            }
            siteIndex++
        }
        return best.coerceIn(0, sites.lastIndex.coerceAtLeast(0))
    }

    private fun exitSiteDragMode() {
        siteDragMode = false
        render()
    }

    private fun showSiteDeleteBadges() {
        for (i in 0 until grid.childCount) {
            val child = grid.getChildAt(i) as? ViewGroup ?: continue
            val id = (child.tag as? Long) ?: continue
            if (child.findViewWithTag<View>("site_delete") != null) continue
            child.addView(removeBadge(id).apply { tag = "site_delete" })
        }
    }

    private fun siteView(item: FavoriteItem): View {
        val name = item.title.ifEmpty { item.url }
        return cell().apply {
            tag = item.id
            pressable(this)
            addView(iconView(name.take(1), colorOf(item.url)))
            addView(labelView(name))
            if (editing) {
                addView(removeBadge(item.id))
                setOnLongClickListener {
                    showReorderDialog(item)
                    true
                }
            } else {
                if (!previewMode) {
                    setOnClickListener {
                        if (siteDragMode) {
                            exitSiteDragMode()
                        } else {
                            exitSearchInput()
                            onOpen(item.url)
                        }
                    }
                    setOnLongClickListener {
                        siteDragMode = true
                        showSiteDeleteBadges()
                        val clip = android.content.ClipData.newPlainText("favorite", item.id.toString())
                        startDragAndDrop(
                            clip,
                            android.view.View.DragShadowBuilder(this),
                            item.id,
                            0
                        )
                        true
                    }
                }
            }
        }
    }

    /** 编辑模式下长按收藏：左移/右移/删除（不改动点击打开行为）。 */
    private fun showReorderDialog(item: FavoriteItem) {
        android.app.AlertDialog.Builder(context)
            .setTitle(item.title.ifEmpty { item.url })
            .setItems(
                arrayOf(
                    context.getString(R.string.favorite_move_left),
                    context.getString(R.string.favorite_move_right),
                    context.getString(R.string.delete)
                )
            ) { _, which ->
                when (which) {
                    0 -> CoroutineScope(Dispatchers.IO).launch {
                        AppServices.favoriteRepository.move(item, up = true)
                    }
                    1 -> CoroutineScope(Dispatchers.IO).launch {
                        AppServices.favoriteRepository.move(item, up = false)
                    }
                    else -> CoroutineScope(Dispatchers.IO).launch {
                        AppServices.favoriteRepository.delete(item.id)
                    }
                }
            }
            .show()
    }

    private fun addView(): View = cell().apply {
        pressable(this)
        addView(iconView("＋", null))
        addView(labelView(context.getString(R.string.home_sites_add)))
        setOnClickListener { showAddDialog() }
    }

    private fun cell(): LinearLayout = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        gravity = Gravity.CENTER
        isClickable = true
        val gap = AppServices.settings.homeCellGap().takeIf { it >= 0 } ?: 8
        layoutParams = GridLayout.LayoutParams().apply {
            width = 0
            columnSpec = GridLayout.spec(GridLayout.UNDEFINED, 1f)
            setMargins(0, dp(gap), 0, dp(gap))
        }
    }

    private fun iconView(letter: String, color: Int?): TextView = TextView(context).apply {
        text = letter
        setTextColor(Color.WHITE)
        textSize = 16f
        gravity = Gravity.CENTER
        val size = when (AppServices.settings.homeIconSize()) {
            0 -> 40
            2 -> 56
            else -> 48
        }
        layoutParams = LinearLayout.LayoutParams(dp(size), dp(size))
        background = GradientDrawable().apply {
            if (AppServices.settings.homeIconShape() == 0) {
                shape = GradientDrawable.OVAL
            } else {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = dp(
                    when (AppServices.settings.homeIconRadius()) {
                        0 -> 0
                        1 -> 8
                        3 -> 20
                        else -> 14
                    }
                ).toFloat()
            }
            val accentOnly = AppServices.settings.homeIconAccent()
            setColor(if (accentOnly) ThemeAccent.color(context) else (color ?: ThemeAccent.color(context)))
        }
    }

    private fun labelView(name: String): TextView = TextView(context).apply {
        text = name
        textSize = (AppServices.settings.homeLabelSize().takeIf { it > 0 } ?: 12).coerceIn(8, 20).toFloat()
        gravity = Gravity.CENTER
        maxLines = 1
        ellipsize = TextUtils.TruncateAt.END
        setTextColor(context.getColor(R.color.text_secondary))
        visibility = if (AppServices.settings.homeShowLabels()) View.VISIBLE else View.GONE
        layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = dp(6) }
    }

    private fun removeBadge(id: Long): TextView = TextView(context).apply {
        text = "×"
        textSize = 12f
        gravity = Gravity.CENTER
        setTextColor(Color.WHITE)
        layoutParams = LinearLayout.LayoutParams(dp(18), dp(18)).apply { topMargin = dp(4) }
        background = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(context.getColor(R.color.danger))
        }
        setOnClickListener {
            CoroutineScope(Dispatchers.IO).launch { AppServices.favoriteRepository.delete(id) }
        }
    }

    private fun showAddDialog() {
        val nameInput = EditText(context).apply { hint = context.getString(R.string.home_title) }
        val urlInput = EditText(context).apply { hint = context.getString(R.string.home_url) }
        val box = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(8), dp(20), 0)
            addView(nameInput)
            addView(urlInput)
        }
        AlertDialog.Builder(context)
            .setTitle(R.string.home_add)
            .setView(box)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                val url = urlInput.text.toString().trim()
                if (url.isNotEmpty()) {
                    CoroutineScope(Dispatchers.IO).launch {
                        AppServices.favoriteRepository.add(url, nameInput.text.toString().trim())
                    }
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun colorOf(url: String): Int {
        var h = 0
        url.forEach { h = h * 31 + it.code }
        return COLORS[Math.floorMod(h, COLORS.size)]
    }

    private fun dp(value: Int): Int =
        (value * context.resources.displayMetrics.density).toInt()

    private companion object {
        val COLORS = intArrayOf(
            0xFF2F7CF6.toInt(), 0xFF34C28F.toInt(), 0xFFF2984A.toInt(), 0xFF8A6FF0.toInt(),
            0xFFE5537A.toInt(), 0xFF3AB3C9.toInt(), 0xFFC9A23A.toInt(), 0xFF6D7F95.toInt()
        )
    }
}

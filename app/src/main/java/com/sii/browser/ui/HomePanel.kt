// SPDX-License-Identifier: LicenseRef-Sii-NC
// Copyright (c) 2026 wanwuwww
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
import android.view.MotionEvent
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
    private val topSearchBox = view.findViewById<View>(R.id.home_top_search)
    private val topSearchInput = view.findViewById<EditText>(R.id.home_top_search_input)
    private val searchScan = view.findViewById<ImageButton>(R.id.home_scan)
    private val searchBoxElevation = searchBox.elevation
    private val grid = view.findViewById<GridLayout>(R.id.home_sites)
    private val editButton = view.findViewById<TextView>(R.id.home_edit)

    private var editing = false
    private var lastSiteStyle: String? = null
    private var sites: List<FavoriteItem> = emptyList()
    private var suggestionPopup: SuggestionPopup? = null
    private var topSuggestionPopup: SuggestionPopup? = null
    private var topSuggestJob: Job? = null
    private var topSuggestionsSuppressed = false
    private var suggestionsSuppressed = false
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var bgJob: Job? = null
    private var bgKey: String? = null
    private var suggestJob: Job? = null
    private var clipboardJob: Job? = null
    private var siteDragItem: FavoriteItem? = null
    private var siteDragView: View? = null
    private var siteDragStartX = 0f
    private var siteDragStartY = 0f
    private var siteDragMode = false
    private var touchStartedInSearch = false
    private var pullStartY = 0f
    private var pullTriggered = false
    private var touchDownX = 0f
    private var touchDownY = 0f
    private var lastImeHeight = 0
    private var lastImeVisible = false
    private var searchFocusActive = false
    private var pendingFocusMove = false
    private var othersDimmed = false
    private var dimmedMainSearch = false
    private var homeOriginalHeight = Int.MIN_VALUE
    private var lockScreenY = Int.MIN_VALUE

    private var searchFocusAnimator: android.animation.ValueAnimator? = null

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
                        kotlinx.coroutines.delay(80)
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
                enterSearchFocus()
                // 放在 enterSearchFocus 之后：它内部的 cancel() 会杀掉刚启动的缩放动画。
                animateSearchFocus(true)
            } else {
                searchBox.elevation = searchBoxElevation
                searchBox.backgroundTintList = null
                applySearchStyle()
                hideKeyboard()
                hideSuggestions()
                exitSearchFocus()
                // 同上：exitSearchFocus 会取消搜索框动画，回收动画必须放在它后面。
                animateSearchFocus(false)
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
        setupPullToSearch()
        setupTopSearchInput()
        androidx.core.view.ViewCompat.setOnApplyWindowInsetsListener(view) { v, insets ->
            lastImeHeight = insets.getInsets(androidx.core.view.WindowInsetsCompat.Type.ime()).bottom
            val imeVisible = insets.isVisible(androidx.core.view.WindowInsetsCompat.Type.ime())
            if (lastImeVisible && !imeVisible &&
                (searchFocusActive || topSearchInput.hasFocus())
            ) {
                // 键盘被系统收起（返回键/收起按钮）等同于取消搜索。
                exitSearchInput()
            }
            lastImeVisible = imeVisible
            if (imeVisible && pendingFocusMove) moveContentForFocus()
            insets
        }
        applyHomeStyle()
    }

    /**
     * 聚焦主页搜索框时锁定内容区高度（而不是滚动区），键盘弹出只缩小视口，
     * 内容子视图不重新测量，常用网站不会闪。
     */
    private fun enterSearchFocus() {
        if (previewMode) return
        setImeSuspend(true)
        searchFocusActive = true
        pendingFocusMove = true
        lockHomeHeight()
        lockScreenPosition()
        lockContentHeight()
        setOthersDimmed(true)
        if (lastImeVisible) moveContentForFocus()
    }

    /** 聚焦时把 Logo / 常用网站淡化，形成轻微变暗的层次。 */
    private fun setOthersDimmed(dim: Boolean, includeMainSearch: Boolean = false) {
        if (othersDimmed == dim && (!dim || dimmedMainSearch == includeMainSearch)) return
        othersDimmed = dim
        dimmedMainSearch = includeMainSearch
        val alpha = if (dim) 0.4f else 1f
        val targets = mutableListOf<View>()
        listOf(
            R.id.home_logo,
            R.id.home_logo_icon,
            R.id.home_clock,
            R.id.home_sites_card
        ).forEach { id ->
            view.findViewById<View>(id)?.let { targets.add(it) }
        }
        // 顶部搜索聚焦时，中间搜索框也要一起退到后面；失焦时总是恢复。
        if ((includeMainSearch || !dim) && searchBox.visibility == View.VISIBLE) {
            targets.add(searchBox)
        } else {
            searchBox.alpha = 1f
        }
        targets.forEach { target ->
            target.animate().cancel()
            target.animate()
                .alpha(alpha)
                .setDuration(160L)
                .setInterpolator(android.view.animation.DecelerateInterpolator())
                .start()
        }
    }

    /**
     * 聚焦期间把主页钉在屏幕上的固定位置。父容器因键盘缩小、重力变化时，
     * 通过 translationY 抵消位移，内容不会再向上跑。
     */
    private val keepScreenPosition = android.view.ViewTreeObserver.OnPreDrawListener {
        if (!searchFocusActive || lockScreenY == Int.MIN_VALUE) {
            true
        } else {
            val loc = IntArray(2)
            view.getLocationOnScreen(loc)
            val layoutY = loc[1] - view.translationY.toInt()
            val dy = lockScreenY - layoutY
            if (kotlin.math.abs(dy) > 1) view.translationY = dy.toFloat()
            true
        }
    }

    private fun lockScreenPosition() {
        if (previewMode || lockScreenY != Int.MIN_VALUE) return
        val loc = IntArray(2)
        view.getLocationOnScreen(loc)
        lockScreenY = loc[1] - view.translationY.toInt()
        view.viewTreeObserver.addOnPreDrawListener(keepScreenPosition)
    }

    private fun unlockScreenPosition() {
        if (lockScreenY == Int.MIN_VALUE) return
        view.viewTreeObserver.removeOnPreDrawListener(keepScreenPosition)
        lockScreenY = Int.MIN_VALUE
        view.translationY = 0f
    }

    /**
     * 锁住主页视图高度：即使键盘弹出让父容器变矮，主页内部也不重新布局，
     * 超出部分由父容器裁剪，用户看到的是键盘直接盖住底栏。
     */
    private fun lockHomeHeight() {
        if (previewMode || homeOriginalHeight != Int.MIN_VALUE) return
        val lp = view.layoutParams ?: return
        if (view.height <= 0) return
        homeOriginalHeight = lp.height
        lp.height = view.height
        view.layoutParams = lp
    }

    private fun unlockHomeHeight() {
        if (homeOriginalHeight == Int.MIN_VALUE) return
        val lp = view.layoutParams ?: return
        lp.height = homeOriginalHeight
        view.layoutParams = lp
        homeOriginalHeight = Int.MIN_VALUE
    }

    /**
     * 锁定内容区最小高度。home_content 的父级是 ScrollView，不是 LinearLayout，
     * 不能改 LinearLayout.LayoutParams；用 minimumHeight 让 fillViewport 无法压缩它。
     */
    private fun lockContentHeight() {
        val content = view.findViewById<View>(R.id.home_content) ?: return
        if (content.height <= 0 || content.minimumHeight > 0) return
        content.minimumHeight = content.height
    }

    private fun restoreContentHeight() {
        val content = view.findViewById<View>(R.id.home_content) ?: return
        content.minimumHeight = 0
    }

    /** 主页搜索聚焦时让键盘直接覆盖悬浮工具栏，不为键盘预留窗口高度。 */
    private fun setImeSuspend(suspend: Boolean) {
        if (previewMode) return
        if (AppServices.homeSearchImeSuspend == suspend) return
        AppServices.homeSearchImeSuspend = suspend
        (context as? ShellActivity)?.refreshImeInsets()
    }

    private fun moveContentForFocus() {
        if (!searchFocusActive) return
        pendingFocusMove = false
        val box = view.findViewById<View>(R.id.home_search_box) ?: return
        val pos = AppServices.settings.homeLayoutPos()
        // 居中布局且关闭常用网站时，只按键盘实际遮挡量上移搜索框，其余布局不动。
        if (pos != 2) {
            if (pos == 1 && !AppServices.settings.showSites() && lastImeHeight > 0) {
                liftSearchBoxAboveIme(box)
            } else {
                box.translationY = 0f
            }
            return
        }
        val lift = if (lastImeHeight > 0) lastImeHeight + dp(12) else dp(120)
        box.animate().cancel()
        box.animate()
            .translationY(-lift.toFloat())
            .setDuration(180L)
            .setInterpolator(android.view.animation.DecelerateInterpolator())
            .start()
    }

    /** 按键盘顶边算搜索框需要上移的距离，只移动到刚好露出为止。 */
    private fun liftSearchBoxAboveIme(box: View) {
        val root = view.rootView
        val rootLocation = IntArray(2)
        root.getLocationOnScreen(rootLocation)
        val visibleBottom = rootLocation[1] + root.height - lastImeHeight - dp(8)
        val boxLocation = IntArray(2)
        box.getLocationOnScreen(boxLocation)
        // 屏幕坐标已包含当前 translationY，先扣除得到布局位置再算重叠量。
        val boxBottom = boxLocation[1] + box.height - box.translationY
        val overlap = boxBottom - visibleBottom
        box.animate().cancel()
        if (overlap > 0) {
            box.animate()
                .translationY(-overlap.toFloat())
                .setDuration(180L)
                .setInterpolator(android.view.animation.DecelerateInterpolator())
                .start()
        } else {
            box.translationY = 0f
        }
    }

    private fun exitSearchFocus() {
        if (!searchFocusActive) return
        searchFocusActive = false
        setImeSuspend(false)
        unlockScreenPosition()
        unlockHomeHeight()
        pendingFocusMove = false
        val content = view.findViewById<View>(R.id.home_content)
        content?.animate()?.cancel()
        content?.translationY = 0f
        val box = view.findViewById<View>(R.id.home_search_box)
        box?.animate()?.cancel()
        box?.translationY = 0f
        // 立即恢复高度，不等动画回调；键盘中途收起时不会卡在半截布局。
        restoreContentHeight()
        setOthersDimmed(false)
    }

    /** 顶部搜索框：可独立输入；中间搜索框在它显示时只负责把焦点转过去。 */
    private fun setupTopSearchInput() {
        topSearchBox.setOnClickListener { focusTopSearch() }
        topSearchInput.setOnFocusChangeListener { _, hasFocus ->
            animateTopSearchFocus(hasFocus)
            topSearchBox.elevation = if (hasFocus) dp(6).toFloat() else 0f
            topSearchIconTint(hasFocus)
            if (hasFocus) {
                setImeSuspend(true)
                lockHomeHeight()
                lockScreenPosition()
                lockContentHeight()
                setOthersDimmed(true, includeMainSearch = true)
            } else {
                // 失焦必须收起建议，否则弹层会跟随页面切换成为幽灵窗口。
                topSuggestionPopup?.dismiss()
                if (!searchFocusActive) {
                    setImeSuspend(false)
                    unlockScreenPosition()
                    unlockHomeHeight()
                    restoreContentHeight()
                    setOthersDimmed(false)
                }
            }
        }
        topSearchInput.setOnEditorActionListener { _, actionId, event ->
            if (actionId == EditorInfo.IME_ACTION_GO ||
                (event != null && event.keyCode == KeyEvent.KEYCODE_ENTER)
            ) {
                submitTopSearch()
                true
            } else false
        }
        topSearchInput.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                val query = s?.toString().orEmpty().trim()
                if (query.isEmpty()) {
                    topSuggestJob?.cancel()
                    topSuggestionPopup?.dismiss()
                    return
                }
                topSuggestionsSuppressed = false
                topSuggestJob?.cancel()
                topSuggestJob = scope.launch {
                    kotlinx.coroutines.delay(80)
                    if (topSuggestionsSuppressed) return@launch
                    onSuggest(query) { items ->
                        if (topSuggestionsSuppressed) return@onSuggest
                        if (topSearchInput.text.toString().trim() == query) {
                            showTopSuggestions(items)
                        }
                    }
                }
            }
        })
        if (!previewMode) {
            searchInput.setOnTouchListener { _, event ->
                if (event.actionMasked == MotionEvent.ACTION_DOWN) {
                    setImeSuspend(true)
                    if (topSearchVisible()) {
                        focusTopSearch()
                        return@setOnTouchListener true
                    }
                }
                false
            }
            topSearchInput.setOnTouchListener { _, event ->
                if (event.actionMasked == MotionEvent.ACTION_DOWN) setImeSuspend(true)
                false
            }
        }
    }

    private fun topSearchVisible(): Boolean = topSearchBox.visibility == View.VISIBLE

    private fun focusTopSearch() {
        topSearchInput.requestFocus()
        topSearchInput.setSelection(topSearchInput.text.length)
        topSearchInput.post {
            val imm = context.getSystemService(Context.INPUT_METHOD_SERVICE)
                as? android.view.inputmethod.InputMethodManager ?: return@post
            imm.showSoftInput(topSearchInput, android.view.inputmethod.InputMethodManager.SHOW_IMPLICIT)
        }
    }

    private fun submitTopSearch() {
        topSuggestionsSuppressed = true
        hideKeyboard(topSearchInput)
        topSearchInput.clearFocus()
        topSuggestionPopup?.dismiss()
        val text = topSearchInput.text.toString().trim()
        if (text.isNotEmpty()) onSearch(text)
    }

    private fun showTopSuggestions(items: List<Suggestion>) {
        if (!topSearchInput.hasFocus()) {
            topSuggestionPopup?.dismiss()
            return
        }
        if (items.isEmpty()) {
            topSuggestionPopup?.dismiss()
            return
        }
        val count = AppServices.settings.suggestionCount().coerceIn(3, 10)
        val sorted = sortSuggestions(items, AppServices.settings.suggestOrder(), count)
        if (sorted.isEmpty()) {
            topSuggestionPopup?.dismiss()
            return
        }
        val popup = topSuggestionPopup ?: SuggestionPopup(
            context, topSearchBox, horizontalShiftDp = 5
        ) { item -> pickTopSuggestion(item) }.also { topSuggestionPopup = it }
        popup.show(sorted)
    }

    private fun pickTopSuggestion(item: Suggestion) {
        topSearchInput.setText(item.value)
        topSearchInput.setSelection(item.value.length)
        submitTopSearch()
    }

    /** 顶部搜索入口只控制主页的快捷入口，底部地址栏不受影响。 */
    private fun applyTopSearchVisibility() {
        val top = view.findViewById<View>(R.id.home_top_search) ?: return
        val show = !previewMode && AppServices.settings.homeTopAddress() &&
            AppServices.settings.homeLayoutPos() != 0
        top.visibility = if (show) View.VISIBLE else View.GONE
        if (!show) {
            topSearchInput.clearFocus()
            topSuggestionPopup?.dismiss(false)
        }
    }

    /** 搜索框聚焦动画：轻微放大并回弹，图标同步缩放；失焦反向恢复。 */
    private fun animateSearchFocus(active: Boolean) {
        if (previewMode) return
        // 用独立 ValueAnimator 而不是 ViewPropertyAnimator：后者与聚焦时的
        // translationY 抬升共用一个实例，互相 cancel 会把缩放留在中间值。
        searchFocusAnimator?.cancel()
        val target = if (active) 1.035f else 1f
        val animator = android.animation.ValueAnimator.ofFloat(searchBox.scaleX, target)
        animator.duration = if (active) 220L else 160L
        animator.interpolator = if (active) {
            android.view.animation.OvershootInterpolator(1.1f)
        } else {
            android.view.animation.DecelerateInterpolator()
        }
        animator.addUpdateListener { animation ->
            val value = animation.animatedValue as Float
            searchBox.scaleX = value
            searchBox.scaleY = value
        }
        animator.start()
        searchFocusAnimator = animator
        listOfNotNull(searchScan, view.findViewById<View>(R.id.home_search_go)).forEach { icon ->
            if (icon.visibility != View.VISIBLE) return@forEach
            icon.animate().cancel()
            if (active) {
                icon.animate().scaleX(1.08f).scaleY(1.08f).setDuration(120L)
                    .withEndAction {
                        icon.animate().scaleX(1f).scaleY(1f).setDuration(140L).start()
                    }
                    .start()
            } else {
                icon.animate().scaleX(1f).scaleY(1f).setDuration(120L).start()
            }
        }
    }

    /** 顶部搜索框同款聚焦动画（框内只有图标与输入框）。 */
    private fun animateTopSearchFocus(active: Boolean) {
        if (previewMode) return
        val scale = if (active) 1.02f else 1f
        topSearchBox.animate().cancel()
        topSearchBox.animate()
            .scaleX(scale)
            .scaleY(scale)
            .setDuration(if (active) 220L else 160L)
            .setInterpolator(
                if (active) android.view.animation.OvershootInterpolator(1.1f)
                else android.view.animation.DecelerateInterpolator()
            )
            .start()
    }

    /** 顶部搜索框左侧图标聚焦时用主题色。 */
    private fun topSearchIconTint(focused: Boolean) {
        val icon = (topSearchBox as? android.view.ViewGroup)?.getChildAt(0) as? ImageView ?: return
        icon.imageTintList = android.content.res.ColorStateList.valueOf(
            if (focused) ThemeAccent.color(context)
            else context.getColor(R.color.text_secondary)
        )
    }

    /** 主页搜索框显示开关：关闭后直接不占位，下拉手势与聚焦逻辑也一并停用。 */
    private fun applySearchVisibility() {
        val show = AppServices.settings.homeSearchShow()
        searchBox.visibility = if (show) View.VISIBLE else View.GONE
        if (show) return
        if (searchInput.hasFocus()) searchInput.clearFocus()
        searchFocusAnimator?.cancel()
        searchBox.animate().cancel()
        searchBox.alpha = 1f
        searchBox.scaleX = 1f
        searchBox.scaleY = 1f
        searchBox.translationY = 0f
        exitSearchFocus()
        hideSuggestions()
    }

    /** 主页下拉进入搜索：仅在顶部且向下拉动超过阈值时触发一次。 */
    private fun setupPullToSearch() {
        val scroll = view.findViewById<android.widget.ScrollView>(R.id.home_scroll) ?: return
        scroll.setOnTouchListener { target, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    pullStartY = event.rawY
                    pullTriggered = false
                }
                MotionEvent.ACTION_MOVE -> {
                    if (!pullTriggered && !searchInput.hasFocus() &&
                        !target.canScrollVertically(-1) &&
                        event.rawY - pullStartY > dp(64)
                    ) {
                        pullTriggered = true
                        focusSearch()
                    }
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> pullTriggered = false
            }
            false
        }
        scroll.setOnScrollChangeListener { _, _, scrollY, _, _ ->
            // 聚焦时系统可能为让输入框可见主动滚动，这里把它拉回顶部。
            if (searchFocusActive && scrollY != 0) {
                scroll.scrollTo(0, 0)
            }
        }
    }

    private fun focusSearch() {
        if (topSearchVisible()) {
            focusTopSearch()
            return
        }
        if (!AppServices.settings.homeSearchShow()) return
        searchInput.requestFocus()
        searchInput.setSelection(searchInput.text.length)
        searchInput.post {
            val imm = context.getSystemService(Context.INPUT_METHOD_SERVICE)
                as? android.view.inputmethod.InputMethodManager ?: return@post
            imm.showSoftInput(searchInput, android.view.inputmethod.InputMethodManager.SHOW_IMPLICIT)
        }
    }

    /** 按设置调整主页搜索框（连同 logo）的垂直位置。 */
    fun applyLayoutPos() {
        val root = view.findViewById<LinearLayout>(R.id.home_content) ?: return
        val raw = AppServices.settings.homeLayoutPos()
        val pos = if (AppServices.settings.uiStyle() == 1 && raw == 2) 1 else raw
        // 常用网站关闭后内容变短，居中对齐不会导致遮挡：聚焦时由 moveContentForFocus() 补位。
        // 居中位置固定为顶部偏移：常用网站显示或隐藏都不移动时钟与搜索框。
        root.gravity = if (pos == 2) android.view.Gravity.BOTTOM else android.view.Gravity.TOP
        val logo = view.findViewById<TextView>(R.id.home_logo) ?: return
        val modern = AppServices.settings.uiStyle() == 1
        val baseTop = when (pos) {
            0 -> dp(36)
            2 -> dp(0)
            else -> (context.resources.displayMetrics.heightPixels * 0.18f).toInt()
        }
        val top = (baseTop - if (modern && pos == 0) dp(20) else 0).coerceAtLeast(0)
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
        val sites = view.findViewById<View>(R.id.home_sites_card) ?: return
        val show = box.visibility == View.VISIBLE
        box.translationY = 0f
        if (pos == 0) {
            if (show && content.indexOfChild(box) != 0) {
                content.removeView(box)
                content.addView(box, 0)
            }
            // 搜索框在顶部时，常用网站紧跟搜索框，Logo/时钟排到常用网站下方。
            if (content.indexOfChild(sites) != 1) {
                content.removeView(sites)
                content.addView(sites, 1)
            }
            return
        }
        if (pos == 2) {
            // 底部布局：常用网站先移到 Logo 组之后，再把搜索框放到最底部。
            val logoEnd = listOfNotNull(
                view.findViewById<View>(R.id.home_logo),
                view.findViewById<View>(R.id.home_logo_icon),
                view.findViewById<View>(R.id.home_clock)
            ).maxOfOrNull { content.indexOfChild(it) } ?: 0
            if (content.indexOfChild(sites) != logoEnd + 1) {
                content.removeView(sites)
                content.addView(sites, (logoEnd + 1).coerceAtMost(content.childCount))
            }
            if (show && content.indexOfChild(box) != content.childCount - 1) {
                content.removeView(box)
                content.addView(box)
            }
            return
        }
        val anchor = listOfNotNull(
            view.findViewById<View>(R.id.home_logo),
            view.findViewById<View>(R.id.home_logo_icon),
            view.findViewById<View>(R.id.home_clock)
        ).maxOfOrNull { content.indexOfChild(it) } ?: return
        if (show && content.indexOfChild(box) != anchor + 1) {
            content.removeView(box)
            content.addView(box, (anchor + 1).coerceAtMost(content.childCount))
        }
        if (content.indexOfChild(sites) != content.childCount - 1) {
            content.removeView(sites)
            content.addView(sites)
        }
    }

    /** 应用主页样式：位置、Logo、列数、图标、搜索框、背景与名称。 */
    fun applyHomeStyle() {
        applyTopSearchVisibility()
        applySearchVisibility()
        applyLayoutPos()
        val modern = AppServices.settings.uiStyle() == 1
        val content = view.findViewById<android.view.ViewGroup>(R.id.home_content)
        content?.setPadding(
            dp(20), dp(20), dp(20), if (modern) dp(96) else dp(20)
        )
        // 聚焦时搜索框会放大到 padding 区域；clipToPadding 默认 true 会把两侧圆角裁平。
        content?.clipToPadding = false
        val s = AppServices.settings
        val logo = view.findViewById<TextView>(R.id.home_logo)
        val logoIcon = view.findViewById<ImageView>(R.id.home_logo_icon)
        val mode = if (s.homeStyle() == 2) 2 else s.homeLogoMode()
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
        val columns = when (s.homeStyle()) {
            1 -> 4
            2 -> 2
            else -> s.homeGridColumns().coerceIn(4, 6)
        }
        if (grid.columnCount != columns) grid.columnCount = columns
        applySearchStyle()
        applyBackground()
        applySitesVisibility()
        // 只有站点格子样式变化才重建，拖动无关滑块时不再每帧重建网格。
        val siteStyle = listOf(
            s.homeIconSize(), s.homeIconRadius(), s.homeIconShape(),
            s.homeShowLabels(), s.homeLabelSize(), s.homeCellGap()
        ).joinToString(",")
        if (siteStyle != lastSiteStyle) {
            lastSiteStyle = siteStyle
            render()
        }
    }

    private fun parseColorValue(value: String?): Int? {
        if (value.isNullOrBlank()) return null
        return runCatching { Color.parseColor(value) }.getOrNull()
    }

    private fun applySitesVisibility() {
        val show = AppServices.settings.showSites()
        val card = view.findViewById<View>(R.id.home_sites_card)
        card?.visibility = if (show) View.VISIBLE else View.GONE
        applySitesCardBg(card)
        view.findViewById<View>(R.id.home_sites_title)?.visibility =
            if (show && AppServices.settings.homeShowSitesTitle()) View.VISIBLE else View.GONE
        grid.visibility = if (show) View.VISIBLE else View.GONE
    }

    /** 卡片/搜索框背景 alpha：由玻璃强度控制，下限保证文字可读；描边与投影不受影响。 */
    private fun surfaceAlpha(min: Int): Int =
        (255 - AppServices.settings.glassStrength()).coerceIn(min, 255)

    /** 常用网站卡片背景：关闭后站点直接浮在主页背景上。 */
    private fun applySitesCardBg(card: View?) {
        card ?: return
        val on = AppServices.settings.homeSitesCardBg()
        val dark = AppServices.settings.isNightMode()
        val style = AppServices.settings.homeStyle()
        val themeFill = ThemePalette.fill(context, ThemePart.SITES)
        val alpha = surfaceAlpha(165)
        val base = if (dark) 0xFF1B1E23.toInt() else context.getColor(R.color.card)
        val fill = (alpha shl 24) or (base and 0x00FFFFFF)
        card.background = when {
            style != 0 -> null
            !on -> null
            themeFill != null -> GradientDrawable().apply {
                cornerRadius = dp(24).toFloat()
                setColor((alpha shl 24) or (themeFill and 0x00FFFFFF))
            }
            // 卡片不走玻璃：白/深色卡 + 细描边 + 投影；只有填充 alpha 随玻璃强度变化。
            else -> GradientDrawable().apply {
                cornerRadius = dp(24).toFloat()
                setColor(fill)
                setStroke(dp(1), if (dark) 0x1FFFFFFF else 0x14000000)
            }
        }
        // LayerDrawable 不自带 outline，不补轮廓时 elevation 投影不显示，卡片会“贴”在主页上。
        val cardRadius = dp(24).toFloat()
        card.outlineProvider = object : android.view.ViewOutlineProvider() {
            override fun getOutline(v: View, outline: android.graphics.Outline) {
                outline.setRoundRect(0, 0, v.width, v.height, cardRadius)
            }
        }
        card.elevation = if (!on || card.background == null) 0f
        else if (dark) dp(6).toFloat() else dp(8).toFloat()
    }

    /** 主页搜索框圆角：搜索框与建议弹层共用，保证两者一致。 */
    private fun searchCornerRadiusDp(): Int {
        val base = when (AppServices.settings.homeSearchRadius()) {
            0 -> 8
            2 -> 16
            3 -> AppServices.settings.homeSearchRadiusCustom().coerceIn(0, 48)
            else -> 12
        }
        return if (AppServices.settings.uiStyle() == 1) base.coerceAtLeast(22) else base
    }

    /** 应用搜索框圆角、高度、颜色与图标色。 */
    private fun applySearchStyle(focused: Boolean = false) {
        val box = view.findViewById<LinearLayout>(R.id.home_search_box) ?: return
        val modern = AppServices.settings.uiStyle() == 1
        val dark = modern && AppServices.settings.isNightMode()
        val radius = searchCornerRadiusDp()
        val strokeColor = when {
            modern && focused -> ThemeAccent.color(context)
            dark -> 0x24FFFFFF
            else -> context.getColor(R.color.line)
        }
        // 搜索框不走玻璃：白/深色卡 + 细描边 + 投影；只有填充 alpha 随玻璃强度变化。
        val alpha = surfaceAlpha(175)
        val baseColor = if (dark) 0xFF1B1E23.toInt() else context.getColor(R.color.card)
        box.background = GradientDrawable().apply {
            cornerRadius = dp(radius).toFloat()
            setColor((alpha shl 24) or (baseColor and 0x00FFFFFF))
            setStroke(dp(1), strokeColor)
        }
        // 与玻璃背景不同，这里背景是 GradientDrawable 自带上 outline，但统一显式设置更稳。
        val radiusPx = dp(radius).toFloat()
        box.outlineProvider = object : android.view.ViewOutlineProvider() {
            override fun getOutline(v: View, outline: android.graphics.Outline) {
                outline.setRoundRect(0, 0, v.width, v.height, radiusPx)
            }
        }
        box.elevation = if (modern) {
            dp(if (focused) 12 else 6).toFloat()
        } else if (focused) {
            dp(8).toFloat()
        } else {
            searchBoxElevation
        }
        val padV = when (AppServices.settings.homeSearchHeight()) {
            0 -> 2
            2 -> 8
            else -> 4
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
        // 极简 / 杂志：改为细边框，去掉胶囊与阴影；圆角仍按设置的档位。
        if (AppServices.settings.homeStyle() != 0) {
            box.background = GradientDrawable().apply {
                cornerRadius = dp(radius).toFloat()
                setColor(0x00000000)
                setStroke(dp(1), if (dark) 0x33FFFFFF else 0x26000000)
            }
            box.elevation = 0f
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

    /** 主页底色：开启「主页背景」时用主色淡底，否则用页面底色。 */
    fun backgroundColor(): Int =
        ThemePalette.fill(context, ThemePart.HOME_BG) ?: context.getColor(R.color.bg_page)

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

    fun exitSearchInput() {
        setImeSuspend(false)
        searchInput.clearFocus()
        topSearchInput.clearFocus()
        hideKeyboard()
        hideSuggestions()
        topSuggestionPopup?.dismiss()
    }

    /** 主页搜索框是否持有焦点。 */
    fun isSearchActive(): Boolean = searchInput.hasFocus()

    /**
     * 全局触摸兜底：主页内容被 ScrollView 消费、底栏/工具栏又不在本视图内，
     * 点击这些区域时只能由窗口层把事件转进来取消搜索焦点。
     */
    fun onGlobalTouch(event: MotionEvent) {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                touchStartedInSearch = isInsideSearch(event.rawX, event.rawY)
                touchDownX = event.rawX
                touchDownY = event.rawY
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                // 下拉/滚动也会收到 UP，只有位移很小的点击才当作“点击搜索框外部”。
                val moved = kotlin.math.abs(event.rawX - touchDownX) > dp(8) ||
                    kotlin.math.abs(event.rawY - touchDownY) > dp(8)
                if (!touchStartedInSearch && !moved &&
                    (searchInput.hasFocus() || topSearchInput.hasFocus())
                ) {
                    exitSearchInput()
                }
            }
        }
    }

    private fun isInsideSearch(rawX: Float, rawY: Float): Boolean {
        if (isInsideView(searchBox, rawX, rawY)) return true
        return topSearchVisible() && isInsideView(topSearchBox, rawX, rawY)
    }

    private fun isInsideView(target: View, rawX: Float, rawY: Float): Boolean {
        if (target.width <= 0 || target.height <= 0) return false
        val location = IntArray(2)
        target.getLocationOnScreen(location)
        return rawX >= location[0] && rawX <= location[0] + target.width &&
            rawY >= location[1] && rawY <= location[1] + target.height
    }

    private fun hideKeyboard(input: EditText = searchInput) {
        val imm = context.getSystemService(Context.INPUT_METHOD_SERVICE)
            as? android.view.inputmethod.InputMethodManager ?: return
        imm.hideSoftInputFromWindow(input.windowToken, 0)
    }

    /** 返回主页时清掉上一次搜索留下的网址。 */
    fun clearSearch() {
        suggestionsSuppressed = true
        topSuggestionsSuppressed = true
        hideSuggestions()
        topSuggestJob?.cancel()
        topSuggestionPopup?.dismiss(false)
        searchInput.setText("")
        topSearchInput.setText("")
        topSearchInput.clearFocus()
    }

    private fun showSuggestions(items: List<Suggestion>, preferAbove: Boolean = false) {
        // 焦点已不在搜索框时不能再弹：异步结果晚到会留下无人关闭的幽灵弹层。
        if (!searchInput.hasFocus()) {
            suggestionPopup?.dismiss()
            return
        }
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
            context,
            searchBox,
            minWidthDp = 0,
            cornerRadiusDp = { searchCornerRadiusDp() },
            horizontalShiftDp = 5
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
        clipboardJob?.cancel()
        clipboardJob = scope.launch {
            // 读剪贴板和正则解析都放后台，避免与键盘动画抢主线程。
            val clips = withContext(Dispatchers.IO) {
                ClipboardSuggestion.readAll(context.applicationContext)
            }
            if (!searchInput.hasFocus() || searchInput.text.isNotEmpty()) return@launch
            if (clips.isEmpty()) {
                hideSuggestions()
                return@launch
            }
            // 等键盘动画把主页布局推到最终位置再弹出，避免先出现在旧位置再跳。
            kotlinx.coroutines.delay(180)
            if (!searchInput.hasFocus() || searchInput.text.isNotEmpty()) return@launch
            showSuggestions(clips, preferAbove = true)
        }
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
        setImeSuspend(false)
        hideSuggestions()
        searchFocusAnimator?.cancel()
        searchFocusAnimator = null
        topSuggestJob?.cancel()
        topSuggestionPopup?.dismiss(false)
        topSuggestionPopup = null
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
        val showSearch = searchBox.visibility == View.VISIBLE
        val targets = listOfNotNull(logoView, searchBox.takeIf { showSearch }, card)
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
        if (showSearch) {
            searchBox.alpha = 0f
            searchBox.translationY = dp(10).toFloat()
            searchBox.scaleX = 0.97f
            searchBox.scaleY = 0.97f
        }
        card?.alpha = 0f
        card?.translationY = dp(18).toFloat()
        cells.forEach { it.alpha = 0f; it.translationY = dp(10).toFloat() }

        val interpolator = android.view.animation.DecelerateInterpolator()
        logoView?.animate()?.alpha(1f)?.translationY(0f)?.setDuration(320L)
            ?.setInterpolator(interpolator)?.start()
        searchBox.takeIf { showSearch }?.animate()?.alpha(1f)?.translationY(0f)
            ?.scaleX(1f)?.scaleY(1f)
            ?.setDuration(320L)?.setStartDelay(70L)?.setInterpolator(interpolator)?.start()
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
        sites.forEach { grid.addView(siteView(it)) }
        if (!previewMode) grid.addView(addView())
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

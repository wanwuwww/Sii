// SPDX-License-Identifier: LicenseRef-Sii-NC
// Copyright (c) 2026 wanwuwww
package com.sii.browser.ui

import android.content.Context
import android.graphics.Color
import android.graphics.Rect
import android.graphics.drawable.GradientDrawable
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.PopupWindow
import android.widget.ScrollView
import android.widget.TextView
import androidx.core.content.ContextCompat
import com.sii.browser.R
import com.sii.browser.core.mcp.DiagLog
import kotlin.math.min

/** 地址栏和主页搜索框共用的建议弹层：原地更新、轻量淡入、自动翻转和滚动。 */
class SuggestionPopup(
    private val context: Context,
    private val anchor: View,
    private val minWidthDp: Int = 280,
    private val cornerRadiusDp: (() -> Int)? = null,
    /** 水平微调：正值向右、负值向左（相对锚点左边界，屏幕边缘仍会回收）。 */
    private val horizontalShiftDp: Int = 0,
    private val onPick: (Suggestion) -> Unit
) {
    private var popup: PopupWindow? = null
    private var scroll: ScrollView? = null
    private var showingAbove = false
    private var lastSignature: String? = null
    private var lastXOffset = 0
    private var lastYOffset = 0
    private var lastWidth = 0
    private var lastHeight = 0
    private var resizeAnimator: android.animation.ValueAnimator? = null
    private var followAnchor: View? = null
    private var lastAnchorX = Int.MIN_VALUE
    private var lastAnchorY = Int.MIN_VALUE

    /** 键盘弹出、主页内容上移或页面滚动时，让弹层跟着锚点走，而不是停在原位。 */
    private val followPreDraw = android.view.ViewTreeObserver.OnPreDrawListener {
        val window = popup
        if (window == null || !window.isShowing) {
            detachFollow()
        } else if (resizeAnimator?.isRunning != true) {
            val location = IntArray(2)
            anchor.getLocationOnScreen(location)
            if (location[0] != lastAnchorX || location[1] != lastAnchorY) {
                lastAnchorX = location[0]
                lastAnchorY = location[1]
                // 导航/隐藏主页时锚点可能已脱离窗口，更新失败不能往外抛。
                runCatching {
                    window.update(anchor, lastXOffset, lastYOffset, lastWidth, lastHeight)
                }
            }
        }
        true
    }

    private fun attachFollow() {
        if (followAnchor === anchor) return
        detachFollow()
        if (!anchor.isAttachedToWindow || !anchor.viewTreeObserver.isAlive) return
        val location = IntArray(2)
        anchor.getLocationOnScreen(location)
        lastAnchorX = location[0]
        lastAnchorY = location[1]
        anchor.viewTreeObserver.addOnPreDrawListener(followPreDraw)
        followAnchor = anchor
    }

    private fun detachFollow() {
        followAnchor?.let { view ->
            if (view.viewTreeObserver.isAlive) {
                view.viewTreeObserver.removeOnPreDrawListener(followPreDraw)
            }
        }
        followAnchor = null
    }

    val isShowing: Boolean get() = popup?.isShowing == true

    fun show(items: List<Suggestion>, preferAbove: Boolean = false) {
        if (items.isEmpty()) {
            dismiss(false)
            return
        }
        // 锚点已脱离窗口时 showAsDropDown 会抛 BadTokenException，直接放弃。
        if (!anchor.isAttachedToWindow) {
            dismiss(false)
            return
        }
        // 宽度取锚点布局宽度（不乘聚焦缩放，否则动画期间对不齐）。
        val maxWidth = context.resources.displayMetrics.widthPixels - dp(16)
        val minWidth = dp(minWidthDp).coerceAtMost(maxWidth)
        val width = (anchor.width.takeIf { it > 0 }
            ?: (context.resources.displayMetrics.widthPixels - dp(32)))
            .coerceIn(minWidth, maxWidth)
        if (width <= 0) return
        // 锚点已脱离窗口（页面已切走）时不再弹，避免留下关不掉的弹层。
        if (!anchor.isAttachedToWindow || !anchor.isShown) {
            DiagLog.record("suggest", "skip-detached anchor=${anchor.id}")
            return
        }
        val signature = items.joinToString("|") { it.kind.name + ":" + it.value } +
            ":" + preferAbove
        if (popup?.isShowing == true && signature == lastSignature) return
        lastSignature = signature

        val placement = measurePlacement(items, preferAbove, width)
        val displayItems = if (placement.above) items.reversed() else items
        // 向下展开时测量用的列可以直接复用，避免重复构建整排控件。
        val column = if (placement.above) buildColumn(displayItems) else placement.column

        val currentPopup = popup
        val currentScroll = scroll
        if (currentPopup?.isShowing == true && currentScroll != null) {
            // 结果更新（本地 → 联网）时原地替换内容，不再重建窗口和重播动画。
            currentScroll.removeAllViews()
            currentScroll.addView(
                column,
                ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                )
            )
            showingAbove = placement.above
            currentScroll.alpha = 1f
            currentScroll.translationY = 0f
            animateWindowTo(currentPopup, placement)
            return
        }

        val view = ScrollView(context).apply {
            isVerticalScrollBarEnabled = true
            if (cornerRadiusDp != null) background = panelBackground()
            else setBackgroundResource(R.drawable.bg_suggestion_panel)
            clipToOutline = true
            addView(
                column,
                ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                )
            )
        }
        scroll = view
        showingAbove = placement.above
        view.alpha = 1f
        view.translationY = 0f
        val window = PopupWindow(view, placement.width, placement.height).apply {
            isFocusable = false
            isOutsideTouchable = true
            elevation = dp(8).toFloat()
            setBackgroundDrawable(android.graphics.drawable.ColorDrawable(Color.TRANSPARENT))
            setTouchInterceptor { _, event ->
                if (event.action == android.view.MotionEvent.ACTION_OUTSIDE) {
                    dismiss()
                    true
                } else false
            }
        }
        popup = window
        activeWindows.add(window)
        DiagLog.record(
            "suggest",
            "show id=${Integer.toHexString(System.identityHashCode(window))} " +
                "above=${placement.above} anchor=${anchor.id} n=${items.size}"
        )
        activeWindows.add(window)
        lastXOffset = placement.xOffset
        lastYOffset = placement.yOffset
        lastWidth = placement.width
        lastHeight = placement.height
        val shown = runCatching {
            window.showAsDropDown(anchor, placement.xOffset, placement.yOffset)
        }.isSuccess
        if (!shown) {
            popup = null
            scroll = null
            lastSignature = null
            detachFollow()
            DiagLog.record("suggest", "show-fail anchor=${anchor.id}")
            return
        }
        attachFollow()
    }

    /** 本地结果 → 联网结果时平滑扩展/收缩窗口，避免高度突变造成闪烁。 */
    private fun animateWindowTo(window: PopupWindow, placement: Placement) {
        resizeAnimator?.cancel()
        val startX = lastXOffset
        val startY = lastYOffset
        val startW = lastWidth
        val startH = lastHeight
        val endX = placement.xOffset
        val endY = placement.yOffset
        val endW = placement.width
        val endH = placement.height
        lastXOffset = endX
        lastYOffset = endY
        lastWidth = endW
        lastHeight = endH
        if (startH <= 0) {
            window.width = endW
            window.height = endH
            window.update(anchor, endX, endY, endW, endH)
            return
        }
        if (startH == endH && startW == endW && startX == endX && startY == endY) return
        val animator = android.animation.ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 120L
            addUpdateListener { animation ->
                val f = animation.animatedValue as Float
                val xOff = (startX + (endX - startX) * f).toInt()
                val yOff = (startY + (endY - startY) * f).toInt()
                val w = (startW + (endW - startW) * f).toInt()
                val h = (startH + (endH - startH) * f).toInt()
                window.update(anchor, xOff, yOff, w.coerceAtLeast(1), h.coerceAtLeast(1))
            }
        }
        resizeAnimator = animator
        animator.start()
    }

    fun dismiss(animated: Boolean = true) {
        val window = popup ?: return
        DiagLog.record(
            "suggest",
            "dismiss id=${Integer.toHexString(System.identityHashCode(window))} " +
                "animated=$animated showing=${window.isShowing}"
        )
        popup = null
        scroll = null
        lastSignature = null
        detachFollow()
        val content = window.contentView
        if (!animated || !window.isShowing || content == null || !content.isAttachedToWindow) {
            closeWindow(window)
            return
        }
        content.animate().cancel()
        resizeAnimator?.cancel()
        resizeAnimator = null
        content.alpha = 1f
        content.animate()
            .alpha(0f)
            .translationY(if (showingAbove) dp(6).toFloat() else -dp(6).toFloat())
            .setDuration(90L)
            .withEndAction { closeWindow(window) }
            .start()
        // 动画被打断（view 脱离窗口、转场）时也要确保窗口关掉，否则会残留成幽灵弹层。
        android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
            closeWindow(window)
        }, 200L)
    }

    /** 生命周期路径用：不等动画，立即关闭。 */
    fun dismissNow() {
        val window = popup ?: return
        DiagLog.record(
            "suggest",
            "dismiss-now id=${Integer.toHexString(System.identityHashCode(window))} " +
                "showing=${window.isShowing}"
        )
        popup = null
        scroll = null
        lastSignature = null
        detachFollow()
        closeWindow(window)
    }

    private fun closeWindow(window: PopupWindow) {
        val wasShowing = runCatching { window.isShowing }.getOrDefault(false)
        runCatching { if (wasShowing) window.dismiss() }
        activeWindows.remove(window)
        DiagLog.record(
            "suggest",
            "close id=${Integer.toHexString(System.identityHashCode(window))} wasShowing=$wasShowing"
        )
    }

    companion object {
        /** 所有曾经显示过的弹窗（含已丢失引用的），页面切换时强制清理。 */
        private val activeWindows =
            java.util.Collections.newSetFromMap(java.util.WeakHashMap<PopupWindow, Boolean>())

        fun dismissAll() {
            val list = activeWindows.toList()
            val showing = list.count { runCatching { it.isShowing }.getOrDefault(false) }
            DiagLog.record("suggest", "dismiss-all tracked=${list.size} showing=$showing")
            list.forEach { window ->
                runCatching { if (window.isShowing) window.dismiss() }
            }
            activeWindows.clear()
        }

        /** 诊断用：当前跟踪的窗口数与仍在显示的数量。 */
        fun debugState(): String {
            val list = activeWindows.toList()
            val ids = list.filter { runCatching { it.isShowing }.getOrDefault(false) }
                .map { Integer.toHexString(System.identityHashCode(it)) }
            return "tracked=${list.size} showing=${ids.size} ids=${ids.joinToString(",")}"
        }
    }

    private class Placement(
        val above: Boolean,
        val xOffset: Int,
        val yOffset: Int,
        val width: Int,
        val height: Int,
        val column: LinearLayout
    )

    private fun measurePlacement(
        items: List<Suggestion>,
        preferAbove: Boolean,
        width: Int
    ): Placement {
        val anchorLocation = IntArray(2)
        anchor.getLocationInWindow(anchorLocation)
        val root = anchor.rootView
        val rootLocation = IntArray(2)
        root.getLocationOnScreen(rootLocation)
        val frame = Rect()
        anchor.getWindowVisibleDisplayFrame(frame)
        val margin = dp(8)
        val anchorX = rootLocation[0] + anchorLocation[0]
        val anchorScreenTop = rootLocation[1] + anchorLocation[1]
        val anchorScreenBottom = anchorScreenTop + anchor.height
        val visibleTop = frame.top
        val visibleBottom = frame.bottom
        val spaceAbove = (anchorScreenTop - visibleTop - margin).coerceAtLeast(0)
        val spaceBelow = (visibleBottom - anchorScreenBottom - margin).coerceAtLeast(0)
        val maxScreenHeight = (visibleBottom - visibleTop - margin * 2).coerceAtLeast(dp(80))

        val measured = buildColumn(items)
        measure(measured, width, maxScreenHeight)
        val desired = measured.measuredHeight.coerceAtLeast(0)
        val above = when {
            preferAbove && spaceAbove >= dp(80) -> true
            desired <= spaceAbove && spaceAbove >= spaceBelow -> true
            desired <= spaceBelow -> false
            else -> spaceAbove > spaceBelow
        }
        val available = if (above) spaceAbove else spaceBelow
        val height = min(desired.coerceAtLeast(dp(48)), available.coerceAtLeast(dp(48)))
        // 与锚点左边界对齐（略右于缩放后的视觉边界），超出屏幕再回收。
        var xOffset = dp(horizontalShiftDp)
        if (anchorX + width > frame.right - margin) {
            xOffset = frame.right - margin - width - anchorX
        }
        if (anchorX + xOffset < frame.left + margin) {
            xOffset = frame.left + margin - anchorX
        }
        val yOffset = if (above) {
            -(anchor.height + margin + height)
        } else {
            margin
        }
        return Placement(above, xOffset, yOffset, width, height, measured)
    }

    /** 主页搜索框的建议弹层跟随其圆角设置。 */
    private fun panelBackground(): GradientDrawable = GradientDrawable().apply {
        shape = GradientDrawable.RECTANGLE
        cornerRadius = dp(cornerRadiusDp?.invoke() ?: 22).toFloat()
        setColor(ContextCompat.getColor(context, R.color.card))
        setStroke(dp(1), ContextCompat.getColor(context, R.color.line))
    }

    private fun buildColumn(items: List<Suggestion>): LinearLayout {
        val column = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(4), 0, dp(4))
        }
        items.forEachIndexed { index, item ->
            column.addView(buildRow(item))
            if (index != items.lastIndex) {
                column.addView(View(context).apply {
                    layoutParams = LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, dp(1)
                    )
                    setBackgroundColor(ContextCompat.getColor(context, R.color.line))
                })
            }
        }
        return column
    }

    private fun buildRow(item: Suggestion): View {
        val accent = ThemeAccent.color(context)
        val primary = ContextCompat.getColor(context, R.color.text_primary)
        val secondary = ContextCompat.getColor(context, R.color.text_secondary)
        val row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(12), dp(9), dp(10), dp(9))
            setBackgroundResource(android.R.drawable.list_selector_background)
            setOnClickListener {
                dismiss(false)
                onPick(item)
            }
        }
        val search = item.kind == SuggestionKind.SEARCH
        val iconColor = if (search) accent else secondary
        val iconBg = if (search) ((0x26 shl 24) or (accent and 0x00FFFFFF))
        else ((0x14 shl 24) or (primary and 0x00FFFFFF))
        val iconWrap = android.widget.FrameLayout(context).apply {
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(iconBg)
            }
        }
        iconWrap.addView(android.widget.ImageView(context).apply {
            setImageResource(
                when (item.kind) {
                    SuggestionKind.SEARCH -> R.drawable.ic_m_find
                    SuggestionKind.HISTORY -> R.drawable.ic_m_history
                    SuggestionKind.BOOKMARK -> R.drawable.ic_m_bmlist
                    else -> R.drawable.ic_m_copy
                }
            )
            imageTintList = android.content.res.ColorStateList.valueOf(iconColor)
            setPadding(dp(9), dp(9), dp(9), dp(9))
        }, android.widget.FrameLayout.LayoutParams(dp(36), dp(36), Gravity.CENTER))
        row.addView(iconWrap, LinearLayout.LayoutParams(dp(36), dp(36)))

        val textColumn = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(10), 0, dp(6), 0)
        }
        val titleRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val tagRes = when (item.kind) {
            SuggestionKind.HISTORY -> R.string.suggest_tag_history
            SuggestionKind.BOOKMARK -> R.string.suggest_tag_bookmark
            SuggestionKind.CLIPBOARD_TEXT,
            SuggestionKind.CLIPBOARD_LINK,
            SuggestionKind.CLIPBOARD_MIXED -> R.string.suggest_tag_clipboard
            else -> null
        }
        if (tagRes != null) {
            titleRow.addView(TextView(context).apply {
                text = context.getString(tagRes)
                setTextSize(9.5f)
                setTextColor(accent)
                setPadding(dp(5), dp(1), dp(5), dp(1))
                background = GradientDrawable().apply {
                    cornerRadius = dp(5).toFloat()
                    setColor((0x1F shl 24) or (accent and 0x00FFFFFF))
                }
            }, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { marginEnd = dp(6) })
        }
        titleRow.addView(TextView(context).apply {
            text = item.title
            setTextSize(if (search) 15f else 14f)
            setTextColor(primary)
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
            if (search) {
                typeface = android.graphics.Typeface.create(
                    "sans-serif-medium", android.graphics.Typeface.NORMAL
                )
            }
            layoutParams = LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f
            )
        })
        textColumn.addView(titleRow)
        val subtitle = item.subtitle.orEmpty()
        if (subtitle.isNotBlank()) {
            textColumn.addView(TextView(context).apply {
                text = subtitle
                setTextSize(11.5f)
                setTextColor(secondary)
                maxLines = 1
                ellipsize = TextUtils.TruncateAt.END
                setPadding(0, dp(2), 0, 0)
            })
        }
        row.addView(textColumn, LinearLayout.LayoutParams(
            0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f
        ))
        row.addView(TextView(context).apply {
            text = "↵"
            setTextSize(15f)
            setTextColor(secondary)
            gravity = Gravity.CENTER
            setPadding(dp(8), 0, dp(4), 0)
        }, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        ))
        return row
    }

    private fun measure(view: View, width: Int, maxHeight: Int) {
        view.measure(
            View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(maxHeight, View.MeasureSpec.AT_MOST)
        )
    }

    private fun dp(value: Int): Int =
        (value * context.resources.displayMetrics.density).toInt()
}

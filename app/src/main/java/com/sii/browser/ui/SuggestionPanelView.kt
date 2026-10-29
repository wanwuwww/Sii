// SPDX-License-Identifier: LicenseRef-Sii-NC
// Copyright (c) 2026 wanwuwww
package com.sii.browser.ui

import android.content.Context
import android.graphics.drawable.GradientDrawable
import android.view.View
import android.view.ViewTreeObserver
import android.widget.FrameLayout
import android.widget.LinearLayout
import androidx.core.content.ContextCompat
import com.sii.browser.R
import com.sii.browser.core.mcp.DiagLog

/**
 * 页面内搜索建议面板：不创建独立窗口，直接挂在浏览器根布局的覆盖层里，
 * 位置跟随锚点（地址栏/主页搜索框）实时重排，天然不会残留成幽灵弹层。
 */
class SuggestionPanelView(
    private val context: Context,
    private val layer: FrameLayout
) {

    private var panel: LinearLayout? = null
    private var anchor: View? = null
    private var widthAnchor: View? = null
    private var preferAbove = false
    private var radiusDp = 22
    private var signature: String? = null
    private var followAnchor: View? = null
    private var picker: ((Suggestion) -> Unit)? = null

    /** 默认点击处理；每次 show 也可以单独指定。 */
    var onPick: ((Suggestion) -> Unit)? = null

    /** 悬浮样式传入玻璃背景，经典/主页默认用卡片色。 */
    var backgroundProvider: (() -> android.graphics.drawable.Drawable?)? = null

    /** 键盘遮挡高度（屏幕底部像素），用于计算可见区域。 */
    var imeBottomProvider: (() -> Int)? = null

    private val follow = ViewTreeObserver.OnPreDrawListener {
        reposition()
        true
    }

    init {
        // 键盘弹出/收起、窗口尺寸变化时重新定位。
        layer.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ ->
            if (panel != null) reposition()
        }
    }

    /** 外部（IME insets 变化）触发的重排。 */
    fun refresh() {
        if (panel != null) reposition()
    }

    fun show(
        anchor: View,
        widthAnchor: View?,
        items: List<Suggestion>,
        preferAbove: Boolean,
        radiusDp: Int,
        onItemPick: (Suggestion) -> Unit = { onPick?.invoke(it) }
    ) {
        if (items.isEmpty() || !anchor.isAttachedToWindow) {
            hide()
            return
        }
        val sig = items.joinToString("|") { it.kind.name + ":" + it.value } +
            ":" + preferAbove + ":" + radiusDp
        val showing = layer.visibility == View.VISIBLE && panel != null
        if (showing && sig == signature) {
            reposition()
            return
        }
        signature = sig
        this.anchor = anchor
        this.widthAnchor = widthAnchor
        this.preferAbove = preferAbove
        this.radiusDp = radiusDp
        this.picker = onItemPick
        DiagLog.record(
            "suggest",
            "inline-show anchor=${anchor.id} items=${items.size} " +
                "layer=${layer.width}x${layer.height} above=$preferAbove"
        )

        val column = SuggestionRows.column(context, items) { item ->
            hide()
            (picker ?: onPick)?.invoke(item)
        }
        val view = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            addView(column)
            background = panelBackground(radiusDp)
            elevation = dp(8).toFloat()
            clipToOutline = true
            isClickable = true
        }
        layer.removeAllViews()
        layer.addView(view, FrameLayout.LayoutParams(1, 1))
        layer.visibility = View.VISIBLE
        panel = view
        attachFollow(anchor)
        // 覆盖层刚从 GONE 恢复时，这一帧宽高可能还是 0，下一帧再定位。
        reposition()
        layer.post { if (panel === view) reposition() }
    }

    fun hide() {
        layer.removeAllViews()
        panel = null
        signature = null
        anchor = null
        widthAnchor = null
        detachFollow()
    }

    fun isShowing(): Boolean = panel != null

    fun debugState(): String =
        if (isShowing()) {
            "inline showing=1 layer=${layer.width}x${layer.height} " +
                "panel=${panel?.width}x${panel?.height} at=${panel?.left},${panel?.top}"
        } else "inline showing=0"

    /** 面板是否命中屏幕坐标（用于点面板外部时收起建议）。 */
    fun contains(x: Float, y: Float): Boolean {
        val view = panel ?: return false
        if (layer.visibility != View.VISIBLE || !view.isAttachedToWindow) return false
        val loc = IntArray(2)
        view.getLocationOnScreen(loc)
        return x >= loc[0] && x <= loc[0] + view.width &&
            y >= loc[1] && y <= loc[1] + view.height
    }

    private fun attachFollow(target: View) {
        if (followAnchor === target) return
        detachFollow()
        if (!target.isAttachedToWindow || !target.viewTreeObserver.isAlive) return
        target.viewTreeObserver.addOnPreDrawListener(follow)
        followAnchor = target
    }

    private fun detachFollow() {
        followAnchor?.let { view ->
            if (view.viewTreeObserver.isAlive) {
                view.viewTreeObserver.removeOnPreDrawListener(follow)
            }
        }
        followAnchor = null
    }

    /** 参照锚点/容器在屏幕上的矩形，重新设置面板的位置与尺寸。 */
    private fun reposition() {
        val view = panel ?: return
        val a = anchor ?: return
        if (!a.isAttachedToWindow) {
            hide()
            return
        }
        if (layer.width <= 0 || layer.height <= 0) {
            DiagLog.record("suggest", "inline-wait layer=${layer.width}x${layer.height}")
            layer.post { reposition() }
            return
        }
        val layerLoc = IntArray(2)
        layer.getLocationOnScreen(layerLoc)
        val anchorLoc = IntArray(2)
        a.getLocationOnScreen(anchorLoc)
        val anchorX = anchorLoc[0] - layerLoc[0]
        val anchorY = anchorLoc[1] - layerLoc[1]
        val edge = dp(8)
        val gap = dp(2)

        // 可用区域要扣除键盘：home 搜索会 suspend IME padding，窗口本身不会缩。
        val frame = android.graphics.Rect()
        a.getWindowVisibleDisplayFrame(frame)
        val visibleTop = (frame.top - layerLoc[1]).coerceAtLeast(0)
        val imeBottom = imeBottomProvider?.invoke() ?: 0
        val visibleBottom = minOf(
            (frame.bottom - layerLoc[1]).coerceIn(0, layer.height),
            (layer.height - imeBottom).coerceIn(0, layer.height)
        )
        val maxHeight = (visibleBottom - visibleTop - edge * 2).coerceAtLeast(dp(80))

        val maxWidth = (layer.width - edge * 2).coerceAtLeast(dp(120))
        val width = ((widthAnchor?.width?.takeIf { it > 0 } ?: a.width)).coerceAtLeast(dp(120))
            .coerceAtMost(maxWidth)
        val rawLeft = widthAnchor?.let { target ->
            val loc = IntArray(2)
            target.getLocationOnScreen(loc)
            loc[0] - layerLoc[0]
        } ?: anchorX
        val left = rawLeft.coerceIn(edge, (layer.width - width - edge).coerceAtLeast(edge))

        view.measure(
            View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(maxHeight, View.MeasureSpec.AT_MOST)
        )
        val height = view.measuredHeight.coerceIn(dp(48), maxHeight)
        val spaceAbove = (anchorY - visibleTop).coerceAtLeast(0)
        val spaceBelow = (visibleBottom - (anchorY + a.height)).coerceAtLeast(0)
        val above = preferAbove || (spaceBelow < height && spaceAbove > spaceBelow)
        val top = if (above) anchorY - height - gap else anchorY + a.height + gap
        val params = (view.layoutParams as? FrameLayout.LayoutParams)
            ?: FrameLayout.LayoutParams(width, height)
        params.width = width
        params.height = height
        params.leftMargin = left
        params.topMargin = top.coerceIn(visibleTop, (visibleBottom - height).coerceAtLeast(visibleTop))
        view.layoutParams = params
    }

    private fun panelBackground(radiusDp: Int): android.graphics.drawable.Drawable {
        backgroundProvider?.invoke()?.let { return it }
        return GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = dp(radiusDp).toFloat()
            setColor(ContextCompat.getColor(context, R.color.card))
        }
    }

    private fun dp(value: Int): Int =
        (value * context.resources.displayMetrics.density).toInt()
}

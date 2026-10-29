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

    private val follow = ViewTreeObserver.OnPreDrawListener {
        reposition()
        true
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
        reposition()
    }

    fun hide() {
        layer.removeAllViews()
        layer.visibility = View.GONE
        panel = null
        signature = null
        anchor = null
        widthAnchor = null
        detachFollow()
    }

    fun isShowing(): Boolean = layer.visibility == View.VISIBLE && panel != null

    fun debugState(): String =
        if (isShowing()) "inline showing=1 x=${panel?.left} y=${panel?.top}" else "inline showing=0"

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
        if (!a.isAttachedToWindow || layer.width <= 0 || layer.height <= 0) {
            hide()
            return
        }
        val layerLoc = IntArray(2)
        layer.getLocationOnScreen(layerLoc)
        val anchorLoc = IntArray(2)
        a.getLocationOnScreen(anchorLoc)
        val anchorX = anchorLoc[0] - layerLoc[0]
        val anchorY = anchorLoc[1] - layerLoc[1]
        val width = (widthAnchor?.width?.takeIf { it > 0 } ?: a.width).coerceAtLeast(dp(120))
        val left = widthAnchor?.let { target ->
            val loc = IntArray(2)
            target.getLocationOnScreen(loc)
            loc[0] - layerLoc[0]
        } ?: anchorX

        val gap = dp(2)
        val maxHeight = (layer.height - dp(16)).coerceAtLeast(dp(80))
        view.measure(
            View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(maxHeight, View.MeasureSpec.AT_MOST)
        )
        val height = view.measuredHeight.coerceAtLeast(dp(48))
        val spaceBelow = layer.height - (anchorY + a.height)
        val above = preferAbove || (spaceBelow < height && anchorY > height)
        val top = if (above) anchorY - height - gap else anchorY + a.height + gap
        val params = (view.layoutParams as? FrameLayout.LayoutParams)
            ?: FrameLayout.LayoutParams(width, height)
        params.width = width
        params.height = height
        params.leftMargin = left.coerceIn(0, (layer.width - width).coerceAtLeast(0))
        params.topMargin = top.coerceIn(0, (layer.height - height).coerceAtLeast(0))
        view.layoutParams = params
    }

    private fun panelBackground(radiusDp: Int): android.graphics.drawable.Drawable {
        backgroundProvider?.invoke()?.let { return it }
        return GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = dp(radiusDp).toFloat()
            setColor(ContextCompat.getColor(context, R.color.card))
            setStroke(dp(1), ContextCompat.getColor(context, R.color.line))
        }
    }

    private fun dp(value: Int): Int =
        (value * context.resources.displayMetrics.density).toInt()
}

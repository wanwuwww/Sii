// SPDX-License-Identifier: LicenseRef-Sii-NC
// Copyright (c) 2026 wanwuwww
package com.sii.browser.ui

import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.core.graphics.ColorUtils
import com.sii.browser.R
import com.sii.browser.di.AppServices

/**
 * 卡片列表构建器。
 */
object CardList {

    const val SETTINGS_CARD_TAG = "sii_settings_card"
    const val DIVIDER_TAG = "sii_card_divider"

    private data class SurfaceTag(val page: Boolean)

    /** 卡片容器：透明度与外框跟随设置（同 [styledCard]）。 */
    fun card(context: Context): LinearLayout = styledCard(context, page = false)

    /** 页面卡片：透明度与外框跟随设置（同 [styledCard]）。 */
    fun pageCard(context: Context): LinearLayout = styledCard(context, page = true)

    /** 给外部自建的页面卡片（如书签行、历史搜索行）套用统一的透明度与边框设置。 */
    fun stylePageCard(view: View) {
        view.tag = SurfaceTag(true)
        applySurface(view, true)
    }

    /** 统一卡片：填充透明度与边框/分隔线开关由设置决定。 */
    fun styledCard(context: Context, page: Boolean): LinearLayout =
        cardWith(context, if (page) R.drawable.bg_page_card else R.drawable.bg_card).apply {
            tag = SurfaceTag(page)
            applySurface(this)
        }

    /** 兼容旧调用；透明度从设置读取，不再使用传入值。 */
    fun settingsCard(context: Context, alphaPercent: Int = 100): LinearLayout =
        styledCard(context, page = true)

    fun applySettingsAlpha(root: View, alphaPercent: Int) {
        applyCardStyle(root)
    }

    /** 实时刷新当前页面所有统一样式的卡片与分隔线。 */
    fun applyCardStyle(root: View) {
        when (val value = root.tag) {
            is SurfaceTag -> applySurface(root, value.page)
            DIVIDER_TAG -> root.setBackgroundColor(
                if (AppServices.settings.cardBorder()) root.context.getColor(R.color.line)
                else Color.TRANSPARENT
            )
        }
        if (root is ViewGroup) {
            for (i in 0 until root.childCount) applyCardStyle(root.getChildAt(i))
        }
    }

    private fun applySurface(view: View, page: Boolean? = null) {
        val isPage = page ?: (view.tag as? SurfaceTag)?.page ?: return
        val alpha = AppServices.settings.settingsCardAlpha().coerceIn(0, 100)
        val fill = ColorUtils.setAlphaComponent(
            view.context.getColor(if (isPage) R.color.card_page else R.color.card),
            alpha * 255 / 100
        )
        view.background = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = dp(view.context, 16).toFloat()
            setColor(fill)
            if (AppServices.settings.cardBorder()) {
                setStroke(dp(view.context, 1), view.context.getColor(R.color.line))
            } else {
                setStroke(0, Color.TRANSPARENT)
            }
        }
        view.clipToOutline = true
    }

    private fun cardWith(context: Context, backgroundRes: Int): LinearLayout = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        setBackgroundResource(backgroundRes)
        clipToOutline = true
        layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply {
            marginStart = dp(context, 12)
            marginEnd = dp(context, 12)
            bottomMargin = dp(context, 8)
        }
    }

    /** 垂直滚动容器：内容超屏时可滚动（列表/设置页通用）。 */
    fun scroll(context: Context, child: View): ScrollView = ScrollView(context).apply {
        isFillViewport = true
        overScrollMode = View.OVER_SCROLL_IF_CONTENT_SCROLLS
        addView(
            child,
            ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        )
    }

    /** 卡片外的分组小标题（对齐 .sec-title：12sp、sub 色、16/18/8 内边距）。 */
    fun sectionTitle(context: Context, text: CharSequence): TextView = TextView(context).apply {
        this.text = text
        setTextSize(12.5f)
        setTextColor(context.getColor(R.color.text_secondary))
        setPadding(dp(context, 18), dp(context, 16), dp(context, 18), dp(context, 8))
    }

    /** 逐行加入卡片，行与行之间自动插入分隔线。 */
    fun addRows(card: LinearLayout, rows: List<View>) {
        val styled = card.tag is SurfaceTag
        rows.forEachIndexed { index, row ->
            if (index > 0) card.addView(if (styled) styledDivider(card.context) else divider(card.context))
            card.addView(row)
        }
    }

    /** 行分隔线：1dp 全宽（对齐 .set-row 的 border-bottom）。 */
    fun divider(context: Context): View = View(context).apply {
        layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            dp(context, 1)
        )
        setBackgroundColor(context.getColor(R.color.line))
    }

    /** 统一卡片的行分隔线：跟随外框开关，不跟随背景透明度。 */
    fun styledDivider(context: Context): View = View(context).apply {
        tag = DIVIDER_TAG
        layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            dp(context, 1)
        )
        setBackgroundColor(
            if (AppServices.settings.cardBorder()) context.getColor(R.color.line)
            else Color.TRANSPARENT
        )
    }

    /** 水平行：标题/副标题 + 尾部控件，内容垂直居中。 */
    fun row(context: Context): LinearLayout = base(context, LinearLayout.HORIZONTAL).apply {
        gravity = Gravity.CENTER_VERTICAL
    }

    /** 垂直行：内容较多（如名称行 + 进度条）时使用。 */
    fun column(context: Context): LinearLayout = base(context, LinearLayout.VERTICAL)

    private fun base(context: Context, orientation: Int): LinearLayout =
        LinearLayout(context).apply {
            this.orientation = orientation
            setPadding(dp(context, 16), dp(context, 14), dp(context, 16), dp(context, 14))
            minimumHeight = dp(context, 52)
            setBackgroundResource(R.drawable.bg_list_item)
        }

    fun dp(context: Context, value: Int): Int =
        (value * context.resources.displayMetrics.density).toInt()
}

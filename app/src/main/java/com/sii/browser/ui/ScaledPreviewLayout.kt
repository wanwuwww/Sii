// SPDX-License-Identifier: LicenseRef-Sii-NC
// Copyright (c) 2026 wanwuwww
package com.sii.browser.ui

import android.content.Context
import android.util.AttributeSet
import android.view.View
import android.widget.FrameLayout
import kotlin.math.min

/** 固定 360x640dp 设计稿画布，按可用空间等比缩放并居中。 */
class ScaledPreviewLayout @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : FrameLayout(context, attrs, defStyleAttr) {

    private val designWidth = dp(360)
    private val designHeight = dp(640)

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val width = MeasureSpec.getSize(widthMeasureSpec)
        val height = MeasureSpec.getSize(heightMeasureSpec)
        setMeasuredDimension(width, height)
        val child = getChildAt(0) ?: return
        child.measure(
            MeasureSpec.makeMeasureSpec(designWidth, MeasureSpec.EXACTLY),
            MeasureSpec.makeMeasureSpec(designHeight, MeasureSpec.EXACTLY)
        )
    }

    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        val child = getChildAt(0) ?: return
        val scale = min(width.toFloat() / designWidth, height.toFloat() / designHeight)
        child.pivotX = 0f
        child.pivotY = 0f
        child.scaleX = scale
        child.scaleY = scale
        child.translationX = (width - designWidth * scale) / 2f
        child.translationY = (height - designHeight * scale) / 2f
        child.layout(0, 0, designWidth, designHeight)
    }

    private fun dp(value: Int): Int =
        (value * resources.displayMetrics.density).toInt()
}

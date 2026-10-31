// SPDX-License-Identifier: LicenseRef-Sii-NC
// Copyright (c) 2026 wanwuwww
package com.sii.browser.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.view.View
import androidx.core.graphics.ColorUtils
import com.sii.browser.R

/**
 * 下载进度条「圆点阵」样式：一排小圆点按进度逐个点亮，最后一个按比例放大。
 */
class DotsProgressView(context: Context) : View(context) {

    private val trackPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private var percent = 0f
    private val dots = 10

    init {
        fillPaint.color = ThemeAccent.color(context)
        trackPaint.color = ColorUtils.setAlphaComponent(context.getColor(R.color.line), 90)
    }

    fun setProgressPercent(value: Int) {
        val next = value.coerceIn(0, 100) / 100f
        if (next == percent) return
        percent = next
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        if (width <= 0 || height <= 0) return
        val step = width.toFloat() / dots
        val radius = minOf(height / 2f, step * 0.34f)
        for (index in 0 until dots) {
            val cx = step * (index + 0.5f)
            val cy = height / 2f
            canvas.drawCircle(cx, cy, radius, trackPaint)
            val fraction = (percent * dots - index).coerceIn(0f, 1f)
            if (fraction > 0f) {
                canvas.drawCircle(cx, cy, radius * fraction, fillPaint)
            }
        }
    }
}

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
 * 下载进度条「分段」样式：一排圆角小段，按进度逐段点亮（最后一段按比例）。
 */
class SegmentProgressView(context: Context) : View(context) {

    private val trackPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private var percent = 0f
    private val gap = (3 * resources.displayMetrics.density).toInt().coerceAtLeast(2)
    private val segments = 12

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
        val segmentWidth = ((width - gap * (segments - 1)) / segments).coerceAtLeast(1).toFloat()
        val radius = height / 2f
        for (index in 0 until segments) {
            val left = index * (segmentWidth + gap)
            val right = left + segmentWidth
            canvas.drawRoundRect(left, 0f, right, height.toFloat(), radius, radius, trackPaint)
            val fraction = (percent * segments - index).coerceIn(0f, 1f)
            if (fraction > 0f) {
                canvas.drawRoundRect(
                    left, 0f, left + segmentWidth * fraction, height.toFloat(),
                    radius, radius, fillPaint
                )
            }
        }
    }
}

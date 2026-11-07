// SPDX-License-Identifier: LicenseRef-Sii-NC
// Copyright (c) 2026 wanwuwww
package com.sii.browser.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Typeface
import android.view.View
import androidx.core.graphics.ColorUtils
import com.sii.browser.R

/**
 * 下载进度条「胶囊百分比」样式：主色填充 + 居中显示百分比，文字随填充自动反白。
 */
class PercentProgressView(context: Context) : View(context) {

    private val trackPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val darkText = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = context.getColor(R.color.text_primary)
        textAlign = Paint.Align.CENTER
        typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
    }
    private val lightText = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFFFFFFFF.toInt()
        textAlign = Paint.Align.CENTER
        typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
    }
    private var percent = 0f

    init {
        fillPaint.color = ThemeAccent.color(context)
        trackPaint.color = ColorUtils.setAlphaComponent(context.getColor(R.color.line), 90)
        val size = 11f * resources.displayMetrics.scaledDensity
        darkText.textSize = size
        lightText.textSize = size
    }

    fun setProgressPercent(value: Int) {
        val next = value.coerceIn(0, 100) / 100f
        if (next == percent) return
        percent = next
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        val radius = height / 2f
        canvas.drawRoundRect(0f, 0f, width.toFloat(), height.toFloat(), radius, radius, trackPaint)
        val filled = width * percent
        if (filled > 0f) {
            canvas.save()
            canvas.clipRect(0f, 0f, filled, height.toFloat())
            canvas.drawRoundRect(0f, 0f, width.toFloat(), height.toFloat(), radius, radius, fillPaint)
            canvas.restore()
        }
        val label = "${(percent * 100).toInt()}%"
        val baseline = height / 2f - (darkText.descent() + darkText.ascent()) / 2f
        val center = width / 2f
        canvas.drawText(label, center, baseline, darkText)
        if (filled > 0f) {
            canvas.save()
            canvas.clipRect(0f, 0f, filled, height.toFloat())
            canvas.drawText(label, center, baseline, lightText)
            canvas.restore()
        }
    }
}

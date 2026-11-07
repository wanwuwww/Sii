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
 * 下载进度条「细条」样式：圆角轨道 + 圆角填充，无动画。
 */
class LineProgressView(context: Context) : View(context) {

    private val trackPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private var percent = 0f

    init {
        val accent = ThemeAccent.color(context)
        fillPaint.color = accent
        trackPaint.color = ColorUtils.setAlphaComponent(context.getColor(R.color.line), 90)
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
        if (percent > 0f) {
            canvas.drawRoundRect(
                0f, 0f, width * percent, height.toFloat(), radius, radius, fillPaint
            )
        }
    }
}

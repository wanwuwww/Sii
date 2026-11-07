// SPDX-License-Identifier: LicenseRef-Sii-NC
// Copyright (c) 2026 wanwuwww
package com.sii.browser.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Typeface
import android.util.AttributeSet
import android.view.View
import kotlin.math.cos
import kotlin.math.sin

/**
 * 快退/快进按钮：圆环、箭头、秒数全部由同一个 Canvas 绘制，
 * 圆环圆心与文字中心都取 width/2、height/2，天然居中。
 */
class SeekButtonView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    var seconds: Int = 5
        set(value) {
            field = value
            invalidate()
        }

    /** true = 前进（顺时针箭头），false = 后退。 */
    var forward: Boolean = false
        set(value) {
            field = value
            invalidate()
        }

    private val density = resources.displayMetrics.density

    private val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 2.4f * density
        strokeCap = Paint.Cap.ROUND
        color = Color.WHITE
    }

    private val head = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.WHITE
    }

    private val label = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textAlign = Paint.Align.CENTER
        textSize = 10.5f * resources.displayMetrics.scaledDensity
        typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
    }

    override fun onDraw(canvas: Canvas) {
        val cx = width / 2f
        val cy = height / 2f
        // 圆环比按钮本身小一圈，留出空白，不和中间播放键“顶满”。
        val radius = minOf(width, height) / 2f - ring.strokeWidth - 5.5f * density
        if (radius <= 0f) return

        val gap = 62f
        if (forward) {
            val start = -90f + gap / 2f
            val sweep = 360f - gap
            canvas.drawArc(cx - radius, cy - radius, cx + radius, cy + radius, start, sweep, false, ring)
            drawHead(canvas, cx, cy, radius, start + sweep, clockwise = true)
        } else {
            val start = -90f - gap / 2f
            val sweep = -(360f - gap)
            canvas.drawArc(cx - radius, cy - radius, cx + radius, cy + radius, start, sweep, false, ring)
            drawHead(canvas, cx, cy, radius, start + sweep, clockwise = false)
        }

        val metrics = label.fontMetrics
        val baseline = cy - (metrics.ascent + metrics.descent) / 2f
        canvas.drawText(seconds.toString(), cx, baseline, label)
    }

    private fun drawHead(canvas: Canvas, cx: Float, cy: Float, radius: Float, angle: Float, clockwise: Boolean) {
        val radians = Math.toRadians(angle.toDouble())
        val sinA = sin(radians).toFloat()
        val cosA = cos(radians).toFloat()
        val px = cx + radius * cosA
        val py = cy + radius * sinA
        val tangentX = if (clockwise) -sinA else sinA
        val tangentY = if (clockwise) cosA else -cosA
        val length = 4.0f * density
        val half = 2.6f * density
        val normalX = -tangentY
        val normalY = tangentX
        val path = Path().apply {
            moveTo(px + tangentX * length, py + tangentY * length)
            lineTo(px + normalX * half, py + normalY * half)
            lineTo(px - normalX * half, py - normalY * half)
            close()
        }
        canvas.drawPath(path, head)
    }
}

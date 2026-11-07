// SPDX-License-Identifier: LicenseRef-Sii-NC
// Copyright (c) 2026 wanwuwww
package com.sii.browser.ui

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Shader
import android.view.View
import android.view.animation.LinearInterpolator
import androidx.core.graphics.ColorUtils
import com.sii.browser.R

/**
 * 下载进度条「渐变流光」样式：细条填充 + 一条高光循环扫过，只在下载中播放。
 */
class ShimmerProgressView(context: Context) : View(context) {

    private val trackPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val glossPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private var percent = 0f
    private var running = false
    private var phase = 0f
    private var animator: ValueAnimator? = null

    init {
        fillPaint.color = ThemeAccent.color(context)
        trackPaint.color = ColorUtils.setAlphaComponent(context.getColor(R.color.line), 90)
        val band = (60 * resources.displayMetrics.density).toInt().coerceAtLeast(40)
        glossPaint.shader = LinearGradient(
            0f, 0f, band.toFloat(), 0f,
            intArrayOf(0x00FFFFFF, 0x66FFFFFF, 0x00FFFFFF),
            floatArrayOf(0f, 0.5f, 1f),
            Shader.TileMode.CLAMP
        )
    }

    fun setProgressPercent(value: Int) {
        val next = value.coerceIn(0, 100) / 100f
        if (next == percent) return
        percent = next
        invalidate()
    }

    fun setRunning(running: Boolean) {
        this.running = running
        if (running) startAnimator() else stopAnimator()
    }

    private fun startAnimator() {
        if (animator != null || !isAttachedToWindow) return
        animator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 1100L
            repeatCount = ValueAnimator.INFINITE
            interpolator = LinearInterpolator()
            addUpdateListener {
                phase = it.animatedValue as Float
                invalidate()
            }
            start()
        }
    }

    private fun stopAnimator() {
        animator?.cancel()
        animator = null
        invalidate()
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        if (running) startAnimator()
    }

    override fun onDetachedFromWindow() {
        stopAnimator()
        super.onDetachedFromWindow()
    }

    override fun onDraw(canvas: Canvas) {
        val radius = height / 2f
        canvas.drawRoundRect(0f, 0f, width.toFloat(), height.toFloat(), radius, radius, trackPaint)
        if (percent <= 0f) return
        canvas.save()
        canvas.clipRect(0f, 0f, width * percent, height.toFloat())
        canvas.drawRoundRect(0f, 0f, width.toFloat(), height.toFloat(), radius, radius, fillPaint)
        if (running) {
            val band = bandWidth()
            val x = (width + band) * phase - band
            canvas.translate(x, 0f)
            canvas.drawRect(0f, 0f, band, height.toFloat(), glossPaint)
            canvas.translate(-x, 0f)
        }
        canvas.restore()
    }

    private fun bandWidth(): Float =
        (60 * resources.displayMetrics.density).coerceAtLeast(40f)
}

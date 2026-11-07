// SPDX-License-Identifier: LicenseRef-Sii-NC
// Copyright (c) 2026 wanwuwww
package com.sii.browser.ui

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.view.View
import android.view.animation.LinearInterpolator
import androidx.core.graphics.ColorUtils
import com.sii.browser.R

/**
 * 下载进度条：圆角轨道 + 双层正弦波填充。
 *
 * 下载中波形横向流动；暂停/失败停在当前相位；完成时填满并停波。
 * 总大小未知时按整条流动波显示（相当于不定态）。
 */
class WaveProgressView(context: Context) : View(context) {

    private val trackPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val wavePaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val backWavePaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val rect = RectF()
    private val path = Path()

    private var percent = 0f
    private var phase = 0f
    private var indeterminate = false
    private var running = false
    private var animator: ValueAnimator? = null

    init {
        val accent = ThemeAccent.color(context)
        wavePaint.color = accent
        backWavePaint.color = ColorUtils.setAlphaComponent(accent, 110)
        trackPaint.color = ColorUtils.setAlphaComponent(context.getColor(R.color.line), 90)
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        rect.set(0f, 0f, w.toFloat(), h.toFloat())
    }

    /** 进度百分比（0-100）。 */
    fun setProgressPercent(value: Int) {
        val next = value.coerceIn(0, 100) / 100f
        if (next == percent) return
        percent = next
        invalidate()
    }

    /** 总大小未知：整条铺满并让波形持续流动。 */
    fun setIndeterminate(value: Boolean) {
        if (indeterminate == value) return
        indeterminate = value
        invalidate()
    }

    /** 只有下载中的任务才让波形流动，避免暂停的任务白跑动画。 */
    fun setRunning(running: Boolean) {
        this.running = running
        if (running) startAnimator() else stopAnimator()
    }

    private fun startAnimator() {
        if (animator != null) return
        if (!isAttachedToWindow) return
        animator = ValueAnimator.ofFloat(0f, (2 * Math.PI).toFloat()).apply {
            duration = 1400L
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
        canvas.drawRoundRect(rect, radius, radius, trackPaint)
        val visible = if (indeterminate) 1f else percent
        if (visible <= 0f) return
        canvas.save()
        if (!indeterminate) canvas.clipRect(0f, 0f, width * visible, height.toFloat())
        val baseY = if (visible < 1f) height * 0.62f else height * 0.42f
        // 两层波反相位，叠加出细碎的流动感。
        drawWave(canvas, backWavePaint, baseY, Math.PI.toFloat())
        drawWave(canvas, wavePaint, baseY, 0f)
        canvas.restore()
    }

    private fun drawWave(canvas: Canvas, paint: Paint, baseY: Float, offset: Float) {
        val amplitude = height * 0.34f
        val wavelength = (width * 0.55f).coerceAtLeast(1f)
        val step = 3f
        val right = if (indeterminate) width.toFloat() else width * percent
        path.reset()
        path.moveTo(0f, height.toFloat())
        var x = 0f
        val waveLengthRad = 2f * Math.PI.toFloat() / wavelength
        while (x <= right) {
            val angle = waveLengthRad * x + phase + offset
            path.lineTo(x, baseY + amplitude * kotlin.math.sin(angle))
            x += step
        }
        path.lineTo(right, height.toFloat())
        path.close()
        canvas.drawPath(path, paint)
    }
}

// SPDX-License-Identifier: LicenseRef-Sii-NC
// Copyright (c) 2026 wanwuwww
package com.sii.browser.ui

import android.content.Context
import android.graphics.Color
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.LayerDrawable
import com.sii.browser.di.AppServices

/**
 * 玻璃材质：统一由「玻璃强度」推导填充、描边与顶部高光，供底栏、面板、卡片、搜索框共用。
 */
object GlassStyle {

    fun fill(context: Context, dark: Boolean, strength: Int, minAlpha: Int = 150, base: Int? = null): Int {
        val s = strength.coerceIn(0, 100)
        // 强度越高越通透：0 完全不透明，100 减 100 个 alpha，再由 minAlpha 保底。
        val alpha = (255 - s).coerceIn(minAlpha.coerceIn(0, 255), 255)
        val rgb = base?.let { it and 0x00FFFFFF } ?: if (dark) 0x1B1E23 else 0xFFFFFF
        return (alpha shl 24) or rgb
    }

    fun stroke(dark: Boolean, strength: Int): Int {
        val s = strength.coerceIn(0, 100)
        val alpha = (0x16 + s * 0x30 / 100).coerceIn(0x10, 0x50)
        return (alpha shl 24) or if (dark) 0xFFFFFF else 0x000000
    }

    /**
     * 玻璃表面：填充 + 1dp 描边 + 顶部高光渐变。
     *
     * 高光是自上而下衰减的白色渐变，模拟玻璃上缘的反光；夜间减半，避免发灰。
     * 返回 LayerDrawable，调用方直接赋给 background 即可。
     */
    fun surface(
        context: Context,
        dark: Boolean,
        radiusPx: Float,
        strength: Int,
        topOnly: Boolean = false,
        minAlpha: Int = 150,
        base: Int? = null,
        highlight: Float = if (dark) 0.06f else 0.10f
    ): Drawable {
        val radii = cornerRadii(radiusPx, topOnly)
        val body = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadii = radii
            setColor(fill(context, dark, strength, minAlpha, base))
            setStroke(
                context.resources.displayMetrics.density.toInt().coerceAtLeast(1),
                stroke(dark, strength)
            )
        }
        val gloss = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadii = radii
            gradientType = GradientDrawable.LINEAR_GRADIENT
            orientation = GradientDrawable.Orientation.TOP_BOTTOM
            colors = intArrayOf(
                withAlpha(Color.WHITE, highlight),
                withAlpha(Color.WHITE, highlight * 0.35f),
                withAlpha(Color.WHITE, 0f)
            )
        }
        return LayerDrawable(arrayOf(body, gloss))
    }

    /** 圆角四角，topOnly 时只圆上方两角（贴底面板用）。 */
    fun cornerRadii(radiusPx: Float, topOnly: Boolean = false): FloatArray =
        if (topOnly) {
            floatArrayOf(radiusPx, radiusPx, radiusPx, radiusPx, 0f, 0f, 0f, 0f)
        } else {
            floatArrayOf(
                radiusPx, radiusPx, radiusPx, radiusPx,
                radiusPx, radiusPx, radiusPx, radiusPx
            )
        }

    private fun withAlpha(color: Int, alpha: Float): Int {
        val a = (alpha.coerceIn(0f, 1f) * 255).toInt()
        return (color and 0x00FFFFFF) or (a shl 24)
    }
}

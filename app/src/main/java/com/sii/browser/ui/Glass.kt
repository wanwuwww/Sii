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
 * 玻璃材质：统一由「玻璃强度」推导填充、描边、顶部高光与底部内阴影。
 *
 * 高光负责「反光」，内阴影负责「厚度」——浅色背景下高光几乎不可见，
 * 真正让玻璃有存在感的是描边与内阴影，因此两者都保留。
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
        // 白天加深描边，否则白色玻璃叠白色背景时边界不可见。
        val alpha = if (dark) {
            (0x18 + s * 0x38 / 100).coerceIn(0x18, 0x50)
        } else {
            (0x24 + s * 0x36 / 100).coerceIn(0x24, 0x5A)
        }
        return (alpha shl 24) or if (dark) 0xFFFFFF else 0x000000
    }

    /**
     * 玻璃表面：填充 + 描边 + 顶部高光 + 底部内阴影。
     *
     * @param highlight 顶部白色高光的起始强度（白底上会失效，所以默认较低）
     * @param shade     底部黑色内阴影强度，模拟玻璃下缘的厚度
     * @param strokeOverride 指定描边色（例如搜索框聚焦时的主色描边）
     */
    fun surface(
        context: Context,
        dark: Boolean,
        radiusPx: Float,
        strength: Int,
        topOnly: Boolean = false,
        minAlpha: Int = 150,
        base: Int? = null,
        highlight: Float = if (dark) 0.10f else 0.07f,
        shade: Float = if (dark) 0.04f else 0.06f,
        strokeOverride: Int? = null
    ): Drawable {
        val radii = cornerRadii(radiusPx, topOnly)
        val body = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadii = radii
            setColor(fill(context, dark, strength, minAlpha, base))
            setStroke(
                context.resources.displayMetrics.density.toInt().coerceAtLeast(1),
                strokeOverride ?: stroke(dark, strength)
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
        val innerShade = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadii = radii
            gradientType = GradientDrawable.LINEAR_GRADIENT
            orientation = GradientDrawable.Orientation.BOTTOM_TOP
            colors = intArrayOf(
                withAlpha(Color.BLACK, shade),
                withAlpha(Color.BLACK, 0f)
            )
        }
        return LayerDrawable(arrayOf(body, gloss, innerShade))
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

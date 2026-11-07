// SPDX-License-Identifier: LicenseRef-Sii-NC
// Copyright (c) 2026 wanwuwww
package com.sii.browser.ui

import android.content.Context
import android.graphics.Color
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.LayerDrawable

/**
 * 玻璃材质：统一由「玻璃强度」推导填充、描边、顶部高光与底部内阴影。
 *
 * 高光负责「反光」，内阴影负责「厚度」——浅色背景下高光几乎不可见，
 * 真正让玻璃有存在感的是描边与内阴影，因此两者都保留。
 */
object GlassStyle {

    /** 胶囊底栏不透明度下限：强度拉满也要压住网页原文，否则透出的文字会被读成渲染异常。 */
    const val DOCK_GLASS_MIN_ALPHA = 96

    fun fill(context: Context, dark: Boolean, strength: Int, minAlpha: Int = 150, base: Int? = null): Int {
        val s = strength.coerceIn(0, 100)
        // 0–40 段维持 1:1（默认强度观感不变），之后加速，强度 100 时 alpha 约 75。
        val alpha = if (s <= 40) 255 - s else 215 - (s - 40) * 7 / 3
        val clamped = alpha.coerceIn(minAlpha.coerceIn(0, 255), 255)
        val rgb = base?.let { it and 0x00FFFFFF } ?: if (dark) 0x1B1E23 else 0xFFFFFF
        return (clamped shl 24) or rgb
    }

    /** 磨砂底填充（搜索框、常用网站卡片、悬浮底栏、建议弹层共用）：白天约 38%、夜间约 52%。 */
    fun frostedFill(dark: Boolean): Int =
        ((if (dark) 0x85 else 0x61) shl 24) or (if (dark) 0x1B1E23 else 0xFFFFFF)

    /** 磨砂表面：半透明填充 + 亮描边 + 顶部高光 + 底部内阴影；overlay 用于悬浮底栏的进度层。 */
    fun frosted(
        context: Context,
        dark: Boolean,
        radiusPx: Float,
        fillOverride: Int? = null,
        strokeOverride: Int? = null,
        overlay: Drawable? = null
    ): Drawable =
        surface(
            context, dark, radiusPx, strength = 100,
            fillOverride = fillOverride ?: frostedFill(dark),
            highlight = 0.08f,
            shade = 0.03f,
            strokeOverride = strokeOverride ?: if (dark) 0x3DFFFFFF else 0x24000000,
            overlay = overlay
        )

    fun stroke(dark: Boolean, strength: Int): Int {
        val s = strength.coerceIn(0, 100)
        // 默认保持轻盈的描边；需要「看得见的边缘」的组件自行用 strokeOverride 加深。
        val alpha = (0x16 + s * 0x30 / 100).coerceIn(0x10, 0x50)
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
        highlight: Float = if (dark) 0.10f else 0.06f,
        shade: Float = 0f,
        strokeOverride: Int? = null,
        overlay: Drawable? = null,
        fillOverride: Int? = null
    ): Drawable {
        val radii = cornerRadii(radiusPx, topOnly)
        val body = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadii = radii
            setColor(fillOverride ?: fill(context, dark, strength, minAlpha, base))
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
        val layers = ArrayList<Drawable>(4)
        layers.add(body)
        // 进度填充层插在填充与高光之间，保证高光/内阴影仍在最上层。
        overlay?.let { layers.add(it) }
        // highlight = 0 表示不要高光层（悬浮底栏）。
        if (highlight > 0f) layers.add(gloss)
        layers.add(innerShade)
        return LayerDrawable(layers.toTypedArray())
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

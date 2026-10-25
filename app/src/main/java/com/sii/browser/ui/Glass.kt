package com.sii.browser.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.webkit.WebView
import androidx.core.graphics.drawable.RoundedBitmapDrawableFactory
import com.sii.browser.di.AppServices

/**
 * 玻璃材质：统一由「玻璃强度」推导填充与描边，供底栏、面板、主页卡片共用。
 */
object GlassStyle {

    fun fill(context: Context, dark: Boolean, strength: Int): Int {
        val s = strength.coerceIn(0, 100)
        // 强度越高越通透；最底保留一层颜色，避免完全透明时文字发虚。
        val alpha = (255 - s * 150 / 100).coerceIn(70, 255)
        val base = if (dark) 0x1B1E23 else 0xFFFFFF
        return (alpha shl 24) or base
    }

    fun stroke(dark: Boolean, strength: Int): Int {
        val s = strength.coerceIn(0, 100)
        val alpha = (0x16 + s * 0x30 / 100).coerceIn(0x10, 0x50)
        return (alpha shl 24) or if (dark) 0xFFFFFF else 0x000000
    }

    /** 圆角玻璃底：radiusPx 为像素；topOnly 为 true 时只圆上方两角。 */
    fun background(
        context: Context,
        dark: Boolean,
        radiusPx: Float,
        strength: Int,
        topOnly: Boolean = false
    ): GradientDrawable = GradientDrawable().apply {
        shape = GradientDrawable.RECTANGLE
        cornerRadii = if (topOnly) {
            floatArrayOf(radiusPx, radiusPx, radiusPx, radiusPx, 0f, 0f, 0f, 0f)
        } else {
            floatArrayOf(
                radiusPx, radiusPx, radiusPx, radiusPx,
                radiusPx, radiusPx, radiusPx, radiusPx
            )
        }
        setColor(fill(context, dark, strength))
        setStroke(context.resources.displayMetrics.density.toInt().coerceAtLeast(1), stroke(dark, strength))
    }
}

/**
 * 实时模糊背景层。
 *
 * 把当前网页缩到 1/10 再放大，形成模糊底图；作为背景层叠在玻璃填充下方。
 * 不插入子视图，避免破坏底栏/面板的布局。
 */
object GlassBlur {

    fun layer(
        context: Context,
        source: WebView?,
        width: Int,
        height: Int,
        radiusPx: Float
    ): Drawable? {
        if (!AppServices.settings.glassBlur()) return null
        if (source == null || source.width <= 0 || source.height <= 0) return null
        if (width <= 0 || height <= 0) return null
        val smallWidth = (width / 10).coerceAtLeast(8)
        val smallHeight = (height / 10).coerceAtLeast(8)
        val small = Bitmap.createBitmap(smallWidth, smallHeight, Bitmap.Config.ARGB_8888)
        runCatching {
            val canvas = Canvas(small)
            canvas.scale(
                smallWidth.toFloat() / source.width,
                smallHeight.toFloat() / source.height
            )
            source.draw(canvas)
        }
        return RoundedBitmapDrawableFactory.create(context.resources, small).apply {
            cornerRadius = radiusPx
            setAntiAlias(true)
        }
    }
}

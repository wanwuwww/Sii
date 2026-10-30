// SPDX-License-Identifier: LicenseRef-Sii-NC
// Copyright (c) 2026 wanwuwww
package com.sii.browser.ui

import android.content.Context
import androidx.core.graphics.ColorUtils
import com.sii.browser.R
import com.sii.browser.di.AppServices

/**
 * 由主色推导的界面底色。
 *
 * 主色来自主题 `colorAccent`（设置 → 主题色），淡底只用于给页面底/工具栏叠一层极淡的
 * 主色，文字与控件颜色仍由 text_* 与 icon_* 决定，避免出现浅底浅字。
 */
object ThemePalette {

    private const val LIGHT_WEIGHT = 0.06f
    private const val DARK_WEIGHT = 0.12f

    /** 白天的淡底叠在页面底上，夜间叠在深色底上。 */
    fun soft(context: Context, weight: Float = 0f): Int {
        val night = AppServices.settings.isNightMode()
        val ratio = if (weight > 0f) weight else if (night) DARK_WEIGHT else LIGHT_WEIGHT
        return ColorUtils.blendARGB(
            context.getColor(R.color.bg_page),
            ThemeAccent.color(context),
            ratio
        )
    }

    /** 卡片填充：默认跟随主题色，只叠很淡的一层主色。 */
    fun cardFill(context: Context, page: Boolean): Int {
        val night = AppServices.settings.isNightMode()
        val base = context.getColor(if (page) R.color.card_page else R.color.card)
        return when (AppServices.settings.cardBg().coerceIn(0, 3)) {
            0 -> ColorUtils.blendARGB(
                base, ThemeAccent.color(context), if (night) 0.10f else 0.05f
            )
            2 -> if (night) 0xFF1D2024.toInt() else 0xFFF3F4F6.toInt()
            3 -> if (night) 0xFF262A30.toInt() else 0xFFE9EBEF.toInt()
            else -> base
        }
    }
}

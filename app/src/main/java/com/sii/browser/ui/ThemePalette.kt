// SPDX-License-Identifier: LicenseRef-Sii-NC
// Copyright (c) 2026 wanwuwww
package com.sii.browser.ui

import android.content.Context
import androidx.core.graphics.ColorUtils
import com.sii.browser.R
import com.sii.browser.di.AppServices

/** 主题色应用范围里的元素：对应「主题 → 主题色应用范围」的 6 个开关。 */
enum class ThemePart { TOOLBAR, TABS, SITES, HOME_BG, MENU, STATUS }

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

    /** 该元素是否开启「使用主题色」。 */
    fun enabled(part: ThemePart): Boolean {
        val s = AppServices.settings
        return when (part) {
            ThemePart.TOOLBAR -> s.themePartToolbar()
            ThemePart.TABS -> s.themePartTabs()
            ThemePart.SITES -> s.themePartSites()
            ThemePart.HOME_BG -> s.themePartHomeBg()
            ThemePart.MENU -> s.themePartMenu()
            ThemePart.STATUS -> s.themePartStatus()
        }
    }

    /** 元素的主题色淡底；未开启时返回 null，由调用方回落默认色。 */
    fun fill(context: Context, part: ThemePart, onWeb: Boolean = false): Int? {
        if (!enabled(part)) return null
        // 夜间默认不生效（「夜间生效」开关打开后才套用）。
        if (AppServices.settings.isNightMode() && !AppServices.settings.themePartsNight()) return null
        // 这 6 个开关只作用于主页；网页还要额外看「网页应用主题色」。
        if (onWeb && !AppServices.settings.themeOverDetect()) return null
        return soft(context)
    }

    /** 卡片填充：默认跟随主题色，只叠很淡的一层主色。 */
    fun cardFill(context: Context, page: Boolean): Int {
        val night = AppServices.settings.isNightMode()
        val base = context.getColor(if (page) R.color.card_page else R.color.card)
        return when (AppServices.settings.cardBg().coerceIn(0, 3)) {
            0 -> if (night && !AppServices.settings.themePartsNight()) base
            else ColorUtils.blendARGB(
                base, ThemeAccent.color(context), if (night) 0.10f else 0.05f
            )
            2 -> if (night) 0xFF1D2024.toInt() else 0xFFF3F4F6.toInt()
            3 -> if (night) 0xFF262A30.toInt() else 0xFFE9EBEF.toInt()
            else -> base
        }
    }
}

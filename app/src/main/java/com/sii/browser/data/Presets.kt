// SPDX-License-Identifier: LicenseRef-Sii-NC
// Copyright (c) 2026 wanwuwww
package com.sii.browser.data

/**
 * 界面风格预设：只写入「界面DIY」里现有的设置项。
 *
 * DIY 的「界面风格预设」与首次启动共用这里，避免两处参数漂移。
 */
object Presets {

    const val CLASSIC = 0
    const val GLASS = 1
    const val MINIMAL = 2
    const val FOCUS = 3

    fun apply(settings: Settings, index: Int) {
        when (index) {
            CLASSIC -> {
                settings.setUiStyle(0)
                settings.setGlassStrength(30)
                settings.setTabCardGrid(true)
                settings.setTabGridColumns(2)
                settings.setMenuScrollStyle(0)
                settings.setSettingsCardAlpha(100)
                settings.setCardBorder(true)
                settings.setAddressStyle(2)
                settings.setThemeColorEnabled(true)
                settings.setHomeShowLabels(true)
                settings.setShowSites(true)
                settings.setHomeSitesShadow(false)
                settings.setHomeSearchShadow(false)
                settings.setHomeLogoMode(1)
                settings.setHomeSearchRadius(3)
                settings.setHomeSearchRadiusCustom(28)
                settings.setHomeTopAddress(false)
                settings.setNightMode(2)
            }
            GLASS -> {
                settings.setUiStyle(1)
                settings.setGlassStrength(40)
                settings.setTabCardGrid(true)
                settings.setTabGridColumns(3)
                settings.setMenuScrollStyle(0)
                settings.setSettingsCardAlpha(0)
                settings.setCardBorder(true)
                settings.setThemeColorEnabled(false)
                settings.setHomeShowLabels(true)
                settings.setShowSites(true)
                settings.setHomeSitesCardBg(true)
                settings.setHomeSitesShadow(true)
                settings.setHomeSearchShadow(true)
                settings.setHomeLogoMode(1)
                settings.setHomeSearchRadius(3)
                settings.setHomeSearchRadiusCustom(28)
                settings.setHomeTopAddress(false)
                settings.setNightMode(2)
            }
            MINIMAL -> {
                settings.setUiStyle(0)
                settings.setTabCardGrid(true)
                settings.setTabGridColumns(3)
                settings.setMenuScrollStyle(0)
                settings.setSettingsCardAlpha(0)
                settings.setCardBorder(false)
                settings.setAddressStyle(0)
                settings.setThemeColorEnabled(false)
                settings.setGlassStrength(0)
                settings.setHomeShowLabels(true)
                settings.setShowSites(true)
                // 极简白：站点直接浮在主页背景上。
                settings.setHomeSitesCardBg(false)
                settings.setHomeSitesShadow(false)
                settings.setHomeSearchShadow(false)
                settings.setHomeLogoMode(0)
                settings.setHomeSearchRadius(0)
                settings.setHomeSearchRadiusCustom(28)
                settings.setHomeTopAddress(false)
                settings.setNightMode(2)
            }
            else -> {
                settings.setUiStyle(1)
                settings.setGlassStrength(0)
                settings.setTabCardGrid(false)
                settings.setTabGridColumns(2)
                settings.setMenuScrollStyle(1)
                settings.setSettingsCardAlpha(0)
                settings.setCardBorder(false)
                settings.setAddressStyle(1)
                settings.setThemeColorEnabled(false)
                settings.setHomeShowLabels(false)
                settings.setShowSites(false)
                settings.setHomeLogoMode(2)
                settings.setHomeSearchRadius(3)
                settings.setHomeSearchRadiusCustom(28)
                settings.setHomeTopAddress(false)
                settings.setNightMode(2)
            }
        }
    }
}

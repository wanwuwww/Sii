// SPDX-License-Identifier: LicenseRef-Sii-NC
// Copyright (c) 2026 wanwuwww
package com.sii.browser.data

import com.sii.browser.core.privacy.PrivacyPacks
import com.sii.browser.di.AppServices

/**
 * 隐私保护等级：等级与各分类开关的唯一映射，设置页与菜单快捷开关共用。
 */
object PrivacyLevels {

    fun apply(settings: Settings, level: Int) {
        val normalized = level.coerceIn(0, 3)
        settings.setPrivacyLevel(normalized)
        when (normalized) {
            0 -> {
                settings.setPrivacyTracking(false)
                settings.setPrivacySocial(false)
                settings.setPrivacyMiners(false)
                settings.setPrivacyFingerprint(false)
                settings.setPrivacyContent(false)
                settings.setPrivacyCookies(false)
                settings.setPrivacyCleanUrls(false)
                settings.setPrivacyGpc(false)
                settings.setPrivacyHttpsOnly(false)
            }
            1 -> {
                settings.setPrivacyTracking(true)
                settings.setPrivacySocial(true)
                settings.setPrivacyMiners(true)
                settings.setPrivacyFingerprint(true)
                settings.setPrivacyContent(false)
                settings.setPrivacyCookies(false)
                settings.setPrivacyCleanUrls(false)
                settings.setPrivacyGpc(true)
                settings.setPrivacyHttpsOnly(false)
            }
            2 -> {
                settings.setPrivacyTracking(true)
                settings.setPrivacySocial(true)
                settings.setPrivacyMiners(true)
                settings.setPrivacyFingerprint(true)
                settings.setPrivacyContent(true)
                settings.setPrivacyCookies(true)
                settings.setPrivacyCleanUrls(false)
                settings.setPrivacyGpc(true)
                settings.setPrivacyHttpsOnly(false)
            }
        }
    }

    /** 关闭前快照等级与各分类开关，供菜单开关下次开启时原样恢复。 */
    fun snapshot(settings: Settings): Int {
        var state = settings.privacyLevel().coerceIn(0, 3)
        fun bit(index: Int, value: Boolean) {
            if (value) state = state or (1 shl index)
        }
        bit(2, settings.privacyTracking())
        bit(3, settings.privacySocial())
        bit(4, settings.privacyMiners())
        bit(5, settings.privacyFingerprint())
        bit(6, settings.privacyContent())
        bit(7, settings.privacyCookies())
        bit(8, settings.privacyCleanUrls())
        bit(9, settings.privacyGpc())
        bit(10, settings.privacyHttpsOnly())
        return state
    }

    fun restore(settings: Settings, state: Int) {
        settings.setPrivacyLevel(state and 0b11)
        settings.setPrivacyTracking(state and (1 shl 2) != 0)
        settings.setPrivacySocial(state and (1 shl 3) != 0)
        settings.setPrivacyMiners(state and (1 shl 4) != 0)
        settings.setPrivacyFingerprint(state and (1 shl 5) != 0)
        settings.setPrivacyContent(state and (1 shl 6) != 0)
        settings.setPrivacyCookies(state and (1 shl 7) != 0)
        settings.setPrivacyCleanUrls(state and (1 shl 8) != 0)
        settings.setPrivacyGpc(state and (1 shl 9) != 0)
        settings.setPrivacyHttpsOnly(state and (1 shl 10) != 0)
    }

    /** 按当前分类准备隐私规则包并载入引擎。 */
    suspend fun ensureRules(settings: Settings) {
        val categories = buildSet {
            if (settings.privacyTracking()) add(PrivacyPacks.TRACKING)
            if (settings.privacySocial()) add(PrivacyPacks.SOCIAL)
            if (settings.privacyMiners()) add(PrivacyPacks.MINERS)
            if (settings.privacyFingerprint()) add(PrivacyPacks.FINGERPRINT)
            if (settings.privacyContent()) add(PrivacyPacks.CONTENT)
        }
        AppServices.ruleRepository.ensurePrivacyPacks(categories)
        AppServices.ruleRepository.loadIntoEngine(AppServices.adBlock)
    }
}

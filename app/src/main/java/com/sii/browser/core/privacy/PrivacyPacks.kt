// SPDX-License-Identifier: LicenseRef-Sii-NC
// Copyright (c) 2026 wanwuwww
package com.sii.browser.core.privacy

/** 隐私保护规则包：按类别下载，不打包进 APK。 */
object PrivacyPacks {

    const val TRACKING = "tracking"
    const val SOCIAL = "social"
    const val MINERS = "miners"
    const val FINGERPRINT = "fingerprint"
    const val CONTENT = "content"

    data class Pack(val category: String, val label: String, val url: String)

    val ALL = listOf(
        Pack(TRACKING, "EasyPrivacy", "https://easylist.to/easylist/easyprivacy.txt"),
        Pack(SOCIAL, "Fanboy Social", "https://easylist.to/easylist/fanboy-social.txt"),
        Pack(
            MINERS,
            "NoCoin",
            "https://cdn.jsdelivr.net/gh/hoshsadiq/adblock-nocoin-list@master/nocoin.txt"
        ),
        Pack(
            FINGERPRINT,
            "uBlock Privacy",
            "https://cdn.jsdelivr.net/gh/uBlockOrigin/uAssets@master/filters/privacy.txt"
        ),
        Pack(
            CONTENT,
            "AdGuard Tracking",
            "https://cdn.jsdelivr.net/gh/AdguardTeam/AdguardFilters@master/SpywareFilter/sections/tracking_servers.txt"
        )
    )

    fun byCategory(category: String): Pack? = ALL.firstOrNull { it.category == category }
}

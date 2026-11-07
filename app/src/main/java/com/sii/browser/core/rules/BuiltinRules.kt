// SPDX-License-Identifier: LicenseRef-Sii-NC
// Copyright (c) 2026 wanwuwww
package com.sii.browser.core.rules

/**
 * 内建规则。
 * 覆盖常见广告/统计域名，用户可另行添加自定义规则与订阅。
 */
object BuiltinRules {
    val RULES: List<String> = listOf(
        "||doubleclick.net^",
        "||googlesyndication.com^",
        "||googleadservices.com^",
        "||adservice.google.com^",
        "||adnxs.com^",
        "||ads.yahoo.com^",
        "||cpro.baidu.com^",
        "||pos.baidu.com^",
        "||hm.baidu.com^",
        "||tanx.com^",
        "||alimama.com^",
        "||mmstat.com^",
        "||cnzz.com^",
        "||umeng.com^"
    )
}

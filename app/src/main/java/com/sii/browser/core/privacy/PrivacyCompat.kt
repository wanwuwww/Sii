// SPDX-License-Identifier: LicenseRef-Sii-NC
// Copyright (c) 2026 wanwuwww
package com.sii.browser.core.privacy

/**
 * 兼容模式放行的功能性域名：验证码、第三方登录、支付、客服组件。
 * 只放行隐私规则，广告规则仍然生效；仅「标准」档应用。
 */
object PrivacyCompat {

    val HOSTS: Set<String> = setOf(
        // 人机验证
        "recaptcha.net",
        "www.recaptcha.net",
        "hcaptcha.com",
        "challenges.cloudflare.com",
        // 第三方登录
        "accounts.google.com",
        "appleid.apple.com",
        "login.microsoftonline.com",
        "open.weixin.qq.com",
        "open.work.weixin.qq.com",
        // 支付
        "js.stripe.com",
        "checkout.stripe.com",
        "pay.google.com",
        "www.paypalobjects.com",
        // 客服组件
        "client.crisp.chat",
        "widget.intercom.io",
        "static.zdassets.com"
    )
}

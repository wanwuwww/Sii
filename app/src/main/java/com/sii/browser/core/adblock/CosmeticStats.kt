// SPDX-License-Identifier: LicenseRef-Sii-NC
// Copyright (c) 2026 wanwuwww
package com.sii.browser.core.adblock

/** 最近一次页面注入元素隐藏规则后的命中统计，供「广告拦截」页展示。 */
object CosmeticStats {

    @Volatile
    var host: String? = null
        private set

    /** 被隐藏的节点数。 */
    @Volatile
    var hidden: Int = 0
        private set

    /** 生效的元素隐藏规则条数。 */
    @Volatile
    var rules: Int = 0
        private set

    fun update(host: String?, hidden: Int, rules: Int) {
        this.host = host
        this.hidden = hidden
        this.rules = rules
    }
}

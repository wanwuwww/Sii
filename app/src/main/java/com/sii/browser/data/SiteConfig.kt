// SPDX-License-Identifier: LicenseRef-Sii-NC
// Copyright (c) 2026 wanwuwww
package com.sii.browser.data

import org.json.JSONObject

/**
 * 单站配置解析：只存显式覆盖，缺省项一律回落到全局设置。
 * 三态值：1=开启/允许，2=关闭/拒绝，缺失=跟随全局。
 * 权限四态：0=跟随全局，1=允许，2=拒绝，3=始终允许。
 */
object SiteConfig {

    fun parse(raw: String?): JSONObject? =
        raw?.let { runCatching { JSONObject(it) }.getOrNull() }

    fun enabled(config: JSONObject?): Boolean =
        config?.optBoolean("on", true) ?: true

    fun tri(config: JSONObject?, key: String): Int? {
        if (config == null || !config.has(key)) return null
        val value = config.opt(key)
        if (value is Boolean) return if (value) 1 else 2
        val int = config.optInt(key, 0)
        return if (int == 1 || int == 2) int else null
    }

    fun permission(config: JSONObject?, key: String, legacyKey: String? = null): Int {
        if (config == null) return 0
        if (config.has(key)) {
            val value = config.opt(key)
            if (value is Boolean) return if (value) 1 else 2
            return config.optInt(key, 0).coerceIn(0, 4)
        }
        if (legacyKey != null && config.has(legacyKey)) {
            return if (config.optBoolean(legacyKey, true)) 1 else 2
        }
        return 0
    }

    fun text(config: JSONObject?, key: String): String? =
        config?.optString(key)?.takeIf { it.isNotBlank() && it != "null" }

    /** 本站是否启用用户脚本（js=2 表示本站停用）。 */
    fun scriptsEnabled(config: JSONObject?): Boolean = tri(config, "js") != 2

    /** 生成写入后的站点配置：enabled=false 写 js=2，true 则移除该键。 */
    fun withScriptsEnabled(raw: String?, enabled: Boolean): String {
        val json = parse(raw) ?: JSONObject().put("on", true)
        if (enabled) json.remove("js") else json.put("js", 2)
        return json.toString()
    }

    /** 本站是否启用了资源嗅探（sn=1）。缺省=未启用。 */
    fun sniffEnabled(config: JSONObject?): Boolean = tri(config, "sn") == 1

    /** 快速判断：raw 里没有 sn 键就不解析 JSON，供拦截器/脚本桥每次请求调用。 */
    fun sniffEnabledRaw(raw: String?): Boolean {
        if (raw.isNullOrBlank() || !raw.contains("\"sn\"")) return false
        return sniffEnabled(parse(raw))
    }

    /** 生成写入后的站点配置：enabled=true 写 sn=1，否则移除该键。 */
    fun withSniffEnabled(raw: String?, enabled: Boolean): String {
        val json = parse(raw) ?: JSONObject().put("on", true)
        if (enabled) json.put("sn", 1) else json.remove("sn")
        return json.toString()
    }

    fun intOpt(config: JSONObject?, key: String): Int? {
        if (config == null || !config.has(key)) return null
        return config.optInt(key, 0).takeIf { it > 0 }
    }
}

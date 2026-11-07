// SPDX-License-Identifier: LicenseRef-Sii-NC
// Copyright (c) 2026 wanwuwww
package com.sii.browser.core.dns

import com.sii.browser.di.AppServices
import java.net.InetAddress
import okhttp3.Dns

/**
 * 供应用自身 OkHttp 使用的 DNS：开启应用内 DoH 时先走 DoH，
 * 失败或本地域名再回退系统解析。DoH 客户端自己的引导连接不经过这里。
 */
object DohDns : Dns {

    override fun lookup(hostname: String): List<InetAddress> {
        val settings = runCatching { AppServices.settings }.getOrNull()
            ?: return Dns.SYSTEM.lookup(hostname)
        if (!settings.dohProxyEnabled()) return Dns.SYSTEM.lookup(hostname)
        if (isLocal(hostname)) return Dns.SYSTEM.lookup(hostname)
        val addresses = DohClient.resolve(hostname, settings.dohProvider())
        return addresses.ifEmpty { Dns.SYSTEM.lookup(hostname) }
    }

    private fun isLocal(host: String): Boolean {
        val value = host.lowercase()
        if (value == "localhost" || value.endsWith(".local")) return true
        if (!value.contains('.')) return true
        if (value.matches(Regex("\\d{1,3}(\\.\\d{1,3}){3}"))) {
            val parts = value.split('.').mapNotNull { it.toIntOrNull() }
            if (parts.size == 4) {
                return parts[0] == 10 ||
                    (parts[0] == 192 && parts[1] == 168) ||
                    (parts[0] == 172 && parts[1] in 16..31) ||
                    parts[0] == 127
            }
        }
        return false
    }
}

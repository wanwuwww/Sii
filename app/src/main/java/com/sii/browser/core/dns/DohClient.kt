// SPDX-License-Identifier: LicenseRef-Sii-NC
// Copyright (c) 2026 wanwuwww
package com.sii.browser.core.dns

import java.net.InetAddress
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import okhttp3.Dns
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject

/**
 * DNS over HTTPS 客户端：JSON 接口（阿里 / 腾讯 / Cloudflare / Google），
 * 只用于本地代理的域名解析，不改变系统 DNS。
 */
object DohClient {

    private data class Entry(val addresses: List<InetAddress>, val expiresAt: Long)

    /** DoH 服务商的固定 IP：避开系统 DNS 引导，防止域名本身被污染。 */
    private val bootstrap = mapOf(
        "dns.alidns.com" to listOf("223.5.5.5", "223.6.6.6"),
        "doh.pub" to listOf("1.12.12.12", "120.53.53.53"),
        "cloudflare-dns.com" to listOf("1.1.1.1", "1.0.0.1"),
        "dns.google" to listOf("8.8.8.8", "8.8.4.4")
    )

    private val client = OkHttpClient.Builder()
        .dns(object : Dns {
            override fun lookup(hostname: String): List<InetAddress> {
                val fixed = bootstrap[hostname]
                if (fixed != null) {
                    val addresses = fixed.mapNotNull { ip ->
                        runCatching { InetAddress.getByName(ip) }.getOrNull()
                    }
                    if (addresses.isNotEmpty()) return addresses
                }
                return Dns.SYSTEM.lookup(hostname)
            }
        })
        .connectTimeout(4, TimeUnit.SECONDS)
        .readTimeout(4, TimeUnit.SECONDS)
        .build()

    private val cache = ConcurrentHashMap<String, Entry>()

    fun resolve(host: String, provider: String): List<InetAddress> {
        if (isIpLiteral(host)) {
            return runCatching { listOf(InetAddress.getByName(host)) }.getOrDefault(emptyList())
        }
        val key = "$provider|$host"
        val now = System.currentTimeMillis()
        cache[key]?.takeIf { it.expiresAt > now }?.let { return it.addresses }
        // 首选服务商失败（如阿里对个别域名返回 SERVFAIL）时自动换下一家。
        val providers = listOf(provider, "tencent", "cloudflare", "google").distinct()
        var addresses: List<InetAddress> = emptyList()
        for (candidate in providers) {
            addresses = query(host, "A", candidate).ifEmpty { query(host, "AAAA", candidate) }
            if (addresses.isNotEmpty()) break
        }
        cache[key] = Entry(addresses, now + 60_000)
        return addresses
    }

    private fun isIpLiteral(host: String): Boolean =
        host.contains(':') || host.matches(Regex("\\d{1,3}(\\.\\d{1,3}){3}"))

    private fun endpoint(provider: String): String = when (provider) {
        "tencent" -> "https://doh.pub/dns-query?name=%s&type=%s"
        "cloudflare" -> "https://cloudflare-dns.com/dns-query?name=%s&type=%s"
        "google" -> "https://dns.google/resolve?name=%s&type=%s"
        else -> "https://dns.alidns.com/resolve?name=%s&type=%s"
    }

    private fun query(host: String, type: String, provider: String): List<InetAddress> {
        val url = String.format(Locale.US, endpoint(provider), host, type)
        val request = Request.Builder()
            .url(url)
            .header("Accept", "application/dns-json")
            .build()
        return runCatching {
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return emptyList()
                val body = response.body?.string().orEmpty()
                val json = JSONObject(body)
                if (json.optInt("Status", 0) != 0) return emptyList()
                val answers = json.optJSONArray("Answer") ?: return emptyList()
                val out = mutableListOf<InetAddress>()
                for (i in 0 until answers.length()) {
                    val item = answers.optJSONObject(i) ?: continue
                    val type = item.optInt("type", 0)
                    if (type != 1 && type != 28) continue
                    val data = item.optString("data")
                    if (!isIpLiteral(data)) continue
                    runCatching { InetAddress.getByName(data) }.getOrNull()?.let { out.add(it) }
                }
                out
            }
        }.getOrDefault(emptyList())
    }
}

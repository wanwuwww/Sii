// SPDX-License-Identifier: LicenseRef-Sii-NC
// Copyright (c) 2026 wanwuwww
package com.sii.browser.data

import android.net.Uri
import com.sii.browser.core.adblock.AdBlockEngine
import com.sii.browser.core.privacy.PrivacyPacks
import com.sii.browser.core.rules.BuiltinRules
import com.sii.browser.data.db.RuleDao
import com.sii.browser.data.db.RuleEntity
import com.sii.browser.data.db.RuleSummary
import com.sii.browser.data.db.RuleType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

/**
 * 规则仓库：规则持久化、订阅下载更新，并把全部规则装载进 [AdBlockEngine]。
 *
 * 订阅文本可能超过 SQLite CursorWindow 单行限制，因此列表只查摘要，
 * 正文通过 [RuleDao.contentChunk] 分块读取。
 */
class RuleRepository(
    private val dao: RuleDao,
    private val client: OkHttpClient = OkHttpClient()
) {

    /** 隐私规则包体积较大（EasyPrivacy 约 2MB），默认 10 秒读超时在慢网络下会被切断。 */
    private val packClient: OkHttpClient by lazy {
        client.newBuilder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .build()
    }

    fun all(): Flow<List<RuleSummary>> = dao.all()

    /** 保存自定义规则（多行文本，整体覆盖自定义条目）。 */
    suspend fun saveCustomRules(content: String) {
        val existing = dao.summariesByType(RuleType.CUSTOM).firstOrNull()
        val now = System.currentTimeMillis()
        if (existing == null) {
            dao.insert(
                RuleEntity(
                    type = RuleType.CUSTOM, title = "custom", content = content,
                    enabled = true, updatedAt = now
                )
            )
        } else {
            dao.updateContent(existing.id, content, now)
        }
    }

    suspend fun customRulesText(): String =
        dao.summariesByType(RuleType.CUSTOM).firstOrNull()?.let { contentOf(it.id) }.orEmpty()

    suspend fun addSubscription(url: String): Long {
        val now = System.currentTimeMillis()
        return dao.insert(
            RuleEntity(
                type = RuleType.SUBSCRIPTION, title = url, content = "",
                enabled = true, updatedAt = now
            )
        )
    }

    /** 手动重命名订阅；传空字符串可恢复自动名称。 */
    suspend fun renameSubscription(id: Long, name: String) {
        dao.updateName(id, name.trim())
    }

    suspend fun setEnabled(rule: RuleSummary, enabled: Boolean) {
        dao.setEnabled(rule.id, enabled)
    }

    suspend fun delete(id: Long) = dao.delete(id)

    suspend fun privacyPackSummaries(): List<RuleSummary> =
        dao.summariesByType(RuleType.PRIVACY)

    /**
     * 确保指定分类的隐私规则包存在并启用；缺内容时下载。
     * 返回 false 表示至少一个启用的规则包下载失败。
     */
    suspend fun ensurePrivacyPacks(categories: Set<String>): Boolean = withContext(Dispatchers.IO) {
        var ok = true
        val existing = dao.summariesByType(RuleType.PRIVACY)
        PrivacyPacks.ALL.forEach { pack ->
            val enabled = pack.category in categories
            val current = existing.firstOrNull { it.title == pack.category }
            val id = current?.id ?: dao.insert(
                RuleEntity(
                    type = RuleType.PRIVACY,
                    title = pack.category,
                    name = pack.label,
                    content = "",
                    enabled = enabled,
                    updatedAt = 0L
                )
            )
            if (current != null) dao.setEnabled(id, enabled)
            if (enabled && (current?.contentLength ?: 0) == 0) {
                var downloaded = downloadPrivacyPack(id, pack.url)
                if (!downloaded) {
                    delay(1200)
                    downloaded = downloadPrivacyPack(id, pack.url)
                }
                if (!downloaded) ok = false
            }
        }
        ok
    }

    /** 更新单个隐私规则包；不修改 title，title 是分类 id。 */
    suspend fun updatePrivacyPack(id: Long, category: String): Boolean {
        val pack = PrivacyPacks.byCategory(category) ?: return false
        return downloadPrivacyPack(id, pack.url)
    }

    private suspend fun downloadPrivacyPack(id: Long, url: String): Boolean =
        withContext(Dispatchers.IO) {
            try {
                val request = Request.Builder().url(url).build()
                packClient.newCall(request).execute().use { resp ->
                    if (!resp.isSuccessful) return@withContext false
                    val body = resp.body?.string().orEmpty()
                    if (body.isEmpty()) return@withContext false
                    dao.updateContent(id, body, System.currentTimeMillis())
                    dao.updateNameIfEmpty(id, PrivacyPacks.ALL.firstOrNull {
                        it.url == url
                    }?.label ?: url)
                    true
                }
            } catch (e: Exception) {
                false
            }
        }

    /** 读取规则正文（分块，避免单行超过 CursorWindow）。 */
    suspend fun contentOf(id: Long): String = readContent(id)

    private suspend fun readContent(id: Long): String {
        val out = StringBuilder()
        var offset = 0
        val chunkSize = 128 * 1024
        while (true) {
            val part = dao.contentChunk(id, offset, chunkSize) ?: break
            if (part.isEmpty()) break
            out.append(part)
            if (part.length < chunkSize) break
            offset += part.length
        }
        return out.toString()
    }

    /** 下载订阅源并更新本地规则文本；旧地址会迁移到当前可用地址。 */
    suspend fun updateSubscription(id: Long, url: String): Boolean = withContext(Dispatchers.IO) {
        try {
            val target = normalizedSubscriptionUrl(url)
            val request = Request.Builder().url(target).build()
            client.newCall(request).execute().use { resp ->
                if (!resp.isSuccessful) return@withContext false
                val body = resp.body?.string().orEmpty()
                if (body.isEmpty()) return@withContext false
                dao.updateContent(id, body, System.currentTimeMillis())
                if (target != url) dao.updateTitle(id, target)
                dao.updateNameIfEmpty(id, titleFromContent(body) ?: nameFromUrl(target))
                true
            }
        } catch (e: Exception) {
            false
        }
    }

    /** 优先读取 ABP 规则头里的 `! Title:`，用于自动命名订阅。 */
    private fun titleFromContent(body: String): String? {
        body.lineSequence().take(80).forEach { line ->
            val text = line.trim()
            val lower = text.lowercase()
            if (lower.startsWith("! title:")) return text.substring(8).trim().ifEmpty { null }
            if (lower.startsWith("!title:")) return text.substring(7).trim().ifEmpty { null }
            if (lower.startsWith("! name:")) return text.substring(7).trim().ifEmpty { null }
        }
        return null
    }

    private fun nameFromUrl(url: String): String {
        val path = runCatching { Uri.parse(url).path }.getOrNull().orEmpty().lowercase()
        return when {
            "filter_224_chinese" in path -> "AdGuard Chinese"
            "filter_2_base" in path -> "AdGuard Base"
            "filter_19_annoyances_popups" in path -> "AdGuard Popups"
            "filter_14_annoyances" in path -> "AdGuard Annoyances"
            "filter_11_mobile" in path -> "AdGuard Mobile Ads"
            "cjxlist" in path || "cjx-annoyance" in path -> "CJX's Annoyance List"
            "easylistchina" in path -> "EasyList China"
            "easyprivacy" in path -> "EasyPrivacy"
            "antiadblockfilters" in path -> "Anti-Adblock Filters"
            "easylist" in path -> "EasyList"
            else -> runCatching { Uri.parse(url).host }.getOrNull().orEmpty().ifEmpty { url }
        }
    }

    private fun normalizedSubscriptionUrl(url: String): String = when (url.trim()) {
        "https://easylist.to/easylistchina/easylistchina.txt" ->
            "https://easylist-downloads.adblockplus.org/easylistchina.txt"
        "https://easylist.to/easylistchina/cjx-annoyance.txt" ->
            "https://easylist-downloads.adblockplus.org/cjx-annoyance.txt"
        else -> url
    }

    /** 把内建 + 自定义 + 已启用订阅装载进拦截引擎。 */
    suspend fun loadIntoEngine(
        engine: AdBlockEngine,
        includeBuiltin: Boolean = com.sii.browser.di.AppServices.settings.builtinRulesEnabled()
    ) {
        engine.clearCustomRules()
        if (includeBuiltin) {
            BuiltinRules.RULES.forEach { engine.addCustomRule(it) }
        }
        dao.summariesByType(RuleType.CUSTOM).forEach { rule ->
            readContent(rule.id).lineSequence().forEach { engine.addCustomRule(it) }
        }
        val privacyLevel = runCatching {
            com.sii.browser.di.AppServices.settings.privacyLevel()
        }.getOrDefault(0)
        engine.privacyEnabled = privacyLevel > 0
        engine.clearPrivacyRules()
        if (privacyLevel > 0) {
            dao.summariesByType(RuleType.PRIVACY)
                .filter { it.enabled }
                .forEach { rule ->
                    val lines = readContent(rule.id).lineSequence()
                        .filter { it.isNotBlank() }
                        .toList()
                    if (lines.isNotEmpty()) engine.setPrivacyRules(rule.title, lines)
                }
        }
        val subscriptionLines = mutableListOf<String>()
        dao.summariesByType(RuleType.SUBSCRIPTION)
            .filter { it.enabled }
            .forEach { rule ->
                readContent(rule.id).lineSequence().forEach { line ->
                    if (line.isNotBlank()) subscriptionLines.add(line)
                }
            }
        if (subscriptionLines.isNotEmpty()) engine.setSubscriptionRules(subscriptionLines)
    }
}

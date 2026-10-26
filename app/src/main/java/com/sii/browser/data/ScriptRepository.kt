// SPDX-License-Identifier: LicenseRef-Sii-NC
// Copyright (c) 2026 wanwuwww
package com.sii.browser.data

import com.sii.browser.data.db.ScriptDao
import com.sii.browser.data.db.ScriptEntity
import com.sii.browser.script.UserScript
import kotlinx.coroutines.flow.Flow

/**
 * 用户脚本仓库。
 */
class ScriptRepository(private val dao: ScriptDao) {

    /** 已启用脚本及其解析结果缓存；脚本增删改时失效，避免每页重复解析全文。 */
    @Volatile
    private var cachedScripts: List<Pair<ScriptEntity, UserScript>>? = null

    private fun invalidate() {
        cachedScripts = null
    }

    private suspend fun enabledWithParsed(): List<Pair<ScriptEntity, UserScript>> {
        cachedScripts?.let { return it }
        val list = dao.enabledScripts().map { it to toUserScript(it) }
        cachedScripts = list
        return list
    }

    fun all(): Flow<List<ScriptEntity>> = dao.all()

    /** 添加脚本（按 metadata 解析 name/match/runAt；sourceUrl 用于后续更新）。 */
    suspend fun add(source: String, sourceUrl: String? = null): ScriptEntity {
        val parsed = UserScript.parse(source)
        val entity = ScriptEntity(
            name = parsed.name,
            author = parsed.author,
            url = sourceUrl ?: parsed.downloadUrl,
            match = parsed.matches.joinToString(","),
            exclude = parsed.excludes.joinToString(",").ifEmpty { null },
            runAt = parsed.runAt,
            code = parsed.code,
            enabled = true,
            updatedAt = System.currentTimeMillis()
        )
        val id = dao.insert(entity)
        invalidate()
        return entity.copy(id = id)
    }

    /** 新建/编辑脚本：名称、匹配和运行时机允许在界面上覆盖 metadata。 */
    suspend fun saveScript(
        existing: ScriptEntity?,
        name: String,
        match: String,
        runAt: String,
        code: String,
        sourceUrl: String? = null
    ): ScriptEntity {
        val parsed = UserScript.parse(code)
        val resolvedName = name.ifBlank { parsed.name }
        val resolvedMatch = match.ifBlank { parsed.matches.joinToString(",") }
        val resolvedRunAt = runAt.ifBlank { parsed.runAt }
        if (existing == null) {
            val entity = ScriptEntity(
                name = resolvedName,
                author = parsed.author,
                url = sourceUrl ?: parsed.downloadUrl,
                match = resolvedMatch,
                exclude = parsed.excludes.joinToString(",").ifEmpty { null },
                runAt = resolvedRunAt,
                code = parsed.code,
                enabled = true,
                updatedAt = System.currentTimeMillis()
            )
            val id = dao.insert(entity)
            invalidate()
            return entity.copy(id = id)
        }
        val updated = existing.copy(
            name = resolvedName,
            match = resolvedMatch,
            runAt = resolvedRunAt,
            code = code,
            url = sourceUrl ?: existing.url ?: parsed.downloadUrl,
            updatedAt = System.currentTimeMillis()
        )
        dao.update(updated)
        invalidate()
        return updated
    }

    /** 从安装来源重新抓取后替换脚本内容（名称/匹配/运行时机跟随新 metadata）。 */
    suspend fun updateFromSource(script: ScriptEntity, code: String) {
        val parsed = UserScript.parse(code)
        invalidate()
        dao.update(
            script.copy(
                name = parsed.name,
                author = parsed.author,
                url = script.url ?: parsed.downloadUrl,
                match = parsed.matches.joinToString(","),
                exclude = parsed.excludes.joinToString(",").ifEmpty { null },
                runAt = parsed.runAt,
                code = code,
                updatedAt = System.currentTimeMillis()
            )
        )
    }

    suspend fun setEnabled(script: ScriptEntity, enabled: Boolean) {
        dao.update(script.copy(enabled = enabled, updatedAt = System.currentTimeMillis()))
        invalidate()
    }

    suspend fun delete(id: Long) {
        dao.delete(id)
        invalidate()
    }

    suspend fun clearAll() {
        dao.clearAll()
        invalidate()
    }

    /** 当前 URL 匹配的已启用脚本（不区分运行时机）。 */
    suspend fun matchingScripts(url: String): List<ScriptEntity> =
        enabledWithParsed()
            .filter { (_, script) -> script.matchesUrl(url) }
            .map { it.first }

    /** 匹配当前 URL 需要注入的脚本（按运行时机过滤）。 */
    suspend fun scriptsFor(url: String, runAt: String): List<String> =
        enabledWithParsed()
            .filter { (entity, script) -> entity.runAt == runAt && script.matchesUrl(url) }
            .map { it.first.code }

    private fun toUserScript(entity: ScriptEntity): UserScript {
        val parsed = UserScript.parse(entity.code)
        return UserScript(
            name = entity.name,
            author = entity.author,
            downloadUrl = entity.url,
            matches = entity.match.split(",").filter { it.isNotEmpty() },
            excludes = entity.exclude?.split(",")?.filter { it.isNotEmpty() } ?: emptyList(),
            runAt = entity.runAt,
            requires = parsed.requires,
            grants = parsed.grants,
            code = entity.code
        )
    }
}

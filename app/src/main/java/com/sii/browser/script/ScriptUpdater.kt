// SPDX-License-Identifier: LicenseRef-Sii-NC
// Copyright (c) 2026 wanwuwww
package com.sii.browser.script

import android.content.Context
import com.sii.browser.di.AppServices
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request

/** 用户脚本自动更新：按 @downloadURL/@updateURL 拉取，内容变化时覆盖本地脚本。 */
object ScriptUpdater {
    private val client = OkHttpClient()

    suspend fun check(): Int = withContext(Dispatchers.IO) {
        val repository = AppServices.scriptRepository
        val scripts = repository.all().first()
        var updated = 0
        scripts.forEach { script ->
            val url = script.url?.takeIf { it.startsWith("http") } ?: return@forEach
            val code = runCatching {
                val request = Request.Builder().url(url).build()
                client.newCall(request).execute().use { response ->
                    if (response.isSuccessful) response.body?.string() else null
                }
            }.getOrNull() ?: return@forEach
            if (code.isNotBlank() && code != script.code) {
                runCatching { repository.updateFromSource(script, code) }
                    .onSuccess { updated++ }
            }
        }
        updated
    }

    fun maybeAutoCheck(context: Context) {
        val settings = AppServices.settings
        val days = settings.scriptUpdateDays()
        if (days <= 0) return
        val last = settings.scriptLastUpdate()
        if (System.currentTimeMillis() - last < days * 24L * 60L * 60L * 1000L) return
        settings.setScriptLastUpdate(System.currentTimeMillis())
        kotlinx.coroutines.CoroutineScope(
            kotlinx.coroutines.SupervisorJob() + Dispatchers.IO
        ).launch { check() }
    }
}

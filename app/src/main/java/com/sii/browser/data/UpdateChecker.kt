package com.sii.browser.data

import com.sii.browser.di.AppServices
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

/**
 * 更新检查（预留接口）。
 *
 * 检查地址在「设置 → 高级 → 更新检查地址」中配置（空 = 不检查），
 * 返回 JSON 约定：
 * ```json
 * { "versionCode": 12, "versionName": "1.2.0", "url": "https://.../sii.apk", "notes": "更新说明" }
 * ```
 * 地址为空、网络失败或格式不对时返回 null，不向用户报错刷屏。
 */
object UpdateChecker {

    data class UpdateInfo(
        val versionCode: Long,
        val versionName: String,
        val url: String,
        val notes: String?
    )

    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()

    suspend fun check(): UpdateInfo? = withContext(Dispatchers.IO) {
        val endpoint = runCatching { AppServices.settings.updateCheckUrl() }.getOrNull()
            ?.takeIf { it.isNotBlank() }
            ?: return@withContext null
        runCatching {
            val request = Request.Builder().url(endpoint).build()
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return@withContext null
                val json = org.json.JSONObject(response.body?.string().orEmpty())
                val code = json.optLong("versionCode", 0)
                if (code <= 0) return@withContext null
                UpdateInfo(
                    versionCode = code,
                    versionName = json.optString("versionName"),
                    url = json.optString("url"),
                    notes = json.optString("notes").ifBlank { null }
                )
            }
        }.getOrNull()
    }
}

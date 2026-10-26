package com.sii.browser.data

import com.sii.browser.di.AppServices
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * 更新检查。
 *
 * 数据源：设置里的自定义地址（JSON）优先，未配置时读 GitHub Releases。
 * 自定义 JSON 约定 `{ versionCode, versionName, url, notes }`；
 * GitHub 解析 `releases/latest` 的 tag、正文与 apk 资产。
 * 任何失败都返回 null，不向用户报错。
 */
object UpdateChecker {

    data class UpdateInfo(
        val versionCode: Long,
        val versionName: String,
        val url: String,
        val notes: String?
    )

    private const val GITHUB_LATEST =
        "https://api.github.com/repos/wanwuwww/Sii/releases/latest"

    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()

    suspend fun check(): UpdateInfo? = withContext(Dispatchers.IO) {
        val endpoint = runCatching { AppServices.settings.updateCheckUrl() }.getOrNull()
        if (!endpoint.isNullOrBlank()) checkJson(endpoint.trim()) else checkGitHub()
    }

    private fun checkJson(endpoint: String): UpdateInfo? = runCatching {
        val request = Request.Builder().url(endpoint).build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) return null
            val json = JSONObject(response.body?.string().orEmpty())
            val code = json.optLong("versionCode", 0)
            val name = json.optString("versionName")
            if (code <= 0 && name.isBlank()) return null
            UpdateInfo(
                versionCode = code,
                versionName = name,
                url = json.optString("url"),
                notes = json.optString("notes").ifBlank { null }
            )
        }
    }.getOrNull()

    private fun checkGitHub(): UpdateInfo? = runCatching {
        val request = Request.Builder()
            .url(GITHUB_LATEST)
            .header("Accept", "application/vnd.github+json")
            .header("User-Agent", "Sii-Browser")
            .build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) return null
            val json = JSONObject(response.body?.string().orEmpty())
            val tag = json.optString("tag_name")
                .trim().removePrefix("v").removePrefix("V")
            if (tag.isBlank()) return null
            val assets = json.optJSONArray("assets")
            var apk: String? = null
            if (assets != null) {
                for (i in 0 until assets.length()) {
                    val item = assets.optJSONObject(i) ?: continue
                    if (item.optString("name").endsWith(".apk", ignoreCase = true)) {
                        apk = item.optString("browser_download_url")
                        break
                    }
                }
            }
            UpdateInfo(
                versionCode = 0L,
                versionName = tag,
                url = apk ?: json.optString("html_url"),
                notes = json.optString("body").ifBlank { null }
            )
        }
    }.getOrNull()

    /** 语义版本比较：candidate 是否比 current 新，缺失段按 0 处理。 */
    fun isNewer(candidate: String, current: String): Boolean {
        fun parts(value: String): List<Int> = value.trim()
            .removePrefix("v").removePrefix("V")
            .split(".")
            .map { segment -> segment.takeWhile { it.isDigit() }.toIntOrNull() ?: 0 }
        val old = parts(current)
        val new = parts(candidate)
        for (i in 0 until maxOf(old.size, new.size)) {
            val x = old.getOrElse(i) { 0 }
            val y = new.getOrElse(i) { 0 }
            if (x != y) return y > x
        }
        return false
    }

    /** 自定义源带 versionCode 时以它为准，GitHub 源按版本名比较。 */
    fun isUpdate(info: UpdateInfo, localVersionName: String, localVersionCode: Long): Boolean =
        if (info.versionCode > 0) info.versionCode > localVersionCode
        else isNewer(info.versionName, localVersionName)
}

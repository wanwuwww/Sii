// SPDX-License-Identifier: LicenseRef-Sii-NC
// Copyright (c) 2026 wanwuwww
package com.sii.browser.core.webview

import android.content.Context
import com.sii.browser.di.AppServices
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder

/**
 * 云翻译：内置免费接口（MyMemory / LibreTranslate / Google 非官方）+ 自定义 URL 模板。
 * 接口在设置中选择，文本会发送到对应服务商。
 */
object Translator {

    data class Result(val text: String?, val error: String? = null)

    private val client = OkHttpClient.Builder().build()

    /** 目标语言：用户设置优先，否则跟随界面语言。 */
    fun targetLang(context: Context): String {
        val saved = AppServices.settings.translateTarget()
        if (saved.isNotBlank()) return saved
        return if (context.resources.configuration.locales[0].language == "zh") "zh-CN" else "en"
    }

    suspend fun translate(
        context: Context,
        text: String,
        to: String = targetLang(context),
        from: String? = null
    ): Result =
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            val mode = AppServices.settings.translateMode()
            val result = when (mode) {
                0 -> Result(null, "translation_disabled")
                2 -> OfflineTranslator.translate(context, text, to, from)
                else -> runCatching {
                    when (AppServices.settings.translateProvider()) {
                        1 -> libreTranslate(text, to)
                        2 -> google(text, to)
                        3 -> custom(text, to)
                        4 -> baidu(text, to)
                        else -> myMemory(text, to)
                    }
                }.getOrElse { Result(null, it.message ?: "error") }
            }
            result
        }

    private fun myMemory(text: String, to: String): Result {
        val from = if (to.startsWith("zh")) "en" else "zh-CN"
        val url = "https://api.mymemory.translated.net/get?q=" + enc(text) +
            "&langpair=" + enc("$from|$to")
        val body = get(url) ?: return Result(null, "network")
        val json = JSONObject(body)
        val translated = json.optJSONObject("responseData")?.optString("translatedText")
        return if (!translated.isNullOrBlank()) Result(translated)
        else Result(null, json.optString("responseDetails"))
    }

    private fun libreTranslate(text: String, to: String): Result {
        val base = AppServices.settings.translateEndpoint()
            .ifBlank { "https://translate.terraprint.co" }
            .trimEnd('/')
        val lang = if (to.startsWith("zh")) "zh" else to.substringBefore('-')
        val url = "$base/translate?q=" + enc(text) +
            "&source=auto&target=" + enc(lang) + "&format=text"
        val body = get(url) ?: return Result(null, "network")
        val translated = JSONObject(body).optString("translatedText")
        return if (translated.isNotBlank()) Result(translated) else Result(null, "empty")
    }

    private fun google(text: String, to: String): Result {
        val url = "https://translate.googleapis.com/translate_a/single?client=gtx&sl=auto&tl=" +
            enc(to) + "&dt=t&q=" + enc(text)
        val body = get(url) ?: return Result(null, "network")
        val segments = JSONArray(body).optJSONArray(0) ?: return Result(null, "empty")
        val sb = StringBuilder()
        for (i in 0 until segments.length()) {
            val segment = segments.optJSONArray(i) ?: continue
            sb.append(segment.optString(0))
        }
        return if (sb.isNotEmpty()) Result(sb.toString()) else Result(null, "empty")
    }

    private fun custom(text: String, to: String): Result {
        val template = AppServices.settings.translateEndpoint()
        if (template.isBlank()) return Result(null, "no endpoint")
        val from = "auto"
        fun fill(source: String): String = source
            .replace("{text}", enc(text))
            .replace("{from}", enc(from))
            .replace("{to}", enc(to))
        val url = fill(template)
        val method = AppServices.settings.translateCustomMethod()
        val headers = AppServices.settings.translateCustomHeaders()
        val bodyTemplate = AppServices.settings.translateCustomBody()
        val body = if (method == 1 && bodyTemplate.isNotBlank()) fill(bodyTemplate) else null
        val response = request(method, url, headers, body) ?: return Result(null, "network")
        val path = AppServices.settings.translateCustomPath().ifBlank { "translatedText" }
        val translated = extractPath(response, path) ?: response.trim()
        return if (translated.isNotBlank()) Result(translated) else Result(null, "empty")
    }

    /** 百度翻译：MD5(appid + q + salt + 密钥) 签名。 */
    private fun baidu(text: String, to: String): Result {
        val appid = AppServices.settings.translateBaiduAppId()
        val key = AppServices.settings.translateBaiduKey()
        if (appid.isBlank() || key.isBlank()) return Result(null, "no key")
        val salt = (System.currentTimeMillis() % 1000000).toString()
        val sign = md5(appid + text + salt + key)
        val lang = if (to.startsWith("zh")) "zh" else to.substringBefore('-')
        val url = "https://fanyi-api.baidu.com/api/trans/vip/translate"
        val form = "q=" + enc(text) + "&from=auto&to=" + enc(lang) +
            "&appid=" + enc(appid) + "&salt=" + salt + "&sign=" + sign
        val body = postForm(url, form) ?: return Result(null, "network")
        val json = JSONObject(body)
        val result = json.optJSONArray("trans_result")?.optJSONObject(0)?.optString("dst")
        return if (!result.isNullOrBlank()) Result(result)
        else Result(null, json.optString("error_msg").ifBlank { "empty" })
    }

    private fun request(method: Int, url: String, headers: String, body: String?): String? =
        runCatching {
            val builder = Request.Builder()
                .url(url)
                .header(
                    "User-Agent",
                    "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 " +
                        "(KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36"
                )
            headers.lineSequence().forEach { line ->
                val idx = line.indexOf(':')
                if (idx > 0) {
                    val name = line.substring(0, idx).trim()
                    val value = line.substring(idx + 1).trim()
                    if (name.isNotEmpty()) builder.header(name, value)
                }
            }
            if (method == 1) {
                val media = if (body != null && body.trimStart().startsWith("{")) {
                    "application/json; charset=utf-8"
                } else {
                    "application/x-www-form-urlencoded; charset=utf-8"
                }
                builder.post((body ?: "").toRequestBody(media.toMediaTypeOrNull()))
            }
            client.newCall(builder.build()).execute().use { response ->
                if (!response.isSuccessful) return null
                response.body?.string()
            }
        }.getOrNull()

    private fun postForm(url: String, form: String): String? = runCatching {
        val request = Request.Builder()
            .url(url)
            .header(
                "User-Agent",
                "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 " +
                    "(KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36"
            )
            .post(form.toRequestBody("application/x-www-form-urlencoded; charset=utf-8".toMediaTypeOrNull()))
            .build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) null else response.body?.string()
        }
    }.getOrNull()

    /** 简单 JSON 路径：a.b[0].c，找不到返回 null。 */
    private fun extractPath(json: String, path: String): String? {
        var current: Any? = runCatching { org.json.JSONTokener(json).nextValue() }.getOrNull()
            ?: return null
        for (part in path.split('.')) {
            var name = part
            var index: Int? = null
            val match = Regex("(.+)\\[(\\d+)]").matchEntire(part)
            if (match != null) {
                name = match.groupValues[1]
                index = match.groupValues[2].toIntOrNull()
            }
            current = when (val value = current) {
                is JSONObject -> value.opt(name)
                is JSONArray -> name.toIntOrNull()?.let { value.opt(it) }
                else -> return null
            }
            if (index != null) {
                current = (current as? JSONArray)?.opt(index)
            }
            if (current == null || current == JSONObject.NULL) return null
        }
        return when (val value = current) {
            is String -> value
            null -> null
            else -> value.toString()
        }
    }

    private fun md5(input: String): String {
        val bytes = java.security.MessageDigest.getInstance("MD5")
            .digest(input.toByteArray(Charsets.UTF_8))
        return bytes.joinToString("") { "%02x".format(it.toInt() and 0xFF) }
    }

    private fun get(url: String): String? = runCatching {
        val request = Request.Builder()
            .url(url)
            .header(
                "User-Agent",
                "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 " +
                    "(KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36"
            )
            .build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) return null
            response.body?.string()
        }
    }.getOrNull()

    private fun enc(value: String): String = URLEncoder.encode(value, "UTF-8")
}

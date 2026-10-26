// SPDX-License-Identifier: LicenseRef-Sii-NC
// Copyright (c) 2026 wanwuwww
package com.sii.browser.script

import android.content.Context
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import okhttp3.OkHttpClient
import okhttp3.Request

/** @require 依赖缓存：首次注入时后台下载，之后优先使用本地缓存。 */
object ScriptRequireCache {
    private val client = OkHttpClient()
    private val downloading = ConcurrentHashMap<String, Boolean>()

    private fun dir(context: Context): File =
        File(context.filesDir, "script_requires").apply { mkdirs() }

    private fun file(context: Context, url: String): File {
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(url.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it.toInt() and 0xFF) }
        return File(dir(context), "$digest.js")
    }

    fun cached(context: Context, url: String): String? = runCatching {
        val file = file(context, url)
        if (file.exists() && file.length() > 0) file.readText() else null
    }.getOrNull()

    fun ensure(context: Context, url: String) {
        if (cached(context, url) != null) return
        if (downloading.putIfAbsent(url, true) != null) return
        Thread {
            runCatching {
                val request = Request.Builder().url(url).build()
                client.newCall(request).execute().use { response ->
                    if (response.isSuccessful) {
                        response.body?.string()?.let { body ->
                            if (body.isNotBlank()) file(context, url).writeText(body)
                        }
                    }
                }
            }
            downloading.remove(url)
        }.start()
    }
}

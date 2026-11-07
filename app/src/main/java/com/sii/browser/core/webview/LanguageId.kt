// SPDX-License-Identifier: LicenseRef-Sii-NC
// Copyright (c) 2026 wanwuwww
package com.sii.browser.core.webview

import android.content.Context
import com.google.android.gms.tasks.Task
import com.google.mlkit.nl.languageid.LanguageIdentification
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * 设备端语言识别：只负责把页面样本识别成 BCP-47 语言码，
 * 与具体翻译引擎解耦。
 */
object LanguageId {

    private val client by lazy { LanguageIdentification.getClient() }

    /** 识别样本语言；样本为空、无法识别（und）或调用失败时返回 null。 */
    suspend fun identify(context: Context, sample: String): String? =
        withContext(kotlinx.coroutines.Dispatchers.IO) {
            val text = sample.trim()
            if (text.isEmpty()) return@withContext null
            runCatching { client.identifyLanguage(text.take(200)).awaitResult() }
                .getOrNull()
                ?.takeIf { it.isNotBlank() && it != "und" }
        }

    private suspend fun <T> Task<T>.awaitResult(): T = suspendCancellableCoroutine { cont ->
        addOnSuccessListener { value -> if (cont.isActive) cont.resume(value) }
        addOnFailureListener { error -> if (cont.isActive) cont.resumeWithException(error) }
    }
}

// SPDX-License-Identifier: LicenseRef-Sii-NC
// Copyright (c) 2026 wanwuwww
package com.sii.browser.download

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import com.sii.browser.core.webview.OfflineTranslator
import com.sii.browser.core.webview.TranslationTrace
import com.sii.browser.data.db.DownloadTaskFlags
import com.sii.browser.di.AppServices
import kotlinx.coroutines.flow.first
import java.io.File

/** 无痕退出清理：只处理带 INCOGNITO 标记的任务，避免误删普通下载。 */
object IncognitoCleanup {

    suspend fun cleanDownloads(context: Context, sessionStart: Long): Int {
        val tasks = AppServices.downloadRepository.allTasks().first()
            .filter {
                it.flags and DownloadTaskFlags.INCOGNITO != 0 &&
                    (sessionStart <= 0L || it.createdAt >= sessionStart)
            }
        var deleted = 0
        tasks.forEach { task ->
            runCatching { AppServices.downloadEngine.cancelAndWait(task.id) }
            if (deleteFile(context, task.path, task.name)) deleted++
            runCatching { AppServices.downloadRepository.deleteTask(task.id) }
        }
        return deleted
    }

    fun deleteFile(context: Context, path: String?, name: String): Boolean {
        if (path != null && path.startsWith("content://")) {
            val uri = runCatching { Uri.parse(path) }.getOrNull() ?: return false
            val deleted = runCatching {
                DocumentFile.fromSingleUri(context, uri)?.delete() == true
            }.getOrDefault(false)
            if (deleted) return true
            return runCatching { context.contentResolver.delete(uri, null, null) > 0 }
                .getOrDefault(false)
        }
        val file = path?.takeIf { it.startsWith("/") }?.let(::File)
            ?: context.getExternalFilesDir(android.os.Environment.DIRECTORY_DOWNLOADS)
                ?.let { File(it, name) }
        if (file != null && isAppPrivateFile(context, file.absolutePath)) {
            runCatching { File(file.absolutePath + ".part").delete() }
            return runCatching { file.delete() }.getOrDefault(false)
        }
        return false
    }

    fun cleanTranslation(context: Context) {
        OfflineTranslator.clearCache()
        TranslationTrace.clear(context)
    }

    private fun isAppPrivateFile(context: Context, path: String): Boolean {
        val dirs = listOfNotNull(
            context.filesDir,
            context.cacheDir,
            context.getExternalFilesDir(null),
            context.getExternalCacheDir()
        )
        return dirs.any { path.startsWith(it.path + File.separator) }
    }
}

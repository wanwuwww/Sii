// SPDX-License-Identifier: LicenseRef-Sii-NC
// Copyright (c) 2026 wanwuwww
package com.sii.browser.download

import android.content.Context
import com.sii.browser.data.db.DownloadState
import com.sii.browser.data.db.DownloadTask
import com.sii.browser.di.AppServices
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * 下载完成后把 APK 重命名为「应用名_版本号.apk」。
 *
 * 只在本地文件上处理：SAF 目录的 content:// 地址无法交给 PackageManager 解析。
 * 任何一步失败都静默保留原文件名。
 */
object ApkRename {

    suspend fun rename(context: Context, task: DownloadTask): DownloadTask? =
        withContext(Dispatchers.IO) {
            if (!AppServices.settings.apkRenameToLabel()) return@withContext null
            if (task.state != DownloadState.COMPLETED) return@withContext null
            val path = task.path ?: return@withContext null
            if (path.startsWith("content://")) return@withContext null
            val isApk = task.name.endsWith(".apk", ignoreCase = true) ||
                task.mimetype.orEmpty().contains("android.package")
            if (!isApk) return@withContext null
            val file = File(path)
            if (!file.isFile) return@withContext null
            val info = runCatching {
                context.packageManager.getPackageArchiveInfo(path, 0)
            }.getOrNull() ?: return@withContext null
            val app = info.applicationInfo ?: return@withContext null
            app.sourceDir = path
            val label = runCatching {
                context.packageManager.getApplicationLabel(app).toString()
            }.getOrNull()?.trim().orEmpty()
            if (label.isBlank()) return@withContext null
            val version = info.versionName?.trim().orEmpty().ifBlank {
                val code = if (android.os.Build.VERSION.SDK_INT >= 28) {
                    info.longVersionCode
                } else {
                    @Suppress("DEPRECATION")
                    info.versionCode.toLong()
                }
                if (code > 0) code.toString() else ""
            }
            val base = (if (version.isBlank()) label else "${label}_$version")
                .replace(Regex("""[\\/:*?"<>|]"""), "_")
                .trim()
                .take(90)
            if (base.isBlank()) return@withContext null
            val dir = file.parentFile ?: return@withContext null
            var candidate = "$base.apk"
            var index = 1
            while (File(dir, candidate).exists()) {
                candidate = "$base ($index).apk"
                index++
                if (index > 99) return@withContext null
            }
            if (candidate == task.name) return@withContext null
            val dest = File(dir, candidate)
            if (!file.renameTo(dest)) return@withContext null
            task.copy(name = candidate, path = dest.absolutePath)
        }
}

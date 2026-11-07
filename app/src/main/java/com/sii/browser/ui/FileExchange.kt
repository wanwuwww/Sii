// SPDX-License-Identifier: LicenseRef-Sii-NC
// Copyright (c) 2026 wanwuwww
package com.sii.browser.ui

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import java.io.File

/**
 * 导入/导出的文件通道：
 * - 导出：Android 10+ 经 MediaStore 写入公共 Download 目录（免存储权限），旧版本落应用沙箱；
 * - 导入：由调用方用 SAF（OpenDocument）取 URI，这里只负责读文本。
 */
object FileExchange {

    /** 把文本写入 Download（返回展示用路径，失败返回 null）。 */
    fun writeToDownloads(context: Context, fileName: String, content: String): String? =
        writeBytesToDownloads(context, fileName, "application/json", content.toByteArray(Charsets.UTF_8))

    /** 把任意字节写入 Download（用于 zip 备份）。 */
    fun writeBytesToDownloads(
        context: Context,
        fileName: String,
        mime: String,
        bytes: ByteArray
    ): String? = runCatching {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val values = ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, fileName)
                put(MediaStore.Downloads.MIME_TYPE, mime)
                put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
            }
            val resolver = context.contentResolver
            val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                ?: return@runCatching null
            resolver.openOutputStream(uri)?.use { it.write(bytes) }
            "${Environment.DIRECTORY_DOWNLOADS}/$fileName"
        } else {
            val dir = context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS) ?: context.filesDir
            if (!dir.exists()) dir.mkdirs()
            File(dir, fileName).apply { writeBytes(bytes) }.absolutePath
        }
    }.getOrNull()

    /** 读取 SAF 选中的文件文本。 */
    fun readText(context: Context, uri: Uri): String? =
        readBytes(context, uri)?.toString(Charsets.UTF_8)

    /** 读取 SAF 选中的文件字节（zip 备份用）。 */
    fun readBytes(context: Context, uri: Uri): ByteArray? = runCatching {
        context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
    }.getOrNull()
}

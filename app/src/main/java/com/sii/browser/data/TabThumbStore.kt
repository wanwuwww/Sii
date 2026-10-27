// SPDX-License-Identifier: LicenseRef-Sii-NC
// Copyright (c) 2026 wanwuwww
package com.sii.browser.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import java.io.File
import java.security.MessageDigest

/**
 * 标签缩略图磁盘缓存。
 *
 * 内存紧张不会清它，只有关闭标签或从最近任务划掉时才删除对应文件，
 * 因此系统回收进程后重新打开的标签仍能看到上次的缩略图。
 */
object TabThumbStore {

    private const val DIR = "tab_thumbs"
    private const val MAX_WIDTH = 360
    private const val QUALITY = 85

    fun save(context: Context, url: String, bitmap: Bitmap) {
        runCatching {
            val target = file(context, url)
            target.parentFile?.mkdirs()
            val scaled = scale(bitmap)
            target.outputStream().use {
                scaled.compress(Bitmap.CompressFormat.JPEG, QUALITY, it)
            }
            if (scaled !== bitmap) scaled.recycle()
        }
    }

    fun load(context: Context, url: String): Bitmap? = runCatching {
        val target = file(context, url)
        if (!target.exists()) return null
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(target.absolutePath, bounds)
        var sample = 1
        while (bounds.outWidth / (sample * 2) >= MAX_WIDTH) sample *= 2
        BitmapFactory.decodeFile(
            target.absolutePath,
            BitmapFactory.Options().apply { inSampleSize = sample }
        )
    }.getOrNull()

    fun delete(context: Context, url: String) {
        if (url.isBlank()) return
        runCatching { file(context, url).delete() }
    }

    fun clear(context: Context) {
        runCatching { File(context.filesDir, DIR).deleteRecursively() }
    }

    private fun scale(source: Bitmap): Bitmap {
        if (source.width <= MAX_WIDTH) return source
        val height = (source.height * MAX_WIDTH / source.width).coerceAtLeast(1)
        return Bitmap.createScaledBitmap(source, MAX_WIDTH, height, true)
    }

    private fun file(context: Context, url: String): File {
        val digest = MessageDigest.getInstance("SHA-1").digest(url.toByteArray())
        val name = digest.joinToString("") { "%02x".format(it.toInt() and 0xff) } + ".jpg"
        return File(File(context.filesDir, DIR), name)
    }
}

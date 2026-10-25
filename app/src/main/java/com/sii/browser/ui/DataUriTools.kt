package com.sii.browser.ui

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.util.Base64
import java.io.File
import java.net.URLDecoder

/** 解析和保存 data:image/... 内联图片。 */
object DataUriTools {
    fun isDataImage(uri: String): Boolean =
        uri.startsWith("data:image/", ignoreCase = true)

    fun bitmap(uri: String): Bitmap? = runCatching {
        val data = bytes(uri) ?: return null
        BitmapFactory.decodeByteArray(data, 0, data.size)
    }.getOrNull()

    fun saveTemp(context: Context, uri: String): java.io.File? = runCatching {
        val data = bytes(uri) ?: return null
        val dir = java.io.File(context.cacheDir, "share").apply { mkdirs() }
        val file = java.io.File(dir, "sii_share_${System.currentTimeMillis()}.png")
        file.writeBytes(data)
        file
    }.getOrNull()

    fun saveImage(context: Context, uri: String): Boolean = runCatching {
        val data = bytes(uri) ?: return false
        val mime = mime(uri)
        val ext = when {
            mime.contains("webp") -> "webp"
            mime.contains("jpeg") || mime.contains("jpg") -> "jpg"
            mime.contains("gif") -> "gif"
            else -> "png"
        }
        val name = "Sii_${System.currentTimeMillis()}.$ext"
        if (Build.VERSION.SDK_INT >= 29) {
            val values = ContentValues().apply {
                put(MediaStore.Images.Media.DISPLAY_NAME, name)
                put(MediaStore.Images.Media.MIME_TYPE, mime)
                put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/Sii")
            }
            val target = context.contentResolver.insert(
                MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values
            ) ?: return false
            context.contentResolver.openOutputStream(target)?.use { it.write(data) } ?: return false
        } else {
            val dir = File(
                context.getExternalFilesDir(Environment.DIRECTORY_PICTURES), "Sii"
            ).apply { mkdirs() }
            File(dir, name).writeBytes(data)
        }
        true
    }.getOrDefault(false)

    private fun mime(uri: String): String {
        val meta = uri.substringAfter("data:", "").substringBefore(',')
        return meta.substringBefore(';').ifBlank { "image/png" }
    }

    private fun bytes(uri: String): ByteArray? = runCatching {
        val comma = uri.indexOf(',')
        if (comma < 0) return null
        val meta = uri.substring(5, comma)
        val payload = uri.substring(comma + 1)
        if (meta.contains(";base64", ignoreCase = true)) {
            Base64.decode(payload, Base64.DEFAULT)
        } else {
            URLDecoder.decode(payload, "UTF-8").toByteArray(Charsets.UTF_8)
        }
    }.getOrNull()
}

// SPDX-License-Identifier: LicenseRef-Sii-NC
// Copyright (c) 2026 wanwuwww
package com.sii.browser.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.view.Gravity
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import com.google.zxing.BarcodeFormat
import com.google.zxing.qrcode.QRCodeWriter
import com.sii.browser.R

/** 把链接或文本生成二维码，显示并支持复制/保存。 */
object QrCodeDialog {
    fun show(context: Context, text: String) {
        if (text.isBlank()) return
        val bitmap = runCatching {
            val size = 720
            val matrix = QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, size, size)
            Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888).apply {
                for (x in 0 until size) {
                    for (y in 0 until size) {
                        setPixel(x, y, if (matrix[x, y]) Color.BLACK else Color.WHITE)
                    }
                }
            }
        }.getOrNull()
        if (bitmap == null) {
            Toast.makeText(context, R.string.scan_generate_failed, Toast.LENGTH_SHORT).show()
            return
        }
        val content = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(context, 20), dp(context, 12), dp(context, 20), dp(context, 4))
            addView(ImageView(context).apply {
                setImageBitmap(bitmap)
                adjustViewBounds = true
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply { bottomMargin = dp(context, 10) }
            })
            addView(TextView(context).apply {
                this.text = text
                setTextSize(12f)
                setTextColor(context.getColor(R.color.text_secondary))
                maxLines = 3
                ellipsize = android.text.TextUtils.TruncateAt.END
            })
        }
        val dialog = android.app.AlertDialog.Builder(context)
            .setTitle(R.string.scan_generate)
            .setView(content)
            .setPositiveButton("保存", null)
            .setNeutralButton("复制", null)
            .setNegativeButton(android.R.string.cancel, null)
            .create()
        dialog.setOnShowListener {
            dialog.getButton(android.app.AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                runCatching { save(context, bitmap) }
                    .onSuccess {
                        Toast.makeText(context, R.string.scan_generate_saved, Toast.LENGTH_SHORT).show()
                    }
                    .onFailure {
                        Toast.makeText(context, R.string.scan_generate_failed, Toast.LENGTH_SHORT).show()
                    }
            }
            dialog.getButton(android.app.AlertDialog.BUTTON_NEUTRAL).setOnClickListener {
                val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                clipboard.setPrimaryClip(ClipData.newPlainText("url", text))
                Toast.makeText(context, R.string.link_copied, Toast.LENGTH_SHORT).show()
            }
        }
        dialog.show()
    }

    private fun save(context: Context, bitmap: Bitmap) {
        val name = "Sii_QR_${System.currentTimeMillis()}.png"
        if (Build.VERSION.SDK_INT >= 29) {
            val values = ContentValues().apply {
                put(MediaStore.Images.Media.DISPLAY_NAME, name)
                put(MediaStore.Images.Media.MIME_TYPE, "image/png")
                put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/Sii")
            }
            val uri = context.contentResolver.insert(
                MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values
            ) ?: error("insert failed")
            context.contentResolver.openOutputStream(uri)?.use {
                bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)
            } ?: error("open failed")
        } else {
            val dir = java.io.File(
                context.getExternalFilesDir(Environment.DIRECTORY_PICTURES), "Sii"
            ).apply { mkdirs() }
            java.io.File(dir, name).outputStream().use {
                bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)
            }
        }
    }

    private fun dp(context: Context, value: Int): Int =
        (value * context.resources.displayMetrics.density).toInt()
}

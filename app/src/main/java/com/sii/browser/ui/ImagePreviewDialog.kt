package com.sii.browser.ui

import android.app.Dialog
import android.content.Context
import android.graphics.BitmapFactory
import android.graphics.Color
import android.view.Gravity
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.ImageView
import android.widget.Toast
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** 全屏看图模式：黑底、自适应显示、点击关闭。 */
object ImagePreviewDialog {
    fun show(context: Context, url: String) {
        val dialog = Dialog(context, android.R.style.Theme_Black_NoTitleBar_Fullscreen)
        val image = ImageView(context).apply {
            setBackgroundColor(Color.BLACK)
            scaleType = ImageView.ScaleType.FIT_CENTER
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
            setOnClickListener { dialog.dismiss() }
        }
        dialog.setContentView(image)
        dialog.window?.setLayout(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT
        )
        dialog.show()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
        scope.launch {
            val bitmap = withContext(Dispatchers.IO) {
                runCatching {
                    if (DataUriTools.isDataImage(url)) {
                        DataUriTools.bitmap(url)
                    } else {
                        val client = okhttp3.OkHttpClient()
                        val request = okhttp3.Request.Builder().url(url).build()
                        client.newCall(request).execute().use { response ->
                            response.body?.byteStream()?.let { BitmapFactory.decodeStream(it) }
                        }
                    }
                }.getOrNull()
            }
            if (bitmap != null && dialog.isShowing) {
                image.setImageBitmap(bitmap)
            } else {
                Toast.makeText(context, "图片加载失败", Toast.LENGTH_SHORT).show()
                dialog.dismiss()
            }
        }
    }
}

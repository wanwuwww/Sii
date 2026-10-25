package com.sii.browser.core.webview

import android.content.Context
import android.util.Log
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** 本地翻译诊断日志：同时写 logcat 和 filesDir/translation_trace.log。 */
object TranslationTrace {

    private const val TAG = "SiiBergamot"
    private const val FILE_NAME = "translation_trace.log"

    fun log(context: Context, message: String) {
        Log.d(TAG, message)
        runCatching {
            val file = File(context.filesDir, FILE_NAME)
            if (file.length() > 256 * 1024) file.delete()
            val time = SimpleDateFormat("HH:mm:ss.SSS", Locale.US).format(Date())
            file.appendText("$time $message\n")
        }
    }

    /** 读取日志正文（最新的在后），供设置页展示/导出。 */
    fun read(context: Context): String = runCatching {
        val file = File(context.filesDir, FILE_NAME)
        if (!file.isFile) "" else file.readText().takeLast(64 * 1024)
    }.getOrDefault("")

    fun clear(context: Context) {
        runCatching { File(context.filesDir, FILE_NAME).delete() }
    }
}

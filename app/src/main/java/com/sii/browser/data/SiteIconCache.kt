package com.sii.browser.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.LruCache
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File

/**
 * 站点图标缓存：按域名抓取 favicon 并落盘，失败由调用方回退首字母。
 * 只在书签选择「原网图」时访问网络，且每域名同一时间只请求一次。
 */
object SiteIconCache {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val client = OkHttpClient()
    private val memory = object : LruCache<String, Bitmap>(8 * 1024) {
        override fun sizeOf(key: String, value: Bitmap): Int =
            (value.byteCount / 1024).coerceAtLeast(1)
    }

    /** 系统内存紧张时释放图标位图。 */
    fun trim() {
        memory.evictAll()
    }
    private val pending = HashSet<String>()

    fun cached(context: Context, host: String): Bitmap? {
        memory.get(host)?.let { return it }
        val file = fileFor(context, host)
        if (!file.exists()) return null
        val bitmap = runCatching { BitmapFactory.decodeFile(file.absolutePath) }.getOrNull()
        if (bitmap != null) memory.put(host, bitmap)
        return bitmap
    }

    fun load(context: Context, host: String, onReady: (Bitmap?) -> Unit) {
        cached(context, host)?.let {
            onReady(it)
            return
        }
        synchronized(pending) {
            if (!pending.add(host)) return
        }
        scope.launch {
            val bitmap = runCatching { fetch(context, host) }.getOrNull()
            synchronized(pending) { pending.remove(host) }
            onReady(bitmap)
        }
    }

    private fun fetch(context: Context, host: String): Bitmap? {
        if (!host.matches(Regex("[a-zA-Z0-9.-]+"))) return null
        val request = Request.Builder()
            .url("https://$host/favicon.ico")
            .header("User-Agent", "Mozilla/5.0 (Linux; Android 10) AppleWebKit/537.36 Chrome/120.0.0.0")
            .build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) return null
            val body = response.body ?: return null
            if (body.contentLength() > MAX_BYTES) return null
            val bytes = body.bytes()
            if (bytes.size > MAX_BYTES) return null
            val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: return null
            memory.put(host, bitmap)
            runCatching { fileFor(context, host).writeBytes(bytes) }
            return bitmap
        }
    }

    private fun fileFor(context: Context, host: String): File {
        val dir = File(context.filesDir, "site_icons").apply { mkdirs() }
        return File(dir, Integer.toHexString(host.hashCode()))
    }

    private const val MAX_BYTES = 512 * 1024
}

package com.sii.browser.core.webview

import android.content.Context
import com.google.android.gms.tasks.Task
import com.google.mlkit.common.model.DownloadConditions
import com.google.mlkit.common.model.RemoteModelManager
import com.google.mlkit.nl.translate.TranslateLanguage
import com.google.mlkit.nl.translate.TranslateRemoteModel
import com.google.mlkit.nl.translate.Translation
import com.google.mlkit.nl.translate.TranslatorOptions
import com.sii.browser.di.AppServices
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * ML Kit 设备端翻译：只支持中英，语言包手动下载，不自动联网。
 */
object OfflineTranslator {

    private val manager = RemoteModelManager.getInstance()
    private val clients = mutableMapOf<String, com.google.mlkit.nl.translate.Translator>()

    /** 译文缓存（原文→译文）：页面重建节点后重翻零开销，避免重复调 ML Kit。 */
    private val cache = object : LinkedHashMap<String, String>(256, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, String>) = size > 2000
    }

    /** 模型就绪状态缓存：isModelDownloaded 是跨进程调用，逐 chunk 查询会明显拖慢翻译。 */
    @Volatile
    private var readyCache: Boolean? = null

    @Volatile
    var downloading = false
        private set

    @Volatile
    var progressPercent = 0
        private set

    @Volatile
    var progressIndex = 0
        private set

    @Volatile
    var progressCount = 2
        private set

    @Synchronized
    fun clearCache() {
        cache.clear()
    }

    fun targetLanguage(context: Context): String? {
        val saved = AppServices.settings.translateTarget()
        val tag = saved.ifBlank {
            if (context.resources.configuration.locales[0].language == "zh") "zh" else "en"
        }
        return when {
            tag.startsWith("zh") -> TranslateLanguage.CHINESE
            tag.startsWith("en") -> TranslateLanguage.ENGLISH
            else -> null
        }
    }

    private fun mapTarget(tag: String): String? = when {
        tag.startsWith("zh") -> TranslateLanguage.CHINESE
        tag.startsWith("en") -> TranslateLanguage.ENGLISH
        else -> null
    }

    private fun mapSource(tag: String): String? = when {
        tag.startsWith("zh") -> TranslateLanguage.CHINESE
        tag.startsWith("en") -> TranslateLanguage.ENGLISH
        else -> null
    }

    private fun models(): List<TranslateRemoteModel> = listOf(
        TranslateRemoteModel.Builder(TranslateLanguage.CHINESE).build(),
        TranslateRemoteModel.Builder(TranslateLanguage.ENGLISH).build()
    )

    private suspend fun <T> Task<T>.awaitResult(): T = suspendCancellableCoroutine { cont ->
        addOnSuccessListener { value -> if (cont.isActive) cont.resume(value) }
        addOnFailureListener { error -> if (cont.isActive) cont.resumeWithException(error) }
    }

    /** 本地模型状态查询；必须在协程/IO 中调用，不能阻塞主线程。 */
    suspend fun isReady(context: Context): Boolean = withContext(kotlinx.coroutines.Dispatchers.IO) {
        readyCache?.let { return@withContext it }
        val ready = models().all { model ->
            runCatching { manager.isModelDownloaded(model).awaitResult() }.getOrDefault(false)
        }
        readyCache = ready
        ready
    }

    suspend fun downloadModels(
        context: Context,
        onProgress: (percent: Int, index: Int, count: Int, read: Long, total: Long) -> Unit = { _, _, _, _, _ -> }
    ): Boolean = withContext(kotlinx.coroutines.Dispatchers.IO) {
        if (downloading) return@withContext false
        downloading = true
        progressPercent = 0
        progressIndex = 0
        progressCount = models().size
        try {
            val conditions = DownloadConditions.Builder().build()
            val list = models()
            for ((index, model) in list.withIndex()) {
                progressIndex = index + 1
                onProgress(
                    (index * 100) / list.size,
                    index + 1,
                    list.size,
                    0L,
                    0L
                )
                manager.download(model, conditions).awaitResult()
            }
            progressPercent = 100
            onProgress(100, list.size, list.size, 0L, 0L)
            readyCache = true
            synchronized(clients) {
                clients.values.forEach { runCatching { it.close() } }
                clients.clear()
            }
            true
        } catch (e: Exception) {
            TranslationTrace.log(context, "ML Kit download failed: ${e.message}")
            false
        } finally {
            downloading = false
        }
    }

    suspend fun deleteModels(context: Context): Boolean = withContext(kotlinx.coroutines.Dispatchers.IO) {
        var ok = true
        for (model in models()) {
            runCatching { manager.deleteDownloadedModel(model).awaitResult() }
                .onFailure { ok = false }
        }
        synchronized(clients) {
            clients.values.forEach { runCatching { it.close() } }
            clients.clear()
        }
        progressPercent = 0
        synchronized(cache) { cache.clear() }
        readyCache = null
        ok
    }

    suspend fun translate(
        context: Context,
        text: String,
        to: String,
        from: String? = null
    ): Translator.Result {
        val target = mapTarget(to) ?: targetLanguage(context)
            ?: return Translator.Result(null, "offline_unsupported")
        val source = mapSource(from.orEmpty())
            ?: return Translator.Result(null, "offline_source_unknown")
        if (source == target) return Translator.Result(text)
        synchronized(cache) { cache[text] }?.let { return Translator.Result(it) }
        if (!isReady(context)) return Translator.Result(null, "offline_model_missing")
        val key = "$source-$target"
        val client = synchronized(clients) {
            clients.getOrPut(key) {
                Translation.getClient(
                    TranslatorOptions.Builder()
                        .setSourceLanguage(source)
                        .setTargetLanguage(target)
                        .build()
                )
            }
        }
        return runCatching { client.translate(text).awaitResult() }
            .fold(
                onSuccess = {
                    synchronized(cache) { cache[text] = it }
                    Translator.Result(it)
                },
                onFailure = { Translator.Result(null, it.message ?: "offline_error") }
            )
    }

    /** 逐条并发翻译（带 LRU 缓存）。不合并文本，避免分隔符被模型改坏导致错位。 */
    suspend fun translateBatch(
        context: Context,
        texts: List<String>,
        to: String,
        from: String,
        onProgress: ((done: Int, total: Int) -> Unit)? = null
    ): List<String?> {
        if (texts.isEmpty()) return emptyList()
        val startedAt = System.currentTimeMillis()
        val gate = Semaphore(6)
        val done = java.util.concurrent.atomic.AtomicInteger(0)
        val results = coroutineScope {
            texts.map { text ->
                async {
                    gate.withPermit { translate(context, text.take(1000), to, from).text }.also {
                        onProgress?.invoke(done.incrementAndGet(), texts.size)
                    }
                }
            }.awaitAll()
        }
        TranslationTrace.log(
            context,
            "mlkit parallel count=${texts.size} ms=${System.currentTimeMillis() - startedAt}"
        )
        return results
    }

    /** ML Kit 没有 HTML 对齐接口，这里按分隔符合并成批翻译。 */
    suspend fun translateHtmlBatch(
        context: Context,
        texts: List<String>,
        to: String,
        from: String
    ): List<String?> =
        translateBatch(context, texts, to, from)
}

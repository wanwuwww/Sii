package com.sii.browser.download

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Environment
import com.sii.browser.data.db.DownloadChunk
import com.sii.browser.data.db.DownloadState
import com.sii.browser.data.db.DownloadTask
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.Callback
import okhttp3.Response
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.util.concurrent.TimeUnit

/**
 * 分块下载引擎。
 *
 * 核心能力：Range 并行分块（网络自适应段数）、每块进度持久化（断点续传）、
 * 暂停/恢复、失败状态。下载目录策略（沙箱/SAF）由上层决定。
 */
class DownloadEngine(
    private val context: Context,
    private val repository: DownloadRepository,
    private val client: OkHttpClient = defaultClient()
) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val jobs = java.util.concurrent.ConcurrentHashMap<Long, Job>()

    /** 每个任务最后一次用户意图：true 运行，false 暂停；用于快速连点时以最后一次为准。 */
    private val desiredRunning = java.util.concurrent.ConcurrentHashMap<Long, Boolean>()
    private val lastProgressAt = java.util.concurrent.ConcurrentHashMap<Long, Long>()

    /** 任务级互斥：同一任务的启动、暂停、清空串行执行，避免多个下载协程并存。 */
    private val locks = java.util.concurrent.ConcurrentHashMap<Long, Mutex>()

    private fun lockFor(taskId: Long): Mutex = locks.computeIfAbsent(taskId) { Mutex() }

    /** 任务进度回调（服务层用于通知栏更新）。 */
    var onTaskProgress: ((taskId: Long, downloaded: Long, total: Long) -> Unit)? = null

    /** 任务完成回调（服务层用于自动打开文件/空闲停止）。 */
    var onTaskCompleted: ((task: DownloadTask) -> Unit)? = null

    private val attempts = java.util.concurrent.ConcurrentHashMap<Long, Int>()

    private val etags = java.util.concurrent.ConcurrentHashMap<String, String>()

    companion object {
        private const val DEFAULT_BUFFER_SIZE = 256 * 1024
        private const val PROGRESS_FLUSH_BYTES = 1024L * 1024
        private const val MAX_RETRY = 3
        private const val RETRY_DELAY_MS = 3000L
        private const val BROWSER_UA =
            "Mozilla/5.0 (Linux; Android 13; Mobile) AppleWebKit/537.36 " +
                "(KHTML, like Gecko) Chrome/120.0 Mobile Safari/537.36"

        fun defaultClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .connectionPool(okhttp3.ConnectionPool(8, 5, TimeUnit.MINUTES))
            .build()

        /** 网络类型自适应段数：Wi-Fi 4 段，移动网络 2 段，未知 1 段。 */
        fun chunkCountForNetwork(context: Context): Int {
            val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            val network = cm?.activeNetwork ?: return 1
            val caps = cm.getNetworkCapabilities(network) ?: return 1
            return when {
                caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> 4
                caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> 2
                else -> 1
            }
        }

        /** 混合策略：网络建议值与文件大小建议值取小，避免小文件过度并发。 */
        fun chunkCountForFile(context: Context, total: Long): Int {
            if (total <= 0L) return 1
            val byNetwork = chunkCountForNetwork(context)
            val bySize = ((total + 1024L * 1024L - 1) / (1024L * 1024L))
                .coerceIn(1L, 4L)
                .toInt()
            return minOf(byNetwork, bySize).coerceAtLeast(1)
        }
    }

    /** 探测服务器是否真正支持 Range；不支持则走单流下载，避免分块写坏文件。 */
    suspend fun supportsRange(url: String): Boolean = withContext(Dispatchers.IO) {
        runCatching {
            val request = requestBuilder(url).header("Range", "bytes=0-0").build()
            client.newCall(request).execute().use { response ->
                response.code == 206 ||
                    response.header("Accept-Ranges")?.contains("bytes", true) == true
            }
        }.getOrDefault(false)
    }

    /** 探测资源总大小（HEAD；不支持时退回 Range 探测）。 */
    suspend fun probeSize(url: String): Long = withContext(Dispatchers.IO) {
        val head = requestBuilder(url).head().build()
        client.newCall(head).execute().use { resp ->
            resp.header("ETag")?.takeIf { it.isNotBlank() }?.let { etags[url] = it }
            resp.header("Content-Length")?.toLongOrNull()?.let {
                if (it > 0L) return@withContext it
            }
        }
        val range = requestBuilder(url).header("Range", "bytes=0-0").build()
        client.newCall(range).execute().use { resp ->
            resp.header("Content-Range")?.let { cr ->
                val slash = cr.lastIndexOf('/')
                if (slash >= 0) cr.substring(slash + 1).toLongOrNull()?.let { return@withContext it }
            }
            0L
        }
    }

    fun start(taskId: Long) {
        desiredRunning[taskId] = true
        scope.launch {
            lockFor(taskId).withLock {
                if (desiredRunning[taskId] != true) {
                    return@withLock
                }
                val old = jobs.remove(taskId)
                old?.cancelAndJoin()
                if (desiredRunning[taskId] != true) {
                    return@withLock
                }
                repository.setState(taskId, DownloadState.DOWNLOADING)
                jobs[taskId] = scope.launch { run(taskId) }
            }
        }
    }

    fun pause(taskId: Long) {
        desiredRunning[taskId] = false
        scope.launch { cancelAndWait(taskId) }
    }

    /** 先标记暂停，再取消并等待任务结束；清空列表也走这里。 */
    suspend fun cancelAndWait(taskId: Long) {
        desiredRunning[taskId] = false
        lockFor(taskId).withLock {
            repository.setState(taskId, DownloadState.PAUSED)
            val job = jobs.remove(taskId)
            job?.cancelAndJoin()
            val current = repository.taskById(taskId)
            if (current != null && current.state != DownloadState.COMPLETED) {
                repository.setState(taskId, DownloadState.PAUSED)
            }
        }
    }

    private suspend fun run(taskId: Long) {
        if (desiredRunning[taskId] == false) return
        val task = repository.taskById(taskId) ?: return
        val path = resolvePath(task)
        if (task.path != path) repository.updatePath(taskId, path)
        val resolved = task.copy(path = path)
        repository.setState(taskId, DownloadState.DOWNLOADING)
        try {
            if (resolved.totalSize <= 0L) {
                downloadStream(taskId, resolved)
                return
            }
            val chunks = repository.chunksOf(taskId).sortedBy { it.start }
            if (chunks.isEmpty()) {
                repository.markFailed(taskId, "no chunks")
                repository.taskById(taskId)?.let { onTaskCompleted?.invoke(it) }
                return
            }
            coroutineScope {
                // 首块立即开始，其余分块错峰建连；避免同时握手抢占带宽，起步更快。
                val jobs = chunks.mapIndexed { index, chunk ->
                    async(start = CoroutineStart.LAZY) {
                        if (index > 0) kotlinx.coroutines.delay(index * 250L)
                        downloadChunk(resolved, chunk)
                    }
                }
                jobs.forEach { it.start() }
                jobs.awaitAll()
            }
            val downloaded = repository.sumDownloaded(taskId)
            repository.updateProgress(taskId, downloaded)
            onTaskProgress?.invoke(taskId, downloaded, task.totalSize)
            if (downloaded >= task.totalSize) {
                if (repository.taskById(taskId)?.state == DownloadState.PAUSED) return
                val expected = etags[task.url]
                if (expected != null && !verifyEtag(task.url, expected)) {
                    File(resolved.path).delete()
                    repository.markFailed(taskId, "ETag changed")
                    etags.remove(task.url)
                    repository.taskById(taskId)?.let { onTaskCompleted?.invoke(it) }
                    return
                }
                repository.setState(taskId, DownloadState.COMPLETED)
                val completed = repository.taskById(taskId)
                if (completed != null) {
                    val moved = copyToSafIfConfigured(completed)
                    val finalTask = moved?.let { repository.taskById(taskId) } ?: completed
                    attempts.remove(taskId)
                    onTaskCompleted?.invoke(finalTask)
                }
            }
        } catch (e: CancellationException) {
            return
        } catch (e: Exception) {
            if (!currentCoroutineContext().isActive) return
            if (currentCoroutineContext()[Job]?.isCancelled == true) return
            if (repository.taskById(taskId)?.state == DownloadState.PAUSED) return
            val retried = attempts.getOrDefault(taskId, 0) < MAX_RETRY &&
                runCatching { com.sii.browser.di.AppServices.settings.downloadRetry() }.getOrDefault(false)
            if (retried) {
                attempts[taskId] = attempts.getOrDefault(taskId, 0) + 1
                kotlinx.coroutines.delay(RETRY_DELAY_MS)
                if (desiredRunning[taskId] == true) {
                    start(taskId)
                }
            } else {
                repository.markFailed(taskId, e.message)
                attempts.remove(taskId)
                repository.taskById(taskId)?.let { onTaskCompleted?.invoke(it) }
            }
        }
    }

    /** 下载完成后复核 ETag，防止服务器在分块期间更换了资源。 */
    private suspend fun verifyEtag(url: String, expected: String): Boolean =
        withContext(Dispatchers.IO) {
            runCatching {
                val request = Request.Builder().url(url).head().build()
                client.newCall(request).execute().use { resp ->
                    val current = resp.header("ETag") ?: return@withContext true
                    current == expected
                }
            }.getOrDefault(true)
        }

    /** 已配置 SAF 下载目录时，完成后把文件移入该目录并删掉沙箱副本。 */
    private suspend fun copyToSafIfConfigured(task: DownloadTask): String? = withContext(Dispatchers.IO) {
        val localPath = task.path ?: return@withContext null
        if (localPath.startsWith("content://")) return@withContext null
        val tree = runCatching { com.sii.browser.di.AppServices.settings.downloadDir() }.getOrNull()
            ?: return@withContext null
        runCatching {
            val treeUri = android.net.Uri.parse(tree)
            val dir = androidx.documentfile.provider.DocumentFile.fromTreeUri(context, treeUri)
                ?: return@runCatching null
            dir.findFile(task.name)?.delete()
            val out = dir.createFile("application/octet-stream", task.name) ?: return@runCatching null
            context.contentResolver.openOutputStream(out.uri)?.use { os ->
                File(localPath).inputStream().use { it.copyTo(os) }
            }
            File(localPath).delete()
            repository.updateTask(
                task.copy(path = out.uri.toString(), updatedAt = System.currentTimeMillis())
            )
            out.uri.toString()
        }.getOrNull()
    }

    /** 下载目录策略：默认应用沙箱，已指定路径（如 SAF）时沿用。 */
    private fun resolvePath(task: DownloadTask): String {
        task.path?.let { return it }
        val dir = context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS)
            ?: java.io.File(context.filesDir, "Download").apply { mkdirs() }
        return File(dir, task.name).absolutePath
    }

    private fun requestBuilder(url: String): Request.Builder {
        val builder = Request.Builder()
            .url(url)
            .header("User-Agent", BROWSER_UA)
            .header("Referer", url)
        runCatching {
            android.webkit.CookieManager.getInstance().getCookie(url)
                ?.takeIf { it.isNotBlank() }
                ?.let { builder.header("Cookie", it) }
        }
        return builder
    }

    private suspend fun awaitResponse(call: Call): Response =
        suspendCancellableCoroutine { cont ->
            cont.invokeOnCancellation { call.cancel() }
            call.enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    if (cont.isCancelled) return
                    if (call.isCanceled()) cont.cancel() else cont.resumeWithException(e)
                }

                override fun onResponse(call: Call, response: Response) {
                    if (cont.isCancelled) response.close()
                    else if (call.isCanceled()) {
                        response.close()
                        cont.cancel()
                    } else cont.resume(response)
                }
            })
        }

    /** 服务器不支持大小探测/Range 时，用单流下载到 EOF；完成后补总大小。 */
    private suspend fun downloadStream(taskId: Long, task: DownloadTask) {
        val path = task.path ?: throw IOException("empty path")
        val call = client.newCall(requestBuilder(task.url).build())
        val resp = awaitResponse(call)
        resp.use { response ->
            if (!response.isSuccessful) throw IOException("HTTP ${response.code}")
            val contentType = response.header("Content-Type").orEmpty()
            if (task.name.endsWith(".apk", true) && contentType.contains("text/html", true)) {
                throw IOException("server returned html instead of apk")
            }
            val body = response.body ?: throw IOException("empty body")
            RandomAccessFile(path, "rw").use { raf ->
                raf.setLength(0)
                val input = body.byteStream()
                val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                var downloaded = 0L
                var lastFlush = 0L
                while (true) {
                    currentCoroutineContext().ensureActive()
                    val read = input.read(buffer)
                    if (read < 0) break
                    raf.write(buffer, 0, read)
                    downloaded += read
                    if (downloaded - lastFlush >= 256 * 1024L) {
                        repository.updateProgress(taskId, downloaded)
                        onTaskProgress?.invoke(taskId, downloaded, 0L)
                        lastFlush = downloaded
                    }
                }
                if (downloaded <= 0L) throw IOException("empty body")
                repository.updateProgress(taskId, downloaded)
                repository.updateTotalSize(taskId, downloaded)
            }
        }
        if (repository.taskById(taskId)?.state == DownloadState.PAUSED) return
        repository.setState(taskId, DownloadState.COMPLETED)
        val completed = repository.taskById(taskId) ?: return
        val moved = copyToSafIfConfigured(completed)
        val finalTask = moved?.let { repository.taskById(taskId) } ?: completed
        attempts.remove(taskId)
        onTaskCompleted?.invoke(finalTask)
    }

    private suspend fun downloadChunk(task: DownloadTask, chunk: DownloadChunk) {
        if (chunk.downloaded >= chunk.length) return
        val start = chunk.start + chunk.downloaded
        val end = chunk.start + chunk.length - 1
        val request = requestBuilder(task.url)
            .header("Range", "bytes=$start-$end")
            .build()
        val call = client.newCall(request)
        val resp = awaitResponse(call)
        resp.use { response ->
            if (response.code != 206) {
                throw IOException("HTTP ${response.code} (range unsupported)")
            }
            val contentRange = response.header("Content-Range")
            if (contentRange != null) {
                val rangeStart = contentRange.substringAfter("bytes ", "")
                    .substringBefore('-').toLongOrNull()
                if (rangeStart != null && rangeStart != start) {
                    throw IOException("range mismatch")
                }
            }
            val body = response.body ?: throw IOException("empty body")
            val file = RandomAccessFile(task.path, "rw")
            file.use { raf ->
                raf.seek(start)
                val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                var downloaded = chunk.downloaded
                var lastFlush = downloaded
                val input = body.byteStream()
                while (true) {
                    currentCoroutineContext().ensureActive()
                    val read = input.read(buffer)
                    if (read < 0) break
                    raf.write(buffer, 0, read)
                    downloaded += read
                    if (downloaded - lastFlush >= PROGRESS_FLUSH_BYTES) {
                        repository.updateChunkProgress(chunk.id, downloaded)
                        publishTaskProgress(task.id)
                        lastFlush = downloaded
                    }
                }
                repository.updateChunkProgress(chunk.id, downloaded)
                publishTaskProgress(task.id)
            }
        }
    }

    /** 汇总各分块进度写回任务，驱动下载页与通知实时刷新。 */
    private suspend fun publishTaskProgress(taskId: Long) {
        val now = android.os.SystemClock.elapsedRealtime()
        val last = lastProgressAt[taskId] ?: 0L
        if (now - last < 500) return
        lastProgressAt[taskId] = now
        val total = repository.sumDownloaded(taskId)
        val current = repository.taskById(taskId) ?: return
        if (current.state == DownloadState.PAUSED || current.state == DownloadState.COMPLETED) {
            return
        }
        if (current.downloadedSize != total) {
            repository.updateProgress(taskId, total)
        }
        onTaskProgress?.invoke(taskId, total, current.totalSize)
    }

    fun shutdown() {
        scope.cancel()
    }
}

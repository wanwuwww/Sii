// SPDX-License-Identifier: LicenseRef-Sii-NC
// Copyright (c) 2026 wanwuwww
package com.sii.browser.download

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import com.sii.browser.R
import com.sii.browser.data.db.DownloadState
import com.sii.browser.data.db.DownloadTaskFlags
import com.sii.browser.di.AppServices
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * 下载前台服务（Sii 自有实现）。
 *
 * 职责：前台通知（进度/暂停/完成）、动作分发（下载/暂停/恢复/重下/全部）、
 * 启动时恢复未完成任务。targetSdk 34+ 以 dataSync 类型前台运行。
 */
class DownloadService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var activeTaskId: Long = -1
    /** 当前任务的分段数，仅用于通知栏提示。 */
    private var activeChunks = 0
    private var hlsActive = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createChannel()
        AppServices.downloadEngine.onTaskProgress = { taskId, downloaded, total ->
            updateNotification(taskId, downloaded, total)
        }
        AppServices.downloadEngine.onTaskCompleted = { task ->
            if (task.state == DownloadState.COMPLETED && AppServices.settings.downloadAutoOpen()) {
                openFile(task)
            }
            maybeStopIdle()
        }
        startForeground(NOTIFICATION_ID, buildNotification(-1, 0L, 0L))
        restorePendingTasks()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_DOWNLOAD -> handleDownload(intent)
            ACTION_DOWNLOAD_HLS -> handleDownloadHls(intent)
            ACTION_RESUME -> handleResume(intent)
            ACTION_PAUSE -> handlePause(intent)
            ACTION_REDOWNLOAD -> handleRedownload(intent)
            ACTION_PAUSE_ALL -> scope.launch { pauseAll() }
            ACTION_RESUME_ALL -> scope.launch { resumeAll() }
            ACTION_REFRESH -> scope.launch { refreshFromTasks() }
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        AppServices.downloadEngine.onTaskProgress = null
        AppServices.downloadEngine.onTaskCompleted = null
        scope.cancel()
        super.onDestroy()
    }

    /** 无进行中任务且无 HLS 下载时自动停止前台服务（空闲回收）。 */
    private fun maybeStopIdle() {
        scope.launch {
            val anyActive = AppServices.downloadRepository.allTasks().first()
                .any { it.state == DownloadState.DOWNLOADING || it.state == DownloadState.PENDING }
            if (anyActive || hlsActive) return@launch
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
    }

    /** 任务删除/清空后刷新通知：还有活动任务就更新，否则停止服务。 */
    private suspend fun refreshFromTasks() {
        val active = AppServices.downloadRepository.allTasks().first().firstOrNull {
            it.state == DownloadState.DOWNLOADING || it.state == DownloadState.PENDING
        }
        if (active == null) {
            maybeStopIdle()
        } else {
            updateNotification(active.id, active.downloadedSize, active.totalSize)
        }
    }

    /** 用系统应用打开已下载的文件（FileProvider 共享，支持 SAF content URI）。 */
    private fun openFile(task: com.sii.browser.data.db.DownloadTask) {
        val path = task.path ?: return
        runCatching {
            val uri = if (path.startsWith("content://")) {
                android.net.Uri.parse(path)
            } else {
                androidx.core.content.FileProvider.getUriForFile(
                    this, "$packageName.fileprovider", java.io.File(path)
                )
            }
            val ext = task.name.substringAfterLast('.', "").lowercase()
            val mime = android.webkit.MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext) ?: "*/*"
            val intent = Intent(Intent.ACTION_VIEW)
                .setDataAndType(uri, mime)
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
            startActivity(intent)
        }
    }

    // ---- 动作 ----

    private fun handleDownload(intent: Intent) {
        val url = intent.getStringExtra(EXTRA_URL) ?: return
        if (blockedByNetwork()) return
        val name = intent.getStringExtra(EXTRA_NAME) ?: url.substringAfterLast('/').ifEmpty { "download" }
        scope.launch {
            try {
            val existing = AppServices.downloadRepository.taskByUrl(url)
            if (existing != null && existing.state != DownloadState.FAILED) {
                kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                    android.widget.Toast.makeText(
                        this@DownloadService,
                        R.string.download_already_exists,
                        android.widget.Toast.LENGTH_SHORT
                    ).show()
                }
                maybeStopIdle()
                return@launch
            }
            if (existing != null) AppServices.downloadRepository.deleteTask(existing.id)
            val probe = runCatching {
                AppServices.downloadEngine.probe(url)
            }.getOrDefault(DownloadEngine.Probe(0L, false))
            val total = if (probe.total > 0L && probe.rangeSupported) probe.total else 0L
            val task = AppServices.downloadRepository.createTask(
                url = url,
                name = name,
                mimetype = intent.getStringExtra(EXTRA_MIME),
                totalSize = total,
                chunkCount = if (total > 0) {
                    DownloadEngine.chunkCountForFile(this@DownloadService, total)
                } else 1,
                flags = if (intent.getBooleanExtra(EXTRA_INCOGNITO, false) ||
                    AppServices.privacyManager.isIncognito
                ) DownloadTaskFlags.INCOGNITO else 0
            )
            activeTaskId = task.id
            activeChunks = task.chunks
            AppServices.downloadEngine.start(task.id)
            } catch (e: Exception) {
                maybeStopIdle()
            }
        }
    }

    private fun handleDownloadHls(intent: Intent) {
        val url = intent.getStringExtra(EXTRA_URL) ?: return
        if (blockedByNetwork()) return
        val name = intent.getStringExtra(EXTRA_NAME)
            ?: url.substringAfterLast('/').substringBefore('?').ifEmpty { "video.ts" }
        val referer = intent.getStringExtra(EXTRA_REFERER)
        val ua = intent.getStringExtra(EXTRA_UA)
        scope.launch {
            hlsActive = true
            var taskId = -1L
            var partialFile: java.io.File? = null
            try {
                val dir = getExternalFilesDir(android.os.Environment.DIRECTORY_DOWNLOADS)
                    ?: java.io.File(filesDir, "Download").apply { mkdirs() }
                val safeName = name.replace(Regex("""[\\/:*?"<>|]"""), "_")
                    .trim().take(120).ifBlank { "video" }
                val fileName = if (safeName.endsWith(".ts")) safeName else "$safeName.ts"
                val file = java.io.File(dir, fileName)
                partialFile = file
                val task = AppServices.downloadRepository.createTask(
                    url = url,
                    name = fileName,
                    mimetype = "video/mp2t",
                    totalSize = 0L,
                    chunkCount = 1,
                    flags = if (AppServices.privacyManager.isIncognito) {
                        DownloadTaskFlags.INCOGNITO
                    } else 0
                )
                taskId = task.id
                AppServices.downloadRepository.updatePath(taskId, file.absolutePath)
                AppServices.downloadRepository.setState(taskId, DownloadState.DOWNLOADING)
                activeTaskId = taskId
                val ok = HlsDownloader.download(url, referer, ua, file) { progress ->
                    val percent = if (progress.total > 0) progress.done * 100 / progress.total else 0
                    notifyHls(percent, progress.done, progress.total)
                }
                if (ok && file.length() > 0L) {
                    val mp4 = java.io.File(file.parentFile, file.nameWithoutExtension + ".mp4")
                    val wantMp4 = AppServices.settings.hlsToMp4()
                    if (wantMp4) notifyHlsConverting()
                    val converted = wantMp4 && TsToMp4.convert(file, mp4)
                    val finalFile = if (converted) mp4 else file
                    if (converted) file.delete()
                    val finalMime = if (converted) "video/mp4" else "video/mp2t"
                    AppServices.downloadRepository.updateFileInfo(
                        taskId,
                        finalFile.name,
                        finalMime,
                        finalFile.absolutePath,
                        finalFile.length()
                    )
                    AppServices.downloadRepository.setState(taskId, DownloadState.COMPLETED)
                    val finalTask = AppServices.downloadRepository.taskById(taskId)
                    notifyHlsDone(finalFile.name)
                    if (AppServices.settings.downloadAutoOpen() && finalTask != null) {
                        openFile(finalTask)
                    }
                } else {
                    file.delete()
                    AppServices.downloadRepository.markFailed(taskId, "HLS download failed")
                    notifyHlsFailed()
                }
            } catch (e: Exception) {
                partialFile?.delete()
                if (taskId > 0L) {
                    AppServices.downloadRepository.markFailed(taskId, e.message)
                }
                notifyHlsFailed()
            } finally {
                hlsActive = false
                maybeStopIdle()
            }
        }
    }

    /** 仅 Wi-Fi 下载：移动网络下拒绝新建任务并提示。 */
    private fun blockedByNetwork(): Boolean {
        if (!DownloadDispatcher.isWifiOnlyBlocked(this)) return false
        DownloadDispatcher.showWifiOnlyToast(this)
        maybeStopIdle()
        return true
    }

    private fun handleResume(intent: Intent) {        val id = intent.getLongExtra(EXTRA_TASK_ID, -1L)
        if (id <= 0) return
        if (blockedByNetwork()) return
        activeTaskId = id
        scope.launch {
            AppServices.downloadRepository.taskById(id)?.let { activeChunks = it.chunks }
        }
        AppServices.downloadEngine.start(id)
    }

    private fun handlePause(intent: Intent) {
        val id = intent.getLongExtra(EXTRA_TASK_ID, -1L)
        if (id <= 0) return
        AppServices.downloadEngine.pause(id)
        scope.launch {
            kotlinx.coroutines.delay(1200)
            maybeStopIdle()
        }
    }

    private fun handleRedownload(intent: Intent) {
        val id = intent.getLongExtra(EXTRA_TASK_ID, -1L)
        if (id <= 0) return
        if (blockedByNetwork()) return
        scope.launch {
            val task = AppServices.downloadRepository.taskById(id) ?: return@launch
            // 先停止旧任务，再重置分块进度重新下载
            AppServices.downloadEngine.cancelAndWait(id)
            AppServices.downloadRepository.deleteTask(id)
            val probe = runCatching {
                AppServices.downloadEngine.probe(task.url)
            }.getOrDefault(DownloadEngine.Probe(0L, false))
            val total = if (probe.total > 0L && probe.rangeSupported) probe.total else 0L
            val fresh = AppServices.downloadRepository.createTask(
                url = task.url, name = task.name, mimetype = task.mimetype,
                totalSize = total,
                chunkCount = if (total > 0) {
                    DownloadEngine.chunkCountForFile(this@DownloadService, total)
                } else 1,
                flags = if (AppServices.privacyManager.isIncognito ||
                    task.flags and DownloadTaskFlags.INCOGNITO != 0
                ) DownloadTaskFlags.INCOGNITO else 0
            )
            activeTaskId = fresh.id
            activeChunks = fresh.chunks
            AppServices.downloadEngine.start(fresh.id)
        }
    }

    private suspend fun pauseAll() {
        AppServices.downloadRepository.allTasks().first().forEach {
            if (it.state == DownloadState.DOWNLOADING) AppServices.downloadEngine.pause(it.id)
        }
        kotlinx.coroutines.delay(1200)
        maybeStopIdle()
    }

    private suspend fun resumeAll() {
        if (blockedByNetwork()) return
        AppServices.downloadRepository.allTasks().first().forEach {
            if (it.state == DownloadState.PAUSED) AppServices.downloadEngine.start(it.id)
        }
    }

    private fun restorePendingTasks() {
        if (blockedByNetwork()) return
        scope.launch {
            // 只恢复被中断的任务；新下载由 onStartCommand 创建，不能在这里提前停服务。
            val interrupted = AppServices.downloadRepository.allTasks().first()
                .firstOrNull { it.state == DownloadState.DOWNLOADING } ?: return@launch
            activeTaskId = interrupted.id
            activeChunks = interrupted.chunks
            AppServices.downloadEngine.start(interrupted.id)
        }
    }

    // ---- 通知 ----

    private fun createChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.download_channel),
            NotificationManager.IMPORTANCE_LOW
        )
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    private fun updateNotification(taskId: Long, downloaded: Long, total: Long) {
        val nm = getSystemService(NotificationManager::class.java)
        nm.notify(NOTIFICATION_ID, buildNotification(taskId, downloaded, total))
    }

    /** HLS 下载进度通知（按分片数计算百分比）。 */
    private fun notifyHls(percent: Int, done: Int, total: Int) {
        val nm = getSystemService(NotificationManager::class.java)
        val notification = Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle(getString(R.string.download_notification_title))
            .setContentText(getString(R.string.download_hls_progress, done, total))
            .setProgress(100, percent, total <= 0)
            .setOngoing(true)
            .build()
        nm.notify(NOTIFICATION_ID, notification)
    }

    private fun notifyHlsConverting() {
        val nm = getSystemService(NotificationManager::class.java)
        val notification = Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle(getString(R.string.download_notification_title))
            .setContentText(getString(R.string.download_hls_converting))
            .setProgress(100, 100, true)
            .setOngoing(true)
            .build()
        nm.notify(NOTIFICATION_ID, notification)
    }

    private fun notifyHlsDone(name: String) {
        val nm = getSystemService(NotificationManager::class.java)
        val notification = Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_download_done)
            .setContentTitle(getString(R.string.download_notification_title))
            .setContentText(getString(R.string.download_hls_done, name))
            .setOngoing(false)
            .build()
        nm.notify(NOTIFICATION_ID, notification)
        stopForeground(STOP_FOREGROUND_DETACH)
    }

    private fun notifyHlsFailed() {
        val nm = getSystemService(NotificationManager::class.java)
        val notification = Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_notify_error)
            .setContentTitle(getString(R.string.download_notification_title))
            .setContentText(getString(R.string.download_hls_failed))
            .setOngoing(false)
            .build()
        nm.notify(NOTIFICATION_ID, notification)
        stopForeground(STOP_FOREGROUND_DETACH)
    }

    private fun buildNotification(taskId: Long, downloaded: Long, total: Long): Notification {        val contentIntent = PendingIntent.getActivity(
            this, 0,
            android.content.Intent(this, com.sii.browser.ui.ShellActivity::class.java)
                .putExtra("sii_action", "downloads")
                .addFlags(
                    android.content.Intent.FLAG_ACTIVITY_NEW_TASK or
                        android.content.Intent.FLAG_ACTIVITY_SINGLE_TOP
                ),
            PendingIntent.FLAG_IMMUTABLE
        )
        val progress = if (total > 0) (downloaded * 100 / total).toInt() else 0
        val segments = if (activeChunks > 1) {
            " · " + getString(R.string.download_segments, activeChunks)
        } else ""
        return Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle(getString(R.string.download_notification_title))
            .setContentText(
                if (taskId > 0) "$downloaded / $total$segments"
                else getString(R.string.download_preparing)
            )
            .setProgress(100, progress, total <= 0L)
            .setOngoing(true)
            .setContentIntent(contentIntent)
            .build()
    }

    companion object {
        private const val CHANNEL_ID = "downloads"
        private const val NOTIFICATION_ID = 1

        const val ACTION_DOWNLOAD = "com.sii.browser.download.DOWNLOAD"
        const val ACTION_DOWNLOAD_HLS = "com.sii.browser.download.DOWNLOAD_HLS"
        const val ACTION_RESUME = "com.sii.browser.download.RESUME"
        const val ACTION_PAUSE = "com.sii.browser.download.PAUSE"
        const val ACTION_REDOWNLOAD = "com.sii.browser.download.REDOWNLOAD"
        const val ACTION_PAUSE_ALL = "com.sii.browser.download.PAUSE_ALL"
        const val ACTION_REFRESH = "com.sii.browser.download.REFRESH"
        const val ACTION_RESUME_ALL = "com.sii.browser.download.RESUME_ALL"
        const val EXTRA_URL = "url"
        const val EXTRA_NAME = "name"
        const val EXTRA_MIME = "mime"
        const val EXTRA_TASK_ID = "task_id"
        const val EXTRA_REFERER = "referer"
        const val EXTRA_UA = "ua"
        const val EXTRA_INCOGNITO = "incognito"

        fun resumeTask(context: Context, id: Long) = startControl(context, ACTION_RESUME, id)

        fun pauseTask(context: Context, id: Long) = startControl(context, ACTION_PAUSE, id)

        fun pauseAllTasks(context: Context) = startControl(context, ACTION_PAUSE_ALL, -1L)

        fun resumeAllTasks(context: Context) = startControl(context, ACTION_RESUME_ALL, -1L)

        private fun startControl(context: Context, action: String, id: Long) {
            runCatching {
                val intent = Intent(context, DownloadService::class.java).setAction(action)
                if (id > 0) intent.putExtra(EXTRA_TASK_ID, id)
                context.startForegroundService(intent)
            }
        }

        /** 删除/清空任务后通知服务刷新或停止，避免通知栏残留。 */
        fun onTasksChanged(context: Context) {
            runCatching {
                context.startService(
                    Intent(context, DownloadService::class.java).setAction(ACTION_REFRESH)
                )
            }
        }

        fun startDownload(
            context: Context,
            url: String,
            name: String?,
            mime: String?,
            incognito: Boolean = false
        ) {
            val intent = Intent(context, DownloadService::class.java).apply {
                action = ACTION_DOWNLOAD
                putExtra(EXTRA_URL, url)
                putExtra(EXTRA_NAME, name)
                putExtra(EXTRA_MIME, mime)
                putExtra(EXTRA_INCOGNITO, incognito)
            }
            runCatching { context.startForegroundService(intent) }
        }

        /** 触发 HLS（m3u8）下载。 */
        fun startHlsDownload(
            context: Context,
            url: String,
            name: String?,
            referer: String?,
            userAgent: String?
        ) {
            val intent = Intent(context, DownloadService::class.java).apply {
                action = ACTION_DOWNLOAD_HLS
                putExtra(EXTRA_URL, url)
                putExtra(EXTRA_NAME, name)
                putExtra(EXTRA_REFERER, referer)
                putExtra(EXTRA_UA, userAgent)
            }
            runCatching { context.startForegroundService(intent) }
        }
    }
}

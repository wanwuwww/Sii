// SPDX-License-Identifier: LicenseRef-Sii-NC
// Copyright (c) 2026 wanwuwww
package com.sii.browser.download

import com.sii.browser.data.db.DownloadChunk
import com.sii.browser.data.db.DownloadDao
import com.sii.browser.data.db.DownloadState
import com.sii.browser.data.db.DownloadTask
import kotlinx.coroutines.flow.Flow

/**
 * 下载任务仓库（包装 [DownloadDao]：任务/分块创建、进度、级联删除）。
 */
class DownloadRepository(private val dao: DownloadDao) {

    fun allTasks(): Flow<List<DownloadTask>> = dao.allTasks()

    suspend fun taskById(id: Long): DownloadTask? = dao.taskById(id)

    suspend fun taskByUrl(url: String): DownloadTask? = dao.taskByUrl(url)

    /** 创建任务与分块（Range 区间），返回任务。 */
    suspend fun createTask(
        url: String,
        name: String,
        mimetype: String?,
        totalSize: Long,
        chunkCount: Int,
        flags: Int = 0
    ): DownloadTask {
        val now = System.currentTimeMillis()
        val safeName = name.replace(Regex("""[\\/:*?"<>|]"""), "_")
            .trim().take(120).ifBlank { "download" }
        val task = DownloadTask(
            name = safeName,
            url = url,
            header = null,
            mimetype = mimetype,
            path = null,
            fileUri = null,
            downloadedSize = 0L,
            totalSize = totalSize,
            chunks = chunkCount,
            priority = 0,
            state = DownloadState.PENDING,
            errorMessage = null,
            flags = flags,
            createdAt = now,
            updatedAt = now
        )
        val count = chunkCount.coerceAtLeast(1)
        val chunkSize = if (totalSize > 0) (totalSize + count - 1) / count else 0L
        val chunks = (0 until count).map { i ->
            val start = i * chunkSize
            val length = if (i == count - 1) totalSize - start else chunkSize
            DownloadChunk(taskId = 0, start = start, length = length, downloaded = 0L, flags = 0)
        }
        val taskId = dao.insertTaskWithChunks(task, chunks)
        return task.copy(id = taskId)
    }

    suspend fun updateTask(task: DownloadTask) = dao.updateTask(task)

    suspend fun chunksOf(taskId: Long): List<DownloadChunk> = dao.chunksOf(taskId)

    suspend fun updateChunkProgress(chunkId: Long, downloaded: Long) =
        dao.updateChunkProgress(chunkId, downloaded)

    suspend fun deleteTask(id: Long) {
        dao.deleteChunks(id)
        dao.deleteTask(id)
    }

    /** 清空已完成任务。 */
    suspend fun clearCompleted() {
        dao.tasksByState(DownloadState.COMPLETED).forEach { deleteTask(it.id) }
    }

    /** 清空全部任务。 */
    suspend fun clearAll() {
        dao.allIds().forEach { deleteTask(it) }
    }

    /** 当前已下载总字节（数据库聚合，避免取回全部分块）。 */
    suspend fun sumDownloaded(taskId: Long): Long =
        dao.sumDownloaded(taskId) ?: 0L

    suspend fun setState(taskId: Long, state: Int) {
        dao.updateState(taskId, state, System.currentTimeMillis())
    }

    suspend fun updateProgress(taskId: Long, downloaded: Long) {
        dao.updateProgress(taskId, downloaded, System.currentTimeMillis())
    }

    suspend fun markFailed(taskId: Long, error: String?) {
        dao.updateFailed(taskId, DownloadState.FAILED, error, System.currentTimeMillis())
    }

    suspend fun updatePath(taskId: Long, path: String?) {
        dao.updatePath(taskId, path, System.currentTimeMillis())
    }

    suspend fun updateTotalSize(taskId: Long, total: Long) {
        dao.updateTotalSize(taskId, total, System.currentTimeMillis())
    }

    suspend fun updateFileInfo(
        taskId: Long,
        name: String,
        mime: String?,
        path: String?,
        total: Long
    ) {
        dao.updateFileInfo(taskId, name, mime, path, total, System.currentTimeMillis())
    }
}

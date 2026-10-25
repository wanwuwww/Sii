package com.sii.browser.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

/**
 * 下载任务与分块存取。
 */
@Dao
interface DownloadDao {

    @Insert
    suspend fun insertTask(task: DownloadTask): Long

    @Update
    suspend fun updateTask(task: DownloadTask)

    @Query("SELECT * FROM download_tasks WHERE id = :id")
    suspend fun taskById(id: Long): DownloadTask?

    @Query("SELECT * FROM download_tasks ORDER BY created_at DESC")
    fun allTasks(): Flow<List<DownloadTask>>

    @Query("SELECT * FROM download_tasks WHERE state = :state ORDER BY created_at DESC")
    suspend fun tasksByState(state: Int): List<DownloadTask>

    @Query("SELECT id FROM download_tasks")
    suspend fun allIds(): List<Long>

    @Query("SELECT * FROM download_tasks WHERE url = :url ORDER BY created_at DESC LIMIT 1")
    suspend fun taskByUrl(url: String): DownloadTask?

    @Query("DELETE FROM download_tasks WHERE id = :id")
    suspend fun deleteTask(id: Long)

    @Query("DELETE FROM download_chunks WHERE task_id = :taskId")
    suspend fun deleteChunks(taskId: Long)

    @Insert
    suspend fun insertChunks(chunks: List<DownloadChunk>)

    /** 任务与分块同一事务写入，避免进程中断留下无分块的任务。 */
    @Transaction
    suspend fun insertTaskWithChunks(task: DownloadTask, chunks: List<DownloadChunk>): Long {
        val id = insertTask(task)
        insertChunks(chunks.map { it.copy(taskId = id) })
        return id
    }

    @Query("SELECT * FROM download_chunks WHERE task_id = :taskId ORDER BY start")
    suspend fun chunksOf(taskId: Long): List<DownloadChunk>

    @Query("SELECT SUM(downloaded) FROM download_chunks WHERE task_id = :taskId")
    suspend fun sumDownloaded(taskId: Long): Long?

    @Query("UPDATE download_chunks SET downloaded = :downloaded WHERE id = :id")
    suspend fun updateChunkProgress(id: Long, downloaded: Long)

    @Query("UPDATE download_tasks SET state = :state, updated_at = :updatedAt WHERE id = :id")
    suspend fun updateState(id: Long, state: Int, updatedAt: Long)

    @Query("UPDATE download_tasks SET downloaded_size = :downloaded, updated_at = :updatedAt WHERE id = :id")
    suspend fun updateProgress(id: Long, downloaded: Long, updatedAt: Long)

    @Query("UPDATE download_tasks SET state = :state, error_message = :error, updated_at = :updatedAt WHERE id = :id")
    suspend fun updateFailed(id: Long, state: Int, error: String?, updatedAt: Long)

    @Query("UPDATE download_tasks SET path = :path, updated_at = :updatedAt WHERE id = :id")
    suspend fun updatePath(id: Long, path: String?, updatedAt: Long)

    @Query("UPDATE download_tasks SET total_size = :total, updated_at = :updatedAt WHERE id = :id")
    suspend fun updateTotalSize(id: Long, total: Long, updatedAt: Long)

    @Query("UPDATE download_tasks SET name = :name, mimetype = :mime, path = :path, total_size = :total, downloaded_size = :total, updated_at = :updatedAt WHERE id = :id")
    suspend fun updateFileInfo(
        id: Long,
        name: String,
        mime: String?,
        path: String?,
        total: Long,
        updatedAt: Long
    )
}

// SPDX-License-Identifier: LicenseRef-Sii-NC
// Copyright (c) 2026 wanwuwww
package com.sii.browser.data.db

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * 下载任务。
 */
@Entity(tableName = "download_tasks")
data class DownloadTask(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val url: String,
    val header: String?,
    val mimetype: String?,
    val path: String?,
    @ColumnInfo(name = "file_uri") val fileUri: String?,
    @ColumnInfo(name = "downloaded_size") val downloadedSize: Long,
    @ColumnInfo(name = "total_size") val totalSize: Long,
    val chunks: Int,
    val priority: Int,
    val state: Int,
    @ColumnInfo(name = "error_message") val errorMessage: String?,
    val flags: Int,
    @ColumnInfo(name = "created_at") val createdAt: Long,
    @ColumnInfo(name = "updated_at") val updatedAt: Long
)

/**
 * 下载分块进度。
 */
@Entity(tableName = "download_chunks")
data class DownloadChunk(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    @ColumnInfo(name = "task_id") val taskId: Long,
    val start: Long,
    val length: Long,
    val downloaded: Long,
    val flags: Int
)

/** 下载任务状态（Sii 自定，写入 state 字段）。 */
object DownloadState {
    const val PENDING = 0
    const val DOWNLOADING = 1
    const val PAUSED = 2
    const val COMPLETED = 3
    const val FAILED = 4
}

object DownloadTaskFlags {
    /** 无痕会话中创建的任务，退出无痕时可清理。 */
    const val INCOGNITO = 1 shl 1
}

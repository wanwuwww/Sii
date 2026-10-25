package com.sii.browser.data.db

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * 书签条目。
 */
@Entity(tableName = "bookmark_items")
data class BookmarkItem(
    @PrimaryKey val id: String,
    val url: String,
    val title: String,
    @ColumnInfo(name = "folder_id") val folderId: String?,
    val ordering: Int,
    @ColumnInfo(name = "created_at") val createdAt: Long,
    @ColumnInfo(name = "last_updated_at") val updatedAt: Long,
    val pinned: Int = 0,
    @ColumnInfo(name = "last_opened_at") val lastOpenedAt: Long = 0
)

/** 文件夹内的条目数（书签或子文件夹）。 */
data class FolderItemCount(
    @ColumnInfo(name = "folder_id") val folderId: String?,
    val count: Int
)

/**
 * 书签文件夹。
 */
@Entity(tableName = "bookmark_folders")
data class BookmarkFolder(
    @PrimaryKey val id: String,
    val title: String,
    @ColumnInfo(name = "parent_folder_id") val parentFolderId: String?,
    val ordering: Int,
    @ColumnInfo(name = "created_at") val createdAt: Long,
    @ColumnInfo(name = "last_updated_at") val updatedAt: Long
)

/**
 * 浏览历史。
 */
@Entity(tableName = "history")
data class HistoryItem(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val url: String,
    val title: String,
    @ColumnInfo(name = "updated_at") val updatedAt: Long
)

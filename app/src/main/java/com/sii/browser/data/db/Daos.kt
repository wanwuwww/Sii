package com.sii.browser.data.db

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

/**
 * 书签存取。
 */
@Dao
interface BookmarkDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertItem(item: BookmarkItem)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertFolder(folder: BookmarkFolder)

    @Update
    suspend fun updateItem(item: BookmarkItem)

    @Update
    suspend fun updateFolder(folder: BookmarkFolder)

    @Query("DELETE FROM bookmark_items WHERE id = :id")
    suspend fun deleteItem(id: String)

    @Query("DELETE FROM bookmark_items WHERE folder_id = :folderId")
    suspend fun deleteItemsInFolder(folderId: String)

    @Query("DELETE FROM bookmark_folders WHERE id = :id")
    suspend fun deleteFolder(id: String)

    @Query("DELETE FROM bookmark_folders WHERE parent_folder_id = :parentId")
    suspend fun deleteChildFolders(parentId: String)

    @Query(
        "SELECT * FROM bookmark_items WHERE " +
            "(folder_id = :folderId OR (folder_id IS NULL AND :folderId IS NULL)) " +
            "ORDER BY ordering"
    )
    fun itemsInFolder(folderId: String?): Flow<List<BookmarkItem>>

    @Query(
        "SELECT * FROM bookmark_items WHERE " +
            "(folder_id = :folderId OR (folder_id IS NULL AND :folderId IS NULL)) " +
            "ORDER BY ordering"
    )
    suspend fun itemsInFolderOnce(folderId: String?): List<BookmarkItem>

    @Query(
        "SELECT * FROM bookmark_folders WHERE " +
            "(parent_folder_id = :parentId OR (parent_folder_id IS NULL AND :parentId IS NULL)) " +
            "ORDER BY ordering"
    )
    fun childFolders(parentId: String?): Flow<List<BookmarkFolder>>

    @Query("SELECT * FROM bookmark_items ORDER BY folder_id, ordering")
    suspend fun allItemsOnce(): List<BookmarkItem>

    @Query("SELECT * FROM bookmark_folders ORDER BY parent_folder_id, ordering")
    suspend fun allFoldersOnce(): List<BookmarkFolder>

    @Query(
        "SELECT * FROM bookmark_folders WHERE " +
            "(parent_folder_id = :parentId OR (parent_folder_id IS NULL AND :parentId IS NULL)) " +
            "ORDER BY ordering"
    )
    suspend fun childFoldersOnce(parentId: String?): List<BookmarkFolder>

    @Query("SELECT * FROM bookmark_items WHERE url = :url LIMIT 1")
    suspend fun findItemByUrl(url: String): BookmarkItem?

    @Query("SELECT * FROM bookmark_items WHERE id = :id LIMIT 1")
    suspend fun findItemById(id: String): BookmarkItem?

    @Query("SELECT * FROM bookmark_folders WHERE id = :id LIMIT 1")
    suspend fun findFolderById(id: String): BookmarkFolder?

    @Query("DELETE FROM bookmark_items WHERE id IN (:ids)")
    suspend fun deleteItems(ids: List<String>)

    @Query("SELECT folder_id, COUNT(*) AS count FROM bookmark_items GROUP BY folder_id")
    fun itemCounts(): Flow<List<FolderItemCount>>

    @Query("SELECT parent_folder_id AS folder_id, COUNT(*) AS count FROM bookmark_folders GROUP BY parent_folder_id")
    fun folderCounts(): Flow<List<FolderItemCount>>

    @Query(
        "SELECT MAX(ordering) FROM bookmark_items WHERE " +
            "(folder_id = :folderId OR (folder_id IS NULL AND :folderId IS NULL))"
    )
    suspend fun maxItemOrdering(folderId: String?): Int?

    @Query(
        "SELECT MAX(ordering) FROM bookmark_folders WHERE " +
            "(parent_folder_id = :parentId OR (parent_folder_id IS NULL AND :parentId IS NULL))"
    )
    suspend fun maxFolderOrdering(parentId: String?): Int?
}

/**
 * 历史存取。
 */
@Dao
interface HistoryDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(item: HistoryItem)

    @Update
    suspend fun update(item: HistoryItem)

    @Query("SELECT * FROM history WHERE url = :url LIMIT 1")
    suspend fun findByUrl(url: String): HistoryItem?

    @Query("SELECT * FROM history ORDER BY updated_at DESC LIMIT :limit")
    fun recent(limit: Int): Flow<List<HistoryItem>>

    @Query("DELETE FROM history WHERE id = :id")
    suspend fun delete(id: Long)

    @Query("DELETE FROM history")
    suspend fun clearAll()
}

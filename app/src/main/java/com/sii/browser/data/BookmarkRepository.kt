// SPDX-License-Identifier: LicenseRef-Sii-NC
// Copyright (c) 2026 wanwuwww
package com.sii.browser.data

import com.sii.browser.data.db.BookmarkDao
import com.sii.browser.data.db.BookmarkFolder
import com.sii.browser.data.db.BookmarkItem
import com.sii.browser.data.db.FolderItemCount
import kotlinx.coroutines.flow.Flow
import java.util.UUID

/**
 * 书签仓库（包装 [BookmarkDao]，提供文件夹级联与排序逻辑）。
 */
class BookmarkRepository(private val dao: BookmarkDao) {

    fun itemsInFolder(folderId: String?): Flow<List<BookmarkItem>> = dao.itemsInFolder(folderId)

    fun childFolders(parentId: String?): Flow<List<BookmarkFolder>> = dao.childFolders(parentId)

    fun itemCounts(): Flow<List<FolderItemCount>> = dao.itemCounts()

    fun folderCounts(): Flow<List<FolderItemCount>> = dao.folderCounts()

    suspend fun allItemsOnce(): List<BookmarkItem> = dao.allItemsOnce()

    suspend fun allFoldersOnce(): List<BookmarkFolder> = dao.allFoldersOnce()

    suspend fun childFoldersOnce(parentId: String?): List<BookmarkFolder> =
        dao.childFoldersOnce(parentId)

    /** 同目录内上移/下移条目（重排 ordering 使顺序连续）。 */
    suspend fun moveItem(item: BookmarkItem, up: Boolean) {
        val list = dao.itemsInFolderOnce(item.folderId)
        val index = list.indexOfFirst { it.id == item.id }
        if (index < 0) return
        val target = if (up) index - 1 else index + 1
        if (target !in list.indices) return
        val reordered = list.toMutableList()
        reordered.add(target, reordered.removeAt(index))
        reordered.forEachIndexed { i, entry ->
            if (entry.ordering != i) dao.updateItem(entry.copy(ordering = i))
        }
    }

    suspend fun findItemByUrl(url: String): BookmarkItem? = dao.findItemByUrl(url)

    suspend fun addItem(url: String, title: String, folderId: String?): BookmarkItem {
        val now = System.currentTimeMillis()
        val order = (dao.maxItemOrdering(folderId) ?: 0) + 1
        val item = BookmarkItem(
            id = UUID.randomUUID().toString(),
            url = url,
            title = title,
            folderId = folderId,
            ordering = order,
            createdAt = now,
            updatedAt = now
        )
        dao.insertItem(item)
        return item
    }

    suspend fun addFolder(title: String, parentId: String?): BookmarkFolder {
        val now = System.currentTimeMillis()
        val order = (dao.maxFolderOrdering(parentId) ?: 0) + 1
        val folder = BookmarkFolder(
            id = UUID.randomUUID().toString(),
            title = title,
            parentFolderId = parentId,
            ordering = order,
            createdAt = now,
            updatedAt = now
        )
        dao.insertFolder(folder)
        return folder
    }

    /** 删除文件夹及其中条目与子文件夹。 */
    suspend fun deleteFolderCascade(folderId: String) {
        dao.deleteItemsInFolder(folderId)
        dao.childFoldersOnce(folderId).forEach { deleteFolderCascade(it.id) }
        dao.deleteFolder(folderId)
    }

    suspend fun deleteItem(id: String) = dao.deleteItem(id)

    suspend fun updateTitle(item: BookmarkItem, title: String) {
        dao.updateItem(item.copy(title = title, updatedAt = System.currentTimeMillis()))
    }

    suspend fun updateItem(item: BookmarkItem, title: String, url: String, folderId: String?) {
        val order = if (item.folderId != folderId) {
            (dao.maxItemOrdering(folderId) ?: 0) + 1
        } else {
            item.ordering
        }
        dao.updateItem(
            item.copy(
                title = title,
                url = url,
                folderId = folderId,
                ordering = order,
                updatedAt = System.currentTimeMillis()
            )
        )
    }

    suspend fun setPinned(item: BookmarkItem, pinned: Boolean) {
        dao.updateItem(
            item.copy(pinned = if (pinned) 1 else 0, updatedAt = System.currentTimeMillis())
        )
    }

    suspend fun markOpened(item: BookmarkItem) {
        dao.updateItem(item.copy(lastOpenedAt = System.currentTimeMillis()))
    }

    suspend fun moveItemToFolder(item: BookmarkItem, targetFolderId: String?) {
        val order = (dao.maxItemOrdering(targetFolderId) ?: 0) + 1
        dao.updateItem(
            item.copy(
                folderId = targetFolderId,
                ordering = order,
                updatedAt = System.currentTimeMillis()
            )
        )
    }

    suspend fun moveFolderToFolder(folder: BookmarkFolder, targetParentId: String?) {
        val order = (dao.maxFolderOrdering(targetParentId) ?: 0) + 1
        dao.updateFolder(
            folder.copy(
                parentFolderId = targetParentId,
                ordering = order,
                updatedAt = System.currentTimeMillis()
            )
        )
    }

    suspend fun deleteItems(ids: List<String>) = dao.deleteItems(ids)

    /** 按当前展示顺序重排自定义排序值。 */
    suspend fun reorderItems(items: List<BookmarkItem>) {
        items.forEachIndexed { index, item ->
            if (item.ordering != index) dao.updateItem(item.copy(ordering = index))
        }
    }

    suspend fun renameFolder(folder: BookmarkFolder, title: String) {
        dao.updateFolder(
            folder.copy(title = title, updatedAt = System.currentTimeMillis())
        )
    }

    suspend fun findItemById(id: String): BookmarkItem? = dao.findItemById(id)

    suspend fun findFolderById(id: String): BookmarkFolder? = dao.findFolderById(id)
}

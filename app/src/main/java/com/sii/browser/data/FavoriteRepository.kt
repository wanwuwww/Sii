package com.sii.browser.data

import com.sii.browser.data.db.FavoriteDao
import com.sii.browser.data.db.FavoriteItem
import kotlinx.coroutines.flow.Flow

/**
 * 主页收藏仓库（包装 [FavoriteDao]）。
 */
class FavoriteRepository(private val dao: FavoriteDao) {

    fun all(): Flow<List<FavoriteItem>> = dao.all()

    suspend fun findByUrl(url: String) = dao.findByUrl(url)

    suspend fun add(url: String, title: String): FavoriteItem {
        val item = FavoriteItem(
            url = url,
            title = title.ifEmpty { url },
            order = dao.nextOrder(),
            createdAt = System.currentTimeMillis()
        )
        dao.insert(item)
        return item
    }

    suspend fun delete(id: Long) = dao.delete(id)

    /** 将收藏上移/下移一位（交换排序值）。 */
    suspend fun move(item: FavoriteItem, up: Boolean) {
        val list = dao.allOnce()
        val index = list.indexOfFirst { it.id == item.id }
        if (index < 0) return
        val target = if (up) index - 1 else index + 1
        if (target !in list.indices) return
        val other = list[target]
        dao.update(item.copy(order = other.order))
        dao.update(other.copy(order = item.order))
    }

    suspend fun clearAll() = dao.clearAll()

    /** 按给定 id 顺序整体重排常用网站。 */
    suspend fun reorder(ids: List<Long>) {
        if (ids.isEmpty()) return
        val byId = dao.allOnce().associateBy { it.id }
        val ordered = ids.mapNotNull { byId[it] } + byId.values.filter { it.id !in ids }
        dao.updateAll(ordered.mapIndexed { index, item -> item.copy(order = index + 1) })
    }
}

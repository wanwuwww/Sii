package com.sii.browser.data

import com.sii.browser.data.db.HistoryDao
import com.sii.browser.data.db.HistoryItem
import kotlinx.coroutines.flow.Flow

/**
 * 历史仓库（包装 [HistoryDao]：记录/更新/最近列表/清除）。
 */
class HistoryRepository(private val dao: HistoryDao) {

    fun recent(limit: Int = 50): Flow<List<HistoryItem>> = dao.recent(limit)

    /** 记录一次访问：存在则更新时间与标题，否则新建。 */
    suspend fun recordVisit(url: String, title: String?) {
        val now = System.currentTimeMillis()
        val existing = dao.findByUrl(url)
        if (existing != null) {
            dao.update(existing.copy(updatedAt = now, title = title ?: existing.title))
        } else {
            dao.insert(HistoryItem(url = url, title = title.orEmpty(), updatedAt = now))
        }
    }

    suspend fun delete(id: Long) = dao.delete(id)

    suspend fun findByUrl(url: String): HistoryItem? = dao.findByUrl(url)

    suspend fun search(query: String, limit: Int = 20): List<HistoryItem> =
        dao.search(query, limit.coerceIn(1, 200))

    suspend fun clearAll() = dao.clearAll()
}

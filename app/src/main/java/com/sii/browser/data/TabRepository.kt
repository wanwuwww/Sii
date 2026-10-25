package com.sii.browser.data

import com.sii.browser.data.db.TabDao
import com.sii.browser.data.db.TabEntity

/**
 * 标签页持久化。
 *
 * 只保存可恢复的 URL/标题/顺序；无痕时不保存（由调用方判断）。
 */
class TabRepository(private val dao: TabDao) {

    suspend fun restore(): List<TabEntity> = dao.all()

    /** 覆盖式保存当前标签列表（url/title 顺序即 position）。 */
    suspend fun save(tabs: List<Pair<String, String>>) {
        dao.clear()
        if (tabs.isEmpty()) return
        val now = System.currentTimeMillis()
        dao.insertAll(
            tabs.mapIndexed { index, (url, title) ->
                TabEntity(url = url, title = title, position = index, lastVisitedAt = now)
            }
        )
    }

    suspend fun clear() = dao.clear()
}

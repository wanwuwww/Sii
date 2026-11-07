// SPDX-License-Identifier: LicenseRef-Sii-NC
// Copyright (c) 2026 wanwuwww
package com.sii.browser.data

import com.sii.browser.data.db.ReadLaterDao
import com.sii.browser.data.db.ReadLaterItem
import kotlinx.coroutines.flow.Flow

/** 稍后读列表（离线网页存档）。 */
class ReadLaterRepository(private val dao: ReadLaterDao) {

    fun all(): Flow<List<ReadLaterItem>> = dao.all()

    suspend fun add(url: String, title: String, filePath: String): Long =
        dao.insert(
            ReadLaterItem(
                url = url,
                title = title,
                filePath = filePath,
                createdAt = System.currentTimeMillis()
            )
        )

    suspend fun byId(id: Long): ReadLaterItem? = dao.byId(id)

    suspend fun delete(id: Long) = dao.delete(id)

    suspend fun setRead(id: Long, read: Boolean) = dao.setRead(id, read)

    suspend fun clearAll() = dao.clearAll()
}

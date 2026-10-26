// SPDX-License-Identifier: LicenseRef-Sii-NC
// Copyright (c) 2026 wanwuwww
package com.sii.browser.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

/**
 * 主页收藏存取。
 */
@Dao
interface FavoriteDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(item: FavoriteItem)

    @Query("SELECT * FROM favorites ORDER BY f_order")
    fun all(): Flow<List<FavoriteItem>>

    @Query("SELECT * FROM favorites ORDER BY f_order")
    suspend fun allOnce(): List<FavoriteItem>

    @Update
    suspend fun update(item: FavoriteItem)

    @Update
    suspend fun updateAll(items: List<FavoriteItem>)

    @Query("SELECT * FROM favorites WHERE f_url = :url LIMIT 1")
    suspend fun findByUrl(url: String): FavoriteItem?

    @Query("SELECT COALESCE(MAX(f_order), 0) + 1 FROM favorites")
    suspend fun nextOrder(): Int

    @Query("DELETE FROM favorites WHERE f_id = :id")
    suspend fun delete(id: Long)

    @Query("DELETE FROM favorites")
    suspend fun clearAll()
}

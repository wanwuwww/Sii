// SPDX-License-Identifier: LicenseRef-Sii-NC
// Copyright (c) 2026 wanwuwww
package com.sii.browser.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

/**
 * 账号密码存取（按域名/用户名精确匹配）。
 */
@Dao
interface PasswordDao {

    @Query("SELECT * FROM passwords ORDER BY updated_at DESC")
    fun all(): Flow<List<PasswordItem>>

    @Query("SELECT * FROM passwords WHERE host = :host ORDER BY updated_at DESC")
    suspend fun byHost(host: String): List<PasswordItem>

    @Query("SELECT * FROM passwords WHERE host = :host AND username = :username LIMIT 1")
    suspend fun find(host: String, username: String): PasswordItem?

    @Insert
    suspend fun insert(item: PasswordItem): Long

    @Update
    suspend fun update(item: PasswordItem)

    @Query("DELETE FROM passwords WHERE id = :id")
    suspend fun delete(id: Long)

    @Query("DELETE FROM passwords")
    suspend fun clearAll()
}

// SPDX-License-Identifier: LicenseRef-Sii-NC
// Copyright (c) 2026 wanwuwww
package com.sii.browser.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

/**
 * 站点配置读写（同步方法，供主页 JS 桥在非主线程调用）。
 */
@Dao
interface SiteConfigDao {

    @Query("SELECT * FROM site_configs")
    fun all(): List<SiteConfigEntity>

    @Query("SELECT * FROM site_configs WHERE host = :host LIMIT 1")
    fun findByHost(host: String): SiteConfigEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun put(entity: SiteConfigEntity)

    @Query("DELETE FROM site_configs WHERE host = :host")
    fun remove(host: String)

    @Query("DELETE FROM site_configs")
    fun clearAll()
}

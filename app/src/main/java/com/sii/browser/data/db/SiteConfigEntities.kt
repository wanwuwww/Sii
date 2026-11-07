// SPDX-License-Identifier: LicenseRef-Sii-NC
// Copyright (c) 2026 wanwuwww
package com.sii.browser.data.db

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * 站点级配置。
 */
@Entity(tableName = "site_configs")
data class SiteConfigEntity(
    @PrimaryKey @ColumnInfo(name = "host") val host: String,
    @ColumnInfo(name = "config") val config: String,
    @ColumnInfo(name = "updated_at") val updatedAt: Long
)

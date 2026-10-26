// SPDX-License-Identifier: LicenseRef-Sii-NC
// Copyright (c) 2026 wanwuwww
package com.sii.browser.data.db

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * 主页收藏。
 */
@Entity(tableName = "favorites")
data class FavoriteItem(
    @PrimaryKey(autoGenerate = true) @ColumnInfo(name = "f_id") val id: Long = 0,
    @ColumnInfo(name = "f_url") val url: String,
    @ColumnInfo(name = "f_title") val title: String,
    @ColumnInfo(name = "f_order") val order: Int,
    @ColumnInfo(name = "created_at") val createdAt: Long
)

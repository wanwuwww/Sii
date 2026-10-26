// SPDX-License-Identifier: LicenseRef-Sii-NC
// Copyright (c) 2026 wanwuwww
package com.sii.browser.data.db

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * 已保存的账号密码（密码字段为 AES-GCM 密文，格式 `base64(iv):base64(ct)`）。
 */
@Entity(tableName = "passwords")
data class PasswordItem(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val host: String,
    val username: String,
    val cipher: String,
    @ColumnInfo(name = "created_at") val createdAt: Long,
    @ColumnInfo(name = "updated_at") val updatedAt: Long
)

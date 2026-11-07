// SPDX-License-Identifier: LicenseRef-Sii-NC
// Copyright (c) 2026 wanwuwww
package com.sii.browser.data.db

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * 用户脚本。
 */
@Entity(tableName = "scripts")
data class ScriptEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val author: String?,
    val url: String?,
    /** 生效范围（@match / @include，逗号分隔；空表示全部）。 */
    val match: String,
    /** 排除范围（@exclude-match / @exclude）。 */
    val exclude: String?,
    /** 运行时机（document-start / document-end / document-idle）。 */
    val runAt: String,
    val code: String,
    val enabled: Boolean,
    @ColumnInfo(name = "updated_at") val updatedAt: Long
)

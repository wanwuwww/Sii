// SPDX-License-Identifier: LicenseRef-Sii-NC
// Copyright (c) 2026 wanwuwww
package com.sii.browser.data.db

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * 广告过滤规则。
 */
@Entity(tableName = "rules")
data class RuleEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    /** 0 = 自定义规则，1 = 规则订阅。 */
    val type: Int,
    /** 自定义为名称，订阅为订阅源地址。 */
    val title: String,
    /** 订阅显示名称；为空时按规则标题或预设自动显示。 */
    val name: String = "",
    /** 自定义为规则文本，订阅为下载到的规则文本。 */
    val content: String,
    val enabled: Boolean,
    @ColumnInfo(name = "updated_at") val updatedAt: Long
)

data class RuleSummary(
    val id: Long,
    val type: Int,
    val title: String,
    val name: String,
    val enabled: Boolean,
    @ColumnInfo(name = "updated_at") val updatedAt: Long,
    @ColumnInfo(name = "content_length") val contentLength: Int,
    @ColumnInfo(name = "line_count") val lineCount: Int
)

object RuleType {
    const val CUSTOM = 0
    const val SUBSCRIPTION = 1
    /** 隐私保护托管规则包，不出现在普通订阅列表。 */
    const val PRIVACY = 2
}

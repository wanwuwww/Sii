package com.sii.browser.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

/** 广告过滤规则存取。大文本 content 只按分块读取，避免 CursorWindow 超限。 */
@Dao
interface RuleDao {

    @Insert
    suspend fun insert(rule: RuleEntity): Long

    @Query(
        "SELECT id, type, title, name, enabled, updated_at, " +
            "LENGTH(content) AS content_length, " +
            "CASE WHEN LENGTH(content) = 0 THEN 0 " +
            "ELSE LENGTH(content) - LENGTH(REPLACE(content, char(10), '')) + 1 END AS line_count " +
            "FROM rules ORDER BY type, id"
    )
    fun all(): Flow<List<RuleSummary>>

    @Query(
        "SELECT id, type, title, name, enabled, updated_at, " +
            "LENGTH(content) AS content_length, " +
            "CASE WHEN LENGTH(content) = 0 THEN 0 " +
            "ELSE LENGTH(content) - LENGTH(REPLACE(content, char(10), '')) + 1 END AS line_count " +
            "FROM rules WHERE type = :type ORDER BY id"
    )
    suspend fun summariesByType(type: Int): List<RuleSummary>

    @Query("SELECT substr(content, :offset + 1, :length) FROM rules WHERE id = :id")
    suspend fun contentChunk(id: Long, offset: Int, length: Int): String?

    @Query("UPDATE rules SET content = :content, updated_at = :updatedAt WHERE id = :id")
    suspend fun updateContent(id: Long, content: String, updatedAt: Long)

    @Query("UPDATE rules SET title = :title WHERE id = :id")
    suspend fun updateTitle(id: Long, title: String)

    @Query("UPDATE rules SET name = :name WHERE id = :id")
    suspend fun updateName(id: Long, name: String)

    @Query("UPDATE rules SET name = :name WHERE id = :id AND (name IS NULL OR name = '')")
    suspend fun updateNameIfEmpty(id: Long, name: String)

    @Query("UPDATE rules SET enabled = :enabled WHERE id = :id")
    suspend fun setEnabled(id: Long, enabled: Boolean)

    @Query("DELETE FROM rules WHERE id = :id")
    suspend fun delete(id: Long)
}

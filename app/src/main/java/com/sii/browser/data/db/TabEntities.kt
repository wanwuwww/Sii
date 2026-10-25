package com.sii.browser.data.db

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query

/**
 * 标签页恢复记录。
 *
 * 只存 URL/标题/顺序，WebView 实例不持久化；恢复时按顺序重建标签。
 */
@Entity(tableName = "tabs")
data class TabEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val url: String,
    val title: String,
    val position: Int,
    val lastVisitedAt: Long
)

@Dao
interface TabDao {

    @Query("SELECT * FROM tabs ORDER BY position ASC")
    suspend fun all(): List<TabEntity>

    @Query("SELECT COUNT(*) FROM tabs")
    suspend fun count(): Int

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(items: List<TabEntity>)

    @Query("DELETE FROM tabs")
    suspend fun clear()
}

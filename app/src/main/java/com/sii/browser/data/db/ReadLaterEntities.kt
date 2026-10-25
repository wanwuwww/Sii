package com.sii.browser.data.db

import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.PrimaryKey
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

/** 稍后读条目（离线保存的网页存档）。 */
@Entity(tableName = "read_later")
data class ReadLaterItem(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val url: String,
    val title: String,
    @ColumnInfo(name = "file_path") val filePath: String,
    @ColumnInfo(name = "created_at") val createdAt: Long,
    @ColumnInfo(name = "read_flag") val read: Boolean = false
)

@Dao
interface ReadLaterDao {

    @Insert
    suspend fun insert(item: ReadLaterItem): Long

    @Query("SELECT * FROM read_later ORDER BY created_at DESC")
    fun all(): Flow<List<ReadLaterItem>>

    @Query("SELECT * FROM read_later WHERE id = :id LIMIT 1")
    suspend fun byId(id: Long): ReadLaterItem?

    @Query("DELETE FROM read_later WHERE id = :id")
    suspend fun delete(id: Long)

    @Query("UPDATE read_later SET read_flag = :read WHERE id = :id")
    suspend fun setRead(id: Long, read: Boolean)

    @Query("DELETE FROM read_later")
    suspend fun clearAll()
}

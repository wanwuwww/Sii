package com.sii.browser.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

/**
 * 用户脚本存取。
 */
@Dao
interface ScriptDao {

    @Insert
    suspend fun insert(script: ScriptEntity): Long

    @Update
    suspend fun update(script: ScriptEntity)

    @Query("SELECT * FROM scripts ORDER BY id")
    fun all(): Flow<List<ScriptEntity>>

    @Query("SELECT * FROM scripts WHERE enabled = 1")
    suspend fun enabledScripts(): List<ScriptEntity>

    @Query("SELECT * FROM scripts WHERE id = :id")
    suspend fun byId(id: Long): ScriptEntity?

    @Query("DELETE FROM scripts WHERE id = :id")
    suspend fun delete(id: Long)

    @Query("DELETE FROM scripts")
    suspend fun clearAll()
}

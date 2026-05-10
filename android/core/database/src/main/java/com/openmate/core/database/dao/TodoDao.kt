package com.openmate.core.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.openmate.core.database.entity.TodoEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface TodoDao {
    @Query("SELECT * FROM TodoEntity WHERE sessionID = :sessionID")
    fun observeBySession(sessionID: String): Flow<TodoEntity?>

    @Query("SELECT * FROM TodoEntity WHERE sessionID = :sessionID")
    suspend fun getBySession(sessionID: String): TodoEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(todo: TodoEntity)

    @Query("DELETE FROM TodoEntity WHERE sessionID = :sessionID")
    suspend fun deleteBySession(sessionID: String)
}

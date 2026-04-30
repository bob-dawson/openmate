package com.openmate.core.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.openmate.core.database.entity.MessageEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface MessageDao {
    @Query("SELECT * FROM MessageEntity WHERE sessionID = :sid ORDER BY createdAt DESC LIMIT :limit")
    suspend fun getBySession(sid: String, limit: Int): List<MessageEntity>

    @Query("SELECT * FROM MessageEntity WHERE sessionID = :sid AND createdAt > :after ORDER BY createdAt ASC LIMIT :limit")
    suspend fun getBySessionAfter(sid: String, limit: Int, after: Long): List<MessageEntity>

    @Query("SELECT * FROM MessageEntity WHERE sessionID = :sid ORDER BY createdAt ASC")
    fun observeBySession(sid: String): Flow<List<MessageEntity>>

    @Query("SELECT * FROM MessageEntity WHERE id = :id")
    suspend fun getById(id: String): MessageEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(message: MessageEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(messages: List<MessageEntity>)

    @Query("DELETE FROM MessageEntity WHERE id = :id")
    suspend fun delete(id: String)

    @Query("DELETE FROM MessageEntity WHERE sessionID = :sid")
    suspend fun deleteBySession(sid: String)

    @Query("SELECT DISTINCT sessionID FROM MessageEntity WHERE role = 'ASSISTANT' AND completedAt IS NULL")
    suspend fun getBusySessionIDs(): List<String>
}

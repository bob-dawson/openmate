package com.openmate.core.database.dao

import androidx.room.*
import com.openmate.core.database.entity.SessionMessageEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface SessionMessageDao {
    @Query("SELECT * FROM session_message WHERE sessionId = :sessionId ORDER BY timeCreated ASC")
    fun observeBySession(sessionId: String): Flow<List<SessionMessageEntity>>

    @Query("SELECT * FROM session_message WHERE sessionId = :sessionId ORDER BY timeCreated DESC LIMIT :limit")
    suspend fun getBySessionDesc(sessionId: String, limit: Int): List<SessionMessageEntity>

    @Query("SELECT * FROM session_message WHERE sessionId = :sessionId ORDER BY timeCreated ASC")
    suspend fun getAllBySession(sessionId: String): List<SessionMessageEntity>

    @Query("SELECT * FROM session_message WHERE id = :id")
    suspend fun getById(id: String): SessionMessageEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: SessionMessageEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(entities: List<SessionMessageEntity>)

    @Query("DELETE FROM session_message WHERE id = :id")
    suspend fun delete(id: String)

    @Query("DELETE FROM session_message WHERE sessionId = :sessionId")
    suspend fun deleteBySession(sessionId: String)

    @Query("SELECT COUNT(*) FROM session_message WHERE sessionId = :sessionId")
    suspend fun countBySession(sessionId: String): Int

    @Query("SELECT * FROM session_message WHERE sessionId = :sessionId AND type = 'assistant' AND roundMark = 0 ORDER BY timeCreated DESC LIMIT 1")
    suspend fun getLatestIncompleteAssistant(sessionId: String): SessionMessageEntity?

    @Query("UPDATE session_message SET roundMark = 0 WHERE type = 'assistant' AND roundMark = 1 AND completedAt IS NULL")
    suspend fun fixRunningAssistantRoundMark()

    @Transaction
    suspend fun replaceAllForSession(sessionId: String, messages: List<SessionMessageEntity>) {
        deleteBySession(sessionId)
        upsertAll(messages)
    }
}

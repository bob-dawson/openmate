package com.openmate.core.database.dao

import androidx.room.*
import com.openmate.core.database.entity.SessionMessageFullContentEntity

@Dao
interface SessionMessageFullContentDao {
    @Query("SELECT * FROM session_message_full_content WHERE messageId = :messageId")
    suspend fun get(messageId: String): SessionMessageFullContentEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: SessionMessageFullContentEntity)

    @Query("DELETE FROM session_message_full_content WHERE messageId = :messageId")
    suspend fun delete(messageId: String)
}

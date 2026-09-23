package com.openmate.core.database.dao

import androidx.room.*
import com.openmate.core.database.entity.SyncStateEntity

@Dao
interface SyncStateDao {
    @Query("SELECT * FROM sync_state WHERE sessionId = :sessionId")
    suspend fun get(sessionId: String): SyncStateEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: SyncStateEntity)

    @Query("DELETE FROM sync_state WHERE sessionId = :sessionId")
    suspend fun delete(sessionId: String)
}

package com.openmate.core.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.openmate.core.database.entity.SessionEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface SessionDao {
    @Query("SELECT * FROM SessionEntity WHERE id = :id")
    fun observeById(id: String): Flow<SessionEntity?>

    @Query("SELECT * FROM SessionEntity WHERE id = :id")
    suspend fun getById(id: String): SessionEntity?

    @Query("SELECT * FROM SessionEntity WHERE directory = :dir AND parentID IS NULL ORDER BY updatedAt DESC")
    fun observeByDirectory(dir: String): Flow<List<SessionEntity>>

    @Query("SELECT * FROM SessionEntity WHERE parentID IS NULL ORDER BY updatedAt DESC")
    fun observeAll(): Flow<List<SessionEntity>>

    @Query("SELECT * FROM SessionEntity WHERE parentID IS NULL ORDER BY updatedAt DESC")
    suspend fun getAll(): List<SessionEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(session: SessionEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(sessions: List<SessionEntity>)

    @Query("DELETE FROM SessionEntity WHERE id = :id")
    suspend fun delete(id: String)

    @Query("UPDATE SessionEntity SET status = :status WHERE id = :id")
    suspend fun updateStatus(id: String, status: String?)

    @Query("UPDATE SessionEntity SET syncAnchor = :anchor WHERE id = :id")
    suspend fun updateSyncAnchor(id: String, anchor: String?)

    @Query("SELECT syncAnchor FROM SessionEntity WHERE id = :id")
    suspend fun getSyncAnchor(id: String): String?
}

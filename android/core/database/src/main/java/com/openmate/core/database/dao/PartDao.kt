package com.openmate.core.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.openmate.core.database.entity.PartEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface PartDao {
    @Query("SELECT * FROM PartEntity WHERE id = :id")
    suspend fun getById(id: String): PartEntity?

    @Query("SELECT * FROM PartEntity WHERE messageID = :mid ORDER BY sequence")
    suspend fun getByMessage(mid: String): List<PartEntity>

    @Query("SELECT * FROM PartEntity WHERE messageID IN (:messageIDs) ORDER BY sequence")
    suspend fun getByMessages(messageIDs: List<String>): List<PartEntity>

    @Query("SELECT * FROM PartEntity WHERE sessionID = :sid ORDER BY messageID, sequence")
    fun observeBySession(sid: String): Flow<List<PartEntity>>

    @Query("SELECT * FROM PartEntity WHERE messageID = :mid ORDER BY sequence")
    fun observeByMessage(mid: String): Flow<List<PartEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(part: PartEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(parts: List<PartEntity>)

    @Query("DELETE FROM PartEntity WHERE id = :id")
    suspend fun delete(id: String)

    @Query("DELETE FROM PartEntity WHERE messageID = :mid")
    suspend fun deleteByMessage(mid: String)
}

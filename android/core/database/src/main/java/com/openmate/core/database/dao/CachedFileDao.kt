package com.openmate.core.database.dao

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.openmate.core.database.entity.CachedFileEntity

@Dao
interface CachedFileDao {
    @Query("SELECT * FROM CachedFileEntity WHERE remotePath = :remotePath AND profileId = :profileId LIMIT 1")
    suspend fun getByRemotePath(remotePath: String, profileId: String): CachedFileEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(entity: CachedFileEntity)

    @Delete
    suspend fun delete(entity: CachedFileEntity)

    @Query("DELETE FROM CachedFileEntity WHERE profileId = :profileId")
    suspend fun deleteByProfile(profileId: String)

    @Query("DELETE FROM CachedFileEntity")
    suspend fun deleteAll()

    @Query("SELECT COALESCE(SUM(fileSize), 0) FROM CachedFileEntity")
    suspend fun totalCacheSize(): Long

    @Query("SELECT * FROM CachedFileEntity")
    suspend fun getAll(): List<CachedFileEntity>
}

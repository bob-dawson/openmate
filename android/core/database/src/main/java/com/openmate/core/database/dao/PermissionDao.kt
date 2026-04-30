package com.openmate.core.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.openmate.core.database.entity.PermissionEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface PermissionDao {
    @Query("SELECT * FROM PermissionEntity ORDER BY id")
    fun observeAll(): Flow<List<PermissionEntity>>

    @Query("SELECT * FROM PermissionEntity ORDER BY id")
    suspend fun getAll(): List<PermissionEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(permission: PermissionEntity)

    @Query("DELETE FROM PermissionEntity WHERE id = :id")
    suspend fun delete(id: String)

    @Query("DELETE FROM PermissionEntity")
    suspend fun deleteAll()
}

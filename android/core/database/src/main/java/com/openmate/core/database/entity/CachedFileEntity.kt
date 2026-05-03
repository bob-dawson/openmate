package com.openmate.core.database.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "CachedFileEntity")
data class CachedFileEntity(
    @PrimaryKey
    val remotePath: String,
    val localPath: String,
    val filename: String,
    val fileSize: Long,
    val modifiedTime: Long,
    val profileId: String,
    val cachedAt: Long = System.currentTimeMillis(),
)

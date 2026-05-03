package com.openmate.core.database

import androidx.room.Database
import androidx.room.RoomDatabase
import com.openmate.core.database.dao.CachedFileDao
import com.openmate.core.database.entity.CachedFileEntity

@Database(
    entities = [CachedFileEntity::class],
    version = 1,
    exportSchema = false,
)
abstract class CacheDatabase : RoomDatabase() {
    abstract fun cachedFileDao(): CachedFileDao
}

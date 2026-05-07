package com.openmate.core.database

import androidx.room.Database
import androidx.room.RoomDatabase
import com.openmate.core.database.dao.SessionDao
import com.openmate.core.database.dao.SessionMessageDao
import com.openmate.core.database.dao.SessionMessageFullContentDao
import com.openmate.core.database.dao.SyncStateDao
import com.openmate.core.database.entity.SessionEntity
import com.openmate.core.database.entity.SessionMessageEntity
import com.openmate.core.database.entity.SessionMessageFullContentEntity
import com.openmate.core.database.entity.SyncStateEntity

@Database(
    entities = [
        SessionEntity::class,
        SyncStateEntity::class,
        SessionMessageEntity::class,
        SessionMessageFullContentEntity::class,
    ],
    version = 10,
    exportSchema = true,
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun sessionDao(): SessionDao
    abstract fun syncStateDao(): SyncStateDao
    abstract fun sessionMessageDao(): SessionMessageDao
    abstract fun sessionMessageFullContentDao(): SessionMessageFullContentDao
}

package com.openmate.core.database

import androidx.room.Database
import androidx.room.RoomDatabase
import com.openmate.core.database.dao.SessionDao
import com.openmate.core.database.dao.SessionMessageDao
import com.openmate.core.database.dao.SessionMessageFullContentDao
import com.openmate.core.database.dao.SyncStateDao
import com.openmate.core.database.dao.TodoDao
import com.openmate.core.database.entity.SessionEntity
import com.openmate.core.database.entity.SessionMessageEntity
import com.openmate.core.database.entity.SessionMessageFullContentEntity
import com.openmate.core.database.entity.SyncStateEntity
import com.openmate.core.database.entity.TodoEntity

@Database(
    entities = [
        SessionEntity::class,
        SessionMessageEntity::class,
        SessionMessageFullContentEntity::class,
        SyncStateEntity::class,
        TodoEntity::class,
    ],
    version = 2,
    exportSchema = true,
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun sessionDao(): SessionDao
    abstract fun sessionMessageDao(): SessionMessageDao
    abstract fun sessionMessageFullContentDao(): SessionMessageFullContentDao
    abstract fun syncStateDao(): SyncStateDao
    abstract fun todoDao(): TodoDao
}

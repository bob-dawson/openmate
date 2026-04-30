package com.openmate.core.database

import androidx.room.Database
import androidx.room.RoomDatabase
import com.openmate.core.database.dao.MessageDao
import com.openmate.core.database.dao.PartDao
import com.openmate.core.database.dao.PermissionDao
import com.openmate.core.database.dao.QuestionDao
import com.openmate.core.database.dao.SessionDao
import com.openmate.core.database.dao.TodoDao
import com.openmate.core.database.entity.MessageEntity
import com.openmate.core.database.entity.PartEntity
import com.openmate.core.database.entity.PermissionEntity
import com.openmate.core.database.entity.QuestionEntity
import com.openmate.core.database.entity.SessionEntity
import com.openmate.core.database.entity.TodoEntity

@Database(
    entities = [
        SessionEntity::class,
        MessageEntity::class,
        PartEntity::class,
        PermissionEntity::class,
        QuestionEntity::class,
        TodoEntity::class,
    ],
    version = 8,
    exportSchema = true,
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun sessionDao(): SessionDao
    abstract fun messageDao(): MessageDao
    abstract fun partDao(): PartDao
    abstract fun permissionDao(): PermissionDao
    abstract fun questionDao(): QuestionDao
    abstract fun todoDao(): TodoDao
}

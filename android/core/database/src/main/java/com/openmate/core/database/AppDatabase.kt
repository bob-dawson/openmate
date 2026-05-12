package com.openmate.core.database

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
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

val MIGRATION_15_16 = object : Migration(15, 16) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE SessionEntity ADD COLUMN modelProviderID TEXT")
        db.execSQL("ALTER TABLE SessionEntity ADD COLUMN modelID TEXT")
        db.execSQL("ALTER TABLE SessionEntity ADD COLUMN modelName TEXT")
        db.execSQL("ALTER TABLE session_message ADD COLUMN roundMark INTEGER NOT NULL DEFAULT 1")
    }
}

val MIGRATION_16_17 = object : Migration(16, 17) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS index_session_message_sessionId_timeCreated ON session_message(sessionId, timeCreated)",
        )
    }
}

val MIGRATION_17_18 = object : Migration(17, 18) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE SessionEntity DROP COLUMN syncAnchor")
    }
}

@Database(
    entities = [
        SessionEntity::class,
        SessionMessageEntity::class,
        SessionMessageFullContentEntity::class,
        SyncStateEntity::class,
        TodoEntity::class,
    ],
    version = 18,
    exportSchema = true,
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun sessionDao(): SessionDao
    abstract fun sessionMessageDao(): SessionMessageDao
    abstract fun sessionMessageFullContentDao(): SessionMessageFullContentDao
    abstract fun syncStateDao(): SyncStateDao
    abstract fun todoDao(): TodoDao
}

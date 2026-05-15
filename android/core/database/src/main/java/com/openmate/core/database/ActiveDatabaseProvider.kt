package com.openmate.core.database

import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ActiveDatabaseProvider @Inject constructor(
    private val factory: DatabaseFactory,
) {
    @Volatile
    private var currentDb: AppDatabase? = null

    @Volatile
    private var currentProfileId: String? = null

    @Synchronized
    fun setActive(profileId: String): AppDatabase {
        if (currentProfileId == profileId && currentDb != null) {
            return currentDb!!
        }
        currentDb?.let { db ->
            try {
                db.openHelper.writableDatabase.execSQL("PRAGMA wal_checkpoint(TRUNCATE)")
            } catch (_: Exception) {}
            db.close()
        }
        currentProfileId = profileId
        val db = factory.create(profileId)
        currentDb = db
        return db
    }

    @Synchronized
    fun getActive(): AppDatabase {
        return currentDb ?: throw IllegalStateException("No active database. Call setActive(profileId) first.")
    }

    @Synchronized
    fun clearActive() {
        currentDb?.let { db ->
            try {
                db.openHelper.writableDatabase.execSQL("PRAGMA wal_checkpoint(TRUNCATE)")
            } catch (_: Exception) {}
            db.close()
        }
        currentDb = null
        currentProfileId = null
    }

    fun getActiveProfileId(): String? = currentProfileId

    fun getDatabaseFile(profileId: String): java.io.File = factory.getDatabasePath(profileId)
}

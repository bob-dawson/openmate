package com.openmate.core.database

import android.content.Context
import android.content.SharedPreferences
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ActiveDatabaseProvider @Inject constructor(
    @ApplicationContext private val appContext: Context,
    private val factory: DatabaseFactory,
) {
    companion object {
        private const val KEY_ACTIVE_PROFILE_ID = "active_profile_id"
    }

    @Volatile
    private var currentDb: AppDatabase? = null

    @Volatile
    private var currentProfileId: String? = null

    private val prefs: SharedPreferences by lazy {
        appContext.getSharedPreferences("active_db", Context.MODE_PRIVATE)
    }

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
        prefs.edit().putString(KEY_ACTIVE_PROFILE_ID, profileId).apply()
        val db = factory.create(profileId)
        currentDb = db
        return db
    }

    @Synchronized
    fun getActive(): AppDatabase {
        currentDb?.let { return it }
        val savedId = prefs.getString(KEY_ACTIVE_PROFILE_ID, null)
        if (savedId != null) {
            return setActive(savedId)
        }
        throw IllegalStateException("No active database. Call setActive(profileId) first.")
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
        prefs.edit().remove(KEY_ACTIVE_PROFILE_ID).apply()
    }

    fun getActiveProfileId(): String? = currentProfileId ?: prefs.getString(KEY_ACTIVE_PROFILE_ID, null)

    fun getDatabaseFile(profileId: String): java.io.File = factory.getDatabasePath(profileId)
}
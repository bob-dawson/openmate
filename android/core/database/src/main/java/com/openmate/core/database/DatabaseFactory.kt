package com.openmate.core.database

import android.content.Context
import androidx.room.Room
import java.io.File

class DatabaseFactory(private val context: Context) {

    fun create(profileId: String): AppDatabase {
        val sanitizedName = profileId.replace(Regex("[^a-zA-Z0-9_]"), "_")
        val dbName = "instance_$sanitizedName"
        return Room.databaseBuilder(
            context,
            AppDatabase::class.java,
            dbName,
        ).fallbackToDestructiveMigration(dropAllTables = true)
            .build()
    }

    fun delete(context: Context, profileId: String) {
        val sanitizedName = profileId.replace(Regex("[^a-zA-Z0-9_]"), "_")
        val dbName = "instance_$sanitizedName"
        val dbFile = context.getDatabasePath(dbName)
        if (dbFile.exists()) {
            dbFile.delete()
        }
        val walFile = File(dbFile.path + "-wal")
        if (walFile.exists()) walFile.delete()
        val shmFile = File(dbFile.path + "-shm")
        if (shmFile.exists()) shmFile.delete()
    }
}

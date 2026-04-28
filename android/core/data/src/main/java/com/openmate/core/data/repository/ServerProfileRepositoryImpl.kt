package com.openmate.core.data.repository

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.openmate.core.domain.model.ServerProfile
import com.openmate.core.domain.repository.ServerProfileRepository
import com.openmate.core.database.DatabaseFactory
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import javax.inject.Inject
import javax.inject.Singleton

private val Context.profileDataStore: DataStore<Preferences> by preferencesDataStore(name = "server_profiles")

@Singleton
class ServerProfileRepositoryImpl @Inject constructor(
    @ApplicationContext private val context: Context,
    private val databaseFactory: DatabaseFactory,
) : ServerProfileRepository {

    private val json = Json { ignoreUnknownKeys = true }
    private val key = stringSetPreferencesKey("profiles")

    override fun observeAll(): Flow<List<ServerProfile>> {
        return context.profileDataStore.data.map { prefs ->
            val set = prefs[key] ?: emptySet()
            set.mapNotNull { json.decodeFromString<ServerProfile>(it) }
        }
    }

    override suspend fun getAll(): List<ServerProfile> {
        return context.profileDataStore.data.map { prefs ->
            val set = prefs[key] ?: emptySet()
            set.mapNotNull { json.decodeFromString<ServerProfile>(it) }
        }.first()
    }

    override suspend fun getById(id: String): ServerProfile? {
        return getAll().find { it.id == id }
    }

    override suspend fun save(profile: ServerProfile) {
        context.profileDataStore.edit { prefs ->
            val existing = prefs[key]?.toMutableSet() ?: mutableSetOf()
            existing.removeIf { json.decodeFromString<ServerProfile>(it).id == profile.id }
            existing.add(json.encodeToString(profile))
            prefs[key] = existing
        }
    }

    override suspend fun delete(id: String) {
        context.profileDataStore.edit { prefs ->
            val existing = prefs[key]?.toMutableSet() ?: return@edit
            existing.removeIf { json.decodeFromString<ServerProfile>(it).id == id }
            prefs[key] = existing
        }
        databaseFactory.delete(context, id)
    }
}

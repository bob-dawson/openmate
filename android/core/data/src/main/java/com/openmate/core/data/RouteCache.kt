package com.openmate.core.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

private val Context.routeCacheDataStore: DataStore<Preferences> by preferencesDataStore(name = "route_cache")

@Singleton
class RouteCache @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    suspend fun get(profileId: String): CachedRoute? = withContext(Dispatchers.IO) {
        val key = stringPreferencesKey("route_$profileId")
        val value = context.routeCacheDataStore.data.map { it[key] }.first()
        when (value) {
            "direct" -> CachedRoute.DIRECT
            "gateway" -> CachedRoute.GATEWAY
            else -> null
        }
    }

    suspend fun setDirect(profileId: String) = withContext(Dispatchers.IO) {
        val key = stringPreferencesKey("route_$profileId")
        context.routeCacheDataStore.edit { it[key] = "direct" }
    }

    suspend fun setGateway(profileId: String) = withContext(Dispatchers.IO) {
        val key = stringPreferencesKey("route_$profileId")
        context.routeCacheDataStore.edit { it[key] = "gateway" }
    }

    suspend fun clear(profileId: String) = withContext(Dispatchers.IO) {
        val key = stringPreferencesKey("route_$profileId")
        context.routeCacheDataStore.edit { it.remove(key) }
    }
}

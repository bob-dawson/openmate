package com.openmate.core.network

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

private val Context.tokenDataStore: DataStore<Preferences> by preferencesDataStore(name = "bridge_tokens")

@Singleton
class TokenStore @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    private val scope = CoroutineScope(Dispatchers.IO)

    @Volatile
    private var activeProfileId: String? = null

    @Volatile
    var activeToken: String? = null
        private set

    suspend fun setActiveProfileId(profileId: String?) {
        activeProfileId = profileId
        activeToken = null
        if (profileId != null) {
            activeToken = getToken(profileId)
        }
    }

    suspend fun getToken(profileId: String): String? {
        val key = stringPreferencesKey("token_$profileId")
        return context.tokenDataStore.data.map { prefs -> prefs[key] }.first()
    }

    suspend fun saveToken(profileId: String, token: String) {
        val key = stringPreferencesKey("token_$profileId")
        context.tokenDataStore.edit { prefs -> prefs[key] = token }
        if (activeProfileId == profileId) {
            activeToken = token
        }
    }

    suspend fun clearToken(profileId: String) {
        val key = stringPreferencesKey("token_$profileId")
        context.tokenDataStore.edit { prefs -> prefs.remove(key) }
        if (activeProfileId == profileId) {
            activeToken = null
        }
    }
}

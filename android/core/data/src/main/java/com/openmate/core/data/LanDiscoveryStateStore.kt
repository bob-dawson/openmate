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

private val Context.lanDiscoveryDataStore: DataStore<Preferences> by preferencesDataStore(name = "lan_discovery")

@Singleton
open class LanDiscoveryStateStore @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    data class State(
        val fingerprint: String = "",
        val candidatesSignature: String = "",
        val failures: Int = 0,
        val lastProbeAt: Long = 0L,
        val lastGatewaySeenAt: Long = 0L,
    )

    open suspend fun load(profileId: String): State = withContext(Dispatchers.IO) {
        val key = stringPreferencesKey("lan_discovery_$profileId")
        val raw = context.lanDiscoveryDataStore.data.map { it[key] }.first() ?: return@withContext State()
        val parts = raw.split(SEPARATOR)
        if (parts.size != 5) return@withContext State()
        State(
            fingerprint = parts[0],
            candidatesSignature = parts[1],
            failures = parts[2].toIntOrNull() ?: 0,
            lastProbeAt = parts[3].toLongOrNull() ?: 0L,
            lastGatewaySeenAt = parts[4].toLongOrNull() ?: 0L,
        )
    }

    open suspend fun save(profileId: String, state: State) {
        withContext(Dispatchers.IO) {
            val key = stringPreferencesKey("lan_discovery_$profileId")
            val raw = listOf(
                state.fingerprint,
                state.candidatesSignature,
                state.failures.toString(),
                state.lastProbeAt.toString(),
                state.lastGatewaySeenAt.toString(),
            ).joinToString(SEPARATOR)
            context.lanDiscoveryDataStore.edit { it[key] = raw }
        }
    }

    open suspend fun clear(profileId: String) {
        withContext(Dispatchers.IO) {
            val key = stringPreferencesKey("lan_discovery_$profileId")
            context.lanDiscoveryDataStore.edit { it.remove(key) }
        }
    }

    private companion object {
        const val SEPARATOR = "\u001F"
    }
}

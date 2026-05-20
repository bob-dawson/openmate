package com.openmate.core.network

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

private val Context.installationDataStore: DataStore<Preferences> by preferencesDataStore(name = "installation")

@Singleton
class InstallationIdProvider @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    private val key = stringPreferencesKey("installation_id")

    suspend fun getInstallationId(): String {
        val existing = context.installationDataStore.data.map { it[key] }.first()
        if (existing != null) return existing

        val newId = UUID.randomUUID().toString()
        context.installationDataStore.edit { it[key] = newId }
        return newId
    }
}

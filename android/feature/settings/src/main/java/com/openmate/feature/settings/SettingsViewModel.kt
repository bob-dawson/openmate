package com.openmate.feature.settings

import android.content.Context
import android.content.SharedPreferences
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.openmate.core.domain.model.ServerProfile
import com.openmate.core.domain.repository.ServerProfileRepository
import com.openmate.core.domain.repository.SseEventRepository
import com.openmate.core.database.ActiveDatabaseProvider
import dagger.hilt.android.lifecycle.HiltViewModel
import com.openmate.feature.settings.R
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.File
import javax.inject.Inject

private const val PREFS_NAME = "settings"
private const val KEY_SHOW_REASONING = "show_reasoning"

@HiltViewModel
class SettingsViewModel @Inject constructor(
    @ApplicationContext private val appContext: Context,
    private val profileRepository: ServerProfileRepository,
    private val sseEventRepository: SseEventRepository,
    private val dbProvider: ActiveDatabaseProvider,
) : ViewModel() {

    private val prefs: SharedPreferences = appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private val _activeProfile = MutableStateFlow<ServerProfile?>(null)
    val activeProfile: StateFlow<ServerProfile?> = _activeProfile.asStateFlow()

    private val _cacheSize = MutableStateFlow(appContext.getString(R.string.calculating))
    val cacheSize: StateFlow<String> = _cacheSize.asStateFlow()

    private val _cacheFileCount = MutableStateFlow(0)
    val cacheFileCount: StateFlow<Int> = _cacheFileCount.asStateFlow()

    private val _showReasoning = MutableStateFlow(prefs.getBoolean(KEY_SHOW_REASONING, true))
    val showReasoning: StateFlow<Boolean> = _showReasoning.asStateFlow()

    private val cacheDir get() = File(appContext.cacheDir, "file_cache")

    init {
        loadActiveProfile()
        refreshCacheInfo()
    }

    fun refreshCacheInfo() {
        viewModelScope.launch(Dispatchers.IO) {
            if (!cacheDir.exists()) {
                _cacheSize.value = "0 B"
                _cacheFileCount.value = 0
                return@launch
            }
            var totalBytes = 0L
            var count = 0
            cacheDir.walkTopDown().forEach { file ->
                if (file.isFile) {
                    totalBytes += file.length()
                    count++
                }
            }
            _cacheSize.value = formatCacheSize(totalBytes)
            _cacheFileCount.value = count
        }
    }

    private fun formatCacheSize(bytes: Long): String {
        return when {
            bytes < 1024 -> "$bytes B"
            bytes < 1024 * 1024 -> "${bytes / 1024} KB"
            else -> "${"%.1f".format(bytes / (1024.0 * 1024.0))} MB"
        }
    }

    private fun loadActiveProfile() {
        viewModelScope.launch(Dispatchers.IO) {
            val profileId = dbProvider.getActiveProfileId()
            if (profileId != null) {
                _activeProfile.value = profileRepository.getById(profileId)
            }
        }
    }

    fun clearCache() {
        viewModelScope.launch(Dispatchers.IO) {
            if (cacheDir.exists()) {
                cacheDir.walkTopDown().forEach { file ->
                    if (file.isFile) file.delete()
                }
                cacheDir.walkBottomUp().forEach { dir ->
                    if (dir != cacheDir && dir.listFiles().isNullOrEmpty()) dir.delete()
                }
            }
            refreshCacheInfo()
        }
    }

    fun disconnect() {
        sseEventRepository.disconnect()
        dbProvider.clearActive()
    }

    fun setShowReasoning(show: Boolean) {
        _showReasoning.value = show
        prefs.edit().putBoolean(KEY_SHOW_REASONING, show).apply()
    }
}

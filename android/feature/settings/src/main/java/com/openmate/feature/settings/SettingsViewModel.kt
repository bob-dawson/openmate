package com.openmate.feature.settings

import android.content.SharedPreferences
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.openmate.core.domain.model.ServerProfile
import com.openmate.core.domain.repository.ServerProfileRepository
import com.openmate.core.domain.repository.FileCacheRepository
import com.openmate.core.domain.repository.SseEventRepository
import com.openmate.core.database.ActiveDatabaseProvider
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val profileRepository: ServerProfileRepository,
    private val sseEventRepository: SseEventRepository,
    private val dbProvider: ActiveDatabaseProvider,
    private val fileCacheRepo: FileCacheRepository,
    private val prefs: SharedPreferences,
) : ViewModel() {

    private val _activeProfile = MutableStateFlow<ServerProfile?>(null)
    val activeProfile: StateFlow<ServerProfile?> = _activeProfile.asStateFlow()

    private val _notifyPermissions = MutableStateFlow(prefs.getBoolean("notify_permissions", true))
    val notifyPermissions: StateFlow<Boolean> = _notifyPermissions.asStateFlow()

    private val _notifyQuestions = MutableStateFlow(prefs.getBoolean("notify_questions", true))
    val notifyQuestions: StateFlow<Boolean> = _notifyQuestions.asStateFlow()

    private val _notifyComplete = MutableStateFlow(prefs.getBoolean("notify_complete", false))
    val notifyComplete: StateFlow<Boolean> = _notifyComplete.asStateFlow()

    private val _notifyErrors = MutableStateFlow(prefs.getBoolean("notify_errors", true))
    val notifyErrors: StateFlow<Boolean> = _notifyErrors.asStateFlow()

    private val _autoAllowRead = MutableStateFlow(prefs.getBoolean("auto_allow_read", true))
    val autoAllowRead: StateFlow<Boolean> = _autoAllowRead.asStateFlow()

    private val _autoAllowGrep = MutableStateFlow(prefs.getBoolean("auto_allow_grep", true))
    val autoAllowGrep: StateFlow<Boolean> = _autoAllowGrep.asStateFlow()

    private val _autoAllowBash = MutableStateFlow(prefs.getBoolean("auto_allow_bash", false))
    val autoAllowBash: StateFlow<Boolean> = _autoAllowBash.asStateFlow()

    private val _cacheSize = MutableStateFlow("计算中...")
    val cacheSize: StateFlow<String> = _cacheSize.asStateFlow()

    private val _cachePolicyLabel = MutableStateFlow("LRU 最近 500 条")
    val cachePolicyLabel: StateFlow<String> = _cachePolicyLabel.asStateFlow()

    init {
        loadActiveProfile()
        refreshCacheSize()
    }

    private fun refreshCacheSize() {
        viewModelScope.launch(Dispatchers.IO) {
            val bytes = fileCacheRepo.totalCacheSize()
            _cacheSize.value = formatCacheSize(bytes)
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

    fun setNotifyPermissions(value: Boolean) {
        _notifyPermissions.value = value
        prefs.edit().putBoolean("notify_permissions", value).apply()
    }

    fun setNotifyQuestions(value: Boolean) {
        _notifyQuestions.value = value
        prefs.edit().putBoolean("notify_questions", value).apply()
    }

    fun setNotifyComplete(value: Boolean) {
        _notifyComplete.value = value
        prefs.edit().putBoolean("notify_complete", value).apply()
    }

    fun setNotifyErrors(value: Boolean) {
        _notifyErrors.value = value
        prefs.edit().putBoolean("notify_errors", value).apply()
    }

    fun setAutoAllowRead(value: Boolean) {
        _autoAllowRead.value = value
        prefs.edit().putBoolean("auto_allow_read", value).apply()
    }

    fun setAutoAllowGrep(value: Boolean) {
        _autoAllowGrep.value = value
        prefs.edit().putBoolean("auto_allow_grep", value).apply()
    }

    fun setAutoAllowBash(value: Boolean) {
        _autoAllowBash.value = value
        prefs.edit().putBoolean("auto_allow_bash", value).apply()
    }

    fun clearCache() {
        viewModelScope.launch(Dispatchers.IO) {
            fileCacheRepo.clearAllCache()
            _cacheSize.value = "0 B"
        }
    }

    fun disconnect() {
        sseEventRepository.disconnect()
        dbProvider.clearActive()
    }
}

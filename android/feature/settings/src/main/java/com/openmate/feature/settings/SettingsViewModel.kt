package com.openmate.feature.settings

import android.content.Context
import android.content.SharedPreferences
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.openmate.core.domain.model.ServerProfile
import com.openmate.core.domain.repository.ServerProfileRepository
import com.openmate.core.domain.repository.SseEventRepository
import com.openmate.core.database.ActiveDatabaseProvider
import com.openmate.core.network.OpencodeApiClient
import com.openmate.core.network.dto.OpencodeVersionResponse
import dagger.hilt.android.lifecycle.HiltViewModel
import com.openmate.feature.settings.R
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
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
    private val apiClient: OpencodeApiClient,
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

    private val _opencodeVersion = MutableStateFlow<OpencodeVersionResponse?>(null)
    val opencodeVersion: StateFlow<OpencodeVersionResponse?> = _opencodeVersion.asStateFlow()

    private val _isUpgrading = MutableStateFlow(false)
    val isUpgrading: StateFlow<Boolean> = _isUpgrading.asStateFlow()

    private val _isCheckingVersion = MutableStateFlow(false)
    val isCheckingVersion: StateFlow<Boolean> = _isCheckingVersion.asStateFlow()

    private val _isRestarting = MutableStateFlow(false)
    val isRestarting: StateFlow<Boolean> = _isRestarting.asStateFlow()

    private val _upgradeError = MutableStateFlow<String?>(null)
    val upgradeError: StateFlow<String?> = _upgradeError.asStateFlow()

    private val cacheDir get() = File(appContext.cacheDir, "file_cache")

    init {
        loadActiveProfile()
        refreshCacheInfo()
        checkVersion()
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

    fun checkVersion() {
        viewModelScope.launch(Dispatchers.IO) {
            _isCheckingVersion.value = true
            try {
                _opencodeVersion.value = apiClient.bridgeOpencodeVersion()
                _upgradeError.value = null
            } catch (_: Exception) {} finally {
                _isCheckingVersion.value = false
            }
        }
    }

    fun upgradeOpencode() {
        viewModelScope.launch(Dispatchers.IO) {
            if (_isUpgrading.value) return@launch
            _isUpgrading.value = true
            _upgradeError.value = null
            try {
                val result = apiClient.bridgeOpencodeUpgrade()
                if (result.status == "in_progress") {
                    _upgradeError.value = "Upgrade already in progress"
                    _isUpgrading.value = false
                    return@launch
                }
                repeat(40) {
                    delay(3000)
                    try {
                        val status = apiClient.bridgeOpencodeUpgradeStatus()
                        if (!status.upgrading) {
                            _opencodeVersion.value = apiClient.bridgeOpencodeVersion()
                            _upgradeError.value = null
                            _isUpgrading.value = false
                            return@launch
                        }
                    } catch (_: Exception) { }
                }
                _upgradeError.value = "Upgrade timed out"
            } catch (e: Exception) {
                _upgradeError.value = e.message ?: "Upgrade failed"
            }
            _isUpgrading.value = false
        }
    }

    fun restartOpencode() {
        viewModelScope.launch(Dispatchers.IO) {
            _isRestarting.value = true
            try {
                apiClient.bridgeOpencodeRestart()
            } catch (e: Exception) {
                _upgradeError.value = e.message ?: "Restart failed"
            } finally {
                _isRestarting.value = false
            }
        }
    }
}

package com.openmate.feature.settings

import android.content.Context
import android.content.SharedPreferences
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.openmate.core.domain.model.ServerProfile
import com.openmate.core.domain.repository.ServerProfileRepository
import com.openmate.core.domain.repository.ConnectionRepository
import com.openmate.core.domain.repository.SseEventRepository
import com.openmate.core.common.AppInfo
import com.openmate.core.common.FileOpener
import com.openmate.core.database.ActiveDatabaseProvider
import com.openmate.core.network.OpencodeApiClient
import com.openmate.core.network.ReleaseAssets
import com.openmate.core.network.VersionClient
import com.openmate.core.network.dto.ModuleVersion
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
private const val KEY_COMPACT_MODE = "compact_mode"

@HiltViewModel
class SettingsViewModel @Inject constructor(
    @ApplicationContext private val appContext: Context,
    private val profileRepository: ServerProfileRepository,
    private val connectionRepository: ConnectionRepository,
    private val sseEventRepository: SseEventRepository,
    private val dbProvider: ActiveDatabaseProvider,
    private val apiClient: OpencodeApiClient,
    private val versionClient: VersionClient,
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

    private val _compactMode = MutableStateFlow(prefs.getBoolean(KEY_COMPACT_MODE, false))
    val compactMode: StateFlow<Boolean> = _compactMode.asStateFlow()

    private val _gatewayEnabled = MutableStateFlow(true)
    val gatewayEnabled: StateFlow<Boolean> = _gatewayEnabled.asStateFlow()

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

    private val _updateMessage = MutableStateFlow<String?>(null)
    val updateMessage: StateFlow<String?> = _updateMessage.asStateFlow()

    fun clearUpdateMessage() {
        _updateMessage.value = null
    }

    data class AppUpdateInfo(
        val currentVersion: String,
        val latestVersion: String?,
        val hasUpdate: Boolean,
    )

    data class AppDownloadState(
        val isDownloading: Boolean = false,
        val progress: Int = 0,
        val error: String? = null,
    )

    private val _appUpdateInfo = MutableStateFlow<AppUpdateInfo?>(null)
    val appUpdateInfo: StateFlow<AppUpdateInfo?> = _appUpdateInfo.asStateFlow()

    private val _appDownloadState = MutableStateFlow(AppDownloadState())
    val appDownloadState: StateFlow<AppDownloadState> = _appDownloadState.asStateFlow()

    private var latestModuleVersion: ModuleVersion? = null

    data class BridgeUpdateInfo(
        val currentVersion: String,
        val latestVersion: String?,
        val hasUpdate: Boolean,
    )

    data class BridgeUpgradeState(
        val isDownloading: Boolean = false,
        val progress: Int = 0,
        val isApplying: Boolean = false,
        val error: String? = null,
    )

    private val _bridgeUpdateInfo = MutableStateFlow<BridgeUpdateInfo?>(null)
    val bridgeUpdateInfo: StateFlow<BridgeUpdateInfo?> = _bridgeUpdateInfo.asStateFlow()

    private val _bridgeUpgradeState = MutableStateFlow(BridgeUpgradeState())
    val bridgeUpgradeState: StateFlow<BridgeUpgradeState> = _bridgeUpgradeState.asStateFlow()

    private var latestBridgeVersion: ModuleVersion? = null

    private val cacheDir get() = File(appContext.cacheDir, "file_cache")

    init {
        loadActiveProfile()
        refreshCacheInfo()
        checkVersion()
        checkAppUpdate()
        checkBridgeUpdate()
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
                val profile = profileRepository.getById(profileId)
                _activeProfile.value = profile
                _gatewayEnabled.value = profile?.gatewayEnabled ?: true
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
        connectionRepository.disconnect()
    }

    fun setShowReasoning(show: Boolean) {
        _showReasoning.value = show
        prefs.edit().putBoolean(KEY_SHOW_REASONING, show).apply()
    }

    fun setCompactMode(enabled: Boolean) {
        _compactMode.value = enabled
        prefs.edit().putBoolean(KEY_COMPACT_MODE, enabled).apply()
    }

    fun setGatewayEnabled(enabled: Boolean) {
        _gatewayEnabled.value = enabled
        val profile = _activeProfile.value ?: return
        viewModelScope.launch(Dispatchers.IO) {
            profileRepository.save(profile.copy(gatewayEnabled = enabled))
            connectionRepository.notifyProfileUpdated(profile.copy(gatewayEnabled = enabled))
        }
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

    fun checkAppUpdate(userTriggered: Boolean = false) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val latest = versionClient.fetchAndroidVersion()
                latestModuleVersion = latest
                val current = AppInfo.versionName(appContext)
                val hasUpdate = latest != null && isNewer(latest.version, current)
                _appUpdateInfo.value = AppUpdateInfo(
                    currentVersion = current,
                    latestVersion = latest?.version,
                    hasUpdate = hasUpdate,
                )
                if (!hasUpdate && latest != null && userTriggered) {
                    _updateMessage.value = appContext.getString(R.string.app_up_to_date)
                }
            } catch (_: Exception) {
            }
        }
    }

    private fun isNewer(latest: String, current: String): Boolean {
        val a = latest.trimStart('v').split('.').mapNotNull { it.toIntOrNull() }
        val b = current.trimStart('v').split('.').mapNotNull { it.toIntOrNull() }
        for (i in 0 until maxOf(a.size, b.size)) {
            val x = a.getOrNull(i) ?: 0
            val y = b.getOrNull(i) ?: 0
            if (x != y) return x > y
        }
        return false
    }

    fun downloadAndInstallApp() {
        val info = _appUpdateInfo.value ?: return
        val tag = latestModuleVersion?.tag ?: return
        viewModelScope.launch(Dispatchers.IO) {
            if (_appDownloadState.value.isDownloading) return@launch
            _appDownloadState.value = AppDownloadState(isDownloading = true)
            try {
                val url = ReleaseAssets.apkUrl(tag)
                val destDir = File(appContext.cacheDir, "file_cache")
                val destFile = File(destDir, ReleaseAssets.apkFilename(tag))
                versionClient.downloadReleaseAsset(
                    url = url,
                    destFile = destFile,
                    onProgress = { downloaded, total ->
                        if (total > 0) {
                            _appDownloadState.value = _appDownloadState.value.copy(
                                progress = ((downloaded * 100) / total).toInt(),
                            )
                        }
                    },
                )
                _appDownloadState.value = AppDownloadState(isDownloading = false, progress = 100)
                FileOpener.installApk(appContext, destFile, destFile.name)
            } catch (e: Exception) {
                val destDir = File(appContext.cacheDir, "file_cache")
                val destFile = File(destDir, ReleaseAssets.apkFilename(tag))
                if (destFile.exists() && destFile.length() > 0) {
                    _appDownloadState.value = AppDownloadState(isDownloading = false, progress = 100)
                    FileOpener.installApk(appContext, destFile, destFile.name)
                } else {
                    _appDownloadState.value = AppDownloadState(
                        error = e.message ?: "Download failed",
                    )
                }
            }
        }
    }

    fun clearAppDownloadError() {
        _appDownloadState.value = AppDownloadState()
    }

    fun checkBridgeUpdate(userTriggered: Boolean = false) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val latest = versionClient.fetchBridgeVersion()
                latestBridgeVersion = latest
                val current = _opencodeVersion.value?.let { ver ->
                    apiClient.bridgeStatus().bridge.version
                } ?: "0.0.0"
                val hasUpdate = latest != null && isNewer(latest.version, current)
                _bridgeUpdateInfo.value = BridgeUpdateInfo(
                    currentVersion = current,
                    latestVersion = latest?.version,
                    hasUpdate = hasUpdate,
                )
                if (!hasUpdate && latest != null && userTriggered) {
                    _updateMessage.value = appContext.getString(R.string.bridge_up_to_date)
                }
            } catch (_: Exception) {
            }
        }
    }

    fun upgradeBridge() {
        if (_bridgeUpgradeState.value.isDownloading || _bridgeUpgradeState.value.isApplying) return
        val info = _bridgeUpdateInfo.value ?: return
        if (!info.hasUpdate) return

        viewModelScope.launch(Dispatchers.IO) {
            _bridgeUpgradeState.value = BridgeUpgradeState(isDownloading = true)
            try {
                apiClient.bridgeUpgradeDownload()

                val maxPolls = 120
                var downloaded = false
                for (i in 0 until maxPolls) {
                    delay(3000)
                    val status = apiClient.bridgeUpgradeStatus()
                    when (status.state) {
                        "downloading" -> {
                            _bridgeUpgradeState.value = BridgeUpgradeState(
                                isDownloading = true,
                                progress = status.progress.toInt(),
                            )
                        }
                        "downloaded" -> {
                            downloaded = true
                            break
                        }
                        "failed" -> {
                            _bridgeUpgradeState.value = BridgeUpgradeState(
                                error = appContext.getString(R.string.bridge_download_failed),
                            )
                            return@launch
                        }
                    }
                }

                if (!downloaded) {
                    _bridgeUpgradeState.value = BridgeUpgradeState(
                        error = appContext.getString(R.string.bridge_download_timeout),
                    )
                    return@launch
                }

                _bridgeUpgradeState.value = BridgeUpgradeState(isApplying = true)
                val oldVersion = _bridgeUpdateInfo.value?.currentVersion ?: ""
                apiClient.bridgeUpgradeApply()
                for (i in 0 until 3) {
                    delay(3000)
                    try {
                        val status = apiClient.bridgeStatus()
                        if (status.bridge.version != oldVersion) {
                            _bridgeUpgradeState.value = BridgeUpgradeState()
                            checkBridgeUpdate(true)
                            return@launch
                        }
                    } catch (_: Exception) {
                    }
                }
                _bridgeUpgradeState.value = BridgeUpgradeState()
            } catch (_: Exception) {
                _bridgeUpgradeState.value = BridgeUpgradeState(
                    error = appContext.getString(R.string.bridge_upgrade_failed),
                )
            }
        }
    }

    fun clearBridgeUpgradeError() {
        _bridgeUpgradeState.value = BridgeUpgradeState()
    }
}

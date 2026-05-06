package com.openmate.app

import com.openmate.core.database.ActiveDatabaseProvider
import com.openmate.core.domain.model.ConnectionStatus
import com.openmate.core.domain.model.ServerProfile
import com.openmate.core.domain.repository.ServerProfileRepository
import com.openmate.core.domain.repository.SessionRepository
import com.openmate.core.domain.repository.SseEventRepository
import com.openmate.core.network.OpencodeApiClient
import com.openmate.core.network.TokenStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ConnectionManager @Inject constructor(
    private val profileRepository: ServerProfileRepository,
    private val sseEventRepository: SseEventRepository,
    private val sessionRepository: SessionRepository,
    private val dbProvider: ActiveDatabaseProvider,
    private val apiClient: OpencodeApiClient,
    private val tokenStore: TokenStore,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val _connectionStatus = MutableStateFlow(ConnectionStatus.DISCONNECTED)
    val connectionStatus: StateFlow<ConnectionStatus> = _connectionStatus.asStateFlow()

    private val _activeProfile = MutableStateFlow<ServerProfile?>(null)
    val activeProfile: StateFlow<ServerProfile?> = _activeProfile.asStateFlow()

    private val _isConnected = MutableStateFlow(false)
    val isConnected: StateFlow<Boolean> = _isConnected.asStateFlow()

    private val _errorMessage = MutableStateFlow<String?>(null)
    val errorMessage: StateFlow<String?> = _errorMessage.asStateFlow()

    private val _needsRepairing = MutableStateFlow<String?>(null)
    val needsRepairing: StateFlow<String?> = _needsRepairing.asStateFlow()

    val client: OpencodeApiClient get() = apiClient

    init {
        scope.launch {
            sseEventRepository.observeConnectionStatus().collect { status ->
                _connectionStatus.value = status
                _isConnected.value = status == ConnectionStatus.CONNECTED
                if (status == ConnectionStatus.CONNECTED) {
                    sessionRepository.refreshSessionStatuses()
                }
                if (status == ConnectionStatus.ERROR) {
                    _errorMessage.value = "Connection lost"
                }
            }
        }
    }

    fun connect(profile: ServerProfile) {
        scope.launch {
            _connectionStatus.value = ConnectionStatus.CONNECTING
            _errorMessage.value = null
            _needsRepairing.value = null
            _activeProfile.value = profile
            
            tokenStore.setActiveProfileId(profile.id)

            dbProvider.setActive(profile.id)
            apiClient.baseUrl = "http://${profile.address}:${profile.port}"

            try {
                val status = apiClient.bridgeStatus()
                if (status.bridge.version.isBlank()) {
                    _connectionStatus.value = ConnectionStatus.NOT_BRIDGE
                    _errorMessage.value = "Not a Bridge server. Only Bridge connections are supported."
                    clearConnection()
                    return@launch
                }

                if (status.bridge.authEnabled) {
                    val token = tokenStore.getToken(profile.id)
                    if (token == null) {
                        _needsRepairing.value = profile.id
                        _connectionStatus.value = ConnectionStatus.DISCONNECTED
                        return@launch
                    }
                }
            } catch (e: Exception) {
                _connectionStatus.value = ConnectionStatus.NOT_BRIDGE
                _errorMessage.value = "Bridge not reachable: ${e.message}"
                clearConnection()
                return@launch
            }

            val updated = profile.copy(lastConnectedAt = System.currentTimeMillis())
            profileRepository.save(updated)

            try {
                sseEventRepository.connect(profile.address, profile.port, profile.password)
            } catch (e: Exception) {
                _connectionStatus.value = ConnectionStatus.ERROR
                _errorMessage.value = e.message ?: "Connection failed"
            }
        }
    }

    fun confirmRepairing(profileId: String, token: String) {
        scope.launch {
            tokenStore.saveToken(profileId, token)
            _needsRepairing.value = null
            _activeProfile.value?.let { connect(it) }
        }
    }

    fun clearNeedsRepairing() {
        _needsRepairing.value = null
    }

    fun clearError() {
        _errorMessage.value = null
    }

    fun disconnect() {
        sseEventRepository.disconnect()
        scope.launch { clearConnection() }
    }

    private suspend fun clearConnection() {
        dbProvider.clearActive()
        _activeProfile.value = null
        _isConnected.value = false
        _connectionStatus.value = ConnectionStatus.DISCONNECTED
        tokenStore.setActiveProfileId(null)
    }
}


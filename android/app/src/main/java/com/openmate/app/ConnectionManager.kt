package com.openmate.app

import com.openmate.core.database.ActiveDatabaseProvider
import com.openmate.core.domain.model.ConnectionStatus
import com.openmate.core.domain.model.ServerProfile
import com.openmate.core.domain.repository.ServerProfileRepository
import com.openmate.core.domain.repository.SseEventRepository
import com.openmate.core.network.OpencodeApiClient
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
    private val dbProvider: ActiveDatabaseProvider,
    private val apiClient: OpencodeApiClient,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val _connectionStatus = MutableStateFlow(ConnectionStatus.DISCONNECTED)
    val connectionStatus: StateFlow<ConnectionStatus> = _connectionStatus.asStateFlow()

    private val _activeProfile = MutableStateFlow<ServerProfile?>(null)
    val activeProfile: StateFlow<ServerProfile?> = _activeProfile.asStateFlow()

    private val _isConnected = MutableStateFlow(false)
    val isConnected: StateFlow<Boolean> = _isConnected.asStateFlow()

    init {
        scope.launch {
            sseEventRepository.observeConnectionStatus().collect { status ->
                _connectionStatus.value = status
                _isConnected.value = status == ConnectionStatus.CONNECTED
            }
        }
    }

    fun connect(profile: ServerProfile) {
        scope.launch {
            _connectionStatus.value = ConnectionStatus.CONNECTING
            _activeProfile.value = profile

            dbProvider.setActive(profile.id)
            apiClient.baseUrl = "http://${profile.address}:${profile.port}"

            val updated = profile.copy(lastConnectedAt = System.currentTimeMillis())
            profileRepository.save(updated)

            try {
                sseEventRepository.connect(profile.address, profile.port, profile.password)
            } catch (e: Exception) {
                _connectionStatus.value = ConnectionStatus.ERROR
            }
        }
    }

    fun disconnect() {
        sseEventRepository.disconnect()
        dbProvider.clearActive()
        _activeProfile.value = null
        _isConnected.value = false
        _connectionStatus.value = ConnectionStatus.DISCONNECTED
    }
}

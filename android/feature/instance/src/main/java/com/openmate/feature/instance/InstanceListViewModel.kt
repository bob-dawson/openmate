package com.openmate.feature.instance

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.openmate.core.database.ActiveDatabaseProvider
import com.openmate.core.domain.model.ConnectionStatus
import com.openmate.core.domain.model.ServerProfile
import com.openmate.core.domain.repository.ServerProfileRepository
import com.openmate.core.domain.repository.SessionRepository
import com.openmate.core.domain.repository.SseEventRepository
import com.openmate.core.data.sync.SyncSseHandler
import com.openmate.core.network.GatewayInterceptor
import com.openmate.core.network.OpencodeApiClient
import com.openmate.core.network.SyncSseClient
import com.openmate.core.network.TokenStore
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

data class ProfileWithStatus(
    val profile: ServerProfile,
    val status: ConnectionStatus = ConnectionStatus.DISCONNECTED,
)

@HiltViewModel
class InstanceListViewModel @Inject constructor(
    private val profileRepository: ServerProfileRepository,
    private val sessionRepository: SessionRepository,
    private val sseEventRepository: SseEventRepository,
    private val dbProvider: ActiveDatabaseProvider,
    private val apiClient: OpencodeApiClient,
    private val tokenStore: TokenStore,
    private val syncSseClient: SyncSseClient,
    private val syncSseHandler: SyncSseHandler,
) : ViewModel() {

    private val _profiles = MutableStateFlow<List<ProfileWithStatus>>(emptyList())
    val profiles: StateFlow<List<ProfileWithStatus>> = _profiles.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    init {
        observeCombined()
        autoReconnect()
        observeConnectionForStatusRefresh()
    }

    private fun observeConnectionForStatusRefresh() {
        viewModelScope.launch(Dispatchers.IO) {
            sseEventRepository.observeConnectionStatus().collect { status ->
                if (status == ConnectionStatus.CONNECTED) {
                    try {
                        sessionRepository.refreshSessionStatuses()
                    } catch (_: Exception) {}
                }
            }
        }
    }

    private fun autoReconnect() {
        viewModelScope.launch(Dispatchers.IO) {
            val profiles = profileRepository.getAll()
            if (profiles.size != 1) return@launch
            val profile = profiles.first()
            if (sseEventRepository.isConnectedTo(profile.address, profile.port)) return@launch
            try {
                tokenStore.setActiveProfileId(profile.id)
                dbProvider.setActive(profile.id)
                apiClient.baseUrl = "http://${profile.address}:${profile.port}"
                sseEventRepository.connect(profile.address, profile.port, profile.password)
                syncSseHandler.start()
                syncSseClient.connect(apiClient.baseUrl)
            } catch (_: Exception) {}
        }
    }

    private fun observeCombined() {
        viewModelScope.launch {
            profileRepository.observeAll()
                .combine(sseEventRepository.observeConnectionStatus()) { profiles, connectionStatus ->
                    val activeId = dbProvider.getActiveProfileId()
                    profiles.map { profile ->
                        val status = if (profile.id == activeId) connectionStatus
                                     else ConnectionStatus.DISCONNECTED
                        ProfileWithStatus(profile, status)
                    }
                }
                .collect { _profiles.value = it }
        }
    }

    fun connect(profile: ServerProfile, onNavigate: () -> Unit = {}) {
        onNavigate()
        if (sseEventRepository.isConnectedTo(profile.address, profile.port)) {
            syncSseHandler.start()
            viewModelScope.launch(Dispatchers.IO) { syncSseClient.connect(apiClient.baseUrl) }
            return
        }
        viewModelScope.launch(Dispatchers.IO) {
            val directUrl = "http://${profile.address}:${profile.port}"
            apiClient.baseUrl = directUrl

            var useGateway = false
            try {
                apiClient.bridgeStatus()
            } catch (e: Exception) {
                if (profile.instanceId.isNotEmpty() && isGatewayOnline(profile.instanceId)) {
                    useGateway = true
                    apiClient.baseUrl = "https://gateway.clawmate.net"
                }
            }

            try {
                tokenStore.setActiveProfileId(profile.id)
                dbProvider.setActive(profile.id)
                if (useGateway) {
                    sseEventRepository.connectViaGateway(apiClient.baseUrl)
                } else {
                    sseEventRepository.connect(profile.address, profile.port, profile.password)
                }
                syncSseHandler.start()
                syncSseClient.instanceId = if (useGateway) profile.instanceId else null
                syncSseClient.connect(apiClient.baseUrl)
                val updated = profile.copy(lastConnectedAt = System.currentTimeMillis())
                profileRepository.save(updated)
            } catch (e: Exception) {
                Log.w("InstanceListVM", "connect failed", e)
            }
        }
    }

    private fun isGatewayOnline(instanceId: String): Boolean {
        return try {
            val url = java.net.URL("https://gateway.clawmate.net/api/gateway/status?instance_id=$instanceId")
            val conn = url.openConnection() as java.net.HttpURLConnection
            conn.connectTimeout = 5000
            conn.readTimeout = 5000
            val success = conn.responseCode == 200
            conn.disconnect()
            success
        } catch (e: Exception) {
            false
        }
    }

    fun deleteProfile(id: String) {
        viewModelScope.launch(Dispatchers.IO) {
            if (id == dbProvider.getActiveProfileId()) {
                sseEventRepository.disconnect()
                dbProvider.clearActive()
            }
            profileRepository.delete(id)
        }
    }

    fun clearError() {
        _error.value = null
    }
}

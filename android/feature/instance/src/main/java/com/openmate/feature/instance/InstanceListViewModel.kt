package com.openmate.feature.instance

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.openmate.core.database.ActiveDatabaseProvider
import com.openmate.core.domain.model.ConnectionStatus
import com.openmate.core.domain.model.ServerProfile
import com.openmate.core.domain.repository.ServerProfileRepository
import com.openmate.core.domain.repository.SseEventRepository
import com.openmate.core.network.OpencodeApiClient
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

data class ProfileWithStatus(
    val profile: ServerProfile,
    val status: ConnectionStatus = ConnectionStatus.DISCONNECTED,
)

@HiltViewModel
class InstanceListViewModel @Inject constructor(
    private val profileRepository: ServerProfileRepository,
    private val sseEventRepository: SseEventRepository,
    private val dbProvider: ActiveDatabaseProvider,
    private val apiClient: OpencodeApiClient,
) : ViewModel() {

    private val _profiles = MutableStateFlow<List<ProfileWithStatus>>(emptyList())
    val profiles: StateFlow<List<ProfileWithStatus>> = _profiles.asStateFlow()

    private val _connectionStatus = MutableStateFlow(ConnectionStatus.DISCONNECTED)
    val connectionStatus: StateFlow<ConnectionStatus> = _connectionStatus.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    init {
        observeProfiles()
        observeConnectionStatus()
    }

    private fun observeProfiles() {
        viewModelScope.launch {
            profileRepository.observeAll().collect { list ->
                val currentStatus = _connectionStatus.value
                val activeId = dbProvider.getActiveProfileId()
                _profiles.value = list.map { profile ->
                    val status = if (profile.id == activeId) currentStatus
                                 else ConnectionStatus.DISCONNECTED
                    ProfileWithStatus(profile, status)
                }
            }
        }
    }

    private fun observeConnectionStatus() {
        viewModelScope.launch {
            sseEventRepository.observeConnectionStatus().collect { status ->
                _connectionStatus.value = status
                val activeId = dbProvider.getActiveProfileId()
                if (activeId != null) {
                    _profiles.value = _profiles.value.map { pws ->
                        if (pws.profile.id == activeId) pws.copy(status = status)
                        else pws
                    }
                }
            }
        }
    }

    fun connect(profile: ServerProfile, onConnected: () -> Unit = {}) {
        try {
            dbProvider.setActive(profile.id)
            apiClient.baseUrl = "http://${profile.address}:${profile.port}"
        } catch (e: Exception) {
            _error.value = e.message
            return
        }
        onConnected()
        viewModelScope.launch(Dispatchers.IO) {
            try {
                sseEventRepository.connect(profile.address, profile.port, profile.password)
                val updated = profile.copy(lastConnectedAt = System.currentTimeMillis())
                profileRepository.save(updated)
            } catch (e: Exception) {
                _error.value = e.message
            }
        }
    }

    fun deleteProfile(id: String) {
        viewModelScope.launch(Dispatchers.IO) {
            profileRepository.delete(id)
        }
    }

    fun clearError() {
        _error.value = null
    }
}

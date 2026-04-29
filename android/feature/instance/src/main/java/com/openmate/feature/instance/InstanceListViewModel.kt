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
import com.openmate.core.network.OpencodeApiClient
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
    private val sseEventRepository: SseEventRepository,
    private val sessionRepository: SessionRepository,
    private val dbProvider: ActiveDatabaseProvider,
    private val apiClient: OpencodeApiClient,
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
                    } catch (e: Exception) {
                        Log.e("InstanceListVM", "refreshSessionStatuses failed", e)
                    }
                }
            }
        }
    }

    private fun autoReconnect() {
        viewModelScope.launch(Dispatchers.IO) {
            val profiles = profileRepository.getAll()
            val lastProfile = profiles.filter { (it.lastConnectedAt ?: 0L) > 0L }
                .maxByOrNull { it.lastConnectedAt!! }
            if (lastProfile != null) {
                try {
                    dbProvider.setActive(lastProfile.id)
                    apiClient.baseUrl = "http://${lastProfile.address}:${lastProfile.port}"
                    sseEventRepository.connect(lastProfile.address, lastProfile.port, lastProfile.password)
                } catch (_: Exception) {}
            }
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

    fun connect(profile: ServerProfile, onConnected: () -> Unit = {}) {
        try {
            dbProvider.setActive(profile.id)
            apiClient.baseUrl = "http://${profile.address}:${profile.port}"
        } catch (e: Exception) {
            _error.value = e.message
            return
        }
        viewModelScope.launch(Dispatchers.IO) {
            try {
                sseEventRepository.connect(profile.address, profile.port, profile.password)
                sseEventRepository.observeConnectionStatus().first { it == ConnectionStatus.CONNECTED }
                val updated = profile.copy(lastConnectedAt = System.currentTimeMillis())
                profileRepository.save(updated)
                withContext(Dispatchers.Main) { onConnected() }
            } catch (e: Exception) {
                _error.value = e.message
            }
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

package com.openmate.feature.instance

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.openmate.core.database.ActiveDatabaseProvider
import com.openmate.core.domain.model.ConnectionStatus
import com.openmate.core.domain.model.ServerProfile
import com.openmate.core.domain.repository.ConnectionRepository
import com.openmate.core.domain.repository.ServerProfileRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import javax.inject.Inject

data class ProfileWithStatus(
    val profile: ServerProfile,
    val status: ConnectionStatus = ConnectionStatus.DISCONNECTED,
)

@HiltViewModel
class InstanceListViewModel @Inject constructor(
    private val profileRepository: ServerProfileRepository,
    private val dbProvider: ActiveDatabaseProvider,
    private val connectionManager: ConnectionRepository,
) : ViewModel() {

    private val _profiles = MutableStateFlow<List<ProfileWithStatus>>(emptyList())
    val profiles: StateFlow<List<ProfileWithStatus>> = _profiles.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    init {
        observeCombined()
    }

    private fun observeCombined() {
        viewModelScope.launch {
            profileRepository.observeAll()
                .combine(connectionManager.connectionStatus) { profiles, connectionStatus ->
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
        connectionManager.connect(profile)
    }

    fun deleteProfile(id: String) {
        viewModelScope.launch(Dispatchers.IO) {
            if (id == dbProvider.getActiveProfileId()) {
                connectionManager.disconnect()
            }
            profileRepository.delete(id)
        }
    }

    fun clearError() {
        _error.value = null
    }
}

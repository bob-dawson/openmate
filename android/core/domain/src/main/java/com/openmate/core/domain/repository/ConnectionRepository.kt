package com.openmate.core.domain.repository

import com.openmate.core.domain.model.ConnectionStatus
import com.openmate.core.domain.model.ServerProfile
import kotlinx.coroutines.flow.StateFlow

interface ConnectionRepository {
    val connectionStatus: StateFlow<ConnectionStatus>
    val activeProfile: StateFlow<ServerProfile?>
    val isConnected: StateFlow<Boolean>
    val errorMessage: StateFlow<String?>
    val needsRepairing: StateFlow<String?>

    fun connect(profile: ServerProfile)
    fun reconnect()
    fun disconnect()
    fun confirmRepairing(profileId: String, token: String)
    fun clearNeedsRepairing()
    fun clearError()
}
package com.openmate.feature.instance

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.openmate.core.domain.model.ServerProfile
import com.openmate.core.domain.repository.ConnectionRepository
import com.openmate.core.domain.repository.ServerProfileRepository
import com.openmate.core.network.TempHttpClient
import com.openmate.core.network.TokenStore
import com.openmate.core.network.dto.BridgeStatusResponse
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.UUID
import javax.inject.Inject

private const val TAG = "AddInstanceViewModel"

@HiltViewModel
class AddInstanceViewModel @Inject constructor(
    private val profileRepository: ServerProfileRepository,
    private val tokenStore: TokenStore,
    private val connectionRepository: ConnectionRepository,
) : ViewModel() {

    val name = MutableStateFlow("")
    val address = MutableStateFlow("")
    val port = MutableStateFlow("4097")

    private val _uiState = MutableStateFlow<AddInstanceUiState>(AddInstanceUiState.Idle)
    val uiState: StateFlow<AddInstanceUiState> = _uiState.asStateFlow()

    private val _pin = MutableStateFlow<String?>(null)
    val pin: StateFlow<String?> = _pin.asStateFlow()

    private var editProfileId: String? = null
    private var pendingProfileId: String? = null
    private var pendingOnSaved: (() -> Unit)? = null
    private var pendingInstanceId: String = ""

    fun loadProfileForEdit(profileId: String) {
        if (editProfileId != null) return
        editProfileId = profileId
        viewModelScope.launch(Dispatchers.IO) {
            val profile = profileRepository.getById(profileId) ?: return@launch
            name.value = profile.name
            address.value = profile.address
            port.value = profile.port.toString()
        }
    }

    fun setEditProfile(profile: ServerProfile) {
        editProfileId = profile.id
        name.value = profile.name
        address.value = profile.address
        port.value = profile.port.toString()
    }

    fun testConnection() {
        viewModelScope.launch {
            _uiState.value = AddInstanceUiState.Testing
            _pin.value = null
            try {
                val portNum = port.value.toIntOrNull()
                    ?: throw IllegalArgumentException("Invalid port")
                val url = "http://${address.value}:$portNum"
                val status = TempHttpClient.fetchBridgeStatus(url)
                if (status.bridge.version.isBlank()) {
                    _uiState.value = AddInstanceUiState.Error("Not a Bridge server")
                } else {
                    _uiState.value = AddInstanceUiState.TestSuccess(status)
                }
            } catch (e: Exception) {
                _uiState.value = AddInstanceUiState.Error(e.message ?: "Connection failed")
            }
        }
    }

    fun save(onSaved: () -> Unit) {
        val portNum = port.value.toIntOrNull()
        if (name.value.isBlank() || address.value.isBlank() || portNum == null || portNum !in 1..65535) {
            return
        }
        viewModelScope.launch {
            _uiState.value = AddInstanceUiState.Saving
            try {
                val url = "http://${address.value}:$portNum"
                val status = TempHttpClient.fetchBridgeStatus(url)
                if (status.bridge.version.isBlank()) {
                    throw IllegalStateException("Not a Bridge server")
                }
                pendingInstanceId = status.bridge.instanceId

                val profileId = editProfileId ?: UUID.randomUUID().toString()
                pendingProfileId = profileId

                val needsPairing = status.bridge.authEnabled && tokenStore.getToken(profileId) == null
                Log.d(TAG, "needsPairing=$needsPairing, authEnabled=${status.bridge.authEnabled}")
                if (needsPairing) {
                    pendingOnSaved = onSaved
                    startPairing(portNum)
                } else {
                    completeSave(profileId, portNum, onSaved)
                }
            } catch (e: Exception) {
                _uiState.value = AddInstanceUiState.Error("Save failed: ${e.message}")
            }
        }
    }

    private fun startPairing(portNum: Int) {
        viewModelScope.launch {
            try {
                _uiState.value = AddInstanceUiState.Pairing
                val url = "http://${address.value}:$portNum"
                Log.d(TAG, "Starting pairing request to $url")
                val response = try {
                    TempHttpClient.pairRequest(url)
                } catch (e: Exception) {
                    Log.e(TAG, "Pair request failed", e)
                    throw e
                }
                Log.d(TAG, "Got PIN: ${response.pin}")
                _pin.value = response.pin
            } catch (e: Exception) {
                Log.e(TAG, "Pairing failed: ${e.message}", e)
                _uiState.value = AddInstanceUiState.Error("Pairing failed: ${e.message}")
            }
        }
    }

    fun confirmPairing() {
        val pin = _pin.value ?: return
        val profileId = pendingProfileId ?: return
        val portNum = port.value.toIntOrNull() ?: return

        viewModelScope.launch {
            _uiState.value = AddInstanceUiState.ConfirmingPairing
            try {
                val url = "http://${address.value}:$portNum"
                val response = TempHttpClient.pairConfirm(url, pin)
                tokenStore.saveToken(profileId, response.token)
                val onSaved = pendingOnSaved
                pendingOnSaved = null
                if (onSaved != null) {
                    completeSave(profileId, portNum, onSaved)
                } else {
                    _uiState.value = AddInstanceUiState.Error("Save callback lost")
                }
            } catch (e: Exception) {
                _uiState.value = AddInstanceUiState.Error("Pairing confirmation failed: ${e.message}")
            }
        }
    }

    fun cancelPairing() {
        _uiState.value = AddInstanceUiState.Idle
        _pin.value = null
        pendingProfileId = null
        pendingOnSaved = null
        pendingInstanceId = ""
    }

    fun dismissError() {
        if (_uiState.value is AddInstanceUiState.Error) {
            _uiState.value = AddInstanceUiState.Idle
        }
    }

    fun applyScanResult(name: String, address: String, port: Int, token: String) {
        val profileId = UUID.randomUUID().toString()
        viewModelScope.launch(Dispatchers.IO) {
            tokenStore.saveToken(profileId, token)
            val profile = ServerProfile(
                id = profileId,
                name = name,
                address = address,
                port = port,
                createdAt = System.currentTimeMillis(),
            )
            profileRepository.save(profile)
        }
    }

    private suspend fun completeSave(profileId: String, portNum: Int, onSaved: () -> Unit) {
        val existing = editProfileId?.let { profileRepository.getById(it) }
        val saved: ServerProfile
        if (existing != null) {
            saved = existing.copy(
                name = name.value,
                address = address.value,
                port = portNum,
                instanceId = pendingInstanceId.ifEmpty { existing.instanceId },
            )
            profileRepository.save(saved)
        } else {
            saved = ServerProfile(
                id = profileId,
                name = name.value,
                address = address.value,
                port = portNum,
                instanceId = pendingInstanceId,
                createdAt = System.currentTimeMillis(),
            )
            profileRepository.save(saved)
        }
        connectionRepository.notifyProfileUpdated(saved)
        withContext(Dispatchers.Main) { onSaved() }
    }
}

sealed interface AddInstanceUiState {
    data object Idle : AddInstanceUiState
    data object Testing : AddInstanceUiState
    data class TestSuccess(val status: BridgeStatusResponse) : AddInstanceUiState
    data object Saving : AddInstanceUiState
    data object Pairing : AddInstanceUiState
    data object ConfirmingPairing : AddInstanceUiState
    data class Error(val message: String) : AddInstanceUiState
}

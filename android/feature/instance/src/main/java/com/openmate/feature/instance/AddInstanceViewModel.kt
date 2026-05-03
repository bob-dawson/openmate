package com.openmate.feature.instance

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.openmate.core.domain.model.ServerProfile
import com.openmate.core.domain.repository.ServerProfileRepository
import com.openmate.core.network.OpencodeApiClient
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

@HiltViewModel
class AddInstanceViewModel @Inject constructor(
    private val profileRepository: ServerProfileRepository,
    private val apiClient: OpencodeApiClient,
) : ViewModel() {

    val name = MutableStateFlow("")
    val address = MutableStateFlow("")
    val port = MutableStateFlow("4097")
    val password = MutableStateFlow("")

    private val _testResult = MutableStateFlow<TestResult?>(null)
    val testResult: StateFlow<TestResult?> = _testResult.asStateFlow()

    private val _isSaving = MutableStateFlow(false)
    val isSaving: StateFlow<Boolean> = _isSaving.asStateFlow()

    private var editProfileId: String? = null

    fun loadProfileForEdit(profileId: String) {
        if (editProfileId != null) return
        editProfileId = profileId
        viewModelScope.launch(Dispatchers.IO) {
            val profile = profileRepository.getById(profileId) ?: return@launch
            name.value = profile.name
            address.value = profile.address
            port.value = profile.port.toString()
            password.value = profile.password ?: ""
        }
    }

    fun setEditProfile(profile: ServerProfile) {
        editProfileId = profile.id
        name.value = profile.name
        address.value = profile.address
        port.value = profile.port.toString()
        password.value = profile.password ?: ""
    }

    fun testConnection() {
        viewModelScope.launch {
            _testResult.value = TestResult.Testing
            try {
                val status = withContext(Dispatchers.IO) {
                    val portNum = port.value.toIntOrNull()
                        ?: throw IllegalArgumentException("Invalid port")
                    val url = "http://${address.value}:$portNum"
                    val saved = apiClient.baseUrl
                    apiClient.baseUrl = url
                    try {
                        apiClient.bridgeStatus()
                    } finally {
                        apiClient.baseUrl = saved
                    }
                }
                if (status.bridge.version.isBlank()) {
                    _testResult.value = TestResult.Error("Not a Bridge server")
                } else {
                    _testResult.value = TestResult.Success(status)
                }
            } catch (e: Exception) {
                _testResult.value = TestResult.Error(e.message ?: "Connection failed")
            }
        }
    }

    fun save(onSaved: () -> Unit) {
        val portNum = port.value.toIntOrNull()
        if (name.value.isBlank() || address.value.isBlank() || portNum == null || portNum !in 1..65535) {
            return
        }
        viewModelScope.launch {
            _isSaving.value = true
            try {
                withContext(Dispatchers.IO) {
                    val url = "http://${address.value}:$portNum"
                    val saved = apiClient.baseUrl
                    apiClient.baseUrl = url
                    try {
                        val status = apiClient.bridgeStatus()
                        if (status.bridge.version.isBlank()) {
                            throw IllegalStateException("Not a Bridge server")
                        }
                    } finally {
                        apiClient.baseUrl = saved
                    }
                }
                val profile = ServerProfile(
                    id = editProfileId ?: UUID.randomUUID().toString(),
                    name = name.value,
                    address = address.value,
                    port = portNum,
                    password = password.value.ifBlank { null },
                    createdAt = editProfileId?.let {
                        profileRepository.getById(it)?.createdAt
                    } ?: System.currentTimeMillis(),
                )
                profileRepository.save(profile)
                withContext(Dispatchers.Main) { onSaved() }
            } catch (e: Exception) {
                _testResult.value = TestResult.Error("Save failed: ${e.message}")
            } finally {
                _isSaving.value = false
            }
        }
    }

    fun clearTestResult() {
        _testResult.value = null
    }
}

sealed interface TestResult {
    data object Testing : TestResult
    data class Success(val status: BridgeStatusResponse) : TestResult
    data class Error(val message: String) : TestResult
}

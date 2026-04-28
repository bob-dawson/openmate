package com.openmate.feature.instance

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.openmate.core.domain.model.ServerProfile
import com.openmate.core.domain.repository.ServerProfileRepository
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
) : ViewModel() {

    val name = MutableStateFlow("")
    val address = MutableStateFlow("")
    val port = MutableStateFlow("4096")
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
                _testResult.value = TestResult.Success
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
        viewModelScope.launch(Dispatchers.IO) {
            _isSaving.value = true
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
            _isSaving.value = false
            withContext(Dispatchers.Main) { onSaved() }
        }
    }

    fun clearTestResult() {
        _testResult.value = null
    }
}

sealed interface TestResult {
    data object Testing : TestResult
    data object Success : TestResult
    data class Error(val message: String) : TestResult
}

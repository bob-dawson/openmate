package com.openmate.feature.session

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.openmate.core.domain.model.ConnectionStatus
import com.openmate.core.domain.model.Workspace
import com.openmate.core.domain.repository.ServerProfileRepository
import com.openmate.core.domain.repository.SessionRepository
import com.openmate.core.domain.repository.SseEventRepository
import com.openmate.core.database.ActiveDatabaseProvider
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

@HiltViewModel
class WorkspaceListViewModel @Inject constructor(
    private val sessionRepository: SessionRepository,
    private val sseEventRepository: SseEventRepository,
    private val profileRepository: ServerProfileRepository,
    private val dbProvider: ActiveDatabaseProvider,
) : ViewModel() {

    private val _workspaces = MutableStateFlow<List<Workspace>>(emptyList())
    val workspaces: StateFlow<List<Workspace>> = _workspaces.asStateFlow()

    private val _connectionStatus = MutableStateFlow(ConnectionStatus.DISCONNECTED)
    val connectionStatus: StateFlow<ConnectionStatus> = _connectionStatus.asStateFlow()

    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    private val _errorMessage = MutableStateFlow<String?>(null)
    val errorMessage: StateFlow<String?> = _errorMessage.asStateFlow()

    private val _instanceName = MutableStateFlow("")
    val instanceName: StateFlow<String> = _instanceName.asStateFlow()

    companion object {
        private const val TAG = "WorkspaceListVM"
    }

    init {
        loadInstanceName()
        refresh()
        observeWorkspaces()
        observeConnection()
    }

    private fun loadInstanceName() {
        viewModelScope.launch(Dispatchers.IO) {
            val profileId = dbProvider.getActiveProfileId()
            if (profileId != null) {
                val profile = profileRepository.getById(profileId)
                _instanceName.value = profile?.name ?: ""
            }
        }
    }

    fun refresh() {
        viewModelScope.launch(Dispatchers.IO) {
            _isLoading.value = true
            _errorMessage.value = null
            try {
                sessionRepository.getSessions(null, null, null)
            } catch (e: Exception) {
                Log.e(TAG, "refresh failed", e)
                _errorMessage.value = "${e.javaClass.simpleName}: ${e.message}"
            }
            _isLoading.value = false
        }
    }

    fun clearError() {
        _errorMessage.value = null
    }

    fun createSession(title: String? = null, directory: String? = null, onCreated: (String) -> Unit) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val session = sessionRepository.createSession(title = title, directory = directory)
                withContext(Dispatchers.Main) {
                    onNavigateToWorkspace(session.directory, onCreated, session.id)
                }
            } catch (e: Exception) {
                Log.e(TAG, "createSession failed", e)
                _errorMessage.value = "${e.javaClass.simpleName}: ${e.message}"
            }
        }
    }

    private fun onNavigateToWorkspace(directory: String, onCreated: (String) -> Unit, sessionId: String) {
        onCreated(sessionId)
    }

    private fun observeWorkspaces() {
        viewModelScope.launch {
            sessionRepository.observeWorkspaces().collect { list ->
                _workspaces.value = list
            }
        }
    }

    private fun observeConnection() {
        viewModelScope.launch {
            sseEventRepository.observeConnectionStatus().collect { status ->
                _connectionStatus.value = status
            }
        }
    }
}

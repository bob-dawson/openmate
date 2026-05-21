package com.openmate.feature.session

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.openmate.core.domain.model.ConnectionStatus
import com.openmate.core.domain.model.Session
import com.openmate.core.domain.repository.ConnectionRepository
import com.openmate.core.domain.repository.SessionRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

@HiltViewModel
class SessionListViewModel @Inject constructor(
    private val sessionRepository: SessionRepository,
    private val connectionRepository: ConnectionRepository,
) : ViewModel() {

    private val _sessions = MutableStateFlow<List<Session>>(emptyList())
    val sessions: StateFlow<List<Session>> = _sessions.asStateFlow()

    private val _connectionStatus = MutableStateFlow(ConnectionStatus.DISCONNECTED)
    val connectionStatus: StateFlow<ConnectionStatus> = _connectionStatus.asStateFlow()

    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    private val _errorMessage = MutableStateFlow<String?>(null)
    val errorMessage: StateFlow<String?> = _errorMessage.asStateFlow()

    private var currentDirectory: String? = null
    private var pollJob: Job? = null

    companion object {
        private const val TAG = "SessionListVM"
        private const val POLL_INTERVAL_MS = 15_000L
    }

    fun setDirectory(directory: String) {
        currentDirectory = directory
        refresh()
        observeSessions()
        observeConnection()
        startPolling()
    }

    fun refresh() {
        viewModelScope.launch(Dispatchers.IO) {
            _isLoading.value = true
            _errorMessage.value = null
            try {
                sessionRepository.getSessions(currentDirectory, null, null)
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
        android.util.Log.d(TAG, "createSession title=$title directory=$directory")
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val session = sessionRepository.createSession(title, directory)
                withContext(Dispatchers.Main) { onCreated(session.id) }
            } catch (e: Exception) {
                Log.e(TAG, "createSession failed", e)
                _errorMessage.value = "${e.javaClass.simpleName}: ${e.message}"
            }
        }
    }

    fun deleteSession(id: String) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                sessionRepository.deleteSession(id)
            } catch (e: Exception) {
                Log.e(TAG, "deleteSession failed", e)
                _errorMessage.value = "${e.javaClass.simpleName}: ${e.message}"
            }
        }
    }

    private fun observeSessions() {
        viewModelScope.launch {
            sessionRepository.observeSessions(currentDirectory).collect { list ->
                _sessions.value = list
            }
        }
    }

    private fun observeConnection() {
        viewModelScope.launch {
            connectionRepository.connectionStatus.collect { status ->
                _connectionStatus.value = status
            }
        }
    }

    private fun startPolling() {
        stopPolling()
        pollJob = viewModelScope.launch(Dispatchers.IO) {
            while (isActive) {
                delay(POLL_INTERVAL_MS)
                try {
                    sessionRepository.getSessions(currentDirectory, null, null)
                    sessionRepository.refreshSessionStatuses(currentDirectory)
                } catch (e: Exception) {
                    Log.e(TAG, "poll refresh failed", e)
                }
            }
        }
    }

    private fun stopPolling() {
        pollJob?.cancel()
        pollJob = null
    }

    override fun onCleared() {
        super.onCleared()
        stopPolling()
    }
}

package com.openmate.feature.session

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.openmate.core.domain.model.ConnectionStatus
import com.openmate.core.domain.model.Session
import com.openmate.core.domain.repository.SessionRepository
import com.openmate.core.domain.repository.SseEventRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

@HiltViewModel
class SessionListViewModel @Inject constructor(
    private val sessionRepository: SessionRepository,
    private val sseEventRepository: SseEventRepository,
) : ViewModel() {

    private val _sessions = MutableStateFlow<List<Session>>(emptyList())
    val sessions: StateFlow<List<Session>> = _sessions.asStateFlow()

    private val _connectionStatus = MutableStateFlow(ConnectionStatus.DISCONNECTED)
    val connectionStatus: StateFlow<ConnectionStatus> = _connectionStatus.asStateFlow()

    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    private val _errorMessage = MutableStateFlow<String?>(null)
    val errorMessage: StateFlow<String?> = _errorMessage.asStateFlow()

    companion object {
        private const val TAG = "SessionListVM"
    }

    init {
        refresh()
        observeSessions()
        observeConnection()
    }

    fun refresh() {
        viewModelScope.launch(Dispatchers.IO) {
            _isLoading.value = true
            _errorMessage.value = null
            try {
                val sessions = sessionRepository.getSessions(null, null, null)
                _sessions.value = sessions
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

    fun createSession(onCreated: (String) -> Unit) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val session = sessionRepository.createSession(null)
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
            sessionRepository.observeSessions(null).collect { list ->
                _sessions.value = list
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

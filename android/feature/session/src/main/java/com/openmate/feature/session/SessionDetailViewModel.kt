package com.openmate.feature.session

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.openmate.core.domain.model.Message
import com.openmate.core.domain.model.MessageRole
import com.openmate.core.domain.model.PermissionReply
import com.openmate.core.domain.model.PermissionRequest
import com.openmate.core.domain.model.QuestionRequest
import com.openmate.core.domain.model.SessionStatus
import com.openmate.core.domain.model.TodoInfo
import com.openmate.core.domain.repository.MessageRepository
import com.openmate.core.domain.repository.PermissionRepository
import com.openmate.core.domain.repository.QuestionRepository
import com.openmate.core.domain.repository.SessionRepository
import com.openmate.core.domain.repository.TodoRepository
import com.openmate.core.network.OpencodeApiClient
import com.openmate.core.network.dto.ModelInfoDto
import com.openmate.core.network.dto.ProviderInfoDto
import com.openmate.core.network.dto.ProviderListDto
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
class SessionDetailViewModel @Inject constructor(
    private val sessionRepository: SessionRepository,
    private val messageRepository: MessageRepository,
    private val permissionRepository: PermissionRepository,
    private val questionRepository: QuestionRepository,
    private val todoRepository: TodoRepository,
    private val apiClient: OpencodeApiClient,
) : ViewModel() {

    private val _messages = MutableStateFlow<List<Message>>(emptyList())
    val messages: StateFlow<List<Message>> = _messages.asStateFlow()

    private val _pendingPermissions = MutableStateFlow<List<PermissionRequest>>(emptyList())
    val pendingPermissions: StateFlow<List<PermissionRequest>> = _pendingPermissions.asStateFlow()

    private val _pendingQuestions = MutableStateFlow<List<QuestionRequest>>(emptyList())
    val pendingQuestions: StateFlow<List<QuestionRequest>> = _pendingQuestions.asStateFlow()

    private val _todos = MutableStateFlow<List<TodoInfo>>(emptyList())
    val todos: StateFlow<List<TodoInfo>> = _todos.asStateFlow()

    private val _isStreaming = MutableStateFlow(false)
    val isStreaming: StateFlow<Boolean> = _isStreaming.asStateFlow()

    private val _inputText = MutableStateFlow("")
    val inputText: StateFlow<String> = _inputText.asStateFlow()

    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    private val _pendingAssistantId = MutableStateFlow<String?>(null)
    val pendingAssistantId: StateFlow<String?> = _pendingAssistantId.asStateFlow()

    private val _errorMessage = MutableStateFlow<String?>(null)
    val errorMessage: StateFlow<String?> = _errorMessage.asStateFlow()

    private val _sessionTitle = MutableStateFlow("")
    val sessionTitle: StateFlow<String> = _sessionTitle.asStateFlow()

    data class ModelRef(val providerID: String, val modelID: String, val modelName: String)

    private val _providers = MutableStateFlow<ProviderListDto?>(null)
    val providers: StateFlow<ProviderListDto?> = _providers.asStateFlow()

    private val _selectedModel = MutableStateFlow<ModelRef?>(null)
    val selectedModel: StateFlow<ModelRef?> = _selectedModel.asStateFlow()

    companion object {
        private const val TAG = "SessionDetailVM"
        private const val POLL_INTERVAL_MS = 15_000L
    }

    private var currentSessionID: String? = null
    private var pollJob: Job? = null

    fun clearError() {
        _errorMessage.value = null
    }

    fun loadSession(sessionID: String) {
        currentSessionID = sessionID
        viewModelScope.launch(Dispatchers.IO) {
            _isLoading.value = true
            try {
                val session = sessionRepository.getSession(sessionID)
                _sessionTitle.value = session?.title ?: ""
            } catch (e: Exception) {
                Log.e(TAG, "loadSession title failed", e)
            }
            try {
                messageRepository.syncMessages(sessionID, 80)
            } catch (e: Exception) {
                Log.e(TAG, "syncMessages failed", e)
            }
            try {
                todoRepository.refreshTodos(sessionID)
            } catch (e: Exception) {
                Log.e(TAG, "refreshTodos failed", e)
            }
            try {
                sessionRepository.refreshSessionStatusesFromMessages()
            } catch (e: Exception) {
                Log.e(TAG, "refreshStatusFromMessages failed", e)
            }
            try {
                permissionRepository.refresh()
            } catch (e: Exception) {
                Log.e(TAG, "refreshPermissions failed", e)
            }
            try {
                questionRepository.refresh()
            } catch (e: Exception) {
                Log.e(TAG, "refreshQuestions failed", e)
            }
            _isLoading.value = false
        }
        observeMessages(sessionID)
        observeSessionStatus(sessionID)
        observePermissions()
        observeQuestions()
        observeTodos(sessionID)
        startPolling()
    }

    fun refresh() {
        val sid = currentSessionID ?: return
        viewModelScope.launch(Dispatchers.IO) {
            try {
                sessionRepository.getSession(sid)
                messageRepository.syncMessages(sid, 80)
                sessionRepository.refreshSessionStatusesFromMessages()
                todoRepository.refreshTodos(sid)
            } catch (e: Exception) {
                Log.e(TAG, "manual refresh failed", e)
            }
        }
    }

    private fun startPolling() {
        stopPolling()
        pollJob = viewModelScope.launch(Dispatchers.IO) {
            while (isActive) {
                delay(POLL_INTERVAL_MS)
                val sid = currentSessionID ?: continue
                try {
                    messageRepository.syncMessages(sid, 80)
                    sessionRepository.refreshSessionStatusesFromMessages()
                    todoRepository.refreshTodos(sid)
                    permissionRepository.refresh()
                    questionRepository.refresh()
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

    fun sendMessage(sessionID: String) {
        val text = _inputText.value.ifBlank { return }
        _inputText.value = ""
        val model = _selectedModel.value
        viewModelScope.launch(Dispatchers.IO) {
            try {
                messageRepository.sendMessage(sessionID, text, model?.providerID, model?.modelID)
                messageRepository.syncMessages(sessionID, 80)
                permissionRepository.refresh()
                questionRepository.refresh()
            } catch (e: Exception) {
                Log.e(TAG, "sendMessage FAILED: ${e.javaClass.simpleName}: ${e.message}", e)
                _errorMessage.value = "${e.javaClass.simpleName}: ${e.message}"
            }
        }
    }

    fun updateInput(text: String) {
        _inputText.value = text
    }

    fun abort(sessionID: String) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                sessionRepository.abortSession(sessionID)
            } catch (_: Exception) {}
        }
    }

    fun replyPermission(requestID: String, reply: PermissionReply, message: String?) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                permissionRepository.reply(requestID, reply, message)
            } catch (e: Exception) {
                Log.e(TAG, "replyPermission failed", e)
                _errorMessage.emit(e.message ?: "Permission reply failed")
            }
        }
    }

    fun replyQuestion(requestID: String, answers: List<List<String>>) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                questionRepository.reply(requestID, answers)
            } catch (e: Exception) {
                Log.e(TAG, "replyQuestion failed", e)
                _errorMessage.emit(e.message ?: "Question reply failed")
            }
        }
    }

    fun rejectQuestion(requestID: String) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                questionRepository.reject(requestID)
            } catch (e: Exception) {
                Log.e(TAG, "rejectQuestion failed", e)
                _errorMessage.emit(e.message ?: "Question reject failed")
            }
        }
    }

    fun renameSession(newTitle: String) {
        val sid = currentSessionID ?: return
        viewModelScope.launch(Dispatchers.IO) {
            try {
                sessionRepository.updateSession(sid, newTitle)
                _sessionTitle.value = newTitle
            } catch (e: Exception) {
                _errorMessage.value = "Rename failed: ${e.message}"
            }
        }
    }

    fun deleteSession(onDeleted: () -> Unit) {
        val sid = currentSessionID ?: return
        viewModelScope.launch(Dispatchers.IO) {
            try {
                sessionRepository.deleteSession(sid)
                withContext(Dispatchers.Main) { onDeleted() }
            } catch (e: Exception) {
                _errorMessage.value = "Delete failed: ${e.message}"
            }
        }
    }

    fun loadProviders() {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val result = apiClient.getProviders()
                _providers.value = result
            } catch (e: Exception) {
                Log.e(TAG, "loadProviders failed", e)
            }
        }
    }

    fun selectModel(providerID: String, modelID: String, modelName: String) {
        _selectedModel.value = ModelRef(providerID, modelID, modelName)
    }

    fun clearSelectedModel() {
        _selectedModel.value = null
    }

private fun observeMessages(sessionID: String) {
        viewModelScope.launch {
            messageRepository.observeMessages(sessionID).collect { list ->
                _messages.value = list
                val hasIncompleteAssistant = list.any { it.role == MessageRole.ASSISTANT && it.completedAt == null }
                _isStreaming.value = hasIncompleteAssistant
                _pendingAssistantId.value = list
                    .filter { it.role == MessageRole.ASSISTANT && it.completedAt == null }
                    .maxByOrNull { it.id }?.id
            }
        }
    }

    private fun observeSessionStatus(sessionID: String) {
    }

    private fun observePermissions() {
        viewModelScope.launch {
            try {
                permissionRepository.observePending().collect { list ->
                    _pendingPermissions.value = list
                }
            } catch (e: Exception) {
                Log.e(TAG, "observePermissions failed", e)
            }
        }
    }

    private fun observeQuestions() {
        viewModelScope.launch {
            try {
                questionRepository.observePending().collect { list ->
                    _pendingQuestions.value = list
                }
            } catch (e: Exception) {
                Log.e(TAG, "observeQuestions failed", e)
            }
        }
    }

    private fun observeTodos(sessionID: String) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                todoRepository.refreshTodos(sessionID)
            } catch (e: Exception) {
                Log.e(TAG, "refreshTodos failed", e)
            }
        }
        viewModelScope.launch {
            todoRepository.observeTodos(sessionID).collect { list ->
                _todos.value = list
            }
        }
    }
}

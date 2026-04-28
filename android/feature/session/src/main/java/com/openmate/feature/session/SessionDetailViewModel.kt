package com.openmate.feature.session

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.openmate.core.domain.model.Message
import com.openmate.core.domain.model.Part
import com.openmate.core.domain.model.PermissionReply
import com.openmate.core.domain.model.PermissionRequest
import com.openmate.core.domain.model.QuestionRequest
import com.openmate.core.domain.repository.MessageRepository
import com.openmate.core.domain.repository.PermissionRepository
import com.openmate.core.domain.repository.QuestionRepository
import com.openmate.core.domain.repository.SessionRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class SessionDetailViewModel @Inject constructor(
    private val sessionRepository: SessionRepository,
    private val messageRepository: MessageRepository,
    private val permissionRepository: PermissionRepository,
    private val questionRepository: QuestionRepository,
) : ViewModel() {

    private val _messages = MutableStateFlow<List<Message>>(emptyList())
    val messages: StateFlow<List<Message>> = _messages.asStateFlow()

    private val _pendingPermissions = MutableStateFlow<List<PermissionRequest>>(emptyList())
    val pendingPermissions: StateFlow<List<PermissionRequest>> = _pendingPermissions.asStateFlow()

    private val _pendingQuestions = MutableStateFlow<List<QuestionRequest>>(emptyList())
    val pendingQuestions: StateFlow<List<QuestionRequest>> = _pendingQuestions.asStateFlow()

    private val _isStreaming = MutableStateFlow(false)
    val isStreaming: StateFlow<Boolean> = _isStreaming.asStateFlow()

    private val _inputText = MutableStateFlow("")
    val inputText: StateFlow<String> = _inputText.asStateFlow()

    fun loadSession(sessionID: String) {
        viewModelScope.launch(Dispatchers.IO) {
            messageRepository.getMessages(sessionID, 80, null)
        }
        observeMessages(sessionID)
        observePermissions()
        observeQuestions()
    }

    fun sendMessage(sessionID: String) {
        val text = _inputText.value.ifBlank { return }
        _inputText.value = ""
        _isStreaming.value = true
        viewModelScope.launch(Dispatchers.IO) {
            try {
                messageRepository.sendMessage(sessionID, text).collect {}
            } finally {
                _isStreaming.value = false
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
            permissionRepository.reply(requestID, reply, message)
        }
    }

    fun replyQuestion(requestID: String, answers: List<List<String>>) {
        viewModelScope.launch(Dispatchers.IO) {
            questionRepository.reply(requestID, answers)
        }
    }

    fun rejectQuestion(requestID: String) {
        viewModelScope.launch(Dispatchers.IO) {
            questionRepository.reject(requestID)
        }
    }

    private fun observeMessages(sessionID: String) {
        viewModelScope.launch {
            messageRepository.observeMessages(sessionID).collect { list ->
                _messages.value = list
            }
        }
    }

    private fun observePermissions() {
        viewModelScope.launch {
            permissionRepository.observePending().collect { list ->
                _pendingPermissions.value = list
            }
        }
    }

    private fun observeQuestions() {
        viewModelScope.launch {
            questionRepository.observePending().collect { list ->
                _pendingQuestions.value = list
            }
        }
    }
}

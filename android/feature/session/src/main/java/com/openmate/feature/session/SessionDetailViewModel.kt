package com.openmate.feature.session

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.openmate.core.domain.model.SessionMessage
import com.openmate.core.domain.model.QuestionRequest
import com.openmate.core.domain.model.PermissionRequest
import com.openmate.core.domain.model.PermissionReply
import com.openmate.core.domain.repository.FileAttachment
import com.openmate.core.domain.repository.SessionMessageRepository
import com.openmate.core.domain.repository.QuestionRepository
import com.openmate.core.domain.repository.PermissionRepository
import com.openmate.core.domain.repository.SessionRepository
import com.openmate.core.domain.repository.TodoRepository
import com.openmate.core.network.OpencodeApiClient
import com.openmate.core.network.dto.ModelInfoDto
import com.openmate.core.network.dto.ProviderInfoDto
import com.openmate.core.network.dto.ProviderListDto
import com.openmate.core.common.guessMimeForAttachment
import com.openmate.core.network.dto.SkillInfoDto
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

object AttachmentBridge {
    var pendingPath: String? = null
}

@HiltViewModel
class SessionDetailViewModel @Inject constructor(
    @ApplicationContext private val appContext: Context,
    private val sessionRepository: SessionRepository,
    private val sessionMessageRepository: SessionMessageRepository,
    private val todoRepository: TodoRepository,
    private val questionRepository: QuestionRepository,
    private val permissionRepository: PermissionRepository,
    internal val apiClient: OpencodeApiClient,
) : ViewModel() {
    private val prefs: SharedPreferences = appContext.getSharedPreferences("openmate_settings", Context.MODE_PRIVATE)

    private val _messages = MutableStateFlow<List<SessionMessage>>(emptyList())
    val messages: StateFlow<List<SessionMessage>> = _messages.asStateFlow()

    private val _todos = MutableStateFlow<List<com.openmate.core.domain.model.TodoInfo>>(emptyList())
    val todos: StateFlow<List<com.openmate.core.domain.model.TodoInfo>> = _todos.asStateFlow()

    private val _isStreaming = MutableStateFlow(false)
    val isStreaming: StateFlow<Boolean> = _isStreaming.asStateFlow()

    private val _queuedMessageId = MutableStateFlow<String?>(null)
    val queuedMessageId: StateFlow<String?> = _queuedMessageId.asStateFlow()

    private val _pendingQuestions = MutableStateFlow<List<QuestionRequest>>(emptyList())
    val pendingQuestions: StateFlow<List<QuestionRequest>> = _pendingQuestions.asStateFlow()

    private val _pendingPermissions = MutableStateFlow<List<PermissionRequest>>(emptyList())
    val pendingPermissions: StateFlow<List<PermissionRequest>> = _pendingPermissions.asStateFlow()

    private val _inputText = MutableStateFlow("")
    val inputText: StateFlow<String> = _inputText.asStateFlow()

    private val _attachedFiles = MutableStateFlow<List<FileAttachment>>(emptyList())
    val attachedFiles: StateFlow<List<FileAttachment>> = _attachedFiles.asStateFlow()

    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    private val _isUploading = MutableStateFlow(false)
    val isUploading: StateFlow<Boolean> = _isUploading.asStateFlow()

    private val _errorMessage = MutableStateFlow<String?>(null)
    val errorMessage: StateFlow<String?> = _errorMessage.asStateFlow()

    private val _disableAutoScroll = MutableStateFlow(false)
    val disableAutoScroll: StateFlow<Boolean> = _disableAutoScroll.asStateFlow()

    fun setDisableAutoScroll(value: Boolean) {
        _disableAutoScroll.value = value
    }

    private val _sessionTitle = MutableStateFlow("")
    val sessionTitle: StateFlow<String> = _sessionTitle.asStateFlow()

    data class ModelRef(val providerID: String, val modelID: String, val modelName: String)

    private val _providers = MutableStateFlow<ProviderListDto?>(null)
    val providers: StateFlow<ProviderListDto?> = _providers.asStateFlow()

    private val _selectedModel = MutableStateFlow<ModelRef?>(null)
    val selectedModel: StateFlow<ModelRef?> = _selectedModel.asStateFlow()

    private val _selectedAgent = MutableStateFlow("build")
    val selectedAgent: StateFlow<String> = _selectedAgent.asStateFlow()

    private val _recentModels = MutableStateFlow<List<ModelRef>>(loadRecentModels())
    val recentModels: StateFlow<List<ModelRef>> = _recentModels.asStateFlow()

    private var pollJob: Job? = null

    companion object {
        private const val TAG = "SessionDetailVM"
        private const val KEY_RECENT_MODELS = "recent_models"
        private const val KEY_DRAFTS = "draft_messages"
        private const val POLL_INTERVAL_MS = 15_000L
    }

    private var currentSessionID: String? = null
    private var currentDirectory: String = ""
    private var draftSessionID: String? = null
    private var observeMsgJob: Job? = null
    private var observeTodoJob: Job? = null
    private var observeQuestionJob: Job? = null
    private var observePermissionJob: Job? = null

    fun clearError() {
        _errorMessage.value = null
    }

    fun restoreDraft() {
        val sid = currentSessionID ?: return
        val savedDraft = loadDraft(sid)
        _inputText.value = savedDraft.text
        _attachedFiles.value = savedDraft.files
    }

    fun fetchFullContent(sessionId: String, messageId: String) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                sessionMessageRepository.fetchFullMessage(sessionId, messageId)
            } catch (e: Exception) {
                Log.e(TAG, "fetchFullContent failed", e)
            }
        }
    }

    fun uploadAndAttach(uriList: List<android.net.Uri>, contentResolver: android.content.ContentResolver) {
        viewModelScope.launch(Dispatchers.IO) {
            _isUploading.value = true
            for (uri in uriList) {
                try {
                    val filename = resolveFilename(uri, contentResolver)
                    val bytes = contentResolver.openInputStream(uri)?.use { it.readBytes() }
                        ?: throw Exception("Cannot read $filename")
                    if (bytes.size > 20 * 1024 * 1024) {
                        _errorMessage.value = appContext.getString(R.string.file_too_large, filename)
                        continue
                    }
                    val serverPath = "${currentDirectory.ifBlank { "." }}/.openmate/upload/$filename"
                    apiClient.bridgeUploadFile(serverPath, bytes, createDirs = true)
                    _attachedFiles.value = _attachedFiles.value + FileAttachment(serverPath, filename, guessMimeForAttachment(filename))
                    draftSessionID?.let { saveDraft(it, _inputText.value, _attachedFiles.value) }
                } catch (e: Exception) {
                    _errorMessage.value = appContext.getString(R.string.upload_failed, e.message ?: "Unknown error")
                }
            }
            _isUploading.value = false
        }
    }

    private fun resolveFilename(uri: android.net.Uri, contentResolver: android.content.ContentResolver): String {
        contentResolver.query(uri, null, null, null, null)?.use { cursor ->
            val nameIndex = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
            if (nameIndex >= 0 && cursor.moveToFirst()) {
                val name = cursor.getString(nameIndex)
                if (!name.isNullOrBlank()) return name
            }
        }
        val lastSegment = uri.lastPathSegment ?: "unknown"
        return lastSegment.substringAfterLast("/")
    }

    fun loadSession(sessionID: String) {
        currentSessionID = sessionID
        observeMsgJob?.cancel()
        observeTodoJob?.cancel()

        viewModelScope.launch(Dispatchers.IO) {
            sessionRepository.observeSession(sessionID).collect { session ->
                if (session != null && session.title.isNotBlank()) {
                    _sessionTitle.value = session.title
                }
            }
        }

        viewModelScope.launch(Dispatchers.IO) {
            try {
                val session = sessionRepository.getSession(sessionID)
                if (session?.title?.isNotBlank() == true) {
                    _sessionTitle.value = session.title
                }
                currentDirectory = session?.directory ?: ""
            } catch (e: Exception) {
                Log.e(TAG, "loadSession title API failed", e)
            }
        }

        draftSessionID = sessionID
        val savedDraft = loadDraft(sessionID)
        _inputText.value = savedDraft.text
        _attachedFiles.value = savedDraft.files

        observeMessages(sessionID)
        observeTodos(sessionID)
        observeQuestions()
        observePermissions()
        startPolling(sessionID)

        viewModelScope.launch(Dispatchers.IO) {
            try {
                val lastSeq = sessionMessageRepository.getLastSeq(sessionID)
                if (lastSeq != null && lastSeq > 0) {
                    sessionMessageRepository.incrementalSync(sessionID)
                } else {
                    sessionMessageRepository.initSync(sessionID, 30)
                }
            } catch (e: Exception) {
                Log.e(TAG, "sync failed", e)
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
            resolveDefaultModel()
        }
    }

    fun refresh() {
        val sid = currentSessionID ?: return
        viewModelScope.launch(Dispatchers.IO) {
            try {
                sessionMessageRepository.incrementalSync(sid)
                sessionRepository.refreshSessionStatusesFromMessages()
                todoRepository.refreshTodos(sid)
            } catch (e: Exception) {
                Log.e(TAG, "manual refresh failed", e)
            }
        }
    }

    private fun startPolling(sessionId: String) {
        pollJob?.cancel()
        pollJob = viewModelScope.launch(Dispatchers.IO) {
            while (isActive) {
                delay(POLL_INTERVAL_MS)
                try {
                    sessionMessageRepository.incrementalSync(sessionId)
                    sessionRepository.refreshSessionStatusesFromMessages()
                } catch (e: Exception) {
                    Log.e(TAG, "poll sync failed", e)
                }
            }
        }
    }

    override fun onCleared() {
        super.onCleared()
        observeMsgJob?.cancel()
        observeTodoJob?.cancel()
        observeQuestionJob?.cancel()
        observePermissionJob?.cancel()
        pollJob?.cancel()
    }

    fun sendMessage(sessionID: String) {
        val text = _inputText.value.ifBlank { return }
        _inputText.value = ""
        val model = _selectedModel.value
        val agent = _selectedAgent.value
        val files = _attachedFiles.value
        _attachedFiles.value = emptyList()
        clearDraft(sessionID)
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val apiFiles = files.map { com.openmate.core.network.OpencodeApiClient.FileAttachment(it.path, it.filename, it.mime) }
                apiClient.sendPrompt(sessionID, text, model?.providerID, model?.modelID, agent, apiFiles, currentDirectory.ifBlank { null })
                delay(500)
                sessionMessageRepository.incrementalSync(sessionID)
            } catch (e: Exception) {
                Log.e(TAG, "sendMessage FAILED: ${e.javaClass.simpleName}: ${e.message}", e)
                _errorMessage.value = "${e.javaClass.simpleName}: ${e.message}"
            }
        }
    }

    fun updateInput(text: String) {
        _inputText.value = text
        draftSessionID?.let { saveDraft(it, text, _attachedFiles.value) }
    }

    fun abort(sessionID: String) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                sessionRepository.abortSession(sessionID, currentDirectory.ifBlank { null })
                refresh()
            } catch (_: Exception) {}
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

    private suspend fun resolveDefaultModel() {
        if (_selectedModel.value != null) return
        val recent = _recentModels.value.firstOrNull()
        if (recent != null) {
            _selectedModel.value = recent
            return
        }
        try {
            val result = apiClient.getProviders()
            _providers.value = result
            val connected = result.connected
            val defaults = result.default
            for (providerID in connected) {
                val modelID = defaults[providerID] ?: continue
                val provider = result.all.find { it.id == providerID } ?: continue
                val model = provider.models[modelID] ?: provider.models.values.firstOrNull() ?: continue
                _selectedModel.value = ModelRef(providerID, modelID, model.name.ifBlank { modelID })
                return
            }
        } catch (e: Exception) {
            Log.e(TAG, "resolveDefaultModel failed", e)
        }
    }

    fun loadProviders() {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val result = apiClient.getProviders()
                Log.d(TAG, "loadProviders OK: ${result.all.size} providers, connected=${result.connected}")
                _providers.value = result
            } catch (e: Exception) {
                Log.e(TAG, "loadProviders FAILED: ${e.javaClass.simpleName}: ${e.message}", e)
            }
        }
    }

    fun selectModel(providerID: String, modelID: String, modelName: String) {
        val ref = ModelRef(providerID, modelID, modelName)
        _selectedModel.value = ref
        val updated = (listOf(ref) + _recentModels.value.filter {
            !(it.providerID == providerID && it.modelID == modelID)
        }).take(5)
        _recentModels.value = updated
        saveRecentModels(updated)
    }

    fun clearSelectedModel() {
        _selectedModel.value = null
    }

    fun getWorkingDirectory(): String = currentDirectory

    fun setAgent(agent: String) {
        _selectedAgent.value = agent
    }

    private val _isCompacting = MutableStateFlow(false)
    val isCompacting: StateFlow<Boolean> = _isCompacting.asStateFlow()

    private val _skills = MutableStateFlow<List<SkillInfoDto>>(emptyList())
    val skills: StateFlow<List<SkillInfoDto>> = _skills.asStateFlow()

    fun compact(sessionID: String) {
        val model = _selectedModel.value ?: return
        viewModelScope.launch(Dispatchers.IO) {
            _isCompacting.value = true
            try {
                apiClient.summarizeSession(sessionID, model.providerID, model.modelID, currentDirectory.ifBlank { null })
                delay(2000)
                sessionMessageRepository.incrementalSync(sessionID)
                sessionRepository.refreshSessionStatusesFromMessages()
            } catch (e: Exception) {
                Log.e(TAG, "compact failed", e)
                _errorMessage.value = "Compact failed: ${e.message}"
            } finally {
                _isCompacting.value = false
            }
        }
    }

    fun loadSkills() {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                _skills.value = apiClient.getSkills()
            } catch (e: Exception) {
                Log.e(TAG, "loadSkills failed", e)
            }
        }
    }

    fun useSkill(skillName: String) {
        val current = _inputText.value
        _inputText.value = (if (current.isNotBlank()) "$current\n" else "") + "/skill $skillName"
    }

    fun insertFilePath(path: String) {
        val current = _inputText.value
        _inputText.value = if (current.isNotBlank()) "$current\n$path" else path
    }

    fun consumePendingPath() {
        val path = AttachmentBridge.pendingPath ?: return
        AttachmentBridge.pendingPath = null
        insertFilePath(path)
    }

    fun attachFile(path: String, filename: String) {
        val mime = guessMimeForAttachment(filename)
        _attachedFiles.value = _attachedFiles.value + FileAttachment(path, filename, mime)
        draftSessionID?.let { saveDraft(it, _inputText.value, _attachedFiles.value) }
    }

    fun removeAttachedFile(index: Int) {
        _attachedFiles.value = _attachedFiles.value.toMutableList().apply { removeAt(index) }
        draftSessionID?.let { saveDraft(it, _inputText.value, _attachedFiles.value) }
    }

    private data class DraftData(val text: String, val files: List<FileAttachment>)

    private fun saveDraft(sessionID: String, text: String, files: List<FileAttachment>) {
        val filesRaw = files.joinToString("||") { "${it.path}::${it.filename}::${it.mime}" }
        prefs.edit()
            .putString("draft_text_$sessionID", text)
            .putString("draft_files_$sessionID", filesRaw)
            .apply()
    }

    private fun loadDraft(sessionID: String): DraftData {
        val text = prefs.getString("draft_text_$sessionID", "") ?: ""
        val filesRaw = prefs.getString("draft_files_$sessionID", "") ?: ""
        val files = if (filesRaw.isBlank()) emptyList() else filesRaw.split("||").mapNotNull { entry ->
            val parts = entry.split("::")
            if (parts.size == 3) FileAttachment(parts[0], parts[1], parts[2]) else null
        }
        return DraftData(text, files)
    }

    private fun clearDraft(sessionID: String) {
        prefs.edit()
            .remove("draft_text_$sessionID")
            .remove("draft_files_$sessionID")
            .apply()
    }

    private fun loadRecentModels(): List<ModelRef> {
        val raw = prefs.getString(KEY_RECENT_MODELS, null) ?: return emptyList()
        return raw.split("||").mapNotNull { entry ->
            val parts = entry.split("::")
            if (parts.size == 3) ModelRef(parts[0], parts[1], parts[2]) else null
        }
    }

    private fun saveRecentModels(models: List<ModelRef>) {
        val raw = models.joinToString("||") { "${it.providerID}::${it.modelID}::${it.modelName}" }
        prefs.edit().putString(KEY_RECENT_MODELS, raw).apply()
    }

    private fun observeMessages(sessionID: String) {
        observeMsgJob = viewModelScope.launch {
            try {
                sessionMessageRepository.observeMessages(sessionID).collect { list ->
                    Log.d(TAG, "observeMessages: ${list.size} messages for $sessionID")
                    _messages.value = list
                    val lastAssistant = list.lastOrNull { it.type == "assistant" }
                    val isStillStreaming = lastAssistant?.completedAt == null
                    _isStreaming.value = isStillStreaming
                    val lastMessage = list.lastOrNull()
                    _queuedMessageId.value = if (lastMessage?.type == "user") lastMessage.id else null
                }
            } catch (e: Exception) {
                Log.e(TAG, "observeMessages failed", e)
            }
        }
    }

    private fun observeTodos(sessionID: String) {
        observeTodoJob = viewModelScope.launch(Dispatchers.IO) {
            try {
                todoRepository.refreshTodos(sessionID)
            } catch (e: Exception) {
                Log.e(TAG, "refreshTodos failed", e)
            }
            try {
                todoRepository.observeTodos(sessionID).collect { list ->
                    _todos.value = list
                }
            } catch (e: Exception) {
                Log.e(TAG, "observeTodos failed", e)
            }
        }
    }

    private fun observeQuestions() {
        observeQuestionJob = viewModelScope.launch(Dispatchers.IO) {
            try {
                questionRepository.observePending().collect { list ->
                    _pendingQuestions.value = list
                }
            } catch (e: Exception) {
                Log.e(TAG, "observeQuestions failed", e)
            }
        }
    }

    private fun observePermissions() {
        observePermissionJob = viewModelScope.launch(Dispatchers.IO) {
            try {
                permissionRepository.observePending().collect { list ->
                    _pendingPermissions.value = list
                }
            } catch (e: Exception) {
                Log.e(TAG, "observePermissions failed", e)
            }
        }
    }

    fun replyQuestion(requestID: String, answers: List<List<String>>) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                questionRepository.reply(requestID, answers, currentDirectory.ifBlank { null })
            } catch (e: Exception) {
                Log.e(TAG, "replyQuestion failed", e)
            }
        }
    }

    fun rejectQuestion(requestID: String) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                questionRepository.reject(requestID, currentDirectory.ifBlank { null })
            } catch (e: Exception) {
                Log.e(TAG, "rejectQuestion failed", e)
            }
        }
    }

    fun replyPermission(requestID: String, reply: PermissionReply, message: String? = null) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                permissionRepository.reply(requestID, reply, message, currentDirectory.ifBlank { null })
            } catch (e: Exception) {
                Log.e(TAG, "replyPermission failed", e)
            }
        }
    }
}

package com.openmate.feature.session

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.openmate.core.data.sync.SyncDebugController
import com.openmate.core.data.sync.SyncLogCategory
import com.openmate.core.data.sync.SyncLogEntry
import com.openmate.core.data.sync.SyncLogLevel
import com.openmate.core.domain.model.Session
import com.openmate.core.domain.model.SessionRevert
import com.openmate.core.domain.model.SessionMessage
import com.openmate.core.domain.model.SessionRetryStatus
import com.openmate.core.domain.model.SessionMessageSyncResult
import com.openmate.core.domain.model.SessionStatus
import com.openmate.core.domain.model.QuestionRequest
import com.openmate.core.domain.model.PermissionRequest
import com.openmate.core.domain.model.PermissionReply
import com.openmate.core.domain.repository.FileAttachment
import com.openmate.core.domain.repository.SessionMessageRepository
import com.openmate.core.domain.repository.QuestionRepository
import com.openmate.core.domain.repository.PermissionRepository
import com.openmate.core.domain.repository.SessionRepository
import com.openmate.core.domain.repository.SseEventRepository
import com.openmate.core.domain.repository.TodoRepository
import com.openmate.core.database.ActiveDatabaseProvider
import com.openmate.core.network.OpencodeApiClient
import com.openmate.core.network.dto.BridgeFileContent
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
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.contentOrNull
import java.io.File

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
    private val sseEventRepository: SseEventRepository,
    private val dbProvider: ActiveDatabaseProvider,
    private val syncDebugController: SyncDebugController,
    internal val apiClient: OpencodeApiClient,
) : ViewModel() {
    private val prefs: SharedPreferences = appContext.getSharedPreferences("openmate_settings", Context.MODE_PRIVATE)

    private val _sessionRevert = MutableStateFlow<SessionRevert?>(null)
    val sessionRevert: StateFlow<SessionRevert?> = _sessionRevert.asStateFlow()

    private val _messages = MutableStateFlow<List<SessionMessage>>(emptyList())
    val messages: StateFlow<List<SessionMessage>> = _messages.asStateFlow()

    private var messageWindowState = SessionMessageWindowManager.State(
        messages = emptyList(),
        loadedCount = 30,
        hasOlderMessages = false,
    )

    private val _todos = MutableStateFlow<List<com.openmate.core.domain.model.TodoInfo>>(emptyList())
    val todos: StateFlow<List<com.openmate.core.domain.model.TodoInfo>> = _todos.asStateFlow()

    private val _isStreaming = MutableStateFlow(false)
    val isStreaming: StateFlow<Boolean> = _isStreaming.asStateFlow()

    private val _queuedMessageIds = MutableStateFlow<Set<String>>(emptySet())
    val queuedMessageIds: StateFlow<Set<String>> = _queuedMessageIds.asStateFlow()

    private val _runningAnchors = MutableStateFlow<Map<String, Long>>(emptyMap())
    val runningAnchors: StateFlow<Map<String, Long>> = _runningAnchors.asStateFlow()

    private val _sessionTotalDuration = MutableStateFlow<Long?>(null)
    val sessionTotalDuration: StateFlow<Long?> = _sessionTotalDuration.asStateFlow()

    private val _currentBusyStart = MutableStateFlow<Long?>(null)
    val currentBusyStart: StateFlow<Long?> = _currentBusyStart.asStateFlow()

    private val _sessionRetryStatus = MutableStateFlow<SessionRetryStatus?>(null)
    val sessionRetryStatus: StateFlow<SessionRetryStatus?> = _sessionRetryStatus.asStateFlow()

    private var wasBusy = false
    private val _sessionStatus = MutableStateFlow("")
    val sessionStatus: StateFlow<String> = _sessionStatus.asStateFlow()

    private val _serverBusy = MutableStateFlow<Boolean?>(null)

    private val _userModelMap = MutableStateFlow<Map<String, String>>(emptyMap())
    val userModelMap: StateFlow<Map<String, String>> = _userModelMap.asStateFlow()

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

    private val _isLoadingOlder = MutableStateFlow(false)
    val isLoadingOlder: StateFlow<Boolean> = _isLoadingOlder.asStateFlow()

    private val _hasOlderMessages = MutableStateFlow(false)
    val hasOlderMessages: StateFlow<Boolean> = _hasOlderMessages.asStateFlow()

    private val _isUploading = MutableStateFlow(false)
    val isUploading: StateFlow<Boolean> = _isUploading.asStateFlow()

    private val _errorMessage = MutableStateFlow<String?>(null)
    val errorMessage: StateFlow<String?> = _errorMessage.asStateFlow()

    private val _previewFileState = MutableStateFlow<com.openmate.feature.session.component.FileViewState?>(null)
    val previewFileState: StateFlow<com.openmate.feature.session.component.FileViewState?> = _previewFileState.asStateFlow()

    private val _previewFileContent = MutableStateFlow<BridgeFileContent?>(null)
    val previewFileContent: StateFlow<BridgeFileContent?> = _previewFileContent.asStateFlow()

    private val _previewFileLoading = MutableStateFlow(false)
    val previewFileLoading: StateFlow<Boolean> = _previewFileLoading.asStateFlow()

    private val _disableAutoScroll = MutableStateFlow(false)
    val disableAutoScroll: StateFlow<Boolean> = _disableAutoScroll.asStateFlow()

    fun setDisableAutoScroll(value: Boolean) {
        _disableAutoScroll.value = value
    }

    private val _sessionTitle = MutableStateFlow("")
    val sessionTitle: StateFlow<String> = _sessionTitle.asStateFlow()

    private val _syncLogLines = MutableStateFlow<List<String>>(emptyList())
    val syncLogLines: StateFlow<List<String>> = _syncLogLines.asStateFlow()

    private val _syncLogEntries = MutableStateFlow<List<SyncLogEntry>>(emptyList())
    val syncLogEntries: StateFlow<List<SyncLogEntry>> = _syncLogEntries.asStateFlow()

    private val _revertedPrompt = MutableStateFlow<String?>(null)
    val revertedPrompt: StateFlow<String?> = _revertedPrompt.asStateFlow()

    data class ModelRef(val providerID: String, val modelID: String, val modelName: String)

    private val _providers = MutableStateFlow<ProviderListDto?>(null)
    val providers: StateFlow<ProviderListDto?> = _providers.asStateFlow()

    private val _selectedModel = MutableStateFlow<ModelRef?>(null)
    val selectedModel: StateFlow<ModelRef?> = _selectedModel.asStateFlow()

    private val _selectedVariant = MutableStateFlow<String?>(null)
    val selectedVariant: StateFlow<String?> = _selectedVariant.asStateFlow()

    private val _hasExplicitDefaultVariant = MutableStateFlow(false)
    val hasExplicitDefaultVariant: StateFlow<Boolean> = _hasExplicitDefaultVariant.asStateFlow()

    private val _availableVariants = MutableStateFlow<List<String>>(emptyList())
    val availableVariants: StateFlow<List<String>> = _availableVariants.asStateFlow()

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
        private const val MESSAGE_WINDOW_PAGE_SIZE = 30
    }

    private var currentSessionID: String? = null
    private var currentDirectory: String = ""
    private var draftSessionID: String? = null
    private var observeMsgJob: Job? = null
    private var observeTodoJob: Job? = null
    private var observeQuestionJob: Job? = null
    private var observePermissionJob: Job? = null
    private var observeSyncEventJob: Job? = null
    private var observeRetryStatusJob: Job? = null
    private var observeSyncLogsJob: Job? = null

    init {
        observeSyncLogs()
    }

    fun clearError() {
        _errorMessage.value = null
    }

    private fun observeSyncLogs() {
        observeSyncLogsJob?.cancel()
        observeSyncLogsJob = viewModelScope.launch {
            syncDebugController.logs.collect { entries ->
                _syncLogEntries.value = entries
                _syncLogLines.value = entries.map { it.renderedText }
            }
        }
    }

    fun reconnectSyncSse() {
        syncDebugController.reconnectSse()
    }

    fun triggerManualIncrementalSync() {
        val sid = currentSessionID ?: return
        viewModelScope.launch(Dispatchers.IO) {
            syncDebugController.triggerManualIncrementalSync(sid)
        }
    }

    fun clearSyncLogs() {
        syncDebugController.clearLogs()
    }

    fun copyVisibleSyncLogsToClipboard(lines: List<String>) {
        val text = lines.joinToString("\n")
        val clipboard = appContext.getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
        clipboard.setPrimaryClip(android.content.ClipData.newPlainText("sync_logs", text))
    }

    private fun activeProfileKey(): String? = dbProvider.getActiveProfileId()?.ifBlank { null }

    private fun providerCacheKey(profileId: String): String = "provider_cache::$profileId"

    private fun variantPrefKey(profileId: String, providerID: String, modelID: String): String =
        "variant_pref::$profileId::$providerID::$modelID"

    private fun loadCachedProviders(): ProviderListDto? {
        val profileId = activeProfileKey() ?: return null
        val raw = prefs.getString(providerCacheKey(profileId), null) ?: return null
        return runCatching { Json.decodeFromString<ProviderListDto>(raw) }.getOrNull()
    }

    private fun saveCachedProviders(providers: ProviderListDto) {
        val profileId = activeProfileKey() ?: return
        prefs.edit().putString(providerCacheKey(profileId), Json.encodeToString(providers)).apply()
    }

    private fun saveVariantPreference(providerID: String, modelID: String, variant: String?) {
        val profileId = activeProfileKey() ?: return
        prefs.edit().putString(variantPrefKey(profileId, providerID, modelID), variant ?: "default").apply()
    }

    private fun restoreVariantPreference(providerID: String, modelID: String) {
        val profileId = activeProfileKey()
        if (profileId == null) {
            _selectedVariant.value = null
            _hasExplicitDefaultVariant.value = false
            return
        }
        when (val stored = prefs.getString(variantPrefKey(profileId, providerID, modelID), null)) {
            null -> {
                _selectedVariant.value = null
                _hasExplicitDefaultVariant.value = false
            }
            "default" -> {
                _selectedVariant.value = null
                _hasExplicitDefaultVariant.value = true
            }
            else -> {
                _selectedVariant.value = stored
                _hasExplicitDefaultVariant.value = false
            }
        }
    }

    private fun applySelectedModel(ref: ModelRef) {
        _selectedModel.value = ref
        updateAvailableVariants()
        restoreVariantPreference(ref.providerID, ref.modelID)
    }

    private fun resolvePreviewPath(path: String): String {
        val normalizedPath = path
            .trim()
            .removeSurrounding("`")
            .removeSurrounding("\"")
            .replace('\\', '/')
        if (normalizedPath.isBlank()) return normalizedPath
        val isWindowsAbsolutePath = normalizedPath.length >= 3 &&
            normalizedPath[1] == ':' &&
            normalizedPath[0].isLetter() &&
            normalizedPath[2] == '/'
        if (normalizedPath.startsWith("/") || isWindowsAbsolutePath) {
            return normalizedPath
        }
        if (currentDirectory.isBlank()) return path
        return File(currentDirectory, normalizedPath).path.replace('\\', '/')
    }

    fun openFilePreview(path: String) {
        val resolvedPath = resolvePreviewPath(path)
        viewModelScope.launch(Dispatchers.IO) {
            _previewFileLoading.value = true
            _previewFileContent.value = null
            _previewFileState.value = com.openmate.feature.session.component.FileViewState(
                path = resolvedPath,
                name = resolvedPath.substringAfterLast('/').substringAfterLast('\\'),
            )
            try {
                _previewFileContent.value = apiClient.bridgeReadFile(resolvedPath)
            } catch (e: Exception) {
                _errorMessage.value = e.message ?: "Failed to read file"
            } finally {
                _previewFileLoading.value = false
            }
        }
    }

    fun closeFilePreview() {
        _previewFileState.value = null
        _previewFileContent.value = null
        _previewFileLoading.value = false
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
        if (currentSessionID == sessionID) return
        currentSessionID = sessionID
        observeMsgJob?.cancel()
        observeTodoJob?.cancel()
        observeSyncEventJob?.cancel()
        observeRetryStatusJob?.cancel()
        _selectedModel.value = null
        messageWindowState = SessionMessageWindowManager.State(
            messages = emptyList(),
            loadedCount = MESSAGE_WINDOW_PAGE_SIZE,
            hasOlderMessages = false,
        )
        _messages.value = emptyList()
        _sessionTotalDuration.value = null
        _currentBusyStart.value = null
        _sessionRetryStatus.value = null
        _sessionStatus.value = ""
        _isStreaming.value = false
        _queuedMessageIds.value = emptySet()
        _runningAnchors.value = emptyMap()
        _isLoadingOlder.value = false
        _hasOlderMessages.value = false
        _sessionRevert.value = null
        wasBusy = false

        viewModelScope.launch(Dispatchers.IO) {
            val session = sessionRepository.getSession(sessionID)
            val busy = session?.status == SessionStatus.BUSY || session?.status == SessionStatus.RUNNING
            _serverBusy.value = busy
            _sessionStatus.value = if (busy) SessionStatus.BUSY.name else SessionStatus.IDLE.name
            _currentBusyStart.value = session?.startedAt
            if (session?.title?.isNotBlank() == true) {
                _sessionTitle.value = session.title
            }
            currentDirectory = session?.directory ?: ""
            loadCachedProviders()?.let { _providers.value = it }
            sseEventRepository.setActiveSessionScope(currentDirectory.ifBlank { null }, enabled = true)
            if (session?.totalDuration != null && _sessionTotalDuration.value == null) {
                _sessionTotalDuration.value = session.totalDuration
            }
            val sPID = session?.modelProviderID
            val sMID = session?.modelID
            if (sPID != null && sMID != null && _selectedModel.value == null) {
                applySelectedModel(ModelRef(sPID, sMID, session.modelName ?: sMID))
            }
        }

        viewModelScope.launch(Dispatchers.IO) {
            sessionRepository.observeSession(sessionID).collect { session ->
                if (session != null && session.title.isNotBlank()) {
                    _sessionTitle.value = session.title
                }
                val newRevert = session?.revert
                if (newRevert != null) {
                    val fromId = newRevert.from
                    if (fromId != null) {
                        _sessionRevert.value = newRevert
                    } else {
                        _sessionRevert.value = newRevert
                        viewModelScope.launch(Dispatchers.IO) {
                            val evtId = runCatching { sessionRepository.resolveEvtID(currentSessionID!!, newRevert.messageID) }
                                .onFailure { Log.w(TAG, "resolveEvtID failed for ${newRevert.messageID}", it) }
                                .getOrNull()
                            if (evtId != null) {
                                _sessionRevert.value = newRevert.copy(from = evtId)
                            }
                        }
                    }
                } else {
                    _sessionRevert.value = null
                }
                val busy = session?.status == SessionStatus.BUSY || session?.status == SessionStatus.RUNNING
                if (_serverBusy.value != busy) {
                    _serverBusy.value = busy
                    if (busy && _currentBusyStart.value == null) {
                        _currentBusyStart.value = session?.startedAt ?: System.currentTimeMillis()
                    }
                    recalculateMessageDerivedState(messageWindowState.messages)
                }
            }
        }

        draftSessionID = sessionID
        val savedDraft = loadDraft(sessionID)
        _inputText.value = savedDraft.text
        _attachedFiles.value = savedDraft.files

        observeTodos(sessionID)
        observeQuestions()
        observePermissions()
        observeSyncEvents(sessionID)
        observeRetryStatus(sessionID)
        startPolling(sessionID)

        viewModelScope.launch(Dispatchers.IO) {
            try {
                rebuildInitialWindow(sessionID)
                val lastSeq = sessionMessageRepository.getLastSeq(sessionID)
                val hasLocalMessages = messageWindowState.messages.isNotEmpty()
                val syncResult = if (lastSeq != null && lastSeq > 0 && hasLocalMessages) {
                    sessionMessageRepository.incrementalSync(sessionID)
                } else {
                    sessionMessageRepository.initSync(sessionID, MESSAGE_WINDOW_PAGE_SIZE)
                }
                applySyncResult(syncResult)
            } catch (e: Exception) {
                Log.e(TAG, "sync failed", e)
            }
            try {
                todoRepository.refreshTodos(sessionID)
            } catch (e: Exception) {
                Log.e(TAG, "refreshTodos failed", e)
            }
            try {
                questionRepository.refresh(currentDirectory.ifBlank { "/" })
            } catch (e: Exception) {
                Log.e(TAG, "refreshQuestions failed", e)
            }
            try {
                permissionRepository.refresh(currentDirectory.ifBlank { "/" })
            } catch (e: Exception) {
                Log.e(TAG, "refreshPermissions failed", e)
            }
            try {
                sessionRepository.refreshSessionStatusesFromMessages()
            } catch (e: Exception) {
                Log.e(TAG, "refreshStatusFromMessages failed", e)
            }
            try {
                refreshRetryStatus(sessionID)
            } catch (e: Exception) {
                Log.e(TAG, "refreshRetryStatus failed", e)
            }
            resolveDefaultModel()
        }
    }

    fun refresh() {
        val sid = currentSessionID ?: return
        viewModelScope.launch(Dispatchers.IO) {
            try {
                applySyncResult(sessionMessageRepository.incrementalSync(sid))
                sessionRepository.refreshSessionStatusesFromMessages()
                refreshRetryStatus(sid)
                todoRepository.refreshTodos(sid)
                questionRepository.refresh(currentDirectory.ifBlank { "/" })
                permissionRepository.refresh(currentDirectory.ifBlank { "/" })
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
                    applySyncResult(sessionMessageRepository.incrementalSync(sessionId))
                    sessionRepository.refreshSessionStatusesFromMessages()
                    refreshRetryStatus(sessionId)
                } catch (e: Exception) {
                    Log.e(TAG, "poll sync failed", e)
                }
                try {
                    questionRepository.refresh(currentDirectory.ifBlank { "/" })
                    permissionRepository.refresh(currentDirectory.ifBlank { "/" })
                } catch (e: Exception) {
                    Log.e(TAG, "poll question/permission refresh failed", e)
                }
                try {
                    todoRepository.refreshTodos(sessionId)
                } catch (e: Exception) {
                    Log.e(TAG, "poll todo refresh failed", e)
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
        observeSyncEventJob?.cancel()
        observeRetryStatusJob?.cancel()
        observeSyncLogsJob?.cancel()
        pollJob?.cancel()
        sseEventRepository.setActiveSessionScope(null, enabled = false)
        val sid = currentSessionID
        val start = _currentBusyStart.value
        if (sid != null && start != null) {
            val increment = maxOf(0L, System.currentTimeMillis() - start)
            viewModelScope.launch(Dispatchers.IO) {
                try {
                    sessionRepository.addSessionDuration(sid, increment)
                } catch (_: Exception) {}
            }
        }
    }

    fun sendMessage(sessionID: String) {
        val text = _inputText.value.ifBlank { return }
        syncDebugController.log(
            level = SyncLogLevel.Info,
            category = SyncLogCategory.Manual,
            sessionId = sessionID,
            title = "发送消息",
            message = "send message requested textLength=${text.length} attachments=${_attachedFiles.value.size}",
        )
        _inputText.value = ""
        val model = _selectedModel.value
        val agent = _selectedAgent.value
        val variant = _selectedVariant.value
        val files = _attachedFiles.value
        _attachedFiles.value = emptyList()
        clearDraft(sessionID)
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val apiFiles = files.map { com.openmate.core.network.OpencodeApiClient.FileAttachment(it.path, it.filename, it.mime) }
                apiClient.sendPrompt(sessionID, text, model?.providerID, model?.modelID, agent, apiFiles, currentDirectory.ifBlank { null }, variant)
                applySyncResult(sessionMessageRepository.incrementalSync(sessionID))
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
        _serverBusy.value = false
        _currentBusyStart.value = null
        _runningAnchors.value = emptyMap()
        wasBusy = false
        _sessionStatus.value = SessionStatus.IDLE.name
        recalculateMessageDerivedState(messageWindowState.messages)
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
            applySelectedModel(recent)
            return
        }
        try {
            val result = loadCachedProviders() ?: apiClient.getProviders().also {
                _providers.value = it
                saveCachedProviders(it)
            }
            _providers.value = result
            val connected = result.connected
            val defaults = result.default
            for (providerID in connected) {
                val modelID = defaults[providerID] ?: continue
                val provider = result.all.find { it.id == providerID } ?: continue
                val model = provider.models[modelID] ?: provider.models.values.firstOrNull() ?: continue
                val ref = ModelRef(providerID, modelID, model.name.ifBlank { modelID })
                applySelectedModel(ref)
                val sid = currentSessionID ?: return
                try {
                    sessionRepository.updateSessionModel(sid, providerID, modelID, ref.modelName)
                } catch (_: Exception) {}
                return
            }
        } catch (e: Exception) {
            Log.e(TAG, "resolveDefaultModel failed", e)
        }
    }

    fun loadProviders(forceRefresh: Boolean = false) {
        if (!forceRefresh) {
            loadCachedProviders()?.let {
                _providers.value = it
                updateAvailableVariants()
                return
            }
        }
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val result = apiClient.getProviders()
                Log.d(TAG, "loadProviders OK: ${result.all.size} providers, connected=${result.connected}")
                _providers.value = result
                saveCachedProviders(result)
                updateAvailableVariants()
            } catch (e: Exception) {
                Log.e(TAG, "loadProviders FAILED: ${e.javaClass.simpleName}: ${e.message}", e)
            }
        }
    }

    fun selectModel(providerID: String, modelID: String, modelName: String) {
        val ref = ModelRef(providerID, modelID, modelName)
        applySelectedModel(ref)
        val updated = (listOf(ref) + _recentModels.value.filter {
            !(it.providerID == providerID && it.modelID == modelID)
        }).take(5)
        _recentModels.value = updated
        saveRecentModels(updated)
        val sid = currentSessionID ?: return
        viewModelScope.launch(Dispatchers.IO) {
            try {
                sessionRepository.updateSessionModel(sid, providerID, modelID, modelName)
            } catch (_: Exception) {}
        }
    }

    fun clearSelectedModel() {
        _selectedModel.value = null
        _selectedVariant.value = null
        _hasExplicitDefaultVariant.value = false
        _availableVariants.value = emptyList()
    }

    fun selectVariant(variant: String?) {
        _selectedVariant.value = variant
        _hasExplicitDefaultVariant.value = variant == null
        _selectedModel.value?.let { model ->
            saveVariantPreference(model.providerID, model.modelID, variant)
        }
    }

    private fun updateAvailableVariants() {
        val model = _selectedModel.value ?: run {
            _availableVariants.value = emptyList()
            return
        }
        val provider = _providers.value?.all?.find { it.id == model.providerID }
        val modelInfo = provider?.models?.get(model.modelID)
        val variants = modelInfo?.variants?.keys?.toList() ?: emptyList()
        _availableVariants.value = variants
    }

    fun getWorkingDirectory(): String = currentDirectory

    fun setAgent(agent: String) {
        _selectedAgent.value = agent
    }

    private val _skills = MutableStateFlow<List<SkillInfoDto>>(emptyList())
    val skills: StateFlow<List<SkillInfoDto>> = _skills.asStateFlow()

    fun compact(sessionID: String) {
        val model = _selectedModel.value ?: return
        viewModelScope.launch(Dispatchers.IO) {
            try {
                apiClient.summarizeSession(sessionID, model.providerID, model.modelID, currentDirectory.ifBlank { null })
            } catch (e: Exception) {
                Log.e(TAG, "compact failed", e)
                _errorMessage.value = "Compact failed: ${e.message}"
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

    fun loadOlderMessages() {
        val sessionId = currentSessionID ?: return
        if (_isLoadingOlder.value) return
        if (!messageWindowState.hasOlderMessages) return
        val first = messageWindowState.messages.firstOrNull() ?: return

        _isLoadingOlder.value = true

        viewModelScope.launch(Dispatchers.IO) {
            try {
                val older = sessionMessageRepository.getOlderPage(
                    sessionId = sessionId,
                    beforeTimeCreated = first.timeCreated,
                    beforeId = first.id,
                    limit = MESSAGE_WINDOW_PAGE_SIZE,
                )
                messageWindowState = SessionMessageWindowManager.prependOlderPage(
                    state = messageWindowState,
                    olderPage = older,
                    hasOlderMessages = older.size == MESSAGE_WINDOW_PAGE_SIZE,
                )
                _messages.value = messageWindowState.messages
                _hasOlderMessages.value = messageWindowState.hasOlderMessages
                recalculateMessageDerivedState(messageWindowState.messages)
            } catch (e: Exception) {
                Log.e(TAG, "loadOlderMessages failed", e)
            } finally {
                _isLoadingOlder.value = false
            }
        }
    }

    private suspend fun rebuildInitialWindow(sessionId: String) {
        val recent = sessionMessageRepository.getRecentWindow(sessionId, MESSAGE_WINDOW_PAGE_SIZE)
        messageWindowState = SessionMessageWindowManager.State(
            messages = recent,
            loadedCount = recent.size,
            hasOlderMessages = recent.size == MESSAGE_WINDOW_PAGE_SIZE,
        )
        _messages.value = recent
        _hasOlderMessages.value = messageWindowState.hasOlderMessages
        logMessageWindowState(
            sessionId = sessionId,
            source = "initial-window",
            messages = recent,
        )
        recalculateMessageDerivedState(recent)
    }

    private fun applySyncResult(result: SessionMessageSyncResult) {
        val hadNoMessages = messageWindowState.messages.isEmpty()
        messageWindowState = SessionMessageWindowManager.apply(messageWindowState, result.changes)
        if (hadNoMessages && result.changes.size >= MESSAGE_WINDOW_PAGE_SIZE) {
            messageWindowState = messageWindowState.copy(hasOlderMessages = true)
        }
        _messages.value = messageWindowState.messages
        _hasOlderMessages.value = messageWindowState.hasOlderMessages
        currentSessionID?.let { sessionId ->
            logMessageWindowState(
                sessionId = sessionId,
                source = "apply-sync",
                messages = messageWindowState.messages,
            )
        }
        recalculateMessageDerivedState(messageWindowState.messages)
    }

    private fun logMessageWindowState(
        sessionId: String,
        source: String,
        messages: List<SessionMessage>,
    ) {
        val last = messages.lastOrNull()
        val lastCompaction = messages.lastOrNull {
            it.type == "compaction" || (it.type == "assistant" && it.data.contains("\"agent\":\"compaction\""))
        }
        syncDebugController.log(
            level = SyncLogLevel.Info,
            category = SyncLogCategory.Sync,
            sessionId = sessionId,
            title = "消息窗口更新",
            message = "source=$source count=${messages.size} last=${last?.id ?: "none"}/${last?.type ?: "none"} lastCompaction=${lastCompaction?.id ?: "none"}/${lastCompaction?.type ?: "none"}",
        )
    }

    private fun recalculateMessageDerivedState(list: List<SessionMessage>) {
        val lastMsg = list.lastOrNull()
        val lastAssistant = list.lastOrNull { it.type == "assistant" }
        val hasBusyAssistant = _serverBusy.value == true
        val lastAssistantFinish = lastAssistant?.let {
            runCatching {
                Json.parseToJsonElement(it.data).jsonObject["finish"]?.jsonPrimitive?.contentOrNull
            }.getOrNull()
        }
        _isStreaming.value = lastAssistant?.completedAt == null || (lastAssistantFinish != "stop" && lastAssistantFinish != "length")
        _queuedMessageIds.value = buildQueuedMessageIds(list)
        _userModelMap.value = buildUserModelMap(list)

        if (_sessionRetryStatus.value != null) {
            _currentBusyStart.value = null
            _sessionStatus.value = SessionStatus.BUSY.name
            wasBusy = false
            return
        }

        if (hasBusyAssistant) {
            if (_currentBusyStart.value == null) {
                _currentBusyStart.value = SessionBusyTimerCalculator.findBusyStart(list) ?: System.currentTimeMillis()
            }
        } else if (wasBusy) {
            val start = _currentBusyStart.value ?: System.currentTimeMillis()
            val increment = maxOf(0L, System.currentTimeMillis() - start)
            val newTotal = (_sessionTotalDuration.value ?: 0L) + increment
            _sessionTotalDuration.value = newTotal
            _currentBusyStart.value = null
            viewModelScope.launch(Dispatchers.IO) {
                try {
                    sessionRepository.addSessionDuration(currentSessionID!!, increment)
                } catch (_: Exception) {}
            }
        }

        _sessionStatus.value = if (hasBusyAssistant) SessionStatus.BUSY.name else SessionStatus.IDLE.name
        wasBusy = hasBusyAssistant

        val lastFinalizedAssistant = list.lastOrNull { it.type == "assistant" && it.roundMark }
        if (lastFinalizedAssistant != null && _selectedModel.value == null) {
            val data = runCatching { Json.parseToJsonElement(lastFinalizedAssistant.data).jsonObject }.getOrNull()
            val modelObj = data?.get("model")?.jsonObject
            val pId = modelObj?.get("providerID")?.jsonPrimitive?.contentOrNull
            val mId = modelObj?.get("modelID")?.jsonPrimitive?.contentOrNull
            if (pId != null && mId != null) {
                val ref = ModelRef(pId, mId, mId)
                applySelectedModel(ref)
                viewModelScope.launch(Dispatchers.IO) {
                    try {
                        sessionRepository.updateSessionModel(currentSessionID!!, pId, mId, mId)
                    } catch (_: Exception) {}
                }
            }
        }

        val anchors = _runningAnchors.value.toMutableMap()
        val now = android.os.SystemClock.elapsedRealtime()
        val wallNow = System.currentTimeMillis()
        for (msg in list) {
            if ((msg.type == "assistant" || msg.type == "compaction") && msg.completedAt == null) {
                val data = runCatching { Json.parseToJsonElement(msg.data).jsonObject }.getOrNull()
                val finish = data?.get("finish")?.jsonPrimitive?.contentOrNull
                if (finish == null && !anchors.containsKey(msg.id)) {
                    val elapsed = (wallNow - msg.timeCreated).coerceAtLeast(0L)
                    anchors[msg.id] = now - elapsed
                }
            }
        }
        anchors.keys.retainAll { id -> list.any { it.id == id && it.completedAt == null } }
        _runningAnchors.value = anchors
    }

    private suspend fun refreshRetryStatus(sessionId: String) {
        val latest = sessionRepository.getSessionRetryStatus(sessionId)
        if (latest != null || _sessionRetryStatus.value != null) {
            _sessionRetryStatus.value = latest
            recalculateMessageDerivedState(messageWindowState.messages)
        }
    }

    private fun observeRetryStatus(sessionId: String) {
        observeRetryStatusJob?.cancel()
        observeRetryStatusJob = viewModelScope.launch(Dispatchers.IO) {
            sessionRepository.observeSessionRetryStatus(sessionId).collect { status ->
                _sessionRetryStatus.value = status
                recalculateMessageDerivedState(messageWindowState.messages)
            }
        }
    }

    private fun buildQueuedMessageIds(list: List<SessionMessage>): Set<String> {
        val lastAssistantIdx = list.indexOfLast { it.type == "assistant" }
        val lastAssistantRoundMark = list.getOrNull(lastAssistantIdx)?.roundMark ?: true
        val firstUserAfterLastAssistant = if (lastAssistantIdx >= 0) {
            list.drop(lastAssistantIdx + 1).indexOfFirst { it.type == "user" }
                .let { if (it >= 0) lastAssistantIdx + 1 + it else -1 }
        } else -1
        return buildSet {
            for ((idx, msg) in list.withIndex()) {
                if (idx > lastAssistantIdx && msg.type == "user") {
                    if (!lastAssistantRoundMark) add(msg.id)
                    else if (idx != firstUserAfterLastAssistant) add(msg.id)
                }
            }
        }
    }

    private fun buildUserModelMap(list: List<SessionMessage>): Map<String, String> {
        val map = mutableMapOf<String, String>()
        var currentModel: String? = null
        for (msg in list) {
            when (msg.type) {
                "model-switched" -> {
                    val data = runCatching { Json.parseToJsonElement(msg.data).jsonObject }.getOrNull()
                    val model = data?.get("model")?.jsonObject
                    val provider = model?.get("providerID")?.jsonPrimitive?.contentOrNull ?: ""
                    val modelId = model?.get("id")?.jsonPrimitive?.contentOrNull ?: ""
                    currentModel = if (provider.isNotBlank()) "$provider/$modelId" else modelId.ifBlank { null }
                }
                "assistant" -> {
                    val data = runCatching { Json.parseToJsonElement(msg.data).jsonObject }.getOrNull()
                    val model = data?.get("model")?.jsonObject
                    val provider = model?.get("providerID")?.jsonPrimitive?.contentOrNull ?: ""
                    val modelId = model?.get("id")?.jsonPrimitive?.contentOrNull ?: ""
                    val m = if (provider.isNotBlank()) "$provider/$modelId" else modelId.ifBlank { null }
                    if (m != null) currentModel = m
                }
                "user" -> {
                    if (currentModel != null) map[msg.id] = currentModel
                }
            }
        }
        return map
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

    private fun observeSyncEvents(sessionId: String) {
        observeSyncEventJob = viewModelScope.launch(Dispatchers.IO) {
            try {
                sessionMessageRepository.observeSyncEvents().collect { event ->
                    if (event.sessionId == sessionId) {
                        applySyncResult(event.result)
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "observeSyncEvents failed", e)
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

    fun revertToMessage(sessionID: String, messageID: String) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val busy = isSessionBusy()
                val recentMessages = sessionMessageRepository.getRecentWindow(sessionID, 100)
                val targetMsg = recentMessages.find { it.id == messageID }
                if (targetMsg == null) {
                    syncDebugController.log(
                        level = SyncLogLevel.Error,
                        category = SyncLogCategory.Manual,
                        sessionId = sessionID,
                        title = "revert失败",
                        message = "messageID=$messageID not found in recent window",
                    )
                    return@launch
                }
                val msgID = sessionRepository.resolveMessageID(sessionID, targetMsg.timeCreated)
                if (msgID == null) {
                    syncDebugController.log(
                        level = SyncLogLevel.Error,
                        category = SyncLogCategory.Manual,
                        sessionId = sessionID,
                        title = "revert失败",
                        message = "evtID=$messageID timeCreated=${targetMsg.timeCreated} resolve msg_ ID failed",
                    )
                    return@launch
                }
                syncDebugController.log(
                    level = SyncLogLevel.Info,
                    category = SyncLogCategory.Manual,
                    sessionId = sessionID,
                    title = "revert请求",
                    message = "evtID=$messageID → msgID=$msgID busy=$busy dir=${currentDirectory.ifBlank { "null" }}",
                )
                if (busy) {
                    syncDebugController.log(
                        level = SyncLogLevel.Info,
                        category = SyncLogCategory.Manual,
                        sessionId = sessionID,
                        title = "abort先执行",
                        message = "session busy, aborting before revert",
                    )
                    sessionRepository.abortSession(sessionID, currentDirectory.ifBlank { null })
                    delay(500)
                }
                val promptText = runCatching {
                    val obj = Json.parseToJsonElement(targetMsg.data).jsonObject
                    obj["text"]?.jsonPrimitive?.contentOrNull
                }.getOrNull()
                sessionRepository.revertSession(sessionID, msgID, directory = currentDirectory.ifBlank { null })
                _sessionRevert.value = SessionRevert(messageID = msgID, from = targetMsg.id)
                _revertedPrompt.value = promptText
                applySyncResult(sessionMessageRepository.incrementalSync(sessionID))
            } catch (e: Exception) {
                Log.e(TAG, "revertToMessage failed", e)
                syncDebugController.log(
                    level = SyncLogLevel.Error,
                    category = SyncLogCategory.Manual,
                    sessionId = sessionID,
                    title = "revert失败",
                    message = "evtID=$messageID error=${e.message}",
                )
                _errorMessage.value = "Revert failed: ${e.message}"
            }
        }
    }

    fun revertToLastMessage(sessionID: String) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val recent = sessionMessageRepository.getRecentWindow(sessionID, 100)
                val lastUser = recent.lastOrNull { it.type == "user" }
                if (lastUser != null) {
                    revertToMessage(sessionID, lastUser.id)
                } else {
                    syncDebugController.log(
                        level = SyncLogLevel.Warn,
                        category = SyncLogCategory.Manual,
                        sessionId = sessionID,
                        title = "revert跳过",
                        message = "no user message found in recent window",
                    )
                }
            } catch (e: Exception) {
                Log.e(TAG, "revertToLastMessage failed", e)
                _errorMessage.value = "Revert failed: ${e.message}"
            }
        }
    }

    fun unrevert(sessionID: String) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                syncDebugController.log(
                    level = SyncLogLevel.Info,
                    category = SyncLogCategory.Manual,
                    sessionId = sessionID,
                    title = "unrevert请求",
                    message = "dir=${currentDirectory.ifBlank { "null" }}",
                )
                sessionRepository.unrevertSession(sessionID, directory = currentDirectory.ifBlank { null })
                applySyncResult(sessionMessageRepository.incrementalSync(sessionID))
            } catch (e: Exception) {
                Log.e(TAG, "unrevert failed", e)
                syncDebugController.log(
                    level = SyncLogLevel.Error,
                    category = SyncLogCategory.Manual,
                    sessionId = sessionID,
                    title = "unrevert失败",
                    message = "error=${e.message}",
                )
                _errorMessage.value = "Unrevert failed: ${e.message}"
            }
        }
    }

    fun clearRevertedPrompt() {
        _revertedPrompt.value = null
    }

    private fun isSessionBusy(): Boolean {
        return _sessionStatus.value == SessionStatus.BUSY.name || _sessionStatus.value == SessionStatus.RUNNING.name
    }
}

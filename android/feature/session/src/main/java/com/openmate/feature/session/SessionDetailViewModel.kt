package com.openmate.feature.session

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.openmate.core.data.sync.SyncDebugController
import com.openmate.core.data.sync.SyncLogCategory
import com.openmate.core.data.sync.SyncSseStarter
import com.openmate.core.data.sync.SyncLogEntry
import com.openmate.core.data.sync.SyncLogLevel
import com.openmate.core.network.dto.AgentDto
import com.openmate.core.domain.model.Session
import com.openmate.core.domain.model.ConnectionStatus
import com.openmate.core.domain.model.SessionRevert
import com.openmate.core.domain.model.SessionTokens
import com.openmate.core.domain.model.SessionMessage
import com.openmate.core.domain.model.SessionRetryStatus
import com.openmate.core.domain.model.SessionMessageSyncResult
import com.openmate.core.domain.model.SessionStatus
import com.openmate.core.domain.model.QuestionRequest
import com.openmate.core.domain.model.PermissionRequest
import com.openmate.core.domain.model.PermissionReply
import com.openmate.core.domain.repository.FileAttachment
import com.openmate.core.domain.repository.ConnectionRepository
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
import com.openmate.core.network.dto.McpServerEntry
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
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.debounce
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
import java.util.concurrent.ConcurrentHashMap
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
    private val connectionRepository: ConnectionRepository,
    private val dbProvider: ActiveDatabaseProvider,
    private val syncDebugController: SyncDebugController,
    private val syncSseStarter: SyncSseStarter,
    internal val apiClient: OpencodeApiClient,
    private val bridgeFileOpener: BridgeFileOpener,
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

    private val _sessionTokens = MutableStateFlow<SessionTokens?>(null)
    val sessionTokens: StateFlow<SessionTokens?> = _sessionTokens.asStateFlow()

    private val _sessionCost = MutableStateFlow(0.0)
    val sessionCost: StateFlow<Double> = _sessionCost.asStateFlow()

    private val _lastMessageTokens = MutableStateFlow<Long?>(null)
    val lastMessageTokens: StateFlow<Long?> = _lastMessageTokens.asStateFlow()

    private val _lastMessageCost = MutableStateFlow(0.0)
    val lastMessageCost: StateFlow<Double> = _lastMessageCost.asStateFlow()

    val contextLimit: StateFlow<Long?>
        get() = _contextLimit
    private val _contextLimit = MutableStateFlow<Long?>(null)

    private val _currentBusyStart = MutableStateFlow<Long?>(null)
    val currentBusyStart: StateFlow<Long?> = _currentBusyStart.asStateFlow()

    private val _sessionRetryStatus = MutableStateFlow<SessionRetryStatus?>(null)
    val sessionRetryStatus: StateFlow<SessionRetryStatus?> = _sessionRetryStatus.asStateFlow()

    private var wasBusy = false
    private var messageIdCounter = 0
    private val _sessionStatus = MutableStateFlow("")
    val sessionStatus: StateFlow<String> = _sessionStatus.asStateFlow()

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

    private val _isSending = MutableStateFlow(false)
    val isSending: StateFlow<Boolean> = _isSending.asStateFlow()

    private val _errorMessage = MutableStateFlow<String?>(null)
    val errorMessage: StateFlow<String?> = _errorMessage.asStateFlow()

    private val _previewFileState = MutableStateFlow<com.openmate.feature.session.component.FileViewState?>(null)
    val previewFileState: StateFlow<com.openmate.feature.session.component.FileViewState?> = _previewFileState.asStateFlow()

    private val _previewFileContent = MutableStateFlow<BridgeFileContent?>(null)
    val previewFileContent: StateFlow<BridgeFileContent?> = _previewFileContent.asStateFlow()

    private val _previewFileLoading = MutableStateFlow(false)
    val previewFileLoading: StateFlow<Boolean> = _previewFileLoading.asStateFlow()

    private val _connectionStatus = MutableStateFlow(ConnectionStatus.DISCONNECTED)
    val connectionStatus: StateFlow<ConnectionStatus> = _connectionStatus.asStateFlow()

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

    private fun resolveModelName(providerID: String, modelID: String, fallback: String? = null): String {
        val providers = _providers.value
        val cached = providers?.all
            ?.find { it.id == providerID }?.models?.get(modelID)?.name
        val result = cached?.ifBlank { null } ?: fallback?.ifBlank { null } ?: modelID
        return result
    }

    private fun refreshModelName() {
        val cur = _selectedModel.value ?: return
        val providers = _providers.value
        val resolved = resolveModelName(cur.providerID, cur.modelID)
        if (resolved != cur.modelName) {
            _selectedModel.value = cur.copy(modelName = resolved)
        }
    }

    private val providerBucket = ConcurrentHashMap<String, ProviderListDto>()
    private val agentBucket = ConcurrentHashMap<String, List<AgentDto>>()
    private val skillBucket = ConcurrentHashMap<String, List<SkillInfoDto>>()
    private val mcpBucket = ConcurrentHashMap<String, List<McpServerEntry>>()

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

    private val _agents = MutableStateFlow<List<AgentDto>>(emptyList())
    val agents: StateFlow<List<AgentDto>> = _agents.asStateFlow()

    private val _skills = MutableStateFlow<List<SkillInfoDto>>(emptyList())
    val skills: StateFlow<List<SkillInfoDto>> = _skills.asStateFlow()

    private val _mcpServers = MutableStateFlow<List<McpServerEntry>>(emptyList())
    val mcpServers: StateFlow<List<McpServerEntry>> = _mcpServers.asStateFlow()

    private fun syncInstanceCaches() {
        val key = activeProfileKey()
        _providers.value = key?.let { providerBucket[it] }
        _agents.value = key?.let { agentBucket[it] } ?: emptyList()
        _skills.value = key?.let { skillBucket[it] } ?: emptyList()
        _mcpServers.value = key?.let { mcpBucket[it] } ?: emptyList()
    }

    private var isModelOverridden = false
    private var isAgentOverridden = false

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
    private var observeSessionJob: Job? = null
    private var observeTodoJob: Job? = null
    private var observeQuestionJob: Job? = null
    private var observePermissionJob: Job? = null
    private var observeSyncEventJob: Job? = null
    private var observeMessageSyncJob: Job? = null
    private var observeSessionErrorJob: Job? = null
    private var observeRetryStatusJob: Job? = null
    private var observeSyncLogsJob: Job? = null

    init {
        observeSyncLogs()
        observeConnectionStatus()
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

    private var lastObservedProfileId: String? = null

    private fun observeConnectionStatus() {
        viewModelScope.launch {
            connectionRepository.connectionStatus.collect { status ->
                _connectionStatus.value = status
            }
        }
        viewModelScope.launch {
            connectionRepository.activeProfile.collect { profile ->
                val newId = profile?.id
                if (newId != lastObservedProfileId) {
                    lastObservedProfileId = newId
                    syncInstanceCaches()
                }
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

    private fun agentCacheKey(profileId: String): String = "agent_cache::$profileId"

    private fun loadCachedAgents(): List<AgentDto>? {
        val profileId = activeProfileKey() ?: return null
        val raw = prefs.getString(agentCacheKey(profileId), null) ?: return null
        return runCatching { Json.decodeFromString<List<AgentDto>>(raw) }.getOrNull()
    }

    private fun saveCachedAgents(agents: List<AgentDto>) {
        val profileId = activeProfileKey() ?: return
        prefs.edit().putString(agentCacheKey(profileId), Json.encodeToString(agents)).apply()
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
        if (bridgeFileOpener.isBinaryFile(resolvedPath)) {
            viewModelScope.launch(Dispatchers.IO) {
                bridgeFileOpener.openFile(
                    resolvedPath,
                    onTextPreview = {},
                    onError = { _errorMessage.value = it },
                )
            }
        } else {
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
                    _errorMessage.value = appContext.getString(R.string.failed_read_file)
                } finally {
                    _previewFileLoading.value = false
                }
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
                    _errorMessage.value = appContext.getString(R.string.upload_failed_msg)
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
        if (currentSessionID == sessionID) {
            viewModelScope.launch(Dispatchers.IO) {
                try {
                    sessionMessageRepository.incrementalSync(sessionID)
                } catch (_: Exception) {}
            }
            return
        }
        currentSessionID = sessionID
        observeMsgJob?.cancel()
        observeSessionJob?.cancel()
        observeTodoJob?.cancel()
        observeSyncEventJob?.cancel()
        observeMessageSyncJob?.cancel()
        observeSessionErrorJob?.cancel()
        observeRetryStatusJob?.cancel()
        pollJob?.cancel()
        _selectedModel.value = null
        val profileKey = activeProfileKey()
        if (profileKey != null) {
            providerBucket[profileKey]?.let { _providers.value = it }
                ?: loadCachedProviders()?.let { providerBucket[profileKey] = it; _providers.value = it }
            agentBucket[profileKey]?.let { _agents.value = it }
                ?: loadCachedAgents()?.let { agentBucket[profileKey] = it; _agents.value = it }
            skillBucket[profileKey]?.let { _skills.value = it }
            mcpBucket[profileKey]?.let { _mcpServers.value = it }
        }
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
        isModelOverridden = false
        isAgentOverridden = false
        syncSseStarter.setActiveSession(sessionID)

        viewModelScope.launch(Dispatchers.IO) {
            val session = sessionRepository.getSession(sessionID)
            if (session?.title?.isNotBlank() == true) {
                _sessionTitle.value = session.title
            }
            currentDirectory = session?.directory ?: ""
            if (session?.totalDuration != null && _sessionTotalDuration.value == null) {
                _sessionTotalDuration.value = session.totalDuration
            }
            _sessionTokens.value = session?.tokens
            _sessionCost.value = session?.cost ?: 0.0
            session?.let { updateContextLimit(it) }
            sseEventRepository.setActiveSessionScope(currentDirectory.ifBlank { null }, enabled = true)

            val sPID = session?.modelProviderID
            val sMID = session?.modelID
            if (sPID != null && sMID != null && !isModelOverridden) {
                val current = _selectedModel.value
                if (current == null || current.providerID != sPID || current.modelID != sMID) {
                    applySelectedModel(ModelRef(sPID, sMID, resolveModelName(sPID, sMID, session.modelName)))
                }
            }
            if (!session?.agent.isNullOrBlank() && !isAgentOverridden) {
                _selectedAgent.value = session.agent!!
            }
        }

        viewModelScope.launch(Dispatchers.IO) {
            sessionRepository.observeSession(sessionID).collect { session ->
                if (session != null && session.title.isNotBlank()) {
                    _sessionTitle.value = session.title
                }
                val newRevert = session?.revert
                if (newRevert != null) {
                    _sessionRevert.value = newRevert
                } else {
                    _sessionRevert.value = null
                }
                session?.let {
                    _sessionTokens.value = it.tokens
                    _sessionCost.value = it.cost
                    updateContextLimit(it)
                    if (it.modelProviderID != null && it.modelID != null) {
                        if (!isModelOverridden) {
                            val p = it.modelProviderID!!
                            val m = it.modelID!!
                            applySelectedModel(ModelRef(p, m, resolveModelName(p, m, it.modelName)))
                        }
                    }
                    val sAgent = it.agent
                    if (!sAgent.isNullOrBlank() && !isAgentOverridden) {
                        if (_selectedAgent.value != sAgent) {
                            _selectedAgent.value = sAgent
                        }
                    }
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
                val pk = activeProfileKey()
                if (pk != null && providerBucket[pk] == null) {
                    loadCachedProviders()?.let { providerBucket[pk] = it; _providers.value = it }
                }
                rebuildInitialWindow(sessionID)
                val lastSeq = sessionMessageRepository.getLastSeq(sessionID)
                val hasLocalMessages = messageWindowState.messages.isNotEmpty()
                if (lastSeq != null && lastSeq > 0 && hasLocalMessages) {
                    sessionMessageRepository.incrementalSync(sessionID)
                } else {
                    applySyncResult(sessionMessageRepository.initSync(sessionID, MESSAGE_WINDOW_PAGE_SIZE))
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
                refreshRetryStatus(sessionID)
            } catch (e: Exception) {
                Log.e(TAG, "refreshRetryStatus failed", e)
            }
            loadAgents(forceRefresh = true)
            resolveDefaultModel()
        }
    }

    fun refresh() {
        val sid = currentSessionID ?: return
        viewModelScope.launch(Dispatchers.IO) {
            try {
                sessionMessageRepository.incrementalSync(sid)
                refreshRetryStatus(sid)
                todoRepository.refreshTodos(sid)
                questionRepository.refresh(currentDirectory.ifBlank { "/" })
                permissionRepository.refresh(currentDirectory.ifBlank { "/" })
            } catch (e: Exception) {
                Log.e(TAG, "manual refresh failed", e)
            }
        }
    }

    fun resync(eventCount: Int) {
        val sid = currentSessionID ?: return
        viewModelScope.launch(Dispatchers.IO) {
            try {
                sessionMessageRepository.rollbackSeq(sid, eventCount.toLong())
                _messages.value = emptyList()
                _hasOlderMessages.value = false
                _isStreaming.value = false
                messageWindowState = SessionMessageWindowManager.State(
                    messages = emptyList(),
                    loadedCount = 0,
                    hasOlderMessages = false,
                )
                sessionMessageRepository.incrementalSync(sid)
                refreshRetryStatus(sid)
                todoRepository.refreshTodos(sid)
            } catch (e: Exception) {
                Log.e(TAG, "resync failed", e)
                _errorMessage.value = appContext.getString(R.string.resync_failed)
            }
        }
    }

    suspend fun getCurrentSeq(): Long? {
        val sid = currentSessionID ?: return null
        return sessionMessageRepository.getLastSeq(sid)
    }

    private val _isUploadingDb = MutableStateFlow(false)
    val isUploadingDb: StateFlow<Boolean> = _isUploadingDb.asStateFlow()

    fun uploadDatabase() {
        val profileId = dbProvider.getActiveProfileId() ?: return
        val sid = currentSessionID ?: return
        val dir = currentDirectory.ifBlank { "." }
        viewModelScope.launch(Dispatchers.IO) {
            _isUploadingDb.value = true
            try {
                val dbFile = dbProvider.getDatabaseFile(profileId)
                val files = listOf(
                    dbFile to dbFile.name,
                    File(dbFile.path + "-wal") to dbFile.name + "-wal",
                    File(dbFile.path + "-shm") to dbFile.name + "-shm",
                )
                dbProvider.clearActive()
                pollJob?.cancel()
                observeSyncEventJob?.cancel()
                observeMessageSyncJob?.cancel()
                observeSessionErrorJob?.cancel()
                try {
                    for ((file, name) in files) {
                        if (!file.exists()) continue
                        val bytes = file.readBytes()
                        val serverPath = "$dir/.openmate/debug/$name"
                        apiClient.bridgeUploadFile(serverPath, bytes, createDirs = true)
                    }
                } finally {
                    dbProvider.setActive(profileId)
                    loadSession(sid)
                }
                syncDebugController.log(
                    level = SyncLogLevel.Info,
                    category = SyncLogCategory.Sync,
                    sessionId = sid,
                    message = "数据库上传完成 uploaded db files to $dir/.openmate/debug/",
                )
            } catch (e: Exception) {
                Log.e(TAG, "uploadDatabase failed", e)
                _errorMessage.value = appContext.getString(R.string.upload_db_failed)
            } finally {
                _isUploadingDb.value = false
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
        observeMessageSyncJob?.cancel()
        observeSessionErrorJob?.cancel()
        observeRetryStatusJob?.cancel()
        observeSyncLogsJob?.cancel()
        pollJob?.cancel()
        sseEventRepository.setActiveSessionScope(null, enabled = false)
        syncSseStarter.setActiveSession(null)
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
            message = "发送消息 send message requested textLength=${text.length} attachments=${_attachedFiles.value.size}",
        )
        _isSending.value = true
        val model = _selectedModel.value
        val agent = _selectedAgent.value
        val variant = _selectedVariant.value
        val files = _attachedFiles.value
        val sendModelPID = if (isModelOverridden) model?.providerID else null
        val sendModelMID = if (isModelOverridden) model?.modelID else null
        val sendAgent = if (isAgentOverridden) agent else null
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val apiFiles = files.map { com.openmate.core.network.OpencodeApiClient.FileAttachment(it.path, it.filename, it.mime) }
                apiClient.sendPrompt(sessionID, text, sendModelPID, sendModelMID, sendAgent, apiFiles, currentDirectory.ifBlank { null }, variant)
                _inputText.value = ""
                _attachedFiles.value = emptyList()
                clearDraft(sessionID)
                sessionMessageRepository.incrementalSync(sessionID)
            } catch (e: Exception) {
                Log.e(TAG, "sendMessage FAILED: ${e.javaClass.simpleName}: ${e.message}", e)
                _errorMessage.value = appContext.getString(R.string.send_failed)
            } finally {
                _isSending.value = false
            }
        }
    }

    fun updateInput(text: String) {
        _inputText.value = text
        draftSessionID?.let { saveDraft(it, text, _attachedFiles.value) }
    }

    fun abort(sessionID: String) {
        _currentBusyStart.value = null
        _runningAnchors.value = emptyMap()
        wasBusy = false
        _sessionStatus.value = SessionStatus.IDLE.name
        persistSessionStatus(SessionStatus.IDLE.name)
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
                _errorMessage.value = appContext.getString(R.string.rename_session_failed)
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
                _errorMessage.value = appContext.getString(R.string.delete_session_msg_failed)
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
            val key = activeProfileKey()
            val bucketHit = key?.let { providerBucket[it] }
            val result = bucketHit
                ?: loadCachedProviders()?.also { if (key != null) providerBucket[key] = it }
                ?: apiClient.getProviders().also {
                    _providers.value = it
                    saveCachedProviders(it)
                    if (key != null) providerBucket[key] = it
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
                isModelOverridden = true
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
        val key = activeProfileKey()
        if (!forceRefresh && key != null) {
            providerBucket[key]?.let {
                _providers.value = it
                updateAvailableVariants()
                refreshModelName()
                return
            }
            loadCachedProviders()?.let {
                providerBucket[key] = it
                _providers.value = it
                updateAvailableVariants()
                refreshModelName()
                return
            }
        }
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val result = apiClient.getProviders()
                Log.d(TAG, "loadProviders OK: ${result.all.size} providers, connected=${result.connected}")
                _providers.value = result
                if (key != null) providerBucket[key] = result
                saveCachedProviders(result)
                updateAvailableVariants()
                refreshModelName()
            } catch (e: Exception) {
                Log.e(TAG, "loadProviders FAILED: ${e.javaClass.simpleName}: ${e.message}", e)
            }
        }
    }

    fun loadAgents(forceRefresh: Boolean = false) {
        val key = activeProfileKey()
        if (!forceRefresh && key != null) {
            agentBucket[key]?.let {
                _agents.value = it
                return
            }
            loadCachedAgents()?.let {
                agentBucket[key] = it
                _agents.value = it
                return
            }
        }
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val result = apiClient.getAgents()
                _agents.value = result
                if (key != null) agentBucket[key] = result
                saveCachedAgents(result)
            } catch (e: Exception) {
                Log.e(TAG, "loadAgents FAILED: ${e.javaClass.simpleName}: ${e.message}", e)
            }
        }
    }

    fun selectModel(providerID: String, modelID: String, modelName: String) {
        val ref = ModelRef(providerID, modelID, modelName)
        val current = _selectedModel.value
        applySelectedModel(ref)
        if (current == null || current.providerID != providerID || current.modelID != modelID) {
            isModelOverridden = true
        }
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
        val prev = _selectedAgent.value
        _selectedAgent.value = agent
        if (prev != agent) {
            isAgentOverridden = true
        }
    }

    fun compact(sessionID: String) {
        val model = _selectedModel.value ?: return
        viewModelScope.launch(Dispatchers.IO) {
            try {
                apiClient.summarizeSession(sessionID, model.providerID, model.modelID, currentDirectory.ifBlank { null })
            } catch (e: Exception) {
                Log.e(TAG, "compact failed", e)
                _errorMessage.value = appContext.getString(R.string.compact_failed)
            }
        }
    }

    fun initSession(sessionID: String) {
        val model = _selectedModel.value ?: return
        val messageID = generateMessageID()
        viewModelScope.launch(Dispatchers.IO) {
            try {
                apiClient.initSession(sessionID, model.providerID, model.modelID, messageID, currentDirectory.ifBlank { null })
            } catch (e: Exception) {
                Log.w(TAG, "initSession request completed with: ${e.javaClass.simpleName}: ${e.message}")
            }
        }
    }

    private fun generateMessageID(): String {
        val timestamp = System.currentTimeMillis()
        val counter = ++messageIdCounter
        val now = (timestamp.toLong() shl 12) + counter
        val timeHex = String.format("%012x", now)
        val random = (1..14).map { "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz".random() }.joinToString("")
        return "msg_$timeHex$random"
    }

    fun loadSkills() {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val result = apiClient.getSkills()
                _skills.value = result
                activeProfileKey()?.let { skillBucket[it] = result }
            } catch (e: Exception) {
                Log.e(TAG, "loadSkills failed", e)
            }
        }
    }

    fun useSkill(skillName: String) {
        val current = _inputText.value
        _inputText.value = (if (current.isNotBlank()) "$current\n" else "") + "/skill $skillName"
    }

    fun loadMcpServers() {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val result = apiClient.getMcpStatus(currentDirectory.ifBlank { null })
                _mcpServers.value = result
                activeProfileKey()?.let { mcpBucket[it] = result }
            } catch (e: Exception) {
                Log.e(TAG, "loadMcpServers failed", e)
            }
        }
    }

    fun toggleMcp(name: String, enable: Boolean) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                if (enable) {
                    apiClient.connectMcp(name, currentDirectory.ifBlank { null })
                } else {
                    apiClient.disconnectMcp(name, currentDirectory.ifBlank { null })
                }
                _mcpServers.value = apiClient.getMcpStatus(currentDirectory.ifBlank { null })
            } catch (e: Exception) {
                Log.e(TAG, "toggleMcp failed", e)
                _errorMessage.value = appContext.getString(R.string.mcp_toggle_failed)
            }
        }
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

    fun loadMoreSearchMessages(userTurns: Int) {
        val sessionId = currentSessionID ?: return
        if (_isLoadingOlder.value) return
        val first = messageWindowState.messages.firstOrNull() ?: return

        _isLoadingOlder.value = true

        viewModelScope.launch(Dispatchers.IO) {
            try {
                val older = sessionMessageRepository.getOlderPageByUserTurns(
                    sessionId = sessionId,
                    beforeTimeCreated = first.timeCreated,
                    beforeId = first.id,
                    userTurns = userTurns,
                )
                if (older.isNotEmpty()) {
                    messageWindowState = SessionMessageWindowManager.prependOlderPage(
                        state = messageWindowState,
                        olderPage = older,
                        hasOlderMessages = true,
                    )
                    _messages.value = messageWindowState.messages
                    _hasOlderMessages.value = messageWindowState.hasOlderMessages
                    recalculateMessageDerivedState(messageWindowState.messages)
                } else {
                    _hasOlderMessages.value = false
                    messageWindowState = messageWindowState.copy(hasOlderMessages = false)
                }
            } catch (e: Exception) {
                Log.e(TAG, "loadMoreSearchMessages failed", e)
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
        if (result.changes.isNotEmpty()) {
            recalculateMessageDerivedState(messageWindowState.messages)
        }
        if (result.hasTodoEvent) {
            val sid = currentSessionID ?: return
            viewModelScope.launch(Dispatchers.IO) {
                try {
                    todoRepository.refreshTodos(sid)
                } catch (e: Exception) {
                    Log.e(TAG, "sync-triggered todo refresh failed", e)
                }
            }
        }
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
            message = "消息窗口更新 source=$source count=${messages.size} last=${last?.id ?: "none"}/${last?.type ?: "none"} lastCompaction=${lastCompaction?.id ?: "none"}/${lastCompaction?.type ?: "none"}",
        )
    }

    private fun persistSessionStatus(status: String) {
        val sid = currentSessionID ?: return
        viewModelScope.launch(Dispatchers.IO) {
            try {
                sessionRepository.updateSessionStatus(sid, status)
            } catch (_: Exception) {}
        }
    }

    private fun recalculateMessageDerivedState(list: List<SessionMessage>) {
        val lastAssistant = list.lastOrNull { it.type == "assistant" }
        val lastAssistantFinish = lastAssistant?.let {
            runCatching {
                Json.parseToJsonElement(it.data).jsonObject["finish"]?.jsonPrimitive?.contentOrNull
            }.getOrNull()
        }
        val lastAssistantError = lastAssistant?.let {
            runCatching {
                Json.parseToJsonElement(it.data).jsonObject["error"]
            }.getOrNull()
        }

        val lastAssistantFinished = lastAssistantFinish == "stop" || lastAssistantFinish == "error" || lastAssistantFinish == "length" || lastAssistantFinish == "other" || lastAssistantError != null

        var lastTotalFound: Long? = null
        var lastCostFound = 0.0
        for (i in list.indices.reversed()) {
            val msg = list[i]
            if (msg.type != "assistant") continue
            val obj = runCatching { Json.parseToJsonElement(msg.data).jsonObject }.getOrNull() ?: continue
            val tokensObj = obj["tokens"]?.jsonObject ?: continue
            val t = tokensObj["total"]?.jsonPrimitive?.content?.toLongOrNull()
                ?: ((tokensObj["input"]?.jsonPrimitive?.content?.toLongOrNull() ?: 0L) +
                    (tokensObj["output"]?.jsonPrimitive?.content?.toLongOrNull() ?: 0L) +
                    (tokensObj["reasoning"]?.jsonPrimitive?.content?.toLongOrNull() ?: 0L) +
                    (tokensObj["cache"]?.jsonObject?.get("read")?.jsonPrimitive?.content?.toLongOrNull() ?: 0L) +
                    (tokensObj["cache"]?.jsonObject?.get("write")?.jsonPrimitive?.content?.toLongOrNull() ?: 0L))
            if (t == 0L) continue
            lastTotalFound = t
            lastCostFound = obj["cost"]?.jsonPrimitive?.content?.toDoubleOrNull() ?: 0.0
            var msgPID = obj["providerID"]?.jsonPrimitive?.contentOrNull
            var msgMID = obj["modelID"]?.jsonPrimitive?.contentOrNull
            if (msgPID == null || msgMID == null) {
                val modelObj = obj["model"]?.jsonObject
                msgPID = modelObj?.get("providerID")?.jsonPrimitive?.contentOrNull
                msgMID = modelObj?.get("id")?.jsonPrimitive?.contentOrNull
            }
            if (msgPID != null && msgMID != null) {
                val limit = _providers.value?.all
                    ?.find { it.id == msgPID }?.models?.get(msgMID)?.limit?.context
                if (limit != null && limit > 0) _contextLimit.value = limit
                if (!isModelOverridden) {
                    val cur = _selectedModel.value
                    val resolved = resolveModelName(msgPID, msgMID)
                    if (cur == null || cur.providerID != msgPID || cur.modelID != msgMID) {
                        applySelectedModel(ModelRef(msgPID, msgMID, resolved))
                    } else if (cur.modelName != resolved) {
                        _selectedModel.value = cur.copy(modelName = resolved)
                    }
                } else if (_selectedModel.value?.providerID == msgPID && _selectedModel.value?.modelID == msgMID) {
                    isModelOverridden = false
                }
            }
            break
        }
        _lastMessageTokens.value = lastTotalFound
        _lastMessageCost.value = lastCostFound

        if (!isAgentOverridden) {
            for (i in list.indices.reversed()) {
                val msg = list[i]
                if (msg.type != "assistant") continue
                val obj = runCatching { Json.parseToJsonElement(msg.data).jsonObject }.getOrNull() ?: continue
                val msgAgent = obj["agent"]?.jsonPrimitive?.contentOrNull
                if (!msgAgent.isNullOrBlank()) {
                    if (_selectedAgent.value != msgAgent) {
                        _selectedAgent.value = msgAgent
                    }
                    break
                }
            }
        } else {
            for (i in list.indices.reversed()) {
                val msg = list[i]
                if (msg.type != "assistant") continue
                val obj = runCatching { Json.parseToJsonElement(msg.data).jsonObject }.getOrNull() ?: continue
                val msgAgent = obj["agent"]?.jsonPrimitive?.contentOrNull
                if (!msgAgent.isNullOrBlank()) {
                    if (_selectedAgent.value == msgAgent) {
                        isAgentOverridden = false
                    }
                    break
                }
            }
        }

        val isReverting = _sessionRevert.value != null
        val lastIsUserWaitingReply = list.lastOrNull()?.type == "user" && !isReverting
        _isStreaming.value = lastIsUserWaitingReply || lastAssistant == null || (!lastAssistantFinished && !isReverting)
        _queuedMessageIds.value = buildQueuedMessageIds(list)
        _userModelMap.value = buildUserModelMap(list)

        val localBusy = _isStreaming.value
        if (localBusy) {
            if (_currentBusyStart.value == null) {
                val fromWindow = SessionBusyTimerCalculator.findBusyStart(list)
                if (fromWindow != null) {
                    _currentBusyStart.value = fromWindow
                } else {
                    val sid = currentSessionID
                    if (sid != null) {
                        viewModelScope.launch(Dispatchers.IO) {
                            val dbStart = sessionMessageRepository.findBusyStartTime(sid)
                            if (dbStart != null && _currentBusyStart.value == null && _isStreaming.value) {
                                _currentBusyStart.value = dbStart
                            }
                        }
                    }
                }
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

        _sessionStatus.value = if (localBusy) SessionStatus.BUSY.name else SessionStatus.IDLE.name
        wasBusy = localBusy
        persistSessionStatus(_sessionStatus.value)

        val lastFinalizedAssistant = list.lastOrNull { it.type == "assistant" && it.roundMark }
        if (lastFinalizedAssistant != null && _selectedModel.value == null) {
            val data = runCatching { Json.parseToJsonElement(lastFinalizedAssistant.data).jsonObject }.getOrNull()
            val modelObj = data?.get("model")?.jsonObject
            val pId = modelObj?.get("providerID")?.jsonPrimitive?.contentOrNull
            val mId = modelObj?.get("modelID")?.jsonPrimitive?.contentOrNull
            if (pId != null && mId != null) {
                val ref = ModelRef(pId, mId, resolveModelName(pId, mId))
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
                    _pendingPermissions.value = list.take(1)
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
        observeMessageSyncJob = viewModelScope.launch(Dispatchers.IO) {
            try {
                sseEventRepository.observeMessageSyncNeeded()
                    .conflate()
                    .debounce(300)
                    .collect { sid ->
                        if (sid == sessionId) {
                            try {
                                sessionMessageRepository.incrementalSync(sid)
                            } catch (e: Exception) {
                                Log.e(TAG, "SSE-triggered incremental sync failed", e)
                            }
                        }
                    }
            } catch (e: Exception) {
                Log.e(TAG, "observeMessageSyncNeeded failed", e)
            }
        }
        observeSessionErrorJob = viewModelScope.launch(Dispatchers.IO) {
            try {
                sseEventRepository.observeSessionErrors()
                    .collect { (sid, _) ->
                        if (sid == sessionId) {
                            try {
                                sessionMessageRepository.incrementalSync(sid)
                            } catch (e: Exception) {
                                Log.e(TAG, "session.error-triggered incremental sync failed", e)
                            }
                        }
                    }
            } catch (e: Exception) {
                Log.e(TAG, "observeSessionErrors failed", e)
            }
        }
    }

    fun replyQuestion(requestID: String, answers: List<List<String>>) {
        val sid = currentSessionID ?: return
        viewModelScope.launch(Dispatchers.IO) {
            try {
                questionRepository.reply(requestID, answers, currentDirectory.ifBlank { null })
                sessionMessageRepository.incrementalSync(sid)
            } catch (e: Exception) {
                Log.e(TAG, "replyQuestion failed", e)
            }
        }
    }

    fun rejectQuestion(requestID: String) {
        val sid = currentSessionID ?: return
        viewModelScope.launch(Dispatchers.IO) {
            try {
                questionRepository.reject(requestID, currentDirectory.ifBlank { null })
                sessionMessageRepository.incrementalSync(sid)
            } catch (e: Exception) {
                Log.e(TAG, "rejectQuestion failed", e)
            }
        }
    }

    fun replyPermission(requestID: String, reply: PermissionReply, message: String? = null) {
        val sid = currentSessionID ?: return
        viewModelScope.launch(Dispatchers.IO) {
            try {
                permissionRepository.reply(requestID, reply, message, currentDirectory.ifBlank { null })
                permissionRepository.refresh(currentDirectory.ifBlank { "" })
                sessionMessageRepository.incrementalSync(sid)
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
                        message = "revert失败 messageID=$messageID not found in recent window",
                    )
                    return@launch
                }
                val msgID = sessionRepository.resolveMessageID(sessionID, targetMsg.timeCreated)
                if (msgID == null) {
                    syncDebugController.log(
                        level = SyncLogLevel.Error,
                        category = SyncLogCategory.Manual,
                        sessionId = sessionID,
                        message = "revert失败 evtID=$messageID timeCreated=${targetMsg.timeCreated} resolve msg_ ID failed",
                    )
                    return@launch
                }
                syncDebugController.log(
                    level = SyncLogLevel.Info,
                    category = SyncLogCategory.Manual,
                    sessionId = sessionID,
                    message = "revert请求 evtID=$messageID → msgID=$msgID busy=$busy dir=${currentDirectory.ifBlank { "null" }}",
                )
                if (busy) {
                    syncDebugController.log(
                        level = SyncLogLevel.Info,
                        category = SyncLogCategory.Manual,
                        sessionId = sessionID,
                        message = "abort先执行 session busy, aborting before revert",
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
                if (!promptText.isNullOrBlank()) {
                    _inputText.value = promptText
                }
                sessionMessageRepository.incrementalSync(sessionID)
            } catch (e: Exception) {
                Log.e(TAG, "revertToMessage failed", e)
                syncDebugController.log(
                    level = SyncLogLevel.Error,
                    category = SyncLogCategory.Manual,
                    sessionId = sessionID,
                    message = "revert失败 evtID=$messageID error=${e.message}",
                )
                _errorMessage.value = appContext.getString(R.string.revert_failed)
            }
        }
    }

    fun revertToLastMessage(sessionID: String) {
        val revertFromId = _sessionRevert.value?.from
        val visibleMessages = if (revertFromId != null) {
            val revertTs = extractMsgTimestamp(revertFromId)
            messageWindowState.messages.filter { extractMsgTimestamp(it.id) < revertTs }
        } else {
            messageWindowState.messages
        }
        val lastUser = visibleMessages.lastOrNull { it.type == "user" }
        if (lastUser != null) {
            revertToMessage(sessionID, lastUser.id)
        } else {
            syncDebugController.log(
                level = SyncLogLevel.Warn,
                category = SyncLogCategory.Manual,
                sessionId = sessionID,
                message = "revert跳过 no visible user message found",
            )
        }
    }

    fun unrevert(sessionID: String) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                syncDebugController.log(
                    level = SyncLogLevel.Info,
                    category = SyncLogCategory.Manual,
                    sessionId = sessionID,
                    message = "unrevert请求 dir=${currentDirectory.ifBlank { "null" }}",
                )
                sessionRepository.unrevertSession(sessionID, directory = currentDirectory.ifBlank { null })
                sessionMessageRepository.incrementalSync(sessionID)
            } catch (e: Exception) {
                Log.e(TAG, "unrevert failed", e)
                syncDebugController.log(
                    level = SyncLogLevel.Error,
                    category = SyncLogCategory.Manual,
                    sessionId = sessionID,
                    message = "unrevert失败 error=${e.message}",
                )
                _errorMessage.value = appContext.getString(R.string.unrevert_failed)
            }
        }
    }

    fun clearRevertedPrompt() {
        _revertedPrompt.value = null
    }

    private fun isSessionBusy(): Boolean {
        return _sessionStatus.value == SessionStatus.BUSY.name || _sessionStatus.value == SessionStatus.RUNNING.name
    }

    private fun updateContextLimit(session: Session) {
        val pID = session.modelProviderID ?: return
        val mID = session.modelID ?: return
        val limit = _providers.value?.all
            ?.find { it.id == pID }?.models?.get(mID)?.limit?.context ?: return
        if (limit > 0) _contextLimit.value = limit
    }
}

private fun extractMsgTimestamp(msgId: String): Long {
    val hex = msgId.split("_")[1].take(12)
    return hex.toLong(16) / 4096L
}

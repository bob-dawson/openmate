package com.openmate.feature.session

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.contentOrNull
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowLeft
import androidx.compose.material.icons.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Redo
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.Undo
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Card
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.FilterChip
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.VerticalDivider
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import com.openmate.feature.session.R
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.hilt.navigation.compose.hiltViewModel
import com.openmate.core.ui.component.TopBar
import com.openmate.core.domain.model.ConnectionStatus
import com.openmate.core.common.AutoFollowTracker
import com.openmate.feature.session.component.ChatInputBar
import com.openmate.feature.session.component.FileViewer
import com.openmate.core.domain.model.SessionMessage
import com.openmate.feature.session.component.SessionMessageRenderer
import com.openmate.feature.session.component.SessionMessageSearchPanel
import com.openmate.feature.session.component.AgentPickerSheet
import com.openmate.feature.session.component.McpPickerSheet
import com.openmate.feature.session.component.ModelPickerSheet
import com.openmate.feature.session.component.SelectedModel
import com.openmate.feature.session.component.SkillPickerSheet
import com.openmate.feature.session.component.TodoListCard
import com.openmate.core.domain.model.SessionRetryStatus
import com.openmate.core.domain.model.SessionStatus
import com.openmate.core.domain.model.PermissionReply
import com.openmate.core.common.formatDurationMillis
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

internal fun shouldScrollToBottomOnInitialLoad(
    messageCount: Int,
    previousMessageCount: Int,
    hasSavedScrollPosition: Boolean,
): Boolean {
    return messageCount > 0 && previousMessageCount == 0 && !hasSavedScrollPosition
}

internal fun sessionDetailMenuItems(): List<String> = listOf(
    "Rename",
    "Delete",
    "Skill",
    "同步日志",
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SessionDetailScreen(
    sessionID: String,
    onBack: () -> Unit,
    onNavigateToSubtask: (subtaskSessionID: String, title: String) -> Unit = { _, _ -> },
    onNavigateToBrowser: (directory: String) -> Unit = {},
    viewModel: SessionDetailViewModel = hiltViewModel(),
) {
    val messages by viewModel.messages.collectAsState()
    val isLoading by viewModel.isLoading.collectAsState()
    val isStreaming by viewModel.isStreaming.collectAsState()
    val queuedMessageIds by viewModel.queuedMessageIds.collectAsState()
    val runningAnchors by viewModel.runningAnchors.collectAsState()
    val sessionTotalDuration by viewModel.sessionTotalDuration.collectAsState()
    val currentBusyStart by viewModel.currentBusyStart.collectAsState()
    val sessionRetryStatus by viewModel.sessionRetryStatus.collectAsState()
    val inputText by viewModel.inputText.collectAsState()
    val sessionTitle by viewModel.sessionTitle.collectAsState()
    val errorMessage by viewModel.errorMessage.collectAsState()
    val todos by viewModel.todos.collectAsState()
    val isUploading by viewModel.isUploading.collectAsState()
    val isSending by viewModel.isSending.collectAsState()
    val providers by viewModel.providers.collectAsState()
    val selectedModel by viewModel.selectedModel.collectAsState()
    val selectedVariant by viewModel.selectedVariant.collectAsState()
    val hasExplicitDefaultVariant by viewModel.hasExplicitDefaultVariant.collectAsState()
    val availableVariants by viewModel.availableVariants.collectAsState()
    val recentModels by viewModel.recentModels.collectAsState()
    val selectedAgent by viewModel.selectedAgent.collectAsState()
    val agents by viewModel.agents.collectAsState()
    val sessionStatus by viewModel.sessionStatus.collectAsState()
    val connectionStatus by viewModel.connectionStatus.collectAsState()
    val sessionRevert by viewModel.sessionRevert.collectAsState()
    val skills by viewModel.skills.collectAsState()
    val mcpServers by viewModel.mcpServers.collectAsState()
    val attachedFiles by viewModel.attachedFiles.collectAsState()
    val pendingQuestions by viewModel.pendingQuestions.collectAsState()
    val pendingPermissions by viewModel.pendingPermissions.collectAsState()
    val syncLogEntries by viewModel.syncLogEntries.collectAsState()
    val hasOlderMessages by viewModel.hasOlderMessages.collectAsState()
    val isLoadingOlder by viewModel.isLoadingOlder.collectAsState()
    val previewFileState by viewModel.previewFileState.collectAsState()
    val previewFileContent by viewModel.previewFileContent.collectAsState()
    val previewFileLoading by viewModel.previewFileLoading.collectAsState()
    val userModelMap by viewModel.userModelMap.collectAsState()
    val savedIndex = rememberSaveable(sessionID) { mutableIntStateOf(0) }
    val savedOffset = rememberSaveable(sessionID) { mutableIntStateOf(0) }
    val listState = rememberLazyListState(
        initialFirstVisibleItemIndex = savedIndex.intValue,
        initialFirstVisibleItemScrollOffset = savedOffset.intValue,
    )
    val listRestored = rememberSaveable(sessionID) { mutableStateOf(false) }
    val coroutineScope = rememberCoroutineScope()
    val context = LocalContext.current
    val prefs = remember { context.getSharedPreferences("settings", android.content.Context.MODE_PRIVATE) }
    val showReasoning by remember { mutableStateOf(prefs.getBoolean("show_reasoning", true)) }
    val compactMode by remember { mutableStateOf(prefs.getBoolean("compact_mode", false)) }

    val snackbarHostState = remember { SnackbarHostState() }

    var menuExpanded by remember { mutableStateOf(false) }
    var showRenameDialog by remember { mutableStateOf(false) }
    var showDeleteDialog by remember { mutableStateOf(false) }
    var showRevertDialog by remember { mutableStateOf(false) }
    var showResyncDialog by remember { mutableStateOf(false) }
    var showModelPicker by remember { mutableStateOf(false) }
    var showVariantPicker by remember { mutableStateOf(false) }
    var showSkillPicker by remember { mutableStateOf(false) }
    var showMcpPicker by remember { mutableStateOf(false) }
    var showAgentPicker by remember { mutableStateOf(false) }
    var showSearch by remember { mutableStateOf(false) }
    var showSyncLogs by remember { mutableStateOf(false) }
    var renameText by remember { mutableStateOf("") }
    val autoFollowTracker = remember { AutoFollowTracker() }
    val savedShouldFollow = rememberSaveable(sessionID) { mutableStateOf(true) }
    LaunchedEffect(Unit) {
        autoFollowTracker.restoreState(savedShouldFollow.value)
    }
    LaunchedEffect(autoFollowTracker.shouldFollow) {
        savedShouldFollow.value = autoFollowTracker.shouldFollow
    }
    var prevImeBottom by remember { mutableIntStateOf(0) }
    var pagingTriggerState by remember(sessionID) { mutableStateOf(SessionPagingTrigger.State(lastTriggeredFirstMessageId = null)) }
    var pendingRestoreAnchor by remember(sessionID) { mutableStateOf<String?>(null) }
    val compactActionEnabled = sessionStatus == SessionStatus.IDLE.name
    val compactActionLabel = if (sessionStatus == SessionStatus.COMPACTING.name) {
        stringResource(R.string.compacting)
    } else {
        stringResource(R.string.compact)
    }

    val imeBottom = WindowInsets.ime.getBottom(LocalDensity.current)
    LaunchedEffect(imeBottom) {
        if (imeBottom != prevImeBottom) {
            prevImeBottom = imeBottom
            autoFollowTracker.onKeyboardAnimationStarted()
            delay(350)
            autoFollowTracker.onKeyboardAnimationEnded()
        }
    }

    val prevMessageCount = rememberSaveable(sessionID) { mutableIntStateOf(0) }
    LaunchedEffect(messages.size) {
        autoFollowTracker.onMessagesChanged(messages.size, isLoading)
        if (shouldScrollToBottomOnInitialLoad(messages.size, prevMessageCount.intValue, listRestored.value)) {
            delay(300)
            if (listState.layoutInfo.totalItemsCount > 0) {
                listState.scrollToItem(listState.layoutInfo.totalItemsCount - 1)
            }
        }
        prevMessageCount.intValue = messages.size
    }

    LaunchedEffect(listState.firstVisibleItemIndex, listState.firstVisibleItemScrollOffset) {
        if (listState.firstVisibleItemIndex > 0 || listState.firstVisibleItemScrollOffset > 0) {
            savedIndex.intValue = listState.firstVisibleItemIndex
            savedOffset.intValue = listState.firstVisibleItemScrollOffset
            listRestored.value = true
        }
    }

    val contentUpdateKey = remember(messages) {
        val last = messages.lastOrNull()
        if (last == null) "" else listOf(last.id, last.timeUpdated, last.completedAt, last.data).joinToString("|")
    }
    LaunchedEffect(contentUpdateKey) {
        if (messages.isNotEmpty() && prevMessageCount.intValue == messages.size) {
            autoFollowTracker.onContentUpdated()
        }
    }

    LaunchedEffect(listState.isScrollInProgress) {
        if (listState.isScrollInProgress) {
            autoFollowTracker.onScrollStarted(listState.canScrollForward)
        } else {
            autoFollowTracker.onScrollStopped(listState.canScrollForward)
        }
    }

    LaunchedEffect(listState.canScrollForward, listState.isScrollInProgress) {
        if (listState.isScrollInProgress) {
            autoFollowTracker.onScrollPositionChanged(listState.canScrollForward)
        }
    }

    suspend fun scrollToBottom() {
        runAutoScroll(
            messageCount = messages.size,
            canScrollForward = { listState.canScrollForward },
            onStarted = autoFollowTracker::onAutoScrollStarted,
            onEnded = autoFollowTracker::onAutoScrollEnded,
            scroll = { index -> listState.animateScrollToItem(index) },
        )
    }

    LaunchedEffect(autoFollowTracker.scrollVersion) {
        if (autoFollowTracker.consumeShouldScrollToBottom()) {
            scrollToBottom()
        }
    }

    val needFollowScroll by remember {
        derivedStateOf {
            autoFollowTracker.shouldAutoFollow(
                canScrollForward = listState.canScrollForward,
                isScrollInProgress = listState.isScrollInProgress,
            )
        }
    }
    LaunchedEffect(needFollowScroll) {
        if (needFollowScroll && !autoFollowTracker.consumeShouldScrollToBottom()) {
            scrollToBottom()
        }
    }

    val firstMessageId = messages.firstOrNull()?.id
    LaunchedEffect(
        listState.firstVisibleItemIndex,
        listState.firstVisibleItemScrollOffset,
        firstMessageId,
        hasOlderMessages,
        isLoadingOlder,
        pendingRestoreAnchor,
    ) {
        if (pendingRestoreAnchor != null) return@LaunchedEffect
        if (
            SessionPagingTrigger.shouldLoadOlder(
                state = pagingTriggerState,
                firstVisibleItemIndex = listState.firstVisibleItemIndex,
                firstVisibleItemScrollOffset = listState.firstVisibleItemScrollOffset,
                firstMessageId = firstMessageId,
                hasOlderMessages = hasOlderMessages,
                isLoadingOlder = isLoadingOlder,
                shouldFollow = autoFollowTracker.shouldFollow,
            )
        ) {
            pendingRestoreAnchor = firstMessageId
            pagingTriggerState = SessionPagingTrigger.onTriggered(pagingTriggerState, firstMessageId)
            viewModel.loadOlderMessages()
        }
    }

    LaunchedEffect(isLoadingOlder, pendingRestoreAnchor, messages) {
        val anchor = pendingRestoreAnchor ?: return@LaunchedEffect
        if (isLoadingOlder) return@LaunchedEffect
        val newIndex = messages.indexOfFirst { it.id == anchor }
        if (newIndex > 0) {
            listState.scrollToItem(newIndex)
        }
        pendingRestoreAnchor = null
    }

    val showScrollToBottom by remember {
        derivedStateOf { !autoFollowTracker.shouldFollow && listState.canScrollForward }
    }

    LaunchedEffect(sessionID) {
        viewModel.loadSession(sessionID)
    }

    val lifecycleOwner = androidx.lifecycle.compose.LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = androidx.lifecycle.LifecycleEventObserver { _, event ->
            if (event == androidx.lifecycle.Lifecycle.Event.ON_RESUME) {
                viewModel.restoreDraft()
                viewModel.consumePendingPath()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
    }

    LaunchedEffect(errorMessage) {
        errorMessage?.let {
            snackbarHostState.showSnackbar(it)
            viewModel.clearError()
        }
    }

    val fileState = previewFileState
    if (fileState != null) {
        BackHandler(enabled = true) {
            viewModel.closeFilePreview()
        }
        FileViewer(
            state = fileState,
            fileContent = previewFileContent,
            isLoading = previewFileLoading,
            error = "",
            onBack = { viewModel.closeFilePreview() },
        )
        return
    }

    if (showSyncLogs) {
        BackHandler(enabled = true) {
            showSyncLogs = false
        }
        SyncLogScreen(
            currentSessionId = sessionID,
            logEntries = syncLogEntries,
            onBack = { showSyncLogs = false },
            onCopy = { visibleLines -> viewModel.copyVisibleSyncLogsToClipboard(visibleLines) },
            onClear = viewModel::clearSyncLogs,
            onReconnectSse = viewModel::reconnectSyncSse,
            onManualIncrementalSync = viewModel::triggerManualIncrementalSync,
        )
        return
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        contentWindowInsets = WindowInsets.systemBars.union(WindowInsets.ime),
        topBar = {
            TopBar(
                title = sessionTitle.ifBlank { stringResource(R.string.chat) },
                onBack = onBack,
                titleContent = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = sessionTitle.ifBlank { stringResource(R.string.chat) },
                            style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f, fill = false),
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        ConnectionDot(status = connectionStatus)
                    }
                },
                actions = {
                    IconButton(
                        onClick = { showSearch = true },
                    ) {
                        Icon(
                            Icons.Default.Search,
                            contentDescription = stringResource(R.string.search_messages),
                            tint = MaterialTheme.colorScheme.onSurface,
                        )
                    }
                    Box {
                        IconButton(onClick = { menuExpanded = true }) {
                            Icon(
                                Icons.Default.MoreVert,
                                contentDescription = stringResource(R.string.content_desc_more),
                                tint = MaterialTheme.colorScheme.onSurface,
                            )
                        }
                        DropdownMenu(
                            expanded = menuExpanded,
                            onDismissRequest = { menuExpanded = false },
                        ) {
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.rename)) },
                                onClick = {
                                    menuExpanded = false
                                    renameText = sessionTitle
                                    showRenameDialog = true
                                },
                            )
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.delete)) },
                                onClick = {
                                    menuExpanded = false
                                    showDeleteDialog = true
                                },
                            )
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.skill)) },
                                onClick = {
                                    menuExpanded = false
                                    viewModel.loadSkills()
                                    showSkillPicker = true
                                },
                            )
                            DropdownMenuItem(
                                text = { Text("MCP") },
                                onClick = {
                                    menuExpanded = false
                                    viewModel.loadMcpServers()
                                    showMcpPicker = true
                                },
                            )
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.abort)) },
                                onClick = {
                                    menuExpanded = false
                                    viewModel.abort(sessionID)
                                },
                            )
                            if (selectedModel != null) {
                                DropdownMenuItem(
                                    text = { Text(stringResource(R.string.init_session)) },
                                    onClick = {
                                        menuExpanded = false
                                        viewModel.initSession(sessionID)
                                    },
                                    enabled = currentBusyStart == null,
                                )
                            }
                            if (selectedModel != null) {
                                DropdownMenuItem(
                                    text = { Text(compactActionLabel) },
                                    onClick = {
                                        menuExpanded = false
                                        viewModel.compact(sessionID)
                                    },
                                    enabled = compactActionEnabled,
                                )
                            }
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 16.dp, vertical = 6.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text(
                                    "调试",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.outline,
                                )
                                HorizontalDivider(modifier = Modifier.weight(1f).padding(start = 8.dp))
                            }
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.resync)) },
                                onClick = {
                                    menuExpanded = false
                                    showResyncDialog = true
                                },
                            )
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.sync_logs)) },
                                onClick = {
                                    menuExpanded = false
                                    showSyncLogs = true
                                },
                            )
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.upload_database)) },
                                onClick = {
                                    menuExpanded = false
                                    viewModel.uploadDatabase()
                                },
                                enabled = !viewModel.isUploadingDb.value,
                            )
                        }
                    }
                },
            )
        },
    ) { padding ->
        val isBusy = currentBusyStart != null || sessionRetryStatus != null
        val displayMessages = remember(messages, queuedMessageIds, isBusy, sessionRevert) {
            val revertFromId = sessionRevert?.from
            val filtered = if (revertFromId != null) {
                val revertTs = extractMsgTimestamp(revertFromId)
                messages.filter { extractMsgTimestamp(it.id) < revertTs }
            } else messages
            if (!isBusy || queuedMessageIds.isEmpty()) filtered
            else {
                val queued = mutableListOf<SessionMessage>()
                val rest = mutableListOf<SessionMessage>()
                for (msg in filtered) {
                    if (msg.id in queuedMessageIds) queued.add(msg)
                    else rest.add(msg)
                }
                rest + queued
            }
        }

        val previousUserMessageIndex by remember(displayMessages) {
            derivedStateOf {
                val idx = listState.firstVisibleItemIndex
                if (idx <= 0) -1
                else displayMessages.subList(0, minOf(idx, displayMessages.size)).indexOfLast { it.type == "user" }
            }
        }

        val nextUserMessageIndex by remember(displayMessages) {
            derivedStateOf {
                val idx = listState.firstVisibleItemIndex
                if (idx < 0 || displayMessages.isEmpty()) -1
                else {
                    val searchFrom = minOf(idx + 1, displayMessages.size)
                    val found = displayMessages.subList(searchFrom, displayMessages.size)
                        .indexOfFirst { it.type == "user" }
                    if (found < 0) -1 else searchFrom + found
                }
            }
        }

        val lastTotal by viewModel.lastMessageTokens.collectAsStateWithLifecycle()
        val lastCost by viewModel.lastMessageCost.collectAsStateWithLifecycle()
        val sessionCost by viewModel.sessionCost.collectAsStateWithLifecycle()
        val contextLimit by viewModel.contextLimit.collectAsStateWithLifecycle()
        val tokenDisplay = remember(lastTotal, contextLimit, lastCost, sessionCost) {
            val total = lastTotal
            if (total == null) ""
            else {
                val formatted = when {
                    total >= 1_000_000 -> "${total / 1_000_000}.${(total % 1_000_000) / 100_000}M"
                    total >= 1_000 -> "${total / 1000}.${(total % 1000) / 100}k"
                    else -> "$total"
                }
                val pct = contextLimit?.let { if (it > 0) " (${total * 100 / it}%)" else "" } ?: ""
                val effectiveCost = if (lastCost > 0) lastCost else sessionCost
                val costStr = if (effectiveCost > 0) " · $${String.format("%.2f", effectiveCost)}" else ""
                "$formatted$pct$costStr"
            }
        }

        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
        ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.background),
        ) {
            if (isUploading || isSending) {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            }
            if (isLoading) {
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth(),
                    contentAlignment = Alignment.Center,
                ) {
                    CircularProgressIndicator()
                }
            } else {
                if (todos.isNotEmpty()) {
                    TodoListCard(todos = todos)
                }
                LazyColumn(
                    state = listState,
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                ) {
                    items(displayMessages, key = { it.id }) { entity ->
                        val userModelName = if (entity.type == "user") userModelMap[entity.id] else null
                        SessionMessageRenderer(
                            entity = entity,
                            showReasoning = showReasoning && !compactMode,
                            compactMode = compactMode,
                            isQueued = entity.id in queuedMessageIds,
                            userModelName = userModelName,
                            onFullContentRequest = { messageId ->
                                viewModel.fetchFullContent(sessionID, messageId)
                            },
                            onNavigateToSubtask = onNavigateToSubtask,
                            pendingQuestions = pendingQuestions,
                            pendingPermissions = pendingPermissions,
                            onReplyQuestion = { requestID, answers -> viewModel.replyQuestion(requestID, answers) },
                            onRejectQuestion = { requestID -> viewModel.rejectQuestion(requestID) },
                            onReplyPermission = { requestID, reply, msg -> viewModel.replyPermission(requestID, reply, msg) },
                            runningAnchors = runningAnchors,
                            onViewFile = { filePath -> viewModel.openFilePreview(filePath) },
                            onViewDiff = { sId, mId, toolName, fp ->
                                val intent = android.content.Intent().apply {
                                    setClassName(context, "com.openmate.app.diff.DiffViewerActivity")
                                    putExtra("session_id", sId)
                                    putExtra("message_id", mId)
                                    putExtra("tool_name", toolName)
                                    if (fp != null) putExtra("file_path", fp)
                                    putExtra("directory", viewModel.getWorkingDirectory())
                                }
                                context.startActivity(intent)
                            },
                            onRevertToMessage = { messageID -> viewModel.revertToMessage(sessionID, messageID) },
                        )
                    }
                    if (displayMessages.isEmpty()) {
                        item {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 48.dp),
                                contentAlignment = Alignment.Center,
                            ) {
                                Text(
                                    text = stringResource(R.string.no_messages),
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                }
            }

            if (sessionRevert != null) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(MaterialTheme.colorScheme.errorContainer)
                        .padding(horizontal = 16.dp, vertical = 6.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = "已回滚消息",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onErrorContainer,
                    )
                    TextButton(onClick = { viewModel.unrevert(sessionID) }) {
                        Text(
                            text = "恢复",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onErrorContainer,
                        )
                    }
                }
            }

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 2.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(4.dp))
                            .background(MaterialTheme.colorScheme.tertiaryContainer)
                            .clickable { showAgentPicker = true }
                            .padding(horizontal = 6.dp, vertical = 2.dp),
                    ) {
                        Text(
                            text = selectedAgent.uppercase(),
                            style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
                            color = MaterialTheme.colorScheme.onTertiaryContainer,
                        )
                    }
                    if (selectedModel != null) {
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(4.dp))
                                .background(MaterialTheme.colorScheme.secondaryContainer)
                                .clickable {
                                    viewModel.loadProviders(forceRefresh = false)
                                    showModelPicker = true
                                }
                                .padding(horizontal = 6.dp, vertical = 2.dp),
                        ) {
                            Text(
                                text = selectedModel!!.modelName,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSecondaryContainer,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                    if (availableVariants.isNotEmpty()) {
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(4.dp))
                                .background(MaterialTheme.colorScheme.tertiaryContainer)
                                .clickable { showVariantPicker = true }
                                .padding(horizontal = 6.dp, vertical = 2.dp),
                        ) {
                            Text(
                                text = selectedVariant ?: stringResource(R.string.variant_default),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onTertiaryContainer,
                            )
                        }
                    }
                    val retryStatus = sessionRetryStatus
                    if (retryStatus != null) {
                        SessionRetryCard(retryStatus)
                    } else {
                        val busyStart = currentBusyStart
                        if (busyStart != null) {
                            var elapsed by remember(busyStart) { mutableStateOf(System.currentTimeMillis() - busyStart) }
                            LaunchedEffect(busyStart) {
                                while (true) {
                                    delay(1000)
                                    elapsed = System.currentTimeMillis() - busyStart
                                }
                            }
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(4.dp),
                            ) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(12.dp),
                                    strokeWidth = 2.dp,
                                    color = MaterialTheme.colorScheme.primary,
                                )
                                Text(
                                    text = formatDurationMillis(
                                        SessionBusyTimerCalculator.displayDuration(
                                            totalDuration = sessionTotalDuration,
                                            currentBusyStart = busyStart,
                                            now = busyStart + maxOf(0L, elapsed),
                                        ) ?: 0L,
                                    ),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.primary,
                                )
                            }
                        } else if (sessionTotalDuration != null) {
                            Text(
                                text = formatDurationMillis(sessionTotalDuration!!),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }

            if (attachedFiles.isNotEmpty()) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 2.dp),
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    attachedFiles.forEachIndexed { index, file ->
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(4.dp))
                                .background(MaterialTheme.colorScheme.surfaceVariant)
                                .clickable { viewModel.removeAttachedFile(index) }
                                .padding(horizontal = 6.dp, vertical = 2.dp),
                        ) {
                            Text(
                                text = "✕ ${file.filename}",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                }
            }

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 8.dp, vertical = 0.dp),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    IconButton(
                        onClick = {
                            val idx = listState.firstVisibleItemIndex
                            if (idx > 0 && displayMessages.isNotEmpty()) {
                                val target = displayMessages.subList(0, minOf(idx, displayMessages.size))
                                    .indexOfLast { it.type == "user" }
                                if (target >= 0) {
                                    coroutineScope.launch { listState.animateScrollToItem(target) }
                                }
                            }
                        },
                        enabled = previousUserMessageIndex >= 0,
                        modifier = Modifier.size(28.dp),
                    ) {
                        Icon(
                            Icons.Default.KeyboardArrowLeft,
                            contentDescription = stringResource(R.string.scroll_to_previous_user),
                            modifier = Modifier.size(24.dp),
                        )
                    }
                    IconButton(
                        onClick = {
                            if (nextUserMessageIndex >= 0) {
                                coroutineScope.launch { listState.animateScrollToItem(nextUserMessageIndex) }
                            }
                        },
                        enabled = nextUserMessageIndex >= 0,
                        modifier = Modifier.size(28.dp),
                    ) {
                        Icon(
                            Icons.Default.KeyboardArrowRight,
                            contentDescription = stringResource(R.string.scroll_to_next_user),
                            modifier = Modifier.size(24.dp),
                        )
                    }
                    IconButton(
                        onClick = {
                            coroutineScope.launch {
                                autoFollowTracker.onRequestFollow()
                                scrollToBottom()
                            }
                        },
                        enabled = showScrollToBottom,
                        modifier = Modifier.size(28.dp),
                    ) {
                        Icon(
                            Icons.Default.SkipNext,
                            contentDescription = stringResource(R.string.scroll_to_bottom),
                            modifier = Modifier.size(24.dp),
                        )
                    }
                }
                VerticalDivider(
                    modifier = Modifier.height(20.dp).padding(horizontal = 4.dp),
                    color = MaterialTheme.colorScheme.outline,
                )
                IconButton(
                    onClick = { showRevertDialog = true },
                    modifier = Modifier.size(28.dp),
                ) {
                    Icon(
                        Icons.Default.Undo,
                        contentDescription = stringResource(R.string.revert),
                        modifier = Modifier.size(24.dp),
                    )
                }
                IconButton(
                    onClick = { onNavigateToBrowser(viewModel.getWorkingDirectory()) },
                    modifier = Modifier.size(28.dp),
                ) {
                    Icon(
                        Icons.Default.FolderOpen,
                        contentDescription = stringResource(R.string.browse_files),
                        modifier = Modifier.size(24.dp),
                    )
                }

                Spacer(modifier = Modifier.weight(1f))

                if (tokenDisplay.isNotEmpty()) {
                    Surface(
                        shape = RoundedCornerShape(16.dp),
                        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.7f),
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                    ) {
                        Text(
                            text = tokenDisplay,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                        )
                    }
                }
            }

            ChatInputBar(
                text = inputText,
                onTextChange = { viewModel.updateInput(it) },
                onSend = {
                    autoFollowTracker.onLocalMessageSent()
                    viewModel.sendMessage(sessionID)
                },
                onAbort = { viewModel.abort(sessionID) },
                isBusy = currentBusyStart != null || sessionRetryStatus != null,
                isUploading = isUploading,
                isSending = isSending,
            )
        }
    }
    }

    if (showRenameDialog) {
        AlertDialog(
            onDismissRequest = { showRenameDialog = false },
            title = { Text(stringResource(R.string.rename_session)) },
            text = {
                OutlinedTextField(
                    value = renameText,
                    onValueChange = { renameText = it },
                    singleLine = true,
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.renameSession(renameText)
                    showRenameDialog = false
                }) {
                    Text(stringResource(R.string.confirm))
                }
            },
            dismissButton = {
                TextButton(onClick = { showRenameDialog = false }) {
                    Text(stringResource(R.string.cancel))
                }
            },
        )
    }

    if (showDeleteDialog) {
        AlertDialog(
            onDismissRequest = { showDeleteDialog = false },
            title = { Text(stringResource(R.string.delete_session)) },
            text = { Text(stringResource(R.string.delete_session_confirm)) },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.deleteSession { onBack() }
                    showDeleteDialog = false
                }) {
                    Text(stringResource(R.string.delete), color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteDialog = false }) {
                    Text(stringResource(R.string.cancel))
                }
            },
        )
    }

    if (showResyncDialog) {
        var selectedCount by remember { mutableStateOf(50) }
        var customCount by remember { mutableStateOf("") }
        var currentSeq by remember { mutableStateOf<Long?>(null) }
        LaunchedEffect(Unit) {
            currentSeq = viewModel.getCurrentSeq()
        }
        val effectiveCount = customCount.toIntOrNull() ?: selectedCount
        AlertDialog(
            onDismissRequest = { showResyncDialog = false },
            title = { Text(stringResource(R.string.resync)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(stringResource(R.string.resync_desc, currentSeq?.toString() ?: "-"))
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        listOf(20, 50, 100).forEach { count ->
                            FilterChip(
                                selected = selectedCount == count && customCount.isBlank(),
                                onClick = {
                                    selectedCount = count
                                    customCount = ""
                                },
                                label = { Text("$count") },
                            )
                        }
                        OutlinedTextField(
                            value = customCount,
                            onValueChange = { customCount = it },
                            modifier = Modifier.width(80.dp),
                            placeholder = { Text("自定义") },
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(
                                keyboardType = KeyboardType.Number,
                                imeAction = ImeAction.Go,
                            ),
                            keyboardActions = KeyboardActions(
                                onGo = {
                                    viewModel.resync(effectiveCount)
                                    showResyncDialog = false
                                },
                            ),
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.resync(effectiveCount)
                    showResyncDialog = false
                }) {
                    Text(stringResource(R.string.resync))
                }
            },
            dismissButton = {
                TextButton(onClick = { showResyncDialog = false }) {
                    Text(stringResource(R.string.cancel))
                }
            },
        )
    }

    if (showRevertDialog) {
        AlertDialog(
            onDismissRequest = { showRevertDialog = false },
            title = { Text("回滚") },
            text = { Text("确定回滚到上一条消息？此操作将撤销之后的所有对话和文件变更。") },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.revertToLastMessage(sessionID)
                    showRevertDialog = false
                }) {
                    Text("回滚")
                }
            },
            dismissButton = {
                TextButton(onClick = { showRevertDialog = false }) {
                    Text(stringResource(R.string.cancel))
                }
            },
        )
    }

    if (showSearch) {
        Dialog(
            onDismissRequest = { showSearch = false },
            properties = DialogProperties(usePlatformDefaultWidth = false),
        ) {
            SessionMessageSearchPanel(
                messages = messages,
                onNavigateToMessage = { index ->
                    showSearch = false
                    autoFollowTracker.onNavigateToMessage()
                    coroutineScope.launch {
                        listState.animateScrollToItem(index)
                        autoFollowTracker.onNavigateComplete()
                    }
                },
                onClose = { showSearch = false },
                onRevertToMessage = { messageID ->
                    viewModel.revertToMessage(sessionID, messageID)
                    showSearch = false
                },
                hasOlderMessages = hasOlderMessages,
                isLoadingOlder = isLoadingOlder,
                onLoadMore = { userTurns ->
                    viewModel.loadMoreSearchMessages(userTurns)
                },
                modifier = Modifier.fillMaxSize(),
            )
        }
    }

    if (showModelPicker) {
        ModelPickerSheet(
            providers = providers,
            currentModel = selectedModel?.let { SelectedModel(it.providerID, it.modelID, it.modelName) },
            recentModels = recentModels.map { SelectedModel(it.providerID, it.modelID, it.modelName) },
            onSelect = { model ->
                val variantsForModel = providers
                    ?.all
                    ?.find { it.id == model.providerID }
                    ?.models
                    ?.get(model.modelID)
                    ?.variants
                    ?.keys
                    ?.toList()
                    .orEmpty()
                viewModel.selectModel(model.providerID, model.modelID, model.modelName)
                showModelPicker = false
                if (variantsForModel.isNotEmpty()) {
                    showVariantPicker = true
                }
            },
            onRefresh = { viewModel.loadProviders(forceRefresh = true) },
            onDismiss = { showModelPicker = false },
        )
    }

    if (showAgentPicker) {
        AgentPickerSheet(
            agents = agents,
            currentAgent = selectedAgent,
            onSelect = { agent ->
                viewModel.setAgent(agent)
                showAgentPicker = false
            },
            onDismiss = { showAgentPicker = false },
        )
    }

    if (showMcpPicker) {
        McpPickerSheet(
            servers = mcpServers,
            onToggle = { name, enable -> viewModel.toggleMcp(name, enable) },
            onDismiss = { showMcpPicker = false },
        )
    }

    if (showVariantPicker && availableVariants.isNotEmpty()) {
        VariantPickerSheet(
            variants = availableVariants,
            currentVariant = selectedVariant,
            hasExplicitDefaultVariant = hasExplicitDefaultVariant,
            onSelect = {
                viewModel.selectVariant(it)
                showVariantPicker = false
            },
            onDismiss = { showVariantPicker = false },
        )
    }

    if (showSkillPicker) {
        SkillPickerSheet(
            skills = skills,
            onSelect = { skill ->
                viewModel.useSkill(skill.name)
                showSkillPicker = false
            },
            onDismiss = { showSkillPicker = false },
        )
    }

}

@Composable
private fun SessionRetryCard(status: SessionRetryStatus) {
    var remainingSeconds by remember(status.next) {
        mutableStateOf(status.next?.let { ((it - System.currentTimeMillis()) / 1000L).coerceAtLeast(0L) })
    }

    LaunchedEffect(status.next) {
        val next = status.next ?: run {
            remainingSeconds = null
            return@LaunchedEffect
        }
        while (true) {
            remainingSeconds = ((next - System.currentTimeMillis()) / 1000L).coerceAtLeast(0L)
            delay(1000)
        }
    }

    Card {
        Row(
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            CircularProgressIndicator(
                modifier = Modifier.size(12.dp),
                strokeWidth = 2.dp,
                color = MaterialTheme.colorScheme.error,
            )
            Column {
                Text(
                    text = status.message,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.error,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                val detail = buildString {
                    append("自动重试中")
                    status.attempt?.let { append(" · 第 ${it} 次") }
                    remainingSeconds?.let { append(" · ${it} 秒后重试") }
                }
                Text(
                    text = detail,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun VariantPickerSheet(
    variants: List<String>,
    currentVariant: String?,
    hasExplicitDefaultVariant: Boolean,
    onSelect: (String?) -> Unit,
    onDismiss: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp)
                .padding(bottom = 32.dp),
        ) {
            Text(
                text = stringResource(R.string.select_variant),
                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
                modifier = Modifier.padding(bottom = 12.dp),
            )
            LazyColumn(
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                item {
                    VariantRow(
                        name = stringResource(R.string.variant_default),
                        isSelected = currentVariant == null && hasExplicitDefaultVariant,
                        onClick = { onSelect(null) },
                    )
                }
                items(variants) { variant ->
                    VariantRow(
                        name = variant,
                        isSelected = currentVariant == variant,
                        onClick = { onSelect(variant) },
                    )
                }
            }
        }
    }
}

@Composable
private fun VariantRow(
    name: String,
    isSelected: Boolean,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 4.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(
            selected = isSelected,
            onClick = null,
            modifier = Modifier.size(20.dp),
        )
        Spacer(modifier = Modifier.width(8.dp))
        Text(
            text = name,
            style = MaterialTheme.typography.bodyMedium.copy(
                fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal,
            ),
            color = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
        )
    }
}

private fun extractMsgTimestamp(msgId: String): Long {
    val hex = msgId.split("_")[1].take(12)
    return hex.toLong(16) / 4096L
}

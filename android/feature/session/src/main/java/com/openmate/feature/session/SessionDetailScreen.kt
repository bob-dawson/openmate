package com.openmate.feature.session

import androidx.compose.foundation.clickable
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Card
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import com.openmate.feature.session.R
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.layout.size
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.hilt.navigation.compose.hiltViewModel
import com.openmate.core.ui.component.TopBar
import com.openmate.core.common.AutoFollowTracker
import com.openmate.feature.session.component.ChatInputBar
import com.openmate.feature.session.component.SessionMessageRenderer
import com.openmate.feature.session.component.SessionMessageSearchPanel
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
    val providers by viewModel.providers.collectAsState()
    val selectedModel by viewModel.selectedModel.collectAsState()
    val recentModels by viewModel.recentModels.collectAsState()
    val selectedAgent by viewModel.selectedAgent.collectAsState()
    val sessionStatus by viewModel.sessionStatus.collectAsState()
    val skills by viewModel.skills.collectAsState()
    val attachedFiles by viewModel.attachedFiles.collectAsState()
    val pendingQuestions by viewModel.pendingQuestions.collectAsState()
    val pendingPermissions by viewModel.pendingPermissions.collectAsState()
    val hasOlderMessages by viewModel.hasOlderMessages.collectAsState()
    val isLoadingOlder by viewModel.isLoadingOlder.collectAsState()
    val listState = rememberLazyListState()
    val coroutineScope = rememberCoroutineScope()
    val context = LocalContext.current
    val prefs = remember { context.getSharedPreferences("settings", android.content.Context.MODE_PRIVATE) }
    val showReasoning by remember { mutableStateOf(prefs.getBoolean("show_reasoning", true)) }

    val snackbarHostState = remember { SnackbarHostState() }

    var menuExpanded by remember { mutableStateOf(false) }
    var showRenameDialog by remember { mutableStateOf(false) }
    var showDeleteDialog by remember { mutableStateOf(false) }
    var showModelPicker by remember { mutableStateOf(false) }
    var showSkillPicker by remember { mutableStateOf(false) }
    var showSearch by remember { mutableStateOf(false) }
    var renameText by remember { mutableStateOf("") }
    val autoFollowTracker = remember { AutoFollowTracker() }
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

    val prevMessageCount = remember { mutableIntStateOf(0) }
    LaunchedEffect(messages.size) {
        autoFollowTracker.onMessagesChanged(messages.size, isLoading)
        if (messages.size > 0 && prevMessageCount.intValue == 0) {
            delay(150)
        }
        prevMessageCount.intValue = messages.size
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

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        contentWindowInsets = WindowInsets.systemBars.union(WindowInsets.ime),
        topBar = {
            TopBar(
                title = sessionTitle.ifBlank { stringResource(R.string.chat) },
                onBack = onBack,
                actions = {
                    IconButton(onClick = { showSearch = true }) {
                        Icon(
                            Icons.Default.Search,
                            contentDescription = stringResource(R.string.search_messages),
                            tint = MaterialTheme.colorScheme.onSurface,
                        )
                    }
                    IconButton(onClick = { onNavigateToBrowser(viewModel.getWorkingDirectory()) }) {
                        Icon(
                            Icons.Default.FolderOpen,
                            contentDescription = stringResource(R.string.browse_files),
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
                                text = { Text(stringResource(R.string.abort)) },
                                onClick = {
                                    menuExpanded = false
                                    viewModel.abort(sessionID)
                                },
                            )
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.model)) },
                                onClick = {
                                    menuExpanded = false
                                    viewModel.loadProviders()
                                    showModelPicker = true
                                },
                            )
                            DropdownMenuItem(
                                text = {
                                    Text(if (selectedAgent == "plan") stringResource(R.string.mode_plan) else stringResource(R.string.mode_build))
                                },
                                onClick = {
                                    viewModel.setAgent(if (selectedAgent == "plan") "build" else "plan")
                                    menuExpanded = false
                                },
                            )
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
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.skill)) },
                                onClick = {
                                    menuExpanded = false
                                    viewModel.loadSkills()
                                    showSkillPicker = true
                                },
                             )
                             DropdownMenuItem(
                                text = { Text(stringResource(R.string.refresh)) },
                                onClick = {
                                    menuExpanded = false
                                    viewModel.refresh()
                                },
                            )
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
                        }
                    }
                },
            )
        },
    ) { padding ->
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
            if (isUploading) {
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
                    items(messages, key = { it.id }) { entity ->
                        val userModelName = if (entity.type == "user") {
                            val idx = messages.indexOf(entity)
                            if (idx > 0) {
                                val prev = messages[idx - 1]
                                if (prev.type == "model-switched") {
                                    runCatching {
                                        val data = Json.parseToJsonElement(prev.data).jsonObject
                                        val model = data["model"]?.jsonObject
                                        val provider = model?.get("providerID")?.jsonPrimitive?.contentOrNull ?: ""
                                        val modelId = model?.get("id")?.jsonPrimitive?.contentOrNull ?: ""
                                        if (provider.isNotBlank()) "$provider/$modelId" else modelId
                                    }.getOrNull()
                                } else null
                            } else null
                        } else null
                        SessionMessageRenderer(
                            entity = entity,
                            showReasoning = showReasoning,
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
                            onViewFile = { filePath ->
                                val dir = filePath.substringBeforeLast('/', filePath.substringBeforeLast('\\'))
                                if (dir.isNotBlank() && dir != filePath) {
                                    onNavigateToBrowser(dir)
                                } else {
                                    onNavigateToBrowser(viewModel.getWorkingDirectory())
                                }
                            },
                        )
                    }
                    if (messages.isEmpty()) {
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

            if (selectedAgent != "build" || selectedModel != null || currentBusyStart != null || sessionTotalDuration != null) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 2.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    if (selectedAgent != "build") {
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(4.dp))
                                .background(MaterialTheme.colorScheme.tertiaryContainer)
                                .padding(horizontal = 6.dp, vertical = 2.dp),
                        ) {
                            Text(
                                text = selectedAgent.uppercase(),
                                style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
                                color = MaterialTheme.colorScheme.onTertiaryContainer,
                            )
                        }
                    }
                    if (selectedModel != null) {
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(4.dp))
                                .background(MaterialTheme.colorScheme.secondaryContainer)
                                .padding(horizontal = 6.dp, vertical = 2.dp),
                        ) {
                            Text(
                                text = "${selectedModel!!.providerID}/${selectedModel!!.modelName}",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSecondaryContainer,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
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

            ChatInputBar(
                text = inputText,
                onTextChange = { viewModel.updateInput(it) },
                onSend = {
                    autoFollowTracker.onLocalMessageSent()
                    viewModel.sendMessage(sessionID)
                },
                onAbort = { viewModel.abort(sessionID) },
                isBusy = currentBusyStart != null,
                isUploading = isUploading,
            )
        }

        if (showScrollToBottom) {
            Box(
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(end = 16.dp, bottom = 96.dp)
                    .clip(RoundedCornerShape(50))
                    .background(MaterialTheme.colorScheme.surfaceVariant)
                    .clickable {
                        coroutineScope.launch {
                            autoFollowTracker.onRequestFollow()
                            scrollToBottom()
                        }
                    }
                    .padding(8.dp),
            ) {
                Icon(
                    Icons.Default.KeyboardArrowDown,
                    contentDescription = stringResource(R.string.scroll_to_bottom),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
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
                viewModel.selectModel(model.providerID, model.modelID, model.modelName)
                showModelPicker = false
            },
            onDismiss = { showModelPicker = false },
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

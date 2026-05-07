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
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
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
import androidx.hilt.navigation.compose.hiltViewModel
import com.openmate.core.domain.model.PermissionReply
import com.openmate.core.domain.repository.FileAttachment
import com.openmate.core.ui.component.TopBar
import com.openmate.core.ui.component.SmartAutoScroll
import com.openmate.feature.session.component.ChatInputBar
import com.openmate.feature.session.component.MessageItem
import com.openmate.feature.session.component.ModelPickerSheet
import com.openmate.feature.session.component.SelectedModel
import com.openmate.feature.session.component.SkillPickerSheet
import com.openmate.feature.session.component.MessageSearchPanel
import com.openmate.feature.session.component.TodoListCard
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
    val inputText by viewModel.inputText.collectAsState()
    val pendingPermissions by viewModel.pendingPermissions.collectAsState()
    val pendingQuestions by viewModel.pendingQuestions.collectAsState()
    val sessionTitle by viewModel.sessionTitle.collectAsState()
    val errorMessage by viewModel.errorMessage.collectAsState()
    val todos by viewModel.todos.collectAsState()
    val hasOlderMessages by viewModel.hasOlderMessages.collectAsState()
    val isUploading by viewModel.isUploading.collectAsState()
    val pendingAssistantId by viewModel.pendingAssistantId.collectAsState()
    val providers by viewModel.providers.collectAsState()
    val selectedModel by viewModel.selectedModel.collectAsState()
    val recentModels by viewModel.recentModels.collectAsState()
    val selectedAgent by viewModel.selectedAgent.collectAsState()
    val isCompacting by viewModel.isCompacting.collectAsState()
    val skills by viewModel.skills.collectAsState()
    val attachedFiles by viewModel.attachedFiles.collectAsState()
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
    var showSearchPanel by remember { mutableStateOf(false) }
    var renameText by remember { mutableStateOf("") }
    var userNavigating by remember { mutableStateOf(false) }

    SmartAutoScroll(listState, messages.size, isLoading, userNavigating)

    val notAtBottom by remember {
        derivedStateOf { listState.canScrollForward }
    }

    val atTop by remember {
        derivedStateOf { listState.firstVisibleItemIndex == 0 && listState.firstVisibleItemScrollOffset < 10 }
    }

    var wasAtBottomBeforeIME by remember { mutableStateOf(true) }

    LaunchedEffect(notAtBottom) {
        if (!notAtBottom) wasAtBottomBeforeIME = true
    }

    val imeBottom = WindowInsets.ime.getBottom(LocalDensity.current)
    LaunchedEffect(imeBottom) {
        if (imeBottom > 100 && wasAtBottomBeforeIME && messages.size > 0) {
            listState.animateScrollToItem(messages.size)
        }
    }

    LaunchedEffect(sessionID) {
        viewModel.loadSession(sessionID)
    }

    LaunchedEffect(atTop, hasOlderMessages) {
        if (atTop && hasOlderMessages) {
            viewModel.loadOlderMessages()
        }
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
                    IconButton(onClick = { showSearchPanel = true }) {
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
                                    text = {
                                        if (isCompacting) Text(stringResource(R.string.compacting)) else Text(stringResource(R.string.compact))
                                    },
                                    onClick = {
                                        menuExpanded = false
                                        viewModel.compact(sessionID)
                                    },
                                    enabled = !isCompacting,
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
                                 text = { Text(stringResource(R.string.browse_files)) },
                                onClick = {
                                    menuExpanded = false
                                    onNavigateToBrowser(viewModel.getWorkingDirectory())
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
            if (isStreaming) {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            }
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
                    items(messages, key = { it.id }) { message ->
                        MessageItem(
                            message = message,
                            pendingAssistantId = pendingAssistantId,
                            pendingQuestions = pendingQuestions,
                            pendingPermissions = pendingPermissions,
                            onReplyQuestion = { id, answers -> viewModel.replyQuestion(id, answers) },
                            onRejectQuestion = { id -> viewModel.rejectQuestion(id) },
                            onReplyPermission = { id, reply, msg -> viewModel.replyPermission(id, reply, msg) },
                            onNavigateToSubtask = onNavigateToSubtask,
                            showReasoning = showReasoning,
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

            if (selectedAgent != "build" || selectedModel != null) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 2.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
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
                onSend = { viewModel.sendMessage(sessionID) },
                isUploading = isUploading,
            )
        }

        if (showSearchPanel) {
            MessageSearchPanel(
                messages = messages,
                onNavigateToMessage = { index ->
                    coroutineScope.launch {
                        userNavigating = true
                        showSearchPanel = false
                        if (index >= 0) listState.animateScrollToItem(index)
                        userNavigating = false
                    }
                },
                onClose = { showSearchPanel = false },
                modifier = Modifier.fillMaxSize(),
            )
        }

        if (notAtBottom && !showSearchPanel) {
            Box(
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(end = 16.dp, bottom = 96.dp)
                    .clip(RoundedCornerShape(50))
                    .background(MaterialTheme.colorScheme.surfaceVariant)
                    .clickable {
                        coroutineScope.launch {
                            userNavigating = false
                            if (messages.size > 0) listState.animateScrollToItem(messages.size)
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

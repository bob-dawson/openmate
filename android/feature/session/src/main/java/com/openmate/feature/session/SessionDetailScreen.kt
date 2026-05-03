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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MoreVert
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.openmate.core.domain.model.PermissionReply
import com.openmate.core.domain.repository.FileAttachment
import com.openmate.core.ui.component.TopBar
import com.openmate.feature.session.component.ChatInputBar
import com.openmate.feature.session.component.MessageItem
import com.openmate.feature.session.component.FilePickerSheet
import com.openmate.feature.session.component.ModelPickerSheet
import com.openmate.feature.session.component.SelectedModel
import com.openmate.feature.session.component.SkillPickerSheet
import com.openmate.feature.session.component.TodoListCard

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
    val pendingAssistantId by viewModel.pendingAssistantId.collectAsState()
    val providers by viewModel.providers.collectAsState()
    val selectedModel by viewModel.selectedModel.collectAsState()
    val recentModels by viewModel.recentModels.collectAsState()
    val selectedAgent by viewModel.selectedAgent.collectAsState()
    val isCompacting by viewModel.isCompacting.collectAsState()
    val skills by viewModel.skills.collectAsState()
    val attachedFiles by viewModel.attachedFiles.collectAsState()
    val listState = rememberLazyListState()
    val snackbarHostState = remember { SnackbarHostState() }

    var menuExpanded by remember { mutableStateOf(false) }
    var showRenameDialog by remember { mutableStateOf(false) }
    var showDeleteDialog by remember { mutableStateOf(false) }
    var showModelPicker by remember { mutableStateOf(false) }
    var showSkillPicker by remember { mutableStateOf(false) }
    var showFilePicker by remember { mutableStateOf(false) }
    var renameText by remember { mutableStateOf("") }

    val atBottom by remember {
        derivedStateOf { !listState.canScrollForward }
    }

    LaunchedEffect(sessionID) {
        viewModel.loadSession(sessionID)
    }

    LaunchedEffect(isLoading) {
        if (!isLoading && messages.isNotEmpty()) {
            listState.scrollToItem(messages.size - 1)
        }
    }

    LaunchedEffect(messages.size) {
        if (messages.isNotEmpty()) {
            listState.animateScrollToItem(messages.size - 1)
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
        topBar = {
            TopBar(
                title = sessionTitle.ifBlank { "Chat" },
                onBack = onBack,
                actions = {
                    Box {
                        IconButton(onClick = { menuExpanded = true }) {
                            Icon(
                                Icons.Default.MoreVert,
                                contentDescription = "More",
                                tint = MaterialTheme.colorScheme.onSurface,
                            )
                        }
                        DropdownMenu(
                            expanded = menuExpanded,
                            onDismissRequest = { menuExpanded = false },
                        ) {
                            if (isStreaming) {
                                DropdownMenuItem(
                                    text = { Text("中断") },
                                    onClick = {
                                        menuExpanded = false
                                        viewModel.abort(sessionID)
                                    },
                                )
                            }
                            DropdownMenuItem(
                                text = { Text("Model") },
                                onClick = {
                                    menuExpanded = false
                                    viewModel.loadProviders()
                                    showModelPicker = true
                                },
                            )
                            DropdownMenuItem(
                                text = {
                                    Text(if (selectedAgent == "plan") "Mode: Plan → Build" else "Mode: Build → Plan")
                                },
                                onClick = {
                                    viewModel.setAgent(if (selectedAgent == "plan") "build" else "plan")
                                    menuExpanded = false
                                },
                            )
                            if (selectedModel != null) {
                                DropdownMenuItem(
                                    text = {
                                        if (isCompacting) Text("Compacting...") else Text("Compact")
                                    },
                                    onClick = {
                                        menuExpanded = false
                                        viewModel.compact(sessionID)
                                    },
                                    enabled = !isCompacting,
                                )
                            }
                            DropdownMenuItem(
                                text = { Text("Skill") },
                                onClick = {
                                    menuExpanded = false
                                    viewModel.loadSkills()
                                    showSkillPicker = true
                                },
                             )
                            DropdownMenuItem(
                                text = { Text("Attach File") },
                                onClick = {
                                    menuExpanded = false
                                    showFilePicker = true
                                },
                            )
                            DropdownMenuItem(
                                text = { Text("Browse Files") },
                                onClick = {
                                    menuExpanded = false
                                    onNavigateToBrowser(viewModel.getWorkingDirectory())
                                },
                            )
                            DropdownMenuItem(
                                text = { Text("刷新") },
                                onClick = {
                                    menuExpanded = false
                                    viewModel.refresh()
                                },
                            )
                            DropdownMenuItem(
                                text = { Text("重命名") },
                                onClick = {
                                    menuExpanded = false
                                    renameText = sessionTitle
                                    showRenameDialog = true
                                },
                            )
                            DropdownMenuItem(
                                text = { Text("删除") },
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
        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.background)
                .padding(padding)
                .imePadding(),
        ) {
            if (isStreaming) {
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
                                    text = "No messages yet",
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
            )
        }
    }

    if (showRenameDialog) {
        AlertDialog(
            onDismissRequest = { showRenameDialog = false },
            title = { Text("重命名") },
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
                    Text("确定")
                }
            },
            dismissButton = {
                TextButton(onClick = { showRenameDialog = false }) {
                    Text("取消")
                }
            },
        )
    }

    if (showDeleteDialog) {
        AlertDialog(
            onDismissRequest = { showDeleteDialog = false },
            title = { Text("删除会话") },
            text = { Text("确定要删除这个会话吗？此操作不可撤销。") },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.deleteSession { onBack() }
                    showDeleteDialog = false
                }) {
                    Text("删除", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteDialog = false }) {
                    Text("取消")
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

    if (showFilePicker) {
        FilePickerSheet(
            apiClient = viewModel.apiClient,
            initialDirectory = viewModel.getWorkingDirectory(),
            onSelect = { path, filename ->
                viewModel.attachFile(path, filename)
                showFilePicker = false
            },
            onDismiss = { showFilePicker = false },
        )
    }
}

package com.openmate.feature.session

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Chat
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.outlined.ChatBubbleOutline
import androidx.compose.material.icons.outlined.FolderOpen
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import com.openmate.feature.session.R
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import com.openmate.core.common.toRelativeTimeString
import com.openmate.core.domain.model.ConnectionStatus
import com.openmate.core.domain.model.Session
import com.openmate.core.domain.model.SessionStatus
import com.openmate.core.domain.model.Workspace
import com.openmate.core.network.OpencodeApiClient
import com.openmate.core.ui.component.EmptyStateView
import com.openmate.core.ui.theme.Success
import com.openmate.feature.settings.SettingsViewModel

private data class TabItem(
    val labelResId: Int,
    val selectedIcon: ImageVector,
    val unselectedIcon: ImageVector,
)

private val TABS = listOf(
    TabItem(R.string.workspace, Icons.Filled.Folder, Icons.Outlined.FolderOpen),
    TabItem(R.string.sessions, Icons.Filled.Chat, Icons.Outlined.ChatBubbleOutline),
    TabItem(R.string.settings, Icons.Filled.Settings, Icons.Outlined.Settings),
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WorkspaceListScreen(
    onNavigateToWorkspace: (String) -> Unit,
    onNavigateToDetail: (String) -> Unit,
    onNavigateToLocalFileManager: () -> Unit,
    onBack: () -> Unit,
    viewModel: WorkspaceListViewModel = hiltViewModel(),
    settingsViewModel: SettingsViewModel = hiltViewModel(),
) {
    val workspaces by viewModel.workspaces.collectAsState()
    val allSessions by viewModel.allSessions.collectAsState()
    val connectionStatus by viewModel.connectionStatus.collectAsState()
    val errorMessage by viewModel.errorMessage.collectAsState()
    val snackbarHostState = remember { SnackbarHostState() }
    val instanceName by viewModel.instanceName.collectAsState()
    val selectedTab by viewModel.selectedTab.collectAsState()
    var showNewSessionDialog by remember { mutableStateOf(false) }
    var newSessionTitle by remember { mutableStateOf("") }
    var newSessionDirectory by remember { mutableStateOf("") }
    var showDirPicker by remember { mutableStateOf(false) }
    var searchQuery by remember { mutableStateOf("") }

    LaunchedEffect(errorMessage) {
        errorMessage?.let {
            snackbarHostState.showSnackbar(it)
            viewModel.clearError()
        }
    }

    val lifecycleOwner = LocalLifecycleOwner.current
    LaunchedEffect(lifecycleOwner) {
        lifecycleOwner.lifecycle.addObserver(object : androidx.lifecycle.LifecycleObserver {
            @androidx.lifecycle.OnLifecycleEvent(androidx.lifecycle.Lifecycle.Event.ON_RESUME)
            fun onResume() {
                viewModel.refresh()
            }
        })
    }

    val filteredSessions = if (searchQuery.isBlank()) allSessions
        else allSessions.filter { it.title.contains(searchQuery, ignoreCase = true) }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            androidx.compose.material3.TopAppBar(
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(instanceName)
                        Spacer(modifier = Modifier.width(8.dp))
                        ConnectionDotWithLabel(status = connectionStatus)
                    }
                },
                colors = androidx.compose.material3.TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                ),
            )
        },
        bottomBar = {
            NavigationBar {
                TABS.forEachIndexed { index, tab ->
                    NavigationBarItem(
                        selected = selectedTab == index,
                        onClick = {
                            viewModel.selectTab(index)
                            searchQuery = ""
                        },
                        icon = {
                            Icon(
                                imageVector = if (selectedTab == index) tab.selectedIcon else tab.unselectedIcon,
                                contentDescription = stringResource(tab.labelResId),
                            )
                        },
                        label = { Text(stringResource(tab.labelResId), style = MaterialTheme.typography.labelSmall) },
                    )
                }
            }
        },
        floatingActionButton = {
            if (selectedTab < 2) {
                FloatingActionButton(onClick = {
                    newSessionTitle = ""
                    newSessionDirectory = ""
                    showNewSessionDialog = true
                }) {
                    Icon(Icons.Default.Add, contentDescription = stringResource(R.string.content_desc_new_session))
                }
            }
        },
    ) { padding ->
        when (selectedTab) {
            0 -> {
                if (workspaces.isEmpty()) {
                    EmptyStateView(
                        message = stringResource(R.string.no_workspaces),
                        modifier = Modifier.padding(padding),
                    )
                } else {
                    LazyColumn(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(padding)
                            .padding(horizontal = 16.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        items(workspaces, key = { it.directory }) { workspace ->
                            WorkspaceCard(
                                workspace = workspace,
                                onClick = { onNavigateToWorkspace(workspace.directory) },
                            )
                        }
                    }
                }
            }
            1 -> {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(padding),
                ) {
                    OutlinedTextField(
                        value = searchQuery,
                        onValueChange = { searchQuery = it },
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 8.dp),
                        placeholder = {
                            Text(stringResource(R.string.search_sessions), style = MaterialTheme.typography.bodySmall)
                        },
                        leadingIcon = {
                            Icon(
                                Icons.Default.Search,
                                contentDescription = null,
                                modifier = Modifier.padding(start = 8.dp),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        },
                        singleLine = true,
                        textStyle = MaterialTheme.typography.bodySmall,
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = MaterialTheme.colorScheme.outline,
                            unfocusedBorderColor = MaterialTheme.colorScheme.outlineVariant,
                        ),
                        shape = MaterialTheme.shapes.small,
                    )

                    if (filteredSessions.isEmpty()) {
                        EmptyStateView(
                            message = stringResource(R.string.no_sessions),
                            modifier = Modifier.weight(1f),
                        )
                    } else {
                        LazyColumn(
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(horizontal = 16.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            items(filteredSessions, key = { it.id }) { session ->
                                AllSessionCard(
                                    session = session,
                                    onClick = { onNavigateToDetail(session.id) },
                                )
                            }
                        }
                    }
                }
            }
            2 -> {
                SettingsContent(
                    viewModel = settingsViewModel,
                    onNavigateToLocalFileManager = onNavigateToLocalFileManager,
                    onDisconnect = {
                        settingsViewModel.disconnect()
                        onBack()
                    },
                    modifier = Modifier.padding(padding),
                )
            }
        }
    }

    if (showNewSessionDialog) {
        AlertDialog(
            onDismissRequest = { showNewSessionDialog = false },
            title = { Text(stringResource(R.string.new_session)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    OutlinedTextField(
                        value = newSessionTitle,
                        onValueChange = { newSessionTitle = it },
                        label = { Text(stringResource(R.string.session_title_optional)) },
                        singleLine = true,
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    showNewSessionDialog = false
                    viewModel.createSession(
                        title = newSessionTitle.ifBlank { null as String? },
                        directory = newSessionDirectory.ifBlank { null as String? },
                        onNavigateToDirectory = onNavigateToWorkspace,
                        onNavigateToDetail = onNavigateToDetail,
                    )
                }) {
                    Text(stringResource(R.string.create))
                }
            },
            dismissButton = {
                TextButton(onClick = { showNewSessionDialog = false }) {
                    Text(stringResource(R.string.cancel))
                }
            },
        )
    }
}

@Composable
private fun WorkspaceCard(
    workspace: Workspace,
    onClick: () -> Unit,
) {
    val dirName = workspace.directory.substringAfterLast("\\").substringAfterLast("/")

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        shape = MaterialTheme.shapes.extraSmall,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
    ) {
        Row(
            modifier = Modifier.padding(14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = "\uD83D\uDCC1",
                fontSize = 16.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(modifier = Modifier.width(10.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = dirName,
                    style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold),
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = workspace.directory,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Spacer(modifier = Modifier.width(8.dp))
            WorkspaceBadge(text = stringResource(R.string.session_count, workspace.sessionCount))
        }
    }
}

@Composable
private fun AllSessionCard(
    session: Session,
    onClick: () -> Unit,
) {
    val statusColor: Color
    val statusLabel: String
    val cardAlpha: Float

    when {
        session.isArchived -> {
            statusColor = Color(0xFF808080)
            statusLabel = stringResource(R.string.session_archived)
            cardAlpha = 0.5f
        }
        session.status == SessionStatus.ERROR -> {
            statusColor = Color(0xFFe06c75)
            statusLabel = stringResource(R.string.session_error)
            cardAlpha = 1f
        }
        session.status == SessionStatus.RUNNING || session.status == SessionStatus.BUSY -> {
            statusColor = Color(0xFF56b6c2)
            statusLabel = stringResource(R.string.session_busy)
            cardAlpha = 1f
        }
        else -> {
            statusColor = Color(0xFF7fd88f)
            statusLabel = stringResource(R.string.session_idle)
            cardAlpha = 1f
        }
    }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        shape = MaterialTheme.shapes.extraSmall,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
    ) {
        Column(
            modifier = Modifier
                .padding(14.dp)
                .then(if (cardAlpha < 1f) Modifier else Modifier),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = session.title.ifBlank { stringResource(R.string.untitled) },
                    style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold),
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                Spacer(modifier = Modifier.width(6.dp))
                Text(
                    text = statusLabel,
                    style = MaterialTheme.typography.labelSmall.copy(fontSize = 9.sp, fontWeight = FontWeight.Medium),
                    color = statusColor,
                    modifier = Modifier
                        .border(BorderStroke(1.dp, statusColor), MaterialTheme.shapes.extraSmall)
                        .padding(horizontal = 6.dp, vertical = 1.dp),
                )
            }
            Spacer(modifier = Modifier.height(6.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = session.directory.substringAfterLast("\\").substringAfterLast("/"),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 10.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier
                        .background(
                            color = MaterialTheme.colorScheme.surfaceVariant,
                            shape = MaterialTheme.shapes.extraSmall,
                        )
                        .border(BorderStroke(1.dp, MaterialTheme.colorScheme.outline), MaterialTheme.shapes.extraSmall)
                        .padding(horizontal = 6.dp, vertical = 2.dp),
                )
                Text(
                    text = session.updatedAt.toRelativeTimeString(),
                    style = MaterialTheme.typography.labelSmall,
                    color = Color(0xFF4a4a4a),
                    fontSize = 10.sp,
                )
            }
        }
    }
}

@Composable
private fun WorkspaceBadge(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier
            .background(
                color = MaterialTheme.colorScheme.surfaceVariant,
                shape = MaterialTheme.shapes.extraSmall,
            )
            .border(BorderStroke(1.dp, MaterialTheme.colorScheme.outline), MaterialTheme.shapes.extraSmall)
            .padding(horizontal = 6.dp, vertical = 2.dp),
    )
}

@Composable
private fun ConnectionDotWithLabel(status: ConnectionStatus) {
    val color = when (status) {
        ConnectionStatus.CONNECTED -> Color(0xFF7fd88f)
        ConnectionStatus.CONNECTING -> Color(0xFFf5a742)
        ConnectionStatus.PAIRING -> Color(0xFFf5a742)
        ConnectionStatus.ERROR -> Color(0xFFe06c75)
        ConnectionStatus.NOT_BRIDGE -> Color(0xFFe06c75)
        ConnectionStatus.DISCONNECTED -> Color(0xFF808080)
    }
    val label = when (status) {
        ConnectionStatus.CONNECTED -> stringResource(R.string.status_connected)
        ConnectionStatus.CONNECTING -> stringResource(R.string.status_connecting)
        ConnectionStatus.PAIRING -> stringResource(R.string.status_connecting)
        ConnectionStatus.ERROR -> stringResource(R.string.status_error)
        ConnectionStatus.NOT_BRIDGE -> stringResource(R.string.status_not_bridge)
        ConnectionStatus.DISCONNECTED -> stringResource(R.string.status_disconnected)
    }
    Row(
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(5.dp)
                .clip(CircleShape)
                .background(color)
        )
        Spacer(modifier = Modifier.width(3.dp))
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = color,
            fontSize = 10.sp,
        )
    }
}

@Composable
private fun SettingsContent(
    viewModel: SettingsViewModel,
    onNavigateToLocalFileManager: () -> Unit,
    onDisconnect: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val activeProfile by viewModel.activeProfile.collectAsState()
    var showClearCacheDialog by remember { mutableStateOf(false) }

    if (showClearCacheDialog) {
        AlertDialog(
            onDismissRequest = { showClearCacheDialog = false },
            title = { Text(stringResource(R.string.confirm)) },
            text = { Text(stringResource(R.string.clear_cache_confirm)) },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.clearCache()
                    showClearCacheDialog = false
                }) {
                    Text(stringResource(R.string.delete), color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { showClearCacheDialog = false }) {
                    Text(stringResource(R.string.cancel))
                }
            },
        )
    }

    LazyColumn(
        modifier = modifier.padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        item {
            Spacer(modifier = Modifier.height(12.dp))
            ProfileSection(
                name = activeProfile?.name ?: stringResource(R.string.not_connected),
                address = activeProfile?.let { "${it.address}:${it.port}" } ?: "",
                onDisconnect = {
                    viewModel.disconnect()
                    onDisconnect()
                },
            )
        }

        item {
            SectionHeader(title = stringResource(R.string.cache))
            SettingsCard {
                SettingsRow(
                    title = stringResource(R.string.cache),
                    subtitle = viewModel.cacheSize.collectAsState().value,
                    trailing = {
                        Text(
                            text = stringResource(R.string.delete),
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.error,
                            modifier = Modifier.clickable { showClearCacheDialog = true },
                        )
                    },
                )
                SettingsRow(
                    title = stringResource(R.string.manage),
                    subtitle = stringResource(R.string.file_count, viewModel.cacheFileCount.collectAsState().value),
                    showDivider = false,
                    modifier = Modifier.clickable { onNavigateToLocalFileManager() },
                    trailing = {
                        IconButton(
                            onClick = { viewModel.refreshCacheInfo() },
                            modifier = Modifier.size(32.dp),
                        ) {
                            Icon(
                                imageVector = Icons.Filled.Refresh,
                                contentDescription = stringResource(R.string.refresh_stats),
                                modifier = Modifier.size(16.dp),
                            )
                        }
                    },
                )
            }
        }

        item {
            SectionHeader(title = stringResource(R.string.display))
            SettingsCard {
                val showReasoning by viewModel.showReasoning.collectAsState()
                SettingsRow(
                    title = stringResource(R.string.show_reasoning),
                    subtitle = stringResource(R.string.show_reasoning_subtitle),
                    showDivider = false,
                    trailing = {
                        Switch(
                            checked = showReasoning,
                            onCheckedChange = { viewModel.setShowReasoning(it) },
                        )
                    },
                )
            }
        }

        item {
            val opencodeVersion by viewModel.opencodeVersion.collectAsState()
            val isUpgrading by viewModel.isUpgrading.collectAsState()
            val isRestarting by viewModel.isRestarting.collectAsState()
            val isCheckingVersion by viewModel.isCheckingVersion.collectAsState()

            var showUpgradeDialog by remember { mutableStateOf(false) }
            var showRestartDialog by remember { mutableStateOf(false) }

            if (showUpgradeDialog) {
                AlertDialog(
                    onDismissRequest = { showUpgradeDialog = false },
                    title = { Text(stringResource(R.string.confirm)) },
                    text = { Text(stringResource(R.string.upgrade_confirm)) },
                    confirmButton = {
                        TextButton(onClick = {
                            showUpgradeDialog = false
                            viewModel.upgradeOpencode()
                        }) {
                            Text(stringResource(R.string.upgrade_opencode), color = MaterialTheme.colorScheme.error)
                        }
                    },
                    dismissButton = {
                        TextButton(onClick = { showUpgradeDialog = false }) {
                            Text(stringResource(R.string.cancel))
                        }
                    },
                )
            }

            if (showRestartDialog) {
                AlertDialog(
                    onDismissRequest = { showRestartDialog = false },
                    title = { Text(stringResource(R.string.confirm)) },
                    text = { Text(stringResource(R.string.restart_confirm)) },
                    confirmButton = {
                        TextButton(onClick = {
                            showRestartDialog = false
                            viewModel.restartOpencode()
                        }) {
                            Text(stringResource(R.string.restart_opencode))
                        }
                    },
                    dismissButton = {
                        TextButton(onClick = { showRestartDialog = false }) {
                            Text(stringResource(R.string.cancel))
                        }
                    },
                )
            }

            SectionHeader(title = stringResource(R.string.opencode_management))
            SettingsCard {
                SettingsRow(
                    title = stringResource(R.string.latest_version),
                    subtitle = null,
                    trailing = {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                text = when {
                                    isUpgrading -> stringResource(R.string.upgrading)
                                    opencodeVersion?.latest != null -> "v${opencodeVersion?.latest}"
                                    else -> stringResource(R.string.opencode_not_connected)
                                },
                                style = MaterialTheme.typography.labelMedium,
                                color = if (opencodeVersion?.hasUpdate == true) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            IconButton(
                                onClick = { viewModel.checkVersion() },
                                enabled = !isCheckingVersion && !isUpgrading && !isRestarting,
                                modifier = Modifier.size(32.dp),
                            ) {
                                if (isCheckingVersion) {
                                    CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                                } else {
                                    Icon(
                                        imageVector = Icons.Filled.Refresh,
                                        contentDescription = stringResource(R.string.check_for_updates),
                                        modifier = Modifier.size(16.dp),
                                    )
                                }
                            }
                        }
                    },
                )
                if (opencodeVersion?.hasUpdate == true && !isUpgrading && !isRestarting) {
                    SettingsRow(
                        title = stringResource(R.string.upgrade_to, opencodeVersion?.latest ?: ""),
                        subtitle = null,
                        modifier = Modifier.clickable { showUpgradeDialog = true },
                        trailing = {
                            Text(
                                text = stringResource(R.string.upgrade_opencode),
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.primary,
                            )
                        },
                    )
                }
                SettingsRow(
                    title = stringResource(R.string.current_version),
                    subtitle = null,
                    showDivider = false,
                    trailing = {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                text = if (isRestarting) stringResource(R.string.restarting)
                                       else opencodeVersion?.current?.let { "v$it" } ?: stringResource(R.string.opencode_not_connected),
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            IconButton(
                                onClick = { showRestartDialog = true },
                                enabled = !isRestarting && !isUpgrading,
                                modifier = Modifier.size(32.dp),
                            ) {
                                Icon(
                                    imageVector = Icons.Filled.Refresh,
                                    contentDescription = stringResource(R.string.restart_opencode),
                                    modifier = Modifier.size(16.dp),
                                )
                            }
                        }
                    },
                )
            }
        }

        item {
            SectionHeader(title = stringResource(R.string.about))
            SettingsCard {
                SettingsRow(
                    title = stringResource(R.string.version),
                    subtitle = null,
                    trailing = {
                        val context = LocalContext.current
                        val appVersion = remember {
                            runCatching {
                                context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: "?"
                            }.getOrDefault("?")
                        }
                        Text(
                            text = appVersion,
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    },
                )
                SettingsRow(
                    title = stringResource(R.string.open_source_licenses),
                    subtitle = null,
                    showDivider = false,
                    trailing = {
                        Text(
                            text = "\u203A",
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    },
                )
            }
            Spacer(modifier = Modifier.height(24.dp))
        }
    }
}

@Composable
private fun ProfileSection(
    name: String,
    address: String,
    onDisconnect: () -> Unit,
) {
    SettingsCard {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .size(44.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.surfaceVariant)
                    .border(BorderStroke(1.dp, MaterialTheme.colorScheme.outline), CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = name.firstOrNull()?.uppercaseChar()?.toString() ?: "?",
                    style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.SemiBold),
                    color = MaterialTheme.colorScheme.primary,
                )
            }
            Spacer(modifier = Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = name,
                    style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold),
                    color = MaterialTheme.colorScheme.onSurface,
                )
                if (address.isNotBlank()) {
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        text = address,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            Text(
                text = stringResource(R.string.disconnect),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.clickable(onClick = onDisconnect),
            )
        }
    }
}

@Composable
private fun SectionHeader(title: String) {
    Text(
        text = title,
        style = MaterialTheme.typography.labelMedium.copy(
            fontWeight = FontWeight.SemiBold,
            letterSpacing = 0.5.sp,
        ),
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(start = 4.dp, bottom = 8.dp),
    )
}

@Composable
private fun SettingsCard(
    content: @Composable () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surface, MaterialTheme.shapes.extraSmall)
            .border(BorderStroke(1.dp, MaterialTheme.colorScheme.outline), MaterialTheme.shapes.extraSmall),
    ) {
        content()
    }
}

@Composable
private fun SettingsToggle(
    title: String,
    subtitle: String?,
    subtitleColor: Color = MaterialTheme.colorScheme.onSurfaceVariant,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    showDivider: Boolean = true,
) {
    Column {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                if (subtitle != null) {
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        text = subtitle,
                        style = MaterialTheme.typography.labelSmall,
                        color = subtitleColor,
                    )
                }
            }
            Switch(
                checked = checked,
                onCheckedChange = onCheckedChange,
                colors = SwitchDefaults.colors(
                    checkedTrackColor = Success.copy(alpha = 0.3f),
                    checkedBorderColor = Success,
                    checkedThumbColor = Success,
                    uncheckedTrackColor = MaterialTheme.colorScheme.surfaceVariant,
                    uncheckedBorderColor = MaterialTheme.colorScheme.outline,
                    uncheckedThumbColor = Color(0xFF808080),
                ),
            )
        }
        if (showDivider) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 14.dp)
                    .height(1.dp)
                    .background(MaterialTheme.colorScheme.outlineVariant),
            )
        }
    }
}

@Composable
private fun SettingsRow(
    title: String,
    subtitle: String?,
    modifier: Modifier = Modifier,
    showDivider: Boolean = true,
    trailing: @Composable () -> Unit = {},
) {
    Column(modifier = modifier) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                if (subtitle != null) {
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        text = subtitle,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            trailing()
        }
        if (showDivider) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 14.dp)
                    .height(1.dp)
                    .background(MaterialTheme.colorScheme.outlineVariant),
            )
        }
    }
}

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
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.material3.HorizontalDivider
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
import com.openmate.feature.session.component.DirectoryPickerSheet
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
    var showLicense by remember { mutableStateOf(false) }

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
                            .padding(padding),
                    ) {
                        itemsIndexed(workspaces, key = { _, w -> w.directory }) { index, workspace ->
                            if (index > 0) HorizontalDivider()
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
                                .fillMaxSize(),
                        ) {
                            itemsIndexed(filteredSessions, key = { _, s -> s.id }) { index, session ->
                                if (index > 0) HorizontalDivider()
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
                    onShowLicense = { showLicense = true },
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
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { showDirPicker = true }
                            .padding(vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = if (newSessionDirectory.isBlank()) stringResource(R.string.select_directory) else
                                newSessionDirectory,
                            style = MaterialTheme.typography.bodyMedium,
                            color = if (newSessionDirectory.isBlank()) MaterialTheme.colorScheme.onSurfaceVariant else
                                MaterialTheme.colorScheme.onSurface,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
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

    if (showDirPicker) {
        DirectoryPickerSheet(
            apiClient = viewModel.apiClient,
            onSelect = { path ->
                newSessionDirectory = path
                showDirPicker = false
            },
            onDismiss = { showDirPicker = false },
        )
    }

    if (showLicense) {
        LicenseDialog(onDismiss = { showLicense = false })
    }
}

@Composable
private fun WorkspaceCard(
    workspace: Workspace,
    onClick: () -> Unit,
) {
    val dirName = workspace.directory.substringAfterLast("\\").substringAfterLast("/")

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 10.dp),
    ) {
        Text(
            text = dirName,
            style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.Medium),
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Spacer(modifier = Modifier.height(2.dp))
        Text(
            text = workspace.directory,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun AllSessionCard(
    session: Session,
    onClick: () -> Unit,
) {
    val statusColor = when (session.status) {
        SessionStatus.ERROR -> Color(0xFFe06c75)
        SessionStatus.RUNNING, SessionStatus.BUSY -> Color(0xFF56b6c2)
        else -> Color(0xFF7fd88f)
    }
    val statusLabel = when (session.status) {
        SessionStatus.ERROR -> stringResource(R.string.session_error)
        SessionStatus.RUNNING, SessionStatus.BUSY -> stringResource(R.string.session_busy)
        else -> stringResource(R.string.session_idle)
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 10.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = session.title.ifBlank { stringResource(R.string.untitled) },
                style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.Medium),
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = statusLabel,
                style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp, fontWeight = FontWeight.Medium),
                color = statusColor,
            )
        }
        Spacer(modifier = Modifier.height(2.dp))
        Row(
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = session.directory.substringAfterLast("\\").substringAfterLast("/"),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontSize = 11.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = session.updatedAt.toRelativeTimeString(),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontSize = 11.sp,
            )
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
        ConnectionStatus.GATEWAY_CONNECTED -> Color(0xFF5b9cf5)
        ConnectionStatus.CONNECTING -> Color(0xFFf5a742)
        ConnectionStatus.PAIRING -> Color(0xFFf5a742)
        ConnectionStatus.ERROR -> Color(0xFFe06c75)
        ConnectionStatus.NOT_BRIDGE -> Color(0xFFe06c75)
        ConnectionStatus.DISCONNECTED -> Color(0xFF808080)
    }
    val label = when (status) {
        ConnectionStatus.CONNECTED -> stringResource(R.string.status_connected)
        ConnectionStatus.GATEWAY_CONNECTED -> stringResource(R.string.status_gateway_connected)
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
    onShowLicense: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val activeProfile by viewModel.activeProfile.collectAsState()
    val updateMessage by viewModel.updateMessage.collectAsState()
    val context = LocalContext.current
    LaunchedEffect(updateMessage) {
        updateMessage?.let {
            android.widget.Toast.makeText(context, it, android.widget.Toast.LENGTH_SHORT).show()
            viewModel.clearUpdateMessage()
        }
    }
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
            SectionHeader(title = stringResource(R.string.connection))
            SettingsCard {
                val gatewayEnabled by viewModel.gatewayEnabled.collectAsState()
                SettingsRow(
                    title = stringResource(R.string.gateway_enabled),
                    subtitle = stringResource(R.string.gateway_enabled_subtitle),
                    showDivider = false,
                    trailing = {
                        Switch(
                            checked = gatewayEnabled,
                            onCheckedChange = { viewModel.setGatewayEnabled(it) },
                        )
                    },
                )
            }
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
                    trailing = {
                        Switch(
                            checked = showReasoning,
                            onCheckedChange = { viewModel.setShowReasoning(it) },
                        )
                    },
                )
                val compactMode by viewModel.compactMode.collectAsState()
                SettingsRow(
                    title = stringResource(R.string.compact_mode),
                    subtitle = stringResource(R.string.compact_mode_subtitle),
                    showDivider = false,
                    trailing = {
                        Switch(
                            checked = compactMode,
                            onCheckedChange = { viewModel.setCompactMode(it) },
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
                            Spacer(modifier = Modifier.width(8.dp))
                            if (isCheckingVersion) {
                                CircularProgressIndicator(modifier = Modifier.size(14.dp), strokeWidth = 2.dp)
                            } else {
                                Text(
                                    text = stringResource(R.string.check_for_updates),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.clickable(
                                        enabled = !isUpgrading && !isRestarting,
                                        onClick = { viewModel.checkVersion() }
                                    ),
                                )
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
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = stringResource(R.string.restart_opencode),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.clickable(
                                    enabled = !isRestarting && !isUpgrading,
                                    onClick = { showRestartDialog = true }
                                ),
                            )
                        }
                    },
                )
            }
        }

        item {
            val appUpdateInfo by viewModel.appUpdateInfo.collectAsState()
            val appDownloadState by viewModel.appDownloadState.collectAsState()

            SectionHeader(title = stringResource(R.string.app_update))
            SettingsCard {
                SettingsRow(
                    title = stringResource(R.string.app_latest_version),
                    subtitle = null,
                    trailing = {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                text = when {
                                    appDownloadState.isDownloading -> stringResource(R.string.downloading)
                                    appUpdateInfo?.latestVersion != null -> "v${appUpdateInfo?.latestVersion}"
                                    else -> stringResource(R.string.check_failed_retry)
                                },
                                style = MaterialTheme.typography.labelMedium,
                                color = if (appUpdateInfo?.hasUpdate == true)
                                    MaterialTheme.colorScheme.primary
                                else MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = stringResource(R.string.check_for_updates),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.clickable(
                                    enabled = !appDownloadState.isDownloading,
                                    onClick = { viewModel.checkAppUpdate(userTriggered = true) }
                                ),
                            )
                        }
                    },
                )
                if (appUpdateInfo?.hasUpdate == true && !appDownloadState.isDownloading) {
                    SettingsRow(
                        title = stringResource(R.string.download_and_install),
                        subtitle = null,
                        showDivider = appDownloadState.error != null,
                        modifier = Modifier.clickable { viewModel.downloadAndInstallApp() },
                        trailing = {
                            Text(
                                text = "\u203A",
                                style = MaterialTheme.typography.bodyLarge,
                                color = MaterialTheme.colorScheme.primary,
                            )
                        },
                    )
                }
                if (appDownloadState.isDownloading) {
                    SettingsRow(
                        title = stringResource(R.string.downloading),
                        subtitle = "${appDownloadState.progress}%",
                        showDivider = false,
                        trailing = {
                            CircularProgressIndicator(
                                progress = { appDownloadState.progress / 100f },
                                modifier = Modifier.size(14.dp),
                                strokeWidth = 2.dp,
                            )
                        },
                    )
                } else if (appDownloadState.error != null) {
                    SettingsRow(
                        title = stringResource(R.string.app_download_failed),
                        subtitle = null,
                        showDivider = false,
                        modifier = Modifier.clickable { viewModel.clearAppDownloadError() },
                        trailing = {
                            Text(
                                text = stringResource(R.string.check_for_updates),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.primary,
                            )
                        },
                    )
                } else if (appUpdateInfo?.hasUpdate == false) {
                    SettingsRow(
                        title = stringResource(R.string.app_current_version),
                        subtitle = null,
                        showDivider = false,
                        trailing = {
                            Text(
                                text = "v${appUpdateInfo?.currentVersion ?: "?"}",
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        },
                    )
                }
            }
        }

        item {
            val bridgeUpdateInfo by viewModel.bridgeUpdateInfo.collectAsState()
            val bridgeUpgradeState by viewModel.bridgeUpgradeState.collectAsState()

            SectionHeader(title = stringResource(R.string.bridge_update))
            SettingsCard {
                SettingsRow(
                    title = stringResource(R.string.app_latest_version),
                    subtitle = null,
                    trailing = {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                text = when {
                                    bridgeUpgradeState.isDownloading -> stringResource(R.string.bridge_downloading)
                                    bridgeUpgradeState.isApplying -> stringResource(R.string.bridge_applying)
                                    bridgeUpdateInfo?.latestVersion != null -> "v${bridgeUpdateInfo?.latestVersion}"
                                    else -> stringResource(R.string.check_failed_retry)
                                },
                                style = MaterialTheme.typography.labelMedium,
                                color = if (bridgeUpdateInfo?.hasUpdate == true)
                                    MaterialTheme.colorScheme.primary
                                else MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = stringResource(R.string.check_for_updates),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.clickable(
                                    enabled = !bridgeUpgradeState.isDownloading && !bridgeUpgradeState.isApplying,
                                    onClick = { viewModel.checkBridgeUpdate(userTriggered = true) }
                                ),
                            )
                        }
                    },
                )
                if (bridgeUpgradeState.isDownloading) {
                    SettingsRow(
                        title = stringResource(R.string.bridge_downloading),
                        subtitle = "${bridgeUpgradeState.progress}%",
                        showDivider = false,
                        trailing = {
                            CircularProgressIndicator(
                                progress = { bridgeUpgradeState.progress / 100f },
                                modifier = Modifier.size(14.dp),
                                strokeWidth = 2.dp,
                            )
                        },
                    )
                } else if (bridgeUpgradeState.isApplying) {
                    SettingsRow(
                        title = stringResource(R.string.bridge_applying),
                        subtitle = null,
                        showDivider = false,
                        trailing = {
                            CircularProgressIndicator(modifier = Modifier.size(14.dp), strokeWidth = 2.dp)
                        },
                    )
                } else if (bridgeUpgradeState.error != null) {
                    SettingsRow(
                        title = stringResource(R.string.bridge_upgrade_failed),
                        subtitle = bridgeUpgradeState.error,
                        showDivider = false,
                        modifier = Modifier.clickable { viewModel.clearBridgeUpgradeError() },
                        trailing = {
                            Text(
                                text = stringResource(R.string.check_for_updates),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.primary,
                            )
                        },
                    )
                } else if (bridgeUpdateInfo?.hasUpdate == true) {
                    SettingsRow(
                        title = stringResource(R.string.bridge_upgrade_now),
                        subtitle = null,
                        showDivider = false,
                        modifier = Modifier.clickable { viewModel.upgradeBridge() },
                        trailing = {
                            Text(
                                text = "\u203A",
                                style = MaterialTheme.typography.bodyLarge,
                                color = MaterialTheme.colorScheme.primary,
                            )
                        },
                    )
                } else if (bridgeUpdateInfo?.hasUpdate == false) {
                    SettingsRow(
                        title = stringResource(R.string.app_current_version),
                        subtitle = null,
                        showDivider = false,
                        trailing = {
                            Text(
                                text = "v${bridgeUpdateInfo?.currentVersion ?: "?"}",
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        },
                    )
                }
            }
        }

        item {
            SectionHeader(title = stringResource(R.string.about))
                SettingsCard {
                    SettingsRow(
                        title = stringResource(R.string.open_source_licenses),
                        subtitle = null,
                        showDivider = false,
                        modifier = Modifier.clickable { onShowLicense() },
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
private fun LicenseDialog(onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Apache License 2.0") },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
            ) {
                Text(
                    text = APACHE_LICENSE_TEXT,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface,
                )
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.content_desc_close))
            }
        },
    )
}

private const val APACHE_LICENSE_TEXT = """Apache License
Version 2.0, January 2004
http://www.apache.org/licenses/

TERMS AND CONDITIONS FOR USE, REPRODUCTION, AND DISTRIBUTION

1. Definitions.

"License" shall mean the terms and conditions for use, reproduction, and distribution as defined by Sections 1 through 9 of this document.

"Licensor" shall mean the copyright owner or entity authorized by the copyright owner that is granting the License.

"You" (or "Your") shall mean an individual or Legal Entity exercising permissions granted by this License.

"Source" form shall mean the preferred form for making modifications, including but not limited to software source code, documentation source, and configuration files.

"Object" form shall mean any form resulting from mechanical transformation or translation of a Source form, including but not limited to compiled object code, generated documentation, and conversions to other media types.

"Work" shall mean the work of authorship, whether in Source or Object form, made available under the License.

"Derivative Works" shall mean any work, whether in Source or Object form, that is based on (or derived from) the Work and for which the editorial revisions, annotations, elaborations, or other modifications represent, as a whole, an original work of authorship.

"Contribution" shall mean any work of authorship, including the original version of the Work and any modifications or additions to that Work or Derivative Works thereof, that is intentionally submitted to Licensor for inclusion in the Work by the copyright owner or by an individual or Legal Entity authorized to submit on behalf of the copyright owner.

"Contributor" shall mean Licensor and any individual or Legal Entity on behalf of whom a Contribution has been received by Licensor and subsequently incorporated within the Work.

2. Grant of Copyright License. Each Contributor hereby grants to You a perpetual, worldwide, non-exclusive, no-charge, royalty-free, irrevocable copyright license to reproduce, prepare Derivative Works of, publicly display, publicly perform, sublicense, and distribute the Work and such Derivative Works in Source or Object form.

3. Grant of Patent License. Each Contributor hereby grants to You a perpetual, worldwide, non-exclusive, no-charge, royalty-free, irrevocable patent license to make, have made, use, offer to sell, sell, import, and otherwise transfer the Work.

4. Redistribution. You may reproduce and distribute copies of the Work or Derivative Works thereof in any medium, with or without modifications, and in Source or Object form, provided that You give any other recipients of the Work or Derivative Works a copy of this License and cause any modified files to carry prominent notices stating that You changed the files.

5. Submission of Contributions. Unless You explicitly state otherwise, any Contribution intentionally submitted for inclusion in the Work by You to the Licensor shall be under the terms and conditions of this License, without any additional terms or conditions.

6. Trademarks. This License does not grant permission to use the trade names, trademarks, service marks, or product names of the Licensor.

7. Disclaimer of Warranty. Unless required by applicable law or agreed to in writing, Licensor provides the Work on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.

8. Limitation of Liability. In no event shall any Contributor be liable to You for damages of any character arising as a result of this License or out of the use or inability to use the Work.

9. Accepting Warranty or Additional Liability. While redistributing the Work, You may choose to offer acceptance of support, warranty, indemnity, or other liability obligations consistent with this License.

END OF TERMS AND CONDITIONS

Copyright 2026 OpenMate

Licensed under the Apache License, Version 2.0 (the "License");
you may not use this file except in compliance with the License.
You may obtain a copy of the License at

    http://www.apache.org/licenses/LICENSE-2.0

Unless required by applicable law or agreed to in writing, software
distributed under the License is distributed on an "AS IS" BASIS,
WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
See the License for the specific language governing permissions and
limitations under the License."""

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

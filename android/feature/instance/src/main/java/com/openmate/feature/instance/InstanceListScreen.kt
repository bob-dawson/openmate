package com.openmate.feature.instance

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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.QrCodeScanner
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import com.openmate.core.common.crash.CrashLogManager
import com.openmate.feature.instance.R
import com.openmate.core.domain.model.ConnectionStatus
import com.openmate.core.domain.model.ServerProfile
import com.openmate.core.ui.component.EmptyStateView
import com.openmate.core.ui.component.TopBar
import com.openmate.core.ui.theme.TopBarBackground

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun InstanceListScreen(
    onNavigateToAdd: () -> Unit,
    onNavigateToEdit: (String) -> Unit,
    onNavigateToQrScan: () -> Unit,
    onNavigateToSessions: () -> Unit,
    onNavigateToCrashLog: () -> Unit,
    viewModel: InstanceListViewModel = hiltViewModel(),
) {
    val profiles by viewModel.profiles.collectAsState()
    val context = LocalContext.current
    val crashLogManager = remember { CrashLogManager(context) }
    val unreadCount = remember { crashLogManager.getUnreadCount() }
    var menuExpanded by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopBar(
                title = stringResource(R.string.instances),
                actions = {
                    Box {
                        IconButton(onClick = { menuExpanded = true }) {
                            Icon(
                                Icons.Default.MoreVert,
                                contentDescription = stringResource(R.string.content_desc_more),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        DropdownMenu(
                            expanded = menuExpanded,
                            onDismissRequest = { menuExpanded = false },
                        ) {
                            DropdownMenuItem(
                                text = {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Text(stringResource(R.string.crash_log))
                                        if (unreadCount > 0) {
                                            Spacer(modifier = Modifier.width(8.dp))
                                            Box(
                                                modifier = Modifier
                                                    .size(18.dp)
                                                    .clip(CircleShape)
                                                    .background(MaterialTheme.colorScheme.error),
                                                contentAlignment = Alignment.Center,
                                            ) {
                                                Text(
                                                    text = if (unreadCount > 9) "9+" else unreadCount.toString(),
                                                    color = MaterialTheme.colorScheme.onError,
                                                    fontSize = 10.sp,
                                                    fontWeight = FontWeight.Bold,
                                                )
                                            }
                                        }
                                    }
                                },
                                onClick = {
                                    menuExpanded = false
                                    onNavigateToCrashLog()
                                },
                            )
                        }
                    }
                },
            )
        },
        floatingActionButton = {
            Column(horizontalAlignment = Alignment.End) {
                FloatingActionButton(
                    onClick = onNavigateToQrScan,
                    containerColor = MaterialTheme.colorScheme.tertiaryContainer,
                    modifier = Modifier.padding(bottom = 12.dp),
                ) {
                    Icon(Icons.Default.QrCodeScanner, contentDescription = stringResource(R.string.content_desc_scan_qr))
                }
                FloatingActionButton(onClick = onNavigateToAdd) {
                    Icon(Icons.Default.Add, contentDescription = stringResource(R.string.content_desc_add_instance))
                }
            }
        },
    ) { padding ->
        if (profiles.isEmpty()) {
            EmptyStateView(
                message = stringResource(R.string.add_first_instance),
                modifier = Modifier.padding(padding),
            )
        } else {
            Column(modifier = Modifier.padding(padding)) {
                Text(
                    text = stringResource(R.string.instance_count, profiles.size),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = 20.dp, top = 12.dp, bottom = 4.dp),
                )
                LazyColumn(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(horizontal = 16.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    items(profiles, key = { it.profile.id }) { item ->
                        InstanceCard(
                            profile = item.profile,
                            status = item.status,
                            onClick = {
                                viewModel.connect(item.profile) {
                                    onNavigateToSessions()
                                }
                            },
                            onEdit = { onNavigateToEdit(item.profile.id) },
                            onDelete = { viewModel.deleteProfile(item.profile.id) },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun InstanceCard(
    profile: ServerProfile,
    status: ConnectionStatus,
    onClick: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
) {
    val firstChar = profile.name.firstOrNull()?.uppercaseChar()?.toString() ?: "?"
    var menuExpanded by remember { mutableStateOf(false) }

    androidx.compose.material3.Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        colors = androidx.compose.material3.CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface,
        ),
        shape = MaterialTheme.shapes.extraSmall,
        border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(40.dp)
                        .clip(MaterialTheme.shapes.extraSmall)
                        .background(MaterialTheme.colorScheme.surfaceVariant)
                        .border(1.dp, MaterialTheme.colorScheme.outline, MaterialTheme.shapes.extraSmall),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = firstChar,
                        color = MaterialTheme.colorScheme.primary,
                        fontWeight = FontWeight.Bold,
                        fontSize = 18.sp,
                    )
                }
                Spacer(modifier = Modifier.width(12.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = profile.name,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        when (status) {
                            ConnectionStatus.CONNECTED -> StatusDot(stringResource(R.string.status_connected), Color(0xFF7fd88f))
                            ConnectionStatus.CONNECTING -> StatusDot(stringResource(R.string.status_connecting), Color(0xFFf5a742))
                            ConnectionStatus.ERROR -> StatusDot(stringResource(R.string.status_error), Color(0xFFe06c75))
                            ConnectionStatus.NOT_BRIDGE -> StatusDot(stringResource(R.string.status_not_bridge), Color(0xFFe06c75))
                            else -> StatusDot(stringResource(R.string.status_disconnected), Color(0xFF808080))
                        }
                    }
                }
                Box {
                    IconButton(onClick = { menuExpanded = true }) {
                        Icon(
                            Icons.Default.MoreVert,
                            contentDescription = stringResource(R.string.content_desc_more),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    DropdownMenu(
                        expanded = menuExpanded,
                        onDismissRequest = { menuExpanded = false },
                    ) {
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.edit)) },
                            onClick = {
                                menuExpanded = false
                                onEdit()
                            },
                        )
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.delete)) },
                            onClick = {
                                menuExpanded = false
                                onDelete()
                            },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun StatusDot(label: String, color: Color) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            modifier = Modifier
                .size(6.dp)
                .clip(CircleShape)
                .background(color)
        )
        Spacer(modifier = Modifier.width(4.dp))
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = color,
        )
    }
}

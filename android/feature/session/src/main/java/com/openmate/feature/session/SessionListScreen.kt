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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import com.openmate.core.common.toRelativeTimeString
import com.openmate.core.domain.model.ConnectionStatus
import com.openmate.core.domain.model.Session
import com.openmate.core.domain.model.SessionStatus
import com.openmate.core.ui.component.EmptyStateView
import com.openmate.core.ui.component.TopBar

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SessionListScreen(
    directory: String,
    onNavigateToDetail: (String) -> Unit,
    onBack: () -> Unit,
    viewModel: SessionListViewModel = hiltViewModel(),
) {
    val sessions by viewModel.sessions.collectAsState()
    val errorMessage by viewModel.errorMessage.collectAsState()
    val snackbarHostState = remember { SnackbarHostState() }
    var searchQuery by remember { mutableStateOf("") }
    var showNewSessionDialog by remember { mutableStateOf(false) }
    var newSessionTitle by remember { mutableStateOf("") }
    val dirName = directory.substringAfterLast("\\").substringAfterLast("/")

    LaunchedEffect(directory) {
        viewModel.setDirectory(directory)
    }

    LaunchedEffect(errorMessage) {
        errorMessage?.let {
            snackbarHostState.showSnackbar(it)
            viewModel.clearError()
        }
    }

    val filteredSessions = if (searchQuery.isBlank()) sessions
        else sessions.filter { it.title.contains(searchQuery, ignoreCase = true) }
    val connectionStatus by viewModel.connectionStatus.collectAsState()

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            TopBar(
                title = dirName,
                onBack = onBack,
                titleContent = {
                    Column {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                text = dirName,
                                style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            ConnectionDot(status = connectionStatus)
                        }
                        Text(
                            text = directory,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                },
            )
        },
        floatingActionButton = {
            FloatingActionButton(onClick = {
                newSessionTitle = ""
                    showNewSessionDialog = true
            }) {
                Icon(Icons.Default.Add, contentDescription = stringResource(R.string.content_desc_new_session))
            }
        },
    ) { padding ->
        Column(modifier = Modifier.padding(padding)) {
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
                        SessionCard(
                            session = session,
                            onClick = { onNavigateToDetail(session.id) },
                        )
                    }
                }
            }
        }
    }

    if (showNewSessionDialog) {
        AlertDialog(
            onDismissRequest = { showNewSessionDialog = false },
            title = { Text(stringResource(R.string.new_session)) },
            text = {
                OutlinedTextField(
                    value = newSessionTitle,
                    onValueChange = { newSessionTitle = it },
                    label = { Text(stringResource(R.string.session_title_optional)) },
                    singleLine = true,
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    showNewSessionDialog = false
                    viewModel.createSession(
                        title = newSessionTitle.ifBlank { null },
                        directory = directory,
                        onCreated = onNavigateToDetail,
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
private fun SessionCard(
    session: Session,
    onClick: () -> Unit,
) {
    val statusColor: Color
    val statusLabel: String

    when {
        session.isArchived -> {
            statusColor = Color(0xFF808080)
            statusLabel = stringResource(R.string.session_archived)
        }
        session.status == SessionStatus.ERROR -> {
            statusColor = Color(0xFFe06c75)
            statusLabel = stringResource(R.string.session_error)
        }
        session.status == SessionStatus.RUNNING || session.status == SessionStatus.BUSY -> {
            statusColor = Color(0xFF56b6c2)
            statusLabel = stringResource(R.string.session_busy)
        }
        else -> {
            statusColor = Color(0xFF7fd88f)
            statusLabel = stringResource(R.string.session_idle)
        }
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
        Text(
            text = session.updatedAt.toRelativeTimeString(),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            fontSize = 11.sp,
        )
    }
}

@Composable
internal fun ConnectionDot(status: ConnectionStatus) {
    val color = when (status) {
        ConnectionStatus.CONNECTED -> Color(0xFF7fd88f)
        ConnectionStatus.GATEWAY_CONNECTED -> Color(0xFF5b9cf5)
        ConnectionStatus.CONNECTING -> Color(0xFFf5a742)
        ConnectionStatus.PAIRING -> Color(0xFFf5a742)
        ConnectionStatus.ERROR -> Color(0xFFe06c75)
        ConnectionStatus.NOT_BRIDGE -> Color(0xFFe06c75)
        ConnectionStatus.DISCONNECTED -> Color(0xFF808080)
    }
    Spacer(
        modifier = Modifier
            .padding(end = 8.dp)
            .width(6.dp)
            .height(6.dp)
            .clip(CircleShape)
            .background(color),
    )
}

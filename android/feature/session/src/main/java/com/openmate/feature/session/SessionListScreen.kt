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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
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

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            TopBar(
                title = dirName,
                subtitle = directory,
                onBack = onBack,
            )
        },
        floatingActionButton = {
            FloatingActionButton(onClick = {
                newSessionTitle = ""
                    showNewSessionDialog = true
            }) {
                Icon(Icons.Default.Add, contentDescription = "New Session")
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
                    Text("搜索会话", style = MaterialTheme.typography.bodySmall)
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
                    message = "No sessions",
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
            title = { Text("新建会话") },
            text = {
                OutlinedTextField(
                    value = newSessionTitle,
                    onValueChange = { newSessionTitle = it },
                    label = { Text("标题（可选）") },
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
                    Text("创建")
                }
            },
            dismissButton = {
                TextButton(onClick = { showNewSessionDialog = false }) {
                    Text("取消")
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
    val leftBorderColor: Color
    val cardAlpha: Float

    when {
        session.isArchived -> {
            statusColor = Color(0xFF808080)
            statusLabel = "已归档"
            leftBorderColor = Color(0xFF808080)
            cardAlpha = 0.5f
        }
        session.status == SessionStatus.ERROR -> {
            statusColor = Color(0xFFe06c75)
            statusLabel = "错误"
            leftBorderColor = Color(0xFFe06c75)
            cardAlpha = 1f
        }
        session.status == SessionStatus.RUNNING || session.status == SessionStatus.BUSY -> {
            statusColor = Color(0xFF56b6c2)
            statusLabel = "忙碌"
            leftBorderColor = Color(0xFF56b6c2)
            cardAlpha = 1f
        }
        else -> {
            statusColor = Color(0xFF7fd88f)
            statusLabel = "空闲"
            leftBorderColor = Color(0xFF7fd88f)
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
                    text = session.title.ifBlank { "Untitled" },
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
            Spacer(modifier = Modifier.height(8.dp))
            Row(
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
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
private fun ConnectionDot(status: ConnectionStatus) {
    val color = when (status) {
        ConnectionStatus.CONNECTED -> Color(0xFF7fd88f)
        ConnectionStatus.CONNECTING -> Color(0xFFf5a742)
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

package com.openmate.feature.session

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.openmate.core.data.sync.SyncLogCategory
import com.openmate.core.data.sync.SyncLogEntry

private enum class CopyRange { All, First30, Last30 }

@Composable
fun SyncLogScreen(
    currentSessionId: String,
    logEntries: List<SyncLogEntry>,
    onBack: () -> Unit,
    onCopy: (List<String>) -> Unit,
    onClear: () -> Unit,
    onReconnectSse: () -> Unit,
    onManualIncrementalSync: () -> Unit,
) {
    var query by remember { mutableStateOf("") }
    var selectedCategory by remember { mutableStateOf<SyncLogCategory?>(null) }
    var showClearDialog by remember { mutableStateOf(false) }
    var showCopyMenu by remember { mutableStateOf(false) }

    val categoryFiltered = if (selectedCategory != null) {
        logEntries.filter { it.category == selectedCategory }
    } else {
        logEntries
    }

    var previousVisibleLogs by remember { mutableStateOf(categoryFiltered.map { it.renderedText }) }
    val renderedLogs = categoryFiltered.map { it.renderedText }
    val filterResult = filterRenderedLogs(renderedLogs, query, previousVisibleLogs, stringResource(R.string.invalid_regex))
    previousVisibleLogs = filterResult.visibleLogs

    val visibleCount = filterResult.visibleLogs.size

    val listState = rememberLazyListState()
    var autoFollowEnabled by remember { mutableStateOf(true) }
    var prevLogCount by remember { mutableIntStateOf(0) }
    LaunchedEffect(visibleCount) {
        if (shouldAutoFollowSyncLogs(autoFollowEnabled, prevLogCount, visibleCount)) {
            listState.animateScrollToItem(visibleCount - 1)
        }
        prevLogCount = visibleCount
    }

    if (showClearDialog) {
        AlertDialog(
            onDismissRequest = { showClearDialog = false },
            title = { Text(stringResource(R.string.sync_logs_clear_title)) },
            text = { Text(stringResource(R.string.sync_logs_clear_message)) },
            confirmButton = {
                TextButton(onClick = {
                    onClear()
                    showClearDialog = false
                }) {
                    Text(stringResource(R.string.sync_logs_clear_confirm_action))
                }
            },
            dismissButton = {
                TextButton(onClick = { showClearDialog = false }) {
                    Text(stringResource(R.string.sync_logs_clear_cancel))
                }
            },
        )
    }

    if (showCopyMenu) {
        AlertDialog(
            onDismissRequest = { showCopyMenu = false },
            title = { Text(stringResource(R.string.sync_logs_copy_title)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(stringResource(R.string.sync_logs_copy_message, visibleCount))
                    CopyRange.entries.forEach { range ->
                        OutlinedButton(
                            onClick = {
                                val lines = when (range) {
                                    CopyRange.All -> filterResult.visibleLogs
                                    CopyRange.First30 -> filterResult.visibleLogs.take(30)
                                    CopyRange.Last30 -> filterResult.visibleLogs.takeLast(30)
                                }
                                onCopy(lines)
                                showCopyMenu = false
                            },
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(8.dp),
                        ) {
                            Text(
                                when (range) {
                                    CopyRange.All -> stringResource(R.string.sync_logs_copy_all, visibleCount)
                                    CopyRange.First30 -> stringResource(R.string.sync_logs_copy_first, 30)
                                    CopyRange.Last30 -> stringResource(R.string.sync_logs_copy_last, 30)
                                }
                            )
                        }
                    }
                }
            },
            confirmButton = {},
            dismissButton = {
                TextButton(onClick = { showCopyMenu = false }) {
                    Text(stringResource(R.string.sync_logs_clear_cancel))
                }
            },
        )
    }

    Scaffold(
        bottomBar = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                HorizontalDivider()
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    OutlinedButton(
                        onClick = { showCopyMenu = true },
                        shape = RoundedCornerShape(8.dp),
                        modifier = Modifier.weight(1f),
                    ) { Text(stringResource(R.string.copy)) }
                    OutlinedButton(
                        onClick = { showClearDialog = true },
                        shape = RoundedCornerShape(8.dp),
                        modifier = Modifier.weight(1f),
                    ) { Text(stringResource(R.string.delete)) }
                }
            }
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = onBack, modifier = Modifier.size(36.dp)) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = stringResource(R.string.content_desc_back),
                    )
                }
                Text(
                    text = stringResource(R.string.sync_logs),
                    style = MaterialTheme.typography.titleMedium,
                )
            }

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                OutlinedButton(
                    onClick = onReconnectSse,
                    shape = RoundedCornerShape(8.dp),
                ) { Text(stringResource(R.string.sync_logs_reconnect_sse)) }
                OutlinedButton(
                    onClick = onManualIncrementalSync,
                    shape = RoundedCornerShape(8.dp),
                ) { Text(stringResource(R.string.sync_logs_incremental_sync)) }
            }

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                FilterChip(
                    selected = selectedCategory == null,
                    onClick = { selectedCategory = null },
                    label = { Text("ALL") },
                    shape = RoundedCornerShape(8.dp),
                )
                SyncLogCategory.entries.forEach { cat ->
                    FilterChip(
                        selected = selectedCategory == cat,
                        onClick = { selectedCategory = if (selectedCategory == cat) null else cat },
                        label = { Text(cat.name) },
                        shape = RoundedCornerShape(8.dp),
                    )
                }
            }

            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                modifier = Modifier.fillMaxWidth(),
                placeholder = { Text(stringResource(R.string.sync_logs_search_hint)) },
                supportingText = {
                    filterResult.regexError?.let {
                        Text(text = it, color = MaterialTheme.colorScheme.error)
                    }
                },
                shape = RoundedCornerShape(8.dp),
                singleLine = true,
            )

            SelectionContainer {
                LazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxSize(),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    items(
                        categoryFiltered.filter { entry ->
                            filterResult.visibleLogs.contains(entry.renderedText)
                        },
                        key = { it.id },
                    ) { entry ->
                        Text(
                            text = entry.renderedText,
                            style = MaterialTheme.typography.bodySmall,
                            color = when {
                                entry.sessionId == currentSessionId -> MaterialTheme.colorScheme.primary
                                else -> MaterialTheme.colorScheme.onSurfaceVariant
                            },
                        )
                    }
                }
            }
        }
    }
}

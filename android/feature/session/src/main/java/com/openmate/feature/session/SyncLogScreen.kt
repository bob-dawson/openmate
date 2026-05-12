package com.openmate.feature.session

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.openmate.core.data.sync.SyncLogEntry

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
    var previousVisibleLogs by remember { mutableStateOf(logEntries.map { it.renderedText }) }
    val renderedLogs = logEntries.map { it.renderedText }
    val filterResult = filterRenderedLogs(renderedLogs, query, previousVisibleLogs)
    previousVisibleLogs = filterResult.visibleLogs

    val listState = rememberLazyListState()
    var autoFollowEnabled by remember { mutableStateOf(true) }
    var prevLogCount by remember { mutableIntStateOf(0) }
    LaunchedEffect(filterResult.visibleLogs.size) {
        if (shouldAutoFollowSyncLogs(autoFollowEnabled, prevLogCount, filterResult.visibleLogs.size)) {
            listState.animateScrollToItem(filterResult.visibleLogs.size - 1)
        }
        prevLogCount = filterResult.visibleLogs.size
    }

    var clearConfirming by remember { mutableStateOf(false) }
    val context = LocalContext.current

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = onBack) { Text(stringResource(R.string.content_desc_back)) }
            Button(onClick = { onCopy(filterResult.visibleLogs) }) { Text(stringResource(R.string.copy)) }
            Button(onClick = onReconnectSse) { Text(stringResource(R.string.sync_logs_reconnect_sse)) }
            Button(onClick = onManualIncrementalSync) { Text(stringResource(R.string.sync_logs_incremental_sync)) }
            Button(
                onClick = {
                    if (shouldExecuteSyncLogClear(clearConfirming, true)) {
                        onClear()
                        clearConfirming = false
                    } else {
                        clearConfirming = true
                    }
                },
            ) {
                Text(if (clearConfirming) stringResource(R.string.sync_logs_clear_confirm) else stringResource(R.string.delete))
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
        )

        SelectionContainer {
            LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                items(logEntries.filter { entry -> filterResult.visibleLogs.contains(entry.renderedText) }) { entry ->
                    Text(
                        text = entry.renderedText,
                        color = when {
                            entry.sessionId == currentSessionId -> MaterialTheme.colorScheme.primary
                            else -> MaterialTheme.colorScheme.onSurface
                        },
                    )
                }
            }
        }
    }
}

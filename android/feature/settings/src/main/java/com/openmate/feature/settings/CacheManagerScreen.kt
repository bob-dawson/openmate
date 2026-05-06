package com.openmate.feature.settings

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import androidx.hilt.navigation.compose.hiltViewModel
import com.openmate.core.ui.theme.TopBarBackground
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.roundToInt

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CacheManagerScreen(
    onBack: () -> Unit,
    viewModel: CacheManagerViewModel = hiltViewModel(),
) {
    val cacheFiles by viewModel.cacheFiles.collectAsState()
    val sortField by viewModel.sortField.collectAsState()
    val sortAscending by viewModel.sortAscending.collectAsState()
    val errorMessage by viewModel.errorMessage.collectAsState()
    var contextMenuFile by remember { mutableStateOf<CacheFileInfo?>(null) }
    var showRenameDialog by remember { mutableStateOf(false) }
    var renameTarget by remember { mutableStateOf<CacheFileInfo?>(null) }
    var renameText by remember { mutableStateOf("") }
    var showDeleteConfirm by remember { mutableStateOf<CacheFileInfo?>(null) }

    val lifecycleOwner = LocalLifecycleOwner.current
    val itemPositions = remember { mutableStateMapOf<String, Offset>() }

    LaunchedEffect(lifecycleOwner) {
        lifecycleOwner.lifecycle.addObserver(object : androidx.lifecycle.LifecycleObserver {
            @androidx.lifecycle.OnLifecycleEvent(androidx.lifecycle.Lifecycle.Event.ON_RESUME)
            fun onResume() {
                viewModel.retryPendingApkInstall()
                viewModel.refresh()
            }
        })
    }

    LaunchedEffect(errorMessage) {
        errorMessage?.let {
            viewModel.clearError()
        }
    }

    val sortedFiles = remember(cacheFiles, sortField, sortAscending) {
        val files = cacheFiles
        when (sortField) {
            CacheSortField.NAME -> if (sortAscending) files.sortedBy { it.name.lowercase() } else files.sortedByDescending { it.name.lowercase() }
            CacheSortField.SIZE -> if (sortAscending) files.sortedBy { it.size } else files.sortedByDescending { it.size }
            CacheSortField.MODIFIED -> if (sortAscending) files.sortedBy { it.lastModified } else files.sortedByDescending { it.lastModified }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.cache_manager)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.back),
                            tint = MaterialTheme.colorScheme.primary,
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = TopBarBackground,
                ),
            )
        },
    ) { padding ->
        if (sortedFiles.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = stringResource(R.string.no_cached_files),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        } else {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
            ) {
                CacheHeaderRow(
                    sortField = sortField,
                    sortAscending = sortAscending,
                    onSort = { viewModel.setSortField(it) },
                )
                HorizontalDivider()
                LazyColumn {
                    items(sortedFiles, key = { it.file.absolutePath }) { info ->
                        Box(
                            modifier = Modifier.onGloballyPositioned { coords ->
                                itemPositions[info.file.absolutePath] = coords.positionInWindow()
                            }
                        ) {
                            CacheFileRow(
                                info = info,
                                onClick = { viewModel.openFile(info) },
                                onLongClick = { contextMenuFile = info },
                            )
                        }
                    }
                }
            }
        }
    }

    contextMenuFile?.let { info ->
        val pos = itemPositions[info.file.absolutePath]
        CacheFileContextMenu(
            info = info,
            offset = pos,
            onDismiss = { contextMenuFile = null },
            onShare = {
                contextMenuFile = null
                viewModel.shareFile(info)
            },
            onDelete = {
                contextMenuFile = null
                showDeleteConfirm = info
            },
            onRename = {
                contextMenuFile = null
                renameTarget = info
                renameText = info.name
                showRenameDialog = true
            },
        )
    }

    showDeleteConfirm?.let { info ->
        AlertDialog(
            onDismissRequest = { showDeleteConfirm = null },
            title = { Text(stringResource(R.string.delete_file)) },
            text = { Text(stringResource(R.string.delete_file_confirm, info.name)) },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.deleteFile(info)
                    showDeleteConfirm = null
                }) {
                    Text(stringResource(R.string.delete), color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteConfirm = null }) {
                    Text(stringResource(R.string.cancel))
                }
            },
        )
    }

    if (showRenameDialog && renameTarget != null) {
        AlertDialog(
            onDismissRequest = {
                showRenameDialog = false
                renameTarget = null
            },
            title = { Text(stringResource(R.string.rename_file)) },
            text = {
                OutlinedTextField(
                    value = renameText,
                    onValueChange = { renameText = it },
                    singleLine = true,
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.renameFile(renameTarget!!, renameText)
                    showRenameDialog = false
                    renameTarget = null
                }) {
                    Text(stringResource(R.string.confirm))
                }
            },
            dismissButton = {
                TextButton(onClick = {
                    showRenameDialog = false
                    renameTarget = null
                }) {
                    Text(stringResource(R.string.cancel))
                }
            },
        )
    }
}

@Composable
private fun CacheHeaderRow(
    sortField: CacheSortField,
    sortAscending: Boolean,
    onSort: (CacheSortField) -> Unit,
) {
    val nameIcon = when {
        sortField == CacheSortField.NAME && sortAscending -> "▲"
        sortField == CacheSortField.NAME && !sortAscending -> "▼"
        else -> ""
    }
    val sizeIcon = when {
        sortField == CacheSortField.SIZE && sortAscending -> "▲"
        sortField == CacheSortField.SIZE && !sortAscending -> "▼"
        else -> ""
    }
    val timeIcon = when {
        sortField == CacheSortField.MODIFIED && sortAscending -> "▲"
        sortField == CacheSortField.MODIFIED && !sortAscending -> "▼"
        else -> ""
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = stringResource(R.string.file_browser_name) + " $nameIcon",
            style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold),
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier
                .weight(1f)
                .clickable { onSort(CacheSortField.NAME) },
        )
        Text(
            text = stringResource(R.string.file_browser_size) + " $sizeIcon",
            style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold),
            color = MaterialTheme.colorScheme.primary,
            textAlign = TextAlign.End,
            modifier = Modifier
                .width(80.dp)
                .clickable { onSort(CacheSortField.SIZE) },
        )
        Text(
            text = stringResource(R.string.file_browser_modified) + " $timeIcon",
            style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold),
            color = MaterialTheme.colorScheme.primary,
            textAlign = TextAlign.End,
            modifier = Modifier
                .width(120.dp)
                .padding(start = 8.dp)
                .clickable { onSort(CacheSortField.MODIFIED) },
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun CacheFileRow(
    info: CacheFileInfo,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .combinedClickable(
                onClick = onClick,
                onLongClick = onLongClick,
            )
            .padding(horizontal = 16.dp, vertical = 10.dp),
    ) {
        Text(
            text = "\uD83D\uDCC4 ${info.name}",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.fillMaxWidth(),
        )
        Row(
            modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Spacer(modifier = Modifier.weight(1f))
            Text(
                text = formatCacheSize(info.size),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.End,
                modifier = Modifier.width(80.dp),
            )
            Text(
                text = formatCacheTime(info.lastModified),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.End,
                modifier = Modifier.width(120.dp).padding(start = 8.dp),
            )
        }
    }
    HorizontalDivider()
}

@Composable
private fun CacheFileContextMenu(
    info: CacheFileInfo,
    offset: Offset?,
    onDismiss: () -> Unit,
    onShare: () -> Unit,
    onDelete: () -> Unit,
    onRename: () -> Unit,
) {
    Popup(
        alignment = Alignment.TopStart,
        offset = if (offset != null) IntOffset(offset.x.roundToInt(), offset.y.roundToInt()) else IntOffset.Zero,
        onDismissRequest = onDismiss,
        properties = PopupProperties(focusable = true),
    ) {
        Card(
            modifier = Modifier.widthIn(max = 250.dp),
            shape = RoundedCornerShape(12.dp),
            elevation = CardDefaults.cardElevation(defaultElevation = 4.dp),
        ) {
            Column {
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.share)) },
                    onClick = onShare,
                )
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.delete), color = MaterialTheme.colorScheme.error) },
                    onClick = onDelete,
                )
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.rename)) },
                    onClick = onRename,
                )
            }
        }
    }
}

private fun formatCacheSize(bytes: Long): String {
    return when {
        bytes < 1024 -> "$bytes B"
        bytes < 1024 * 1024 -> "${bytes / 1024} KB"
        bytes < 1024 * 1024 * 1024 -> "${bytes / (1024 * 1024)} MB"
        else -> "${bytes / (1024 * 1024 * 1024)} GB"
    }
}

private fun formatCacheTime(epochMs: Long): String {
    val sdf = SimpleDateFormat("MM/dd HH:mm", Locale.getDefault())
    return sdf.format(Date(epochMs))
}

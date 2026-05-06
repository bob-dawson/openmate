package com.openmate.feature.settings

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CreateNewFolder
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
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
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import androidx.hilt.navigation.compose.hiltViewModel
import com.openmate.core.ui.component.DirectoryPickerDialog
import com.openmate.core.ui.theme.TopBarBackground
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.roundToInt

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LocalFileManagerScreen(
    onBack: () -> Unit,
    viewModel: LocalFileManagerViewModel = hiltViewModel(),
) {
    val entries by viewModel.entries.collectAsState()
    val currentDir by viewModel.currentDir.collectAsState()
    val isAtRoot by remember { derivedStateOf { viewModel.isAtRoot() } }
    val relativePath by remember { derivedStateOf { viewModel.relativePath() } }

    var contextMenuEntry by remember { mutableStateOf<LocalFileEntry?>(null) }
    var contextMenuOffset by remember { mutableStateOf(Offset.Zero) }
    var showDeleteConfirm by remember { mutableStateOf<LocalFileEntry?>(null) }
    var showRenameDialog by remember { mutableStateOf<Pair<LocalFileEntry, String>?>(null) }
    var showCreateDirDialog by remember { mutableStateOf(false) }
    var showMovePicker by remember { mutableStateOf<LocalFileEntry?>(null) }

    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = androidx.lifecycle.LifecycleEventObserver { _, event ->
            if (event == androidx.lifecycle.Lifecycle.Event.ON_RESUME) {
                viewModel.retryPendingApkInstall()
                viewModel.refresh()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("Local Files")
                        if (!isAtRoot && relativePath.isNotBlank()) {
                            Text(
                                text = relativePath,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                },
                navigationIcon = {
                    if (!isAtRoot) {
                        IconButton(onClick = { viewModel.navigateUp() }) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, "Navigate up")
                        }
                    } else {
                        IconButton(onClick = onBack) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back")
                        }
                    }
                },
                actions = {
                    IconButton(onClick = {
                        showCreateDirDialog = true
                    }) {
                        Icon(
                            Icons.Default.CreateNewFolder,
                            contentDescription = "Create directory",
                            tint = MaterialTheme.colorScheme.primary,
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = TopBarBackground),
            )
        },
    ) { padding ->
        if (entries.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    "Empty directory",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        } else {
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .padding(horizontal = 16.dp),
            ) {
                items(entries, key = { it.file.absolutePath }) { entry ->
                    FileRow(
                        entry = entry,
                        onClick = {
                            if (entry.isDirectory) {
                                viewModel.navigateInto(entry.name)
                            } else {
                                viewModel.openFile(entry)
                            }
                        },
                        onLongClick = {
                            contextMenuOffset = it
                            contextMenuEntry = entry
                        },
                    )
                }
            }
        }
    }

    if (contextMenuEntry != null) {
        val entry = contextMenuEntry!!
        Popup(
            alignment = Alignment.TopStart,
            offset = IntOffset(contextMenuOffset.x.roundToInt(), contextMenuOffset.y.roundToInt()),
            onDismissRequest = { contextMenuEntry = null },
            properties = PopupProperties(focusable = true),
        ) {
            Card(
                shape = RoundedCornerShape(12.dp),
                elevation = CardDefaults.cardElevation(defaultElevation = 4.dp),
            ) {
                Column {
                    if (entry.isDirectory) {
                        ContextMenuItem("Rename") {
                            contextMenuEntry = null
                            showRenameDialog = Pair(entry, entry.name)
                        }
                        ContextMenuItem("Delete", isDestructive = true) {
                            contextMenuEntry = null
                            showDeleteConfirm = entry
                        }
                    } else {
                        ContextMenuItem("Open") {
                            contextMenuEntry = null
                            viewModel.openFile(entry)
                        }
                        ContextMenuItem("Share") {
                            contextMenuEntry = null
                            viewModel.shareFile(entry)
                        }
                        ContextMenuItem("Move to...") {
                            contextMenuEntry = null
                            showMovePicker = entry
                        }
                        ContextMenuItem("Rename") {
                            contextMenuEntry = null
                            showRenameDialog = Pair(entry, entry.name)
                        }
                        ContextMenuItem("Delete", isDestructive = true) {
                            contextMenuEntry = null
                            showDeleteConfirm = entry
                        }
                    }
                }
            }
        }
    }

    if (showDeleteConfirm != null) {
        val entry = showDeleteConfirm!!
        AlertDialog(
            onDismissRequest = { showDeleteConfirm = null },
            title = { Text(if (entry.isDirectory) "Delete directory" else "Delete file") },
            text = { Text("Are you sure you want to delete ${entry.name}?") },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.deleteFile(entry)
                    showDeleteConfirm = null
                }) {
                    Text("Delete", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteConfirm = null }) {
                    Text("Cancel")
                }
            },
        )
    }

    if (showRenameDialog != null) {
        val (entry, initialName) = showRenameDialog!!
        var newName by remember { mutableStateOf(initialName) }
        AlertDialog(
            onDismissRequest = { showRenameDialog = null },
            title = { Text("Rename") },
            text = {
                OutlinedTextField(
                    value = newName,
                    onValueChange = { newName = it },
                    singleLine = true,
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    if (newName.isNotBlank() && newName != initialName) {
                        viewModel.renameFile(entry, newName)
                    }
                    showRenameDialog = null
                }) {
                    Text("Confirm")
                }
            },
            dismissButton = {
                TextButton(onClick = { showRenameDialog = null }) {
                    Text("Cancel")
                }
            },
        )
    }

    if (showCreateDirDialog) {
        var dirName by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { showCreateDirDialog = false },
            title = { Text("Create directory") },
            text = {
                OutlinedTextField(
                    value = dirName,
                    onValueChange = { dirName = it },
                    singleLine = true,
                    placeholder = { Text("Directory name") },
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    if (dirName.isNotBlank()) {
                        viewModel.createDirectory(dirName)
                    }
                    showCreateDirDialog = false
                }) {
                    Text("Create")
                }
            },
            dismissButton = {
                TextButton(onClick = { showCreateDirDialog = false }) {
                    Text("Cancel")
                }
            },
        )
    }

    if (showMovePicker != null) {
        val entry = showMovePicker!!
        val pickerContext = LocalContext.current
        val rootDir = remember {
            val dir = File(pickerContext.cacheDir, "file_cache")
            dir.mkdirs()
            dir
        }
        DirectoryPickerDialog(
            rootDir = rootDir,
            onDismiss = { showMovePicker = null },
            onSelect = { targetDir ->
                viewModel.moveFile(entry, targetDir)
                showMovePicker = null
            },
        )
    }
}

@Composable
private fun ContextMenuItem(
    label: String,
    isDestructive: Boolean = false,
    onClick: () -> Unit,
) {
    androidx.compose.material3.DropdownMenuItem(
        text = {
            Text(
                label,
                color = if (isDestructive) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
            )
        },
        onClick = onClick,
    )
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun FileRow(
    entry: LocalFileEntry,
    onClick: () -> Unit,
    onLongClick: (Offset) -> Unit,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .combinedClickable(
                onClick = onClick,
                onLongClick = { onLongClick(Offset.Zero) },
            )
            .padding(vertical = 8.dp),
    ) {
        Icon(
            imageVector = if (entry.isDirectory) Icons.Default.Folder else Icons.Default.Description,
            contentDescription = null,
            modifier = Modifier.size(20.dp),
            tint = if (entry.isDirectory) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(modifier = Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = entry.name,
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (!entry.isDirectory) {
                Text(
                    text = formatSize(entry.size) + " · " + formatTime(entry.lastModified),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

private fun formatSize(bytes: Long): String {
    return when {
        bytes < 1024 -> "$bytes B"
        bytes < 1024 * 1024 -> "${bytes / 1024} KB"
        bytes < 1024 * 1024 * 1024 -> String.format("%.1f MB", bytes / (1024.0 * 1024.0))
        else -> String.format("%.1f GB", bytes / (1024.0 * 1024.0 * 1024.0))
    }
}

private fun formatTime(epochMs: Long): String {
    val sdf = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault())
    return sdf.format(Date(epochMs))
}

# Step 4: Rewrite Cache Manager → Local File Manager

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Replace the flat-file cache manager with a directory-based local file manager that supports multi-level navigation, create directory, and move file operations.

**Architecture:** Rename `CacheManagerViewModel` → `LocalFileManagerViewModel` and `CacheManagerScreen` → `LocalFileManagerScreen`. The ViewModel now tracks a `currentDir` state and lists only the current directory's contents. The Screen adds directory navigation UI, create directory button, and "Move to..." context menu.

**Tech Stack:** Kotlin, Jetpack Compose, Material 3

---

### Task 4.1: Rewrite LocalFileManagerViewModel

**Files:**
- Delete: `D:\openmate\android\feature\settings\src\main\java\com\openmate\feature\settings\CacheManagerViewModel.kt`
- Create: `D:\openmate\android\feature\settings\src\main\java\com\openmate\feature\settings\LocalFileManagerViewModel.kt`

- [ ] **Step 1: Create new ViewModel**

```kotlin
package com.openmate.feature.settings

import android.app.Activity
import android.content.Context
import android.util.Log
import androidx.lifecycle.ViewModel
import com.openmate.core.common.FileOpener
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File
import javax.inject.Inject

data class LocalFileEntry(
    val file: File,
    val name: String,
    val isDirectory: Boolean,
    val size: Long,
    val lastModified: Long,
)

enum class LocalSortField { NAME, SIZE, MODIFIED }

@HiltViewModel
class LocalFileManagerViewModel @Inject constructor(
    @ApplicationContext private val appContext: Context,
) : ViewModel() {
    private val TAG = "LocalFileManagerVM"

    private val rootDir get() = File(appContext.cacheDir, "file_cache")

    private val _currentDir = MutableStateFlow(rootDir)
    val currentDir: StateFlow<File> = _currentDir.asStateFlow()

    private val _entries = MutableStateFlow<List<LocalFileEntry>>(emptyList())
    val entries: StateFlow<List<LocalFileEntry>> = _entries.asStateFlow()

    private val _sortField = MutableStateFlow(LocalSortField.NAME)
    val sortField: StateFlow<LocalSortField> = _sortField.asStateFlow()

    private val _sortAsc = MutableStateFlow(true)
    val sortAsc: StateFlow<Boolean> = _sortAsc.asStateFlow()

    private val _errorMessage = MutableStateFlow<String?>(null)
    val errorMessage: StateFlow<String?> = _errorMessage.asStateFlow()

    var pendingApkFile: File? = null
        private set
    var pendingApkName: String? = null
        private set

    fun refresh() {
        val dir = _currentDir.value
        if (!dir.exists()) {
            dir.mkdirs()
        }
        val files = dir.listFiles()?.toList() ?: emptyList()
        _entries.value = files.map { file ->
            LocalFileEntry(
                file = file,
                name = file.name,
                isDirectory = file.isDirectory,
                size = if (file.isFile) file.length() else 0L,
                lastModified = file.lastModified(),
            )
        }.let { entries ->
            val sorted = when (_sortField.value) {
                LocalSortField.NAME -> entries.sortedWith(
                    compareBy<LocalFileEntry> { !it.isDirectory }
                        .thenBy { it.name.lowercase() }
                )
                LocalSortField.SIZE -> entries.sortedWith(
                    compareBy<LocalFileEntry> { !it.isDirectory }
                        .thenBy { it.size }
                )
                LocalSortField.MODIFIED -> entries.sortedWith(
                    compareBy<LocalFileEntry> { !it.isDirectory }
                        .thenBy { it.lastModified }
                )
            }
            if (_sortAsc.value) sorted else sorted.reversed()
        }
    }

    fun setSortField(field: LocalSortField) {
        if (_sortField.value == field) {
            _sortAsc.value = !_sortAsc.value
        } else {
            _sortField.value = field
            _sortAsc.value = true
        }
        refresh()
    }

    fun navigateInto(name: String) {
        val target = File(_currentDir.value, name)
        if (target.isDirectory) {
            _currentDir.value = target
            refresh()
        }
    }

    fun navigateUp() {
        val parent = _currentDir.value.parentFile
        if (parent != null && parent.startsWith(rootDir)) {
            _currentDir.value = parent
            refresh()
        }
    }

    fun isAtRoot(): Boolean = _currentDir.value == rootDir

    fun relativePath(): String {
        return _currentDir.value.absolutePath.removePrefix(rootDir.absolutePath).removePrefix("/")
    }

    fun createDirectory(name: String) {
        val dir = File(_currentDir.value, name)
        if (dir.exists()) {
            _errorMessage.value = "Directory already exists: $name"
            return
        }
        dir.mkdirs()
        refresh()
    }

    fun moveFile(entry: LocalFileEntry, targetDir: File) {
        val dest = File(targetDir, entry.name)
        if (dest.exists()) {
            _errorMessage.value = "File already exists: ${entry.name}"
            return
        }
        val moved = entry.file.renameTo(dest)
        if (!moved) {
            _errorMessage.value = "Failed to move ${entry.name}"
        }
        refresh()
    }

    fun openFile(entry: LocalFileEntry) {
        if (entry.isDirectory) return
        val ext = entry.name.substringAfterLast('.', "")
        if (ext == "apk") {
            pendingApkFile = entry.file
            pendingApkName = entry.name
            FileOpener.installApk(appContext, entry.file, entry.name)
        } else {
            FileOpener.openWithSystemViewer(appContext, entry.file, entry.name)
        }
    }

    fun shareFile(entry: LocalFileEntry) {
        if (entry.isDirectory) return
        FileOpener.shareFile(appContext, entry.file, entry.name)
    }

    fun deleteFile(entry: LocalFileEntry) {
        entry.file.deleteRecursively()
        refresh()
    }

    fun renameFile(entry: LocalFileEntry, newName: String) {
        val dest = File(entry.file.parentFile, newName)
        if (dest.exists()) {
            _errorMessage.value = "Already exists: $newName"
            return
        }
        val renamed = entry.file.renameTo(dest)
        if (!renamed) {
            _errorMessage.value = "Rename failed"
        }
        refresh()
    }

    fun retryPendingApkInstall() {
        val file = pendingApkFile ?: return
        val name = pendingApkName ?: return
        FileOpener.installApk(appContext, file, name)
    }

    fun clearError() {
        _errorMessage.value = null
    }
}
```

- [ ] **Step 2: Delete old CacheManagerViewModel**

Delete file: `D:\openmate\android\feature\settings\src\main\java\com\openmate\feature\settings\CacheManagerViewModel.kt`

- [ ] **Step 3: Build check**

Run: `cd D:\openmate\android && .\gradlew.bat :feature:settings:compileDebugKotlin --no-daemon 2>&1 | Select-String -Pattern "^e:"`
Expected: No errors (note: CacheManagerScreen will break, fixed in next task)

---

### Task 4.2: Rewrite LocalFileManagerScreen

**Files:**
- Delete: `D:\openmate\android\feature\settings\src\main\java\com\openmate\feature\settings\CacheManagerScreen.kt`
- Create: `D:\openmate\android\feature\settings\src\main\java\com\openmate\feature\settings\LocalFileManagerScreen.kt`

- [ ] **Step 1: Create new Screen**

```kotlin
package com.openmate.feature.settings

import androidx.compose.foundation.ExperimentalFoundationApi
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
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
        val rootDir = remember {
            val dir = File(androidx.compose.ui.platform.LocalContext.current.cacheDir, "file_cache")
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
    var rowOffset by remember { mutableStateOf(Offset.Zero) }
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .combinedClickable(
                onClick = onClick,
                onLongClick = { onLongClick(rowOffset) },
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
```

- [ ] **Step 2: Delete old CacheManagerScreen**

Delete file: `D:\openmate\android\feature\settings\src\main\java\com\openmate\feature\settings\CacheManagerScreen.kt`

- [ ] **Step 3: Build check**

Run: `cd D:\openmate\android && .\gradlew.bat :feature:settings:compileDebugKotlin --no-daemon 2>&1 | Select-String -Pattern "^e:"`
Expected: No errors

---

### Task 4.3: Update navigation to use new screen

**Files:**
- Modify: `D:\openmate\android\feature\session\src\main\java\com\openmate\feature\session\SessionNavigation.kt`

- [ ] **Step 1: Update imports and route references**

Replace `import com.openmate.feature.settings.CacheManagerScreen` with:
```kotlin
import com.openmate.feature.settings.LocalFileManagerScreen
```

Replace route constant (line 19):
```kotlin
    const val LOCAL_FILE_MANAGER = "local_file_manager"
```

Replace the composable block (lines 97-101):
```kotlin
    composable(SessionRoutes.LOCAL_FILE_MANAGER) {
        LocalFileManagerScreen(
            onBack = { navController.popBackStack() },
        )
    }
```

Update the `onNavigateToCacheManager` lambda (line 34-36):
```kotlin
            onNavigateToLocalFileManager = {
                navController.navigate(SessionRoutes.LOCAL_FILE_MANAGER)
            },
```

- [ ] **Step 2: Update WorkspaceListScreen to use new names**

In `D:\openmate\android\feature\session\src\main\java\com\openmate\feature\session\WorkspaceListScreen.kt`:

Rename parameter `onNavigateToCacheManager` → `onNavigateToLocalFileManager` in `WorkspaceListScreen` function signature.

Update the `SettingsContent` composable call site to pass the renamed callback.

- [ ] **Step 3: Build check**

Run: `cd D:\openmate\android && .\gradlew.bat :feature:session:compileDebugKotlin --no-daemon 2>&1 | Select-String -Pattern "^e:"`
Expected: No errors

- [ ] **Step 4: Commit**

```bash
git add -A
git commit -m "refactor: replace CacheManager with LocalFileManager"
```

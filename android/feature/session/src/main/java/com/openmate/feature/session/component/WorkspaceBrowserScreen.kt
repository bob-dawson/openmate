package com.openmate.feature.session.component

import android.widget.Toast
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import com.openmate.feature.session.R
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.graphics.Color
import androidx.hilt.navigation.compose.hiltViewModel
import com.openmate.core.network.dto.BridgeDirEntryDto
import com.openmate.core.network.dto.BridgeFileContent
import com.openmate.core.network.dto.BridgeSearchResultDto
import com.openmate.core.ui.theme.TopBarBackground
import com.openmate.feature.session.DownloadState
import com.openmate.feature.session.WorkspaceBrowserViewModel
import dev.jeziellago.compose.markdowntext.MarkdownText
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.io.File

private val CodeBlockBackground = Color(0xFF2a2a3a)
private val CodeBlockText = Color(0xFFe0e0f0)

private const val LARGE_FILE_THRESHOLD = 10 * 1024 * 1024L

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WorkspaceBrowserScreen(
    initialDirectory: String,
    onBack: () -> Unit,
    viewModel: WorkspaceBrowserViewModel = hiltViewModel(),
) {
    val apiClient = viewModel.apiClient
    val context = LocalContext.current
    val downloadState by viewModel.downloadState.collectAsState()
    var currentPath by remember { mutableStateOf(initialDirectory) }
    var entries by remember { mutableStateOf<List<BridgeDirEntryDto>>(emptyList()) }
    var searchQuery by remember { mutableStateOf("") }
    var searchResults by remember { mutableStateOf<List<BridgeSearchResultDto>>(emptyList()) }
    var isLoading by remember { mutableStateOf(false) }
    var loadError by remember { mutableStateOf("") }
    var viewingFile by remember { mutableStateOf<FileViewState?>(null) }
    var fileContent by remember { mutableStateOf<BridgeFileContent?>(null) }
    var fileLoading by remember { mutableStateOf(false) }
    var fileError by remember { mutableStateOf("") }
    var largeFileConfirm by remember { mutableStateOf<LargeFileConfirm?>(null) }
    val scope = rememberCoroutineScope()

    fun loadDir(path: String) {
        scope.launch(Dispatchers.IO) {
            isLoading = true
            loadError = ""
            try {
                val result = apiClient.bridgeListDir(path.ifBlank { "." })
                entries = result.sortedWith(compareByDescending<BridgeDirEntryDto> { it.isDirectory }.thenBy { it.name.lowercase() })
            } catch (e: Exception) {
                entries = emptyList()
                loadError = e.message ?: "Failed to list directory"
            }
            isLoading = false
        }
    }

    fun openTextFile(path: String) {
        scope.launch(Dispatchers.IO) {
            fileLoading = true
            fileError = ""
            fileContent = null
            try {
                fileContent = apiClient.bridgeReadFile(path)
                viewingFile = FileViewState(path, path.substringAfterLast("/"))
            } catch (e: Exception) {
                fileError = e.message ?: "Failed to read file"
                fileContent = null
                viewingFile = FileViewState(path, path.substringAfterLast("/"))
            }
            fileLoading = false
        }
    }

    fun openBinaryFile(path: String, filename: String, size: Long, modified: Long) {
        scope.launch(Dispatchers.IO) {
            val cached = viewModel.checkCache(path)
            val cacheValid = cached != null
                    && File(cached.localPath).exists()
                    && cached.fileSize == size
                    && cached.modifiedTime == modified
            if (cacheValid) {
                val localFile = File(cached!!.localPath)
                viewModel.openWithSystemViewer(localFile, filename)
                return@launch
            }
            if (size > LARGE_FILE_THRESHOLD) {
                largeFileConfirm = LargeFileConfirm(path, filename, size, modified)
                return@launch
            }
            viewModel.downloadAndOpen(path, filename, size, modified) { file ->
                viewModel.openWithSystemViewer(file, filename)
            }
        }
    }

    fun onFileClick(path: String) {
        val ext = path.substringAfterLast(".").lowercase()
        if (ext in BINARY_EXTENSIONS) {
            val filename = path.substringAfterLast("/")
            scope.launch(Dispatchers.IO) {
                try {
                    val stat = apiClient.bridgeStat(path)
                    openBinaryFile(path, filename, stat.size, stat.modified)
                } catch (e: Exception) {
                    Toast.makeText(context, "Cannot stat file: ${e.message}", Toast.LENGTH_SHORT).show()
                }
            }
        } else {
            openTextFile(path)
        }
    }

    LaunchedEffect(currentPath) {
        viewingFile = null
        searchQuery = ""
        searchResults = emptyList()
        loadDir(currentPath)
    }

    LaunchedEffect(searchQuery) {
        if (searchQuery.length >= 2) {
            scope.launch(Dispatchers.IO) {
                try {
                    searchResults = apiClient.bridgeSearch(
                        currentPath.ifBlank { "." },
                        searchQuery,
                        "filename",
                        50,
                    )
                } catch (_: Exception) {
                    searchResults = emptyList()
                }
            }
        } else {
            searchResults = emptyList()
        }
    }

    val canGoUp = currentPath.isNotBlank() && currentPath != "/" && currentPath.count { it == '/' } > 0

    largeFileConfirm?.let { confirm ->
        AlertDialog(
            onDismissRequest = { largeFileConfirm = null },
            title = { Text(stringResource(R.string.large_file)) },
            text = { Text(stringResource(R.string.large_file_confirm, formatSize(confirm.size))) },
            confirmButton = {
                TextButton(onClick = {
                    largeFileConfirm = null
                    viewModel.downloadAndOpen(confirm.path, confirm.filename, confirm.size, confirm.modified) { file ->
                        viewModel.openWithSystemViewer(file, confirm.filename)
                    }
                }) { Text(stringResource(R.string.download)) }
            },
            dismissButton = {
                TextButton(onClick = { largeFileConfirm = null }) { Text(stringResource(R.string.cancel)) }
            },
        )
    }

    val vf = viewingFile
    if (vf != null) {
        FileViewer(
            state = vf,
            fileContent = fileContent,
            isLoading = fileLoading,
            error = fileError,
            onBack = { viewingFile = null },
        )
        return
    }

    if (downloadState.downloading) {
        DownloadOverlay(state = downloadState)
        return
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(
                            text = stringResource(R.string.files),
                            style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            text = currentPath.ifBlank { "/" },
                            style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                },
                navigationIcon = {
                    IconButton(onClick = {
                        if (canGoUp) {
                            currentPath = currentPath.substringBeforeLast("/")
                        } else {
                            onBack()
                        }
                    }) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.content_desc_back),
                            tint = MaterialTheme.colorScheme.primary,
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = TopBarBackground,
                    titleContentColor = MaterialTheme.colorScheme.onBackground,
                ),
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
        ) {
            OutlinedTextField(
                value = searchQuery,
                onValueChange = { searchQuery = it },
                label = { Text(stringResource(R.string.search_files)) },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                singleLine = true,
            )

            if (canGoUp && searchQuery.isBlank()) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable {
                            currentPath = currentPath.substringBeforeLast("/")
                        }
                        .padding(horizontal = 16.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = "📁 ..",
                        style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Medium),
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
                HorizontalDivider()
            }

            if (isLoading) {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center,
                ) {
                    CircularProgressIndicator()
                }
            } else if (loadError.isNotBlank()) {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = loadError,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            } else if (searchQuery.length >= 2) {
                if (searchResults.isNotEmpty()) {
                    LazyColumn {
                        items(searchResults) { result ->
                            val name = result.path.substringAfterLast("/").substringAfterLast("\\")
                            BrowserFileRow(
                                name = name,
                                path = result.path,
                                isDir = false,
                                onClick = { onFileClick(result.path) },
                            )
                        }
                    }
                } else {
                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            text = stringResource(R.string.no_files_found),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            } else {
                if (entries.isEmpty()) {
                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            text = stringResource(R.string.empty_directory),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                } else {
                    LazyColumn {
                        items(entries, key = { "${it.name}_${it.isDirectory}" }) { entry ->
                            BrowserFileRow(
                                name = entry.name,
                                path = if (currentPath.isBlank()) entry.name else "${currentPath}/${entry.name}",
                                isDir = entry.isDirectory,
                                size = entry.size,
                                onClick = {
                                    if (entry.isDirectory) {
                                        currentPath = if (currentPath.isBlank()) entry.name else "${currentPath}/${entry.name}"
                                    } else {
                                        val fullPath = if (currentPath.isBlank()) entry.name else "${currentPath}/${entry.name}"
                                        onFileClick(fullPath)
                                    }
                                },
                            )
                        }
                    }
                }
            }
        }
    }
}

private data class FileViewState(val path: String, val name: String)
private data class LargeFileConfirm(val path: String, val filename: String, val size: Long, val modified: Long)

@Composable
private fun DownloadOverlay(state: DownloadState) {
    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            CircularProgressIndicator()
            Text(
                text = stringResource(R.string.downloading) + " " + formatSize(state.downloadedBytes) + if (state.totalBytes > 0) "/ ${formatSize(state.totalBytes)}" else "",
                style = MaterialTheme.typography.bodyMedium,
            )
            if (state.totalBytes > 0) {
                LinearProgressIndicator(
                    progress = { state.progress },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 48.dp),
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun FileViewer(
    state: FileViewState,
    fileContent: BridgeFileContent?,
    isLoading: Boolean,
    error: String,
    onBack: () -> Unit,
) {
    val ext = state.path.substringAfterLast(".").lowercase()
    val isMarkdown = ext in setOf("md", "markdown", "mdx")
    val content = fileContent?.content ?: ""

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(
                            text = state.name,
                            style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            text = state.path,
                            style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.content_desc_back),
                            tint = MaterialTheme.colorScheme.primary,
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = TopBarBackground,
                    titleContentColor = MaterialTheme.colorScheme.onBackground,
                ),
            )
        },
    ) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 16.dp, vertical = 8.dp),
        ) {
            when {
                isLoading -> {
                    CircularProgressIndicator(
                        modifier = Modifier.align(Alignment.Center),
                    )
                }
                error.isNotBlank() -> {
                    Column(
                        modifier = Modifier.align(Alignment.Center),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Text(
                            text = error,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                }
                isMarkdown && content.length <= 500_000 -> {
                    LazyColumn {
                        item {
                            MarkdownText(
                                markdown = content.ifEmpty { stringResource(R.string.empty_file) },
                                style = MaterialTheme.typography.bodySmall.copy(
                                    color = MaterialTheme.colorScheme.onBackground,
                                ),
                                syntaxHighlightColor = CodeBlockBackground,
                                syntaxHighlightTextColor = CodeBlockText,
                            )
                        }
                    }
                }
                content.length > 500_000 -> {
                    LazyColumn {
                        item {
                            Text(
                                text = stringResource(R.string.file_to_large, content.length),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(bottom = 8.dp),
                            )
                        }
                        item {
                            Text(
                                text = content.take(500_000),
                                style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                                color = MaterialTheme.colorScheme.onSurface,
                            )
                        }
                    }
                }
                else -> {
                    LazyColumn {
                        item {
                            Text(
                                text = content.ifEmpty { stringResource(R.string.empty_file) },
                                style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                                color = MaterialTheme.colorScheme.onSurface,
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun BrowserFileRow(
    name: String,
    path: String,
    isDir: Boolean,
    size: Long = 0,
    onClick: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 10.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.weight(1f),
            ) {
                Text(
                    text = if (isDir) "📁 " else "📄 ",
                    style = MaterialTheme.typography.bodyMedium,
                )
                Text(
                    text = name + if (isDir) "/" else "",
                    style = MaterialTheme.typography.bodyMedium.copy(
                        fontWeight = if (isDir) FontWeight.Medium else FontWeight.Normal,
                    ),
                    color = if (isDir) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (!isDir && size > 0) {
                Text(
                    text = formatSize(size),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Text(
            text = path,
            style = MaterialTheme.typography.labelSmall.copy(fontFamily = FontFamily.Monospace),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
    HorizontalDivider()
}

private val BINARY_EXTENSIONS = setOf(
    "png", "jpg", "jpeg", "gif", "webp", "bmp", "ico", "svg",
    "pdf", "zip", "gz", "tar", "rar", "7z",
    "mp3", "wav", "ogg", "flac", "aac",
    "mp4", "avi", "mkv", "mov", "webm",
    "doc", "docx", "xls", "xlsx", "ppt", "pptx",
    "exe", "dll", "so", "dylib",
    "apk", "ipa", "dmg",
)

private fun formatSize(bytes: Long): String {
    return when {
        bytes < 1024 -> "$bytes B"
        bytes < 1024 * 1024 -> "${bytes / 1024} KB"
        bytes < 1024 * 1024 * 1024 -> "${bytes / (1024 * 1024)} MB"
        else -> "${bytes / (1024 * 1024 * 1024)} GB"
    }
}

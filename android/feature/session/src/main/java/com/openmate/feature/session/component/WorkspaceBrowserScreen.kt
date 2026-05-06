package com.openmate.feature.session.component

import android.widget.Toast
import androidx.compose.foundation.background
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
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.TabRowDefaults
import androidx.compose.material3.TabRowDefaults.tabIndicatorOffset
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
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
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.graphics.Color
import androidx.hilt.navigation.compose.hiltViewModel
import com.openmate.core.network.dto.BridgeDirEntryDto
import com.openmate.core.network.dto.BridgeFileContent
import com.openmate.core.network.dto.BridgeSearchResultDto
import com.openmate.core.ui.theme.TopBarBackground
import com.openmate.feature.session.R
import com.openmate.feature.session.DownloadState
import com.openmate.feature.session.WorkspaceBrowserViewModel
import dev.jeziellago.compose.markdowntext.MarkdownText
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private val CodeBlockBackground = Color(0xFF2a2a3a)
private val CodeBlockText = Color(0xFFe0e0f0)

private const val LARGE_FILE_THRESHOLD = 10 * 1024 * 1024L

private enum class SortColumn { NAME, SIZE, MODIFIED }
private enum class SortOrder { ASC, DESC }

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
    var selectedTab by remember { mutableStateOf(0) }
    var filenameQuery by remember { mutableStateOf("") }
    var filenameResults by remember { mutableStateOf<List<BridgeSearchResultDto>>(emptyList()) }
    var contentQuery by remember { mutableStateOf("") }
    var contentResults by remember { mutableStateOf<List<BridgeSearchResultDto>>(emptyList()) }
    var contentSearching by remember { mutableStateOf(false) }
    var contentGlob by remember { mutableStateOf("") }
    var searchJob by remember { mutableStateOf<Job?>(null) }
    var isLoading by remember { mutableStateOf(false) }
    var loadError by remember { mutableStateOf("") }
    var viewingFile by remember { mutableStateOf<FileViewState?>(null) }
    var fileContent by remember { mutableStateOf<BridgeFileContent?>(null) }
    var fileLoading by remember { mutableStateOf(false) }
    var fileError by remember { mutableStateOf("") }
    var largeFileConfirm by remember { mutableStateOf<LargeFileConfirm?>(null) }
    val scope = rememberCoroutineScope()

    var sortColumn by remember { mutableStateOf(SortColumn.NAME) }
    var sortOrder by remember { mutableStateOf(SortOrder.ASC) }

    fun formatSize(size: Long): String {
        return when {
            size >= 1024 * 1024 * 1024 -> String.format("%.1f GB", size / (1024.0 * 1024 * 1024))
            size >= 1024 * 1024 -> String.format("%.1f MB", size / (1024.0 * 1024))
            size >= 1024 -> String.format("%.1f KB", size / 1024.0)
            else -> "$size B"
        }
    }

    fun formatTime(timestamp: Long): String {
        if (timestamp <= 0) return "-"
        val ts = if (timestamp > 1_000_000_000_000) timestamp / 1000 else timestamp
        val sdf = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault())
        return sdf.format(Date(ts * 1000))
    }

    fun sortEntries(list: List<BridgeDirEntryDto>): List<BridgeDirEntryDto> {
        val dirs = list.filter { it.isDirectory }
        val files = list.filter { !it.isDirectory }
        val comparator = when (sortColumn) {
            SortColumn.NAME -> compareBy<BridgeDirEntryDto> { it.name.lowercase() }
            SortColumn.SIZE -> compareBy { it.size }
            SortColumn.MODIFIED -> compareBy { it.modified }
        }
        val sortedDirs = if (sortOrder == SortOrder.ASC) dirs.sortedWith(comparator) else dirs.sortedWith(comparator.reversed())
        val sortedFiles = if (sortOrder == SortOrder.ASC) files.sortedWith(comparator) else files.sortedWith(comparator.reversed())
        return sortedDirs + sortedFiles
    }

    fun toggleSort(column: SortColumn) {
        if (sortColumn == column) {
            sortOrder = if (sortOrder == SortOrder.ASC) SortOrder.DESC else SortOrder.ASC
        } else {
            sortColumn = column
            sortOrder = SortOrder.ASC
        }
    }

    fun loadDir(path: String) {
        scope.launch(Dispatchers.IO) {
            isLoading = true
            loadError = ""
            try {
                val result = apiClient.bridgeListDir(path.ifBlank { "." })
                entries = result
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

    fun openFile(file: File, filename: String) {
        val ext = filename.substringAfterLast(".").lowercase()
        if (ext == "apk") {
            viewModel.installApk(file, filename)
        } else {
            viewModel.openWithSystemViewer(file, filename)
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
                openFile(localFile, filename)
                return@launch
            }
            if (size > LARGE_FILE_THRESHOLD) {
                largeFileConfirm = LargeFileConfirm(path, filename, size, modified)
                return@launch
            }
            viewModel.downloadAndOpen(path, filename, size, modified) { file ->
                openFile(file, filename)
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
        filenameQuery = ""
        filenameResults = emptyList()
        contentQuery = ""
        contentResults = emptyList()
        contentGlob = ""
        loadDir(currentPath)
    }

    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = androidx.lifecycle.LifecycleEventObserver { _, event ->
            if (event == androidx.lifecycle.Lifecycle.Event.ON_RESUME) {
                viewModel.retryPendingApkInstall()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
    }

    LaunchedEffect(filenameQuery) {
        if (selectedTab == 0 && filenameQuery.length >= 2) {
            scope.launch(Dispatchers.IO) {
                try {
                    filenameResults = apiClient.bridgeSearch(
                        currentPath.ifBlank { "." },
                        filenameQuery,
                        "filename",
                        50,
                    )
                } catch (_: Exception) {
                    filenameResults = emptyList()
                }
            }
        } else {
            filenameResults = emptyList()
        }
    }

    fun doContentSearch() {
        if (contentQuery.isBlank()) return
        searchJob?.cancel()
        searchJob = scope.launch(Dispatchers.IO) {
            contentSearching = true
            try {
                contentResults = apiClient.bridgeSearch(
                    currentPath.ifBlank { "." },
                    contentQuery,
                    "content",
                    100,
                    glob = contentGlob.ifBlank { null },
                )
            } catch (_: Exception) {
                contentResults = emptyList()
            }
            contentSearching = false
        }
    }

    fun cancelSearch() {
        searchJob?.cancel()
        searchJob = null
        contentSearching = false
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
                        openFile(file, confirm.filename)
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
            TabRow(
                selectedTabIndex = selectedTab,
                indicator = { tabPositions ->
                    TabRowDefaults.Indicator(
                        modifier = Modifier.tabIndicatorOffset(tabPositions[selectedTab]),
                        color = MaterialTheme.colorScheme.primary,
                    )
                },
                containerColor = Color.Transparent,
            ) {
                Tab(
                    selected = selectedTab == 0,
                    onClick = { selectedTab = 0 },
                    text = { Text(stringResource(R.string.tab_files)) },
                )
                Tab(
                    selected = selectedTab == 1,
                    onClick = { selectedTab = 1 },
                    text = { Text(stringResource(R.string.tab_content)) },
                )
            }

            if (selectedTab == 0) {
                OutlinedTextField(
                    value = filenameQuery,
                    onValueChange = { filenameQuery = it },
                    label = { Text(stringResource(R.string.search_files)) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                    singleLine = true,
                )
            } else {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        OutlinedTextField(
                            value = contentQuery,
                            onValueChange = { contentQuery = it },
                            label = { Text(stringResource(R.string.search_in_content)) },
                            modifier = Modifier.weight(1f),
                            singleLine = true,
                        )
                        if (contentSearching) {
                            IconButton(onClick = { cancelSearch() }) {
                                Icon(
                                    imageVector = Icons.Filled.Close,
                                    contentDescription = stringResource(R.string.cancel),
                                    tint = MaterialTheme.colorScheme.error,
                                )
                            }
                        } else {
                            Button(
                                onClick = { doContentSearch() },
                                enabled = contentQuery.isNotBlank(),
                            ) {
                                Text(stringResource(R.string.search_button))
                            }
                        }
                    }
                    OutlinedTextField(
                        value = contentGlob,
                        onValueChange = { contentGlob = it },
                        label = { Text(stringResource(R.string.filename_filter)) },
                        placeholder = { Text("*.kt, *.rs, *.md") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                    )
                }
            }

            if (canGoUp && selectedTab == 0 && filenameQuery.isBlank()) {
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
            } else if (selectedTab == 1) {
                if (contentSearching) {
                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center,
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            CircularProgressIndicator()
                            Spacer(modifier = Modifier.height(8.dp))
                            Text(
                                text = stringResource(R.string.searching),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                } else if (contentResults.isNotEmpty()) {
                    Text(
                        text = stringResource(R.string.content_search_results, contentResults.size),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                    )
                    LazyColumn {
                        items(contentResults) { result ->
                            ContentSearchResultRow(
                                result = result,
                                onClick = { onFileClick(result.path) },
                            )
                        }
                    }
                } else if (contentQuery.isNotBlank() && contentResults.isEmpty()) {
                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            text = stringResource(R.string.no_results_found),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            } else if (filenameQuery.length >= 2) {
                if (filenameResults.isNotEmpty()) {
                    LazyColumn {
                        items(filenameResults) { result ->
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
                    BrowserHeaderRow(
                        sortColumn = sortColumn,
                        sortOrder = sortOrder,
                        onSort = { toggleSort(it) },
                    )
                    HorizontalDivider()
                    val sortedEntries = sortEntries(entries)
                    LazyColumn {
                        items(sortedEntries, key = { "${it.name}_${it.isDirectory}" }) { entry ->
                            BrowserFileRow(
                                name = entry.name,
                                path = if (currentPath.isBlank()) entry.name else "${currentPath}/${entry.name}",
                                isDir = entry.isDirectory,
                                size = if (entry.isDirectory) "-" else formatSize(entry.size),
                                modified = formatTime(entry.modified),
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
private fun ContentSearchResultRow(
    result: BridgeSearchResultDto,
    onClick: () -> Unit,
) {
    val filename = result.path.substringAfterLast("/").substringAfterLast("\\")
    val relativePath = result.path

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 10.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = "📄 ",
                style = MaterialTheme.typography.bodyMedium,
            )
            Text(
                text = filename,
                style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Medium),
                color = MaterialTheme.colorScheme.primary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(modifier = Modifier.width(8.dp))
            if (result.line != null) {
                Text(
                    text = stringResource(R.string.line_info, result.line!!),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        if (!result.snippet.isNullOrBlank()) {
            Text(
                text = result.snippet!!,
                style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(start = 24.dp, top = 2.dp),
            )
        }
        Text(
            text = relativePath,
            style = MaterialTheme.typography.labelSmall.copy(fontFamily = FontFamily.Monospace),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(start = 24.dp, top = 2.dp),
        )
    }
    HorizontalDivider()
}

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
    var showSearch by remember { mutableStateOf(false) }
    var searchQuery by remember { mutableStateOf("") }
    var currentMatchIndex by remember { mutableStateOf(-1) }
    val listState = rememberLazyListState()

    val lines = remember(content) { content.lines() }
    val matchIndices = remember(content, searchQuery) {
        if (searchQuery.isBlank() || content.isBlank()) emptyList()
        else {
            val query = searchQuery.lowercase()
            lines.mapIndexedNotNull { index, line ->
                if (line.lowercase().contains(query)) index else null
            }
        }
    }

    LaunchedEffect(searchQuery) {
        currentMatchIndex = if (matchIndices.isEmpty()) -1 else 0
    }

    LaunchedEffect(currentMatchIndex) {
        if (currentMatchIndex in matchIndices.indices) {
            listState.animateScrollToItem(matchIndices[currentMatchIndex])
        }
    }

    Scaffold(
        topBar = {
            if (showSearch) {
                TopAppBar(
                    title = {
                        OutlinedTextField(
                            value = searchQuery,
                            onValueChange = { searchQuery = it },
                            placeholder = { Text("Search...") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                            textStyle = MaterialTheme.typography.bodyMedium,
                        )
                    },
                    navigationIcon = {
                        IconButton(onClick = {
                            showSearch = false
                            searchQuery = ""
                        }) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                                contentDescription = "Close search",
                                tint = MaterialTheme.colorScheme.primary,
                            )
                        }
                    },
                    actions = {
                        if (matchIndices.isNotEmpty()) {
                            Text(
                                text = "${currentMatchIndex + 1}/${matchIndices.size}",
                                style = MaterialTheme.typography.labelMedium,
                                modifier = Modifier.padding(end = 4.dp),
                            )
                            IconButton(onClick = {
                                currentMatchIndex = if (currentMatchIndex > 0) currentMatchIndex - 1 else matchIndices.lastIndex
                            }) {
                                Icon(Icons.Filled.KeyboardArrowUp, "Previous")
                            }
                            IconButton(onClick = {
                                currentMatchIndex = if (currentMatchIndex < matchIndices.lastIndex) currentMatchIndex + 1 else 0
                            }) {
                                Icon(Icons.Filled.KeyboardArrowDown, "Next")
                            }
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = TopBarBackground,
                        titleContentColor = MaterialTheme.colorScheme.onBackground,
                    ),
                )
            } else {
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
                    actions = {
                        if (content.isNotBlank()) {
                            IconButton(onClick = { showSearch = true }) {
                                Icon(
                                    imageVector = Icons.Filled.Search,
                                    contentDescription = "Search",
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = TopBarBackground,
                        titleContentColor = MaterialTheme.colorScheme.onBackground,
                    ),
                )
            }
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
                searchQuery.isNotBlank() -> {
                    val highlightedLines = remember(lines, searchQuery) {
                        val query = searchQuery.lowercase()
                        lines.mapIndexed { index, line ->
                            val isMatch = line.lowercase().contains(query)
                            Triple(index, line, isMatch)
                        }
                    }
                    LazyColumn(state = listState) {
                        items(count = highlightedLines.size, key = { it }) { idx ->
                            val (_, line, isMatch) = highlightedLines[idx]
                            val isCurrentMatch = matchIndices.getOrNull(currentMatchIndex) == idx
                            val bgColor = when {
                                isCurrentMatch -> Color(0xFF5a3e1e)
                                isMatch -> Color(0xFF3a3a2e)
                                else -> Color.Transparent
                            }
                            val lineNum = idx + 1
                            Row(modifier = Modifier.fillMaxWidth()) {
                                Text(
                                    text = "$lineNum",
                                    style = MaterialTheme.typography.labelSmall.copy(fontFamily = FontFamily.Monospace),
                                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                                    modifier = Modifier.width(40.dp),
                                )
                                SelectionContainer {
                                    Text(
                                        text = line,
                                        style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                                        color = MaterialTheme.colorScheme.onSurface,
                                        modifier = Modifier
                                            .weight(1f)
                                            .background(bgColor, RoundedCornerShape(2.dp))
                                            .padding(horizontal = 4.dp, vertical = 1.dp),
                                    )
                                }
                            }
                        }
                    }
                }
                isMarkdown && content.length <= 500_000 -> {
                    LazyColumn(state = listState) {
                        item {
                            MarkdownText(
                                markdown = content.ifEmpty { stringResource(R.string.empty_file) },
                                style = MaterialTheme.typography.bodySmall.copy(
                                    color = MaterialTheme.colorScheme.onBackground,
                                ),
                                syntaxHighlightColor = CodeBlockBackground,
                                syntaxHighlightTextColor = CodeBlockText,
                                isTextSelectable = true,
                            )
                        }
                    }
                }
                content.length > 500_000 -> {
                    SelectionContainer {
                        LazyColumn(state = listState) {
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
                }
                else -> {
                    SelectionContainer {
                        LazyColumn(state = listState) {
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
}

@Composable
private fun BrowserHeaderRow(
    sortColumn: SortColumn,
    sortOrder: SortOrder,
    onSort: (SortColumn) -> Unit,
) {
    val nameIcon = when {
        sortColumn == SortColumn.NAME && sortOrder == SortOrder.ASC -> "▲"
        sortColumn == SortColumn.NAME && sortOrder == SortOrder.DESC -> "▼"
        else -> ""
    }
    val sizeIcon = when {
        sortColumn == SortColumn.SIZE && sortOrder == SortOrder.ASC -> "▲"
        sortColumn == SortColumn.SIZE && sortOrder == SortOrder.DESC -> "▼"
        else -> ""
    }
    val timeIcon = when {
        sortColumn == SortColumn.MODIFIED && sortOrder == SortOrder.ASC -> "▲"
        sortColumn == SortColumn.MODIFIED && sortOrder == SortOrder.DESC -> "▼"
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
                .clickable { onSort(SortColumn.NAME) },
        )
        Text(
            text = stringResource(R.string.file_browser_size) + " $sizeIcon",
            style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold),
            color = MaterialTheme.colorScheme.primary,
            textAlign = TextAlign.End,
            modifier = Modifier
                .width(80.dp)
                .clickable { onSort(SortColumn.SIZE) },
        )
        Text(
            text = stringResource(R.string.file_browser_modified) + " $timeIcon",
            style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold),
            color = MaterialTheme.colorScheme.primary,
            textAlign = TextAlign.End,
            modifier = Modifier
                .width(120.dp)
                .padding(start = 8.dp)
                .clickable { onSort(SortColumn.MODIFIED) },
        )
    }
}

@Composable
private fun BrowserFileRow(
    name: String,
    path: String,
    isDir: Boolean,
    size: String = "",
    modified: String = "",
    onClick: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 10.dp),
    ) {
        Text(
            text = if (isDir) "📁 $name" else "📄 $name",
            style = MaterialTheme.typography.bodyMedium.copy(
                fontWeight = if (isDir) FontWeight.Medium else FontWeight.Normal,
            ),
            color = if (isDir) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
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
                text = size,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.End,
                modifier = Modifier.width(80.dp),
            )
            Text(
                text = modified,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.End,
                modifier = Modifier.width(120.dp).padding(start = 8.dp),
            )
        }
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

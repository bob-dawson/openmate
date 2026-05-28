package com.openmate.app.fileviewer

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.openmate.app.R
import com.openmate.core.network.OpencodeApiClient
import com.openmate.core.ui.theme.TopBarBackground
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.jeziellago.compose.markdowntext.MarkdownText
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import javax.inject.Inject

private val CodeBlockBackground = Color(0xFF2a2a3a)
private val CodeBlockText = Color(0xFFe0e0f0)

data class FileViewerState(
    val loading: Boolean = true,
    val content: String = "",
    val error: String? = null,
)

@HiltViewModel
class FileViewerViewModel @Inject constructor(
    private val apiClient: OpencodeApiClient,
) : ViewModel() {
    val state = mutableStateOf(FileViewerState())

    fun loadFile(filePath: String) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val fullPath = resolvePath(filePath)
                val result = apiClient.bridgeReadFile(fullPath)
                state.value = FileViewerState(loading = false, content = result.content)
            } catch (e: Exception) {
                state.value = FileViewerState(loading = false, error = e.message ?: "Failed to load file")
            }
        }
    }

    private fun resolvePath(path: String): String {
        return path.trim().removeSurrounding("`").removeSurrounding("\"").replace('\\', '/')
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FileViewerScreen(
    filePath: String,
    viewModel: FileViewerViewModel = androidx.hilt.navigation.compose.hiltViewModel(),
    onBack: () -> Unit,
) {
    val state by viewModel.state
    val fileName = filePath.substringAfterLast('/')

    LaunchedEffect(filePath) {
        viewModel.loadFile(filePath)
    }

    FileViewerContent(
        fileName = fileName,
        filePath = filePath,
        content = state.content,
        isLoading = state.loading,
        error = state.error,
        onBack = onBack,
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FileViewerContent(
    fileName: String,
    filePath: String,
    content: String,
    isLoading: Boolean,
    error: String?,
    onBack: () -> Unit,
) {
    val ext = fileName.substringAfterLast(".").lowercase()
    val isMarkdown = ext in setOf("md", "markdown", "mdx")
    var showSearch by remember { mutableStateOf(false) }
    var searchQuery by remember { mutableStateOf("") }
    var currentMatchIndex by remember { mutableIntStateOf(-1) }
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
                            placeholder = { Text(stringResource(R.string.search_hint)) },
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
                                contentDescription = stringResource(R.string.close_search),
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
                                Icon(Icons.Filled.KeyboardArrowUp, stringResource(R.string.previous))
                            }
                            IconButton(onClick = {
                                currentMatchIndex = if (currentMatchIndex < matchIndices.lastIndex) currentMatchIndex + 1 else 0
                            }) {
                                Icon(Icons.Filled.KeyboardArrowDown, stringResource(R.string.next_result))
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
                                text = fileName,
                                style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            Text(
                                text = filePath,
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
                                    contentDescription = stringResource(R.string.search_files),
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
                error != null -> {
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

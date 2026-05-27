package com.openmate.app.diff

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.openmate.core.ui.component.TopBar
import dagger.hilt.android.lifecycle.HiltViewModel
import com.openmate.core.domain.model.DiffFile
import com.openmate.core.domain.model.DiffLine
import com.openmate.core.domain.model.DiffLineType
import com.openmate.core.domain.repository.SessionMessageRepository
import com.openmate.core.network.OpencodeApiClient
import javax.inject.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import com.openmate.app.R

@Composable
fun DiffViewerScreen(
    sessionId: String,
    messageId: String,
    toolName: String,
    targetFilePath: String?,
    directory: String,
    onBack: () -> Unit,
) {
    val viewModel: DiffViewerViewModel = hiltViewModel()
    val uiState by viewModel.state

    LaunchedEffect(Unit) {
        viewModel.loadDiff(sessionId, messageId, toolName, targetFilePath, directory)
    }

    val menuExpanded = remember { mutableStateOf(false) }
    val context = LocalContext.current

    Column(modifier = Modifier.fillMaxSize()) {
        TopBar(
            title = targetFilePath ?: stringResource(R.string.diff_viewer),
            onBack = onBack,
            actions = {
                if (uiState.files != null || uiState.viewingFile) {
                    Box {
                        IconButton(onClick = { menuExpanded.value = true }) {
                            Icon(
                                imageVector = Icons.Filled.MoreVert,
                                contentDescription = stringResource(R.string.content_desc_more),
                                tint = MaterialTheme.colorScheme.onBackground,
                            )
                        }
                        DropdownMenu(
                            expanded = menuExpanded.value,
                            onDismissRequest = { menuExpanded.value = false },
                        ) {
                            if (uiState.viewingFile) {
                                DropdownMenuItem(
                                    text = { Text(stringResource(R.string.view_diff)) },
                                    onClick = {
                                        menuExpanded.value = false
                                        viewModel.closeFileView()
                                    },
                                )
                            } else if (uiState.files != null && targetFilePath != null) {
                                DropdownMenuItem(
                                    text = { Text(stringResource(R.string.view_file)) },
                                    onClick = {
                                        menuExpanded.value = false
                                        viewModel.openFileView(targetFilePath)
                                    },
                                )
                            }
                        }
                    }
                }
            },
        )

        when {
            uiState.viewingFile -> FileContentView(uiState)
            uiState.loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
            uiState.error != null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(uiState.error ?: "Unknown error", color = MaterialTheme.colorScheme.error)
            }
            uiState.isEmpty -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(stringResource(R.string.no_diff_data), color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            uiState.files != null -> UnifiedDiffView(uiState.files!!)
        }
    }
}

@Composable
private fun FileContentView(uiState: DiffViewState) {
    when {
        uiState.fileLoading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            CircularProgressIndicator()
        }
        uiState.fileError != null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text(uiState.fileError ?: "Unknown error", color = MaterialTheme.colorScheme.error)
        }
        uiState.fileContent != null -> {
            val listState = rememberLazyListState()
            val lines = uiState.fileContent.lines()
            val lineNumberWidth = lines.size.toString().length.coerceAtLeast(3)
            val monoSize = 13.sp
            val lineH = 16.sp
            val lineNumberColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f)

            LazyColumn(state = listState, modifier = Modifier.fillMaxSize()) {
                items(items = lines, key = { idx -> idx }) { line ->
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(start = 4.dp, end = 8.dp),
                    ) {
                        Text(
                            text = (lines.indexOf(line) + 1).toString(),
                            fontSize = monoSize,
                            fontFamily = FontFamily.Monospace,
                            color = lineNumberColor,
                            modifier = Modifier.width((lineNumberWidth * 8 + 8).dp),
                            lineHeight = lineH,
                        )
                        Text(
                            text = line,
                            fontSize = monoSize,
                            fontFamily = FontFamily.Monospace,
                            color = MaterialTheme.colorScheme.onSurface,
                            softWrap = true,
                            lineHeight = lineH,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun UnifiedDiffView(files: List<DiffFile>) {
    val removeBg = MaterialTheme.colorScheme.error.copy(alpha = 0.12f)
    val addBg = Color(0xFF2E7D32).copy(alpha = 0.12f)
    val lineNumberColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f)
    val monoSize = 13.sp
    val lineH = 16.sp

    val listState = rememberLazyListState()

    LazyColumn(state = listState, modifier = Modifier.fillMaxSize()) {
        files.forEachIndexed { fileIdx, file ->
            if (fileIdx > 0) {
                item(key = "divider-$fileIdx") {
                    HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
                }
            }
            if (files.size > 1) {
                item(key = "header-$fileIdx") {
                    Text(
                        text = file.filePath,
                        style = MaterialTheme.typography.labelMedium.copy(fontFamily = FontFamily.Monospace),
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                    )
                }
            }
            file.hunks.forEachIndexed { hunkIdx, hunk ->
                if (hunkIdx > 0) {
                    item(key = "hunk-sep-$fileIdx-$hunkIdx") {
                        HorizontalDivider(
                            modifier = Modifier.padding(vertical = 2.dp),
                            thickness = 0.5.dp,
                            color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f),
                        )
                    }
                }
                items(
                    items = hunk.lines,
                    key = { line -> "f$fileIdx-h$hunkIdx-${line.oldLineNumber}-${line.newLineNumber}-${line.content.hashCode()}" },
                ) { line ->
                    UnifiedDiffLine(
                        line = line,
                        removeBg = removeBg,
                        addBg = addBg,
                        lineNumberColor = lineNumberColor,
                        monoSize = monoSize,
                        lineH = lineH,
                    )
                }
            }
        }
    }
}

@Composable
private fun UnifiedDiffLine(
    line: DiffLine,
    removeBg: Color,
    addBg: Color,
    lineNumberColor: Color,
    monoSize: androidx.compose.ui.unit.TextUnit,
    lineH: androidx.compose.ui.unit.TextUnit,
) {
    val bgColor = when (line.type) {
        DiffLineType.REMOVE -> removeBg
        DiffLineType.ADD -> addBg
        DiffLineType.CONTEXT -> Color.Transparent
    }

    val sign = when (line.type) {
        DiffLineType.REMOVE -> "-"
        DiffLineType.ADD -> "+"
        DiffLineType.CONTEXT -> " "
    }

    val signColor = when (line.type) {
        DiffLineType.REMOVE -> MaterialTheme.colorScheme.error
        DiffLineType.ADD -> Color(0xFF2E7D32)
        DiffLineType.CONTEXT -> lineNumberColor
    }

    val textColor = when (line.type) {
        DiffLineType.REMOVE -> MaterialTheme.colorScheme.error.copy(alpha = 0.9f)
        DiffLineType.ADD -> Color(0xFF4CAF50)
        DiffLineType.CONTEXT -> MaterialTheme.colorScheme.onSurface
    }

    Row(
        modifier = Modifier.fillMaxWidth().background(bgColor).padding(start = 4.dp, end = 8.dp),
    ) {
        Text(
            text = line.oldLineNumber?.toString() ?: "",
            fontSize = monoSize,
            fontFamily = FontFamily.Monospace,
            color = lineNumberColor,
            modifier = Modifier.width(32.dp),
            lineHeight = lineH,
        )
        Text(
            text = line.newLineNumber?.toString() ?: "",
            fontSize = monoSize,
            fontFamily = FontFamily.Monospace,
            color = lineNumberColor,
            modifier = Modifier.width(32.dp),
            lineHeight = lineH,
        )
        Text(
            text = sign,
            fontSize = monoSize,
            fontFamily = FontFamily.Monospace,
            color = signColor,
            modifier = Modifier.width(8.dp),
            lineHeight = lineH,
        )
        Text(
            text = line.content,
            fontSize = monoSize,
            fontFamily = FontFamily.Monospace,
            color = textColor,
            softWrap = true,
            lineHeight = lineH,
        )
    }
}

data class DiffViewState(
    val loading: Boolean = true,
    val files: List<DiffFile>? = null,
    val error: String? = null,
    val isEmpty: Boolean = false,
    val viewingFile: Boolean = false,
    val fileLoading: Boolean = false,
    val fileContent: String? = null,
    val fileError: String? = null,
)

@HiltViewModel
class DiffViewerViewModel @Inject constructor(
    private val sessionMessageRepository: SessionMessageRepository,
    private val apiClient: OpencodeApiClient,
) : ViewModel() {
    val state = mutableStateOf(DiffViewState())
    private var currentDirectory: String = ""

    fun loadDiff(sessionId: String, messageId: String, toolName: String, targetFilePath: String?, directory: String) {
        currentDirectory = directory
        viewModelScope.launch {
            try {
                val files = sessionMessageRepository.fetchDiffFiles(sessionId, messageId, toolName, targetFilePath)
                if (files.isEmpty()) {
                    state.value = DiffViewState(loading = false, isEmpty = true)
                } else {
                    state.value = DiffViewState(loading = false, files = files)
                }
            } catch (e: Exception) {
                state.value = DiffViewState(loading = false, error = e.message ?: "Failed to load diff")
            }
        }
    }

    fun openFileView(filePath: String) {
        val resolved = resolvePath(filePath)
        state.value = state.value.copy(viewingFile = true, fileLoading = true, fileContent = null, fileError = null)
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val content = apiClient.bridgeReadFile(resolved)
                state.value = state.value.copy(fileLoading = false, fileContent = content.content)
            } catch (e: Exception) {
                state.value = state.value.copy(fileLoading = false, fileError = e.message ?: "Failed to load file")
            }
        }
    }

    fun closeFileView() {
        state.value = state.value.copy(viewingFile = false, fileLoading = false, fileContent = null, fileError = null)
    }

    private fun resolvePath(path: String): String {
        val normalized = path.trim().removeSurrounding("`").removeSurrounding("\"").replace('\\', '/')
        if (normalized.isBlank()) return normalized
        val isWindowsAbsolute = normalized.length >= 3 &&
            normalized[1] == ':' &&
            normalized[0].isLetter() &&
            normalized[2] == '/'
        if (normalized.startsWith("/") || isWindowsAbsolute) return normalized
        if (currentDirectory.isBlank()) return normalized
        return "${currentDirectory.replace('\\', '/')}/$normalized"
    }
}

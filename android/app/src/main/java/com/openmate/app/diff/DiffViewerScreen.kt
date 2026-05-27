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
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
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
import javax.inject.Inject
import kotlinx.coroutines.launch
import com.openmate.app.R

@Composable
fun DiffViewerScreen(
    sessionId: String,
    messageId: String,
    toolName: String,
    targetFilePath: String?,
    onBack: () -> Unit,
) {
    val viewModel: DiffViewerViewModel = hiltViewModel()
    val uiState by viewModel.state

    LaunchedEffect(Unit) {
        viewModel.loadDiff(sessionId, messageId, toolName, targetFilePath)
    }

    Column(modifier = Modifier.fillMaxSize()) {
        TopBar(
            title = targetFilePath ?: stringResource(R.string.diff_viewer),
            onBack = onBack,
        )

        when {
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
)

@HiltViewModel
class DiffViewerViewModel @Inject constructor(
    private val sessionMessageRepository: SessionMessageRepository,
) : ViewModel() {
    val state = mutableStateOf(DiffViewState())

    fun loadDiff(sessionId: String, messageId: String, toolName: String, targetFilePath: String?) {
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
}
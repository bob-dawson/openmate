package com.openmate.feature.session.component

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.openmate.feature.session.R
import com.openmate.core.network.OpencodeApiClient
import com.openmate.core.network.dto.BridgeDirEntryDto
import com.openmate.core.network.dto.BridgeSearchResultDto
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private enum class FileSortColumn { NAME, SIZE, MODIFIED }
private enum class FileSortOrder { ASC, DESC }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FilePickerSheet(
    apiClient: OpencodeApiClient,
    initialDirectory: String = "",
    onSelect: (path: String, filename: String) -> Unit,
    onDismiss: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var currentPath by remember { mutableStateOf(initialDirectory) }
    var entries by remember { mutableStateOf<List<BridgeDirEntryDto>>(emptyList()) }
    var searchQuery by remember { mutableStateOf("") }
    var searchResults by remember { mutableStateOf<List<BridgeSearchResultDto>>(emptyList()) }
    var isLoading by remember { mutableStateOf(false) }
    var loadError by remember { mutableStateOf("") }
    val scope = rememberCoroutineScope()

    var sortColumn by remember { mutableStateOf(FileSortColumn.NAME) }
    var sortOrder by remember { mutableStateOf(FileSortOrder.ASC) }

    fun canNavigateUp(): Boolean {
        if (currentPath.isBlank()) return false
        val normalizedCurrent = currentPath.replace("\\", "/")
        val normalizedInitial = initialDirectory.replace("\\", "/")
        if (normalizedCurrent == normalizedInitial) return false
        return normalizedCurrent.count { it == '/' } > 0
    }

    fun navigateUp(): String {
        return currentPath.replace("\\", "/").substringBeforeLast("/", "")
    }

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
            FileSortColumn.NAME -> compareBy<BridgeDirEntryDto> { it.name.lowercase() }
            FileSortColumn.SIZE -> compareBy { it.size }
            FileSortColumn.MODIFIED -> compareBy { it.modified }
        }
        val sortedDirs = if (sortOrder == FileSortOrder.ASC) dirs.sortedWith(comparator) else dirs.sortedWith(comparator.reversed())
        val sortedFiles = if (sortOrder == FileSortOrder.ASC) files.sortedWith(comparator) else files.sortedWith(comparator.reversed())
        return sortedDirs + sortedFiles
    }

    fun toggleSort(column: FileSortColumn) {
        if (sortColumn == column) {
            sortOrder = if (sortOrder == FileSortOrder.ASC) FileSortOrder.DESC else FileSortOrder.ASC
        } else {
            sortColumn = column
            sortOrder = FileSortOrder.ASC
        }
    }

    fun loadDir(path: String) {
        scope.launch(Dispatchers.IO) {
            isLoading = true
            loadError = ""
            try {
                entries = apiClient.bridgeListDir(path.ifBlank { "." })
            } catch (e: Exception) {
                entries = emptyList()
                loadError = e.message ?: "Failed to list directory"
            }
            isLoading = false
        }
    }

    LaunchedEffect(currentPath) {
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
                        30,
                    )
                } catch (_: Exception) {
                    searchResults = emptyList()
                }
            }
        } else {
            searchResults = emptyList()
        }
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp)
                .height(520.dp),
        ) {
            Text(
                text = stringResource(R.string.attach_file),
                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
                modifier = Modifier.padding(bottom = 8.dp),
            )

            Text(
                text = currentPath.ifBlank { "/" },
                style = MaterialTheme.typography.labelSmall.copy(fontFamily = FontFamily.Monospace),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(bottom = 8.dp),
            )

            OutlinedTextField(
                value = searchQuery,
                onValueChange = { searchQuery = it },
                label = { Text(stringResource(R.string.search_files)) },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 4.dp),
                singleLine = true,
            )

            Row(
                modifier = Modifier.fillMaxWidth().padding(bottom = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "..",
                    style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Medium),
                    color = if (canNavigateUp()) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = if (canNavigateUp()) Modifier.clickable { currentPath = navigateUp() } else Modifier,
                )
            }
            HorizontalDivider()

            if (isLoading) {
                CircularProgressIndicator(
                    modifier = Modifier.padding(16.dp).size(24.dp).align(Alignment.CenterHorizontally),
                )
            } else if (loadError.isNotBlank()) {
                Text(
                    text = loadError,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(16.dp),
                )
            } else if (searchQuery.length >= 2) {
                if (searchResults.isNotEmpty()) {
                    LazyColumn(contentPadding = PaddingValues(vertical = 4.dp)) {
                        items(searchResults) { result ->
                            val name = result.path.substringAfterLast("/").substringAfterLast("\\")
                            FileRow(
                                name = name,
                                path = result.path,
                                size = "",
                                modified = "",
                                isDir = false,
                                onClick = { onSelect(result.path, name) },
                            )
                        }
                    }
                } else {
                    Text(
                        text = stringResource(R.string.no_files_found),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(16.dp),
                    )
                }
            } else {
                HeaderRow(
                    sortColumn = sortColumn,
                    sortOrder = sortOrder,
                    onSort = { toggleSort(it) },
                )
                HorizontalDivider()
                val sortedEntries = sortEntries(entries)
                LazyColumn(contentPadding = PaddingValues(vertical = 4.dp)) {
                    items(sortedEntries, key = { "${it.name}_${it.isDirectory}" }) { entry ->
                        FileRow(
                            name = entry.name,
                            path = if (currentPath.isBlank()) entry.name else "${currentPath}/${entry.name}",
                            size = if (entry.isDirectory) "-" else formatSize(entry.size),
                            modified = formatTime(entry.modified),
                            isDir = entry.isDirectory,
                            onClick = {
                                if (entry.isDirectory) {
                                    currentPath = if (currentPath.isBlank()) entry.name else "${currentPath}/${entry.name}"
                                } else {
                                    val fullPath = if (currentPath.isBlank()) entry.name else "${currentPath}/${entry.name}".replace("\\", "/")
                                    onSelect(fullPath, entry.name)
                                }
                            },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun HeaderRow(
    sortColumn: FileSortColumn,
    sortOrder: FileSortOrder,
    onSort: (FileSortColumn) -> Unit,
) {
    val nameIcon = when {
        sortColumn == FileSortColumn.NAME && sortOrder == FileSortOrder.ASC -> "▲"
        sortColumn == FileSortColumn.NAME && sortOrder == FileSortOrder.DESC -> "▼"
        else -> ""
    }
    val sizeIcon = when {
        sortColumn == FileSortColumn.SIZE && sortOrder == FileSortOrder.ASC -> "▲"
        sortColumn == FileSortColumn.SIZE && sortOrder == FileSortOrder.DESC -> "▼"
        else -> ""
    }
    val timeIcon = when {
        sortColumn == FileSortColumn.MODIFIED && sortOrder == FileSortOrder.ASC -> "▲"
        sortColumn == FileSortColumn.MODIFIED && sortOrder == FileSortOrder.DESC -> "▼"
        else -> ""
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 4.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = stringResource(R.string.file_browser_name) + " $nameIcon",
            style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold),
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier
                .weight(1f)
                .clickable { onSort(FileSortColumn.NAME) },
        )
        Text(
            text = stringResource(R.string.file_browser_size) + " $sizeIcon",
            style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold),
            color = MaterialTheme.colorScheme.primary,
            textAlign = TextAlign.End,
            modifier = Modifier
                .width(80.dp)
                .clickable { onSort(FileSortColumn.SIZE) },
        )
        Text(
            text = stringResource(R.string.file_browser_modified) + " $timeIcon",
            style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold),
            color = MaterialTheme.colorScheme.primary,
            textAlign = TextAlign.End,
            modifier = Modifier
                .width(140.dp)
                .padding(start = 8.dp)
                .clickable { onSort(FileSortColumn.MODIFIED) },
        )
    }
}

@Composable
private fun FileRow(
    name: String,
    path: String,
    size: String,
    modified: String,
    isDir: Boolean,
    onClick: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 4.dp, vertical = 8.dp),
    ) {
        Text(
            text = if (isDir) "📁 $name" else "📄 $name",
            style = MaterialTheme.typography.bodySmall.copy(
                fontWeight = if (isDir) FontWeight.Medium else FontWeight.Normal,
            ),
            color = if (isDir) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.fillMaxWidth(),
        )
        Row(
            modifier = Modifier.fillMaxWidth().padding(top = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Spacer(modifier = Modifier.weight(1f))
            Text(
                text = size,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.End,
                modifier = Modifier.width(80.dp),
            )
            Text(
                text = modified,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.End,
                modifier = Modifier.width(140.dp).padding(start = 8.dp),
            )
        }
    }
}

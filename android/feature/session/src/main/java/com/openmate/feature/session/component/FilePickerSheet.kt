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
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.openmate.core.network.OpencodeApiClient
import com.openmate.core.network.dto.FileNodeDto
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FilePickerSheet(
    apiClient: OpencodeApiClient,
    onSelect: (path: String, filename: String) -> Unit,
    onDismiss: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var currentPath by remember { mutableStateOf("") }
    var files by remember { mutableStateOf<List<FileNodeDto>>(emptyList()) }
    var searchQuery by remember { mutableStateOf("") }
    var searchResults by remember { mutableStateOf<List<String>>(emptyList()) }
    var isLoading by remember { mutableStateOf(false) }
    var serverDir by remember { mutableStateOf("") }
    val scope = rememberCoroutineScope()

    LaunchedEffect(Unit) {
        scope.launch(Dispatchers.IO) {
            try {
                serverDir = apiClient.getPath().directory
            } catch (_: Exception) {}
        }
    }

    LaunchedEffect(Unit) {
        scope.launch(Dispatchers.IO) {
            isLoading = true
            try {
                val nodes = apiClient.listFiles(".")
                files = nodes.filter { !it.ignored }.sortedWith(
                    compareBy<FileNodeDto> { it.type != "directory" }.thenBy { it.name.lowercase() }
                )
            } catch (_: Exception) {
                files = emptyList()
            }
            isLoading = false
        }
    }

    LaunchedEffect(currentPath) {
        if (currentPath.isBlank()) return@LaunchedEffect
        scope.launch(Dispatchers.IO) {
            isLoading = true
            try {
                val nodes = apiClient.listFiles(currentPath)
                files = nodes.filter { !it.ignored }.sortedWith(
                    compareBy<FileNodeDto> { it.type != "directory" }.thenBy { it.name.lowercase() }
                )
            } catch (_: Exception) {
                files = emptyList()
            }
            isLoading = false
        }
    }

    LaunchedEffect(searchQuery) {
        if (searchQuery.length >= 2) {
            scope.launch(Dispatchers.IO) {
                try {
                    searchResults = apiClient.searchFiles(searchQuery, 30)
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
                text = "Attach File",
                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
                modifier = Modifier.padding(bottom = 8.dp),
            )

            Text(
                text = if (currentPath.isBlank()) "/" else currentPath,
                style = MaterialTheme.typography.labelSmall.copy(fontFamily = FontFamily.Monospace),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(bottom = 8.dp),
            )

            OutlinedTextField(
                value = searchQuery,
                onValueChange = { searchQuery = it },
                label = { Text("Search files") },
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
                    color = if (currentPath.isNotBlank()) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = if (currentPath.isNotBlank()) Modifier.clickable {
                        currentPath = currentPath.substringBeforeLast("/", "")
                    } else Modifier,
                )
            }
            HorizontalDivider()

            if (isLoading) {
                CircularProgressIndicator(
                    modifier = Modifier.padding(16.dp).size(24.dp).align(Alignment.CenterHorizontally),
                )
            } else if (searchQuery.length >= 2) {
                if (searchResults.isNotEmpty()) {
                    LazyColumn(contentPadding = PaddingValues(vertical = 4.dp)) {
                        items(searchResults) { relPath ->
                            val name = relPath.substringAfterLast("/").trimEnd('/')
                            val absPath = if (serverDir.isNotBlank()) java.io.File(serverDir, relPath.trimEnd('/')).path else relPath
                            FileRow(
                                name = name,
                                path = absPath,
                                isDir = false,
                                onClick = { onSelect(absPath, name) },
                            )
                        }
                    }
                } else {
                    Text(
                        text = "No files found",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(16.dp),
                    )
                }
            } else {
                LazyColumn(contentPadding = PaddingValues(vertical = 4.dp)) {
                    items(files, key = { it.path }) { node ->
                        FileRow(
                            name = node.name,
                            path = node.path,
                            isDir = node.type == "directory",
                            onClick = {
                                if (node.type == "directory") {
                                    currentPath = node.path
                                } else {
                                    onSelect(node.absolute.ifBlank { node.path }, node.name)
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
private fun FileRow(name: String, path: String, isDir: Boolean, onClick: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 4.dp, vertical = 8.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = if (isDir) "📁 " else "📄 ",
                style = MaterialTheme.typography.bodySmall,
            )
            Text(
                text = name,
                style = MaterialTheme.typography.bodySmall.copy(
                    fontWeight = if (isDir) FontWeight.Medium else FontWeight.Normal
                ),
                color = if (isDir) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
            )
        }
        Text(
            text = path,
            style = MaterialTheme.typography.labelSmall.copy(fontFamily = FontFamily.Monospace),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

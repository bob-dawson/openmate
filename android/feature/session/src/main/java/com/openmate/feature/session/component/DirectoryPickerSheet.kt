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
import androidx.compose.material3.TextButton
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
fun DirectoryPickerSheet(
    apiClient: OpencodeApiClient,
    onSelect: (path: String) -> Unit,
    onDismiss: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var currentPath by remember { mutableStateOf("") }
    var directories by remember { mutableStateOf<List<FileNodeDto>>(emptyList()) }
    var searchQuery by remember { mutableStateOf("") }
    var searchResults by remember { mutableStateOf<List<String>>(emptyList()) }
    var isLoading by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    LaunchedEffect(currentPath) {
        scope.launch(Dispatchers.IO) {
            isLoading = true
            try {
                val nodes = apiClient.listFiles(if (currentPath.isBlank()) "." else currentPath)
                directories = nodes.filter { it.type == "directory" && !it.ignored }.sortedBy { it.name.lowercase() }
            } catch (_: Exception) {
                directories = emptyList()
            }
            isLoading = false
        }
    }

    LaunchedEffect(searchQuery) {
        if (searchQuery.length >= 2) {
            scope.launch(Dispatchers.IO) {
                try {
                    searchResults = apiClient.searchFiles(searchQuery, 20)
                        .filter { it.endsWith("/") || !it.contains(".") }
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
                text = "Select Directory",
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
                label = { Text("Search directories") },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 8.dp),
                singleLine = true,
            )

            Row(
                modifier = Modifier.fillMaxWidth().padding(bottom = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TextButton(onClick = {
                    val parent = currentPath.substringBeforeLast("/", "")
                    currentPath = parent
                }, enabled = currentPath.isNotBlank()) {
                    Text("..")
                }
                Spacer(modifier = Modifier.weight(1f))
                TextButton(
                    onClick = { onSelect(if (currentPath.isBlank()) "." else currentPath) },
                ) {
                    Text("Use this dir")
                }
            }
            HorizontalDivider()

            if (isLoading) {
                CircularProgressIndicator(
                    modifier = Modifier.padding(16.dp).size(24.dp).align(Alignment.CenterHorizontally),
                )
            } else if (searchQuery.length >= 2 && searchResults.isNotEmpty()) {
                LazyColumn(contentPadding = PaddingValues(vertical = 4.dp)) {
                    items(searchResults) { path ->
                        DirectoryRow(
                            name = path.substringAfterLast("/").trimEnd('/'),
                            fullPath = path,
                            onClick = {
                                currentPath = path.trimEnd('/')
                                searchQuery = ""
                            },
                        )
                    }
                }
            } else {
                LazyColumn(contentPadding = PaddingValues(vertical = 4.dp)) {
                    item {
                        DirectoryRow(
                            name = ". (current)",
                            fullPath = currentPath.ifBlank { "." },
                            onClick = { onSelect(if (currentPath.isBlank()) "." else currentPath) },
                        )
                    }
                    items(directories, key = { it.path }) { dir ->
                        DirectoryRow(
                            name = dir.name,
                            fullPath = dir.absolute.ifBlank { dir.path },
                            onClick = { currentPath = dir.absolute.ifBlank { dir.path } },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun DirectoryRow(name: String, fullPath: String, onClick: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 4.dp, vertical = 10.dp),
    ) {
        Text(
            text = name,
            style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.Medium),
            color = MaterialTheme.colorScheme.onSurface,
        )
        if (fullPath != name) {
            Text(
                text = fullPath,
                style = MaterialTheme.typography.labelSmall.copy(fontFamily = FontFamily.Monospace),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

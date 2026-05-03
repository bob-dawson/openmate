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
import com.openmate.core.network.dto.BridgeDirEntryDto
import com.openmate.core.network.dto.BridgeSearchResultDto
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

private data class DirItem(val name: String, val fullPath: String)

private fun normalizePath(path: String): String {
    return path.replace('\\', '/')
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DirectoryPickerSheet(
    apiClient: OpencodeApiClient,
    initialDirectory: String = "",
    onSelect: (path: String) -> Unit,
    onDismiss: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var currentPath by remember { mutableStateOf(normalizePath(initialDirectory)) }
    var isAtRoot by remember { mutableStateOf(initialDirectory.isBlank()) }
    var directories by remember { mutableStateOf<List<DirItem>>(emptyList()) }
    var searchResults by remember { mutableStateOf<List<DirItem>>(emptyList()) }
    var pathInput by remember { mutableStateOf("") }
    var isLoading by remember { mutableStateOf(false) }
    var loadError by remember { mutableStateOf("") }
    var createError by remember { mutableStateOf("") }
    val scope = rememberCoroutineScope()

    fun loadRoots() {
        scope.launch(Dispatchers.IO) {
            isLoading = true
            loadError = ""
            try {
                val roots = apiClient.bridgeListRoots()
                directories = roots.map { DirItem(name = it.name, fullPath = normalizePath(it.path)) }
                isAtRoot = true
            } catch (e: Exception) {
                directories = emptyList()
                loadError = e.message ?: "Failed to list roots"
            }
            isLoading = false
        }
    }

    fun loadDir(path: String) {
        scope.launch(Dispatchers.IO) {
            isLoading = true
            loadError = ""
            try {
                val all = apiClient.bridgeListDir(path)
                directories = all.filter { it.isDirectory }.map { e ->
                    val separator = if (path.endsWith("/")) "" else "/"
                    DirItem(name = e.name, fullPath = normalizePath("$path$separator${e.name}"))
                }
                isAtRoot = false
            } catch (e: Exception) {
                directories = emptyList()
                loadError = e.message ?: "Failed to list directory"
            }
            isLoading = false
        }
    }

    fun navigateTo(path: String) {
        currentPath = normalizePath(path)
        pathInput = currentPath
        if (path.isBlank()) {
            loadRoots()
        } else {
            loadDir(path)
        }
    }

    fun goUp() {
        if (isAtRoot) return
        val parent = currentPath.substringBeforeLast("/", "").substringBeforeLast("\\", "")
        if (parent.isBlank() || parent.length <= 3) {
            navigateTo("")
        } else {
            navigateTo(parent)
        }
    }

    fun doPrefixSearch(query: String) {
        val normalized = normalizePath(query)
        if (normalized.length < 2) {
            searchResults = emptyList()
            return
        }
        scope.launch(Dispatchers.IO) {
            try {
                val results = apiClient.bridgeSearch("", normalized, "prefix", 100)
                searchResults = results.map { r -> DirItem(
                    name = r.path.substringAfterLast("/"),
                    fullPath = normalizePath(r.path),
                ) }
            } catch (_: Exception) {
                searchResults = emptyList()
            }
        }
    }

    LaunchedEffect(Unit) {
        if (initialDirectory.isNotBlank()) {
            navigateTo(normalizePath(initialDirectory))
        } else {
            loadRoots()
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
                .height(560.dp),
        ) {
            Text(
                text = "Select Directory",
                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
                modifier = Modifier.padding(bottom = 8.dp),
            )

            Text(
                text = if (isAtRoot) "/" else currentPath,
                style = MaterialTheme.typography.labelSmall.copy(fontFamily = FontFamily.Monospace),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(bottom = 8.dp),
            )

            OutlinedTextField(
                value = pathInput,
                onValueChange = { newPath ->
                    pathInput = newPath
                    createError = ""
                    doPrefixSearch(newPath)
                },
                label = { Text("Path (prefix search or create)") },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 4.dp),
                singleLine = true,
            )

            Row(
                modifier = Modifier.fillMaxWidth().padding(bottom = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TextButton(onClick = { goUp() }, enabled = !isAtRoot) {
                    Text("..")
                }
                Spacer(modifier = Modifier.weight(1f))
                if (pathInput.isNotBlank()) {
                    TextButton(onClick = {
                        val target = normalizePath(pathInput)
                        scope.launch(Dispatchers.IO) {
                            try {
                                apiClient.bridgeMkdir(target, true)
                                onSelect(target)
                            } catch (e: Exception) {
                                createError = e.message ?: "Failed to create directory"
                            }
                        }
                    }) {
                        Text("Create & Use")
                    }
                }
                TextButton(
                    onClick = { onSelect(if (isAtRoot) "/" else currentPath) },
                ) {
                    Text("Use this dir")
                }
            }

            if (createError.isNotBlank()) {
                Text(
                    text = createError,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(bottom = 4.dp),
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
            } else if (searchResults.isNotEmpty()) {
                LazyColumn(contentPadding = PaddingValues(vertical = 4.dp)) {
                    items(searchResults) { item ->
                        DirectoryRow(
                            name = item.name,
                            fullPath = item.fullPath,
                            onClick = {
                                navigateTo(item.fullPath)
                                searchResults = emptyList()
                            },
                        )
                    }
                }
            } else {
                LazyColumn(contentPadding = PaddingValues(vertical = 4.dp)) {
                    if (!isAtRoot) {
                        item {
                            DirectoryRow(
                                name = ". (current)",
                                fullPath = currentPath,
                                onClick = { onSelect(currentPath) },
                            )
                        }
                    }
                    items(directories, key = { it.fullPath }) { dir ->
                        DirectoryRow(
                            name = dir.name,
                            fullPath = dir.fullPath,
                            onClick = { navigateTo(dir.fullPath) },
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

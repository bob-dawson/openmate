package com.openmate.core.ui.component

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CreateNewFolder
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.openmate.core.ui.R
import java.io.File

@Composable
fun DirectoryPickerDialog(
    rootDir: File,
    onDismiss: () -> Unit,
    onSelect: (File) -> Unit,
) {
    var currentDir by remember { mutableStateOf(rootDir) }
    var showCreateDir by remember { mutableStateOf(false) }
    var newDirName by remember { mutableStateOf("") }

    val subDirs = remember(currentDir) {
        currentDir.listFiles()
            ?.filter { it.isDirectory }
            ?.sortedBy { it.name.lowercase() }
            ?: emptyList()
    }

    val relativePath = remember(currentDir) {
        currentDir.absolutePath.removePrefix(rootDir.absolutePath).removePrefix("/")
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(stringResource(R.string.select_directory))
        },
        text = {
            Column {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    if (currentDir != rootDir) {
                        IconButton(
                            onClick = { currentDir = currentDir.parentFile ?: rootDir },
                            modifier = Modifier.size(32.dp),
                        ) {
                            Icon(
                                Icons.AutoMirrored.Filled.ArrowBack,
                                contentDescription = stringResource(R.string.content_desc_back),
                                modifier = Modifier.size(18.dp),
                            )
                        }
                    }
                    Text(
                        text = if (relativePath.isBlank()) "/" else "/$relativePath/",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(modifier = Modifier.weight(1f))
                    IconButton(
                        onClick = {
                            newDirName = ""
                            showCreateDir = true
                        },
                        modifier = Modifier.size(32.dp),
                    ) {
                        Icon(
                            Icons.Default.CreateNewFolder,
                            contentDescription = stringResource(R.string.new_folder),
                            modifier = Modifier.size(18.dp),
                            tint = MaterialTheme.colorScheme.primary,
                        )
                    }
                }

                Spacer(modifier = Modifier.height(8.dp))

                if (subDirs.isEmpty() && !showCreateDir) {
                    Text(
                        text = stringResource(R.string.no_subdirectories),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(vertical = 16.dp),
                    )
                }

                LazyColumn(
                    modifier = Modifier.height(200.dp),
                ) {
                    items(subDirs) { dir ->
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { currentDir = dir }
                                .padding(vertical = 8.dp, horizontal = 4.dp),
                        ) {
                            Icon(
                                Icons.Default.Folder,
                                contentDescription = null,
                                modifier = Modifier.size(20.dp),
                                tint = MaterialTheme.colorScheme.primary,
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = dir.name,
                                style = MaterialTheme.typography.bodyMedium,
                            )
                        }
                    }
                }

                if (showCreateDir) {
                    Spacer(modifier = Modifier.height(8.dp))
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        OutlinedTextField(
                            value = newDirName,
                            onValueChange = { newDirName = it },
                            placeholder = { Text(stringResource(R.string.folder_name_hint)) },
                            singleLine = true,
                            modifier = Modifier.weight(1f),
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        TextButton(
                            onClick = {
                                if (newDirName.isNotBlank()) {
                                    val newDir = File(currentDir, newDirName)
                                    newDir.mkdirs()
                                    currentDir = newDir
                                    showCreateDir = false
                                }
                            },
                        ) {
                            Text(stringResource(R.string.create))
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onSelect(currentDir) }) {
                Text(stringResource(R.string.select_here))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.cancel))
            }
        },
    )
}

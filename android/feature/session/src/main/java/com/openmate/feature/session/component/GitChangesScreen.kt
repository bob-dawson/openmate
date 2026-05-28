package com.openmate.feature.session.component

import android.text.TextUtils
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.platform.LocalContext
import androidx.hilt.navigation.compose.hiltViewModel
import com.openmate.core.network.dto.BridgeGitStatusEntry
import com.openmate.feature.session.GitChangesViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GitChangesScreen(
    directory: String,
    onBack: () -> Unit,
    onOpenDiff: (filePath: String, directory: String) -> Unit = { _, _ -> },
    viewModel: GitChangesViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsState()

    LaunchedEffect(directory) {
        viewModel.loadStatus(directory)
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Git Changes") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                ),
            )
        },
    ) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
        ) {
            when {
                state.loading -> {
                    CircularProgressIndicator(
                        modifier = Modifier.align(Alignment.Center),
                    )
                }
                state.isNotGitRepo -> {
                    Text(
                        text = "此目录不在 Git 仓库中",
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.align(Alignment.Center),
                    )
                }
                state.error != null -> {
                    Text(
                        text = state.error!!,
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.align(Alignment.Center),
                    )
                }
                state.files != null -> {
                    val files = state.files!!
                    if (files.isEmpty()) {
                        Text(
                            text = "没有未提交的变更",
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.align(Alignment.Center),
                        )
                    } else {
                        FileList(
                             files = files,
                             directory = directory,
                             onOpenDiff = onOpenDiff,
                         )
                    }
                }
            }
        }
    }
}

@Composable
private fun FileList(
    files: List<BridgeGitStatusEntry>,
    directory: String,
    onOpenDiff: (filePath: String, directory: String) -> Unit,
) {
    val context = LocalContext.current
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(0.dp),
    ) {
        items(files, key = { it.path }) { entry ->
            val color = statusColor(entry.status)
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable {
                        when (entry.status) {
                            "untracked" -> {
                                val fullPath = if (entry.path.startsWith("/") || (entry.path.length >= 3 && entry.path[1] == ':')) {
                                    entry.path
                                } else {
                                    "${directory.replace('\\', '/')}/${entry.path}"
                                }
                                val intent = android.content.Intent().apply {
                                    setClassName(context, "com.openmate.app.fileviewer.FileViewerActivity")
                                    putExtra("file_path", fullPath)
                                }
                                context.startActivity(intent)
                            }
                            "deleted" -> {
                                android.widget.Toast.makeText(context, "文件已被删除", android.widget.Toast.LENGTH_SHORT).show()
                            }
                            else -> onOpenDiff(entry.path, directory)
                        }
                    }
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    imageVector = statusIcon(entry.status),
                    contentDescription = entry.status,
                    tint = color,
                    modifier = Modifier.size(20.dp),
                )
                Spacer(modifier = Modifier.width(12.dp))
                StartEllipsisText(
                    text = entry.path,
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}

@Composable
private fun StartEllipsisText(
    text: String,
    modifier: Modifier = Modifier,
) {
    AndroidView(
        modifier = modifier,
        factory = { ctx ->
            android.widget.TextView(ctx).apply {
                ellipsize = TextUtils.TruncateAt.START
                maxLines = 1
                setSingleLine()
                typeface = android.graphics.Typeface.MONOSPACE
                setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 14f)
            }
        },
        update = { tv ->
            tv.text = text
        },
    )
}

private fun statusColor(status: String): Color {
    return when (status) {
        "modified" -> Color(0xFFFFA500)
        "added" -> Color(0xFF4CAF50)
        "deleted" -> Color(0xFFF44336)
        "untracked" -> Color(0xFF42A5F5)
        "renamed" -> Color(0xFF9C27B0)
        "unmerged" -> Color(0xFFFF5722)
        else -> Color(0xFFB0BEC5)
    }
}

private fun statusIcon(status: String) = when (status) {
    "added" -> Icons.Default.Add
    "deleted" -> Icons.Default.Close
    "modified" -> Icons.Default.Edit
    else -> Icons.Default.Edit
}

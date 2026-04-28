package com.openmate.feature.session.component

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.openmate.core.domain.model.Part

@Composable
fun PartColumn(
    parts: List<Part>,
    isUser: Boolean,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier) {
        parts.forEach { part ->
            PartRenderer(part = part, isUser = isUser)
            Spacer(modifier = Modifier.height(4.dp))
        }
    }
}

@Composable
fun PartRenderer(
    part: Part,
    isUser: Boolean,
    modifier: Modifier = Modifier,
) {
    when (part) {
        is Part.TextPart -> {
            com.openmate.core.ui.component.MessageBubble(
                text = part.text,
                isUser = isUser,
                modifier = modifier,
            )
        }
        is Part.ToolInvocationPart -> {
            ToolInvocationCard(
                toolName = part.toolName,
                state = part.state,
                args = part.args,
                result = part.result,
                modifier = modifier,
            )
        }
        is Part.ReasoningPart -> {
            Text(
                text = part.text,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = modifier.padding(8.dp),
            )
        }
        is Part.StepStartPart -> {
            Text(
                text = if (part.snapshot != null) "Step started" else "Step started",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.outline,
                modifier = modifier.padding(horizontal = 8.dp),
            )
        }
        is Part.StepFinishPart -> {
            Text(
                text = "Step finished: ${part.reason}",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.outline,
                modifier = modifier.padding(horizontal = 8.dp),
            )
        }
        is Part.FilePart -> {
            com.openmate.core.ui.component.MessageBubble(
                text = "\uD83D\uDCC4 ${part.filename ?: part.url}",
                isUser = false,
                modifier = modifier,
            )
        }
        is Part.SnapshotPart -> {
            Text(
                text = "Snapshot",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.outline,
                modifier = modifier.padding(horizontal = 8.dp),
            )
        }
        is Part.PatchPart -> {
            PatchCard(
                hash = part.hash,
                files = part.files,
                modifier = modifier,
            )
        }
        is Part.AgentPart -> {
            Text(
                text = "Agent: ${part.name}",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.primary,
                modifier = modifier.padding(horizontal = 8.dp),
            )
        }
        is Part.CompactionPart -> {
            Text(
                text = if (part.auto) "Compacted (auto)" else "Compacted",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = modifier.padding(8.dp),
            )
        }
        is Part.SubtaskPart -> {
            Text(
                text = "Subtask: ${part.prompt}",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = modifier.padding(horizontal = 8.dp),
            )
        }
        is Part.RetryPart -> {
            Text(
                text = "Retry #${part.attempt}${part.error?.let { ": $it" } ?: ""}",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.error,
                modifier = modifier.padding(horizontal = 8.dp),
            )
        }
    }
}

@Composable
private fun PatchCard(
    hash: String,
    files: List<String>,
    modifier: Modifier = Modifier,
) {
    androidx.compose.material3.Card(
        modifier = modifier.padding(vertical = 4.dp),
        colors = androidx.compose.material3.CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant,
        ),
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Text(
                text = "Patch ${hash.take(8)}",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary,
            )
            Spacer(modifier = Modifier.height(4.dp))
            files.forEach { file ->
                Text(
                    text = file,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                )
            }
        }
    }
}

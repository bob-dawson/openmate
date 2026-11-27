package com.openmate.feature.session.component

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.openmate.core.ui.theme.TopBarBackground
import com.openmate.feature.session.PendingPrompt
import com.openmate.feature.session.PromptDelivery
import com.openmate.feature.session.R

/**
 * Prompts that are enqueued but not yet admitted. They stay out of the timeline because their
 * enqueue time is not their processing time.
 */
@Composable
fun PendingPromptsBar(
    prompts: List<PendingPrompt>,
    onCancel: (String) -> Unit,
    onToggleDelivery: (PendingPrompt) -> Unit,
    onEdit: (PendingPrompt) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (prompts.isEmpty()) return
    val visible = prompts.take(3)
    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(TopBarBackground)
            .padding(horizontal = 16.dp, vertical = 2.dp),
    ) {
        visible.forEach { prompt ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 2.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                PendingBadge(prompt = prompt, onToggle = { onToggleDelivery(prompt) })
                Text(
                    text = prompt.text.ifBlank { stringResource(R.string.pending_attachment_only) },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                if (prompt.imageCount > 0) {
                    Text(
                        text = stringResource(R.string.pending_image_count, prompt.imageCount),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (prompt.fileCount > 0) {
                    Text(
                        text = stringResource(R.string.pending_file_count, prompt.fileCount),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (!prompt.localOnly && !prompt.hasAttachments) {
                    IconButton(onClick = { onEdit(prompt) }, modifier = Modifier.size(28.dp)) {
                        Icon(
                            Icons.Filled.Edit,
                            contentDescription = stringResource(R.string.pending_edit),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(16.dp),
                        )
                    }
                }
                IconButton(onClick = { onCancel(prompt.id) }, modifier = Modifier.size(28.dp)) {
                    Icon(
                        Icons.Filled.Close,
                        contentDescription = stringResource(R.string.pending_delete),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(16.dp),
                    )
                }
            }
        }
        if (prompts.size > visible.size) {
            Text(
                text = stringResource(R.string.pending_more, prompts.size - visible.size),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 4.dp, top = 2.dp),
            )
        }
    }
}

@Composable
private fun PendingBadge(prompt: PendingPrompt, onToggle: () -> Unit) {
    val label = when {
        prompt.localOnly -> stringResource(R.string.pending_delivering)
        prompt.delivery == PromptDelivery.QUEUE -> stringResource(R.string.pending_delivery_queue)
        else -> stringResource(R.string.pending_delivery_steer)
    }
    val clickable = !prompt.localOnly
    Surface(
        shape = RoundedCornerShape(4.dp),
        color = MaterialTheme.colorScheme.primary.copy(alpha = 0.12f),
        modifier = if (clickable) {
            Modifier.clickable { onToggle() }
        } else {
            Modifier
        },
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
        )
    }
}

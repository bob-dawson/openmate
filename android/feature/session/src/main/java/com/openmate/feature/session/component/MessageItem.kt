package com.openmate.feature.session.component

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.openmate.feature.session.R
import com.openmate.core.common.formatDurationMillis
import com.openmate.core.common.toTimeString
import com.openmate.core.domain.model.Message
import com.openmate.core.domain.model.MessageRole
import com.openmate.core.domain.model.Part
import com.openmate.core.domain.model.PermissionReply
import com.openmate.core.domain.model.PermissionRequest
import com.openmate.core.domain.model.QuestionRequest

@Composable
fun MessageItem(
    message: Message,
    pendingAssistantId: String? = null,
    pendingQuestions: List<QuestionRequest> = emptyList(),
    pendingPermissions: List<PermissionRequest> = emptyList(),
    onReplyQuestion: (String, List<List<String>>) -> Unit = { _, _ -> },
    onRejectQuestion: (String) -> Unit = {},
    onReplyPermission: (String, PermissionReply, String?) -> Unit = { _, _, _ -> },
    onNavigateToSubtask: ((subtaskSessionID: String, title: String) -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    val isUser = message.role == MessageRole.USER
    val isQueued = isUser && pendingAssistantId != null && message.id > pendingAssistantId
    val modelLabel = if (isUser && message.modelID != null) {
        val provider = message.providerID ?: ""
        val model = message.modelID
        if (provider.isNotBlank()) "$provider/$model" else model
    } else null
    val isEmptyAssistant = !isUser && message.parts.none {
        it is Part.TextPart && it.text.isNotBlank() && !it.synthetic
                || it is Part.ToolInvocationPart
                || it is Part.FilePart
                || it is Part.ReasoningPart
                || it is Part.SubtaskPart
                || it is Part.AgentPart
                || it is Part.PatchPart
                || it is Part.SnapshotPart
    }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp, horizontal = 12.dp),
    ) {
        if (modelLabel != null) {
            Text(
                text = modelLabel,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 4.dp, bottom = 2.dp),
            )
        }
        if (isEmptyAssistant) {
            if (message.completedAt == null) {
                ThinkingIndicator()
            } else {
                AbortedIndicator()
            }
        } else {
            PartColumn(
                parts = message.parts,
                isUser = isUser,
                pendingQuestions = pendingQuestions,
                pendingPermissions = pendingPermissions,
                onReplyQuestion = onReplyQuestion,
                onRejectQuestion = onRejectQuestion,
                onReplyPermission = onReplyPermission,
                onNavigateToSubtask = onNavigateToSubtask,
            )
        }
        if (isQueued) {
            Box(
                modifier = Modifier
                    .padding(start = 4.dp, top = 2.dp, bottom = 4.dp)
                    .clip(RoundedCornerShape(4.dp))
                    .background(MaterialTheme.colorScheme.primary)
                    .padding(horizontal = 6.dp, vertical = 2.dp),
            ) {
                Text(
                    text = stringResource(R.string.queued),
                    style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
                    color = MaterialTheme.colorScheme.onPrimary,
                )
            }
        } else if (message.createdAt > 0) {
            val timeText = message.createdAt.toTimeString()
            val completedAt = message.completedAt
            val durationText = if (!isUser && completedAt != null && completedAt > message.createdAt) {
                val duration = completedAt - message.createdAt
                " · ${formatDurationMillis(duration)}"
            } else ""
            Text(
                text = timeText + durationText,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 4.dp, top = 2.dp, bottom = 4.dp),
            )
        }
    }
}

@Composable
private fun ThinkingIndicator() {
    Row(
        modifier = Modifier.padding(start = 4.dp, top = 4.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        CircularProgressIndicator(
            modifier = Modifier.size(12.dp),
            strokeWidth = 2.dp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(modifier = Modifier.width(8.dp))
        Text(
            text = stringResource(R.string.thinking),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun AbortedIndicator() {
    Text(
        text = stringResource(R.string.aborted),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(start = 4.dp, top = 4.dp, bottom = 4.dp),
    )
}

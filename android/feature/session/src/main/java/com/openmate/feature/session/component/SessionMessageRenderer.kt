package com.openmate.feature.session.component

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import com.openmate.core.domain.model.SessionMessage
import com.openmate.core.ui.component.MessageBubble
import com.openmate.feature.session.R
import com.openmate.core.common.formatDurationMillis
import com.openmate.core.common.toTimeString
import kotlinx.serialization.json.*

@Composable
fun SessionMessageRenderer(
    entity: SessionMessage,
    showReasoning: Boolean = true,
    isQueued: Boolean = false,
    onFullContentRequest: (messageId: String) -> Unit,
    onNavigateToSubtask: (subtaskSessionID: String, title: String) -> Unit = { _, _ -> },
) {
    val dataJson = remember(entity.data) {
        runCatching { Json.parseToJsonElement(entity.data).jsonObject }.getOrNull()
    } ?: return

    when (entity.type) {
        "user" -> {
            Column(modifier = Modifier.fillMaxWidth()) {
                UserMessageItem(dataJson)
                MessageMetadata(
                    timeCreated = entity.timeCreated,
                    completedAt = null,
                    modelName = null,
                    isQueued = isQueued,
                )
            }
        }
        "assistant" -> {
            val modelName = dataJson["model"]?.jsonObject?.get("name")?.jsonPrimitive?.contentOrNull
            AssistantMessageItem(dataJson, showReasoning, onNavigateToSubtask)
            MessageMetadata(
                timeCreated = entity.timeCreated,
                completedAt = entity.completedAt,
                modelName = modelName,
                isQueued = false,
            )
        }
        "synthetic" -> { }
        else -> { }
    }
}

@Composable
private fun MessageMetadata(
    timeCreated: Long,
    completedAt: Long?,
    modelName: String?,
    isQueued: Boolean,
) {
    if (timeCreated <= 0) return
    val timeText = timeCreated.toTimeString()
    val durationText = if (completedAt != null && completedAt > timeCreated) {
        " · ${formatDurationMillis(completedAt - timeCreated)}"
    } else ""
    val modelText = modelName?.let { " · $it" } ?: ""
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 4.dp, top = 2.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (isQueued) {
            QueuedBadge()
            Spacer(modifier = Modifier.width(6.dp))
        }
        Text(
            text = timeText + durationText + modelText,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
fun UserMessageItem(data: JsonObject) {
    val text = data["text"]?.jsonPrimitive?.contentOrNull ?: ""
    if (text.isNotBlank()) {
        MessageBubble(text = text, isUser = true, modifier = Modifier.fillMaxWidth())
    }
    val files = data["files"]?.jsonArray
    if (files != null) {
        for (file in files) {
            val obj = file.jsonObject
            val mime = obj["mime"]?.jsonPrimitive?.contentOrNull ?: "file"
            val filename = obj["filename"]?.jsonPrimitive?.contentOrNull
            if (filename != null) {
                FileAttachmentTag(mime = mime, filename = filename)
            }
        }
    }
    val content = data["content"]?.jsonArray
    if (content != null) {
        for (item in content) {
            val obj = item.jsonObject
            if (obj["type"]?.jsonPrimitive?.contentOrNull == "file") {
                val mime = obj["mime"]?.jsonPrimitive?.contentOrNull ?: "file"
                val filename = obj["filename"]?.jsonPrimitive?.contentOrNull
                if (filename != null) {
                    FileAttachmentTag(mime = mime, filename = filename)
                }
            }
        }
    }
}

@Composable
fun AssistantMessageItem(data: JsonObject, showReasoning: Boolean = true, onNavigateToSubtask: (String, String) -> Unit = { _, _ -> }) {
    val content = data["content"]?.jsonArray ?: return
    val reasoningExpanded = remember { mutableStateOf(true) }

    Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 2.dp)) {
        for (item in content) {
            val obj = item.jsonObject
            when (obj["type"]?.jsonPrimitive?.contentOrNull) {
                "text" -> {
                    val text = obj["text"]?.jsonPrimitive?.contentOrNull ?: ""
                    if (text.isNotBlank()) {
                        MessageBubble(text = text, isUser = false, modifier = Modifier.fillMaxWidth())
                    }
                }
                "tool" -> {
                    val name = obj["name"]?.jsonPrimitive?.contentOrNull ?: "tool"
                    val state = obj["state"]?.jsonObject
                    val status = state?.get("status")?.jsonPrimitive?.contentOrNull ?: ""
                    val input = state?.get("input")?.toString()
                    val structuredResult = state?.get("structured")
                    val contentArr = state?.get("content")?.jsonArray
                    val errorObj = state?.get("error")

                    val resultText = when {
                        errorObj != null -> errorObj.toString()
                        contentArr != null && contentArr.isNotEmpty() -> {
                            contentArr.joinToString("\n") { elem ->
                                elem.jsonObject["text"]?.jsonPrimitive?.contentOrNull ?: elem.toString()
                            }
                        }
                        structuredResult != null -> structuredResult.toString()
                        else -> null
                    }

                    val displayItem = DisplayItem.ToolItem(
                        toolName = name,
                        state = when (status) {
                            "pending" -> com.openmate.core.domain.model.ToolCallState.PENDING
                            "running" -> com.openmate.core.domain.model.ToolCallState.RUNNING
                            "completed" -> com.openmate.core.domain.model.ToolCallState.COMPLETED
                            "error" -> com.openmate.core.domain.model.ToolCallState.ERROR
                            else -> com.openmate.core.domain.model.ToolCallState.COMPLETED
                        },
                        args = input,
                        result = resultText,
                        files = emptyList(),
                        hash = null,
                    )

                    when (status) {
                        "pending" -> PendingToolLine(displayItem)
                        "running" -> RunningToolLine(displayItem)
                        "error" -> ErrorToolLine(displayItem)
                        else -> {
                            val summary = toolSummary(name, input, resultText)
                            if (name == "task") {
                                TaskToolLine(
                                    item = displayItem,
                                    summary = summary,
                                    onNavigate = onNavigateToSubtask,
                                )
                            } else if (summary.isBlock) {
                                BlockToolLine(displayItem, summary)
                            } else {
                                InlineToolLine(displayItem)
                            }
                        }
                    }
                }
                "reasoning" -> {
                    val text = obj["text"]?.jsonPrimitive?.contentOrNull ?: ""
                    if (text.isNotBlank() && showReasoning) {
                        val filtered = text.replace(Regex("\\[REDACTED\\][\\s\\S]*?\\[REDACTED\\]"), "[REDACTED]")
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(start = 8.dp, top = 4.dp)
                                .clickable { reasoningExpanded.value = !reasoningExpanded.value },
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Box(
                                modifier = Modifier
                                    .width(2.dp)
                                    .height(16.dp)
                                    .background(MaterialTheme.colorScheme.surfaceVariant),
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = if (reasoningExpanded.value) "▼ Thinking" else "▶ Thinking",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                            )
                        }
                        AnimatedVisibility(visible = reasoningExpanded.value) {
                            Row(
                                modifier = Modifier.fillMaxWidth().padding(top = 2.dp),
                            ) {
                                Box(
                                    modifier = Modifier
                                        .width(2.dp)
                                        .fillMaxWidth()
                                        .padding(start = 8.dp)
                                        .background(MaterialTheme.colorScheme.surfaceVariant),
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(
                                    text = filtered,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                                    modifier = Modifier.weight(1f).padding(end = 8.dp),
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun QueuedBadge() {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.padding(start = 4.dp, top = 2.dp, bottom = 4.dp),
    ) {
        Box(
            modifier = Modifier
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
    }
}

@Composable
private fun FileAttachmentTag(mime: String, filename: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 8.dp, top = 2.dp, bottom = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .background(
                    color = MaterialTheme.colorScheme.primary.copy(alpha = 0.15f),
                    shape = RoundedCornerShape(4.dp),
                )
                .padding(horizontal = 6.dp, vertical = 2.dp),
        ) {
            Text(
                text = mime.ifBlank { "file" },
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.primary,
            )
        }
        Spacer(modifier = Modifier.width(6.dp))
        Box(
            modifier = Modifier
                .background(
                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                    shape = RoundedCornerShape(4.dp),
                )
                .padding(horizontal = 6.dp, vertical = 2.dp),
        ) {
            Text(
                text = filename,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

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
import com.openmate.core.domain.model.SessionMessage
import com.openmate.core.ui.component.MessageBubble
import kotlinx.serialization.json.*

@Composable
fun SessionMessageRenderer(
    entity: SessionMessage,
    showReasoning: Boolean = true,
    onFullContentRequest: (messageId: String) -> Unit,
) {
    val dataJson = remember(entity.data) {
        runCatching { Json.parseToJsonElement(entity.data).jsonObject }.getOrNull()
    } ?: return

    when (entity.type) {
        "user" -> UserMessageItem(dataJson)
        "assistant" -> AssistantMessageItem(dataJson, showReasoning)
        "synthetic" -> { }
        else -> { }
    }
}

@Composable
fun UserMessageItem(data: JsonObject) {
    val text = data["text"]?.jsonPrimitive?.contentOrNull ?: ""
    if (text.isNotBlank()) {
        MessageBubble(text = text, isUser = true, modifier = Modifier.fillMaxWidth())
    }
}

@Composable
fun AssistantMessageItem(data: JsonObject, showReasoning: Boolean = true) {
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
                                    onNavigate = { _, _ -> },
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

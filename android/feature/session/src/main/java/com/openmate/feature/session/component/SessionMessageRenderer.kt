package com.openmate.feature.session.component

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.openmate.core.domain.model.SessionMessage
import kotlinx.serialization.json.*

@Composable
fun SessionMessageRenderer(
    entity: SessionMessage,
    onFullContentRequest: (messageId: String) -> Unit,
) {
    val dataJson = remember(entity.data) {
        runCatching { Json.parseToJsonElement(entity.data).jsonObject }.getOrNull()
    } ?: return

    when (entity.type) {
        "user" -> UserMessageItem(dataJson)
        "assistant" -> AssistantMessageItem(dataJson)
        "model-switched" -> ModelSwitchedItem(dataJson)
        "agent-switched" -> AgentSwitchedItem(dataJson)
        "compaction" -> CompactionItem(dataJson)
        "shell" -> ShellMessageItem(dataJson)
        "synthetic" -> { }
        else -> UnknownMessageItem(entity)
    }
}

@Composable
fun UserMessageItem(data: JsonObject) {
    val text = data["text"]?.jsonPrimitive?.contentOrNull ?: ""
    Surface(
        color = MaterialTheme.colorScheme.primaryContainer,
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp)
    ) {
        Text(
            text = text,
            modifier = Modifier.padding(12.dp),
            style = MaterialTheme.typography.bodyLarge,
        )
    }
}

@Composable
fun AssistantMessageItem(data: JsonObject) {
    val content = data["content"]?.jsonArray ?: return
    Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp)) {
        for (item in content) {
            val obj = item.jsonObject
            when (obj["type"]?.jsonPrimitive?.contentOrNull) {
                "text" -> {
                    val text = obj["text"]?.jsonPrimitive?.contentOrNull ?: ""
                    if (text.isNotBlank()) {
                        Text(
                            text = text,
                            style = MaterialTheme.typography.bodyLarge,
                            modifier = Modifier.padding(vertical = 2.dp)
                        )
                    }
                }
                "tool" -> {
                    val name = obj["name"]?.jsonPrimitive?.contentOrNull ?: "tool"
                    val state = obj["state"]?.jsonObject
                    val status = state?.get("status")?.jsonPrimitive?.contentOrNull ?: ""
                    Text(
                        text = "\u2699 $name ($status)",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(vertical = 2.dp)
                    )
                }
                "reasoning" -> {
                    val text = obj["text"]?.jsonPrimitive?.contentOrNull ?: ""
                    if (text.isNotBlank()) {
                        Text(
                            text = "\uD83D\uDCAD $text",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(vertical = 2.dp)
                        )
                    }
                }
            }
        }
        val agent = data["agent"]?.jsonPrimitive?.contentOrNull
        val model = data["model"]?.jsonObject
        if (agent != null || model != null) {
            val modelStr = model?.let { "${it["providerID"]?.jsonPrimitive?.contentOrNull}/${it["id"]?.jsonPrimitive?.contentOrNull}" } ?: ""
            Text(
                text = "\u25A3 $agent \u00B7 $modelStr",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp)
            )
        }
    }
}

@Composable
fun ModelSwitchedItem(data: JsonObject) {
    val model = data["model"]?.jsonObject ?: return
    val providerID = model["providerID"]?.jsonPrimitive?.contentOrNull ?: ""
    val id = model["id"]?.jsonPrimitive?.contentOrNull ?: ""
    Text(
        text = "\u25C7 Switched model to $providerID/$id",
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 2.dp)
    )
}

@Composable
fun AgentSwitchedItem(data: JsonObject) {
    val agent = data["agent"]?.jsonPrimitive?.contentOrNull ?: ""
    Text(
        text = "\u25A3 Switched agent to $agent",
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 2.dp)
    )
}

@Composable
fun CompactionItem(data: JsonObject) {
    val summary = data["summary"]?.jsonPrimitive?.contentOrNull ?: ""
    if (summary.isNotBlank()) {
        Text(
            text = "\uD83D\uDCDD Compaction: ${summary.take(200)}${if (summary.length > 200) "..." else ""}",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 2.dp)
        )
    }
}

@Composable
fun ShellMessageItem(data: JsonObject) {
    val command = data["command"]?.jsonPrimitive?.contentOrNull ?: ""
    val output = data["output"]?.jsonPrimitive?.contentOrNull ?: ""
    Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 2.dp)) {
        Text(text = "$ $command", style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Bold)
        if (output.isNotBlank()) {
            Text(
                text = output.take(500),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
fun UnknownMessageItem(entity: SessionMessage) {
    Text(
        text = "[${entity.type}] ${entity.id.take(20)}",
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 2.dp))
}

package com.openmate.feature.session.component

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.openmate.core.domain.model.Part
import com.openmate.core.domain.model.ToolCallState
import androidx.compose.ui.graphics.Color
import dev.jeziellago.compose.markdowntext.MarkdownText

private val CodeBlockBackground = Color(0xFF1e1e2e)
private val CodeBlockText = Color(0xFFcdd6f4)

sealed class DisplayItem {
    data class TextItem(val text: String, val isUser: Boolean) : DisplayItem()
    data class ToolItem(
        val toolName: String,
        val state: ToolCallState,
        val args: String?,
        val result: String?,
        val files: List<String>,
        val hash: String?,
    ) : DisplayItem()
    data class ReasoningItem(val text: String) : DisplayItem()
    data class AgentItem(val name: String) : DisplayItem()
    data class SubtaskItem(val agent: String, val description: String, val prompt: String) : DisplayItem()
}

fun List<Part>.toDisplayItems(isUser: Boolean): List<DisplayItem> {
    val items = mutableListOf<DisplayItem>()
    val patches = mutableListOf<Part.PatchPart>()
    var i = 0
    while (i < this.size) {
        val part = this[i]
        when (part) {
            is Part.TextPart -> {
                if (!part.synthetic) {
                    items.add(DisplayItem.TextItem(part.text, isUser))
                }
            }
            is Part.ReasoningPart -> {
                items.add(DisplayItem.ReasoningItem(part.text))
            }
            is Part.ToolInvocationPart -> {
                val nextPatch = if (i + 1 < this.size && this[i + 1] is Part.PatchPart) {
                    i++
                    (this[i] as Part.PatchPart)
                } else if (patches.isNotEmpty()) {
                    patches.removeAt(0)
                } else null
                items.add(DisplayItem.ToolItem(
                    toolName = part.toolName,
                    state = part.state,
                    args = part.args,
                    result = part.result,
                    files = nextPatch?.files ?: emptyList(),
                    hash = nextPatch?.hash,
                ))
            }
            is Part.PatchPart -> {
                patches.add(part)
            }
            is Part.StepStartPart, is Part.StepFinishPart,
            is Part.CompactionPart, is Part.RetryPart,
            is Part.SnapshotPart, is Part.FilePart -> {}
            is Part.AgentPart -> {
                items.add(DisplayItem.AgentItem(part.name))
            }
            is Part.SubtaskPart -> {
                items.add(DisplayItem.SubtaskItem(part.agent, part.description, part.prompt))
            }
        }
        i++
    }
    return items
}

@Composable
fun PartColumn(
    parts: List<Part>,
    isUser: Boolean,
    modifier: Modifier = Modifier,
) {
    val displayItems = parts.toDisplayItems(isUser)
    Column(modifier = modifier) {
        displayItems.forEach { item ->
            when (item) {
                is DisplayItem.TextItem -> {
                    com.openmate.core.ui.component.MessageBubble(
                        text = item.text,
                        isUser = item.isUser,
                    )
                }
                is DisplayItem.ToolItem -> {
                    ToolInvocationCard(
                        toolName = item.toolName,
                        state = item.state,
                        args = item.args,
                        result = item.result,
                        files = item.files,
                        hash = item.hash,
                    )
                }
                is DisplayItem.ReasoningItem -> {
                    MarkdownText(
                        markdown = item.text,
                        modifier = Modifier.padding(start = 8.dp, end = 8.dp, top = 2.dp, bottom = 2.dp),
                        style = MaterialTheme.typography.bodySmall.copy(
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        ),
                        syntaxHighlightColor = CodeBlockBackground,
                        syntaxHighlightTextColor = CodeBlockText,
                    )
                }
                is DisplayItem.AgentItem -> {
                    AgentCard(name = item.name)
                }
                is DisplayItem.SubtaskItem -> {
                    SubtaskCard(agent = item.agent, description = item.description, prompt = item.prompt)
                }
            }
            Spacer(modifier = Modifier.height(4.dp))
        }
    }
}

private val AgentColor = Color(0xFF9D7CD8)

@Composable
private fun AgentCard(name: String, modifier: Modifier = Modifier) {
    val expanded = remember { mutableStateOf(false) }
    Card(
        modifier = modifier.padding(vertical = 4.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { expanded.value = !expanded.value }
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = "●",
                style = MaterialTheme.typography.bodySmall,
                color = AgentColor,
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = "Agent: $name",
                style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.Medium),
                color = MaterialTheme.colorScheme.onSurface,
            )
        }
    }
}

@Composable
private fun SubtaskCard(agent: String, description: String, prompt: String, modifier: Modifier = Modifier) {
    val expanded = remember { mutableStateOf(false) }
    Card(
        modifier = modifier.padding(vertical = 4.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
    ) {
        Column {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { expanded.value = !expanded.value }
                    .padding(12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "●",
                    style = MaterialTheme.typography.bodySmall,
                    color = AgentColor,
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = if (description.isNotBlank()) "$agent: $description" else "Subtask: $agent",
                    style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.Medium),
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    text = if (expanded.value) "▲" else "▼",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            AnimatedVisibility(visible = expanded.value) {
                Text(
                    text = prompt,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = 12.dp, end = 12.dp, bottom = 12.dp),
                    maxLines = 5,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

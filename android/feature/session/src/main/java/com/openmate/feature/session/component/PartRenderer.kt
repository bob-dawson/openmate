package com.openmate.feature.session.component

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
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
            is Part.AgentPart, is Part.SubtaskPart,
            is Part.SnapshotPart, is Part.FilePart -> {}
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
            }
            Spacer(modifier = Modifier.height(4.dp))
        }
    }
}

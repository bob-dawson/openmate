package com.openmate.feature.session.component

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.background
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.openmate.core.domain.model.Part
import com.openmate.core.domain.model.QuestionInfo
import com.openmate.core.domain.model.QuestionOption
import com.openmate.core.domain.model.QuestionRequest
import com.openmate.core.domain.model.ToolCallState
import androidx.compose.ui.graphics.Color
import dev.jeziellago.compose.markdowntext.MarkdownText
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

private val CodeBlockBackground = Color(0xFF1e1e2e)
private val CodeBlockText = Color(0xFFcdd6f4)
private val questionJson = Json { ignoreUnknownKeys = true }

sealed class DisplayItem {
    data class TextItem(val text: String, val isUser: Boolean) : DisplayItem()
    data class ToolItem(
        val toolName: String,
        val state: ToolCallState,
        val args: String?,
        val result: String?,
        val files: List<String>,
        val hash: String?,
        val callID: String? = null,
    ) : DisplayItem()
    data class ReasoningItem(val text: String) : DisplayItem()
    data class AgentItem(val name: String) : DisplayItem()
    data class SubtaskItem(val agent: String, val description: String, val prompt: String) : DisplayItem()
}

private fun parseQuestionArgs(args: String?): List<QuestionInfo>? {
    if (args == null) return null
    try {
        val root = questionJson.parseToJsonElement(args).jsonObject
        val questionsArr = root["questions"]?.jsonArray ?: return null
        return questionsArr.map { elem ->
            val obj = elem.jsonObject
            val opts = obj["options"]?.jsonArray?.map { opt ->
                val optObj = opt.jsonObject
                QuestionOption(
                    label = optObj["label"]?.jsonPrimitive?.content ?: "",
                    description = optObj["description"]?.jsonPrimitive?.content ?: "",
                )
            } ?: emptyList()
            QuestionInfo(
                header = obj["header"]?.jsonPrimitive?.content ?: "",
                question = obj["question"]?.jsonPrimitive?.content ?: "",
                options = opts,
                multiple = obj["multiple"]?.jsonPrimitive?.boolean ?: false,
            )
        }
    } catch (_: Exception) {
        return null
    }
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
                    callID = part.toolCallID,
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
    pendingQuestions: List<QuestionRequest>,
    onReplyQuestion: (String, List<List<String>>) -> Unit,
    onRejectQuestion: (String) -> Unit,
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
                    if (item.toolName == "question" && item.state == ToolCallState.RUNNING) {
                        val parsedQuestions = remember(item.args) { parseQuestionArgs(item.args) }
                        val matchedRequest = item.callID?.let { callID ->
                            pendingQuestions.find { it.tool?.callID == callID }
                        }
                        if (parsedQuestions != null) {
                            QuestionCard(
                                questions = parsedQuestions,
                                matchedRequest = matchedRequest,
                                onReply = matchedRequest?.let { req ->
                                    { answers -> onReplyQuestion(req.id, answers) }
                                },
                                onReject = matchedRequest?.let { req ->
                                    { onRejectQuestion(req.id) }
                                },
                            )
                        } else {
                            ToolInvocationCard(
                                toolName = item.toolName,
                                state = item.state,
                                args = item.args,
                                result = item.result,
                                files = item.files,
                                hash = item.hash,
                            )
                        }
                    } else {
                        ToolInvocationCard(
                            toolName = item.toolName,
                            state = item.state,
                            args = item.args,
                            result = item.result,
                            files = item.files,
                            hash = item.hash,
                        )
                    }
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
fun QuestionCard(
    questions: List<QuestionInfo>,
    matchedRequest: QuestionRequest?,
    onReply: ((List<List<String>>) -> Unit)?,
    onReject: (() -> Unit)?,
    modifier: Modifier = Modifier,
) {
    val selectedAnswers = remember { mutableStateOf<Map<Int, List<String>>>(emptyMap()) }
    val canInteract = matchedRequest != null && onReply != null

    Card(
        modifier = modifier.padding(vertical = 4.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            if (!canInteract) {
                Text(
                    text = "Waiting for response...",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(modifier = Modifier.height(8.dp))
            }
            questions.forEachIndexed { qIndex, questionInfo ->
                if (questionInfo.header.isNotBlank()) {
                    Text(
                        text = questionInfo.header,
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.primary,
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                }
                Text(
                    text = questionInfo.question,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Spacer(modifier = Modifier.height(8.dp))

                Column(Modifier.selectableGroup()) {
                    questionInfo.options.forEach { option ->
                        val selected = option.label in (selectedAnswers.value[qIndex] ?: emptyList())
                        OutlinedButton(
                            onClick = {
                                if (canInteract) {
                                    selectedAnswers.value = selectedAnswers.value.toMutableMap().apply {
                                        val current = this[qIndex] ?: emptyList()
                                        this[qIndex] = if (option.label in current) current - option.label else current + option.label
                                    }
                                }
                            },
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 2.dp)
                                .selectable(
                                    selected = selected,
                                    onClick = {},
                                    role = Role.RadioButton,
                                ),
                        ) {
                            Text(
                                text = if (selected) "\u2713 ${option.label}" else option.label,
                                color = if (selected) {
                                    MaterialTheme.colorScheme.primary
                                } else {
                                    MaterialTheme.colorScheme.onSurface
                                },
                            )
                        }
                    }
                }
                Spacer(modifier = Modifier.height(12.dp))
            }

            if (canInteract) {
                Row(modifier = Modifier.align(Alignment.End)) {
                    onReject?.let { reject ->
                        OutlinedButton(onClick = reject) {
                            Text("Reject")
                        }
                    }
                    Spacer(modifier = Modifier.width(8.dp))
                    Button(
                        onClick = { onReply?.invoke(selectedAnswers.value.values.toList()) },
                        enabled = selectedAnswers.value.isNotEmpty(),
                    ) {
                        Text("Submit")
                    }
                }
            }
        }
    }
}

@Composable
fun ToolInvocationCard(
    toolName: String,
    state: ToolCallState,
    args: String?,
    result: String?,
    files: List<String> = emptyList(),
    hash: String? = null,
    modifier: Modifier = Modifier,
) {
    val expanded = remember { mutableStateOf(false) }
    val Warning = Color(0xFFFFA500)

    Card(
        modifier = modifier.padding(vertical = 4.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant,
        ),
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                when (state) {
                    ToolCallState.PENDING -> {
                        Text(
                            text = "PENDING",
                            style = MaterialTheme.typography.labelSmall,
                            color = Warning,
                            modifier = Modifier
                                .clip(RoundedCornerShape(4.dp))
                                .background(Warning.copy(alpha = 0.15f))
                                .padding(horizontal = 6.dp, vertical = 2.dp),
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "Using $toolName...",
                            style = MaterialTheme.typography.bodySmall,
                            color = Warning,
                        )
                    }
                    ToolCallState.RUNNING -> {
                        CircularProgressIndicator(
                            modifier = Modifier.size(16.dp),
                            strokeWidth = 2.dp,
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "Running $toolName...",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    ToolCallState.COMPLETED -> {
                        TextButton(onClick = { expanded.value = !expanded.value }) {
                            Text(
                                text = "$toolName ${if (expanded.value) "▲" else "▼"}",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.primary,
                            )
                        }
                    }
                    ToolCallState.ERROR -> {
                        TextButton(onClick = { expanded.value = !expanded.value }) {
                            Text(
                                text = "$toolName (error) ${if (expanded.value) "▲" else "▼"}",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.error,
                            )
                        }
                    }
                }
            }

            if (files.isNotEmpty()) {
                Column(modifier = Modifier.padding(top = 4.dp)) {
                    files.forEach { file ->
                        Text(
                            text = file,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            fontFamily = FontFamily.Monospace,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }

            AnimatedVisibility(visible = expanded.value && (state == ToolCallState.COMPLETED || state == ToolCallState.ERROR)) {
                Column {
                    args?.let {
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = "Input:",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Text(
                            text = it.take(1000),
                            style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                    }
                    result?.let {
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = "Result:",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Text(
                            text = it.take(1000),
                            style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                    }
                }
            }
        }
    }
}

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
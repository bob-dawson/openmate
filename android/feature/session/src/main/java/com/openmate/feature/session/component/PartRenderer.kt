package com.openmate.feature.session.component

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.openmate.core.domain.model.Part
import com.openmate.core.domain.model.PermissionReply
import com.openmate.core.domain.model.PermissionRequest
import com.openmate.core.domain.model.QuestionInfo
import com.openmate.core.domain.model.QuestionOption
import com.openmate.core.domain.model.QuestionRequest
import com.openmate.core.domain.model.ToolCallState
import dev.jeziellago.compose.markdowntext.MarkdownText
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject

private val CodeBlockBackground = Color(0xFF1e1e2e)
private val CodeBlockText = Color(0xFFcdd6f4)
private val WarningColor = Color(0xFFFFA500)
private val AgentColor = Color(0xFF9D7CD8)
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
}

private data class ToolSummary(
    val icon: String,
    val text: String,
    val isBlock: Boolean,
)

private fun JsonObject?.str(key: String): String? =
    this?.get(key)?.let { if (it is kotlinx.serialization.json.JsonPrimitive) it.content else null }

private fun JsonObject.bool(key: String): Boolean =
    (this[key] as? kotlinx.serialization.json.JsonPrimitive)?.boolean ?: false

private fun toolSummary(toolName: String, args: String?, result: String?): ToolSummary {
    val jsonArgs = try {
        if (args != null) questionJson.parseToJsonElement(args).jsonObject else null
    } catch (_: Exception) { null }

    when (toolName) {
        "bash" -> {
            val command = jsonArgs.str("command") ?: args?.take(80) ?: ""
            val hasOutput = result != null && result.isNotBlank()
            return ToolSummary("$", command, hasOutput)
        }
        "glob" -> {
            val pattern = jsonArgs.str("pattern") ?: ""
            val path = jsonArgs.str("path")
            val suffix = if (path != null) " in $path" else ""
            return ToolSummary("✱", "glob $pattern$suffix", false)
        }
        "read" -> {
            val filePath = jsonArgs.str("filePath") ?: jsonArgs.str("file_path") ?: ""
            return ToolSummary("→", filePath, false)
        }
        "grep" -> {
            val pattern = jsonArgs.str("pattern") ?: ""
            val path = jsonArgs.str("path")
            val suffix = if (path != null) " in $path" else ""
            return ToolSummary("✱", "grep $pattern$suffix", false)
        }
        "webfetch" -> {
            val url = jsonArgs.str("url") ?: ""
            return ToolSummary("%", url, false)
        }
        "websearch" -> {
            val query = jsonArgs.str("query") ?: ""
            return ToolSummary("◈", query, false)
        }
        "codesearch" -> {
            val query = jsonArgs.str("query") ?: ""
            return ToolSummary("◇", query, false)
        }
        "write" -> {
            val filePath = jsonArgs.str("filePath") ?: jsonArgs.str("file_path") ?: ""
            val hasDiags = result != null
            return ToolSummary("←", filePath, hasDiags)
        }
        "edit" -> {
            val filePath = jsonArgs.str("filePath") ?: jsonArgs.str("file_path") ?: ""
            val hasDiff = result != null
            return ToolSummary("←", filePath, hasDiff)
        }
        "task" -> {
            val desc = jsonArgs.str("description") ?: ""
            val agent = jsonArgs.str("agent") ?: toolName
            return ToolSummary("│", "$agent: $desc", false)
        }
        "apply_patch" -> {
            val hasFiles = result != null
            return ToolSummary("%", "apply_patch", hasFiles)
        }
        "todowrite" -> {
            return ToolSummary("⚙", "todowrite", true)
        }
        "question" -> {
            return ToolSummary("→", "question", true)
        }
        "skill" -> {
            val name = jsonArgs.str("name") ?: ""
            return ToolSummary("→", name, false)
        }
        else -> {
            val hasOutput = result != null && result.isNotBlank()
            val paramSnippet = args?.take(60) ?: ""
            return ToolSummary("⚙", "$toolName $paramSnippet", hasOutput)
        }
    }
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
                    label = optObj.str("label") ?: "",
                    description = optObj.str("description") ?: "",
                )
            } ?: emptyList()
            QuestionInfo(
                header = obj.str("header") ?: "",
                question = obj.str("question") ?: "",
                options = opts,
                multiple = obj.bool("multiple"),
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
            is Part.SnapshotPart, is Part.FilePart,
            is Part.AgentPart, is Part.SubtaskPart -> {}
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
    pendingPermissions: List<PermissionRequest>,
    onReplyQuestion: (String, List<List<String>>) -> Unit,
    onRejectQuestion: (String) -> Unit,
    onReplyPermission: (String, PermissionReply, String?) -> Unit,
    onNavigateToSubtask: ((agent: String, description: String, prompt: String) -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    val showReasoning = remember { mutableStateOf(false) }
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
                            InlineToolLine(item)
                        }
                    } else if (item.state == ToolCallState.PENDING) {
                        val matchedPerm = item.callID?.let { callID ->
                            pendingPermissions.find { it.tool?.callID == callID }
                        }
                        if (matchedPerm != null) {
                            PermissionCard(
                                request = matchedPerm,
                                onReply = { reply, msg -> onReplyPermission(matchedPerm.id, reply, msg) },
                            )
                        } else {
                            PendingToolLine(item)
                        }
                    } else if (item.state == ToolCallState.RUNNING) {
                        RunningToolLine(item)
                    } else if (item.state == ToolCallState.ERROR) {
                        ErrorToolLine(item)
                    } else {
                        val summary = toolSummary(item.toolName, item.args, item.result)
                        if (item.toolName == "task" && onNavigateToSubtask != null) {
                            TaskToolLine(item, summary, onNavigateToSubtask)
                        } else if (summary.isBlock) {
                            BlockToolLine(item, summary)
                        } else {
                            InlineToolLine(item)
                        }
                    }
                }
                is DisplayItem.ReasoningItem -> {
                    val filtered = item.text.replace(Regex("\\[REDACTED\\][\\s\\S]*?\\[REDACTED\\]"), "[REDACTED]")
                    if (filtered.isNotBlank()) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 2.dp)
                                .clickable { showReasoning.value = !showReasoning.value },
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                text = if (showReasoning.value) "▼ Thinking" else "▶ Thinking",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        AnimatedVisibility(visible = showReasoning.value) {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(start = 12.dp)
                                    .clip(RoundedCornerShape(4.dp))
                                    .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
                                    .padding(8.dp),
                            ) {
                                Text(
                                    text = "_Thinking:_ $filtered",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                }
            }
            Spacer(modifier = Modifier.height(4.dp))
        }
    }
}

@Composable
private fun InlineToolLine(item: DisplayItem.ToolItem) {
    val summary = toolSummary(item.toolName, item.args, item.result)
    Row(
        modifier = Modifier.padding(vertical = 1.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = summary.icon,
            style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.Bold),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            fontFamily = FontFamily.Monospace,
        )
        Spacer(modifier = Modifier.width(6.dp))
        Text(
            text = summary.text,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            fontFamily = FontFamily.Monospace,
        )
    }
}

@Composable
private fun RunningToolLine(item: DisplayItem.ToolItem) {
    val summary = toolSummary(item.toolName, item.args, item.result)
    Row(
        modifier = Modifier.padding(vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        CircularProgressIndicator(
            modifier = Modifier.size(14.dp),
            strokeWidth = 2.dp,
        )
        Spacer(modifier = Modifier.width(6.dp))
        Text(
            text = "${summary.icon} ${summary.text}",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun PendingToolLine(item: DisplayItem.ToolItem) {
    val summary = toolSummary(item.toolName, item.args, item.result)
    Row(
        modifier = Modifier.padding(vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = summary.icon,
            style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.Bold),
            color = WarningColor,
            fontFamily = FontFamily.Monospace,
        )
        Spacer(modifier = Modifier.width(6.dp))
        Text(
            text = "Using ${summary.text}...",
            style = MaterialTheme.typography.bodySmall,
            color = WarningColor,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun ErrorToolLine(item: DisplayItem.ToolItem) {
    val summary = toolSummary(item.toolName, item.args, item.result)
    val expanded = remember { mutableStateOf(false) }
    Column {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { expanded.value = !expanded.value }
                .padding(vertical = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = "${summary.icon} ${summary.text}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                textDecoration = if (item.result?.contains("rejected") == true || item.result?.contains("denied") == true) TextDecoration.LineThrough else TextDecoration.None,
            )
            Spacer(modifier = Modifier.width(4.dp))
            Text(
                text = if (expanded.value) "▲" else "▼",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.error,
            )
        }
        AnimatedVisibility(visible = expanded.value) {
            item.result?.let { err ->
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(4.dp))
                        .background(MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.3f))
                        .padding(8.dp),
                ) {
                    Text(
                        text = err.take(500),
                        style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
        }
    }
}

@Composable
private fun TaskToolLine(
    item: DisplayItem.ToolItem,
    summary: ToolSummary,
    onNavigate: (agent: String, description: String, prompt: String) -> Unit,
) {
    val jsonArgs = try {
        if (item.args != null) questionJson.parseToJsonElement(item.args).jsonObject else null
    } catch (_: Exception) { null }

    val agent = jsonArgs?.str("agent") ?: ""
    val description = jsonArgs?.str("description") ?: ""
    val prompt = jsonArgs?.str("prompt") ?: ""

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onNavigate(agent, description, prompt) }
            .padding(vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = "│",
            style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.Bold),
            color = AgentColor,
            fontFamily = FontFamily.Monospace,
        )
        Spacer(modifier = Modifier.width(6.dp))
        Text(
            text = summary.text,
            style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.Medium),
            color = AgentColor,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Spacer(modifier = Modifier.width(4.dp))
        Text(
            text = "→",
            style = MaterialTheme.typography.labelSmall,
            color = AgentColor,
        )
    }
}

@Composable
private fun BlockToolLine(item: DisplayItem.ToolItem, summary: ToolSummary) {
    val expanded = remember { mutableStateOf(false) }

    Column(modifier = Modifier.padding(vertical = 2.dp)) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { expanded.value = !expanded.value }
                .padding(vertical = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = summary.icon,
                style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.Bold),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontFamily = FontFamily.Monospace,
            )
            Spacer(modifier = Modifier.width(6.dp))
            Text(
                text = summary.text,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                fontFamily = FontFamily.Monospace,
            )
            Spacer(modifier = Modifier.width(4.dp))
            Text(
                text = if (expanded.value) "▲" else "▼",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        AnimatedVisibility(visible = expanded.value) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(4.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
                    .padding(8.dp),
            ) {
                Column {
                    if (item.toolName == "bash") {
                        val jsonArgs = try {
                            if (item.args != null) questionJson.parseToJsonElement(item.args).jsonObject else null
                        } catch (_: Exception) { null }
                        val command = jsonArgs?.str("command") ?: ""
                        val workdir = jsonArgs?.str("workdir")
                        if (command.isNotBlank()) {
                            Text(
                                text = "$ $command",
                                style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                                color = MaterialTheme.colorScheme.onSurface,
                            )
                        }
                        if (workdir != null) {
                            Text(
                                text = "cwd: $workdir",
                                style = MaterialTheme.typography.labelSmall.copy(fontFamily = FontFamily.Monospace),
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        item.result?.let { output ->
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                text = output.take(500),
                                style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                                color = MaterialTheme.colorScheme.onSurface,
                                maxLines = 10,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    } else if (item.toolName == "todowrite") {
                        item.result?.let { output ->
                            Text(
                                text = output.take(500),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurface,
                            )
                        }
                    } else {
                        item.result?.let { output ->
                            Text(
                                text = output.take(500),
                                style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                                color = MaterialTheme.colorScheme.onSurface,
                                maxLines = 3,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                    if (item.files.isNotEmpty()) {
                        Spacer(modifier = Modifier.height(4.dp))
                        item.files.forEach { file ->
                            Text(
                                text = file,
                                style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun QuestionCard(
    questions: List<QuestionInfo>,
    matchedRequest: QuestionRequest?,
    onReply: ((List<List<String>>) -> Unit)?,
    onReject: (() -> Unit)?,
    modifier: Modifier = Modifier,
) {
    val selectedAnswers = remember { mutableStateOf(emptyMap<Int, List<String>>()) }
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
fun PermissionCard(
    request: PermissionRequest,
    onReply: (PermissionReply, String?) -> Unit,
    modifier: Modifier = Modifier,
) {
    val description = (request.metadata["description"] as? kotlinx.serialization.json.JsonPrimitive)?.content
        ?: (request.metadata["command"] as? kotlinx.serialization.json.JsonPrimitive)?.content

    Card(
        modifier = modifier.padding(vertical = 4.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = "Permission Request",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.primary,
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = request.permission,
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurface,
            )
            if (description != null) {
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = description,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(androidx.compose.ui.unit.Dp.Unspecified)
                        .verticalScroll(rememberScrollState()),
                )
            }
            if (request.patterns.isNotEmpty()) {
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = request.patterns.joinToString("\n"),
                    style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier
                        .fillMaxWidth()
                        .verticalScroll(rememberScrollState()),
                    maxLines = 5,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Spacer(modifier = Modifier.height(12.dp))
            Row {
                Button(onClick = { onReply(PermissionReply.ONCE, null) }) {
                    Text("Allow")
                }
                Spacer(modifier = Modifier.width(8.dp))
                if (request.always.isNotEmpty()) {
                    OutlinedButton(onClick = { onReply(PermissionReply.ALWAYS, null) }) {
                        Text("Always")
                    }
                    Spacer(modifier = Modifier.width(8.dp))
                }
                OutlinedButton(onClick = { onReply(PermissionReply.REJECT, null) }) {
                    Text("Deny")
                }
            }
        }
    }
}
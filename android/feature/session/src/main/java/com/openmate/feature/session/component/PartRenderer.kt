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
import android.graphics.Typeface
import android.text.TextUtils
import android.text.TextPaint
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import com.openmate.feature.session.R
import androidx.compose.ui.text.rememberTextMeasurer
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
import com.openmate.core.domain.model.TodoInfo
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

private val ansiRegex = Regex("\u001B\\[[0-9;]*[a-zA-Z]")

private fun stripAnsi(s: String?): String? {
    if (s == null) return null
    return ansiRegex.replace(s, "")
}

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
        val metadata: JsonObject? = null,
    ) : DisplayItem()
    data class ReasoningItem(val text: String) : DisplayItem()
    data class FileItem(val filename: String?, val mime: String, val url: String) : DisplayItem()
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

private fun isFilePath(text: String): Boolean {
    return text.contains("/") || text.contains("\\") || text.contains(".")
}

@Composable
private fun StartEllipsisText(
    text: String,
    style: TextStyle,
    color: Color,
    modifier: Modifier = Modifier,
    fontFamily: FontFamily? = null,
) {
    val density = LocalDensity.current
    val textMeasurer = rememberTextMeasurer()
    val textStyle = style.copy(color = color, fontFamily = fontFamily)
    val measured = textMeasurer.measure(text, textStyle)
    BoxWithConstraints(modifier = modifier) {
        val maxWidthPx = with(density) { maxWidth.toPx() }.toInt()
        val displayText = if (measured.size.width <= maxWidthPx) {
            text
        } else {
            val paint = TextPaint().apply {
                textSize = with(density) { style.fontSize.toPx() }
                val baseTypeface = style.fontFamily?.toTypeface() ?: Typeface.DEFAULT
                val isBold = style.fontWeight?.weight?.let { it >= 600 } == true
                typeface = if (isBold) Typeface.create(baseTypeface, Typeface.BOLD) else baseTypeface
            }
            TextUtils.ellipsize(text, paint, maxWidthPx.toFloat(), TextUtils.TruncateAt.START)?.toString() ?: text
        }
        Text(text = displayText, style = textStyle, maxLines = 1, overflow = TextOverflow.Clip)
    }
}

private fun FontFamily.toTypeface(): Typeface {
    return when (this) {
        FontFamily.Monospace -> Typeface.MONOSPACE
        FontFamily.SansSerif -> Typeface.SANS_SERIF
        FontFamily.Serif -> Typeface.SERIF
        else -> Typeface.DEFAULT
    }
}

private fun toolSummary(toolName: String, args: String?, result: String?): ToolSummary {
    val jsonArgs = try {
        if (args != null) questionJson.parseToJsonElement(args).jsonObject else null
    } catch (_: Exception) { null }

    when (toolName) {
        "bash" -> {
            val command = jsonArgs.str("command") ?: args?.take(80) ?: ""
            val hasOutput = result != null && result.isNotBlank()
            return ToolSummary("bash", command.ifBlank { "bash" }, hasOutput)
        }
        "glob" -> {
            val pattern = jsonArgs.str("pattern") ?: ""
            val path = jsonArgs.str("path")
            val suffix = if (path != null) " in $path" else ""
            return ToolSummary("glob", "$pattern$suffix".ifBlank { "glob" }, false)
        }
        "read" -> {
            val filePath = jsonArgs.str("filePath") ?: jsonArgs.str("file_path") ?: ""
            return ToolSummary("read", filePath.ifBlank { "read" }, false)
        }
        "grep" -> {
            val pattern = jsonArgs.str("pattern") ?: ""
            val path = jsonArgs.str("path")
            val suffix = if (path != null) " in $path" else ""
            return ToolSummary("grep", "$pattern$suffix".ifBlank { "grep" }, false)
        }
        "webfetch" -> {
            val url = jsonArgs.str("url") ?: ""
            return ToolSummary("webfetch", url.ifBlank { "webfetch" }, false)
        }
        "websearch" -> {
            val query = jsonArgs.str("query") ?: ""
            return ToolSummary("websearch", query.ifBlank { "websearch" }, false)
        }
        "codesearch" -> {
            val query = jsonArgs.str("query") ?: ""
            return ToolSummary("codesearch", query.ifBlank { "codesearch" }, false)
        }
        "write" -> {
            val filePath = jsonArgs.str("filePath") ?: jsonArgs.str("file_path") ?: ""
            val hasContent = jsonArgs?.containsKey("content") == true
            return ToolSummary("write", filePath.ifBlank { "write" }, hasContent)
        }
        "edit" -> {
            val filePath = jsonArgs.str("filePath") ?: jsonArgs.str("file_path") ?: ""
            val hasDiff = jsonArgs?.containsKey("oldString") == true || jsonArgs?.containsKey("newString") == true
            return ToolSummary("edit", filePath.ifBlank { "edit" }, hasDiff)
        }
        "task" -> {
            val desc = jsonArgs.str("description") ?: ""
            val agent = jsonArgs.str("agent") ?: toolName
            return ToolSummary("task", "$agent: $desc", false)
        }
        "apply_patch" -> {
            val hasFiles = result != null
            return ToolSummary("apply_patch", "", hasFiles)
        }
        "todowrite" -> {
            return ToolSummary("todowrite", "", true)
        }
        "question" -> {
            return ToolSummary("question", "", true)
        }
        "skill" -> {
            val name = jsonArgs.str("name") ?: ""
            return ToolSummary("skill", name, false)
        }
        else -> {
            val hasOutput = result != null && result.isNotBlank()
            val paramSnippet = args?.take(60) ?: ""
            return ToolSummary(toolName, paramSnippet, hasOutput)
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

private fun parseTodoArgs(args: String?): List<TodoInfo>? {
    if (args == null) return null
    try {
        val root = questionJson.parseToJsonElement(args).jsonObject
        val todosArr = root["todos"]?.jsonArray ?: return null
        return todosArr.map { elem ->
            val obj = elem.jsonObject
            TodoInfo(
                content = obj.str("content") ?: "",
                status = obj.str("status") ?: "pending",
                priority = obj.str("priority") ?: "medium",
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
                    args = stripAnsi(part.args),
                    result = stripAnsi(part.result),
                    files = nextPatch?.files ?: emptyList(),
                    hash = nextPatch?.hash,
                    callID = part.toolCallID,
                    metadata = part.metadata,
                ))
            }
            is Part.PatchPart -> {
                patches.add(part)
            }
            is Part.StepStartPart -> {
                items.add(DisplayItem.TextItem("▸ step", isUser = false))
            }
            is Part.StepFinishPart -> {}
            is Part.CompactionPart -> {
                items.add(DisplayItem.TextItem("▸ compaction", isUser = false))
            }
            is Part.RetryPart -> {
                items.add(DisplayItem.TextItem("▸ retry #${part.attempt}${part.error?.let { ": $it" } ?: ""}", isUser = false))
            }
            is Part.SnapshotPart -> {}
            is Part.AgentPart -> {
                items.add(DisplayItem.TextItem("▸ agent: ${part.name}", isUser = false))
            }
            is Part.SubtaskPart -> {
                items.add(DisplayItem.TextItem("▸ subtask: ${part.description.ifBlank { part.prompt }}", isUser = false))
            }
            is Part.FilePart -> {
                items.add(DisplayItem.FileItem(part.filename, part.mime, part.url))
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
    pendingPermissions: List<PermissionRequest>,
    onReplyQuestion: (String, List<List<String>>) -> Unit,
    onRejectQuestion: (String) -> Unit,
    onReplyPermission: (String, PermissionReply, String?) -> Unit,
    onNavigateToSubtask: ((subtaskSessionID: String, title: String) -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    val showReasoning = remember { mutableStateOf(true) }
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
                    } else if (item.state == ToolCallState.PENDING || item.state == ToolCallState.RUNNING) {
                        val matchedPerm = item.callID?.let { callID ->
                            pendingPermissions.find { it.tool?.callID == callID }
                        }
                        if (matchedPerm != null) {
                            PermissionCard(
                                request = matchedPerm,
                                onReply = { reply, msg -> onReplyPermission(matchedPerm.id, reply, msg) },
                            )
                        } else if (item.state == ToolCallState.PENDING) {
                            PendingToolLine(item)
                        } else {
                            val summary = toolSummary(item.toolName, item.args, item.result)
                            if (item.toolName == "task" && onNavigateToSubtask != null) {
                                TaskToolLine(item, summary, onNavigateToSubtask)
                            } else {
                                RunningToolLine(item)
                            }
                        }
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
                                .padding(start = 8.dp, top = 4.dp)
                                .clickable { showReasoning.value = !showReasoning.value },
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
                                text = if (showReasoning.value) "▼ Thinking" else "▶ Thinking",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                            )
                        }
                        AnimatedVisibility(visible = showReasoning.value) {
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
                                    text = stringResource(R.string.thinking_prefix, filtered),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                                    modifier = Modifier.weight(1f).padding(end = 8.dp),
                                )
                            }
                        }
                    }
                }
                is DisplayItem.FileItem -> {
                    val displayMime = item.mime.ifBlank { "file" }
                    val displayFilename = item.filename ?: item.url.substringAfterLast("/").substringBefore("?").ifBlank { null }
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
                                text = displayMime,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.primary,
                            )
                        }
                        if (displayFilename != null) {
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
                                    text = displayFilename,
                                    style = MaterialTheme.typography.labelSmall,
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
            style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.SemiBold),
            color = MaterialTheme.colorScheme.primary,
        )
        if (summary.text.isNotBlank()) {
            Spacer(modifier = Modifier.width(4.dp))
            if (isFilePath(summary.text)) {
                StartEllipsisText(
                    text = summary.text,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontFamily = FontFamily.Monospace,
                )
            } else {
                Text(
                    text = summary.text,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
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
            text = summary.icon,
            style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.SemiBold),
            color = MaterialTheme.colorScheme.primary,
        )
        if (summary.text.isNotBlank()) {
            Spacer(modifier = Modifier.width(4.dp))
            if (isFilePath(summary.text)) {
                StartEllipsisText(
                    text = summary.text,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontFamily = FontFamily.Monospace,
                )
            } else {
                Text(
                    text = summary.text,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
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
            style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.SemiBold),
            color = WarningColor,
        )
        Spacer(modifier = Modifier.width(4.dp))
        if (summary.text.isBlank()) {
            Text(text = stringResource(R.string.tool_waiting), style = MaterialTheme.typography.bodySmall, color = WarningColor, maxLines = 1, overflow = TextOverflow.Ellipsis)
        } else if (isFilePath(summary.text)) {
            StartEllipsisText(text = summary.text, style = MaterialTheme.typography.bodySmall, color = WarningColor)
        } else {
            Text(text = summary.text, style = MaterialTheme.typography.bodySmall, color = WarningColor, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

@Composable
private fun ErrorToolLine(item: DisplayItem.ToolItem) {
    val summary = toolSummary(item.toolName, item.args, item.result)
    val expanded = remember { mutableStateOf(false) }
    val textDecoration = if (item.result?.contains("rejected") == true || item.result?.contains("denied") == true) TextDecoration.LineThrough else TextDecoration.None
    Column {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { expanded.value = !expanded.value }
                .padding(vertical = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = summary.icon,
                style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.SemiBold),
                color = MaterialTheme.colorScheme.error,
            )
            if (summary.text.isNotBlank()) {
                Spacer(modifier = Modifier.width(4.dp))
                if (isFilePath(summary.text)) {
                    StartEllipsisText(text = summary.text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                } else {
                    Text(text = summary.text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error, maxLines = 1, overflow = TextOverflow.Ellipsis, textDecoration = textDecoration)
                }
            }
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

private val TaskIdRegex = Regex("task_id:\\s*(ses_\\S+)")

@Composable
private fun TaskToolLine(
    item: DisplayItem.ToolItem,
    summary: ToolSummary,
    onNavigate: (subtaskSessionID: String, title: String) -> Unit,
) {
    val subtaskSessionID = remember(item.metadata, item.result) {
        item.metadata.str("sessionId")
            ?: item.result?.let { TaskIdRegex.find(it)?.groupValues?.getOrNull(1) }
    }
    val isRunning = item.state == ToolCallState.RUNNING
    val canNavigate = subtaskSessionID != null

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .then(if (canNavigate) Modifier.clickable { onNavigate(subtaskSessionID!!, summary.text) } else Modifier)
            .padding(vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (isRunning) {
            CircularProgressIndicator(
                modifier = Modifier.size(14.dp),
                strokeWidth = 2.dp,
            )
            Spacer(modifier = Modifier.width(6.dp))
        }
        Text(
            text = stringResource(R.string.subtask_label),
            style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.SemiBold),
            color = AgentColor,
        )
        if (summary.text.isNotBlank()) {
            Spacer(modifier = Modifier.width(4.dp))
            if (isFilePath(summary.text)) {
                StartEllipsisText(text = summary.text, style = MaterialTheme.typography.bodySmall, color = AgentColor)
            } else {
                Text(text = summary.text, style = MaterialTheme.typography.bodySmall, color = AgentColor, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        if (canNavigate) {
            Spacer(modifier = Modifier.width(4.dp))
            Text(
                text = "→",
                style = MaterialTheme.typography.labelSmall,
                color = AgentColor,
            )
}
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
                style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.SemiBold),
                color = MaterialTheme.colorScheme.primary,
            )
            if (summary.text.isNotBlank()) {
                Spacer(modifier = Modifier.width(4.dp))
                if (isFilePath(summary.text)) {
                    StartEllipsisText(
                        text = summary.text,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontFamily = FontFamily.Monospace,
                    )
                } else {
                    Text(
                        text = summary.text,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
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
                                text = stringResource(R.string.tool_cwd, workdir),
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
                    } else if (item.toolName == "edit") {
                        val jsonArgs = try {
                            if (item.args != null) questionJson.parseToJsonElement(item.args).jsonObject else null
                        } catch (_: Exception) { null }
                        val filePath = jsonArgs?.str("filePath") ?: jsonArgs?.str("file_path") ?: ""
                        if (filePath.isNotBlank()) {
                            Text(
                                text = filePath,
                                style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Medium),
                                color = MaterialTheme.colorScheme.onSurface,
                                softWrap = true,
                            )
                            Spacer(modifier = Modifier.height(4.dp))
                        }
                        val oldStr = jsonArgs?.str("oldString") ?: jsonArgs?.str("old_string")
                        val newStr = jsonArgs?.str("newString") ?: jsonArgs?.str("new_string")
                        if (oldStr != null) {
                            Text(
                                text = "- $oldStr",
                                style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                                color = MaterialTheme.colorScheme.error,
                                maxLines = 8,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                        if (newStr != null) {
                            Text(
                                text = "+ $newStr",
                                style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                                color = Color(0xFF7FD88F),
                                maxLines = 8,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                        if (oldStr == null && newStr == null) {
                            item.result?.let { output ->
                                Text(
                                    text = output.take(500),
                                    style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                                    color = MaterialTheme.colorScheme.onSurface,
                                    maxLines = 10,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                        }
                    } else if (item.toolName == "write") {
                        val jsonArgs = try {
                            if (item.args != null) questionJson.parseToJsonElement(item.args).jsonObject else null
                        } catch (_: Exception) { null }
                        val filePath = jsonArgs?.str("filePath") ?: jsonArgs?.str("file_path") ?: ""
                        if (filePath.isNotBlank()) {
                            Text(
                                text = filePath,
                                style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Medium),
                                color = MaterialTheme.colorScheme.onSurface,
                                softWrap = true,
                            )
                            Spacer(modifier = Modifier.height(4.dp))
                        }
                        val content = jsonArgs?.str("content")
                        if (content != null) {
                            Text(
                                text = content.take(800),
                                style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                                color = MaterialTheme.colorScheme.onSurface,
                                maxLines = 15,
                                overflow = TextOverflow.Ellipsis,
                            )
                        } else {
                            item.result?.let { output ->
                                Text(
                                    text = output.take(500),
                                    style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                                    color = MaterialTheme.colorScheme.onSurface,
                                    maxLines = 10,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                        }
                    } else if (item.toolName == "todowrite") {
                        val todos = parseTodoArgs(item.args)
                        if (todos != null) {
                            todos.forEach { todo ->
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text(
                                        text = when (todo.status) {
                                            "completed" -> "✓"
                                            "in_progress" -> "●"
                                            "cancelled" -> "✗"
                                            else -> "○"
                                        },
                                        style = MaterialTheme.typography.bodySmall,
                                        color = when (todo.status) {
                                            "completed" -> Color(0xFF7FD88F)
                                            "in_progress" -> Color(0xFF56B6C2)
                                            else -> Color(0xFF808080)
                                        },
                                    )
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text(
                                        text = todo.content,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = if (todo.status == "completed" || todo.status == "cancelled") Color(0xFF808080) else MaterialTheme.colorScheme.onSurface,
                                        maxLines = 2,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                }
                            }
                        } else {
                            item.result?.let { output ->
                                Text(
                                    text = output.take(500),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurface,
                                )
                            }
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
                    text = stringResource(R.string.waiting_response),
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
                            Text(stringResource(R.string.reject))
                        }
                    }
                    Spacer(modifier = Modifier.width(8.dp))
                    Button(
                        onClick = { onReply?.invoke(selectedAnswers.value.values.toList()) },
                        enabled = selectedAnswers.value.isNotEmpty(),
                    ) {
                        Text(stringResource(R.string.submit))
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
                text = stringResource(R.string.permission_request),
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
                    modifier = Modifier.fillMaxWidth(),
                    maxLines = 10,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (request.patterns.isNotEmpty()) {
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = request.patterns.joinToString("\n"),
                    style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.fillMaxWidth(),
                    maxLines = 5,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Spacer(modifier = Modifier.height(12.dp))
            Row {
                Button(onClick = { onReply(PermissionReply.ONCE, null) }) {
                    Text(stringResource(R.string.allow))
                }
                Spacer(modifier = Modifier.width(8.dp))
                if (request.always.isNotEmpty()) {
                    OutlinedButton(onClick = { onReply(PermissionReply.ALWAYS, null) }) {
                        Text(stringResource(R.string.always))
                    }
                    Spacer(modifier = Modifier.width(8.dp))
                }
                OutlinedButton(onClick = { onReply(PermissionReply.REJECT, null) }) {
                    Text(stringResource(R.string.deny))
                }
            }
        }
    }
}
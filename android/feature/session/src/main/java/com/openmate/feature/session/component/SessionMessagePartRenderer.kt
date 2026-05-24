package com.openmate.feature.session.component

import android.graphics.Typeface
import android.text.TextPaint
import android.text.TextUtils
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
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
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.openmate.core.domain.model.PermissionReply
import com.openmate.core.domain.model.PermissionRequest
import com.openmate.core.domain.model.QuestionInfo
import com.openmate.core.domain.model.QuestionOption
import com.openmate.core.domain.model.QuestionRequest
import com.openmate.core.domain.model.TodoInfo
import com.openmate.core.domain.model.ToolCallState
import com.openmate.feature.session.R
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject

private val WarningColor = Color(0xFFFFA500)
private val AgentColor = Color(0xFF9D7CD8)
private val AnsiEscapeRegex = Regex("""\u001B\[[0-9;]*[A-Za-z]|\u001B\].*?\u0007|\x1b\[[0-9;]*[A-Za-z]|\x9B[0-9;]*[A-Za-z]""")

internal fun stripAnsi(text: String): String = AnsiEscapeRegex.replace(text, "")
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
        val metadata: JsonObject? = null,
    ) : DisplayItem()
    data class ReasoningItem(val text: String) : DisplayItem()
    data class FileItem(val filename: String?, val mime: String, val url: String) : DisplayItem()
}

internal data class ToolSummary(
    val icon: String,
    val text: String,
    val isBlock: Boolean,
    val filePath: String? = null,
)

private val ApplyPatchFileLineRegex = Regex("""(?m)^(?:[AMDCRTU?!]{1,2}|M)\s+(.+)$""")

internal fun extractApplyPatchResultFiles(result: String?): List<String> {
    if (result.isNullOrBlank()) return emptyList()
    return ApplyPatchFileLineRegex
        .findAll(result)
        .mapNotNull { match -> match.groupValues.getOrNull(1)?.trim()?.ifBlank { null } }
        .toList()
}

internal data class QuestionAnswerRow(
    val question: String,
    val answers: List<String>,
)

private fun JsonObject?.str(key: String): String? =
    this?.get(key)?.let { if (it is JsonPrimitive) it.content else null }

private fun JsonObject.bool(key: String): Boolean =
    (this[key] as? JsonPrimitive)?.boolean ?: false

internal fun isFilePath(text: String): Boolean {
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

internal fun toolSummary(toolName: String, args: String?, result: String?): ToolSummary {
    val jsonArgs = try {
        if (args != null) questionJson.parseToJsonElement(args).jsonObject else null
    } catch (_: Exception) { null }

    when (toolName) {
        "bash" -> {
            val command = jsonArgs.str("command") ?: args?.take(80) ?: ""
            val desc = jsonArgs.str("description") ?: ""
            val hasOutput = result != null && result.isNotBlank()
            val displayText = desc.ifBlank { command }.ifBlank { "shell" }
            return ToolSummary("shell", displayText, hasOutput)
        }
        "glob" -> {
            val pattern = jsonArgs.str("pattern") ?: ""
            val path = jsonArgs.str("path")
            val suffix = if (path != null) " in $path" else ""
            return ToolSummary("glob", "$pattern$suffix".ifBlank { "glob" }, false)
        }
        "read" -> {
            val fp = jsonArgs.str("filePath") ?: jsonArgs.str("file_path") ?: ""
            return ToolSummary("read", fp.ifBlank { "read" }, false, fp.ifBlank { null })
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
            val fp = jsonArgs.str("filePath") ?: jsonArgs.str("file_path") ?: ""
            return ToolSummary("write", fp.ifBlank { "write" }, fp.isNotBlank(), fp.ifBlank { null })
        }
        "edit" -> {
            val fp = jsonArgs.str("filePath") ?: jsonArgs.str("file_path") ?: ""
            return ToolSummary("edit", fp.ifBlank { "edit" }, true, fp.ifBlank { null })
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

internal fun parseQuestionArgs(args: String?): List<QuestionInfo>? {
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
                custom = !obj.containsKey("custom") || obj.bool("custom"),
            )
        }
    } catch (_: Exception) {
        return null
    }
}

internal fun extractQuestionAnswers(metadata: JsonObject?): List<List<String>>? {
    val answers = metadata?.get("answers")?.jsonArray ?: return null
    return answers.map { answer ->
        answer.jsonArray.mapNotNull { item ->
            (item as? JsonPrimitive)?.contentOrNull
        }
    }
}

internal fun buildQuestionAnswerRows(
    questions: List<QuestionInfo>,
    answers: List<List<String>>,
): List<QuestionAnswerRow> {
    return questions.mapIndexed { index, info ->
        QuestionAnswerRow(
            question = info.question,
            answers = answers.getOrNull(index).orEmpty(),
        )
    }
}

internal fun isDismissedQuestionError(error: String?): Boolean {
    val cleaned = error?.removePrefix("Error: ")?.trim().orEmpty()
    return cleaned.contains("dismissed this question", ignoreCase = true)
}

internal fun shouldExpandRunningTool(item: DisplayItem.ToolItem): Boolean {
    return item.toolName == "bash" && !toolSummary(item.toolName, item.args, item.result).text.isBlank()
}

private fun formatQuestionAnswers(answers: List<String>): String {
    return if (answers.isEmpty()) "Unanswered" else answers.joinToString(", ")
}

private fun updateQuestionAnswers(
    current: Map<Int, List<String>>,
    questionIndex: Int,
    questionInfo: QuestionInfo,
    optionLabel: String,
): Map<Int, List<String>> {
    val next = current.toMutableMap()
    val existing = next[questionIndex].orEmpty().toMutableList()
    if (questionInfo.multiple) {
        if (existing.contains(optionLabel)) existing.remove(optionLabel) else existing.add(optionLabel)
        if (existing.isEmpty()) next.remove(questionIndex) else next[questionIndex] = existing
    } else {
        next[questionIndex] = listOf(optionLabel)
    }
    return next
}

private fun updateQuestionCustomAnswer(
    current: Map<Int, List<String>>,
    questionIndex: Int,
    questionInfo: QuestionInfo,
    previousCustom: String,
    customText: String,
): Map<Int, List<String>> {
    val trimmed = customText.trim()
    val next = current.toMutableMap()
    val existing = next[questionIndex].orEmpty().toMutableList().apply {
        if (previousCustom.isNotBlank()) remove(previousCustom)
    }
    if (trimmed.isNotBlank()) {
        if (questionInfo.multiple) {
            existing.add(trimmed)
            next[questionIndex] = existing.distinct()
        } else {
            next[questionIndex] = listOf(trimmed)
        }
    } else if (existing.isNotEmpty()) {
        next[questionIndex] = existing
    } else {
        next.remove(questionIndex)
    }
    return next
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

@Composable
internal fun InlineToolLine(item: DisplayItem.ToolItem, onViewFile: ((filePath: String) -> Unit)? = null) {
    val summary = toolSummary(item.toolName, item.args, item.result)
    val clickablePath = summary.filePath
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
            if (clickablePath != null) {
                StartEllipsisText(
                    text = clickablePath,
                    style = MaterialTheme.typography.bodySmall,
                    color = if (onViewFile != null) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                    fontFamily = FontFamily.Monospace,
                    modifier = if (onViewFile != null) Modifier.clickable { onViewFile!!.invoke(clickablePath) } else Modifier,
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
internal fun RunningToolLine(item: DisplayItem.ToolItem, onViewFile: ((filePath: String) -> Unit)? = null) {
    val summary = toolSummary(item.toolName, item.args, item.result)
    val clickablePath = summary.filePath
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
            if (clickablePath != null) {
                StartEllipsisText(
                    text = clickablePath,
                    style = MaterialTheme.typography.bodySmall,
                    color = if (onViewFile != null) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                    fontFamily = FontFamily.Monospace,
                    modifier = if (onViewFile != null) Modifier.clickable { onViewFile!!.invoke(clickablePath) } else Modifier,
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
internal fun PendingToolLine(item: DisplayItem.ToolItem) {
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
internal fun ErrorToolLine(item: DisplayItem.ToolItem) {
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
                        text = stripAnsi(err).take(500),
                        style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
        }
    }
}

@Composable
internal fun CompletedQuestionToolCard(
    questions: List<QuestionInfo>,
    answers: List<List<String>>,
    modifier: Modifier = Modifier,
) {
    val rows = remember(questions, answers) { buildQuestionAnswerRows(questions, answers) }
    Card(
        modifier = modifier.padding(vertical = 4.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = stringResource(R.string.question),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.primary,
            )
            Spacer(modifier = Modifier.height(8.dp))
            rows.forEachIndexed { index, row ->
                Text(
                    text = row.question,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = formatQuestionAnswers(row.answers),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (index != rows.lastIndex) {
                    Spacer(modifier = Modifier.height(12.dp))
                }
            }
        }
    }
}

@Composable
internal fun DismissedQuestionToolLine() {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.End,
    ) {
        Text(
            text = stringResource(R.string.question_dismissed),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
internal fun TaskToolLine(
    item: DisplayItem.ToolItem,
    summary: ToolSummary,
    subtaskSessionID: String? = null,
    onNavigate: (subtaskSessionID: String, title: String) -> Unit,
    subtaskPermissions: List<PermissionRequest> = emptyList(),
    subtaskQuestions: List<QuestionRequest> = emptyList(),
    onReplyPermission: (String, PermissionReply, String?) -> Unit = { _, _, _ -> },
    onReplyQuestion: (String, List<List<String>>) -> Unit = { _, _ -> },
    onRejectQuestion: (String) -> Unit = {},
) {
    val resolvedSubtaskSessionID = subtaskSessionID ?: remember(item.metadata, item.result) {
        extractSubtaskSessionId(
            metadata = item.metadata,
            structured = null,
            resultText = item.result,
        )
    }
    val isRunning = item.state == ToolCallState.RUNNING
    val canNavigate = resolvedSubtaskSessionID != null

    Column {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .then(if (canNavigate) Modifier.clickable { onNavigate(resolvedSubtaskSessionID!!, summary.text) } else Modifier)
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

        if (isRunning) {
            subtaskPermissions.forEach { perm ->
                PermissionCard(
                    request = perm,
                    onReply = { reply, msg -> onReplyPermission(perm.id, reply, msg) },
                )
            }
            subtaskQuestions.forEach { question ->
                QuestionCard(
                    questions = question.questions,
                    matchedRequest = question,
                    onReply = { answers -> onReplyQuestion(question.id, answers) },
                    onReject = { onRejectQuestion(question.id) },
                )
            }
        }
    }
}

@Composable
internal fun BlockToolLine(item: DisplayItem.ToolItem, summary: ToolSummary, onViewFile: ((filePath: String) -> Unit)? = null) {
    val expanded = remember { mutableStateOf(false) }
    val files = if (item.toolName == "apply_patch" && item.files.isEmpty()) {
        extractApplyPatchResultFiles(item.result)
    } else {
        item.files
    }

    val canViewFile = onViewFile != null && summary.filePath != null

    Column(modifier = Modifier.padding(vertical = 2.dp)) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
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
                if (summary.filePath != null) {
                    StartEllipsisText(
                        text = summary.filePath,
                        style = MaterialTheme.typography.bodySmall,
                        color = if (canViewFile) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                        fontFamily = FontFamily.Monospace,
                        modifier = if (canViewFile) Modifier.clickable { onViewFile!!.invoke(summary.filePath) } else Modifier,
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
                modifier = Modifier.clickable { expanded.value = !expanded.value },
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
                            val cleaned = stripAnsi(output)
                            if (cleaned.isNotBlank()) {
                                Spacer(modifier = Modifier.height(4.dp))
                                Text(
                                    text = cleaned.take(500),
                                    style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                                    color = MaterialTheme.colorScheme.onSurface,
                                    maxLines = 10,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
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
                                color = if (onViewFile != null) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                                softWrap = true,
                                modifier = if (onViewFile != null) Modifier.clickable { onViewFile.invoke(filePath) } else Modifier,
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
                                    text = stripAnsi(output).take(500),
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
                                color = if (onViewFile != null) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                                softWrap = true,
                                modifier = if (onViewFile != null) Modifier.clickable { onViewFile.invoke(filePath) } else Modifier,
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
                                    text = stripAnsi(output).take(500),
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
                                text = stripAnsi(output).take(500),
                                style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                                color = MaterialTheme.colorScheme.onSurface,
                                maxLines = 3,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                    if (files.isNotEmpty()) {
                        Spacer(modifier = Modifier.height(4.dp))
                        files.forEach { file ->
                            Text(
                                text = file,
                                style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                                color = if (onViewFile != null) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = if (onViewFile != null) Modifier.clickable { onViewFile.invoke(file) } else Modifier,
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
    val customAnswers = remember { mutableStateOf(emptyMap<Int, String>()) }
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
                                    selectedAnswers.value = updateQuestionAnswers(
                                        current = selectedAnswers.value,
                                        questionIndex = qIndex,
                                        questionInfo = questionInfo,
                                        optionLabel = option.label,
                                    )
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
                    if (questionInfo.custom) {
                        val customValue = customAnswers.value[qIndex].orEmpty()
                        OutlinedTextField(
                            value = customValue,
                            onValueChange = { value ->
                                if (canInteract) {
                                    val previous = customAnswers.value[qIndex].orEmpty()
                                    customAnswers.value = customAnswers.value.toMutableMap().apply {
                                        this[qIndex] = value
                                    }
                                    selectedAnswers.value = updateQuestionCustomAnswer(
                                        current = selectedAnswers.value,
                                        questionIndex = qIndex,
                                        questionInfo = questionInfo,
                                        previousCustom = previous,
                                        customText = value,
                                    )
                                }
                            },
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(top = 6.dp),
                            enabled = canInteract,
                            singleLine = !questionInfo.multiple,
                            label = { Text(stringResource(R.string.question_custom_answer)) },
                            placeholder = { Text(stringResource(R.string.question_custom_answer_placeholder)) },
                        )
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
                        onClick = {
                            onReply?.invoke(
                                questions.indices.map { index -> selectedAnswers.value[index].orEmpty() },
                            )
                        },
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
    val description = (request.metadata["description"] as? JsonPrimitive)?.content
        ?: (request.metadata["command"] as? JsonPrimitive)?.content

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

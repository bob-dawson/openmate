package com.openmate.feature.session.component

import android.os.SystemClock
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import com.openmate.core.domain.model.SessionMessage
import com.openmate.core.domain.model.PermissionReply
import com.openmate.core.domain.model.PermissionRequest
import com.openmate.core.domain.model.QuestionRequest
import com.openmate.core.ui.component.MessageBubble
import com.openmate.feature.session.R
import com.openmate.core.common.formatDurationMillis
import com.openmate.core.common.toTimeString
import dev.jeziellago.compose.markdowntext.MarkdownText
import kotlinx.coroutines.delay
import com.openmate.core.domain.model.ToolCallState
import kotlinx.serialization.json.*
import kotlinx.serialization.json.put

private val AgentColor = androidx.compose.ui.graphics.Color(0xFF9D7CD8)
private val CompactionCodeBlockBackground = androidx.compose.ui.graphics.Color(0xFF2a2a3a)
private val CompactionCodeBlockText = androidx.compose.ui.graphics.Color(0xFFe0e0f0)
private val TaskIdRegex = Regex("task_id:\\s*(ses_\\S+)")

private fun JsonObject?.str(key: String): String? =
    this?.get(key)?.let { if (it is JsonPrimitive) it.content else null }

internal fun extractSubtaskSessionId(
    metadata: JsonObject?,
    structured: JsonObject?,
    resultText: String?,
): String? {
    return metadata.str("sessionId")
        ?: metadata.str("sessionID")
        ?: structured.str("sessionId")
        ?: structured.str("sessionID")
        ?: resultText?.let { TaskIdRegex.find(it)?.groupValues?.getOrNull(1) }
}

internal fun extractToolItems(data: JsonObject, sessionId: String = "", messageId: String = ""): List<DisplayItem.ToolItem> {
    val content = data["content"]?.jsonArray ?: return emptyList()
    val items = mutableListOf<DisplayItem.ToolItem>()
    val patches = mutableListOf<List<String>>()
    var index = 0
    while (index < content.size) {
        val obj = content[index].jsonObject
        when (obj["type"]?.jsonPrimitive?.contentOrNull) {
            "tool" -> {
                val name = obj["name"]?.jsonPrimitive?.contentOrNull ?: "tool"
                val state = obj["state"]?.jsonObject
                val status = state?.get("status")?.jsonPrimitive?.contentOrNull ?: ""
                val input = state?.get("input")?.toString()
                val structuredResult = state?.get("structured")
                val contentArr = state?.get("content")?.jsonArray
                val errorObj = state?.get("error")
                val callID = obj["callID"]?.jsonPrimitive?.contentOrNull
                    ?: state?.get("callID")?.jsonPrimitive?.contentOrNull
                    ?: obj["id"]?.jsonPrimitive?.contentOrNull
                val metadata = state?.get("metadata")?.jsonObject
                val structuredSessionId = (structuredResult as? JsonObject)?.str("sessionId")
                    ?: (structuredResult as? JsonObject)?.str("sessionID")
                val mergedMetadata = if (metadata != null) {
                    if (structuredSessionId != null && metadata.str("sessionId") == null && metadata.str("sessionID") == null) {
                        buildJsonObject {
                            metadata.forEach { (key, value) -> put(key, value) }
                            put("sessionId", JsonPrimitive(structuredSessionId))
                        }
                    } else {
                        metadata
                    }
                } else if (structuredSessionId != null) {
                    buildJsonObject { put("sessionId", JsonPrimitive(structuredSessionId)) }
                } else {
                    null
                }
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
                val nextPatchFiles = if (index + 1 < content.size) {
                    content[index + 1].jsonObject.takeIf {
                        it["type"]?.jsonPrimitive?.contentOrNull == "patch"
                    }?.get("files")?.jsonArray?.mapNotNull { it.jsonPrimitive.contentOrNull }
                } else {
                    null
                }
                items.add(
                    DisplayItem.ToolItem(
                        toolName = name,
                        state = when (status) {
                            "pending" -> ToolCallState.PENDING
                            "running" -> ToolCallState.RUNNING
                            "completed" -> ToolCallState.COMPLETED
                            "error" -> ToolCallState.ERROR
                            else -> ToolCallState.COMPLETED
                        },
                        args = input,
                        result = resultText,
                        files = nextPatchFiles ?: patches.removeFirstOrNull() ?: emptyList(),
                        hash = null,
                        callID = callID,
                        metadata = mergedMetadata,
                        sessionId = sessionId,
                        messageId = messageId,
                    ),
                )
                if (nextPatchFiles != null) {
                    index++
                }
            }
            "patch" -> {
                val files = obj["files"]?.jsonArray?.mapNotNull { it.jsonPrimitive.contentOrNull } ?: emptyList()
                patches.add(files)
            }
        }
        index++
    }
    return items
}

@Composable
fun SessionMessageRenderer(
    entity: SessionMessage,
    showReasoning: Boolean = true,
    compactMode: Boolean = false,
    isQueued: Boolean = false,
    userModelName: String? = null,
    onFullContentRequest: (messageId: String) -> Unit,
    onNavigateToSubtask: (subtaskSessionID: String, title: String) -> Unit = { _, _ -> },
    pendingQuestions: List<QuestionRequest> = emptyList(),
    pendingPermissions: List<PermissionRequest> = emptyList(),
    onReplyQuestion: (String, List<List<String>>) -> Unit = { _, _ -> },
    onRejectQuestion: (String) -> Unit = {},
    onReplyPermission: (String, PermissionReply, String?) -> Unit = { _, _, _ -> },
    runningAnchors: Map<String, Long> = emptyMap(),
    onViewFile: ((filePath: String) -> Unit)? = null,
    onViewDiff: ((sessionId: String, messageId: String, toolName: String, filePath: String?) -> Unit)? = null,
    onRevertToMessage: (String) -> Unit = {},
) {
    val dataJson = remember(entity.data) {
        runCatching { Json.parseToJsonElement(entity.data).jsonObject }.getOrNull()
    } ?: return

    when (entity.type) {
        "user" -> {
            val hasText = dataJson["text"]?.jsonPrimitive?.contentOrNull?.isNotBlank() == true
            val hasFiles = dataJson["files"]?.jsonArray?.isNotEmpty() == true || dataJson["content"]?.jsonArray?.any { it.jsonObject["type"]?.jsonPrimitive?.contentOrNull == "file" } == true
            if (!hasText && !hasFiles) return
            Column(modifier = Modifier.fillMaxWidth()) {
                UserMessageItem(
                    data = dataJson,
                    messageID = entity.id,
                    onRevertToMessage = onRevertToMessage,
                )
                MessageMetadata(
                    messageId = entity.id,
                    timeCreated = entity.timeCreated,
                    completedAt = null,
                    modelName = userModelName,
                    isQueued = isQueued,
                    runningAnchors = runningAnchors,
                )
            }
        }
        "assistant" -> {
            val content = dataJson["content"]?.jsonArray
            val errorMessage = extractAssistantErrorMessage(dataJson)
            val hasVisible = content?.any { item ->
                val obj = item.jsonObject
                val type = obj["type"]?.jsonPrimitive?.contentOrNull
                when (type) {
                    "text" -> obj["text"]?.jsonPrimitive?.contentOrNull?.isNotBlank() == true
                    "tool" -> !compactMode
                    "reasoning" -> !compactMode && obj["text"]?.jsonPrimitive?.contentOrNull?.isNotBlank() == true
                    "file" -> true
                    "step-start", "step-finish", "agent", "subtask", "compaction", "retry" -> !compactMode
                    else -> false
                }
            } == true || errorMessage != null
            if (!hasVisible) return
            val modelObj = dataJson["model"]?.jsonObject
            val modelName = modelObj?.get("name")?.jsonPrimitive?.contentOrNull
                ?: modelObj?.get("id")?.jsonPrimitive?.contentOrNull
            val finish = dataJson["finish"]?.jsonPrimitive?.contentOrNull
            val isStepRunning = entity.completedAt == null && finish == null
            val toolCount = if (compactMode) 0 else content?.count { it.jsonObject["type"]?.jsonPrimitive?.contentOrNull == "tool" } ?: 0
            Column(modifier = Modifier.fillMaxWidth()) {
                AssistantMessageItem(
                    data = dataJson,
                    sessionId = entity.sessionId,
                    messageId = entity.id,
                    showReasoning = showReasoning,
                    compactMode = compactMode,
                    onNavigateToSubtask = onNavigateToSubtask,
                    pendingQuestions = pendingQuestions,
                    pendingPermissions = pendingPermissions,
                    onReplyQuestion = onReplyQuestion,
                    onRejectQuestion = onRejectQuestion,
                    onReplyPermission = onReplyPermission,
                    onViewFile = onViewFile,
                    onViewDiff = onViewDiff,
                )
                if (errorMessage != null) {
                    AssistantErrorCard(errorMessage)
                }
                MessageMetadata(
                    messageId = entity.id,
                    timeCreated = entity.timeCreated,
                    completedAt = entity.completedAt,
                    modelName = modelName,
                    isQueued = false,
                    isStepRunning = isStepRunning,
                    toolCount = toolCount,
                    finish = finish,
                    runningAnchors = runningAnchors,
                )
            }
        }
        "compaction" -> {
            CompactionMessageItem(
                entity = entity,
                data = dataJson,
                runningAnchors = runningAnchors,
            )
        }
        "synthetic" -> { }
        else -> { }
    }
}

@Composable
private fun CompactionMessageItem(
    entity: SessionMessage,
    data: JsonObject,
    runningAnchors: Map<String, Long>,
) {
    val summary = data["summary"]?.jsonPrimitive?.contentOrNull.orEmpty()
    val isRunning = entity.completedAt == null
    var expanded by remember(entity.id) { mutableStateOf(false) }

    Column(modifier = Modifier.fillMaxWidth()) {
        Text(
            text = if (expanded) "▾ compaction" else "▸ compaction",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier
                .padding(horizontal = 4.dp, vertical = 3.dp)
                .clickable(enabled = summary.isNotBlank()) { expanded = !expanded },
        )
        MessageMetadata(
            messageId = entity.id,
            timeCreated = entity.timeCreated,
            completedAt = entity.completedAt,
            modelName = null,
            isQueued = false,
            isStepRunning = isRunning,
            runningAnchors = runningAnchors,
        )
        AnimatedVisibility(visible = expanded && summary.isNotBlank()) {
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 4.dp, vertical = 4.dp),
            ) {
                MarkdownText(
                    markdown = summary,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 10.dp),
                    style = MaterialTheme.typography.bodySmall.copy(
                        color = MaterialTheme.colorScheme.onSurface,
                    ),
                    syntaxHighlightColor = CompactionCodeBlockBackground,
                    syntaxHighlightTextColor = CompactionCodeBlockText,
                    isTextSelectable = true,
                )
            }
        }
    }
}

internal fun extractAssistantErrorMessage(data: JsonObject): String? {
    val error = data["error"] ?: return null
    if (error is JsonPrimitive && error.isString) {
        return error.content.takeIf { it.isNotBlank() }
    }
    val errorObject = error.jsonObject
    val direct = errorObject["message"]?.jsonPrimitive?.contentOrNull
    if (!direct.isNullOrBlank()) return direct
    val nested = errorObject["data"]?.jsonObject?.get("message")?.jsonPrimitive?.contentOrNull
    return nested?.takeIf { it.isNotBlank() }
}

@Composable
private fun MessageMetadata(
    messageId: String,
    timeCreated: Long,
    completedAt: Long?,
    modelName: String?,
    isQueued: Boolean,
    isStepRunning: Boolean = false,
    toolCount: Int = 0,
    finish: String? = null,
    runningAnchors: Map<String, Long> = emptyMap(),
) {
    if (timeCreated <= 0) return
    val timeText = timeCreated.toTimeString()

    val durationText = if (isStepRunning) {
        val phoneAnchor = runningAnchors[messageId] ?: SystemClock.elapsedRealtime()
        var elapsed by remember { mutableStateOf(SystemClock.elapsedRealtime() - phoneAnchor) }
        LaunchedEffect(phoneAnchor) {
            while (true) {
                delay(1000)
                elapsed = SystemClock.elapsedRealtime() - phoneAnchor
            }
        }
        formatDurationMillis(elapsed)
    } else if (completedAt != null && completedAt > timeCreated) {
        formatDurationMillis(completedAt - timeCreated)
    } else ""

    val modelText = modelName?.let { " · $it" } ?: ""
    val toolText = if (toolCount > 0) " · $toolCount 工具" else ""
    val finishText = when {
        isStepRunning -> ""
        finish == "tool-calls" -> " · 继续执行"
        finish == "error" -> " · 出错"
        finish == "stop" -> ""
        else -> ""
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 4.dp, top = 2.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (isStepRunning) {
            CircularProgressIndicator(
                modifier = Modifier.size(10.dp),
                strokeWidth = 1.5.dp,
            )
            Spacer(modifier = Modifier.width(4.dp))
        }
        if (isQueued) {
            QueuedBadge()
            Spacer(modifier = Modifier.width(6.dp))
        }
        Text(
            text = timeText + if (durationText.isNotBlank()) " · $durationText" else "" + toolText + finishText + modelText,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun AssistantErrorCard(message: String) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 4.dp, vertical = 4.dp),
    ) {
        Text(
            text = message,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.error,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
        )
    }
}

@Composable
fun UserMessageItem(
    data: JsonObject,
    messageID: String,
    onRevertToMessage: (String) -> Unit = {},
) {
    var showMenu by remember { mutableStateOf(false) }
    val clipboardManager = LocalClipboardManager.current
    val text = data["text"]?.jsonPrimitive?.contentOrNull ?: ""

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .combinedClickable(
                onClick = {},
                onLongClick = { showMenu = true },
            ),
    ) {
        Column {
            if (text.isNotBlank()) {
                MessageBubble(text = text, isUser = true, isTextSelectable = false, modifier = Modifier.fillMaxWidth())
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
        DropdownMenu(
            expanded = showMenu,
            onDismissRequest = { showMenu = false },
        ) {
            DropdownMenuItem(
                text = { Text("Copy") },
                onClick = {
                    if (text.isNotBlank()) {
                        clipboardManager.setText(AnnotatedString(text))
                    }
                    showMenu = false
                },
            )
            DropdownMenuItem(
                text = { Text("回滚至此") },
                onClick = {
                    onRevertToMessage(messageID)
                    showMenu = false
                },
            )
        }
    }
}

@Composable
fun AssistantMessageItem(
    data: JsonObject,
    sessionId: String = "",
    messageId: String = "",
    showReasoning: Boolean = true,
    compactMode: Boolean = false,
    onNavigateToSubtask: (String, String) -> Unit = { _, _ -> },
    pendingQuestions: List<QuestionRequest> = emptyList(),
    pendingPermissions: List<PermissionRequest> = emptyList(),
    onReplyQuestion: (String, List<List<String>>) -> Unit = { _, _ -> },
    onRejectQuestion: (String) -> Unit = {},
    onReplyPermission: (String, PermissionReply, String?) -> Unit = { _, _, _ -> },
    onViewFile: ((filePath: String) -> Unit)? = null,
    onViewDiff: ((sessionId: String, messageId: String, toolName: String, filePath: String?) -> Unit)? = null,
) {
    val content = data["content"]?.jsonArray ?: return
    val reasoningExpanded = remember { mutableStateOf(false) }
    val toolItems = remember(data) { extractToolItems(data, sessionId, messageId) }
    var toolIndex = 0

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
                    val displayItem = toolItems.getOrNull(toolIndex++) ?: continue
                    val name = displayItem.toolName
                    val status = when (displayItem.state) {
                        ToolCallState.PENDING -> "pending"
                        ToolCallState.RUNNING -> "running"
                        ToolCallState.COMPLETED -> "completed"
                        ToolCallState.ERROR -> "error"
                    }
                    if (compactMode) {
                        val isInteractiveQuestion = name == "question" && status == "running"
                        val isPendingPermission = status in listOf("pending", "running") &&
                            displayItem.callID?.let { cid -> pendingPermissions.any { it.tool?.callID == cid } } == true
                        if (!isInteractiveQuestion && !isPendingPermission) continue
                    }
                    val input = displayItem.args
                    val resultText = displayItem.result
                    val metadata = displayItem.metadata
                    val callID = displayItem.callID
                    val structuredResult = runCatching {
                        displayItem.result?.let { Json.parseToJsonElement(it).jsonObject }
                    }.getOrNull()

                    if (status == "pending" || status == "running") {
                        val matchedQ = callID?.let { cid ->
                            pendingQuestions.find { it.tool?.callID == cid }
                        }
                        if (matchedQ != null) {
                            QuestionCard(
                                questions = matchedQ.questions,
                                matchedRequest = matchedQ,
                                onReply = { answers -> onReplyQuestion(matchedQ.id, answers) },
                                onReject = { onRejectQuestion(matchedQ.id) },
                            )
                        } else if (name == "question" && status == "running") {
                            val parsedQuestions = remember(input) { parseQuestionArgs(input) }
                            if (parsedQuestions != null) {
                                QuestionCard(
                                    questions = parsedQuestions,
                                    matchedRequest = null,
                                    onReply = null,
                                    onReject = null,
                                )
                            } else {
                                InlineToolLine(displayItem, onViewFile)
                            }
                        } else {
                            val matchedPerm = callID?.let { cid ->
                                pendingPermissions.find { it.tool?.callID == cid }
                            }
                            if (matchedPerm != null) {
                                PermissionCard(
                                    request = matchedPerm,
                                    onReply = { reply, msg -> onReplyPermission(matchedPerm.id, reply, msg) },
                                )
                            } else if (name == "task") {
                                val summary = toolSummary(name, input, resultText)
                                val subtaskSessionID = remember(metadata, structuredResult, resultText) {
                                    extractSubtaskSessionId(
                                        metadata = metadata,
                                        structured = structuredResult?.jsonObject,
                                        resultText = resultText,
                                    )
                                }
                                val subtaskPerms = subtaskSessionID?.let { sid ->
                                    pendingPermissions.filter { it.sessionID == sid }
                                } ?: emptyList()
                                val subtaskQs = subtaskSessionID?.let { sid ->
                                    pendingQuestions.filter { it.sessionID == sid }
                                } ?: emptyList()
                                TaskToolLine(
                                    item = displayItem,
                                    summary = summary,
                                    subtaskSessionID = subtaskSessionID,
                                    onNavigate = onNavigateToSubtask,
                                    subtaskPermissions = subtaskPerms,
                                    subtaskQuestions = subtaskQs,
                                    onReplyPermission = onReplyPermission,
                                    onReplyQuestion = onReplyQuestion,
                                    onRejectQuestion = onRejectQuestion,
                                )
                            } else if (status == "pending") {
                                PendingToolLine(displayItem)
                            } else {
                                val summary = toolSummary(name, input, resultText)
                                if (shouldExpandRunningTool(displayItem)) {
                                    BlockToolLine(displayItem, summary, onViewFile, onViewDiff)
                                } else {
                                    RunningToolLine(displayItem, onViewFile)
                                }
                            }
                        }
                    } else if (name == "task") {
                        val summary = toolSummary(name, input, resultText)
                        TaskToolLine(
                            item = displayItem,
                            summary = summary,
                            onNavigate = onNavigateToSubtask,
                        )
                    } else if (status == "error") {
                        if (name == "question" && isDismissedQuestionError(resultText)) {
                            DismissedQuestionToolLine()
                        } else {
                            ErrorToolLine(displayItem)
                        }
                    } else {
                        val summary = toolSummary(name, input, resultText)
                        val questionAnswers = if (name == "question") extractQuestionAnswers(metadata) else null
                        val parsedQuestions = if (name == "question") parseQuestionArgs(input) else null
                        if (name == "question" && parsedQuestions != null && questionAnswers != null) {
                            CompletedQuestionToolCard(
                                questions = parsedQuestions,
                                answers = questionAnswers,
                            )
                        } else if (summary.isBlock) {
                            BlockToolLine(displayItem, summary, onViewFile, onViewDiff)
                        } else {
                            InlineToolLine(displayItem, onViewFile)
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
                "step-start" -> {}
                "step-finish" -> {}
                "agent" -> {
                    if (compactMode) continue
                    val agentName = obj["name"]?.jsonPrimitive?.contentOrNull ?: ""
                    Text(
                        text = "▸ agent: $agentName",
                        style = MaterialTheme.typography.bodySmall,
                        color = AgentColor,
                        modifier = Modifier.padding(vertical = 1.dp),
                    )
                }
                "subtask" -> {
                    if (compactMode) continue
                    val desc = obj["description"]?.jsonPrimitive?.contentOrNull
                        ?: obj["prompt"]?.jsonPrimitive?.contentOrNull ?: ""
                    Text(
                        text = "▸ subtask: $desc",
                        style = MaterialTheme.typography.bodySmall,
                        color = AgentColor,
                        modifier = Modifier.padding(vertical = 1.dp),
                    )
                }
                "compaction" -> {
                    if (compactMode) continue
                    Text(
                        text = "▸ compaction",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(vertical = 1.dp),
                    )
                }
                "retry" -> {
                    if (compactMode) continue
                    val attempt = obj["attempt"]?.jsonPrimitive?.intOrNull
                    val error = obj["error"]?.jsonPrimitive?.contentOrNull
                    val retryText = "▸ retry" + (attempt?.let { " #$it" } ?: "") + (error?.let { ": $it" } ?: "")
                    Text(
                        text = retryText,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(vertical = 1.dp),
                    )
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

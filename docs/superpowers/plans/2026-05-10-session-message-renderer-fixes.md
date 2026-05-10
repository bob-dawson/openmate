# SessionMessageRenderer 功能补全 实现计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 补全 SessionMessageRenderer 的三项缺失功能：agent 轨迹占位行、子代理导航、Question/Permission 内联卡片

**Architecture:** 在 SessionMessageRenderer.kt 中扩展 AssistantMessageItem 的 content 类型处理，复用 PartRenderer.kt 中已有的 QuestionCard/PermissionCard/TaskToolLine/parseQuestionArgs。通过参数传递链将 pendingQuestions/pendingPermissions/回调从 SessionDetailScreen 传入。

**Tech Stack:** Kotlin, Jetpack Compose, kotlinx.serialization.json

---

### Task 1: PartRenderer.kt — 将 parseQuestionArgs 改为 internal

**Files:**
- Modify: `D:\openmate\android\feature\session\src\main\java\com\openmate\feature\session\component\PartRenderer.kt:225`

- [ ] **Step 1: 修改 parseQuestionArgs 可见性**

将 `private fun parseQuestionArgs` 改为 `internal fun parseQuestionArgs`：

```kotlin
internal fun parseQuestionArgs(args: String?): List<QuestionInfo>? {
```

- [ ] **Step 2: 验证编译**

Run: `cd D:\openmate\android && .\gradlew.bat compileDebugKotlin --no-daemon 2>&1 | Select-String -Pattern "^e:|BUILD"`
Expected: BUILD SUCCESSFUL

---

### Task 2: SessionMessageRenderer — 更新函数签名和 hasVisible 检查

**Files:**
- Modify: `D:\openmate\android\feature\session\src\main\java\com\openmate\feature\session\component\SessionMessageRenderer.kt`

- [ ] **Step 1: 更新 SessionMessageRenderer 签名，新增 pending 列表和回调参数**

在 SessionMessageRenderer composable 函数中新增参数：

```kotlin
@Composable
fun SessionMessageRenderer(
    entity: SessionMessage,
    showReasoning: Boolean = true,
    isQueued: Boolean = false,
    userModelName: String? = null,
    onFullContentRequest: (messageId: String) -> Unit,
    onNavigateToSubtask: (subtaskSessionID: String, title: String) -> Unit = { _, _ -> },
    pendingQuestions: List<QuestionRequest> = emptyList(),
    pendingPermissions: List<PermissionRequest> = emptyList(),
    onReplyQuestion: (String, List<List<String>>) -> Unit = { _, _ -> },
    onRejectQuestion: (String) -> Unit = {},
    onReplyPermission: (String, PermissionReply, String?) -> Unit = { _, _, _ -> },
) {
```

需要新增 import：
```kotlin
import com.openmate.core.domain.model.QuestionRequest
import com.openmate.core.domain.model.PermissionRequest
import com.openmate.core.domain.model.PermissionReply
```

- [ ] **Step 2: 更新 hasVisible 检查，增加更多内容类型**

将 `hasVisible` 的 `when` 块从：

```kotlin
when (type) {
    "text" -> obj["text"]?.jsonPrimitive?.contentOrNull?.isNotBlank() == true
    "tool" -> true
    "reasoning" -> obj["text"]?.jsonPrimitive?.contentOrNull?.isNotBlank() == true
    "file" -> true
    else -> false
}
```

改为：

```kotlin
when (type) {
    "text" -> obj["text"]?.jsonPrimitive?.contentOrNull?.isNotBlank() == true
    "tool" -> true
    "reasoning" -> obj["text"]?.jsonPrimitive?.contentOrNull?.isNotBlank() == true
    "file" -> true
    "step-start", "step-finish", "agent", "subtask", "compaction", "retry" -> true
    else -> false
}
```

- [ ] **Step 3: 传递新参数给 AssistantMessageItem**

将 assistant 分支中的 `AssistantMessageItem` 调用从：

```kotlin
AssistantMessageItem(dataJson, showReasoning, onNavigateToSubtask)
```

改为：

```kotlin
AssistantMessageItem(
    data = dataJson,
    showReasoning = showReasoning,
    onNavigateToSubtask = onNavigateToSubtask,
    pendingQuestions = pendingQuestions,
    pendingPermissions = pendingPermissions,
    onReplyQuestion = onReplyQuestion,
    onRejectQuestion = onRejectQuestion,
    onReplyPermission = onReplyPermission,
)
```

- [ ] **Step 4: 验证编译**

Run: `cd D:\openmate\android && .\gradlew.bat compileDebugKotlin --no-daemon 2>&1 | Select-String -Pattern "^e:|BUILD"`
Expected: BUILD SUCCESSFUL（此时新参数都有默认值，不会影响调用方）

---

### Task 3: SessionMessageRenderer — 重写 AssistantMessageItem

**Files:**
- Modify: `D:\openmate\android\feature\session\src\main\java\com\openmate\feature\session\component\SessionMessageRenderer.kt`

这是最核心的改动。将整个 `AssistantMessageItem` 函数替换。

- [ ] **Step 1: 替换 AssistantMessageItem 函数签名**

```kotlin
@Composable
fun AssistantMessageItem(
    data: JsonObject,
    showReasoning: Boolean = true,
    onNavigateToSubtask: (String, String) -> Unit = { _, _ -> },
    pendingQuestions: List<QuestionRequest> = emptyList(),
    pendingPermissions: List<PermissionRequest> = emptyList(),
    onReplyQuestion: (String, List<List<String>>) -> Unit = { _, _ -> },
    onRejectQuestion: (String) -> Unit = {},
    onReplyPermission: (String, PermissionReply, String?) -> Unit = { _, _, _ -> },
) {
```

- [ ] **Step 2: 实现完整的 content 遍历逻辑**

替换 AssistantMessageItem 的函数体。核心逻辑如下：

```kotlin
    val content = data["content"]?.jsonArray ?: return
    val reasoningExpanded = remember { mutableStateOf(true) }

    data class StepGroup(
        val startIndex: Int,
        val startedAt: Long?,
        val items: MutableList<JsonObject> = mutableListOf(),
        var toolCount: Int = 0,
        var finishedAt: Long? = null,
        var isRunning: Boolean = true,
    )
    var currentStep by remember { mutableStateOf<StepGroup?>(null) }

    Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 2.dp)) {
        for ((itemIndex, item) in content.withIndex()) {
            val obj = item.jsonObject
            when (obj["type"]?.jsonPrimitive?.contentOrNull) {
                "text" -> {
                    currentStep?.let { step -> StepSummaryRow(step, onNavigateToSubtask) }
                    currentStep = null
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
                    val callID = obj["callID"]?.jsonPrimitive?.contentOrNull
                        ?: state?.get("callID")?.jsonPrimitive?.contentOrNull
                    val metadata = state?.get("metadata")?.jsonObject

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
                        callID = callID,
                        metadata = metadata,
                    )

                    if (currentStep != null) {
                        if (name != "question") {
                            currentStep!!.toolCount++
                        }
                        currentStep!!.items.add(obj)
                    }

                    // Question tool: inline QuestionCard
                    if (name == "question" && status == "running") {
                        val parsedQuestions = remember(input) { parseQuestionArgs(input) }
                        val matchedRequest = callID?.let { cid ->
                            pendingQuestions.find { it.tool?.callID == cid }
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
                            InlineToolLine(displayItem)
                        }
                    }
                    // Permission matching for pending/running tools
                    else if (status == "pending" || status == "running") {
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
                            val subtaskSessionID = remember(metadata, resultText) {
                                metadata?.str("sessionId")
                                    ?: resultText?.let { TaskIdRegex.find(it)?.groupValues?.getOrNull(1) }
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
                            RunningToolLine(displayItem)
                        }
                    }
                    // Completed task tool: TaskToolLine
                    else if (name == "task") {
                        val summary = toolSummary(name, input, resultText)
                        TaskToolLine(
                            item = displayItem,
                            summary = summary,
                            onNavigate = onNavigateToSubtask,
                        )
                    }
                    // Error
                    else if (status == "error") {
                        ErrorToolLine(displayItem)
                    }
                    // Completed: inline or block
                    else {
                        val summary = toolSummary(name, input, resultText)
                        if (summary.isBlock) {
                            BlockToolLine(displayItem, summary)
                        } else {
                            InlineToolLine(displayItem)
                        }
                    }
                }
                "reasoning" -> {
                    currentStep?.let { step -> StepSummaryRow(step, onNavigateToSubtask) }
                    currentStep = null
                    val text = obj["text"]?.jsonPrimitive?.contentOrNull ?: ""
                    if (text.isNotBlank() && showReasoning) {
                        // ... existing reasoning rendering code unchanged ...
                    }
                }
                "step-start" -> {
                    val startedAt = obj["time"]?.jsonObject?.get("started")?.jsonPrimitive?.longOrNull
                    if (currentStep == null) {
                        currentStep = StepGroup(startIndex = itemIndex, startedAt = startedAt)
                    }
                }
                "step-finish" -> {
                    val completedAt = obj["time"]?.jsonObject?.get("completed")?.jsonPrimitive?.longOrNull
                    val finish = obj["finish"]?.jsonPrimitive?.contentOrNull
                    currentStep?.let { step ->
                        step.finishedAt = completedAt
                        step.isRunning = completedAt == null && finish == null
                        StepSummaryRow(step, onNavigateToSubtask)
                    }
                    currentStep = null
                }
                "agent" -> {
                    val name = obj["name"]?.jsonPrimitive?.contentOrNull ?: ""
                    Text(
                        text = "▸ agent: $name",
                        style = MaterialTheme.typography.bodySmall,
                        color = AgentColor,
                        modifier = Modifier.padding(vertical = 1.dp),
                    )
                }
                "subtask" -> {
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
                    Text(
                        text = "▸ compaction",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(vertical = 1.dp),
                    )
                }
                "retry" -> {
                    val attempt = obj["attempt"]?.jsonPrimitive?.intOrNull
                    val error = obj["error"]?.jsonPrimitive?.contentOrNull
                    val text = "▸ retry" + (attempt?.let { " #$it" } ?: "") + (error?.let { ": $it" } ?: "")
                    Text(
                        text = text,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(vertical = 1.dp),
                    )
                }
            }
        }
        // If a step is still running at the end of content array, show it
        currentStep?.let { step -> StepSummaryRow(step, onNavigateToSubtask) }
    }
```

需要新增 import：
```kotlin
import com.openmate.core.domain.model.QuestionRequest
import com.openmate.core.domain.model.PermissionRequest
import com.openmate.core.domain.model.PermissionReply
import android.os.SystemClock
import kotlinx.coroutines.delay
```

- [ ] **Step 3: 实现 StepSummaryRow composable**

在 SessionMessageRenderer.kt 中添加 StepSummaryRow：

```kotlin
@Composable
private fun StepSummaryRow(
    step: StepGroup,
    onNavigateToSubtask: (String, String) -> Unit,
) {
    val expanded = remember { mutableStateOf(false) }
    val durationText = if (step.isRunning) {
        val phoneAnchor = remember { SystemClock.elapsedRealtime() }
        var elapsed by remember { mutableStateOf(SystemClock.elapsedRealtime() - phoneAnchor) }
        LaunchedEffect(Unit) {
            while (true) {
                delay(1000)
                elapsed = SystemClock.elapsedRealtime() - phoneAnchor
            }
        }
        formatDurationMillis(elapsed)
    } else {
        val duration = step.finishedAt?.let { end ->
            step.startedAt?.let { start -> end - start }
        }
        duration?.let { formatDurationMillis(it) } ?: ""
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { if (step.items.isNotEmpty()) expanded.value = !expanded.value }
            .padding(vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (step.isRunning) {
            CircularProgressIndicator(
                modifier = Modifier.size(12.dp),
                strokeWidth = 1.5.dp,
            )
            Spacer(modifier = Modifier.width(4.dp))
        }
        Text(
            text = "⚡ Step",
            style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.SemiBold),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (durationText.isNotBlank()) {
            Spacer(modifier = Modifier.width(4.dp))
            Text(
                text = "· $durationText",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (step.toolCount > 0) {
            Spacer(modifier = Modifier.width(4.dp))
            Text(
                text = "· ${step.toolCount} 工具",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (step.items.isNotEmpty()) {
            Spacer(modifier = Modifier.width(4.dp))
            Text(
                text = if (expanded.value) "▲" else "▼",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }

    AnimatedVisibility(visible = expanded.value) {
        Column(modifier = Modifier.padding(start = 12.dp)) {
            step.items.forEach { toolObj ->
                val toolName = toolObj["name"]?.jsonPrimitive?.contentOrNull ?: "tool"
                val toolState = toolObj["state"]?.jsonObject
                val toolStatus = toolState?.get("status")?.jsonPrimitive?.contentOrNull ?: ""
                val toolInput = toolState?.get("input")?.toString()
                val toolResult = toolState?.get("structured")?.toString()
                    ?: toolState?.get("content")?.jsonArray?.joinToString("\n") {
                        it.jsonObject["text"]?.jsonPrimitive?.contentOrNull ?: ""
                    }
                val summary = toolSummary(toolName, toolInput, toolResult)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = summary.icon,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary,
                    )
                    if (summary.text.isNotBlank()) {
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(
                            text = summary.text,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(
                        text = when (toolStatus) {
                            "completed" -> "✓"
                            "error" -> "✗"
                            "running" -> "…"
                            "pending" -> "⏳"
                            else -> ""
                        },
                        style = MaterialTheme.typography.labelSmall,
                        color = when (toolStatus) {
                            "completed" -> MaterialTheme.colorScheme.primary
                            "error" -> MaterialTheme.colorScheme.error
                            else -> MaterialTheme.colorScheme.onSurfaceVariant
                        },
                    )
                }
            }
        }
    }
}
```

注意：`StepGroup` data class 和 `StepSummaryRow` 需要在 `AssistantMessageItem` 外部定义，因为 Composable 函数内不能定义 data class。将 `StepGroup` 移到文件顶层：

```kotlin
private data class StepGroup(
    val startIndex: Int,
    val startedAt: Long?,
    val items: MutableList<JsonObject> = mutableListOf(),
    var toolCount: Int = 0,
    var finishedAt: Long? = null,
    var isRunning: Boolean = true,
)
```

还需要添加 `TaskIdRegex` 的 import 或在 SessionMessageRenderer.kt 中定义：
```kotlin
private val TaskIdRegex = Regex("task_id:\\s*(ses_\\S+)")
```

以及 `AgentColor`：
```kotlin
private val AgentColor = Color(0xFF9D7CD8)
```

- [ ] **Step 4: 验证编译**

Run: `cd D:\openmate\android && .\gradlew.bat compileDebugKotlin --no-daemon 2>&1 | Select-String -Pattern "^e:|BUILD"`
Expected: BUILD SUCCESSFUL

---

### Task 4: SessionDetailScreen — 传递新参数并移除模态弹窗

**Files:**
- Modify: `D:\openmate\android\feature\session\src\main\java\com\openmate\feature\session\SessionDetailScreen.kt`

- [ ] **Step 1: 传递 pendingQuestions/pendingPermissions/回调给 SessionMessageRenderer**

在 LazyColumn 的 `items` 块中，更新 SessionMessageRenderer 调用：

```kotlin
SessionMessageRenderer(
    entity = entity,
    showReasoning = showReasoning,
    isQueued = entity.type == "user" && streamingAssistantId != null && entity.id > streamingAssistantId!!,
    userModelName = userModelName,
    onFullContentRequest = { messageId ->
        viewModel.fetchFullContent(sessionID, messageId)
    },
    onNavigateToSubtask = onNavigateToSubtask,
    pendingQuestions = pendingQuestions,
    pendingPermissions = pendingPermissions,
    onReplyQuestion = { requestID, answers -> viewModel.replyQuestion(requestID, answers) },
    onRejectQuestion = { requestID -> viewModel.rejectQuestion(requestID) },
    onReplyPermission = { requestID, reply, msg -> viewModel.replyPermission(requestID, reply, msg) },
)
```

- [ ] **Step 2: 移除底部模态弹窗代码**

删除 `SessionDetailScreen` 函数末尾的模态弹窗（约 line 548-564）：

```kotlin
// 删除以下代码：
pendingQuestions.firstOrNull()?.let { question ->
    QuestionDialog(
        request = question,
        onSubmit = { answers -> viewModel.replyQuestion(question.id, answers) },
        onReject = { viewModel.rejectQuestion(question.id) },
        onDismiss = { viewModel.rejectQuestion(question.id) },
    )
}

pendingPermissions.firstOrNull()?.let { permission ->
    PermissionDialog(
        request = permission,
        onAllow = { viewModel.replyPermission(permission.id, PermissionReply.ONCE) },
        onDeny = { viewModel.replyPermission(permission.id, PermissionReply.REJECT) },
        onDismiss = { viewModel.replyPermission(permission.id, PermissionReply.REJECT) },
    )
}
```

同时移除不再需要的 import（`QuestionDialog`、`PermissionDialog`）。

- [ ] **Step 3: 验证编译**

Run: `cd D:\openmate\android && .\gradlew.bat compileDebugKotlin --no-daemon 2>&1 | Select-String -Pattern "^e:|BUILD"`
Expected: BUILD SUCCESSFUL

---

### Task 5: 完整构建和安装验证

**Files:** 无修改

- [ ] **Step 1: 完整 assembleDebug**

Run: `cd D:\openmate\android && .\gradlew.bat assembleDebug --no-daemon 2>&1 | Select-String -Pattern "^e:|BUILD"`
Expected: BUILD SUCCESSFUL

- [ ] **Step 2: 安装到设备**

Run: `adb install -r D:\openmate\android\app\build\outputs\apk\debug\app-debug.apk`
Expected: Success

- [ ] **Step 3: 手动验证**

打开一个有 agent 活动的会话，验证：
1. Step 摘要行显示（`⚡ Step · Xs · N 工具`），可点击展开
2. 执行中的 step 实时更新耗时
3. 子代理 task 工具可点击跳转
4. Question 卡片内联显示
5. Permission 卡片内联显示
6. 无模态弹窗弹出

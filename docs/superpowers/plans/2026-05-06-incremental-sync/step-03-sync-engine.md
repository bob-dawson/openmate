# 步骤 3：域模型 + 同步引擎 — EventReplayer + 截断 + SyncOrchestrator

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task.

**Goal:** 新增 SessionMessage 域模型、事件回放器（含截断逻辑）、同步编排器。

**Architecture:** core:domain 新增模型和仓库接口；core:data 新增 EventReplayer（纯函数式回放+截断）和 SyncOrchestrator（调用 API → 回放 → 写 DB）。

**Tech Stack:** Kotlin, kotlinx.serialization, Room, Hilt

---

## Files

### core:domain
- Create: `core/domain/src/main/java/com/openmate/core/domain/model/SessionMessage.kt`
- Create: `core/domain/src/main/java/com/openmate/core/domain/model/SyncEvent.kt`
- Create: `core/domain/src/main/java/com/openmate/core/domain/repository/EventSyncRepository.kt`

### core:data
- Create: `core/data/src/main/java/com/openmate/core/data/sync/EventReplayer.kt`
- Create: `core/data/src/main/java/com/openmate/core/data/sync/DataTruncator.kt`
- Create: `core/data/src/main/java/com/openmate/core/data/sync/SyncOrchestrator.kt`
- Create: `core/data/src/main/java/com/openmate/core/data/sync/ToolDataTruncator.kt`

---

## Task 1: Create SessionMessage domain model

**Files:**
- Create: `core/domain/src/main/java/com/openmate/core/domain/model/SessionMessage.kt`

- [ ] **Step 1: Create the model**

对齐 opencode v2 `SessionMessage` 结构。使用 sealed interface + data class 鉴别联合类型。

```kotlin
package com.openmate.core.domain.model

sealed interface SessionMessage {
    val id: String
    val sessionID: String
    val createdAt: Long

    data class User(
        override val id: String,
        override val sessionID: String,
        override val createdAt: Long,
        val text: String,
        val files: List<FileRef> = emptyList(),
    ) : SessionMessage

    data class Assistant(
        override val id: String,
        override val sessionID: String,
        override val createdAt: Long,
        val completedAt: Long? = null,
        val agent: String = "",
        val model: ModelRef? = null,
        val content: List<AssistantContent> = emptyList(),
        val finish: String? = null,
        val cost: Double? = null,
        val tokens: TokenUsage? = null,
        val error: ErrorInfo? = null,
    ) : SessionMessage

    data class Shell(
        override val id: String,
        override val sessionID: String,
        override val createdAt: Long,
        val completedAt: Long? = null,
        val callID: String = "",
        val command: String = "",
        val output: String = "",
    ) : SessionMessage

    data class Compaction(
        override val id: String,
        override val sessionID: String,
        override val createdAt: Long,
        val reason: String = "",
        val summary: String = "",
    ) : SessionMessage

    data class Synthetic(
        override val id: String,
        override val sessionID: String,
        override val createdAt: Long,
        val text: String,
    ) : SessionMessage

    data class AgentSwitched(
        override val id: String,
        override val sessionID: String,
        override val createdAt: Long,
        val agent: String = "",
    ) : SessionMessage

    data class ModelSwitched(
        override val id: String,
        override val sessionID: String,
        override val createdAt: Long,
        val model: ModelRef? = null,
    ) : SessionMessage
}

data class FileRef(
    val name: String = "",
    val uri: String = "",
    val mime: String = "",
)

data class ModelRef(
    val id: String = "",
    val providerID: String = "",
    val variant: String? = null,
)

data class ErrorInfo(
    val type: String = "",
    val message: String = "",
)

sealed interface AssistantContent {
    data class Text(
        val text: String,
    ) : AssistantContent

    data class Reasoning(
        val id: String,
        val text: String,
    ) : AssistantContent

    data class Tool(
        val id: String,
        val name: String,
        val callID: String = "",
        val state: ToolState,
    ) : AssistantContent
}

sealed interface ToolState {
    data class Pending(
        val input: String = "",
    ) : ToolState

    data class Running(
        val input: Map<String, kotlin.Any?> = emptyMap(),
    ) : ToolState

    data class Completed(
        val input: Map<String, kotlin.Any?> = emptyMap(),
    ) : ToolState

    data class Error(
        val input: Map<String, kotlin.Any?> = emptyMap(),
        val error: ErrorInfo = ErrorInfo(),
    ) : ToolState
}
```

注意：`ToolState` 在这里重新定义以匹配新模型的 structured 形式，与旧 `ToolCallState` 枚举不同。旧模型保留不变，新模型用新类型。

- [ ] **Step 2: Build and verify**

Run: `.\gradlew.bat :core:domain:compileDebugKotlin --no-daemon 2>&1 | Select-String -Pattern "^e:"`

Expected: No errors

---

## Task 2: Create SyncEvent domain model

**Files:**
- Create: `core/domain/src/main/java/com/openmate/core/domain/model/SyncEvent.kt`

- [ ] **Step 1: Create the model**

```kotlin
package com.openmate.core.domain.model

data class SyncEvent(
    val id: String,
    val aggregateID: String,
    val seq: Int,
    val type: String,
)
```

注意：不包含 `data: JsonElement`。域模型层不依赖 kotlinx.serialization.json，事件 data 在 data 层处理。

- [ ] **Step 2: Build and verify**

Run: `.\gradlew.bat :core:domain:compileDebugKotlin --no-daemon 2>&1 | Select-String -Pattern "^e:"`

Expected: No errors

---

## Task 3: Create EventSyncRepository interface

**Files:**
- Create: `core/domain/src/main/java/com/openmate/core/domain/repository/EventSyncRepository.kt`

- [ ] **Step 1: Create the interface**

```kotlin
package com.openmate.core.domain.repository

import com.openmate.core.domain.model.SessionMessage
import kotlinx.coroutines.flow.Flow

interface EventSyncRepository {
    suspend fun syncEvents(sessionIDs: Map<String, Int>): Result<Int>
    fun observeSessionMessages(sessionID: String): Flow<List<SessionMessage>>
    suspend fun fetchFullContent(sessionID: String, messageID: String): Result<SessionMessage?>
    suspend fun getLastSeq(sessionID: String): Int?
}
```

- `syncEvents`：拉取增量事件并回放，返回处理的事件数
- `observeSessionMessages`：观察会话的新模型消息列表
- `fetchFullContent`：三级回源，从 API 获取完整消息
- `getLastSeq`：获取本地已知的 lastSeq

- [ ] **Step 2: Build and verify**

Run: `.\gradlew.bat :core:domain:compileDebugKotlin --no-daemon 2>&1 | Select-String -Pattern "^e:"`

Expected: No errors

---

## Task 4: Create DataTruncator

**Files:**
- Create: `core/data/src/main/java/com/openmate/core/data/sync/DataTruncator.kt`

- [ ] **Step 1: Create the truncator**

截断逻辑的核心入口。接收 `SyncEventDto`（包含 JsonElement data），按事件类型和截断规则处理，返回截断后的 JSON 字符串（用于写入 SessionMessageEntity.data）。

```kotlin
package com.openmate.core.data.sync

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject

object DataTruncator {

    private val json = kotlinx.serialization.json.Json { ignoreUnknownKeys = true }

    fun truncateEventData(eventType: String, data: JsonElement): String {
        val withoutVersion = eventType.substringBeforeLast(".") // e.g. "session.next.text.ended"
        return when {
            withoutVersion.endsWith(".delta") -> "" // delta 事件跳过
            else -> truncateByEventType(withoutVersion, data)
        }
    }

    private fun truncateByEventType(type: String, data: JsonElement): String {
        return when (type) {
            "session.next.prompted" -> truncatePrompted(data)
            "session.next.synthetic" -> truncateSynthetic(data)
            "session.next.step.started" -> truncateStepStarted(data)
            "session.next.step.ended" -> truncateStepEnded(data)
            "session.next.step.failed" -> truncateStepFailed(data)
            "session.next.text.started" -> data.toString()
            "session.next.text.ended" -> truncateTextEnded(data)
            "session.next.reasoning.started" -> data.toString()
            "session.next.reasoning.ended" -> truncateReasoningEnded(data)
            "session.next.tool.input.started" -> truncateToolInputStarted(data)
            "session.next.tool.input.ended" -> truncateToolInputEnded(data)
            "session.next.tool.called" -> truncateToolCalled(data)
            "session.next.tool.progress" -> truncateToolProgress(data)
            "session.next.tool.success" -> truncateToolSuccess(data)
            "session.next.tool.failed" -> truncateToolFailed(data)
            "session.next.shell.started" -> data.toString()
            "session.next.shell.ended" -> truncateShellEnded(data)
            "session.next.agent.switched" -> data.toString()
            "session.next.model.switched" -> data.toString()
            "session.next.compaction.started" -> data.toString()
            "session.next.compaction.ended" -> truncateCompactionEnded(data)
            "session.next.retried" -> truncateRetried(data)
            else -> data.toString()
        }
    }

    // --- 各事件类型的截断实现 ---
    // 以下方法按 truncation-rules.md 实现
    // 详细实现见各方法注释

    private fun truncatePrompted(data: JsonElement): String {
        // user text: 不截断
        // files: 保留 name/uri/mime，跳过 source.text/description
        // 其他字段全量保留
        val obj = data.jsonObject
        val prompt = obj["prompt"]?.jsonObject ?: return data.toString()
        val truncatedFiles = prompt["files"]?.jsonArray?.map { file ->
            val f = file.jsonObject
            buildJsonObject {
                f["name"]?.let { put("name", it) }
                f["uri"]?.let { put("uri", it) }
                f["mime"]?.let { put("mime", it) }
            }
        }
        val truncatedPrompt = buildJsonObject {
            prompt.entries.forEach { (key, value) ->
                if (key == "files" && truncatedFiles != null) {
                    put("files", kotlinx.serialization.json.JsonArray(truncatedFiles))
                } else {
                    put(key, value)
                }
            }
        }
        return buildJsonObject {
            obj.entries.forEach { (key, value) ->
                if (key == "prompt") {
                    put("prompt", truncatedPrompt)
                } else {
                    put(key, value)
                }
            }
        }.toString()
    }

    private fun truncateSynthetic(data: JsonElement): String {
        // synthetic text: 不截断
        return data.toString()
    }

    private fun truncateStepStarted(data: JsonElement): String {
        // snapshot: 跳过
        val obj = data.jsonObject
        return buildJsonObject {
            obj.entries.forEach { (key, value) ->
                if (key != "snapshot") put(key, value)
            }
        }.toString()
    }

    private fun truncateStepEnded(data: JsonElement): String {
        // snapshot: 跳过, tokens/cost/finish: 全量保留
        val obj = data.jsonObject
        return buildJsonObject {
            obj.entries.forEach { (key, value) ->
                if (key != "snapshot") put(key, value)
            }
        }.toString()
    }

    private fun truncateStepFailed(data: JsonElement): String {
        // error: 全量保留
        return data.toString()
    }

    private fun truncateTextEnded(data: JsonElement): String {
        // assistant content text: 不截断
        return data.toString()
    }

    private fun truncateReasoningEnded(data: JsonElement): String {
        // reasoning: 保留前后各 100 字符，中间 ...[truncated]...
        val obj = data.jsonObject
        val text = obj["text"]?.jsonPrimitive?.content ?: return data.toString()
        val truncated = truncateMiddle(text, 100, 100)
        if (truncated == text) return data.toString()
        return buildJsonObject {
            obj.entries.forEach { (key, value) ->
                if (key == "text") put("text", JsonPrimitive(truncated))
                else put(key, value)
            }
        }.toString()
    }

    private fun truncateToolInputStarted(data: JsonElement): String {
        // 只保留 callID 和 name
        val obj = data.jsonObject
        return buildJsonObject {
            obj["callID"]?.let { put("callID", it) }
            obj["name"]?.let { put("name", it) }
            obj["sessionID"]?.let { put("sessionID", it) }
            obj["timestamp"]?.let { put("timestamp", it) }
        }.toString()
    }

    private fun truncateToolInputEnded(data: JsonElement): String {
        // 只保留 callID，跳过 text（工具输入JSON，可能很大）
        val obj = data.jsonObject
        return buildJsonObject {
            obj["callID"]?.let { put("callID", it) }
            obj["sessionID"]?.let { put("sessionID", it) }
            obj["timestamp"]?.let { put("timestamp", it) }
        }.toString()
    }

    private fun truncateToolCalled(data: JsonElement): String {
        // input: 按工具类型截断（由 ToolDataTruncator 处理）
        // 保留 callID, tool, provider
        val obj = data.jsonObject
        val toolName = obj["tool"]?.jsonPrimitive?.content ?: ""
        val truncatedInput = ToolDataTruncator.truncateInput(toolName, obj["input"]?.jsonObject)
        return buildJsonObject {
            obj["callID"]?.let { put("callID", it) }
            put("tool", JsonPrimitive(toolName))
            put("input", truncatedInput)
            obj["provider"]?.let { put("provider", it) }
            obj["sessionID"]?.let { put("sessionID", it) }
            obj["timestamp"]?.let { put("timestamp", it) }
        }.toString()
    }

    private fun truncateToolProgress(data: JsonElement): String {
        // content: 跳过, structured: 按 ToolDataTruncator 处理
        val obj = data.jsonObject
        val toolName = obj["name"]?.jsonPrimitive?.content ?: ""
        return buildJsonObject {
            obj["callID"]?.let { put("callID", it) }
            obj["sessionID"]?.let { put("sessionID", it) }
            obj["timestamp"]?.let { put("timestamp", it) }
            put("structured", ToolDataTruncator.truncateStructuredOutput(toolName, obj["structured"]))
        }.toString()
    }

    private fun truncateToolSuccess(data: JsonElement): String {
        // content: 跳过, structured: 按 ToolDataTruncator 处理
        val obj = data.jsonObject
        val callID = obj["callID"]?.jsonPrimitive?.content ?: ""
        return buildJsonObject {
            obj["callID"]?.let { put("callID", it) }
            obj["sessionID"]?.let { put("sessionID", it) }
            obj["timestamp"]?.let { put("timestamp", it) }
            put("structured", ToolDataTruncator.truncateStructuredOutput("", obj["structured"]))
            obj["provider"]?.let { put("provider", it) }
        }.toString()
    }

    private fun truncateToolFailed(data: JsonElement): String {
        // error: 全量保留
        val obj = data.jsonObject
        return buildJsonObject {
            obj["callID"]?.let { put("callID", it) }
            obj["sessionID"]?.let { put("sessionID", it) }
            obj["timestamp"]?.let { put("timestamp", it) }
            obj["error"]?.let { put("error", it) }
            obj["provider"]?.let { put("provider", it) }
        }.toString()
    }

    private fun truncateShellEnded(data: JsonElement): String {
        // output: 前5行+后5行，保留 exit/truncated
        val obj = data.jsonObject
        val output = obj["output"]?.jsonPrimitive?.content ?: ""
        val truncated = truncateBashOutput(output)
        return buildJsonObject {
            obj.entries.forEach { (key, value) ->
                if (key == "output") put("output", JsonPrimitive(truncated))
                else put(key, value)
            }
        }.toString()
    }

    private fun truncateCompactionEnded(data: JsonElement): String {
        // text: 前10行+后10行, include: 跳过
        val obj = data.jsonObject
        val text = obj["text"]?.jsonPrimitive?.content ?: ""
        val truncated = truncateCompactionText(text)
        return buildJsonObject {
            obj.entries.forEach { (key, value) ->
                when (key) {
                    "text" -> put("text", JsonPrimitive(truncated))
                    "include" -> { /* skip */ }
                    else -> put(key, value)
                }
            }
        }.toString()
    }

    private fun truncateRetried(data: JsonElement): String {
        // 跳过 error.responseBody, error.responseHeaders
        val obj = data.jsonObject
        return buildJsonObject {
            obj.entries.forEach { (key, value) ->
                if (key == "error") {
                    val err = value.jsonObject
                    put("error", buildJsonObject {
                        err.entries.forEach { (ek, ev) ->
                            when (ek) {
                                "message", "statusCode", "isRetryable" -> put(ek, ev)
                            }
                        }
                    })
                } else {
                    put(key, value)
                }
            }
        }.toString()
    }

    // --- 通用截断工具方法 ---

    fun truncateMiddle(text: String, headLen: Int, tailLen: Int): String {
        if (text.length <= headLen + tailLen + 20) return text
        return text.take(headLen) + "...[truncated]..." + text.takeLast(tailLen)
    }

    fun truncateBashOutput(output: String, maxHead: Int = 5, maxTail: Int = 5): String {
        val lines = output.lines()
        if (lines.size <= maxHead + maxTail) return output
        val head = lines.take(maxHead)
        val tail = lines.takeLast(maxTail)
        return head.joinToString("\n") + "\n...[${lines.size - maxHead - maxTail} lines truncated]...\n" + tail.joinToString("\n")
    }

    fun truncateCompactionText(text: String, maxHead: Int = 10, maxTail: Int = 10): String {
        val lines = text.lines()
        if (lines.size <= maxHead + maxTail) return text
        val head = lines.take(maxHead)
        val tail = lines.takeLast(maxTail)
        return head.joinToString("\n") + "\n...[${lines.size - maxHead - maxTail} lines truncated]...\n" + tail.joinToString("\n")
    }
}
```

- [ ] **Step 2: Build and verify**

Run: `.\gradlew.bat :core:data:compileDebugKotlin --no-daemon 2>&1 | Select-String -Pattern "^e:"`

Expected: No errors

---

## Task 5: Create ToolDataTruncator

**Files:**
- Create: `core/data/src/main/java/com/openmate/core/data/sync/ToolDataTruncator.kt`

- [ ] **Step 1: Create the tool-specific truncator**

按截断规则文档中"按工具类型的截断规则"实现。

```kotlin
package com.openmate.core.data.sync

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject

object ToolDataTruncator {

    fun truncateInput(toolName: String, input: JsonObject?): JsonObject {
        if (input == null) return buildJsonObject {}
        return when (toolName) {
            "bash" -> keepKeys(input, "command")
            "read" -> keepKeys(input, "filePath")
            "write" -> keepKeys(input, "filePath")
            "edit" -> keepKeys(input, "filePath")
            "apply_patch" -> buildJsonObject {}
            "glob" -> keepKeys(input, "pattern", "path")
            "grep" -> keepKeys(input, "pattern", "path", "include")
            "task" -> keepKeys(input, "description", "subagent_type")
            "todowrite" -> input
            "webfetch" -> keepKeys(input, "url", "format")
            "websearch" -> keepKeys(input, "query")
            "skill" -> keepKeys(input, "name")
            "question" -> input
            "lsp" -> keepKeys(input, "operation", "filePath", "line", "character", "query")
            "plan_exit" -> buildJsonObject {}
            "invalid" -> keepKeys(input, "tool", "error")
            else -> keepKeys(input, "name")
        }
    }

    fun truncateStructuredOutput(toolName: String, structured: JsonElement?): JsonObject {
        if (structured == null) return buildJsonObject {}
        val obj = structured.jsonObject
        return when (toolName) {
            "bash" -> buildJsonObject {
                obj["exit"]?.let { put("exit", it) }
                obj["truncated"]?.let { put("truncated", it) }
                val output = obj["output"]?.jsonPrimitive?.content
                if (output != null) {
                    put("output", JsonPrimitive(DataTruncator.truncateBashOutput(output)))
                }
            }
            "read" -> keepKeys(obj, "truncated")
            "write" -> keepKeys(obj, "filepath", "exists")
            "edit" -> keepKeys(obj, "additions", "deletions")
            "apply_patch" -> {
                val files = obj["files"]?.jsonArray?.map { file ->
                    val f = file.jsonObject
                    buildJsonObject {
                        f["filePath"]?.let { put("filePath", it) }
                        f["type"]?.let { put("type", it) }
                        f["additions"]?.let { put("additions", it) }
                        f["deletions"]?.let { put("deletions", it) }
                    }
                }
                buildJsonObject {
                    if (files != null) put("files", kotlinx.serialization.json.JsonArray(files))
                }
            }
            "glob" -> keepKeys(obj, "count", "truncated")
            "grep" -> keepKeys(obj, "matches", "truncated")
            "task" -> keepKeys(obj, "sessionId", "model")
            "todowrite" -> obj
            "webfetch" -> buildJsonObject {}
            "websearch" -> buildJsonObject {}
            "skill" -> keepKeys(obj, "name", "dir")
            "question" -> obj
            "lsp" -> buildJsonObject {}
            "plan_exit" -> buildJsonObject {}
            "invalid" -> buildJsonObject {}
            else -> {
                val str = obj.toString()
                if (str.length <= 500) obj else buildJsonObject {}
            }
        }
    }

    private fun keepKeys(obj: JsonObject, vararg keys: String): JsonObject {
        return buildJsonObject {
            for (key in keys) {
                obj[key]?.let { put(key, it) }
            }
        }
    }
}
```

- [ ] **Step 2: Build and verify**

Run: `.\gradlew.bat :core:data:compileDebugKotlin --no-daemon 2>&1 | Select-String -Pattern "^e:"`

Expected: No errors

---

## Task 6: Create EventReplayer

**Files:**
- Create: `core/data/src/main/java/com/openmate/core/data/sync/EventReplayer.kt`

- [ ] **Step 1: Create the replayer**

EventReplayer 维护一个内存中的 `MutableMap<String, SessionMessageEntity>` 状态，按事件顺序回放，输出需要 upsert 的实体列表。

核心设计：
- 回放期间维护内存状态（`messageID → SessionMessageEntity`）
- 每个 `session.next.*` 事件更新对应消息的状态
- delta 事件跳过
- 最终返回所有变更的实体列表

```kotlin
package com.openmate.core.data.sync

import com.openmate.core.database.entity.SessionMessageEntity
import com.openmate.core.network.dto.SyncEventDto
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long

object EventReplayer {

    fun replay(
        events: List<SyncEventDto>,
        existingMessages: Map<String, SessionMessageEntity>,
    ): List<SessionMessageEntity> {
        val state = existingMessages.toMutableMap()

        for (event in events) {
            val type = event.type.substringBeforeLast(".") // 去掉版本号
            if (type.endsWith(".delta")) continue
            replayEvent(type, event, state)
        }

        return state.values.toList()
    }

    private fun replayEvent(
        type: String,
        event: SyncEventDto,
        state: MutableMap<String, SessionMessageEntity>,
    ) {
        val data = event.data.jsonObject
        val sessionID = data["sessionID"]?.jsonPrimitive?.content ?: event.aggregate_id
        val timestamp = data["timestamp"]?.jsonPrimitive?.long ?: 0L

        when (type) {
            "session.next.prompted" -> {
                val prompt = data["prompt"]?.jsonObject ?: return
                val msgID = "msg_${event.id}"
                val truncatedData = DataTruncator.truncateEventData(event.type, event.data)
                state[msgID] = SessionMessageEntity(
                    id = msgID,
                    sessionID = sessionID,
                    type = "user",
                    data = truncatedData,
                    createdAt = timestamp,
                    seq = event.seq,
                )
            }

            "session.next.synthetic" -> {
                val msgID = "msg_syn_${event.id}"
                val truncatedData = DataTruncator.truncateEventData(event.type, event.data)
                state[msgID] = SessionMessageEntity(
                    id = msgID,
                    sessionID = sessionID,
                    type = "synthetic",
                    data = truncatedData,
                    createdAt = timestamp,
                    seq = event.seq,
                )
            }

            "session.next.step.started" -> {
                val msgID = "msg_step_${event.id}"
                val truncatedData = DataTruncator.truncateEventData(event.type, event.data)
                state[msgID] = SessionMessageEntity(
                    id = msgID,
                    sessionID = sessionID,
                    type = "assistant",
                    data = truncatedData,
                    createdAt = timestamp,
                    seq = event.seq,
                )
            }

            "session.next.step.ended" -> {
                val stepMsg = findStepMessage(state, sessionID)
                if (stepMsg != null) {
                    val truncatedData = DataTruncator.truncateEventData(event.type, event.data)
                    val merged = mergeAssistantData(stepMsg.data, truncatedData, "step.ended")
                    state[stepMsg.id] = stepMsg.copy(
                        data = merged,
                        completedAt = timestamp,
                        seq = event.seq,
                    )
                }
            }

            "session.next.step.failed" -> {
                val stepMsg = findStepMessage(state, sessionID)
                if (stepMsg != null) {
                    val truncatedData = DataTruncator.truncateEventData(event.type, event.data)
                    val merged = mergeAssistantData(stepMsg.data, truncatedData, "step.failed")
                    state[stepMsg.id] = stepMsg.copy(
                        data = merged,
                        completedAt = timestamp,
                        seq = event.seq,
                    )
                }
            }

            "session.next.text.started" -> {
                val stepMsg = findStepMessage(state, sessionID)
                if (stepMsg != null) {
                    val content = appendContentToData(stepMsg.data, mapOf("type" to "text", "text" to ""))
                    state[stepMsg.id] = stepMsg.copy(data = content, seq = event.seq)
                }
            }

            "session.next.text.ended" -> {
                val stepMsg = findStepMessage(state, sessionID)
                if (stepMsg != null) {
                    val truncatedData = DataTruncator.truncateEventData(event.type, event.data)
                    val text = truncatedData // text.ended 的截断后 data 包含完整 text
                    val content = updateLastTextContent(stepMsg.data, text)
                    state[stepMsg.id] = stepMsg.copy(data = content, seq = event.seq)
                }
            }

            "session.next.reasoning.started" -> {
                val stepMsg = findStepMessage(state, sessionID)
                if (stepMsg != null) {
                    val reasoningID = data["reasoningID"]?.jsonPrimitive?.content ?: ""
                    val content = appendContentToData(stepMsg.data, mapOf("type" to "reasoning", "id" to reasoningID, "text" to ""))
                    state[stepMsg.id] = stepMsg.copy(data = content, seq = event.seq)
                }
            }

            "session.next.reasoning.ended" -> {
                val stepMsg = findStepMessage(state, sessionID)
                if (stepMsg != null) {
                    val truncatedData = DataTruncator.truncateEventData(event.type, event.data)
                    val content = updateLastReasoningContent(stepMsg.data, truncatedData)
                    state[stepMsg.id] = stepMsg.copy(data = content, seq = event.seq)
                }
            }

            "session.next.tool.input.started" -> {
                val stepMsg = findStepMessage(state, sessionID)
                if (stepMsg != null) {
                    val callID = data["callID"]?.jsonPrimitive?.content ?: ""
                    val name = data["name"]?.jsonPrimitive?.content ?: ""
                    val content = appendContentToData(stepMsg.data, mapOf(
                        "type" to "tool",
                        "id" to callID,
                        "name" to name,
                        "callID" to callID,
                        "state" to mapOf("status" to "pending", "input" to ""),
                    ))
                    state[stepMsg.id] = stepMsg.copy(data = content, seq = event.seq)
                }
            }

            "session.next.tool.input.ended" -> {
                // 跳过（input 已在 tool.called 中完整提供）
            }

            "session.next.tool.called" -> {
                val stepMsg = findStepMessage(state, sessionID)
                if (stepMsg != null) {
                    val callID = data["callID"]?.jsonPrimitive?.content ?: ""
                    val truncatedData = DataTruncator.truncateEventData(event.type, event.data)
                    val content = updateToolContent(stepMsg.data, callID, truncatedData, "called")
                    state[stepMsg.id] = stepMsg.copy(data = content, seq = event.seq)
                }
            }

            "session.next.tool.progress" -> {
                val stepMsg = findStepMessage(state, sessionID)
                if (stepMsg != null) {
                    val callID = data["callID"]?.jsonPrimitive?.content ?: ""
                    val truncatedData = DataTruncator.truncateEventData(event.type, event.data)
                    val content = updateToolContent(stepMsg.data, callID, truncatedData, "progress")
                    state[stepMsg.id] = stepMsg.copy(data = content, seq = event.seq)
                }
            }

            "session.next.tool.success" -> {
                val stepMsg = findStepMessage(state, sessionID)
                if (stepMsg != null) {
                    val callID = data["callID"]?.jsonPrimitive?.content ?: ""
                    val truncatedData = DataTruncator.truncateEventData(event.type, event.data)
                    val content = updateToolContent(stepMsg.data, callID, truncatedData, "success")
                    state[stepMsg.id] = stepMsg.copy(data = content, seq = event.seq)
                }
            }

            "session.next.tool.failed" -> {
                val stepMsg = findStepMessage(state, sessionID)
                if (stepMsg != null) {
                    val callID = data["callID"]?.jsonPrimitive?.content ?: ""
                    val truncatedData = DataTruncator.truncateEventData(event.type, event.data)
                    val content = updateToolContent(stepMsg.data, callID, truncatedData, "failed")
                    state[stepMsg.id] = stepMsg.copy(data = content, seq = event.seq)
                }
            }

            "session.next.shell.started" -> {
                val callID = data["callID"]?.jsonPrimitive?.content ?: ""
                val msgID = "msg_shell_$callID"
                val truncatedData = DataTruncator.truncateEventData(event.type, event.data)
                state[msgID] = SessionMessageEntity(
                    id = msgID,
                    sessionID = sessionID,
                    type = "shell",
                    data = truncatedData,
                    createdAt = timestamp,
                    seq = event.seq,
                )
            }

            "session.next.shell.ended" -> {
                val callID = data["callID"]?.jsonPrimitive?.content ?: ""
                val msgID = "msg_shell_$callID"
                val existing = state[msgID]
                if (existing != null) {
                    val truncatedData = DataTruncator.truncateEventData(event.type, event.data)
                    state[msgID] = existing.copy(
                        data = truncatedData,
                        completedAt = timestamp,
                        seq = event.seq,
                    )
                }
            }

            "session.next.agent.switched" -> {
                val msgID = "msg_agent_${event.id}"
                val truncatedData = DataTruncator.truncateEventData(event.type, event.data)
                state[msgID] = SessionMessageEntity(
                    id = msgID,
                    sessionID = sessionID,
                    type = "agent-switched",
                    data = truncatedData,
                    createdAt = timestamp,
                    seq = event.seq,
                )
            }

            "session.next.model.switched" -> {
                val msgID = "msg_model_${event.id}"
                val truncatedData = DataTruncator.truncateEventData(event.type, event.data)
                state[msgID] = SessionMessageEntity(
                    id = msgID,
                    sessionID = sessionID,
                    type = "model-switched",
                    data = truncatedData,
                    createdAt = timestamp,
                    seq = event.seq,
                )
            }

            "session.next.compaction.started" -> {
                val msgID = "msg_compaction_${event.id}"
                val truncatedData = DataTruncator.truncateEventData(event.type, event.data)
                state[msgID] = SessionMessageEntity(
                    id = msgID,
                    sessionID = sessionID,
                    type = "compaction",
                    data = truncatedData,
                    createdAt = timestamp,
                    seq = event.seq,
                )
            }

            "session.next.compaction.ended" -> {
                val msgID = "msg_compaction_${event.id}"
                val existing = state.entries.find { it.value.sessionID == sessionID && it.value.type == "compaction" && it.key.startsWith("msg_compaction_") }?.value
                if (existing != null) {
                    val truncatedData = DataTruncator.truncateEventData(event.type, event.data)
                    state[existing.id] = existing.copy(
                        data = truncatedData,
                        seq = event.seq,
                    )
                }
            }

            "session.next.retried" -> {
                // retry 事件更新当前 step 消息
                val stepMsg = findStepMessage(state, sessionID)
                if (stepMsg != null) {
                    val truncatedData = DataTruncator.truncateEventData(event.type, event.data)
                    val merged = mergeAssistantData(stepMsg.data, truncatedData, "retried")
                    state[stepMsg.id] = stepMsg.copy(data = merged, seq = event.seq)
                }
            }
        }
    }

    private fun findStepMessage(
        state: Map<String, SessionMessageEntity>,
        sessionID: String,
    ): SessionMessageEntity? {
        return state.values.find {
            it.sessionID == sessionID && it.type == "assistant" && it.completedAt == null
        }
    }

    // --- JSON content manipulation helpers ---
    // 以下方法操作 SessionMessageEntity.data (JSON string) 中的 content 数组

    private val json = kotlinx.serialization.json.Json { ignoreUnknownKeys = true }

    private fun appendContentToData(data: String, contentItem: Map<String, Any?>): String {
        val obj = kotlinx.serialization.json.JsonObject(
            json.parseToJsonElement(data).jsonObject.toMutableMap().apply {
                val content = get("content")?.jsonArray ?: kotlinx.serialization.json.JsonArray(emptyList())
                val newItem = mapToJsonObject(contentItem)
                put("content", kotlinx.serialization.json.JsonArray(content + newItem))
            }
        )
        return obj.toString()
    }

    private fun updateLastTextContent(data: String, textEndedData: String): String {
        val obj = json.parseToJsonElement(data).jsonObject.toMutableMap()
        val content = obj["content"]?.jsonArray ?: return data
        val textObj = json.parseToJsonElement(textEndedData).jsonObject
        val text = textObj["text"]?.jsonPrimitive?.content ?: return data

        val newContent = content.toMutableList()
        for (i in newContent.lastIndex downTo 0) {
            if (newContent[i].jsonObject["type"]?.jsonPrimitive?.content == "text") {
                val updated = newContent[i].jsonObject.toMutableMap()
                updated["text"] = kotlinx.serialization.json.JsonPrimitive(text)
                newContent[i] = kotlinx.serialization.json.JsonObject(updated)
                break
            }
        }
        obj["content"] = kotlinx.serialization.json.JsonArray(newContent)
        return kotlinx.serialization.json.JsonObject(obj).toString()
    }

    private fun updateLastReasoningContent(data: String, reasoningEndedData: String): String {
        val obj = json.parseToJsonElement(data).jsonObject.toMutableMap()
        val content = obj["content"]?.jsonArray ?: return data
        val reasoningObj = json.parseToJsonElement(reasoningEndedData).jsonObject
        val text = reasoningObj["text"]?.jsonPrimitive?.content ?: return data

        val newContent = content.toMutableList()
        for (i in newContent.lastIndex downTo 0) {
            if (newContent[i].jsonObject["type"]?.jsonPrimitive?.content == "reasoning") {
                val updated = newContent[i].jsonObject.toMutableMap()
                updated["text"] = kotlinx.serialization.json.JsonPrimitive(text)
                newContent[i] = kotlinx.serialization.json.JsonObject(updated)
                break
            }
        }
        obj["content"] = kotlinx.serialization.json.JsonArray(newContent)
        return kotlinx.serialization.json.JsonObject(obj).toString()
    }

    private fun updateToolContent(data: String, callID: String, toolEventData: String, phase: String): String {
        val obj = json.parseToJsonElement(data).jsonObject.toMutableMap()
        val content = obj["content"]?.jsonArray ?: return data

        val newContent = content.toMutableList()
        val idx = newContent.indexOfFirst {
            it.jsonObject["callID"]?.jsonPrimitive?.content == callID ||
            it.jsonObject["id"]?.jsonPrimitive?.content == callID
        }
        if (idx >= 0) {
            val existing = newContent[idx].jsonObject.toMutableMap()
            val toolEventObj = json.parseToJsonElement(toolEventData).jsonObject

            when (phase) {
                "called" -> {
                    existing["name"] = toolEventObj["tool"] ?: existing["name"] ?: kotlinx.serialization.json.JsonPrimitive("")
                    existing["state"] = kotlinx.serialization.json.buildJsonObject {
                        put("status", kotlinx.serialization.json.JsonPrimitive("running"))
                        put("input", toolEventObj["input"] ?: kotlinx.serialization.json.JsonObject(emptyMap()))
                    }
                    toolEventObj["provider"]?.let { existing["provider"] = it }
                }
                "progress" -> {
                    val state = existing["state"]?.jsonObject?.toMutableMap() ?: mutableMapOf()
                    state["status"] = kotlinx.serialization.json.JsonPrimitive("running")
                    toolEventObj["structured"]?.let { state["structured"] = it }
                    existing["state"] = kotlinx.serialization.json.JsonObject(state)
                }
                "success" -> {
                    val state = existing["state"]?.jsonObject?.toMutableMap() ?: mutableMapOf()
                    state["status"] = kotlinx.serialization.json.JsonPrimitive("completed")
                    toolEventObj["structured"]?.let { state["structured"] = it }
                    existing["state"] = kotlinx.serialization.json.JsonObject(state)
                    toolEventObj["provider"]?.let { existing["provider"] = it }
                }
                "failed" -> {
                    val state = existing["state"]?.jsonObject?.toMutableMap() ?: mutableMapOf()
                    state["status"] = kotlinx.serialization.json.JsonPrimitive("error")
                    toolEventObj["error"]?.let { state["error"] = it }
                    existing["state"] = kotlinx.serialization.json.JsonObject(state)
                    toolEventObj["provider"]?.let { existing["provider"] = it }
                }
            }
            newContent[idx] = kotlinx.serialization.json.JsonObject(existing)
        }
        obj["content"] = kotlinx.serialization.json.JsonArray(newContent)
        return kotlinx.serialization.json.JsonObject(obj).toString()
    }

    private fun mergeAssistantData(existingData: String, newData: String, phase: String): String {
        val existing = json.parseToJsonElement(existingData).jsonObject.toMutableMap()
        val newJson = json.parseToJsonElement(newData).jsonObject

        when (phase) {
            "step.ended" -> {
                newJson["finish"]?.let { existing["finish"] = it }
                newJson["cost"]?.let { existing["cost"] = it }
                newJson["tokens"]?.let { existing["tokens"] = it }
            }
            "step.failed" -> {
                newJson["error"]?.let { existing["error"] = it }
            }
            "retried" -> {
                // retry 信息追加到 assistant data
                newJson["attempt"]?.let { existing["attempt"] = it }
            }
        }
        return kotlinx.serialization.json.JsonObject(existing).toString()
    }

    private fun mapToJsonObject(map: Map<String, Any?>): kotlinx.serialization.json.JsonObject {
        return kotlinx.serialization.json.buildJsonObject {
            map.forEach { (key, value) ->
                when (value) {
                    is String -> put(key, kotlinx.serialization.json.JsonPrimitive(value))
                    is Number -> put(key, kotlinx.serialization.json.JsonPrimitive(value))
                    is Boolean -> put(key, kotlinx.serialization.json.JsonPrimitive(value))
                    is Map<*, *> -> {
                        @Suppress("UNCHECKED_CAST")
                        put(key, mapToJsonObject(value as Map<String, Any?>))
                    }
                    null -> { /* skip nulls */ }
                    else -> put(key, kotlinx.serialization.json.JsonPrimitive(value.toString()))
                }
            }
        }
    }
}
```

**重要设计说明：**

EventReplayer 的消息 ID 生成策略：
- `user` → `msg_{eventID}`
- `synthetic` → `msg_syn_{eventID}`
- `assistant` → `msg_step_{eventID}`（基于 step.started 事件的 ID）
- `shell` → `msg_shell_{callID}`
- `compaction` → `msg_compaction_{eventID}`
- `agent-switched` → `msg_agent_{eventID}`
- `model-switched` → `msg_model_{eventID}`

这些 ID 在回放和数据库之间需要一致。如果服务端未来在事件 data 中提供 messageID，应优先使用。

- [ ] **Step 2: Build and verify**

Run: `.\gradlew.bat :core:data:compileDebugKotlin --no-daemon 2>&1 | Select-String -Pattern "^e:"`

Expected: No errors

---

## Task 7: Create SyncOrchestrator

**Files:**
- Create: `core/data/src/main/java/com/openmate/core/data/sync/SyncOrchestrator.kt`

- [ ] **Step 1: Create the orchestrator**

编排增量同步流程：读 DB → 调 API → 回放 → 写 DB → 更新 lastSeq。

```kotlin
package com.openmate.core.data.sync

import android.util.Log
import com.openmate.core.database.ActiveDatabaseProvider
import com.openmate.core.database.entity.SessionMessageEntity
import com.openmate.core.network.OpencodeApiClient
import javax.inject.Inject

class SyncOrchestrator @Inject constructor(
    private val api: OpencodeApiClient,
    private val dbProvider: ActiveDatabaseProvider,
) {
    companion object {
        private const val TAG = "SyncOrchestrator"
    }

    suspend fun syncAll(): Result<Int> {
        return runCatching {
            val db = dbProvider.getActive()
            val syncStates = db.syncStateDao().getAll()
            val lastKnownSeqs = syncStates.associate { it.sessionID to it.lastSeq }
            val events = api.syncHistory(lastKnownSeqs)
            if (events.isEmpty()) {
                Log.d(TAG, "No new events")
                return@runCatching 0
            }
            val grouped = events.groupBy { it.aggregate_id }
            var totalProcessed = 0

            for ((sessionID, sessionEvents) in grouped) {
                val processed = replayAndSave(sessionID, sessionEvents)
                totalProcessed += processed
            }

            Log.d(TAG, "Synced $totalProcessed events across ${grouped.size} sessions")
            totalProcessed
        }
    }

    suspend fun syncSession(sessionID: String): Result<Int> {
        return runCatching {
            val db = dbProvider.getActive()
            val lastSeq = db.syncStateDao().getLastSeq(sessionID) ?: 0
            val events = api.syncHistory(mapOf(sessionID to lastSeq))
                .filter { it.aggregate_id == sessionID }
            if (events.isEmpty()) return@runCatching 0
            replayAndSave(sessionID, events)
        }
    }

    private suspend fun replayAndSave(
        sessionID: String,
        events: List<com.openmate.core.network.dto.SyncEventDto>,
    ): Int {
        val db = dbProvider.getActive()

        val existingMessages = db.sessionMessageDao().let { dao ->
            val all = dao.getBySessionSync(sessionID)
            all.associateBy { it.id }
        }

        val results = EventReplayer.replay(events, existingMessages)

        if (results.isNotEmpty()) {
            db.sessionMessageDao().upsertAll(results)
        }

        val maxSeq = events.maxOf { it.seq }
        db.syncStateDao().upsert(
            com.openmate.core.database.entity.SyncStateEntity(
                sessionID = sessionID,
                lastSeq = maxSeq,
                lastSyncAt = System.currentTimeMillis(),
            )
        )

        return events.size
    }
}
```

注意：`SessionMessageDao.getBySessionSync()` 是一个非 Flow 的同步查询方法，需要在 step-02 的 DAO 中补充。

**补充 DAO 方法**（在 step-02 的 SessionMessageDao 中添加）：

```kotlin
@Query("SELECT * FROM SessionMessageEntity WHERE sessionID = :sessionID ORDER BY createdAt ASC")
suspend fun getBySessionSync(sessionID: String): List<SessionMessageEntity>
```

- [ ] **Step 2: Build and verify**

Run: `.\gradlew.bat :core:data:compileDebugKotlin --no-daemon 2>&1 | Select-String -Pattern "^e:"`

Expected: No errors

---

## Task 8: Create EventSyncRepositoryImpl

**Files:**
- Create: `core/data/src/main/java/com/openmate/core/data/repository/EventSyncRepositoryImpl.kt`

- [ ] **Step 1: Create the implementation**

```kotlin
package com.openmate.core.data.repository

import com.openmate.core.data.sync.SyncOrchestrator
import com.openmate.core.database.ActiveDatabaseProvider
import com.openmate.core.domain.model.SessionMessage
import com.openmate.core.domain.repository.EventSyncRepository
import com.openmate.core.network.OpencodeApiClient
import com.openmate.core.network.dto.toDomain
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject

class EventSyncRepositoryImpl @Inject constructor(
    private val syncOrchestrator: SyncOrchestrator,
    private val api: OpencodeApiClient,
    private val dbProvider: ActiveDatabaseProvider,
) : EventSyncRepository {

    override suspend fun syncEvents(sessionIDs: Map<String, Int>): Result<Int> {
        return syncOrchestrator.syncAll()
    }

    override fun observeSessionMessages(sessionID: String): Flow<List<SessionMessage>> {
        val db = dbProvider.getActive()
        return db.sessionMessageDao().observeBySession(sessionID).map { entities ->
            entities.mapNotNull { entity ->
                runCatching { entityToDomain(entity) }.getOrNull()
            }
        }
    }

    override suspend fun fetchFullContent(sessionID: String, messageID: String): Result<SessionMessage?> {
        return runCatching {
            val db = dbProvider.getActive()
            val cached = db.sessionMessageFullContentDao().getById(messageID)
            if (cached != null) {
                val fullMsg = api.getMessage(sessionID, messageID).toDomain()
                db.sessionMessageFullContentDao().upsert(
                    com.openmate.core.database.entity.SessionMessageFullContentEntity(
                        messageID = messageID,
                        sessionID = sessionID,
                        data = cached.data,
                        fetchedAt = System.currentTimeMillis(),
                    )
                )
                fullMsg
            } else {
                val fullMsg = api.getMessage(sessionID, messageID).toDomain()
                db.sessionMessageFullContentDao().upsert(
                    com.openmate.core.database.entity.SessionMessageFullContentEntity(
                        messageID = messageID,
                        sessionID = sessionID,
                        data = "",
                        fetchedAt = System.currentTimeMillis(),
                    )
                )
                fullMsg
            }
        }
    }

    override suspend fun getLastSeq(sessionID: String): Int? {
        val db = dbProvider.getActive()
        return db.syncStateDao().getLastSeq(sessionID)
    }

    private fun entityToDomain(entity: com.openmate.core.database.entity.SessionMessageEntity): SessionMessage {
        // 按 type 解析 data JSON 为域模型
        // 这里用简化的 JSON 解析，实际实现需处理每种类型的字段映射
        TODO("Implement per-type deserialization in implementation phase")
    }
}
```

注意：`entityToDomain` 的完整实现需要在编码阶段完成，这里标记 TODO。此方法是 SessionMessageEntity.data (JSON) → SessionMessage 域模型的反序列化。

- [ ] **Step 2: Build and verify**

Run: `.\gradlew.bat :core:data:compileDebugKotlin --no-daemon 2>&1 | Select-String -Pattern "^e:"`

Expected: 编译错误在 `entityToDomain` 的 `TODO()` 处是可接受的（运行时抛异常）。如果需要编译通过，可暂时返回 `SessionMessage.Synthetic(id="", sessionID="", createdAt=0, text="")` 作为占位。

---

## Design Notes

### EventReplayer 的状态管理

- 使用内存 `MutableMap<String, SessionMessageEntity>` 跟踪当前会话消息状态
- 输入：事件列表 + 已有 DB 消息
- 输出：所有变更后的消息实体列表
- 适用于单次同步的批量处理，不跨调用持久化状态

### 消息 ID 生成策略

当前 opencode 事件 data 中**不包含** messageID。使用以下规则生成确定性 ID：
- 这些 ID 在 DB 中唯一，且在多次同步中对同一事件产生相同 ID
- 如果未来服务端在事件 data 中提供 messageID，应优先使用服务端 ID

### 截断规则的位置

截断逻辑分两层：
1. **DataTruncator**：按事件类型截断大字段（事件级截断）
2. **ToolDataTruncator**：按工具类型截断 input/output（工具级截断）

两层解耦，方便独立测试和修改。

### 与旧锚点方案的共存

SyncOrchestrator 独立于现有 MessageRepositoryImpl，不修改旧代码。两套数据同时写入 DB，UI 选择数据源。

---

## Verification

- [ ] `.\gradlew.bat :core:domain:compileDebugKotlin --no-daemon 2>&1 | Select-String -Pattern "^e:"` — zero errors
- [ ] `.\gradlew.bat :core:data:compileDebugKotlin --no-daemon 2>&1 | Select-String -Pattern "^e:"` — zero errors (or only TODO-related)
- [ ] DataTruncator 所有事件类型都有对应截断方法
- [ ] ToolDataTruncator 覆盖 16 个内置工具 + 未知工具兜底
- [ ] EventReplayer 覆盖所有 26 种事件类型（delta 除外）

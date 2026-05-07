# Step 04: 同步引擎

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development or superpowers:executing-plans to implement this plan task-by-task.

**Goal:** 实现移动端核心同步逻辑——SessionMessage 域模型、EventReplayer（事件→消息回放）、SyncOrchestrator（同步编排器）、截断规则。

**Architecture:** EventReplayer 参考 TUI `sync-v2.tsx` 的逻辑，将 session.next.* 事件转换为 SessionMessage INSERT/UPDATE。SyncOrchestrator 编排 init→replay→增量流程。域模型用 sealed class 表达 7 种 SessionMessage 类型。

**Tech Stack:** Kotlin, kotlinx.serialization, Room, Hilt

**Design Doc:** `docs/superpowers/specs/2026-05-06-mobile-incremental-sync-design.md`
**Reference:** `D:\github\opencode\packages\opencode\src\cli\cmd\tui\context\sync-v2.tsx`（307 行）

---

## File Structure

```
core/domain/src/main/java/com/openmate/core/domain/model/
├── SessionMessage.kt             — 7 种消息类型的 sealed class
└── SyncEvent.kt                  — 同步事件域模型（从 DTO 映射）

core/data/src/main/java/com/openmate/core/data/
├── sync/
│   ├── EventReplayer.kt          — 事件→SessionMessage 回放器
│   ├── MobileTruncator.kt        — 移动端截断规则（与 Bridge 侧一致）
│   ├── SyncOrchestrator.kt       — 同步编排器
│   └── SessionMessageMapper.kt   — Entity↔Domain↔DTO 映射
├── repository/
│   └── SessionMessageRepositoryImpl.kt — 新 Repository（替代旧的 MessageRepository）
```

---

## Task 1: SessionMessage 域模型

**Files:**
- Create: `core/domain/src/main/java/com/openmate/core/domain/model/SessionMessage.kt`

- [ ] **Step 1: 创建 SessionMessage sealed class**

```kotlin
package com.openmate.core.domain.model

import kotlinx.serialization.json.JsonObject

sealed class SessionMessage {
    abstract val id: String
    abstract val timeCreated: Long

    data class User(
        override val id: String,
        val text: String,
        val files: List<FileAttachment> = emptyList(),
        val agents: List<AgentAttachment> = emptyList(),
        override val timeCreated: Long,
    ) : SessionMessage()

    data class Assistant(
        override val id: String,
        val agent: String,
        val model: ModelRef,
        val content: List<AssistantContent> = emptyList(),
        val snapshot: Snapshot? = null,
        val finish: String? = null,
        val cost: Double? = null,
        val tokens: Tokens? = null,
        val error: ErrorInfo? = null,
        val timeCompleted: Long? = null,
        override val timeCreated: Long,
    ) : SessionMessage()

    data class AgentSwitched(
        override val id: String,
        val agent: String,
        override val timeCreated: Long,
    ) : SessionMessage()

    data class ModelSwitched(
        override val id: String,
        val model: ModelRef,
        override val timeCreated: Long,
    ) : SessionMessage()

    data class Shell(
        override val id: String,
        val callID: String,
        val command: String,
        val output: String = "",
        val timeCompleted: Long? = null,
        override val timeCreated: Long,
    ) : SessionMessage()

    data class Synthetic(
        override val id: String,
        val sessionID: String,
        val text: String,
        override val timeCreated: Long,
    ) : SessionMessage()

    data class Compaction(
        override val id: String,
        val reason: String,
        val summary: String = "",
        val include: String? = null,
        override val timeCreated: Long,
    ) : SessionMessage()
}

data class ModelRef(val id: String, val providerID: String, val variant: String = "default")
data class FileAttachment(val uri: String, val mime: String, val name: String? = null, val description: String? = null)
data class AgentAttachment(val name: String)
data class Snapshot(val start: String? = null, val end: String? = null)
data class ErrorInfo(val type: String = "unknown", val message: String)
data class Tokens(val total: Long, val input: Long, val output: Long, val reasoning: Long, val cache: CacheTokens)
data class CacheTokens(val read: Long, val write: Long)

sealed class AssistantContent {
    data class Text(val text: String) : AssistantContent()
    data class Reasoning(val id: String, val text: String) : AssistantContent()
    data class Tool(
        val id: String,
        val name: String,
        val provider: ProviderInfo? = null,
        val state: ToolState,
        val timeCreated: Long? = null,
        val timeRan: Long? = null,
        val timeCompleted: Long? = null,
    ) : AssistantContent()
}

data class ProviderInfo(val executed: Boolean, val metadata: JsonObject? = null)

sealed class ToolState {
    data class Pending(val input: String) : ToolState()
    data class Running(val input: JsonObject, val structured: JsonObject, val content: List<ToolContent>) : ToolState()
    data class Completed(val input: JsonObject, val structured: JsonObject, val content: List<ToolContent>) : ToolState()
    data class Error(val error: ErrorInfo, val input: JsonObject, val structured: JsonObject, val content: List<ToolContent>) : ToolState()
}

sealed class ToolContent {
    data class Text(val text: String) : ToolContent()
    data class File(val uri: String, val mime: String, val name: String? = null) : ToolContent()
}
```

- [ ] **Step 2: Commit**

```
git add core/domain/src/main/java/com/openmate/core/domain/model/SessionMessage.kt
git commit -m "feat(domain): add SessionMessage sealed class model"
```

---

## Task 2: Entity↔Domain 映射

**Files:**
- Create: `core/data/src/main/java/com/openmate/core/data/sync/SessionMessageMapper.kt`

- [ ] **Step 1: 创建映射器**

核心思路：SessionMessage 的 data 字段是 JSON 字符串。存储时将域模型序列化为 JSON 写入 data。读取时反序列化。但考虑到域模型和 JSON 结构的复杂性，**选择用 JSON 字符串透传方案**：

- Entity.data 存储原始 JSON（截断后），UI 层直接解析 JSON 渲染
- Domain 层主要在 EventReplayer 中使用，replayer 输出的就是 JSON data

```kotlin
package com.openmate.core.data.sync

import com.openmate.core.database.entity.SessionMessageEntity
import com.openmate.core.network.dto.SyncMessageDto

object SessionMessageMapper {

    fun dtoToEntity(dto: SyncMessageDto): SessionMessageEntity {
        return SessionMessageEntity(
            id = dto.id,
            sessionId = dto.sessionId,
            type = dto.type,
            data = dto.data.toString(),
            timeCreated = dto.timeCreated,
            timeUpdated = dto.timeUpdated,
        )
    }

    fun entityToDataJson(entity: SessionMessageEntity): String {
        return entity.data
    }
}
```

- [ ] **Step 2: Commit**

```
git add core/data/src/main/java/com/openmate/core/data/sync/SessionMessageMapper.kt
git commit -m "feat(data): add session message DTO-Entity mapper"
```

---

## Task 3: EventReplayer

**Files:**
- Create: `core/data/src/main/java/com/openmate/core/data/sync/EventReplayer.kt`

这是最核心、最复杂的部分。直接参考 TUI `sync-v2.tsx` 的逻辑逐事件类型翻译为 Kotlin。

- [ ] **Step 1: 创建 EventReplayer**

```kotlin
package com.openmate.core.data.sync

import kotlinx.serialization.json.*
import com.openmate.core.database.entity.SessionMessageEntity

sealed class ReplayChange {
    data class Insert(val entity: SessionMessageEntity) : ReplayChange()
    data class Update(
        val id: String,
        val data: JsonObject,
        val timeUpdated: Long,
    ) : ReplayChange()
}

class EventReplayer {

    private data class ReplayState(
        val messages: MutableList<JsonObject> = mutableListOf(),
        var currentAssistantIndex: Int = -1,
        var currentCompactionIndex: Int = -1,
        val activeShells: MutableMap<String, Int> = mutableMapOf(),
    )

    fun replay(
        events: List<ReplayEvent>,
        sessionId: String,
    ): List<ReplayChange> {
        val state = ReplayState()
        val changes = mutableListOf<ReplayChange>()

        for (event in events) {
            val props = event.data
            val timestamp = props["timestamp"]?.jsonPrimitive?.longOrNull ?: System.currentTimeMillis()
            val eventSessionId = props["sessionID"]?.jsonPrimitive?.contentOrNull ?: sessionId

            when (event.type.substringBeforeLast(".")) {
                "session.next.prompted" -> {
                    val msg = buildJsonObject {
                        put("type", "user")
                        put("id", event.id)
                        put("text", props["prompt"]?.jsonObject?.get("text")?.jsonPrimitive?.contentOrNull ?: "")
                        put("files", props["prompt"]?.jsonObject?.get("files") ?: JsonArray(emptyList()))
                        put("agents", props["prompt"]?.jsonObject?.get("agents") ?: JsonArray(emptyList()))
                        put("time", buildJsonObject { put("created", timestamp) })
                    }
                    state.messages.add(0, msg)
                    changes += ReplayChange.Insert(msgToEntity(event.id, eventSessionId, "user", msg, timestamp))
                }

                "session.next.agent.switched" -> {
                    val msg = buildJsonObject {
                        put("type", "agent-switched")
                        put("id", event.id)
                        put("agent", props["agent"]?.jsonPrimitive?.contentOrNull ?: "")
                        put("time", buildJsonObject { put("created", timestamp) })
                    }
                    state.messages.add(0, msg)
                    changes += ReplayChange.Insert(msgToEntity(event.id, eventSessionId, "agent-switched", msg, timestamp))
                }

                "session.next.model.switched" -> {
                    val model = props["model"]?.jsonObject ?: buildJsonObject {}
                    val msg = buildJsonObject {
                        put("type", "model-switched")
                        put("id", event.id)
                        put("model", model)
                        put("time", buildJsonObject { put("created", timestamp) })
                    }
                    state.messages.add(0, msg)
                    changes += ReplayChange.Insert(msgToEntity(event.id, eventSessionId, "model-switched", msg, timestamp))
                }

                "session.next.synthetic" -> {
                    val msg = buildJsonObject {
                        put("type", "synthetic")
                        put("id", event.id)
                        put("sessionID", eventSessionId)
                        put("text", props["text"]?.jsonPrimitive?.contentOrNull ?: "")
                        put("time", buildJsonObject { put("created", timestamp) })
                    }
                    state.messages.add(0, msg)
                    changes += ReplayChange.Insert(msgToEntity(event.id, eventSessionId, "synthetic", msg, timestamp))
                }

                "session.next.shell.started" -> {
                    val callID = props["callID"]?.jsonPrimitive?.contentOrNull ?: ""
                    val msg = buildJsonObject {
                        put("type", "shell")
                        put("id", event.id)
                        put("callID", callID)
                        put("command", props["command"]?.jsonPrimitive?.contentOrNull ?: "")
                        put("output", "")
                        put("time", buildJsonObject { put("created", timestamp) })
                    }
                    state.messages.add(0, msg)
                    state.activeShells[callID] = 0
                    changes += ReplayChange.Insert(msgToEntity(event.id, eventSessionId, "shell", msg, timestamp))
                }

                "session.next.shell.ended" -> {
                    val callID = props["callID"]?.jsonPrimitive?.contentOrNull ?: ""
                    val idx = state.activeShells[callID] ?: continue
                    if (idx < state.messages.size) {
                        val existing = state.messages[idx].toMutableMap()
                        existing["output"] = JsonPrimitive(props["output"]?.jsonPrimitive?.contentOrNull ?: "")
                        val time = (existing["time"]?.jsonObject?.toMutableMap() ?: mutableMapOf())
                        time["completed"] = JsonPrimitive(timestamp)
                        existing["time"] = JsonObject(time)
                        val updated = JsonObject(existing)
                        state.messages[idx] = updated
                        val id = updated["id"]?.jsonPrimitive?.contentOrNull ?: continue
                        changes += ReplayChange.Update(id, updated, timestamp)
                    }
                }

                "session.next.step.started" -> {
                    // 关闭前一个未完成的 assistant
                    closeCurrentAssistant(state, changes, timestamp)

                    val snapshot = props["snapshot"]?.jsonPrimitive?.contentOrNull
                    val msg = buildJsonObject {
                        put("type", "assistant")
                        put("id", event.id)
                        put("agent", props["agent"]?.jsonPrimitive?.contentOrNull ?: "")
                        put("model", props["model"]?.jsonObject ?: buildJsonObject {})
                        put("content", JsonArray(emptyList()))
                        if (snapshot != null) {
                            put("snapshot", buildJsonObject { put("start", snapshot) })
                        }
                        put("time", buildJsonObject { put("created", timestamp) })
                    }
                    state.messages.add(0, msg)
                    state.currentAssistantIndex = 0
                    changes += ReplayChange.Insert(msgToEntity(event.id, eventSessionId, "assistant", msg, timestamp))
                }

                "session.next.step.ended" -> {
                    val idx = state.currentAssistantIndex
                    if (idx < 0 || idx >= state.messages.size) continue
                    val existing = state.messages[idx].toMutableMap()
                    existing["finish"] = JsonPrimitive(props["finish"]?.jsonPrimitive?.contentOrNull ?: "")
                    props["cost"]?.jsonPrimitive?.longOrNull?.let { existing["cost"] = JsonPrimitive(it.toDouble()) }
                    props["tokens"]?.jsonObject?.let { existing["tokens"] = it }
                    val snapEnd = props["snapshot"]?.jsonPrimitive?.contentOrNull
                    if (snapEnd != null) {
                        val snap = (existing["snapshot"]?.jsonObject?.toMutableMap() ?: mutableMapOf())
                        snap["end"] = JsonPrimitive(snapEnd)
                        existing["snapshot"] = JsonObject(snap)
                    }
                    val time = (existing["time"]?.jsonObject?.toMutableMap() ?: mutableMapOf())
                    time["completed"] = JsonPrimitive(timestamp)
                    existing["time"] = JsonObject(time)
                    val updated = JsonObject(existing)
                    state.messages[idx] = updated
                    val id = updated["id"]?.jsonPrimitive?.contentOrNull ?: continue
                    changes += ReplayChange.Update(id, updated, timestamp)
                }

                "session.next.step.failed" -> {
                    val idx = state.currentAssistantIndex
                    if (idx < 0 || idx >= state.messages.size) continue
                    val existing = state.messages[idx].toMutableMap()
                    existing["finish"] = JsonPrimitive("error")
                    props["error"]?.jsonObject?.let { existing["error"] = it }
                    val time = (existing["time"]?.jsonObject?.toMutableMap() ?: mutableMapOf())
                    time["completed"] = JsonPrimitive(timestamp)
                    existing["time"] = JsonObject(time)
                    val updated = JsonObject(existing)
                    state.messages[idx] = updated
                    val id = updated["id"]?.jsonPrimitive?.contentOrNull ?: continue
                    changes += ReplayChange.Update(id, updated, timestamp)
                }

                "session.next.text.started" -> {
                    val assistant = getMutableAssistant(state) ?: continue
                    val content = assistant["content"]?.jsonArray?.toMutableList() ?: mutableListOf()
                    content.add(buildJsonObject { put("type", "text"); put("text", "") })
                    assistant["content"] = JsonArray(content)
                }

                "session.next.text.ended" -> {
                    val assistant = getMutableAssistant(state) ?: continue
                    val content = assistant["content"]?.jsonArray?.toMutableList() ?: continue
                    val lastTextIdx = content.indexOfLast { it.jsonObject["type"]?.jsonPrimitive?.contentOrNull == "text" }
                    if (lastTextIdx >= 0) {
                        val textObj = content[lastTextIdx].jsonObject.toMutableMap()
                        textObj["text"] = JsonPrimitive(props["text"]?.jsonPrimitive?.contentOrNull ?: "")
                        content[lastTextIdx] = JsonObject(textObj)
                        assistant["content"] = JsonArray(content)
                    }
                    emitAssistantUpdate(state, changes, timestamp)
                }

                "session.next.tool.input.started" -> {
                    val assistant = getMutableAssistant(state) ?: continue
                    val callID = props["callID"]?.jsonPrimitive?.contentOrNull ?: ""
                    val name = props["name"]?.jsonPrimitive?.contentOrNull ?: ""
                    val content = assistant["content"]?.jsonArray?.toMutableList() ?: mutableListOf()
                    content.add(buildJsonObject {
                        put("type", "tool")
                        put("id", callID)
                        put("name", name)
                        put("time", buildJsonObject { put("created", timestamp) })
                        put("state", buildJsonObject {
                            put("status", "pending")
                            put("input", "")
                        })
                    })
                    assistant["content"] = JsonArray(content)
                }

                "session.next.tool.input.ended" -> { /* no-op */ }

                "session.next.tool.called" -> {
                    val assistant = getMutableAssistant(state) ?: continue
                    val callID = props["callID"]?.jsonPrimitive?.contentOrNull ?: ""
                    val content = assistant["content"]?.jsonArray ?: continue
                    val toolIdx = findToolIndex(content, callID) ?: continue
                    val mutableContent = content.toMutableList()
                    val toolObj = mutableContent[toolIdx].jsonObject.toMutableMap()
                    val timeObj = toolObj["time"]?.jsonObject?.toMutableMap() ?: mutableMapOf()
                    timeObj["ran"] = JsonPrimitive(timestamp)
                    toolObj["time"] = JsonObject(timeObj)
                    toolObj["provider"] = (props["provider"]?.jsonObject ?: buildJsonObject {})
                    toolObj["state"] = buildJsonObject {
                        put("status", "running")
                        put("input", props["input"] ?: JsonObject(emptyMap()))
                        put("structured", JsonObject(emptyMap()))
                        put("content", JsonArray(emptyList()))
                    }
                    mutableContent[toolIdx] = JsonObject(toolObj)
                    assistant["content"] = JsonArray(mutableContent)
                }

                "session.next.tool.success" -> {
                    val assistant = getMutableAssistant(state) ?: continue
                    val callID = props["callID"]?.jsonPrimitive?.contentOrNull ?: ""
                    val content = assistant["content"]?.jsonArray ?: continue
                    val toolIdx = findToolIndex(content, callID) ?: continue
                    val mutableContent = content.toMutableList()
                    val toolObj = mutableContent[toolIdx].jsonObject.toMutableMap()
                    val prevState = toolObj["state"]?.jsonObject ?: continue
                    val prevInput = prevState["input"] ?: JsonObject(emptyMap())
                    toolObj["state"] = buildJsonObject {
                        put("status", "completed")
                        put("input", prevInput)
                        put("structured", props["structured"] ?: JsonObject(emptyMap()))
                        put("content", props["content"] ?: JsonArray(emptyList()))
                    }
                    toolObj["provider"] = (props["provider"]?.jsonObject ?: buildJsonObject {})
                    val timeObj = toolObj["time"]?.jsonObject?.toMutableMap() ?: mutableMapOf()
                    timeObj["completed"] = JsonPrimitive(timestamp)
                    toolObj["time"] = JsonObject(timeObj)
                    mutableContent[toolIdx] = JsonObject(toolObj)
                    assistant["content"] = JsonArray(mutableContent)
                    emitAssistantUpdate(state, changes, timestamp)
                }

                "session.next.tool.failed" -> {
                    val assistant = getMutableAssistant(state) ?: continue
                    val callID = props["callID"]?.jsonPrimitive?.contentOrNull ?: ""
                    val content = assistant["content"]?.jsonArray ?: continue
                    val toolIdx = findToolIndex(content, callID) ?: continue
                    val mutableContent = content.toMutableList()
                    val toolObj = mutableContent[toolIdx].jsonObject.toMutableMap()
                    val prevState = toolObj["state"]?.jsonObject ?: continue
                    toolObj["state"] = buildJsonObject {
                        put("status", "error")
                        put("error", props["error"] ?: buildJsonObject {})
                        put("input", prevState["input"] ?: JsonObject(emptyMap()))
                        put("structured", prevState["structured"] ?: JsonObject(emptyMap()))
                        put("content", prevState["content"] ?: JsonArray(emptyList()))
                    }
                    toolObj["provider"] = (props["provider"]?.jsonObject ?: buildJsonObject {})
                    val timeObj = toolObj["time"]?.jsonObject?.toMutableMap() ?: mutableMapOf()
                    timeObj["completed"] = JsonPrimitive(timestamp)
                    toolObj["time"] = JsonObject(timeObj)
                    mutableContent[toolIdx] = JsonObject(toolObj)
                    assistant["content"] = JsonArray(mutableContent)
                    emitAssistantUpdate(state, changes, timestamp)
                }

                "session.next.reasoning.started" -> {
                    val assistant = getMutableAssistant(state) ?: continue
                    val reasoningID = props["reasoningID"]?.jsonPrimitive?.contentOrNull ?: ""
                    val content = assistant["content"]?.jsonArray?.toMutableList() ?: mutableListOf()
                    content.add(buildJsonObject {
                        put("type", "reasoning")
                        put("id", reasoningID)
                        put("text", "")
                    })
                    assistant["content"] = JsonArray(content)
                }

                "session.next.reasoning.ended" -> {
                    val assistant = getMutableAssistant(state) ?: continue
                    val reasoningID = props["reasoningID"]?.jsonPrimitive?.contentOrNull ?: ""
                    val content = assistant["content"]?.jsonArray ?: continue
                    val idx = content.indexOfLast {
                        it.jsonObject["type"]?.jsonPrimitive?.contentOrNull == "reasoning" &&
                        it.jsonObject["id"]?.jsonPrimitive?.contentOrNull == reasoningID
                    }
                    if (idx >= 0) {
                        val mutableContent = content.toMutableList()
                        val obj = mutableContent[idx].jsonObject.toMutableMap()
                        obj["text"] = JsonPrimitive(props["text"]?.jsonPrimitive?.contentOrNull ?: "")
                        mutableContent[idx] = JsonObject(obj)
                        assistant["content"] = JsonArray(mutableContent)
                    }
                }

                "session.next.compaction.started" -> {
                    val msg = buildJsonObject {
                        put("type", "compaction")
                        put("id", event.id)
                        put("reason", props["reason"]?.jsonPrimitive?.contentOrNull ?: "auto")
                        put("summary", "")
                        put("time", buildJsonObject { put("created", timestamp) })
                    }
                    state.messages.add(0, msg)
                    state.currentCompactionIndex = 0
                    changes += ReplayChange.Insert(msgToEntity(event.id, eventSessionId, "compaction", msg, timestamp))
                }

                "session.next.compaction.ended" -> {
                    val idx = state.currentCompactionIndex
                    if (idx < 0 || idx >= state.messages.size) continue
                    val existing = state.messages[idx].toMutableMap()
                    existing["summary"] = JsonPrimitive(props["text"]?.jsonPrimitive?.contentOrNull ?: "")
                    props["include"]?.jsonPrimitive?.contentOrNull?.let { existing["include"] = JsonPrimitive(it) }
                    val updated = JsonObject(existing)
                    state.messages[idx] = updated
                    val id = updated["id"]?.jsonPrimitive?.contentOrNull ?: continue
                    changes += ReplayChange.Update(id, updated, timestamp)
                }

                // 跳过: delta, retried, progress, input.delta, input.ended
            }
        }

        return changes
    }

    private fun closeCurrentAssistant(state: ReplayState, changes: MutableList<ReplayChange>, timestamp: Long) {
        val idx = state.currentAssistantIndex
        if (idx < 0 || idx >= state.messages.size) return
        val msg = state.messages[idx]
        if (msg["time"]?.jsonObject?.containsKey("completed") == false) {
            val existing = msg.toMutableMap()
            val time = (existing["time"]?.jsonObject?.toMutableMap() ?: mutableMapOf())
            time["completed"] = JsonPrimitive(timestamp)
            existing["time"] = JsonObject(time)
            val updated = JsonObject(existing)
            state.messages[idx] = updated
            val id = updated["id"]?.jsonPrimitive?.contentOrNull ?: return
            changes += ReplayChange.Update(id, updated, timestamp)
        }
    }

    private fun getMutableAssistant(state: ReplayState): MutableMap<String, JsonElement>? {
        val idx = state.currentAssistantIndex
        if (idx < 0 || idx >= state.messages.size) return null
        val existing = state.messages[idx].toMutableMap()
        state.messages[idx] = JsonObject(existing)
        return existing
    }

    private fun emitAssistantUpdate(state: ReplayState, changes: MutableList<ReplayChange>, timestamp: Long) {
        val idx = state.currentAssistantIndex
        if (idx < 0 || idx >= state.messages.size) return
        val msg = state.messages[idx]
        val id = msg["id"]?.jsonPrimitive?.contentOrNull ?: return
        changes += ReplayChange.Update(id, msg as JsonObject, timestamp)
    }

    private fun findToolIndex(content: JsonArray, callID: String): Int? {
        return content.indexOfLast {
            it.jsonObject["type"]?.jsonPrimitive?.contentOrNull == "tool" &&
            it.jsonObject["id"]?.jsonPrimitive?.contentOrNull == callID
        }.takeIf { it >= 0 }
    }

    private fun msgToEntity(id: String, sessionId: String, type: String, data: JsonObject, timeCreated: Long): SessionMessageEntity {
        return SessionMessageEntity(
            id = id,
            sessionId = sessionId,
            type = type,
            data = data.toString(),
            timeCreated = timeCreated,
            timeUpdated = timeCreated,
        )
    }
}

data class ReplayEvent(
    val id: String,
    val type: String,
    val data: JsonObject,
)
```

- [ ] **Step 2: Commit**

```
git add core/data/src/main/java/com/openmate/core/data/sync/EventReplayer.kt
git commit -m "feat(data): implement EventReplayer based on TUI sync-v2.tsx"
```

---

## Task 4: 移动端截断规则

**Files:**
- Create: `core/data/src/main/java/com/openmate/core/data/sync/MobileTruncator.kt`

- [ ] **Step 1: 创建 MobileTruncator**

逻辑与 Bridge 侧 `truncate.rs` 一致，但用 Kotlin 实现。输入/输出都是 `JsonObject`。

核心函数：

```kotlin
object MobileTruncator {
    fun truncate(type: String, data: JsonObject): JsonObject
    private fun truncateAssistant(data: JsonObject): JsonObject
    private fun truncateTool(name: String, state: JsonObject): JsonObject
    private fun truncateCompaction(data: JsonObject): JsonObject
    private fun truncateShell(data: JsonObject): JsonObject
    private fun truncateBashOutput(output: String, head: Int = 5, tail: Int = 5): String
    private fun truncateReasoning(text: String, keepChars: Int = 100): String
}
```

规则完全参照 `docs/superpowers/specs/2026-05-06-mobile-sync-truncation-rules.md`。此处不展开完整实现（与 Bridge 侧 truncate.rs 对称），实现时逐条对照文档编写。

- [ ] **Step 2: Commit**

```
git add core/data/src/main/java/com/openmate/core/data/sync/MobileTruncator.kt
git commit -m "feat(data): implement mobile-side truncation rules"
```

---

## Task 5: SyncOrchestrator

**Files:**
- Create: `core/data/src/main/java/com/openmate/core/data/sync/SyncOrchestrator.kt`
- Create: `core/data/src/main/java/com/openmate/core/data/repository/SessionMessageRepositoryImpl.kt`

- [ ] **Step 1: 创建 SessionMessageRepositoryImpl**

```kotlin
package com.openmate.core.data.repository

import com.openmate.core.database.dao.SessionMessageDao
import com.openmate.core.database.dao.SyncStateDao
import com.openmate.core.database.dao.SessionMessageFullContentDao
import com.openmate.core.database.entity.SessionMessageEntity
import com.openmate.core.database.entity.SyncStateEntity
import com.openmate.core.database.entity.SessionMessageFullContentEntity
import com.openmate.core.data.sync.SessionMessageMapper
import com.openmate.core.data.sync.MobileTruncator
import com.openmate.core.data.sync.EventReplayer
import com.openmate.core.data.sync.ReplayChange
import com.openmate.core.data.sync.ReplayEvent
import com.openmate.core.domain.repository.SessionMessageRepository
import com.openmate.core.network.SyncApiClient
import kotlinx.coroutines.flow.Flow
import javax.inject.Inject

class SessionMessageRepositoryImpl @Inject constructor(
    private val syncApiClient: SyncApiClient,
    private val sessionMessageDao: SessionMessageDao,
    private val syncStateDao: SyncStateDao,
    private val fullContentDao: SessionMessageFullContentDao,
) : SessionMessageRepository {

    override fun observeMessages(sessionId: String): Flow<List<SessionMessageEntity>> {
        return sessionMessageDao.observeBySession(sessionId)
    }

    override suspend fun initSync(sessionId: String, limit: Int) {
        val response = syncApiClient.init(sessionId, limit)
        val entities = response.messages.map { dto ->
            val truncatedData = MobileTruncator.truncate(dto.type, dto.data)
            dto.copy(data = truncatedData).let { SessionMessageMapper.dtoToEntity(it) }
        }
        sessionMessageDao.replaceAllForSession(sessionId, entities)
        response.maxSeq?.let { seq ->
            syncStateDao.upsert(SyncStateEntity(sessionId, seq))
        }
    }

    override suspend fun incrementalSync(sessionId: String) {
        val state = syncStateDao.get(sessionId) ?: return
        val response = syncApiClient.events(sessionId, state.lastSeq)
        if (response.events.isEmpty()) return

        val replayer = EventReplayer()
        val events = response.events.map { ReplayEvent(it.id, it.type, it.data) }
        val changes = replayer.replay(events, sessionId)

        for (change in changes) {
            when (change) {
                is ReplayChange.Insert -> {
                    val truncatedData = MobileTruncator.truncate(change.entity.type, 
                        kotlinx.serialization.json.JsonObject(kotlinx.serialization.json.Json.parseToJsonElement(change.entity.data).jsonObject))
                    sessionMessageDao.upsert(change.entity.copy(data = truncatedData.toString()))
                }
                is ReplayChange.Update -> {
                    val type = sessionMessageDao.getById(change.id)?.type ?: continue
                    val truncatedData = MobileTruncator.truncate(type, change.data)
                    sessionMessageDao.upsert(SessionMessageEntity(
                        id = change.id,
                        sessionId = sessionId,
                        type = type,
                        data = truncatedData.toString(),
                        timeCreated = 0,
                        timeUpdated = change.timeUpdated,
                    ))
                }
            }
        }

        response.maxSeq?.let { seq ->
            syncStateDao.upsert(SyncStateEntity(sessionId, seq))
        }
    }

    override suspend fun fetchFullMessage(sessionId: String, messageId: String) {
        val response = syncApiClient.fullMessage(sessionId, messageId)
        fullContentDao.upsert(SessionMessageFullContentEntity(
            messageId = response.id,
            content = response.data.toString(),
            fetchedAt = System.currentTimeMillis(),
        ))
    }

    override suspend fun getLastSeq(sessionId: String): Long? {
        return syncStateDao.get(sessionId)?.lastSeq
    }
}
```

- [ ] **Step 2: 创建 Repository 接口（core:domain）**

在 `core/domain` 中添加接口：

```kotlin
package com.openmate.core.domain.repository

import com.openmate.core.database.entity.SessionMessageEntity
import kotlinx.coroutines.flow.Flow

interface SessionMessageRepository {
    fun observeMessages(sessionId: String): Flow<List<SessionMessageEntity>>
    suspend fun initSync(sessionId: String, limit: Int = 30)
    suspend fun incrementalSync(sessionId: String)
    suspend fun fetchFullMessage(sessionId: String, messageId: String)
    suspend fun getLastSeq(sessionId: String): Long?
}
```

- [ ] **Step 3: Commit**

```
git add core/data/src/main/java/com/openmate/core/data/sync/SyncOrchestrator.kt core/data/src/main/java/com/openmate/core/data/repository/SessionMessageRepositoryImpl.kt core/domain/src/main/java/com/openmate/core/domain/repository/SessionMessageRepository.kt
git commit -m "feat(data): add SyncOrchestrator and SessionMessageRepository"
```

---

## Task 6: Hilt DI 绑定 + 编译验证

- [ ] **Step 1: 在 Hilt 模块中绑定 Repository**

在 data 模块的 Hilt module 中添加：

```kotlin
@Binds
abstract fun bindSessionMessageRepository(impl: SessionMessageRepositoryImpl): SessionMessageRepository
```

- [ ] **Step 2: 修复所有旧引用**

旧代码中引用 `MessageDao`、`PartDao`、`MessageRepository` 的地方需要更新。主要在：
- `feature/session/SessionDetailViewModel.kt` — 改为使用 `SessionMessageRepository`
- 其他引用旧 DAO 的 Repository/UseCase

这些改动会在 Step 06 (UI 适配) 中完成。此处只需确保 `core:domain` 和 `core:data` 模块能独立编译。

Run: `./gradlew :core:data:compileDebugKotlin :core:domain:compileDebugKotlin`
Expected: 编译成功（feature 模块可能有错误，后续修复）

- [ ] **Step 3: Commit**

```
git add -A core/domain/ core/data/
git commit -m "feat(data): wire up sync engine DI bindings"
```

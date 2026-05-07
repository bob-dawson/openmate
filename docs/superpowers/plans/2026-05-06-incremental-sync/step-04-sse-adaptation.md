# 步骤 4：SSE 适配 — 接入 session.next.* 事件

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task.

**Goal:** 在现有 SSE 事件分发体系中新增 `session.next.*` 事件处理，使实时推送的事件也能通过 EventReplayer 回放更新 SessionMessage。

**Architecture:** 现有 EventDispatcher 按 type prefix 分发。新增 `SessionNextEventHandler` 处理 `session.next.*` 前缀事件，复用 EventReplayer 回放逻辑。

**Tech Stack:** Kotlin, Hilt, kotlinx.serialization

---

## Files

- Create: `core/data/src/main/java/com/openmate/core/data/sse/SessionNextEventHandler.kt`
- Modify: `core/data/src/main/java/com/openmate/core/data/sse/EventDispatcher.kt`

---

## Task 1: Create SessionNextEventHandler

**Files:**
- Create: `core/data/src/main/java/com/openmate/core/data/sse/SessionNextEventHandler.kt`

- [ ] **Step 1: Create the handler**

SSE 实时事件与 `/sync/history` 拉取的事件共享同一回放逻辑。区别：
- SSE 事件是单条，逐条处理
- 需要从 DB 读取当前会话状态，回放后写回

```kotlin
package com.openmate.core.data.sse

import android.util.Log
import com.openmate.core.data.sync.DataTruncator
import com.openmate.core.data.sync.EventReplayer
import com.openmate.core.database.ActiveDatabaseProvider
import com.openmate.core.network.SseData
import com.openmate.core.network.dto.SyncEventDto
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonPrimitive
import javax.inject.Inject

open class SessionNextEventHandler @Inject constructor(
    private val dbProvider: ActiveDatabaseProvider,
) {
    companion object {
        private const val TAG = "SessionNextEventHandler"
    }

    suspend fun handle(type: String, event: SseData) {
        val props = event.properties
        val sessionID = props["sessionID"]?.jsonPrimitive?.content ?: return

        val withoutVersion = type.substringBeforeLast(".")
        if (withoutVersion.endsWith(".delta")) return

        try {
            val seq = props["seq"]?.jsonPrimitive?.content?.toIntOrNull() ?: 0
            val syncEventDto = SyncEventDto(
                id = props["id"]?.jsonPrimitive?.content ?: "",
                aggregate_id = sessionID,
                seq = seq,
                type = type,
                data = props,
            )

            val db = dbProvider.getActive()
            val existingMessages = db.sessionMessageDao().let { dao ->
                dao.getBySessionSync(sessionID).associateBy { it.id }
            }

            val results = EventReplayer.replay(listOf(syncEventDto), existingMessages)

            if (results.isNotEmpty()) {
                db.sessionMessageDao().upsertAll(results)
            }

            if (seq > 0) {
                val currentLastSeq = db.syncStateDao().getLastSeq(sessionID) ?: 0
                if (seq > currentLastSeq) {
                    db.syncStateDao().upsert(
                        com.openmate.core.database.entity.SyncStateEntity(
                            sessionID = sessionID,
                            lastSeq = seq,
                            lastSyncAt = System.currentTimeMillis(),
                        )
                    )
                }
            }

            Log.d(TAG, "Handled $type for session $sessionID")
        } catch (e: Exception) {
            Log.w(TAG, "$type failed", e)
        }
    }
}
```

**关键设计：**
- SSE 事件 `properties` 直接作为 `SyncEventDto.data` 传入 EventReplayer
- EventReplayer 只读 `data` 的 JSON 字段，`SseData.properties` 是 `JsonObject`，兼容
- 回放后更新 `SyncStateEntity.lastSeq`，确保增量同步不会重复处理
- delta 事件跳过，与批量同步一致

- [ ] **Step 2: Build and verify**

Run: `.\gradlew.bat :core:data:compileDebugKotlin --no-daemon 2>&1 | Select-String -Pattern "^e:"`

Expected: No errors

---

## Task 2: Update EventDispatcher

**Files:**
- Modify: `core/data/src/main/java/com/openmate/core/data/sse/EventDispatcher.kt`

- [ ] **Step 1: Add session.next.* routing**

在 EventDispatcher 中新增 `session.next.` 前缀路由到 `SessionNextEventHandler`。

```kotlin
class EventDispatcher @Inject constructor(
    private val sessionHandler: SessionEventHandler,
    private val messageHandler: MessageEventHandler,
    private val permissionHandler: PermissionEventHandler,
    private val questionHandler: QuestionEventHandler,
    private val todoHandler: TodoEventHandler,
    private val sessionNextHandler: SessionNextEventHandler,  // 新增
) {
    // ... existing code ...

    suspend fun dispatch(event: SseData) {
        // ... existing filtering logic ...

        when {
            type.startsWith("session.next.") -> sessionNextHandler.handle(type, event)  // 新增，必须在 session. 之前
            type.startsWith("session.") -> sessionHandler.handle(type, event)
            type.startsWith("message.") -> messageHandler.handle(type, event)
            type.startsWith("permission.") -> permissionHandler.handle(type, event)
            type.startsWith("question.") -> questionHandler.handle(type, event)
            type.startsWith("todo.") -> todoHandler.handle(type, event)
        }
    }
}
```

**注意：** `session.next.` 必须在 `session.` 之前匹配，因为 `session.next.` 是 `session.` 的子集前缀。

- [ ] **Step 2: Build and verify**

Run: `.\gradlew.bat :core:data:compileDebugKotlin --no-daemon 2>&1 | Select-String -Pattern "^e:"`

Expected: No errors

---

## Task 3: Handle SSE parser version suffix

**Files:**
- Modify: `core/network/src/main/java/com/openmate/core/network/SseParser.kt`

- [ ] **Step 1: Verify SseParser strips version suffix**

当前 `SseParser.parseLine()` 对 syncEvent 已经做了 `type.substringBeforeLast('.')`（去掉版本号），所以 SSE 推送的 `session.next.text.ended.1` 会变成 `session.next.text.ended`。这与 EventReplayer 的 `type` 匹配逻辑一致。

**验证：不需要修改 SseParser。** 但需要确认：
1. syncEvent 路径中，SseParser 已去掉版本号 ✓
2. 非 syncEvent 路径中（`payload.type`），版本号是否保留？→ 当前保留，但 `session.next.*` 事件走 syncEvent 路径

- [ ] **Step 2: 确认实际 SSE 事件格式**

在实际测试中，需要确认 opencode 服务端推送的 `session.next.*` SSE 事件是否走 `syncEvent` 路径。如果走 `properties` 路径，SseParser 会保留版本号后缀（如 `session.next.text.ended.1`），此时 EventDispatcher 的 `startsWith("session.next.")` 仍然匹配，但 handler 中需要去掉版本号。

在 SessionNextEventHandler.handle() 中已有 `type.substringBeforeLast(".")` 处理，所以两种路径都兼容。

---

## Design Notes

### SSE 事件与批量同步的统一

核心设计原则：**SSE 实时事件和 /sync/history 批量事件共享同一套回放逻辑（EventReplayer）**。

| 维度 | SSE 实时 | /sync/history 批量 |
|------|---------|-------------------|
| 事件来源 | 服务端推送 | 客户端拉取 |
| 事件数量 | 单条 | 批量 |
| 处理方式 | 逐条通过 EventReplayer | 批量通过 EventReplayer |
| seq 更新 | 实时更新 | 批量更新 |
| delta 事件 | **需要处理**（流式更新 UI） | 跳过 |

**重要差异：SSE delta 事件在实时场景不应跳过。**

SSE 的 `message.part.delta` 已经在 MessageEventHandler 中处理（拼接增量文本）。对于新模型的 `session.next.text.delta` 等，需要考虑：
- 如果 UI 基于旧模型渲染，delta 继续走 MessageEventHandler
- 如果 UI 基于新模型渲染，需要处理 delta 以实现流式更新

**当前策略：** SSE 的 `session.next.*.delta` 事件暂时跳过，流式更新仍通过旧模型的 `message.part.delta` 处理。待 UI 迁移到新模型后，再处理新模型 delta。

### 事件顺序保证

SSE 事件按服务端发送顺序到达，EventReplayer 按顺序处理。但 SSE 可能丢事件（网络中断），此时通过定期 `syncHistory()` 补全。

---

## Verification

- [ ] `.\gradlew.bat :core:data:compileDebugKotlin --no-daemon 2>&1 | Select-String -Pattern "^e:"` — zero errors
- [ ] EventDispatcher 正确路由 `session.next.*` 到新 handler
- [ ] `session.next.*` 在 `session.*` 之前匹配
- [ ] SSE delta 事件被跳过（当前阶段）

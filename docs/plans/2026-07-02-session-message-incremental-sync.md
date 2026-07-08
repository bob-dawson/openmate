# session_message 增量同步 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 用 `session_message.time_updated` 增量拉取替代基于 V2 event 的增量同步，删除检测沿用 V1 `message.removed` 事件。

**Architecture:** Bridge 新增 `/messages` 端点从 `session_message` 表按 `time_updated` 增量拉取（含截断），events 端点过滤为仅返回 `message.removed` + `session.updated`。Android 端增量同步改为两阶段：先拉 messages 做 upsert，再拉 events 做删除/revert 处理。EventReplayer 大幅简化。

**Tech Stack:** Rust (Bridge), Kotlin/Room (Android DB), OkHttp (Android network)

---

## File Structure

### Bridge (Rust)
- **Modify** `opencode-bridge/src/sync/db.rs` — 新增 `get_messages_since()` 方法
- **Modify** `opencode-bridge/src/sync/router.rs` — 新增 `messages()` handler，修改 `events()` 过滤
- **Modify** `opencode-bridge/src/server.rs` — 注册新路由
- **Modify** `opencode-bridge/src/sync/truncate.rs` — 无修改，复用现有 `truncate_message()`

### Android (Kotlin)
- **Modify** `core/database/.../entity/SyncStateEntity.kt` — 新增 `lastTimeUpdated` 字段
- **Modify** `core/database/.../dao/SyncStateDao.kt` — 无需改（Room 自动处理）
- **Modify** `core/database/.../AppDatabase.kt` — version 5→6
- **Modify** `core/network/.../dto/SyncDto.kt` — 新增 `MessagesResponseDto`
- **Modify** `core/network/.../SyncApiClient.kt` — 新增 `messages()` 方法
- **Modify** `core/data/.../repository/SessionMessageRepositoryImpl.kt` — 重写 `doIncrementalSync()`，修改 `initSync()`
- **Modify** `core/data/.../sync/EventReplayer.kt` — 删除所有 `session.next.*` 分支和 `message.part.updated` 分支，仅保留 `message.removed`

---

### Task 1: Bridge — 新增 `get_messages_since()` 数据库方法

**Files:**
- Modify: `opencode-bridge/src/sync/db.rs`

- [ ] **Step 1: 在 `SyncDb` impl 中新增 `get_messages_since` 方法**

在 `db.rs` 的 `impl SyncDb` 块中，`get_init_snapshot` 方法之后添加：

```rust
pub fn get_messages_since(&self, session_id: &str, since: i64, limit: i64) -> Result<(Vec<Value>, bool, Option<i64>), String> {
    let conn = self.conn()?;
    let mut stmt = conn.prepare(
        "SELECT id, session_id, type, time_created, time_updated, data
         FROM session_message
         WHERE session_id = ? AND time_updated > ?
         ORDER BY time_updated ASC
         LIMIT ?"
    ).map_err(|e| format!("Prepare failed: {}", e))?;

    let messages: Vec<Value> = stmt.query_map(params![session_id, since, limit + 1], |row| {
        let id: String = row.get(0)?;
        let sid: String = row.get(1)?;
        let msg_type: String = row.get(2)?;
        let time_created: i64 = row.get(3)?;
        let time_updated: i64 = row.get(4)?;
        let data_str: String = row.get(5)?;
        let data_val: Value = serde_json::from_str(&data_str).unwrap_or(Value::String(data_str.clone()));
        Ok(json!({
            "id": id,
            "sessionId": sid,
            "type": msg_type,
            "timeCreated": time_created,
            "timeUpdated": time_updated,
            "data": data_val,
        }))
    }).map_err(|e| format!("Query failed: {}", e))?
      .filter_map(|r| r.ok())
      .collect();

    let has_more = messages.len() > limit as usize;
    let result_messages: Vec<Value> = if has_more {
        messages[..limit as usize].to_vec()
    } else {
        messages
    };

    let max_time_updated: Option<i64> = result_messages.iter()
        .filter_map(|m| m.get("timeUpdated").and_then(|v| v.as_i64()))
        .max();

    Ok((result_messages, has_more, max_time_updated))
}
```

查询 `LIMIT limit+1` 来检测 hasMore，返回时截断到 limit 条。返回 `(messages, hasMore, maxTimeUpdated)`。

- [ ] **Step 2: 验证编译**

Run: `cd D:\openmate\opencode-bridge && cargo check`
Expected: 编译通过

---

### Task 2: Bridge — 新增 `/messages` handler + events 过滤

**Files:**
- Modify: `opencode-bridge/src/sync/router.rs`
- Modify: `opencode-bridge/src/server.rs`

- [ ] **Step 1: 在 `router.rs` 新增 `MessagesQuery` 和 `messages()` handler**

在 `EventsQuery` 之后添加：

```rust
#[derive(Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct MessagesQuery {
    pub since: Option<i64>,
    pub limit: Option<i64>,
}
```

在 `events()` 函数之后添加：

```rust
pub async fn messages(
    State(state): State<AppState>,
    Path(session_id): Path<String>,
    Query(query): Query<MessagesQuery>,
) -> Result<impl IntoResponse, AppError> {
    let since = query.since.unwrap_or(0);
    let limit = query.limit.unwrap_or(100);
    let (messages, has_more, max_time_updated) = state.sync_db
        .get_messages_since(&session_id, since, limit)
        .map_err(|e| AppError::DatabaseError(e))?;

    let truncated: Vec<Value> = messages.into_iter().map(|mut msg| {
        if let Some(data_str) = msg["data"].as_str() {
            if let Ok(data_val) = serde_json::from_str::<Value>(data_str) {
                let msg_type = msg["type"].as_str().unwrap_or("");
                let truncated_data = super::truncate::truncate_message(msg_type, &data_val);
                msg["data"] = truncated_data;
            }
        }
        msg
    }).collect();

    Ok(Json(json!({
        "messages": truncated,
        "hasMore": has_more,
        "maxTimeUpdated": max_time_updated,
    })))
}
```

- [ ] **Step 2: 修改 `events()` handler，添加事件类型过滤**

将 `events()` 中的 `get_events` 调用改为 `get_events_filtered`：

```rust
pub async fn events(
    State(state): State<AppState>,
    Path(session_id): Path<String>,
    Query(query): Query<EventsQuery>,
) -> Result<impl IntoResponse, AppError> {
    let after_seq = query.after_seq.unwrap_or(0);
    let limit = query.limit.unwrap_or(100);
    let (events, max_seq) = state.sync_db
        .get_events_filtered(&session_id, after_seq, limit)
        .map_err(|e| AppError::DatabaseError(e))?;
    // ... rest unchanged
```

- [ ] **Step 3: 在 `db.rs` 新增 `get_events_filtered()` 方法**

在 `get_events()` 之后添加：

```rust
pub fn get_events_filtered(&self, session_id: &str, after_seq: i64, limit: i64) -> Result<(Vec<Value>, Option<i64>), String> {
    let conn = self.conn()?;
    let mut stmt = conn.prepare(
        "SELECT id, aggregate_id, seq, type, data
         FROM event
         WHERE aggregate_id = ? AND seq > ?
           AND type IN ('message.removed.1', 'session.updated.1')
         ORDER BY seq
         LIMIT ?"
    ).map_err(|e| format!("Prepare failed: {}", e))?;

    let events: Vec<Value> = stmt.query_map(params![session_id, after_seq, limit], |row| {
        let id: String = row.get(0)?;
        let aggregate_id: String = row.get(1)?;
        let seq: i64 = row.get(2)?;
        let event_type: String = row.get(3)?;
        let data_str: String = row.get(4)?;
        let data_val: Value = serde_json::from_str(&data_str).unwrap_or(Value::String(data_str.clone()));
        Ok(json!({
            "id": id,
            "aggregateId": aggregate_id,
            "seq": seq,
            "type": event_type,
            "data": data_val,
        }))
    }).map_err(|e| format!("Query failed: {}", e))?
      .filter_map(|r| r.ok())
      .collect();

    let actual_max: Option<i64> = conn.query_row(
        "SELECT seq FROM event_sequence WHERE aggregate_id = ?",
        params![session_id],
        |row| row.get(0),
    ).ok();

    Ok((events, actual_max))
}
```

- [ ] **Step 4: 在 `server.rs` 注册新路由**

在 `init` 路由之后添加：

```rust
.route("/api/bridge/sync/session/{sessionID}/messages", get(sync::router::messages))
```

- [ ] **Step 5: 验证编译**

Run: `cd D:\openmate\opencode-bridge && cargo check`
Expected: 编译通过

- [ ] **Step 6: 运行 Bridge 测试**

Run: `cd D:\openmate\opencode-bridge && cargo test`
Expected: 所有测试通过

---

### Task 3: Android — SyncStateEntity 扩展 + DB migration

**Files:**
- Modify: `core/database/.../entity/SyncStateEntity.kt`
- Modify: `core/database/.../AppDatabase.kt`

- [ ] **Step 1: 给 `SyncStateEntity` 新增 `lastTimeUpdated` 字段**

```kotlin
@Entity(tableName = "sync_state")
data class SyncStateEntity(
    @PrimaryKey val sessionId: String,
    val lastSeq: Long,
    val lastTimeUpdated: Long = 0,
)
```

- [ ] **Step 2: AppDatabase version 5→6，添加 migration**

```kotlin
@Database(
    entities = [
        SessionEntity::class,
        SessionMessageEntity::class,
        SessionMessageFullContentEntity::class,
        SyncStateEntity::class,
        TodoEntity::class,
    ],
    version = 6,
    exportSchema = true,
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun sessionDao(): SessionDao
    abstract fun sessionMessageDao(): SessionMessageDao
    abstract fun sessionMessageFullContentDao(): SessionMessageFullContentDao
    abstract fun syncStateDao(): SyncStateDao
    abstract fun todoDao(): TodoDao
}

val MIGRATION_5_6 = object : Migration(5, 6) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE sync_state ADD COLUMN lastTimeUpdated INTEGER NOT NULL DEFAULT 0")
    }
}
```

确保 `ActiveDatabaseProvider` 中添加 `addMigrations(MIGRATION_5_6)`。

- [ ] **Step 3: 验证编译**

Run: `Invoke-RestMethod -Uri "http://localhost:5099/api/gradle/run" -Method Post -ContentType "application/json" -Body '{"args":[":app:assembleDebug"],"cwd":"D:\\openmate\\android"}'`
Expected: BUILD SUCCESSFUL

---

### Task 4: Android — SyncApiClient + DTO 新增

**Files:**
- Modify: `core/network/.../dto/SyncDto.kt`
- Modify: `core/network/.../SyncApiClient.kt`

- [ ] **Step 1: 在 `SyncDto.kt` 新增 `MessagesResponseDto`**

```kotlin
@Serializable
data class MessagesResponseDto(
    val messages: List<SyncMessageDto> = emptyList(),
    @SerialName("hasMore") val hasMore: Boolean = false,
    @SerialName("maxTimeUpdated") val maxTimeUpdated: Long? = null,
)
```

- [ ] **Step 2: 在 `SyncApiClient.kt` 新增 `messages()` 方法**

```kotlin
suspend fun messages(sessionId: String, since: Long, limit: Int = 100): MessagesResponseDto =
    withContext(Dispatchers.IO) {
        val url = "$baseUrl/api/bridge/sync/session/$sessionId/messages?since=$since&limit=$limit"
        val request = Request.Builder().url(url).get().build()
        val response = client.newCall(request).execute()
        val body = response.body?.string() ?: throw Exception("Empty response")
        json.decodeFromString<MessagesResponseDto>(body)
    }
```

- [ ] **Step 3: 验证编译**

Run: `Invoke-RestMethod -Uri "http://localhost:5099/api/gradle/run" -Method Post -ContentType "application/json" -Body '{"args":[":app:assembleDebug"],"cwd":"D:\\openmate\\android"}'`
Expected: BUILD SUCCESSFUL

---

### Task 5: Android — 重写 `doIncrementalSync()` + 修改 `initSync()`

**Files:**
- Modify: `core/data/.../repository/SessionMessageRepositoryImpl.kt`

这是最核心的改动。将 `doIncrementalSync` 从基于 event 的 V2 重建改为两阶段：messages upsert + events 删除/revert。

- [ ] **Step 1: 修改 `initSync()` — 同时记录 `lastTimeUpdated`**

在 `initSync` 中，`replaceAllForSession` 之后，计算 `maxTimeUpdated` 并写入 SyncState：

```kotlin
override suspend fun initSync(sessionId: String, limit: Int): SessionMessageSyncResult {
    val db = dbProvider.getActive()
    val seqResponse = syncApiClient.events(sessionId, Long.MAX_VALUE)
    val currentSeq = seqResponse.maxSeq ?: 0L

    val response = syncApiClient.init(sessionId, limit)
    val entities = response.messages.map { dto ->
        val truncatedData = MobileTruncator.truncate(dto.type, dto.data)
        dto.copy(data = truncatedData).let { SessionMessageMapper.dtoToEntity(it) }
    }
    db.sessionMessageDao().replaceAllForSession(sessionId, entities)

    val maxTimeUpdated = entities.maxOfOrNull { it.timeUpdated } ?: 0L
    db.syncStateDao().upsert(SyncStateEntity(sessionId, currentSeq, maxTimeUpdated))

    return SessionMessageSyncResult(
        lastSeq = currentSeq,
        changes = entities.map { SessionMessageSyncChange.Insert(it.toDomain()) },
    )
}
```

- [ ] **Step 2: 重写 `doIncrementalSync()` — 两阶段**

替换整个 `doIncrementalSync` 方法体：

```kotlin
private suspend fun doIncrementalSync(sessionId: String) {
    val db = dbProvider.getActive()
    val syncState = db.syncStateDao().get(sessionId) ?: run {
        Log.w("SyncRepo", "incrementalSync skip: no sync state for $sessionId")
        return
    }
    val t0 = System.currentTimeMillis()
    val traceId = "inc-${System.nanoTime()}"
    logStore.log(
        level = SyncLogLevel.Info,
        category = SyncLogCategory.Sync,
        message = "增量同步开始 afterSeq=${syncState.lastSeq} afterTime=${syncState.lastTimeUpdated} trace=$traceId",
        sessionId = sessionId,
    )

    try {
        var batchChanges = mutableListOf<SessionMessageSyncChange>()
        var hasTodoEvent = false

        // Phase 1: session_message 增量拉取 (upsert)
        var since = syncState.lastTimeUpdated
        var totalMessages = 0
        var msgBatchIndex = 0
        while (true) {
            val response = syncApiClient.messages(sessionId, since, limit = 100)
            msgBatchIndex++
            totalMessages += response.messages.size
            if (response.messages.isEmpty()) break

            db.withTransaction {
                for (dto in response.messages) {
                    val truncatedData = MobileTruncator.truncate(dto.type, dto.data)
                    val entity = SessionMessageMapper.dtoToEntity(dto.copy(data = truncatedData))
                    val existing = db.sessionMessageDao().getById(entity.id)
                    db.sessionMessageDao().upsert(entity)
                    batchChanges += if (existing != null) {
                        SessionMessageSyncChange.Update(entity.toDomain())
                    } else {
                        SessionMessageSyncChange.Insert(entity.toDomain())
                    }
                    if (!hasTodoEvent && entity.data.contains("todowrite")) {
                        hasTodoEvent = true
                    }
                }
            }

            since = response.maxTimeUpdated ?: break
            if (!response.hasMore) break
        }

        val maxTimeUpdated = since

        // Phase 2: event 增量拉取 (message.removed + session.updated only)
        var afterSeq = syncState.lastSeq
        var totalEvents = 0
        var evtBatchIndex = 0
        while (true) {
            val response = syncApiClient.events(sessionId, afterSeq, limit = 100)
            evtBatchIndex++
            totalEvents += response.events.size
            if (response.events.isEmpty()) {
                val lastSeq = response.maxSeq ?: afterSeq
                db.syncStateDao().upsert(SyncStateEntity(sessionId, lastSeq, maxTimeUpdated))
                break
            }

            db.withTransaction {
                for (event in response.events) {
                    when {
                        event.type.startsWith("message.removed") && !event.type.startsWith("message.part.removed") -> {
                            val session = db.sessionDao().getById(sessionId)
                            val fromId = session?.revertFrom
                            val toId = session?.revertTo
                            if (fromId != null && toId != null) {
                                val rangeMessages = db.sessionMessageDao().getInRange(sessionId, fromId, toId)
                                db.sessionMessageDao().deleteRange(sessionId, fromId, toId)
                                for (msg in rangeMessages) {
                                    batchChanges += SessionMessageSyncChange.Remove(msg.id)
                                }
                            }
                            db.sessionDao().updateRevertFields(sessionId, null, null, null, null)
                        }
                        event.type.startsWith("session.updated") -> {
                            val aggId = event.aggregateId.ifBlank { sessionId }
                            val info = event.data["info"]?.jsonObject
                            val revertJson = info?.get("revert")
                            val hasRevertKey = info?.contains("revert") == true
                            if (revertJson is JsonObject) {
                                val revertMsgID = revertJson["messageID"]?.jsonPrimitive?.content
                                val revertPartID = revertJson["partID"]?.jsonPrimitive?.content
                                var fromId: String? = null
                                var toId: String? = null
                                if (revertMsgID != null) {
                                    val storedTs = extractStoredTs(revertMsgID)
                                    val nowK = System.currentTimeMillis() / MOD_2_36
                                    for (k in nowK downTo maxOf(0L, nowK - 1)) {
                                        val firstId = db.sessionMessageDao().getFirstIdAfterTimeCreated(sessionId, storedTs + k * MOD_2_36)
                                        if (firstId != null) {
                                            fromId = firstId
                                            toId = db.sessionMessageDao().getMaxIdGte(sessionId, firstId)
                                            break
                                        }
                                    }
                                }
                                db.sessionDao().updateRevertFields(aggId, revertMsgID, revertPartID, fromId, toId)
                            } else if (hasRevertKey && revertJson is JsonNull) {
                                db.sessionDao().updateRevertFields(aggId, null, null, null, null)
                            } else if (!hasRevertKey) {
                                val existing = db.sessionDao().getById(aggId)
                                if (existing?.revertMessageID != null) {
                                    db.sessionDao().updateRevertFields(aggId, null, null, null, null)
                                }
                            }
                        }
                    }
                }
                val batchMaxSeq = response.events.maxOfOrNull { it.seq }
                val newSeq = batchMaxSeq?.let { maxOf(afterSeq, it) } ?: afterSeq
                db.syncStateDao().upsert(SyncStateEntity(sessionId, newSeq, maxTimeUpdated))
                afterSeq = newSeq
            }
        }

        if (batchChanges.isNotEmpty()) {
            val finalState = db.syncStateDao().get(sessionId)
            syncEvents.tryEmit(
                SessionMessageSyncEvent(
                    sessionId = sessionId,
                    result = SessionMessageSyncResult(
                        lastSeq = finalState?.lastSeq ?: syncState.lastSeq,
                        changes = batchChanges,
                        hasTodoEvent = hasTodoEvent,
                    ),
                )
            )
        }

        logStore.log(
            level = SyncLogLevel.Info,
            category = SyncLogCategory.Sync,
            message = "增量同步完成 msgs=$totalMessages evts=$totalEvents cost=${System.currentTimeMillis() - t0}ms trace=$traceId",
            sessionId = sessionId,
        )
    } catch (e: Exception) {
        logStore.log(
            level = SyncLogLevel.Error,
            category = SyncLogCategory.Sync,
            message = "增量同步失败 afterSeq=${syncState.lastSeq} error=${e.javaClass.simpleName}: ${e.message} cost=${System.currentTimeMillis() - t0}ms trace=$traceId",
            sessionId = sessionId,
        )
        throw e
    }
}
```

- [ ] **Step 3: 移除 EventReplayer 相关 import 和使用**

从 `SessionMessageRepositoryImpl` 中移除：
- `import com.openmate.core.data.sync.EventReplayer`
- `import com.openmate.core.data.sync.ReplayChange`
- `import com.openmate.core.data.sync.ReplayEvent`
- `doIncrementalSync` 中创建 `replayer` 和 `loader` 的代码（已在新方法中不存在）

- [ ] **Step 4: 验证编译**

Run: `Invoke-RestMethod -Uri "http://localhost:5099/api/gradle/run" -Method Post -ContentType "application/json" -Body '{"args":[":app:assembleDebug"],"cwd":"D:\\openmate\\android"}'`
Expected: BUILD SUCCESSFUL

---

### Task 6: Android — 简化 EventReplayer

**Files:**
- Modify: `core/data/.../sync/EventReplayer.kt`

- [ ] **Step 1: 大幅简化 EventReplayer**

删除所有 `session.next.*` 分支和 `message.part.updated` 分支，仅保留 `message.removed`。简化后的完整文件：

```kotlin
package com.openmate.core.data.sync

import kotlinx.serialization.json.*

data class ReplayEvent(
    val id: String,
    val type: String,
    val data: JsonObject,
)

sealed class ReplayChange {
    data class Delete(val id: String) : ReplayChange()
}

class EventReplayer {

    suspend fun processEvent(
        event: ReplayEvent,
        sessionId: String,
    ): List<ReplayChange> {
        when (event.type) {
            "message.removed" -> {
                val messageId = event.data["messageID"]?.jsonPrimitive?.contentOrNull ?: return emptyList()
                return listOf(ReplayChange.Delete(messageId))
            }
        }
        return emptyList()
    }
}
```

- [ ] **Step 2: 验证编译**

Run: `Invoke-RestMethod -Uri "http://localhost:5099/api/gradle/run" -Method Post -ContentType "application/json" -Body '{"args":[":app:assembleDebug"],"cwd":"D:\\openmate\\android"}'`
Expected: BUILD SUCCESSFUL

---

### Task 7: Bridge — 更新 events SSE 过滤

**Files:**
- Modify: `opencode-bridge/src/events/filter.rs`

当前 `filter_event` 对 SSE 推送的事件做过滤。需要确认 `message.removed` 和 `session.updated` 事件能通过 Bridge SSE 推送到 Android 端（用于实时触发增量同步）。

- [ ] **Step 1: 确认 `message.removed` 和 `session.updated` 事件在 SSE 中不被过滤**

检查 `filter.rs` 中的 `filter_event` 函数，确认 `message.removed` 和 `session.updated` 类型不被 drop。当前逻辑应该已经放行这些类型（它们不是 delta 类型），如果被过滤则修复。

- [ ] **Step 2: 验证编译**

Run: `cd D:\openmate\opencode-bridge && cargo check`
Expected: 编译通过

---

### Task 8: 端到端验证

- [ ] **Step 1: 构建 Bridge release**

Run: `cd D:\openmate\opencode-bridge && cargo build --release`

- [ ] **Step 2: 部署 Bridge**

Run: `python D:\openmate\scripts\update-bridge.ps1 -SkipBuild`

- [ ] **Step 3: 构建 Android debug APK**

Run: `Invoke-RestMethod -Uri "http://localhost:5099/api/gradle/run" -Method Post -ContentType "application/json" -Body '{"args":[":app:assembleDebug"],"cwd":"D:\\openmate\\android"}'`

- [ ] **Step 4: 手动测试**

1. 安装 APK，连接 Bridge
2. 进入一个会话，等待 init sync 完成
3. 在 opencode 中发新消息，观察 Android 端是否实时更新
4. 执行 revert 操作，观察消息是否被删除
5. 检查 sync log 确认新路径生效（"增量同步开始 afterTime=..." 日志）

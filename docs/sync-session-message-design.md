# 基于 session_message.time_updated 的增量同步

## 背景

opencode v1.17.x 通过 V2 `session.next.*` 事件驱动 Android 增量同步。commit `a1f093a74` (#33993) **删除了 V1 processor 中 V2 事件的双写逻辑**，此后 opencode 不再发布 `session.next.*` 事件，导致 EventReplayer 收到的全是 V1 事件（`message.updated`/`message.part.updated`），大部分被忽略，增量同步卡住。

## 新方案核心思路

**新增/修改**：直接从 opencode 的 `session_message` 表按 `time_updated` 增量拉取，经截断后 upsert 到 Android DB。

**删除**：继续沿用 V1 `message.removed` 事件（durable，写入 event 表）。

**session 元数据**：继续沿用 `session.updated` 事件（处理 revert 字段等）。

## 数据源

opencode DB 中有两套 message 表：

| 表 | 说明 | time_updated |
|---|---|---|
| `session_message` | V2 projector 写入，包含完整 data（含 type、content 等） | drizzle `$onUpdate` 自动维护 |
| `message` + `part` | V1 projector 写入，message/part 分离 | 同上 |

**选择 `session_message`**：init snapshot 已经读此表，data 结构与 Android 端一致，无需额外转换。

## 需要保留的 V1 事件

从 event 表增量拉取，仅保留 Android 端需要的事件类型：

| 事件类型 | 用途 | 处理方式 |
|---|---|---|
| `message.removed` | 消息删除（revert cleanup） | 直接从 event.data 取 `messageID`，删除 Android DB 中对应记录 |
| `session.updated` | session 元数据变更（revert 字段等） | 提取 `info.revert` 写入 SessionEntity |

其他所有事件类型（包括 `message.updated`、`message.part.updated`、所有 `session.next.*`）**忽略**，因为 message 数据变更已由 `session_message` 增量拉取覆盖。

## Bridge API 变更

### 新增：`GET /api/bridge/sync/session/:sessionId/messages`

从 `session_message` 表按 `time_updated` 增量拉取消息。

**请求参数**：

| 参数 | 类型 | 说明 |
|---|---|---|
| `since` | long | 时间戳（毫秒），返回 `time_updated > since` 的记录 |
| `limit` | int | 单次最大返回条数，默认 100 |

**响应**：

```json
{
  "messages": [
    {
      "id": "msg_xxx",
      "sessionId": "ses_xxx",
      "type": "assistant",
      "timeCreated": 1782972346753,
      "timeUpdated": 1782972347156,
      "data": { ... }  // 截断后的 data
    }
  ],
  "hasMore": true,
  "maxTimeUpdated": 1782972347156
}
```

**截断**：复用现有 `truncate_message()` 逻辑，对每条消息的 data 按类型截断（user/assistant/shell/compaction 各有不同截断规则）。

### 修改：`GET /api/bridge/sync/session/:sessionId/events`

**过滤事件类型**：仅返回 `message.removed` 和 `session.updated`，忽略其他所有事件。

在 `get_events()` SQL 查询中添加 WHERE 过滤：

```sql
SELECT id, aggregate_id, seq, type, data
FROM event
WHERE aggregate_id = ? AND seq > ?
  AND type IN ('message.removed.1', 'session.updated.1')
ORDER BY seq
LIMIT ?
```

这大幅减少传输量和 Android 端处理量。

## Android 端变更

### SyncStateEntity 扩展

新增 `lastTimeUpdated` 字段：

```kotlin
@Entity(tableName = "sync_state")
data class SyncStateEntity(
    @PrimaryKey val sessionId: String,
    val lastSeq: Long,
    val lastTimeUpdated: Long = 0,
)
```

### SyncApiClient 新增

```kotlin
suspend fun messages(sessionId: String, since: Long, limit: Int = 100): MessagesResponseDto
```

### 增量同步流程（doIncrementalSync）

替换原流程为两阶段：

```
阶段1: 拉取 session_message 增量
  while (true) {
    response = syncApiClient.messages(sessionId, since=lastTimeUpdated, limit=100)
    for (msg in response.messages) {
      upsert 到 Android DB
    }
    lastTimeUpdated = response.maxTimeUpdated
    if (!response.hasMore) break
  }

阶段2: 拉取 event 增量（仅 message.removed + session.updated）
  while (true) {
    response = syncApiClient.events(sessionId, afterSeq=lastSeq, limit=100)
    for (event in response.events) {
      when {
        type.startsWith("message.removed") -> 处理删除（现有逻辑）
        type.startsWith("session.updated") -> 处理 revert 字段（现有逻辑）
      }
    }
    lastSeq = response.maxSeq
    if (response.events.isEmpty()) break
  }
```

### EventReplayer 简化

不再需要 EventReplayer 处理 V2 事件重建 message 数据。仅保留：

- `message.removed` → `ReplayChange.Delete(messageId)`
- `session.updated` → 处理 revert 字段（直接在 doIncrementalSync 中处理，不走 EventReplayer）

EventReplayer 中所有 `session.next.*` 分支和 `message.part.updated` 分支可以删除。

### SSE 实时路径

SSE 路径保持不变——`message.*` 和 `session.next.*` 事件仍触发 `messageSyncNeeded`，驱动增量同步。区别是增量同步的内容变了（从 event 表重建 → session_message 表直接拉取）。

## 冷启动（init sync）不变

init snapshot 仍然读 `session_message` 表 + 截断，逻辑不变。只是返回后同时记录 `lastTimeUpdated`。

## 迁移策略

1. Bridge 先部署（新增 `/messages` 端点 + events 过滤），向后兼容旧 Android 客户端
2. Android 更新后，新的增量同步路径自动生效
3. DB migration：`SyncStateEntity` 新增 `lastTimeUpdated` 列（默认 0，等价于全量拉取一次 session_message）

## 优势

| 对比项 | 旧方案（event-based） | 新方案（session_message-based） |
|---|---|---|
| 依赖 V2 事件 | 是（已停止发布） | 否 |
| 数据完整性 | 需从事件重建，复杂且脆弱 | 直接读 opencode 最终状态，准确 |
| 传输量 | 大量 V2 事件（已不存在） | 仅变更的 message + 少量 event |
| 删除检测 | 依赖 V1 `message.removed` 事件 | 同上 |
| 代码复杂度 | EventReplayer 600+ 行重建逻辑 | 大幅简化，upsert 即可 |

## 需要注意的风险

1. **`session_message` 表可能不存在**：旧版 opencode 没有 V2 projector，只有 `message`+`part` 表。Bridge 已有 fallback 到 `get_legacy_message_snapshot()` 的逻辑，增量拉取也需类似 fallback。
2. **时钟偏移**：`time_updated` 由 opencode 服务端生成，Android 端仅存储和比较，不依赖本地时钟。
3. **并发写入**：Bridge 只读 opencode DB（`SQLITE_OPEN_READ_ONLY`），不受影响。

# 移动端增量同步机制设计

## 概述

OpenCode 基于事件溯源(Event Sourcing)构建了一套完善的增量同步系统。移动客户端（Android 等）可以利用现有机制实现会话消息的新增、变更、删除的增量同步。

**前置条件**：需要设置环境变量 `OPENCODE_EXPERIMENTAL_WORKSPACES=true`（或 `OPENCODE_EXPERIMENTAL=true`）以启用事件持久化。未开启时 `/sync/history` 返回空数组。

## 服务端事件系统现状

OpenCode 服务端当前存在**两套并存的投影器**，同时写入两套数据模型：

### 旧模型（message + part）

- 表：`MessageTable` + `PartTable`
- 事件类型：`session.created/updated/deleted.1`、`message.updated.1`、`message.removed.1`、`message.part.updated.1`、`message.part.removed.1`
- 数据结构：消息和 Part 分离存储，Part 是消息的子行

### 新模型（session.next → SessionMessage）

- 表：`SessionMessageTable`
- 事件类型：`session.next.*` 系列
- 数据结构：所有消息类型统一为 `SessionMessage`，通过 `type` 字段区分
- 投影器：`projectors-next.ts`，在旧投影器末尾 `...nextProjectors` 注入

**建议移动端基于新模型（`session.next.*`）设计**，原因：
1. 新模型是 opencode 的演进方向，旧模型将逐步废弃
2. `SessionMessage` 统一结构更简单，不需要 message/part 两级关联
3. 事件粒度更细，客户端可以更精确地更新 UI

## 核心架构

```
移动客户端                    OpenCode 服务端
    │                              │
    │──── POST /sync/history ──────│  (携带 lastKnownSeq)
    │                              │
    │◄─── events (seq > N) ────────│  (增量事件)
    │                              │
    │─── 回放事件，更新本地 DB ─────│
    │                              │
    │──── SSE（未来可用）───────────│  (实时推送，尚未实现)
    │                              │
```

## 服务端数据库表结构（v1.14.39 实际）

### 事件存储

```sql
CREATE TABLE event_sequence (
  aggregate_id TEXT PRIMARY KEY,  -- 即 sessionID
  seq          INTEGER NOT NULL,
  owner_id     TEXT               -- 新增：owner 标识，用于 workspace 场景
);

CREATE TABLE event (
  id           TEXT PRIMARY KEY,
  aggregate_id TEXT NOT NULL REFERENCES event_sequence(aggregate_id) ON DELETE CASCADE,
  seq          INTEGER NOT NULL,
  type         TEXT NOT NULL,    -- 如 "session.next.text.started.1"
  data         TEXT NOT NULL     -- JSON
);
```

### 新模型：SessionMessageTable

```sql
CREATE TABLE session_message (
  id           TEXT PRIMARY KEY,
  session_id   TEXT NOT NULL REFERENCES session(id) ON DELETE CASCADE,
  type         TEXT NOT NULL,    -- assistant / user / shell / compaction / agent-switched / model-switched / synthetic
  time_created INTEGER NOT NULL,
  time_updated INTEGER NOT NULL,
  data         TEXT NOT NULL     -- JSON，内容因 type 而异
);
```

### 旧模型：MessageTable + PartTable（逐步废弃）

```sql
CREATE TABLE message (
  id           TEXT PRIMARY KEY,
  session_id   TEXT NOT NULL REFERENCES session(id) ON DELETE CASCADE,
  time_created INTEGER NOT NULL,
  time_updated INTEGER NOT NULL,
  data         TEXT NOT NULL     -- JSON
);

CREATE TABLE part (
  id           TEXT PRIMARY KEY,
  message_id   TEXT NOT NULL REFERENCES message(id) ON DELETE CASCADE,
  session_id   TEXT NOT NULL,
  time_created INTEGER NOT NULL,
  time_updated INTEGER NOT NULL,
  data         TEXT NOT NULL     -- JSON
);
```

## 增量同步协议

### 1. 初始同步（拉取全量）

```
POST /sync/history
Content-Type: application/json

{
  "ses_xxx": 0,    -- 本地未知该 session，从 0 开始
  "ses_yyy": 0
}
```

**响应（实际格式）：**

```json
[
  { "id": "evt_001", "aggregate_id": "ses_xxx", "seq": 1, "type": "session.next.step.started.1", "data": { "sessionID": "ses_xxx", "timestamp": 1715012345678, "agent": "coder", "model": { "id": "glm-5.1", "providerID": "zai" } } },
  { "id": "evt_002", "aggregate_id": "ses_xxx", "seq": 2, "type": "session.next.text.started.1", "data": { ... } },
  { "id": "evt_003", "aggregate_id": "ses_xxx", "seq": 3, "type": "session.next.text.ended.1", "data": { ... } }
]
```

**关键说明：**
- 响应是**扁平数组**，不是按 session 分组的对象
- `aggregate_id` 即 `sessionID`
- 事件类型格式为 `name.version`（如 `session.next.text.ended.1`），不是 `message-v2.xxx`
- 传空 `{}` 时返回所有 session 的全量事件

### 2. 增量同步（拉取变更）

```
POST /sync/history
Content-Type: application/json

{
  "ses_xxx": 3,     -- 本地已知 ses_xxx 到 seq=3
  "ses_yyy": 5
}
```

**响应：** 返回 `ses_xxx` 的 seq > 3 和 `ses_yyy` 的 seq > 5 的所有事件。未列出的 session 返回全量事件。

### 3. 事件类型完整列表

#### 会话级别事件（旧投影器，写 SessionTable）

| 事件类型 | 含义 | 本地操作 |
|----------|------|---------|
| `session.created.1` | 创建会话 | INSERT session |
| `session.updated.1` | 更新会话元数据 | UPDATE session |
| `session.deleted.1` | 删除会话 | DELETE session + 级联 |

#### 旧消息模型事件（旧投影器，写 MessageTable + PartTable）

| 事件类型 | 含义 | 本地操作 |
|----------|------|---------|
| `message.updated.1` | 新增/变更消息 | UPSERT message |
| `message.removed.1` | 删除消息 | DELETE message |
| `message.part.updated.1` | 新增/变更 part | UPSERT part |
| `message.part.removed.1` | 删除 part | DELETE part |

#### 新消息模型事件（next 投影器，写 SessionMessageTable）

| 事件类型 | 含义 | 对应 SessionMessage 类型 |
|----------|------|------------------------|
| `session.next.agent.switched.1` | 切换 Agent | `agent-switched` |
| `session.next.model.switched.1` | 切换模型 | `model-switched` |
| `session.next.prompted.1` | 用户发送消息 | `user` |
| `session.next.synthetic.1` | 合成消息 | `synthetic` |
| `session.next.shell.started.1` | Shell 命令开始 | `shell` |
| `session.next.shell.ended.1` | Shell 命令结束 | `shell` (更新) |
| `session.next.step.started.1` | 步骤开始 | `assistant` (创建) |
| `session.next.step.ended.1` | 步骤结束 | `assistant` (完成) |
| `session.next.step.failed.1` | 步骤失败 | `assistant` (错误) |
| `session.next.text.started.1` | 文本输出开始 | `assistant` (添加 text content) |
| `session.next.text.delta.1` | 文本增量 | **跳过**（流式更新，同步场景不需要） |
| `session.next.text.ended.1` | 文本输出结束 | `assistant` (更新 text content) |
| `session.next.tool.input.started.1` | 工具输入开始 | `assistant` (添加 tool content) |
| `session.next.tool.input.delta.1` | 工具输入增量 | **跳过** |
| `session.next.tool.input.ended.1` | 工具输入结束 | `assistant` (更新 tool content) |
| `session.next.tool.called.1` | 工具被调用 | `assistant` (更新 tool state) |
| `session.next.tool.progress.1` | 工具进度 | `assistant` (更新 tool state) |
| `session.next.tool.success.1` | 工具执行成功 | `assistant` (更新 tool state=completed) |
| `session.next.tool.failed.1` | 工具执行失败 | `assistant` (更新 tool state=error) |
| `session.next.reasoning.started.1` | 推理开始 | `assistant` (添加 reasoning content) |
| `session.next.reasoning.delta.1` | 推理增量 | **跳过** |
| `session.next.reasoning.ended.1` | 推理结束 | `assistant` (更新 reasoning content) |
| `session.next.retried.1` | 重试 | `assistant` |
| `session.next.compaction.started.1` | 压缩开始 | `compaction` |
| `session.next.compaction.delta.1` | 压缩增量 | **跳过** |
| `session.next.compaction.ended.1` | 压缩结束 | `compaction` (更新) |

### 4. SessionMessage 数据结构

新模型中所有消息类型统一为 `SessionMessage`，通过 `type` 字段区分：

```typescript
// 核心类型（Tagged Union）
type SessionMessage =
  | AgentSwitched     // type: "agent-switched"
  | ModelSwitched     // type: "model-switched"
  | User              // type: "user"
  | Synthetic         // type: "synthetic"
  | Shell             // type: "shell"
  | Assistant         // type: "assistant"
  | Compaction        // type: "compaction"
```

**Assistant 消息结构（重点）：**

```typescript
{
  type: "assistant",
  id: string,
  agent: string,
  model: { id, providerID, variant? },
  content: AssistantContent[],  // 统一的内容数组
  snapshot?: { start?, end? },
  finish?: string,
  cost?: number,
  tokens?: { input, output, reasoning, cache: { read, write } },
  error?: { type: "unknown", message: string },
  time: { created: DateTimeUtc, completed?: DateTimeUtc },
  metadata?: Record<string, unknown>
}

// 内容类型（Tagged Union by "type"）
type AssistantContent =
  | { type: "text", text: string }
  | { type: "reasoning", id: string, text: string }
  | { type: "tool", id: string, name: string, state: ToolState, ... }
```

**与旧模型的关键差异：**
- 旧模型：message + part 两级，part 是 message 的子行，需要 JOIN
- 新模型：单个 SessionMessage 行，content 内嵌在 data JSON 中
- 新模型的 `assistant` 类型把 text/reasoning/tool 统一为 `content` 数组

## 客户端同步流程

### 推荐方案：基于 session.next.* 事件

```kotlin
fun syncSessions(lastKnownSeqs: Map<String, Int>): SyncResult {
    // 1. 请求增量事件
    val events = httpPost("/sync/history", lastKnownSeqs)

    // 2. 按事件类型回放，更新本地 DB
    db.transaction {
        for (event in events) {
            when {
                event.type.startsWith("session.next.") -> replayNextEvent(event)
                event.type.startsWith("session.") -> replaySessionEvent(event)
                // 旧模型事件可选择忽略或双写
            }
        }
        // 3. 更新 lastSeq
        updateLastSeqs(events)
    }
}

fun replayNextEvent(event: SyncEvent) {
    // session.next.* 事件由 SessionMessageUpdater 处理
    // 核心逻辑：每个事件对应 SessionMessageTable 的一行 upsert
    // - step.started → 创建 assistant 行
    // - text.ended / tool.success → 更新 assistant 行的 content 数组
    // - prompted → 创建 user 行
    // 等等
    when (event.type) {
        "session.next.prompted.1" -> upsertUserMessage(event.data)
        "session.next.step.started.1" -> upsertAssistantMessage(event.data)
        "session.next.step.ended.1" -> completeAssistantMessage(event.data)
        "session.next.text.ended.1" -> appendTextContent(event.data)
        "session.next.tool.called.1" -> appendToolContent(event.data)
        "session.next.tool.success.1" -> updateToolState(event.data)
        // delta 类事件跳过
    }
}
```

### 幂等处理

所有事件回放都是幂等的：
- **UPSERT** 使用 `INSERT OR REPLACE` 或 `ON CONFLICT DO UPDATE`
- **UPDATE/DELETE** 使用主键定位，不存在则跳过
- **session.deleted** 级联删除关联的 SessionMessage

### 断点续传

- 客户端在本地持久化每个 session 的 `lastSeq`
- 网络失败时记录失败状态，下次上线从断点处继续
- `POST /sync/history` 返回 `seq > N` 的事件，天然支持重试

## 实时推送（尚未实现）

当前版本(v1.14.39) **SSE `/sync/stream` 尚未实现**。未来可用时：

```
GET /workspace/{workspaceId}/sync/stream

data: {"aggregate_id":"ses_xxx","seq":6,"type":"session.next.text.ended.1","data":{...}}
```

客户端可：
1. 上线时先拉取增量
2. 建立 SSE 连接接收实时事件
3. 断开时记录断点，下次用增量补全

## 移动端 Room 数据库设计

### 基于新模型的表结构

```kotlin
@Entity(tableName = "sync_state")
data class SyncState(
    @PrimaryKey val sessionId: String,
    val lastSeq: Int
)

@Entity(tableName = "session")
data class SessionEntity(
    @PrimaryKey val id: String,
    val projectId: String,
    val slug: String,
    val title: String,
    val agent: String? = null,
    val model: String? = null,       // JSON
    val timeCreated: Long,
    val timeUpdated: Long
)

@Entity(
    tableName = "session_message",
    foreignKeys = [ForeignKey(
        entity = SessionEntity::class,
        parentColumns = ["id"],
        childColumns = ["sessionId"],
        onDelete = ForeignKey.CASCADE
    )],
    indices = [Index("sessionId"), Index("sessionId", "type"), Index("timeCreated")]
)
data class SessionMessageEntity(
    @PrimaryKey val id: String,
    val sessionId: String,
    val type: String,                 // assistant / user / shell / compaction / agent-switched / model-switched / synthetic
    val data: String,                 // JSON，内容因 type 而异
    val timeCreated: Long,
    val timeUpdated: Long
)
```

### 与旧模型的对比

| 维度 | 旧模型 (message + part) | 新模型 (session_message) |
|------|------------------------|------------------------|
| 表数量 | 2（message + part） | 1（session_message） |
| 关联 | part → message → session | session_message → session |
| 查询 | 需要 JOIN | 单表查询 |
| 写入 | 每次更新写 part 行 | 每次更新写 session_message 行的 data JSON |
| 内容大小 | part.data 较小（单个文本/工具） | session_message.data 较大（含完整 content 数组） |

## 数据库膨胀分析与移动端优化

### 服务端 DB 为何会膨胀到 700MB+

| 原因 | 说明 | 占比预估 |
|------|------|---------|
| **PartTable.data / SessionMessageTable.data（大字段 JSON）** | 包含 LLM 输出全文、推理过程、工具执行输出、git diff 快照等。推理模型的 `reasoning` 文本可达数万~数十万 token | ~60% |
| **EventTable 双重存储** | 每条变更同时写入 EventTable，事件日志**永不清理**。当前状态表 + 全量历史 ≈ 2x 数据量 | ~35% |
| **Snapshot 数据** | step 事件中的 snapshot 字段存储 git diff 内容 | ~5% |

**核心问题：EventTable 只增不删**，没有 TTL、归档或 rollup 机制。

### 移动端同步优化策略

| 策略 | 做法 | 效果 |
|------|------|------|
| **跳过 EventTable** | 不同步 event 表，只回放事件更新 SessionMessage 当前状态 | 节省 ~50% 存储 |
| **跳过 delta 事件** | `session.next.text.delta.1`、`session.next.tool.input.delta.1`、`session.next.reasoning.delta.1` 等流式增量事件不需要回放 | 大幅减少事件处理量 |
| **选择性同步 content** | reasoning 文本、大 tool 输出截断，移动端直接基于截断后的本地数据展示 | 大幅节省 |
| **按 session 活跃度同步** | 只同步最近 N 天活跃的 session，旧 session 仅保留元数据 | 控制长期膨胀 |
| **本地数据保留策略** | 设置存储上限（如 200MB），超出时清理最旧 session 的 data 缓存 | 防止无限膨胀 |

### SessionMessage 内容截断规则

> **待讨论**：以下截断策略为初始草案，需要逐条确认。

#### 事件级大字段分析

每个 `session.next.*` 事件回写 SessionMessage 时，需要决定哪些字段保留、截断或跳过。以下是所有大字段的完整清单：

| 事件类型 | 大字段 | 典型大小 | 移动端展示需求 | 草案策略 |
|----------|--------|---------|---------------|---------|
| `text.ended` | `text` | 数百~数万字符 | LLM 回复正文，核心内容 | 截断至 2000 字符 |
| `reasoning.ended` | `text` | 数千~数十万字符（推理模型） | 可折叠展示，用户偶尔查看 | 保留前后各 100 字符，中间 `...[truncated]...` |
| `tool.success/progress` | `content[].text` | 数百~数十KB（grep 结果、文件内容等） | 工具输出，用户可能展开查看 | 跳过 content，只存 name/status/time |
| `tool.success/progress` | `structured` | 不定（结构化输出） | 部分工具有用（如搜索结果摘要） | 跳过 |
| `tool.input.ended` | `text` | 数百~数万字符（工具输入 JSON） | 一般不展示 | 截断至 500 字符？或跳过？ |
| `tool.called` | `input` | 数百~数万字符（工具调用参数） | 一般不展示 | 截断至 500 字符？或跳过？ |
| `shell.ended` | `output` | 数百~数十KB | 用户可能查看命令输出 | **跳过** |
| `prompted` | `prompt.text` | 数十~数千字符 | 用户输入，核心内容 | 截断至 2000 字符 |
| `prompted` | `prompt.files[].source.text` | 数百~数十KB | 文件附件内容 | 跳过 source.text，保留 name/uri/mime |
| `step.started/ended` | `snapshot` | 数千~数十KB（对话快照） | 不展示 | **跳过** |
| `compaction.ended` | `text` | 数千字符（压缩摘要） | 历史上下文摘要 | 全量保留？截断？ |
| `compaction.ended` | `include` | 数千字符 | 压缩包含的内容说明 | 跳过？ |
| `step.failed/tool.failed` | `error.message` | 数百~数千字符 | 错误信息，有用 | 全量保留 |
| `retried` | `error.responseBody` | 数百~数十KB | 不展示 | 跳过 |

#### 需要讨论的问题

1. **reasoning**：保留前后各 100 字符，中间用 `...[truncated]...` 标记。UI 可展示推理开头+结尾，让用户感知方向和结论。
2. **tool input**（`tool.input.ended.text` / `tool.called.input`）：截断 500 字 vs 跳过？用户是否需要看到工具调用了什么参数？
3. **tool output**（`tool.success.content[].text`）：完全跳过 vs 保留前 500 字？
   - 某些工具输出很短（如 `file_exists → true`），跳过可能丢失有用信息
   - 是否按 tool name 区分？比如 bash/Read 输出大，但 list_directory 输出小
4. **shell.output**：完全跳过 vs 保留前 500 字？
5. **compaction**：全量保留 vs 截断？移动端是否需要完整的压缩摘要？
6. **prompt.text**：2000 字符是否够？用户粘贴大段代码时可能更长
7. **tool.structured**：是否部分工具有用的结构化输出需要保留？

#### 旧版截断规则（参考）

| Message 类型 | 字段 | 同步策略 |
|-------------|------|---------|
| `assistant` | `content[].text` (type=text) | 截断至 2000 字符 |
| `assistant` | `content[]` (type=reasoning) | 保留前后各 100 字符，中间 `...[truncated]...` |
| `assistant` | `content[]` (type=tool, state=completed) | 跳过 output content，只存 name/status/time |
| `assistant` | `content[]` (type=tool, state=running) | 只存 name/callID |
| `assistant` | `snapshot` | **跳过** |
| `assistant` | `tokens` / `cost` | 同步（很小） |
| `user` | `text` | 截断至 2000 字符 |
| `shell` | `output` | **跳过** |
| `compaction` | `summary` | 同步 |
| `agent-switched` / `model-switched` | 全部 | 同步（很小） |

### Room 实体设计

移动端懒加载基于本地 DB，不需要网络请求。UI 列表页先加载摘要字段，用户展开时从本地 DB 读取完整 data：

```kotlin
@Entity(tableName = "session_message")
data class SessionMessageEntity(
    @PrimaryKey val id: String,
    val sessionId: String,
    val type: String,
    val data: String,                 // JSON（同步时已按截断规则处理）
    val timeCreated: Long,
    val timeUpdated: Long
)
```

- 同步时按截断规则处理大字段后写入 `data`
- UI 层展示列表时只解析 `data` 中的摘要信息（如 text 前 N 字符、tool name/status）
- 用户展开时再从本地 DB 读取完整 `data` 渲染详情
- 不需要 `session_message_content` 表，不需要网络请求回源

## 与当前锚点方案的关系

### 当前方案（锚点 + 分页）

- 基于 `GET /session/:id/message` 分页 API
- 用 `syncAnchor`（最后一条已完成的 assistant 消息 ID）标记同步进度
- 无法检测删除，Part 全量同步

### 迁移路径

| 阶段 | 方案 | 说明 |
|------|------|------|
| **当前** | 锚点 + 分页 | 不依赖 experimental flag，立即可用 |
| **过渡** | 锚点 + `/sync/history` 双写 | 开启 flag 后，同时用两种方式同步，验证一致性 |
| **目标** | 纯 `/sync/history` + `session.next.*` | 基于事件溯源，支持删除检测、精确增量、实时推送 |

### 渐进迁移注意

- 开启 `OPENCODE_EXPERIMENTAL_WORKSPACES` 后，**只有新产生的事件**会被持久化，历史会话没有事件
- 迁移期间可以：锚点方案负责历史会话，`/sync/history` 负责新会话
- 旧模型事件（`message.updated.1` 等）和新模型事件（`session.next.*`）同时存在，客户端可选择只处理新模型

## 注意事项

1. **单写入者模型**：每个 session 同一时间只能由一个客户端写入。移动端目前只做读同步，不写入新消息。
2. **事件版本兼容**：事件类型包含版本号后缀（如 `.1`），客户端应忽略未知版本，未来版本升级时向后兼容。
3. **数据量控制**：首次同步或长时间离线后，增量可能较大。建议客户端分页处理或配合进度提示。
4. **序列号 gap 检测**：服务端 replay 时会检测 seq 跳跃，客户端不自行写入事件。
5. **本地懒加载**：UI 列表先展示摘要，用户展开时从本地 DB 读取完整 data 渲染，无需网络请求。
6. **存储上限**：移动端可设置存储上限（如 200MB），达到阈值时清理最旧 session 的懒加载缓存。
7. **压缩传输**：`/sync/history` 响应应启用 gzip 压缩，大 JSON 传输量可减少 10-20 倍。
8. **delta 事件跳过**：`text.delta`、`tool.input.delta`、`reasoning.delta` 等流式增量事件在同步场景中应跳过，只处理 `ended` 事件即可获得完整内容。

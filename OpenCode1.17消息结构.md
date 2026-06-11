# OpenCode 1.17 消息结构文档

> 基于 opencode 源码 `packages/core/src/session/` 分析，版本 1.17。
> 用于 Android EventReplayer 重构参考，以及后续 opencode 升级时的版本对比。

## 1. session_message 表结构

SQL 定义：`packages/core/src/session/sql.ts` `SessionMessageTable`

| 列 | 类型 | 说明 |
|---|---|---|
| `id` | TEXT PK | `msg_` 前缀，见 `message-id.ts` |
| `session_id` | TEXT FK → session.id | |
| `type` | TEXT | 消息类型（见下方联合类型） |
| `seq` | INTEGER | 首次 insert 时的事件 seq，**update 时不改变** |
| `time_created` | INTEGER | epoch 毫秒 |
| `time_updated` | INTEGER | epoch 毫秒 |
| `data` | TEXT (JSON) | 消息体，JSON 编码，不含 `id` 和 `type` 字段 |

重要特性：
- **seq 在 insert 时设置，update 时不改变**（`projector.ts:122` updateMessage 只 set type/time_created/data）
- **id 是 msg_ 前缀**，由 `SessionMessageID.ID` 生成（`message-id.ts`）
- **data 字段不包含 id 和 type**（编码时解构：`const { id, type, ...data } = encoded`）

## 2. 消息类型（SessionMessage.Message）

Schema 定义：`packages/core/src/session/message.ts`

联合类型，通过 `type` 字段区分：

### 2.1 agent-switched

```
{
  agent: string,          // agent ID
  time: { created: number }
}
```
可选字段：`metadata`

### 2.2 model-switched

```
{
  model: {
    id: string,
    providerID: string,
    variant?: string       // 可选，默认 "default"
  },
  time: { created: number }
}
```
可选字段：`metadata`

### 2.3 user

```
{
  text: string,
  files?: FileAttachment[],
  agents?: AgentAttachment[],
  time: { created: number }
}
```
可选字段：`metadata`

FileAttachment：
```
{
  uri: string,
  mime: string,
  name?: string,
  description?: string,
  source?: { start: number, end: number, text: string }
}
```

AgentAttachment：
```
{
  name: string,
  source?: { start: number, end: number, text: string }
}
```

注意：`prompt.ts` 中 Prompt 的 `files` 和 `agents` 都是 optional（缺省为空数组），但 `message.ts` 中 User 继承了这些定义。实际 DB 中 user 消息恒有 `files`、`agents`、`references` 字段（references 来自 Prompt 的 Schema 但未在 Prompt class 中显式定义——可能是 effect Schema 的默认行为，需确认）。

### 2.4 assistant

```
{
  agent: string,
  model: {
    id: string,
    providerID: string,
    variant?: string
  },
  content: AssistantContent[],
  snapshot?: { start?: string, end?: string },
  finish?: string,           // "stop" | "tool-calls" | "length" | "error" | 其他
  cost?: number,
  tokens?: {
    input: number,
    output: number,
    reasoning: number,
    cache: { read: number, write: number }
  },
  error?: { type: "unknown", message: string },
  time: {
    created: number,
    completed?: number
  }
}
```
可选字段：`metadata`

DB 中观察到的 top-level key 组合：
- `agent, content, cost, finish, model, snapshot, time, tokens` — 正常完成（finish=stop/tool-calls）
- `agent, content, error, finish, model, snapshot, time` — 失败（finish=error）
- `agent, content, model, snapshot, time` — 未完成（无 finish/cost/tokens）

#### 2.4.1 AssistantContent（联合类型，通过 `type` 区分）

**text**：
```
{
  type: "text",
  id: string,        // 如 "txt-0"
  text: string
}
```

**reasoning**：
```
{
  type: "reasoning",
  id: string,        // reasoningID
  text: string,
  providerMetadata?: ProviderMetadata
}
```

**tool**：
```
{
  type: "tool",
  id: string,              // callID
  name: string,            // tool name
  provider?: {
    executed: boolean,
    metadata?: ProviderMetadata,
    resultMetadata?: ProviderMetadata
  },
  state: ToolState,        // 见下方
  time: {
    created: number,
    ran?: number,
    completed?: number,
    pruned?: number
  }
}
```

#### 2.4.2 ToolState（联合类型，通过 `status` 区分）

**pending**：
```
{
  status: "pending",
  input: string            // 注意：是 string，不是 object
}
```

**running**：
```
{
  status: "running",
  input: Record<string, unknown>,   // object
  structured: Record<string, any>,
  content: ToolContent[]
}
```

**completed**：
```
{
  status: "completed",
  input: Record<string, unknown>,
  attachments?: FileAttachment[],
  content: ToolContent[],
  outputPaths?: string[],
  structured: Record<string, any>,
  result?: unknown
}
```

**error**：
```
{
  status: "error",
  input: Record<string, unknown>,
  content: ToolContent[],
  structured: Record<string, any>,
  error: { type: "unknown", message: string },
  result?: unknown
}
```

#### 2.4.3 ToolContent（联合类型，通过 `type` 区分）

定义在 `packages/llm/src/schema/messages.ts`

**text**：`{ type: "text", text: string }`

**file**：`{ type: "file", uri: string, mime: string, name?: string }`

### 2.5 compaction

```
{
  reason: "auto" | "manual",
  summary: string,
  recent: string,            // JSON 字符串，序列化的最近对话
  time: { created: number, completed?: number }
}
```
可选字段：`metadata`

DB 中观察到两个版本：
- v1 (compaction.ended.1): 无 messageID，有 `include` 字段
- v2 (compaction.ended.2): 有 messageID，有 `recent` 字段

### 2.6 synthetic

```
{
  sessionID: string,
  text: string,
  time: { created: number }
}
```
可选字段：`metadata`

### 2.7 system

```
{
  text: string,
  time: { created: number }
}
```
可选字段：`metadata`

### 2.8 shell

```
{
  callID: string,
  command: string,
  output: string,
  time: {
    created: number,
    completed?: number
  }
}
```
可选字段：`metadata`

## 3. V2 事件结构（SessionEvent）

Schema 定义：`packages/core/src/session/event.ts`

### 3.1 产生 Insert 的事件

| 事件类型 | 消息 ID 字段 | 产生的消息类型 |
|---|---|---|
| `session.next.agent.switched` | `messageID` | agent-switched |
| `session.next.model.switched` | `messageID` | model-switched |
| `session.next.prompted` | `messageID` | user |
| `session.next.synthetic` | `messageID` | synthetic |
| `session.next.context.updated` | `messageID` | system |
| `session.next.shell.started` | `messageID` | shell |
| `session.next.step.started` | `assistantMessageID` | assistant |
| `session.next.compaction.started` | `messageID` | compaction |

### 3.2 产生 Update 的事件

这些事件不创建新消息，而是更新已有的 assistant 消息：

| 事件类型 | 目标 ID 字段 | 更新内容 |
|---|---|---|
| `session.next.step.ended` | `assistantMessageID` | finish, cost, tokens, snapshot.end, time.completed |
| `session.next.step.failed` | `assistantMessageID` | finish="error", error, time.completed |
| `session.next.text.started` | `assistantMessageID` | 追加 text content item |
| `session.next.text.ended` | `assistantMessageID` | 更新 text content |
| `session.next.tool.input.started` | `assistantMessageID` | 追加 tool content item (pending) |
| `session.next.tool.input.ended` | `assistantMessageID` | 更新 tool input |
| `session.next.tool.called` | `assistantMessageID` | 更新 tool state → running |
| `session.next.tool.progress` | `assistantMessageID` | 更新 tool structured/content |
| `session.next.tool.success` | `assistantMessageID` | 更新 tool state → completed |
| `session.next.tool.failed` | `assistantMessageID` | 更新 tool state → error |
| `session.next.reasoning.started` | `assistantMessageID` | 追加 reasoning content item |
| `session.next.reasoning.ended` | `assistantMessageID` | 更新 reasoning text |
| `session.next.shell.ended` | (无 msg ID，通过 callID 匹配) | 更新 shell output |
| `session.next.compaction.ended` | `messageID` | 更新 compaction summary |

### 3.3 特殊事件

| 事件类型 | 说明 |
|---|---|
| `session.next.retried` | 无 messageID/assistantMessageID，不产生新消息（opencode projector 直接 Effect.void） |
| `session.next.interrupt.requested` | projector 直接 Effect.void |
| `session.next.prompt.admitted` | 只更新 session_input 表，不产生 session_message |
| `session.next.prompt.promoted` | 通过 insertMessage 产生 session_message |

### 3.4 V1 事件（与 V2 并存）

| 事件类型 | 写入表 | 说明 |
|---|---|---|
| `message.updated` | message 表 (V1) | 与 session_message 表无关，ID 体系不同 |
| `message.removed` | 删除 message 表 + session_message 表 | messageID 是 `msg_` 前缀 |
| `message.part.updated` | part 表 (V1) | 与 session_message 表无关 |
| `message.part.removed` | 删除 part 表 | |
| `session.created` | session 表 | |
| `session.updated` | session 表 | |
| `session.deleted` | 删除 session 表 | |

## 4. 事件 data 字段详细结构

### 4.1 Insert 类事件 data

**session.next.prompted**：
```
{
  sessionID, timestamp, messageID,
  delivery: "steer" | "queue",
  prompt: {
    text: string,
    files?: FileAttachment[],
    agents?: AgentAttachment[],
    references?: ...     // 可选
  }
}
```

**session.next.step.started**：
```
{
  sessionID, timestamp, assistantMessageID,
  agent: string,
  model: { id, providerID, variant? },
  snapshot?: string
}
```

**session.next.step.ended** (version 2)：
```
{
  sessionID, timestamp, assistantMessageID,
  finish: string,
  cost: number,
  tokens: { input, output, reasoning, cache: { read, write } },
  snapshot?: string
}
```

**session.next.step.failed** (version 2)：
```
{
  sessionID, timestamp, assistantMessageID,
  error: { type: "unknown", message: string }
}
```

**session.next.text.started**：
```
{ sessionID, timestamp, assistantMessageID, textID }
```

**session.next.text.ended**：
```
{ sessionID, timestamp, assistantMessageID, textID, text }
```

**session.next.tool.input.started**：
```
{ sessionID, timestamp, assistantMessageID, callID, name }
```

**session.next.tool.input.ended**：
```
{ sessionID, timestamp, assistantMessageID, callID, text }
```

**session.next.tool.called**：
```
{
  sessionID, timestamp, assistantMessageID, callID,
  tool: string,              // 注意：字段名是 "tool"，不是 "name"
  input: Record<string, unknown>,
  provider: { executed: boolean, metadata?: ProviderMetadata }
}
```

**session.next.tool.progress**：
```
{
  sessionID, timestamp, assistantMessageID, callID,
  structured: Record<string, any>,
  content: ToolContent[]
}
```

**session.next.tool.success**：
```
{
  sessionID, timestamp, assistantMessageID, callID,
  structured: Record<string, any>,
  content: ToolContent[],
  outputPaths?: string[],
  result?: unknown,
  provider: { executed: boolean, metadata?: ProviderMetadata }
}
```

**session.next.tool.failed**：
```
{
  sessionID, timestamp, assistantMessageID, callID,
  error: { type: "unknown", message: string },
  result?: unknown,
  provider: { executed: boolean, metadata?: ProviderMetadata }
}
```

**session.next.reasoning.started**：
```
{
  sessionID, timestamp, assistantMessageID, reasoningID,
  providerMetadata?: ProviderMetadata
}
```

**session.next.reasoning.ended**：
```
{
  sessionID, timestamp, assistantMessageID, reasoningID,
  text: string,
  providerMetadata?: ProviderMetadata
}
```

**session.next.agent.switched**：
```
{ sessionID, timestamp, messageID, agent: string }
```

**session.next.model.switched**：
```
{ sessionID, timestamp, messageID, model: { id, providerID, variant? } }
```

**session.next.synthetic**：
```
{ sessionID, timestamp, messageID, text }
```

**session.next.shell.started**：
```
{ sessionID, timestamp, messageID, callID, command }
```

**session.next.shell.ended**：
```
{ sessionID, timestamp, callID, output }
```

**session.next.compaction.started**：
```
{ sessionID, timestamp, messageID, reason: "auto" | "manual" }
```

**session.next.compaction.ended** (version 2)：
```
{ sessionID, timestamp, messageID, reason, text, recent }
```

**session.next.compaction.ended** (version 1, 无 messageID)：
```
{ sessionID, timestamp, text, include?: string }
```

### 4.2 删除类事件 data

**message.removed**：
```
{ sessionID, messageID }     // messageID 是 msg_ 前缀
```

**message.part.removed**：
```
{ sessionID, messageID, partID }
```

## 5. V1 与 V2 ID 体系的关系

- **V1 表** (message + part)：ID 来自 `message.updated` 的 `info.id`，是 `msg_` 前缀
- **V2 表** (session_message)：ID 来自 V2 事件的 `messageID` / `assistantMessageID`，也是 `msg_` 前缀
- **V1 和 V2 的 assistant 消息 ID 完全不同**——同一轮对话中 V1 创建的 assistant ID 与 V2 创建的 assistant ID 是两个独立 ID
- **session_message 表只存 V2 ID**，V1 ID 仅存于 message 表
- **message.removed 的 messageID 是 V1 的 msg_ ID**，但如果 opencode 同时删除 session_message，则该 ID 在两个表中都能匹配（需确认实际行为）

## 6. EventReplayer 重构要点

### 6.1 只需处理的事件

**V2 事件（创建/更新消息）**：
- 所有 `session.next.*` 事件
- 从 `data.messageID` / `data.assistantMessageID` 提取正确的 `msg_` ID

**V1 事件（仅删除）**：
- `message.removed` — 用 `data.messageID`
- `message.part.removed` — 用 `data.messageID` + `data.partID`

### 6.2 不需处理的 V1 事件

- `message.updated` — 写入 V1 message 表，与 session_message 无关
- `message.part.updated` — 写入 V1 part 表，与 session_message 无关

### 6.3 不需处理的 V2 事件

- `session.next.retried` — projector 直接 void，不产生 session_message
- `session.next.interrupt.requested` — projector 直接 void
- `session.next.prompt.admitted` — 只更新 session_input 表
- `session.next.text.delta` / `session.next.tool.input.delta` / `session.next.reasoning.delta` / `session.next.compaction.delta` — 流式片段，不持久化到 event 表
- `session.next.prompt.promoted` — 触发 insertMessage，但实际产生的消息与 prompted 相同
- `session.next.moved` — 只更新 session 表

### 6.4 关键差异对照（EventReplayer 当前实现 vs opencode projector）

| 项目 | 当前 EventReplayer | opencode projector |
|---|---|---|
| Insert 消息 ID | `event.id`（`evt_` 前缀）❌ | `data.messageID` / `data.assistantMessageID`（`msg_` 前缀）|
| tool.name 来源 | `session.next.tool.input.started` 的 `name` | 同左 |
| tool state pending.input | `""` (空字符串) ✅ | `""` (空字符串，符合 ToolStatePending schema) |
| tool provider 结构 | 只设 `executed` | `{ executed, metadata?, resultMetadata? }` |
| user 消息 references | 未构建 | 存在于 ground truth（来自 Prompt schema）|
| assistant text id | 未设 `id` 字段 | 从 `textID` 设置 |
| reasoning providerMetadata | 未构建 | 从事件 data 设置 |
| compaction.recent | 未构建 | v2 事件中有 `recent` 字段 |
| compaction.include | 从 v1 事件构建 | v1 事件有 `include` |
| shell.ended | 通过 callID 匹配内存 cache | 通过 callID 查 DB |
| message.updated 处理 | 尝试关闭 assistant（V1 ID，实际无效）| 写入 V1 message 表 |
| message.part.updated 处理 | 合并 tool state/patch（V1 ID，实际无效）| 写入 V1 part 表 |

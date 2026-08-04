# OpenCode V2 适配研究报告

**研究日期**: 2026-08-04
**V2 版本**: v0.0.0-next-16732（beta）
**数据来源**: opencode 源码 (origin/dev @ 7fe993879f) + Docker 实际运行验证

---

## 一、V2 概述

OpenCode V2 是一次重大架构升级，引入了事件溯源（Event Sourcing）的 Session 运行时。V2 的核心变化方向：

1. **事件溯源架构**: Session 的所有状态变化以 durable event 形式持久化，通过 projector 投影出消息视图
2. **双 API 并存**: V1 API (`/session/*`, `/global/*`) 和 V2 API (`/api/*`) 同时运行，V1 未被移除
3. **Schema 所有权迁移**: 数据库 schema 从 `packages/opencode` 迁移到 `packages/core`，由 Drizzle 管理
4. **配置兼容**: V1 配置格式仍然支持，V2 在内存中自动转换，不需要改写源文件

### 三个有意为之的 Breaking Changes

| 变更项 | 说明 |
|--------|------|
| Plugin API | 插件使用全新 API，需移植代码 |
| Server API & Clients | 服务端 API 合约重构，V2 客户端使用 `@opencode-ai/client` |
| TUI 配置 | 从分层 `tui.json(c)` 改为单一全局 `cli.json`（自动迁移） |

### V2 与 V1 能否并行运行

**可以并行运行**，互不冲突：
- 命令名不同：V1 = `opencode`，V2 = `opencode2`
- 数据库不同：V1 = `opencode.db`，V2 = `opencode-next.db`（beta channel）
- 只需端口不同、数据目录隔离即可

---

## 二、数据库 Schema 变化（Docker 实测）

### 2.1 ⚠️ 重大发现：V2 不再写入 `message`/`part` 表

通过 Docker 运行 V2 并实际发送消息后检查数据库：

| 表 | V1 行为 | V2 行为 | 影响 |
|----|---------|---------|------|
| `message` | 存储所有消息 | **❌ 空表**，V2 不写入 | **Bridge 直读方案完全失效** |
| `part` | 存储所有消息部件 | **❌ 空表**，V2 不写入 | **Bridge 直读方案完全失效** |
| `session_message` | V1 也有但非主要 | **✅ V2 唯一的消息存储** | **必须切换到读此表** |
| `event` | 存储事件 | ✅ 仍然写入 | 兼容 |
| `event_sequence` | 序列号 | ✅ 仍然写入 | 兼容 |

**结论：Bridge 当前依赖的 `get_messages_since()` 读 `message`+`part` 表的方案在 V2 下完全不可用。必须切换到读 `session_message` 表。**

### 2.2 `session_message` 表 — V2 的唯一消息源

| 列 | 类型 | 说明 |
|----|------|------|
| id | text PK | `msg_*` 格式 |
| session_id | text NOT NULL FK | |
| type | text NOT NULL | user/assistant/tool/text/reasoning/shell/system/compaction/agent-switched/model-switched/synthetic |
| seq | integer NOT NULL | 单调递增的 per-session 序列号 |
| time_created | integer NOT NULL | |
| time_updated | integer NOT NULL | |
| data | text(json) NOT NULL | 完整的 SessionMessage 数据 |

索引: `session_message_session_seq_idx(session_id, seq)` UNIQUE

### 2.3 消息删除（Revert）行为实测

通过 V2 的 `revert/stage` → `revert/commit` 流程测试消息删除：

1. **revert/stage**: 在 session 表记录 `revert` JSON（含 `messageID`），发射 `session.revert.staged.1` 事件
2. **revert/commit**: **直接从 `session_message` 表删除被 revert 的消息**，发射 `session.revert.committed.1` 事件（含 `to: msg_xxx`）

**实测结果**：
- revert commit 后，`session_message` 中被删除的消息行**直接消失**
- `session.revert.committed.1` 事件只包含 `to` 字段（revert 终点的 messageID），**不直接列出所有被删除的消息 ID**
- **全量快照同步不受影响**：直接从 `session_message` 读取即可获得正确的当前状态
- **增量同步需要处理**：收到 `session.revert.committed` 事件时，需要重新拉取全量快照或根据 `to` 字段推算删除范围

### 2.4 V2 事件类型实测

V2 实际使用的事件前缀是 `session.*`（不是之前文档中的 `session.next.*`）：

实测事件列表（按 seq 顺序）：
```
session.created.1
session.input.admitted.1
session.execution.started.1
session.instructions.updated.2
session.input.promoted.1
session.step.started.1
session.text.started.1
session.text.ended.1
session.step.ended.1
session.execution.succeeded.1
session.usage.recorded.1
session.renamed.1
session.revert.staged.1
session.revert.committed.1
```

**注意**: V1 事件类型（`message.updated`, `message.part.updated` 等）在 V2 中**未观察到**。V2 使用全新的 `session.*` 事件体系。

### 2.5 数据库文件名变化

| 渠道 | DB 文件名 |
|------|-----------|
| V1 / stable | `opencode.db` |
| V2 beta (next) | `opencode-next.db` |

**影响**: Bridge 的 `db_path()` 需要根据 opencode 版本选择正确的 DB 文件名。

---

## 三、HTTP API 变化（Docker 实测）

### 3.0 ⚠️ 重大发现：V1 API 完全移除

通过 Docker 实测确认，**V2 中所有 V1 API 端点均返回 404**：

| V1 端点 | V2 状态 |
|---------|---------|
| `GET /global/health` | ❌ 404 |
| `GET /session` | ❌ 404 |
| `GET /session/:id` | ❌ 404 |
| `GET /session/:id/message` | ❌ 404 |
| `GET /experimental/session` | ❌ 404 |
| `GET /session/status` | ❌ 404 |
| `GET /global/event` (SSE) | ❌ 404 |

**这与之前从源码分析得出的"V1+V2 双 API 并存"结论不符**。可能是因为：
1. beta 版本只启用了 V2 API
2. 或者 V1 API 需要特定的配置/flag 才启用
3. 或者源码中的双 API 组装是渐进迁移的中间状态

**影响：Bridge 的代理转发层（fallback proxy）在 V2 下完全不可用。** Bridge 和 Android 必须适配 V2 API。

### 3.1 认证方式变化

| 项 | V1 | V2 |
|----|----|----|
| 方式 | 无认证 / 自定义 | HTTP Basic Auth |
| Header | — | `Authorization: Basic base64(user:password)` |
| 密码 | — | server 启动时生成，打印到日志 |

### 3.2 V1 → V2 API 映射

| 操作 | V1 路径 | V2 路径 | 变化 |
|------|---------|---------|------|
| 健康检查 | `GET /global/health` | `GET /api/health` | 路径变更 |
| 列出会话 | `GET /session` | `GET /api/session` | 游标分页，返回 `{data, cursor}` |
| 获取会话 | `GET /session/:id` | `GET /api/session/:sessionID` | 路径变更 |
| 获取消息 | `GET /session/:id/message` | `GET /api/session/:sessionID/message` | 游标分页，消息格式变化 |
| 发送提示 | `POST /session/:id/prompt_async` | `POST /api/session/:sessionID/prompt` | 返回 `SessionInput.Admitted`（非 204） |
| 列出模型 | `GET /model` | `GET /api/model` | 包装在 `{location, data}` 中 |
| 会话状态 | `GET /session/status` | `GET /api/session/active` | 仅返回 `{id:{type:"running"}}` |
| 中断会话 | `POST /session/:id/abort` | `POST /api/session/:sessionID/interrupt` | 路径变更 |
| 全局 SSE | `GET /global/event` | `GET /api/event` | 事件格式变化 |
| 会话 SSE | — | `GET /api/session/:sessionID/event?after=N` | ⭐ 新增：支持 seq 断点续传 |
| 删除会话 | `DELETE /session/:id` | ❌ **无 V2 等价** | 仍需使用 V1 |
| 会话 TODO | `GET /session/:id/todo` | ❌ **无 V2 等价** | 仍需使用 V1（todo.updated 事件仍存在） |
| 权限列表 | `GET /permission` | `GET /api/session/:sessionID/permission` | session 级别 |
| 问题列表 | `GET /question` | `GET /api/session/:sessionID/question` | session 级别 |

### 3.3 响应格式变化

**V1 会话列表**: 直接返回数组 `[Session, Session, ...]`
**V2 会话列表**: 包裹在 `{data: [...], cursor: {previous, next}}` 中，使用游标分页

**V1 模型列表**: 直接返回数组
**V2 模型列表**: 包裹在 `{location: {...}, data: [...]}` 中

### 3.4 消息格式变化

V1 的 `GET /session/:id/message` 返回扁平的 message + parts[] 结构。

V2 的 `GET /api/session/:sessionID/message` 返回 `SessionMessage.Message[]`，这是一个 tagged union：
- type=`user`: 用户消息
- type=`assistant`: AI 消息，包含 `content[]`（text/reasoning/tool 三种子类型）
- type=`agent-switched`/`model-switched`/`compaction`/`shell`/`system`/`synthetic`: 系统事件

**关键**: V2 的消息格式是事件溯源的投影结果，不是原始的 message+part 结构。

---

## 四、SSE/事件系统变化

### 4.1 V2 事件架构

V2 引入了事件溯源架构，事件分为两类：

| 类别 | 说明 | 可重放 |
|------|------|--------|
| **Durable（持久）** | 存储在 event 表，可通过 seq 重放 | ✅ |
| **Live（瞬态）** | 流式增量片段，不可重放 | ❌ |

### 4.2 V2 Session 事件类型

以 `session.next.*` 为前缀（durable 的可重放）：

**Durable 事件**:
- `agent.switched`, `model.switched`, `moved`
- `prompted`, `prompt.admitted`
- `context.updated`, `synthetic`
- `shell.started`, `shell.ended`
- `step.started`, `step.ended`, `step.failed`
- `text.started`, `text.ended`
- `tool.input.started`, `tool.input.ended`
- `tool.called`, `tool.progress`, `tool.success`, `tool.failed`
- `reasoning.started`, `reasoning.ended`
- `retried`, `compaction.started`, `compaction.ended`
- `revert.staged`, `revert.cleared`, `revert.committed`

**Live 事件（不可重放）**:
- `text.delta`, `reasoning.delta`, `tool.input.delta`, `compaction.delta`

### 4.3 V1 兼容事件（仍存在）

V1 的事件类型在 V2 中仍然存在：
- `session.created`, `session.updated`, `session.deleted` (durable)
- `message.updated`, `message.removed`, `message.part.updated`, `message.part.removed` (durable)
- `message.part.delta`, `session.diff`, `session.error` (live)

### 4.4 关键 SSE 端点

**V2 新增**: `GET /api/session/:sessionID/event?after=N`
- **可重放的 per-session 事件流**，支持从指定 seq 断点续传
- 这是 V2 同步的核心原语
- `after` = per-session 的 aggregate seq（不是全局 seq）

---

## 五、Bridge 同步方案分析

### 5.1 当前 Bridge 同步机制

```
Android → Bridge → opencode.db (SQLite 直读)
         ↓
         /api/sync/messages (读取 message + part 表)  ← V2 下为空！
         /api/sync/events   (读取 event 表)
```

### 5.2 V2 下的同步路径

#### ⚠️ 方案 A（继续直读 message/part 表）：已排除

V2 **不写入** `message`/`part` 表，此方案完全不可用。

#### ✅ 方案 B（直读 session_message 表）：推荐短期方案

V2 的 `session_message` 是唯一的消息存储，Bridge 已有 `get_session_message_snapshot()` 实现。

**改动点**：
1. Bridge 的 `get_init_snapshot()` 改为调用 `get_session_message_snapshot()` 而非 `get_legacy_message_snapshot()`
2. Bridge 的 `get_messages_since()` 改为基于 `session_message` 表的 `seq` 或 `time_updated` 做增量查询
3. DB 路径需要适配 `opencode-next.db`（beta channel）
4. 消息格式适配：`session_message.data` 的 JSON 结构与 V1 的 `message.data`+`part.data` 不同

**优点**：
- 改动集中在 Bridge 层，Android 端改动最小
- 不依赖 V2 HTTP API 的认证（Basic Auth）和响应格式变化
- session_message 的 `seq` 字段提供了可靠的排序和增量同步基础

**缺点**：
- `session_message.data` 格式与当前 Android 的 Domain Model 不同，需要 Bridge 层做格式转换
- revert 删除处理：`session.revert.committed` 事件只提供 `to` 字段，需要根据时间/seq 推算删除范围
- Bridge 仍需直读 DB 文件，未来 V2 可能进一步隔离

#### ✅ 方案 C（使用 V2 HTTP API）：推荐中期方案

使用 V2 的 `GET /api/session/:sessionID/event?after=N` 做增量同步，`GET /api/session/:sessionID/message` 做全量快照。

**优点**：
- 不再依赖直读数据库文件
- 使用 V2 的原生接口，架构更清晰
- per-session event stream 支持 seq 断点续传

**缺点**：
- V2 API 使用 Basic Auth，Bridge 需要获取和管理密码
- V2 API 响应格式与 V1 完全不同，Bridge 代理层需要大量重写
- Android DTO 和 Domain Model 需要适配

#### 推荐路径：B → C 渐进迁移

1. **Phase 1**（方案 B）：最小改动让 Bridge 支持读 `session_message` 表
2. **Phase 2**（方案 C）：Bridge 增加 V2 API 代理模式

### 5.3 增量同步与删除处理（基于 v0.1.25 方案改进）

#### 增量同步

v0.1.25 使用 `session_message` 做全量快照 + `event` 表做增量事件。但 `session_message` 的 `seq` 不连续（对应事件 seq 而非消息序号，实测 seq=4→17 跳跃），不适合直接做增量游标。

**推荐方案**：使用 `session_message.time_updated` 做增量查询（与当前 `message`+`part` 方案一致，仅切换表）：

```sql
-- Bridge 增量查询（替代 get_messages_since）
SELECT id, session_id, type, time_created, time_updated, data
FROM session_message
WHERE session_id = ? AND time_updated > ?
ORDER BY time_updated ASC
LIMIT ?
```

Android 端逻辑不变：记录 `lastTimeUpdated`，增量同步时拉取 `time_updated > lastTimeUpdated` 的消息。

#### 删除同步（增量方式）

`session.revert.committed.1` 事件格式：
```json
{"sessionID":"ses_xxx", "to":"msg_fca935309001XakBlZ3M9S3L5g"}
```

`to` = revert 终点 messageID，该 message 及其后所有消息被删除。

**增量删除算法**：
1. 收到 `session.revert.committed` 事件
2. 提取 `to` 字段中的 messageID
3. 在本地 DB 中查找该 messageID 的 `time_created`
4. 删除本地 `DELETE FROM session_message WHERE session_id = ? AND time_created >= ?`（该消息及之后的消息）
5. 如果本地找不到该 messageID（极端情况），回退到全量快照

**注意**：使用 `time_created` 而非 `seq` 确定删除范围，因为 seq 不连续且可能无法精确对应。

### 5.4 SSE 通知机制（V1 端点不存在的替代方案）

#### 问题

当前链路：opencode `/global/event` SSE → Bridge 代理 → Android → 触发增量同步
V2 中 `/global/event` 返回 404，整个通知链路断裂。

#### V2 替代方案

V2 的全局 SSE 端点是 `GET /api/event`，事件格式：
```
data: {"id":"evt_xxx","type":"session.text.ended.1","data":{"sessionID":"ses_xxx",...}}
```

与 V1 格式差异：
| 项 | V1 | V2 |
|----|----|----|
| SSE URL | `/global/event` | `/api/event` |
| 认证 | 无 | Basic Auth |
| 事件包裹 | `{type, properties, directory}` | `{id, type, data}` |
| 消息事件 | `message.part.updated` | `session.text.ended` |
| 同步触发 | `message.*`, `session.next.*` | `session.*` |

**Bridge 改造方案**：
1. SSE 代理 URL 改为 `/api/event`（需要处理 Basic Auth）
2. Bridge 做格式转换：将 V2 的 `{id, type, data}` 转换为 V1 的 `{type, properties, directory}` 格式
3. 这样 Android 端的 EventDispatcher 无需改动（Bridge 屏蔽差异）
4. EventDispatcher 的触发规则需要适配：将 `session.text.ended`、`session.step.ended` 等映射为同步触发

**V2 触发同步的事件类型**（都包含 `sessionID`）：
- `session.input.admitted` — 新用户消息
- `session.text.ended` — AI 文本输出完成
- `session.reasoning.ended` — 思考完成
- `session.tool.success` / `session.tool.failed` — 工具调用完成
- `session.step.ended` / `session.step.failed` — 步骤完成
- `session.revert.committed` — revert 删除
- `session.execution.succeeded` — 执行完成

### 5.5 纯 V2 适配（不做 V1 兼容）

本分支只支持 V2，不保留 V1 兼容层。Bridge 和 Android 直接消费 V2 格式。

| 组件 | 改动 |
|------|------|
| Bridge DB 读取 | 只读 `session_message` 表（删除 `message`+`part` 查询代码） |
| Bridge SSE 代理 | 连接 `/api/event` + Basic Auth，原样转发 V2 事件格式 |
| Bridge API 认证 | 转发 V2 的 Basic Auth 认证 |
| Bridge DB 路径 | 固定 `opencode-next.db` |
| Android EventDispatcher | 原生处理 `session.*` 事件（不依赖 `message.*`） |
| Android DTO | 适配 V2 的 `{id, type, data}` SSE 格式和 `SessionMessage` 消息格式 |

---

## 六、Android 客户端影响评估

### 6.1 不需要改动的部分

- Room 数据库结构（Android 本地 DB 不受影响）
- UI 渲染层（SessionMessageRenderer 等）
- 本地状态管理

### 6.2 可能需要改动的部分

| 模块 | 影响 | 优先级 |
|------|------|--------|
| OpencodeApiClient | API 路径/认证方式/响应格式 | 高（如果切换到 V2 API） |
| DTO → Domain 映射 | V2 消息格式完全不同 | 高（如果切换到 V2 API） |
| SSE 事件处理 | V2 新增大量 `session.next.*` 事件 | 中（如果需要处理 V2 事件） |
| SessionMessagePartRenderer | 新消息类型（agent-switched 等） | 低（渐进适配） |

### 6.3 如果保持 Bridge 直读数据库方案

Android 端**几乎不需要改动**：
- Bridge 屏蔽了 opencode 的版本差异
- Bridge API (`/api/sync/*`) 保持不变
- 只要 Bridge 能从 V2 的数据库正确读取数据，Android 无感知

---

## 七、V2 完整数据库 Schema（Docker 实测）

以下通过 Docker 运行 `opencode2 v0.0.0-next-16732` 实际验证的完整 schema：

### 新增表（V2 独有）

- `workspace`: 工作区管理
- `session_pending`: 用户输入收件箱（替代 session_input）
- `session_message`: 事件投影消息表
- `session_context_epoch` → 已简化为 `instruction_state` + `instruction_entry` + `instruction_blob`
- `credential`: 凭据管理
- `project` / `project_directory`: 项目管理
- `permission`: 权限规则（V2 重建，去掉 data 列，改为 action/resource 列）
- `account` / `account_state` / `control_account`: 账户管理
- `session_share`: 会话分享
- `kv`: 键值存储
- `data_migration`: 数据迁移记录
- `migration`: Schema 迁移记录

### V1 保留表（可能结构有变）

- `session`: 大量新增列
- `message`: 移除 role 列
- `part`: 移除 type 列
- `event`: 新增 created 列
- `event_sequence`: 新增 owner_id 列
- `todo`: 不变

---

## 八、实施计划（纯 V2）

本分支（`feat/v2-adaptation`）只支持 V2，不保留 V1 兼容。

### Phase 1: Bridge 适配

1. **DB 读取切换**：`get_init_snapshot()` 和增量查询改为读 `session_message` 表，删除 `message`+`part` 查询代码
2. **DB 路径**：改为 `opencode-next.db`
3. **SSE 代理**：URL 改为 `/api/event`，处理 Basic Auth
4. **SSE 格式**：原样转发 V2 的 `{id, type, data}` 格式
5. **同步触发**：不再依赖 V1 的 `message.*` 事件，改为 V2 的 `session.*` 事件
6. **revert 增量删除**：从 `session.revert.committed` 事件的 `to` 字段获取删除起点

### Phase 2: Android 适配

1. **SSE 事件解析**：适配 V2 的 `{id, type, data}` 格式
2. **EventDispatcher**：原生处理 `session.*` 事件类型，触发增量同步
3. **DTO**：适配 V2 `SessionMessage` 格式（`session_message.data` JSON）
4. **删除处理**：收到 `session.revert.committed` 时，根据 `to` 字段做增量删除
5. **API 调用**：V2 的 session list/message/prompt 等端点路径和响应格式

### Phase 3: 功能完善

1. V2 新事件类型的 UI 渲染（reasoning、tool 等）
2. compaction、revert 等新功能支持
3. 性能优化

---

## 附录：关键源码路径

| 内容 | 路径 |
|------|------|
| V2 数据库 schema 定义 | `packages/core/src/database/schema.gen.ts` |
| V2 迁移文件 | `packages/core/src/database/migration/` |
| V2 API 路由声明 | `packages/protocol/src/groups/` |
| V2 API 处理器 | `packages/server/src/handlers/` |
| V2 事件系统核心 | `packages/core/src/event.ts` |
| V2 Session 运行时 | `packages/core/src/session.ts` |
| SessionMessage schema | `packages/schema/src/session-message.ts` |
| Session 事件定义 | `packages/schema/src/session-event.ts` |
| API 组装（V1+V2 并存） | `packages/opencode/src/server/routes/instance/httpapi/api.ts` |
| V1→V2 配置迁移 | `packages/core/src/v1/config/migrate.ts` |
| V2 迁移指南 | https://opencode.ai/v2/docs/migrate-v1 |
| Schema Changelog | `specs/v2/schema-changelog.md` |

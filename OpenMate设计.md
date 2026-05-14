# OpenMate 设计文档

> OpenMate：opencode 的原生 Android 客户端，通过云端中继服务与运行在电脑上的 opencode 实例进行实时通讯，实现随时随地的移动端编码协作。

## 1. 整体架构

```
┌─────────────┐     WebSocket      ┌──────────────────┐     WebSocket      ┌──────────────┐
│  Android    │◄──────────────────►│  Cloud Relay      │◄──────────────────►│  Bridge       │
│  Client     │                    │  Server           │                    │  Agent        │
└─────────────┘                    └──────────────────┘                    └──────────────┘
                                          │                                    │
                                     MongoDB                           SSE / HTTP API
                                   (用户/实例/推送)                         │
                                                                 ┌──────────────┐
                                                                 │  opencode     │
                                                                 │  (localhost)  │
                                                                 └──────────────┘
```

### 四个组件

| 组件 | 语言/框架 | 运行位置 | 职责 |
|------|-----------|----------|------|
| **Cloud Relay Server** | .NET 10 (ASP.NET Core) | 云端 VPS | WebSocket 中继、GitHub OAuth 认证、多实例管理、推送通知 |
| **Bridge Agent** | TypeScript (Node/Bun) | opencode 同机 | 常驻服务，按需启停 opencode server，连接 SSE 流转发事件，代理客户端请求到 opencode HTTP API |
| **Android Client** | Kotlin / Jetpack Compose | 手机 | 全功能客户端，WebSocket 连接云端，本地 SQLite 缓存 |
| **opencode** | TypeScript | 电脑 | 不修改，Bridge Agent 通过其现有 HTTP API + SSE 交互 |

### 数据流

- **下行**：opencode SSE → Bridge Agent → Cloud Relay → Android Client
- **上行**：Android Client → Cloud Relay → Bridge Agent → opencode HTTP API
- **重连补发**：Client 断线后，通过 Bridge Agent 调用 opencode `POST /sync/history` 获取增量事件
- **WebSocket 连接模型**：客户端维持单个 WebSocket 连接，通过 `instanceId` 字段路由消息到不同实例的本地数据库

### 关键设计决策

- **Cloud Relay 不存储事件**：opencode 自身具备完整 Sync API，重连补发通过 Bridge→opencode 实现
- **单 WebSocket + instanceId 路由**：客户端一个连接接收所有实例事件，按 instanceId 分发到对应本地数据库
- **每实例独立 SQLite**：seq 天然隔离，删除实例直接删库，schema 升级独立
- **时间范围同步**：首次连接不同步全量历史，默认同步最近两周的会话（从该范围内最早事件的 seq 开始），用户可在设置中调整或清理更早数据
- **一个 Bridge Agent 一个 opencode 进程**：`opencode serve` 通过 `directory` 参数即可服务多项目，无需多 Bridge 实例

---

## 2. opencode 现有基础设施

opencode 已具备我们所需的绝大部分能力，无需修改其核心代码：

### 2.1 SSE 实时推送

- `GET /global/event` — 长连接，所有状态变更实时推送，无需轮询
- 心跳每 10 秒，断线自动重连
- 推送两类事件：
  - **Bus 事件**：`{ type: "session.created", properties: {...} }` — 完整对象，向后兼容
  - **Sync 事件**：`{ type: "sync", syncEvent: { type: "session.created.1", id, seq, aggregateID, data } }` — 事件溯源记录

### 2.2 Sync API（增量同步）

- `POST /sync/history` — 传入 `{ aggregateID: lastKnownSeq }`，只返回新增事件
- `POST /sync/replay` — 回放事件到数据库
- EventTable 持久化所有事件，按 aggregate（sessionID）分组，seq 递增

### 2.3 事件流模型

```
opencode 状态变更
  → SyncEvent.run() 
    → 1. Projector 修改数据库
    → 2. 持久化事件到 EventTable
    → 3. 发布到 Bus + GlobalBus
      → Bus 事件 (完整对象) → Bus.subscribeAll()
      → Sync 事件 (溯源记录) → GlobalBus.emit()
        → SSE /global/event 推出

GlobalEvent 线路格式:
{
  directory: string,
  project?: string,
  workspace?: string,
  payload: 
    | { type: "session.created", properties: {...} }     // Bus 事件
    | { type: "sync", syncEvent: { type, id, seq, aggregateID, data } }  // Sync 事件
}
```

### 2.4 已有跨设备同步模式

opencode 的 `control-plane/workspace.ts` 已实现跨设备同步：
- 连接远程 SSE 流 → 实时接收事件
- 断线后通过 `POST /sync/history` 补齐
- 这正好是 Bridge Agent 所需的模式

### 2.5 工作区层级

```
Project（项目，一个 git 仓库）
  └── Workspace（工作区，通常是 git worktree）
        └── Session（会话，一个对话）
              ├── UserMessage
              └── AssistantMessage
                    └── Part（text / tool / reasoning / file / subtask / ...）
```

---

## 3. Cloud Relay Server

### 3.1 核心功能

- **WebSocket 中继**：管理 Bridge Agent 和 Android Client 的长连接，双向转发消息
- **GitHub OAuth 认证**：GitHub OAuth 登录，JWT token 签发
- **多实例管理**：每个 Bridge Agent 注册为独立节点，客户端可切换
- **推送通知**：FCM 推送，客户端后台时通知权限请求、问题等

> **设计决策：Cloud Relay 不存储事件。** opencode 自身具备完整的 Sync API（增量同步 + 事件溯源），客户端重连补发通过 Bridge Agent 调用 opencode 的 `POST /sync/history` 实现，无需在中继层重复存储。

### 3.2 MongoDB 文档模型

仅存储用户认证、实例注册、推送通知相关数据，不存储事件：

```json
// 实例注册文档
{
  "_id": ObjectId,
  "instanceId": "公司开发机",
  "userId": "user-xxx",
  "directory": "/home/user/project",
  "projectName": "my-project",
  "connectedAt": ISODate,
  "lastHeartbeat": ISODate,
  "status": "online"        // online / offline
}

// 用户文档
{
  "_id": ObjectId,
  "userId": "user-xxx",
  "oauthProvider": "github",
  "oauthId": "12345",
  "email": "user@example.com",
  "name": "User",
  "avatar": "url",
  "createdAt": ISODate
}

// FCM 推送 Token
{
  "_id": ObjectId,
  "userId": "user-xxx",
  "instanceId": "公司开发机",
  "fcmToken": "xxx",
  "platform": "android",
  "updatedAt": ISODate
}
```

### 3.3 WebSocket 协议

所有通信使用 JSON 帧，统一消息格式：

```json
{
  "type": "event|request|response|error|heartbeat|sync|state",
  "id": "msg-uuid",
  "instanceId": "公司开发机",
  "payload": { ... },
  "seq": 42,
  "timestamp": 1700000000000
}
```

**消息类型：**

| type | 方向 | 用途 |
|------|------|------|
| `event` | Bridge→Server→Client | opencode 状态变更事件 |
| `request` | Client→Server→Bridge | 客户端操作请求 |
| `response` | Bridge→Server→Client | 请求的响应 |
| `error` | 双向 | 错误 |
| `heartbeat` | 双向 | 每 30 秒心跳 |
| `sync` | Server→Client | 重连补发事件 |
| `state` | Bridge→Server | Bridge 状态更新（opencode 启停等） |

### 3.4 HTTP API 端点

| 方法 | 路径 | 用途 |
|------|------|------|
| `GET` | `/ws` | WebSocket 连接（客户端和 Bridge 共用，通过 JWT token 区分身份） |
| `GET` | `/api/auth/github` | GitHub OAuth 登录 |
| `GET` | `/api/auth/github/callback` | GitHub OAuth 回调 |
| `POST` | `/api/auth/refresh` | 刷新 JWT token |
| `GET` | `/api/instances` | 列出用户关联的 opencode 实例及状态 |

---

## 4. Bridge Agent

### 4.1 定位

轻量 TypeScript 常驻服务，运行在 opencode 同一机器上。独立于 opencode 运行，按需启停 opencode server。

> **一个 Bridge Agent 对应一个 opencode 进程**。opencode serve 通过 `directory` 参数（query param 或 `x-opencode-directory` header）即可服务多个项目，无需启动多个 opencode 实例。Bridge Agent 注册时绑定的 `directory` 是默认目录，客户端可以通过请求中的 `directory` 参数切换项目。

### 4.2 生命周期管理

```
Bridge Agent 启动:
  1. 读取配置 (bridge.config.json)
  2. 连接 Cloud Relay WebSocket
  3. 注册实例，发送初始状态 (state: opencode=stopped)
  4. 若 autoStart=true，自动启动 opencode

Android 客户端触发启动:
  4. 发送 request(action: "opencode.start", directory: "/path/to/project")
  5. Bridge Agent 执行: opencode serve --hostname=... --port=...
  6. 检测 opencode 就绪（监听 stdout "opencode server listening"）
  7. 连接 SSE 流 (GET /global/event)
  8. 发送 state: opencode=running，通知客户端实例就绪

运行时:
  - SSE 事件 → 转发 Cloud Relay → 客户端
  - 客户端请求 → 代理到 opencode HTTP API

opencode 重启/更新:
  9. 客户端或 Bridge Agent 发起重启
  10. 停止 opencode 进程 → 重新执行步骤 5-7
  11. 断线期间 Cloud Relay 缓存事件 seq，重连后补发

Bridge Agent 关闭:
  - 优雅停止 opencode 进程
  - 通知 Cloud Relay 实例下线
```

### 4.3 进程管理

```typescript
class OpencodeManager {
  private proc: ChildProcess | null = null

  async start(directory: string): Promise<string> {
    // spawn: opencode serve --hostname=... --port=...
    // 等待 stdout 输出 "opencode server listening on ..."
    // 返回 URL
  }

  async stop(): Promise<void> {
    // SIGTERM → 等待退出 → SIGKILL
  }

  async restart(): Promise<string> {
    await this.stop()
    return this.start()
  }

  get status(): "stopped" | "starting" | "running" | "stopping"
}
```

### 4.4 配置文件

```jsonc
// ~/.opencode/bridge.config.json
{
  "serverUrl": "wss://relay.example.com/ws",
  "instanceName": "公司开发机",
  "opencode": {
    "binary": "opencode",           // 可执行文件路径
    "hostname": "127.0.0.1",
    "port": 4098,
    "directory": "/home/user/project"
  },
  "autoStart": true                 // Bridge 启动时自动启动 opencode
}
```

### 4.5 作为系统服务

```ini
# /etc/systemd/system/opencode-bridge.service (Linux)
[Unit]
Description=OpenCode Bridge Agent
After=network.target

[Service]
Type=simple
ExecStart=/usr/bin/npx opencode-bridge
Restart=always
RestartSec=5

[Install]
WantedBy=default.target
```

macOS 用 launchd，Windows 用 NSSM。

### 4.6 请求代理映射

客户端的操作请求，Bridge Agent 转换为 opencode HTTP API 调用：

| 客户端请求 action | opencode API | 说明 |
|---|---|---|
| `opencode.start` | spawn `opencode serve` | 启动 opencode |
| `opencode.stop` | SIGTERM 进程 | 停止 opencode |
| `opencode.restart` | stop + start | 重启 opencode |
| `project.list` | `GET /project` | 列出项目 |
| `project.current` | `GET /project/current` | 当前项目 |
| `workspace.list` | `GET /workspace/list` | 列出工作区 |
| `workspace.create` | `POST /workspace/create` | 创建工作区 |
| `workspace.remove` | `DELETE /workspace/remove` | 删除工作区 |
| `workspace.status` | `GET /workspace/status` | 工作区连接状态 |
| `workspace.adaptors` | `GET /workspace/adaptors` | 可用的工作区适配器 |
| `workspace.session-restore` | `POST /workspace/session-restore` | 迁移会话到工作区 |
| `session.list` | `GET /session` | 列出会话 |
| `session.create` | `POST /session` | 创建会话 |
| `session.get` | `GET /session/{id}` | 获取会话详情 |
| `session.delete` | `DELETE /session/{id}` | 删除会话 |
| `session.update` | `PATCH /session/{id}` | 更新会话 |
| `session.prompt` | `POST /session/{id}/prompt` | 发送消息/提示 |
| `session.abort` | `POST /session/{id}/abort` | 中止当前操作 |
| `session.share` | `POST /session/{id}/share` | 分享会话 |
| `session.unshare` | `POST /session/{id}/unshare` | 取消分享 |
| `session.fork` | `POST /session/{id}/fork` | 分叉会话 |
| `session.messages` | `GET /session/{id}/messages` | 获取会话消息列表 |
| `session.message` | `GET /session/{id}/message/{mid}` | 获取单条消息 |
| `permission.reply` | `POST /permission/{id}/reply` | 回复权限请求 |
| `question.reply` | `POST /question/{id}/reply` | 回复问题 |
| `file.list` | `GET /file/list` | 列出文件 |
| `file.read` | `GET /file/read` | 读取文件 |
| `file.status` | `GET /file/status` | 文件状态 |
| `find.text` | `POST /find/text` | 搜索文本 |
| `find.files` | `POST /find/files` | 搜索文件 |
| `find.symbols` | `POST /find/symbols` | 搜索符号 |
| `config.get` | `GET /config` | 获取配置 |
| `config.update` | `PATCH /config` | 更新配置 |
| `tool.list` | `GET /tool` | 工具列表 |
| `tool.ids` | `GET /tool/ids` | 工具 ID 列表 |
| `provider.list` | `GET /provider` | 模型提供者列表 |
| `provider.auth` | `POST /provider/auth` | 提供者认证 |
| `mcp.status` | `GET /mcp/status` | MCP 状态 |
| `mcp.add` | `POST /mcp/add` | 添加 MCP |
| `mcp.connect` | `POST /mcp/connect` | 连接 MCP |
| `mcp.disconnect` | `POST /mcp/disconnect` | 断开 MCP |

---

## 5. Android 客户端

### 5.1 导航层级

用户登录后，按以下层级导航：

```
登录 (GitHub OAuth)
  → 实例列表（选择一台电脑的 Bridge Agent）
    → 工作区列表（选择/创建工作区）
      → 会话列表（选择/创建/切换/删除会话）
        → 会话详情（消息流、发送消息、确认权限等）
```

### 5.2 页面结构

| 页面 | 功能 | 核心操作 |
|------|------|----------|
| **登录页** | GitHub OAuth | 登录、token 刷新 |
| **实例列表页** | 展示用户关联的所有 opencode 实例 | 查看在线/离线状态、选择实例、远程启停 opencode |
| **工作区列表页** | 展示当前实例的所有工作区 | 查看/创建/删除工作区、查看连接状态 |
| **会话列表页** | 展示当前工作区的所有会话 | 新建/打开/切换/删除会话、查看会话摘要 |
| **会话详情页** | 消息流与交互 | 发送消息、查看回复、确认权限/回答问题、查看工具调用、查看 diff |
| **设置页** | 配置管理 | 查看/修改 opencode 配置、管理 MCP、管理 provider |

### 5.3 工作区管理

**打开工作区：**
- 从工作区列表选择已存在的工作区，进入其会话列表
- 工作区显示：名称、分支名、连接状态（connected/disconnected/error）

**创建工作区：**
- 选择适配器类型（默认 worktree）
- 输入分支名称
- Bridge Agent 调用 `POST /workspace/create`
- 创建完成后自动切换到新工作区

**关闭/删除工作区：**
- 确认对话框（删除工作区会同时删除其下所有会话）
- Bridge Agent 调用 `DELETE /workspace/remove`
- 返回工作区列表

### 5.4 会话管理

**新建会话：**
- 在当前工作区内创建新会话
- 可选：指定 agent、model
- Bridge Agent 调用 `POST /session`

**打开会话：**
- 从会话列表选择，进入消息流
- 加载历史消息（`GET /session/{id}/messages`）
- 实时接收新消息（SSE 事件推送）

**切换会话：**
- 在会话详情页通过导航切换到同工作区的其他会话
- 保留当前会话状态，可随时切回

**删除会话：**
- 确认对话框
- Bridge Agent 调用 `DELETE /session/{id}`

### 5.4 会话内交互

**发送消息：**
- 客户端发送 `request(action: "session.prompt")`
- Bridge Agent 调用 `POST /session/{id}/prompt`
- 实时接收 SSE 事件流：message.part.delta（流式文本）、message.part.updated（工具调用、文件等）

**确认权限：**
- opencode 发出 `permission.asked` 事件
- 客户端弹出权限请求通知
- 用户确认/拒绝 → `request(action: "permission.reply")`

**回答问题：**
- opencode 发出 `question.asked` 事件
- 客户端弹出问题对话框
- 用户回答 → `request(action: "question.reply")`

**查看 diff：**
- `session.diff` 事件推送文件变更
- 客户端展示 additions/deletions/文件列表
- 可查看具体 diff 内容

**中止操作：**
- `request(action: "session.abort")` → `POST /session/{id}/abort`

### 5.5 离线与重连

- 客户端本地 SQLite 缓存已收到的消息和事件（每个实例独立数据库，见 5.7）
- 记录每个 aggregate 的最后 seq
- 重连时客户端发送 `request(action: "sync.history", aggregates: { "session-xxx": 42 })` 到 Bridge Agent
- Bridge Agent 调用 opencode 的 `POST /sync/history` 获取增量事件，转发给客户端
- 客户端在本地 replay 增量事件，更新物化视图
- 保证客户端关闭再打开后，状态完全一致
- 如果 Bridge Agent 离线（opencode 也未运行），客户端仅使用本地缓存，显示离线数据

### 5.5.1 首次同步策略

客户端首次连接某个实例时，通过 REST API 按需加载数据，不走 sync/history：

1. Bridge Agent 调用 `GET /session` 获取会话列表
2. 客户端按默认时间范围（两周）筛选出**最近两周内有过更新的会话**
3. 对筛选出的每个会话，调用 `GET /session/{id}/message?limit=80` 拉取最近消息
4. 加载完成后进入实时 SSE 事件推送模式

**后续增量同步**（重连补发）才使用 `POST /sync/history`：客户端传入所有已知 aggregate 的最新 seq，获取断线期间的增量事件。

**核心原则**：
- 会话元数据依赖 `GET /session` 等列表接口，不依赖事件 replay
- 消息内容按会话逐个拉取，保证每个同步的会话数据完整
- sync/history 仅用于重连增量补发，不用于首次加载

**时间范围设置**：
- 默认：最近 14 天内更新过的会话
- 用户可在设置中调整（7天/14天/30天/90天）
- 用户可在设置中清理更早的会话数据，释放空间
- 清理后如需重新查看，需重新拉取该会话消息

### 5.6 推送通知

- 当客户端在后台时，Cloud Relay 通过 FCM 推送通知
- 通知类型：新消息、权限请求、问题、操作完成
- 点击通知直接跳转到对应会话

### 5.7 本地数据存储

**每个实例一个独立的 SQLite 数据库**，通过 Room 管理。

```
data/data/com.openmate/databases/
  ├── instance_公司开发机.db      // 实例 "公司开发机" 的数据库
  ├── instance_家里服务器.db      // 实例 "家里服务器" 的数据库
  └── ...
```

**为什么不合并多实例数据：**
- opencode 的 seq 是 per-instance 的，分开存储无需额外处理
- 某个实例断线不影响其他实例的数据访问
- 删除实例时直接删库，不用级联清理
- 不同实例可能跑不同版本 opencode，schema 升级可独立进行

**表结构复用 opencode 的模型：**

| Room Entity | 对应 opencode | 说明 |
|-------------|---------------|------|
| `SessionEntity` | `Session.Info` | 会话元数据 |
| `session_message` | `MessageV2.WithParts` | 消息 Part（text/tool/reasoning/file/...），每条 part 一行，data 字段存 JSON |
| `session_message_full_content` | 完整消息内容缓存 | 用于长消息的全文检索 |
| `sync_state` | 同步游标 | 每个 session 的最新同步 seq |
| `TodoEntity` | `Todo.Info` | TODO 项 |

**事件 Replay 机制：**

客户端实现与 opencode 相同的 Projector 逻辑，接收 Sync 事件后在本地 replay：
1. 收到 Sync 事件 → 写入 EventEntity + 更新 EventSequenceEntity
2. 执行 Projector → 更新物化表（SessionEntity、MessageEntity 等）
3. 查询时直接读取物化表，无需从事件重建

> **注意**：客户端的 Projector 逻辑需与 opencode 保持一致。opencode 升级引入新事件类型时，客户端需同步更新。

**跨实例查询：**

底部 Tab "会话" 需要展示所有实例的最近会话时，遍历所有本地实例数据库取最近 N 条，按时间排序合并。实例数量通常 1-3 台，性能无问题。

---

## 6. 事件订阅机制

### 6.1 Bridge Agent 订阅

Bridge Agent 连接 opencode 的 `GET /global/event` SSE 流，接收所有事件。

**核心事件类型（Bus 事件，客户端使用这些）：**

| 事件类型 | 数据 | 用途 |
|----------|------|------|
| `session.created` | Session 完整对象 | 新会话创建 |
| `session.updated` | Session 完整对象 | 会话更新（标题、状态等） |
| `session.deleted` | `{ sessionID }` | 会话删除 |
| `session.status` | `{ sessionID, status }` | 会话状态（idle/busy） |
| `session.diff` | `{ sessionID, diff }` | 文件变更 |
| `session.error` | `{ sessionID, error }` | 会话错误 |
| `message.updated` | Message 完整对象 | 消息创建/更新 |
| `message.removed` | `{ sessionID, messageID }` | 消息删除 |
| `message.part.updated` | Part 完整对象 | Part 创建/更新 |
| `message.part.delta` | `{ sessionID, messageID, partID, field, delta }` | 流式文本增量 |
| `message.part.removed` | `{ sessionID, messageID, partID }` | Part 删除 |
| `permission.asked` | Permission 对象 | 权限请求 |
| `permission.replied` | `{ id }` | 权限已回复 |
| `question.asked` | Question 对象 | 问题请求 |
| `question.replied` | `{ id }` | 问题已回复 |
| `question.rejected` | `{ id }` | 问题已拒绝 |
| `todo.updated` | Todo 列表 | TODO 更新 |
| `workspace.status` | Workspace 状态 | 工作区连接状态 |
| `vcs.branch.updated` | VCS 信息 | 分支变更 |
| `file.edited` | 文件信息 | 文件编辑 |
| `lsp.updated` | LSP 状态 | LSP 更新 |
| `mcp.updated` | MCP 状态 | MCP 更新 |
| `server.connected` | `{}` | SSE 连接成功 |
| `server.heartbeat` | `{}` | 心跳 |

**客户端同时使用两种事件**：
- **Bus 事件**：驱动 UI 更新（消息、状态变更等）
- **Sync 事件**：用于本地事件溯源 replay，保证离线数据一致性

### 6.2 事件转发流程

```
opencode SSE 事件到达 Bridge Agent
  → 解析 GlobalEvent.payload
  → 如果 payload.type === "sync":
      提取 syncEvent { type, id, seq, aggregateID, data }
      发送到 Cloud Relay (type: "event", seq: syncEvent.seq, payload: syncEvent)
  → 如果 payload.type !== "sync":
      发送到 Cloud Relay (type: "event", payload: { type, properties })
  
Cloud Relay 收到 event
  → 转发给所有订阅该 instanceId 的客户端（纯透传，不持久化）
```

### 6.3 客户端重连补发

客户端断线重连后，增量同步通过 Bridge Agent 代理 opencode 的 Sync API：

```
1. 客户端重连 WebSocket，发送 request:
   { action: "sync.history", aggregates: { "session-xxx": 42, "session-yyy": 15 } }

2. Bridge Agent 调用 opencode POST /sync/history:
   传入客户端最后已知的各 aggregate seq，获取增量事件

3. Bridge Agent 将增量事件逐条转发给客户端 (type: "sync")

4. 客户端在本地 replay 增量事件，更新物化视图

5. 补发完成后，切换到实时 event 推送模式
```

**与 opencode 自身同步机制的关系**：这套补发逻辑与 opencode 的 `control-plane/workspace.ts` 跨设备同步模式完全一致——都是通过 SSE 实时接收 + `POST /sync/history` 补齐断线期间的事件。

---

## 7. 认证流程

### 7.1 GitHub OAuth

```
Android 客户端:
   1. 打开 Custom Tab → GET /api/auth/github → 重定向到 GitHub 授权页
   2. 用户授权 → GitHub 回调 → GET /api/auth/github/callback?code=xxx
   3. Cloud Relay 用 code 换取 GitHub access_token
   4. 获取 GitHub 用户信息（id, email, name, avatar）
   5. 签发 JWT token
   6. 重定向到 Deep Link: openmate://auth/callback?token=xxx&refresh=xxx
   7. 客户端通过 App Links 捕获 Deep Link，存储 JWT
   8. 后续 WebSocket 连接时携带 JWT

Bridge Agent:
  1. 首次启动时通过浏览器完成同样的 OAuth 流程
  2. 获取 JWT token，存储在本地配置
  3. WebSocket 连接时携带 JWT
  4. Cloud Relay 通过 JWT 将实例绑定到用户
```

### 7.2 JWT 结构

```json
{
  "sub": "user-xxx",
  "role": "client|bridge",
  "instanceId": "公司开发机",     // 仅 bridge 角色
  "iat": 1700000000,
  "exp": 1700086400
}
```

---

## 8. 错误处理

### 8.1 连接断开

| 场景 | Bridge Agent 行为 | 客户端行为 |
|------|-------------------|-----------|
| opencode 崩溃 | 检测进程退出，发送 state: opencode=stopped，尝试自动重启 | 显示"opencode 已停止"提示，可手动重启 |
| Bridge 与 Cloud 断开 | 指数退避重连（最长 2 分钟） | 显示"实例离线"，等待重连 |
| 客户端与 Cloud 断开 | 无感知 | 指数退避重连，重连后通过 Bridge→opencode sync/history 补发 |
| Cloud Relay 宕机 | Bridge 指数退避重连 | 客户端指数退避重连，离线期间使用本地缓存 |

### 8.2 请求超时

- 客户端请求带 `id` 字段，Bridge Agent 响应时回传相同 `id`
- 请求超时 30 秒，超时返回 error
- 长操作（如 session.prompt）不等待完成，通过 SSE 事件异步获取结果

### 8.3 大数据传输

与 opencode web 客户端保持一致，不做消息分片，采用全量传输 + 客户端管理策略：

| 数据类型 | 传输方式 | 客户端管理 |
|----------|----------|------------|
| SSE 事件 | 单条 JSON 全量推送 | 16ms 合并窗口，避免过度渲染 |
| 文件内容 | 单次 JSON 返回 | LRU 淘汰：40 条目，20MB 总量 |
| 会话消息 | cursor 分页加载 | 初始 80 条，历史 200 条/页 |
| Diff/快照 | 单次 JSON 全量返回 | 按需加载，不主动缓存 |

对 Android 客户端的额外考虑：
- 移动端内存有限，文件内容 LRU 缩减为 20 条目、10MB
- 大型 diff 渲染使用懒加载（只渲染可视区域）
- 图片等二进制内容 base64 传输，客户端按需解码

---

## 9. 技术栈总结

| 组件 | 语言 | 框架 | 数据库 | 关键依赖 |
|------|------|------|--------|----------|
| Cloud Relay Server | C# | .NET 10 / ASP.NET Core | MongoDB（用户/实例/推送） | WebSocket、JWT、GitHub OAuth |
| Bridge Agent | TypeScript | Node.js / Bun | - | @opencode-ai/sdk、ws |
| Android Client | Kotlin | Jetpack Compose、MVVM | SQLite（每实例独立） | OkHttp、Room |

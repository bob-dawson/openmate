# AGENTS.md — OpenMate Android

## Project Overview
OpenMate: opencode 的原生 Android 客户端，连接 `opencode serve` 实例 (LAN/Tailscale)。

- **Android root**: `D:\openmate\android`
- **opencode 源码**: `D:\github\opencode`
- **设计文档**: `D:\openmate\OpenMate设计.md`
- **同步设计**: `D:\openmate\会话同步设计.md`

## Architecture
Kotlin 2.2.0 / Jetpack Compose + Material 3 (dark theme) / MVVM + Hilt + Room + OkHttp
AGP 8.11.0 / KSP 2.2.0-2.0.2 / Compose BOM 2025.07.00 / minSdk 26 / targetSdk 36

```
app/                    → OpenMateApp, MainActivity, NavHost, ConnectionManager
core/common/            → Result<T>, Flow.asResult(), time extensions, AppDispatchers
core/domain/            → 12 domain models (Part 有 12 子类型), 7 repository 接口
core/data/              → 7 repository 实现 + EventDispatcher + 5 SSE event handlers
core/database/          → Room DB v9, 6 entities, 6 DAOs, ActiveDatabaseProvider (per-instance DB)
core/network/           → OpencodeApiClient, SseClient, SseParser, AuthInterceptor, DTOs
core/ui/                → 暗色主题 (opencode palette), MessageBubble, StreamingText, TopBar 等
feature/instance/       → 实例列表/添加 (2 ViewModel)
feature/session/        → 工作区/会话列表/聊天详情 (3 ViewModel, PartRenderer 925行)
feature/settings/       → 设置页 (1 ViewModel)
```

## Build & Run
- **IDE**: Android Studio 编译，不要跑 `assembleDebug`（除非被要求）
- **Kotlin 编译错误过滤**: `Select-String -Pattern "^e:"`（`^` 锚点很重要，否则会匹配 `core:database:preBuild` 等正常日志）

## Key Conventions
- **不写注释**，除非明确要求
- **不 commit**，除非明确要求
- **不用 mock 框架**，手写 fake/test double
- **测试断言**: Google Truth
- **错误处理**: `Log.e(TAG, ...)` + `errorMessage: StateFlow<String?>` → Snackbar
- **TODO 列表规则**: 不删除/标记完成直到用户验证；每个已完成任务后必须有 "User verification" pending 项
- **机制变更规则**: 涉及同步/数据流/状态管理等底层机制时，必须先写/更新设计文档 → 讨论 → 获批准后再写代码

## Data Flow
```
REST: OpencodeApiClient → DTOs → toDomain() → Domain → toEntity() → Room → Flow → ViewModel → UI
SSE:  SseClient → SseParser → SseData → EventDispatcher → *EventHandler → Room → Flow
```

## Key Implementation Details
- **ActiveDatabaseProvider**: 每个 ServerProfile 一个独立 SQLite (`instance_{profileId}.db`)，切换实例时切换 DB
- **ConnectionManager** (`@Singleton`): 管理 SSE 连接生命周期，connect 时设 `apiClient.baseUrl` + 切换 active DB
- **SseClient**: OkHttp 长连接，30s 心跳超时，指数退避重连 (1s→30s)
- **消息同步**: anchor-based 增量同步，详见 `会话同步设计.md`
- **Session busy 状态**: 由 DB 查询 (`role=ASSISTANT && completedAt=null`) 判断，不依赖单独 API
- **PartRenderer**: 将 12 种 Part 类型映射为 DisplayItem (TextItem/ToolItem/ReasoningItem)，tool 渲染分 Inline/Block 两种模式

## OpenCode API Quick Reference
Base URL: `http://{address}:{port}`

| Method | Path | Description |
|--------|------|-------------|
| GET | `/global/health` | Health check |
| GET | `/global/event` | SSE event stream |
| GET | `/session` | List sessions (支持 `?directory=&limit=&start=`) |
| GET | `/session/:id` | Get session |
| POST | `/session` | Create session |
| DELETE | `/session/:id` | Delete session |
| PATCH | `/session/:id` | Update session (title) |
| POST | `/session/:id/abort` | Abort session |
| GET | `/session/:id/message` | List messages + parts (支持 `?limit=&before=cursor`) |
| POST | `/session/:id/prompt_async` | Async prompt (204) |
| POST | `/session/:id/fork` | Fork session |
| GET | `/session/status` | Session statuses |
| GET | `/session/:id/todo` | Session TODO list |
| GET/POST | `/permission`, `/permission/:id/reply` | Permission flow |
| GET/POST | `/question`, `/question/:id/reply`, `/question/:id/reject` | Question flow |
| GET | `/path` | Server paths (home, state, config, worktree, directory) |

**注意事项**:
- `time.created`/`time.updated` 是 **epoch 毫秒**，不是 ISO 字符串
- `GET /session/:id/message` 返回扁平数组 `MessageV2.WithParts[]` (每项有 `info` + `parts[]`)，不是 `{messages:[...]}`
- SSE 格式: `data: {"type":"event.type","properties":{...}}`，**没有** `directory`/`payload` 包裹层
- SSE `/global/event` 路径（非 `/event`），但解析器已处理

## SSE Event Types
- `server.connected` / `server.heartbeat` / `server.instance.disposed`
- `session.created` / `session.updated` / `session.deleted` / `session.status` / `session.error` / `session.diff`
- `message.updated` / `message.removed`
- `message.part.updated` / `message.part.removed` / `message.part.delta` (流式文本增量)
- `permission.asked` / `permission.replied`
- `question.asked` / `question.replied` / `question.rejected`
- `todo.updated`

## Part Types (discriminated on `type`)
Base fields: `{id, sessionID, messageID, type}`

`text` | `reasoning` | `tool` (callID, tool, state) | `file` | `agent` | `step-start` | `step-finish` | `snapshot` | `patch` | `subtask` | `retry` | `compaction`

## Current Status
- **Phase 1** (直连 LAN opencode) 开发中，Phase 2 (Cloud Relay + Bridge Agent) 设计中
- feature 模块无测试，domain 部分测试与源码不同步
- opencode server: `opencode serve --hostname 0.0.0.0 --port 4096`（必须 `--hostname 0.0.0.0`）

# OpenCode V2 API 兼容性审计与修正

> 目的：一次性梳理 App 调用的全部 opencode API，对照 v2 协议（`D:\github\opencode\packages\protocol\src\groups`）修正不兼容处，避免反复处理。
> 结论依据：v2 协议源码 + 生成客户端 `packages/client/src/promise/generated/client.ts`。

## 位置选择约定（确认无误）

- v2 统一用 query `location[directory]=<dir>`（`LocationQuery`），客户端亦支持 `x-opencode-directory` 头。
- App 现有 `location[directory]` 用法正确，保留。

## 会话相关

| App 调用 | v2 正确形式 | 状态 |
|---|---|---|
| GET `/api/session` | GET `/api/session` | ✅ |
| GET `/api/session/{id}` | GET `/api/session/:sessionID` | ✅ |
| POST `/api/session`（仅 query 传目录） | POST `/api/session`，body 带 `location:{directory}` | ⚠️ 改为 body 传 location |
| DELETE `/api/session/{id}` | DELETE `/api/session/:sessionID` | ✅ |
| POST `/api/session/{id}/rename` | **PATCH** `/api/session/:sessionID`，body `{title}` | ❌ 修正 |
| GET `/api/session/{id}/message` | GET `/api/session/:sessionID/message` | ✅ |
| POST `/api/session/{id}/model` | POST `/api/session/:sessionID/model` | ✅ |
| POST `/api/session/{id}/agent` | POST `/api/session/:sessionID/agent` | ✅ |
| POST `/api/session/{id}/prompt` | POST `/api/session/:sessionID/prompt` | ✅ |
| POST `/api/session/{id}/interrupt` | POST `/api/session/:sessionID/interrupt` | ✅ |
| POST `/api/session/{id}/revert/stage` | POST `/api/session/:sessionID/revert/stage` | ✅ |
| DELETE `/api/session/{id}/revert` | DELETE `/api/session/:sessionID/revert` | ✅ |
| GET `/api/session/active` | GET `/api/session/active` | ✅ |
| GET `/api/session/{id}/todo` | **v2 无此端点，且移除 `todowrite` 工具** | ❌ v2 无 TODO 数据源 |
| POST `/api/session/{id}/summarize` | POST `/api/session/:sessionID/compact`，body `{}` | ❌ 修正 |
| POST `/session/{id}/init` | **v2 无此端点** | ❌ 移除入口 |

## 权限 / 问题

| App 调用 | v2 正确形式 | 状态 |
|---|---|---|
| GET `/api/permission/request` | GET `/api/permission/request` | ✅ |
| POST `/api/permission/request/{id}/reply` | POST `/api/session/:sessionID/permission/:requestID/reply`，body `{decision:"once"\|"always"\|"reject", message?}` | ❌ 修正 |
| GET `/api/question/request` | v2 改为 form：GET `/api/session/:sessionID/form` | ❌ 修正 |
| POST `/api/question/request/{id}/reply` | POST `/api/session/:sessionID/form/:formID/reply`，body `{answer:{"q0":...}}` | ❌ 修正 |
| POST `/api/question/request/{id}/reject` | DELETE `/api/session/:sessionID/form/:formID` | ❌ 修正 |

事件层：
- v2 `permission.asked.source = {type:"tool", messageID, id}`（**callID 字段名变为 `id`**），App 需兼容。
- v2 不再发 `question.asked`，改发 ephemeral `form.created` / `form.replied` / `form.cancelled`。
- Bridge `events/filter.rs` 需转发 `form.*`（已加）。

## 其它

| App 调用 | v2 正确形式 | 状态 |
|---|---|---|
| GET `/api/health` | GET `/api/info` | ❌（当前未被调用，顺手修正） |
| GET `/api/location` | GET `/api/location` | ✅ |
| GET `/api/fs/list` | GET `/api/fs/list` | ✅ |
| GET `/api/fs/find` | GET `/api/fs/find` | ✅ |
| GET `/api/provider`,`/api/model`,`/api/agent`,`/api/skill`,`/api/mcp` | 同名 | ✅ |
| POST `/api/mcp/{name}/connect` | POST `/api/experimental/mcp/:server/connect` | ❌ 修正 |
| POST `/api/mcp/{name}/disconnect` | POST `/api/experimental/mcp/:server/disconnect` | ❌ 修正 |

## 子代理（subagent）运行中无法点击进入

- **现象**：前台运行中的 subagent 行不可点击。
- **根因**：v2 运行中的 subagent tool part 的 `state.metadata` 为空 `{}`，子会话 id 只出现在 ephemeral `session.tool.progress` 的 `metadata.sessionID`（`subagent.ts` 中 `context.progress({sessionID: child.id, status:"running"})`），完成时才写入 `metadata.sessionID`。
- **修正（App）**：`EventDispatcher` 消费 `session.tool.progress`，用 `id`(callID)+`metadata.sessionID` 记录到 `SubtaskSessionTracker`；渲染器在 `subagent` 行用该映射（`SessionMessageRenderer.subtaskSessionIds`）作为 `metadata` 缺失时的回退。
- **Bridge**：`retain_session_event` 已含 `session.tool.progress`；`truncate_event` 对 `session.tool.progress` 走默认（保留 metadata）。无需改动。

## 待用户决策

1. **TODO 列表**：v2 无 `/todo` 端点且移除 `todowrite`，无数据来源。建议隐藏 TODO 卡片与轮询；或等待 v2 提供新机制。
2. **Init Session 菜单**：v2 无对应端点，已移除该菜单项（如需保留请告知）。

## 修正实施清单（已完成）

- [x] Bridge `events/filter.rs`：转发 `form.created/replied/cancelled`（+单测）
- [x] `OpencodeApiClient`：permission reply(v2)、form list/reply/cancel、session rename(PATCH)、compact、MCP experimental、info、create(location in body)
- [x] DTO：`PermissionSourceDto.id` 回退；新增 `FormDto`；删除 `QuestionDto`
- [x] `QuestionEventHandler`：处理 `form.created/replied/cancelled`（+单测）
- [x] `EventDispatcher`：路由 `form.*`；消费 `session.tool.progress` 记录子会话
- [x] `PermissionRepository` / `QuestionRepository`：签名带 sessionID；v2 实现
- [x] `SessionDetailViewModel` / `SessionDetailScreen`：调整调用；移除 Init Session 入口
- [x] 子代理运行中导航（`SubtaskSessionTracker` + 渲染器回退）
- [x] 单测 / Bridge 测试


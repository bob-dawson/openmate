# 增量同步实现计划 — 模块级大纲

**Goal:** 基于 Bridge 直读 opencode SQLite，实现移动端按会话按需加载的增量同步。冷启动走 SessionMessage 快照，后续走 Event 增量。旧模型完全替换为新模型。

**Architecture:** Bridge 暴露 5 个端点（init/events/full/sessions + SSE），移动端打开会话时拉快照+seq锚点，后续增量同步走 event + 本地 replay。截断在 Bridge 侧（快照）和移动端（replay）执行。

**Design Doc:** `docs/superpowers/specs/2026-05-06-mobile-incremental-sync-design.md`

**Tech Stack:** Rust (Bridge), Kotlin, Room, OkHttp, kotlinx.serialization, Hilt

---

## 模块划分

### 模块 A：Bridge 同步 API（opencode-bridge）
- 新增 `GET /sync/session/{sessionID}/init?limit=30` — 快照 + seq 锚点
- 新增 `GET /sync/session/{sessionID}/events?afterSeq=N` — 增量事件
- 新增 `GET /sync/session/{sessionID}/message/{messageID}/full` — 回源
- 新增 `GET /sync/sessions` — 会话列表（含 hasEvents 标记）
- 新增 `GET /sync/events` (SSE) — 轻量变更通知（过滤 opencode SSE，只推 sessionID+seq）
- Bridge 侧截断逻辑
- 启动时自建 event 表 `(aggregate_id, seq)` 复合索引
- 旧会话降级：无 session_message 时从旧 message+part 表读取并映射

### 模块 B：移动端网络层（core:network）
- 新增 Bridge 同步 API 的 Retrofit 接口
- 新增 SyncEvent DTO、SessionMessageDto、InitResponse DTO

### 模块 C：移动端数据库层（core:database）
- 删除旧 `MessageEntity` + `PartEntity` 表
- 新增 `SyncStateEntity` 表（sessionId → lastSeq）
- 新增 `SessionMessageEntity` 表（新模型，含 JSON data 字段）
- 新增 `SessionMessageFullContentEntity` 表（回源缓存）
- DB version 升级（fallbackToDestructiveMigration，用户卸载重装）

### 模块 D：移动端域模型 + 同步引擎（core:domain + core:data）
- SessionMessage 域模型（对齐 opencode v2，7 种消息类型）
- EventReplayer：参考 TUI sync-v2.tsx，session.next.* 事件 → SessionMessage 变更
- 移动端截断逻辑（与 Bridge 侧规则一致）
- SyncOrchestrator：打开会话→init→后续增量
- 删除旧 MessageRepository/PartRepository，用新的 SessionMessageRepository 替代

### 模块 E：SSE 适配（core:data + core:network）
- Bridge SSE `/sync/events` 客户端
- 收到轻量通知后触发增量同步（调 /events 接口 + replay）

### 模块 F：UI 适配（feature:session）
- 删除旧 PartRenderer
- 新 SessionMessageRenderer：支持 7 种消息类型渲染
- 三级展示：折叠摘要 → 本地完整 data → 回源窗口

---

## 步骤依赖关系

```
A (Bridge API) ──→ B (网络层) ──→ C (数据库层) ──→ D (同步引擎) ──→ E (SSE) ──→ F (UI)
```

A 是基础（Bridge 先有 API），B/C 可并行，D 依赖 B+C，E/F 在核心功能后。

---

## 各步骤计划文档索引

| 步骤 | 文档 | 状态 |
|------|------|------|
| 1 | `step-01-bridge-sync-api.md` | 待编写 |
| 2 | `step-02-mobile-network-layer.md` | 待编写 |
| 3 | `step-03-mobile-database-layer.md` | 待编写 |
| 4 | `step-04-sync-engine.md` | 待编写 |
| 5 | `step-05-sse-adaptation.md` | 待编写 |
| 6 | `step-06-ui-adaptation.md` | 待编写 |

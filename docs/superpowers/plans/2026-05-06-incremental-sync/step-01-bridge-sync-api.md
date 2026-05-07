# Step 01: Bridge 同步 API

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development or superpowers:executing-plans to implement this plan task-by-task.

**Goal:** 在 OpenMate Bridge 中新增同步 API，直读 opencode SQLite 的 session_message 和 event 表，支持移动端按会话增量同步。

**Architecture:** Bridge 新增 `rusqlite` 依赖直读 opencode.db（WAL 模式）。暴露 5 个端点：init（快照+锚点）、events（增量）、full（回源）、sessions（列表）、sync/events（SSE 轻量通知）。启动时自建缺失的复合索引。

**Tech Stack:** Rust, axum 0.8, rusqlite, serde_json, tokio

**Design Doc:** `docs/superpowers/specs/2026-05-06-mobile-incremental-sync-design.md`

---

## File Structure

```
opencode-bridge/src/
├── sync/
│   ├── mod.rs              — 模块声明，导出 router
│   ├── db.rs               — SQLite 连接管理 + 所有查询函数
│   ├── truncate.rs         — 截断规则实现
│   ├── router.rs           — 5 个 axum route handler
│   └── sse.rs              — /sync/events SSE 端点（过滤 opencode SSE）
├── error.rs                — 新增 AppError 变体
├── config.rs               — 新增 opencode.db 路径配置
└── server.rs               — 注册新路由
```

---

## Task 1: 添加 rusqlite 依赖

**Files:**
- Modify: `opencode-bridge/Cargo.toml`

- [ ] **Step 1: 添加 rusqlite 依赖**

在 `Cargo.toml` 的 `[dependencies]` 中添加：

```toml
rusqlite = { version = "0.32", features = ["bundled"] }
```

`bundled` feature 自带 SQLite 编译，无需系统安装。WAL 模式默认支持。

- [ ] **Step 2: 验证编译**

Run: `cargo check`
Expected: 编译成功

- [ ] **Step 3: Commit**

```
git add Cargo.toml Cargo.lock
git commit -m "feat(bridge): add rusqlite dependency for opencode.db access"
```

---

## Task 1.5: 确保实验性环境变量注入

**Files:**
- Modify: `opencode-bridge/src/process/opencode_manager.rs`

- [ ] **Step 1: 在 spawn_opencode 中注入环境变量**

在 `spawn_opencode()` 函数中，`Command::new(...)` 之后、`.spawn()` 之前添加 `.env()`：

```rust
// Windows (行 233-239)
tokio::process::Command::new("cmd")
    .args(["/C", &cmd])
    .current_dir(&work_dir)
    .env("OPENCODE_EXPERIMENTAL", "true")
    .stdout(std::process::Stdio::piped())
    .stderr(std::process::Stdio::piped())
    .spawn()
    // ...

// 非 Windows (行 244-250)
tokio::process::Command::new(binary)
    .args(["serve", "--hostname", hostname, "--port", &port_str])
    .current_dir(&work_dir)
    .env("OPENCODE_EXPERIMENTAL", "true")
    .stdout(std::process::Stdio::piped())
    .stderr(std::process::Stdio::piped())
    .spawn()
    // ...
```

这确保 opencode 启动时启用 v2 event 系统和 session_message 表。

- [ ] **Step 2: 验证编译**

Run: `cargo check`
Expected: 编译成功

- [ ] **Step 3: Commit**

```
git add src/process/opencode_manager.rs
git commit -m "feat(bridge): inject OPENCODE_EXPERIMENTAL=true when spawning opencode"
```

---

## Task 2: SQLite 连接管理 + opencode.db 路径发现

**Files:**
- Create: `opencode-bridge/src/sync/mod.rs`
- Create: `opencode-bridge/src/sync/db.rs`
- Modify: `opencode-bridge/src/config.rs`
- Modify: `opencode-bridge/src/lib.rs`

- [ ] **Step 1: 在 config.rs 添加 opencode.db 路径配置**

在 `OpencodeConfig` struct 中添加字段：

```rust
#[serde(default = "default_db_path")]
pub db_path: String,
```

添加默认值函数和 helper 方法：

```rust
fn default_db_path() -> String {
    let home = dirs::home_dir().unwrap_or_default();
    let path = home.join(".local").join("share").join("opencode").join("opencode.db");
    path.to_string_lossy().to_string()
}

impl OpencodeConfig {
    pub fn db_path(&self) -> PathBuf {
        PathBuf::from(&self.db_path)
    }
}
```

在 `Cargo.toml` 中添加 `dirs` crate（如尚未添加）：

```toml
dirs = "6"
```

- [ ] **Step 2: 创建 sync/mod.rs**

```rust
pub mod db;
pub mod truncate;
pub mod router;
pub mod sse;
```

- [ ] **Step 3: 创建 sync/db.rs — SQLite 连接管理**

```rust
use rusqlite::{Connection, OpenFlags, params};
use serde_json::{Value, json};
use std::path::PathBuf;
use std::sync::Arc;
use tokio::sync::Mutex;
use crate::config::Config;

pub struct SyncDb {
    db_path: PathBuf,
}

impl SyncDb {
    pub fn new(config: &Config) -> Self {
        Self {
            db_path: config.opencode.db_path(),
        }
    }

    fn connect(&self) -> Result<Connection, String> {
        Connection::open_with_flags(
            &self.db_path,
            OpenFlags::SQLITE_OPEN_READ_ONLY | OpenFlags::SQLITE_OPEN_NO_MUTEX,
        ).map_err(|e| format!("Failed to open opencode.db: {}", e))
    }

    pub fn ensure_indexes(&self) -> Result<(), String> {
        let conn = self.connect()?;
        conn.execute_batch(
            "CREATE INDEX IF NOT EXISTS idx_event_aggregate_seq ON event(aggregate_id, seq);
             CREATE INDEX IF NOT EXISTS idx_session_message_session_time ON session_message(session_id, time_created DESC);"
        ).map_err(|e| format!("Failed to create indexes: {}", e))
    }

    pub fn get_init_snapshot(&self, session_id: &str, limit: i64) -> Result<(Vec<Value>, Option<i64>), String> {
        let conn = self.connect()?;
        let mut stmt = conn.prepare(
            "SELECT id, session_id, type, time_created, time_updated, data
             FROM session_message
             WHERE session_id = ?
             ORDER BY time_created DESC
             LIMIT ?"
        ).map_err(|e| format!("Prepare failed: {}", e))?;

        let messages: Vec<Value> = stmt.query_map(params![session_id, limit], |row| {
            let id: String = row.get(0)?;
            let session_id: String = row.get(1)?;
            let msg_type: String = row.get(2)?;
            let time_created: i64 = row.get(3)?;
            let time_updated: i64 = row.get(4)?;
            let data: String = row.get(5)?;
            Ok(json!({
                "id": id,
                "sessionId": session_id,
                "type": msg_type,
                "timeCreated": time_created,
                "timeUpdated": time_updated,
                "data": data,
            }))
        }).map_err(|e| format!("Query failed: {}", e))?
          .filter_map(|r| r.ok())
          .collect();

        let max_seq: Option<i64> = conn.query_row(
            "SELECT seq FROM event_sequence WHERE aggregate_id = ?",
            params![session_id],
            |row| row.get(0),
        ).ok();

        Ok((messages, max_seq))
    }

    pub fn get_events(&self, session_id: &str, after_seq: i64) -> Result<(Vec<Value>, Option<i64>), String> {
        let conn = self.connect()?;
        let mut stmt = conn.prepare(
            "SELECT id, aggregate_id, seq, type, data
             FROM event
             WHERE aggregate_id = ? AND seq > ?
             ORDER BY seq"
        ).map_err(|e| format!("Prepare failed: {}", e))?;

        let events: Vec<Value> = stmt.query_map(params![session_id, after_seq], |row| {
            let id: String = row.get(0)?;
            let aggregate_id: String = row.get(1)?;
            let seq: i64 = row.get(2)?;
            let event_type: String = row.get(3)?;
            let data: String = row.get(4)?;
            Ok(json!({
                "id": id,
                "aggregateId": aggregate_id,
                "seq": seq,
                "type": event_type,
                "data": data,
            }))
        }).map_err(|e| format!("Query failed: {}", e))?
          .filter_map(|r| r.ok())
          .collect();

        let max_seq: Option<i64> = events.last()
            .map(|e| e["seq"].as_i64().unwrap_or(after_seq))
            .or(Some(after_seq));

        let actual_max: Option<i64> = conn.query_row(
            "SELECT seq FROM event_sequence WHERE aggregate_id = ?",
            params![session_id],
            |row| row.get(0),
        ).ok();

        Ok((events, actual_max.or(max_seq)))
    }

    pub fn get_full_message(&self, message_id: &str) -> Result<Option<Value>, String> {
        let conn = self.connect()?;
        let result = conn.query_row(
            "SELECT id, type, data FROM session_message WHERE id = ?",
            params![message_id],
            |row| {
                let id: String = row.get(0)?;
                let msg_type: String = row.get(1)?;
                let data: String = row.get(2)?;
                Ok(json!({
                    "id": id,
                    "type": msg_type,
                    "data": data,
                }))
            },
        );
        match result {
            Ok(val) => Ok(Some(val)),
            Err(rusqlite::Error::QueryReturnedNoRows) => Ok(None),
            Err(e) => Err(format!("Query failed: {}", e)),
        }
    }

    pub fn get_sessions(&self) -> Result<Vec<Value>, String> {
        let conn = self.connect()?;
        let mut stmt = conn.prepare(
            "SELECT s.id, s.title, s.agent, s.model, s.time_created, s.time_updated,
                    CASE WHEN es.seq IS NOT NULL THEN 1 ELSE 0 END as hasEvents,
                    es.seq as maxSeq
             FROM session s
             LEFT JOIN event_sequence es ON es.aggregate_id = s.id
             ORDER BY s.time_updated DESC"
        ).map_err(|e| format!("Prepare failed: {}", e))?;

        let sessions: Vec<Value> = stmt.query_map([], |row| {
            let id: String = row.get(0)?;
            let title: String = row.get(1)?;
            let agent: Option<String> = row.get(2)?;
            let model_json: Option<String> = row.get(3)?;
            let time_created: i64 = row.get(4)?;
            let time_updated: i64 = row.get(5)?;
            let has_events: bool = row.get(6)?;
            let max_seq: Option<i64> = row.get(7)?;
            let model: Option<Value> = model_json
                .and_then(|s| serde_json::from_str(&s).ok());
            Ok(json!({
                "id": id,
                "title": title,
                "agent": agent,
                "model": model,
                "timeCreated": time_created,
                "timeUpdated": time_updated,
                "hasEvents": has_events,
                "maxSeq": max_seq,
            }))
        }).map_err(|e| format!("Query failed: {}", e))?
          .filter_map(|r| r.ok())
          .collect();

        Ok(sessions)
    }

    pub fn has_session_messages(&self, session_id: &str) -> Result<bool, String> {
        let conn = self.connect()?;
        let count: i64 = conn.query_row(
            "SELECT count(*) FROM session_message WHERE session_id = ?",
            params![session_id],
            |row| row.get(0),
        ).map_err(|e| format!("Query failed: {}", e))?;
        Ok(count > 0)
    }
}
```

- [ ] **Step 4: 在 lib.rs 注册 sync 模块**

在 `lib.rs` 的 `mod` 声明中添加：

```rust
pub mod sync;
```

- [ ] **Step 5: 验证编译**

Run: `cargo check`
Expected: 编译成功

- [ ] **Step 6: Commit**

```
git add src/sync/mod.rs src/sync/db.rs src/lib.rs src/config.rs Cargo.toml Cargo.lock
git commit -m "feat(bridge): add SQLite access layer for opencode.db"
```

---

## Task 3: 截断规则实现

**Files:**
- Create: `opencode-bridge/src/sync/truncate.rs`

- [ ] **Step 1: 创建 truncate.rs**

实现截断规则，参照 `docs/superpowers/specs/2026-05-06-mobile-sync-truncation-rules.md`。

截断输入：一个 `Value`（session_message 的 data JSON）+ `type`（消息类型）。
截断输出：截断后的 `Value`。

核心函数签名：

```rust
pub fn truncate_message(msg_type: &str, data: &Value) -> Value
```

按 msg_type 分发：

- `user` → 跳过 `files[].source.text`、`files[].description`、`agents[].source`
- `assistant` → 遍历 `content[]`：
  - `type=text` → 不截断
  - `type=reasoning` → `text` 保留前后各 100 字符
  - `type=tool` → 调用 `truncate_tool()` 按 tool name 截断 input/output
  - 跳过 `snapshot`、`metadata`、`diagnostics`
- `compaction` → `summary` 保留前后各 10 行，跳过 `include`
- `shell` → `output` 前 5 行+后 5 行
- `agent-switched` / `model-switched` / `synthetic` → 不截断

`truncate_tool(name, tool_state)` 按 `2026-05-06-mobile-sync-truncation-rules.md` 的工具规则表处理。对 `tool.state.input`（对象）按工具类型保留指定字段跳过其他。对 `tool.state.content`（数组）中的 `type=text` 项，按工具类型决定保留或跳过。

- [ ] **Step 2: 验证编译**

Run: `cargo check`
Expected: 编译成功

- [ ] **Step 3: Commit**

```
git add src/sync/truncate.rs
git commit -m "feat(bridge): implement message truncation rules"
```

---

## Task 4: Route handlers

**Files:**
- Create: `opencode-bridge/src/sync/router.rs`
- Modify: `opencode-bridge/src/error.rs`
- Modify: `opencode-bridge/src/state.rs`
- Modify: `opencode-bridge/src/server.rs`

- [ ] **Step 1: 在 error.rs 添加新错误变体**

```rust
#[error("Database error: {0}")]
DatabaseError(String),

#[error("Session not found: {0}")]
SessionNotFound(String),

#[error("Message not found: {0}")]
MessageNotFound(String),
```

在 `into_response` 中添加对应 match arm：

```rust
AppError::DatabaseError(_) => (StatusCode::INTERNAL_SERVER_ERROR, message),
AppError::SessionNotFound(_) => (StatusCode::NOT_FOUND, message),
AppError::MessageNotFound(_) => (StatusCode::NOT_FOUND, message),
```

- [ ] **Step 2: 在 state.rs 添加 SyncDb 到 AppState**

在 `AppStateInner` 中添加字段：

```rust
pub sync_db: sync::db::SyncDb,
```

在 `AppState::new()` 中初始化：

```rust
sync_db: sync::db::SyncDb::new(&config),
```

在 Bridge 启动时调用 `ensure_indexes()`：

```rust
if let Err(e) = state.sync_db.ensure_indexes() {
    tracing::warn!("Failed to create sync indexes: {}", e);
}
```

- [ ] **Step 3: 创建 sync/router.rs**

```rust
use axum::{
    extract::{Path, Query, State},
    response::IntoResponse,
    Json,
};
use serde::Deserialize;
use serde_json::json;
use crate::error::AppError;
use crate::state::AppState;

#[derive(Deserialize)]
pub struct InitQuery {
    pub limit: Option<i64>,
}

pub async fn init(
    State(state): State<AppState>,
    Path(session_id): Path<String>,
    Query(query): Query<InitQuery>,
) -> Result<impl IntoResponse, AppError> {
    let limit = query.limit.unwrap_or(30);
    let (messages, max_seq) = state.sync_db.get_init_snapshot(&session_id, limit)
        .map_err(|e| AppError::DatabaseError(e))?;

    let truncated: Vec<serde_json::Value> = messages.into_iter().map(|mut msg| {
        if let Some(data_str) = msg["data"].as_str() {
            if let Ok(data_val) = serde_json::from_str::<serde_json::Value>(data_str) {
                let msg_type = msg["type"].as_str().unwrap_or("");
                let truncated_data = super::truncate::truncate_message(msg_type, &data_val);
                msg["data"] = truncated_data;
            }
        }
        msg
    }).collect();

    Ok(Json(json!({
        "messages": truncated,
        "maxSeq": max_seq,
    })))
}

pub async fn events(
    State(state): State<AppState>,
    Path(session_id): Path<String>,
    Query(query): Query<EventsQuery>,
) -> Result<impl IntoResponse, AppError> {
    let after_seq = query.afterSeq.unwrap_or(0);
    let (events, max_seq) = state.sync_db.get_events(&session_id, after_seq)
        .map_err(|e| AppError::DatabaseError(e))?;

    Ok(Json(json!({
        "events": events,
        "maxSeq": max_seq,
    })))
}

#[derive(Deserialize)]
pub struct EventsQuery {
    pub afterSeq: Option<i64>,
}

pub async fn full(
    State(state): State<AppState>,
    Path((session_id, message_id)): Path<(String, String)>,
) -> Result<impl IntoResponse, AppError> {
    let message = state.sync_db.get_full_message(&message_id)
        .map_err(|e| AppError::DatabaseError(e))?
        .ok_or_else(|| AppError::MessageNotFound(message_id))?;

    Ok(Json(message))
}

pub async fn sessions(
    State(state): State<AppState>,
) -> Result<impl IntoResponse, AppError> {
    let sessions = state.sync_db.get_sessions()
        .map_err(|e| AppError::DatabaseError(e))?;

    Ok(Json(json!({ "sessions": sessions })))
}
```

- [ ] **Step 4: 在 server.rs 注册路由**

在 Router 构建中添加（在 `// Bridge management routes` 之前）：

```rust
// Sync API routes
.route("/api/bridge/sync/sessions", get(sync::router::sessions))
.route("/api/bridge/sync/session/{sessionID}/init", get(sync::router::init))
.route("/api/bridge/sync/session/{sessionID}/events", get(sync::router::events))
.route("/api/bridge/sync/session/{sessionID}/message/{messageID}/full", get(sync::router::full))
.route("/api/bridge/sync/events", get(sync::sse::sync_sse))
```

- [ ] **Step 5: 验证编译**

Run: `cargo check`
Expected: 编译成功

- [ ] **Step 6: Commit**

```
git add src/sync/router.rs src/error.rs src/state.rs src/server.rs
git commit -m "feat(bridge): add sync API route handlers"
```

---

## Task 5: SSE /sync/events 端点

**Files:**
- Create: `opencode-bridge/src/sync/sse.rs`

- [ ] **Step 1: 创建 sync/sse.rs**

这个端点复用现有 `proxy::sse` 的 opencode SSE 连接，但过滤后只转发轻量通知。

两种实现策略（选简单的一种）：

**策略 A（推荐）：独立 SSE 连接**

```rust
use axum::response::sse::{Event, Sse};
use futures::stream::BoxStream;
use std::convert::Infallible;
use tokio::sync::mpsc;
use tokio_stream::wrappers::ReceiverStream;
use crate::state::AppState;

pub async fn sync_sse(
    State(state): State<AppState>,
) -> Sse<impl tokio::stream::Stream<Item = Result<Event, Infallible>>> {
    let opencode_url = state.config.opencode_url();
    let (tx, rx) = mpsc::channel(32);

    tokio::spawn(async move {
        let sse_url = format!("{}/global/event", opencode_url);
        let client = reqwest::Client::new();
        loop {
            match client.get(&sse_url).send().await {
                Ok(resp) if resp.status().is_success() => {
                    let mut stream = resp.bytes_stream();
                    let mut buffer = String::new();
                    use futures::StreamExt;
                    while let Some(chunk) = stream.next().await {
                        let chunk = match chunk { Ok(c) => c, Err(_) => break };
                        buffer.push_str(&String::from_utf8_lossy(&chunk));
                        while let Some(pos) = buffer.find('\n') {
                            let line = buffer[..pos].to_string();
                            buffer = buffer[pos + 1..].to_string();
                            let trimmed = line.trim();
                            if let Some(data) = trimmed.strip_prefix("data:") {
                                let data = data.trim();
                                if let Ok(json) = serde_json::from_str::<serde_json::Value>(data) {
                                    if json["type"] == "sync" {
                                        if let Some(sync_event) = json.get("syncEvent") {
                                            let session_id = sync_event["aggregateID"].as_str().unwrap_or("");
                                            let seq = sync_event["seq"].as_i64().unwrap_or(0);
                                            if !session_id.is_empty() {
                                                let notification = serde_json::json!({
                                                    "sessionID": session_id,
                                                    "seq": seq,
                                                });
                                                let event = Event::default().data(notification.to_string());
                                                if tx.send(Ok(event)).await.is_err() { return; }
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
                _ => {}
            }
            tokio::time::sleep(std::time::Duration::from_secs(3)).await;
            if tx.is_closed() { break; }
        }
    });

    Sse::new(ReceiverStream::new(rx)).keep_alive(axum::response::sse::KeepAlive::default())
}
```

- [ ] **Step 2: 验证编译**

Run: `cargo check`
Expected: 编译成功

- [ ] **Step 3: Commit**

```
git add src/sync/sse.rs
git commit -m "feat(bridge): add /sync/events SSE endpoint for mobile notifications"
```

---

## Task 6: 端到端测试

- [ ] **Step 1: 启动 Bridge 并测试 init 端点**

Run: `cargo run` (或启动 Bridge 服务)

测试 init：
```powershell
Invoke-RestMethod -Uri "http://127.0.0.1:4097/api/bridge/sync/session/ses_2044572abffe61a85D22RxnA2F/init?limit=5"
```

Expected: 返回 `{ messages: [...], maxSeq: 1368 }`

- [ ] **Step 2: 测试 events 端点**

```powershell
Invoke-RestMethod -Uri "http://127.0.0.1:4097/api/bridge/sync/session/ses_2044572abffe61a85D22RxnA2F/events?afterSeq=1360"
```

Expected: 返回 seq > 1360 的增量事件

- [ ] **Step 3: 测试 full 端点**

```powershell
Invoke-RestMethod -Uri "http://127.0.0.1:4097/api/bridge/sync/session/ses_2044572abffe61a85D22RxnA2F/message/evt_e005999b2001eDwd9K7I560CdJ/full"
```

Expected: 返回完整的未截断消息

- [ ] **Step 4: 测试 sessions 端点**

```powershell
Invoke-RestMethod -Uri "http://127.0.0.1:4097/api/bridge/sync/sessions" | ConvertTo-Json -Depth 2 | Select-Object -First 20
```

Expected: 返回会话列表，含 hasEvents 和 maxSeq

- [ ] **Step 5: 验证索引已创建**

```powershell
sqlite3 "C:\Users\bob_d\.local\share\opencode\opencode.db" ".indices event"
sqlite3 "C:\Users\bob_d\.local\share\opencode\opencode.db" ".indices session_message"
```

Expected: 两个新索引出现

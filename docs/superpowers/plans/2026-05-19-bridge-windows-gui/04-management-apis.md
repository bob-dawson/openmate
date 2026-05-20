# 步骤 04: Management APIs — 管理 API 端点

> 依赖: 步骤 01, 02, 03
> 产出: 新建 `src/api/` 目录，修改 `server.rs`、`middleware.rs`

## 背景

Web UI 需要一组后端 API 来获取数据。所有管理 API 仅限 localhost 访问（不需要 Bearer token）。

## 文件结构

```
src/api/
├── mod.rs          # 模块入口 + 路由组装
├── devices.rs      # 设备管理 CRUD
├── logs.rs         # 日志查询 + SSE
├── network.rs      # 网卡枚举
├── qrcode.rs       # QR 码生成
├── autostart.rs    # 开机自启
└── open_ui.rs      # 打开浏览器 + APK 下载
```

## 实现步骤

### Step 1: 创建 `src/api/mod.rs`

```rust
pub mod devices;
pub mod logs;
pub mod network;
pub mod qrcode;
pub mod autostart;
pub mod open_ui;

use axum::Router;
use crate::state::AppState;

pub fn management_routes() -> Router<AppState> {
    Router::new()
        .merge(devices::routes())
        .merge(logs::routes())
        .merge(network::routes())
        .merge(qrcode::routes())
        .merge(autostart::routes())
        .merge(open_ui::routes())
}
```

### Step 2: `src/api/devices.rs` — 设备管理

```rust
use axum::extract::{Path, State};
use axum::Json;
use serde::Deserialize;
use crate::error::AppError;
use crate::state::AppState;

pub fn routes() -> Router<AppState> {
    Router::new()
        .route("/api/bridge/devices", axum::routing::get(list_devices))
        .route("/api/bridge/devices/{device_id}/name", axum::routing::put(rename_device))
        .route("/api/bridge/devices/{device_id}", axum::routing::delete(delete_device))
}

async fn list_devices(
    State(state): State<AppState>,
) -> Result<Json<Vec<crate::bridge_db::PairedDevice>>, AppError> {
    let devices = state.bridge_db.list_devices()
        .map_err(|e| AppError::InternalServerError(e))?;
    Ok(Json(devices))
}

#[derive(Deserialize)]
struct RenameRequest { name: String }

async fn rename_device(
    State(state): State<AppState>,
    Path(device_id): Path<String>,
    Json(body): Json<RenameRequest>,
) -> Result<Json<serde_json::Value>, AppError> {
    state.bridge_db.rename_device(&device_id, &body.name)
        .map_err(|e| AppError::InternalServerError(e))?;
    Ok(Json(serde_json::json!({"ok": true})))
}

async fn delete_device(
    State(state): State<AppState>,
    Path(device_id): Path<String>,
) -> Result<Json<serde_json::Value>, AppError> {
    state.bridge_db.delete_device(&device_id)
        .map_err(|e| AppError::InternalServerError(e))?;
    Ok(Json(serde_json::json!({"ok": true})))
}
```

### Step 3: `src/api/logs.rs` — 日志查询 + SSE

```rust
use axum::extract::{Query, State};
use axum::response::sse::{Event, Sse};
use axum::Json;
use futures::stream::{self, Stream};
use serde::Deserialize;
use std::convert::Infallible;
use tokio_stream::StreamExt;
use crate::error::AppError;
use crate::log_capture::LogEntry;
use crate::state::AppState;

pub fn routes() -> Router<AppState> {
    Router::new()
        .route("/api/bridge/logs", axum::routing::get(query_logs))
        .route("/api/bridge/logs/stream", axum::routing::get(stream_logs))
}

#[derive(Deserialize)]
struct LogQuery {
    level: Option<String>,
    search: Option<String>,
    offset: Option<usize>,
    limit: Option<usize>,
}

async fn query_logs(
    State(state): State<AppState>,
    Query(q): Query<LogQuery>,
) -> Result<Json<Vec<LogEntry>>, AppError> {
    let buf = state.log_buffer.lock().map_err(|e| AppError::InternalServerError(e.to_string()))?;
    let entries = buf.query(
        q.level.as_deref(),
        q.search.as_deref(),
        q.offset.unwrap_or(0),
        q.limit.unwrap_or(200),
    );
    Ok(Json(entries))
}

async fn stream_logs(
    State(state): State<AppState>,
) -> Sse<impl Stream<Item = Result<Event, Infallible>>> {
    let buffer = state.log_buffer.clone();
    let stream = tokio_stream::wrappers::IntervalStream::new(
        tokio::time::interval(std::time::Duration::from_secs(1))
    ).filter_map(move |_| {
        let buf = buffer.lock().ok()?;
        if let Some(last) = buf.entries().back() {
            let data = serde_json::to_string(last).ok()?;
            Some(Ok(Event::default().data(data)))
        } else {
            None
        }
    });

    Sse::new(stream).keep_alive(
        axum::response::sse::KeepAlive::new()
            .interval(std::time::Duration::from_secs(15))
    )
}
```

> 注：SSE stream 的实现可以优化为只在有新日志时推送（用 tokio::sync::watch），但先用轮询简化实现。

### Step 4: `src/api/network.rs` — 网卡枚举

```rust
use axum::extract::State;
use axum::Json;
use serde::Serialize;
use crate::error::AppError;
use crate::state::AppState;

#[derive(Serialize)]
struct NetworkInterface {
    name: String,
    ip: String,
}

pub fn routes() -> Router<AppState> {
    Router::new()
        .route("/api/bridge/network/interfaces", axum::routing::get(list_interfaces))
}

async fn list_interfaces(
    State(state): State<AppState>,
) -> Result<Json<Vec<NetworkInterface>>, AppError> {
    let interfaces = get_local_interfaces();
    Ok(Json(interfaces))
}

fn get_local_interfaces() -> Vec<NetworkInterface> {
    // 使用 std::net 或 windows API 枚举网卡
    // 简化实现: 用 local_ip_addrs 或手动枚举
    // 实际实现可用 nix crate (linux) 或 windows crate (windows)
    #[cfg(windows)]
    {
        use std::process::Command;
        let output = Command::new("ipconfig").output().ok();
        // 解析 ipconfig 输出获取 IPv4 地址
        // 或使用 windows::Win32::NetworkManagement::IpHelper
        parse_ipconfig_output(output)
    }
    #[cfg(not(windows))]
    {
        vec![]
    }
}

#[cfg(windows)]
fn parse_ipconfig_output(output: Option<std::process::Output>) -> Vec<NetworkInterface> {
    let output = match output {
        Some(o) => o,
        None => return vec![],
    };
    let text = String::from_utf8_lossy(&output.stdout);
    let mut interfaces = Vec::new();
    let mut current_name = String::new();
    for line in text.lines() {
        let trimmed = line.trim();
        if !trimmed.starts_with(" ") && trimmed.ends_with(':') && !trimmed.contains("IPv4") {
            current_name = trimmed.trim_end_matches(':').to_string();
        }
        if trimmed.starts_with("IPv4 Address") {
            if let Some(ip_str) = trimmed.split(':').nth(1) {
                let ip = ip_str.trim().trim_end_matches("(Preferred)").trim();
                if !ip.is_empty() && !ip.starts_with("127.") {
                    interfaces.push(NetworkInterface {
                        name: current_name.clone(),
                        ip: ip.to_string(),
                    });
                }
            }
        }
    }
    interfaces
}
```

### Step 5: `src/api/qrcode.rs` — QR 码生成

需要先在 `Cargo.toml` 添加 `qrcode` 依赖。

```rust
use axum::extract::{Query, State};
use axum::response::{Html, IntoResponse};
use serde::Deserialize;
use crate::error::AppError;
use crate::state::AppState;

pub fn routes() -> Router<AppState> {
    Router::new()
        .route("/api/bridge/qrcode", axum::routing::get(generate_qrcode))
}

#[derive(Deserialize)]
struct QrQuery {
    ip: String,
}

async fn generate_qrcode(
    State(state): State<AppState>,
    Query(q): Query<QrQuery>,
) -> Result<impl IntoResponse, AppError> {
    let port = state.config.bridge.port;
    let url = format!("http://{}:{}/ui/download", q.ip, port);

    let code = qrcode::QrCode::new(url)
        .map_err(|e| AppError::InternalServerError(format!("QR generation failed: {}", e)))?;

    let svg = code.render::<qrcode::render::svg::Color>()
        .min_dimensions(200, 200)
        .build();

    Ok(Html(svg))
}
```

### Step 6: `src/api/autostart.rs` — 开机自启

```rust
use axum::extract::State;
use axum::Json;
use serde::{Deserialize, Serialize};
use crate::error::AppError;
use crate::state::AppState;

pub fn routes() -> Router<AppState> {
    Router::new()
        .route("/api/bridge/autostart", axum::routing::get(get_autostart))
        .route("/api/bridge/autostart", axum::routing::post(set_autostart))
}

#[derive(Serialize)]
struct AutostartStatus { enabled: bool }

#[derive(Deserialize)]
struct AutostartRequest { enabled: bool }

fn startup_shortcut_path() -> std::path::PathBuf {
    let appdata = std::env::var("APPDATA").unwrap_or_default();
    std::path::PathBuf::from(appdata)
        .join("Microsoft")
        .join("Windows")
        .join("Start Menu")
        .join("Programs")
        .join("Startup")
        .join("OpenMate Bridge.lnk")
}

async fn get_autostart() -> Result<Json<AutostartStatus>, AppError> {
    let enabled = startup_shortcut_path().exists();
    Ok(Json(AutostartStatus { enabled }))
}

async fn set_autostart(
    Json(body): Json<AutostartRequest>,
) -> Result<Json<serde_json::Value>, AppError> {
    let shortcut_path = startup_shortcut_path();
    if body.enabled {
        create_startup_shortcut(&shortcut_path)?;
    } else {
        let _ = std::fs::remove_file(&shortcut_path);
    }
    Ok(Json(serde_json::json!({"ok": true})))
}

fn create_startup_shortcut(path: &std::path::Path) -> Result<(), AppError> {
    // 使用 PowerShell 创建 .lnk 快捷方式
    let exe_path = std::env::current_exe()
        .map_err(|e| AppError::InternalServerError(format!("Cannot get exe path: {}", e)))?;
    let ps_script = format!(
        "$ws = New-Object -ComObject WScript.Shell; $sc = $ws.CreateShortcut('{}'); $sc.TargetPath = '{}'; $sc.Arguments = '--tray'; $sc.Save()",
        path.display(),
        exe_path.display(),
    );
    std::process::Command::new("powershell")
        .args(["-NoProfile", "-Command", &ps_script])
        .output()
        .map_err(|e| AppError::InternalServerError(format!("Failed to create shortcut: {}", e)))?;
    Ok(())
}
```

### Step 7: `src/api/open_ui.rs` — 打开浏览器 + APK 下载

```rust
use axum::extract::State;
use axum::http::header;
use axum::response::{IntoResponse, Redirect};
use crate::error::AppError;
use crate::state::AppState;

pub fn routes() -> Router<AppState> {
    Router::new()
        .route("/api/bridge/open-ui", axum::routing::post(open_ui))
        .route("/download/openmate.apk", axum::routing::get(download_apk))
}

async fn open_ui(
    State(state): State<AppState>,
) -> Result<Json<serde_json::Value>, AppError> {
    let port = state.config.bridge.port;
    let url = format!("http://localhost:{}/ui/", port);
    open::that(&url)
        .map_err(|e| AppError::InternalServerError(format!("Failed to open browser: {}", e)))?;
    Ok(Json(serde_json::json!({"ok": true})))
}

async fn download_apk() -> Result<impl IntoResponse, AppError> {
    let exe_dir = std::env::current_exe()
        .map_err(|e| AppError::InternalServerError(e.to_string()))?
        .parent()
        .ok_or_else(|| AppError::InternalServerError("No parent dir".into()))?
        .to_path_buf();

    let apk_path = exe_dir.join("apk").join("openmate.apk");
    if !apk_path.exists() {
        return Err(AppError::BadRequest("APK not found".to_string()));
    }

    let data = std::fs::read(&apk_path)
        .map_err(|e| AppError::InternalServerError(format!("Read APK failed: {}", e)))?;

    Ok((
        [
            (header::CONTENT_TYPE, "application/vnd.android.package-archive".to_string()),
            (header::CONTENT_DISPOSITION, "attachment; filename=\"openmate.apk\"".to_string()),
        ],
        data,
    ))
}
```

### Step 8: 修改 `src/server.rs` — 注册新路由

在 Router 构建中添加：

```rust
.use(axum::Router::new())
// ... 现有路由 ...
.merge(crate::api::management_routes())
```

### Step 9: 修改 `src/auth/middleware.rs` — 更新路径白名单

更新 `LOCALHOST_ONLY_PATHS` 和 `PUBLIC_PATHS`（已在步骤 03 中完成，此处确认包含所有新路径）。

### Step 10: 修改 `src/lib.rs`

```rust
pub mod api;  // 新增
```

### Step 11: 更新 `Cargo.toml`

```toml
[dependencies]
qrcode = "0.14"
open = "5"
```

### Step 12: 集成测试

为每个新 API 添加集成测试（参照 `tests/integration.rs` 现有模式）。

### Step 13: 验证

```powershell
cargo test
cargo test --test integration
```

### Step 14: 提交

```
feat(bridge): add management APIs for devices, logs, network, QR, autostart
```

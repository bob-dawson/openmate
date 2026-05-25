# Linux 兼容性 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 让 Bridge 在 Linux 上完整编译运行，支持有桌面和无桌面场景，对齐 Windows 管理体验。

**Architecture:** 按优先级分 5 个任务：编译修复 → 核心功能（单实例/Gateway keepalive）→ autostart 重构 → 无桌面配对（终端 QR）→ 桌面体验（托盘/管理页远程访问）。

**Tech Stack:** Rust, ksni (Linux tray), qrcode (Unicode renderer), axum, Android Kotlin

---

## File Structure

### Bridge (Rust) — 新建文件
- `src/api/autostart/mod.rs` — 公共 trait + cfg 重导出
- `src/api/autostart/windows.rs` — Windows 实现（从现有 autostart.rs 迁移）
- `src/api/autostart/linux.rs` — Linux 实现 (systemd system service)
- `src/terminal_qr.rs` — 终端 ASCII 二维码输出模块

### Bridge (Rust) — 修改文件
- `Cargo.toml` — 增加 ksni 依赖
- `src/api/mod.rs` — 更新 mod 声明
- `src/api/autostart.rs` — 删除（拆分为目录）
- `src/main.rs` — is_already_running + 启动流程 + 终端 QR
- `src/tray.rs` — 增加 Linux ksni 实现
- `src/lib.rs` — 更新 tray 模块条件编译 + 新增 terminal_qr
- `src/auth/middleware.rs` — 已配对设备 token 可访问管理页
- `src/gateway/client.rs` — Linux TCP keepalive
- `src/bridge/router.rs` — status 增加 autostart_mode
- `src/ui/index.html` — 前端动态适配

### Android (Kotlin) — 修改文件
- `feature/instance/.../QrScanViewModel.kt` — 自定义协议解析

---

### Task 1: 修复编译阻断 — autostart 模块拆分

**Files:**
- Delete: `src/api/autostart.rs`
- Create: `src/api/autostart/mod.rs`
- Create: `src/api/autostart/windows.rs`
- Create: `src/api/autostart/linux.rs`
- Modify: `src/api/mod.rs`

- [ ] **Step 1: 创建 autostart 目录和公共接口**

创建 `src/api/autostart/mod.rs`：

```rust
use axum::extract::State;
use axum::response::IntoResponse;
use axum::Json;
use serde::Deserialize;

use crate::error::AppError;
use crate::state::AppState;

#[derive(Deserialize)]
pub struct AutostartRequest {
    pub enabled: bool,
}

pub async fn get_autostart() -> Result<impl IntoResponse, AppError> {
    let enabled = Autostart::is_enabled();
    Ok(Json(serde_json::json!({ "enabled": enabled })))
}

pub async fn set_autostart(
    Json(body): Json<AutostartRequest>,
) -> Result<impl IntoResponse, AppError> {
    Autostart::set_enabled(body.enabled)?;
    Ok(Json(serde_json::json!({ "success": true, "enabled": body.enabled })))
}

pub fn autostart_mode() -> &'static str {
    Autostart::mode()
}

#[cfg(target_os = "windows")]
mod windows;
#[cfg(target_os = "linux")]
mod linux;

#[cfg(target_os = "windows")]
pub use windows::WindowsAutostart as Autostart;
#[cfg(target_os = "linux")]
pub use linux::LinuxAutostart as Autostart;
```

- [ ] **Step 2: 迁移 Windows 实现**

创建 `src/api/autostart/windows.rs`（从现有 autostart.rs 迁移核心逻辑）：

```rust
use crate::error::AppError;

pub struct WindowsAutostart;

impl super::AutostartImpl for WindowsAutostart {
    fn mode() -> &'static str { "windows" }

    fn is_enabled() -> bool {
        get_startup_shortcut_path()
            .map(|p| p.exists())
            .unwrap_or(false)
    }

    fn set_enabled(enabled: bool) -> Result<(), AppError> {
        let shortcut_path = get_startup_shortcut_path()?;
        if enabled {
            create_startup_shortcut(&shortcut_path)?;
        } else {
            remove_startup_shortcut(&shortcut_path)?;
        }
        Ok(())
    }
}
```

其中 `get_startup_shortcut_path`, `create_startup_shortcut`, `remove_startup_shortcut` 从现有 `src/api/autostart.rs` 原样搬入。

- [ ] **Step 3: 创建 Linux 实现**

创建 `src/api/autostart/linux.rs`：

```rust
use crate::error::AppError;

pub struct LinuxAutostart;

impl super::AutostartImpl for LinuxAutostart {
    fn mode() -> &'static str { "systemd" }

    fn is_enabled() -> bool {
        std::process::Command::new("systemctl")
            .args(["is-enabled", "openmate.service"])
            .output()
            .map(|o| o.status.success())
            .unwrap_or(false)
    }

    fn set_enabled(enabled: bool) -> Result<(), AppError> {
        let action = if enabled { "enable" } else { "disable" };
        let output = std::process::Command::new("sudo")
            .args(["systemctl", action, "openmate.service"])
            .output()
            .map_err(|e| AppError::Internal(anyhow::anyhow!("Failed to run systemctl: {}", e)))?;
        if !output.status.success() {
            let stderr = String::from_utf8_lossy(&output.stderr);
            return Err(AppError::Internal(anyhow::anyhow!("systemctl {} failed: {}", action, stderr)));
        }
        Ok(())
    }
}
```

- [ ] **Step 4: 更新 mod.rs 公共接口定义 trait**

回到 `src/api/autostart/mod.rs`，确认定义了 `AutostartImpl` trait（上面 Step 1 中 `Autostart` 是类型别名，实际 trait 应叫 `AutostartImpl`）：

```rust
pub trait AutostartImpl {
    fn mode() -> &'static str;
    fn is_enabled() -> bool;
    fn set_enabled(enabled: bool) -> Result<(), AppError>;
}
```

`get_autostart` 和 `set_autostart` handler 调用 `Autostart::is_enabled()` / `Autostart::set_enabled()`。

- [ ] **Step 5: 更新 src/api/mod.rs**

将 `mod autostart;` 保持不变（Rust 模块目录的 mod.rs 会自动识别），路由注册不变。删除旧的 `src/api/autostart.rs` 文件。

- [ ] **Step 6: 删除旧文件**

```bash
rm src/api/autostart.rs
```

- [ ] **Step 7: 验证编译**

```bash
cargo check
```

Expected: 编译通过，无错误

- [ ] **Step 8: 提交**

```bash
git add -A
git commit -m "refactor: split autostart module into platform implementations"
```

---

### Task 2: 单实例保护 + Gateway TCP Keepalive

**Files:**
- Modify: `src/main.rs:170-173`
- Modify: `src/gateway/client.rs:58-73`

- [ ] **Step 1: 实现 Linux 单实例保护**

修改 `src/main.rs`，将 `#[cfg(not(windows))]` 块替换为 Linux 特定实现：

```rust
#[cfg(target_os = "linux")]
fn is_already_running() -> bool {
    use std::os::unix::net::UnixListener;
    match UnixListener::bind("\0openmate-bridge") {
        Ok(_) => false,
        Err(_) => true,
    }
}

#[cfg(not(any(target_os = "windows", target_os = "linux")))]
fn is_already_running() -> bool {
    false
}
```

- [ ] **Step 2: 补全 Linux Gateway TCP Keepalive**

修改 `src/gateway/client.rs`，在 `#[cfg(windows)]` 块后增加：

```rust
#[cfg(target_os = "linux")]
{
    use std::os::unix::io::{AsRawFd, FromRawFd};
    let inner: &MaybeTlsStream<tokio::net::TcpStream> = ws_stream.get_ref();
    let tcp = match inner {
        MaybeTlsStream::Plain(tcp) => tcp,
        MaybeTlsStream::Rustls(tls) => tls.get_ref().0,
        _ => return Ok(()),
    };
    let ka = socket2::TcpKeepalive::new()
        .with_time(Duration::from_secs(30))
        .with_interval(Duration::from_secs(10));
    let sock = unsafe { socket2::Socket::from_raw_fd(tcp.as_raw_fd()) };
    let sock = std::mem::ManuallyDrop::new(sock);
    let _ = socket2::SockRef::from(&*sock).set_tcp_keepalive(&ka);
}
```

- [ ] **Step 3: 验证编译**

```bash
cargo check
```

Expected: 编译通过

- [ ] **Step 4: 运行现有测试**

```bash
cargo test
```

Expected: 所有测试通过

- [ ] **Step 5: 提交**

```bash
git add -A
git commit -m "feat: linux single-instance protection and gateway TCP keepalive"
```

---

### Task 3: 终端二维码配对（无桌面场景）

**Files:**
- Create: `src/terminal_qr.rs`
- Modify: `src/lib.rs`
- Modify: `src/main.rs`

- [ ] **Step 1: 创建终端二维码模块**

创建 `src/terminal_qr.rs`：

```rust
use crate::state::AppState;

pub fn print_terminal_qr(state: &AppState) {
    let iid = &state.config.gateway.instance_id;
    let port = state.actual_port.load(std::sync::atomic::Ordering::Relaxed);

    let scan_token = match state.scan_token.try_read() {
        Ok(st) => st.as_ref().map(|e| e.token.clone()).unwrap_or_default(),
        Err(_) => String::new(),
    };

    if scan_token.is_empty() {
        tracing::warn!("Scan token not available, cannot display QR code");
        return;
    }

    let qr_data = format!("openmate:iid={};st={}", iid, scan_token);

    match qrcode::QrCode::new(&qr_data) {
        Ok(code) => {
            let string = code.render::<qrcode::render::unicode::Dense1x2>()
                .quiet_zone(true)
                .module_dimensions(2, 1)
                .build();
            println!("\n{}", string);
        }
        Err(e) => {
            tracing::error!("Failed to generate QR code: {}", e);
        }
    }

    let hostname = hostname::get()
        .ok()
        .and_then(|h| h.into_string().ok())
        .unwrap_or_else(|| "Bridge".to_string());

    let local_ip = local_ip_address::local_ip()
        .map(|ip| ip.to_string())
        .unwrap_or_else(|_| "unknown".to_string());

    println!("  Bridge: {} ({}:{})", hostname, local_ip, port);
    println!("  Gateway: gateway.clawmate.net");
    println!("  Scan the QR code to pair your device");
    println!("  Or run: openmate approve <pin>\n");
}
```

- [ ] **Step 2: 注册模块**

修改 `src/lib.rs`，增加：

```rust
pub mod terminal_qr;
```

- [ ] **Step 3: 修改启动流程**

修改 `src/main.rs` 的 `run_gui_mode` 函数。在 `#[cfg(target_os = "windows")]` 托盘块之后，增加 Linux 桌面/无桌面判断：

将现有的"非 tray 模式打开浏览器"逻辑改为平台感知：

```rust
#[cfg(target_os = "linux")]
{
    let has_display = std::env::var("DISPLAY").is_ok()
        || std::env::var("WAYLAND_DISPLAY").is_ok();

    if has_display {
        // 有桌面：启动托盘 + 打开浏览器
        let (tx, rx) = std::sync::mpsc::channel::<openmate::tray::TrayEvent>();
        match openmate::tray::spawn_tray_thread(port, tx) {
            Ok(_) => {
                let shutdown_signal = shutdown_clone.clone();
                tokio::spawn(async move {
                    while let Ok(event) = rx.recv() {
                        match event {
                            openmate::tray::TrayEvent::Quit => {
                                shutdown_signal.notify_one();
                                break;
                            }
                            openmate::tray::TrayEvent::OpenUi => {
                                let url = format!("http://127.0.0.1:{}/ui/", port);
                                let _ = open::that(&url);
                            }
                            openmate::tray::TrayEvent::ToggleAutostart => {}
                        }
                    }
                });
            }
            Err(e) => tracing::warn!("Tray init failed: {}, continuing without tray", e),
        }

        if !_args.tray {
            let port_copy = port;
            tokio::spawn(async move {
                tokio::time::sleep(tokio::time::Duration::from_secs(2)).await;
                let url = format!("http://127.0.0.1:{}/ui/", port_copy);
                let _ = open::that(&url);
            });
        }
    } else {
        // 无桌面：输出终端二维码
        let state = /* 需要等 server 启动后获取 AppState，或在此生成 QR */;
        // 简化方案：延迟 3 秒输出 QR（等 server 就绪和 scan_token 生成）
        let port_copy = port;
        tokio::spawn(async move {
            tokio::time::sleep(tokio::time::Duration::from_secs(3)).await;
            // 直接调用 Bridge API 触发 scan_token 生成，然后输出 QR
            let client = reqwest::Client::new();
            let url = format!("http://127.0.0.1:{}/api/bridge/pair/scan", port_copy);
            if let Ok(resp) = client.get(&url).send().await {
                if let Ok(json) = resp.json::<serde_json::Value>().await {
                    if let Some(st) = json.get("scan_token").and_then(|v| v.as_str()) {
                        let iid = ""; // 需要从 config 获取
                        let qr_data = format!("openmate:iid={};st={}", iid, st);
                        match qrcode::QrCode::new(&qr_data) {
                            Ok(code) => {
                                let string = code.render::<qrcode::render::unicode::Dense1x2>()
                                    .quiet_zone(true)
                                    .module_dimensions(2, 1)
                                    .build();
                                println!("\n{}", string);
                            }
                            Err(e) => tracing::error!("QR generation failed: {}", e),
                        }
                        let hostname = hostname::get()
                            .ok()
                            .and_then(|h| h.into_string().ok())
                            .unwrap_or_else(|| "Bridge".to_string());
                        let local_ip = local_ip_address::local_ip()
                            .map(|ip| ip.to_string())
                            .unwrap_or_else(|_| "unknown".to_string());
                        println!("  Bridge: {} ({}:{})", hostname, local_ip, port_copy);
                        println!("  Gateway: gateway.clawmate.net");
                        println!("  Scan the QR code to pair your device");
                        println!("  Or run: openmate approve <pin>\n");
                    }
                }
            }
        });
    }
}
```

注意：由于 `run_gui_mode` 中 `AppState` 在 server 启动后才可用，终端 QR 输出需要通过 API 触发 scan_token 生成。上面的实现通过调用 `/api/bridge/pair/scan` API 来获取 token，同时需要从已加载的 config 中获取 instance_id。实际实现时需要将 config 的 gateway.instance_id 传入闭包。

- [ ] **Step 4: 验证编译**

```bash
cargo check
```

Expected: 编译通过

- [ ] **Step 5: 提交**

```bash
git add -A
git commit -m "feat: terminal QR code pairing for headless Linux"
```

---

### Task 4: Linux 系统托盘 (ksni)

**Files:**
- Modify: `Cargo.toml`
- Modify: `src/tray.rs`
- Modify: `src/lib.rs`

- [ ] **Step 1: 添加 ksni 依赖**

修改 `Cargo.toml`，在文件末尾添加：

```toml
[target.'cfg(target_os = "linux")'.dependencies]
ksni = "0.3"
```

- [ ] **Step 2: 更新 lib.rs 的 tray 模块声明**

修改 `src/lib.rs`，将 tray 模块从 windows-only 改为 windows+linux：

```rust
#[cfg(target_os = "windows")]
pub mod tray;
#[cfg(target_os = "linux")]
pub mod tray;
```

或者简化为：

```rust
#[cfg(any(target_os = "windows", target_os = "linux"))]
pub mod tray;
```

- [ ] **Step 3: 在 tray.rs 中添加 Linux 实现**

修改 `src/tray.rs`，在文件末尾（`#[cfg(not(windows))]` 的 stub 之前）添加 Linux 实现：

```rust
#[cfg(target_os = "linux")]
pub use ksni;

#[cfg(target_os = "linux")]
struct BridgeTray {
    port: u16,
    autostart_enabled: bool,
    tx: std::sync::mpsc::Sender<TrayEvent>,
}

#[cfg(target_os = "linux")]
impl ksni::Tray for BridgeTray {
    fn id(&self) -> String { "openmate-bridge".into() }

    fn title(&self) -> String { "OpenMate Bridge".into() }

    fn tool_tip(&self) -> ksni::ToolTip {
        ksni::ToolTip {
            title: "OpenMate Bridge".into(),
            description: format!("Port: {}", self.port).into(),
            ..Default::default()
        }
    }

    fn activate(&self, _x: i32, _y: i32) {
        let _ = self.tx.send(TrayEvent::OpenUi);
    }

    fn menu(&self) -> Vec<ksni::MenuItem<Self>> {
        use ksni::menu::*;
        vec![
            StandardItem {
                label: "Open Management Page".into(),
                activate: Box::new(|tray: &mut BridgeTray| {
                    let _ = tray.tx.send(TrayEvent::OpenUi);
                }),
                ..Default::default()
            },
            Separator,
            CheckmarkItem {
                label: "Auto-start on boot".into(),
                checked: self.autostart_enabled,
                activate: Box::new(|tray: &mut BridgeTray| {
                    tray.autostart_enabled = !tray.autostart_enabled;
                    let _ = tray.tx.send(TrayEvent::ToggleAutostart);
                }),
                ..Default::default()
            },
            Separator,
            StandardItem {
                label: "Quit".into(),
                activate: Box::new(|tray: &mut BridgeTray| {
                    let _ = tray.tx.send(TrayEvent::Quit);
                }),
                ..Default::default()
            },
        ]
    }
}

#[cfg(target_os = "linux")]
pub fn spawn_tray_thread(port: u16, tx: std::sync::mpsc::Sender<TrayEvent>) -> anyhow::Result<()> {
    if std::env::var("DBUS_SESSION_BUS_ADDRESS").is_err() {
        anyhow::bail!("D-Bus session bus not available, skipping tray");
    }

    let tray = BridgeTray { port, autostart_enabled: false, tx };
    let handle = ksni::Handle::new(tray);

    std::thread::spawn(move || {
        if let Err(e) = handle.run_blocking() {
            tracing::error!("Tray error: {}", e);
        }
    });
    Ok(())
}
```

- [ ] **Step 4: 删除旧的 stub**

删除 `src/tray.rs` 末尾的 `#[cfg(not(windows))]` stub 块（`spawn_tray_thread` 返回错误的部分），因为 Linux 已有实现。

- [ ] **Step 5: 验证编译**

```bash
cargo check
```

Expected: 编译通过

- [ ] **Step 6: 提交**

```bash
git add -A
git commit -m "feat: linux system tray via ksni"
```

---

### Task 5: 管理页远程访问 + Web 前端适配

**Files:**
- Modify: `src/auth/middleware.rs`
- Modify: `src/bridge/router.rs`
- Modify: `src/ui/index.html`

- [ ] **Step 1: 修改认证中间件允许已配对设备访问管理页**

修改 `src/auth/middleware.rs`，将 `LOCALHOST_ONLY_PATHS` 和 `/ui/` 的访问判断逻辑从：

```rust
if LOCALHOST_ONLY_PATHS.iter().any(|p| path == *p) || path.starts_with("/ui/") {
    if !is_localhost {
        return (StatusCode::FORBIDDEN, "Forbidden").into_response();
    }
    return next.run(req).await;
}
```

改为：

```rust
if LOCALHOST_ONLY_PATHS.iter().any(|p| path == *p) || path.starts_with("/ui/") {
    if is_localhost {
        return next.run(req).await;
    }
    // 允许已配对设备的 Bearer token 访问管理页和管理 API
    if let Some(auth_header) = req.headers().get("authorization") {
        if let Some(token_str) = auth_header.to_str().ok().and_then(Token::extract_from_header) {
            if Token::validate(&state.secret_key, token_str) {
                if let Some(device_id) = Token::extract_device_id(token_str) {
                    let bridge_db = state.bridge_db.clone();
                    let did = device_id.clone();
                    let exists = tokio::task::spawn_blocking(move || {
                        bridge_db.device_exists(&did).unwrap_or(false)
                    }).await.unwrap_or(false);
                    if exists {
                        let bridge_db = state.bridge_db.clone();
                        tokio::task::spawn_blocking(move || {
                            let now = SystemTime::now().duration_since(UNIX_EPOCH).unwrap_or_default().as_millis() as i64;
                            let _ = bridge_db.update_last_seen(&device_id, now);
                        });
                        return next.run(req).await;
                    }
                }
            }
        }
    }
    return (StatusCode::FORBIDDEN, "Forbidden").into_response();
}
```

- [ ] **Step 2: status API 增加 autostart_mode**

修改 `src/bridge/router.rs` 的 `status` 函数，在 JSON 响应中增加：

```rust
"autostart_mode": crate::api::autostart::autostart_mode(),
```

- [ ] **Step 3: Web 前端动态适配**

修改 `src/ui/index.html` 的设置页（Settings page），找到 autostart 开关部分：

1. 在页面加载时，从 `/api/bridge/status` 获取 `autostart_mode`
2. 根据 mode 动态渲染设置项：
   - `"windows"` → label 显示"开机自动启动"
   - `"systemd"` → label 显示"系统启动时自动启动 (systemd)"
   - `"unavailable"` → 隐藏该设置项

具体改动：在现有 settings page 的 autostart 开关渲染逻辑中，加入 mode 判断。如果 mode 为 `"unavailable"` 则不渲染该行。

- [ ] **Step 4: 验证编译**

```bash
cargo check
```

Expected: 编译通过

- [ ] **Step 5: 运行测试**

```bash
cargo test
```

Expected: 所有测试通过

- [ ] **Step 6: 提交**

```bash
git add -A
git commit -m "feat: allow paired device token for management page access"
```

---

### Task 6: Android 自定义协议解析

**Files:**
- Modify: `feature/instance/.../QrScanViewModel.kt`

- [ ] **Step 1: 添加自定义协议解析**

修改 `QrScanViewModel.kt` 的 `parseQrUrl` 方法，在现有 HTTP URL 解析前增加自定义协议分支：

```kotlin
private fun parseQrUrl(url: String): ParsedQrUrl? {
    if (url.startsWith("openmate:", ignoreCase = true)) {
        return parseCustomProtocol(url)
    }
    // 现有 HTTP URL 解析逻辑不变
    return try {
        val parsed = URL(url)
        // ... 现有代码
    } catch (e: Exception) {
        null
    }
}

private fun parseCustomProtocol(url: String): ParsedQrUrl? {
    // openmate:iid=<32hex>;st=<64hex>
    val params = url.removePrefix("openmate:").split(";")
    var iid = ""
    var st = ""
    for (param in params) {
        val parts = param.split("=", limit = 2)
        if (parts.size == 2) {
            when (parts[0]) {
                "iid" -> iid = parts[1]
                "st" -> st = parts[1]
            }
        }
    }
    if (st.isBlank()) return null
    return ParsedQrUrl(
        name = "",
        address = "",
        port = 0,
        scanToken = st,
        instanceId = iid,
    )
}
```

- [ ] **Step 2: 配对成功后获取补充信息**

在 `performScanPair` 或 `handleScanComplete` 中，当 `ParsedQrUrl.address` 为空（自定义协议场景）时，配对成功后用 token 调用 Bridge `/api/bridge/status` 获取 name、端口等信息填充 ServerProfile：

```kotlin
// 在 ScanResult 处理中，如果 address 为空，从 status API 获取
if (result.address.isBlank()) {
    try {
        val statusUrl = "${GATEWAY_URL}/api/bridge/status"
        // 通过网关代理调用，使用 instanceId 构建路径
        val status = apiClient.getBridgeStatus(result.instanceId, result.token)
        result = result.copy(
            name = status.bridgeName ?: "Bridge",
            address = status.address ?: "",
            port = status.port ?: 4097,
        )
    } catch (e: Exception) {
        Log.w(TAG, "Failed to fetch bridge info, using defaults", e)
    }
}
```

注意：需要确认 Android 端 `OpencodeApiClient` 是否已有通过网关代理调用 Bridge API 的能力。如果没有，可能需要在 `handleScanComplete` 中使用直接连接（如果有 LAN 地址）或添加网关代理 API 调用方法。

- [ ] **Step 3: 编译验证**

```bash
gradle_runner_run_gradle args=[":feature:instance:compileDebugKotlin"]
```

Expected: BUILD SUCCESSFUL

- [ ] **Step 4: 提交**

```bash
git add -A
git commit -m "feat: android custom protocol qr parsing for headless pairing"
```

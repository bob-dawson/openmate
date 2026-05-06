# OpenMate Bridge Service Mode Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Rename binary to `openmate`, add `install`/`uninstall`/`service` CLI subcommands for Windows (win32 service) and Linux (systemd) system service support.

**Architecture:** Extract server startup into `server.rs` shared by foreground and service modes. Platform-specific service code lives in `service_windows.rs` / `service_linux.rs` behind `#[cfg]`. Windows uses `windows-service` crate for Win32 Service API; Linux generates systemd unit files.

**Tech Stack:** Rust, `windows-service` crate (Windows only), systemd (Linux), `tokio::sync::Notify` for graceful shutdown.

---

## File Structure

| File | Action | Responsibility |
|------|--------|---------------|
| `Cargo.toml` | Modify | Rename package, add platform-specific deps |
| `src/main.rs` | Modify | Add Install/Uninstall/Service commands, route to platform modules |
| `src/server.rs` | Create | Extracted axum server startup + graceful shutdown |
| `src/service_windows.rs` | Create | Windows service install/uninstall/run via `windows-service` crate |
| `src/service_linux.rs` | Create | Linux systemd unit install/uninstall |
| `src/lib.rs` | Modify | Export `server` module |
| `src/config.rs` | Modify | No changes needed (config search already includes exe dir) |
| `deploy.bat` | Modify | Update binary name references |
| `tests/integration.rs` | Modify | No changes needed |

---

### Task 1: Rename package and update Cargo.toml

**Files:**
- Modify: `Cargo.toml`

- [ ] **Step 1: Update Cargo.toml**

```toml
[package]
name = "openmate"
version = "0.1.0"
edition = "2024"
description = "OpenMate Bridge - proxy, process management, file serving, and authentication"

[dependencies]
axum = { version = "0.8", features = ["macros"] }
tokio = { version = "1", features = ["full"] }
reqwest = { version = "0.12", features = ["stream", "json"] }
serde = { version = "1", features = ["derive"] }
serde_json = "1"
toml = "0.8"
tracing = "0.1"
tracing-subscriber = { version = "0.3", features = ["env-filter"] }
anyhow = "1"
thiserror = "2"
tower = "0.5"
tower-http = { version = "0.6", features = ["cors", "trace"] }
tokio-stream = "0.1"
tokio-util = { version = "0.7", features = ["io"] }
futures = "0.3"
async-stream = "0.3"
clap = { version = "4", features = ["derive"] }
hmac = "0.12"
sha2 = "0.10"
getrandom = "0.3"

[target.'cfg(windows)'.dependencies]
windows-service = "0.8"

[dev-dependencies]
http-body-util = "0.1"
tower = { version = "0.5", features = ["util"] }
```

Note: remove duplicate `tokio-util` entry if there's already one.

- [ ] **Step 2: Build to verify Cargo.toml resolves**

Run: `cargo check 2>&1`
Expected: compiles (may have unused import warnings)

- [ ] **Step 3: Commit**

```bash
git add Cargo.toml
git commit -m "chore: rename package to openmate, add windows-service dep"
```

---

### Task 2: Extract server startup into `server.rs`

**Files:**
- Create: `src/server.rs`
- Modify: `src/main.rs`
- Modify: `src/lib.rs`

- [ ] **Step 1: Create `src/server.rs`**

Extract the server startup logic from `main.rs` into a standalone function that can be called from both foreground mode and service mode. Add graceful shutdown support via `tokio::sync::Notify`.

```rust
use axum::Router;
use axum::routing::{any, get, post};
use std::net::SocketAddr;
use std::sync::Arc;
use tokio::sync::Notify;
use tower_http::cors::CorsLayer;
use tower_http::trace::TraceLayer;

use crate::auth;
use crate::bridge;
use crate::config::Config;
use crate::files;
use crate::fs;
use crate::proxy;
use crate::state::create_app_state;

pub async fn run_server(config: Config, shutdown_notify: Option<Arc<Notify>>) -> anyhow::Result<()> {
    tracing::info!("OpenMate Bridge starting");
    tracing::info!("Bridge listen: {}:{}", config.bridge.hostname, config.bridge.port);
    tracing::info!("OpenCode target: {}", config.opencode_url());
    tracing::info!("Allowed paths: {:?}", config.effective_allowed_paths());
    tracing::info!("Auth enabled: {}", config.bridge.auth_enabled);

    let app_state = create_app_state(config.clone());

    if config.opencode.auto_start {
        tracing::info!("Auto-starting opencode...");
        if let Err(e) = app_state.opencode_manager.start().await {
            tracing::warn!("Auto-start failed: {}", e);
        }
    }

    let app = Router::new()
        .route("/api/bridge/status", get(bridge::router::status))
        .route("/api/bridge/opencode/start", post(bridge::router::start_opencode))
        .route("/api/bridge/opencode/stop", post(bridge::router::stop_opencode))
        .route("/api/bridge/opencode/restart", post(bridge::router::restart_opencode))
        .route("/api/bridge/pair/request", post(auth::pair::pair_request))
        .route("/api/bridge/pair/approve", post(auth::pair::pair_approve))
        .route("/api/bridge/pair/confirm", post(auth::pair::pair_confirm))
        .route("/api/bridge/fs/roots", get(fs::router::roots))
        .route("/api/bridge/fs/list", get(fs::router::list))
        .route("/api/bridge/fs/read", get(fs::router::read))
        .route("/api/bridge/fs/download", get(fs::router::download))
        .route("/api/bridge/fs/mkdir", post(fs::router::mkdir))
        .route("/api/bridge/fs/search", post(fs::router::search))
        .route("/api/bridge/fs/stat", get(fs::router::stat))
        .route("/api/bridge/fs/write", post(fs::router::write))
        .route("/api/bridge/fs/upload", axum::routing::put(fs::router::upload).layer(axum::extract::DefaultBodyLimit::max(100 * 1024 * 1024)))
        .route("/files/{*path}", get(files::router::serve_file))
        .route("/api/opencode/global/event", get(proxy::sse::sse_proxy))
        .route("/global/event", get(proxy::sse::sse_proxy))
        .route("/api/opencode/{*path}", any(proxy::rest::proxy_opencode_request))
        .fallback(any(proxy::rest::proxy_fallback))
        .layer(axum::middleware::from_fn_with_state(
            app_state.clone(),
            auth::middleware::auth_middleware,
        ))
        .layer(CorsLayer::permissive())
        .layer(TraceLayer::new_for_http())
        .with_state(app_state);

    let listener = tokio::net::TcpListener::bind(config.bridge_listen_addr()).await?;
    tracing::info!("Bridge listening on {}", config.bridge_listen_addr());

    let server = axum::serve(
        listener,
        app.into_make_service_with_connect_info::<SocketAddr>(),
    );

    if let Some(notify) = shutdown_notify {
        tokio::select! {
            result = server => result?,
            _ = notify.notified() => {
                tracing::info!("Shutdown signal received, stopping server");
            }
        }
    } else {
        server.await?;
    }

    Ok(())
}
```

- [ ] **Step 2: Update `src/lib.rs` to export server module**

Add `pub mod server;` to `src/lib.rs`.

- [ ] **Step 3: Update `src/main.rs` to use `server::run_server`**

Replace the inline server startup code in the `None` branch of `match args.command` with a call to `crate::server::run_server(config, None)`. Keep the `run_approve` and `run_reset_token` functions. Remove the now-unused imports from main.rs (Router, routing, tower_http imports, SocketAddr, etc.) — they move to server.rs.

The updated `main.rs` should look like:

```rust
use clap::{Parser, Subcommand};
use opencode_bridge::config::Config;
use std::path::PathBuf;

#[derive(Parser, Debug)]
#[command(name = "openmate", about = "OpenMate Bridge")]
struct Args {
    #[arg(short, long)]
    config: Option<PathBuf>,

    #[command(subcommand)]
    command: Option<Commands>,
}

#[derive(Subcommand, Debug)]
enum Commands {
    Approve { pin: String },
    ResetToken,
    Install,
    Uninstall,
    Service,
}

#[tokio::main]
async fn main() -> anyhow::Result<()> {
    tracing_subscriber::fmt()
        .with_env_filter(
            tracing_subscriber::EnvFilter::try_from_default_env()
                .unwrap_or_else(|_| tracing_subscriber::EnvFilter::new("info")),
        )
        .init();

    let args = Args::parse();

    match args.command {
        Some(Commands::Approve { pin }) => run_approve(&pin).await,
        Some(Commands::ResetToken) => run_reset_token().await,
        Some(Commands::Install) => run_install().await,
        Some(Commands::Uninstall) => run_uninstall().await,
        Some(Commands::Service) => run_service_mode().await,
        None => {
            let config = Config::find_and_load(args.config)?;
            opencode_bridge::server::run_server(config, None).await
        }
    }
}

#[cfg(target_os = "windows")]
async fn run_install() -> anyhow::Result<()> {
    opencode_bridge::service_windows::install()
}

#[cfg(target_os = "linux")]
async fn run_install() -> anyhow::Result<()> {
    opencode_bridge::service_linux::install()
}

#[cfg(target_os = "windows")]
async fn run_uninstall() -> anyhow::Result<()> {
    opencode_bridge::service_windows::uninstall()
}

#[cfg(target_os = "linux")]
async fn run_uninstall() -> anyhow::Result<()> {
    opencode_bridge::service_linux::uninstall()
}

#[cfg(target_os = "windows")]
async fn run_service_mode() -> anyhow::Result<()> {
    opencode_bridge::service_windows::run_service()
}

#[cfg(target_os = "linux")]
async fn run_service_mode() -> anyhow::Result<()> {
    let config = Config::find_and_load(None)?;
    opencode_bridge::server::run_server(config, None).await
}

// ... keep run_approve and run_reset_token unchanged
```

Note: The `run_install`, `run_uninstall`, `run_service_mode` functions need `#[cfg(not(target_os = "..."))]` stubs or a compile-time guarantee that only Windows or Linux targets are used. Since this project only targets Windows and Linux, the above is sufficient. If compiling on another platform, add a catch-all that panics.

- [ ] **Step 4: Build and verify**

Run: `cargo check 2>&1`
Expected: compiles with warnings about unused `#[cfg]` modules (not yet created)

- [ ] **Step 5: Run existing tests**

Run: `cargo test 2>&1`
Expected: all tests pass (server.rs is a pure extraction, no behavior change)

- [ ] **Step 6: Commit**

```bash
git add src/server.rs src/main.rs src/lib.rs
git commit -m "refactor: extract server startup into server.rs, add CLI stubs for install/uninstall/service"
```

---

### Task 3: Implement Windows service module

**Files:**
- Create: `src/service_windows.rs`

- [ ] **Step 1: Create `src/service_windows.rs`**

This module implements three public functions: `install()`, `uninstall()`, `run_service()`.

```rust
use std::ffi::OsString;
use std::path::PathBuf;
use std::sync::mpsc;
use std::time::Duration;

#[macro_use]
extern crate windows_service;

use windows_service::service::{
    ServiceAccess, ServiceControl, ServiceControlAccept, ServiceErrorControl, ServiceExitCode,
    ServiceInfo, ServiceState, ServiceStatus, ServiceType,
};
use windows_service::service_control_handler::{self, ServiceControlHandlerResult};
use windows_service::service_manager::{ServiceManager, ServiceManagerAccess};

use crate::config::Config;

const SERVICE_NAME: &str = "OpenMate";
const SERVICE_DISPLAY_NAME: &str = "OpenMate Bridge";

define_windows_service!(ffi_service_main, windows_service_main);

fn windows_service_main(_arguments: Vec<OsString>) {
    if let Err(e) = run_service_inner() {
        tracing::error!("Service error: {}", e);
    }
}

fn run_service_inner() -> windows_service::Result<()> {
    let (shutdown_tx, shutdown_rx) = mpsc::channel::<()>();

    let status_handle = service_control_handler::register(
        SERVICE_NAME,
        move |control_event| -> ServiceControlHandlerResult {
            match control_event {
                ServiceControl::Interrogate => ServiceControlHandlerResult::NoError,
                ServiceControl::Stop | ServiceControl::Shutdown => {
                    let _ = shutdown_tx.send(());
                    ServiceControlHandlerResult::NoError
                }
                _ => ServiceControlHandlerResult::NotImplemented,
            }
        },
    )?;

    status_handle.set_service_status(ServiceStatus {
        service_type: ServiceType::OWN_PROCESS,
        current_state: ServiceState::Running,
        controls_accepted: ServiceControlAccept::STOP | ServiceControlAccept::SHUTDOWN,
        exit_code: ServiceExitCode::Win32(0),
        checkpoint: 0,
        wait_hint: Duration::default(),
        process_id: None,
    })?;

    let config = Config::find_and_load(None).unwrap_or_else(|_| {
        tracing::warn!("No config file found, using defaults");
        Config::default()
    });

    let rt = tokio::runtime::Runtime::new().map_err(|e| {
        windows_service::Error::General(format!("Failed to create tokio runtime: {}", e))
    })?;

    let notify = std::sync::Arc::new(tokio::sync::Notify::new());
    let notify_clone = notify.clone();

    rt.spawn(async move {
        shutdown_rx.recv().ok();
        notify_clone.notify_one();
    });

    if let Err(e) = rt.block_on(crate::server::run_server(config, Some(notify))) {
        tracing::error!("Server error: {}", e);
    }

    status_handle.set_service_status(ServiceStatus {
        service_type: ServiceType::OWN_PROCESS,
        current_state: ServiceState::Stopped,
        controls_accepted: ServiceControlAccept::empty(),
        exit_code: ServiceExitCode::Win32(0),
        checkpoint: 0,
        wait_hint: Duration::default(),
        process_id: None,
    })?;

    Ok(())
}

pub fn install() -> anyhow::Result<()> {
    let exe_path = std::env::current_exe()?;
    let manager_access = ServiceManagerAccess::CONNECT | ServiceManagerAccess::CREATE_SERVICE;
    let service_manager = ServiceManager::local_computer(None::<&str>, manager_access)?;

    let service_info = ServiceInfo {
        name: OsString::from(SERVICE_NAME),
        display_name: OsString::from(SERVICE_DISPLAY_NAME),
        service_type: ServiceType::OWN_PROCESS,
        start_type: windows_service::service::ServiceStartType::AutoStart,
        error_control: ServiceErrorControl::Normal,
        executable_path: exe_path,
        launch_arguments: vec![OsString::from("service")],
        dependencies: vec![],
        account_name: None,
        account_password: None,
    };

    let service = service_manager.create_service(&service_info, ServiceAccess::START)?;
    service.set_description("OpenMate Bridge - proxy, file serving, and authentication for OpenCode")?;

    service.start(&[] as &[&OsString])?;
    println!("Service '{}' installed and started successfully.", SERVICE_NAME);

    Ok(())
}

pub fn uninstall() -> anyhow::Result<()> {
    let manager_access = ServiceManagerAccess::CONNECT;
    let service_manager = ServiceManager::local_computer(None::<&str>, manager_access)?;

    let service_access = ServiceAccess::QUERY_STATUS | ServiceAccess::STOP | ServiceAccess::DELETE;
    let service = service_manager.open_service(SERVICE_NAME, service_access)?;

    service.delete()?;

    let status = service.query_status()?;
    if status.current_state != ServiceState::Stopped {
        service.stop()?;
    }

    drop(service);

    println!("Service '{}' uninstalled successfully.", SERVICE_NAME);

    Ok(())
}

pub fn run_service() -> anyhow::Result<()> {
    windows_service::service_dispatcher::start(SERVICE_NAME, ffi_service_main)?;
    Ok(())
}
```

- [ ] **Step 2: Add `pub mod service_windows;` to `src/lib.rs`**

```rust
#[cfg(target_os = "windows")]
pub mod service_windows;
```

- [ ] **Step 3: Build on Windows**

Run: `cargo check 2>&1`
Expected: compiles successfully

- [ ] **Step 4: Commit**

```bash
git add src/service_windows.rs src/lib.rs
git commit -m "feat(windows): add service install/uninstall/run via windows-service crate"
```

---

### Task 4: Implement Linux service module

**Files:**
- Create: `src/service_linux.rs`

- [ ] **Step 1: Create `src/service_linux.rs`**

```rust
use std::io::Write;
use std::path::Path;
use std::process::Command;

const SERVICE_NAME: &str = "openmate";
const UNIT_PATH: &str = "/etc/systemd/system/openmate.service";

fn unit_content(exe_path: &str, working_dir: &str) -> String {
    format!(
        "[Unit]\n\
         Description=OpenMate Bridge\n\
         After=network.target\n\
         \n\
         [Service]\n\
         Type=simple\n\
         ExecStart={} service\n\
         WorkingDirectory={}\n\
         Restart=on-failure\n\
         RestartSec=5\n\
         \n\
         [Install]\n\
         WantedBy=multi-user.target\n",
        exe_path, working_dir
    )
}

pub fn install() -> anyhow::Result<()> {
    let exe_path = std::env::current_exe()?;
    let exe_str = exe_path.to_str().ok_or_else(|| anyhow::anyhow!("Invalid exe path"))?;
    let working_dir = exe_path
        .parent()
        .ok_or_else(|| anyhow::anyhow!("Cannot determine exe directory"))?
        .to_str()
        .ok_or_else(|| anyhow::anyhow!("Invalid working directory path"))?;

    let content = unit_content(exe_str, working_dir);

    let mut file = std::fs::File::create(UNIT_PATH)
        .map_err(|e| anyhow::anyhow!("Failed to create {}: {} (run as root/sudo)", UNIT_PATH, e))?;
    file.write_all(content.as_bytes())?;

    systemctl(&["daemon-reload"])?;
    systemctl(&["enable", SERVICE_NAME])?;
    systemctl(&["start", SERVICE_NAME])?;

    println!("Service '{}' installed and started successfully.", SERVICE_NAME);
    Ok(())
}

pub fn uninstall() -> anyhow::Result<()> {
    systemctl(&["stop", SERVICE_NAME]).ok();
    systemctl(&["disable", SERVICE_NAME])?;

    if Path::new(UNIT_PATH).exists() {
        std::fs::remove_file(UNIT_PATH)?;
    }

    systemctl(&["daemon-reload"])?;

    println!("Service '{}' uninstalled successfully.", SERVICE_NAME);
    Ok(())
}

fn systemctl(args: &[&str]) -> anyhow::Result<()> {
    let output = Command::new("systemctl").args(args).output()?;
    if !output.status.success() {
        let stderr = String::from_utf8_lossy(&output.stderr);
        anyhow::bail!("systemctl {} failed: {}", args.join(" "), stderr);
    }
    Ok(())
}
```

- [ ] **Step 2: Add `pub mod service_linux;` to `src/lib.rs`**

```rust
#[cfg(target_os = "linux")]
pub mod service_linux;
```

- [ ] **Step 3: Verify on current platform (Windows)**

Run: `cargo check 2>&1`
Expected: compiles (Linux module is behind `#[cfg(target_os = "linux")]`, not compiled on Windows)

- [ ] **Step 4: Commit**

```bash
git add src/service_linux.rs src/lib.rs
git commit -m "feat(linux): add systemd service install/uninstall"
```

---

### Task 5: Update deploy.bat and build artifacts

**Files:**
- Modify: `D:\openmate\scripts\deploy.bat`
- Delete old: `D:\openmate\opencode-bridge\release\opencode-bridge.exe` (replaced by `openmate.exe`)

- [ ] **Step 1: Update deploy.bat**

Replace all references to `opencode-bridge` with `openmate`:

```bat
@echo off
setlocal

echo === Stopping Bridge ===
taskkill /F /IM openmate.exe 2>nul
timeout /t 2 /nobreak >nul

echo === Copying Bridge binary ===
copy /Y "D:\openmate\opencode-bridge\target\release\openmate.exe" "D:\openmate\opencode-bridge\release\openmate.exe"
if %errorlevel% neq 0 (
    echo FAILED: Could not copy Bridge binary
    goto :bridge_fail
)

echo === Starting Bridge ===
start "" "D:\openmate\opencode-bridge\release\openmate.exe"
timeout /t 3 /nobreak >nul

:bridge_fail

echo === Installing APK ===
adb install -r "D:\openmate\android\app\build\outputs\apk\debug\app-debug.apk"
if %errorlevel% neq 0 (
    echo FAILED: Could not install APK - is device connected?
)

echo === Done ===
```

- [ ] **Step 2: Build release binary and verify name**

Run: `cargo build --release 2>&1`
Expected: produces `target/release/openmate.exe`

- [ ] **Step 3: Verify new binary works**

Run: `.\target\release\openmate.exe --help 2>&1`
Expected: shows `openmate` in usage text with `install`, `uninstall`, `service` subcommands

- [ ] **Step 4: Commit**

```bash
git add scripts/deploy.bat
git commit -m "chore: update deploy.bat for openmate binary name"
```

---

### Task 6: Update AGENTS.md references

**Files:**
- Modify: `D:\openmate\opencode-bridge\AGENTS.md`
- Modify: `D:\openmate\AGENTS.md`

- [ ] **Step 1: Update Bridge AGENTS.md**

Replace `opencode-bridge.exe` with `openmate.exe`, update `cargo build` output description, add `install`/`uninstall`/`service` commands to documentation.

- [ ] **Step 2: Update root AGENTS.md**

Update Bridge Agent section to reference `openmate` binary name.

- [ ] **Step 3: Commit**

```bash
git add opencode-bridge/AGENTS.md AGENTS.md
git commit -m "docs: update AGENTS.md for openmate binary rename"
```

---

## Summary of Changes

1. **Task 1**: Rename Cargo.toml package, add `windows-service` dep
2. **Task 2**: Extract `run_server()` into `server.rs`, restructure `main.rs` CLI
3. **Task 3**: Windows service via `windows-service` crate (install/uninstall/run)
4. **Task 4**: Linux systemd service (install/uninstall)
5. **Task 5**: Update deploy.bat, verify binary name
6. **Task 6**: Update documentation

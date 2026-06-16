# Bridge 更新 实施计划 (Plan 2)

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 实现 Bridge 端自更新（查版本 → 下载新二进制 → helper-script 自替换 + 重启），以及 Android 端触发与状态展示。

**Architecture:** Bridge 新增 `src/update/` 模块（UpgradeManager），通过 version.json（jsDelivr/raw 双源）查 bridge 模块最新版本，按 tag 构造 GitHub release asset 下载 URL，reqwest 流式下载到临时文件。用户确认后生成跨平台 helper-script（Win ps1 / Unix sh），spawn detached 脚本 → 触发优雅退出 → 脚本等待旧进程退出 → 备份 → 替换 → 启动新进程 → 自删。3 个 API endpoint 暴露给 Android。Android 设置页新增 Bridge 更新卡片，镜像 Plan 1 的 App 更新卡片样式。

**Tech Stack:** Rust (axum, reqwest, tokio), Kotlin (Jetpack Compose, Hilt)

**设计文档:** `docs/superpowers/specs/2026-06-16-auto-update-design.md` §5 (Bridge 端设计), §6.2-6.3/6.6 (Android 端设计)

**前置条件:** Plan 1 已实施（VersionClient + version.json 基础设施 + App 更新卡片）

---

## File Structure

| 文件 | 操作 | 职责 |
|------|------|------|
| `opencode-bridge/Cargo.toml` | 修改 | 加 `mockito` dev-dependency |
| `opencode-bridge/src/update/mod.rs` | 创建 | 模块声明 + re-export |
| `opencode-bridge/src/update/version.rs` | 创建 | version.json 查询（jsDelivr/raw）+ 版本比较 |
| `opencode-bridge/src/update/platform.rs` | 创建 | 平台→产物名映射 + exe 路径 |
| `opencode-bridge/src/update/script.rs` | 创建 | helper-script 生成（ps1/sh） |
| `opencode-bridge/src/update/manager.rs` | 创建 | UpgradeManager（download + apply + 状态管理） |
| `opencode-bridge/src/bridge/router.rs` | 修改 | 新增 3 个 upgrade handler |
| `opencode-bridge/src/server.rs` | 修改 | 注册 3 条新 route |
| `opencode-bridge/src/state.rs` | 修改 | AppState 加 upgrade_manager 字段 |
| `opencode-bridge/src/lib.rs` | 修改 | 加 `pub mod update;` |
| `android/core/network/.../VersionClient.kt` | 修改 | 加 fetchBridgeVersion() 方法 |
| `android/core/network/.../OpencodeApiClient.kt` | 修改 | 加 3 个 bridge upgrade 方法 |
| `android/core/network/.../dto/BridgeDto.kt` | 修改 | 加 BridgeUpgradeStatusDto |
| `android/feature/settings/.../SettingsViewModel.kt` | 修改 | Bridge 更新状态管理 + upgradeBridge() |
| `android/feature/session/.../WorkspaceListScreen.kt` | 修改 | Bridge 更新卡片 |
| `android/feature/session/.../strings.xml` | 修改 | Bridge 更新字符串 |

---

## Task 1: version.rs — version.json 查询 + 版本比较

**Files:**
- Modify: `opencode-bridge/Cargo.toml`（加 mockito dev-dependency）
- Create: `opencode-bridge/src/update/version.rs`

- [ ] **Step 1: 加 mockito dev-dependency**

在 `Cargo.toml` 的 `[dev-dependencies]` 末尾追加：

```toml
mockito = "1"
```

- [ ] **Step 2: 写失败测试**

`src/update/version.rs`（先写测试模块，实现留空）：

```rust
use serde::Deserialize;

#[derive(Debug, Deserialize)]
pub struct VersionManifest {
    pub bridge: Option<ModuleVersion>,
    pub android: Option<ModuleVersion>,
}

#[derive(Debug, Deserialize)]
pub struct ModuleVersion {
    pub version: String,
    pub tag: String,
    #[serde(rename = "releasedAt")]
    pub released_at: Option<String>,
}

const JSDELIVR_URL: &str =
    "https://cdn.jsdelivr.net/gh/bob-dawson/openmate@main/version.json";
const RAW_URL: &str =
    "https://raw.githubusercontent.com/bob-dawson/openmate/main/version.json";

pub async fn fetch_version_manifest_from(
    jsdelivr_url: &str,
    raw_url: &str,
) -> Option<VersionManifest> {
    fetch_from(jsdelivr_url).await.or_else(|| {
        // blocking scope doesn't work; need manual fallback
        None
    })
}

pub async fn fetch_version_manifest() -> Option<VersionManifest> {
    fetch_version_manifest_from(JSDELIVR_URL, RAW_URL).await
}

async fn fetch_from(url: &str) -> Option<VersionManifest> {
    // implementation in Step 4
    None
}

pub fn is_newer(new: &str, old: &str) -> bool {
    let parse = |v: &str| -> Vec<u32> {
        v.trim_start_matches('v')
            .split('.')
            .filter_map(|s| s.parse().ok())
            .collect()
    };
    parse(new) > parse(old)
}
```

> 注意：`fetch_version_manifest_from` 的 fallback 逻辑用 sequential await 实现（见 Step 4），不是 `or_else`。

- [ ] **Step 3: 写测试**

在文件末尾追加测试模块：

```rust
#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn test_is_newer_major() {
        assert!(is_newer("2.0.0", "1.0.0"));
    }

    #[test]
    fn test_is_newer_patch() {
        assert!(is_newer("1.0.1", "1.0.0"));
    }

    #[test]
    fn test_is_newer_same() {
        assert!(!is_newer("1.0.0", "1.0.0"));
    }

    #[test]
    fn test_is_newer_older() {
        assert!(!is_newer("1.0.0", "2.0.0"));
    }

    #[test]
    fn test_is_newer_with_v_prefix() {
        assert!(is_newer("v1.16.0", "v1.15.0"));
    }

    #[test]
    fn test_parse_manifest() {
        let json = r#"{"android":{"version":"0.1.20","tag":"v0.1.20"},"bridge":{"version":"0.1.19","tag":"v0.1.19","releasedAt":"2026-06-16"}}"#;
        let m: VersionManifest = serde_json::from_str(json).unwrap();
        assert_eq!(m.android.as_ref().unwrap().version, "0.1.20");
        assert_eq!(m.bridge.as_ref().unwrap().version, "0.1.19");
        assert_eq!(m.bridge.as_ref().unwrap().tag, "v0.1.19");
    }

    #[tokio::test]
    async fn test_fetch_jsdelivr_succeeds() {
        let mut server = mockito::Server::new_async().await;
        let _m = server
            .mock("GET", "/version.json")
            .with_status(200)
            .with_body(r#"{"bridge":{"version":"0.1.20","tag":"v0.1.20"}}"#)
            .create_async()
            .await;
        let url = format!("{}/version.json", server.url());
        let result = fetch_version_manifest_from(&url, "https://invalid.example.invalid/v.json").await;
        assert!(result.is_some());
        assert_eq!(result.unwrap().bridge.unwrap().version, "0.1.20");
    }

    #[tokio::test]
    async fn test_fetch_falls_back_to_raw() {
        let mut jsdelivr = mockito::Server::new_async().await;
        let mut raw = mockito::Server::new_async().await;
        jsdelivr
            .mock("GET", "/version.json")
            .with_status(500)
            .create_async()
            .await;
        raw.mock("GET", "/version.json")
            .with_status(200)
            .with_body(r#"{"bridge":{"version":"0.1.20","tag":"v0.1.20"}}"#)
            .create_async()
            .await;
        let result = fetch_version_manifest_from(
            &format!("{}/version.json", jsdelivr.url()),
            &format!("{}/version.json", raw.url()),
        )
        .await;
        assert!(result.is_some());
    }

    #[tokio::test]
    async fn test_fetch_both_fail() {
        let mut s = mockito::Server::new_async().await;
        s.mock("GET", "/v.json").with_status(500).create_async().await;
        let result = fetch_version_manifest_from(
            &format!("{}/v.json", s.url()),
            &format!("{}/v.json", s.url()),
        )
        .await;
        assert!(result.is_none());
    }
}
```

- [ ] **Step 4: 实现 fetch_from + fetch_version_manifest_from**

替换 Step 2 中的占位实现：

```rust
pub async fn fetch_version_manifest_from(
    jsdelivr_url: &str,
    raw_url: &str,
) -> Option<VersionManifest> {
    if let Some(m) = fetch_from(jsdelivr_url).await {
        return Some(m);
    }
    fetch_from(raw_url).await
}

async fn fetch_from(url: &str) -> Option<VersionManifest> {
    let client = reqwest::Client::builder()
        .timeout(std::time::Duration::from_secs(15))
        .build()
        .ok()?;
    let resp = client.get(url).send().await.ok()?;
    if !resp.status().is_success() {
        return None;
    }
    resp.json::<VersionManifest>().await.ok()
}
```

- [ ] **Step 5: 运行测试确认通过**

Run: `cargo test --lib update::version`
Expected: 8 tests PASS

- [ ] **Step 6: Commit**

```bash
cd opencode-bridge
git add Cargo.toml Cargo.lock src/update/version.rs
git commit -m "feat(update): version.rs — version.json 查询与版本比较"
```

---

## Task 2: platform.rs — 平台→产物名映射

**Files:**
- Create: `opencode-bridge/src/update/platform.rs`

- [ ] **Step 1: 写实现 + 测试**

```rust
use std::path::PathBuf;

pub fn asset_name_for(os: &str, arch: &str) -> Option<&'static str> {
    match (os, arch) {
        ("windows", _) => Some("openmate.exe"),
        ("linux", "x86_64") => Some("openmate-linux-x86_64"),
        ("linux", "aarch64") => Some("openmate-linux-arm64"),
        ("macos", "aarch64") => Some("openmate-darwin-arm64"),
        _ => None,
    }
}

pub fn asset_name() -> &'static str {
    asset_name_for(std::env::consts::OS, std::env::consts::ARCH)
        .unwrap_or_else(|| panic!("Unsupported platform: {} {}", std::env::consts::OS, std::env::consts::ARCH))
}

pub fn current_exe() -> std::io::Result<PathBuf> {
    std::env::current_exe()
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn test_windows() {
        assert_eq!(asset_name_for("windows", "x86_64"), Some("openmate.exe"));
        assert_eq!(asset_name_for("windows", "aarch64"), Some("openmate.exe"));
    }

    #[test]
    fn test_linux_x86_64() {
        assert_eq!(asset_name_for("linux", "x86_64"), Some("openmate-linux-x86_64"));
    }

    #[test]
    fn test_linux_arm64() {
        assert_eq!(asset_name_for("linux", "aarch64"), Some("openmate-linux-arm64"));
    }

    #[test]
    fn test_macos() {
        assert_eq!(asset_name_for("macos", "aarch64"), Some("openmate-darwin-arm64"));
    }

    #[test]
    fn test_unknown() {
        assert_eq!(asset_name_for("freebsd", "x86_64"), None);
    }
}
```

- [ ] **Step 2: 运行测试**

Run: `cargo test --lib update::platform`
Expected: 5 tests PASS

- [ ] **Step 3: Commit**

```bash
git add src/update/platform.rs
git commit -m "feat(update): platform.rs — 平台产物名映射"
```

---

## Task 3: script.rs — helper-script 生成

**Files:**
- Create: `opencode-bridge/src/update/script.rs`

- [ ] **Step 1: 写实现 + 测试**

```rust
pub fn generate_ps1(exe: &str, update: &str, pid: u32) -> String {
    format!(
        r#"$ErrorActionPreference = 'Stop'
$targetPid = {pid}
$new = '{update}'
$tgt = '{exe}'
$deadline = (Get-Date).AddSeconds(30)
while ((Get-Date) -lt $deadline -and (Get-Process -Id $targetPid -ErrorAction SilentlyContinue)) {{
    Start-Sleep -Milliseconds 500
}}
Copy-Item $tgt "$tgt.bak" -Force
try {{
    Move-Item $new $tgt -Force
}} catch {{
    Move-Item "$tgt.bak" $tgt -Force
    Start-Process $tgt -WindowStyle Hidden
    exit 1
}}
Start-Process $tgt -WindowStyle Hidden
Remove-Item $MyInvocation.MyCommand.Path -Force
"#,
        pid = pid,
        update = update.replace('\'', "''"),
        exe = exe.replace('\'', "''"),
    )
}

pub fn generate_sh(exe: &str, update: &str, pid: u32) -> String {
    format!(
        r#"#!/bin/bash
set -e
OLDPID={pid}
NEW='{update}'
TGT='{exe}'
for i in $(seq 1 60); do kill -0 $OLDPID 2>/dev/null || break; sleep 0.5; done
cp "$TGT" "$TGT.bak"
if ! mv "$NEW" "$TGT"; then
    mv "$TGT.bak" "$TGT"
    nohup "$TGT" >/dev/null 2>&1 &
    exit 1
fi
chmod +x "$TGT"
nohup "$TGT" >/dev/null 2>&1 &
rm -- "$0"
"#,
        pid = pid,
        update = update.replace('\'', "'\\''"),
        exe = exe.replace('\'', "'\\''"),
    )
}

pub fn generate(exe: &str, update: &str, pid: u32) -> String {
    if cfg!(windows) {
        generate_ps1(exe, update, pid)
    } else {
        generate_sh(exe, update, pid)
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn ps1_contains_key_steps() {
        let s = generate_ps1("/path/to/openmate.exe", "/tmp/openmate.update", 12345);
        assert!(s.contains("$targetPid = 12345"));
        assert!(s.contains("Get-Process"));
        assert!(s.contains(".bak"));
        assert!(s.contains("Move-Item"));
        assert!(s.contains("Start-Process"));
        assert!(s.contains("Remove-Item $MyInvocation.MyCommand.Path"));
    }

    #[test]
    fn ps1_has_rollback_on_move_failure() {
        let s = generate_ps1("/path/openmate.exe", "/tmp/update", 99);
        assert!(s.contains("catch"));
        assert!(s.contains(".bak"));
    }

    #[test]
    fn sh_contains_key_steps() {
        let s = generate_sh("/path/openmate", "/tmp/openmate.update", 12345);
        assert!(s.contains("OLDPID=12345"));
        assert!(s.contains("kill -0"));
        assert!(s.contains(".bak"));
        assert!(s.contains("mv"));
        assert!(s.contains("chmod +x"));
        assert!(s.contains("nohup"));
        assert!(s.contains("rm --"));
    }

    #[test]
    fn sh_has_rollback_on_move_failure() {
        let s = generate_sh("/path/openmate", "/tmp/update", 99);
        assert!(s.contains("if ! mv"));
        assert!(s.contains("mv \"$TGT.bak\""));
    }
}
```

- [ ] **Step 2: 运行测试**

Run: `cargo test --lib update::script`
Expected: 4 tests PASS

- [ ] **Step 3: Commit**

```bash
git add src/update/script.rs
git commit -m "feat(update): script.rs — 跨平台 helper-script 生成（含回滚）"
```

---

## Task 4: manager.rs + mod.rs — UpgradeManager 主体

**Files:**
- Create: `opencode-bridge/src/update/manager.rs`
- Create: `opencode-bridge/src/update/mod.rs`

- [ ] **Step 1: 写 mod.rs**

```rust
pub mod manager;
pub mod platform;
pub mod script;
pub mod version;

pub use manager::{UpgradeManager, UpgradePhase, UpgradeState};
```

- [ ] **Step 2: 写 manager.rs**

```rust
use std::sync::atomic::{AtomicBool, Ordering};
use std::sync::Arc;

use futures::StreamExt;
use tokio::io::AsyncWriteExt;
use tokio::sync::Mutex;

use super::platform;
use super::script;
use super::version;

const GITHUB_BASE: &str = "https://github.com/bob-dawson/openmate/releases/download";

#[derive(Clone, Default)]
pub struct UpgradeState {
    pub phase: UpgradePhase,
    pub progress: u64,
    pub version: Option<String>,
    pub error: Option<String>,
}

#[derive(Clone, Default, PartialEq, Debug)]
pub enum UpgradePhase {
    #[default]
    Idle,
    Downloading,
    Downloaded,
    Failed,
}

impl UpgradePhase {
    pub fn as_str(&self) -> &'static str {
        match self {
            Self::Idle => "idle",
            Self::Downloading => "downloading",
            Self::Downloaded => "downloaded",
            Self::Failed => "failed",
        }
    }
}

pub struct UpgradeManager {
    state: Arc<Mutex<UpgradeState>>,
    in_progress: Arc<AtomicBool>,
    shutdown_tx: tokio::sync::watch::Sender<bool>,
}

impl UpgradeManager {
    pub fn new(shutdown_tx: tokio::sync::watch::Sender<bool>) -> Self {
        Self {
            state: Arc::new(Mutex::new(UpgradeState::default())),
            in_progress: Arc::new(AtomicBool::new(false)),
            shutdown_tx,
        }
    }

    pub async fn get_status(&self) -> UpgradeState {
        self.state.lock().await.clone()
    }

    pub fn start_download(&self) -> bool {
        if self.in_progress.swap(true, Ordering::Relaxed) {
            return false;
        }
        let state = self.state.clone();
        let in_progress = self.in_progress.clone();
        tokio::spawn(async move {
            let result = do_download(state.clone()).await;
            {
                let mut s = state.lock().await;
                match result {
                    Ok(ver) => {
                        s.phase = UpgradePhase::Downloaded;
                        s.version = Some(ver);
                        s.progress = 100;
                        s.error = None;
                    }
                    Err(e) => {
                        tracing::error!("Bridge upgrade download failed: {}", e);
                        s.phase = UpgradePhase::Failed;
                        s.error = Some(e);
                    }
                }
            }
            in_progress.store(false, Ordering::Relaxed);
        });
        true
    }

    pub async fn apply(&self) -> Result<(), String> {
        if std::env::var("OPENMATE_SERVICE_MODE").is_ok() {
            return Err("Running as service — please update manually".to_string());
        }
        {
            let s = self.state.lock().await;
            if s.phase != UpgradePhase::Downloaded {
                return Err("No downloaded update to apply".to_string());
            }
        }

        let exe_path = platform::current_exe().map_err(|e| format!("Cannot find current exe: {}", e))?;
        let update_path = std::env::temp_dir().join("openmate.update");
        if !update_path.exists() {
            return Err("Update file missing".to_string());
        }
        let pid = std::process::id();

        let script_content = script::generate(
            &exe_path.to_string_lossy(),
            &update_path.to_string_lossy(),
            pid,
        );

        let ext = if cfg!(windows) { "ps1" } else { "sh" };
        let script_path = std::env::temp_dir().join(format!("openmate-update.{}", ext));
        std::fs::write(&script_path, &script_content).map_err(|e| format!("Failed to write script: {}", e))?;

        #[cfg(unix)]
        {
            use std::os::unix::fs::PermissionsExt;
            std::fs::set_permissions(&script_path, std::fs::Permissions::from_mode(0o755))
                .ok();
        }

        #[cfg(windows)]
        {
            use std::os::windows::process::CommandExt;
            let mut cmd = std::process::Command::new("powershell");
            cmd.creation_flags(0x08000000) // CREATE_NO_WINDOW
                .arg("-ExecutionPolicy")
                .arg("Bypass")
                .arg("-File")
                .arg(&script_path);
            cmd.spawn().map_err(|e| format!("Failed to spawn update script: {}", e))?;
        }

        #[cfg(not(windows))]
        {
            use std::os::unix::process::CommandExt;
            let mut cmd = std::process::Command::new(&script_path);
            unsafe {
                cmd.pre_exec(|| {
                    libc::setsid();
                    Ok(())
                });
            }
            cmd.spawn().map_err(|e| format!("Failed to spawn update script: {}", e))?;
        }

        tracing::info!("Update script spawned (pid={}), sending shutdown signal", pid);
        let _ = self.shutdown_tx.send(true);
        Ok(())
    }
}

async fn do_download(state: Arc<Mutex<UpgradeState>>) -> Result<String, String> {
    {
        let mut s = state.lock().await;
        s.phase = UpgradePhase::Downloading;
        s.progress = 0;
        s.error = None;
    }

    let manifest = version::fetch_version_manifest()
        .await
        .ok_or("Failed to fetch version.json from all sources")?;
    let bridge = manifest
        .bridge
        .ok_or("No bridge version in version.json")?;

    let current = env!("CARGO_PKG_VERSION");
    if !version::is_newer(&bridge.version, current) {
        return Err(format!("Already up to date ({})", current));
    }

    let asset = platform::asset_name();
    let url = format!("{}/{}/{}", GITHUB_BASE, bridge.tag, asset);

    tracing::info!("Downloading bridge update from {}", url);

    let client = reqwest::Client::builder()
        .timeout(std::time::Duration::from_secs(300))
        .build()
        .map_err(|e| format!("HTTP client error: {}", e))?;

    let resp = client
        .get(&url)
        .send()
        .await
        .map_err(|e| format!("Download request failed: {}", e))?;

    if !resp.status().is_success() {
        return Err(format!("Download HTTP {}", resp.status()));
    }

    let total = resp.content_length().unwrap_or(0);
    let mut stream = resp.bytes_stream();

    let dest = std::env::temp_dir().join("openmate.update");
    let mut file = tokio::fs::File::create(&dest)
        .await
        .map_err(|e| format!("Cannot create temp file: {}", e))?;

    let mut downloaded: u64 = 0;
    while let Some(chunk_result) = stream.next().await {
        let chunk = chunk_result.map_err(|e| format!("Stream error: {}", e))?;
        file.write_all(&chunk)
            .await
            .map_err(|e| format!("Write error: {}", e))?;
        downloaded += chunk.len() as u64;
        if total > 0 {
            let pct = std::cmp::min((downloaded * 100) / total, 100);
            let mut s = state.lock().await;
            s.progress = pct;
        }
    }
    file.flush().await.map_err(|e| format!("Flush error: {}", e))?;

    tracing::info!("Downloaded {} bytes to {}", downloaded, dest.display());
    Ok(bridge.version)
}
```

> 注意 `libc` crate：Linux 的 `setsid()` 需要 `libc`。在 Cargo.toml 加 `libc = "0.2"` 到 `[target.'cfg(unix)'.dependencies]`。或者简化：不在 pre_exec 里调用 setsid，只用 `Command::new(&script_path).spawn()` —— 不带 setsid 脚本也能运行（只是不创建新 session）。**推荐简化版**：去掉 pre_exec/setsid，直接 spawn。避免引入 libc 依赖。

**简化后的 Unix spawn 代码**（替换上面 `#[cfg(not(windows))]` 块）：

```rust
        #[cfg(not(windows))]
        {
            std::process::Command::new(&script_path)
                .spawn()
                .map_err(|e| format!("Failed to spawn update script: {}", e))?;
        }
```

- [ ] **Step 3: 验证编译**

Run: `cargo build`
Expected: 编译通过（`update` 模块尚未注册到 lib.rs，此 step 仅验证模块内编译。如报 "unresolved module"，先做 Task 5 的 lib.rs 注册）

> 如果编译报找不到 `update` 模块，先在 `lib.rs` 加 `pub mod update;` 再编译。

- [ ] **Step 4: Commit**

```bash
git add src/update/manager.rs src/update/mod.rs
git commit -m "feat(update): manager.rs — UpgradeManager（download + apply + 状态管理）"
```

---

## Task 5: API 路由 + AppState + lib.rs 集成

**Files:**
- Modify: `opencode-bridge/src/lib.rs`
- Modify: `opencode-bridge/src/state.rs`
- Modify: `opencode-bridge/src/bridge/router.rs`
- Modify: `opencode-bridge/src/server.rs`

- [ ] **Step 1: lib.rs 注册 update 模块**

在 `lib.rs` 的 `pub mod` 列表中（`pub mod ui;` 之后）追加：

```rust
pub mod update;
```

- [ ] **Step 2: state.rs 加 upgrade_manager 字段**

在 `AppStateInner` struct 中（`pub shutdown_tx` 之后）加：

```rust
    pub upgrade_manager: crate::update::UpgradeManager,
```

在 `create_app_state_with_db_event_source_and_actual_port()` 中，构造 `shutdown_tx` 之后、构造 `Arc::new(AppStateInner {` 之前，加：

```rust
    let upgrade_manager = crate::update::UpgradeManager::new(
        shutdown_tx.as_ref().map_or_else(
            || tokio::sync::watch::channel(false).0,
            |(tx, _)| tx.clone(),
        ),
    );
```

> 注意：当前代码中 `shutdown_tx` 的构造在 `Arc::new(AppStateInner { ... })` 内联完成（`shutdown_tx: shutdown.map_or_else(|| ..., |(tx, _)| tx)`）。需要把 shutdown_tx 提取为变量（`let shutdown_tx = ...`），然后用 `shutdown_tx.clone()` 传给 UpgradeManager，`shutdown_tx` 本身（move）传给 AppStateInner。

具体改法——将 state.rs 中现有的：

```rust
    Arc::new(AppStateInner {
        // ... other fields ...
        shutdown_tx: shutdown.map_or_else(
            || tokio::sync::watch::channel(false).0,
            |(tx, _)| tx,
        ),
    })
```

改为：

```rust
    let shutdown_tx = shutdown.map_or_else(
        || tokio::sync::watch::channel(false).0,
        |(tx, _)| tx,
    );
    let upgrade_manager = crate::update::UpgradeManager::new(shutdown_tx.clone());

    Arc::new(AppStateInner {
        // ... other fields ...
        upgrade_manager,
        shutdown_tx,
    })
```

- [ ] **Step 3: bridge/router.rs 加 3 个 handler**

在 `bridge/router.rs` 末尾（`is_newer_version` 函数之前，`#[cfg(test)]` 之前）追加：

```rust
pub async fn bridge_upgrade_download(
    State(state): State<AppState>,
) -> impl IntoResponse {
    if state.upgrade_manager.start_download() {
        Json(json!({ "status": "started" }))
    } else {
        Json(json!({ "status": "already_in_progress" }))
    }
}

pub async fn bridge_upgrade_status(
    State(state): State<AppState>,
) -> impl IntoResponse {
    let s = state.upgrade_manager.get_status().await;
    Json(json!({
        "state": s.phase.as_str(),
        "progress": s.progress,
        "version": s.version,
        "error": s.error,
    }))
}

pub async fn bridge_upgrade_apply(
    State(state): State<AppState>,
) -> impl IntoResponse {
    match state.upgrade_manager.apply().await {
        Ok(()) => Json(json!({ "status": "applying" })),
        Err(e) => Json(json!({ "status": "error", "error": e })),
    }
}
```

- [ ] **Step 4: server.rs 注册 3 条 route**

在 `server.rs` 的 Router 链中，`.route("/api/bridge/opencode/upgrade", post(bridge::router::upgrade_opencode))` 之后、`.route("/api/bridge/pair/request", ...)` 之前，追加：

```rust
        .route(
            "/api/bridge/upgrade/download",
            post(bridge::router::bridge_upgrade_download),
        )
        .route(
            "/api/bridge/upgrade/status",
            get(bridge::router::bridge_upgrade_status),
        )
        .route(
            "/api/bridge/upgrade/apply",
            post(bridge::router::bridge_upgrade_apply),
        )
```

- [ ] **Step 5: 验证编译 + 既有测试通过**

Run: `cargo build && cargo test`
Expected: BUILD SUCCESSFUL + 全部既有测试 PASS

- [ ] **Step 6: Commit**

```bash
git add src/lib.rs src/state.rs src/bridge/router.rs src/server.rs
git commit -m "feat(bridge): 集成 upgrade API 路由 + AppState upgrade_manager"
```

---

## Task 6: Android OpencodeApiClient + DTO

**Files:**
- Modify: `android/core/network/.../OpencodeApiClient.kt`（加 3 个方法）
- Modify: `android/core/network/.../dto/BridgeDto.kt`（加 BridgeUpgradeStatusDto）
- Modify: `android/core/network/.../VersionClient.kt`（加 fetchBridgeVersion）

- [ ] **Step 1: BridgeDto.kt 加 DTO**

在 `BridgeDto.kt` 末尾追加：

```kotlin
@Serializable
data class BridgeUpgradeStatusDto(
    val state: String = "idle",
    val progress: Long = 0,
    val version: String? = null,
    val error: String? = null,
)
```

- [ ] **Step 2: OpencodeApiClient 加 3 个方法**

在 `OpencodeApiClient.kt`（`bridgeOpencodeRestart()` 之后，约 line 602 附近）追加：

```kotlin
    suspend fun bridgeUpgradeDownload(): JsonObject {
        return post("/api/bridge/upgrade/download", JsonObject(emptyMap()))
    }

    suspend fun bridgeUpgradeStatus(): BridgeUpgradeStatusDto {
        return get("/api/bridge/upgrade/status")
    }

    suspend fun bridgeUpgradeApply(): JsonObject {
        return post("/api/bridge/upgrade/apply", JsonObject(emptyMap()))
    }
```

- [ ] **Step 3: VersionClient 加 fetchBridgeVersion**

在 `VersionClient.kt`（`fetchAndroidVersion()` 之后）追加：

```kotlin
    suspend fun fetchBridgeVersion(): ModuleVersion? = withContext(Dispatchers.IO) {
        fetchManifest(jsdelivrVersionUrl)?.bridge ?: fetchManifest(rawVersionUrl)?.bridge
    }
```

- [ ] **Step 4: 验证编译**

Run: 通过 GradleMcp `{"args":[":core:network:assembleDebug"],"cwd":"D:\\openmate\\android"}`
Expected: BUILD SUCCESSFUL

- [ ] **Step 5: Commit**

```bash
cd D:\openmate
git add android/core/network/src/main/java/com/openmate/core/network/OpencodeApiClient.kt android/core/network/src/main/java/com/openmate/core/network/dto/BridgeDto.kt android/core/network/src/main/java/com/openmate/core/network/VersionClient.kt
git commit -m "feat(network): 添加 Bridge 升级 API 方法 + BridgeUpgradeStatusDto + fetchBridgeVersion"
```

---

## Task 7: Android SettingsViewModel — Bridge 更新逻辑

**Files:**
- Modify: `android/feature/settings/.../SettingsViewModel.kt`

> 前置：Plan 1 已在 SettingsViewModel 中加入 `versionClient` 构造参数、`appUpdateInfo`/`appDownloadState` StateFlow、`checkAppUpdate()`/`downloadAndInstallApp()` 方法。本 task 在此基础上追加 Bridge 相关逻辑。

- [ ] **Step 1: 新增 Bridge 更新状态**

在 SettingsViewModel class 内（`appDownloadState` StateFlow 之后）追加：

```kotlin
    data class BridgeUpdateInfo(
        val currentVersion: String,
        val latestVersion: String?,
        val hasUpdate: Boolean,
    )

    data class BridgeUpgradeState(
        val isDownloading: Boolean = false,
        val progress: Int = 0,
        val isApplying: Boolean = false,
        val error: String? = null,
    )

    private val _bridgeUpdateInfo = MutableStateFlow<BridgeUpdateInfo?>(null)
    val bridgeUpdateInfo: StateFlow<BridgeUpdateInfo?> = _bridgeUpdateInfo.asStateFlow()

    private val _bridgeUpgradeState = MutableStateFlow(BridgeUpgradeState())
    val bridgeUpgradeState: StateFlow<BridgeUpgradeState> = _bridgeUpgradeState.asStateFlow()

    private var latestBridgeVersion: ModuleVersion? = null
```

新增 import：

```kotlin
import kotlinx.coroutines.delay
```

- [ ] **Step 2: 实现 checkBridgeUpdate()**

在 `checkAppUpdate()` 之后追加：

```kotlin
    fun checkBridgeUpdate() {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val latest = versionClient.fetchBridgeVersion()
                latestBridgeVersion = latest
                val current = bridgeStatusCache?.bridge?.version ?: "0.0.0"
                val hasUpdate = latest != null && isNewer(latest.version, current)
                _bridgeUpdateInfo.value = BridgeUpdateInfo(
                    currentVersion = current,
                    latestVersion = latest?.version,
                    hasUpdate = hasUpdate,
                )
            } catch (_: Exception) {
            }
        }
    }
```

> 注意：`bridgeStatusCache` 是 ViewModel 中已有的 `BridgeStatusResponse?` 缓存（来自 `checkVersion()` 调用 `bridgeStatus()`）。如变量名不同，请用实际变量名。Bridge 当前版本从 `bridgeStatus().bridge.version` 获取。

在 `init {}` 块追加 `checkBridgeUpdate()`：

```kotlin
    init {
        loadActiveProfile()
        refreshCacheInfo()
        checkVersion()
        checkAppUpdate()
        checkBridgeUpdate()
    }
```

- [ ] **Step 3: 实现 upgradeBridge()**

```kotlin
    fun upgradeBridge() {
        if (_bridgeUpgradeState.value.isDownloading || _bridgeUpgradeState.value.isApplying) return
        val info = _bridgeUpdateInfo.value ?: return
        if (!info.hasUpdate) return

        viewModelScope.launch(Dispatchers.IO) {
            _bridgeUpgradeState.value = BridgeUpgradeState(isDownloading = true)
            try {
                apiClient.bridgeUpgradeDownload()

                val maxPolls = 120
                var downloaded = false
                for (i in 0 until maxPolls) {
                    delay(3000)
                    val status = apiClient.bridgeUpgradeStatus()
                    when (status.state) {
                        "downloading" -> {
                            _bridgeUpgradeState.value = BridgeUpgradeState(
                                isDownloading = true,
                                progress = status.progress.toInt(),
                            )
                        }
                        "downloaded" -> {
                            downloaded = true
                            break
                        }
                        "failed" -> {
                            _bridgeUpgradeState.value = BridgeUpgradeState(
                                error = status.error ?: "下载失败，请前往 GitHub Releases 手动更新",
                            )
                            return@launch
                        }
                    }
                }

                if (!downloaded) {
                    _bridgeUpgradeState.value = BridgeUpgradeState(
                        error = "下载超时，请前往 GitHub Releases 手动更新",
                    )
                    return@launch
                }

                _bridgeUpgradeState.value = BridgeUpgradeState(isApplying = true)
                apiClient.bridgeUpgradeApply()

            } catch (e: Exception) {
                _bridgeUpgradeState.value = BridgeUpgradeState(
                    error = e.message ?: "升级失败",
                )
            }
        }
    }

    fun clearBridgeUpgradeError() {
        _bridgeUpgradeState.value = BridgeUpgradeState()
    }
```

- [ ] **Step 4: 验证编译**

Run: 通过 GradleMcp `{"args":[":feature:settings:assembleDebug"],"cwd":"D:\\openmate\\android"}`
Expected: BUILD SUCCESSFUL

- [ ] **Step 5: Commit**

```bash
git add android/feature/settings/src/main/java/com/openmate/feature/settings/SettingsViewModel.kt
git commit -m "feat(settings): SettingsViewModel 添加 Bridge 更新检查与升级逻辑"
```

---

## Task 8: Android 设置页 — Bridge 更新卡片

**Files:**
- Modify: `android/feature/session/.../WorkspaceListScreen.kt`（SettingsContent，App 更新卡片之后）
- Modify: `android/feature/session/.../strings.xml`

- [ ] **Step 1: 新增字符串资源**

在 `strings.xml` 追加（App 更新字符串之后）：

```xml
    <string name="bridge_update">Bridge Update</string>
    <string name="bridge_upgrade_now">Upgrade Now</string>
    <string name="bridge_downloading">Downloading Bridge…</string>
    <string name="bridge_applying">Applying update, Bridge will restart…</string>
    <string name="bridge_upgrade_failed">Upgrade failed. Please update manually from GitHub Releases.</string>
```

- [ ] **Step 2: 在 SettingsContent 新增 Bridge 更新卡片**

在 App 更新卡片的 `item { ... }` 之后，插入：

```kotlin
        item {
            val bridgeUpdateInfo by viewModel.bridgeUpdateInfo.collectAsState()
            val bridgeUpgradeState by viewModel.bridgeUpgradeState.collectAsState()

            SectionHeader(title = stringResource(R.string.bridge_update))
            SettingsCard {
                SettingsRow(
                    title = stringResource(R.string.app_latest_version),
                    subtitle = null,
                    trailing = {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                text = when {
                                    bridgeUpgradeState.isDownloading -> stringResource(R.string.bridge_downloading)
                                    bridgeUpgradeState.isApplying -> stringResource(R.string.bridge_applying)
                                    bridgeUpdateInfo?.latestVersion != null -> "v${bridgeUpdateInfo?.latestVersion}"
                                    else -> stringResource(R.string.check_failed_retry)
                                },
                                style = MaterialTheme.typography.labelMedium,
                                color = if (bridgeUpdateInfo?.hasUpdate == true)
                                    MaterialTheme.colorScheme.primary
                                else MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = stringResource(R.string.check_for_updates),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.clickable(
                                    enabled = !bridgeUpgradeState.isDownloading && !bridgeUpgradeState.isApplying,
                                    onClick = { viewModel.checkBridgeUpdate() }
                                ),
                            )
                        }
                    },
                )
                if (bridgeUpgradeState.isDownloading) {
                    SettingsRow(
                        title = stringResource(R.string.bridge_downloading),
                        subtitle = "${bridgeUpgradeState.progress}%",
                        showDivider = false,
                        trailing = {
                            CircularProgressIndicator(
                                progress = { bridgeUpgradeState.progress / 100f },
                                modifier = Modifier.size(14.dp),
                                strokeWidth = 2.dp,
                            )
                        },
                    )
                } else if (bridgeUpgradeState.isApplying) {
                    SettingsRow(
                        title = stringResource(R.string.bridge_applying),
                        subtitle = null,
                        showDivider = false,
                        trailing = {
                            CircularProgressIndicator(modifier = Modifier.size(14.dp), strokeWidth = 2.dp)
                        },
                    )
                } else if (bridgeUpgradeState.error != null) {
                    SettingsRow(
                        title = stringResource(R.string.bridge_upgrade_failed),
                        subtitle = bridgeUpgradeState.error,
                        showDivider = false,
                        modifier = Modifier.clickable { viewModel.clearBridgeUpgradeError() },
                        trailing = {
                            Text(
                                text = stringResource(R.string.check_for_updates),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.primary,
                            )
                        },
                    )
                } else if (bridgeUpdateInfo?.hasUpdate == true) {
                    SettingsRow(
                        title = stringResource(R.string.bridge_upgrade_now),
                        subtitle = null,
                        showDivider = false,
                        modifier = Modifier.clickable { viewModel.upgradeBridge() },
                        trailing = {
                            Text(
                                text = "\u203A",
                                style = MaterialTheme.typography.bodyLarge,
                                color = MaterialTheme.colorScheme.primary,
                            )
                        },
                    )
                } else if (bridgeUpdateInfo?.hasUpdate == false) {
                    SettingsRow(
                        title = stringResource(R.string.app_current_version),
                        subtitle = null,
                        showDivider = false,
                        trailing = {
                            Text(
                                text = "v${bridgeUpdateInfo?.currentVersion ?: "?"}",
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        },
                    )
                }
            }
        }
```

- [ ] **Step 3: 验证编译**

Run: 通过 GradleMcp `{"args":[":feature:session:assembleDebug"],"cwd":"D:\\openmate\\android"}`
Expected: BUILD SUCCESSFUL

- [ ] **Step 4: Commit**

```bash
git add android/feature/session/src/main/java/com/openmate/feature/session/WorkspaceListScreen.kt android/feature/session/src/main/res/values/strings.xml
git commit -m "feat(session): 设置页添加 Bridge 更新卡片"
```

---

## Task 9: 集成验证

- [ ] **Step 1: Bridge 编译 + 全部测试**

Run: `cd opencode-bridge && cargo build && cargo test`
Expected: BUILD SUCCESSFUL + 全部测试 PASS（含 update 模块测试）

- [ ] **Step 2: Android 整体编译**

Run: 通过 GradleMcp `{"args":[":app:assembleDebug"],"cwd":"D:\\openmate\\android"}`
Expected: BUILD SUCCESSFUL

- [ ] **Step 3: 手动功能验证（Bridge 端）**

启动 Bridge → `curl http://127.0.0.1:4097/api/bridge/upgrade/status` 应返回 `{"state":"idle","progress":0,...}`。
POST `/api/bridge/upgrade/download` → 触发下载 → 轮询 status 看到 downloading→downloaded。
POST `/api/bridge/upgrade/apply` → Bridge 退出 + 脚本替换 + 重启。

> 完整下载验证需要 version.json 中 bridge 版本高于当前 Bridge 版本。发版前手动更新 version.json 的 bridge 条目即可测试。

- [ ] **Step 4: 手动功能验证（Android 端）**

安装 APK → 打开设置页 → 应看到 Bridge 更新卡片。Bridge 版本相同 → 显示 "Up to date"。如 bridge 版本更高 → 显示 "Upgrade Now" 按钮。

- [ ] **Step 5: 最终 Commit（如有调整）**

```bash
git add -A
git commit -m "chore: Bridge 更新 Plan 2 集成验证通过" --allow-empty
```

---

## Self-Review 检查清单（实施完成后对照）

1. **Spec 覆盖**：version.rs 双源查询（Task 1）✅、platform 映射（Task 2）✅、helper-script 含回滚（Task 3）✅、UpgradeManager download+apply+状态（Task 4）✅、3 API + AppState（Task 5）✅、Android API+DTO（Task 6）✅、ViewModel 升级流（Task 7）✅、UI 卡片（Task 8）✅
2. **Rust 编译**：`update` 模块注册到 `lib.rs`，AppState 含 `upgrade_manager`，shutdown_tx 提取为变量再 clone ✅
3. **状态流转**：Idle → Downloading → Downloaded → (apply) → 进程退出 → 重连后版本对比 → 成功/失败 ✅
4. **回滚**：helper-script Move/mv 失败时从 .bak 恢复旧版本并启动（Task 3 测试验证）✅
5. **服务模式**：apply 检查 `OPENMATE_SERVICE_MODE` env var，返回错误提示手动更新 ✅
6. **防重入**：AtomicBool 覆盖 download（start_download 返回 false 表示已在进行）；Android UI 按钮禁用 ✅
7. **测试**：version.rs（mockito jsDelivr/raw + JSON 解析 + is_newer）✅、platform.rs（纯函数）✅、script.rs（关键步骤 + 回滚）✅
8. **Plan 1 依赖**：VersionClient 加 fetchBridgeVersion()（Task 6 Step 3），不影响 Plan 1 的 fetchAndroidVersion() ✅

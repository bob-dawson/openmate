use std::sync::Arc;
use std::sync::atomic::{AtomicBool, Ordering};
use tokio::sync::RwLock;

use crate::state::OpencodeStatus;

#[derive(Clone)]
pub struct OpencodeManager {
    opencode_url: String,
    status: Arc<RwLock<OpencodeStatus>>,
    binary: Arc<String>,
    directory: Arc<String>,
    auto_restart: Arc<bool>,
    run_as_user: Arc<String>,
    opencode_version: Arc<RwLock<Option<String>>>,
    upgrade_in_progress: Arc<AtomicBool>,
    last_upgrade: Arc<RwLock<Option<UpgradeResult>>>,
    upgrade_registry: Arc<String>,
    pid: Arc<RwLock<Option<u32>>>,
}

#[derive(Debug, Clone, serde::Serialize)]
pub struct UpgradeResult {
    pub success: bool,
    #[serde(rename = "previousVersion")]
    pub previous_version: Option<String>,
    #[serde(rename = "newVersion")]
    pub new_version: Option<String>,
    pub error: Option<String>,
    pub recovered: Option<bool>,
    #[serde(rename = "currentVersion")]
    pub current_version: Option<String>,
}

#[allow(dead_code)]
impl OpencodeManager {
    pub fn new(opencode_url: String) -> Self {
        OpencodeManager {
            opencode_url,
            status: Arc::new(RwLock::new(OpencodeStatus::Stopped)),
            binary: Arc::new("opencode".to_string()),
            directory: Arc::new(String::new()),
            auto_restart: Arc::new(true),
            run_as_user: Arc::new(String::new()),
            opencode_version: Arc::new(RwLock::new(None)),
            upgrade_in_progress: Arc::new(AtomicBool::new(false)),
            last_upgrade: Arc::new(RwLock::new(None)),
            upgrade_registry: Arc::new(String::new()),
            pid: Arc::new(RwLock::new(None)),
        }
    }

    pub fn with_config(
        opencode_url: String,
        binary: String,
        directory: String,
        auto_restart: bool,
        run_as_user: String,
        upgrade_registry: String,
    ) -> Self {
        OpencodeManager {
            opencode_url,
            status: Arc::new(RwLock::new(OpencodeStatus::Stopped)),
            binary: Arc::new(binary),
            directory: Arc::new(directory),
            auto_restart: Arc::new(auto_restart),
            run_as_user: Arc::new(run_as_user),
            opencode_version: Arc::new(RwLock::new(None)),
            upgrade_in_progress: Arc::new(AtomicBool::new(false)),
            last_upgrade: Arc::new(RwLock::new(None)),
            upgrade_registry: Arc::new(upgrade_registry),
            pid: Arc::new(RwLock::new(None)),
        }
    }

    pub async fn get_status(&self) -> OpencodeStatus {
        *self.status.read().await
    }

    pub async fn is_running(&self) -> bool {
        *self.status.read().await == OpencodeStatus::Running
    }

    pub async fn check_health(&self) -> bool {
        let base = crate::config::read_service_url().unwrap_or_else(|| self.opencode_url.clone());
        match fetch_health_version(&base).await {
            Some(version) => {
                if !version.is_empty() {
                    *self.opencode_version.write().await = Some(version);
                }
                true
            }
            None => false,
        }
    }

    pub async fn start(&self) -> Result<(), String> {
        let mut status = self.status.write().await;
        match *status {
            OpencodeStatus::Running => return Err("opencode is already running".to_string()),
            OpencodeStatus::Starting => return Err("opencode is already starting".to_string()),
            OpencodeStatus::Stopping => return Err("opencode is stopping".to_string()),
            _ => {}
        }
        *status = OpencodeStatus::Starting;
        drop(status);

        let binary = self.binary.clone();

        match run_service_cmd(&binary, &["service", "start"]).await {
            Ok(output) => tracing::info!("service start: {}", output.trim()),
            Err(e) => {
                let mut s = self.status.write().await;
                *s = OpencodeStatus::Crashed;
                return Err(format!("Failed to start opencode service: {}", e));
            }
        }

        let url = self.opencode_url.clone();
        let status_arc = self.status.clone();
        let version_arc = self.opencode_version.clone();
        let auto_restart = *self.auto_restart;
        let binary_r = binary.clone();

        tokio::spawn(async move {
            let mut retry_count = 0u32;
            let max_retries = 30u32;

            while retry_count < max_retries {
                tokio::time::sleep(tokio::time::Duration::from_secs(2)).await;

                if let Some(version) = fetch_health_version(&url).await {
                    let mut s = status_arc.write().await;
                    *s = OpencodeStatus::Running;
                    if !version.is_empty() {
                        *version_arc.write().await = Some(version);
                    }
                    tracing::info!("opencode service is ready");
                    break;
                }
                retry_count += 1;
                tracing::debug!("Waiting for opencode service... ({}/{})", retry_count, max_retries);
            }

            let current = *status_arc.read().await;
            if current == OpencodeStatus::Stopping || current == OpencodeStatus::Stopped {
                return;
            }
            if current != OpencodeStatus::Running {
                let mut s = status_arc.write().await;
                *s = OpencodeStatus::Crashed;
                tracing::error!("opencode service failed to start within timeout");
                return;
            }

            // Monitor health periodically — service daemon won't give us child exit
            let mut health_failures = 0u32;
            loop {
                tokio::time::sleep(tokio::time::Duration::from_secs(10)).await;

                let cur = *status_arc.read().await;
                if cur == OpencodeStatus::Stopping || cur == OpencodeStatus::Stopped {
                    return;
                }

                if let Some(version) = fetch_health_version(&url).await {
                    health_failures = 0;
                    if !version.is_empty() {
                        let mut cached = version_arc.write().await;
                        if cached.as_deref() != Some(version.as_str()) {
                            *cached = Some(version);
                        }
                    }
                } else {
                    health_failures += 1;
                    tracing::warn!("opencode health check failed ({}/3)", health_failures);
                    if health_failures >= 3 {
                        let mut s = status_arc.write().await;
                        *s = OpencodeStatus::Crashed;
                        drop(s);

                        if auto_restart {
                            tracing::info!("Auto-restarting opencode service...");
                            let status_arc_r = status_arc.clone();
                            let version_arc_r = version_arc.clone();
                            let url_r = url.clone();
                            let binary_rr = binary_r.clone();
                            tokio::spawn(async move {
                                tokio::time::sleep(tokio::time::Duration::from_secs(3)).await;
                                restart_service_loop(&binary_rr, &url_r, &status_arc_r, &version_arc_r).await;
                            });
                        }
                        return;
                    }
                }
            }
        });

        Ok(())
    }

    pub async fn stop(&self) -> Result<(), String> {
        let mut status = self.status.write().await;
        match *status {
            OpencodeStatus::Stopped => return Err("opencode is already stopped".to_string()),
            OpencodeStatus::Stopping => return Err("opencode is already stopping".to_string()),
            _ => {}
        }
        *status = OpencodeStatus::Stopping;
        drop(status);

        let binary = self.binary.clone();
        match run_service_cmd(&binary, &["service", "stop"]).await {
            Ok(output) => tracing::info!("service stop: {}", output.trim()),
            Err(e) => tracing::warn!("service stop failed: {}", e),
        }

        let mut s = self.status.write().await;
        *s = OpencodeStatus::Stopped;
        tracing::info!("opencode stopped");
        Ok(())
    }

    pub async fn restart(&self) -> Result<(), String> {
        if self.is_running().await {
            self.stop().await?;
        }
        self.start().await
    }

    pub async fn set_status(&self, new_status: OpencodeStatus) {
        let mut status = self.status.write().await;
        *status = new_status;
    }

    pub async fn get_cached_version(&self) -> Option<String> {
        self.opencode_version.read().await.clone()
    }

    pub async fn get_latest_version(&self) -> Result<String, String> {
        match self.update_api_version().await {
            Ok(v) => Ok(v),
            Err(e1) => match self.npm_view_version().await {
                Ok(v) => Ok(v),
                Err(e2) => self
                    .registry_fetch_version()
                    .await
                    .map_err(|e3| format!("{}; {}; {}", e1, e2, e3)),
            },
        }
    }

    /// 官方更新接口：`https://opencode.ai/update/api/{channel}/{artifact}/{distribution}`
    async fn update_api_version(&self) -> Result<String, String> {
        let current = self.get_cached_version().await.unwrap_or_default();
        let url = format!(
            "https://opencode.ai/update/api/latest/cli/npm?current={}",
            current
        );
        let resp = tokio::time::timeout(std::time::Duration::from_secs(15), reqwest::get(&url))
            .await
            .map_err(|_| "update api timed out (15s)".to_string())?
            .map_err(|e| format!("update api failed: {}", e))?;
        if !resp.status().is_success() {
            return Err(format!("update api returned status {}", resp.status()));
        }
        let body: serde_json::Value = resp
            .json()
            .await
            .map_err(|e| format!("update api parse error: {}", e))?;
        body.get("version")
            .and_then(|v| v.as_str())
            .map(|s| s.to_string())
            .ok_or_else(|| "update api response missing version field".to_string())
    }

    async fn npm_view_version(&self) -> Result<String, String> {
        let output = tokio::time::timeout(
            std::time::Duration::from_secs(30),
            tokio::process::Command::new("npm")
                .args(["view", "@opencode/cli", "version"])
                .output(),
        )
        .await
        .map_err(|_| "npm view timed out (30s)".to_string())?
        .map_err(|e| format!("Failed to run npm view: {}", e))?;

        if !output.status.success() {
            return Err(format!(
                "npm view failed: {}",
                String::from_utf8_lossy(&output.stderr)
            ));
        }

        let version = String::from_utf8_lossy(&output.stdout).trim().to_string();
        if version.is_empty() {
            return Err("npm view returned empty version".to_string());
        }
        Ok(version)
    }

    async fn registry_fetch_version(&self) -> Result<String, String> {
        let url = "https://registry.npmjs.org/@opencode%2Fcli/latest";
        let resp = tokio::time::timeout(
            std::time::Duration::from_secs(15),
            reqwest::get(url),
        )
        .await
        .map_err(|_| "registry fetch timed out (15s)".to_string())?
        .map_err(|e| format!("registry fetch failed: {}", e))?;

        if !resp.status().is_success() {
            return Err(format!("registry returned status {}", resp.status()));
        }

        let body: serde_json::Value = resp
            .json()
            .await
            .map_err(|e| format!("registry parse error: {}", e))?;

        body.get("version")
            .and_then(|v| v.as_str())
            .map(|s| s.to_string())
            .ok_or_else(|| "registry response missing version field".to_string())
    }

    pub fn is_upgrade_in_progress(&self) -> bool {
        self.upgrade_in_progress.load(Ordering::Relaxed)
    }

    pub async fn last_upgrade_result(&self) -> Option<UpgradeResult> {
        self.last_upgrade.read().await.clone()
    }

    pub async fn upgrade(&self) -> Result<UpgradeResult, String> {
        if !self.try_begin_upgrade() {
            return Err("Upgrade already in progress".to_string());
        }
        self.run_claimed_upgrade().await
    }

    /// 原子抢占升级槽位；返回 false 表示已有升级在进行中。
    pub fn try_begin_upgrade(&self) -> bool {
        !self.upgrade_in_progress.swap(true, Ordering::Relaxed)
    }

    /// 在已抢占槽位的前提下执行升级，结束时必定释放槽位。
    pub async fn run_claimed_upgrade(&self) -> Result<UpgradeResult, String> {
        *self.last_upgrade.write().await = None;
        let result = self.do_upgrade_internal().await;
        if let Ok(ref r) = result {
            *self.last_upgrade.write().await = Some(r.clone());
        }
        self.upgrade_in_progress.store(false, Ordering::Relaxed);
        result
    }

    /// 升级可用 npm 源候选（最优先在前）；brew/curl 安装方式无需换源。
    async fn upgrade_registries(&self) -> Vec<String> {
        let method = detect_upgrade_method(self.binary.as_str()).await;
        if method == "brew" || method == "curl" {
            return Vec::new();
        }
        crate::process::npm_registry::select(self.upgrade_registry.as_str()).await
    }

    /// 单次升级：先让 opencode 自动探测安装方式，失败时由 Bridge 显式传入 --method。
    async fn run_upgrade_once(
        &self,
        binary: &str,
        registry: Option<&str>,
    ) -> Result<std::process::Output, String> {
        let mut run = exec_opencode_upgrade(binary, None, registry).await;
        let needs_method = match &run {
            Ok(output) if !output.status.success() => {
                let text = format!(
                    "{}{}",
                    String::from_utf8_lossy(&output.stdout),
                    String::from_utf8_lossy(&output.stderr)
                );
                text.contains("Could not detect the installation method")
            }
            _ => false,
        };
        if needs_method {
            let method = detect_upgrade_method(binary).await;
            tracing::info!("opencode 自动探测安装方式失败，改用 --method {}", method);
            run = exec_opencode_upgrade(binary, Some(method.as_str()), registry).await;
        }
        run
    }

    async fn do_upgrade_internal(&self) -> Result<UpgradeResult, String> {
        let previous_version = self.get_cached_version().await;
        let prev_ver_clone = previous_version.clone();
        let binary = self.binary.clone();

        let make_failure = |error: String| UpgradeResult {
            success: false,
            previous_version: previous_version.clone(),
            new_version: None,
            error: Some(error),
            recovered: Some(true),
            current_version: prev_ver_clone.clone(),
        };

        // 依次尝试候选 npm 源（最多 MAX_UPGRADE_ATTEMPTS 个）：源太慢或不可用时顺延下一个。
        let registries = self.upgrade_registries().await;
        let primary_registry = registries.first().cloned();

        // 上一次升级被 opencode 内部的 300s 上限打断，会留下 npm 的占位 bin
        // （内容就是 "postinstall was not run"），此时 opencode 已不可执行，
        // 再跑 `opencode upgrade` 只会秒失败，直接用 npm 修复。
        if !self.verify_opencode().await {
            tracing::warn!("opencode is not runnable (interrupted npm install?); repairing instead of upgrading");
            return self
                .repair_and_restart(primary_registry.as_deref(), previous_version, prev_ver_clone)
                .await;
        }

        let attempts: Vec<Option<String>> = if registries.is_empty() {
            vec![None]
        } else {
            registries
                .into_iter()
                .take(MAX_UPGRADE_ATTEMPTS)
                .map(Some)
                .collect()
        };

        let mut last_error = String::from("Upgrade failed");
        for (index, registry) in attempts.iter().enumerate() {
            if let Some(registry) = registry {
                tracing::info!(
                    "opencode upgrade attempt {}/{} via npm registry {}",
                    index + 1,
                    attempts.len(),
                    registry
                );
            }

            match self
                .run_upgrade_once(binary.as_str(), registry.as_deref())
                .await
            {
                Ok(output) if output.status.success() => {
                    tracing::info!(
                        "opencode upgrade output: {}",
                        String::from_utf8_lossy(&output.stdout).trim()
                    );

                    // 安装可能在 postinstall 前被打断（占位 bin 还留在原地），先校验再重启。
                    if !self.verify_opencode().await {
                        tracing::warn!("upgrade reported success but the binary is not runnable; repairing via npm");
                        return self
                            .repair_and_restart(registry.as_deref(), previous_version, prev_ver_clone)
                            .await;
                    }

                    if let Err(e) = self.restart().await {
                        let error = format!("Upgrade succeeded but restart failed: {}", e);
                        tracing::error!("{}", error);
                        return Ok(make_failure(error));
                    }

                    // 等待服务恢复并刷新缓存版本
                    for _ in 0..HEALTH_WAIT_ATTEMPTS {
                        tokio::time::sleep(tokio::time::Duration::from_secs(2)).await;
                        if self.check_health().await {
                            break;
                        }
                    }
                    let current_version = self.get_cached_version().await;
                    return Ok(UpgradeResult {
                        success: true,
                        previous_version,
                        new_version: current_version.clone(),
                        error: None,
                        recovered: None,
                        current_version,
                    });
                }
                Ok(output) => {
                    let stderr = String::from_utf8_lossy(&output.stderr).trim().to_string();
                    let error = if stderr.is_empty() {
                        format!("opencode upgrade exited with {}", output.status)
                    } else {
                        stderr
                    };
                    tracing::warn!("opencode upgrade failed: {}", error);
                    last_error = error;
                }
                Err(e) => {
                    tracing::warn!("opencode upgrade failed: {}", e);
                    last_error = e;
                }
            }
        }

        // 所有尝试都失败：如果二进制已经被写坏，至少把用户从「opencode 不可用」里救回来。
        if !self.verify_opencode().await {
            tracing::warn!("upgrade failed and opencode is not runnable; repairing via npm");
            return self
                .repair_and_restart(primary_registry.as_deref(), previous_version, prev_ver_clone)
                .await;
        }

        Ok(make_failure(last_error))
    }

    /// 确认 opencode 可执行。npm 安装被中断时会留下 "postinstall was not run" 的占位 bin，
    /// 此时 `opencode --version` 会失败（或在 Unix 上打印那句提示）。
    pub async fn verify_opencode(&self) -> bool {
        let mut cmd = opencode_cmd(self.binary.as_str()).await;
        cmd.arg("--version");
        match run_with_timeout(cmd, VERIFY_TIMEOUT, "opencode --version").await {
            Ok(output) => version_output_ok(
                output.status.success(),
                &String::from_utf8_lossy(&output.stdout),
                &String::from_utf8_lossy(&output.stderr),
            ),
            Err(_) => false,
        }
    }

    /// 用 npm 直接重装 @opencode/cli@latest。
    ///
    /// 不能走 `opencode upgrade`：安装被打断后连 opencode 本身都不可执行。
    /// 调用前必须先停服务，否则 Windows 会锁住 exe 导致 npm 替换失败。
    async fn repair_opencode(&self, registry: Option<&str>) -> Result<(), String> {
        let mut cmd = npm_cmd().await;
        cmd.args(["install", "--global", "--force", "@opencode/cli@latest"]);
        if let Some(registry) = registry.filter(|r| !r.is_empty()) {
            cmd.env("NPM_CONFIG_REGISTRY", registry);
        }
        tracing::info!(
            "repairing opencode: npm install -g --force @opencode/cli@latest (registry={:?})",
            registry
        );
        let output = run_with_timeout(cmd, REPAIR_TIMEOUT, "npm install @opencode/cli").await?;
        if !output.status.success() {
            let stderr = String::from_utf8_lossy(&output.stderr).trim().to_string();
            return Err(if stderr.is_empty() {
                format!("npm install exited with {}", output.status)
            } else {
                stderr
            });
        }
        Ok(())
    }

    async fn repair_and_restart(
        &self,
        registry: Option<&str>,
        previous_version: Option<String>,
        prev_ver_clone: Option<String>,
    ) -> Result<UpgradeResult, String> {
        let make_failure = |error: String| UpgradeResult {
            success: false,
            previous_version: previous_version.clone(),
            new_version: None,
            error: Some(error),
            recovered: Some(true),
            current_version: prev_ver_clone.clone(),
        };

        if self.is_running().await {
            if let Err(e) = self.stop().await {
                tracing::warn!("stop before repair failed: {}", e);
            }
        }

        if let Err(e) = self.repair_opencode(registry).await {
            tracing::error!("opencode repair failed: {}", e);
            let _ = self.start().await;
            return Ok(make_failure(format!(
                "opencode 安装已损坏，自动修复失败：{}。可在电脑上执行 `npm install -g --force @opencode/cli` 手动修复",
                e
            )));
        }

        if !self.verify_opencode().await {
            let _ = self.start().await;
            return Ok(make_failure(
                "opencode 修复后仍然无法运行，请在电脑上执行 `npm install -g --force @opencode/cli`".to_string(),
            ));
        }

        if let Err(e) = self.start().await {
            return Ok(make_failure(format!("opencode 已修复，但启动失败：{}", e)));
        }
        for _ in 0..HEALTH_WAIT_ATTEMPTS {
            tokio::time::sleep(tokio::time::Duration::from_secs(2)).await;
            if self.check_health().await {
                break;
            }
        }
        let current_version = self.get_cached_version().await;
        Ok(UpgradeResult {
            success: true,
            previous_version,
            new_version: current_version.clone(),
            error: None,
            recovered: Some(true),
            current_version,
        })
    }
}


/// 构造启动 opencode CLI 的命令。
///
/// Windows 上 npm/pnpm/yarn 安装的可执行入口是 `.cmd`/`.bat` shim，`CreateProcess`
/// 无法直接执行（只会补 `.exe`），会报 `program not found`；因此非 `.exe` 时统一经由
/// `cmd /C` 交给命令解释器按 PATHEXT 解析，并把可执行文件所在目录前置到子进程 `PATH`
/// （保证 opencode 内部再调用 `npm` 等仍可解析）。其他平台无此问题，保持直接 spawn。
#[cfg(windows)]
async fn opencode_cmd(binary: &str) -> tokio::process::Command {
    let mut cmd = if binary.to_lowercase().ends_with(".exe") {
        tokio::process::Command::new(binary)
    } else {
        let mut c = tokio::process::Command::new("cmd");
        c.arg("/C").arg(binary);
        c
    };

    let mut dirs: Vec<String> = Vec::new();
    if let Some(dir) = resolved_binary_dir(binary).await {
        dirs.push(dir);
    }
    if let Some(home) = dirs::home_dir() {
        for dir in [
            home.join("AppData").join("Roaming").join("npm"),
            home.join(".opencode").join("bin"),
        ] {
            if dir.exists() {
                dirs.push(dir.to_string_lossy().to_string());
            }
        }
    }
    prepend_child_paths(&mut cmd, &dirs);
    cmd
}

/// 非 Windows：Bridge 常以精简 PATH 启动（macOS 的 nohup/launchd、Linux 的 systemd），
/// 需要把常见安装目录前置到子进程 PATH，否则 `opencode` / `npm` 等无法解析。
#[cfg(not(windows))]
async fn opencode_cmd(binary: &str) -> tokio::process::Command {
    let mut cmd = tokio::process::Command::new(binary);
    let dirs: Vec<String> = crate::config::extra_binary_dirs()
        .into_iter()
        .filter(|dir| dir.exists())
        .map(|dir| dir.to_string_lossy().to_string())
        .collect();
    prepend_child_paths(&mut cmd, &dirs);
    cmd
}

/// 把若干目录前置到子进程 `PATH`，确保 opencode 及其包管理器（npm 等）能被找到。
fn prepend_child_paths(cmd: &mut tokio::process::Command, dirs: &[String]) {
    let prefix = dirs
        .iter()
        .filter(|dir| !dir.is_empty())
        .cloned()
        .collect::<Vec<_>>()
        .join(if cfg!(windows) { ";" } else { ":" });
    if prefix.is_empty() {
        return;
    }
    let current = std::env::var("PATH").unwrap_or_default();
    let value = if current.is_empty() {
        prefix
    } else {
        format!("{prefix}{}{current}", if cfg!(windows) { ";" } else { ":" })
    };
    cmd.env("PATH", value);
}

#[cfg(windows)]
async fn resolved_binary_dir(binary: &str) -> Option<String> {
    let resolved = resolve_binary_path(binary).await?;
    std::path::Path::new(&resolved)
        .parent()
        .map(|p| p.to_string_lossy().to_string())
        .filter(|s| !s.is_empty())
}

/// 升级时最多尝试的 npm 源数量（首个 + 一个顺延）。
const MAX_UPGRADE_ATTEMPTS: usize = 2;

/// 修复安装的超时。直接走 npm（不受 opencode 内部 300s 上限约束），给慢网留足下载时间。
const REPAIR_TIMEOUT: std::time::Duration = std::time::Duration::from_secs(900);

/// `opencode --version` 的校验超时。
const VERIFY_TIMEOUT: std::time::Duration = std::time::Duration::from_secs(25);

/// 升级/修复后等待服务就绪的轮询次数（每次 2s，最长约 90s）。
const HEALTH_WAIT_ATTEMPTS: usize = 45;

/// 升级超时。略长于 opencode 内部对 npm/pnpm 的 5 分钟上限，让 opencode 自己的
/// 错误先返回（进程正常退出），而不是 Bridge 先放弃、留下孤儿进程继续替换全局包。
const UPGRADE_TIMEOUT: std::time::Duration = std::time::Duration::from_secs(360);

/// 运行 `opencode upgrade [--method <m>]`；`registry` 非空时通过 `NPM_CONFIG_REGISTRY`
/// 让 opencode 内部的 npm/pnpm 走指定源（不改动用户全局 npm 配置）。
async fn exec_opencode_upgrade(
    binary: &str,
    method: Option<&str>,
    registry: Option<&str>,
) -> Result<std::process::Output, String> {
    let mut cmd = opencode_cmd(binary).await;
    cmd.arg("upgrade");
    if let Some(m) = method {
        cmd.args(["--method", m]);
    }
    if let Some(registry) = registry.filter(|r| !r.is_empty()) {
        cmd.env("NPM_CONFIG_REGISTRY", registry);
    }
    run_with_timeout(cmd, UPGRADE_TIMEOUT, &format!("{} upgrade", binary)).await
}

/// 运行命令；超时后杀掉**整棵进程树**（Windows `taskkill /T`，Unix 进程组），
/// 避免 `opencode upgrade` / npm 变成孤儿进程继续替换全局包。
async fn run_with_timeout(
    mut cmd: tokio::process::Command,
    timeout: std::time::Duration,
    label: &str,
) -> Result<std::process::Output, String> {
    cmd.stdin(std::process::Stdio::null())
        .stdout(std::process::Stdio::piped())
        .stderr(std::process::Stdio::piped());
    #[cfg(not(windows))]
    cmd.process_group(0);

    let child = cmd
        .spawn()
        .map_err(|e| format!("Failed to run '{}': {}", label, e))?;
    let pid = child.id();

    let timed_out = Arc::new(AtomicBool::new(false));
    let killer = {
        let timed_out = timed_out.clone();
        let label = label.to_string();
        tokio::spawn(async move {
            tokio::time::sleep(timeout).await;
            timed_out.store(true, Ordering::Relaxed);
            if let Some(pid) = pid {
                tracing::warn!(
                    "{} timed out ({}s), killing process tree {}",
                    label,
                    timeout.as_secs(),
                    pid
                );
                kill_process_tree(pid).await;
            }
        })
    };

    let output = child.wait_with_output().await;
    killer.abort();

    if timed_out.load(Ordering::Relaxed) {
        return Err(format!("{} timed out ({}s)", label, timeout.as_secs()));
    }
    output.map_err(|e| format!("Failed to run '{}': {}", label, e))
}

#[cfg(windows)]
async fn kill_process_tree(pid: u32) {
    let _ = tokio::process::Command::new("taskkill")
        .args(["/F", "/T", "/PID", &pid.to_string()])
        .stdin(std::process::Stdio::null())
        .stdout(std::process::Stdio::null())
        .stderr(std::process::Stdio::null())
        .status()
        .await;
}

#[cfg(not(windows))]
async fn kill_process_tree(pid: u32) {
    // 子进程以自身为进程组组长启动（process_group(0)），负 pid 表示整组
    let _ = tokio::process::Command::new("kill")
        .args(["-9", &format!("-{}", pid)])
        .stdin(std::process::Stdio::null())
        .stdout(std::process::Stdio::null())
        .stderr(std::process::Stdio::null())
        .status()
        .await;
}

/// 判断 `opencode --version` 的输出是否来自真实的二进制。
///
/// npm 安装被中断时，`@opencode/cli` 自带的占位 bin 会留在原地，内容包含
/// "postinstall script was not run"，必须当作「不可用」处理并触发修复。
fn version_output_ok(success: bool, stdout: &str, stderr: &str) -> bool {
    if !success {
        return false;
    }
    let text = format!("{}{}", stdout, stderr).trim().to_lowercase();
    !text.is_empty() && !text.contains("postinstall")
}

/// npm 命令（Windows 上是 `.cmd` shim，需要经 `cmd /C` 执行）。
async fn npm_cmd() -> tokio::process::Command {
    #[cfg(windows)]
    {
        let mut cmd = tokio::process::Command::new("cmd");
        cmd.arg("/C").arg("npm");
        let mut dirs: Vec<String> = Vec::new();
        for binary in ["npm", "node"] {
            if let Some(dir) = resolved_binary_dir(binary).await {
                dirs.push(dir);
            }
        }
        if let Some(home) = dirs::home_dir() {
            let dir = home.join("AppData").join("Roaming").join("npm");
            if dir.exists() {
                dirs.push(dir.to_string_lossy().to_string());
            }
        }
        prepend_child_paths(&mut cmd, &dirs);
        cmd
    }
    #[cfg(not(windows))]
    {
        let mut cmd = tokio::process::Command::new("npm");
        let dirs: Vec<String> = crate::config::extra_binary_dirs()
            .into_iter()
            .filter(|dir| dir.exists())
            .map(|dir| dir.to_string_lossy().to_string())
            .collect();
        prepend_child_paths(&mut cmd, &dirs);
        cmd
    }
}

/// 解析可执行文件在 PATH 中的实际路径（`where` / `which`）。
async fn resolve_binary_path(binary: &str) -> Option<String> {
    if std::path::Path::new(binary).is_absolute() {
        return Some(binary.to_string());
    }
    let finder = if cfg!(windows) { "where" } else { "which" };
    let out = tokio::process::Command::new(finder)
        .arg(binary)
        .output()
        .await
        .ok()?;
    #[allow(unused_mut)]
    let mut lines: Vec<String> = if out.status.success() {
        String::from_utf8_lossy(&out.stdout)
            .lines()
            .map(|l| l.trim().to_string())
            .filter(|l| !l.is_empty())
            .collect()
    } else {
        Vec::new()
    };
    // Bridge 可能以精简 PATH 启动（服务/launchd/nohup），再查一遍常见安装目录
    #[cfg(not(windows))]
    {
        for dir in crate::config::extra_binary_dirs() {
            let candidate = dir.join(binary);
            if candidate.exists() {
                let candidate = candidate.to_string_lossy().to_string();
                if !lines.contains(&candidate) {
                    lines.push(candidate);
                }
            }
        }
    }
    // Windows 上 `where` 可能先返回无扩展名的 shell 脚本，优先选择可直接执行的扩展名
    #[cfg(windows)]
    {
        let preferred = |ext: &str| lines.iter().find(|l| l.to_lowercase().ends_with(ext)).cloned();
        if let Some(path) = preferred(".exe").or_else(|| preferred(".cmd")).or_else(|| preferred(".bat")) {
            return Some(path);
        }
    }
    lines.into_iter().next()
}

/// 探测 opencode 安装方式：brew > curl > npm（默认）。
async fn detect_upgrade_method(binary: &str) -> String {
    let resolved = resolve_binary_path(binary)
        .await
        .unwrap_or_else(|| binary.to_string());
    let lower = resolved.to_lowercase().replace('\\', "/");
    if lower.contains("/cellar/opencode") || lower.contains("/homebrew/") {
        return "brew".to_string();
    }
    if lower.contains("/.opencode/bin/") {
        return "curl".to_string();
    }
    if let Some(home) = dirs::home_dir() {
        let name = if cfg!(windows) { "opencode.exe" } else { "opencode" };
        if home.join(".opencode").join("bin").join(name).exists() {
            return "curl".to_string();
        }
    }
    "npm".to_string()
}

async fn run_service_cmd(binary: &str, args: &[&str]) -> Result<String, String> {
    let full_args: Vec<&str> = std::iter::once(binary).chain(args.iter().copied()).collect();
    let cmd_str = full_args.join(" ");
    let mut cmd = opencode_cmd(binary).await;
    cmd.args(args);
    let result = tokio::time::timeout(
        std::time::Duration::from_secs(30),
        cmd.output(),
    )
    .await;
    match result {
        Ok(Ok(output)) => {
            let stdout = String::from_utf8_lossy(&output.stdout).to_string();
            let stderr = String::from_utf8_lossy(&output.stderr).to_string();
            if !output.status.success() {
                tracing::warn!("Command '{}' failed: {}", cmd_str, stderr.trim());
            }
            Ok(stdout)
        }
        Ok(Err(e)) => Err(format!("Failed to run '{}': {}", cmd_str, e)),
        Err(_) => Err(format!("Command '{}' timed out (30s)", cmd_str)),
    }
}

/// 请求 `/api/info`：成功时返回运行中的 opencode 版本（空串表示响应里没有版本字段）。
/// 健康检查与监控循环共用，保证版本缓存能随服务状态刷新。
async fn fetch_health_version(opencode_url: &str) -> Option<String> {
    let base = crate::config::read_service_url().unwrap_or_else(|| opencode_url.to_string());
    let url = format!("{}/api/info", base);
    let client = reqwest::Client::builder()
        .timeout(std::time::Duration::from_secs(3))
        .build()
        .unwrap_or_else(|_| reqwest::Client::new());
    let auth = crate::config::Config::quick_auth_header();
    let mut req = client.get(&url);
    if let Some(a) = auth {
        req = req.header("Authorization", a);
    }
    match req.send().await {
        Ok(resp) if resp.status().is_success() => {
            let version = match resp.json::<serde_json::Value>().await {
                Ok(body) => body
                    .get("version")
                    .and_then(|v| v.as_str())
                    .unwrap_or_default()
                    .to_string(),
                Err(_) => String::new(),
            };
            Some(version)
        }
        _ => None,
    }
}

async fn restart_service_loop(
    binary: &str,
    opencode_url: &str,
    status_arc: &Arc<RwLock<OpencodeStatus>>,
    version_arc: &Arc<RwLock<Option<String>>>,
) {
    let mut attempt = 0u32;
    loop {
        attempt += 1;
        tracing::info!("Auto-restart attempt #{}...", attempt);

        {
            let current = *status_arc.read().await;
            if current == OpencodeStatus::Running || current == OpencodeStatus::Starting {
                return;
            }
            if current == OpencodeStatus::Stopping || current == OpencodeStatus::Stopped {
                tracing::info!("opencode was intentionally stopped, aborting restart loop");
                return;
            }
        }

        if let Some(version) = fetch_health_version(opencode_url).await {
            tracing::info!("opencode health check passed, marking as Running");
            let mut s = status_arc.write().await;
            *s = OpencodeStatus::Running;
            if !version.is_empty() {
                *version_arc.write().await = Some(version);
            }
            return;
        }

        {
            let mut s = status_arc.write().await;
            *s = OpencodeStatus::Starting;
        }

        match run_service_cmd(binary, &["service", "restart"]).await {
            Ok(output) => tracing::info!("service restart: {}", output.trim()),
            Err(e) => {
                tracing::error!("Failed to restart opencode service: {}", e);
                let mut s = status_arc.write().await;
                *s = OpencodeStatus::Crashed;
                tokio::time::sleep(tokio::time::Duration::from_secs(5)).await;
                continue;
            }
        }

        let mut retry_count = 0u32;
        let mut became_ready = false;
        while retry_count < 30 {
            tokio::time::sleep(tokio::time::Duration::from_secs(2)).await;
            if let Some(version) = fetch_health_version(opencode_url).await {
                let mut s = status_arc.write().await;
                *s = OpencodeStatus::Running;
                if !version.is_empty() {
                    *version_arc.write().await = Some(version);
                }
                tracing::info!("opencode is ready after auto-restart");
                became_ready = true;
                break;
            }
            retry_count += 1;
        }

        if !became_ready {
            let mut s = status_arc.write().await;
            *s = OpencodeStatus::Crashed;
            tracing::error!("opencode auto-restart failed to become ready");
            tokio::time::sleep(tokio::time::Duration::from_secs(5)).await;
            continue;
        }

        return;
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::time::Duration;

    /// 超时后必须结束子进程（否则 npm 会变成孤儿进程继续替换全局包）。
    #[tokio::test]
    async fn run_with_timeout_kills_hung_process() {
        let mut cmd = tokio::process::Command::new(if cfg!(windows) { "cmd" } else { "sleep" });
        if cfg!(windows) {
            cmd.args(["/C", "ping", "-n", "60", "127.0.0.1"]);
        } else {
            cmd.arg("60");
        }

        let started = std::time::Instant::now();
        let error = run_with_timeout(cmd, Duration::from_secs(1), "test")
            .await
            .expect_err("hung command should time out");
        assert!(error.contains("timed out"), "unexpected error: {}", error);
        assert!(
            started.elapsed() < Duration::from_secs(30),
            "killing the process tree took too long: {:?}",
            started.elapsed()
        );
    }

    #[test]
    fn version_output_detects_interrupted_install_stub() {
        assert!(version_output_ok(true, "opencode v2.1.4\n", ""));
        assert!(version_output_ok(true, "", "2.1.4"));
        assert!(!version_output_ok(
            true,
            "echo \"Error: @opencode/cli's postinstall script was not run.\"\n",
            ""
        ));
        assert!(!version_output_ok(false, "", "not a valid Win32 application"));
        assert!(!version_output_ok(true, "   ", ""));
    }
}

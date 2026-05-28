use std::sync::Arc;
use std::sync::atomic::{AtomicBool, Ordering};
use tokio::sync::RwLock;

use crate::state::OpencodeStatus;

#[derive(Clone)]
pub struct OpencodeManager {
    opencode_url: String,
    status: Arc<RwLock<OpencodeStatus>>,
    binary: Arc<String>,
    hostname: Arc<String>,
    port: Arc<u16>,
    directory: Arc<String>,
    auto_restart: Arc<bool>,
    run_as_user: Arc<String>,
    opencode_version: Arc<RwLock<Option<String>>>,
    upgrade_in_progress: Arc<AtomicBool>,
    pid: Arc<RwLock<Option<u32>>>,
}

#[derive(Debug, serde::Serialize)]
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
            hostname: Arc::new("127.0.0.1".to_string()),
            port: Arc::new(4096),
            directory: Arc::new(String::new()),
            auto_restart: Arc::new(true),
            run_as_user: Arc::new(String::new()),
            opencode_version: Arc::new(RwLock::new(None)),
            upgrade_in_progress: Arc::new(AtomicBool::new(false)),
            pid: Arc::new(RwLock::new(None)),
        }
    }

    pub fn with_config(
        opencode_url: String,
        binary: String,
        hostname: String,
        port: u16,
        directory: String,
        auto_restart: bool,
        run_as_user: String,
    ) -> Self {
        OpencodeManager {
            opencode_url,
            status: Arc::new(RwLock::new(OpencodeStatus::Stopped)),
            binary: Arc::new(binary),
            hostname: Arc::new(hostname),
            port: Arc::new(port),
            directory: Arc::new(directory),
            auto_restart: Arc::new(auto_restart),
            run_as_user: Arc::new(run_as_user),
            opencode_version: Arc::new(RwLock::new(None)),
            upgrade_in_progress: Arc::new(AtomicBool::new(false)),
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
        let url = format!("{}/global/health", self.opencode_url);
        let client = reqwest::Client::builder()
            .timeout(std::time::Duration::from_secs(2))
            .connect_timeout(std::time::Duration::from_secs(1))
            .build()
            .unwrap_or_else(|_| reqwest::Client::new());
        match client.get(&url).send().await {
            Ok(resp) if resp.status().is_success() => {
                if let Ok(body) = resp.json::<serde_json::Value>().await {
                    if let Some(version) = body.get("version").and_then(|v| v.as_str()) {
                        let mut cached = self.opencode_version.write().await;
                        *cached = Some(version.to_string());
                    }
                }
                true
            }
            _ => false,
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
        let hostname = self.hostname.clone();
        let port = *self.port;
        let directory = self.directory.clone();
        let run_as_user = self.run_as_user.clone();

        let mut child = spawn_opencode(&binary, &hostname, port, &directory, &run_as_user)?;

        let child_pid = child.id();
        tracing::info!("opencode process spawned (pid: {:?})", child_pid);

        {
            let mut p = self.pid.write().await;
            *p = child_pid;
        }

        let url = self.opencode_url.clone();
        let status_arc = self.status.clone();
        let auto_restart = *self.auto_restart;
        let version_arc = self.opencode_version.clone();

        tokio::spawn(async move {
            let mut retry_count = 0u32;
            let max_retries = 30u32;

            while retry_count < max_retries {
                tokio::time::sleep(tokio::time::Duration::from_secs(2)).await;

                let client = reqwest::Client::new();
                if let Ok(resp) = client.get(format!("{}/global/health", url)).send().await {
                    if resp.status().is_success() {
                        if let Ok(body) = resp.json::<serde_json::Value>().await {
                            if let Some(v) = body.get("version").and_then(|v| v.as_str()) {
                                let mut cached = version_arc.write().await;
                                *cached = Some(v.to_string());
                            }
                        }
                        let mut s = status_arc.write().await;
                        *s = OpencodeStatus::Running;
                        tracing::info!("opencode is ready");
                        break;
                    }
                }
                retry_count += 1;
                tracing::debug!("Waiting for opencode to be ready... ({}/{})", retry_count, max_retries);
            }

            let current = *status_arc.read().await;
            if current == OpencodeStatus::Stopping || current == OpencodeStatus::Stopped {
                tracing::info!("opencode was intentionally stopped during startup, not marking as crashed");
                return;
            }
            if current != OpencodeStatus::Running {
                let mut s = status_arc.write().await;
                *s = OpencodeStatus::Crashed;
                tracing::error!("opencode failed to start within timeout");
                return;
            }

            match child.wait().await {
                Ok(exit_status) => {
                    tracing::warn!("opencode process exited: {}", exit_status);
                }
                Err(e) => {
                    tracing::error!("opencode process wait error: {}", e);
                }
            }

            let current = *status_arc.read().await;
            if current == OpencodeStatus::Stopping || current == OpencodeStatus::Stopped {
                tracing::info!("opencode was intentionally stopped, not marking as crashed");
                return;
            }

            let mut s = status_arc.write().await;
            *s = OpencodeStatus::Crashed;
            drop(s);

            if auto_restart {
                tracing::info!("Auto-restarting opencode after crash...");
                let binary_r = binary.clone();
                let hostname_r = hostname.clone();
                let port_r = port;
                let directory_r = directory.clone();
                let status_arc_r = status_arc.clone();
                let url_r = url.clone();
                let auto_restart_r = auto_restart;
                let run_as_user_r = run_as_user.clone();
                tokio::spawn(async move {
                    tokio::time::sleep(tokio::time::Duration::from_secs(3)).await;
                    restart_loop(
                        &binary_r,
                        &hostname_r,
                        port_r,
                        &directory_r,
                        &run_as_user_r,
                        &status_arc_r,
                        &url_r,
                        auto_restart_r,
                    )
                    .await;
                });
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

        let pid = self.find_pid_by_port().await;
        if let Some(pid) = pid {
            send_sigint(pid).await;

            for _ in 0..10 {
                tokio::time::sleep(tokio::time::Duration::from_secs(1)).await;
                let client = reqwest::Client::new();
                if client.get(format!("{}/global/health", self.opencode_url)).send().await.is_err() {
                    tracing::info!("opencode exited after SIGINT");
                    break;
                }
            }

            let client = reqwest::Client::new();
            if client.get(format!("{}/global/health", self.opencode_url)).send().await.is_ok() {
                tracing::warn!("opencode did not exit after SIGINT, sending SIGINT again");
                send_sigint(pid).await;
                for _ in 0..5 {
                    tokio::time::sleep(tokio::time::Duration::from_secs(1)).await;
                    if client.get(format!("{}/global/health", self.opencode_url)).send().await.is_err() {
                        tracing::info!("opencode exited after second SIGINT");
                        break;
                    }
                }
            }

            let client = reqwest::Client::new();
            if client.get(format!("{}/global/health", self.opencode_url)).send().await.is_ok() {
                tracing::warn!("opencode still running after two SIGINTs, force killing");
                force_kill(pid).await;
            }
        }

        {
            let mut p = self.pid.write().await;
            *p = None;
        }
        let mut status = self.status.write().await;
        *status = OpencodeStatus::Stopped;
        tracing::info!("opencode stopped");
        Ok(())
    }

    pub async fn restart(&self) -> Result<(), String> {
        if self.is_running().await {
            self.stop().await?;
            for _ in 0..10 {
                tokio::time::sleep(tokio::time::Duration::from_secs(1)).await;
                let client = reqwest::Client::new();
                if client.get(format!("{}/global/health", self.opencode_url)).send().await.is_err() {
                    break;
                }
            }
            tokio::time::sleep(tokio::time::Duration::from_secs(1)).await;
        }
        self.start().await
    }

    #[cfg(windows)]
    async fn find_pid_by_port(&self) -> Option<u32> {
        let port = *self.port;
        let output = tokio::process::Command::new("netstat")
            .args(["-ano"])
            .creation_flags(0x08000000)
            .output()
            .await
            .ok()?;

        let stdout = String::from_utf8_lossy(&output.stdout);
        let pattern = format!(":{}", port);
        for line in stdout.lines() {
            if line.contains("LISTENING") && line.contains(&pattern) {
                let pid: u32 = line.trim().split_whitespace().last()?.parse().ok()?;
                tracing::info!("Found opencode pid {} by port {}", pid, port);
                return Some(pid);
            }
        }
        None
    }

    #[cfg(not(windows))]
    async fn find_pid_by_port(&self) -> Option<u32> {
        let port = *self.port;
        let output = tokio::process::Command::new("ss")
            .args(["-tlnp"])
            .output()
            .await
            .ok()?;

        let stdout = String::from_utf8_lossy(&output.stdout);
        let pattern = format!(":{}", port);
        for line in stdout.lines() {
            if line.contains(&pattern) {
                if let Some(pid_str) = line.split("pid=").nth(1).and_then(|s| s.split(',').next()).and_then(|s| s.split(')').next()) {
                    if let Ok(pid) = pid_str.parse::<u32>() {
                        tracing::info!("Found opencode pid {} by port {}", pid, port);
                        return Some(pid);
                    }
                }
            }
        }
        None
    }

    pub async fn set_status(&self, new_status: OpencodeStatus) {
        let mut status = self.status.write().await;
        *status = new_status;
    }

    pub async fn get_cached_version(&self) -> Option<String> {
        self.opencode_version.read().await.clone()
    }

    pub async fn get_latest_version(&self) -> Result<String, String> {
        match self.npm_view_version().await {
            Ok(v) => Ok(v),
            Err(_) => self.registry_fetch_version().await,
        }
    }

    async fn npm_view_version(&self) -> Result<String, String> {
        let output = tokio::time::timeout(
            std::time::Duration::from_secs(30),
            tokio::process::Command::new("npm")
                .args(["view", "opencode-ai", "version"])
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
        let url = "https://registry.npmjs.org/opencode-ai/latest";
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

    pub async fn upgrade(&self) -> Result<UpgradeResult, String> {
        if self.upgrade_in_progress.swap(true, Ordering::Relaxed) {
            return Err("Upgrade already in progress".to_string());
        }
        let result = self.do_upgrade_internal().await;
        self.upgrade_in_progress.store(false, Ordering::Relaxed);
        result
    }

    async fn do_upgrade_internal(&self) -> Result<UpgradeResult, String> {
        let previous_version = self.get_cached_version().await;
        let prev_ver_clone = previous_version.clone();

        let upgrade_url = format!("{}/global/upgrade", self.opencode_url);
        let client = reqwest::Client::new();

        let timeout_result = tokio::time::timeout(
            std::time::Duration::from_secs(300),
            client.post(&upgrade_url).json(&serde_json::json!({})).send(),
        )
        .await;

        match timeout_result {
            Ok(Ok(resp)) => {
                if !resp.status().is_success() {
                    let error = format!("Upgrade HTTP {}", resp.status());
                    tracing::error!("{}", error);
                    return Ok(UpgradeResult {
                        success: false,
                        previous_version,
                        new_version: None,
                        error: Some(error),
                        recovered: Some(true),
                        current_version: prev_ver_clone,
                    });
                }

                let body: serde_json::Value = resp.json().await.map_err(|e| {
                    format!("Failed to parse upgrade response: {}", e)
                })?;

                let upgrade_success = body.get("success").and_then(|s| s.as_bool()).unwrap_or(false);
                if !upgrade_success {
                    let error = body.get("error")
                        .and_then(|e| e.as_str())
                        .unwrap_or("opencode upgrade returned failure")
                        .to_string();
                    tracing::error!("opencode upgrade API failed: {}", error);
                    return Ok(UpgradeResult {
                        success: false,
                        previous_version,
                        new_version: None,
                        error: Some(error),
                        recovered: Some(true),
                        current_version: prev_ver_clone,
                    });
                }

                let target_version = body.get("version").and_then(|v| v.as_str()).map(|s| s.to_string());

                tracing::info!("opencode binary upgraded to {:?}, restarting...", target_version);

                self.restart().await.map_err(|e| {
                    format!("Upgrade succeeded but restart failed: {}", e)
                })?;

                tokio::time::sleep(tokio::time::Duration::from_secs(5)).await;
                let current_version = self.get_cached_version().await;

                Ok(UpgradeResult {
                    success: true,
                    previous_version,
                    new_version: current_version.clone().or(target_version),
                    error: None,
                    recovered: None,
                    current_version,
                })
            }
            Ok(Err(e)) => {
                let error = format!("Upgrade request failed: {}", e);
                tracing::error!("{}", error);
                Ok(UpgradeResult {
                    success: false,
                    previous_version,
                    new_version: None,
                    error: Some(error),
                    recovered: Some(true),
                    current_version: prev_ver_clone,
                })
            }
            Err(_) => {
                let error = "Upgrade timed out (300s)".to_string();
                tracing::error!("{}", error);
                Ok(UpgradeResult {
                    success: false,
                    previous_version,
                    new_version: None,
                    error: Some(error),
                    recovered: Some(true),
                    current_version: prev_ver_clone,
                })
            }
        }
    }
}

fn spawn_opencode(
    binary: &str,
    hostname: &str,
    port: u16,
    directory: &str,
    run_as_user: &str,
) -> Result<tokio::process::Child, String> {
    let port_str = port.to_string();
    let work_dir = if directory.is_empty() {
        std::env::current_exe()
            .ok()
            .and_then(|p| p.parent().map(|p| p.to_path_buf()))
            .unwrap_or_else(|| std::env::current_dir().unwrap_or_else(|_| ".".into()))
    } else {
        std::path::PathBuf::from(directory)
    };

    #[cfg(windows)]
    let child = {
        let cmd = format!(
            "{} serve --hostname {} --port {}",
            binary, hostname, port_str
        );
        tokio::process::Command::new("cmd")
            .args(["/C", &cmd])
            .current_dir(&work_dir)
            .env("OPENCODE_EXPERIMENTAL", "true")
            .stdout(std::process::Stdio::piped())
            .stderr(std::process::Stdio::piped())
            .creation_flags(0x08000000)
            .spawn()
            .map_err(|e| format!("Failed to spawn opencode: {}", e))?
    };

    #[cfg(not(windows))]
    let child = {
        let is_root = std::env::var("USER").unwrap_or_default() == "root";
        let needs_sudo = is_root && !run_as_user.is_empty() && run_as_user != "root";

        if needs_sudo {
            let mut cmd = std::process::Command::new("sudo");
            cmd.args(["-u", run_as_user, binary, "serve", "--hostname", hostname, "--port", &port_str])
                .current_dir(&work_dir)
                .env("OPENCODE_EXPERIMENTAL", "true")
                .stdout(std::process::Stdio::piped())
                .stderr(std::process::Stdio::piped());

            #[cfg(unix)]
            {
                use std::os::unix::process::CommandExt;
                cmd.process_group(0);
            }

            tokio::process::Command::from(cmd)
                .spawn()
                .map_err(|e| format!("Failed to spawn opencode via sudo: {}", e))?
        } else {
            let mut cmd = std::process::Command::new(binary);
            cmd.args(["serve", "--hostname", hostname, "--port", &port_str])
                .current_dir(&work_dir)
                .env("OPENCODE_EXPERIMENTAL", "true")
                .stdout(std::process::Stdio::piped())
                .stderr(std::process::Stdio::piped());

            #[cfg(unix)]
            {
                use std::os::unix::process::CommandExt;
                cmd.process_group(0);
            }

            tokio::process::Command::from(cmd)
                .spawn()
                .map_err(|e| format!("Failed to spawn opencode: {}", e))?
        }
    };

    Ok(child)
}

async fn restart_loop(
    binary: &str,
    hostname: &str,
    port: u16,
    directory: &str,
    run_as_user: &str,
    status_arc: &Arc<RwLock<OpencodeStatus>>,
    opencode_url: &str,
    auto_restart: bool,
) {
    let mut attempt = 0u32;
    loop {
        attempt += 1;
        tracing::info!("Auto-restart attempt #{}...", attempt);

        {
            let current = *status_arc.read().await;
            if current == OpencodeStatus::Running || current == OpencodeStatus::Starting {
                tracing::info!("opencode is already {} , skip restart", match current {
                    OpencodeStatus::Running => "running",
                    OpencodeStatus::Starting => "starting",
                    _ => "",
                });
                return;
            }
            if current == OpencodeStatus::Stopping || current == OpencodeStatus::Stopped {
                tracing::info!("opencode was intentionally stopped, aborting restart loop");
                return;
            }
        }

        {
            let client = reqwest::Client::new();
            if let Ok(resp) = client.get(format!("{}/global/health", opencode_url)).send().await {
                if resp.status().is_success() {
                    tracing::info!("opencode health check passed, marking as Running");
                    let mut s = status_arc.write().await;
                    *s = OpencodeStatus::Running;
                    return;
                }
            }
        }

        {
            let mut s = status_arc.write().await;
            *s = OpencodeStatus::Starting;
        }

        match spawn_opencode(binary, hostname, port, directory, run_as_user) {
            Ok(mut child) => {
                tracing::info!("opencode re-spawned (pid: {:?})", child.id());

                let mut retry_count = 0u32;
                let max_retries = 30u32;
                let mut became_ready = false;

                while retry_count < max_retries {
                    tokio::time::sleep(tokio::time::Duration::from_secs(2)).await;
                    let client = reqwest::Client::new();
                    if let Ok(resp) = client.get(format!("{}/global/health", opencode_url)).send().await {
                        if resp.status().is_success() {
                            let mut s = status_arc.write().await;
                            *s = OpencodeStatus::Running;
                            tracing::info!("opencode is ready after auto-restart");
                            became_ready = true;
                            break;
                        }
                    }
                    retry_count += 1;
                }

                if !became_ready {
                    let mut s = status_arc.write().await;
                    *s = OpencodeStatus::Crashed;
                    tracing::error!("opencode auto-restart failed to become ready");
                    if !auto_restart {
                        return;
                    }
                    tokio::time::sleep(tokio::time::Duration::from_secs(5)).await;
                    continue;
                }

                match child.wait().await {
                    Ok(es) => tracing::warn!("opencode process exited again: {}", es),
                    Err(e) => tracing::error!("opencode process wait error: {}", e),
                }

                {
                    let current = *status_arc.read().await;
                    if current == OpencodeStatus::Stopping || current == OpencodeStatus::Stopped {
                        tracing::info!("opencode was stopped, not restarting");
                        return;
                    }
                    let mut s = status_arc.write().await;
                    *s = OpencodeStatus::Crashed;
                }

                if !auto_restart {
                    return;
                }
                tokio::time::sleep(tokio::time::Duration::from_secs(3)).await;
            }
            Err(e) => {
                let mut s = status_arc.write().await;
                *s = OpencodeStatus::Crashed;
                tracing::error!("Failed to spawn opencode in restart loop: {}", e);
                if !auto_restart {
                    return;
                }
                tokio::time::sleep(tokio::time::Duration::from_secs(5)).await;
            }
        }
    }
}

async fn send_sigint(pid: u32) {
    #[cfg(windows)]
    {
        use windows::Win32::System::Console::{GenerateConsoleCtrlEvent, CTRL_C_EVENT};

        let result = unsafe { GenerateConsoleCtrlEvent(CTRL_C_EVENT, pid) };
        if result.is_ok() {
            tracing::info!("CTRL_C_EVENT sent to pid {}", pid);
        } else {
            let err = result.unwrap_err();
            tracing::warn!("GenerateConsoleCtrlEvent failed for pid {}: {}", pid, err);
        }
    }

    #[cfg(not(windows))]
    {
        let pid_str = pid.to_string();
        let output = tokio::process::Command::new("kill")
            .args(["-INT", &pid_str])
            .output()
            .await;
        match output {
            Ok(out) => {
                if out.status.success() {
                    tracing::info!("SIGINT sent to pid {}", pid);
                } else {
                    tracing::warn!("kill -INT failed: {}", String::from_utf8_lossy(&out.stderr));
                }
            }
            Err(e) => tracing::warn!("Failed to send SIGINT: {}", e),
        }
    }
}

async fn force_kill(pid: u32) {
    #[cfg(windows)]
    {
        let pid_str = pid.to_string();
        let output = tokio::process::Command::new("taskkill")
            .args(["/F", "/PID", &pid_str, "/T"])
            .creation_flags(0x08000000)
            .output()
            .await;
        match output {
            Ok(out) => tracing::info!("Force kill output: {}", String::from_utf8_lossy(&out.stdout)),
            Err(e) => tracing::warn!("Failed to force kill: {}", e),
        }
    }

    #[cfg(not(windows))]
    {
        let pid_str = pid.to_string();
        let output = tokio::process::Command::new("kill")
            .args(["-9", &pid_str])
            .output()
            .await;
        match output {
            Ok(out) => {
                if out.status.success() {
                    tracing::info!("Force killed pid {}", pid);
                } else {
                    tracing::warn!("kill -9 failed: {}", String::from_utf8_lossy(&out.stderr));
                }
            }
            Err(e) => tracing::warn!("Failed to force kill: {}", e),
        }
    }
}

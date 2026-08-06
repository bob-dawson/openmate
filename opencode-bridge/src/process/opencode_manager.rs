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
        let port = *self.port;
        let hostname = self.hostname.clone();

        run_service_cmd(&binary, &["service", "set", "port", &port.to_string()]).await;
        run_service_cmd(&binary, &["service", "set", "hostname", &hostname]).await;
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
        let auto_restart = *self.auto_restart;
        let version_arc = self.opencode_version.clone();
        let binary_r = binary.clone();

        tokio::spawn(async move {
            let mut retry_count = 0u32;
            let max_retries = 30u32;

            while retry_count < max_retries {
                tokio::time::sleep(tokio::time::Duration::from_secs(2)).await;

                if check_health_url(&url).await {
                    let mut s = status_arc.write().await;
                    *s = OpencodeStatus::Running;
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

                if check_health_url(&url).await {
                    health_failures = 0;
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
                            let url_r = url.clone();
                            let binary_rr = binary_r.clone();
                            tokio::spawn(async move {
                                tokio::time::sleep(tokio::time::Duration::from_secs(3)).await;
                                restart_service_loop(&binary_rr, &url_r, &status_arc_r).await;
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


async fn run_service_cmd(binary: &str, args: &[&str]) -> Result<String, String> {
    let full_args: Vec<&str> = std::iter::once(binary).chain(args.iter().copied()).collect();
    let cmd_str = full_args.join(" ");
    let result = tokio::time::timeout(
        std::time::Duration::from_secs(30),
        tokio::process::Command::new(binary)
            .args(args)
            .output(),
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

async fn check_health_url(opencode_url: &str) -> bool {
    let url = format!("{}/global/health", opencode_url);
    let client = reqwest::Client::builder()
        .timeout(std::time::Duration::from_secs(3))
        .build()
        .unwrap_or_else(|_| reqwest::Client::new());
    match client.get(&url).send().await {
        Ok(resp) if resp.status().is_success() => true,
        _ => false,
    }
}

async fn restart_service_loop(
    binary: &str,
    opencode_url: &str,
    status_arc: &Arc<RwLock<OpencodeStatus>>,
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

        if check_health_url(opencode_url).await {
            tracing::info!("opencode health check passed, marking as Running");
            let mut s = status_arc.write().await;
            *s = OpencodeStatus::Running;
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
            if check_health_url(opencode_url).await {
                let mut s = status_arc.write().await;
                *s = OpencodeStatus::Running;
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

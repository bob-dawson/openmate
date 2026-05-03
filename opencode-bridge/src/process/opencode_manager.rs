use std::sync::Arc;
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
        }
    }

    pub fn with_config(
        opencode_url: String,
        binary: String,
        hostname: String,
        port: u16,
        directory: String,
        auto_restart: bool,
    ) -> Self {
        OpencodeManager {
            opencode_url,
            status: Arc::new(RwLock::new(OpencodeStatus::Stopped)),
            binary: Arc::new(binary),
            hostname: Arc::new(hostname),
            port: Arc::new(port),
            directory: Arc::new(directory),
            auto_restart: Arc::new(auto_restart),
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
        reqwest::get(&url)
            .await
            .map(|r| r.status().is_success())
            .unwrap_or(false)
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

        let mut child = spawn_opencode(&binary, &hostname, port, &directory)?;

        tracing::info!("opencode process spawned (pid: {:?})", child.id());

        let url = self.opencode_url.clone();
        let status_arc = self.status.clone();
        let auto_restart = *self.auto_restart;

        tokio::spawn(async move {
            let mut retry_count = 0u32;
            let max_retries = 30u32;

            while retry_count < max_retries {
                tokio::time::sleep(tokio::time::Duration::from_secs(2)).await;

                let client = reqwest::Client::new();
                if let Ok(resp) = client.get(format!("{}/global/health", url)).send().await {
                    if resp.status().is_success() {
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
                tokio::spawn(async move {
                    tokio::time::sleep(tokio::time::Duration::from_secs(3)).await;
                    restart_loop(
                        &binary_r,
                        &hostname_r,
                        port_r,
                        &directory_r,
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

        #[cfg(windows)]
        {
            let output = tokio::process::Command::new("taskkill")
                .args(["/F", "/IM", "opencode.exe"])
                .output()
                .await;
            if let Ok(out) = output {
                tracing::info!("taskkill output: {}", String::from_utf8_lossy(&out.stdout));
            }
        }

        #[cfg(not(windows))]
        {
            let output = tokio::process::Command::new("pkill")
                .args(["-f", "opencode serve"])
                .output()
                .await;
            if let Ok(out) = output {
                tracing::info!("pkill output: {}", String::from_utf8_lossy(&out.stdout));
            }
        }

        let mut status = self.status.write().await;
        *status = OpencodeStatus::Stopped;
        tracing::info!("opencode stopped");
        Ok(())
    }

    pub async fn restart(&self) -> Result<(), String> {
        if self.is_running().await {
            self.stop().await?;
            tokio::time::sleep(tokio::time::Duration::from_secs(2)).await;
        }
        self.start().await
    }

    pub async fn set_status(&self, new_status: OpencodeStatus) {
        let mut status = self.status.write().await;
        *status = new_status;
    }
}

fn spawn_opencode(
    binary: &str,
    hostname: &str,
    port: u16,
    directory: &str,
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
            .stdout(std::process::Stdio::piped())
            .stderr(std::process::Stdio::piped())
            .spawn()
            .map_err(|e| format!("Failed to spawn opencode: {}", e))?
    };

    #[cfg(not(windows))]
    let child = {
        tokio::process::Command::new(binary)
            .args(["serve", "--hostname", hostname, "--port", &port_str])
            .current_dir(&work_dir)
            .stdout(std::process::Stdio::piped())
            .stderr(std::process::Stdio::piped())
            .spawn()
            .map_err(|e| format!("Failed to spawn opencode: {}", e))?
    };

    Ok(child)
}

async fn restart_loop(
    binary: &str,
    hostname: &str,
    port: u16,
    directory: &str,
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

        match spawn_opencode(binary, hostname, port, directory) {
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

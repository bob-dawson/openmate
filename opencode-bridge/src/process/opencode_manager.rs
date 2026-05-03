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

        let child = tokio::process::Command::new(binary.as_ref())
            .args(["serve", "--hostname", hostname.as_str(), "--port", &port.to_string()])
            .current_dir(directory.as_ref())
            .stdout(std::process::Stdio::piped())
            .stderr(std::process::Stdio::piped())
            .spawn()
            .map_err(|e| format!("Failed to spawn opencode: {}", e))?;

        tracing::info!("opencode process spawned (pid: {:?})", child.id());

        let url = self.opencode_url.clone();
        let status_arc = self.status.clone();
        let _auto_restart = *self.auto_restart;

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
                        return;
                    }
                }
                retry_count += 1;
                tracing::debug!("Waiting for opencode to be ready... ({}/{})", retry_count, max_retries);
            }

            let mut s = status_arc.write().await;
            *s = OpencodeStatus::Crashed;
            tracing::error!("opencode failed to start within timeout");
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

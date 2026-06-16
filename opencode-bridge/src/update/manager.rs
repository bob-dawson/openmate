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
            cmd.creation_flags(0x08000000)
                .arg("-ExecutionPolicy")
                .arg("Bypass")
                .arg("-File")
                .arg(&script_path);
            cmd.spawn().map_err(|e| format!("Failed to spawn update script: {}", e))?;
        }

        #[cfg(not(windows))]
        {
            std::process::Command::new(&script_path)
                .spawn()
                .map_err(|e| format!("Failed to spawn update script: {}", e))?;
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

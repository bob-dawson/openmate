use std::sync::atomic::{AtomicBool, Ordering};
use std::sync::Arc;

use futures::StreamExt;
use tokio::io::AsyncWriteExt;
use tokio::sync::Mutex;

use super::platform;
use super::script;
use super::version;

const GITHUB_BASE: &str = "https://github.com/bob-dawson/openmate/releases/download";

/// Set before shutting down for an in-app upgrade. The process must exit with a
/// non-zero status so the service manager (launchd `SuccessfulExit=false`,
/// systemd `Restart=on-failure`) starts the freshly installed binary again.
pub static RESTART_AFTER_UPGRADE: AtomicBool = AtomicBool::new(false);

/// True when the current process was started by launchd/systemd, which will
/// restart it on its own after a failed exit.
pub fn managed_service_mode() -> bool {
    if std::env::var("OPENMATE_SERVICE_MODE").is_ok() {
        return true;
    }
    #[cfg(target_os = "macos")]
    {
        launchd_owns_process()
    }
    #[cfg(target_os = "linux")]
    {
        std::env::var("INVOCATION_ID").is_ok()
    }
    #[cfg(not(any(target_os = "macos", target_os = "linux")))]
    {
        false
    }
}

#[cfg(target_os = "macos")]
fn launchd_owns_process() -> bool {
    let output = std::process::Command::new("launchctl")
        .args(["print", "system/net.clawmate.openmate"])
        .output();
    match output {
        Ok(result) if result.status.success() => {
            launchctl_reports_pid(&String::from_utf8_lossy(&result.stdout), std::process::id())
        }
        _ => false,
    }
}

#[cfg(any(target_os = "macos", test))]
fn launchctl_reports_pid(output: &str, pid: u32) -> bool {
    let needle = format!("pid = {pid}");
    output.lines().any(|line| line.trim() == needle)
}

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

    pub fn start_download(&self, force: bool) -> bool {
        if self.in_progress.swap(true, Ordering::Relaxed) {
            return false;
        }
        let state = self.state.clone();
        let in_progress = self.in_progress.clone();
        tokio::spawn(async move {
            let result = do_download(state.clone(), force).await;
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
        {
            let s = self.state.lock().await;
            if s.phase != UpgradePhase::Downloaded {
                return Err("No downloaded update to apply".to_string());
            }
        }

        let exe_path = platform::current_exe().map_err(|e| format!("Cannot find current exe: {}", e))?;
        let version = {
            let s = self.state.lock().await;
            s.version.clone().unwrap_or_default()
        };
        let update_path = if version.is_empty() {
            std::env::temp_dir().join("openmate.update")
        } else {
            std::env::temp_dir().join(format!("openmate-{}.update", version))
        };
        if !update_path.exists() {
            return Err("Update file missing".to_string());
        }
        let pid = std::process::id();
        let managed = managed_service_mode();
        let restart_args = if managed {
            None
        } else if std::env::args().skip(1).any(|arg| arg == "service") {
            Some("service")
        } else {
            Some("")
        };

        let script_content = script::generate(
            &exe_path.to_string_lossy(),
            &update_path.to_string_lossy(),
            pid,
            restart_args,
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

        tracing::info!(
            "Update script spawned (pid={}), sending shutdown signal (managed={})",
            pid,
            managed
        );
        RESTART_AFTER_UPGRADE.store(managed, Ordering::SeqCst);
        let _ = self.shutdown_tx.send(true);
        Ok(())
    }
}

async fn do_download(state: Arc<Mutex<UpgradeState>>, force: bool) -> Result<String, String> {
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
    if !force && !version::is_newer(&bridge.version, current) {
        return Err(format!("Already up to date ({})", current));
    }

    let asset = platform::asset_name();
    let region = version::region_key();
    let bases = version::select_mirrors(&bridge.mirrors, &region, GITHUB_BASE);
    let dest = std::env::temp_dir().join(format!("openmate-{}.update", bridge.version));

    let client = reqwest::Client::builder()
        .connect_timeout(std::time::Duration::from_secs(15))
        .timeout(std::time::Duration::from_secs(300))
        .build()
        .map_err(|e| format!("HTTP client error: {}", e))?;

    tracing::info!("Bridge update: region={} mirrors={:?}", region, bases);

    let mut last_error = String::from("No download mirror available");
    for base in bases.iter() {
        let url = format!("{}/{}/{}", base, bridge.tag, asset);
        match attempt_download(&client, &state, &url, &dest, force).await {
            Ok(()) => {
                tracing::info!("Downloaded update from {}", base);
                return Ok(bridge.version);
            }
            Err(e) => {
                tracing::warn!("Mirror {} failed: {}", base, e);
                last_error = e;
                let _ = tokio::fs::remove_file(&dest).await;
            }
        }
    }
    Err(last_error)
}

async fn attempt_download(
    client: &reqwest::Client,
    state: &Arc<Mutex<UpgradeState>>,
    url: &str,
    dest: &std::path::Path,
    force: bool,
) -> Result<(), String> {
    let total_size = fetch_content_length(client, url).await;

    if total_size > 0 && dest.exists() && dest.metadata().map(|m| m.len()).unwrap_or(0) == total_size && !force {
        tracing::info!("Update file already downloaded ({} bytes), skipping download", total_size);
        return Ok(());
    }

    let existing_bytes = if dest.exists() && total_size > 0 {
        let len = dest.metadata().map(|m| m.len()).unwrap_or(0);
        if len > 0 && len < total_size {
            tracing::info!("Resuming download from {} / {} bytes", len, total_size);
            len
        } else {
            if len > 0 {
                tracing::info!("Existing partial file size {} unexpected (expected {}), re-downloading", len, total_size);
            }
            let _ = tokio::fs::remove_file(dest).await;
            0
        }
    } else {
        0
    };

    let mut request = client.get(url);
    if existing_bytes > 0 {
        request = request.header("Range", format!("bytes={}-", existing_bytes));
    }

    let resp = request
        .send()
        .await
        .map_err(|e| format!("Download request failed: {}", e))?;

    if !resp.status().is_success() && resp.status().as_u16() != 206 {
        return Err(format!("Download HTTP {}", resp.status()));
    }

    let is_partial = resp.status().as_u16() == 206;
    let content_length = resp.content_length().unwrap_or(0);
    let expected_total = if is_partial {
        total_size
    } else if content_length > 0 {
        content_length
    } else {
        0
    };

    let mut stream = resp.bytes_stream();

    let mut file = if is_partial {
        tokio::fs::OpenOptions::new()
            .append(true)
            .open(dest)
            .await
            .map_err(|e| format!("Cannot open temp file for append: {}", e))?
    } else {
        tokio::fs::File::create(dest)
            .await
            .map_err(|e| format!("Cannot create temp file: {}", e))?
    };

    let mut downloaded: u64 = existing_bytes;
    while let Some(chunk_result) = stream.next().await {
        let chunk = chunk_result.map_err(|e| format!("Stream error: {}", e))?;
        file.write_all(&chunk)
            .await
            .map_err(|e| format!("Write error: {}", e))?;
        downloaded += chunk.len() as u64;
        if expected_total > 0 {
            let pct = std::cmp::min((downloaded * 100) / expected_total, 100);
            let mut s = state.lock().await;
            s.progress = pct;
        }
    }
    file.flush().await.map_err(|e| format!("Flush error: {}", e))?;

    tracing::info!("Downloaded {} bytes to {}", downloaded, dest.display());
    Ok(())
}

async fn fetch_content_length(client: &reqwest::Client, url: &str) -> u64 {
    match client.head(url).send().await {
        Ok(resp) if resp.status().is_success() => {
            let cl = resp.headers()
                .get("content-length")
                .and_then(|v| v.to_str().ok())
                .and_then(|v| v.parse::<u64>().ok())
                .unwrap_or(0);
            let accept_ranges = resp.headers()
                .get("accept-ranges")
                .is_some();
            if accept_ranges && cl > 0 { cl } else { 0 }
        }
        _ => 0,
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn launchctl_pid_match_requires_exact_process() {
        let output = "\tstate = running\n\tpid = 4242\n\tprogram = /Users/a/openmate\n";
        assert!(launchctl_reports_pid(output, 4242));
        assert!(!launchctl_reports_pid(output, 4243));
    }

    #[test]
    fn launchctl_without_pid_is_not_managed() {
        let output = "\tstate = not running\n\tpath = /Library/LaunchDaemons/x.plist\n";
        assert!(!launchctl_reports_pid(output, 100));
    }
}

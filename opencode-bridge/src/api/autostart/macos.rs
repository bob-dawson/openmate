use std::process::{Command, Stdio};

use crate::error::AppError;
use crate::service_macos;

use super::AutostartImpl;

pub struct MacosAutostart;

impl AutostartImpl for MacosAutostart {
    fn mode() -> &'static str {
        "launchd"
    }

    fn is_enabled() -> bool {
        service_macos::is_installed() && service_macos::is_loaded()
    }

    fn set_enabled(enabled: bool) -> Result<(), AppError> {
        let domain = format!("system/{}", service_macos::LABEL);
        if enabled {
            let _ = launchctl(&["enable", &domain]);
            if !service_macos::is_loaded() {
                let path = service_macos::plist_path();
                let output = launchctl(&["bootstrap", "system", &path.to_string_lossy()])?;
                if !output.status.success() {
                    return Err(hint(
                        "launchctl bootstrap",
                        &String::from_utf8_lossy(&output.stderr),
                    ));
                }
            }
        } else {
            let _ = launchctl(&["bootout", &domain]);
            let output = launchctl(&["disable", &domain])?;
            if !output.status.success() {
                return Err(hint(
                    "launchctl disable",
                    &String::from_utf8_lossy(&output.stderr),
                ));
            }
        }
        Ok(())
    }
}

fn launchctl(args: &[&str]) -> Result<std::process::Output, AppError> {
    Command::new("sudo")
        .arg("-n")
        .arg("launchctl")
        .args(args)
        .stdin(Stdio::null())
        .output()
        .map_err(|e| AppError::Internal(anyhow::anyhow!("Failed to run launchctl: {}", e)))
}

fn hint(action: &str, stderr: &str) -> AppError {
    AppError::Internal(anyhow::anyhow!(
        "{} failed: {}. Run `sudo {} install` (or `sudo {} uninstall`) in a terminal, or configure passwordless sudo.",
        action,
        stderr.trim(),
        exe_name(),
        exe_name()
    ))
}

fn exe_name() -> String {
    std::env::current_exe()
        .map(|p| p.display().to_string())
        .unwrap_or_else(|_| "openmate".to_string())
}

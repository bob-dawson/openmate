use crate::error::AppError;
use super::AutostartImpl;

pub struct LinuxAutostart;

impl AutostartImpl for LinuxAutostart {
    fn mode() -> &'static str {
        "systemd"
    }

    fn is_enabled() -> bool {
        std::process::Command::new("systemctl")
            .args(["is-enabled", "openmate.service"])
            .output()
            .map(|o| o.status.success())
            .unwrap_or(false)
    }

    fn set_enabled(enabled: bool) -> Result<(), AppError> {
        let action = if enabled { "enable" } else { "disable" };
        let output = std::process::Command::new("sudo")
            .args(["systemctl", action, "openmate.service"])
            .output()
            .map_err(|e| AppError::Internal(anyhow::anyhow!("Failed to run systemctl: {}", e)))?;
        if !output.status.success() {
            let stderr = String::from_utf8_lossy(&output.stderr);
            return Err(AppError::Internal(anyhow::anyhow!(
                "systemctl {} failed: {}",
                action,
                stderr
            )));
        }
        Ok(())
    }
}

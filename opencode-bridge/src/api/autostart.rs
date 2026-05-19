use axum::response::IntoResponse;
use axum::Json;
use serde::Deserialize;

use crate::error::AppError;

#[derive(Deserialize)]
pub struct AutostartRequest {
    pub enabled: bool,
}

pub async fn get_autostart() -> Result<impl IntoResponse, AppError> {
    let shortcut_path = get_startup_shortcut_path()?;
    let exists = shortcut_path.exists();
    Ok(Json(serde_json::json!({ "enabled": exists })))
}

pub async fn set_autostart(
    Json(body): Json<AutostartRequest>,
) -> Result<impl IntoResponse, AppError> {
    let shortcut_path = get_startup_shortcut_path()?;

    if body.enabled {
        create_startup_shortcut(&shortcut_path)?;
    } else {
        remove_startup_shortcut(&shortcut_path)?;
    }

    Ok(Json(serde_json::json!({ "success": true, "enabled": body.enabled })))
}

fn get_startup_shortcut_path() -> Result<std::path::PathBuf, AppError> {
    let appdata = std::env::var("APPDATA")
        .map_err(|e| AppError::Internal(anyhow::anyhow!("APPDATA not found: {}", e)))?;
    Ok(std::path::PathBuf::from(appdata)
        .join("Microsoft")
        .join("Windows")
        .join("Start Menu")
        .join("Programs")
        .join("Startup")
        .join("OpenMate Bridge.lnk"))
}

fn create_startup_shortcut(shortcut_path: &std::path::Path) -> Result<(), AppError> {
    let exe_path = std::env::current_exe()
        .map_err(|e| AppError::Internal(anyhow::anyhow!("Cannot get exe path: {}", e)))?;
    let exe_str = exe_path.to_str().ok_or_else(|| {
        AppError::Internal(anyhow::anyhow!("Exe path is not valid UTF-8"))
    })?;

    let ps_script = format!(
        "$ws = New-Object -ComObject WScript.Shell; \
         $sc = $ws.CreateShortcut('{}'); \
         $sc.TargetPath = '{}'; \
         $sc.Arguments = '--tray'; \
         $sc.Save()",
        shortcut_path.to_str().unwrap_or(""),
        exe_str,
    );

    let output = std::process::Command::new("powershell")
        .args(["-NoProfile", "-NonInteractive", "-Command", &ps_script])
        .output()
        .map_err(|e| AppError::Internal(anyhow::anyhow!("Failed to create shortcut: {}", e)))?;

    if !output.status.success() {
        let stderr = String::from_utf8_lossy(&output.stderr);
        return Err(AppError::Internal(anyhow::anyhow!(
            "PowerShell shortcut creation failed: {}",
            stderr
        )));
    }

    Ok(())
}

fn remove_startup_shortcut(shortcut_path: &std::path::Path) -> Result<(), AppError> {
    if shortcut_path.exists() {
        std::fs::remove_file(shortcut_path)?;
    }
    Ok(())
}

use axum::response::IntoResponse;
use axum::Json;
use serde::Deserialize;

use crate::error::AppError;

#[cfg(target_os = "windows")]
mod windows;
#[cfg(target_os = "linux")]
mod linux;

#[cfg(target_os = "windows")]
pub use windows::WindowsAutostart as Autostart;
#[cfg(target_os = "linux")]
pub use linux::LinuxAutostart as Autostart;
#[cfg(not(any(target_os = "windows", target_os = "linux")))]
pub use self::unsupported::UnsupportedAutostart as Autostart;

#[cfg(not(any(target_os = "windows", target_os = "linux")))]
mod unsupported {
    use crate::error::AppError;
    use super::AutostartImpl;

    pub struct UnsupportedAutostart;

    impl AutostartImpl for UnsupportedAutostart {
        fn mode() -> &'static str { "unavailable" }
        fn is_enabled() -> bool { false }
        fn set_enabled(_: bool) -> Result<(), AppError> {
            Err(AppError::Internal(anyhow::anyhow!("Autostart is not supported on this platform")))
        }
    }
}

pub trait AutostartImpl {
    fn mode() -> &'static str;
    fn is_enabled() -> bool;
    fn set_enabled(enabled: bool) -> Result<(), AppError>;
}

#[derive(Deserialize)]
pub struct AutostartRequest {
    pub enabled: bool,
}

pub fn autostart_mode() -> &'static str {
    Autostart::mode()
}

pub async fn get_autostart() -> Result<impl IntoResponse, AppError> {
    let enabled = Autostart::is_enabled();
    Ok(Json(serde_json::json!({ "enabled": enabled })))
}

pub async fn set_autostart(
    Json(body): Json<AutostartRequest>,
) -> Result<impl IntoResponse, AppError> {
    Autostart::set_enabled(body.enabled)?;
    Ok(Json(serde_json::json!({ "success": true, "enabled": body.enabled })))
}

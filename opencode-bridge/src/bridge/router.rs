use axum::extract::State;
use axum::response::IntoResponse;
use axum::Json;
use serde_json::json;

use crate::error::AppError;
use crate::state::{AppState, OpencodeStatus};

pub async fn status(State(state): State<AppState>) -> impl IntoResponse {
    let healthy = state.opencode_manager.check_health().await;
    let oc_status = if healthy {
        let mut s = state.opencode_status.write().await;
        *s = OpencodeStatus::Running;
        OpencodeStatus::Running
    } else {
        let cached = *state.opencode_status.read().await;
        match cached {
            OpencodeStatus::Starting | OpencodeStatus::Stopping => cached,
            _ => {
                let mut s = state.opencode_status.write().await;
                *s = OpencodeStatus::Stopped;
                OpencodeStatus::Stopped
            }
        }
    };
    let status_str = match oc_status {
        OpencodeStatus::Stopped => "stopped",
        OpencodeStatus::Starting => "starting",
        OpencodeStatus::Running => "running",
        OpencodeStatus::Stopping => "stopping",
        OpencodeStatus::Crashed => "crashed",
    };

    Json(json!({
        "bridge": {
            "version": env!("CARGO_PKG_VERSION"),
            "port": state.config.bridge.port,
            "auth_enabled": state.config.bridge.auth_enabled,
            "instance_id": state.config.gateway.instance_id,
        },
        "opencode": {
            "status": status_str,
            "version": state.opencode_manager.get_cached_version().await,
            "url": state.config.opencode_url(),
            "directory": state.config.opencode.directory,
        }
    }))
}

pub async fn start_opencode(State(state): State<AppState>) -> Result<impl IntoResponse, AppError> {
    let current = *state.opencode_status.read().await;
    match current {
        OpencodeStatus::Running => return Err(AppError::OpencodeAlreadyRunning),
        OpencodeStatus::Starting => return Err(AppError::OpencodeAlreadyStarting),
        OpencodeStatus::Stopping => return Err(AppError::OpencodeIsStopping),
        _ => {}
    }

    state
        .opencode_manager
        .start()
        .await
        .map_err(|e| AppError::OpencodeStartFailed(e))?;

    Ok(Json(json!({ "success": true, "status": "starting" })))
}

pub async fn stop_opencode(State(state): State<AppState>) -> Result<impl IntoResponse, AppError> {
    let current = *state.opencode_status.read().await;
    match current {
        OpencodeStatus::Stopped => {
            return Ok(Json(json!({ "success": true, "status": "stopped" })))
        }
        OpencodeStatus::Stopping => return Err(AppError::OpencodeIsStopping),
        _ => {}
    }

    state
        .opencode_manager
        .stop()
        .await
        .map_err(|e| AppError::OpencodeStopFailed(e))?;

    Ok(Json(json!({ "success": true, "status": "stopped" })))
}

pub async fn restart_opencode(
    State(state): State<AppState>,
) -> Result<impl IntoResponse, AppError> {
    state
        .opencode_manager
        .restart()
        .await
        .map_err(|e| AppError::OpencodeStartFailed(e))?;

    Ok(Json(json!({ "success": true, "status": "starting" })))
}

pub async fn opencode_version(State(state): State<AppState>) -> impl IntoResponse {
    let current = state.opencode_manager.get_cached_version().await;

    Json(json!({
        "current": current,
    }))
}

pub async fn opencode_latest_version(State(state): State<AppState>) -> impl IntoResponse {
    let latest = state.opencode_manager.get_latest_version().await.ok();
    let current = state.opencode_manager.get_cached_version().await;

    let has_update = match (&current, &latest) {
        (Some(c), Some(l)) => is_newer_version(l, c),
        _ => false,
    };

    Json(json!({
        "current": current,
        "latest": latest,
        "hasUpdate": has_update,
    }))
}

pub async fn opencode_upgrade_status(State(state): State<AppState>) -> impl IntoResponse {
    Json(json!({
        "upgrading": state.opencode_manager.is_upgrade_in_progress()
    }))
}

pub async fn upgrade_opencode(
    State(state): State<AppState>,
) -> impl IntoResponse {
    if state.opencode_manager.is_upgrade_in_progress() {
        return Json(json!({ "status": "in_progress" }));
    }

    let manager = state.opencode_manager.clone();
    tokio::spawn(async move {
        let result = manager.upgrade().await;
        match result {
            Ok(r) => tracing::info!("Upgrade completed: success={}", r.success),
            Err(e) => tracing::error!("Upgrade failed: {}", e),
        }
    });

    Json(json!({ "status": "started" }))
}

fn is_newer_version(new: &str, old: &str) -> bool {
    let parse = |v: &str| -> Vec<u32> {
        v.trim_start_matches('v')
            .split('.')
            .filter_map(|s| s.parse().ok())
            .collect()
    };
    let new_parts = parse(new);
    let old_parts = parse(old);
    new_parts > old_parts
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn test_is_newer_version_major() {
        assert!(is_newer_version("2.0.0", "1.15.0"));
    }

    #[test]
    fn test_is_newer_version_minor() {
        assert!(is_newer_version("1.16.0", "1.15.0"));
    }

    #[test]
    fn test_is_newer_version_patch() {
        assert!(is_newer_version("1.15.1", "1.15.0"));
    }

    #[test]
    fn test_is_newer_version_same() {
        assert!(!is_newer_version("1.15.0", "1.15.0"));
    }

    #[test]
    fn test_is_newer_version_older() {
        assert!(!is_newer_version("1.14.0", "1.15.0"));
    }

    #[test]
    fn test_is_newer_version_with_v_prefix() {
        assert!(is_newer_version("v1.16.0", "v1.15.0"));
    }
}

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
        },
        "opencode": {
            "status": status_str,
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

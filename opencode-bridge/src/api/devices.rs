use axum::extract::{Path, State};
use axum::response::IntoResponse;
use axum::Json;
use serde::Deserialize;

use crate::error::AppError;
use crate::state::AppState;

#[derive(Deserialize)]
pub struct RenameRequest {
    pub name: String,
}

pub async fn list_devices(
    State(state): State<AppState>,
) -> Result<impl IntoResponse, AppError> {
    let devices = state
        .bridge_db
        .list_devices()
        .map_err(|e| AppError::DatabaseError(e))?;
    Ok(Json(devices))
}

pub async fn rename_device(
    State(state): State<AppState>,
    Path(device_id): Path<String>,
    Json(body): Json<RenameRequest>,
) -> Result<impl IntoResponse, AppError> {
    state
        .bridge_db
        .rename_device(&device_id, &body.name)
        .map_err(|e| AppError::DatabaseError(e))?;
    Ok(Json(serde_json::json!({ "success": true })))
}

pub async fn delete_device(
    State(state): State<AppState>,
    Path(device_id): Path<String>,
) -> Result<impl IntoResponse, AppError> {
    state
        .bridge_db
        .delete_device(&device_id)
        .map_err(|e| AppError::DatabaseError(e))?;
    Ok(Json(serde_json::json!({ "success": true })))
}

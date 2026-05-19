use axum::response::IntoResponse;
use axum::Json;

use crate::error::AppError;

pub async fn reset_secret() -> Result<impl IntoResponse, AppError> {
    crate::auth::key::SecretKey::delete_key_file()
        .map_err(|e| AppError::Internal(anyhow::anyhow!("Failed to reset secret: {}", e)))?;
    tracing::info!("Secret key reset via API");
    Ok(Json(serde_json::json!({ "success": true })))
}

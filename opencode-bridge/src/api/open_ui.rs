use axum::extract::State;
use axum::response::IntoResponse;
use axum::Json;
use axum::body::Body;
use axum::http::{header, StatusCode};

use crate::error::AppError;
use crate::state::AppState;

pub async fn open_ui(
    State(state): State<AppState>,
) -> Result<impl IntoResponse, AppError> {
    let port = state.config.bridge.port;
    let url = format!("http://localhost:{}/ui/", port);

    crate::browser::open_browser(&url)
        .map_err(|e| AppError::Internal(anyhow::anyhow!("Failed to open browser: {}", e)))?;

    Ok(Json(serde_json::json!({ "success": true })))
}

pub async fn download_apk() -> Result<impl IntoResponse, AppError> {
    let exe_path = std::env::current_exe()
        .map_err(|e| AppError::Internal(anyhow::anyhow!("Cannot get exe path: {}", e)))?;
    let exe_dir = exe_path.parent().ok_or_else(|| {
        AppError::Internal(anyhow::anyhow!("Cannot determine exe directory"))
    })?;
    let apk_path = exe_dir.join("apk").join("openmate.apk");

    if !apk_path.exists() {
        return Err(AppError::PathNotFound(apk_path.display().to_string()));
    }

    let file = tokio::fs::File::open(&apk_path).await?;
    let stream = tokio_util::io::ReaderStream::new(file);
    let body = Body::from_stream(stream);

    let response = (
        StatusCode::OK,
        [
            (header::CONTENT_TYPE, "application/vnd.android.package-archive"),
            (
                header::CONTENT_DISPOSITION,
                "attachment; filename=\"openmate.apk\"",
            ),
        ],
        body,
    );

    Ok(response)
}

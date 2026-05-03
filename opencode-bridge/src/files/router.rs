use axum::body::Body;
use axum::extract::{Query, State};
use axum::http::{StatusCode, header};
use axum::response::{IntoResponse, Response};
use axum::Json;
use serde::Deserialize;
use tokio::io::AsyncReadExt;

use crate::error::AppError;
use crate::fs::operations;
use crate::fs::path_guard::PathGuard;
use crate::state::AppState;

#[derive(Debug, Deserialize)]
pub struct FileQuery {
    pub path: String,
}

pub async fn serve_file(
    State(state): State<AppState>,
    Query(query): Query<FileQuery>,
) -> Result<Response, AppError> {
    let guard = PathGuard::from_config(&state.config);
    let validated_path = guard
        .validate(&query.path)
        .map_err(|e| AppError::PathNotAllowed(e))?;

    let metadata = tokio::fs::metadata(&validated_path)
        .await
        .map_err(|_| AppError::PathNotFound(validated_path.display().to_string()))?;

    if metadata.is_dir() {
        let entries = operations::list_dir(&validated_path)?;
        return Ok(Json(entries).into_response());
    }

    let content_type = guess_mime(&validated_path);
    let size = metadata.len();

    let mut file = tokio::fs::File::open(&validated_path).await.map_err(AppError::Io)?;
    let mut buffer = Vec::with_capacity(size as usize);
    file.read_to_end(&mut buffer).await.map_err(AppError::Io)?;

    Response::builder()
        .status(StatusCode::OK)
        .header(header::CONTENT_TYPE, content_type)
        .header(header::CONTENT_LENGTH, size)
        .body(Body::from(buffer))
        .map_err(|e| AppError::Internal(anyhow::anyhow!("Failed to build response: {}", e)))
}

fn guess_mime(path: &std::path::Path) -> String {
    match path
        .extension()
        .and_then(|e| e.to_str())
        .unwrap_or("")
        .to_lowercase()
        .as_str()
    {
        "html" | "htm" => "text/html; charset=utf-8",
        "css" => "text/css; charset=utf-8",
        "js" | "mjs" => "application/javascript; charset=utf-8",
        "json" => "application/json; charset=utf-8",
        "xml" => "application/xml; charset=utf-8",
        "txt" | "log" | "cfg" | "conf" | "ini" | "toml" | "yaml" | "yml" => {
            "text/plain; charset=utf-8"
        }
        "md" => "text/plain; charset=utf-8",
        "ts" | "tsx" | "jsx" | "py" | "rs" | "go" | "java" | "kt" | "kts" | "c" | "cpp"
        | "h" | "hpp" | "cs" | "rb" | "php" | "sh" | "bash" | "zsh" | "ps1" | "bat"
        | "sql" | "proto" | "gradle" => "text/plain; charset=utf-8",
        "png" => "image/png",
        "jpg" | "jpeg" => "image/jpeg",
        "gif" => "image/gif",
        "svg" => "image/svg+xml",
        "webp" => "image/webp",
        "ico" => "image/x-icon",
        "bmp" => "image/bmp",
        "mp4" => "video/mp4",
        "webm" => "video/webm",
        "mp3" => "audio/mpeg",
        "wav" => "audio/wav",
        "ogg" => "audio/ogg",
        "pdf" => "application/pdf",
        "zip" => "application/zip",
        "gz" | "tar" => "application/gzip",
        _ => "application/octet-stream",
    }
    .to_string()
}

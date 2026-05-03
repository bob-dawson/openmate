use axum::body::Body;
use axum::extract::{Query, State};
use axum::http::StatusCode;
use axum::response::IntoResponse;
use axum::Json;
use serde::Deserialize;

use crate::error::AppError;
use crate::fs::operations;
use crate::fs::path_guard::PathGuard;
use crate::fs::search::{self, SearchRequest};
use crate::state::AppState;

#[derive(Debug, Deserialize)]
pub struct PathQuery {
    pub path: String,
}

#[derive(Debug, Deserialize)]
pub struct MkdirRequest {
    pub path: String,
    #[serde(default)]
    pub recursive: bool,
}

#[derive(Debug, Deserialize)]
pub struct WriteRequest {
    pub path: String,
    pub content: String,
    #[serde(default)]
    pub create_dirs: bool,
}

pub async fn list(
    State(state): State<AppState>,
    Query(query): Query<PathQuery>,
) -> Result<impl IntoResponse, AppError> {
    let guard = PathGuard::from_config(&state.config);
    let validated_path = guard
        .validate(&query.path)
        .map_err(|e| AppError::PathNotAllowed(e))?;

    let entries = operations::list_dir(&validated_path)?;
    Ok(Json(entries))
}

pub async fn read(
    State(state): State<AppState>,
    Query(query): Query<PathQuery>,
) -> Result<impl IntoResponse, AppError> {
    let guard = PathGuard::from_config(&state.config);
    let validated_path = guard
        .validate(&query.path)
        .map_err(|e| AppError::PathNotAllowed(e))?;

    let bytes = operations::read_file(&validated_path)?;

    let is_text = is_likely_text(&bytes);
    if is_text {
        let text = String::from_utf8_lossy(&bytes).to_string();
        Ok(axum::response::Response::builder()
            .status(StatusCode::OK)
            .header("content-type", "text/plain; charset=utf-8")
            .body(Body::from(text))
            .unwrap()
            .into_response())
    } else {
        let b64 = base64_encode(&bytes);
        Ok(Json(serde_json::json!({ "data": b64, "encoding": "base64" })).into_response())
    }
}

pub async fn mkdir(
    State(state): State<AppState>,
    Json(body): Json<MkdirRequest>,
) -> Result<impl IntoResponse, AppError> {
    let guard = PathGuard::from_config(&state.config);
    let validated_path = guard
        .validate(&body.path)
        .map_err(|e| AppError::PathNotAllowed(e))?;

    operations::mkdir(&validated_path, body.recursive)?;
    Ok(Json(serde_json::json!({ "success": true })))
}

pub async fn search(
    State(state): State<AppState>,
    Json(body): Json<SearchRequest>,
) -> Result<impl IntoResponse, AppError> {
    let guard = PathGuard::from_config(&state.config);
    guard
        .validate(&body.path)
        .map_err(|e| AppError::PathNotAllowed(e))?;

    let results = search::search_files(&body).map_err(|e| AppError::Internal(anyhow::anyhow!(e)))?;
    Ok(Json(results))
}

pub async fn stat(
    State(state): State<AppState>,
    Query(query): Query<PathQuery>,
) -> Result<impl IntoResponse, AppError> {
    let guard = PathGuard::from_config(&state.config);
    let validated_path = guard
        .validate(&query.path)
        .map_err(|e| AppError::PathNotAllowed(e))?;

    let stat = operations::stat_path(&validated_path)?;
    Ok(Json(stat))
}

pub async fn write(
    State(state): State<AppState>,
    Json(body): Json<WriteRequest>,
) -> Result<impl IntoResponse, AppError> {
    let guard = PathGuard::from_config(&state.config);
    let validated_path = guard
        .validate(&body.path)
        .map_err(|e| AppError::PathNotAllowed(e))?;

    operations::write_file(&validated_path, body.content.as_bytes(), body.create_dirs)?;
    Ok(Json(serde_json::json!({ "success": true })))
}

fn is_likely_text(bytes: &[u8]) -> bool {
    if bytes.is_empty() {
        return true;
    }
    let sample_len = bytes.len().min(8192);
    let sample = &bytes[..sample_len];
    let text_chars = sample
        .iter()
        .filter(|&&b| b == b'\n' || b == b'\r' || b == b'\t' || (b >= 32 && b < 127))
        .count();
    text_chars as f64 / sample_len as f64 > 0.9
}

fn base64_encode(data: &[u8]) -> String {
    const CHARS: &[u8] = b"ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/";
    let mut result = String::with_capacity((data.len() + 2) / 3 * 4);
    for chunk in data.chunks(3) {
        let b0 = chunk[0] as u32;
        let b1 = if chunk.len() > 1 { chunk[1] as u32 } else { 0 };
        let b2 = if chunk.len() > 2 { chunk[2] as u32 } else { 0 };
        let triple = (b0 << 16) | (b1 << 8) | b2;
        result.push(CHARS[((triple >> 18) & 0x3F) as usize] as char);
        result.push(CHARS[((triple >> 12) & 0x3F) as usize] as char);
        if chunk.len() > 1 {
            result.push(CHARS[((triple >> 6) & 0x3F) as usize] as char);
        } else {
            result.push('=');
        }
        if chunk.len() > 2 {
            result.push(CHARS[(triple & 0x3F) as usize] as char);
        } else {
            result.push('=');
        }
    }
    result
}

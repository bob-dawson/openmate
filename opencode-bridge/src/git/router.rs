use axum::extract::{Query, State};
use axum::http::StatusCode;
use axum::response::IntoResponse;
use axum::Json;
use serde::Deserialize;

use crate::error::AppError;
use crate::fs::path_guard::PathGuard;
use crate::git::operations;
use crate::state::AppState;

#[derive(Debug, Deserialize)]
pub struct PathQuery {
    pub path: String,
}

#[derive(Debug, Deserialize)]
pub struct FileQuery {
    pub file: String,
}

pub async fn status(
    State(state): State<AppState>,
    Query(query): Query<PathQuery>,
) -> Result<impl IntoResponse, AppError> {
    let guard = PathGuard::from_config(&state.config);
    let validated_path = guard
        .validate(&query.path)
        .map_err(AppError::PathNotAllowed)?;

    let entries = operations::git_status(&validated_path)?;
    Ok(Json(entries))
}

pub async fn diff(
    State(state): State<AppState>,
    Query(query): Query<FileQuery>,
) -> Result<impl IntoResponse, AppError> {
    let guard = PathGuard::from_config(&state.config);
    let validated_path = guard
        .validate(&query.file)
        .map_err(AppError::PathNotAllowed)?;

    let diff_text = operations::git_diff(&validated_path)?;
    Ok(axum::response::Response::builder()
        .status(StatusCode::OK)
        .header("content-type", "text/plain; charset=utf-8")
        .body(axum::body::Body::from(diff_text))
        .unwrap()
        .into_response())
}

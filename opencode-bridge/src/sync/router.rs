use axum::{
    extract::{Path, Query, State},
    response::IntoResponse,
    Json,
};
use serde::Deserialize;
use serde_json::{json, Value};
use crate::error::AppError;
use crate::state::AppState;

#[derive(Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct InitQuery {
    pub limit: Option<i64>,
}

#[derive(Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct EventsQuery {
    pub after_seq: Option<i64>,
}

pub async fn init(
    State(state): State<AppState>,
    Path(session_id): Path<String>,
    Query(query): Query<InitQuery>,
) -> Result<impl IntoResponse, AppError> {
    let limit = query.limit.unwrap_or(30);
    let (messages, max_seq) = state.sync_db
        .get_init_snapshot(&session_id, limit)
        .map_err(|e| AppError::DatabaseError(e))?;

    let truncated: Vec<Value> = messages.into_iter().map(|mut msg| {
        if let Some(data_str) = msg["data"].as_str() {
            if let Ok(data_val) = serde_json::from_str::<Value>(data_str) {
                let msg_type = msg["type"].as_str().unwrap_or("");
                let truncated_data = super::truncate::truncate_message(msg_type, &data_val);
                msg["data"] = truncated_data;
            }
        }
        msg
    }).collect();

    Ok(Json(json!({
        "messages": truncated,
        "maxSeq": max_seq,
    })))
}

pub async fn events(
    State(state): State<AppState>,
    Path(session_id): Path<String>,
    Query(query): Query<EventsQuery>,
) -> Result<impl IntoResponse, AppError> {
    let after_seq = query.after_seq.unwrap_or(0);
    let (events, max_seq) = state.sync_db
        .get_events(&session_id, after_seq)
        .map_err(|e| AppError::DatabaseError(e))?;

    Ok(Json(json!({
        "events": events,
        "maxSeq": max_seq,
    })))
}

pub async fn full(
    State(state): State<AppState>,
    Path((_session_id, message_id)): Path<(String, String)>,
) -> Result<impl IntoResponse, AppError> {
    let message = state.sync_db
        .get_full_message(&message_id)
        .map_err(|e| AppError::DatabaseError(e))?
        .ok_or_else(|| AppError::MessageNotFound(message_id))?;

    Ok(Json(message))
}

pub async fn sessions(
    State(state): State<AppState>,
) -> Result<impl IntoResponse, AppError> {
    let sessions = state.sync_db
        .get_sessions()
        .map_err(|e| AppError::DatabaseError(e))?;

    Ok(Json(json!({ "sessions": sessions })))
}

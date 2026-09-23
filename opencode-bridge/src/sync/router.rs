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
    pub limit: Option<i64>,
}

#[derive(Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct MessagesQuery {
    pub since: i64,
    pub limit: Option<i64>,
    pub first_id: Option<String>,
    pub last_id: Option<String>,
    pub count: Option<i64>,
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
    let limit = query.limit.unwrap_or(100);
    let (events, max_seq) = state.sync_db
        .get_events(&session_id, after_seq, limit)
        .map_err(|e| AppError::DatabaseError(e))?;

    let truncated: Vec<Value> = events.into_iter().map(|mut evt| {
        if let Some(data_val) = evt.get("data") {
            let evt_type = evt["type"].as_str().unwrap_or("");
            let truncated_data = super::truncate::truncate_event(evt_type, data_val);
            evt["data"] = truncated_data;
        }
        evt
    }).collect();

    Ok(Json(json!({
        "events": truncated,
        "maxSeq": max_seq,
    })))
}

pub async fn messages(
    State(state): State<AppState>,
    Path(session_id): Path<String>,
    Query(query): Query<MessagesQuery>,
) -> Result<impl IntoResponse, AppError> {
    let limit = query.limit.unwrap_or(100);
    let (messages, has_more, max_seq) = state.sync_db
        .get_messages_since(&session_id, query.since, limit)
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

    let server_count = match (&query.first_id, &query.last_id, query.count) {
        (Some(first_id), Some(last_id), Some(_)) => Some(
            state.sync_db
                .count_alive_in_range(&session_id, first_id, last_id)
                .map_err(|e| AppError::DatabaseError(e))?,
        ),
        _ => None,
    };

    Ok(Json(json!({
        "messages": truncated,
        "hasMore": has_more,
        "maxSeq": max_seq,
        "serverCount": server_count,
    })))
}

#[derive(Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct IdsQuery {
    pub from_id: String,
    pub to_id: String,
}

pub async fn ids(
    State(state): State<AppState>,
    Path(session_id): Path<String>,
    Query(query): Query<IdsQuery>,
) -> Result<impl IntoResponse, AppError> {
    let ids = state.sync_db
        .alive_ids_in_range(&session_id, &query.from_id, &query.to_id)
        .map_err(|e| AppError::DatabaseError(e))?;

    Ok(Json(json!({ "ids": ids })))
}

#[derive(Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct ProbeRequest {
    pub base_id: String,
    pub ids: Vec<String>,
}

pub async fn probe(
    State(state): State<AppState>,
    Path(session_id): Path<String>,
    Json(body): Json<ProbeRequest>,
) -> Result<impl IntoResponse, AppError> {
    let counts = state.sync_db
        .count_alive_upto(&session_id, &body.base_id, &body.ids)
        .map_err(|e| AppError::DatabaseError(e))?;

    Ok(Json(json!({ "counts": counts })))
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

#[derive(Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct ResolveQuery {
    pub time_created: i64,
}

pub async fn resolve_message_id(
    State(state): State<AppState>,
    Path(session_id): Path<String>,
    Query(query): Query<ResolveQuery>,
) -> Result<impl IntoResponse, AppError> {
    let message_id = state.sync_db
        .resolve_message_id(&session_id, query.time_created)
        .map_err(|e| AppError::DatabaseError(e))?;

    Ok(Json(json!({
        "messageID": message_id,
    })))
}

#[derive(Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct ResolveEvtQuery {
    pub message_id: String,
}

pub async fn resolve_evt_id(
    State(state): State<AppState>,
    Path(session_id): Path<String>,
    Query(query): Query<ResolveEvtQuery>,
) -> Result<impl IntoResponse, AppError> {
    let evt_id = state.sync_db
        .resolve_evt_id(&session_id, &query.message_id)
        .map_err(|e| AppError::DatabaseError(e))?;

    Ok(Json(json!({
        "evtID": evt_id,
    })))
}

pub async fn sessions(
    State(state): State<AppState>,
) -> Result<impl IntoResponse, AppError> {
    let sessions = state.sync_db
        .get_sessions()
        .map_err(|e| AppError::DatabaseError(e))?;

    Ok(Json(json!({ "sessions": sessions })))
}

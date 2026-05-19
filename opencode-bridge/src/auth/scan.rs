use axum::extract::{ConnectInfo, State};
use axum::Json;
use serde::Deserialize;
use std::net::SocketAddr;
use std::time::{SystemTime, UNIX_EPOCH};

use crate::bridge_db::PairedDevice;
use crate::error::AppError;
use crate::state::AppState;
use super::token::Token;

const SCAN_TOKEN_TTL_SECS: i64 = 120;

#[derive(Deserialize)]
pub struct ScanConfirmRequest {
    pub scan_token: String,
    pub device_name: Option<String>,
}

pub async fn scan_generate(
    State(state): State<AppState>,
) -> Result<Json<serde_json::Value>, AppError> {
    let token_bytes = super::key::generate_random_bytes(32);
    let token = super::key::hex_encode(&token_bytes);

    let now = SystemTime::now()
        .duration_since(UNIX_EPOCH)
        .unwrap_or_default()
        .as_millis() as i64;

    let expires_at = now + SCAN_TOKEN_TTL_SECS * 1000;

    let entry = crate::state::ScanTokenEntry {
        token: token.clone(),
        expires_at,
    };

    {
        let mut st = state.scan_token.write().await;
        *st = Some(entry);
    }

    Ok(Json(serde_json::json!({
        "scan_token": token,
        "expires_at": expires_at,
    })))
}

pub async fn scan_confirm(
    State(state): State<AppState>,
    ConnectInfo(addr): ConnectInfo<SocketAddr>,
    Json(body): Json<ScanConfirmRequest>,
) -> Result<Json<serde_json::Value>, AppError> {
    let now = SystemTime::now()
        .duration_since(UNIX_EPOCH)
        .unwrap_or_default()
        .as_millis() as i64;

    let valid = {
        let st = state.scan_token.read().await;
        match st.as_ref() {
            Some(entry) => entry.token == body.scan_token && entry.expires_at > now,
            None => false,
        }
    };

    if !valid {
        return Err(AppError::BadRequest("Invalid or expired scan token".to_string()));
    }

    {
        let mut st = state.scan_token.write().await;
        *st = None;
    }

    let ip = addr.ip().to_string();
    let device_id_bytes = super::key::generate_random_bytes(8);
    let device_id = super::key::hex_encode(&device_id_bytes);

    let device = PairedDevice {
        device_id: device_id.clone(),
        ip: ip.clone(),
        name: body.device_name.unwrap_or_default(),
        user_agent: String::new(),
        paired_at: now,
        last_seen: now,
    };

    state
        .bridge_db
        .insert_device(&device)
        .map_err(|e| AppError::DatabaseError(e))?;

    let token = Token::generate(&state.secret_key, &device_id);

    tracing::info!("Scan pair confirmed for {}, device {}", ip, device_id);

    Ok(Json(serde_json::json!({
        "token": token,
        "device_id": device_id,
    })))
}

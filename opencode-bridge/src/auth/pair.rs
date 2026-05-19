use axum::extract::{ConnectInfo, State};
use axum::Json;
use serde::{Deserialize, Serialize};
use std::collections::HashMap;
use std::net::SocketAddr;
use std::time::{SystemTime, UNIX_EPOCH};
use tokio::time::Instant;

use crate::bridge_db::PairedDevice;
use crate::error::AppError;
use crate::state::AppState;
use super::token::Token;

const PIN_EXPIRY_SECS: u64 = 300;
const MAX_CONFIRM_ATTEMPTS: u32 = 3;
const RATE_LIMIT_SECS: u64 = 30;

#[derive(Debug, Clone)]
pub struct PendingPair {
    pub pin: String,
    pub ip: String,
    pub approved: bool,
    pub attempts: u32,
    pub created_at: Instant,
}

#[derive(Debug, Clone, Default)]
pub struct PairState {
    pub pending: HashMap<String, PendingPair>,
    pub last_request_by_ip: HashMap<String, Instant>,
}

impl PairState {
    pub fn new() -> Self {
        Self::default()
    }
}

#[derive(Deserialize)]
pub struct PairRequestBody {
    pub pin: Option<String>,
}

#[derive(Serialize)]
pub struct PairRequestResponse {
    pub pin: String,
}

#[derive(Serialize)]
pub struct PendingPairInfo {
    pub pin: String,
    pub ip: String,
    pub approved: bool,
    pub created_at: u64,
}

#[derive(Serialize)]
pub struct PendingListResponse {
    pub pending: Vec<PendingPairInfo>,
}

#[derive(Serialize)]
pub struct PairApproveResponse {
    pub approved: bool,
}

#[derive(Serialize)]
pub struct PairConfirmResponse {
    pub token: String,
}

fn generate_pin() -> String {
    let bytes = super::key::generate_random_bytes(4);
    let num = u32::from_be_bytes([bytes[0], bytes[1], bytes[2], bytes[3]]) % 1_000_000;
    format!("{:06}", num)
}

fn is_expired(pair: &PendingPair) -> bool {
    pair.created_at.elapsed().as_secs() > PIN_EXPIRY_SECS
}

pub async fn pair_request(
    State(state): State<AppState>,
    ConnectInfo(addr): ConnectInfo<SocketAddr>,
) -> Result<Json<PairRequestResponse>, AppError> {
    let ip = addr.ip().to_string();
    let now = Instant::now();

    let mut pair_state = state.pending_pairs.write().await;

    pair_state.pending.retain(|_, v| !is_expired(v));

    if let Some(last) = pair_state.last_request_by_ip.get(&ip) {
        if last.elapsed().as_secs() < RATE_LIMIT_SECS {
            return Err(AppError::RateLimited);
        }
    }

    let pin = generate_pin();
    pair_state.pending.insert(
        pin.clone(),
        PendingPair {
            pin: pin.clone(),
            ip: ip.clone(),
            approved: false,
            attempts: 0,
            created_at: now,
        },
    );
    pair_state.last_request_by_ip.insert(ip.clone(), now);

    tracing::info!("Pair request from {}, PIN: {}", ip, pin);
    Ok(Json(PairRequestResponse { pin }))
}

pub async fn list_pending(
    State(state): State<AppState>,
) -> Result<Json<PendingListResponse>, AppError> {
    let pair_state = state.pending_pairs.read().await;
    let now_epoch = std::time::SystemTime::now()
        .duration_since(std::time::UNIX_EPOCH)
        .unwrap_or_default()
        .as_secs();
    let pending: Vec<PendingPairInfo> = pair_state
        .pending
        .iter()
        .filter(|(_, v)| !is_expired(v))
        .map(|(pin, v)| {
            let elapsed = v.created_at.elapsed().as_secs() as u64;
            PendingPairInfo {
                pin: pin.clone(),
                ip: v.ip.clone(),
                approved: v.approved,
                created_at: now_epoch.saturating_sub(elapsed),
            }
        })
        .collect();
    Ok(Json(PendingListResponse { pending }))
}

pub async fn pair_reject(
    State(state): State<AppState>,
    Json(body): Json<PairRequestBody>,
) -> Result<Json<serde_json::Value>, AppError> {
    let pin = body
        .pin
        .ok_or_else(|| AppError::BadRequest("PIN is required".to_string()))?;

    let mut pair_state = state.pending_pairs.write().await;
    if pair_state.pending.remove(&pin).is_some() {
        tracing::info!("PIN {} rejected", pin);
        Ok(Json(serde_json::json!({ "success": true })))
    } else {
        Err(AppError::PairNotFound)
    }
}

pub async fn pair_approve(
    State(state): State<AppState>,
    ConnectInfo(addr): ConnectInfo<SocketAddr>,
    Json(body): Json<PairRequestBody>,
) -> Result<Json<PairApproveResponse>, AppError> {
    let ip = addr.ip();
    if !ip.is_loopback() {
        return Err(AppError::Forbidden);
    }

    let pin = body
        .pin
        .ok_or_else(|| AppError::BadRequest("PIN is required".to_string()))?;

    let mut pair_state = state.pending_pairs.write().await;

    if let Some(pair) = pair_state.pending.get_mut(&pin) {
        if is_expired(pair) {
            pair_state.pending.remove(&pin);
            return Err(AppError::PairExpired);
        }
        if pair.approved {
            return Err(AppError::BadRequest("PIN already approved".to_string()));
        }
        pair.approved = true;
        tracing::info!("PIN {} approved", pin);
        Ok(Json(PairApproveResponse { approved: true }))
    } else {
        Err(AppError::PairNotFound)
    }
}

pub async fn pair_confirm(
    State(state): State<AppState>,
    ConnectInfo(addr): ConnectInfo<SocketAddr>,
    Json(body): Json<PairRequestBody>,
) -> Result<Json<PairConfirmResponse>, AppError> {
    let ip = addr.ip().to_string();
    let pin = body
        .pin
        .ok_or_else(|| AppError::BadRequest("PIN is required".to_string()))?;

    let mut pair_state = state.pending_pairs.write().await;

    let pair = pair_state
        .pending
        .get_mut(&pin)
        .ok_or(AppError::PairNotFound)?;

    if is_expired(pair) {
        pair_state.pending.remove(&pin);
        return Err(AppError::PairExpired);
    }

    if pair.ip != ip {
        return Err(AppError::Forbidden);
    }

    pair.attempts += 1;
    if pair.attempts > MAX_CONFIRM_ATTEMPTS {
        pair_state.pending.remove(&pin);
        return Err(AppError::PairAttemptsExceeded);
    }

    if !pair.approved {
        return Err(AppError::PairNotApproved);
    }

    pair_state.pending.remove(&pin);

    let device_id_bytes = super::key::generate_random_bytes(8);
    let device_id = super::key::hex_encode(&device_id_bytes);

    let now = SystemTime::now()
        .duration_since(UNIX_EPOCH)
        .unwrap_or_default()
        .as_millis() as i64;

    let device = PairedDevice {
        device_id: device_id.clone(),
        ip: ip.clone(),
        name: String::new(),
        user_agent: String::new(),
        paired_at: now,
        last_seen: now,
    };

    if let Err(e) = state.bridge_db.insert_device(&device) {
        tracing::error!("Failed to insert paired device: {}", e);
        return Err(AppError::DatabaseError("Failed to register device".to_string()));
    }

    let token = Token::generate(&state.secret_key, &device_id);

    tracing::info!("Pair confirmed for {}, device {}", ip, device_id);
    Ok(Json(PairConfirmResponse { token }))
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn test_generate_pin_format() {
        let pin = generate_pin();
        assert_eq!(pin.len(), 6);
        assert!(pin.chars().all(|c| c.is_ascii_digit()));
    }

    #[test]
    fn test_is_expired_not_expired() {
        let pair = PendingPair {
            pin: "123456".to_string(),
            ip: "127.0.0.1".to_string(),
            approved: false,
            attempts: 0,
            created_at: Instant::now(),
        };
        assert!(!is_expired(&pair));
    }

    #[test]
    fn test_pair_state_default() {
        let state = PairState::new();
        assert!(state.pending.is_empty());
        assert!(state.last_request_by_ip.is_empty());
    }
}

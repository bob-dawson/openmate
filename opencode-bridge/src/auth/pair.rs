use axum::extract::{ConnectInfo, State};
use axum::Json;
use serde::{Deserialize, Serialize};
use std::collections::HashMap;
use std::net::SocketAddr;
use tokio::time::Instant;

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
    let token = Token::generate(&state.secret_key);

    tracing::info!("Pair confirmed for {}, token issued", ip);
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

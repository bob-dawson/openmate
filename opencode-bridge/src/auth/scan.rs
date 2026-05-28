use axum::extract::{ConnectInfo, Query, State};
use axum::Json;
use serde::Deserialize;
use std::net::{IpAddr, Ipv4Addr, SocketAddr};
use std::time::{SystemTime, UNIX_EPOCH};

use crate::error::AppError;
use crate::state::{AppState, ScanConfirmResult};
use super::token::Token;

const SCAN_TOKEN_TTL_SECS: i64 = 120;

#[derive(Deserialize)]
pub struct ScanConfirmRequest {
    pub scan_token: String,
    pub device_name: Option<String>,
    pub client_device_id: Option<String>,
}

pub async fn scan_generate(
    State(state): State<AppState>,
) -> Result<Json<serde_json::Value>, AppError> {
    let now = SystemTime::now()
        .duration_since(UNIX_EPOCH)
        .unwrap_or_default()
        .as_millis() as i64;

    {
        let st = state.scan_token.read().await;
        if let Some(entry) = st.as_ref() {
            if entry.expires_at > now {
                return Ok(Json(serde_json::json!({
                    "scan_token": entry.token,
                    "expires_at": entry.expires_at,
                })));
            }
        }
    }

    let token_bytes = super::key::generate_random_bytes(6);
    let token = super::key::base64url_encode(&token_bytes);

    let expires_at = now + SCAN_TOKEN_TTL_SECS * 1000;

    let entry = crate::state::ScanTokenEntry {
        token: token.clone(),
        expires_at,
    };

    {
        let mut st = state.scan_token.write().await;
        *st = Some(entry);
    }

    tracing::info!("scan_generate token: {}", token);
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
    let device_name = body.device_name.unwrap_or_default();

    let (device_id, is_new) = match body.client_device_id {
        Some(cid) if !cid.is_empty() => {
            let existing = state.bridge_db.find_by_client_device_id(&cid)
                .map_err(|e| AppError::DatabaseError(e))?;
            if let Some(mut dev) = existing {
                dev.ip = ip.clone();
                dev.name = device_name;
                dev.last_seen = now;
                state.bridge_db.update_device(&dev)
                    .map_err(|e| AppError::DatabaseError(e))?;
                (dev.device_id, false)
            } else {
                let id_bytes = super::key::generate_random_bytes(8);
                let id = super::key::hex_encode(&id_bytes);
                let device = crate::bridge_db::PairedDevice {
                    device_id: id.clone(),
                    client_device_id: cid,
                    ip: ip.clone(),
                    name: device_name,
                    user_agent: String::new(),
                    paired_at: now,
                    last_seen: now,
                };
                state.bridge_db.insert_device(&device)
                    .map_err(|e| AppError::DatabaseError(e))?;
                (id, true)
            }
        }
        _ => {
            let id_bytes = super::key::generate_random_bytes(8);
            let id = super::key::hex_encode(&id_bytes);
            let device = crate::bridge_db::PairedDevice {
                device_id: id.clone(),
                client_device_id: String::new(),
                ip: ip.clone(),
                name: device_name,
                user_agent: String::new(),
                paired_at: now,
                last_seen: now,
            };
            state.bridge_db.insert_device(&device)
                .map_err(|e| AppError::DatabaseError(e))?;
            (id, true)
        }
    };

    let token = Token::generate(&state.secret_key, &device_id);

    {
        let mut results = state.scan_confirm_results.write().await;
        results.insert(body.scan_token.clone(), ScanConfirmResult {
            token: token.clone(),
            device_id: device_id.clone(),
            expires_at: now + 30_000,
        });
    }

    if is_new {
        tracing::info!("Scan pair confirmed for {}, device {} (new)", ip, device_id);
    } else {
        tracing::info!("Scan pair re-confirmed for {}, device {} (existing)", ip, device_id);
    }

    let bridge_name = hostname::get()
        .ok()
        .and_then(|h| h.into_string().ok())
        .unwrap_or_else(|| "Bridge".to_string());

    let bridge_port = state.actual_port.load(std::sync::atomic::Ordering::Relaxed);
    let bridge_address = best_lan_ip();

    Ok(Json(serde_json::json!({
        "token": token,
        "device_id": device_id,
        "name": bridge_name,
        "address": bridge_address,
        "port": bridge_port,
    })))
}

fn best_lan_ip() -> String {
    let adapters = match local_ip_address::list_afinet_netifas() {
        Ok(a) => a,
        Err(_) => return String::new(),
    };

    let mut candidates: Vec<Ipv4Addr> = adapters
        .into_iter()
        .filter_map(|(_, addr)| match addr {
            IpAddr::V4(ip) if !ip.is_loopback() && !is_link_local(&ip) && !is_virtual(&ip) => Some(ip),
            _ => None,
        })
        .collect();

    candidates.sort_by(|a, b| {
        let a_lan = is_private_lan(a) as u8;
        let b_lan = is_private_lan(b) as u8;
        b_lan.cmp(&a_lan)
    });

    match candidates.into_iter().next() {
        Some(ip) => ip.to_string(),
        None => local_ip_address::local_ip()
            .map(|a| a.to_string())
            .unwrap_or_default(),
    }
}

fn is_link_local(ip: &Ipv4Addr) -> bool {
    let o = ip.octets();
    o[0] == 169 && o[1] == 254
}

fn is_virtual(ip: &Ipv4Addr) -> bool {
    let o = ip.octets();
    if o[0] == 172 && o[1] >= 16 && o[1] <= 31 { return true; }
    if o[0] == 10 && o[1] == 0 { return true; }
    false
}

fn is_private_lan(ip: &Ipv4Addr) -> bool {
    let o = ip.octets();
    o[0] == 192 && o[1] == 168
}

#[derive(Deserialize)]
pub struct ScanPollQuery {
    pub token: String,
}

pub async fn scan_poll(
    State(state): State<AppState>,
    Query(params): Query<ScanPollQuery>,
) -> Result<Json<serde_json::Value>, AppError> {
    let now = SystemTime::now()
        .duration_since(UNIX_EPOCH)
        .unwrap_or_default()
        .as_millis() as i64;

    {
        let mut results = state.scan_confirm_results.write().await;
        if let Some(result) = results.get(&params.token) {
            if result.expires_at > now {
                let res = result.clone();
                results.remove(&params.token);
                return Ok(Json(serde_json::json!({
                    "status": "confirmed",
                    "token": res.token,
                    "device_id": res.device_id,
                })));
            } else {
                results.remove(&params.token);
            }
        }
    }

    {
        let st = state.scan_token.read().await;
        if let Some(entry) = st.as_ref() {
            if entry.token == params.token && entry.expires_at > now {
                return Ok(Json(serde_json::json!({"status": "pending"})));
            }
        }
    }

    Ok(Json(serde_json::json!({"status": "expired"})))
}

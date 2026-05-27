use axum::extract::{ConnectInfo, Query, State};
use axum::Json;
use axum::body::Body;
use axum::http::{header, HeaderMap, StatusCode};
use axum::response::{IntoResponse, Response};
use serde::{Deserialize, Serialize};
use std::net::SocketAddr;
use std::time::{SystemTime, UNIX_EPOCH};

use crate::error::AppError;
use crate::state::AppState;
use super::token::Token;

const LOGIN_SESSION_TTL_SECS: i64 = 120;

#[derive(Debug, Clone)]
pub struct LoginSession {
    pub session_id: String,
    pub expires_at: i64,
    pub confirmed_device_id: Option<String>,
    pub confirmed_token: Option<String>,
}

#[derive(Serialize)]
pub struct LoginQrResponse {
    pub session_id: String,
    pub expires_at: i64,
}

#[derive(Deserialize)]
pub struct LoginConfirmRequest {
    pub session_id: String,
}

#[derive(Deserialize)]
pub struct LoginSessionQuery {
    pub session: String,
}

pub async fn login_qrcode(
    State(state): State<AppState>,
) -> Result<Json<LoginQrResponse>, AppError> {
    let now = SystemTime::now()
        .duration_since(UNIX_EPOCH)
        .unwrap_or_default()
        .as_millis() as i64;

    let id_bytes = super::key::generate_random_bytes(6);
    let session_id = super::key::base64url_encode(&id_bytes);
    let expires_at = now + LOGIN_SESSION_TTL_SECS * 1000;

    let session = LoginSession {
        session_id: session_id.clone(),
        expires_at,
        confirmed_device_id: None,
        confirmed_token: None,
    };

    {
        let mut sessions = state.login_sessions.write().await;
        sessions.insert(session_id.clone(), session);
    }

    Ok(Json(LoginQrResponse {
        session_id,
        expires_at,
    }))
}

pub async fn login_qr_image(
    State(state): State<AppState>,
    Query(params): Query<LoginSessionQuery>,
) -> Result<impl IntoResponse, AppError> {
    let now = SystemTime::now()
        .duration_since(UNIX_EPOCH)
        .unwrap_or_default()
        .as_millis() as i64;

    let session_id = params.session;

    let valid = {
        let sessions = state.login_sessions.read().await;
        match sessions.get(&session_id) {
            Some(s) => s.expires_at > now,
            None => false,
        }
    };

    if !valid {
        return Err(AppError::BadRequest("Invalid or expired login session".to_string()));
    }

    let iid_b64 = crate::auth::key::base64url_encode(
        &crate::auth::key::hex_to_bytes(&state.config.gateway.instance_id).unwrap_or_default()
    );

    let url = format!("oplogin:{}:{}", iid_b64, session_id);

    let code = qrcode::QrCode::new(&url)
        .map_err(|e| AppError::Internal(anyhow::anyhow!("QR generation failed: {}", e)))?;
    let svg = code.render::<qrcode::render::svg::Color>()
        .min_dimensions(384, 384)
        .build();

    let body = Body::from(svg);
    Ok(Response::builder()
        .status(StatusCode::OK)
        .header(header::CONTENT_TYPE, "image/svg+xml")
        .body(body)
        .unwrap())
}

pub async fn login_confirm(
    State(state): State<AppState>,
    ConnectInfo(_addr): ConnectInfo<SocketAddr>,
    headers: HeaderMap,
    Json(body): Json<LoginConfirmRequest>,
) -> Result<Json<serde_json::Value>, AppError> {
    let now = SystemTime::now()
        .duration_since(UNIX_EPOCH)
        .unwrap_or_default()
        .as_millis() as i64;

    let device_id = headers
        .get("authorization")
        .and_then(|v| v.to_str().ok())
        .and_then(Token::extract_from_header)
        .and_then(|t| Token::extract_device_id(t))
        .ok_or_else(|| AppError::BadRequest("Invalid or missing token".to_string()))?;

    let mut sessions = state.login_sessions.write().await;

    let session = sessions.get_mut(&body.session_id)
        .ok_or_else(|| AppError::BadRequest("Invalid login session".to_string()))?;

    if session.expires_at <= now {
        sessions.remove(&body.session_id);
        return Err(AppError::BadRequest("Login session expired".to_string()));
    }

    if session.confirmed_device_id.is_some() {
        return Err(AppError::BadRequest("Login session already confirmed".to_string()));
    }

    let browser_token = Token::generate(&state.secret_key, &device_id);

    session.confirmed_device_id = Some(device_id.clone());
    session.confirmed_token = Some(browser_token);

    tracing::info!("Login session {} confirmed by device {}", body.session_id, device_id);

    Ok(Json(serde_json::json!({ "status": "confirmed" })))
}

pub async fn login_poll(
    State(state): State<AppState>,
    Query(params): Query<LoginSessionQuery>,
) -> Result<Json<serde_json::Value>, AppError> {
    let now = SystemTime::now()
        .duration_since(UNIX_EPOCH)
        .unwrap_or_default()
        .as_millis() as i64;

    let mut sessions = state.login_sessions.write().await;
    let session = match sessions.get(&params.session) {
        Some(s) => s.clone(),
        None => return Ok(Json(serde_json::json!({"status": "expired"}))),
    };

    if session.expires_at <= now {
        sessions.remove(&params.session);
        return Ok(Json(serde_json::json!({"status": "expired"})));
    }

    if let Some(token) = session.confirmed_token {
        sessions.remove(&params.session);
        return Ok(Json(serde_json::json!({
            "status": "confirmed",
            "token": token,
            "device_id": session.confirmed_device_id,
        })));
    }

    Ok(Json(serde_json::json!({"status": "pending"})))
}

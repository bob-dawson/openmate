use axum::extract::{ConnectInfo, Request, State};
use axum::http::StatusCode;
use axum::middleware::Next;
use axum::response::{IntoResponse, Response};
use std::net::SocketAddr;
use std::time::{SystemTime, UNIX_EPOCH};

use crate::state::AppState;
use super::token::Token;

const PUBLIC_PATHS: &[&str] = &[
    "/api/bridge/status",
    "/api/bridge/pair/request",
    "/api/bridge/pair/confirm",
    "/api/bridge/pair/scan",
    "/api/bridge/pair/scan-confirm",
    "/ui/download",
    "/download/openmate.apk",
];

const LOCALHOST_ONLY_PATHS: &[&str] = &[
    "/api/bridge/pair/approve",
    "/api/bridge/open-ui",
    "/api/bridge/logs",
    "/api/bridge/logs/stream",
    "/api/bridge/network/interfaces",
    "/api/bridge/autostart",
    "/api/bridge/pair/pending",
    "/api/bridge/pair/reject",
    "/api/bridge/reset-secret",
    "/api/bridge/config",
];

pub async fn auth_middleware(
    State(state): State<AppState>,
    req: Request,
    next: Next,
) -> Response {
    if !state.config.bridge.auth_enabled {
        return next.run(req).await;
    }

    let path = req.uri().path();

    if PUBLIC_PATHS.iter().any(|p| path == *p) {
        tracing::debug!("auth: public path {}", path);
        return next.run(req).await;
    }

    let addr = req
        .extensions()
        .get::<ConnectInfo<SocketAddr>>()
        .map(|ci| ci.0);
    let is_localhost = addr.map(|a| a.ip().is_loopback()).unwrap_or(false);

    if LOCALHOST_ONLY_PATHS.iter().any(|p| path == *p) || path.starts_with("/ui/") {
        if is_localhost {
            return next.run(req).await;
        }
        if let Some(auth_header) = req.headers().get("authorization") {
            if let Some(token_str) = auth_header
                .to_str()
                .ok()
                .and_then(Token::extract_from_header)
            {
                if Token::validate(&state.secret_key, token_str) {
                    if let Some(device_id) = Token::extract_device_id(token_str) {
                        let bridge_db = state.bridge_db.clone();
                        let did = device_id.clone();
                        let exists = tokio::task::spawn_blocking(move || {
                            bridge_db.device_exists(&did).unwrap_or(false)
                        })
                        .await
                        .unwrap_or(false);

                        if exists {
                            let bridge_db = state.bridge_db.clone();
                            tokio::task::spawn_blocking(move || {
                                let now = SystemTime::now()
                                    .duration_since(UNIX_EPOCH)
                                    .unwrap_or_default()
                                    .as_millis() as i64;
                                let _ = bridge_db.update_last_seen(&device_id, now);
                            });
                            return next.run(req).await;
                        }
                    }
                }
            }
        }
        return (StatusCode::FORBIDDEN, "Forbidden").into_response();
    }

    if is_localhost {
        return next.run(req).await;
    }

    if let Some(auth_header) = req.headers().get("authorization") {
        if let Some(token_str) = auth_header
            .to_str()
            .ok()
            .and_then(Token::extract_from_header)
        {
            if Token::validate(&state.secret_key, token_str) {
                if let Some(device_id) = Token::extract_device_id(token_str) {
                    let bridge_db = state.bridge_db.clone();
                    let did = device_id.clone();
                    let exists = tokio::task::spawn_blocking(move || {
                        bridge_db.device_exists(&did).unwrap_or(false)
                    })
                    .await
                    .unwrap_or(false);

                    if exists {
                        let bridge_db = state.bridge_db.clone();
                        tokio::task::spawn_blocking(move || {
                            let now = SystemTime::now()
                                .duration_since(UNIX_EPOCH)
                                .unwrap_or_default()
                                .as_millis() as i64;
                            let _ = bridge_db.update_last_seen(&device_id, now);
                        });
                        return next.run(req).await;
                    }
                    tracing::warn!("auth: unknown device {} for {} from {:?}", device_id, path, addr);
                }
            } else {
                tracing::warn!("auth: INVALID token for {} from {:?}", path, addr);
            }
        } else {
            tracing::warn!("auth: malformed auth header for {} from {:?}", path, addr);
        }
    } else {
        tracing::warn!("auth: NO authorization header for {} from {:?}", path, addr);
    }

    (
        StatusCode::UNAUTHORIZED,
        "{\"error\":\"Unauthorized\"}",
    )
        .into_response()
}

use axum::extract::{ConnectInfo, Request, State};
use axum::http::StatusCode;
use axum::middleware::Next;
use axum::response::{IntoResponse, Response};
use std::net::SocketAddr;

use crate::state::AppState;
use super::token::Token;

const PUBLIC_PATHS: &[&str] = &[
    "/api/bridge/status",
    "/api/bridge/pair/request",
    "/api/bridge/pair/confirm",
];

const LOCALHOST_ONLY_PATHS: &[&str] = &["/api/bridge/pair/approve"];

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

    if LOCALHOST_ONLY_PATHS.iter().any(|p| path == *p) {
        if let Some(addr) = addr {
            if addr.ip().is_loopback() {
                return next.run(req).await;
            }
        }
        return (StatusCode::FORBIDDEN, "Forbidden").into_response();
    }

    if let Some(auth_header) = req.headers().get("authorization") {
        if let Some(token_str) = auth_header
            .to_str()
            .ok()
            .and_then(Token::extract_from_header)
        {
            if Token::validate(&state.secret_key, token_str) {
                return next.run(req).await;
            }
            tracing::warn!("auth: INVALID token for {} from {:?}", path, addr);
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

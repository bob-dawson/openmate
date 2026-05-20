use axum::http::StatusCode;
use axum::response::{IntoResponse, Response};
use serde_json::json;

#[derive(Debug, thiserror::Error)]
pub enum GatewayError {
    #[error("unauthorized")]
    Unauthorized(String),

    #[error("bridge offline: {0}")]
    BridgeOffline(String),

    #[error("bad request: {0}")]
    BadRequest(String),

    #[error("timeout: {0}")]
    Timeout(String),

    #[error("internal error: {0}")]
    Internal(String),
}

impl IntoResponse for GatewayError {
    fn into_response(self) -> Response {
        let (status, message) = match &self {
            GatewayError::Unauthorized(msg) => (StatusCode::UNAUTHORIZED, msg.clone()),
            GatewayError::BridgeOffline(msg) => (StatusCode::SERVICE_UNAVAILABLE, msg.clone()),
            GatewayError::BadRequest(msg) => (StatusCode::BAD_REQUEST, msg.clone()),
            GatewayError::Timeout(msg) => (StatusCode::GATEWAY_TIMEOUT, msg.clone()),
            GatewayError::Internal(msg) => {
                tracing::error!("internal error: {msg}");
                (StatusCode::INTERNAL_SERVER_ERROR, "internal server error".to_string())
            }
        };

        let body = axum::Json(json!({
            "error": message,
        }));

        (status, body).into_response()
    }
}

impl From<anyhow::Error> for GatewayError {
    fn from(err: anyhow::Error) -> Self {
        GatewayError::Internal(err.to_string())
    }
}

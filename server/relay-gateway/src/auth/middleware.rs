use axum::extract::State;
use axum::http::{HeaderMap, Request};
use axum::middleware::Next;
use axum::response::Response;

use crate::error::GatewayError;
use crate::state::SharedState;

pub async fn require_auth(
    State(state): State<SharedState>,
    headers: HeaderMap,
    mut req: Request<axum::body::Body>,
    next: Next,
) -> Result<Response, GatewayError> {
    let token = headers
        .get("authorization")
        .and_then(|v| v.to_str().ok())
        .and_then(|v| v.strip_prefix("Bearer "))
        .ok_or_else(|| {
            GatewayError::Unauthorized("Missing or invalid Authorization header".to_string())
        })?;

    if !crate::auth::validate_token(token, &state.secret_key) {
        return Err(GatewayError::Unauthorized("Invalid token".to_string()));
    }

    req.extensions_mut().insert(AuthenticatedToken {
        token: token.to_string(),
    });

    Ok(next.run(req).await)
}

#[derive(Clone)]
pub struct AuthenticatedToken {
    pub token: String,
}

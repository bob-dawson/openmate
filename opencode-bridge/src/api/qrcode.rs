use axum::extract::{Query, State};
use axum::response::{IntoResponse, Response};
use axum::body::Body;
use axum::http::{header, StatusCode};

use crate::error::AppError;
use crate::state::AppState;
use serde::Deserialize;

#[derive(Deserialize)]
pub struct QrCodeQuery {}

pub async fn generate_qrcode(
    State(state): State<AppState>,
    Query(_params): Query<QrCodeQuery>,
) -> Result<impl IntoResponse, AppError> {
    let scan_token = {
        let st = state.scan_token.read().await;
        st.as_ref().map(|e| e.token.clone()).unwrap_or_default()
    };

    let mut url = format!("op:");
    let iid_b64 = crate::auth::key::base64url_encode(
        &crate::auth::key::hex_to_bytes(&state.config.gateway.instance_id).unwrap_or_default()
    );
    url.push_str(&iid_b64);
    url.push(':');
    if !scan_token.is_empty() {
        url.push_str(&scan_token);
    }

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

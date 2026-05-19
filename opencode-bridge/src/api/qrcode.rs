use axum::extract::{Query, State};
use axum::response::{IntoResponse, Response};
use axum::body::Body;
use axum::http::{header, StatusCode};

use crate::error::AppError;
use crate::state::AppState;
use serde::Deserialize;

#[derive(Deserialize)]
pub struct QrCodeQuery {
    pub ip: String,
}

pub async fn generate_qrcode(
    State(state): State<AppState>,
    Query(params): Query<QrCodeQuery>,
) -> Result<impl IntoResponse, AppError> {
    let port = state.config.bridge.port;

    let machine_name = hostname::get()
        .ok()
        .and_then(|h| h.into_string().ok())
        .unwrap_or_else(|| "Bridge".to_string());

    let scan_token = {
        let st = state.scan_token.read().await;
        st.as_ref().map(|e| e.token.clone()).unwrap_or_default()
    };

    let mut url = format!(
        "http://{}:{}/ui/download?name={}",
        params.ip, port,
        urlencoding::encode(&machine_name)
    );
    if !scan_token.is_empty() {
        url.push_str("&st=");
        url.push_str(&scan_token);
    }

    let code = qrcode::QrCode::new(&url)
        .map_err(|e| AppError::Internal(anyhow::anyhow!("QR generation failed: {}", e)))?;
    let svg = code.render::<qrcode::render::svg::Color>()
        .min_dimensions(256, 256)
        .build();

    let body = Body::from(svg);
    Ok(Response::builder()
        .status(StatusCode::OK)
        .header(header::CONTENT_TYPE, "image/svg+xml")
        .body(body)
        .unwrap())
}

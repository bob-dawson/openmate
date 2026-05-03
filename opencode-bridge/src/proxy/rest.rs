use axum::body::Body;
use axum::extract::{Request, State};
use axum::response::{IntoResponse, Response};
use reqwest::StatusCode;

use crate::error::AppError;
use crate::state::AppState;

pub async fn proxy_opencode_request(
    State(state): State<AppState>,
    req: Request,
) -> Result<Response, AppError> {
    let opencode_url = state.config.opencode_url();

    let path = req.uri().path_and_query().map(|pq| pq.as_str()).unwrap_or("/");
    let stripped = path.strip_prefix("/api/opencode").unwrap_or(path);
    let target_url = format!("{}{}", opencode_url, stripped);

    let method = req.method().clone();
    let headers = req.headers().clone();

    let body_bytes = axum::body::to_bytes(req.into_body(), 10 * 1024 * 1024)
        .await
        .map_err(|e| AppError::Internal(anyhow::anyhow!("Failed to read request body: {}", e)))?;

    let client = reqwest::Client::new();
    let mut req_builder = client.request(method, &target_url);

    for (name, value) in headers.iter() {
        let name_str = name.as_str();
        if matches!(name_str, "host" | "connection" | "transfer-encoding") {
            continue;
        }
        if let Ok(v) = value.to_str() {
            req_builder = req_builder.header(name_str, v);
        }
    }

    if !body_bytes.is_empty() {
        req_builder = req_builder.body(body_bytes.to_vec());
    }

    let resp = req_builder
        .send()
        .await
        .map_err(|e| AppError::OpencodeRequestFailed(e.to_string()))?;

    let status = resp.status();
    let resp_headers = resp.headers().clone();

    let body_bytes = resp
        .bytes()
        .await
        .map_err(|e| AppError::OpencodeRequestFailed(e.to_string()))?;

    let mut response = Response::builder().status(status);
    for (name, value) in resp_headers.iter() {
        let name_str = name.as_str();
        if matches!(name_str, "connection" | "transfer-encoding" | "content-length") {
            continue;
        }
        if let Ok(v) = value.to_str() {
            response = response.header(name_str, v);
        }
    }

    let response = response
        .body(Body::from(body_bytes.to_vec()))
        .unwrap_or_else(|_| {
            StatusCode::INTERNAL_SERVER_ERROR
                .into_response()
                .into_body()
                .into_response()
                .into()
        });

    Ok(response.into())
}

use axum::http::StatusCode;
use axum::response::IntoResponse;
use axum::routing::get;
use axum::Router;
use tower_http::cors::CorsLayer;
use tower_http::trace::TraceLayer;

use crate::state::SharedState;

async fn health() -> impl IntoResponse {
    (StatusCode::OK, axum::Json(serde_json::json!({
        "status": "ok"
    })))
}

async fn ws_handler() -> impl IntoResponse {
    (StatusCode::OK, "ws placeholder")
}

async fn fallback() -> impl IntoResponse {
    (StatusCode::NOT_FOUND, axum::Json(serde_json::json!({
        "error": "not found"
    })))
}

pub fn build_app(state: SharedState) -> Router {
    Router::new()
        .route("/api/gateway/health", get(health))
        .route("/ws", get(ws_handler))
        .fallback(fallback)
        .layer(TraceLayer::new_for_http())
        .layer(CorsLayer::permissive())
        .with_state(state)
}

pub async fn run_server(state: SharedState) -> anyhow::Result<()> {
    let addr = state.config.listen_addr();
    let listener = tokio::net::TcpListener::bind(&addr).await?;
    tracing::info!("relay-gateway listening on {addr}");
    let app = build_app(state);
    axum::serve(listener, app).await?;
    Ok(())
}

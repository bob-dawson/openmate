use axum::extract::State;
use axum::response::IntoResponse;
use axum::Json;
use serde_json::json;

use crate::state::AppState;

pub async fn shutdown(State(state): State<AppState>) -> impl IntoResponse {
    tracing::info!("Shutdown requested via API");
    let _ = state.shutdown_tx.send(true);
    Json(json!({ "success": true }))
}

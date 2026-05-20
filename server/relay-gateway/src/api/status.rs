use axum::extract::{Query, State};
use axum::response::IntoResponse;
use axum::http::StatusCode;
use serde::Deserialize;

use crate::state::SharedState;

pub async fn health() -> impl IntoResponse {
    (StatusCode::OK, "OK")
}

#[derive(Deserialize)]
pub struct StatusQuery {
    pub instance_id: Option<String>,
}

pub async fn status(
    State(state): State<SharedState>,
    Query(query): Query<StatusQuery>,
) -> impl IntoResponse {
    let instance_id = query.instance_id.unwrap_or_default();
    let online = crate::tunnel::bridge::is_bridge_online(&state, &instance_id);
    (
        StatusCode::OK,
        axum::Json(serde_json::json!({
            "online": online,
            "instance_id": instance_id,
        })),
    )
}

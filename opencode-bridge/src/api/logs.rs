use axum::extract::{Query, State};
use axum::response::sse::{Event, Sse};
use axum::response::IntoResponse;
use axum::Json;
use futures::stream::Stream;
use serde::Deserialize;
use std::convert::Infallible;

use crate::state::AppState;

#[derive(Deserialize)]
pub struct LogQuery {
    pub level: Option<String>,
    pub search: Option<String>,
    pub offset: Option<usize>,
    pub limit: Option<usize>,
}

pub async fn query_logs(
    State(state): State<AppState>,
    Query(params): Query<LogQuery>,
) -> impl IntoResponse {
    let buffer = state.log_buffer.lock().unwrap();
    let entries = buffer.query(
        params.level.as_deref(),
        params.search.as_deref(),
        params.offset.unwrap_or(0),
        params.limit.unwrap_or(100),
    );
    Json(entries)
}

pub async fn stream_logs(State(state): State<AppState>) -> Sse<impl Stream<Item = Result<Event, Infallible>>> {
    let stream = async_stream::stream! {
        let mut last_count = {
            let buffer = state.log_buffer.lock().unwrap();
            buffer.len()
        };
        loop {
            tokio::time::sleep(std::time::Duration::from_secs(1)).await;
            let entries = {
                let buffer = state.log_buffer.lock().unwrap();
                let current_count = buffer.len();
                let new_entries = if current_count > last_count {
                    buffer.query(None, None, last_count, current_count - last_count)
                } else {
                    Vec::new()
                };
                last_count = current_count;
                new_entries
            };
            for entry in entries {
                let data = serde_json::to_string(&entry).unwrap_or_default();
                yield Ok(Event::default().data(data));
            }
        }
    };
    Sse::new(stream).keep_alive(
        axum::response::sse::KeepAlive::new()
            .interval(std::time::Duration::from_secs(30)),
    )
}

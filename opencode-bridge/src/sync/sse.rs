use axum::extract::State;
use axum::response::sse::{Event, KeepAlive, Sse};
use axum::response::IntoResponse;
use futures::StreamExt;
use std::convert::Infallible;
use std::pin::Pin;
use std::time::Duration;
use tokio::sync::mpsc;
use tokio_stream::wrappers::ReceiverStream;

use crate::state::AppState;

type BoxStream<T> = Pin<Box<dyn tokio_stream::Stream<Item = T> + Send + 'static>>;

pub async fn sync_sse(
    State(state): State<AppState>,
) -> impl IntoResponse {
    let opencode_url = state.config.opencode_url();
    let stream = create_sync_sse_stream(opencode_url);
    Sse::new(stream).keep_alive(KeepAlive::default())
}

fn create_sync_sse_stream(opencode_url: String) -> BoxStream<Result<Event, Infallible>> {
    let (tx, rx) = mpsc::channel(32);

    tokio::spawn(async move {
        let sse_url = format!("{}/global/event", opencode_url);
        tracing::info!("Starting sync SSE stream to {}", sse_url);

        loop {
            match forward_sync_events(&sse_url, &tx).await {
                Ok(()) => {
                    tracing::warn!("opencode SSE stream ended, reconnecting in 3s...");
                    tokio::time::sleep(Duration::from_secs(3)).await;
                }
                Err(e) => {
                    tracing::error!("sync SSE error: {}, reconnecting in 5s...", e);
                    tokio::time::sleep(Duration::from_secs(5)).await;
                }
            }

            if tx.is_closed() {
                tracing::info!("Sync SSE client disconnected, stopping");
                break;
            }
        }
    });

    let stream = ReceiverStream::new(rx);
    Box::pin(stream)
}

async fn forward_sync_events(
    sse_url: &str,
    tx: &mpsc::Sender<Result<Event, Infallible>>,
) -> Result<(), String> {
    let client = reqwest::Client::new();
    let resp = client
        .get(sse_url)
        .send()
        .await
        .map_err(|e| format!("Connect failed: {}", e))?;

    if !resp.status().is_success() {
        return Err(format!("HTTP {}", resp.status()));
    }

    let mut stream = resp.bytes_stream();
    let mut buffer = String::new();

    while let Some(chunk) = stream.next().await {
        let chunk = chunk.map_err(|e| format!("Read error: {}", e))?;
        buffer.push_str(&String::from_utf8_lossy(&chunk));

        while let Some(pos) = buffer.find('\n') {
            let line = buffer[..pos].to_string();
            buffer = buffer[pos + 1..].to_string();

            let trimmed = line.trim();
            if trimmed.is_empty() {
                continue;
            }

            let data = match trimmed.strip_prefix("data:") {
                Some(d) => d.trim(),
                None => continue,
            };

            let json = match serde_json::from_str::<serde_json::Value>(data) {
                Ok(v) => v,
                Err(_) => continue,
            };

            if json["type"] != "sync" {
                continue;
            }

            let sync_event = match json.get("syncEvent") {
                Some(e) => e,
                None => continue,
            };

            let session_id = sync_event["aggregateID"].as_str().unwrap_or("");
            let seq = sync_event["seq"].as_i64().unwrap_or(0);

            if session_id.is_empty() {
                continue;
            }

            let notification = serde_json::json!({
                "sessionID": session_id,
                "seq": seq,
            });
            let event = Event::default().data(notification.to_string());

            if tx.send(Ok(event)).await.is_err() {
                return Ok(());
            }
        }
    }

    Ok(())
}

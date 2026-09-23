use axum::extract::State;
use axum::response::IntoResponse;
use axum::response::sse::{Event, KeepAlive, Sse};
use futures::StreamExt;
use futures::stream::Stream;
use std::convert::Infallible;
use std::pin::Pin;
use std::time::Duration;
use tokio::sync::mpsc;

use crate::state::AppState;

type BoxStream<T> = Pin<Box<dyn Stream<Item = T> + Send + 'static>>;

pub async fn sse_proxy(State(state): State<AppState>) -> impl IntoResponse {
    let opencode_url = state.config.effective_opencode_url();
    let auth_header = state.config.opencode_auth_header();
    let mut shutdown_rx = state.shutdown_tx.subscribe();
    let stream = create_sse_stream(opencode_url, auth_header, &mut shutdown_rx);
    Sse::new(stream).keep_alive(KeepAlive::default())
}

fn create_sse_stream(
    opencode_url: String,
    auth_header: Option<String>,
    shutdown_rx: &mut tokio::sync::watch::Receiver<bool>,
) -> BoxStream<Result<Event, Infallible>> {
    let (tx, rx) = mpsc::channel(32);
    let mut shutdown_rx = shutdown_rx.clone();

    tokio::spawn(async move {
        let sse_url = format!("{}/api/event", opencode_url);
        tracing::info!("Starting SSE proxy stream to {}", sse_url);

        loop {
            match forward_sse_events(&sse_url, &auth_header, &tx).await {
                Ok(()) => {
                    tracing::warn!("opencode SSE stream ended, reconnecting in 3s...");
                    tokio::select! {
                        _ = tokio::time::sleep(Duration::from_secs(3)) => {}
                        _ = shutdown_rx.changed() => {
                            tracing::info!("SSE proxy shutting down");
                            return;
                        }
                    }
                }
                Err(e) => {
                    tracing::error!("opencode SSE error: {}, reconnecting in 5s...", e);
                    tokio::select! {
                        _ = tokio::time::sleep(Duration::from_secs(5)) => {}
                        _ = shutdown_rx.changed() => {
                            tracing::info!("SSE proxy shutting down");
                            return;
                        }
                    }
                }
            }

            if tx.is_closed() {
                tracing::info!("SSE client disconnected, stopping proxy");
                break;
            }
        }
    });

    let stream = tokio_stream::wrappers::ReceiverStream::new(rx);
    Box::pin(stream)
}

async fn forward_sse_events(
    sse_url: &str,
    auth_header: &Option<String>,
    tx: &mpsc::Sender<Result<Event, Infallible>>,
) -> Result<(), String> {
    let client = reqwest::Client::new();
    let mut req_builder = client
        .get(sse_url)
        .header("accept", "text/event-stream");

    if let Some(auth) = auth_header {
        req_builder = req_builder.header("authorization", auth);
    }

    let resp = req_builder
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

            let event = if let Some(data) = trimmed.strip_prefix("data:") {
                Ok(Event::default().data(data.trim().to_string()))
            } else if let Some(event_type) = trimmed.strip_prefix("event:") {
                Ok(Event::default().event(event_type.trim().to_string()))
            } else {
                continue;
            };

            if tx.send(event).await.is_err() {
                return Ok(());
            }
        }
    }

    Ok(())
}

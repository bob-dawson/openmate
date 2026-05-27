use futures::StreamExt;
use serde_json::Value;
use std::sync::Arc;
use std::time::Duration;
use tokio::sync::{RwLock, broadcast};

use crate::events::filter::{SharedEvent, build_shared_event};
use crate::state::AppState;

pub struct SharedEventSource {
    sender: broadcast::Sender<SharedEvent>,
    started: RwLock<bool>,
    inert: bool,
}

impl SharedEventSource {
    pub fn new() -> Self {
        let (sender, _) = broadcast::channel(256);
        Self {
            sender,
            started: RwLock::new(false),
            inert: false,
        }
    }

    pub fn new_inert() -> Self {
        let (sender, _) = broadcast::channel(256);
        Self {
            sender,
            started: RwLock::new(false),
            inert: true,
        }
    }

    pub async fn subscribe(self: &Arc<Self>, state: &AppState) -> broadcast::Receiver<SharedEvent> {
        self.ensure_started(state).await;
        self.sender.subscribe()
    }

    pub fn publish(&self, raw: Value) {
        if let Some(event) = build_shared_event(&raw) {
            let _ = self.sender.send(event);
        }
    }

    async fn ensure_started(self: &Arc<Self>, state: &AppState) {
        if self.inert {
            return;
        }

        {
            let started = self.started.read().await;
            if *started {
                return;
            }
        }

        let mut started = self.started.write().await;
        if *started {
            return;
        }

        *started = true;

        let source = Arc::clone(self);
        let opencode_url = state.config.opencode_url();
        let mut shutdown_rx = state.shutdown_tx.subscribe();
        tokio::spawn(async move {
            let sse_url = format!("{}/global/event", opencode_url);
            tracing::info!("Starting shared bridge event source to {}", sse_url);

            loop {
                match forward_upstream_events(&sse_url, &source.sender).await {
                    Ok(()) => {
                        tracing::warn!("shared bridge event source ended, reconnecting in 3s...");
                        tokio::select! {
                            _ = tokio::time::sleep(Duration::from_secs(3)) => {}
                            _ = shutdown_rx.changed() => {
                                tracing::info!("shared bridge event source shutting down");
                                return;
                            }
                        }
                    }
                    Err(err) => {
                        tracing::error!("shared bridge event source error: {}, reconnecting in 5s...", err);
                        tokio::select! {
                            _ = tokio::time::sleep(Duration::from_secs(5)) => {}
                            _ = shutdown_rx.changed() => {
                                tracing::info!("shared bridge event source shutting down");
                                return;
                            }
                        }
                    }
                }
            }
        });
    }
}

async fn forward_upstream_events(
    sse_url: &str,
    sender: &broadcast::Sender<SharedEvent>,
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

        while let Some(frame) = take_next_sse_frame(&mut buffer) {
            for event in parse_upstream_frames(&frame) {
                let _ = sender.send(event);
            }
        }
    }

    Ok(())
}

fn take_next_sse_frame(buffer: &mut String) -> Option<String> {
    let normalized = buffer.replace("\r\n", "\n");
    *buffer = normalized;

    let pos = buffer.find("\n\n")?;
    let frame = buffer[..pos + 2].to_string();
    *buffer = buffer[pos + 2..].to_string();
    Some(frame)
}

fn parse_upstream_frames(chunk: &str) -> Vec<SharedEvent> {
    let mut frames = Vec::new();
    let mut frame_data = Vec::new();

    for line in chunk.lines() {
        if let Some(data) = line.strip_prefix("data:") {
            frame_data.push(data.trim_start());
            continue;
        }

        if line.is_empty() {
            if let Some(frame) = parse_sse_data_frame(&frame_data.join("\n")) {
                frames.push(frame);
            }
            frame_data.clear();
        }
    }

    frames
}

fn parse_sse_data_frame(data: &str) -> Option<SharedEvent> {
    let raw = serde_json::from_str::<Value>(data).ok()?;
    build_shared_event(&raw)
}

#[cfg(test)]
mod tests {
    use super::*;
    use serde_json::json;

    #[test]
    fn upstream_source_drops_high_volume_unretained_events_before_broadcast() {
        let chunk = concat!(
            "data: {\"type\":\"message.part.delta\",\"properties\":{\"sessionID\":\"ses_1\"}}\n\n",
            "data: {\"payload\":{\"type\":\"sync\",\"syncEvent\":{\"type\":\"session.next.text.delta.1\",\"data\":{\"sessionID\":\"ses_1\",\"delta\":\"drop\"}}}}\n\n",
            "data: {\"type\":\"session.error\",\"properties\":{\"sessionID\":\"ses_1\"}}\n\n"
        );

        let frames = parse_upstream_frames(chunk);
        let retained: Vec<Value> = frames
            .iter()
            .filter_map(|event| event.bridge_event.clone())
            .collect();

        assert_eq!(frames.len(), 1);
        assert_eq!(retained, vec![json!({
            "type": "session.error",
            "properties": {
                "sessionID": "ses_1"
            }
        })]);
    }
}

use axum::extract::State;
use axum::response::IntoResponse;
use axum::response::sse::{Event, KeepAlive, Sse};
use async_stream::stream;
use serde_json::Value;
use std::convert::Infallible;
use std::pin::Pin;

use crate::events::filter::filter_event;
use crate::state::AppState;

type BoxStream<T> = Pin<Box<dyn tokio_stream::Stream<Item = T> + Send + 'static>>;

pub async fn events_sse(State(state): State<AppState>) -> impl IntoResponse {
    let stream = create_events_stream(state);
    Sse::new(stream).keep_alive(KeepAlive::default())
}

fn create_events_stream(state: AppState) -> BoxStream<Result<Event, Infallible>> {
    Box::pin(stream! {
        let mut rx = state.event_source.subscribe(&state).await;
        let mut shutdown_rx = state.shutdown_tx.subscribe();

        loop {
            tokio::select! {
                result = rx.recv() => {
                    match result {
                        Ok(event) => {
                            if let Some(filtered) = project_bridge_event(&event) {
                                yield Ok(Event::default().data(filtered.to_string()));
                            }
                        }
                        Err(tokio::sync::broadcast::error::RecvError::Lagged(_)) => continue,
                        Err(tokio::sync::broadcast::error::RecvError::Closed) => break,
                    }
                }
                _ = shutdown_rx.changed() => {
                    tracing::info!("events SSE handler shutting down");
                    break;
                }
            }
        }
    })
}

fn parse_sse_frames(chunk: &str) -> Vec<Value> {
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

fn parse_sse_data_frame(data: &str) -> Option<Value> {
    let parsed = serde_json::from_str::<Value>(data).ok()?;
    filter_event(&parsed)
}

fn project_bridge_event(event: &crate::events::filter::SharedEvent) -> Option<Value> {
    event.bridge_event.clone()
}

pub fn test_parse_bridge_events_frames(chunk: &str) -> Vec<Value> {
    parse_sse_frames(chunk)
}

#[cfg(test)]
mod tests {
    use super::parse_sse_frames;
    use crate::events::filter::filter_event;
    use serde_json::json;

    #[test]
    fn parses_multiline_data_frames_by_event_boundary() {
        let chunk = concat!(
            "event: message\n",
            "data: {\"type\":\"message.updated\",\n",
            "data: \"properties\":{\"sessionID\":\"ses_1\",\"info\":{\"id\":\"msg_1\"}}}\n",
            "\n",
        );

        let frames = parse_sse_frames(chunk);

        assert_eq!(frames, vec![json!({
            "type": "message.updated",
            "properties": {
                "sessionID": "ses_1",
                "messageID": "msg_1"
            }
        })]);
    }

    #[test]
    fn ignores_non_data_lines_inside_frame() {
        let chunk = concat!(
            "id: 1\n",
            "event: message\n",
            "retry: 1000\n",
            "data: {\"type\":\"session.error\",\"properties\":{\"sessionID\":\"ses_1\"}}\n",
            "\n",
        );

        let frames = parse_sse_frames(chunk);

        assert_eq!(frames, vec![json!({
            "type": "session.error",
            "properties": {
                "sessionID": "ses_1"
            }
        })]);
    }

    #[test]
    fn parse_sse_frames_drops_unretained_events() {
        let chunk = concat!(
            "data: {\"type\":\"message.part.delta\",\"properties\":{\"sessionID\":\"ses_1\"}}\n",
            "\n",
        );

        let frames = parse_sse_frames(chunk);

        assert!(frames.is_empty());
    }

    #[test]
    fn filter_message_updated_contract_is_identifier_only() {
        let input = json!({
            "type": "message.updated",
            "properties": {
                "sessionID": "ses_1",
                "messageID": "msg_1",
                "info": {
                    "id": "msg_1",
                    "summary": {
                        "diffs": ["large"]
                    }
                },
                "message": {
                    "parts": [
                        {
                            "type": "text",
                            "text": "large payload"
                        }
                    ]
                }
            }
        });

        let filtered = filter_event(&input).expect("message.updated should be retained");

        assert_eq!(filtered, json!({
            "type": "message.updated",
            "properties": {
                "sessionID": "ses_1",
                "messageID": "msg_1"
            }
        }));
    }

    #[test]
    fn parse_sse_frames_handles_multiple_frames_in_one_chunk() {
        let chunk = concat!(
            "data: {\"type\":\"session.error\",\"properties\":{\"sessionID\":\"ses_1\"}}\n",
            "\n",
            "data: {\"type\":\"message.updated\",\"properties\":{\"sessionID\":\"ses_1\",\"messageID\":\"msg_1\"}}\n",
            "\n",
        );

        let frames = parse_sse_frames(chunk);

        assert_eq!(frames.len(), 2);
        assert_eq!(frames[0]["type"], "session.error");
        assert_eq!(frames[1], json!({
            "type": "message.updated",
            "properties": {
                "sessionID": "ses_1",
                "messageID": "msg_1"
            }
        }));
    }
}

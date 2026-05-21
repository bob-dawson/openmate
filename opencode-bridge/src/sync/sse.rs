use axum::extract::State;
use axum::response::sse::{Event, KeepAlive, Sse};
use axum::response::IntoResponse;
use async_stream::stream;
use std::convert::Infallible;
use std::pin::Pin;

use crate::state::AppState;

type BoxStream<T> = Pin<Box<dyn tokio_stream::Stream<Item = T> + Send + 'static>>;

pub async fn sync_sse(
    State(state): State<AppState>,
) -> impl IntoResponse {
    let stream = create_sync_sse_stream(state);
    Sse::new(stream).keep_alive(KeepAlive::default())
}

fn create_sync_sse_stream(state: AppState) -> BoxStream<Result<Event, Infallible>> {
    Box::pin(stream! {
        let mut rx = state.event_source.subscribe(&state).await;

        loop {
            match rx.recv().await {
                Ok(event) => {
                    if let Some(notification) = project_sync_notification(&event) {
                        yield Ok(Event::default().data(notification.to_string()));
                    }
                }
                Err(tokio::sync::broadcast::error::RecvError::Lagged(_)) => continue,
                Err(tokio::sync::broadcast::error::RecvError::Closed) => break,
            }
        }
    })
}

#[cfg(test)]
fn filter_bridge_event(input: &serde_json::Value) -> Option<serde_json::Value> {
    crate::events::filter::filter_event(input)
}

fn project_sync_notification(event: &crate::events::filter::SharedEvent) -> Option<serde_json::Value> {
    event.sync_notification.clone()
}

#[cfg(test)]
mod tests {
    use serde_json::json;

    #[test]
    fn retains_session_error_events_for_android_consumers() {
        let input = json!({
            "type": "session.error",
            "properties": {
                "sessionID": "ses_1",
                "error": {
                    "message": "boom",
                    "code": "E_FAIL"
                }
            }
        });

        let output = super::filter_bridge_event(&input).expect("session.error should be retained");

        assert_eq!(
            output,
            json!({
                "type": "session.error",
                "properties": {
                    "sessionID": "ses_1",
                    "error": {
                        "message": "boom",
                        "code": "E_FAIL"
                    }
                }
            })
        );
    }

    #[test]
    fn drops_high_volume_message_part_delta_events() {
        let input = json!({
            "type": "message.part.delta",
            "properties": {
                "sessionID": "ses_1",
                "messageID": "msg_1",
                "partID": "part_1",
                "delta": "streamed text"
            }
        });

        let output = super::filter_bridge_event(&input);

        assert!(output.is_none());
    }

    #[test]
    fn trims_message_updated_events_to_identifier_only_shape() {
        let input = json!({
            "type": "message.updated",
            "properties": {
                "sessionID": "ses_1",
                "messageID": "msg_1",
                "message": {
                    "id": "msg_1",
                    "parts": [
                        {
                            "type": "text",
                            "text": "large payload"
                        }
                    ]
                },
                "unused": "drop me"
            }
        });

        let output = super::filter_bridge_event(&input).expect("message.updated should be retained");

        assert_eq!(
            output,
            json!({
                "type": "message.updated",
                "properties": {
                    "sessionID": "ses_1",
                    "messageID": "msg_1"
                }
            })
        );
    }
}

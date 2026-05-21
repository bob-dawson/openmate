use axum::Router;
use axum::routing::get;
use futures::StreamExt;
use tower::ServiceExt;

use openmate::auth;
use openmate::events::source::SharedEventSource;
use openmate::bridge_db::BridgeDb;
use openmate::events;
use openmate::log_capture::create_shared_buffer;
use openmate::state::create_app_state_with_db_and_event_source;
use openmate::sync;
use serde_json::json;
use std::sync::Arc;
use std::time::Duration;

#[tokio::test]
async fn bridge_events_endpoint_requires_auth() {
    let state = create_app_state_with_db_and_event_source(create_shared_buffer(), None, None);
    let app = Router::new()
        .route("/api/bridge/events", get(events::router::events_sse))
        .layer(axum::middleware::from_fn_with_state(
            state.clone(),
            auth::middleware::auth_middleware,
        ))
        .with_state(state);

    let req = axum::http::Request::builder()
        .uri("/api/bridge/events")
        .body(axum::body::Body::empty())
        .unwrap();

    let resp = app.oneshot(req).await.unwrap();

    assert_eq!(resp.status(), 401);
}

#[tokio::test]
async fn bridge_events_endpoint_streams_retained_drops_unretained_and_trims_message_updated() {
    let source = Arc::new(SharedEventSource::new_inert());
    let db = temp_bridge_db();
    db.init_default_configs().unwrap();

    let state = create_app_state_with_db_and_event_source(
        create_shared_buffer(),
        Some(db),
        Some(source.clone()),
    );

    let app = Router::new()
        .route("/api/bridge/events", get(events::router::events_sse))
        .with_state(state);

    let req = axum::http::Request::builder()
        .uri("/api/bridge/events")
        .body(axum::body::Body::empty())
        .unwrap();

    let resp = app.oneshot(req).await.unwrap();
    assert_eq!(resp.status(), 200);

    let mut body = resp.into_body().into_data_stream();
    let publisher = tokio::spawn(async move {
        tokio::time::sleep(Duration::from_millis(50)).await;

        source.publish(json!({
            "type": "session.error",
            "properties": {
                "sessionID": "ses_1",
                "error": { "message": "boom" }
            }
        }));
        source.publish(json!({
            "type": "message.part.delta",
            "properties": {
                "sessionID": "ses_1",
                "delta": "drop"
            }
        }));
        source.publish(json!({
            "type": "message.updated",
            "properties": {
                "sessionID": "ses_1",
                "message": {
                    "id": "msg_1",
                    "parts": [{"type": "text", "text": "large"}]
                }
            }
        }));
    });

    let first_chunk = tokio::time::timeout(Duration::from_secs(1), body.next())
        .await
        .expect("timed out waiting for SSE chunk")
        .expect("stream ended unexpectedly")
        .expect("failed to read SSE chunk");
    let second_chunk = tokio::time::timeout(Duration::from_secs(1), body.next())
        .await
        .expect("timed out waiting for second SSE chunk")
        .expect("stream ended unexpectedly")
        .expect("failed to read second SSE chunk");
    publisher.await.expect("publisher task failed");

    let text = format!(
        "{}{}",
        String::from_utf8(first_chunk.to_vec()).expect("chunk should be utf-8"),
        String::from_utf8(second_chunk.to_vec()).expect("chunk should be utf-8"),
    );

    let events: Vec<serde_json::Value> = text
        .lines()
        .filter_map(|line| line.strip_prefix("data: "))
        .map(|json| serde_json::from_str(json).expect("SSE data line should be valid JSON"))
        .collect();

    assert!(events.iter().any(|event| {
        event == &json!({
            "type": "session.error",
            "properties": {
                "sessionID": "ses_1",
                "error": { "message": "boom" }
            }
        })
    }));
    assert!(!events.iter().any(|event| event["type"] == "message.part.delta"));
    assert!(events.iter().any(|event| {
        event == &json!({
            "type": "message.updated",
            "properties": {
                "sessionID": "ses_1",
                "messageID": "msg_1"
            }
        })
    }));
}

#[tokio::test]
async fn bridge_events_endpoint_preserves_directory_and_session_next_rules() {
    let source = Arc::new(SharedEventSource::new_inert());
    let db = temp_bridge_db();
    db.init_default_configs().unwrap();

    let state = create_app_state_with_db_and_event_source(
        create_shared_buffer(),
        Some(db),
        Some(source.clone()),
    );

    let app = Router::new()
        .route("/api/bridge/events", get(events::router::events_sse))
        .with_state(state);

    let req = axum::http::Request::builder()
        .uri("/api/bridge/events")
        .body(axum::body::Body::empty())
        .unwrap();

    let resp = app.oneshot(req).await.unwrap();
    assert_eq!(resp.status(), 200);

    let mut body = resp.into_body().into_data_stream();
    let publisher = tokio::spawn(async move {
        tokio::time::sleep(Duration::from_millis(50)).await;

        source.publish(json!({
            "directory": "D:/repo",
            "payload": {
                "type": "sync",
                "syncEvent": {
                    "type": "session.next.step.started.1",
                    "data": {
                        "sessionID": "ses_1",
                        "timestamp": 1,
                        "agent": "code"
                    }
                }
            }
        }));

        source.publish(json!({
            "directory": "D:/repo",
            "payload": {
                "type": "sync",
                "syncEvent": {
                    "type": "session.next.text.delta.1",
                    "data": {
                        "sessionID": "ses_1",
                        "delta": "drop"
                    }
                }
            }
        }));
    });

    let chunk = tokio::time::timeout(Duration::from_secs(1), body.next())
        .await
        .expect("timed out waiting for SSE chunk")
        .expect("stream ended unexpectedly")
        .expect("failed to read SSE chunk");
    publisher.await.expect("publisher task failed");

    let text = String::from_utf8(chunk.to_vec()).expect("chunk should be utf-8");
    let events: Vec<serde_json::Value> = text
        .lines()
        .filter_map(|line| line.strip_prefix("data: "))
        .map(|json| serde_json::from_str(json).expect("SSE data line should be valid JSON"))
        .collect();

    assert!(events.iter().any(|event| {
        event["type"] == "session.next.step.started"
            && event["properties"]["sessionID"] == "ses_1"
            && event["properties"]["directory"] == "D:/repo"
    }));
    assert!(!events.iter().any(|event| event["type"] == "session.next.text.delta"));
}

#[tokio::test]
async fn legacy_sync_events_endpoint_still_emits_notifications_for_unretained_sync_events() {
    let source = Arc::new(SharedEventSource::new_inert());
    let db = temp_bridge_db();
    db.init_default_configs().unwrap();

    let state = create_app_state_with_db_and_event_source(
        create_shared_buffer(),
        Some(db),
        Some(source.clone()),
    );

    let app = Router::new()
        .route("/api/bridge/sync/events", get(sync::sse::sync_sse))
        .with_state(state);

    let req = axum::http::Request::builder()
        .uri("/api/bridge/sync/events")
        .body(axum::body::Body::empty())
        .unwrap();

    let resp = app.oneshot(req).await.unwrap();
    assert_eq!(resp.status(), 200);

    let mut body = resp.into_body().into_data_stream();
    let publisher = tokio::spawn(async move {
        tokio::time::sleep(Duration::from_millis(20)).await;

        source.publish(json!({
            "payload": {
                "type": "sync",
                "syncEvent": {
                    "type": "session.next.text.delta.1",
                    "seq": 42,
                    "aggregateID": "ses_1",
                    "data": {
                        "sessionID": "ses_1",
                        "delta": "streamed text"
                    }
                }
            }
        }));
    });

    let chunk = tokio::time::timeout(Duration::from_millis(250), body.next())
        .await
        .expect("timed out waiting for legacy sync SSE chunk")
        .expect("stream ended unexpectedly")
        .expect("failed to read legacy sync SSE chunk");
    publisher.await.expect("publisher task failed");

    let text = String::from_utf8(chunk.to_vec()).expect("chunk should be utf-8");
    let events: Vec<serde_json::Value> = text
        .lines()
        .filter_map(|line| line.strip_prefix("data: "))
        .map(|json| serde_json::from_str(json).expect("SSE data line should be valid JSON"))
        .collect();

    assert_eq!(events, vec![json!({
        "sessionID": "ses_1",
        "seq": 42
    })]);
}

#[test]
fn bridge_events_stream_emits_retained_event() {
    let frames = events::router::test_parse_bridge_events_frames(concat!(
        "data: {\"type\":\"session.error\",\"properties\":{\"sessionID\":\"ses_1\",\"error\":{\"message\":\"boom\"}}}\n",
        "\n",
    ));

    assert_eq!(frames, vec![json!({
        "type": "session.error",
        "properties": {
            "sessionID": "ses_1",
            "error": {
                "message": "boom"
            }
        }
    })]);
}

#[test]
fn bridge_events_stream_drops_unretained_event() {
    let frames = events::router::test_parse_bridge_events_frames(concat!(
        "data: {\"type\":\"message.part.delta\",\"properties\":{\"sessionID\":\"ses_1\"}}\n",
        "\n",
    ));

    assert!(frames.is_empty());
}

#[test]
fn bridge_events_stream_trims_message_updated_shape() {
    let frames = events::router::test_parse_bridge_events_frames(concat!(
        "data: {\"type\":\"message.updated\",\"properties\":{\"sessionID\":\"ses_1\",\"message\":{\"id\":\"msg_1\",\"parts\":[{\"type\":\"text\",\"text\":\"large\"}]}}}\n",
        "\n",
    ));

    assert_eq!(frames, vec![json!({
        "type": "message.updated",
        "properties": {
            "sessionID": "ses_1",
            "messageID": "msg_1"
        }
    })]);
}

fn temp_bridge_db() -> BridgeDb {
    let dir = tempfile::tempdir().expect("Failed to create temp dir");
    let db_path = dir.path().join("test_bridge.db");
    let db = BridgeDb::open_at(&db_path).expect("Failed to open test DB");
    std::mem::forget(dir);
    db
}

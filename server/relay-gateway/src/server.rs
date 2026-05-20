use std::collections::HashMap;
use std::convert::Infallible;
use std::time::Duration;

use axum::body::Body;
use axum::extract::ws::{Message, WebSocket, WebSocketUpgrade};
use axum::extract::State;
use axum::http::Request;
use axum::response::{IntoResponse, Response, Sse};
use axum::routing::get;
use axum::Router;
use futures::{SinkExt, StreamExt};
use http_body_util::BodyStream;
use tokio::sync::mpsc;
use tower_http::cors::CorsLayer;
use tower_http::trace::TraceLayer;

use crate::error::GatewayError;
use crate::state::SharedState;
use crate::tunnel::frame::TunnelFrame;

async fn ws_handler(
    ws: WebSocketUpgrade,
    State(state): State<SharedState>,
) -> impl IntoResponse {
    ws.on_upgrade(move |socket| handle_bridge_ws(socket, state))
}

async fn handle_bridge_ws(socket: WebSocket, state: SharedState) {
    let (mut sink, mut stream) = socket.split();

    let (tx, mut rx) = mpsc::unbounded_channel::<TunnelFrame>();
    let (binary_tx, mut binary_rx) = mpsc::unbounded_channel::<(String, Vec<u8>)>();

    let send_task = tokio::spawn(async move {
        loop {
            tokio::select! {
                Some(frame) = rx.recv() => {
                    let msg = match serde_json::to_string(&frame) {
                        Ok(json) => Message::Text(json.into()),
                        Err(e) => {
                            tracing::error!("failed to serialize frame: {e}");
                            continue;
                        }
                    };
                    if sink.send(msg).await.is_err() {
                        break;
                    }
                }
                Some((rid, data)) = binary_rx.recv() => {
                    let mut msg = Vec::with_capacity(36 + data.len());
                    msg.extend_from_slice(rid.as_bytes());
                    msg.extend_from_slice(&data);
                    if sink.send(Message::Binary(msg.into())).await.is_err() {
                        break;
                    }
                }
                else => break,
            }
        }
    });

    let recv_state = state.clone();
    let instance_id_holder: std::sync::Arc<tokio::sync::Mutex<Option<String>>> =
        std::sync::Arc::new(tokio::sync::Mutex::new(None));

    while let Some(msg) = futures::StreamExt::next(&mut stream).await {
        let msg = match msg {
            Ok(m) => m,
            Err(e) => {
                tracing::debug!("ws read error: {e}");
                break;
            }
        };

        match msg {
            Message::Text(text) => {
                let frame: TunnelFrame = match serde_json::from_str(&text) {
                    Ok(f) => f,
                    Err(e) => {
                        tracing::warn!("failed to parse ws frame: {e}");
                        continue;
                    }
                };

                match frame.frame_type.as_str() {
                    "register" => {
                        let instance_id = match &frame.instance_id {
                            Some(id) => id.clone(),
                            None => {
                                tracing::warn!("register frame without instance_id");
                                continue;
                            }
                        };

                        *instance_id_holder.lock().await = Some(instance_id.clone());
                        crate::tunnel::bridge::handle_register(&recv_state, instance_id, tx.clone(), binary_tx.clone());

                        if tx.send(TunnelFrame::pong()).is_err() {
                            break;
                        }
                    }
                    "ping" => {
                        let holder = instance_id_holder.lock().await;
                        if let Some(ref iid) = *holder {
                            crate::tunnel::bridge::handle_heartbeat(&recv_state, iid);
                        }
                        drop(holder);

                        if tx.send(TunnelFrame::pong()).is_err() {
                            break;
                        }
                    }
                    "response_start" => {
                        crate::proxy::http::handle_response_start(&recv_state, frame);
                    }
                    "response_chunk" => {}
                    "response_end" => {
                        crate::proxy::http::handle_response_end(&recv_state, frame);
                    }
                    "sse_event" => {
                        crate::proxy::sse::handle_sse_event(&recv_state, &frame);
                    }
                    "sse_close" => {
                        crate::proxy::sse::handle_sse_close(&recv_state, &frame);
                    }
                    other => {
                        tracing::warn!(frame_type = %other, "unknown frame type");
                    }
                }
            }
            Message::Binary(data) => {
                if data.len() < 36 {
                    tracing::warn!("binary ws message too short, ignoring");
                    continue;
                }
                let rid_bytes = &data[..36];
                let chunk = data[36..].to_vec();
                let request_id = String::from_utf8_lossy(rid_bytes);
                crate::proxy::http::handle_stream_chunk(&recv_state, &request_id, chunk);
            }
            Message::Close(_) => break,
            _ => {}
        }
    }

    {
        let holder = instance_id_holder.lock().await;
        if let Some(ref iid) = *holder {
            crate::tunnel::bridge::remove_bridge(&recv_state, iid);
        }
    }

    send_task.abort();
}

async fn proxy_handler(
    State(state): State<SharedState>,
    req: Request<Body>,
) -> Result<Response, crate::error::GatewayError> {
    let headers = req.headers().clone();
    let method = req.method().clone();
    let path_only = req.uri().path().to_string();
    let full_path = req.uri().path_and_query().map(|pq| pq.as_str().to_string()).unwrap_or_else(|| path_only.clone());

    let instance_id = headers
        .get("x-instance-id")
        .and_then(|v| v.to_str().ok())
        .ok_or_else(|| {
            crate::error::GatewayError::BadRequest("Missing X-Instance-Id header".to_string())
        })?
        .to_string();

    let is_upload = path_only.starts_with("/api/bridge/fs/upload");

    let is_download = path_only.starts_with("/api/bridge/fs/download")
        || path_only.starts_with("/files/");

    let is_sse = path_only == "/global/event"
        || headers
            .get("accept")
            .and_then(|v| v.to_str().ok())
            .map(|v| v.contains("text/event-stream"))
            .unwrap_or(false);

    if is_sse {
        let (event_tx, mut event_rx) = mpsc::unbounded_channel::<String>();

        let req_headers: HashMap<String, String> = headers
            .iter()
            .filter_map(|(k, v)| v.to_str().ok().map(|s| (k.to_string(), s.to_string())))
            .collect();

        let _request_id = crate::proxy::sse::open_sse_tunnel(
            &state,
            &instance_id,
            &full_path,
            Some(req_headers),
            event_tx,
        )?;

        let stream = async_stream::stream! {
            while let Some(data) = event_rx.recv().await {
                yield Ok::<_, Infallible>(axum::response::sse::Event::default().data(data));
            }
        };

        let sse_response = Sse::new(stream).keep_alive(
            axum::response::sse::KeepAlive::new()
                .interval(std::time::Duration::from_secs(15)),
        );

        Ok(sse_response.into_response())
    } else if is_upload {
        let binary_tx = crate::tunnel::bridge::get_bridge_binary_tx(&state, &instance_id)
            .ok_or_else(|| {
                GatewayError::BridgeOffline(format!("bridge '{}' is offline", instance_id))
            })?;

        let tx = crate::tunnel::bridge::get_bridge_tx(&state, &instance_id)
            .ok_or_else(|| {
                GatewayError::BridgeOffline(format!("bridge '{}' is offline", instance_id))
            })?;

        let request_id = uuid::Uuid::new_v4().to_string();
        let (start_tx, start_rx) = tokio::sync::oneshot::channel();
        let (chunk_tx, chunk_rx) = mpsc::unbounded_channel();

        state.pending_streams.insert(
            request_id.clone(),
            crate::state::PendingStream {
                start_tx: Some(start_tx),
                chunk_tx,
            },
        );

        let req_headers: HashMap<String, String> = headers
            .iter()
            .filter_map(|(k, v)| v.to_str().ok().map(|s| (k.to_string(), s.to_string())))
            .collect();

        let start_frame = TunnelFrame::request_start(
            &request_id, method.as_str(), &full_path, Some(req_headers),
        );
        if tx.send(start_frame).is_err() {
            state.pending_streams.remove(&request_id);
            return Err(GatewayError::BridgeOffline("bridge connection lost".into()));
        }

        let mut body_stream = BodyStream::new(req.into_body());
        while let Some(frame_result) = body_stream.next().await {
            let data = match frame_result {
                Ok(frame) => {
                    if let Ok(data) = frame.into_data() {
                        data
                    } else {
                        continue;
                    }
                }
                Err(e) => {
                    tracing::error!("Upload body read error: {}", e);
                    let _ = tx.send(TunnelFrame::request_end(&request_id));
                    state.pending_streams.remove(&request_id);
                    return Err(GatewayError::BadRequest(format!("body read error: {e}")));
                }
            };
            if binary_tx.send((request_id.clone(), data.to_vec())).is_err() {
                state.pending_streams.remove(&request_id);
                return Err(GatewayError::BridgeOffline("bridge connection lost".into()));
            }
        }

        let _ = tx.send(TunnelFrame::request_end(&request_id));

        let timeout = Duration::from_secs(state.config.tunnel.request_timeout);
        let (status, resp_headers) = match tokio::time::timeout(timeout, start_rx).await {
            Ok(Ok(r)) => r,
            Ok(Err(_)) => {
                state.pending_streams.remove(&request_id);
                return Err(GatewayError::Internal("response channel dropped".into()));
            }
            Err(_) => {
                state.pending_streams.remove(&request_id);
                return Err(GatewayError::Timeout(format!(
                    "upload request {} timed out", request_id
                )));
            }
        };

        let mut builder = axum::response::Response::builder().status(status);
        for (k, v) in &resp_headers {
            if let Ok(name) = axum::http::header::HeaderName::from_bytes(k.as_bytes()) {
                if let Ok(val) = axum::http::header::HeaderValue::from_str(v) {
                    builder = builder.header(name, val);
                }
            }
        }

        let body = Body::from_stream(
            futures::stream::unfold(chunk_rx, |mut rx| async move {
                rx.recv().await.map(|chunk| (Ok::<_, Infallible>(chunk), rx))
            }),
        );
        Ok(builder.body(body).unwrap())
    } else if is_download {
        let body_bytes = axum::body::to_bytes(req.into_body(), state.config.tunnel.max_request_body)
            .await
            .map_err(|e| crate::error::GatewayError::BadRequest(format!("body read error: {e}")))?;

        let body_str = if body_bytes.is_empty() {
            None
        } else {
            Some(String::from_utf8_lossy(&body_bytes).to_string())
        };

        let req_headers: HashMap<String, String> = headers
            .iter()
            .filter_map(|(k, v)| v.to_str().ok().map(|s| (k.to_string(), s.to_string())))
            .collect();

        let (status, resp_headers, chunk_rx) = crate::proxy::http::proxy_http_request_streaming(
            state,
            &instance_id,
            method.as_str(),
            &full_path,
            Some(req_headers),
            body_str,
        )
        .await?;

        let mut builder = axum::response::Response::builder().status(status);
        for (k, v) in &resp_headers {
            if let Ok(name) = axum::http::header::HeaderName::from_bytes(k.as_bytes()) {
                if let Ok(val) = axum::http::header::HeaderValue::from_str(v) {
                    builder = builder.header(name, val);
                }
            }
        }

        let body = Body::from_stream(
            futures::stream::unfold(chunk_rx, |mut rx| async move {
                rx.recv().await.map(|chunk| (Ok::<_, Infallible>(chunk), rx))
            }),
        );
        Ok(builder.body(body).unwrap())
    } else {
        let body_bytes = axum::body::to_bytes(req.into_body(), state.config.tunnel.max_request_body)
            .await
            .map_err(|e| crate::error::GatewayError::BadRequest(format!("body read error: {e}")))?;

        let body_str = if body_bytes.is_empty() {
            None
        } else {
            Some(String::from_utf8_lossy(&body_bytes).to_string())
        };

        let req_headers: HashMap<String, String> = headers
            .iter()
            .filter_map(|(k, v)| v.to_str().ok().map(|s| (k.to_string(), s.to_string())))
            .collect();

        let (status, resp_headers, chunk_rx) = crate::proxy::http::proxy_http_request_streaming(
            state,
            &instance_id,
            method.as_str(),
            &full_path,
            Some(req_headers),
            body_str,
        )
        .await?;

        let mut builder = axum::response::Response::builder().status(status);
        for (k, v) in &resp_headers {
            if let Ok(name) = axum::http::header::HeaderName::from_bytes(k.as_bytes()) {
                if let Ok(val) = axum::http::header::HeaderValue::from_str(v) {
                    builder = builder.header(name, val);
                }
            }
        }

        let body = Body::from_stream(
            futures::stream::unfold(chunk_rx, |mut rx| async move {
                rx.recv().await.map(|chunk| (Ok::<_, Infallible>(chunk), rx))
            }),
        );
        Ok(builder.body(body).unwrap())
    }
}

pub fn build_app(state: SharedState) -> Router {
    Router::new()
        .route("/api/gateway/health", get(crate::api::status::health))
        .route("/api/gateway/status", get(crate::api::status::status))
        .route("/ws", get(ws_handler))
        .fallback(proxy_handler)
        .layer(TraceLayer::new_for_http())
        .layer(CorsLayer::permissive())
        .with_state(state)
}

pub async fn run_server(state: SharedState) -> anyhow::Result<()> {
    let addr = state.config.listen_addr();
    let listener = tokio::net::TcpListener::bind(&addr).await?;
    tracing::info!("relay-gateway listening on {addr}");

    let heartbeat_state = state.clone();
    let heartbeat_timeout = state.config.tunnel.heartbeat_timeout;
    tokio::spawn(async move {
        let mut interval = tokio::time::interval(std::time::Duration::from_secs(heartbeat_timeout));
        loop {
            interval.tick().await;
            crate::tunnel::bridge::cleanup_stale_bridges(&heartbeat_state, heartbeat_timeout);
        }
    });

    let app = build_app(state);
    axum::serve(listener, app).await?;
    Ok(())
}

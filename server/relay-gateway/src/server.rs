use std::collections::HashMap;
use std::convert::Infallible;

use axum::body::Body;
use axum::extract::ws::{Message, WebSocket, WebSocketUpgrade};
use axum::extract::State;
use axum::http::Request;
use axum::response::{IntoResponse, Response, Sse};
use axum::routing::get;
use axum::Router;
use futures::{SinkExt, StreamExt};
use tokio::sync::mpsc;
use tower_http::cors::CorsLayer;
use tower_http::trace::TraceLayer;

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

    let send_task = tokio::spawn(async move {
        while let Some(frame) = rx.recv().await {
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

        let text = match msg {
            Message::Text(t) => t,
            Message::Close(_) => break,
            _ => continue,
        };

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

                let token = frame.token.as_deref().unwrap_or("");
                if !crate::auth::validate_token(token, &recv_state.secret_key) {
                    tracing::warn!(instance_id = %instance_id, "bridge register with invalid token");
                    let _ = tx.send(TunnelFrame::error(None, 401, "invalid token"));
                    continue;
                }

                *instance_id_holder.lock().await = Some(instance_id.clone());
                crate::tunnel::bridge::handle_register(&recv_state, instance_id, tx.clone());

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
            "response" => {
                crate::proxy::http::handle_tunnel_response(&recv_state, frame);
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
    let path = req.uri().path().to_string();

    let token = headers
        .get("authorization")
        .and_then(|v| v.to_str().ok())
        .and_then(|v| v.strip_prefix("Bearer "))
        .ok_or_else(|| {
            crate::error::GatewayError::Unauthorized(
                "Missing or invalid Authorization header".to_string(),
            )
        })?;

    if !crate::auth::validate_token(token, &state.secret_key) {
        return Err(crate::error::GatewayError::Unauthorized(
            "Invalid token".to_string(),
        ));
    }

    let instance_id = headers
        .get("x-instance-id")
        .and_then(|v| v.to_str().ok())
        .ok_or_else(|| {
            crate::error::GatewayError::BadRequest("Missing X-Instance-Id header".to_string())
        })?
        .to_string();

    let is_sse = path == "/global/event"
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
            &path,
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
                .interval(std::time::Duration::from_secs(15))
                .text("ping"),
        );

        Ok(sse_response.into_response())
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

        let response = crate::proxy::http::proxy_http_request(
            state,
            &instance_id,
            method.as_str(),
            &path,
            Some(req_headers),
            body_str,
        )
        .await?;

        let mut builder = axum::response::Response::builder().status(response.status);
        for (k, v) in &response.headers {
            if let Ok(name) = axum::http::header::HeaderName::from_bytes(k.as_bytes()) {
                if let Ok(val) = axum::http::header::HeaderValue::from_str(v) {
                    builder = builder.header(name, val);
                }
            }
        }

        let body = Body::from(response.body);
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

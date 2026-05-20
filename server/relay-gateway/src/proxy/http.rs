use std::collections::HashMap;
use std::time::Duration;

use tokio::sync::oneshot;

use crate::error::GatewayError;
use crate::state::{SharedState, TunnelResponse};
use crate::tunnel::frame::TunnelFrame;

pub async fn proxy_http_request(
    state: SharedState,
    instance_id: &str,
    method: &str,
    path: &str,
    headers: Option<HashMap<String, String>>,
    body: Option<String>,
) -> Result<TunnelResponse, GatewayError> {
    let tx = crate::tunnel::bridge::get_bridge_tx(&state, instance_id).ok_or_else(|| {
        GatewayError::BridgeOffline(format!("bridge '{}' is offline", instance_id))
    })?;

    let request_id = uuid::Uuid::new_v4().to_string();
    let (responder, receiver) = oneshot::channel();

    state.pending_requests.insert(
        request_id.clone(),
        crate::state::PendingRequest { responder },
    );

    let frame = TunnelFrame::request(&request_id, method, path, headers, body);
    if tx.send(frame).is_err() {
        state.pending_requests.remove(&request_id);
        return Err(GatewayError::BridgeOffline(format!(
            "bridge '{}' connection lost",
            instance_id
        )));
    }

    let timeout = Duration::from_secs(state.config.tunnel.request_timeout);
    match tokio::time::timeout(timeout, receiver).await {
        Ok(Ok(response)) => Ok(response),
        Ok(Err(_)) => {
            state.pending_requests.remove(&request_id);
            Err(GatewayError::Internal("request channel dropped".to_string()))
        }
        Err(_) => {
            state.pending_requests.remove(&request_id);
            Err(GatewayError::Timeout(format!(
                "request {} timed out after {}s",
                request_id, timeout.as_secs()
            )))
        }
    }
}

pub fn handle_tunnel_response(state: &SharedState, frame: TunnelFrame) {
    let request_id = match &frame.request_id {
        Some(id) => id.clone(),
        None => {
            tracing::warn!("response frame without request_id");
            return;
        }
    };

    if let Some((_, pending)) = state.pending_requests.remove(&request_id) {
        let headers_vec = frame
            .headers
            .unwrap_or_default()
            .into_iter()
            .collect();

        let response = TunnelResponse {
            status: frame.status.unwrap_or(502),
            headers: headers_vec,
            body: frame.body.unwrap_or_default(),
        };

        if pending.responder.send(response).is_err() {
            tracing::warn!(request_id = %request_id, "response dropped, requester already gone");
        }
    } else {
        tracing::warn!(request_id = %request_id, "response for unknown request");
    }
}

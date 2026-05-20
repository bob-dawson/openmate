use std::collections::HashMap;

use tokio::sync::mpsc;

use crate::error::GatewayError;
use crate::state::{SseStream, SharedState};
use crate::tunnel::frame::TunnelFrame;

pub fn open_sse_tunnel(
    state: &SharedState,
    instance_id: &str,
    path: &str,
    headers: Option<HashMap<String, String>>,
    event_tx: mpsc::UnboundedSender<String>,
) -> Result<String, GatewayError> {
    let tx = crate::tunnel::bridge::get_bridge_tx(state, instance_id).ok_or_else(|| {
        GatewayError::BridgeOffline(format!("bridge '{}' is offline", instance_id))
    })?;

    let request_id = uuid::Uuid::new_v4().to_string();

    state.pending_sse.insert(
        request_id.clone(),
        SseStream { event_tx },
    );

    let frame = TunnelFrame::sse_open(&request_id, path, headers);
    if tx.send(frame).is_err() {
        state.pending_sse.remove(&request_id);
        return Err(GatewayError::BridgeOffline(format!(
            "bridge '{}' connection lost",
            instance_id
        )));
    }

    tracing::info!(request_id = %request_id, instance_id = %instance_id, path = %path, "SSE tunnel opened");
    Ok(request_id)
}

pub fn handle_sse_event(state: &SharedState, frame: &TunnelFrame) {
    let request_id = match &frame.request_id {
        Some(id) => id.clone(),
        None => {
            tracing::warn!("sse_event frame without request_id");
            return;
        }
    };

    if let Some(stream) = state.pending_sse.get(&request_id) {
        let data = frame.data.as_deref().unwrap_or("");
        if stream.event_tx.send(data.to_string()).is_err() {
            tracing::warn!(request_id = %request_id, "SSE receiver dropped, cleaning up");
            drop(stream);
            state.pending_sse.remove(&request_id);
        }
    } else {
        tracing::warn!(request_id = %request_id, "sse_event for unknown stream");
    }
}

pub fn handle_sse_close(state: &SharedState, frame: &TunnelFrame) {
    let request_id = match &frame.request_id {
        Some(id) => id.clone(),
        None => {
            tracing::warn!("sse_close frame without request_id");
            return;
        }
    };

    if state.pending_sse.remove(&request_id).is_some() {
        tracing::info!(request_id = %request_id, "SSE stream closed");
    } else {
        tracing::warn!(request_id = %request_id, "sse_close for unknown stream");
    }
}

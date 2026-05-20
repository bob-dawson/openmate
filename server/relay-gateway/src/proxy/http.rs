use std::collections::HashMap;
use std::time::Duration;

use tokio::sync::{mpsc, oneshot};

use crate::error::GatewayError;
use crate::state::{PendingStream, SharedState};
use crate::tunnel::frame::TunnelFrame;

pub async fn proxy_http_request_streaming(
    state: SharedState,
    instance_id: &str,
    method: &str,
    path: &str,
    headers: Option<HashMap<String, String>>,
    body: Option<String>,
) -> Result<(u16, Vec<(String, String)>, mpsc::UnboundedReceiver<Vec<u8>>), GatewayError> {
    let tx = crate::tunnel::bridge::get_bridge_tx(&state, instance_id).ok_or_else(|| {
        GatewayError::BridgeOffline(format!("bridge '{}' is offline", instance_id))
    })?;

    let request_id = uuid::Uuid::new_v4().to_string();
    let (start_tx, start_rx) = oneshot::channel();
    let (chunk_tx, chunk_rx) = mpsc::unbounded_channel();

    state.pending_streams.insert(
        request_id.clone(),
        PendingStream {
            start_tx: Some(start_tx),
            chunk_tx,
        },
    );

    let frame = TunnelFrame::request(&request_id, method, path, headers, body);
    if tx.send(frame).is_err() {
        state.pending_streams.remove(&request_id);
        return Err(GatewayError::BridgeOffline(format!(
            "bridge '{}' connection lost",
            instance_id
        )));
    }

    let timeout = Duration::from_secs(state.config.tunnel.request_timeout);
    match tokio::time::timeout(timeout, start_rx).await {
        Ok(Ok((status, headers))) => Ok((status, headers, chunk_rx)),
        Ok(Err(_)) => {
            state.pending_streams.remove(&request_id);
            Err(GatewayError::Internal("stream channel dropped".to_string()))
        }
        Err(_) => {
            state.pending_streams.remove(&request_id);
            Err(GatewayError::Timeout(format!(
                "stream request {} timed out after {}s",
                request_id, timeout.as_secs()
            )))
        }
    }
}

pub fn handle_response_start(state: &SharedState, frame: TunnelFrame) {
    let request_id = match &frame.request_id {
        Some(id) => id.clone(),
        None => {
            tracing::warn!("response_start frame without request_id");
            return;
        }
    };

    if let Some(mut stream) = state.pending_streams.get_mut(&request_id) {
        if let Some(start_tx) = stream.start_tx.take() {
            let status = frame.status.unwrap_or(200);
            let headers: Vec<(String, String)> = frame
                .headers
                .clone()
                .unwrap_or_default()
                .into_iter()
                .collect();
            let _ = start_tx.send((status, headers));
        }
    } else {
        tracing::warn!(request_id = %request_id, "response_start for unknown stream");
    }
}

pub fn handle_stream_chunk(state: &SharedState, request_id: &str, data: Vec<u8>) {
    if let Some(stream) = state.pending_streams.get(request_id) {
        if stream.chunk_tx.send(data).is_err() {
            tracing::warn!(request_id = %request_id, "stream chunk receiver dropped");
        }
    } else {
        tracing::warn!(request_id = %request_id, "chunk for unknown stream");
    }
}

pub fn handle_response_end(state: &SharedState, frame: TunnelFrame) {
    let request_id = match &frame.request_id {
        Some(id) => id.clone(),
        None => {
            tracing::warn!("response_end frame without request_id");
            return;
        }
    };

    if state.pending_streams.remove(&request_id).is_some() {
        tracing::debug!(request_id = %request_id, "stream ended");
    } else {
        tracing::warn!(request_id = %request_id, "response_end for unknown stream");
    }
}

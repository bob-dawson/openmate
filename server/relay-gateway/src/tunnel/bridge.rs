use std::time::Duration;

use tokio::sync::mpsc;

use crate::state::{BridgeConn, SharedState};
use crate::tunnel::frame::TunnelFrame;

pub fn handle_register(
    state: &SharedState,
    instance_id: String,
    tx: mpsc::UnboundedSender<TunnelFrame>,
    binary_tx: mpsc::UnboundedSender<(String, Vec<u8>)>,
) {
    if let Some(old) = state.bridges.insert(
        instance_id.clone(),
        BridgeConn {
            tx,
            binary_tx,
            instance_id: instance_id.clone(),
            last_heartbeat: std::time::Instant::now(),
        },
    ) {
        tracing::warn!(instance_id = %instance_id, "replacing existing bridge connection");
        let _ = old.tx.send(TunnelFrame::error(None, 409, "replaced by new connection"));
    }
    tracing::info!(instance_id = %instance_id, "bridge registered");
}

pub fn remove_bridge(state: &SharedState, instance_id: &str) {
    if state.bridges.remove(instance_id).is_some() {
        tracing::info!(instance_id = %instance_id, "bridge removed");
    }
}

pub fn handle_heartbeat(state: &SharedState, instance_id: &str) {
    if let Some(mut conn) = state.bridges.get_mut(instance_id) {
        conn.last_heartbeat = std::time::Instant::now();
    }
}

pub fn is_bridge_online(state: &SharedState, instance_id: &str) -> bool {
    state.bridges.contains_key(instance_id)
}

pub fn get_bridge_tx(state: &SharedState, instance_id: &str) -> Option<mpsc::UnboundedSender<TunnelFrame>> {
    state.bridges.get(instance_id).map(|c| c.tx.clone())
}

pub fn get_bridge_binary_tx(state: &SharedState, instance_id: &str) -> Option<mpsc::UnboundedSender<(String, Vec<u8>)>> {
    state.bridges.get(instance_id).map(|c| c.binary_tx.clone())
}

pub fn cleanup_stale_bridges(state: &SharedState, timeout_secs: u64) {
    let now = std::time::Instant::now();
    let cutoff = now - Duration::from_secs(timeout_secs);
    let stale: Vec<(String, u64, mpsc::UnboundedSender<TunnelFrame>)> = state
        .bridges
        .iter()
        .filter(|entry| entry.last_heartbeat < cutoff)
        .map(|entry| {
            let elapsed = now.duration_since(entry.last_heartbeat).as_secs();
            (entry.instance_id.clone(), elapsed, entry.tx.clone())
        })
        .collect();

    for (instance_id, elapsed, tx) in stale {
        tracing::warn!(instance_id = %instance_id, elapsed_secs = elapsed, "removing stale bridge");
        let _ = tx.send(TunnelFrame::error(None, 408, "heartbeat timeout"));
        state.bridges.remove(&instance_id);
    }
}

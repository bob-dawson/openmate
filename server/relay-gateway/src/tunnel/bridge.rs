use std::time::Duration;

use tokio::sync::mpsc;

use crate::state::{BridgeConn, SharedState};
use crate::tunnel::frame::{BridgeOut, TunnelFrame};

pub fn handle_register(
    state: &SharedState,
    instance_id: String,
    tx: mpsc::UnboundedSender<BridgeOut>,
) {
    if let Some(old) = state.bridges.insert(
        instance_id.clone(),
        BridgeConn {
            tx,
            instance_id: instance_id.clone(),
            last_heartbeat: std::time::Instant::now(),
        },
    ) {
        tracing::warn!(instance_id = %instance_id, "replacing existing bridge connection");
        let _ = old.tx.send(BridgeOut::Json(TunnelFrame::error(
            None,
            409,
            "replaced by new connection",
        )));
    }
    tracing::info!(instance_id = %instance_id, "bridge registered");
}

pub fn remove_bridge(
    state: &SharedState,
    instance_id: &str,
    tx: &mpsc::UnboundedSender<BridgeOut>,
) {
    let is_current = match state.bridges.get(instance_id) {
        Some(entry) => entry.tx.same_channel(tx),
        None => false,
    };
    if !is_current {
        tracing::info!(
            instance_id = %instance_id,
            "bridge already replaced, skipping removal"
        );
        return;
    }
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

pub fn get_bridge_tx(
    state: &SharedState,
    instance_id: &str,
) -> Option<mpsc::UnboundedSender<BridgeOut>> {
    state.bridges.get(instance_id).map(|c| c.tx.clone())
}

pub fn cleanup_stale_bridges(state: &SharedState, timeout_secs: u64) {
    let now = std::time::Instant::now();
    let cutoff = now - Duration::from_secs(timeout_secs);
    let stale: Vec<(String, u64, mpsc::UnboundedSender<BridgeOut>)> = state
        .bridges
        .iter()
        .filter(|entry| entry.last_heartbeat < cutoff)
        .map(|entry| {
            let elapsed = now.duration_since(entry.last_heartbeat).as_secs();
            (entry.instance_id.clone(), elapsed, entry.tx.clone())
        })
        .collect();

    for (instance_id, elapsed, tx) in stale {
        let is_current = match state.bridges.get(&instance_id) {
            Some(entry) => entry.tx.same_channel(&tx),
            None => false,
        };
        if !is_current {
            continue;
        }
        tracing::warn!(instance_id = %instance_id, elapsed_secs = elapsed, "removing stale bridge");
        let _ = tx.send(BridgeOut::Json(TunnelFrame::error(
            None,
            408,
            "heartbeat timeout",
        )));
        state.bridges.remove(&instance_id);
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::config::Config;
    use crate::state::GatewayState;
    use std::sync::Arc;
    use tokio::sync::mpsc;

    fn state() -> SharedState {
        Arc::new(GatewayState::new(Config::default()))
    }

    fn sender() -> mpsc::UnboundedSender<BridgeOut> {
        let (tx, _rx) = mpsc::unbounded_channel();
        tx
    }

    #[test]
    fn remove_bridge_keeps_newer_connection() {
        let state = state();
        let old_tx = sender();
        let new_tx = sender();

        handle_register(&state, "inst-1".to_string(), old_tx.clone());
        handle_register(&state, "inst-1".to_string(), new_tx.clone());

        remove_bridge(&state, "inst-1", &old_tx);

        assert!(is_bridge_online(&state, "inst-1"), "newer connection must survive");

        remove_bridge(&state, "inst-1", &new_tx);

        assert!(!is_bridge_online(&state, "inst-1"));
    }

    #[test]
    fn remove_bridge_removes_current_connection() {
        let state = state();
        let tx = sender();

        handle_register(&state, "inst-2".to_string(), tx.clone());
        remove_bridge(&state, "inst-2", &tx);

        assert!(!is_bridge_online(&state, "inst-2"));
    }
}

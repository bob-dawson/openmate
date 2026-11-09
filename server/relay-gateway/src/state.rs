use std::sync::Arc;
use std::time::Instant;
use tokio::sync::{mpsc, oneshot};

use crate::config::Config;
use crate::tunnel::frame::BridgeOut;

pub struct BridgeConn {
    /// Single ordered channel: JSON frames and binary upload chunks share it so
    /// the websocket writer cannot reorder them.
    pub tx: mpsc::UnboundedSender<BridgeOut>,
    pub instance_id: String,
    pub last_heartbeat: Instant,
}

pub struct SseStream {
    pub event_tx: mpsc::UnboundedSender<String>,
}

pub struct PendingStream {
    pub start_tx: Option<oneshot::Sender<(u16, Vec<(String, String)>)>>,
    pub chunk_tx: mpsc::UnboundedSender<Vec<u8>>,
}

pub struct GatewayState {
    pub config: Config,
    pub bridges: dashmap::DashMap<String, BridgeConn>,
    pub pending_sse: dashmap::DashMap<String, SseStream>,
    pub pending_streams: dashmap::DashMap<String, PendingStream>,
}

impl GatewayState {
    pub fn new(config: Config) -> Self {
        GatewayState {
            config,
            bridges: dashmap::DashMap::new(),
            pending_sse: dashmap::DashMap::new(),
            pending_streams: dashmap::DashMap::new(),
        }
    }
}

pub type SharedState = Arc<GatewayState>;

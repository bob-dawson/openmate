use std::sync::Arc;
use std::time::Instant;
use tokio::sync::{mpsc, oneshot};

use crate::config::Config;
use crate::tunnel::frame::TunnelFrame;

pub struct BridgeConn {
    pub tx: mpsc::UnboundedSender<TunnelFrame>,
    pub instance_id: String,
    pub last_heartbeat: Instant,
}

pub struct PendingRequest {
    pub responder: oneshot::Sender<TunnelResponse>,
}

pub struct TunnelResponse {
    pub status: u16,
    pub headers: Vec<(String, String)>,
    pub body: String,
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
    pub pending_requests: dashmap::DashMap<String, PendingRequest>,
    pub pending_sse: dashmap::DashMap<String, SseStream>,
    pub pending_streams: dashmap::DashMap<String, PendingStream>,
}

impl GatewayState {
    pub fn new(config: Config) -> Self {
        GatewayState {
            config,
            bridges: dashmap::DashMap::new(),
            pending_requests: dashmap::DashMap::new(),
            pending_sse: dashmap::DashMap::new(),
            pending_streams: dashmap::DashMap::new(),
        }
    }
}

pub type SharedState = Arc<GatewayState>;

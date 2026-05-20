use std::sync::Arc;
use std::time::Instant;
use tokio::sync::{mpsc, oneshot};

use crate::auth::SecretKey;
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

pub struct GatewayState {
    pub config: Config,
    pub secret_key: SecretKey,
    pub bridges: dashmap::DashMap<String, BridgeConn>,
    pub pending_requests: dashmap::DashMap<String, PendingRequest>,
    pub pending_sse: dashmap::DashMap<String, SseStream>,
}

impl GatewayState {
    pub fn new(config: Config, secret_key: SecretKey) -> Self {
        GatewayState {
            config,
            secret_key,
            bridges: dashmap::DashMap::new(),
            pending_requests: dashmap::DashMap::new(),
            pending_sse: dashmap::DashMap::new(),
        }
    }
}

pub type SharedState = Arc<GatewayState>;

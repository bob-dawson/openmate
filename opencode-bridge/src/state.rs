use axum::extract::FromRef;
use serde::{Deserialize, Serialize};
use std::sync::Arc;
use tokio::sync::RwLock;

use crate::auth;
use crate::bridge_db::BridgeDb;
use crate::config::Config;
use crate::log_capture::{SharedLogBuffer, create_shared_buffer};
use crate::process::OpencodeManager;
use crate::sync::db::SyncDb;

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum OpencodeStatus {
    Stopped,
    Starting,
    Running,
    Stopping,
    Crashed,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct ScanTokenEntry {
    pub token: String,
    pub expires_at: i64,
}

pub struct AppStateInner {
    pub config: Config,
    pub opencode_status: RwLock<OpencodeStatus>,
    pub opencode_manager: OpencodeManager,
    pub secret_key: auth::key::SecretKey,
    pub pending_pairs: RwLock<auth::pair::PairState>,
    pub sync_db: SyncDb,
    pub bridge_db: BridgeDb,
    pub log_buffer: SharedLogBuffer,
    pub scan_token: RwLock<Option<ScanTokenEntry>>,
}

pub type AppState = Arc<AppStateInner>;

impl FromRef<AppState> for Config {
    fn from_ref(state: &AppState) -> Self {
        state.config.clone()
    }
}

pub fn create_app_state(config: Config, log_buffer: SharedLogBuffer) -> AppState {
    let secret_key = auth::key::SecretKey::load_or_generate()
        .expect("Failed to load or generate secret key");
    let opencode_url = config.opencode_url();
    let binary = config.opencode.binary.clone();
    let hostname = config.opencode.hostname.clone();
    let port = config.opencode.port;
    let directory = config.opencode.directory.clone();
    let auto_restart = config.opencode.auto_restart;

    let sync_db = SyncDb::new(&config);
    tracing::info!("opencode DB path: {}", sync_db.db_path().display());

    let bridge_db = BridgeDb::open().expect("Failed to open bridge database");

    Arc::new(AppStateInner {
        config,
        opencode_status: RwLock::new(OpencodeStatus::Stopped),
        opencode_manager: OpencodeManager::with_config(
            opencode_url,
            binary,
            hostname,
            port,
            directory,
            auto_restart,
        ),
        secret_key,
        pending_pairs: RwLock::new(auth::pair::PairState::new()),
        sync_db,
        bridge_db,
        log_buffer,
        scan_token: RwLock::new(None),
    })
}

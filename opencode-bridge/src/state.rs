use axum::extract::FromRef;
use serde::{Deserialize, Serialize};
use std::sync::Arc;
use std::sync::atomic::AtomicU16;
use tokio::sync::RwLock;

use crate::auth;
use crate::bridge_db::BridgeDb;
use crate::config::Config;
use crate::log_capture::SharedLogBuffer;
use crate::events::source::SharedEventSource;
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
    pub bridge_id: String,
    pub scan_token: RwLock<Option<ScanTokenEntry>>,
    pub event_source: Arc<SharedEventSource>,
pub actual_port: Arc<AtomicU16>,
    pub shutdown_tx: tokio::sync::watch::Sender<bool>,
}

pub type AppState = Arc<AppStateInner>;

impl FromRef<AppState> for Config {
    fn from_ref(state: &AppState) -> Self {
        state.config.clone()
    }
}

pub fn create_app_state(log_buffer: SharedLogBuffer) -> AppState {
    create_app_state_with_db(log_buffer, None)
}

pub fn create_app_state_with_db(log_buffer: SharedLogBuffer, external_db: Option<BridgeDb>) -> AppState {
    create_app_state_with_db_and_event_source(log_buffer, external_db, None)
}

pub fn create_app_state_with_db_and_event_source(
    log_buffer: SharedLogBuffer,
    external_db: Option<BridgeDb>,
    event_source: Option<Arc<SharedEventSource>>,
) -> AppState {
    create_app_state_with_db_event_source_and_actual_port(log_buffer, external_db, event_source, None, None)
}

pub fn create_app_state_with_db_event_source_and_actual_port(
    log_buffer: SharedLogBuffer,
    external_db: Option<BridgeDb>,
    event_source: Option<Arc<SharedEventSource>>,
    actual_port: Option<Arc<AtomicU16>>,
    shutdown: Option<(tokio::sync::watch::Sender<bool>, tokio::sync::watch::Receiver<bool>)>,
) -> AppState {
    let bridge_db = external_db.unwrap_or_else(|| BridgeDb::open().expect("Failed to open bridge database"));
    bridge_db.init_default_configs().expect("Failed to initialize default configs");

    let config = Config::load_from_db(&bridge_db).expect("Failed to load config from DB");

    let key_hex = bridge_db.get_config("auth.secret_key")
        .expect("Failed to load secret key")
        .expect("secret key not initialized");
    let key_bytes = auth::key::hex_to_bytes(&key_hex).expect("Invalid secret key hex");
    let secret_key = auth::key::SecretKey::from_bytes(key_bytes);

    let opencode_url = config.opencode_url();
    let binary = config.opencode.binary.clone();
    let hostname = config.opencode.hostname.clone();
    let port = config.opencode.port;
    let directory = config.opencode.directory.clone();
    let auto_restart = config.opencode.auto_restart;

    let sync_db = SyncDb::new(&config);
    tracing::info!("opencode DB path: {}", sync_db.db_path().display());

    let bridge_id = match bridge_db.get_bridge_id() {
        Ok(Some(id)) => id,
        Ok(None) => {
            let id = crate::auth::key::hex_encode(&crate::auth::key::generate_random_bytes(16));
            bridge_db.set_bridge_id(&id).expect("Failed to save bridge_id");
            id
        }
        Err(e) => panic!("Failed to load bridge_id: {}", e),
    };
    tracing::info!("Bridge ID: {}", bridge_id);

    let bridge_port = config.bridge.port;

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
        bridge_id,
        event_source: event_source.unwrap_or_else(|| Arc::new(SharedEventSource::new())),
        actual_port: actual_port.unwrap_or_else(|| Arc::new(AtomicU16::new(bridge_port))),
        shutdown_tx: shutdown.map_or_else(
            || tokio::sync::watch::channel(false).0,
            |(tx, _)| tx,
        ),
    })
}

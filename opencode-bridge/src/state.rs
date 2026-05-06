use axum::extract::FromRef;
use std::sync::Arc;
use tokio::sync::RwLock;

use crate::auth;
use crate::config::Config;
use crate::process::OpencodeManager;

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum OpencodeStatus {
    Stopped,
    Starting,
    Running,
    Stopping,
    Crashed,
}

pub struct AppStateInner {
    pub config: Config,
    pub opencode_status: RwLock<OpencodeStatus>,
    pub opencode_manager: OpencodeManager,
    pub secret_key: auth::key::SecretKey,
    pub pending_pairs: RwLock<auth::pair::PairState>,
}

pub type AppState = Arc<AppStateInner>;

impl FromRef<AppState> for Config {
    fn from_ref(state: &AppState) -> Self {
        state.config.clone()
    }
}

pub fn create_app_state(config: Config) -> AppState {
    let secret_key = auth::key::SecretKey::load_or_generate()
        .expect("Failed to load or generate secret key");
    let opencode_url = config.opencode_url();
    let binary = config.opencode.binary.clone();
    let hostname = config.opencode.hostname.clone();
    let port = config.opencode.port;
    let directory = config.opencode.directory.clone();
    let auto_restart = config.opencode.auto_restart;

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
    })
}

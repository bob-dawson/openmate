use axum::extract::FromRef;
use std::sync::Arc;
use tokio::sync::RwLock;

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
}

pub type AppState = Arc<AppStateInner>;

impl FromRef<AppState> for Config {
    fn from_ref(state: &AppState) -> Self {
        state.config.clone()
    }
}

pub fn create_app_state(config: Config) -> AppState {
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
    })
}

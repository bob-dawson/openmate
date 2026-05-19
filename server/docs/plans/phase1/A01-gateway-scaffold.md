# A.01: 网关项目脚手架 + 配置加载

> 目标：创建 relay-gateway crate，可编译运行，加载 gateway.toml 配置，启动 axum server。

## Files

- Create: `server/relay-gateway/Cargo.toml`
- Create: `server/relay-gateway/src/main.rs`
- Create: `server/relay-gateway/src/lib.rs`
- Create: `server/relay-gateway/src/config.rs`
- Create: `server/relay-gateway/src/server.rs`
- Create: `server/relay-gateway/src/state.rs`
- Create: `server/relay-gateway/src/error.rs`
- Create: `server/relay-gateway/gateway.toml`

## Steps

- [ ] **Step 1: 创建 Cargo.toml**

```toml
[package]
name = "relay-gateway"
version = "0.1.0"
edition = "2021"

[dependencies]
axum = { version = "0.8", features = ["macros"] }
tokio = { version = "1", features = ["full"] }
tokio-tungstenite = "0.26"
serde = { version = "1", features = ["derive"] }
serde_json = "1"
toml = "0.8"
tracing = "0.1"
tracing-subscriber = { version = "0.3", features = ["env-filter"] }
anyhow = "1"
thiserror = "2"
tower = "0.5"
tower-http = { version = "0.6", features = ["cors", "trace"] }
dashmap = "6"
uuid = { version = "1", features = ["v4"] }
hmac = "0.12"
sha2 = "0.10"
getrandom = "0.3"
clap = { version = "4", features = ["derive"] }
futures = "0.3"
```

- [ ] **Step 2: 创建 src/lib.rs**

```rust
pub mod config;
pub mod error;
pub mod server;
pub mod state;
```

- [ ] **Step 3: 创建 src/config.rs**

与 Bridge config.rs 风格一致，支持 TOML 加载 + 默认值：

```rust
use serde::{Deserialize, Serialize};
use std::path::PathBuf;

#[derive(Debug, Deserialize, Serialize, Clone)]
pub struct GatewayConfig {
    #[serde(default = "default_port")]
    pub port: u16,
    #[serde(default = "default_hostname")]
    pub hostname: String,
    #[serde(default)]
    pub tls_cert: String,
    #[serde(default)]
    pub tls_key: String,
}

#[derive(Debug, Deserialize, Serialize, Clone)]
pub struct AuthConfig {
    pub secret_key_path: String,
}

#[derive(Debug, Deserialize, Serialize, Clone)]
pub struct TunnelConfig {
    #[serde(default = "default_heartbeat_interval")]
    pub heartbeat_interval: u64,
    #[serde(default = "default_heartbeat_timeout")]
    pub heartbeat_timeout: u64,
    #[serde(default = "default_request_timeout")]
    pub request_timeout: u64,
    #[serde(default = "default_max_body")]
    pub max_request_body: usize,
}

#[derive(Debug, Deserialize, Serialize, Clone)]
pub struct Config {
    #[serde(default = "default_gateway")]
    pub gateway: GatewayConfig,
    pub auth: AuthConfig,
    #[serde(default)]
    pub tunnel: TunnelConfig,
}

fn default_gateway() -> GatewayConfig {
    GatewayConfig {
        port: default_port(),
        hostname: default_hostname(),
        tls_cert: String::new(),
        tls_key: String::new(),
    }
}

fn default_port() -> u16 { 8080 }
fn default_hostname() -> String { "0.0.0.0".to_string() }
fn default_heartbeat_interval() -> u64 { 30 }
fn default_heartbeat_timeout() -> u64 { 60 }
fn default_request_timeout() -> u64 { 30 }
fn default_max_body() -> usize { 10 * 1024 * 1024 }

impl Default for TunnelConfig {
    fn default() -> Self {
        TunnelConfig {
            heartbeat_interval: default_heartbeat_interval(),
            heartbeat_timeout: default_heartbeat_timeout(),
            request_timeout: default_request_timeout(),
            max_request_body: default_max_body(),
        }
    }
}

impl Config {
    pub fn listen_addr(&self) -> String {
        format!("{}:{}", self.gateway.hostname, self.gateway.port)
    }

    pub fn load_from(path: &PathBuf) -> anyhow::Result<Self> {
        let content = std::fs::read_to_string(path)?;
        let config: Config = toml::from_str(&content)?;
        Ok(config)
    }

    pub fn find_and_load(config_path: Option<PathBuf>) -> anyhow::Result<Self> {
        if let Some(path) = config_path {
            return Self::load_from(&path);
        }
        let candidates = vec![
            PathBuf::from("gateway.toml"),
        ];
        for path in &candidates {
            if path.exists() {
                tracing::info!("Loading config from {}", path.display());
                return Self::load_from(path);
            }
        }
        tracing::info!("No config file found, using defaults");
        anyhow::bail!("No config file found. Create gateway.toml or specify -c path")
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn test_listen_addr() {
        let config = Config {
            gateway: default_gateway(),
            auth: AuthConfig { secret_key_path: "/tmp/test.key".to_string() },
            tunnel: TunnelConfig::default(),
        };
        assert_eq!(config.listen_addr(), "0.0.0.0:8080");
    }

    #[test]
    fn test_load_from_toml() {
        let tmp = std::env::temp_dir().join("gateway_config_test.toml");
        let content = r#"
[gateway]
port = 443
hostname = "0.0.0.0"

[auth]
secret_key_path = "/path/to/key"

[tunnel]
heartbeat_interval = 20
"#;
        std::fs::write(&tmp, content).unwrap();
        let config = Config::load_from(&tmp).unwrap();
        assert_eq!(config.gateway.port, 443);
        assert_eq!(config.tunnel.heartbeat_interval, 20);
        assert_eq!(config.tunnel.heartbeat_timeout, 60);
        let _ = std::fs::remove_file(&tmp);
    }
}
```

- [ ] **Step 4: 创建 src/error.rs**

```rust
use axum::http::StatusCode;
use axum::response::{IntoResponse, Response};
use serde_json::json;

#[derive(Debug, thiserror::Error)]
pub enum GatewayError {
    #[error("Unauthorized: {0}")]
    Unauthorized(String),
    #[error("Bridge offline: {0}")]
    BridgeOffline(String),
    #[error("Bad request: {0}")]
    BadRequest(String),
    #[error("Gateway timeout: {0}")]
    Timeout(String),
    #[error("Internal error: {0}")]
    Internal(#[from] anyhow::Error),
}

impl IntoResponse for GatewayError {
    fn into_response(self) -> Response {
        let (status, message) = match &self {
            GatewayError::Unauthorized(_) => (StatusCode::UNAUTHORIZED, self.to_string()),
            GatewayError::BridgeOffline(_) => (StatusCode::SERVICE_UNAVAILABLE, self.to_string()),
            GatewayError::BadRequest(_) => (StatusCode::BAD_REQUEST, self.to_string()),
            GatewayError::Timeout(_) => (StatusCode::GATEWAY_TIMEOUT, self.to_string()),
            GatewayError::Internal(_) => (StatusCode::INTERNAL_SERVER_ERROR, "Internal server error".to_string()),
        };
        (status, axum::Json(json!({ "error": message }))).into_response()
    }
}
```

- [ ] **Step 5: 创建 src/state.rs**

```rust
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

pub struct GatewayState {
    pub config: Config,
    pub secret_key: crate::auth::SecretKey,
    pub bridges: dashmap::DashMap<String, BridgeConn>,
    pub pending_requests: dashmap::DashMap<String, PendingRequest>,
}

impl GatewayState {
    pub fn new(config: Config, secret_key: crate::auth::SecretKey) -> Self {
        GatewayState {
            config,
            secret_key,
            bridges: dashmap::DashMap::new(),
            pending_requests: dashmap::DashMap::new(),
        }
    }
}

pub type SharedState = Arc<GatewayState>;
```

- [ ] **Step 6: 创建占位 auth 模块**

`src/auth/mod.rs`:
```rust
mod hmac_auth;

pub use hmac_auth::*;
```

`src/auth/hmac_auth.rs` — 复用 Bridge 的 HMAC token 逻辑（后续步骤 A.03 完善），先占位：

```rust
use hmac::{Hmac, Mac};
use sha2::Sha256;

type HmacSha256 = Hmac<Sha256>;

#[derive(Clone)]
pub struct SecretKey {
    key: Vec<u8>,
}

impl SecretKey {
    pub fn from_bytes(key: Vec<u8>) -> Self {
        Self { key }
    }

    pub fn load_from_file(path: &str) -> anyhow::Result<Self> {
        let hex = std::fs::read_to_string(path)?;
        let key = hex_to_bytes(hex.trim())?;
        if key.len() != 32 {
            anyhow::bail!("Invalid key length, expected 32 bytes");
        }
        Ok(Self { key })
    }

    pub fn as_bytes(&self) -> &[u8] {
        &self.key
    }
}

fn hex_to_bytes(hex: &str) -> anyhow::Result<Vec<u8>> {
    (0..hex.len())
        .step_by(2)
        .map(|i| u8::from_str_radix(&hex[i..i + 2], 16).map_err(|e| anyhow::anyhow!("Invalid hex: {}", e)))
        .collect()
}

pub fn validate_token(secret_key: &SecretKey, token: &str) -> bool {
    if token.len() != 128 {
        return false;
    }
    let payload = &token[..64];
    let signature_hex = &token[64..];
    let expected = compute_hmac(secret_key.as_bytes(), payload);
    let expected_hex: String = expected.iter().map(|b| format!("{:02x}", b)).collect();
    constant_time_eq(signature_hex.as_bytes(), expected_hex.as_bytes())
}

fn compute_hmac(key: &[u8], data: &str) -> Vec<u8> {
    let mut mac = HmacSha256::new_from_slice(key).expect("HMAC key length is valid");
    mac.update(data.as_bytes());
    mac.finalize().into_bytes().to_vec()
}

fn constant_time_eq(a: &[u8], b: &[u8]) -> bool {
    if a.len() != b.len() {
        return false;
    }
    let mut result = 0u8;
    for (x, y) in a.iter().zip(b.iter()) {
        result |= x ^ y;
    }
    result == 0
}

#[cfg(test)]
mod tests {
    use super::*;

    fn test_key() -> SecretKey {
        SecretKey::from_bytes(vec![0x42u8; 32])
    }

    #[test]
    fn test_validate_wrong_length() {
        assert!(!validate_token(&test_key(), "tooshort"));
    }

    #[test]
    fn test_load_from_file_invalid_path() {
        assert!(SecretKey::load_from_file("/nonexistent/path").is_err());
    }
}
```

- [ ] **Step 7: 创建占位 tunnel 模块**

`src/tunnel/mod.rs`:
```rust
pub mod frame;
```

`src/tunnel/frame.rs` — 隧道帧定义（后续步骤 A.02 完善），先占位：

```rust
use serde::{Deserialize, Serialize};

#[derive(Debug, Serialize, Deserialize)]
pub struct TunnelFrame {
    #[serde(rename = "type")]
    pub frame_type: String,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub request_id: Option<String>,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub method: Option<String>,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub path: Option<String>,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub headers: Option<std::collections::HashMap<String, String>>,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub body: Option<String>,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub status: Option<u16>,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub data: Option<String>,
}
```

- [ ] **Step 8: 创建 src/server.rs**

```rust
use axum::Router;
use axum::routing::{get, any};
use tower_http::cors::CorsLayer;
use tower_http::trace::TraceLayer;

use crate::state::SharedState;

pub fn build_app(state: SharedState) -> Router {
    Router::new()
        .route("/api/gateway/health", get(health_handler))
        .route("/ws", get(ws_handler))
        .fallback(any(proxy_handler))
        .layer(CorsLayer::permissive())
        .layer(TraceLayer::new_for_http())
        .with_state(state)
}

async fn health_handler() -> &'static str {
    "OK"
}

async fn ws_handler() -> &'static str {
    "WS"
}

async fn proxy_handler() -> &'static str {
    "PROXY"
}

pub async fn run_server(state: SharedState) -> anyhow::Result<()> {
    let addr = state.config.listen_addr();
    let app = build_app(state);
    let listener = tokio::net::TcpListener::bind(&addr).await?;
    tracing::info!("Gateway listening on {}", addr);
    axum::serve(listener, app).await?;
    Ok(())
}
```

- [ ] **Step 9: 更新 src/lib.rs**

```rust
pub mod auth;
pub mod config;
pub mod error;
pub mod server;
pub mod state;
pub mod tunnel;
```

- [ ] **Step 10: 创建 src/main.rs**

```rust
use clap::Parser;
use std::path::PathBuf;

#[derive(Parser)]
#[command(name = "relay-gateway", about = "OpenMate Relay Gateway")]
struct Cli {
    #[arg(short = 'c', long = "config")]
    config: Option<PathBuf>,
}

#[tokio::main]
async fn main() -> anyhow::Result<()> {
    tracing_subscriber::fmt()
        .with_env_filter(
            tracing_subscriber::EnvFilter::from_default_env()
                .add_directive("relay_gateway=info".parse()?)
        )
        .init();

    let cli = Cli::parse();
    let config = relay_gateway::config::Config::find_and_load(cli.config)?;
    let secret_key = relay_gateway::auth::SecretKey::load_from_file(&config.auth.secret_key_path)?;

    let state = std::sync::Arc::new(relay_gateway::state::GatewayState::new(config, secret_key));
    relay_gateway::server::run_server(state).await
}
```

- [ ] **Step 11: 创建 gateway.toml**

```toml
[gateway]
port = 8080
hostname = "0.0.0.0"

[auth]
secret_key_path = "gateway.key"

[tunnel]
heartbeat_interval = 30
heartbeat_timeout = 60
request_timeout = 30
```

- [ ] **Step 12: 编译验证**

Run: `cd server/relay-gateway && cargo build`
Expected: BUILD SUCCEEDED

- [ ] **Step 13: 运行测试**

Run: `cd server/relay-gateway && cargo test`
Expected: 所有测试通过

- [ ] **Step 14: 提交**

```bash
git add server/relay-gateway/
git commit -m "feat(gateway): scaffold relay-gateway crate with config and basic server"
```

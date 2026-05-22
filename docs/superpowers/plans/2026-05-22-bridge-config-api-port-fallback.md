# Bridge Config API + Port Fallback Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add config read/write API to Bridge, and make Bridge auto-fallback to a random port when the configured port is occupied.

**Architecture:** 
1. New `api/config.rs` module with `GET /api/bridge/config` and `PUT /api/bridge/config` endpoints. GET returns all configurable keys with metadata (type, current value, whether restart is needed). PUT validates (port availability, path existence) then writes to DB. Some config changes (opencode.*) can be hot-reloaded; bridge.port/hostname require restart.
2. `server.rs` bind logic: try configured port first, if `EADDRINUSE` then try OS-assigned random port (bind to `0`), log the actual port, and update `config.bridge.port` in DB so the value is consistent.

**Tech Stack:** Rust, Axum, rusqlite, tokio

---

### Task 1: Add `ConfigError` variant to `AppError`

**Files:**
- Modify: `opencode-bridge/src/error.rs`

- [ ] **Step 1: Add `ConfigValidation` error variant**

In `error.rs`, add a new variant:

```rust
#[error("Config validation failed: {0}")]
ConfigValidation(String),
```

And in the `IntoResponse` match, add:

```rust
AppError::ConfigValidation(_) => (StatusCode::BAD_REQUEST, self.to_string()),
```

- [ ] **Step 2: Run existing tests**

Run: `cargo test --lib error`
Expected: All pass

---

### Task 2: Add port availability check utility to `config.rs`

**Files:**
- Modify: `opencode-bridge/src/config.rs`

- [ ] **Step 1: Add `is_port_available` function**

```rust
pub fn is_port_available(port: u16) -> bool {
    std::net::TcpListener::bind(("0.0.0.0", port)).is_ok()
}
```

- [ ] **Step 2: Add test**

```rust
#[test]
fn test_is_port_available_with_random_port() {
    let listener = std::net::TcpListener::bind("0.0.0.0:0").unwrap();
    let occupied_port = listener.local_addr().unwrap().port();
    assert!(!is_port_available(occupied_port));
}

#[test]
fn test_is_port_available_with_unused_port() {
    let listener = std::net::TcpListener::bind("0.0.0.0:0").unwrap();
    let free_port = listener.local_addr().unwrap().port();
    drop(listener);
    assert!(is_port_available(free_port));
}
```

- [ ] **Step 3: Run tests**

Run: `cargo test --lib config`
Expected: All pass

---

### Task 3: Add `ConfigEntry` struct and `get_config_metadata` to `config.rs`

**Files:**
- Modify: `opencode-bridge/src/config.rs`

- [ ] **Step 1: Add `ConfigEntry` struct and metadata function**

This defines the schema for all configurable keys — their types, defaults, and whether they need restart.

```rust
#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct ConfigEntry {
    pub key: String,
    pub value: String,
    pub default: String,
    pub r#type: String,
    pub needs_restart: bool,
    pub description: String,
}

impl Config {
    pub fn config_metadata() -> Vec<ConfigEntry> {
        vec![
            ConfigEntry {
                key: "bridge.port".into(),
                value: String::new(),
                default: "4097".into(),
                r#type: "u16".into(),
                needs_restart: true,
                description: "Bridge HTTP server listen port".into(),
            },
            ConfigEntry {
                key: "bridge.hostname".into(),
                value: String::new(),
                default: "0.0.0.0".into(),
                r#type: "string".into(),
                needs_restart: true,
                description: "Bridge HTTP server listen address".into(),
            },
            ConfigEntry {
                key: "bridge.auth_enabled".into(),
                value: String::new(),
                default: "true".into(),
                r#type: "bool".into(),
                needs_restart: false,
                description: "Require authentication for API access".into(),
            },
            ConfigEntry {
                key: "opencode.binary".into(),
                value: String::new(),
                default: "opencode".into(),
                r#type: "string".into(),
                needs_restart: false,
                description: "Path to opencode executable".into(),
            },
            ConfigEntry {
                key: "opencode.hostname".into(),
                value: String::new(),
                default: "127.0.0.1".into(),
                r#type: "string".into(),
                needs_restart: false,
                description: "opencode serve hostname".into(),
            },
            ConfigEntry {
                key: "opencode.port".into(),
                value: String::new(),
                default: "4096".into(),
                r#type: "u16".into(),
                needs_restart: false,
                description: "opencode serve port".into(),
            },
            ConfigEntry {
                key: "opencode.directory".into(),
                value: String::new(),
                default: String::new(),
                r#type: "string".into(),
                needs_restart: false,
                description: "opencode working directory (empty = exe dir)".into(),
            },
            ConfigEntry {
                key: "opencode.auto_start".into(),
                value: String::new(),
                default: "true".into(),
                r#type: "bool".into(),
                needs_restart: false,
                description: "Auto-start opencode when Bridge starts".into(),
            },
            ConfigEntry {
                key: "opencode.auto_restart".into(),
                value: String::new(),
                default: "true".into(),
                r#type: "bool".into(),
                needs_restart: false,
                description: "Auto-restart opencode on crash".into(),
            },
            ConfigEntry {
                key: "opencode.db_path".into(),
                value: String::new(),
                default: default_db_path(),
                r#type: "string".into(),
                needs_restart: false,
                description: "Path to opencode SQLite database".into(),
            },
            ConfigEntry {
                key: "fs.allowed_paths".into(),
                value: String::new(),
                default: String::new(),
                r#type: "string".into(),
                needs_restart: false,
                description: "Comma-separated allowed file paths (empty = all)".into(),
            },
            ConfigEntry {
                key: "gateway.url".into(),
                value: String::new(),
                default: "https://gateway.clawmate.net".into(),
                r#type: "string".into(),
                needs_restart: true,
                description: "Relay gateway URL".into(),
            },
            ConfigEntry {
                key: "gateway.auto_connect".into(),
                value: String::new(),
                default: "true".into(),
                r#type: "bool".into(),
                needs_restart: true,
                description: "Auto-connect to relay gateway".into(),
            },
        ]
    }
}
```

Note: `auth.secret_key` and `auth.instance_id` are NOT included — they have dedicated endpoints (reset-secret, auto-generated).

- [ ] **Step 2: Run tests**

Run: `cargo test --lib config`
Expected: All pass

---

### Task 4: Create `api/config.rs` — GET and PUT handlers

**Files:**
- Create: `opencode-bridge/src/api/config.rs`
- Modify: `opencode-bridge/src/api/mod.rs`

- [ ] **Step 1: Create `api/config.rs`**

```rust
use axum::extract::State;
use axum::response::IntoResponse;
use axum::Json;
use serde::Deserialize;

use crate::config::{ConfigEntry, Config};
use crate::error::AppError;
use crate::state::AppState;

pub async fn get_config(
    State(state): State<AppState>,
) -> Result<impl IntoResponse, AppError> {
    let db_configs: std::collections::HashMap<String, String> = state
        .bridge_db
        .get_all_configs()
        .map_err(|e| AppError::DatabaseError(e))?
        .into_iter()
        .collect();

    let mut entries = Config::config_metadata();
    for entry in &mut entries {
        if let Some(val) = db_configs.get(&entry.key) {
            entry.value = val.clone();
        } else {
            entry.value = entry.default.clone();
        }
    }

    Ok(Json(serde_json::json!({
        "configs": entries,
    })))
}

#[derive(Deserialize)]
pub struct UpdateConfigRequest {
    pub configs: Vec<ConfigUpdate>,
}

#[derive(Deserialize)]
pub struct ConfigUpdate {
    pub key: String,
    pub value: String,
}

pub async fn update_config(
    State(state): State<AppState>,
    Json(body): Json<UpdateConfigRequest>,
) -> Result<impl IntoResponse, AppError> {
    let metadata: std::collections::HashMap<String, ConfigEntry> = Config::config_metadata()
        .into_iter()
        .map(|e| (e.key.clone(), e))
        .collect();

    let mut needs_restart = false;
    let mut entries_to_save: Vec<(String, String)> = Vec::new();

    for update in &body.configs {
        let entry = metadata.get(&update.key).ok_or_else(|| {
            AppError::ConfigValidation(format!("Unknown config key: {}", update.key))
        })?;

        validate_config_value(&update.key, &update.value, entry)?;

        if entry.needs_restart {
            needs_restart = true;
        }
        entries_to_save.push((update.key.clone(), update.value.clone()));
    }

    state
        .bridge_db
        .set_configs_batch(&entries_to_save)
        .map_err(|e| AppError::DatabaseError(e))?;

    let mut restart_keys: Vec<String> = Vec::new();
    if needs_restart {
        for update in &body.configs {
            if let Some(entry) = metadata.get(&update.key) {
                if entry.needs_restart {
                    restart_keys.push(update.key.clone());
                }
            }
        }
    }

    Ok(Json(serde_json::json!({
        "success": true,
        "needs_restart": needs_restart,
        "restart_keys": restart_keys,
    })))
}

fn validate_config_value(key: &str, value: &str, entry: &ConfigEntry) -> Result<(), AppError> {
    match entry.r#type.as_str() {
        "u16" => {
            let port: u16 = value.parse().map_err(|_| {
                AppError::ConfigValidation(format!("{} must be a valid port number", key))
            })?;
            if key == "bridge.port" && !crate::config::is_port_available(port) {
                return Err(AppError::ConfigValidation(format!(
                    "Port {} is already in use",
                    port
                )));
            }
        }
        "bool" => {
            if value != "true" && value != "false" {
                return Err(AppError::ConfigValidation(format!(
                    "{} must be true or false",
                    key
                )));
            }
        }
        "string" => {
            if key == "opencode.binary" && !value.is_empty() {
                let path = std::path::PathBuf::from(value);
                if path.is_absolute() && !path.exists() {
                    return Err(AppError::ConfigValidation(format!(
                        "opencode binary not found: {}",
                        value
                    )));
                }
            }
            if key == "opencode.directory" && !value.is_empty() {
                let path = std::path::PathBuf::from(value);
                if path.is_absolute() && !path.exists() {
                    return Err(AppError::ConfigValidation(format!(
                        "Directory not found: {}",
                        value
                    )));
                }
            }
            if key == "opencode.db_path" && !value.is_empty() {
                let path = std::path::PathBuf::from(value);
                if path.is_absolute() && !path.parent().map(|p| p.exists()).unwrap_or(false) {
                    return Err(AppError::ConfigValidation(format!(
                        "DB path parent directory not found: {}",
                        value
                    )));
                }
            }
        }
        _ => {}
    }
    Ok(())
}
```

- [ ] **Step 2: Register module and routes in `api/mod.rs`**

Add `mod config;` to the module list, and add routes:

```rust
.route("/api/bridge/config", get(config::get_config))
.route("/api/bridge/config", put(config::update_config))
```

- [ ] **Step 3: Run compilation**

Run: `cargo build`
Expected: Compiles successfully

---

### Task 5: Port fallback in `server.rs`

**Files:**
- Modify: `opencode-bridge/src/server.rs`

- [ ] **Step 1: Replace `TcpListener::bind` with fallback logic**

Replace the current bind logic (line 153):

```rust
let listener = tokio::net::TcpListener::bind(&listen_addr).await?;
```

With:

```rust
let listener = match tokio::net::TcpListener::bind(&listen_addr).await {
    Ok(l) => l,
    Err(e) if e.kind() == std::io::ErrorKind::AddrInUse => {
        tracing::warn!(
            "Port {} is already in use, falling back to a random port",
            config.bridge.port
        );
        let fallback_addr = format!("{}:0", config.bridge.hostname);
        let l = tokio::net::TcpListener::bind(&fallback_addr).await?;
        let actual_port = l.local_addr()?.port();
        tracing::info!(
            "Bridge listening on random port {} (configured: {})",
            actual_port,
            config.bridge.port
        );
        l
    }
    Err(e) => return Err(e.into()),
};

let actual_port = listener.local_addr()?.port();
let port_changed = actual_port != config.bridge.port;
```

- [ ] **Step 2: Update port in DB when fallback happens**

After the listener is created, add:

```rust
if port_changed {
    if let Err(e) = app_state.bridge_db.set_config("bridge.port", &actual_port.to_string()) {
        tracing::warn!("Failed to update bridge.port in DB: {}", e);
    }
    tracing::info!("Updated bridge.port in DB to {}", actual_port);
}
```

- [ ] **Step 3: Use `actual_port` instead of `config.bridge.port` for downstream code**

Replace `let port = config.bridge.port;` (line 71) with:

```rust
let port = actual_port;
```

And update the gateway client spawn to use `actual_port`:

The existing code at line 168 uses `port` which is now `actual_port`, so it's already correct.

- [ ] **Step 4: Run compilation**

Run: `cargo build`
Expected: Compiles successfully

---

### Task 6: Add integration tests for config API

**Files:**
- Modify: `opencode-bridge/tests/integration.rs`

- [ ] **Step 1: Add config API routes to test helpers**

In `test_app_with_dir`, add the config routes:

```rust
.route("/api/bridge/config", get(openmate::api::config::get_config))
.route("/api/bridge/config", put(openmate::api::config::update_config))
```

- [ ] **Step 2: Add test for GET /api/bridge/config**

```rust
#[tokio::test]
async fn test_get_config() {
    let dir = std::env::temp_dir().join("bridge_int_config_get");
    let _ = std::fs::remove_dir_all(&dir);
    std::fs::create_dir_all(&dir).unwrap();

    let (app, _) = test_app_with_dir(&dir);
    let token = make_test_token(&app);

    let response = app
        .oneshot(
            axum::http::Request::builder()
                .uri("/api/bridge/config")
                .header("Authorization", format!("Bearer {}", token))
                .body(axum::body::Body::empty())
                .unwrap(),
        )
        .await
        .unwrap();

    assert_eq!(response.status(), 200);
    let body = axum::body::to_bytes(response.into_body(), 8192).await.unwrap();
    let json: serde_json::Value = serde_json::from_slice(&body).unwrap();
    let configs = json["configs"].as_array().unwrap();
    assert!(!configs.is_empty());
    
    let bridge_port = configs.iter().find(|c| c["key"] == "bridge.port").unwrap();
    assert_eq!(bridge_port["type"], "u16");
    assert_eq!(bridge_port["needs_restart"], true);
}
```

- [ ] **Step 3: Add test for PUT /api/bridge/config with valid bool**

```rust
#[tokio::test]
async fn test_update_config_bool() {
    let dir = std::env::temp_dir().join("bridge_int_config_put");
    let _ = std::fs::remove_dir_all(&dir);
    std::fs::create_dir_all(&dir).unwrap();

    let (app, state) = test_app_with_dir(&dir);
    let token = make_test_token(&app);

    let body = serde_json::json!({
        "configs": [{"key": "opencode.auto_start", "value": "false"}]
    });

    let response = app
        .oneshot(
            axum::http::Request::builder()
                .method("PUT")
                .uri("/api/bridge/config")
                .header("Authorization", format!("Bearer {}", token))
                .header("Content-Type", "application/json")
                .body(axum::body::Body::from(serde_json::to_string(&body).unwrap()))
                .unwrap(),
        )
        .await
        .unwrap();

    assert_eq!(response.status(), 200);
    let resp_body = axum::body::to_bytes(response.into_body(), 8192).await.unwrap();
    let json: serde_json::Value = serde_json::from_slice(&resp_body).unwrap();
    assert_eq!(json["success"], true);
    assert_eq!(json["needs_restart"], false);

    let saved = state.bridge_db.get_config("opencode.auto_start").unwrap();
    assert_eq!(saved, Some("false".to_string()));
}
```

- [ ] **Step 4: Add test for PUT with invalid key**

```rust
#[tokio::test]
async fn test_update_config_unknown_key_rejected() {
    let dir = std::env::temp_dir().join("bridge_int_config_unknown");
    let _ = std::fs::remove_dir_all(&dir);
    std::fs::create_dir_all(&dir).unwrap();

    let (app, _) = test_app_with_dir(&dir);
    let token = make_test_token(&app);

    let body = serde_json::json!({
        "configs": [{"key": "auth.secret_key", "value": "hacked"}]
    });

    let response = app
        .oneshot(
            axum::http::Request::builder()
                .method("PUT")
                .uri("/api/bridge/config")
                .header("Authorization", format!("Bearer {}", token))
                .header("Content-Type", "application/json")
                .body(axum::body::Body::from(serde_json::to_string(&body).unwrap()))
                .unwrap(),
        )
        .await
        .unwrap();

    assert_eq!(response.status(), 400);
}
```

- [ ] **Step 5: Run all tests**

Run: `cargo test`
Expected: All pass

---

### Task 7: Add `needs_restart` info to `GET /api/bridge/status`

**Files:**
- Modify: `opencode-bridge/src/bridge/router.rs`

- [ ] **Step 1: Add `actual_port` field to status response**

The status endpoint already returns `bridge.port` from config. After port fallback, the config in `AppState` still has the old port. We need to also expose the actual running port.

Add `actual_port` to the status response:

```rust
Json(json!({
    "bridge": {
        "version": env!("CARGO_PKG_VERSION"),
        "port": state.config.bridge.port,
        "actual_port": state.actual_port.load(std::sync::atomic::Ordering::Relaxed),
        "auth_enabled": state.config.bridge.auth_enabled,
        "instance_id": state.config.gateway.instance_id,
    },
    ...
}))
```

- [ ] **Step 2: Add `actual_port` AtomicU16 to `AppStateInner`**

In `state.rs`, add:

```rust
pub actual_port: std::sync::atomic::AtomicU16,
```

Initialize it in `create_app_state_with_db_and_event_source`:

```rust
actual_port: std::sync::atomic::AtomicU16::new(config.bridge.port),
```

- [ ] **Step 3: Set `actual_port` in `server.rs` after bind**

After the listener bind (including fallback), add:

```rust
app_state.actual_port.store(actual_port, std::sync::atomic::Ordering::Relaxed);
```

- [ ] **Step 4: Run tests**

Run: `cargo test`
Expected: All pass

---

### Task 8: Commit

- [ ] **Step 1: Stage and commit all changes**

```bash
git add opencode-bridge/src/error.rs opencode-bridge/src/config.rs opencode-bridge/src/api/config.rs opencode-bridge/src/api/mod.rs opencode-bridge/src/server.rs opencode-bridge/src/state.rs opencode-bridge/src/bridge/router.rs opencode-bridge/tests/integration.rs
git commit -m "feat(bridge): add config API and port fallback

- GET /api/bridge/config returns all configurable keys with metadata
- PUT /api/bridge/config validates and saves config (port availability check)
- Bridge auto-fallback to random port when configured port is occupied
- actual_port exposed in /api/bridge/status for port fallback detection
- ConfigEntry schema with type, default, needs_restart, description"
```

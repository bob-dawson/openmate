use axum::extract::State;
use axum::response::IntoResponse;
use axum::Json;

use crate::config::{Config, ConfigEntry};
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

#[derive(serde::Deserialize)]
pub struct UpdateConfigRequest {
    pub configs: Vec<ConfigUpdate>,
}

#[derive(serde::Deserialize)]
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
        if update.key == "bridge.auth_enabled" || update.key == "gateway.url" {
            return Err(AppError::ConfigValidation(format!(
                "{} is not configurable via this API",
                update.key
            )));
        }
        let entry = metadata.get(&update.key).ok_or_else(|| {
            AppError::ConfigValidation(format!("Unknown config key: {}", update.key))
        })?;

        validate_config_value(&update.key, &update.value, entry, &state)?;

        if entry.needs_restart {
            needs_restart = true;
        }
        entries_to_save.push((update.key.clone(), update.value.clone()));
    }

    state
        .bridge_db
        .set_configs_batch(&entries_to_save)
        .map_err(|e| AppError::DatabaseError(e))?;

    let restart_keys: Vec<String> = if needs_restart {
        body.configs
            .iter()
            .filter(|u| metadata.get(&u.key).map(|e| e.needs_restart).unwrap_or(false))
            .map(|u| u.key.clone())
            .collect()
    } else {
        Vec::new()
    };

    Ok(Json(serde_json::json!({
        "success": true,
        "needs_restart": needs_restart,
        "restart_keys": restart_keys,
    })))
}

fn validate_config_value(key: &str, value: &str, entry: &ConfigEntry, state: &AppState) -> Result<(), AppError> {
    match entry.r#type.as_str() {
        "u16" => {
            let port: u16 = value.parse().map_err(|_| {
                AppError::ConfigValidation(format!("{} must be a valid port number", key))
            })?;
            if key == "bridge.port" {
                let current_port = state.actual_port.load(std::sync::atomic::Ordering::Relaxed);
                if port != current_port && !crate::config::is_port_available(port) {
                    return Err(AppError::ConfigValidation(format!(
                        "Port {} is already in use",
                        port
                    )));
                }
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

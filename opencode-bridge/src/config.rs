use serde::{Deserialize, Serialize};
use std::path::PathBuf;

use crate::bridge_db::BridgeDb;

#[derive(Debug, Clone)]
pub struct Config {
    pub bridge: BridgeConfig,
    pub opencode: OpencodeConfig,
    pub fs: FsConfig,
    pub gateway: GatewayConfig,
}

#[derive(Debug, Clone)]
pub struct BridgeConfig {
    pub port: u16,
    pub hostname: String,
    pub auth_enabled: bool,
}

#[derive(Debug, Clone)]
pub struct OpencodeConfig {
    pub binary: String,
    pub hostname: String,
    pub port: u16,
    pub directory: String,
    pub auto_start: bool,
    pub auto_restart: bool,
    pub db_path: String,
}

#[derive(Debug, Clone)]
pub struct FsConfig {
    pub allowed_paths: Vec<String>,
}

#[derive(Debug, Clone, Default)]
pub struct GatewayConfig {
    pub url: String,
    pub auto_connect: bool,
    pub instance_id: String,
}

pub fn default_db_path() -> String {
    let home = dirs::home_dir().unwrap_or_default();
    let path = home.join(".local").join("share").join("opencode").join("opencode.db");
    path.to_string_lossy().to_string()
}

pub fn is_port_available(port: u16) -> bool {
    std::net::TcpListener::bind(("0.0.0.0", port)).is_ok()
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct ConfigEntry {
    pub key: String,
    pub value: String,
    pub default: String,
    pub r#type: String,
    pub needs_restart: bool,
    pub description: String,
}

impl Default for FsConfig {
    fn default() -> Self {
        FsConfig {
            allowed_paths: Vec::new(),
        }
    }
}

impl Default for Config {
    fn default() -> Self {
        Config {
            bridge: BridgeConfig {
                port: 4097,
                hostname: "0.0.0.0".to_string(),
                auth_enabled: true,
            },
            opencode: OpencodeConfig {
                binary: "opencode".to_string(),
                hostname: "127.0.0.1".to_string(),
                port: 4096,
                directory: String::new(),
                auto_start: true,
                auto_restart: true,
                db_path: default_db_path(),
            },
            fs: FsConfig::default(),
            gateway: GatewayConfig::default(),
        }
    }
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
        ]
    }

    pub fn opencode_url(&self) -> String {
        format!("http://{}:{}", self.opencode.hostname, self.opencode.port)
    }

    pub fn db_path(&self) -> std::path::PathBuf {
        std::path::PathBuf::from(&self.opencode.db_path)
    }

    pub fn bridge_listen_addr(&self) -> String {
        format!("{}:{}", self.bridge.hostname, self.bridge.port)
    }

    pub fn effective_allowed_paths(&self) -> Vec<PathBuf> {
        if self.fs.allowed_paths.is_empty() {
            return Vec::new()
        }
        self.fs.allowed_paths.iter().map(PathBuf::from).collect()
    }

    pub fn load_from_db(db: &BridgeDb) -> anyhow::Result<Self> {
        let configs: std::collections::HashMap<String, String> = db.get_all_configs()
            .map_err(|e| anyhow::anyhow!("Failed to load config: {}", e))?
            .into_iter().collect();

        let get = |key: &str, default: &str| -> String {
            configs.get(key).cloned().unwrap_or_else(|| default.to_string())
        };
        let get_bool = |key: &str, default: bool| -> bool {
            configs.get(key).and_then(|v| v.parse().ok()).unwrap_or(default)
        };
        let get_u16 = |key: &str, default: u16| -> u16 {
            configs.get(key).and_then(|v| v.parse().ok()).unwrap_or(default)
        };

        let allowed_paths_str = get("fs.allowed_paths", "");
        let allowed_paths = if allowed_paths_str.is_empty() {
            vec![]
        } else {
            allowed_paths_str.split(',')
                .map(|s| s.trim().to_string())
                .filter(|s| !s.is_empty())
                .collect()
        };

        Ok(Config {
            bridge: BridgeConfig {
                port: get_u16("bridge.port", 4097),
                hostname: get("bridge.hostname", "0.0.0.0"),
                auth_enabled: true,
            },
            opencode: OpencodeConfig {
                binary: get("opencode.binary", "opencode"),
                hostname: get("opencode.hostname", "127.0.0.1"),
                port: get_u16("opencode.port", 4096),
                directory: get("opencode.directory", ""),
                auto_start: get_bool("opencode.auto_start", true),
                auto_restart: get_bool("opencode.auto_restart", true),
                db_path: get("opencode.db_path", &default_db_path()),
            },
            fs: FsConfig {
                allowed_paths,
            },
            gateway: GatewayConfig {
                url: get("gateway.url", ""),
                auto_connect: get_bool("gateway.auto_connect", true),
                instance_id: get("auth.instance_id", ""),
            },
        })
    }

    pub fn save_to_db(&self, db: &BridgeDb) -> anyhow::Result<()> {
        let entries = vec![
            ("bridge.port".to_string(), self.bridge.port.to_string()),
            ("bridge.hostname".to_string(), self.bridge.hostname.clone()),
            ("opencode.binary".to_string(), self.opencode.binary.clone()),
            ("opencode.hostname".to_string(), self.opencode.hostname.clone()),
            ("opencode.port".to_string(), self.opencode.port.to_string()),
            ("opencode.directory".to_string(), self.opencode.directory.clone()),
            ("opencode.auto_start".to_string(), self.opencode.auto_start.to_string()),
            ("opencode.auto_restart".to_string(), self.opencode.auto_restart.to_string()),
            ("opencode.db_path".to_string(), self.opencode.db_path.clone()),
            ("fs.allowed_paths".to_string(), self.fs.allowed_paths.join(",")),
            ("gateway.url".to_string(), self.gateway.url.clone()),
            ("gateway.auto_connect".to_string(), self.gateway.auto_connect.to_string()),
        ];
        db.set_configs_batch(&entries)
            .map_err(|e| anyhow::anyhow!("Failed to save config: {}", e))?;
        Ok(())
    }

    pub fn resolve_opencode_binary(&mut self) -> anyhow::Result<()> {
        if self.opencode.binary != "opencode" && PathBuf::from(&self.opencode.binary).is_absolute() {
            if PathBuf::from(&self.opencode.binary).exists() {
                return Ok(());
            }
        }

        let resolved = which_opencode()?;
        self.opencode.binary = resolved.to_str()
            .ok_or_else(|| anyhow::anyhow!("Resolved path is not valid UTF-8"))?
            .to_string();
        Ok(())
    }

    pub fn ensure_opencode_binary(&self) -> anyhow::Result<()> {
        let path = PathBuf::from(&self.opencode.binary);
        if !path.is_absolute() {
            anyhow::bail!("opencode binary is not an absolute path: {}", self.opencode.binary);
        }
        if !path.exists() {
            anyhow::bail!("opencode binary not found: {}", self.opencode.binary);
        }
        Ok(())
    }
}

fn which_opencode() -> anyhow::Result<PathBuf> {
    let path_var = std::env::var("PATH").unwrap_or_default();
    let sep = if cfg!(windows) { ';' } else { ':' };

    for dir in path_var.split(sep) {
        let dir = PathBuf::from(dir);
        if !dir.exists() {
            continue;
        }

        #[cfg(windows)]
        {
            for ext in &["cmd", "exe", "ps1"] {
                let candidate = dir.join(format!("opencode.{}", ext));
                if candidate.exists() {
                    return Ok(candidate);
                }
            }
        }

        #[cfg(not(windows))]
        {
            let candidate = dir.join("opencode");
            if candidate.exists() {
                return Ok(candidate);
            }
        }
    }

    anyhow::bail!(
        "opencode not found in PATH. \
         Install opencode first: https://opencode.ai"
    )
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn test_default_config() {
        let config = Config::default();
        assert_eq!(config.bridge.port, 4097);
        assert_eq!(config.bridge.hostname, "0.0.0.0");
        assert_eq!(config.opencode.binary, "opencode");
        assert_eq!(config.opencode.hostname, "127.0.0.1");
        assert_eq!(config.opencode.port, 4096);
        assert!(config.opencode.auto_start);
        assert!(config.opencode.auto_restart);
        assert!(config.fs.allowed_paths.is_empty());
    }

    #[test]
    fn test_opencode_url() {
        let config = Config::default();
        assert_eq!(config.opencode_url(), "http://127.0.0.1:4096");
    }

    #[test]
    fn test_bridge_listen_addr() {
        let config = Config::default();
        assert_eq!(config.bridge_listen_addr(), "0.0.0.0:4097");
    }

    #[test]
    fn test_effective_allowed_paths_empty_allows_all() {
        let config = Config::default();
        let paths = config.effective_allowed_paths();
        assert!(paths.is_empty());
    }

    #[test]
    fn test_effective_allowed_paths_from_fs_config() {
        let mut config = Config::default();
        config.fs.allowed_paths = vec!["/a".to_string(), "/b".to_string()];
        let paths = config.effective_allowed_paths();
        assert_eq!(paths, vec![PathBuf::from("/a"), PathBuf::from("/b")]);
    }

    #[test]
    fn test_load_from_db_uses_defaults() {
        let dir = tempfile::tempdir().unwrap();
        let db_path = dir.path().join("test_config.db");
        let db = BridgeDb::open_at(&db_path).unwrap();
        db.init_default_configs().unwrap();
        let config = Config::load_from_db(&db).unwrap();
        assert_eq!(config.bridge.port, 4097);
        assert_eq!(config.bridge.hostname, "0.0.0.0");
        assert_eq!(config.opencode.port, 4096);
        assert!(config.opencode.auto_start);
    }

    #[test]
    fn test_save_and_load_roundtrip() {
        let dir = tempfile::tempdir().unwrap();
        let db_path = dir.path().join("test_roundtrip.db");
        let db = BridgeDb::open_at(&db_path).unwrap();

        let mut config = Config::default();
        config.bridge.port = 8080;
        config.bridge.hostname = "127.0.0.1".to_string();
        config.opencode.port = 3000;
        config.opencode.directory = "/test".to_string();
        config.fs.allowed_paths = vec!["/a".to_string(), "/b".to_string()];
        config.gateway.url = "ws://gateway.test".to_string();

        config.save_to_db(&db).unwrap();
        let loaded = Config::load_from_db(&db).unwrap();

        assert_eq!(loaded.bridge.port, 8080);
        assert_eq!(loaded.bridge.hostname, "127.0.0.1");
        assert_eq!(loaded.opencode.port, 3000);
        assert_eq!(loaded.opencode.directory, "/test");
        assert_eq!(loaded.fs.allowed_paths, vec!["/a", "/b"]);
        assert_eq!(loaded.gateway.url, "ws://gateway.test");
    }

    #[test]
    fn test_load_from_empty_db_uses_fallback_defaults() {
        let dir = tempfile::tempdir().unwrap();
        let db_path = dir.path().join("test_empty.db");
        let db = BridgeDb::open_at(&db_path).unwrap();
        let config = Config::load_from_db(&db).unwrap();
        assert_eq!(config.bridge.port, 4097);
        assert_eq!(config.opencode.auto_start, true);
        assert!(config.fs.allowed_paths.is_empty());
    }

    #[test]
    fn test_is_port_available_with_occupied_port() {
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
}

use base64::Engine;
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
    pub run_as_user: String,
    pub upgrade_registry: String,
    pub password: String,
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

/// 解析实际使用的 opencode 数据库文件。
///
/// opencode 正式渠道（latest/prod/dev/beta/next）使用 `opencode.db`，早期版本曾用
/// `opencode-next.db`。配置值可能来自旧默认值或用户手填，这里做一次纠偏：
/// 配置路径不可用（不存在 / 不是 opencode 库 / 缺少所需表）时，在同目录下按
/// 「可用 + 文件最大」挑选候选，避免 Bridge 一直读空库（表现为 App 里消息全空）。
pub fn resolve_opencode_db_path(configured: &str) -> PathBuf {
    let configured = PathBuf::from(configured);
    if is_opencode_db(&configured) {
        return configured;
    }

    let dir = configured
        .parent()
        .map(|p| p.to_path_buf())
        .unwrap_or_else(|| PathBuf::from("."));

    let mut candidates: Vec<(u64, PathBuf)> = Vec::new();
    if let Ok(entries) = std::fs::read_dir(&dir) {
        for entry in entries.flatten() {
            let path = entry.path();
            if path.extension().map(|ext| ext != "db").unwrap_or(true) {
                continue;
            }
            if !is_opencode_db(&path) {
                continue;
            }
            let size = entry.metadata().map(|m| m.len()).unwrap_or(0);
            candidates.push((size, path));
        }
    }
    candidates.sort_by(|a, b| b.0.cmp(&a.0));

    match candidates.into_iter().next() {
        Some((size, path)) => {
            tracing::warn!(
                "opencode db_path {} is unusable; using {} instead ({} bytes)",
                configured.display(),
                path.display(),
                size
            );
            path
        }
        None => {
            tracing::warn!(
                "opencode db_path {} is unusable and no candidate was found in {}",
                configured.display(),
                dir.display()
            );
            configured
        }
    }
}

/// 文件是否为可用的 opencode 数据库（含同步所需的表）。
fn is_opencode_db(path: &std::path::Path) -> bool {
    if !path.is_file() {
        return false;
    }
    let Some(conn) = open_db_file(path) else {
        return false;
    };
    conn.query_row(
        "SELECT COUNT(*) FROM sqlite_master WHERE type = 'table'
         AND name IN ('session_message', 'message', 'session', 'session_v2')",
        [],
        |row| row.get::<_, i64>(0),
    )
    .map(|found| found > 0)
    .unwrap_or(false)
}

/// 以只读方式打开数据库；WAL 库在无其他连接时只读打开可能失败，失败则退回读写方式。
fn open_db_file(path: &std::path::Path) -> Option<rusqlite::Connection> {
    use rusqlite::OpenFlags;
    rusqlite::Connection::open_with_flags(
        path,
        OpenFlags::SQLITE_OPEN_READ_ONLY | OpenFlags::SQLITE_OPEN_NO_MUTEX,
    )
    .or_else(|_| {
        rusqlite::Connection::open_with_flags(
            path,
            OpenFlags::SQLITE_OPEN_READ_WRITE | OpenFlags::SQLITE_OPEN_NO_MUTEX,
        )
    })
    .ok()
}

pub fn default_password_path() -> String {
    let home = dirs::home_dir().unwrap_or_default();
    let path = home.join(".local").join("state").join("opencode").join("password");
    path.to_string_lossy().to_string()
}

pub fn default_service_json_path() -> String {
    let home = dirs::home_dir().unwrap_or_default();
    let path = home.join(".local").join("state").join("opencode").join("service.json");
    path.to_string_lossy().to_string()
}

pub fn read_service_password() -> String {
    for key in ["OPENCODE_SERVER_PASSWORD", "OPENCODE_PASSWORD"] {
        if let Ok(pw) = std::env::var(key) {
            if !pw.is_empty() {
                return pw;
            }
        }
    }

    let path = default_service_json_path();
    match std::fs::read_to_string(&path) {
        Ok(content) => {
            match serde_json::from_str::<serde_json::Value>(&content) {
                Ok(json) => json.get("password").and_then(|v| v.as_str()).unwrap_or("").to_string(),
                Err(_) => String::new(),
            }
        }
        Err(_) => String::new(),
    }
}

/// V2 `opencode service` 的实际监听地址（`service.json` 的 `url`）。
/// 服务可能监听在非配置端口（如随机端口），Bridge 应以该地址为准。
pub fn read_service_url() -> Option<String> {
    let path = default_service_json_path();
    let content = std::fs::read_to_string(&path).ok()?;
    let json: serde_json::Value = serde_json::from_str(&content).ok()?;
    let url = json.get("url").and_then(|v| v.as_str())?.trim().to_string();
    if url.is_empty() {
        return None;
    }
    // 服务绑定 0.0.0.0，客户端连接改用 127.0.0.1
    Some(url.replacen("//0.0.0.0", "//127.0.0.1", 1))
}

pub fn read_opencode_password() -> String {
    let pw = read_service_password();
    if !pw.is_empty() {
        return pw;
    }
    let path = default_password_path();
    std::fs::read_to_string(&path).unwrap_or_else(|e| {
        tracing::warn!("Failed to read opencode password from {}: {}", path, e);
        String::new()
    })
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
                run_as_user: String::new(),
                upgrade_registry: String::new(),
                password: String::new(),
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
                key: "opencode.run_as_user".into(),
                value: String::new(),
                default: String::new(),
                r#type: "string".into(),
                needs_restart: false,
                description: "Run opencode as this user (empty = current user, for systemd service)".into(),
            },
            ConfigEntry {
                key: "opencode.upgrade_registry".into(),
                value: String::new(),
                default: String::new(),
                r#type: "string".into(),
                needs_restart: true,
                description: "npm registry for opencode upgrade (empty = auto: probe and pick the fastest)".into(),
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

    /// 优先使用 V2 `opencode service` 实际地址（`service.json`），否则回退配置的 host:port。
    pub fn effective_opencode_url(&self) -> String {
        read_service_url().unwrap_or_else(|| self.opencode_url())
    }

    pub fn opencode_auth_header(&self) -> Option<String> {
        let pw = if self.opencode.password.is_empty() {
            read_service_password()
        } else {
            self.opencode.password.clone()
        };
        if pw.is_empty() {
            None
        } else {
            let credentials = format!("opencode:{}", pw);
            let encoded = base64::engine::general_purpose::STANDARD.encode(credentials);
            Some(format!("Basic {}", encoded))
        }
    }

    pub fn quick_auth_header() -> Option<String> {
        let pw = read_service_password();
        if pw.is_empty() {
            None
        } else {
            let credentials = format!("opencode:{}", pw);
            let encoded = base64::engine::general_purpose::STANDARD.encode(credentials);
            Some(format!("Basic {}", encoded))
        }
    }

    pub fn db_path(&self) -> std::path::PathBuf {
        resolve_opencode_db_path(&self.opencode.db_path)
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
                run_as_user: get("opencode.run_as_user", ""),
                upgrade_registry: get("opencode.upgrade_registry", ""),
                password: {
                    let configured = get("opencode.password", "");
                    if configured.is_empty() { read_opencode_password() } else { configured }
                },
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
            ("opencode.run_as_user".to_string(), self.opencode.run_as_user.clone()),
            ("opencode.upgrade_registry".to_string(), self.opencode.upgrade_registry.clone()),
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

    // Bridge 可能以精简 PATH 启动（服务 / launchd / nohup），用户 shell 里追加的目录不生效
    #[cfg(not(windows))]
    {
        for dir in extra_binary_dirs() {
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

/// 常见的 opencode / 包管理器安装目录（非 Windows）。
///
/// Bridge 常以精简 PATH 启动（macOS 的 nohup/launchd、Linux 的 systemd），
/// 而 `~/.opencode/bin` 之类通常只由用户的 `.zshrc`/`.bashrc` 追加到交互式 shell。
#[cfg(not(windows))]
pub fn extra_binary_dirs() -> Vec<PathBuf> {
    let mut dirs: Vec<PathBuf> = Vec::new();
    if let Some(home) = dirs::home_dir() {
        dirs.push(home.join(".opencode").join("bin"));
        dirs.push(home.join(".local").join("bin"));
        dirs.push(home.join(".npm-global").join("bin"));
        dirs.push(home.join(".bun").join("bin"));
    }
    dirs.push(PathBuf::from("/opt/homebrew/bin"));
    dirs.push(PathBuf::from("/usr/local/bin"));
    dirs.push(PathBuf::from("/usr/bin"));
    dirs
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
    fn test_default_db_path_uses_opencode_db() {
        assert!(default_db_path().ends_with("opencode.db"));
    }

    #[test]
    fn test_resolve_db_path_falls_back_to_real_database() {
        let dir = tempfile::tempdir().unwrap();
        let stale = dir.path().join("opencode-next.db");
        let real = dir.path().join("opencode.db");

        std::fs::File::create(&stale).unwrap();
        let conn = rusqlite::Connection::open(&real).unwrap();
        conn.execute_batch("CREATE TABLE session_message (id TEXT);").unwrap();
        drop(conn);

        assert_eq!(resolve_opencode_db_path(&stale.to_string_lossy()), real);
    }

    #[test]
    fn test_resolve_db_path_keeps_usable_configured_path() {
        let dir = tempfile::tempdir().unwrap();
        let configured = dir.path().join("custom.db");
        let conn = rusqlite::Connection::open(&configured).unwrap();
        conn.execute_batch("CREATE TABLE session_v2 (id TEXT);").unwrap();
        drop(conn);

        assert_eq!(resolve_opencode_db_path(&configured.to_string_lossy()), configured);
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

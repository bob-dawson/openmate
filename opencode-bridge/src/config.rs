use serde::{Deserialize, Serialize};
use std::path::PathBuf;

#[derive(Debug, Deserialize, Serialize, Clone)]
pub struct Config {
    #[serde(default = "default_bridge")]
    pub bridge: BridgeConfig,

    #[serde(default = "default_opencode")]
    pub opencode: OpencodeConfig,

    #[serde(default)]
    pub fs: FsConfig,
}

#[derive(Debug, Deserialize, Serialize, Clone)]
pub struct BridgeConfig {
    #[serde(default = "default_port")]
    pub port: u16,
    #[serde(default = "default_hostname")]
    pub hostname: String,
    #[serde(default = "default_true")]
    pub auth_enabled: bool,
}

#[derive(Debug, Deserialize, Serialize, Clone)]
pub struct OpencodeConfig {
    #[serde(default = "default_binary")]
    pub binary: String,
    #[serde(default = "default_oc_hostname")]
    pub hostname: String,
    #[serde(default = "default_oc_port")]
    pub port: u16,
    #[serde(default)]
    pub directory: String,
    #[serde(default = "default_true")]
    pub auto_start: bool,
    #[serde(default = "default_true")]
    pub auto_restart: bool,

    #[serde(default = "default_db_path")]
    pub db_path: String,
}

#[derive(Debug, Deserialize, Serialize, Clone)]
pub struct FsConfig {
    #[serde(default)]
    pub allowed_paths: Vec<String>,
}

fn default_bridge() -> BridgeConfig {
    BridgeConfig {
        port: default_port(),
        hostname: default_hostname(),
        auth_enabled: true,
    }
}

fn default_opencode() -> OpencodeConfig {
    OpencodeConfig {
        binary: default_binary(),
        hostname: default_oc_hostname(),
        port: default_oc_port(),
        directory: String::new(),
        auto_start: true,
        auto_restart: true,
        db_path: default_db_path(),
    }
}

fn default_port() -> u16 {
    4097
}

fn default_hostname() -> String {
    "0.0.0.0".to_string()
}

fn default_binary() -> String {
    "opencode".to_string()
}

fn default_oc_hostname() -> String {
    "127.0.0.1".to_string()
}

fn default_oc_port() -> u16 {
    4096
}

fn default_true() -> bool {
    true
}

fn default_db_path() -> String {
    let home = dirs::home_dir().unwrap_or_default();
    let path = home.join(".local").join("share").join("opencode").join("opencode.db");
    path.to_string_lossy().to_string()
}

impl Default for FsConfig {
    fn default() -> Self {
        FsConfig {
            allowed_paths: Vec::new(),
        }
    }
}

impl Config {
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

    pub fn load_from(path: &PathBuf) -> anyhow::Result<Self> {
        let content = std::fs::read_to_string(path)?;
        let config: Config = toml::from_str(&content)?;
        Ok(config)
    }

    pub fn find_and_load(config_path: Option<PathBuf>) -> anyhow::Result<Self> {
        if let Some(path) = config_path {
            return Self::load_from(&path);
        }

        let exe_dir = std::env::current_exe()
            .ok()
            .and_then(|p| p.parent().map(|p| p.to_path_buf()));

        let mut candidates = vec![PathBuf::from("bridge.toml")];
        if let Some(dir) = exe_dir {
            candidates.push(dir.join("bridge.toml"));
        }
        candidates.push(dirs_config_path());

        for path in &candidates {
            if path.exists() {
                tracing::info!("Loading config from {}", path.display());
                return Self::load_from(path);
            }
        }

        tracing::info!("No config file found, using defaults");
        Ok(Config::default())
    }

    pub fn find_config_path() -> Option<PathBuf> {
        let exe_dir = std::env::current_exe()
            .ok()
            .and_then(|p| p.parent().map(|p| p.to_path_buf()));

        let mut candidates = vec![PathBuf::from("bridge.toml")];
        if let Some(dir) = exe_dir {
            candidates.push(dir.join("bridge.toml"));
        }
        candidates.push(dirs_config_path());

        for path in &candidates {
            if path.exists() {
                return Some(path.clone());
            }
        }
        None
    }

    pub fn find_or_create_config_path() -> PathBuf {
        if let Some(path) = Self::find_config_path() {
            return path;
        }
        let exe_dir = std::env::current_exe()
            .ok()
            .and_then(|p| p.parent().map(|p| p.to_path_buf()))
            .unwrap_or_else(|| PathBuf::from("."));
        exe_dir.join("bridge.toml")
    }

    pub fn save_to(&self, path: &PathBuf) -> anyhow::Result<()> {
        let content = toml::to_string_pretty(self)?;
        std::fs::write(path, content)?;
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

fn dirs_config_path() -> PathBuf {
    let home = std::env::var("HOME")
        .or_else(|_| std::env::var("USERPROFILE"))
        .unwrap_or_else(|_| ".".to_string());
    PathBuf::from(home).join(".openmate").join("bridge.toml")
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
         Install opencode first: https://opencode.ai \
         Or set the full path in bridge.toml [opencode] binary"
    )
}

impl Default for Config {
    fn default() -> Self {
        Config {
            bridge: default_bridge(),
            opencode: default_opencode(),
            fs: FsConfig::default(),
        }
    }
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
    fn test_load_from_toml() {
        let tmp = std::env::temp_dir().join("bridge_config_test.toml");
        let content = r#"
[bridge]
port = 8080
hostname = "127.0.0.1"

[opencode]
binary = "/usr/local/bin/opencode"
hostname = "0.0.0.0"
port = 3000
directory = "/home/user/project"
auto_start = false
auto_restart = false

[fs]
allowed_paths = ["/home/user/project", "/tmp"]
"#;
        std::fs::write(&tmp, content).unwrap();
        let config = Config::load_from(&tmp).unwrap();

        assert_eq!(config.bridge.port, 8080);
        assert_eq!(config.bridge.hostname, "127.0.0.1");
        assert_eq!(config.opencode.binary, "/usr/local/bin/opencode");
        assert_eq!(config.opencode.hostname, "0.0.0.0");
        assert_eq!(config.opencode.port, 3000);
        assert_eq!(config.opencode.directory, "/home/user/project");
        assert!(!config.opencode.auto_start);
        assert!(!config.opencode.auto_restart);
        assert_eq!(config.fs.allowed_paths, vec!["/home/user/project", "/tmp"]);

        let _ = std::fs::remove_file(&tmp);
    }

    #[test]
    fn test_load_from_partial_toml_uses_defaults() {
        let tmp = std::env::temp_dir().join("bridge_config_partial.toml");
        let content = r#"
[bridge]
port = 9999
"#;
        std::fs::write(&tmp, content).unwrap();
        let config = Config::load_from(&tmp).unwrap();

        assert_eq!(config.bridge.port, 9999);
        assert_eq!(config.bridge.hostname, "0.0.0.0");
        assert_eq!(config.opencode.port, 4096);
        assert!(config.opencode.auto_start);

        let _ = std::fs::remove_file(&tmp);
    }

    #[test]
    fn test_load_from_invalid_toml_fails() {
        let tmp = std::env::temp_dir().join("bridge_config_bad.toml");
        std::fs::write(&tmp, "not valid toml {{{").unwrap();
        let result = Config::load_from(&tmp);
        assert!(result.is_err());
        let _ = std::fs::remove_file(&tmp);
    }
}

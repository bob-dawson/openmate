use serde::Deserialize;
use std::path::Path;

#[derive(Debug, Deserialize, Clone)]
pub struct GatewayConfig {
    #[serde(default = "default_port")]
    pub port: u16,
    #[serde(default = "default_hostname")]
    pub hostname: String,
    #[serde(default)]
    pub tls_cert: Option<String>,
    #[serde(default)]
    pub tls_key: Option<String>,
}

fn default_port() -> u16 {
    8080
}

fn default_hostname() -> String {
    "0.0.0.0".to_string()
}

impl Default for GatewayConfig {
    fn default() -> Self {
        GatewayConfig {
            port: default_port(),
            hostname: default_hostname(),
            tls_cert: None,
            tls_key: None,
        }
    }
}

#[derive(Debug, Deserialize, Clone)]
pub struct AuthConfig {
    #[serde(default = "default_secret_key_path")]
    pub secret_key_path: String,
}

fn default_secret_key_path() -> String {
    "secret.key".to_string()
}

impl Default for AuthConfig {
    fn default() -> Self {
        AuthConfig {
            secret_key_path: default_secret_key_path(),
        }
    }
}

#[derive(Debug, Deserialize, Clone)]
pub struct TunnelConfig {
    #[serde(default = "default_heartbeat_interval")]
    pub heartbeat_interval: u64,
    #[serde(default = "default_heartbeat_timeout")]
    pub heartbeat_timeout: u64,
    #[serde(default = "default_request_timeout")]
    pub request_timeout: u64,
    #[serde(default = "default_max_request_body")]
    pub max_request_body: usize,
}

fn default_heartbeat_interval() -> u64 {
    30
}

fn default_heartbeat_timeout() -> u64 {
    60
}

fn default_request_timeout() -> u64 {
    30
}

fn default_max_request_body() -> usize {
    10 * 1024 * 1024
}

impl Default for TunnelConfig {
    fn default() -> Self {
        TunnelConfig {
            heartbeat_interval: default_heartbeat_interval(),
            heartbeat_timeout: default_heartbeat_timeout(),
            request_timeout: default_request_timeout(),
            max_request_body: default_max_request_body(),
        }
    }
}

#[derive(Debug, Deserialize, Clone)]
pub struct Config {
    #[serde(default)]
    pub gateway: GatewayConfig,
    #[serde(default)]
    pub auth: AuthConfig,
    #[serde(default)]
    pub tunnel: TunnelConfig,
}

impl Default for Config {
    fn default() -> Self {
        Config {
            gateway: GatewayConfig::default(),
            auth: AuthConfig::default(),
            tunnel: TunnelConfig::default(),
        }
    }
}

impl Config {
    pub fn listen_addr(&self) -> String {
        format!("{}:{}", self.gateway.hostname, self.gateway.port)
    }

    pub fn load_from(path: &Path) -> anyhow::Result<Self> {
        let content = std::fs::read_to_string(path)?;
        let config: Config = toml::from_str(&content)?;
        Ok(config)
    }

    pub fn find_and_load(path: &str) -> anyhow::Result<Self> {
        let p = Path::new(path);
        if p.exists() {
            Self::load_from(p)
        } else {
            tracing::warn!("config file '{}' not found, using defaults", path);
            Ok(Config::default())
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn listen_addr_default() {
        let config = Config::default();
        assert_eq!(config.listen_addr(), "0.0.0.0:8080");
    }

    #[test]
    fn listen_addr_custom() {
        let config = Config {
            gateway: GatewayConfig {
                port: 9090,
                hostname: "127.0.0.1".to_string(),
                tls_cert: None,
                tls_key: None,
            },
            ..Config::default()
        };
        assert_eq!(config.listen_addr(), "127.0.0.1:9090");
    }

    #[test]
    fn load_from_toml() {
        let dir = std::env::temp_dir().join("relay_gateway_test_load_from");
        std::fs::create_dir_all(&dir).unwrap();
        let path = dir.join("test.toml");
        std::fs::write(
            &path,
            r#"
[gateway]
port = 3000
hostname = "192.168.1.1"

[auth]
secret_key_path = "/etc/my.key"

[tunnel]
heartbeat_interval = 10
heartbeat_timeout = 20
request_timeout = 15
max_request_body = 5242880
"#,
        )
        .unwrap();

        let config = Config::load_from(&path).unwrap();
        assert_eq!(config.gateway.port, 3000);
        assert_eq!(config.gateway.hostname, "192.168.1.1");
        assert_eq!(config.auth.secret_key_path, "/etc/my.key");
        assert_eq!(config.tunnel.heartbeat_interval, 10);
        assert_eq!(config.tunnel.heartbeat_timeout, 20);
        assert_eq!(config.tunnel.request_timeout, 15);
        assert_eq!(config.tunnel.max_request_body, 5242880);
    }

    #[test]
    fn load_from_partial_uses_defaults() {
        let dir = std::env::temp_dir().join("relay_gateway_test_partial");
        std::fs::create_dir_all(&dir).unwrap();
        let path = dir.join("partial.toml");
        std::fs::write(&path, "[gateway]\nport = 7070\n").unwrap();

        let config = Config::load_from(&path).unwrap();
        assert_eq!(config.gateway.port, 7070);
        assert_eq!(config.gateway.hostname, "0.0.0.0");
        assert_eq!(config.tunnel.heartbeat_interval, 30);
    }

    #[test]
    fn find_and_load_missing_returns_default() {
        let config = Config::find_and_load("/nonexistent/path.toml").unwrap();
        assert_eq!(config.gateway.port, 8080);
    }
}

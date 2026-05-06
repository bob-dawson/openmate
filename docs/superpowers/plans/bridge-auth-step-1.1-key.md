# Bridge 1.1: 密钥管理模块 (`auth/key.rs`)

## 目标

实现 Bridge 的 HMAC-SHA256 密钥管理：首次启动自动生成 256-bit 密钥并写入 `~/.openmate/bridge.key`，后续启动从文件加载。文件权限 0600。

## 文件变更

| 操作 | 路径 |
|------|------|
| 创建 | `src/auth/mod.rs` |
| 创建 | `src/auth/key.rs` |
| 修改 | `src/lib.rs` — 添加 `pub mod auth;` |
| 修改 | `Cargo.toml` — 添加 `hmac`, `sha2` 依赖 |
| 修改 | `src/config.rs` — BridgeConfig 新增 `auth_enabled: bool` |
| 修改 | `src/state.rs` — AppStateInner 新增 `secret_key` |

## 依赖变更 (`Cargo.toml`)

```toml
hmac = "0.12"
sha2 = "0.10"
```

## `src/auth/mod.rs`

```rust
pub mod key;
pub mod token;
pub mod pair;
pub mod middleware;
```

注：此文件在后续步骤中逐步添加子模块。1.1 步骤只需 `pub mod key;`。

## `src/auth/key.rs`

```rust
use std::path::PathBuf;

const KEY_FILE_NAME: &str = "bridge.key";

pub struct SecretKey {
    key: Vec<u8>,
}

impl SecretKey {
    pub fn load_or_generate() -> anyhow::Result<Self> {
        let path = key_file_path()?;
        if path.exists() {
            let hex = std::fs::read_to_string(&path)?;
            let key = hex_to_bytes(hex.trim())?;
            if key.len() != 32 {
                tracing::warn!("Invalid key length, regenerating");
                return Self::generate_and_save(&path);
            }
            tracing::info!("Loaded secret key from {}", path.display());
            Ok(Self { key })
        } else {
            Self::generate_and_save(&path)
        }
    }

    pub fn as_bytes(&self) -> &[u8] {
        &self.key
    }

    pub fn delete_key_file() -> anyhow::Result<()> {
        let path = key_file_path()?;
        if path.exists() {
            std::fs::remove_file(&path)?;
            tracing::info!("Deleted key file {}", path.display());
        }
        Ok(())
    }

    fn generate_and_save(path: &PathBuf) -> anyhow::Result<Self> {
        let key = generate_random_bytes(32);
        let hex = hex_encode(&key);
        if let Some(parent) = path.parent() {
            std::fs::create_dir_all(parent)?;
        }
        std::fs::write(path, &hex)?;
        #[cfg(unix)]
        {
            use std::os::unix::fs::PermissionsExt;
            std::fs::set_permissions(path, std::fs::Permissions::from_mode(0o600))?;
        }
        tracing::info!("Generated new secret key at {}", path.display());
        Ok(Self { key })
    }
}

fn key_file_path() -> anyhow::Result<PathBuf> {
    let home = std::env::var("HOME")
        .or_else(|_| std::env::var("USERPROFILE"))
        .unwrap_or_else(|_| ".".to_string());
    Ok(PathBuf::from(home).join(".openmate").join(KEY_FILE_NAME))
}

fn generate_random_bytes(len: usize) -> Vec<u8> {
    use std::time::{SystemTime, UNIX_EPOCH};
    let t = SystemTime::now()
        .duration_since(UNIX_EPOCH)
        .unwrap()
        .as_nanos();
    let mut seed = [0u8; 32];
    seed[..8].copy_from_slice(&t.to_le_bytes());
    seed[8..16].copy_from_slice(&t.rotate_left(13).to_le_bytes());
    seed[16..24].copy_from_slice(&t.rotate_right(7).to_le_bytes());
    seed[24..32].copy_from_slice(&(t ^ 0xdeadbeefcafebabe_u128).to_le_bytes());

    let mut result = Vec::with_capacity(len);
    let mut state = u64::from_le_bytes(seed[..8].try_into().unwrap());
    for i in 0..len {
        state = state.wrapping_mul(6364136223846793005).wrapping_add(1442695040888963407);
        result.push(((state >> 33) as u8).wrapping_add(i as u8));
    }

    // 补充：用 getrandom 更安全，但先保持零外部依赖
    // 后续可替换为 getrandom::fill(&mut result)
    result
}

fn hex_encode(bytes: &[u8]) -> String {
    bytes.iter().map(|b| format!("{:02x}", b)).collect()
}

fn hex_to_bytes(hex: &str) -> anyhow::Result<Vec<u8>> {
    if hex.len() % 2 != 0 {
        anyhow::bail!("Invalid hex length");
    }
    (0..hex.len())
        .step_by(2)
        .map(|i| {
            u8::from_str_radix(&hex[i..i + 2], 16)
                .map_err(|e| anyhow::anyhow!("Invalid hex: {}", e))
        })
        .collect()
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn test_hex_roundtrip() {
        let bytes = vec![0x01, 0x23, 0xab, 0xcd, 0xef];
        let hex = hex_encode(&bytes);
        assert_eq!(hex, "0123abcdef");
        let decoded = hex_to_bytes(&hex).unwrap();
        assert_eq!(decoded, bytes);
    }

    #[test]
    fn test_hex_to_bytes_invalid_length() {
        assert!(hex_to_bytes("abc").is_err());
    }

    #[test]
    fn test_hex_to_bytes_invalid_chars() {
        assert!(hex_to_bytes("zz").is_err());
    }

    #[test]
    fn test_generate_random_bytes_length() {
        let bytes = generate_random_bytes(32);
        assert_eq!(bytes.len(), 32);
    }

    #[test]
    fn test_key_file_path_is_under_openmate() {
        let path = key_file_path().unwrap();
        assert!(path.to_string_lossy().contains(".openmate"));
        assert!(path.to_string_lossy().contains("bridge.key"));
    }
}
```

## `src/lib.rs` 变更

在现有模块列表末尾添加：

```rust
pub mod auth;
```

## `src/config.rs` 变更

### BridgeConfig 新增字段

```rust
#[derive(Debug, Deserialize, Clone)]
pub struct BridgeConfig {
    #[serde(default = "default_port")]
    pub port: u16,
    #[serde(default = "default_hostname")]
    pub hostname: String,
    #[serde(default = "default_true")]
    pub auth_enabled: bool,  // 新增，默认 true
}
```

### default_bridge() 更新

```rust
fn default_bridge() -> BridgeConfig {
    BridgeConfig {
        port: default_port(),
        hostname: default_hostname(),
        auth_enabled: true,
    }
}
```

### 新增测试

```rust
#[test]
fn test_auth_enabled_default_is_true() {
    let config = Config::default();
    assert!(config.bridge.auth_enabled);
}

#[test]
fn test_auth_disabled_in_toml() {
    let tmp = std::env::temp_dir().join("bridge_auth_test.toml");
    let content = r#"
[bridge]
port = 4097
auth_enabled = false
"#;
    std::fs::write(&tmp, content).unwrap();
    let config = Config::load_from(&tmp).unwrap();
    assert!(!config.bridge.auth_enabled);
    let _ = std::fs::remove_file(&tmp);
}
```

## `src/state.rs` 变更

### AppStateInner 新增字段

```rust
pub struct AppStateInner {
    pub config: Config,
    pub opencode_status: RwLock<OpencodeStatus>,
    pub opencode_manager: OpencodeManager,
    pub secret_key: auth::key::SecretKey,  // 新增
}
```

### create_app_state 更新

```rust
pub fn create_app_state(config: Config) -> AppState {
    let secret_key = auth::key::SecretKey::load_or_generate()
        .expect("Failed to load or generate secret key");
    let opencode_url = config.opencode_url();
    // ... 其余不变
    Arc::new(AppStateInner {
        config,
        opencode_status: RwLock::new(OpencodeStatus::Stopped),
        opencode_manager: OpencodeManager::with_config(/* ... */),
        secret_key,  // 新增
    })
}
```

### 新增 FromRef

```rust
impl FromRef<AppState> for auth::key::SecretKey {
    fn from_ref(state: &AppState) -> Self {
        state.secret_key.clone()  // 需要 SecretKey 实现 Clone
    }
}
```

为此 `SecretKey` 需要派生 `Clone`：

```rust
#[derive(Clone)]
pub struct SecretKey {
    key: Vec<u8>,
}
```

## 重要说明

1. **随机数生成**：上面用了简易 PCG 方案。生产建议添加 `getrandom` crate（`getrandom = "0.2"`）替换 `generate_random_bytes`：
   ```rust
   fn generate_random_bytes(len: usize) -> Vec<u8> {
       let mut buf = vec![0u8; len];
       getrandom::fill(&mut buf).expect("Failed to get random bytes");
       buf
   }
   ```
   建议在 Cargo.toml 额外添加 `getrandom = "0.2"`。

2. **Windows 文件权限**：Windows 没有 Unix 0600 概念，`#[cfg(unix)]` 分支在 Windows 上不执行。Windows 上 `std::fs::write` 默认继承目录 ACL，对于 `~/.openmate/` 目录已只有当前用户可访问的场景足够。如需更严格，可用 Windows ACL API，但当前阶段不必要。

3. **集成测试影响**：`create_app_state` 现在会读写 `~/.openmate/bridge.key`。集成测试的 `test_app()` 直接调用 `create_app_state`，需要确保测试环境可写。测试也可设置 `auth_enabled = false` 跳过认证。

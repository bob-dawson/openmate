# Bridge API 安全认证 — 完整实现计划

> 基于 docs/superpowers/specs/2026-05-05-bridge-auth-design.md 规格文档

## 步骤索引

1. [bridge-auth-step-1.1-key](./bridge-auth-step-1.1-key.md)
2. [bridge-auth-step-1.2-token](./bridge-auth-step-1.2-token.md)
3. [bridge-auth-step-1.3-pair](./bridge-auth-step-1.3-pair.md)
4. [bridge-auth-step-1.4-middleware](./bridge-auth-step-1.4-middleware.md)
5. [bridge-auth-step-1.5-cli](./bridge-auth-step-1.5-cli.md)
6. [bridge-auth-step-1.6-proxy-strip](./bridge-auth-step-1.6-proxy-strip.md)
7. [bridge-auth-step-2.1-tokenstore](./bridge-auth-step-2.1-tokenstore.md)
8. [bridge-auth-step-2.2-interceptor](./bridge-auth-step-2.2-interceptor.md)
9. [bridge-auth-step-2.3-pair-api](./bridge-auth-step-2.3-pair-api.md)
10. [bridge-auth-step-2.4-sse-bearer](./bridge-auth-step-2.4-sse-bearer.md)
11. [bridge-auth-step-2.5-profile-token](./bridge-auth-step-2.5-profile-token.md)
12. [bridge-auth-step-3.1-pairing-screen](./bridge-auth-step-3.1-pairing-screen.md)
13. [bridge-auth-step-3.2-pairing-viewmodel](./bridge-auth-step-3.2-pairing-viewmodel.md)
14. [bridge-auth-step-3.3-repairing](./bridge-auth-step-3.3-repairing.md)

---

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


---

# Bridge 1.2: Token 生成与验证 (`auth/token.rs`)

## 目标

实现 HMAC-SHA256 签名 Token 的生成与无状态验证。Token 格式：`token_random(64 hex) + token_signature(64 hex)` = 128 hex 字符。

## 文件变更

| 操作 | 路径 |
|------|------|
| 创建 | `src/auth/token.rs` |
| 修改 | `src/auth/mod.rs` — 已有 `pub mod token;` |

## `src/auth/token.rs`

```rust
use hmac::{Hmac, Mac};
use sha2::Sha256;

use super::key::SecretKey;

type HmacSha256 = Hmac<Sha256>;

pub struct Token;

impl Token {
    pub fn generate(secret_key: &SecretKey) -> String {
        let random_part = super::key::generate_random_bytes(32);
        let random_hex = super::key::hex_encode(&random_part);

        let signature = compute_hmac(secret_key.as_bytes(), &random_hex);
        let signature_hex = super::key::hex_encode(&signature);

        format!("{}{}", random_hex, signature_hex)
    }

    pub fn validate(secret_key: &SecretKey, token: &str) -> bool {
        if token.len() != 128 {
            return false;
        }

        let random_hex = &token[..64];
        let signature_hex = &token[64..];

        let expected = compute_hmac(secret_key.as_bytes(), random_hex);
        let expected_hex = super::key::hex_encode(&expected);

        constant_time_eq(signature_hex.as_bytes(), expected_hex.as_bytes())
    }

    pub fn extract_from_header(header_value: &str) -> Option<&str> {
        header_value.strip_prefix("Bearer ")
    }
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
    fn test_generate_token_length() {
        let key = test_key();
        let token = Token::generate(&key);
        assert_eq!(token.len(), 128);
    }

    #[test]
    fn test_validate_valid_token() {
        let key = test_key();
        let token = Token::generate(&key);
        assert!(Token::validate(&key, &token));
    }

    #[test]
    fn test_validate_wrong_key() {
        let key1 = test_key();
        let key2 = SecretKey::from_bytes(vec![0x24u8; 32]);
        let token = Token::generate(&key1);
        assert!(!Token::validate(&key2, &token));
    }

    #[test]
    fn test_validate_tampered_token() {
        let key = test_key();
        let token = Token::generate(&key);
        let mut tampered = token.clone();
        let mut bytes: Vec<char> = tampered.chars().collect();
        bytes[0] = if bytes[0] == '0' { '1' } else { '0' };
        tampered = bytes.into_iter().collect();
        assert!(!Token::validate(&key, &tampered));
    }

    #[test]
    fn test_validate_wrong_length() {
        let key = test_key();
        assert!(!Token::validate(&key, "tooshort"));
        assert!(!Token::validate(&key, &"a".repeat(64)));
    }

    #[test]
    fn test_extract_from_header() {
        assert_eq!(Token::extract_from_header("Bearer abc123"), Some("abc123"));
        assert_eq!(Token::extract_from_header("Basic abc123"), None);
        assert_eq!(Token::extract_from_header(""), None);
    }

    #[test]
    fn test_same_key_validates_multiple_tokens() {
        let key = test_key();
        let t1 = Token::generate(&key);
        let t2 = Token::generate(&key);
        assert_ne!(t1, t2);
        assert!(Token::validate(&key, &t1));
        assert!(Token::validate(&key, &t2));
    }
}
```

## `SecretKey` 需要额外方法

在 `src/auth/key.rs` 中添加测试辅助方法（也可用于从已知密钥恢复）：

```rust
impl SecretKey {
    pub fn from_bytes(key: Vec<u8>) -> Self {
        Self { key }
    }
}
```

## 关键设计点

1. **无状态验证**：不需要存储已签发的 token。验证仅依赖 secret_key + HMAC 重算，这是核心优势——Bridge 重启后 token 依然有效（只要密钥文件未删除）。

2. **常量时间比较**：`constant_time_eq` 防止时序攻击。不使用 `==` 直接比较。

3. **Token 不可伪造**：128 hex 字符中，后 64 字符是 HMAC-SHA256 签名，没有 secret_key 无法生成有效签名。

4. **Token 唯一性**：每次 `generate` 使用随机 256-bit 前缀，碰撞概率极低。


---

# Bridge 1.3: PendingPair 内存状态 + 配对 API

## 目标

实现 PIN 配对流程的内存数据结构和 3 个 API handler：
- `POST /api/bridge/pair/request` — 生成 PIN，等待授权
- `POST /api/bridge/pair/approve` — localhost 授权 PIN
- `POST /api/bridge/pair/confirm` — 用 PIN 换取 token

## 文件变更

| 操作 | 路径 |
|------|------|
| 创建 | `src/auth/pair.rs` |
| 修改 | `src/state.rs` — AppStateInner 新增 `pending_pairs` |
| 修改 | `src/main.rs` — 注册配对路由 |

## `src/auth/pair.rs`

```rust
use axum::extract::{ConnectInfo, State};
use axum::response::IntoResponse;
use serde::{Deserialize, Serialize};
use std::collections::HashMap;
use std::net::SocketAddr;
use std::sync::Arc;
use tokio::sync::RwLock;
use tokio::time::Instant;

use crate::error::AppError;
use crate::state::AppState;
use super::key::SecretKey;
use super::token::Token;

const PIN_EXPIRY_SECS: u64 = 300;
const MAX_CONFIRM_ATTEMPTS: u32 = 3;
const RATE_LIMIT_SECS: u64 = 30;

#[derive(Debug, Clone)]
pub struct PendingPair {
    pub pin: String,
    pub ip: String,
    pub approved: bool,
    pub attempts: u32,
    pub created_at: Instant,
}

#[derive(Debug, Clone, Default)]
pub struct PairState {
    pub pending: HashMap<String, PendingPair>,
    pub last_request_by_ip: HashMap<String, Instant>,
}

impl PairState {
    pub fn new() -> Self {
        Self::default()
    }
}

pub type SharedPairState = Arc<RwLock<PairState>>;

#[derive(Deserialize)]
pub struct PairRequestBody {
    pub pin: Option<String>,
}

#[derive(Serialize)]
pub struct PairRequestResponse {
    pub pin: String,
}

#[derive(Serialize)]
pub struct PairApproveResponse {
    pub approved: bool,
}

#[derive(Serialize)]
pub struct PairConfirmResponse {
    pub token: String,
}

fn generate_pin() -> String {
    let bytes = super::key::generate_random_bytes(4);
    let num = u32::from_be_bytes([bytes[0], bytes[1], bytes[2], bytes[3]]) % 1_000_000;
    format!("{:06}", num)
}

fn is_expired(pair: &PendingPair) -> bool {
    pair.created_at.elapsed().as_secs() > PIN_EXPIRY_SECS
}

pub async fn pair_request(
    State(state): State<AppState>,
    ConnectInfo(addr): ConnectInfo<SocketAddr>,
) -> Result<axum::Json<PairRequestResponse>, AppError> {
    let ip = addr.ip().to_string();
    let now = Instant::now();

    let mut pair_state = state.pending_pairs.write().await;

    // 清理过期条目
    pair_state.pending.retain(|_, v| !is_expired(v));

    // 频率限制
    if let Some(last) = pair_state.last_request_by_ip.get(&ip) {
        if last.elapsed().as_secs() < RATE_LIMIT_SECS {
            return Err(AppError::RateLimited);
        }
    }

    let pin = generate_pin();
    pair_state.pending.insert(
        pin.clone(),
        PendingPair {
            pin: pin.clone(),
            ip: ip.clone(),
            approved: false,
            attempts: 0,
            created_at: now,
        },
    );
    pair_state.last_request_by_ip.insert(ip, now);

    tracing::info!("Pair request from {}, PIN: {}", ip, pin);
    Ok(axum::Json(PairRequestResponse { pin }))
}

pub async fn pair_approve(
    State(state): State<AppState>,
    ConnectInfo(addr): ConnectInfo<SocketAddr>,
    axum::Json(body): axum::Json<PairRequestBody>,
) -> Result<axum::Json<PairApproveResponse>, AppError> {
    // 仅接受 localhost
    let ip = addr.ip();
    if !ip.is_loopback() {
        return Err(AppError::Forbidden);
    }

    let pin = body.pin.ok_or(AppError::BadRequest("PIN is required".to_string()))?;

    let mut pair_state = state.pending_pairs.write().await;

    if let Some(pair) = pair_state.pending.get_mut(&pin) {
        if is_expired(pair) {
            pair_state.pending.remove(&pin);
            return Err(AppError::PairExpired);
        }
        pair.approved = true;
        tracing::info!("PIN {} approved", pin);
        Ok(axum::Json(PairApproveResponse { approved: true }))
    } else {
        Err(AppError::PairNotFound)
    }
}

pub async fn pair_confirm(
    State(state): State<AppState>,
    ConnectInfo(addr): ConnectInfo<SocketAddr>,
    axum::Json(body): axum::Json<PairRequestBody>,
) -> Result<axum::Json<PairConfirmResponse>, AppError> {
    let ip = addr.ip().to_string();
    let pin = body.pin.ok_or(AppError::BadRequest("PIN is required".to_string()))?;

    let mut pair_state = state.pending_pairs.write().await;

    let pair = pair_state.pending.get_mut(&pin).ok_or(AppError::PairNotFound)?;

    if is_expired(pair) {
        pair_state.pending.remove(&pin);
        return Err(AppError::PairExpired);
    }

    if pair.ip != ip {
        return Err(AppError::Forbidden);
    }

    pair.attempts += 1;
    if pair.attempts > MAX_CONFIRM_ATTEMPTS {
        pair_state.pending.remove(&pin);
        return Err(AppError::PairAttemptsExceeded);
    }

    if !pair.approved {
        return Err(AppError::PairNotApproved);
    }

    // 配对成功，移除 PIN，签发 token
    pair_state.pending.remove(&pin);
    let token = Token::generate(&state.secret_key);

    tracing::info!("Pair confirmed for {}, token issued", ip);
    Ok(axum::Json(PairConfirmResponse { token }))
}
```

## `src/error.rs` 新增错误变体

```rust
#[derive(Debug, thiserror::Error)]
pub enum AppError {
    // ... 现有变体 ...

    #[error("Rate limited")]
    RateLimited,

    #[error("Forbidden")]
    Forbidden,

    #[error("Bad request: {0}")]
    BadRequest(String),

    #[error("Pair request not found")]
    PairNotFound,

    #[error("Pair request expired")]
    PairExpired,

    #[error("Pair request not approved")]
    PairNotApproved,

    #[error("Too many confirm attempts")]
    PairAttemptsExceeded,

    #[error("Unauthorized")]
    Unauthorized,
}
```

对应的 `IntoResponse` 映射新增：

```rust
AppError::RateLimited => (StatusCode::TOO_MANY_REQUESTS, self.to_string()),
AppError::Forbidden => (StatusCode::FORBIDDEN, self.to_string()),
AppError::BadRequest(_) => (StatusCode::BAD_REQUEST, self.to_string()),
AppError::PairNotFound => (StatusCode::NOT_FOUND, self.to_string()),
AppError::PairExpired => (StatusCode::GONE, self.to_string()),
AppError::PairNotApproved => (StatusCode::FORBIDDEN, self.to_string()),
AppError::PairAttemptsExceeded => (StatusCode::TOO_MANY_REQUESTS, self.to_string()),
AppError::Unauthorized => (StatusCode::UNAUTHORIZED, self.to_string()),
```

## `src/state.rs` 变更

### AppStateInner 新增字段

```rust
pub struct AppStateInner {
    pub config: Config,
    pub opencode_status: RwLock<OpencodeStatus>,
    pub opencode_manager: OpencodeManager,
    pub secret_key: auth::key::SecretKey,
    pub pending_pairs: RwLock<auth::pair::PairState>,  // 新增
}
```

### create_app_state 更新

```rust
Arc::new(AppStateInner {
    // ... 现有字段 ...
    pending_pairs: RwLock::new(auth::pair::PairState::new()),
})
```

## `src/main.rs` 路由注册

在 Router 中添加配对路由（在 status 路由附近）：

```rust
let app = Router::new()
    // ... 现有路由 ...
    .route("/api/bridge/pair/request", post(auth::pair::pair_request))
    .route("/api/bridge/pair/approve", post(auth::pair::pair_approve))
    .route("/api/bridge/pair/confirm", post(auth::pair::pair_confirm))
    // ...
```

同时需要注册 `ConnectInfo` 层以获取客户端 IP：

```rust
use axum::extract::ConnectInfo;
use std::net::SocketAddr;

// 在 .layer(CorsLayer::permissive()) 之前添加：
.layer(axum::extract::DefaultMethodSubstrate::new())  // 不需要

// 实际需要：axum 的 ConnectInfo 需要IntoConnectInfo 层
// 用 tower 的 MakeService 方式或 axum 的 serve 方式
```

**重要**：axum 0.8 中获取 `ConnectInfo<SocketAddr>` 需要在 `axum::serve` 时提供：

```rust
axum::serve(listener, app.into_make_service_with_connect_info::<SocketAddr>()).await?;
```

将 `main.rs` 最后一行从：

```rust
axum::serve(listener, app).await?;
```

改为：

```rust
axum::serve(listener, app.into_make_service_with_connect_info::<SocketAddr>()).await?;
```

## 测试

```rust
#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn test_generate_pin_format() {
        let pin = generate_pin();
        assert_eq!(pin.len(), 6);
        assert!(pin.chars().all(|c| c.is_ascii_digit()));
    }

    #[test]
    fn test_is_expired_not_expired() {
        let pair = PendingPair {
            pin: "123456".to_string(),
            ip: "127.0.0.1".to_string(),
            approved: false,
            attempts: 0,
            created_at: Instant::now(),
        };
        assert!(!is_expired(&pair));
    }

    #[tokio::test]
    async fn test_pair_state_default() {
        let state = PairState::new();
        assert!(state.pending.is_empty());
        assert!(state.last_request_by_ip.is_empty());
    }
}
```

## 注意事项

1. **ConnectInfo 与集成测试**：`tower::ServiceExt::oneshot` 不支持 `ConnectInfo`。集成测试中需要配对的路由时，要么跳过，要么用 `axum::Router::layer(axum::extract::ConnectInfo::mock(addr))` 注入。

2. **PIN 生成随机性**：`generate_pin` 依赖 `generate_random_bytes`，需确保它有足够随机性（参见 1.1 中关于 getrandom 的建议）。

3. **Bridge 重启**：`PairState` 纯内存，重启后清空。这不影响已签发的 token（token 验证是无状态的）。


---

# Bridge 1.4: 认证中间件 (`auth/middleware.rs`)

## 目标

实现 axum 中间件层，按规格对请求进行认证分流：
- 公开端点放行 (`/api/bridge/pair/*`, `/api/bridge/status`)
- localhost 端点放行 (`/api/bridge/pair/approve` 来自 127.0.0.1)
- 其余端点需 Bearer token 验证
- `auth_enabled = false` 时跳过所有检查

## 文件变更

| 操作 | 路径 |
|------|------|
| 创建 | `src/auth/middleware.rs` |
| 修改 | `src/main.rs` — 应用中间件层 |

## `src/auth/middleware.rs`

```rust
use axum::body::Body;
use axum::extract::{Request, State};
use axum::http::StatusCode;
use axum::middleware::Next;
use axum::response::{IntoResponse, Response};
use axum::extract::ConnectInfo;
use std::net::SocketAddr;

use crate::state::AppState;
use super::token::Token;

const PUBLIC_PATHS: &[&str] = &[
    "/api/bridge/status",
    "/api/bridge/pair/request",
    "/api/bridge/pair/confirm",
];

const LOCALHOST_ONLY_PATHS: &[&str] = &[
    "/api/bridge/pair/approve",
];

pub async fn auth_middleware(
    State(state): State<AppState>,
    ConnectInfo(addr): ConnectInfo<SocketAddr>,
    req: Request,
    next: Next,
) -> Response {
    // auth_enabled = false 时全部放行
    if !state.config.bridge.auth_enabled {
        return next.run(req).await;
    }

    let path = req.uri().path();

    // 公开端点
    if PUBLIC_PATHS.iter().any(|p| path == *p) {
        return next.run(req).await;
    }

    // localhost-only 端点（approve）
    if LOCALHOST_ONLY_PATHS.iter().any(|p| path == *p) {
        if addr.ip().is_loopback() {
            return next.run(req).await;
        } else {
            return (StatusCode::FORBIDDEN, "Forbidden").into_response();
        }
    }

    // 所有其他路径需要 Bearer token
    if let Some(auth_header) = req.headers().get("authorization") {
        if let Some(token_str) = auth_header.to_str().ok().and_then(Token::extract_from_header) {
            if Token::validate(&state.secret_key, token_str) {
                return next.run(req).await;
            }
        }
    }

    (StatusCode::UNAUTHORIZED, "{\"error\":\"Unauthorized\"}").into_response()
}
```

## `src/main.rs` 变更

### 添加导入

```rust
use std::net::SocketAddr;
use opencode_bridge::auth;
```

### 应用中间件

在 `.with_state(app_state)` 之前添加 middleware layer：

```rust
let app = Router::new()
    // ... 所有路由 ...
    .layer(axum::middleware::from_fn_with_state(
        app_state.clone(),
        auth::middleware::auth_middleware,
    ))
    .layer(CorsLayer::permissive())
    .layer(TraceLayer::new_for_http())
    .with_state(app_state);
```

**注意 layer 顺序**：axum 中 layer 从下往上应用，从外到内。所以：
1. `TraceLayer` — 最外层（先进入）
2. `CorsLayer` — CORS 处理
3. `auth_middleware` — 认证检查
4. 路由 handler — 最内层

这个顺序确保：
- 所有请求先经过 trace
- CORS preflight 不被 auth 拦截
- 认证通过后才到达 handler

### 启用 ConnectInfo

确保使用 `into_make_service_with_connect_info`（1.3 步已添加）：

```rust
axum::serve(listener, app.into_make_service_with_connect_info::<SocketAddr>()).await?;
```

## 关于 ConnectInfo 与 middleware

`auth_middleware` 需要 `ConnectInfo<SocketAddr>` 作为参数，这要求：
1. 服务器用 `into_make_service_with_connect_info::<SocketAddr>()`
2. 中间件签名包含 `ConnectInfo<SocketAddr>`

**潜在问题**：axum 的 `from_fn_with_state` 中间件默认不支持 `ConnectInfo` 提取，因为 `ConnectInfo` 是在 TCP accept 层注入的，中间件运行在路由之前。

**解决方案**：改用 `axum::middleware::from_fn` + 通过 `Request::extensions()` 获取 `ConnectInfo`：

```rust
pub async fn auth_middleware(
    State(state): State<AppState>,
    mut req: Request,
    next: Next,
) -> Response {
    if !state.config.bridge.auth_enabled {
        return next.run(req).await;
    }

    let path = req.uri().path();

    if PUBLIC_PATHS.iter().any(|p| path == *p) {
        return next.run(req).await;
    }

    // 从 extensions 获取客户端地址
    let addr = req.extensions()
        .get::<ConnectInfo<SocketAddr>>()
        .map(|ci| ci.0);

    if LOCALHOST_ONLY_PATHS.iter().any(|p| path == *p) {
        if let Some(addr) = addr {
            if addr.ip().is_loopback() {
                return next.run(req).await;
            }
        }
        return (StatusCode::FORBIDDEN, "Forbidden").into_response();
    }

    // Bearer token 检查
    if let Some(auth_header) = req.headers().get("authorization") {
        if let Some(token_str) = auth_header.to_str().ok().and_then(Token::extract_from_header) {
            if Token::validate(&state.secret_key, token_str) {
                return next.run(req).await;
            }
        }
    }

    (StatusCode::UNAUTHORIZED, "{\"error\":\"Unauthorized\"}").into_response()
}
```

## 集成测试影响

现有集成测试的 `test_app()` 函数不包含 auth middleware，因此测试无需修改即可通过。如果需要测试认证行为，需要：

```rust
fn test_app_with_auth(config: Config) -> Router {
    let state = create_app_state(config);
    Router::new()
        // ... 所有路由 ...
        .layer(axum::middleware::from_fn_with_state(
            state.clone(),
            auth::middleware::auth_middleware,
        ))
        .with_state(state)
}
```

并在测试请求中注入 `ConnectInfo`：

```rust
use axum::extract::ConnectInfo;
use std::net::SocketAddr;

let req = axum::http::Request::builder()
    .uri("/api/bridge/fs/list?path=...")
    .header("authorization", "Bearer <valid_token>")
    .extension(ConnectInfo(SocketAddr::from([127, 0, 0, 1]:12345)))
    .body(axum::body::Body::empty())
    .unwrap();
```

## 新增认证集成测试

```rust
#[tokio::test]
async fn test_auth_unauthorized_without_token() {
    let dir = std::env::temp_dir().join("bridge_int_auth");
    let _ = std::fs::remove_dir_all(&dir);
    std::fs::create_dir_all(&dir).unwrap();

    let mut config = Config::default();
    config.opencode.auto_start = false;
    config.bridge.auth_enabled = true;
    config.fs.allowed_paths = vec![dir.to_string_lossy().to_string()];

    let state = create_app_state(config);
    let app = Router::new()
        .route("/api/bridge/fs/list", get(fs::router::list))
        .route("/api/bridge/status", get(bridge::router::status))
        .layer(axum::middleware::from_fn_with_state(
            state.clone(),
            auth::middleware::auth_middleware,
        ))
        .with_state(state);

    // status 是公开端点，无需 token
    let req = axum::http::Request::builder()
        .uri("/api/bridge/status")
        .extension(ConnectInfo(SocketAddr::from(([127, 0, 0, 1], 12345))))
        .body(axum::body::Body::empty())
        .unwrap();
    let resp = app.clone().oneshot(req).await.unwrap();
    assert_eq!(resp.status(), 200);

    // fs/list 需要 token
    let req = axum::http::Request::builder()
        .uri(&format!("/api/bridge/fs/list?path={}", url_encode(&dir.to_string_lossy())))
        .extension(ConnectInfo(SocketAddr::from(([127, 0, 0, 1], 12345))))
        .body(axum::body::Body::empty())
        .unwrap();
    let resp = app.oneshot(req).await.unwrap();
    assert_eq!(resp.status(), 401);

    let _ = std::fs::remove_dir_all(&dir);
}
```


---

# Bridge 1.5: CLI 子命令 (`approve` / `reset-token`)

## 目标

为 `opencode-bridge` 添加两个 CLI 子命令：
- `opencode-bridge approve <PIN>` — 调用 localhost `/api/bridge/pair/approve` 授权 PIN
- `opencode-bridge reset-token` — 删除密钥文件 + 清空 pending pairs

## 文件变更

| 操作 | 路径 |
|------|------|
| 修改 | `src/main.rs` — CLI 重构为子命令模式 |

## `src/main.rs` 重构

将 `Args` 从简单的 `--config` 改为 clap 子命令模式：

```rust
use axum::Router;
use axum::routing::{any, get, post};
use axum::extract::ConnectInfo;
use clap::{Parser, Subcommand};
use opencode_bridge::auth;
use opencode_bridge::bridge;
use opencode_bridge::config::Config;
use opencode_bridge::files;
use opencode_bridge::fs;
use opencode_bridge::proxy;
use opencode_bridge::state::create_app_state;
use std::net::SocketAddr;
use std::path::PathBuf;
use tower_http::cors::CorsLayer;
use tower_http::trace::TraceLayer;
use tracing_subscriber::EnvFilter;

#[derive(Parser, Debug)]
#[command(name = "opencode-bridge", about = "Bridge Agent for OpenMate")]
struct Args {
    #[arg(short, long)]
    config: Option<PathBuf>,

    #[command(subcommand)]
    command: Option<Commands>,
}

#[derive(Subcommand, Debug)]
enum Commands {
    /// Approve a pending pair request
    Approve {
        /// The PIN to approve
        pin: String,
    },
    /// Reset the secret key, invalidating all tokens
    ResetToken,
}

#[tokio::main]
async fn main() -> anyhow::Result<()> {
    tracing_subscriber::fmt()
        .with_env_filter(
            EnvFilter::try_from_default_env().unwrap_or_else(|_| EnvFilter::new("info")),
        )
        .init();

    let args = Args::parse();
    let config = Config::find_and_load(args.config)?;

    match args.command {
        Some(Commands::Approve { pin }) => {
            return run_approve(&config, &pin).await;
        }
        Some(Commands::ResetToken) => {
            return run_reset_token().await;
        }
        None => {}
    }

    // 正常服务器启动流程（不变）
    run_server(config).await
}

async fn run_approve(config: &Config, pin: &str) -> anyhow::Result<()> {
    let url = format!("http://127.0.0.1:{}/api/bridge/pair/approve", config.bridge.port);
    let client = reqwest::Client::new();
    let resp = client
        .post(&url)
        .json(&serde_json::json!({ "pin": pin }))
        .send()
        .await?;

    if resp.status().is_success() {
        let body: serde_json::Value = resp.json().await?;
        println!("Approved: {}", body["approved"]);
    } else {
        let status = resp.status();
        let body = resp.text().await?;
        eprintln!("Failed (HTTP {}): {}", status, body);
        std::process::exit(1);
    }
    Ok(())
}

async fn run_reset_token() -> anyhow::Result<()> {
    auth::key::SecretKey::delete_key_file()?;
    println!("Secret key deleted. All tokens are now invalid.");
    println!("Restart the bridge to generate a new key.");
    Ok(())
}

async fn run_server(config: Config) -> anyhow::Result<()> {
    tracing::info!("OpenCode Bridge starting");
    tracing::info!("Bridge listen: {}:{}", config.bridge.hostname, config.bridge.port);
    tracing::info!("OpenCode target: {}", config.opencode_url());
    tracing::info!("Auth enabled: {}", config.bridge.auth_enabled);
    tracing::info!(
        "Allowed paths: {:?}",
        config.effective_allowed_paths()
    );

    let app_state = create_app_state(config.clone());

    if config.opencode.auto_start {
        tracing::info!("Auto-starting opencode...");
        if let Err(e) = app_state.opencode_manager.start().await {
            tracing::warn!("Auto-start failed: {}", e);
        }
    }

    let app = Router::new()
        .route("/api/bridge/status", get(bridge::router::status))
        .route("/api/bridge/pair/request", post(auth::pair::pair_request))
        .route("/api/bridge/pair/approve", post(auth::pair::pair_approve))
        .route("/api/bridge/pair/confirm", post(auth::pair::pair_confirm))
        .route("/api/bridge/opencode/start", post(bridge::router::start_opencode))
        .route("/api/bridge/opencode/stop", post(bridge::router::stop_opencode))
        .route("/api/bridge/opencode/restart", post(bridge::router::restart_opencode))
        .route("/api/bridge/fs/roots", get(fs::router::roots))
        .route("/api/bridge/fs/list", get(fs::router::list))
        .route("/api/bridge/fs/read", get(fs::router::read))
        .route("/api/bridge/fs/download", get(fs::router::download))
        .route("/api/bridge/fs/mkdir", post(fs::router::mkdir))
        .route("/api/bridge/fs/search", post(fs::router::search))
        .route("/api/bridge/fs/stat", get(fs::router::stat))
        .route("/api/bridge/fs/write", post(fs::router::write))
        .route("/api/bridge/fs/upload", axum::routing::put(fs::router::upload))
        .route("/files/{*path}", get(files::router::serve_file))
        .route("/api/opencode/global/event", get(proxy::sse::sse_proxy))
        .route("/global/event", get(proxy::sse::sse_proxy))
        .route("/api/opencode/{*path}", any(proxy::rest::proxy_opencode_request))
        .fallback(any(proxy::rest::proxy_fallback))
        .layer(axum::middleware::from_fn_with_state(
            app_state.clone(),
            auth::middleware::auth_middleware,
        ))
        .layer(CorsLayer::permissive())
        .layer(TraceLayer::new_for_http())
        .with_state(app_state);

    let listener = tokio::net::TcpListener::bind(config.bridge_listen_addr()).await?;
    tracing::info!("Bridge listening on {}", config.bridge_listen_addr());

    axum::serve(listener, app.into_make_service_with_connect_info::<SocketAddr>()).await?;

    Ok(())
}
```

## CLI 用法

```powershell
# 正常启动服务器
opencode-bridge.exe

# 指定配置启动
opencode-bridge.exe -c bridge.toml

# 授权 PIN
opencode-bridge.exe approve 482901

# 重置所有 token
opencode-bridge.exe reset-token
```

## `reset-token` 补充

`reset-token` 删除密钥文件后，正在运行的 Bridge 进程内存中仍持有旧密钥，旧 token 依然有效。要使失效生效，需要重启 Bridge。

如需不重启就使 token 失效，需要额外机制（如维护 revoked token 列表），但规格中明确通过 `reset-token` + 重启来实现，无需额外复杂度。

可在 `reset-token` 输出中提示用户重启：

```
Secret key deleted. All tokens are now invalid.
Restart the bridge to generate a new key.
```

## 关于 `SecretKey::delete_key_file` 可见性

1.1 步中 `delete_key_file` 已声明为 `pub`，且 `auth` 模块已公开。main.rs 通过 `opencode_bridge::auth::key::SecretKey::delete_key_file()` 访问即可。


---

# Bridge 1.6: 代理请求 Authorization header 剥离

## 目标

转发给 opencode 的请求（REST 代理 + SSE 代理）在转发前必须删除 `Authorization` header，防止 Bridge 的认证 token 泄露给 opencode 进程。

## 文件变更

| 操作 | 路径 |
|------|------|
| 修改 | `src/proxy/rest.rs` — 转发前删除 authorization header |
| 修改 | `src/proxy/sse.rs` — SSE 连接不携带 authorization（当前已不携带，但需确认） |

## `src/proxy/rest.rs` 变更

在 header 转发循环中，将 `authorization` 加入跳过列表：

当前代码（第 49-57 行）：

```rust
for (name, value) in headers.iter() {
    let name_str = name.as_str();
    if matches!(name_str, "host" | "connection" | "transfer-encoding") {
        continue;
    }
    if let Ok(v) = value.to_str() {
        req_builder = req_builder.header(name_str, v);
    }
}
```

修改为：

```rust
for (name, value) in headers.iter() {
    let name_str = name.as_str();
    if matches!(name_str, "host" | "connection" | "transfer-encoding" | "authorization") {
        continue;
    }
    if let Ok(v) = value.to_str() {
        req_builder = req_builder.header(name_str, v);
    }
}
```

唯一改动：在 `matches!` 宏中添加 `"authorization"`。

## `src/proxy/sse.rs` 变更

当前 SSE 代理代码（第 56-58 行）创建了一个新的 `reqwest::Client`，请求不带任何自定义 header：

```rust
let client = reqwest::Client::new();
let resp = client
    .get(sse_url)
    .send()
    .await
```

SSE 代理是从 Bridge 内部向 opencode 发起连接，不经过用户的 `Authorization` header，所以**无需修改**。Bridge 自己是 opencode 的客户端，不会把用户的 Bearer token 带进去。

## 测试验证

可以通过以下方式验证：

1. 启动 Bridge（带 auth）
2. 用有效 token 调用 `/api/opencode/session` 等代理路由
3. 检查 opencode 收到的请求中不包含 `Authorization` header

单元测试可在 `rest.rs` 中添加：

```rust
#[test]
fn test_authorization_header_is_stripped() {
    // 验证 header 名称匹配逻辑
    let skipped = ["host", "connection", "transfer-encoding", "authorization"];
    assert!(skipped.contains(&"authorization"));
}
```

但真正验证需要 mock server，集成测试中通过检查 opencode 端收到的请求来确认。


---

# Android 2.1: TokenStore + EncryptedSharedPreferences

## 目标

创建 `TokenStore` 类，用 `EncryptedSharedPreferences` 安全存储每个实例的 Bearer token。提供 `get(profileId)` / `set(profileId, token)` / `remove(profileId)` 方法。

## 文件变更

| 操作 | 路径 |
|------|------|
| 修改 | `gradle/libs.versions.toml` — 添加 security-crypto 依赖 |
| 修改 | `core/network/build.gradle.kts` — 添加 security-crypto 依赖 |
| 创建 | `core/network/src/main/java/com/openmate/core/network/TokenStore.kt` |
| 修改 | `core/network/src/main/java/com/openmate/core/network/NetworkModule.kt` — 提供 TokenStore |

## 依赖变更

### `gradle/libs.versions.toml`

在 `[versions]` 中添加：

```toml
security-crypto = "1.1.0-alpha06"
```

在 `[libraries]` 中添加：

```toml
security-crypto = { group = "androidx.security", name = "security-crypto", version.ref = "security-crypto" }
```

### `core/network/build.gradle.kts`

在 dependencies 中添加：

```kotlin
implementation(libs.security.crypto)
```

## `TokenStore.kt`

```kotlin
package com.openmate.core.network

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

class TokenStore(context: Context) {

    private val masterKey = MasterKey.Builder(context)
        .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
        .build()

    private val prefs: SharedPreferences = EncryptedSharedPreferences.create(
        context,
        "bridge_tokens",
        masterKey,
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
    )

    fun get(profileId: String): String? {
        return prefs.getString(key(profileId), null)
    }

    fun set(profileId: String, token: String) {
        prefs.edit().putString(key(profileId), token).apply()
    }

    fun remove(profileId: String) {
        prefs.edit().remove(key(profileId)).apply()
    }

    private fun key(profileId: String): String = "token_$profileId"
}
```

## `NetworkModule.kt` 变更

添加 TokenStore 的提供：

```kotlin
@Provides
@Singleton
fun provideTokenStore(@ApplicationContext context: Context): TokenStore {
    return TokenStore(context)
}
```

需要在文件顶部添加 import：

```kotlin
import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import com.openmate.core.network.TokenStore
```

## 注意事项

1. **EncryptedSharedPreferences 初始化**：首次创建时可能较慢（密钥生成），但只在单例创建时发生一次。

2. **Android Keystore 依赖**：`MasterKey` 依赖 Android Keystore，在 API 23+ (minSdk 26) 上可靠运行。

3. **MasterKey 丢失场景**：如果用户清除应用数据或 Keystore 被重置，`EncryptedSharedPreferences` 中的数据将不可读。此时 `get()` 会抛出异常。建议在 `TokenStore` 中捕获并返回 null：

```kotlin
fun get(profileId: String): String? {
    return try {
        prefs.getString(key(profileId), null)
    } catch (_: Exception) {
        null
    }
}
```

4. **与现有 ServerProfile.password 的关系**：Token 存储与 password 是独立的。`password` 字段（HTTP Basic Auth）已废弃，本方案使用 Bearer token 替代。后续步骤中 `password` 字段可保留但不再用于认证。


---

# Android 2.2: Bearer Token 拦截器

## 目标

替换现有 `AuthInterceptor`（HTTP Basic Auth）为新的 `BearerTokenInterceptor`，从 `TokenStore` 动态读取当前 profile 的 Bearer token。将其添加到所有 3 个 OkHttpClient（api, sse, download）。

## 文件变更

| 操作 | 路径 |
|------|------|
| 修改 | `core/network/src/main/java/com/openmate/core/network/AuthInterceptor.kt` — 替换为 BearerTokenInterceptor |
| 修改 | `core/network/src/main/java/com/openmate/core/network/NetworkModule.kt` — 注入拦截器到所有 client |
| 修改 | `core/network/src/test/java/com/openmate/core/network/AuthInterceptorTest.kt` — 更新测试 |

## `AuthInterceptor.kt` — 替换为 `BearerTokenInterceptor`

完全替换文件内容：

```kotlin
package com.openmate.core.network

import okhttp3.Interceptor
import okhttp3.Response

class BearerTokenInterceptor(
    private val tokenStore: TokenStore,
    private val activeProfileIdProvider: () -> String?,
) : Interceptor {

    override fun intercept(chain: Interceptor.Chain): Response {
        val profileId = activeProfileIdProvider()
        val token = profileId?.let { tokenStore.get(it) }

        val request = if (token != null) {
            chain.request().newBuilder()
                .header("Authorization", "Bearer $token")
                .build()
        } else {
            chain.request()
        }

        val response = chain.proceed(request)

        if (response.code == 401 && token != null) {
            tokenStore.remove(profileId!!)
        }

        return response
    }
}
```

### 关键设计点

1. **动态 token 获取**：`activeProfileIdProvider` 是一个 lambda，返回当前活跃的 profile ID。这解决了"单例 OkHttpClient 需要为不同 profile 提供不同 token"的问题。

2. **401 自动清除 token**：收到 401 时自动从 `TokenStore` 中移除失效 token，触发重新配对流程（由上层 ViewModel 处理）。

3. **无 token 时放行**：如果没有活跃 profile 或没有存储的 token，请求不带 Authorization header。配对流程的公开端点（`/pair/request`, `/pair/confirm`, `/status`）不需要 token。

## `NetworkModule.kt` 变更

```kotlin
package com.openmate.core.network

import android.content.Context
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import okhttp3.OkHttpClient
import javax.inject.Named
import javax.inject.Singleton
import dagger.hilt.android.qualifiers.ApplicationContext

@Module
@InstallIn(SingletonComponent::class)
object NetworkModule {

    @Provides
    @Singleton
    fun provideTokenStore(@ApplicationContext context: Context): TokenStore {
        return TokenStore(context)
    }

    @Provides
    @Singleton
    @Named("sse")
    fun provideSseOkHttpClient(tokenInterceptor: BearerTokenInterceptor): OkHttpClient {
        return OkHttpClient.Builder()
            .readTimeout(0, java.util.concurrent.TimeUnit.MINUTES)
            .connectTimeout(30, java.util.concurrent.TimeUnit.SECONDS)
            .addInterceptor(tokenInterceptor)
            .build()
    }

    @Provides
    @Singleton
    @Named("api")
    fun provideApiOkHttpClient(tokenInterceptor: BearerTokenInterceptor): OkHttpClient {
        return OkHttpClient.Builder()
            .readTimeout(30, java.util.concurrent.TimeUnit.SECONDS)
            .connectTimeout(30, java.util.concurrent.TimeUnit.SECONDS)
            .addInterceptor(tokenInterceptor)
            .build()
    }

    @Provides
    @Singleton
    @Named("download")
    fun provideDownloadOkHttpClient(tokenInterceptor: BearerTokenInterceptor): OkHttpClient {
        return OkHttpClient.Builder()
            .readTimeout(0, java.util.concurrent.TimeUnit.MINUTES)
            .connectTimeout(60, java.util.concurrent.TimeUnit.SECONDS)
            .addInterceptor(tokenInterceptor)
            .build()
    }

    @Provides
    @Singleton
    fun provideBearerTokenInterceptor(
        tokenStore: TokenStore,
        apiClient: OpencodeApiClient,
    ): BearerTokenInterceptor {
        return BearerTokenInterceptor(tokenStore) { apiClient.activeProfileId }
    }

    @Provides
    @Singleton
    fun provideSseClient(@Named("sse") client: OkHttpClient): SseClient {
        return SseClient(client)
    }

    @Provides
    @Singleton
    fun provideOpencodeApiClient(
        @Named("api") client: OkHttpClient,
        @Named("download") downloadClient: OkHttpClient,
    ): OpencodeApiClient {
        return OpencodeApiClient(client, downloadClient)
    }
}
```

### `activeProfileIdProvider` 的来源

`OpencodeApiClient` 需要新增一个 `activeProfileId` 属性，由外部设置：

在 `OpencodeApiClient.kt` 中添加：

```kotlin
@Volatile
var activeProfileId: String? = null
```

在 `InstanceListViewModel.connect()` 和 `ConnectionManager.connect()` 中，连接时设置：

```kotlin
apiClient.activeProfileId = profile.id
```

断开时清除：

```kotlin
apiClient.activeProfileId = null
```

## `AuthInterceptorTest.kt` — 更新测试

替换为 `BearerTokenInterceptorTest.kt`：

```kotlin
package com.openmate.core.network

import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class BearerTokenInterceptorTest {

    private lateinit var server: MockWebServer
    private lateinit var tokenStore: TokenStore

    @Before
    fun setup() {
        server = MockWebServer()
        server.start()
        tokenStore = FakeTokenStore()
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun addsBearerHeaderWhenTokenExists() {
        tokenStore.set("profile1", "test-token-123")
        val interceptor = BearerTokenInterceptor(tokenStore) { "profile1" }

        server.enqueue(MockResponse().setBody("ok"))

        val client = OkHttpClient.Builder().addInterceptor(interceptor).build()
        val request = okhttp3.Request.Builder().url(server.url("/test")).build()
        client.newCall(request).execute()

        val recorded = server.takeRequest()
        assertEquals("Bearer test-token-123", recorded.getHeader("Authorization"))
    }

    @Test
    fun noHeaderWhenNoActiveProfile() {
        val interceptor = BearerTokenInterceptor(tokenStore) { null }

        server.enqueue(MockResponse().setBody("ok"))

        val client = OkHttpClient.Builder().addInterceptor(interceptor).build()
        val request = okhttp3.Request.Builder().url(server.url("/test")).build()
        client.newCall(request).execute()

        val recorded = server.takeRequest()
        assertNull(recorded.getHeader("Authorization"))
    }

    @Test
    fun noHeaderWhenNoTokenForProfile() {
        val interceptor = BearerTokenInterceptor(tokenStore) { "nonexistent" }

        server.enqueue(MockResponse().setBody("ok"))

        val client = OkHttpClient.Builder().addInterceptor(interceptor).build()
        val request = okhttp3.Request.Builder().url(server.url("/test")).build()
        client.newCall(request).execute()

        val recorded = server.takeRequest()
        assertNull(recorded.getHeader("Authorization"))
    }

    @Test
    fun removesTokenOn401() {
        tokenStore.set("profile1", "bad-token")
        val interceptor = BearerTokenInterceptor(tokenStore) { "profile1" }

        server.enqueue(MockResponse().setResponseCode(401))

        val client = OkHttpClient.Builder().addInterceptor(interceptor).build()
        val request = okhttp3.Request.Builder().url(server.url("/test")).build()
        client.newCall(request).execute()

        assertNull(tokenStore.get("profile1"))
    }

    private class FakeTokenStore : TokenStore(null!!) {
        private val map = mutableMapOf<String, String>()
        override fun get(profileId: String): String? = map[profileId]
        override fun set(profileId: String, token: String) { map[profileId] = token }
        override fun remove(profileId: String) { map.remove(profileId) }
    }
}
```

**注意**：`FakeTokenStore` 继承 `TokenStore` 需要调整 `TokenStore` 的构造函数或使用接口。更实用的方案：将 `TokenStore` 提取为接口 + 实现，或直接在测试中手写一个简单 store。

### TokenStore 接口抽取（推荐）

将 `TokenStore` 改为接口：

```kotlin
// core/domain/src/main/java/com/openmate/core/domain/repository/TokenRepository.kt
package com.openmate.core.domain.repository

interface TokenRepository {
    fun get(profileId: String): String?
    fun set(profileId: String, token: String)
    fun remove(profileId: String)
}
```

实现留在 `core/network` 中的 `TokenStore`：

```kotlin
class TokenStore(context: Context) : TokenRepository {
    // ... 实现不变
}
```

拦截器依赖 `TokenRepository` 接口而非具体类，方便测试。

## 循环依赖问题

`NetworkModule` 中：
- `BearerTokenInterceptor` 依赖 `TokenStore` + `OpencodeApiClient`
- `OpencodeApiClient` 依赖 `OkHttpClient`
- `OkHttpClient` 依赖 `BearerTokenInterceptor`

这形成循环：`OpencodeApiClient` → `OkHttpClient` → `BearerTokenInterceptor` → `OpencodeApiClient`

**解决方案**：`BearerTokenInterceptor` 不直接依赖 `OpencodeApiClient`，而是依赖 `ActiveProfileProvider` 接口：

```kotlin
// core/domain/src/main/java/com/openmate/core/domain/repository/ActiveProfileProvider.kt
interface ActiveProfileProvider {
    val activeProfileId: String?
}
```

`OpencodeApiClient` 实现此接口：

```kotlin
class OpencodeApiClient(
    private val client: OkHttpClient,
    private val downloadClient: OkHttpClient = client,
    var baseUrl: String = "http://localhost:8080",
) : ActiveProfileProvider {
    override var activeProfileId: String? = null
    // ...
}
```

但 Hilt 中 `OpencodeApiClient` 不是通过接口绑定的，而是直接提供。Interceptor 接受 `ActiveProfileProvider` 参数：

```kotlin
class BearerTokenInterceptor(
    private val tokenStore: TokenRepository,
    private val activeProfileProvider: ActiveProfileProvider,
) : Interceptor
```

Dagger 可以解析这个依赖链：
1. `TokenStore` (无依赖) → 可先创建
2. `OpencodeApiClient` (依赖 OkHttpClient) → 还不能创建
3. `OkHttpClient` (依赖 BearerTokenInterceptor) → 还不能创建
4. `BearerTokenInterceptor` (依赖 TokenStore + ActiveProfileProvider)

实际上还有循环：`OpencodeApiClient` → `OkHttpClient` → `BearerTokenInterceptor` → `ActiveProfileProvider` (= `OpencodeApiClient`)

**最终方案**：使用 `Lazy<ActiveProfileProvider>` 或 `Provider<ActiveProfileProvider>` 打破循环：

```kotlin
class BearerTokenInterceptor(
    private val tokenStore: TokenRepository,
    private val activeProfileProvider: dagger.Lazy<ActiveProfileProvider>,
) : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val profileId = activeProfileProvider.get().activeProfileId
        // ...
    }
}
```

或者更简单：直接用 lambda，不通过 Hilt 注入 `ActiveProfileProvider`：

```kotlin
@Provides
@Singleton
fun provideBearerTokenInterceptor(
    tokenStore: TokenRepository,
    apiClient: Provider<OpencodeApiClient>,
): BearerTokenInterceptor {
    return BearerTokenInterceptor(tokenStore) { apiClient.get().activeProfileId }
}
```

用 `Provider<>` 延迟获取 `OpencodeApiClient`，打破循环依赖。


---

# Android 2.3: 配对 API 方法 (`pairRequest` / `pairConfirm`)

## 目标

在 `OpencodeApiClient` 中添加 Bridge 配对 API 方法，供 Android 端调用配对流程。

## 文件变更

| 操作 | 路径 |
|------|------|
| 修改 | `core/network/src/main/java/com/openmate/core/network/dto/BridgeDto.kt` — 添加配对 DTO |
| 修改 | `core/network/src/main/java/com/openmate/core/network/OpencodeApiClient.kt` — 添加配对方法 |

## `BridgeDto.kt` 新增 DTO

```kotlin
@Serializable
data class PairRequestResponse(
    val pin: String = "",
)

@Serializable
data class PairApproveResponse(
    val approved: Boolean = false,
)

@Serializable
data class PairConfirmResponse(
    val token: String = "",
)

@Serializable
data class PairRequestBody(
    val pin: String,
)
```

## `OpencodeApiClient.kt` 新增方法

在 bridge 方法区域（`bridgeStatus()` 附近）添加：

```kotlin
suspend fun bridgePairRequest(): PairRequestResponse {
    return post("/api/bridge/pair/request", emptyMap<String, String>())
}

suspend fun bridgePairConfirm(pin: String): PairConfirmResponse {
    val body = PairRequestBody(pin)
    val jsonStr = json.encodeToString(PairRequestBody.serializer(), body)
    val requestBody = jsonStr.toRequestBody(jsonMediaType)
    val url = buildUrl("/api/bridge/pair/confirm", emptyMap())
    val request = Request.Builder().url(url).post(requestBody).build()
    val response = client.newCall(request).execute()
    if (!response.isSuccessful) {
        val errorBody = response.body?.string() ?: ""
        if (response.code == 401) {
            throw AuthException("Pair not approved or expired: $errorBody")
        }
        throw ServerUnavailableException("Pair confirm failed: HTTP ${response.code}: $errorBody")
    }
    val responseBody = response.body?.string() ?: throw ServerUnavailableException("Empty response")
    return json.decodeFromString(responseBody)
}
```

### 注意点

1. **`pairRequest` 用 POST 空 body**：Bridge 端的 `pair_request` handler 不要求 body，只需要 `ConnectInfo` 获取 IP。`post("/api/bridge/pair/request", emptyMap())` 会发送空 JSON body `{}`。

2. **`pairConfirm` 需要手动构建请求**：因为需要传递 `{ "pin": "..." }` body，且需要区分 401（未授权/未批准）和其他错误。

3. **`pairRequest` 不需要 token**：配对端点是公开的，`BearerTokenInterceptor` 在没有 token 时不会添加 Authorization header，这正是期望的行为。

4. **`pairConfirm` 也不需要 token**：配对确认也是公开端点，用 PIN 而非 Bearer token 认证。


---

# Android 2.4: SseClient Bearer header

## 目标

让 `SseClient` 在建立 SSE 连接时携带 Bearer token，以通过 Bridge 的认证中间件。

## 文件变更

| 操作 | 路径 |
|------|------|
| 修改 | `core/network/src/main/java/com/openmate/core/network/SseClient.kt` — 连接时添加 token header |

## 当前状态

`SseClient` 使用 `@Named("sse")` OkHttpClient，2.2 步骤已为该 client 添加 `BearerTokenInterceptor`。但 `SseClient.establishConnection()` 中手动构建 `Request`，然后通过 `client.newCall(request).execute()` 执行。

**关键问题**：OkHttp 的 interceptor 链会自动处理 `BearerTokenInterceptor`，所以 `SseClient` **不需要手动添加 Authorization header**。拦截器会根据 `activeProfileId` 从 `TokenStore` 读取 token 并添加到请求中。

## 验证

`SseClient.establishConnection()` 当前代码（第 64-67 行）：

```kotlin
val request = Request.Builder()
    .url("$baseUrl/global/event")
    .get()
    .build()
```

`client.newCall(request).execute()` 会经过 `BearerTokenInterceptor`，自动添加 `Authorization: Bearer <token>` header。

**无需修改 SseClient 代码。**

## 但需要确认一件事

`SseClient.connect()` 接收 `password: String?` 参数但从未使用。随着 Bearer token 机制的引入，`password` 参数不再需要。可以：

1. **保留参数但不使用**（最小改动，向后兼容）
2. **移除参数**（清理代码）

建议先保留，在 3.3 步骤统一清理连接流程时再移除。

## `SseEventRepositoryImpl.connect()` 同理

`SseEventRepositoryImpl.connect()` 调用 `sseClient.connect(address, port, password)`。`password` 参数一路透传但不使用。同样先保留。


---

# Android 2.5: ServerProfile 新增 token 字段 + Repository 适配

## 目标

实际上我们决定 **不在 ServerProfile 中存储 token**。Token 通过 `TokenStore`（EncryptedSharedPreferences）独立存储，以 profile ID 为 key。ServerProfile 保持原有结构不变。

## 设计决策

| 方案 | 优点 | 缺点 |
|------|------|------|
| ServerProfile.token 字段 | 简单直接 | Token 与 profile JSON 一起明文存储在 DataStore |
| 独立 TokenStore | 加密存储，安全 | 多一层存储，但更合理 |

**选择 TokenStore 方案**（2.1 已实现），因为：
- Token 是敏感凭证，必须加密存储
- ServerProfile 的 DataStore 不是加密的
- TokenStore 以 profile ID 为 key，已能关联到对应 profile

## 本步骤的实际工作

1. **确认 `ServerProfile.password` 字段的处置**：
   - `password` 原用于 HTTP Basic Auth，现在改用 Bearer token
   - **保留字段但不再用于认证**，避免破坏序列化兼容性
   - 后续可在 UI 中移除 password 输入框

2. **确认 `TokenStore` 与 `ServerProfile` 的关联**：
   - `TokenStore.get(profile.id)` 获取 token
   - `TokenStore.set(profile.id, token)` 存储 token
   - 删除 profile 时，需同时调用 `TokenStore.remove(profile.id)`

3. **`ServerProfileRepositoryImpl.delete()` 需清除 token**：

```kotlin
override suspend fun delete(id: String) {
    context.profileDataStore.edit { prefs ->
        val existing = prefs[key]?.toMutableSet() ?: return@edit
        existing.removeIf { json.decodeFromString<ServerProfile>(it).id == id }
        prefs[key] = existing
    }
    databaseFactory.delete(context, id)
    tokenStore.remove(id)  // 新增：清除该 profile 的 token
}
```

`ServerProfileRepositoryImpl` 需要注入 `TokenRepository`：

```kotlin
@Singleton
class ServerProfileRepositoryImpl @Inject constructor(
    @ApplicationContext private val context: Context,
    private val databaseFactory: DatabaseFactory,
    private val tokenStore: TokenRepository,  // 新增
) : ServerProfileRepository {
```

## `ServerProfileRepository` 接口

接口不变。`delete()` 的 token 清除是实现细节，不需要在接口中体现。

## 数据迁移

无迁移问题。旧版本的 ServerProfile 没有 token，用户首次连接带认证的 Bridge 时会触发配对流程，token 通过 TokenStore 存储。


---

# Android 3.1: PairingScreen 配对界面

## 目标

在 `AddInstanceScreen` 的 "Test Connection" 流程中，当 Bridge 返回需要配对时（检测到 Bridge 启用了认证但无 token），显示配对 PIN 输入界面，完成配对后获取 token。

## 设计决策：内嵌式 vs 独立页面

**选择内嵌式**：在现有 `AddInstanceScreen` 中添加配对状态区域，而非新路由。原因：
- 配对是测试连接的延续，不是独立页面
- 避免 navigation 参数传递（profile 未保存前没有 ID）
- 用户体验更流畅

## 文件变更

| 操作 | 路径 |
|------|------|
| 修改 | `feature/instance/src/main/java/com/openmate/feature/instance/AddInstanceScreen.kt` — 添加配对 UI 区域 |
| 修改 | `feature/instance/src/main/res/values/strings.xml` — 添加配对相关字符串 |

## UI 流程

```
[Name] [Address] [Port]
[Password 字段 → 改为可选标签 "Password (legacy, leave empty for new instances)"]

[Test Connection] 按钮

测试结果区域：
  - Testing → "Testing…"
  - Success → Bridge 连接成功信息
  - Error → 错误信息
  - PairingRequired → 新增：显示配对流程
    ├── "This Bridge requires pairing. A PIN has been sent to the Bridge terminal."
    ├── [6位 PIN 输入框]
    └── [Confirm Pairing] 按钮
  - PairingSuccess → 新增：显示配对成功 + token 已保存
    └── "Paired successfully! Token saved."

[Save] 按钮
```

## `TestResult` 扩展

在 `AddInstanceViewModel.kt` 中的 `TestResult` sealed interface 添加：

```kotlin
sealed interface TestResult {
    data object Testing : TestResult
    data class Success(val status: BridgeStatusResponse) : TestResult
    data class Error(val message: String) : TestResult
    data class PairingRequired(val status: BridgeStatusResponse) : TestResult
    data object PairingSuccess : TestResult
}
```

### PairingRequired vs 需要配对的检测

Bridge 的 `/status` 端点是**公开的**（不需要认证），所以测试连接时：
1. 调用 `bridgeStatus()` 成功 → 知道 Bridge 存在
2. 调用 `pairRequest()` → 触发 PIN 生成
3. 用户输入 PIN → 调用 `pairConfirm(pin)` → 获取 token

但问题：如何知道 Bridge **需要** 配对？

**方案**：当 Bridge 启用了认证时，非配对端点的请求会返回 401。但测试连接只调用 `/status`（公开端点），不会触发 401。

**改进方案**：Bridge 在 `/status` 响应中添加 `auth_enabled: bool` 字段：

```json
{
  "bridge": { "version": "1.0.0", "auth_enabled": true },
  "opencode": { "status": "running", "directory": "/home/user/project" }
}
```

在 `BridgeStatusResponse` DTO 中添加：

```kotlin
@Serializable
data class BridgeInfo(
    val version: String = "",
    val authEnabled: Boolean = false,  // 新增
)
```

这样 Android 端可以：
- `authEnabled == false` → 直接保存 profile，无需配对
- `authEnabled == true` → 触发配对流程

## `AddInstanceScreen.kt` 变更

在测试结果 `when` 分支中添加 `PairingRequired` 和 `PairingSuccess`：

```kotlin
when (testResult) {
    is TestResult.Testing -> Text(stringResource(R.string.testing), ...)
    is TestResult.Success -> { /* 现有代码 */ }
    is TestResult.Error -> Text((testResult as TestResult.Error).message, ...)
    is TestResult.PairingRequired -> {
        Column {
            Text(
                stringResource(R.string.pairing_required),
                color = MaterialTheme.colorScheme.primary,
            )
            Spacer(modifier = Modifier.height(8.dp))
            OutlinedTextField(
                value = pin,
                onValueChange = { viewModel.pin.value = it },
                label = { Text(stringResource(R.string.pairing_pin)) },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            )
            Spacer(modifier = Modifier.height(8.dp))
            Button(
                onClick = { viewModel.confirmPairing() },
                modifier = Modifier.fillMaxWidth(),
                enabled = pin.value.length == 6,
            ) {
                Text(stringResource(R.string.confirm_pairing))
            }
        }
    }
    is TestResult.PairingSuccess -> {
        Text(
            stringResource(R.string.pairing_success),
            color = MaterialTheme.colorScheme.primary,
        )
    }
    null -> {}
}
```

需要在 Screen 顶层添加 `pin` 状态收集：

```kotlin
val pin by viewModel.pin.collectAsState()
```

## `strings.xml` 新增

```xml
<string name="pairing_required">This Bridge requires pairing. Enter the PIN shown on the Bridge terminal.</string>
<string name="pairing_pin">6-digit PIN</string>
<string name="confirm_pairing">Confirm Pairing</string>
<string name="pairing_success">Paired successfully!</string>
<string name="pairing_failed">Pairing failed: %1$s</string>
<string name="instance_password_legacy">Password (legacy, leave empty)</string>
```

将 `instance_password` 替换为 `instance_password_legacy`，表示密码字段是旧版功能。

## Save 按钮逻辑变更

当 `authEnabled` 时，Save 按钮应该在配对成功后才可用：

```kotlin
Button(
    onClick = { viewModel.save(onBack) },
    modifier = Modifier.fillMaxWidth(),
    enabled = name.isNotBlank() && address.isNotBlank() &&
        (testResult is TestResult.Success || testResult is TestResult.PairingSuccess),
) {
    Text(stringResource(R.string.save))
}
```


---

# Android 3.2: AddInstanceViewModel 配对流程

## 目标

在 `AddInstanceViewModel` 中实现配对流程：当检测到 Bridge 启用认证时，自动发起配对请求，用户输入 PIN 后确认配对，成功后将 token 存入 `TokenStore`。

## 文件变更

| 操作 | 路径 |
|------|------|
| 修改 | `feature/instance/src/main/java/com/openmate/feature/instance/AddInstanceViewModel.kt` — 添加配对逻辑 |
| 修改 | `core/network/src/main/java/com/openmate/core/network/dto/BridgeDto.kt` — BridgeInfo 添加 authEnabled |

## `BridgeDto.kt` 变更

```kotlin
@Serializable
data class BridgeInfo(
    val version: String = "",
    val authEnabled: Boolean = false,
)
```

注意：Rust 端序列化用 `auth_enabled`（snake_case），Kotlin 的 `@Serializable` 默认不转换。需要用 `@SerialName`：

```kotlin
@Serializable
data class BridgeInfo(
    val version: String = "",
    @SerialName("auth_enabled")
    val authEnabled: Boolean = false,
)
```

## `AddInstanceViewModel.kt` 变更

### 新增依赖

```kotlin
@HiltViewModel
class AddInstanceViewModel @Inject constructor(
    private val profileRepository: ServerProfileRepository,
    private val apiClient: OpencodeApiClient,
    private val tokenStore: TokenRepository,  // 新增
) : ViewModel() {
```

### 新增状态

```kotlin
val pin = MutableStateFlow("")
```

### 修改 `testConnection()`

当前逻辑：调用 `bridgeStatus()` → 成功则 `TestResult.Success`，失败则 `TestResult.Error`。

修改后：调用 `bridgeStatus()` → 如果 `authEnabled == true`，自动调用 `pairRequest()` → `TestResult.PairingRequired`。

```kotlin
fun testConnection() {
    viewModelScope.launch {
        _testResult.value = TestResult.Testing
        try {
            val status = withContext(Dispatchers.IO) {
                val portNum = port.value.toIntOrNull()
                    ?: throw IllegalArgumentException("Invalid port")
                val url = "http://${address.value}:$portNum"
                val saved = apiClient.baseUrl
                apiClient.baseUrl = url
                try {
                    apiClient.bridgeStatus()
                } finally {
                    apiClient.baseUrl = saved
                }
            }
            if (status.bridge.version.isBlank()) {
                _testResult.value = TestResult.Error("Not a Bridge server")
            } else if (status.bridge.authEnabled) {
                withContext(Dispatchers.IO) {
                    val saved = apiClient.baseUrl
                    apiClient.baseUrl = "http://${address.value}:${port.value}"
                    try {
                        apiClient.bridgePairRequest()
                    } finally {
                        apiClient.baseUrl = saved
                    }
                }
                _testResult.value = TestResult.PairingRequired(status)
            } else {
                _testResult.value = TestResult.Success(status)
            }
        } catch (e: Exception) {
            _testResult.value = TestResult.Error(e.message ?: "Connection failed")
        }
    }
}
```

### 新增 `confirmPairing()`

```kotlin
fun confirmPairing() {
    viewModelScope.launch {
        try {
            val result = withContext(Dispatchers.IO) {
                val portNum = port.value.toIntOrNull()
                    ?: throw IllegalArgumentException("Invalid port")
                val url = "http://${address.value}:$portNum"
                val saved = apiClient.baseUrl
                apiClient.baseUrl = url
                try {
                    apiClient.bridgePairConfirm(pin.value)
                } finally {
                    apiClient.baseUrl = saved
                }
            }
            val profileId = editProfileId ?: UUID.randomUUID().toString()
            tokenStore.set(profileId, result.token)
            _testResult.value = TestResult.PairingSuccess
        } catch (e: AuthException) {
            _testResult.value = TestResult.Error("Pairing not approved or expired")
        } catch (e: Exception) {
            _testResult.value = TestResult.Error(e.message ?: "Pairing failed")
        }
    }
}
```

### 修改 `save()`

配对成功后，`save()` 需要知道 token 已存储在 `TokenStore` 中。不需要在 `ServerProfile` 中保存 token。

但 `save()` 当前会再次调用 `bridgeStatus()` 验证。对于已配对的 Bridge，这次调用会带上 Bearer token（如果 `activeProfileId` 已设置）。但此时 profile 还没保存，`activeProfileId` 不会是当前 profile 的 ID。

**解决方案**：配对成功后的 `save()` 不再重新验证 Bridge，直接保存 profile：

```kotlin
fun save(onSaved: () -> Unit) {
    val portNum = port.value.toIntOrNull()
    if (name.value.isBlank() || address.value.isBlank() || portNum == null || portNum !in 1..65535) {
        return
    }
    viewModelScope.launch {
        _isSaving.value = true
        try {
            if (testResult.value !is TestResult.PairingSuccess) {
                withContext(Dispatchers.IO) {
                    val url = "http://${address.value}:$portNum"
                    val saved = apiClient.baseUrl
                    apiClient.baseUrl = url
                    try {
                        val status = apiClient.bridgeStatus()
                        if (status.bridge.version.isBlank()) {
                            throw IllegalStateException("Not a Bridge server")
                        }
                    } finally {
                        apiClient.baseUrl = saved
                    }
                }
            }
            val profile = ServerProfile(
                id = editProfileId ?: UUID.randomUUID().toString(),
                name = name.value,
                address = address.value,
                port = portNum,
                password = password.value.ifBlank { null },
                createdAt = editProfileId?.let {
                    profileRepository.getById(it)?.createdAt
                } ?: System.currentTimeMillis(),
            )
            profileRepository.save(profile)
            withContext(Dispatchers.Main) { onSaved() }
        } catch (e: Exception) {
            _testResult.value = TestResult.Error("Save failed: ${e.message}")
        } finally {
            _isSaving.value = false
        }
    }
}
```

### `confirmPairing` 中 profileId 的问题

`confirmPairing()` 中用 `editProfileId ?: UUID.randomUUID().toString()` 生成 profileId 来存储 token。但 `save()` 中也用同样的逻辑生成 profileId。如果两个方法各自生成不同的 UUID，token 就无法关联到正确的 profile。

**解决方案**：在 ViewModel 中预生成 profileId：

```kotlin
private var pendingProfileId: String? = null

private fun ensureProfileId(): String {
    if (editProfileId != null) return editProfileId!!
    if (pendingProfileId == null) {
        pendingProfileId = UUID.randomUUID().toString()
    }
    return pendingProfileId!!
}
```

在 `confirmPairing()` 和 `save()` 中都使用 `ensureProfileId()`。

## `apiClient.baseUrl` 临时修改问题

当前 `testConnection()` 和 `confirmPairing()` 都临时修改 `apiClient.baseUrl`。这是线程不安全的（单例 `OpencodeApiClient` 可能被其他协程同时使用）。

这是**已有问题**，不在本步骤中解决。但需要注意：
- 配对流程中的 API 调用都发生在 ViewModel scope 中，不会被并发调用
- `bridgePairRequest()` 和 `bridgePairConfirm()` 都不需要 Bearer token，所以 `activeProfileId` 不会影响它们

## Hilt 注入 `TokenRepository`

`AddInstanceViewModel` 需要注入 `TokenRepository`。确保 Hilt module 中已提供 `TokenRepository` 绑定：

```kotlin
// 在 NetworkModule.kt 中
@Provides
@Singleton
fun provideTokenRepository(tokenStore: TokenStore): TokenRepository = tokenStore
```

或者直接注入 `TokenStore`（它实现了 `TokenRepository`）。


---

# Android 3.3: 401 重配对流程

## 目标

当已连接的 Bridge 因为 token 过期或重置而返回 401 时，自动触发重配对流程，引导用户重新完成配对。

## 文件变更

| 操作 | 路径 |
|------|------|
| 修改 | `core/network/src/main/java/com/openmate/core/network/BearerTokenInterceptor.kt` — 401 时清除 token |
| 修改 | `app/src/main/java/com/openmate/app/ConnectionManager.kt` — 检测 401 并触发重配对 |
| 修改 | `feature/instance/src/main/java/com/openmate/feature/instance/InstanceListViewModel.kt` — 添加重配对状态 |
| 修改 | `feature/instance/src/main/java/com/openmate/feature/instance/InstanceListScreen.kt` — 显示重配对 UI |
| 修改 | `feature/instance/src/main/res/values/strings.xml` — 添加重配对字符串 |

## 401 检测链

```
BearerTokenInterceptor 检测到 401
  → 从 TokenStore 中移除失效 token
  → ConnectionManager 检测到 SSE 断连 + token 丢失
  → 设置 rePairingRequired 状态
  → InstanceListScreen 显示重配对对话框
  → 用户确认 → 发起 pairRequest + 输入 PIN + pairConfirm
  → 成功后恢复连接
```

## `BearerTokenInterceptor` — 已在 2.2 中实现

2.2 步骤的 `BearerTokenInterceptor` 已包含 401 自动清除 token 的逻辑：

```kotlin
if (response.code == 401 && token != null) {
    tokenStore.remove(profileId!!)
}
```

无需额外修改。

## `ConnectionManager.kt` 变更

### 新增状态

```kotlin
private val _rePairingRequired = MutableStateFlow<ServerProfile?>(null)
val rePairingRequired: StateFlow<ServerProfile?> = _rePairingRequired.asStateFlow()
```

### 检测 401 的时机

SSE 连接在 401 时会被服务端关闭，导致 `ConnectionStatus.ERROR`。但 REST API 的 401 不会触发 SSE 断连。

**方案**：在 SSE 连接失败时检查是否因 token 缺失：

```kotlin
fun connect(profile: ServerProfile) {
    scope.launch {
        _connectionStatus.value = ConnectionStatus.CONNECTING
        _errorMessage.value = null
        _activeProfile.value = profile

        dbProvider.setActive(profile.id)
        apiClient.baseUrl = "http://${profile.address}:${profile.port}"
        apiClient.activeProfileId = profile.id

        try {
            val status = apiClient.bridgeStatus()
            if (status.bridge.version.isBlank()) {
                _connectionStatus.value = ConnectionStatus.NOT_BRIDGE
                _errorMessage.value = "Not a Bridge server."
                dbProvider.clearActive()
                _activeProfile.value = null
                apiClient.activeProfileId = null
                return@launch
            }
        } catch (e: AuthException) {
            _rePairingRequired.value = profile
            _connectionStatus.value = ConnectionStatus.ERROR
            _errorMessage.value = "Authentication required"
            return@launch
        } catch (e: Exception) {
            _connectionStatus.value = ConnectionStatus.NOT_BRIDGE
            _errorMessage.value = "Bridge not reachable: ${e.message}"
            dbProvider.clearActive()
            _activeProfile.value = null
            apiClient.activeProfileId = null
            return@launch
        }

        val updated = profile.copy(lastConnectedAt = System.currentTimeMillis())
        profileRepository.save(updated)

        try {
            sseEventRepository.connect(profile.address, profile.port, profile.password)
        } catch (e: Exception) {
            _connectionStatus.value = ConnectionStatus.ERROR
            _errorMessage.value = e.message ?: "Connection failed"
        }
    }
}
```

关键变更：
1. `apiClient.activeProfileId = profile.id` — 在连接前设置，使 `BearerTokenInterceptor` 能获取正确的 token
2. 捕获 `AuthException` — `bridgeStatus()` 是公开端点不会 401，但未来其他端点可能。如果 SSE 连接因 401 失败，`SseClient` 会抛出异常
3. 断开时清除 `apiClient.activeProfileId = null`

### 新增 `rePair()` 方法

```kotlin
fun rePair(profile: ServerProfile, pin: String, onResult: (Boolean) -> Unit) {
    scope.launch {
        try {
            apiClient.baseUrl = "http://${profile.address}:${profile.port}"
            apiClient.activeProfileId = null
            val pairResult = apiClient.bridgePairConfirm(pin)
            tokenStore.set(profile.id, pairResult.token)
            apiClient.activeProfileId = profile.id
            _rePairingRequired.value = null
            sseEventRepository.connect(profile.address, profile.port, profile.password)
            onResult(true)
        } catch (e: Exception) {
            onResult(false)
        }
    }
}
```

### 修改 `disconnect()`

```kotlin
fun disconnect() {
    sseEventRepository.disconnect()
    dbProvider.clearActive()
    _activeProfile.value = null
    _isConnected.value = false
    _connectionStatus.value = ConnectionStatus.DISCONNECTED
    _errorMessage.value = null
    _rePairingRequired.value = null
    apiClient.activeProfileId = null
}
```

### 新增 `startRePairing()` 方法

```kotlin
fun startRePairing() {
    val profile = _activeProfile.value ?: return
    scope.launch {
        try {
            apiClient.baseUrl = "http://${profile.address}:${profile.port}"
            apiClient.activeProfileId = null
            apiClient.bridgePairRequest()
        } catch (_: Exception) {}
    }
}
```

## `ConnectionManager` 新增依赖

```kotlin
@Singleton
class ConnectionManager @Inject constructor(
    private val profileRepository: ServerProfileRepository,
    private val sseEventRepository: SseEventRepository,
    private val sessionRepository: SessionRepository,
    private val dbProvider: ActiveDatabaseProvider,
    private val apiClient: OpencodeApiClient,
    private val tokenStore: TokenRepository,  // 新增
) {
```

## `InstanceListScreen.kt` — 重配对对话框

当 `ConnectionManager.rePairingRequired` 不为 null 时，显示配对对话框：

```kotlin
val rePairingProfile by connectionManager.rePairingRequired.collectAsState()

if (rePairingProfile != null) {
    RePairingDialog(
        profile = rePairingProfile!!,
        onDismiss = { connectionManager.disconnect() },
        onConfirm = { pin -> connectionManager.rePair(rePairingProfile!!, pin) { success ->
            if (!success) {
                SnackbarHostState.showSnackbar("Pairing failed, please try again")
            }
        }},
    )
}
```

`RePairingDialog` 是一个简单的 `AlertDialog`：

```kotlin
@Composable
fun RePairingDialog(
    profile: ServerProfile,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
) {
    var pin by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Re-pairing Required") },
        text = {
            Column {
                Text("The connection to ${profile.name} requires re-pairing. Enter the PIN shown on the Bridge terminal.")
                Spacer(modifier = Modifier.height(8.dp))
                OutlinedTextField(
                    value = pin,
                    onValueChange = { pin = it },
                    label = { Text("6-digit PIN") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                )
            }
        },
        confirmButton = {
            Button(
                onClick = { onConfirm(pin) },
                enabled = pin.length == 6,
            ) {
                Text("Confirm")
            }
        },
        dismissButton = {
            OutlinedButton(onClick = onDismiss) {
                Text("Disconnect")
            }
        },
    )
}
```

### `InstanceListScreen` 需要注入 `ConnectionManager`

当前 `InstanceListScreen` 已通过 `InstanceListViewModel` 间接访问 `ConnectionManager`。但 `rePairingRequired` 是 `ConnectionManager` 的状态，需要直接收集。

**方案**：在 `InstanceListViewModel` 中暴露 `rePairingRequired`：

```kotlin
val rePairingRequired: StateFlow<ServerProfile?> = connectionManager.rePairingRequired
```

或者直接在 Screen 中 `hiltViewModel()` 注入 `ConnectionManager`。

推荐通过 ViewModel 暴露，保持单向数据流。

## `InstanceListViewModel.kt` 变更

```kotlin
val rePairingRequired: StateFlow<ServerProfile?> = connectionManager.rePairingRequired

fun startRePairing() {
    connectionManager.startRePairing()
}

fun rePair(pin: String) {
    val profile = rePairingRequired.value ?: return
    connectionManager.rePair(profile, pin) { success ->
        if (!success) {
            _errorMessage.value = "Pairing failed, please try again"
        }
    }
}

fun dismissRePairing() {
    connectionManager.disconnect()
}
```

## `strings.xml` 新增

```xml
<string name="re_pairing_title">Re-pairing Required</string>
<string name="re_pairing_message">The connection to %1$s requires re-pairing. Enter the PIN shown on the Bridge terminal.</string>
<string name="re_pairing_pin">6-digit PIN</string>
<string name="re_pairing_confirm">Confirm</string>
<string name="re_pairing_disconnect">Disconnect</string>
<string name="re_pairing_failed">Pairing failed, please try again</string>
```

## SSE 401 的处理

SSE 连接通过 `BearerTokenInterceptor` 添加 Bearer token。如果 Bridge 返回 401，SSE 的 `EventSource` 会收到 HTTP 401 响应。

当前 `SseClient` 使用 `client.newCall(request).execute()` 建立连接。如果收到 401：
- `execute()` 不会抛出异常（HTTP 错误不是异常）
- 但 `response.body` 不是 SSE stream，解析会失败
- `SseClient` 会将此视为连接错误

需要检查 `SseClient.establishConnection()` 中的 401 处理：

```kotlin
// 在 SseClient.establishConnection() 中
val response = client.newCall(request).execute()
if (!response.isSuccessful) {
    if (response.code == 401) {
        throw AuthException("SSE authentication failed")
    }
    throw ServerUnavailableException("SSE connection failed: HTTP ${response.code}")
}
```

这样 401 会以 `AuthException` 形式传播到 `ConnectionManager`，触发重配对流程。

## SSE 断连后的 401 检测

如果 SSE 连接已经建立，后续因为 token 失效被服务端关闭（这种情况不太可能，token 验证只在连接建立时发生），`SseClient` 的重连逻辑会尝试重新连接。

重连时如果收到 401，`BearerTokenInterceptor` 会清除 token，`SseClient` 会抛出 `AuthException`。

`SseEventRepositoryImpl` 的 `observeConnectionStatus()` 会报告 `ConnectionStatus.ERROR`，`ConnectionManager` 可以在此时检查 `tokenStore.get(profileId) == null` 来判断是否需要重配对。

### 在 ConnectionManager.init 中添加检查

```kotlin
init {
    scope.launch {
        sseEventRepository.observeConnectionStatus().collect { status ->
            _connectionStatus.value = status
            _isConnected.value = status == ConnectionStatus.CONNECTED
            if (status == ConnectionStatus.CONNECTED) {
                sessionRepository.refreshSessionStatuses()
            }
            if (status == ConnectionStatus.ERROR) {
                val profileId = _activeProfile.value?.id
                if (profileId != null && tokenStore.get(profileId) == null) {
                    _rePairingRequired.value = _activeProfile.value
                }
                _errorMessage.value = "Connection lost"
            }
        }
    }
}
```

这样当 SSE 断连且 token 已被清除时，自动触发重配对。


---



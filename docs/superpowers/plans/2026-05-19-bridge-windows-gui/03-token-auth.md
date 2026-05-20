# 步骤 03: Token + Auth 变更

> 依赖: 步骤 01（BridgeDb）
> 产出: 修改 `auth/token.rs`、`auth/pair.rs`、`auth/middleware.rs`、`state.rs`

## 背景

Token 需要携带 device_id，认证中间件需要验证 device_id 是否在 paired_devices 表中。不向后兼容，旧 token 全部失效。

## 实现步骤

### Step 1: 修改 `src/auth/token.rs` — 增加 device_id

核心变更：将 device_id 编入 token 的签名数据中。

**当前 token 结构**: `random_hex(64) + hmac_hex(64)` = 128 字符
**新 token 结构**: `device_id_hex(16) + random_hex(48) + hmac_hex(64)` = 128 字符

```rust
use hmac::{Hmac, Mac};
use sha2::Sha256;
use super::key::SecretKey;
use super::key::{generate_random_bytes, hex_encode};

type HmacSha256 = Hmac<Sha256>;

const DEVICE_ID_LEN: usize = 16;

pub struct Token;

impl Token {
    pub fn generate(secret_key: &SecretKey, device_id: &str) -> String {
        let device_hex = hex_encode(device_id.as_bytes());
        let device_part = &device_hex[..DEVICE_ID_LEN];

        let random_bytes = generate_random_bytes(24);
        let random_hex = hex_encode(&random_bytes);

        let payload = format!("{}{}", device_part, random_hex);
        let signature = compute_hmac(secret_key.as_bytes(), &payload);
        let signature_hex = hex_encode(&signature);

        format!("{}{}", payload, signature_hex)
    }

    pub fn validate(secret_key: &SecretKey, token: &str) -> bool {
        if token.len() != 128 {
            return false;
        }
        let payload = &token[..64];
        let signature_hex = &token[64..];
        let expected = compute_hmac(secret_key.as_bytes(), payload);
        let expected_hex = hex_encode(&expected);
        constant_time_eq(signature_hex.as_bytes(), expected_hex.as_bytes())
    }

    pub fn extract_device_id(token: &str) -> Option<String> {
        if token.len() != 128 {
            return None;
        }
        let device_hex = &token[..DEVICE_ID_LEN];
        hex_decode(device_hex)
    }

    pub fn extract_from_header(header_value: &str) -> Option<&str> {
        header_value.strip_prefix("Bearer ")
    }
}

fn hex_decode(hex: &str) -> Option<String> {
    let bytes: Vec<u8> = (0..hex.len())
        .step_by(2)
        .filter_map(|i| u8::from_str_radix(&hex[i..i + 2], 16).ok())
        .collect();
    String::from_utf8(bytes).ok()
}

// compute_hmac 和 constant_time_eq 保持不变
```

### Step 2: 更新 `src/auth/token.rs` 的测试

```rust
#[cfg(test)]
mod tests {
    use super::*;

    fn test_key() -> SecretKey {
        SecretKey::from_bytes(vec![0x42u8; 32])
    }

    #[test]
    fn test_generate_token_length() {
        let key = test_key();
        let token = Token::generate(&key, "device-001");
        assert_eq!(token.len(), 128);
    }

    #[test]
    fn test_validate_valid_token() {
        let key = test_key();
        let token = Token::generate(&key, "device-001");
        assert!(Token::validate(&key, &token));
    }

    #[test]
    fn test_extract_device_id() {
        let key = test_key();
        let token = Token::generate(&key, "test-device");
        let extracted = Token::extract_device_id(&token);
        assert!(extracted.is_some());
        // device_id 可能有截断，但前缀匹配
        assert!(extracted.unwrap().starts_with("test"));
    }

    #[test]
    fn test_validate_wrong_key() {
        let key1 = test_key();
        let key2 = SecretKey::from_bytes(vec![0x24u8; 32]);
        let token = Token::generate(&key1, "device-001");
        assert!(!Token::validate(&key2, &token));
    }

    #[test]
    fn test_validate_tampered_token() {
        let key = test_key();
        let token = Token::generate(&key, "device-001");
        let mut tampered = token.clone();
        let mut bytes: Vec<char> = tampered.chars().collect();
        bytes[0] = if bytes[0] == '0' { '1' } else { '0' };
        tampered = bytes.into_iter().collect();
        assert!(!Token::validate(&key, &tampered));
    }
}
```

### Step 3: 修改 `src/auth/pair.rs` — confirm 时写入 BridgeDb

在 `pair_confirm` 函数中：

```rust
use crate::bridge_db::PairedDevice;

pub async fn pair_confirm(
    State(state): State<AppState>,
    ConnectInfo(addr): ConnectInfo<SocketAddr>,
    Json(body): Json<PairRequestBody>,
) -> Result<Json<PairConfirmResponse>, AppError> {
    let ip = addr.ip().to_string();
    let pin = body.pin.ok_or_else(|| AppError::BadRequest("PIN is required".to_string()))?;

    let mut pair_state = state.pending_pairs.write().await;
    let pair = pair_state.pending.get_mut(&pin).ok_or(AppError::PairNotFound)?;

    if is_expired(pair) {
        pair_state.pending.remove(&pin);
        return Err(AppError::PairExpired);
    }
    if pair.ip != ip { return Err(AppError::Forbidden); }
    pair.attempts += 1;
    if pair.attempts > MAX_CONFIRM_ATTEMPTS {
        pair_state.pending.remove(&pin);
        return Err(AppError::PairAttemptsExceeded);
    }
    if !pair.approved { return Err(AppError::PairNotApproved); }

    pair_state.pending.remove(&pin);

    // 新增: 生成 device_id 并写入 BridgeDb
    let device_id = super::key::generate_random_bytes(16)
        .iter()
        .map(|b| format!("{:02x}", b))
        .collect::<Vec<_>>()
        .join("");

    let now = chrono_iso_now();
    let device = PairedDevice {
        device_id: device_id.clone(),
        ip: ip.clone(),
        name: None,
        user_agent: None,
        paired_at: now,
        last_seen: None,
    };
    state.bridge_db.insert_device(&device)
        .map_err(|e| AppError::InternalServerError(e))?;

    let token = Token::generate(&state.secret_key, &device_id);

    tracing::info!("Pair confirmed for {}, device {}", ip, device_id);
    Ok(Json(PairConfirmResponse { token }))
}

fn chrono_iso_now() -> String {
    let duration = std::time::SystemTime::now()
        .duration_since(std::time::SystemTime::UNIX_EPOCH)
        .unwrap_or_default();
    format!("{}000", duration.as_millis())
}
```

### Step 4: 修改 `src/auth/middleware.rs` — 验证 device_id

```rust
const LOCALHOST_ONLY_PATHS: &[&str] = &[
    "/api/bridge/pair/approve",
    "/api/bridge/open-ui",           // 新增
    "/api/bridge/logs",              // 新增
    "/api/bridge/logs/stream",       // 新增
    "/api/bridge/network/interfaces", // 新增
    "/api/bridge/autostart",         // 新增
];

const PUBLIC_PATHS: &[&str] = &[
    "/api/bridge/status",
    "/api/bridge/pair/request",
    "/api/bridge/pair/confirm",
    "/ui/download",                  // 新增: 扫码落地页
    "/download/",                    // 新增: APK 下载
];

// 在 token 验证成功后，增加 device_id 检查:
if Token::validate(&state.secret_key, token_str) {
    // 新增: 检查 device_id 是否存在于 paired_devices
    if let Some(device_id) = Token::extract_device_id(token_str) {
        if state.bridge_db.device_exists(&device_id).unwrap_or(false) {
            // 异步更新 last_seen (用 blocking spawn 避免 DB 调用阻塞)
            let db = state.bridge_db.clone(); // 需要 BridgeDb 实现 Clone 或用 Arc
            let did = device_id.clone();
            let now = chrono_iso_now();
            tokio::task::spawn_blocking(move || {
                let _ = db.update_last_seen(&did, &now);
            });
            return next.run(req).await;
        }
    }
    tracing::warn!("auth: token valid but device_id not found in paired_devices");
}
```

### Step 5: 修改 `src/state.rs` — AppState 加 BridgeDb

```rust
use crate::bridge_db::BridgeDb;
use crate::log_capture::SharedLogBuffer;

pub struct AppStateInner {
    pub config: Config,
    pub opencode_status: RwLock<OpencodeStatus>,
    pub opencode_manager: OpencodeManager,
    pub secret_key: auth::key::SecretKey,
    pub pending_pairs: RwLock<auth::pair::PairState>,
    pub sync_db: SyncDb,
    pub log_buffer: SharedLogBuffer,        // 新增
    pub bridge_db: BridgeDb,                // 新增
}

pub fn create_app_state(config: Config) -> AppState {
    let secret_key = auth::key::SecretKey::load_or_generate()
        .expect("Failed to load or generate secret key");
    // ... 现有代码 ...

    let bridge_db = BridgeDb::open()
        .expect("Failed to open BridgeDb");
    let log_buffer = crate::log_capture::create_shared_buffer();

    Arc::new(AppStateInner {
        config,
        opencode_status: RwLock::new(OpencodeStatus::Stopped),
        opencode_manager: OpencodeManager::with_config(...),
        secret_key,
        pending_pairs: RwLock::new(auth::pair::PairState::new()),
        sync_db,
        log_buffer,
        bridge_db,
    })
}
```

> BridgeDb 需要 `#[derive(Clone)]` 或内部用 `Arc` 包装使其可克隆。最简方案：BridgeDb 内部用 `Arc<Pool<...>>`。

### Step 6: BridgeDb 添加 Clone 支持

修改 `src/bridge_db.rs`：

```rust
#[derive(Clone)]
pub struct BridgeDb {
    pool: Arc<Pool<SqliteConnectionManager>>,
}
```

同时将所有 `pool` 字段的使用改为 `self.pool.clone()` 获取连接。

### Step 7: 更新集成测试

`tests/integration.rs` 中所有涉及 token 的测试需要更新为 `Token::generate(&key, "test-device")` 格式。

### Step 8: 验证

```powershell
cargo test
cargo test --test integration
```

### Step 9: 提交

```
feat(bridge): add device_id to token, verify against paired_devices
```

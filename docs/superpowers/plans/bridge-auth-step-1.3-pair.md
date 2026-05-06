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

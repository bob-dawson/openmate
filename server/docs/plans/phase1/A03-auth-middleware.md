# A.03: HMAC-SHA256 Token 验证 + 认证中间件

> 目标：网关能验证 Bridge 现有的 HMAC token，提取 device_id，用于 HTTP 请求认证和 WS 注册认证。

## Files

- Modify: `server/relay-gateway/src/auth/hmac_auth.rs`
- Create: `server/relay-gateway/src/auth/middleware.rs`
- Modify: `server/relay-gateway/src/auth/mod.rs`

## Steps

- [ ] **Step 1: 编写 token 验证测试**

在 `src/auth/hmac_auth.rs` tests 中添加：

```rust
#[test]
fn test_generate_and_validate() {
    let key = test_key();
    // 用 Bridge 相同的逻辑生成 token
    let random_bytes = crate::auth::generate_random_bytes(32);
    let random_hex: String = random_bytes.iter().map(|b| format!("{:02x}", b)).collect();
    let payload = &random_hex;
    let signature = compute_hmac(key.as_bytes(), payload);
    let sig_hex: String = signature.iter().map(|b| format!("{:02x}", b)).collect();
    let token = format!("{}{}", payload, sig_hex);
    assert!(validate_token(&key, &token));
}

#[test]
fn test_validate_tampered() {
    let key = test_key();
    let random_bytes = crate::auth::generate_random_bytes(32);
    let random_hex: String = random_bytes.iter().map(|b| format!("{:02x}", b)).collect();
    let signature = compute_hmac(key.as_bytes(), &random_hex);
    let sig_hex: String = signature.iter().map(|b| format!("{:02x}", b)).collect();
    let token = format!("{}{}", random_hex, sig_hex);
    let mut tampered = token.clone();
    let bytes: Vec<char> = tampered.chars().collect();
    let first = bytes[0];
    let replacement = if first == '0' { '1' } else { '0' };
    let mut new_bytes = bytes.clone();
    new_bytes[0] = replacement;
    tampered = new_bytes.into_iter().collect();
    assert!(!validate_token(&key, &tampered));
}

#[test]
fn test_validate_wrong_key() {
    let key1 = test_key();
    let key2 = SecretKey::from_bytes(vec![0x24u8; 32]);
    let random_bytes = crate::auth::generate_random_bytes(32);
    let random_hex: String = random_bytes.iter().map(|b| format!("{:02x}", b)).collect();
    let signature = compute_hmac(key1.as_bytes(), &random_hex);
    let sig_hex: String = signature.iter().map(|b| format!("{:02x}", b)).collect();
    let token = format!("{}{}", random_hex, sig_hex);
    assert!(!validate_token(&key2, &token));
}
```

- [ ] **Step 2: 运行测试验证通过**

Run: `cargo test -- auth::hmac_auth`
Expected: 所有测试通过

- [ ] **Step 3: 编写认证中间件**

`src/auth/middleware.rs`:

```rust
use axum::extract::State;
use axum::http::{HeaderMap, Request};
use axum::middleware::Next;
use axum::response::Response;

use crate::error::GatewayError;
use crate::state::SharedState;

pub async fn require_auth(
    State(state): State<SharedState>,
    headers: HeaderMap,
    mut req: Request,
    next: Next,
) -> Result<Response, GatewayError> {
    let token = headers
        .get("authorization")
        .and_then(|v| v.to_str().ok())
        .and_then(|v| v.strip_prefix("Bearer "))
        .ok_or_else(|| GatewayError::Unauthorized("Missing or invalid Authorization header".to_string()))?;

    if !crate::auth::validate_token(&state.secret_key, token) {
        return Err(GatewayError::Unauthorized("Invalid token".to_string()));
    }

    req.extensions_mut().insert(AuthenticatedToken {
        token: token.to_string(),
    });

    Ok(next.run(req).await)
}

pub struct AuthenticatedToken {
    pub token: String,
}
```

- [ ] **Step 4: 更新 auth/mod.rs**

```rust
mod hmac_auth;
mod middleware;

pub use hmac_auth::*;
pub use middleware::*;
```

- [ ] **Step 5: 编译验证**

Run: `cargo build`
Expected: BUILD SUCCEEDED

- [ ] **Step 6: 提交**

```bash
git add server/relay-gateway/src/auth/
git commit -m "feat(gateway): add HMAC token validation and auth middleware"
```

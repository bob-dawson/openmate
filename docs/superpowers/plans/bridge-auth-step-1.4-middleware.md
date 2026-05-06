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

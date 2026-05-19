# A.07: 网关自身 API

> 目标：实现 /api/gateway/status（查询 Bridge 在线状态）和 /api/gateway/health（健康检查）。

## Files

- Create: `server/relay-gateway/src/api/mod.rs`
- Create: `server/relay-gateway/src/api/status.rs`
- Modify: `server/relay-gateway/src/server.rs`
- Modify: `server/relay-gateway/src/lib.rs`

## Steps

- [ ] **Step 1: 编写 status API 测试**

```rust
#[cfg(test)]
mod tests {
    use super::*;
    use crate::config::Config;
    use crate::auth::SecretKey;
    use crate::state::GatewayState;
    use std::sync::Arc;
    use axum::body::Body;
    use axum::http::{Request, StatusCode};
    use tower::ServiceExt;

    async fn test_app() -> (Router, Arc<GatewayState>) {
        let config = Config::default_test();
        let key = SecretKey::from_bytes(vec![0x42u8; 32]);
        let state = Arc::new(GatewayState::new(config, key));
        let app = crate::server::build_app(state.clone());
        (app, state)
    }

    #[tokio::test]
    async fn test_health() {
        let (app, _) = test_app().await;
        let req = Request::builder()
            .uri("/api/gateway/health")
            .body(Body::empty())
            .unwrap();
        let resp = app.oneshot(req).await.unwrap();
        assert_eq!(resp.status(), StatusCode::OK);
    }

    #[tokio::test]
    async fn test_status_bridge_offline() {
        let (app, _) = test_app().await;
        let req = Request::builder()
            .uri("/api/gateway/status?instance_id=inst_001")
            .body(Body::empty())
            .unwrap();
        let resp = app.oneshot(req).await.unwrap();
        assert_eq!(resp.status(), StatusCode::OK);
        let body = axum::body::to_bytes(resp.into_body(), 1024).await.unwrap();
        let json: serde_json::Value = serde_json::from_slice(&body).unwrap();
        assert_eq!(json["online"], false);
    }
}
```

- [ ] **Step 2: 实现 status API**

`src/api/mod.rs`:
```rust
pub mod status;
```

`src/api/status.rs`:

```rust
use axum::extract::{Query, State};
use axum::Json;
use serde::{Deserialize, Serialize};
use serde_json::json;

use crate::state::SharedState;
use crate::tunnel::bridge;

#[derive(Deserialize)]
pub struct StatusQuery {
    pub instance_id: String,
}

#[derive(Serialize)]
pub struct StatusResponse {
    pub online: bool,
    pub instance_id: String,
}

pub async fn gateway_status(
    State(state): State<SharedState>,
    Query(query): Query<StatusQuery>,
) -> Json<StatusResponse> {
    let online = bridge::is_bridge_online(&state, &query.instance_id);
    Json(StatusResponse {
        online,
        instance_id: query.instance_id,
    })
}

pub async fn gateway_health() -> &'static str {
    "OK"
}
```

- [ ] **Step 3: 更新 server.rs 路由**

```rust
use crate::api::status;

pub fn build_app(state: SharedState) -> Router {
    Router::new()
        .route("/api/gateway/health", get(status::gateway_health))
        .route("/api/gateway/status", get(status::gateway_status))
        .route("/ws", get(ws_handler))
        .fallback(any(proxy_handler))
        .layer(CorsLayer::permissive())
        .layer(TraceLayer::new_for_http())
        .with_state(state)
}
```

- [ ] **Step 4: 更新 lib.rs**

```rust
pub mod api;
pub mod auth;
pub mod config;
pub mod error;
pub mod proxy;
pub mod server;
pub mod state;
pub mod tunnel;
```

- [ ] **Step 5: 运行测试**

Run: `cargo test`
Expected: 所有测试通过

- [ ] **Step 6: 提交**

```bash
git add server/relay-gateway/src/
git commit -m "feat(gateway): add /api/gateway/status and health endpoints"
```

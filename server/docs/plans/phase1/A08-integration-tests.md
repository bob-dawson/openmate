# A.08: 心跳清理 + 集成测试

> 目标：实现定时心跳清理任务，编写端到端集成测试验证完整流程。

## Files

- Modify: `server/relay-gateway/src/server.rs`
- Create: `server/relay-gateway/tests/integration.rs`

## Steps

- [ ] **Step 1: 添加心跳清理定时任务**

在 `src/server.rs` 的 `run_server` 中启动后台清理任务：

```rust
pub async fn run_server(state: SharedState) -> anyhow::Result<()> {
    let addr = state.config.listen_addr();
    let app = build_app(state.clone());
    let listener = tokio::net::TcpListener::bind(&addr).await?;
    tracing::info!("Gateway listening on {}", addr);

    let cleanup_state = state.clone();
    let cleanup_interval = state.config.tunnel.heartbeat_timeout;
    let cleanup_handle = tokio::spawn(async move {
        let mut interval = tokio::time::interval(tokio::time::Duration::from_secs(cleanup_interval));
        loop {
            interval.tick().await;
            crate::tunnel::bridge::cleanup_stale_bridges(&cleanup_state, cleanup_interval).await;
        }
    });

    let result = axum::serve(listener, app).await;
    cleanup_handle.abort();
    result?;
    Ok(())
}
```

- [ ] **Step 2: 编写集成测试**

`tests/integration.rs` — 模拟 Bridge 连接 + Android HTTP 请求的完整流程：

```rust
use relay_gateway::config::Config;
use relay_gateway::auth::SecretKey;
use relay_gateway::state::GatewayState;
use std::sync::Arc;

fn test_state() -> Arc<GatewayState> {
    let config = Config::default_test();
    let key = SecretKey::from_bytes(vec![0x42u8; 32]);
    Arc::new(GatewayState::new(config, key))
}

#[tokio::test]
async fn test_bridge_register_and_query_status() {
    let state = test_state();
    let app = relay_gateway::server::build_app(state.clone());

    // 模拟 Bridge 注册
    let (tx, _rx) = tokio::sync::mpsc::unbounded_channel();
    relay_gateway::tunnel::bridge::handle_register(&state, "inst_test".to_string(), tx).await;

    // 查询状态 - 应该在线
    let req = axum::http::Request::builder()
        .uri("/api/gateway/status?instance_id=inst_test")
        .body(axum::body::Body::empty())
        .unwrap();
    let resp = tower::ServiceExt::oneshot(app, req).await.unwrap();
    assert_eq!(resp.status(), axum::http::StatusCode::OK);
    let body = axum::body::to_bytes(resp.into_body(), 1024).await.unwrap();
    let json: serde_json::Value = serde_json::from_slice(&body).unwrap();
    assert_eq!(json["online"], true);
}

#[tokio::test]
async fn test_proxy_without_bridge_returns_503() {
    let state = test_state();
    let app = relay_gateway::server::build_app(state);

    // 无 Bridge 注册，请求应返回 503
    let req = axum::http::Request::builder()
        .uri("/api/bridge/status")
        .header("authorization", "Bearer sometoken")
        .header("x-instance-id", "inst_missing")
        .body(axum::body::Body::empty())
        .unwrap();
    let resp = tower::ServiceExt::oneshot(app, req).await.unwrap();
    assert_eq!(resp.status(), axum::http::StatusCode::SERVICE_UNAVAILABLE);
}
```

- [ ] **Step 3: 运行全部测试**

Run: `cargo test`
Expected: 所有测试通过

- [ ] **Step 4: 运行集成测试**

Run: `cargo test --test integration`
Expected: 所有测试通过

- [ ] **Step 5: 提交**

```bash
git add server/relay-gateway/
git commit -m "feat(gateway): add heartbeat cleanup and integration tests"
```

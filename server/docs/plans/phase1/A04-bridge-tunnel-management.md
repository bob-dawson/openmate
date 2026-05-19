# A.04: Bridge WebSocket 隧道管理

> 目标：Bridge 通过 WS 连接网关，发送 register 帧注册 instance_id，网关管理连接生命周期（心跳、超时清理）。

## Files

- Create: `server/relay-gateway/src/tunnel/bridge.rs`
- Modify: `server/relay-gateway/src/tunnel/mod.rs`
- Modify: `server/relay-gateway/src/server.rs`

## Steps

- [ ] **Step 1: 编写隧道连接测试**

在 `src/tunnel/bridge.rs` 中编写测试，验证 register 帧处理和连接管理：

```rust
#[cfg(test)]
mod tests {
    use super::*;
    use crate::config::Config;
    use crate::auth::SecretKey;
    use crate::state::GatewayState;
    use std::sync::Arc;

    fn test_state() -> Arc<GatewayState> {
        let config = Config::default_test();
        let key = SecretKey::from_bytes(vec![0x42u8; 32]);
        Arc::new(GatewayState::new(config, key))
    }

    #[tokio::test]
    async fn test_register_adds_bridge() {
        let state = test_state();
        let (tx, _rx) = mpsc::unbounded_channel();
        handle_register(&state, "inst_001".to_string(), tx).await;
        assert!(state.bridges.contains_key("inst_001"));
    }

    #[tokio::test]
    async fn test_register_replaces_old_connection() {
        let state = test_state();
        let (tx1, _rx1) = mpsc::unbounded_channel();
        let (tx2, _rx2) = mpsc::unbounded_channel();
        handle_register(&state, "inst_001".to_string(), tx1).await;
        handle_register(&state, "inst_001".to_string(), tx2).await;
        assert!(state.bridges.contains_key("inst_001"));
        assert_eq!(state.bridges.len(), 1);
    }

    #[tokio::test]
    async fn test_remove_bridge() {
        let state = test_state();
        let (tx, _rx) = mpsc::unbounded_channel();
        handle_register(&state, "inst_001".to_string(), tx).await;
        remove_bridge(&state, "inst_001");
        assert!(!state.bridges.contains_key("inst_001"));
    }

    #[tokio::test]
    async fn test_is_bridge_online() {
        let state = test_state();
        assert!(!is_bridge_online(&state, "inst_001"));
        let (tx, _rx) = mpsc::unbounded_channel();
        handle_register(&state, "inst_001".to_string(), tx).await;
        assert!(is_bridge_online(&state, "inst_001"));
    }
}
```

- [ ] **Step 2: 实现隧道管理**

`src/tunnel/bridge.rs`:

```rust
use crate::state::{BridgeConn, SharedState};
use crate::tunnel::frame::TunnelFrame;
use std::time::Instant;
use tokio::sync::mpsc;
use tracing;

pub async fn handle_register(
    state: &SharedState,
    instance_id: String,
    tx: mpsc::UnboundedSender<TunnelFrame>,
) {
    if let Some(old) = state.bridges.insert(
        instance_id.clone(),
        BridgeConn {
            tx,
            instance_id: instance_id.clone(),
            last_heartbeat: Instant::now(),
        },
    ) {
        tracing::warn!("Bridge {} reconnected, closing old connection", instance_id);
        let _ = old.tx.send(TunnelFrame::error(None, 409, "replaced by new connection"));
    }
    tracing::info!("Bridge registered: {}", instance_id);
}

pub fn remove_bridge(state: &SharedState, instance_id: &str) {
    if state.bridges.remove(instance_id).is_some() {
        tracing::info!("Bridge removed: {}", instance_id);
    }
}

pub fn handle_heartbeat(state: &SharedState, instance_id: &str) {
    if let Some(mut conn) = state.bridges.get_mut(instance_id) {
        conn.last_heartbeat = Instant::now();
    }
}

pub fn is_bridge_online(state: &SharedState, instance_id: &str) -> bool {
    state.bridges.contains_key(instance_id)
}

pub fn get_bridge_tx(state: &SharedState, instance_id: &str) -> Option<mpsc::UnboundedSender<TunnelFrame>> {
    state.bridges.get(instance_id).map(|c| c.tx.clone())
}

pub async fn cleanup_stale_bridges(state: &SharedState, timeout_secs: u64) {
    let mut to_remove = Vec::new();
    for entry in state.bridges.iter() {
        let elapsed = entry.value().last_heartbeat.elapsed().as_secs();
        if elapsed > timeout_secs {
            to_remove.push(entry.key().clone());
        }
    }
    for instance_id in to_remove {
        tracing::warn!("Bridge {} heartbeat timeout, removing", instance_id);
        remove_bridge(state, &instance_id);
    }
}
```

- [ ] **Step 3: 更新 tunnel/mod.rs**

```rust
pub mod bridge;
pub mod frame;
```

- [ ] **Step 4: 实现 WS handler**

修改 `src/server.rs` 中的 `ws_handler`：

```rust
use axum::extract::{State, WebSocketUpgrade, ws::{Message, WebSocket}};
use crate::auth::validate_token;
use crate::state::SharedState;
use crate::tunnel::frame::TunnelFrame;
use crate::tunnel::bridge;
use futures::{SinkExt, StreamExt};

async fn ws_handler(
    State(state): State<SharedState>,
    ws: WebSocketUpgrade,
) -> impl IntoResponse {
    ws.on_upgrade(move |socket| handle_bridge_ws(socket, state))
}

async fn handle_bridge_ws(socket: WebSocket, state: SharedState) {
    let (mut sink, mut stream) = socket.split();
    let (tx, mut rx) = tokio::sync::mpsc::unbounded_channel::<TunnelFrame>();

    let mut registered_instance_id: Option<String> = None;

    let send_task = tokio::spawn(async move {
        while let Some(frame) = rx.recv().await {
            let json = match serde_json::to_string(&frame) {
                Ok(j) => j,
                Err(_) => continue,
            };
            if sink.send(Message::Text(json.into())).await.is_err() {
                break;
            }
        }
    });

    let state_clone = state.clone();
    let recv_task = tokio::spawn(async move {
        while let Some(Ok(msg)) = stream.next().await {
            let text = match msg {
                Message::Text(t) => t,
                Message::Close(_) => break,
                _ => continue,
            };
            let frame: TunnelFrame = match serde_json::from_str(&text) {
                Ok(f) => f,
                Err(e) => {
                    tracing::warn!("Invalid tunnel frame: {}", e);
                    continue;
                }
            };

            match frame.frame_type.as_str() {
                "register" => {
                    let instance_id = match (&frame.instance_id, &frame.token) {
                        (Some(id), Some(token)) => {
                            if !validate_token(&state_clone.secret_key, token) {
                                let _ = tx.send(TunnelFrame::error(None, 401, "invalid token"));
                                continue;
                            }
                            id.clone()
                        }
                        _ => {
                            let _ = tx.send(TunnelFrame::error(None, 400, "missing instance_id or token"));
                            continue;
                        }
                    };
                    bridge::handle_register(&state_clone, instance_id.clone(), tx.clone()).await;
                    let _ = tx.send(TunnelFrame::pong());
                    registered_instance_id = Some(instance_id);
                }
                "ping" => {
                    if let Some(ref id) = registered_instance_id {
                        bridge::handle_heartbeat(&state_clone, id);
                    }
                    let _ = tx.send(TunnelFrame::pong());
                }
                _ => {
                    // response, sse_event, sse_close 等帧由 proxy 模块处理
                    // 后续步骤实现
                }
            }
        }
        registered_instance_id
    });

    let (_, recv_result) = (send_task.await, recv_task.await);
    if let Ok(Some(instance_id)) = recv_result {
        bridge::remove_bridge(&state, &instance_id);
    }
}
```

- [ ] **Step 5: 运行测试**

Run: `cargo test`
Expected: 所有测试通过

- [ ] **Step 6: 提交**

```bash
git add server/relay-gateway/src/
git commit -m "feat(gateway): implement bridge WS tunnel management with register and heartbeat"
```

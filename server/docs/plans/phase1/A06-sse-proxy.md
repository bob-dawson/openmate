# A.06: SSE 流代理

> 目标：Android 请求 SSE 端点（如 /global/event），网关封装为 sse_open 帧发给 Bridge，Bridge 返回 sse_event 帧流，网关转为 SSE 流返回 Android。

## Files

- Create: `server/relay-gateway/src/proxy/sse.rs`
- Modify: `server/relay-gateway/src/server.rs`

## Steps

- [ ] **Step 1: 编写 SSE 代理状态管理**

`src/proxy/sse.rs`:

```rust
use tokio::sync::mpsc;
use uuid::Uuid;

use crate::error::GatewayError;
use crate::state::SharedState;
use crate::tunnel::bridge;
use crate::tunnel::frame::TunnelFrame;

pub struct SseStream {
    pub event_tx: msc::UnboundedSender<String>,
    pub request_id: String,
}

pub async fn open_sse_tunnel(
    state: &SharedState,
    instance_id: &str,
    path: &str,
    headers: &axum::http::HeaderMap,
    event_tx: mpsc::UnboundedSender<String>,
) -> Result<String, GatewayError> {
    let bridge_tx = bridge::get_bridge_tx(state, instance_id)
        .ok_or_else(|| GatewayError::BridgeOffline(instance_id.to_string()))?;

    let request_id = Uuid::new_v4().to_string();

    let frame_headers = convert_headers(headers);
    let frame = TunnelFrame::sse_open(&request_id, path, Some(frame_headers));

    bridge_tx.send(frame)
        .map_err(|_| GatewayError::BridgeOffline(instance_id.to_string()))?;

    Ok(request_id)
}

pub fn handle_sse_event(state: &SharedState, frame: &TunnelFrame) {
    if let (Some(request_id), Some(data)) = (&frame.request_id, &frame.data) {
        if let Some(mut pending) = state.pending_sse.get_mut(request_id) {
            let _ = pending.event_tx.send(data.clone());
        }
    }
}

pub fn handle_sse_close(state: &SharedState, frame: &TunnelFrame) {
    if let Some(request_id) = &frame.request_id {
        state.pending_sse.remove(request_id);
    }
}

fn convert_headers(headers: &axum::http::HeaderMap) -> std::collections::HashMap<String, String> {
    let mut map = std::collections::HashMap::new();
    for (name, value) in headers.iter() {
        if let Ok(v) = value.to_str() {
            map.insert(name.as_str().to_string(), v.to_string());
        }
    }
    map
}
```

- [ ] **Step 2: 在 state.rs 中添加 pending_sse**

```rust
pub struct GatewayState {
    pub config: Config,
    pub secret_key: crate::auth::SecretKey,
    pub bridges: dashmap::DashMap<String, BridgeConn>,
    pub pending_requests: dashmap::DashMap<String, PendingRequest>,
    pub pending_sse: dashmap::DashMap<String, crate::proxy::sse::SseStream>,
}
```

- [ ] **Step 3: 修改 server.rs 的 proxy_handler 识别 SSE 请求**

在 `proxy_handler` 中，判断请求路径是否为 SSE 端点，走不同的处理逻辑：

```rust
let is_sse = path == "/global/event"
    || path.starts_with("/global/event")
    || headers.get("accept")
        .and_then(|v| v.to_str().ok())
        .map(|v| v.contains("text/event-stream"))
        .unwrap_or(false);

if is_sse {
    return handle_sse_proxy(state, instance_id, &path, &headers).await;
}
```

```rust
async fn handle_sse_proxy(
    state: SharedState,
    instance_id: String,
    path: &str,
    headers: &HeaderMap,
) -> Result<Response<Body>, GatewayError> {
    let (event_tx, mut event_rx) = tokio::sync::mpsc::unbounded_channel::<String>();

    let request_id = crate::proxy::sse::open_sse_tunnel(
        &state, &instance_id, path, headers, event_tx,
    ).await?;

    let stream = async_stream::stream! {
        while let Some(data) = event_rx.recv().await {
            yield Ok::<_, std::convert::Infallible>(data);
        }
    };

    let body = Body::from_stream(stream);

    Response::builder()
        .status(StatusCode::OK)
        .header("content-type", "text/event-stream")
        .header("cache-control", "no-cache")
        .header("connection", "keep-alive")
        .body(body)
        .map_err(|e| GatewayError::Internal(anyhow::anyhow!("Failed to build SSE response: {}", e)))
}
```

- [ ] **Step 4: 更新 WS recv_task 处理 SSE 帧**

在 `handle_bridge_ws` 的 recv_task 中增加：

```rust
"sse_event" => {
    crate::proxy::sse::handle_sse_event(&state_clone, &frame);
}
"sse_close" => {
    crate::proxy::sse::handle_sse_close(&state_clone, &frame);
}
```

- [ ] **Step 5: 编译验证**

Run: `cargo build`
Expected: BUILD SUCCEEDED

- [ ] **Step 6: 提交**

```bash
git add server/relay-gateway/src/
git commit -m "feat(gateway): implement SSE stream proxy through tunnel"
```

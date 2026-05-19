# A.05: HTTP 请求代理（HTTP→隧道帧→HTTP）

> 目标：Android 发送 HTTP 请求到网关，网关验证 token，封装为 request 帧发给 Bridge，收到 response 帧后返回 HTTP 响应。

## Files

- Create: `server/relay-gateway/src/proxy/http.rs`
- Create: `server/relay-gateway/src/proxy/mod.rs`
- Modify: `server/relay-gateway/src/server.rs`
- Modify: `server/relay-gateway/src/state.rs`

## Steps

- [ ] **Step 1: 在 state.rs 中添加 extract_instance_id 方法**

Bridge 现有 token 不包含 instance_id。instance_id 通过 Bridge 注册时绑定。Android 请求时需要通过其他方式指定 instance_id。

方案：Android 请求时通过 query parameter `?instance_id=xxx` 指定，或在请求头 `X-Instance-Id` 中指定。

在 `src/state.rs` 中添加辅助函数：

```rust
pub fn extract_instance_id_from_request(req: &axum::http::Request<axum::body::Body>) -> Option<String> {
    req.headers()
        .get("x-instance-id")
        .and_then(|v| v.to_str().ok())
        .map(|s| s.to_string())
        .or_else(|| {
            req.uri().query().and_then(|q| {
                urlencoding/form_urlencoded 解析 instance_id 参数
            })
        })
}
```

实际上 Axum 中可以用 `axum::extract::Query` 提取。先用 header 方案，更简单。

- [ ] **Step 2: 编写 HTTP 代理测试**

```rust
#[cfg(test)]
mod tests {
    // 测试 tunnel request/response 的完整生命周期
    // 1. 创建 mock state，注册一个 bridge
    // 2. 构造 HTTP request
    // 3. 调用 proxy 函数
    // 4. 模拟 bridge 返回 response 帧
    // 5. 验证 HTTP response 正确
}
```

- [ ] **Step 3: 实现 HTTP 代理**

`src/proxy/mod.rs`:
```rust
pub mod http;
pub mod sse;
```

`src/proxy/http.rs`:

```rust
use axum::body::Body;
use axum::extract::State;
use axum::http::{HeaderMap, HeaderValue, Method, Request, Response, StatusCode};
use axum::response::IntoResponse;
use tokio::sync::oneshot;
use uuid::Uuid;

use crate::error::GatewayError;
use crate::state::{SharedState, PendingRequest, TunnelResponse};
use crate::tunnel::bridge;
use crate::tunnel::frame::TunnelFrame;

pub async fn proxy_http_request(
    state: &SharedState,
    instance_id: &str,
    method: Method,
    path: &str,
    headers: HeaderMap,
    body: Option<String>,
) -> Result<TunnelResponse, GatewayError> {
    let bridge_tx = bridge::get_bridge_tx(state, instance_id)
        .ok_or_else(|| GatewayError::BridgeOffline(instance_id.to_string()))?;

    let request_id = Uuid::new_v4().to_string();
    let (tx, rx) = oneshot::channel::<TunnelResponse>();

    state.pending_requests.insert(
        request_id.clone(),
        PendingRequest { responder: tx },
    );

    let frame_headers = convert_headers(&headers);
    let frame = TunnelFrame::request(
        &request_id,
        method.as_str(),
        path,
        Some(frame_headers),
        body,
    );

    bridge_tx.send(frame)
        .map_err(|_| {
            state.pending_requests.remove(&request_id);
            GatewayError::BridgeOffline(instance_id.to_string())
        })?;

    let timeout = tokio::time::Duration::from_secs(state.config.tunnel.request_timeout);
    let result = tokio::time::timeout(timeout, rx).await;

    state.pending_requests.remove(&request_id);

    match result {
        Ok(Ok(response)) => Ok(response),
        Ok(Err(_)) => Err(GatewayError::Internal(anyhow::anyhow!("Bridge connection dropped"))),
        Err(_) => Err(GatewayError::Timeout(format!("Request {} timed out", request_id))),
    }
}

fn convert_headers(headers: &HeaderMap) -> std::collections::HashMap<String, String> {
    let mut map = std::collections::HashMap::new();
    for (name, value) in headers.iter() {
        if let Ok(v) = value.to_str() {
            map.insert(name.as_str().to_string(), v.to_string());
        }
    }
    map
}

pub fn handle_tunnel_response(
    state: &SharedState,
    frame: &TunnelFrame,
) {
    if let Some(request_id) = &frame.request_id {
        if let Some((_, pending)) = state.pending_requests.remove(request_id) {
            let response = TunnelResponse {
                status: frame.status.unwrap_or(500),
                headers: frame.headers.clone().map(|h| h.into_iter().collect()).unwrap_or_default(),
                body: frame.body.clone().unwrap_or_default(),
            };
            let _ = pending.responder.send(response);
        }
    }
}
```

- [ ] **Step 4: 更新 server.rs 的 fallback handler**

将 `proxy_handler` 改为实际的代理逻辑：

```rust
async fn proxy_handler(
    State(state): State<SharedState>,
    req: Request<Body>,
) -> Result<Response<Body>, GatewayError> {
    // 1. 提取 token
    let token = req.headers()
        .get("authorization")
        .and_then(|v| v.to_str().ok())
        .and_then(|v| v.strip_prefix("Bearer "))
        .ok_or_else(|| GatewayError::Unauthorized("Missing token".to_string()))?;

    // 2. 验证 token
    if !crate::auth::validate_token(&state.secret_key, token) {
        return Err(GatewayError::Unauthorized("Invalid token".to_string()));
    }

    // 3. 提取 instance_id
    let instance_id = req.headers()
        .get("x-instance-id")
        .and_then(|v| v.to_str().ok())
        .ok_or_else(|| GatewayError::BadRequest("Missing X-Instance-Id header".to_string()))?
        .to_string();

    // 4. 提取请求信息
    let method = req.method().clone();
    let path = req.uri().path().to_string();
    let headers = req.headers().clone();

    let body = match axum::body::to_bytes(req.into_body(), state.config.tunnel.max_request_body).await {
        Ok(bytes) if !bytes.is_empty() => Some(String::from_utf8_lossy(&bytes).to_string()),
        _ => None,
    };

    // 5. 代理请求
    let tunnel_response = crate::proxy::http::proxy_http_request(
        &state, &instance_id, method, &path, headers, body,
    ).await?;

    // 6. 构造 HTTP 响应
    let status = StatusCode::from_u16(tunnel_response.status)
        .unwrap_or(StatusCode::INTERNAL_SERVER_ERROR);
    let mut response = Response::builder().status(status);
    for (key, value) in &tunnel_response.headers {
        if let (Ok(h), Ok(v)) = (
            axum::http::HeaderName::from_bytes(key.as_bytes()),
            HeaderValue::from_str(value),
        ) {
            response = response.header(h, v);
        }
    }
    response.body(Body::from(tunnel_response.body))
        .map_err(|e| GatewayError::Internal(anyhow::anyhow!("Failed to build response: {}", e)))
}
```

- [ ] **Step 5: 更新 WS recv_task 处理 response 帧**

在 `handle_bridge_ws` 的 recv_task 中增加 response 帧处理：

```rust
"response" => {
    crate::proxy::http::handle_tunnel_response(&state_clone, &frame);
}
```

- [ ] **Step 6: 编译验证**

Run: `cargo build`
Expected: BUILD SUCCEEDED

- [ ] **Step 7: 提交**

```bash
git add server/relay-gateway/src/
git commit -m "feat(gateway): implement HTTP request proxy through tunnel"
```

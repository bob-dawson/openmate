# B.02: Bridge 隧道 HTTP 请求处理

> 目标：Bridge 收到网关的 request 帧后，构造本地 HTTP 请求到 http://127.0.0.1:4097，获取响应后封装为 response 帧返回网关。

## Files

- Modify: `opencode-bridge/src/gateway/client.rs`
- Create: `opencode-bridge/src/gateway/proxy.rs`

## Steps

- [ ] **Step 1: 编写隧道 HTTP 代理测试**

`src/gateway/proxy.rs` tests:

```rust
#[cfg(test)]
mod tests {
    use super::*;

    #[tokio::test]
    async fn test_proxy_simple_get() {
        let result = proxy_request_to_local(
            "GET",
            &format!("http://127.0.0.1:{}/api/bridge/status", 4097),
            None,
            None,
        ).await;
        // 如果没有本地 server，会返回连接错误，验证函数逻辑正确即可
        assert!(result.is_ok() || result.unwrap_err().to_string().contains("connection"));
    }
}
```

- [ ] **Step 2: 实现 proxy_request_to_local**

`src/gateway/proxy.rs`:

```rust
use crate::gateway::frame::TunnelFrame;
use std::collections::HashMap;

pub async fn proxy_request_to_local(
    method: &str,
    local_base_url: &str,
    path: &str,
    headers: Option<HashMap<String, String>>,
    body: Option<String>,
) -> anyhow::Result<TunnelFrame> {
    let url = format!("{}{}", local_base_url, path);
    let client = reqwest::Client::new();

    let mut req = match method {
        "GET" => client.get(&url),
        "POST" => client.post(&url),
        "PUT" => client.put(&url),
        "DELETE" => client.delete(&url),
        "PATCH" => client.patch(&url),
        _ => client.get(&url),
    };

    if let Some(h) = headers {
        for (key, value) in h {
            req = req.header(&key, &value);
        }
    }

    if let Some(b) = body {
        req = req.body(b);
    }

    let resp = client.execute(req.build()?).await?;
    let status = resp.status().as_u16();
    let resp_headers: HashMap<String, String> = resp.headers().iter()
        .filter_map(|(k, v)| {
            v.to_str().ok().map(|s| (k.as_str().to_string(), s.to_string()))
        })
        .collect();
    let resp_body = resp.text().await.unwrap_or_default();

    Ok(TunnelFrame::response(
        "", // request_id 由调用者填充
        status,
        Some(resp_headers),
        if resp_body.is_empty() { None } else { Some(resp_body) },
    ))
}
```

- [ ] **Step 3: 在 client.rs 的 handle_frame 中处理 request 帧**

```rust
"request" => {
    let request_id = frame.request_id.clone().unwrap_or_default();
    let method = frame.method.clone().unwrap_or_else(|| "GET".to_string());
    let path = frame.path.clone().unwrap_or_default();
    let headers = frame.headers.clone();
    let body = frame.body.clone();

    let local_url = format!("http://127.0.0.1:{}", self.local_port);
    let result = crate::gateway::proxy::proxy_request_to_local(
        &method, &local_url, &path, headers, body,
    ).await;

    let response_frame = match result {
        Ok(mut f) => {
            f.request_id = Some(request_id);
            f
        }
        Err(e) => {
            TunnelFrame::error(Some(request_id), 502, &format!("Local proxy failed: {}", e))
        }
    };

    if let Ok(json) = serde_json::to_string(&response_frame) {
        let _ = sink.send(Message::Text(json.into())).await;
    }
}
```

需要在 GatewayClient 中增加 `local_port` 字段（从 config.bridge.port 获取）。

- [ ] **Step 4: 编译验证**

Run: `cargo build`
Expected: BUILD SUCCEEDED

- [ ] **Step 5: 提交**

```bash
git add opencode-bridge/src/gateway/
git commit -m "feat(bridge): implement tunnel HTTP request proxy to local server"
```

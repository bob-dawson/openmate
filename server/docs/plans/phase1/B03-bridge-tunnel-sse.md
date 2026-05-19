# B.03: Bridge 隧道 SSE 流处理

> 目标：Bridge 收到 sse_open 帧后，建立本地 SSE 连接到 http://127.0.0.1:4097/global/event，将事件逐个封装为 sse_event 帧推回网关。

## Files

- Modify: `opencode-bridge/src/gateway/client.rs`
- Create: `opencode-bridge/src/gateway/sse_proxy.rs`

## Steps

- [ ] **Step 1: 实现 SSE 隧道代理**

`src/gateway/sse_proxy.rs`:

```rust
use crate::gateway::frame::TunnelFrame;
use futures::StreamExt;
use std::collections::HashMap;

pub async fn proxy_sse_to_tunnel(
    local_base_url: &str,
    path: &str,
    headers: Option<HashMap<String, String>>,
    request_id: &str,
    tunnel_tx: tokio::sync::mpsc::UnboundedSender<TunnelFrame>,
) -> anyhow::Result<()> {
    let url = format!("{}{}", local_base_url, path);
    let client = reqwest::Client::new();

    let mut req = client.get(&url);
    if let Some(h) = headers {
        for (key, value) in h {
            req = req.header(&key, &value);
        }
    }

    let resp = client.execute(req.build()?).await?;
    if !resp.status().is_success() {
        let frame = TunnelFrame::error(
            Some(request_id.to_string()),
            resp.status().as_u16(),
            "SSE connection failed",
        );
        let _ = tunnel_tx.send(frame);
        return Ok(());
    }

    let mut stream = resp.bytes_stream();
    let mut buffer = String::new();

    while let Some(chunk) = stream.next().await {
        let chunk = chunk?;
        buffer.push_str(&String::from_utf8_lossy(&chunk));

        // 按行分割 SSE 事件
        while let Some(pos) = buffer.find("\n\n") {
            let event = buffer[..pos + 2].to_string();
            buffer = buffer[pos + 2..].to_string();

            let frame = TunnelFrame::sse_event(request_id, &event);
            if tunnel_tx.send(frame).is_err() {
                return Ok(());
            }
        }
    }

    let close_frame = TunnelFrame::sse_close(request_id);
    let _ = tunnel_tx.send(close_frame);
    Ok(())
}
```

- [ ] **Step 2: 在 client.rs 的 handle_frame 中处理 sse_open 帧**

```rust
"sse_open" => {
    let request_id = frame.request_id.clone().unwrap_or_default();
    let path = frame.path.clone().unwrap_or_default();
    let headers = frame.headers.clone();
    let local_url = format!("http://127.0.0.1:{}", self.local_port);
    let tunnel_tx = self.sender();

    tokio::spawn(async move {
        if let Err(e) = crate::gateway::sse_proxy::proxy_sse_to_tunnel(
            &local_url, &path, headers, &request_id, tunnel_tx,
        ).await {
            tracing::error!("SSE tunnel proxy error: {}", e);
            let err_frame = TunnelFrame::error(Some(request_id), 502, &format!("SSE proxy error: {}", e));
            // 尝试发送错误帧
        }
    });
}
```

- [ ] **Step 3: 编译验证**

Run: `cargo build`
Expected: BUILD SUCCEEDED

- [ ] **Step 4: 运行测试**

Run: `cargo test`
Expected: 所有测试通过

- [ ] **Step 5: 提交**

```bash
git add opencode-bridge/src/gateway/
git commit -m "feat(bridge): implement SSE tunnel proxy to gateway"
```

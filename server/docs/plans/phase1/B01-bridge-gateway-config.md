# B.01: Bridge 配置扩展 + 隧道客户端连接

> 目标：Bridge 配置文件新增 [gateway] section，启动时可选连接网关 WS，发送 register 帧注册，维护心跳。

## Files

- Modify: `opencode-bridge/src/config.rs`
- Modify: `opencode-bridge/src/state.rs`
- Create: `opencode-bridge/src/gateway/mod.rs`
- Create: `opencode-bridge/src/gateway/client.rs`
- Create: `opencode-bridge/src/gateway/frame.rs`
- Modify: `opencode-bridge/src/lib.rs`
- Modify: `opencode-bridge/src/main.rs`
- Modify: `opencode-bridge/Cargo.toml` — 添加 tokio-tungstenite, uuid, futures 依赖

## Steps

- [ ] **Step 1: 添加依赖**

在 `Cargo.toml` 中添加：

```toml
tokio-tungstenite = "0.26"
uuid = { version = "1", features = ["v4"] }
futures = "0.3"
```

- [ ] **Step 2: 扩展 Config**

在 `src/config.rs` 中添加 GatewayConfig：

```rust
#[derive(Debug, Deserialize, Serialize, Clone)]
pub struct GatewayConfig {
    #[serde(default)]
    pub url: String,
    #[serde(default = "default_true")]
    pub auto_connect: bool,
    #[serde(default)]
    pub instance_id: String,
}

impl Default for GatewayConfig {
    fn default() -> Self {
        GatewayConfig {
            url: String::new(),
            auto_connect: true,
            instance_id: String::new(),
        }
    }
}
```

在 `Config` struct 中添加字段：

```rust
#[serde(default)]
pub gateway: GatewayConfig,
```

更新 `Config::default()` 和测试。

- [ ] **Step 3: 复用隧道帧定义**

`src/gateway/frame.rs` — 与网关的 TunnelFrame 完全一致的 JSON 结构：

```rust
use serde::{Deserialize, Serialize};
use std::collections::HashMap;

#[derive(Debug, Serialize, Deserialize)]
pub struct TunnelFrame {
    #[serde(rename = "type")]
    pub frame_type: String,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub request_id: Option<String>,
    // ... 与 A.02 完全相同的所有字段
}
// ... 与 A.02 完全相同的所有构造方法
```

> 两个 crate 共享相同的帧格式，但各自实现序列化。未来可提取为共享 crate。

- [ ] **Step 4: 实现 GatewayClient**

`src/gateway/client.rs`:

```rust
use crate::auth::key::SecretKey;
use crate::auth::token::Token;
use crate::config::GatewayConfig;
use crate::gateway::frame::TunnelFrame;
use futures::{SinkExt, StreamExt};
use tokio::sync::mpsc;
use tokio_tungstenite::{connect_async, tungstenite::Message};

pub struct GatewayClient {
    config: GatewayConfig,
    instance_id: String,
    ws_tx: mpsc::UnboundedSender<TunnelFrame>,
    ws_rx: mpsc::UnboundedReceiver<TunnelFrame>,
}

impl GatewayClient {
    pub fn new(config: &GatewayConfig, instance_id: &str) -> Self {
        let (ws_tx, ws_rx) = mpsc::unbounded_channel();
        GatewayClient {
            config: config.clone(),
            instance_id: instance_id.to_string(),
            ws_tx,
            ws_rx,
        }
    }

    pub fn sender(&self) -> mpsc::UnboundedSender<TunnelFrame> {
        self.ws_tx.clone()
    }

    pub async fn connect(&mut self, secret_key: &SecretKey) -> anyhow::Result<()> {
        let token = Token::generate(secret_key);
        let url = format!("{}/ws", self.config.url);

        loop {
            tracing::info!("Connecting to gateway: {}", url);
            match connect_async(&url).await {
                Ok((ws_stream, _)) => {
                    tracing::info!("Connected to gateway");
                    let (mut sink, mut stream) = ws_stream.split();

                    // 发送 register
                    let register = TunnelFrame::register(&self.instance_id, &token);
                    let json = serde_json::to_string(&register)?;
                    sink.send(Message::Text(json.into())).await?;
                    tracing::info!("Sent register for {}", self.instance_id);

                    // 心跳 + 收发循环
                    self.run_loop(&mut sink, &mut stream).await;
                }
                Err(e) => {
                    tracing::error!("Gateway connection failed: {}", e);
                }
            }

            tracing::info!("Reconnecting to gateway in 5s...");
            tokio::time::sleep(tokio::time::Duration::from_secs(5)).await;
        }
    }

    async fn run_loop(
        &mut self,
        sink: &mut futures::stream::SplitSink<
            tokio_tungstenite::WebSocketStream<
                tokio_tungstenite::MaybeTlsStream<tokio::net::TcpStream>,
            >,
            Message,
        >,
        stream: &mut futures::stream::SplitStream<
            tokio_tungstenite::WebSocketStream<
                tokio_tungstenite::MaybeTlsStream<tokio::net::TcpStream>,
            >,
        >,
    ) {
        let mut heartbeat = tokio::time::interval(tokio::time::Duration::from_secs(30));

        loop {
            tokio::select! {
                _ = heartbeat.tick() => {
                    let ping = TunnelFrame::ping();
                    let json = match serde_json::to_string(&ping) {
                        Ok(j) => j,
                        Err(_) => continue,
                    };
                    if sink.send(Message::Text(json.into())).await.is_err() {
                        tracing::warn!("Gateway WS send failed, connection lost");
                        break;
                    }
                }
                Some(frame) = self.ws_rx.recv() => {
                    let json = match serde_json::to_string(&frame) {
                        Ok(j) => j,
                        Err(_) => continue,
                    };
                    if sink.send(Message::Text(json.into())).await.is_err() {
                        tracing::warn!("Gateway WS send failed, connection lost");
                        break;
                    }
                }
                msg = stream.next() => {
                    match msg {
                        Some(Ok(Message::Text(text))) => {
                            let frame: TunnelFrame = match serde_json::from_str(&text) {
                                Ok(f) => f,
                                Err(e) => {
                                    tracing::warn!("Invalid frame from gateway: {}", e);
                                    continue;
                                }
                            };
                            self.handle_frame(frame, sink).await;
                        }
                        Some(Ok(Message::Close(_))) | None => {
                            tracing::warn!("Gateway WS closed");
                            break;
                        }
                        _ => {}
                    }
                }
            }
        }
    }

    async fn handle_frame(
        &mut self,
        frame: TunnelFrame,
        sink: &mut futures::stream::SplitSink<
            tokio_tungstenite::WebSocketStream<
                tokio_tungstenite::MaybeTlsStream<tokio::net::TcpStream>,
            >,
            Message,
        >,
    ) {
        match frame.frame_type.as_str() {
            "request" => {
                // 后续步骤 B.03 实现
            }
            "sse_open" => {
                // 后续步骤 B.04 实现
            }
            "pong" => {
                // 心跳响应，无需处理
            }
            "error" => {
                tracing::error!("Gateway error: {:?}", frame.error_message);
            }
            _ => {
                tracing::warn!("Unknown frame type from gateway: {}", frame.frame_type);
            }
        }
    }
}
```

- [ ] **Step 5: 更新 gateway/mod.rs**

```rust
pub mod client;
pub mod frame;
```

- [ ] **Step 6: 更新 lib.rs**

```rust
pub mod gateway;
```

- [ ] **Step 7: 在 main.rs 中启动 gateway 连接**

在 `main()` 函数中，server 启动后，如果配置了 gateway.url，启动 gateway client：

```rust
if !config.gateway.url.is_empty() && config.gateway.auto_connect {
    let gw_config = config.gateway.clone();
    let secret_key = state.secret_key.clone();
    let instance_id = config.gateway.instance_id.clone();
    tokio::spawn(async move {
        let mut client = crate::gateway::client::GatewayClient::new(&gw_config, &instance_id);
        if let Err(e) = client.connect(&secret_key).await {
            tracing::error!("Gateway client error: {}", e);
        }
    });
}
```

- [ ] **Step 8: 更新配置文件示例**

```toml
[gateway]
url = "wss://gw.openmate.dev"
auto_connect = true
instance_id = "my-dev-machine"
```

- [ ] **Step 9: 编译验证**

Run: `cargo build`
Expected: BUILD SUCCEEDED

- [ ] **Step 10: 运行现有测试确保不破坏**

Run: `cargo test`
Expected: 所有测试通过

- [ ] **Step 11: 提交**

```bash
git add opencode-bridge/
git commit -m "feat(bridge): add gateway tunnel client with WS connection and heartbeat"
```

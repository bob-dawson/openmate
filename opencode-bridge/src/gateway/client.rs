use std::time::Duration;

use futures::{SinkExt, StreamExt};
use tokio::sync::mpsc;
use tokio_tungstenite::{connect_async, tungstenite::Message, MaybeTlsStream};

use crate::config::GatewayConfig;
use crate::gateway::frame::TunnelFrame;
use crate::gateway::{proxy, sse_proxy};

pub struct GatewayClient {
    config: GatewayConfig,
    instance_id: String,
    local_port: u16,
    outbound_tx: mpsc::UnboundedSender<TunnelFrame>,
    outbound_rx: mpsc::UnboundedReceiver<TunnelFrame>,
}

impl GatewayClient {
    pub fn new(config: &GatewayConfig, instance_id: &str, local_port: u16) -> Self {
        let (outbound_tx, outbound_rx) = mpsc::unbounded_channel();
        Self {
            config: config.clone(),
            instance_id: instance_id.to_string(),
            local_port,
            outbound_tx,
            outbound_rx,
        }
    }

    pub fn sender(&self) -> mpsc::UnboundedSender<TunnelFrame> {
        self.outbound_tx.clone()
    }

    pub async fn connect(&mut self) {
        loop {
            if let Err(e) = self.connect_once().await {
                tracing::error!("Gateway connection error: {}", e);
            }
            tracing::info!("Gateway disconnected, reconnecting in 5s...");
            tokio::time::sleep(Duration::from_secs(5)).await;
        }
    }

    async fn connect_once(&mut self) -> anyhow::Result<()> {
        let ws_url = self.config.url
            .replace("https://", "wss://")
            .replace("http://", "ws://");
        let ws_url = format!("{}/ws", ws_url);
        tracing::info!("Connecting to gateway: {}", ws_url);

        let (ws_stream, _) = tokio::time::timeout(Duration::from_secs(10), connect_async(&ws_url))
            .await
            .map_err(|_| anyhow::anyhow!("Gateway connection timed out after 10s"))??;

        #[cfg(windows)]
        {
            use std::os::windows::io::{AsRawSocket, FromRawSocket};
            let inner: &MaybeTlsStream<tokio::net::TcpStream> = ws_stream.get_ref();
            let tcp = match inner {
                MaybeTlsStream::Plain(tcp) => tcp,
                MaybeTlsStream::Rustls(tls) => tls.get_ref().0,
                _ => return Ok(()),
            };
            let ka = socket2::TcpKeepalive::new()
                .with_time(Duration::from_secs(30))
                .with_interval(Duration::from_secs(10));
            let sock = unsafe { socket2::Socket::from_raw_socket(tcp.as_raw_socket()) };
            let sock = std::mem::ManuallyDrop::new(sock);
            let _ = socket2::SockRef::from(&*sock).set_tcp_keepalive(&ka);
        }

        let (mut ws_sink, mut ws_stream) = ws_stream.split();

        tracing::info!("Gateway WebSocket connected");

        let register_frame = TunnelFrame::register(&self.instance_id);
        let register_json = serde_json::to_string(&register_frame)?;
        ws_sink.send(Message::Text(register_json.into())).await?;

        tracing::info!("Registered with gateway as {}", self.instance_id);

        let mut heartbeat = tokio::time::interval(Duration::from_secs(30));
        heartbeat.tick().await;

        loop {
            tokio::select! {
                _ = heartbeat.tick() => {
                    let ping = TunnelFrame::ping();
                    let json = serde_json::to_string(&ping)?;
                    if ws_sink.send(Message::Text(json.into())).await.is_err() {
                        tracing::warn!("Failed to send heartbeat, connection lost");
                        break;
                    }
                }

                Some(frame) = self.outbound_rx.recv() => {
                    let json = serde_json::to_string(&frame)?;
                    if ws_sink.send(Message::Text(json.into())).await.is_err() {
                        tracing::warn!("Failed to send outbound frame, connection lost");
                        break;
                    }
                }

                msg = ws_stream.next() => {
                    match msg {
                        Some(Ok(Message::Text(text))) => {
                            match serde_json::from_str::<TunnelFrame>(&text) {
                                Ok(frame) => {
                                    self.handle_incoming(frame).await;
                                }
                                Err(e) => {
                                    tracing::warn!("Failed to parse tunnel frame: {}", e);
                                }
                            }
                        }
                        Some(Ok(Message::Ping(data))) => {
                            let _ = ws_sink.send(Message::Pong(data)).await;
                        }
                        Some(Ok(Message::Close(_))) | None => {
                            tracing::info!("Gateway WebSocket closed");
                            break;
                        }
                        Some(Err(e)) => {
                            tracing::error!("Gateway WebSocket error: {}", e);
                            break;
                        }
                        _ => {}
                    }
                }
            }
        }

        Ok(())
    }

    async fn handle_incoming(&self, frame: TunnelFrame) {
        match frame.frame_type.as_str() {
            "request" => {
                let request_id = frame.request_id.clone().unwrap_or_default();
                let method = frame.method.clone().unwrap_or_else(|| "GET".to_string());
                let path = frame.path.clone().unwrap_or_default();
                let headers = frame.headers.clone();
                let body = frame.body.clone();
                let local_port = self.local_port;
                let tunnel_tx = self.outbound_tx.clone();

                tokio::spawn(async move {
                    let mut response = proxy::proxy_request_to_local(
                        &method, local_port, &path, headers, body,
                    )
                    .await;
                    response.request_id = Some(request_id);
                    let _ = tunnel_tx.send(response);
                });
            }
            "sse_open" => {
                let request_id = frame.request_id.clone().unwrap_or_default();
                let path = frame.path.clone().unwrap_or_default();
                let headers = frame.headers.clone();
                let local_port = self.local_port;
                let tunnel_tx = self.outbound_tx.clone();

                tokio::spawn(async move {
                    sse_proxy::proxy_sse_to_tunnel(
                        local_port,
                        &path,
                        headers,
                        &request_id,
                        tunnel_tx,
                    )
                    .await;
                });
            }
            "pong" => {}
            "error" => {
                tracing::warn!(
                    "Gateway error: code={:?} msg={:?}",
                    frame.status,
                    frame.error_message
                );
            }
            other => {
                tracing::warn!("Unknown frame type from gateway: {}", other);
            }
        }
    }
}

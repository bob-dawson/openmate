use std::collections::HashMap;

use crate::gateway::frame::{GatewayOutgoing, TunnelFrame};
use futures::StreamExt;
use tokio::sync::mpsc;

pub async fn proxy_sse_to_tunnel(
    local_port: u16,
    path: &str,
    headers: Option<HashMap<String, String>>,
    request_id: &str,
    tunnel_tx: mpsc::UnboundedSender<GatewayOutgoing>,
) {
    let url = format!("http://127.0.0.1:{}{}", local_port, path);

    let client = reqwest::Client::new();
    let mut builder = client.get(&url).header("Accept", "text/event-stream");

    if let Some(ref hdrs) = headers {
        for (k, v) in hdrs {
            let lower = k.to_lowercase();
            if matches!(lower.as_str(), "host" | "connection" | "transfer-encoding" | "authorization" | "x-instance-id") {
                continue;
            }
            builder = builder.header(k.as_str(), v.as_str());
        }
    }

    let resp = match builder.send().await {
        Ok(r) => r,
        Err(e) => {
            tracing::error!("SSE proxy connect failed: {}", e);
            let _ = tunnel_tx.send(GatewayOutgoing::Json(TunnelFrame::error(
                Some(request_id.to_string()),
                502,
                &format!("SSE connect error: {}", e),
            )));
            let _ = tunnel_tx.send(GatewayOutgoing::Json(TunnelFrame::sse_close(request_id)));
            return;
        }
    };

    if !resp.status().is_success() {
        let status = resp.status().as_u16();
        let body = resp.text().await.unwrap_or_default();
        let _ = tunnel_tx.send(GatewayOutgoing::Json(TunnelFrame::error(
            Some(request_id.to_string()),
            status,
            &body,
        )));
        let _ = tunnel_tx.send(GatewayOutgoing::Json(TunnelFrame::sse_close(request_id)));
        return;
    }

    let mut stream = resp.bytes_stream();
    let mut buffer = String::new();

    while let Some(chunk) = stream.next().await {
        match chunk {
            Ok(bytes) => {
                buffer.push_str(&String::from_utf8_lossy(&bytes));
                while let Some(pos) = buffer.find("\n\n") {
                    let event = buffer[..pos + 2].to_string();
                    buffer = buffer[pos + 2..].to_string();
                    if tunnel_tx
                        .send(GatewayOutgoing::Json(TunnelFrame::sse_event(request_id, &event)))
                        .is_err()
                    {
                        return;
                    }
                }
            }
            Err(e) => {
                tracing::error!("SSE stream error: {}", e);
                break;
            }
        }
    }

    if !buffer.trim().is_empty() {
        let _ = tunnel_tx.send(GatewayOutgoing::Json(TunnelFrame::sse_event(request_id, &buffer)));
    }

    let _ = tunnel_tx.send(GatewayOutgoing::Json(TunnelFrame::sse_close(request_id)));
}

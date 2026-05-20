use std::collections::HashMap;

use futures::StreamExt;
use tokio::sync::mpsc;

use crate::gateway::frame::{GatewayOutgoing, TunnelFrame};

pub async fn proxy_request_to_local(
    method: &str,
    local_port: u16,
    path: &str,
    headers: Option<HashMap<String, String>>,
    body: Option<String>,
    request_id: &str,
    tunnel_tx: &mpsc::UnboundedSender<GatewayOutgoing>,
) {
    let url = format!("http://127.0.0.1:{}{}", local_port, path);

    let client = reqwest::Client::builder()
        .no_proxy()
        .build()
        .unwrap_or_default();

    let mut builder = match method.to_uppercase().as_str() {
        "GET" => client.get(&url),
        "POST" => client.post(&url),
        "PUT" => client.put(&url),
        "DELETE" => client.delete(&url),
        "PATCH" => client.patch(&url),
        "HEAD" => client.head(&url),
        _ => client.get(&url),
    };

    if let Some(ref hdrs) = headers {
        for (k, v) in hdrs {
            let lower = k.to_lowercase();
            if matches!(lower.as_str(), "host" | "connection" | "transfer-encoding" | "authorization" | "x-instance-id") {
                continue;
            }
            builder = builder.header(k.as_str(), v.as_str());
        }
    }

    if let Some(ref b) = body {
        builder = builder.body(b.clone());
    }

    let resp = match builder.send().await {
        Ok(r) => r,
        Err(e) => {
            tracing::error!("Proxy request to local failed: {}", e);
            let _ = tunnel_tx.send(GatewayOutgoing::Json(TunnelFrame::error(
                Some(request_id.to_string()),
                502,
                &format!("Local server error: {}", e),
            )));
            return;
        }
    };

    let status = resp.status().as_u16();
    let resp_headers: HashMap<String, String> = resp
        .headers()
        .iter()
        .filter_map(|(k, v)| {
            let key = k.to_string();
            let lower = key.to_lowercase();
            if lower == "content-encoding" || lower == "content-length" || lower == "transfer-encoding" {
                return None;
            }
            v.to_str().ok().map(|s| (key, s.to_string()))
        })
        .collect();

    let start = TunnelFrame::response_start(request_id, status, Some(resp_headers));
    if tunnel_tx.send(GatewayOutgoing::Json(start)).is_err() {
        return;
    }

    let rid = request_id.to_string();
    let mut stream = resp.bytes_stream();
    while let Some(chunk) = stream.next().await {
        let chunk = match chunk {
            Ok(c) => c,
            Err(e) => {
                tracing::error!("Stream read error: {}", e);
                break;
            }
        };
        if tunnel_tx
            .send(GatewayOutgoing::Binary {
                request_id: rid.clone(),
                data: chunk.to_vec(),
            })
            .is_err()
        {
            return;
        }
    }

    let end = TunnelFrame::response_end(request_id);
    let _ = tunnel_tx.send(GatewayOutgoing::Json(end));
}

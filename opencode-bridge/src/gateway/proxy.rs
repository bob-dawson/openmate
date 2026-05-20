use std::collections::HashMap;

use bytes::Bytes;
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
            if lower == "content-encoding" || lower == "transfer-encoding" {
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

pub async fn proxy_upload_to_local(
    method: &str,
    local_port: u16,
    path: &str,
    headers: Option<HashMap<String, String>>,
    request_id: &str,
    tunnel_tx: &mpsc::UnboundedSender<GatewayOutgoing>,
    chunk_rx: mpsc::UnboundedReceiver<Vec<u8>>,
) {
    let url = format!("http://127.0.0.1:{}{}", local_port, path);

    let client = reqwest::Client::builder()
        .no_proxy()
        .build()
        .unwrap_or_default();

    let reqwest_method = match method.to_uppercase().as_str() {
        "GET" => reqwest::Method::GET,
        "POST" => reqwest::Method::POST,
        "PUT" => reqwest::Method::PUT,
        "DELETE" => reqwest::Method::DELETE,
        "PATCH" => reqwest::Method::PATCH,
        "HEAD" => reqwest::Method::HEAD,
        _ => reqwest::Method::GET,
    };

    let mut builder = client.request(reqwest_method, &url);

    if let Some(ref hdrs) = headers {
        for (k, v) in hdrs {
            let lower = k.to_lowercase();
            if matches!(lower.as_str(), "host" | "connection" | "transfer-encoding" | "authorization" | "x-instance-id") {
                continue;
            }
            builder = builder.header(k.as_str(), v.as_str());
        }
    }

    let body_stream = futures::stream::unfold(chunk_rx, |mut rx| async {
        rx.recv().await.map(|chunk| (Ok::<_, reqwest::Error>(Bytes::from(chunk)), rx))
    });
    builder = builder.body(reqwest::Body::wrap_stream(body_stream));

    let resp = match builder.send().await {
        Ok(r) => r,
        Err(e) => {
            tracing::error!("Proxy upload to local failed: {}", e);
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
            if lower == "content-encoding" || lower == "transfer-encoding" {
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

#[cfg(test)]
mod tests {
    use super::*;
    use axum::{routing::get, Router};
    use tokio::net::TcpListener;
    use tokio::time::{timeout, Duration};

    #[tokio::test]
    async fn proxy_request_to_local_preserves_content_length() {
        let app = Router::new().route(
            "/",
            get(|| async {
                axum::response::Response::builder()
                    .status(200)
                    .header("content-type", "text/plain")
                    .header("content-length", "5")
                    .body(axum::body::Body::from("hello"))
                    .unwrap()
            }),
        );

        let listener = TcpListener::bind("127.0.0.1:0").await.unwrap();
        let port = listener.local_addr().unwrap().port();
        let server = tokio::spawn(async move {
            axum::serve(listener, app).await.unwrap();
        });

        let (tx, mut rx) = mpsc::unbounded_channel();
        proxy_request_to_local("GET", port, "/", None, None, "req-1", &tx).await;

        let first = timeout(Duration::from_secs(2), rx.recv())
            .await
            .unwrap()
            .unwrap();

        match first {
            GatewayOutgoing::Json(frame) => {
                assert_eq!(frame.frame_type, "response_start");
                let headers = frame.headers.unwrap();
                assert_eq!(headers.get("content-length").map(String::as_str), Some("5"));
            }
            GatewayOutgoing::Binary { .. } => panic!("expected response_start frame"),
        }

        server.abort();
    }
}

use std::collections::HashMap;

use crate::gateway::frame::TunnelFrame;

pub async fn proxy_request_to_local(
    method: &str,
    local_port: u16,
    path: &str,
    headers: Option<HashMap<String, String>>,
    body: Option<String>,
) -> TunnelFrame {
    let url = format!("http://127.0.0.1:{}{}", local_port, path);

    let client = reqwest::Client::new();

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
            builder = builder.header(k.as_str(), v.as_str());
        }
    }

    if let Some(ref b) = body {
        builder = builder.body(b.clone());
    }

    match builder.send().await {
        Ok(resp) => {
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
            let resp_body = resp.text().await.unwrap_or_default();
            TunnelFrame::response(
                "",
                status,
                Some(resp_headers),
                if resp_body.is_empty() { None } else { Some(resp_body) },
            )
        }
        Err(e) => {
            tracing::error!("Proxy request to local failed: {}", e);
            TunnelFrame::error(None, 502, &format!("Local server error: {}", e))
        }
    }
}

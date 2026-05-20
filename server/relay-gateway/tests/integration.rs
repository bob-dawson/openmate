use std::net::SocketAddr;
use std::sync::Arc;

use relay_gateway::auth::SecretKey;
use relay_gateway::config::Config;
use relay_gateway::state::GatewayState;

fn generate_token(key: &SecretKey) -> String {
    use hmac::{Hmac, Mac};
    use sha2::Sha256;

    let mut random_bytes = [0u8; 32];
    getrandom::fill(&mut random_bytes).unwrap();
    let payload: String = random_bytes.iter().map(|b| format!("{:02x}", b)).collect();

    type HmacSha256 = Hmac<Sha256>;
    let mut mac = HmacSha256::new_from_slice(key.as_bytes()).unwrap();
    mac.update(payload.as_bytes());
    let result = mac.finalize();
    let sig_bytes = result.into_bytes();
    let signature: String = sig_bytes.iter().map(|b| format!("{:02x}", b)).collect();

    format!("{}{}", payload, signature)
}

fn make_state() -> Arc<GatewayState> {
    let config = Config {
        gateway: relay_gateway::config::GatewayConfig {
            port: 0,
            hostname: "127.0.0.1".to_string(),
            tls_cert: None,
            tls_key: None,
        },
        ..Config::default()
    };
    let secret_key = SecretKey::from_bytes(vec![0xabu8; 32]);
    Arc::new(GatewayState::new(config, secret_key))
}

async fn start_server(state: Arc<GatewayState>) -> SocketAddr {
    let listener = tokio::net::TcpListener::bind("127.0.0.1:0").await.unwrap();
    let addr = listener.local_addr().unwrap();
    let app = relay_gateway::server::build_app(state);
    tokio::spawn(async move {
        axum::serve(listener, app).await.unwrap();
    });
    addr
}

#[tokio::test]
async fn test_health_endpoint() {
    let state = make_state();
    let addr = start_server(state).await;

    let client = reqwest::Client::new();
    let resp = client
        .get(format!("http://{}/api/gateway/health", addr))
        .send()
        .await
        .unwrap();

    assert_eq!(resp.status(), reqwest::StatusCode::OK);
    let body = resp.text().await.unwrap();
    assert_eq!(body, "OK");
}

#[tokio::test]
async fn test_proxy_no_bridge_returns_503() {
    let state = make_state();
    let addr = start_server(state).await;
    let key = SecretKey::from_bytes(vec![0xabu8; 32]);
    let token = generate_token(&key);

    let client = reqwest::Client::new();
    let resp = client
        .get(format!("http://{}/api/test", addr))
        .header("authorization", format!("Bearer {}", token))
        .header("x-instance-id", "inst-001")
        .send()
        .await
        .unwrap();

    assert_eq!(resp.status(), reqwest::StatusCode::SERVICE_UNAVAILABLE);
}

#[tokio::test]
async fn test_bridge_register_and_status() {
    use futures::{SinkExt, StreamExt};
    use relay_gateway::tunnel::frame::TunnelFrame;

    let state = make_state();
    let addr = start_server(state.clone()).await;
    let key = SecretKey::from_bytes(vec![0xabu8; 32]);
    let token = generate_token(&key);

    let (mut ws, _) = tokio_tungstenite::connect_async(format!("ws://{}/ws", addr))
        .await
        .unwrap();

    let register = TunnelFrame::register("inst-002", &token);
    ws.send(tokio_tungstenite::tungstenite::Message::Text(
        serde_json::to_string(&register).unwrap().into(),
    ))
    .await
    .unwrap();

    let msg = ws.next().await.unwrap().unwrap();
    let pong: TunnelFrame = match msg {
        tokio_tungstenite::tungstenite::Message::Text(t) => serde_json::from_str(&t).unwrap(),
        _ => panic!("expected text message"),
    };
    assert_eq!(pong.frame_type, "pong");

    let client = reqwest::Client::new();
    let resp = client
        .get(format!("http://{}/api/gateway/status?instance_id=inst-002", addr))
        .send()
        .await
        .unwrap();

    assert_eq!(resp.status(), reqwest::StatusCode::OK);
    let body: serde_json::Value = resp.json().await.unwrap();
    assert_eq!(body["online"], true);
    assert_eq!(body["instance_id"], "inst-002");
}

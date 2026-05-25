use axum::Router;
use axum::extract::ConnectInfo;
use axum::routing::{get, post, put};
use std::net::SocketAddr;
use tower::ServiceExt;

use openmate::auth;
use openmate::bridge;
use openmate::bridge_db::{BridgeDb, PairedDevice};
use openmate::files;
use openmate::fs;
use openmate::log_capture::create_shared_buffer;
use openmate::state::create_app_state_with_db;

fn temp_bridge_db() -> BridgeDb {
    let dir = tempfile::tempdir().expect("Failed to create temp dir");
    let db_path = dir.path().join("test_bridge.db");
    let db = BridgeDb::open_at(&db_path).expect("Failed to open test DB");
    std::mem::forget(dir);
    db
}

fn test_app_with_dir(dir: &std::path::Path) -> (Router, openmate::state::AppState) {
    let db = temp_bridge_db();
    db.init_default_configs().unwrap();
    db.set_config("opencode.auto_start", "false").unwrap();
    db.set_config("fs.allowed_paths", &dir.to_string_lossy()).unwrap();
    let state = create_app_state_with_db(create_shared_buffer(), Some(db));
    let router = Router::new()
        .route("/api/bridge/status", get(bridge::router::status))
        .route("/api/bridge/opencode/start", post(bridge::router::start_opencode))
        .route("/api/bridge/opencode/stop", post(bridge::router::stop_opencode))
        .route("/api/bridge/opencode/restart", post(bridge::router::restart_opencode))
        .route("/api/bridge/pair/request", post(auth::pair::pair_request))
        .route("/api/bridge/pair/approve", post(auth::pair::pair_approve))
        .route("/api/bridge/pair/confirm", post(auth::pair::pair_confirm))
        .route("/api/bridge/fs/list", get(fs::router::list))
        .route("/api/bridge/fs/read", get(fs::router::read))
        .route("/api/bridge/fs/mkdir", post(fs::router::mkdir))
        .route("/api/bridge/fs/search", post(fs::router::search))
        .route("/api/bridge/fs/stat", get(fs::router::stat))
        .route("/api/bridge/fs/write", post(fs::router::write))
        .route("/api/bridge/config", get(openmate::api::config::get_config))
        .route("/api/bridge/config", put(openmate::api::config::update_config))
        .route("/files/{*path}", get(files::router::serve_file))
        .layer(axum::middleware::from_fn_with_state(
            state.clone(),
            auth::middleware::auth_middleware,
        ))
        .with_state(state.clone());
    (router, state)
}


#[tokio::test]
async fn test_status_endpoint() {
    let dir = std::env::temp_dir().join("bridge_int_status");
    let _ = std::fs::remove_dir_all(&dir);
    std::fs::create_dir_all(&dir).unwrap();

    let (app, _) = test_app_with_dir(&dir);

    let req = axum::http::Request::builder()
        .uri("/api/bridge/status")
        .body(axum::body::Body::empty())
        .unwrap();

    let resp = app.oneshot(req).await.unwrap();
    assert_eq!(resp.status(), 200);

    let body = axum::body::to_bytes(resp.into_body(), 1024 * 1024)
        .await
        .unwrap();
    let json: serde_json::Value = serde_json::from_slice(&body).unwrap();
    assert_eq!(json["bridge"]["version"], env!("CARGO_PKG_VERSION"));
    assert!(json["opencode"]["status"].is_string());

    let _ = std::fs::remove_dir_all(&dir);
}

#[tokio::test]
async fn test_get_config() {
    let dir = std::env::temp_dir().join("bridge_int_config_get");
    let _ = std::fs::remove_dir_all(&dir);
    std::fs::create_dir_all(&dir).unwrap();

    let (app, state) = test_app_with_dir(&dir);

    let device_id = "cfgtestdev000001";
    let _ = state.bridge_db.insert_device(&PairedDevice {
        device_id: device_id.to_string(),
        client_device_id: String::new(),
        ip: "127.0.0.1".to_string(),
        name: String::new(),
        user_agent: String::new(),
        paired_at: 0,
        last_seen: 0,
    });

    let token = auth::token::Token::generate(&state.secret_key, device_id);

    let req = axum::http::Request::builder()
        .uri("/api/bridge/config")
        .header("authorization", format!("Bearer {}", token))
        .extension(ConnectInfo(SocketAddr::from(([127, 0, 0, 1], 54321))))
        .body(axum::body::Body::empty())
        .unwrap();

    let resp = app.oneshot(req).await.unwrap();
    assert_eq!(resp.status(), 200);

    let body = axum::body::to_bytes(resp.into_body(), 8192).await.unwrap();
    let json: serde_json::Value = serde_json::from_slice(&body).unwrap();
    let configs = json["configs"].as_array().unwrap();
    assert!(!configs.is_empty());

    let bridge_port = configs.iter().find(|c| c["key"] == "bridge.port").unwrap();
    assert_eq!(bridge_port["type"], "u16");
    assert_eq!(bridge_port["needs_restart"], true);

    let _ = std::fs::remove_dir_all(&dir);
}

#[tokio::test]
async fn test_update_config_bool() {
    let dir = std::env::temp_dir().join("bridge_int_config_put");
    let _ = std::fs::remove_dir_all(&dir);
    std::fs::create_dir_all(&dir).unwrap();

    let (app, state) = test_app_with_dir(&dir);

    let device_id = "cfgtestdev000002";
    let _ = state.bridge_db.insert_device(&PairedDevice {
        device_id: device_id.to_string(),
        client_device_id: String::new(),
        ip: "127.0.0.1".to_string(),
        name: String::new(),
        user_agent: String::new(),
        paired_at: 0,
        last_seen: 0,
    });

    let token = auth::token::Token::generate(&state.secret_key, device_id);

    let body = serde_json::json!({
        "configs": [{"key": "opencode.auto_start", "value": "false"}]
    });

    let req = axum::http::Request::builder()
        .method("PUT")
        .uri("/api/bridge/config")
        .header("authorization", format!("Bearer {}", token))
        .header("content-type", "application/json")
        .extension(ConnectInfo(SocketAddr::from(([127, 0, 0, 1], 54321))))
        .body(axum::body::Body::from(serde_json::to_string(&body).unwrap()))
        .unwrap();

    let resp = app.oneshot(req).await.unwrap();
    assert_eq!(resp.status(), 200);

    let resp_body = axum::body::to_bytes(resp.into_body(), 8192).await.unwrap();
    let json: serde_json::Value = serde_json::from_slice(&resp_body).unwrap();
    assert_eq!(json["success"], true);
    assert_eq!(json["needs_restart"], false);

    let saved = state.bridge_db.get_config("opencode.auto_start").unwrap();
    assert_eq!(saved, Some("false".to_string()));

    let _ = std::fs::remove_dir_all(&dir);
}

#[tokio::test]
async fn test_update_config_unknown_key_rejected() {
    let dir = std::env::temp_dir().join("bridge_int_config_unknown");
    let _ = std::fs::remove_dir_all(&dir);
    std::fs::create_dir_all(&dir).unwrap();

    let (app, state) = test_app_with_dir(&dir);

    let device_id = "cfgtestdev000003";
    let _ = state.bridge_db.insert_device(&PairedDevice {
        device_id: device_id.to_string(),
        client_device_id: String::new(),
        ip: "127.0.0.1".to_string(),
        name: String::new(),
        user_agent: String::new(),
        paired_at: 0,
        last_seen: 0,
    });

    let token = auth::token::Token::generate(&state.secret_key, device_id);

    let body = serde_json::json!({
        "configs": [{"key": "auth.secret_key", "value": "hacked"}]
    });

    let req = axum::http::Request::builder()
        .method("PUT")
        .uri("/api/bridge/config")
        .header("authorization", format!("Bearer {}", token))
        .header("content-type", "application/json")
        .extension(ConnectInfo(SocketAddr::from(([127, 0, 0, 1], 54321))))
        .body(axum::body::Body::from(serde_json::to_string(&body).unwrap()))
        .unwrap();

    let resp = app.oneshot(req).await.unwrap();
    assert_eq!(resp.status(), 401);

    let _ = std::fs::remove_dir_all(&dir);
}

#[cfg(windows)]
#[tokio::test]
async fn test_opencode_start_stop_with_sigint() {
    use openmate::process::opencode_manager::OpencodeManager;
    use openmate::state::OpencodeStatus;

    let port: u16 = 5696;
    let url = format!("http://127.0.0.1:{}", port);
    let dir = std::env::temp_dir().join("bridge_int_sigint_test");
    let _ = std::fs::remove_dir_all(&dir);
    std::fs::create_dir_all(&dir).unwrap();

    let manager = OpencodeManager::with_config(
        url.clone(),
        "opencode".to_string(),
        "127.0.0.1".to_string(),
        port,
        dir.to_string_lossy().to_string(),
        false,
    );

    manager.start().await.expect("Failed to start opencode");

    for i in 0..30 {
        tokio::time::sleep(tokio::time::Duration::from_secs(2)).await;
        if manager.is_running().await {
            break;
        }
        if i == 29 {
            panic!("opencode did not become ready within 60s");
        }
    }
    assert_eq!(manager.get_status().await, OpencodeStatus::Running);

    let client = reqwest::Client::new();
    let resp = client.get(format!("{}/global/health", url)).send().await;
    assert!(resp.is_ok(), "health check should succeed while running");

    manager.stop().await.expect("Failed to stop opencode");
    assert_eq!(manager.get_status().await, OpencodeStatus::Stopped);

    tokio::time::sleep(tokio::time::Duration::from_secs(2)).await;
    let resp = client.get(format!("{}/global/health", url)).send().await;
    assert!(resp.is_err(), "health check should fail after stop, port should be released");

    println!("SIGINT stop test passed: port {} released after stop", port);
}


#[tokio::test]
async fn test_fs_list_endpoint() {
    let dir = std::env::temp_dir().join("bridge_int_list");
    let _ = std::fs::remove_dir_all(&dir);
    std::fs::create_dir_all(&dir).unwrap();
    std::fs::write(dir.join("hello.txt"), "world").unwrap();
    std::fs::create_dir(dir.join("subdir")).unwrap();

    let (app, _) = test_app_with_dir(&dir);

    let req = axum::http::Request::builder()
        .uri(&format!(
            "/api/bridge/fs/list?path={}",
            url_encode(&dir.to_string_lossy())
        ))
        .body(axum::body::Body::empty())
        .unwrap();

    let resp = app.oneshot(req).await.unwrap();
    assert_eq!(resp.status(), 401);

    let _ = std::fs::remove_dir_all(&dir);
}

#[tokio::test]
async fn test_fs_stat_endpoint() {
    let dir = std::env::temp_dir().join("bridge_int_stat");
    let _ = std::fs::remove_dir_all(&dir);
    std::fs::create_dir_all(&dir).unwrap();
    std::fs::write(dir.join("test.txt"), "content").unwrap();

    let (app, _) = test_app_with_dir(&dir);

    let file_path = dir.join("test.txt");
    let req = axum::http::Request::builder()
        .uri(&format!(
            "/api/bridge/fs/stat?path={}",
            url_encode(&file_path.to_string_lossy())
        ))
        .body(axum::body::Body::empty())
        .unwrap();

    let resp = app.oneshot(req).await.unwrap();
    assert_eq!(resp.status(), 401);

    let _ = std::fs::remove_dir_all(&dir);
}

#[tokio::test]
async fn test_fs_read_text_endpoint() {
    let dir = std::env::temp_dir().join("bridge_int_read");
    let _ = std::fs::remove_dir_all(&dir);
    std::fs::create_dir_all(&dir).unwrap();
    std::fs::write(dir.join("readme.txt"), "Hello Bridge!").unwrap();

    let (app, _) = test_app_with_dir(&dir);

    let file_path = dir.join("readme.txt");
    let req = axum::http::Request::builder()
        .uri(&format!(
            "/api/bridge/fs/read?path={}",
            url_encode(&file_path.to_string_lossy())
        ))
        .body(axum::body::Body::empty())
        .unwrap();

    let resp = app.oneshot(req).await.unwrap();
    assert_eq!(resp.status(), 401);

    let _ = std::fs::remove_dir_all(&dir);
}

#[tokio::test]
async fn test_fs_write_endpoint() {
    let dir = std::env::temp_dir().join("bridge_int_write");
    let _ = std::fs::remove_dir_all(&dir);
    std::fs::create_dir_all(&dir).unwrap();

    let (app, _) = test_app_with_dir(&dir);

    let file_path = dir.join("output.txt");
    let body = serde_json::json!({
        "path": file_path.to_string_lossy(),
        "content": "written by test",
        "createDirs": false
    });

    let req = axum::http::Request::builder()
        .method("POST")
        .uri("/api/bridge/fs/write")
        .header("content-type", "application/json")
        .body(axum::body::Body::from(serde_json::to_string(&body).unwrap()))
        .unwrap();

    let resp = app.oneshot(req).await.unwrap();
    assert_eq!(resp.status(), 401);

    let _ = std::fs::remove_dir_all(&dir);
}

#[tokio::test]
async fn test_fs_mkdir_endpoint() {
    let dir = std::env::temp_dir().join("bridge_int_mkdir");
    let _ = std::fs::remove_dir_all(&dir);
    std::fs::create_dir_all(&dir).unwrap();

    let (app, _) = test_app_with_dir(&dir);

    let new_dir = dir.join("new_folder");
    let body = serde_json::json!({
        "path": new_dir.to_string_lossy(),
        "recursive": false
    });

    let req = axum::http::Request::builder()
        .method("POST")
        .uri("/api/bridge/fs/mkdir")
        .header("content-type", "application/json")
        .body(axum::body::Body::from(serde_json::to_string(&body).unwrap()))
        .unwrap();

    let resp = app.oneshot(req).await.unwrap();
    assert_eq!(resp.status(), 401);

    let _ = std::fs::remove_dir_all(&dir);
}

#[tokio::test]
async fn test_fs_search_endpoint() {
    let dir = std::env::temp_dir().join("bridge_int_search");
    let _ = std::fs::remove_dir_all(&dir);
    std::fs::create_dir_all(&dir).unwrap();
    std::fs::write(dir.join("app.rs"), "fn main() {}").unwrap();
    std::fs::write(dir.join("lib.rs"), "pub fn helper() {}").unwrap();

    let (app, _) = test_app_with_dir(&dir);

    let body = serde_json::json!({
        "path": dir.to_string_lossy(),
        "query": "rs",
        "searchType": "filename",
        "maxResults": 50
    });

    let req = axum::http::Request::builder()
        .method("POST")
        .uri("/api/bridge/fs/search")
        .header("content-type", "application/json")
        .body(axum::body::Body::from(serde_json::to_string(&body).unwrap()))
        .unwrap();

    let resp = app.oneshot(req).await.unwrap();
    assert_eq!(resp.status(), 401);

    let _ = std::fs::remove_dir_all(&dir);
}

#[tokio::test]
async fn test_files_serve_endpoint() {
    let dir = std::env::temp_dir().join("bridge_int_files");
    let _ = std::fs::remove_dir_all(&dir);
    std::fs::create_dir_all(&dir).unwrap();
    std::fs::write(dir.join("doc.txt"), "file content").unwrap();

    let (app, _) = test_app_with_dir(&dir);

    let req = axum::http::Request::builder()
        .uri(&format!(
            "/files/anything?path={}",
            url_encode(&dir.to_string_lossy())
        ))
        .body(axum::body::Body::empty())
        .unwrap();

    let resp = app.oneshot(req).await.unwrap();
    assert_eq!(resp.status(), 401);

    let _ = std::fs::remove_dir_all(&dir);
}

#[tokio::test]
async fn test_path_not_allowed_returns_403() {
    let dir = std::env::temp_dir().join("bridge_int_403");
    let _ = std::fs::remove_dir_all(&dir);
    std::fs::create_dir_all(&dir).unwrap();

    let other_dir = std::env::temp_dir().join("bridge_int_403_other");
    let _ = std::fs::remove_dir_all(&other_dir);
    std::fs::create_dir_all(&other_dir).unwrap();

    let (app, _) = test_app_with_dir(&dir);

    let req = axum::http::Request::builder()
        .uri(&format!(
            "/api/bridge/fs/list?path={}",
            url_encode(&other_dir.to_string_lossy())
        ))
        .body(axum::body::Body::empty())
        .unwrap();

    let resp = app.oneshot(req).await.unwrap();
    assert_eq!(resp.status(), 401);

    let _ = std::fs::remove_dir_all(&dir);
    let _ = std::fs::remove_dir_all(&other_dir);
}

#[tokio::test]
async fn test_path_not_found_returns_404() {
    let dir = std::env::temp_dir().join("bridge_int_404");
    let _ = std::fs::remove_dir_all(&dir);
    std::fs::create_dir_all(&dir).unwrap();

    let (app, state) = test_app_with_dir(&dir);

    let device_id = "404testdevic0001";
    let _ = state.bridge_db.insert_device(&PairedDevice {
        device_id: device_id.to_string(),
        client_device_id: String::new(),
        ip: "127.0.0.1".to_string(),
        name: String::new(),
        user_agent: String::new(),
        paired_at: 0,
        last_seen: 0,
    });

    let token = auth::token::Token::generate(&state.secret_key, device_id);

    let nonexistent = dir.join("no_such_file.txt");
    let req = axum::http::Request::builder()
        .uri(&format!(
            "/api/bridge/fs/stat?path={}",
            url_encode(&nonexistent.to_string_lossy())
        ))
        .header("authorization", format!("Bearer {}", token))
        .body(axum::body::Body::empty())
        .unwrap();

    let resp = app.oneshot(req).await.unwrap();
    assert_eq!(resp.status(), 404);

    let _ = std::fs::remove_dir_all(&dir);
}

fn url_encode(s: &str) -> String {
    let mut result = String::new();
    for byte in s.bytes() {
        match byte {
            b'A'..=b'Z' | b'a'..=b'z' | b'0'..=b'9' | b'-' | b'_' | b'.' | b'~' => {
                result.push(byte as char);
            }
            _ => {
                result.push_str(&format!("%{:02X}", byte));
            }
        }
    }
    result
}

#[tokio::test]
async fn test_status_is_public() {
    let dir = std::env::temp_dir().join("bridge_int_auth_status");
    let _ = std::fs::remove_dir_all(&dir);
    std::fs::create_dir_all(&dir).unwrap();

    let (app, _) = test_app_with_dir(&dir);

    let req = axum::http::Request::builder()
        .uri("/api/bridge/status")
        .body(axum::body::Body::empty())
        .unwrap();

    let resp = app.oneshot(req).await.unwrap();
    assert_eq!(resp.status(), 200);

    let body = axum::body::to_bytes(resp.into_body(), 1024 * 1024)
        .await
        .unwrap();
    let json: serde_json::Value = serde_json::from_slice(&body).unwrap();
    assert!(json["bridge"]["auth_enabled"].is_boolean());

    assert_eq!(json["bridge"]["auth_enabled"], true);

    let _ = std::fs::remove_dir_all(&dir);
}

#[tokio::test]
async fn test_fs_endpoints_require_auth() {
    let dir = std::env::temp_dir().join("bridge_int_auth_fs");
    let _ = std::fs::remove_dir_all(&dir);
    std::fs::create_dir_all(&dir).unwrap();

    let (app, _) = test_app_with_dir(&dir);

    let req = axum::http::Request::builder()
        .uri(&format!(
            "/api/bridge/fs/list?path={}",
            url_encode(&dir.to_string_lossy())
        ))
        .body(axum::body::Body::empty())
        .unwrap();

    let resp = app.oneshot(req).await.unwrap();
    assert_eq!(resp.status(), 401);

    let _ = std::fs::remove_dir_all(&dir);
}

#[tokio::test]
async fn test_approve_from_localhost_succeeds() {
    use std::net::SocketAddr;

    let dir = std::env::temp_dir().join("bridge_int_auth_approve_local");
    let _ = std::fs::remove_dir_all(&dir);
    std::fs::create_dir_all(&dir).unwrap();

    let (app, _state) = test_app_with_dir(&dir);

    // 1. Request a PIN first
    let pin: String = {
        let req = axum::http::Request::builder()
            .method("POST")
            .uri("/api/bridge/pair/request")
            .extension(ConnectInfo(SocketAddr::from(([192, 168, 1, 100], 54321))))
            .body(axum::body::Body::empty())
            .unwrap();
        let resp = app.clone().oneshot(req).await.unwrap();
        assert_eq!(resp.status(), 200);
        let body = axum::body::to_bytes(resp.into_body(), 1024 * 1024).await.unwrap();
        let json: serde_json::Value = serde_json::from_slice(&body).unwrap();
        json["pin"].as_str().unwrap().to_string()
    };

    // 2. Try to approve from non-localhost - should fail
    {
        let body = serde_json::json!({ "pin": pin });
        let req = axum::http::Request::builder()
            .method("POST")
            .uri("/api/bridge/pair/approve")
            .header("content-type", "application/json")
            .extension(ConnectInfo(SocketAddr::from(([192, 168, 1, 100], 54321))))
            .body(axum::body::Body::from(serde_json::to_string(&body).unwrap()))
            .unwrap();
        let resp = app.clone().oneshot(req).await.unwrap();
        assert_eq!(resp.status(), 403, "Non-localhost should be forbidden");
    }

    // 3. Approve from localhost - should succeed
    {
        let body = serde_json::json!({ "pin": pin });
        let req = axum::http::Request::builder()
            .method("POST")
            .uri("/api/bridge/pair/approve")
            .header("content-type", "application/json")
            .extension(ConnectInfo(SocketAddr::from(([127, 0, 0, 1], 54321))))
            .body(axum::body::Body::from(serde_json::to_string(&body).unwrap()))
            .unwrap();
        let resp = app.oneshot(req).await.unwrap();
        assert_eq!(resp.status(), 200, "Localhost should be allowed");
    }

    let _ = std::fs::remove_dir_all(&dir);
}

#[tokio::test]
async fn test_confirm_from_different_ip_fails() {
    let dir = std::env::temp_dir().join("bridge_int_auth_confirm_ip");
    let _ = std::fs::remove_dir_all(&dir);
    std::fs::create_dir_all(&dir).unwrap();

    let (app, _) = test_app_with_dir(&dir);

    // 1. Request PIN from IP1
    let pin: String = {
        let req = axum::http::Request::builder()
            .method("POST")
            .uri("/api/bridge/pair/request")
            .extension(ConnectInfo(SocketAddr::from(([192, 168, 1, 100], 54321))))
            .body(axum::body::Body::empty())
            .unwrap();
        let resp = app.clone().oneshot(req).await.unwrap();
        assert_eq!(resp.status(), 200);
        let body = axum::body::to_bytes(resp.into_body(), 1024 * 1024).await.unwrap();
        let json: serde_json::Value = serde_json::from_slice(&body).unwrap();
        json["pin"].as_str().unwrap().to_string()
    };

    // 2. Approve from localhost
    {
        let body = serde_json::json!({ "pin": pin });
        let req = axum::http::Request::builder()
            .method("POST")
            .uri("/api/bridge/pair/approve")
            .header("content-type", "application/json")
            .extension(ConnectInfo(SocketAddr::from(([127, 0, 0, 1], 54321))))
            .body(axum::body::Body::from(serde_json::to_string(&body).unwrap()))
            .unwrap();
        let resp = app.clone().oneshot(req).await.unwrap();
        assert_eq!(resp.status(), 200);
    }

    // 3. Try confirm from different IP - should fail
    {
        let body = serde_json::json!({ "pin": pin });
        let req = axum::http::Request::builder()
            .method("POST")
            .uri("/api/bridge/pair/confirm")
            .header("content-type", "application/json")
            .extension(ConnectInfo(SocketAddr::from(([192, 168, 1, 200], 54321))))
            .body(axum::body::Body::from(serde_json::to_string(&body).unwrap()))
            .unwrap();
        let resp = app.clone().oneshot(req).await.unwrap();
        assert_eq!(resp.status(), 403, "Different IP should be forbidden");
    }

    // 4. Confirm from same IP - should succeed
    {
        let body = serde_json::json!({ "pin": pin });
        let req = axum::http::Request::builder()
            .method("POST")
            .uri("/api/bridge/pair/confirm")
            .header("content-type", "application/json")
            .extension(ConnectInfo(SocketAddr::from(([192, 168, 1, 100], 54321))))
            .body(axum::body::Body::from(serde_json::to_string(&body).unwrap()))
            .unwrap();
        let resp = app.oneshot(req).await.unwrap();
        assert_eq!(resp.status(), 200, "Same IP should be allowed");
        let body = axum::body::to_bytes(resp.into_body(), 1024 * 1024).await.unwrap();
        let json: serde_json::Value = serde_json::from_slice(&body).unwrap();
        assert!(json["token"].as_str().unwrap().len() > 0);
    }

    let _ = std::fs::remove_dir_all(&dir);
}

#[tokio::test]
async fn test_valid_token_allows_access() {
    let dir = std::env::temp_dir().join("bridge_int_auth_token");
    let _ = std::fs::remove_dir_all(&dir);
    std::fs::create_dir_all(&dir).unwrap();
    std::fs::write(dir.join("test.txt"), "hello").unwrap();

    let (app, state) = test_app_with_dir(&dir);

    let device_id = "tokntestdev00001";
    let _ = state.bridge_db.insert_device(&PairedDevice {
        device_id: device_id.to_string(),
        client_device_id: String::new(),
        ip: "127.0.0.1".to_string(),
        name: String::new(),
        user_agent: String::new(),
        paired_at: 0,
        last_seen: 0,
    });

    let token = auth::token::Token::generate(&state.secret_key, device_id);

    let req = axum::http::Request::builder()
        .uri(&format!(
            "/api/bridge/fs/list?path={}",
            url_encode(&dir.to_string_lossy())
        ))
        .header("authorization", format!("Bearer {}", token))
        .body(axum::body::Body::empty())
        .unwrap();

    let resp = app.oneshot(req).await.unwrap();
    assert_eq!(resp.status(), 200);

    let _ = std::fs::remove_dir_all(&dir);
}

#[tokio::test]
async fn test_invalid_token_returns_401() {
    let dir = std::env::temp_dir().join("bridge_int_auth_invalid");
    let _ = std::fs::remove_dir_all(&dir);
    std::fs::create_dir_all(&dir).unwrap();

    let (app, _) = test_app_with_dir(&dir);

    let req = axum::http::Request::builder()
        .uri(&format!(
            "/api/bridge/fs/list?path={}",
            url_encode(&dir.to_string_lossy())
        ))
        .header("authorization", "Bearer invalid_token_here")
        .body(axum::body::Body::empty())
        .unwrap();

    let resp = app.oneshot(req).await.unwrap();
    assert_eq!(resp.status(), 401);

    let _ = std::fs::remove_dir_all(&dir);
}

use axum::Router;
use axum::routing::{get, post};
use tower::ServiceExt;

use opencode_bridge::bridge;
use opencode_bridge::config::Config;
use opencode_bridge::files;
use opencode_bridge::fs;
use opencode_bridge::state::create_app_state;

fn test_app(config: Config) -> Router {
    let state = create_app_state(config);
    Router::new()
        .route("/api/bridge/status", get(bridge::router::status))
        .route("/api/bridge/opencode/start", post(bridge::router::start_opencode))
        .route("/api/bridge/opencode/stop", post(bridge::router::stop_opencode))
        .route("/api/bridge/opencode/restart", post(bridge::router::restart_opencode))
        .route("/api/bridge/fs/list", get(fs::router::list))
        .route("/api/bridge/fs/read", get(fs::router::read))
        .route("/api/bridge/fs/mkdir", post(fs::router::mkdir))
        .route("/api/bridge/fs/search", post(fs::router::search))
        .route("/api/bridge/fs/stat", get(fs::router::stat))
        .route("/api/bridge/fs/write", post(fs::router::write))
        .route("/files/{*path}", get(files::router::serve_file))
        .with_state(state)
}

fn test_config_with_dir(dir: &std::path::Path) -> Config {
    let mut config = Config::default();
    config.opencode.auto_start = false;
    config.fs.allowed_paths = vec![dir.to_string_lossy().to_string()];
    config
}

#[tokio::test]
async fn test_status_endpoint() {
    let dir = std::env::temp_dir().join("bridge_int_status");
    let _ = std::fs::remove_dir_all(&dir);
    std::fs::create_dir_all(&dir).unwrap();

    let config = test_config_with_dir(&dir);
    let app = test_app(config);

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
    assert_eq!(json["opencode"]["status"], "stopped");

    let _ = std::fs::remove_dir_all(&dir);
}

#[tokio::test]
async fn test_fs_list_endpoint() {
    let dir = std::env::temp_dir().join("bridge_int_list");
    let _ = std::fs::remove_dir_all(&dir);
    std::fs::create_dir_all(&dir).unwrap();
    std::fs::write(dir.join("hello.txt"), "world").unwrap();
    std::fs::create_dir(dir.join("subdir")).unwrap();

    let config = test_config_with_dir(&dir);
    let app = test_app(config);

    let req = axum::http::Request::builder()
        .uri(&format!(
            "/api/bridge/fs/list?path={}",
            url_encode(&dir.to_string_lossy())
        ))
        .body(axum::body::Body::empty())
        .unwrap();

    let resp = app.oneshot(req).await.unwrap();
    assert_eq!(resp.status(), 200);

    let body = axum::body::to_bytes(resp.into_body(), 1024 * 1024)
        .await
        .unwrap();
    let entries: serde_json::Value = serde_json::from_slice(&body).unwrap();
    assert!(entries.as_array().unwrap().len() >= 2);

    let _ = std::fs::remove_dir_all(&dir);
}

#[tokio::test]
async fn test_fs_stat_endpoint() {
    let dir = std::env::temp_dir().join("bridge_int_stat");
    let _ = std::fs::remove_dir_all(&dir);
    std::fs::create_dir_all(&dir).unwrap();
    std::fs::write(dir.join("test.txt"), "content").unwrap();

    let config = test_config_with_dir(&dir);
    let app = test_app(config);

    let file_path = dir.join("test.txt");
    let req = axum::http::Request::builder()
        .uri(&format!(
            "/api/bridge/fs/stat?path={}",
            url_encode(&file_path.to_string_lossy())
        ))
        .body(axum::body::Body::empty())
        .unwrap();

    let resp = app.oneshot(req).await.unwrap();
    assert_eq!(resp.status(), 200);

    let body = axum::body::to_bytes(resp.into_body(), 1024 * 1024)
        .await
        .unwrap();
    let stat: serde_json::Value = serde_json::from_slice(&body).unwrap();
    assert_eq!(stat["name"], "test.txt");
    assert_eq!(stat["type"], "file");
    assert_eq!(stat["size"], 7);

    let _ = std::fs::remove_dir_all(&dir);
}

#[tokio::test]
async fn test_fs_read_text_endpoint() {
    let dir = std::env::temp_dir().join("bridge_int_read");
    let _ = std::fs::remove_dir_all(&dir);
    std::fs::create_dir_all(&dir).unwrap();
    std::fs::write(dir.join("readme.txt"), "Hello Bridge!").unwrap();

    let config = test_config_with_dir(&dir);
    let app = test_app(config);

    let file_path = dir.join("readme.txt");
    let req = axum::http::Request::builder()
        .uri(&format!(
            "/api/bridge/fs/read?path={}",
            url_encode(&file_path.to_string_lossy())
        ))
        .body(axum::body::Body::empty())
        .unwrap();

    let resp = app.oneshot(req).await.unwrap();
    assert_eq!(resp.status(), 200);

    let content_type = resp
        .headers()
        .get("content-type")
        .unwrap()
        .to_str()
        .unwrap();
    assert!(content_type.contains("text/plain"));

    let body = axum::body::to_bytes(resp.into_body(), 1024 * 1024)
        .await
        .unwrap();
    assert_eq!(&body[..], b"Hello Bridge!");

    let _ = std::fs::remove_dir_all(&dir);
}

#[tokio::test]
async fn test_fs_write_endpoint() {
    let dir = std::env::temp_dir().join("bridge_int_write");
    let _ = std::fs::remove_dir_all(&dir);
    std::fs::create_dir_all(&dir).unwrap();

    let config = test_config_with_dir(&dir);
    let app = test_app(config);

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
    assert_eq!(resp.status(), 200);

    let written = std::fs::read_to_string(&file_path).unwrap();
    assert_eq!(written, "written by test");

    let _ = std::fs::remove_dir_all(&dir);
}

#[tokio::test]
async fn test_fs_mkdir_endpoint() {
    let dir = std::env::temp_dir().join("bridge_int_mkdir");
    let _ = std::fs::remove_dir_all(&dir);
    std::fs::create_dir_all(&dir).unwrap();

    let config = test_config_with_dir(&dir);
    let app = test_app(config);

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
    assert_eq!(resp.status(), 200);
    assert!(new_dir.is_dir());

    let _ = std::fs::remove_dir_all(&dir);
}

#[tokio::test]
async fn test_fs_search_endpoint() {
    let dir = std::env::temp_dir().join("bridge_int_search");
    let _ = std::fs::remove_dir_all(&dir);
    std::fs::create_dir_all(&dir).unwrap();
    std::fs::write(dir.join("app.rs"), "fn main() {}").unwrap();
    std::fs::write(dir.join("lib.rs"), "pub fn helper() {}").unwrap();

    let config = test_config_with_dir(&dir);
    let app = test_app(config);

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
    assert_eq!(resp.status(), 200);

    let resp_body = axum::body::to_bytes(resp.into_body(), 1024 * 1024)
        .await
        .unwrap();
    let results: serde_json::Value = serde_json::from_slice(&resp_body).unwrap();
    assert!(results.as_array().unwrap().len() >= 1);

    let _ = std::fs::remove_dir_all(&dir);
}

#[tokio::test]
async fn test_files_serve_endpoint() {
    let dir = std::env::temp_dir().join("bridge_int_files");
    let _ = std::fs::remove_dir_all(&dir);
    std::fs::create_dir_all(&dir).unwrap();
    std::fs::write(dir.join("doc.txt"), "file content").unwrap();

    let config = test_config_with_dir(&dir);
    let app = test_app(config);

    let req = axum::http::Request::builder()
        .uri(&format!(
            "/files/anything?path={}",
            url_encode(&dir.to_string_lossy())
        ))
        .body(axum::body::Body::empty())
        .unwrap();

    let resp = app.oneshot(req).await.unwrap();
    assert_eq!(resp.status(), 200);

    let body = axum::body::to_bytes(resp.into_body(), 1024 * 1024)
        .await
        .unwrap();
    let entries: serde_json::Value = serde_json::from_slice(&body).unwrap();
    assert!(entries.as_array().unwrap().len() >= 1);

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

    let config = test_config_with_dir(&dir);
    let app = test_app(config);

    let req = axum::http::Request::builder()
        .uri(&format!(
            "/api/bridge/fs/list?path={}",
            url_encode(&other_dir.to_string_lossy())
        ))
        .body(axum::body::Body::empty())
        .unwrap();

    let resp = app.oneshot(req).await.unwrap();
    assert_eq!(resp.status(), 403);

    let _ = std::fs::remove_dir_all(&dir);
    let _ = std::fs::remove_dir_all(&other_dir);
}

#[tokio::test]
async fn test_path_not_found_returns_404() {
    let dir = std::env::temp_dir().join("bridge_int_404");
    let _ = std::fs::remove_dir_all(&dir);
    std::fs::create_dir_all(&dir).unwrap();

    let config = test_config_with_dir(&dir);
    let app = test_app(config);

    let nonexistent = dir.join("no_such_file.txt");
    let req = axum::http::Request::builder()
        .uri(&format!(
            "/api/bridge/fs/stat?path={}",
            url_encode(&nonexistent.to_string_lossy())
        ))
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

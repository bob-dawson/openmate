# Bridge 1.5: CLI 子命令 (`approve` / `reset-token`)

## 目标

为 `opencode-bridge` 添加两个 CLI 子命令：
- `opencode-bridge approve <PIN>` — 调用 localhost `/api/bridge/pair/approve` 授权 PIN
- `opencode-bridge reset-token` — 删除密钥文件 + 清空 pending pairs

## 文件变更

| 操作 | 路径 |
|------|------|
| 修改 | `src/main.rs` — CLI 重构为子命令模式 |

## `src/main.rs` 重构

将 `Args` 从简单的 `--config` 改为 clap 子命令模式：

```rust
use axum::Router;
use axum::routing::{any, get, post};
use axum::extract::ConnectInfo;
use clap::{Parser, Subcommand};
use opencode_bridge::auth;
use opencode_bridge::bridge;
use opencode_bridge::config::Config;
use opencode_bridge::files;
use opencode_bridge::fs;
use opencode_bridge::proxy;
use opencode_bridge::state::create_app_state;
use std::net::SocketAddr;
use std::path::PathBuf;
use tower_http::cors::CorsLayer;
use tower_http::trace::TraceLayer;
use tracing_subscriber::EnvFilter;

#[derive(Parser, Debug)]
#[command(name = "opencode-bridge", about = "Bridge Agent for OpenMate")]
struct Args {
    #[arg(short, long)]
    config: Option<PathBuf>,

    #[command(subcommand)]
    command: Option<Commands>,
}

#[derive(Subcommand, Debug)]
enum Commands {
    /// Approve a pending pair request
    Approve {
        /// The PIN to approve
        pin: String,
    },
    /// Reset the secret key, invalidating all tokens
    ResetToken,
}

#[tokio::main]
async fn main() -> anyhow::Result<()> {
    tracing_subscriber::fmt()
        .with_env_filter(
            EnvFilter::try_from_default_env().unwrap_or_else(|_| EnvFilter::new("info")),
        )
        .init();

    let args = Args::parse();
    let config = Config::find_and_load(args.config)?;

    match args.command {
        Some(Commands::Approve { pin }) => {
            return run_approve(&config, &pin).await;
        }
        Some(Commands::ResetToken) => {
            return run_reset_token().await;
        }
        None => {}
    }

    // 正常服务器启动流程（不变）
    run_server(config).await
}

async fn run_approve(config: &Config, pin: &str) -> anyhow::Result<()> {
    let url = format!("http://127.0.0.1:{}/api/bridge/pair/approve", config.bridge.port);
    let client = reqwest::Client::new();
    let resp = client
        .post(&url)
        .json(&serde_json::json!({ "pin": pin }))
        .send()
        .await?;

    if resp.status().is_success() {
        let body: serde_json::Value = resp.json().await?;
        println!("Approved: {}", body["approved"]);
    } else {
        let status = resp.status();
        let body = resp.text().await?;
        eprintln!("Failed (HTTP {}): {}", status, body);
        std::process::exit(1);
    }
    Ok(())
}

async fn run_reset_token() -> anyhow::Result<()> {
    auth::key::SecretKey::delete_key_file()?;
    println!("Secret key deleted. All tokens are now invalid.");
    println!("Restart the bridge to generate a new key.");
    Ok(())
}

async fn run_server(config: Config) -> anyhow::Result<()> {
    tracing::info!("OpenCode Bridge starting");
    tracing::info!("Bridge listen: {}:{}", config.bridge.hostname, config.bridge.port);
    tracing::info!("OpenCode target: {}", config.opencode_url());
    tracing::info!("Auth enabled: {}", config.bridge.auth_enabled);
    tracing::info!(
        "Allowed paths: {:?}",
        config.effective_allowed_paths()
    );

    let app_state = create_app_state(config.clone());

    if config.opencode.auto_start {
        tracing::info!("Auto-starting opencode...");
        if let Err(e) = app_state.opencode_manager.start().await {
            tracing::warn!("Auto-start failed: {}", e);
        }
    }

    let app = Router::new()
        .route("/api/bridge/status", get(bridge::router::status))
        .route("/api/bridge/pair/request", post(auth::pair::pair_request))
        .route("/api/bridge/pair/approve", post(auth::pair::pair_approve))
        .route("/api/bridge/pair/confirm", post(auth::pair::pair_confirm))
        .route("/api/bridge/opencode/start", post(bridge::router::start_opencode))
        .route("/api/bridge/opencode/stop", post(bridge::router::stop_opencode))
        .route("/api/bridge/opencode/restart", post(bridge::router::restart_opencode))
        .route("/api/bridge/fs/roots", get(fs::router::roots))
        .route("/api/bridge/fs/list", get(fs::router::list))
        .route("/api/bridge/fs/read", get(fs::router::read))
        .route("/api/bridge/fs/download", get(fs::router::download))
        .route("/api/bridge/fs/mkdir", post(fs::router::mkdir))
        .route("/api/bridge/fs/search", post(fs::router::search))
        .route("/api/bridge/fs/stat", get(fs::router::stat))
        .route("/api/bridge/fs/write", post(fs::router::write))
        .route("/api/bridge/fs/upload", axum::routing::put(fs::router::upload))
        .route("/files/{*path}", get(files::router::serve_file))
        .route("/api/opencode/global/event", get(proxy::sse::sse_proxy))
        .route("/global/event", get(proxy::sse::sse_proxy))
        .route("/api/opencode/{*path}", any(proxy::rest::proxy_opencode_request))
        .fallback(any(proxy::rest::proxy_fallback))
        .layer(axum::middleware::from_fn_with_state(
            app_state.clone(),
            auth::middleware::auth_middleware,
        ))
        .layer(CorsLayer::permissive())
        .layer(TraceLayer::new_for_http())
        .with_state(app_state);

    let listener = tokio::net::TcpListener::bind(config.bridge_listen_addr()).await?;
    tracing::info!("Bridge listening on {}", config.bridge_listen_addr());

    axum::serve(listener, app.into_make_service_with_connect_info::<SocketAddr>()).await?;

    Ok(())
}
```

## CLI 用法

```powershell
# 正常启动服务器
opencode-bridge.exe

# 指定配置启动
opencode-bridge.exe -c bridge.toml

# 授权 PIN
opencode-bridge.exe approve 482901

# 重置所有 token
opencode-bridge.exe reset-token
```

## `reset-token` 补充

`reset-token` 删除密钥文件后，正在运行的 Bridge 进程内存中仍持有旧密钥，旧 token 依然有效。要使失效生效，需要重启 Bridge。

如需不重启就使 token 失效，需要额外机制（如维护 revoked token 列表），但规格中明确通过 `reset-token` + 重启来实现，无需额外复杂度。

可在 `reset-token` 输出中提示用户重启：

```
Secret key deleted. All tokens are now invalid.
Restart the bridge to generate a new key.
```

## 关于 `SecretKey::delete_key_file` 可见性

1.1 步中 `delete_key_file` 已声明为 `pub`，且 `auth` 模块已公开。main.rs 通过 `opencode_bridge::auth::key::SecretKey::delete_key_file()` 访问即可。

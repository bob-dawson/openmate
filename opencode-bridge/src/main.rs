use axum::Router;
use axum::routing::{any, get, post};
use clap::{Parser, Subcommand};
use openmate::auth;
use openmate::bridge;
use openmate::config::Config;
use openmate::files;
use openmate::fs;
use openmate::proxy;
use openmate::state::create_app_state;
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
    Approve {
        pin: String,
    },
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

    match args.command {
        Some(Commands::Approve { pin }) => {
            return run_approve(&pin).await;
        }
        Some(Commands::ResetToken) => {
            return run_reset_token().await;
        }
        None => {}
    }

    let config = Config::find_and_load(args.config)?;

    tracing::info!("OpenCode Bridge starting");
    tracing::info!("Bridge listen: {}:{}", config.bridge.hostname, config.bridge.port);
    tracing::info!("OpenCode target: {}", config.opencode_url());
    tracing::info!(
        "Allowed paths: {:?}",
        config.effective_allowed_paths()
    );
    tracing::info!("Auth enabled: {}", config.bridge.auth_enabled);

    let app_state = create_app_state(config.clone());

    if config.opencode.auto_start {
        tracing::info!("Auto-starting opencode...");
        if let Err(e) = app_state.opencode_manager.start().await {
            tracing::warn!("Auto-start failed: {}", e);
        }
    }

    let app = Router::new()
        .route("/api/bridge/status", get(bridge::router::status))
        .route("/api/bridge/opencode/start", post(bridge::router::start_opencode))
        .route("/api/bridge/opencode/stop", post(bridge::router::stop_opencode))
        .route("/api/bridge/opencode/restart", post(bridge::router::restart_opencode))
        .route("/api/bridge/pair/request", post(auth::pair::pair_request))
        .route("/api/bridge/pair/approve", post(auth::pair::pair_approve))
        .route("/api/bridge/pair/confirm", post(auth::pair::pair_confirm))
        .route("/api/bridge/fs/roots", get(fs::router::roots))
        .route("/api/bridge/fs/list", get(fs::router::list))
        .route("/api/bridge/fs/read", get(fs::router::read))
        .route("/api/bridge/fs/download", get(fs::router::download))
        .route("/api/bridge/fs/mkdir", post(fs::router::mkdir))
        .route("/api/bridge/fs/search", post(fs::router::search))
        .route("/api/bridge/fs/stat", get(fs::router::stat))
        .route("/api/bridge/fs/write", post(fs::router::write))
        .route("/api/bridge/fs/upload", axum::routing::put(fs::router::upload).layer(axum::extract::DefaultBodyLimit::max(100 * 1024 * 1024)))
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

    axum::serve(
        listener,
        app.into_make_service_with_connect_info::<SocketAddr>(),
    )
    .await?;

    Ok(())
}

async fn run_approve(pin: &str) -> anyhow::Result<()> {
    let config = Config::find_and_load(None)?;
    let bridge_url = format!("http://127.0.0.1:{}", config.bridge.port);

    let client = reqwest::Client::new();
    let resp = client
        .post(format!("{}/api/bridge/pair/approve", bridge_url))
        .json(&serde_json::json!({ "pin": pin }))
        .send()
        .await?;

    if resp.status().is_success() {
        tracing::info!("PIN {} approved successfully", pin);
    } else {
        let status = resp.status();
        let body = resp.text().await.unwrap_or_default();
        anyhow::bail!("Failed to approve PIN: {} - {}", status, body);
    }

    Ok(())
}

async fn run_reset_token() -> anyhow::Result<()> {
    auth::key::SecretKey::delete_key_file()?;
    tracing::info!("Secret key deleted. All tokens are now invalid. A new key will be generated on next start.");
    Ok(())
}

use axum::Router;
use axum::routing::{any, get, post};
use clap::Parser;
use opencode_bridge::bridge;
use opencode_bridge::config::Config;
use opencode_bridge::files;
use opencode_bridge::fs;
use opencode_bridge::proxy;
use opencode_bridge::state::create_app_state;
use std::path::PathBuf;
use tower_http::cors::CorsLayer;
use tower_http::trace::TraceLayer;
use tracing_subscriber::EnvFilter;

#[derive(Parser, Debug)]
#[command(name = "opencode-bridge", about = "Bridge Agent for OpenMate")]
struct Args {
    #[arg(short, long)]
    config: Option<PathBuf>,
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

    tracing::info!("OpenCode Bridge starting");
    tracing::info!("Bridge listen: {}:{}", config.bridge.hostname, config.bridge.port);
    tracing::info!("OpenCode target: {}", config.opencode_url());
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
        .route("/files/{*path}", get(files::router::serve_file))
        .route("/api/opencode/global/event", get(proxy::sse::sse_proxy))
        .route("/global/event", get(proxy::sse::sse_proxy))
        .route("/api/opencode/{*path}", any(proxy::rest::proxy_opencode_request))
        .fallback(any(proxy::rest::proxy_fallback))
        .layer(CorsLayer::permissive())
        .layer(TraceLayer::new_for_http())
        .with_state(app_state);

    let listener = tokio::net::TcpListener::bind(config.bridge_listen_addr()).await?;
    tracing::info!("Bridge listening on {}", config.bridge_listen_addr());

    axum::serve(listener, app).await?;

    Ok(())
}

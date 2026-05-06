use axum::Router;
use axum::routing::{any, get, post};
use std::net::SocketAddr;
use std::sync::Arc;
use tokio::sync::Notify;
use tower_http::cors::CorsLayer;
use tower_http::trace::TraceLayer;

use crate::auth;
use crate::bridge;
use crate::config::Config;
use crate::files;
use crate::fs;
use crate::proxy;
use crate::state::create_app_state;

pub async fn run_server(
    config: Config,
    shutdown_notify: Option<Arc<Notify>>,
) -> anyhow::Result<()> {
    tracing::info!("OpenCode Bridge starting");
    tracing::info!(
        "Bridge listen: {}:{}",
        config.bridge.hostname,
        config.bridge.port
    );
    tracing::info!("OpenCode target: {}", config.opencode_url());
    tracing::info!("Allowed paths: {:?}", config.effective_allowed_paths());
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
        .route(
            "/api/bridge/opencode/start",
            post(bridge::router::start_opencode),
        )
        .route(
            "/api/bridge/opencode/stop",
            post(bridge::router::stop_opencode),
        )
        .route(
            "/api/bridge/opencode/restart",
            post(bridge::router::restart_opencode),
        )
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
        .route(
            "/api/bridge/fs/upload",
            axum::routing::put(fs::router::upload)
                .layer(axum::extract::DefaultBodyLimit::max(100 * 1024 * 1024)),
        )
        .route("/files/{*path}", get(files::router::serve_file))
        .route(
            "/api/opencode/global/event",
            get(proxy::sse::sse_proxy),
        )
        .route("/global/event", get(proxy::sse::sse_proxy))
        .route(
            "/api/opencode/{*path}",
            any(proxy::rest::proxy_opencode_request),
        )
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

    let server = axum::serve(
        listener,
        app.into_make_service_with_connect_info::<SocketAddr>(),
    );

    if let Some(notify) = shutdown_notify {
        tokio::select! {
            result = server => result?,
            _ = notify.notified() => {
                tracing::info!("Shutdown signal received, stopping server");
            }
        }
    } else {
        server.await?;
    }

    Ok(())
}

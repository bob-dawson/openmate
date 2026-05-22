use axum::Router;
use axum::routing::{any, get, post};
use std::net::SocketAddr;
use std::sync::Arc;
use std::time::Duration;
use tokio::sync::Notify;
use tower_http::cors::CorsLayer;
use tower_http::trace::TraceLayer;

use crate::auth;
use crate::bridge;
use crate::events;
use crate::files;
use crate::fs;
use crate::proxy;
use crate::log_capture::SharedLogBuffer;
use crate::state::create_app_state;
use crate::sync;

pub async fn run_server(
    log_buffer: SharedLogBuffer,
    shutdown_notify: Option<Arc<Notify>>,
) -> anyhow::Result<()> {
    tracing::info!("OpenCode Bridge starting");

    let app_state = create_app_state(log_buffer);
    let config = &app_state.config;

    tracing::info!(
        "Bridge listen: {}:{}",
        config.bridge.hostname,
        config.bridge.port
    );
    tracing::info!("OpenCode target: {}", config.opencode_url());
    tracing::info!("Allowed paths: {:?}", config.effective_allowed_paths());
    tracing::info!("Auth enabled: {}", config.bridge.auth_enabled);

    {
        let state = app_state.clone();
        tokio::spawn(async move {
            loop {
                match state.sync_db.ensure_indexes() {
                    Ok(()) => {
                        tracing::info!("Sync indexes created successfully");
                        break;
                    }
                    Err(e) => {
                        tracing::warn!("Failed to create sync indexes (will retry in 5min): {}", e);
                        tokio::time::sleep(Duration::from_secs(300)).await;
                    }
                }
            }
        });
    }

    if app_state.opencode_manager.check_health().await {
        tracing::info!("opencode is already running, restarting with OPENCODE_EXPERIMENTAL=true");
        app_state.opencode_manager.stop().await.ok();
        tokio::time::sleep(tokio::time::Duration::from_secs(2)).await;
        if let Err(e) = app_state.opencode_manager.start().await {
            tracing::warn!("Restart failed: {}", e);
        }
    } else if config.opencode.auto_start {
        tracing::info!("Auto-starting opencode...");
        if let Err(e) = app_state.opencode_manager.start().await {
            tracing::warn!("Auto-start failed: {}", e);
        }
    }

    let listen_addr = config.bridge_listen_addr();
    let configured_port = config.bridge.port;
    let gateway_url = config.gateway.url.clone();
    let gateway_auto_connect = config.gateway.auto_connect;
    let gateway_instance_id = config.gateway.instance_id.clone();

    let app = Router::new()
        .route("/api/bridge/sync/sessions", get(sync::router::sessions))
        .route("/api/bridge/sync/session/{sessionID}/init", get(sync::router::init))
        .route("/api/bridge/sync/session/{sessionID}/events", get(sync::router::events))
        .route("/api/bridge/sync/session/{sessionID}/message/{messageID}/full", get(sync::router::full))
        .route("/api/bridge/sync/session/{sessionID}/resolve-message-id", get(sync::router::resolve_message_id))
        .route("/api/bridge/sync/session/{sessionID}/resolve-evt-id", get(sync::router::resolve_evt_id))
        .route("/api/bridge/events", get(events::router::events_sse))
        .route("/api/bridge/sync/events", get(sync::sse::sync_sse))
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
        .route(
            "/api/bridge/opencode/version",
            get(bridge::router::opencode_version),
        )
        .route(
            "/api/bridge/opencode/latest-version",
            get(bridge::router::opencode_latest_version),
        )
        .route(
            "/api/bridge/opencode/upgrade-status",
            get(bridge::router::opencode_upgrade_status),
        )
        .route(
            "/api/bridge/opencode/upgrade",
            post(bridge::router::upgrade_opencode),
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
        .route("/api/bridge/fs/delete", post(fs::router::delete))
        .route("/api/bridge/fs/rename", post(fs::router::rename))
        .merge(crate::api::routes())
        .merge(crate::ui::routes())
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
        .with_state(app_state.clone());

    let listener = match tokio::net::TcpListener::bind(&listen_addr).await {
        Ok(l) => l,
        Err(e) if e.kind() == std::io::ErrorKind::AddrInUse => {
            tracing::warn!(
                "Port {} is already in use, falling back to a random port",
                configured_port
            );
            let fallback_addr = format!("{}:0", config.bridge.hostname);
            let l = tokio::net::TcpListener::bind(&fallback_addr).await?;
            let actual = l.local_addr()?.port();
            tracing::info!(
                "Bridge listening on random port {} (configured: {})",
                actual,
                configured_port
            );
            l
        }
        Err(e) => return Err(e.into()),
    };

    let actual_port = listener.local_addr()?.port();
    let port_changed = actual_port != configured_port;

    if port_changed {
        if let Err(e) = app_state.bridge_db.set_config("bridge.port", &actual_port.to_string()) {
            tracing::warn!("Failed to update bridge.port in DB: {}", e);
        } else {
            tracing::info!("Updated bridge.port in DB to {}", actual_port);
        }
    }

    app_state.actual_port.store(actual_port, std::sync::atomic::Ordering::Relaxed);

    let port = actual_port;
    tracing::info!("Bridge listening on {}", listener.local_addr()?);

    if !gateway_url.is_empty() && gateway_auto_connect {
        let gw_url = gateway_url.clone();
        let gw_instance_id = gateway_instance_id.clone();
        tokio::spawn(async move {
            let mut client = crate::gateway::client::GatewayClient::new(
                &crate::config::GatewayConfig {
                    url: gw_url,
                    auto_connect: true,
                    instance_id: gw_instance_id,
                },
                &gateway_instance_id,
                port,
            );
            client.connect().await;
        });
    }

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

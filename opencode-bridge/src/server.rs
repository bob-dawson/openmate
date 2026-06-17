use axum::Router;
use axum::routing::{any, get, post};
use std::net::SocketAddr;
use std::sync::Arc;
use std::time::Duration;
use tower_http::cors::CorsLayer;
use tower_http::trace::TraceLayer;

#[cfg(windows)]
use std::os::windows::process::CommandExt;

use crate::auth;
use crate::bridge;
use crate::events;
use crate::files;
use crate::fs;
use crate::git;
use crate::proxy;
use crate::log_capture::SharedLogBuffer;
use crate::state::{ScanTokenEntry, create_app_state_with_db_event_source_and_actual_port};
use crate::sync;

pub async fn run_server(
    log_buffer: SharedLogBuffer,
    shutdown: Option<(tokio::sync::watch::Sender<bool>, tokio::sync::watch::Receiver<bool>)>,
    actual_port: Option<Arc<std::sync::atomic::AtomicU16>>,
    ready_notify: Option<Arc<tokio::sync::Notify>>,
) -> anyhow::Result<()> {
    ensure_user_env();

    let app_state = create_app_state_with_db_event_source_and_actual_port(
        log_buffer,
        None,
        None,
        actual_port,
        shutdown,
    );
    let config = &app_state.config;

    tracing::info!(
        "Bridge listen: {}:{}",
        config.bridge.hostname,
        config.bridge.port
    );
    tracing::info!("OpenCode target: {}", config.opencode_url());
    tracing::info!("Allowed paths: {:?}", config.effective_allowed_paths());
    tracing::info!("Auth enabled: {}", config.bridge.auth_enabled);

    if app_state.opencode_manager.check_health().await {
        tracing::info!("opencode is already running, adopting");
        app_state.opencode_manager.set_status(crate::state::OpencodeStatus::Running).await;
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

    {
        let token_bytes = auth::key::generate_random_bytes(6);
        let scan_token = auth::key::base64url_encode(&token_bytes);
        let now = std::time::SystemTime::now()
            .duration_since(std::time::UNIX_EPOCH)
            .unwrap_or_default()
            .as_millis() as i64;
        let expires_at = now + 120_000;
        let mut st = app_state.scan_token.write().await;
        *st = Some(ScanTokenEntry {
            token: scan_token.clone(),
            expires_at,
        });
        tracing::info!("Initial scan_token generated: {} (len={})", scan_token, scan_token.len());
    }

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
        .route(
            "/api/bridge/upgrade/download",
            post(bridge::router::bridge_upgrade_download),
        )
        .route(
            "/api/bridge/upgrade/status",
            get(bridge::router::bridge_upgrade_status),
        )
        .route(
            "/api/bridge/upgrade/apply",
            post(bridge::router::bridge_upgrade_apply),
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
        .route("/api/bridge/git/status", get(git::router::status))
        .route("/api/bridge/git/diff", get(git::router::diff))
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
        tracing::warn!(
            "Port {} unavailable, using {} instead. Config not updated — restart may reclaim original port.",
            configured_port, actual_port
        );
    }

    app_state.actual_port.store(actual_port, std::sync::atomic::Ordering::Relaxed);

    let port = actual_port;
    tracing::info!("Bridge listening on {}", listener.local_addr()?);

    #[cfg(unix)]
    {
        let scan_token = {
            let st = app_state.scan_token.read().await;
            st.as_ref().map(|e| e.token.clone()).unwrap_or_default()
        };
        let instance_id = app_state.config.gateway.instance_id.clone();
        let iid_b64 = auth::key::base64url_encode(
            &auth::key::hex_to_bytes(&instance_id).unwrap_or_default()
        );
        let qr_data = format!("op:{}:{}", iid_b64, scan_token);
        tracing::info!("QR data: {}", qr_data);

        let machine_name = hostname::get()
            .ok()
            .and_then(|h| h.into_string().ok())
            .unwrap_or_else(|| "Bridge".to_string());

        let local_ip = local_ip_address::local_ip()
            .map(|ip| ip.to_string())
            .unwrap_or_else(|_| "unknown".to_string());

        if let Ok(code) = qrcode::QrCode::new(&qr_data) {
            let qr_string = code
                .render::<qrcode::render::unicode::Dense1x2>()
                .quiet_zone(false)
                .module_dimensions(1, 1)
                .build();

            println!();
            println!("╔══════════════════════════════════════╗");
            println!("║   OpenMate Bridge - Pair Your Phone  ║");
            println!("╚══════════════════════════════════════╝");
            println!();
            println!("  Bridge: {}", machine_name);
            println!("  IP:     {}:{}", local_ip, port);
            println!();
            for line in qr_string.lines() {
                println!("  {}", line);
            }
            println!();
            println!("  Scan this QR code with the OpenMate app to pair.");
        } else {
            println!();
            println!("  Pairing data: {}", qr_data);
        }
        println!();
        println!("  Manage: http://127.0.0.1:{}/ui/", port);
        println!("  LAN:    http://{}:{}/ui/", local_ip, port);
        println!();

        let gateway_url = &app_state.config.gateway.url;
        if !gateway_url.is_empty() {
            println!("  Gateway: {}", gateway_url);
            println!();
        }
    }

    if let Some(notify) = ready_notify {
        notify.notify_waiters();
    }

    if !gateway_url.is_empty() && gateway_auto_connect {
        let gw_url = gateway_url.clone();
        let gw_instance_id = gateway_instance_id.clone();
        let gw_shutdown_rx = app_state.shutdown_tx.subscribe();
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
            client.connect(gw_shutdown_rx).await;
        });
    }

    let mut shutdown_rx = app_state.shutdown_tx.subscribe();

    let server = axum::serve(
        listener,
        app.into_make_service_with_connect_info::<SocketAddr>(),
    )
    .with_graceful_shutdown(async move {
        shutdown_rx.changed().await.ok();
        tracing::info!("Shutdown signal received, server shutting down");
    });

    server.await.ok();
    tracing::info!("Server stopped, exiting");
    std::process::exit(0);
}

fn ensure_user_env() {
    #[cfg(windows)]
    {
        unsafe { std::env::set_var("OPENCODE_EXPERIMENTAL", "true") };

        if get_user_env_var("OPENCODE_EXPERIMENTAL").as_deref() == Some("true") {
            return;
        }

        match std::process::Command::new("reg")
            .args([
                "add",
                "HKCU\\Environment",
                "/v",
                "OPENCODE_EXPERIMENTAL",
                "/t",
                "REG_SZ",
                "/d",
                "true",
                "/f",
            ])
            .creation_flags(0x08000000)
            .output()
        {
            Ok(o) if o.status.success() => {
                tracing::info!("Set user env OPENCODE_EXPERIMENTAL=true via registry");
            }
            Ok(o) => {
                tracing::warn!("reg add failed: {}", String::from_utf8_lossy(&o.stderr));
            }
            Err(e) => {
                tracing::warn!("Failed to run reg add: {}", e);
            }
        }
    }

    #[cfg(unix)]
    {
        unsafe { std::env::set_var("OPENCODE_EXPERIMENTAL", "true") };

        let home = match std::env::var("HOME") {
            Ok(h) if !h.is_empty() => h,
            _ => {
                tracing::warn!("HOME not set, cannot configure user env");
                return;
            }
        };

        let profile = std::path::PathBuf::from(&home).join(".profile");
        let marker = "OPENCODE_EXPERIMENTAL=true";

        if let Ok(content) = std::fs::read_to_string(&profile) {
            if content.contains(marker) {
                return;
            }
        }

        let line = "\n# Added by OpenMate Bridge\nexport OPENCODE_EXPERIMENTAL=true\n";
        match std::fs::OpenOptions::new()
            .append(true)
            .create(true)
            .write(true)
            .open(&profile)
        {
            Ok(mut f) => {
                use std::io::Write;
                if let Err(e) = f.write_all(line.as_bytes()) {
                    tracing::warn!("Failed to write to {}: {}", profile.display(), e);
                } else {
                    tracing::info!("Added OPENCODE_EXPERIMENTAL=true to {}", profile.display());
                }
            }
            Err(e) => {
                tracing::warn!("Failed to open {}: {}", profile.display(), e);
            }
        }
    }
}

#[cfg(windows)]
fn get_user_env_var(name: &str) -> Option<String> {
    let output = std::process::Command::new("reg")
        .args([
            "query",
            "HKCU\\Environment",
            "/v",
            name,
        ])
        .creation_flags(0x08000000)
        .output()
        .ok()?;
    if !output.status.success() {
        return None;
    }
    let text = String::from_utf8_lossy(&output.stdout);
    let line = text.lines().find(|l| l.contains(name))?;
    let value = line.split_whitespace().nth(2)?;
    Some(value.to_string())
}

#[cfg(not(windows))]
fn get_user_env_var(_name: &str) -> Option<String> {
    None
}

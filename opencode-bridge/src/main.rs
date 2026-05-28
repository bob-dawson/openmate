#![cfg_attr(all(windows, not(test)), windows_subsystem = "windows")]

use clap::{Parser, Subcommand};
use std::sync::Arc;
use tokio::sync::Notify;

#[derive(Parser, Debug)]
#[command(name = "openmate", about = "OpenMate Bridge")]
struct Args {
    #[arg(long)]
    tray: bool,

    #[command(subcommand)]
    command: Option<Commands>,
}

#[derive(Subcommand, Debug)]
enum Commands {
    Approve {
        pin: String,
    },
    ResetToken,
    Install,
    Uninstall,
    Service,
}

#[tokio::main]
async fn main() -> anyhow::Result<()> {
    let args = Args::parse();

    rustls::crypto::ring::default_provider()
        .install_default()
        .expect("Failed to install rustls crypto provider");

    match args.command {
        Some(Commands::Approve { pin }) => {
            init_console_logging();
            return run_approve(&pin).await;
        }
        Some(Commands::ResetToken) => {
            init_console_logging();
            return run_reset_token().await;
        }
        Some(Commands::Install) => {
            return run_install();
        }
        Some(Commands::Uninstall) => {
            return run_uninstall();
        }
        Some(Commands::Service) => {
            #[cfg(target_os = "windows")]
            return run_service_mode();
            #[cfg(target_os = "linux")]
            return run_service_mode().await;
        }
        None => {}
    }

    run_gui_mode(args).await
}

fn init_console_logging() {
    tracing_subscriber::fmt()
        .with_env_filter(
            tracing_subscriber::EnvFilter::try_from_default_env()
                .unwrap_or_else(|_| tracing_subscriber::EnvFilter::new("info")),
        )
        .init();
}

fn init_capture_logging() -> openmate::log_capture::SharedLogBuffer {
    use openmate::log_capture::{LogCaptureLayer, create_shared_buffer};
    use tracing_subscriber::layer::SubscriberExt;
    use tracing_subscriber::util::SubscriberInitExt;

    let buffer = create_shared_buffer();
    let capture_layer = LogCaptureLayer::new(buffer.clone());
    let env_filter = tracing_subscriber::EnvFilter::try_from_default_env()
        .unwrap_or_else(|_| tracing_subscriber::EnvFilter::new("info"));

    let fmt_layer = tracing_subscriber::fmt::layer();

    let subscriber = tracing_subscriber::registry()
        .with(env_filter)
        .with(fmt_layer)
        .with(capture_layer);

    subscriber.init();
    buffer
}

async fn run_gui_mode(_args: Args) -> anyhow::Result<()> {
    let buffer = init_capture_logging();

    let bridge_db = openmate::bridge_db::BridgeDb::open().map_err(|e| anyhow::anyhow!("{}", e))?;
    bridge_db.init_default_configs().map_err(|e| anyhow::anyhow!("{}", e))?;
    let config = openmate::config::Config::load_from_db(&bridge_db)?;
    let port = config.bridge.port;

    if is_already_running() {
        tracing::info!("Another instance is already running, notifying it to open UI");
        let _ = notify_existing_instance(port);
        return Ok(());
    }

    let (shutdown_tx, shutdown_rx) = tokio::sync::watch::channel(false);
    let actual_port: Arc<std::sync::atomic::AtomicU16> = Arc::new(std::sync::atomic::AtomicU16::new(port));
    let ready_notify = Arc::new(Notify::new());

    #[cfg(target_os = "windows")]
    if !_args.tray {
        let ap = actual_port.clone();
        let ready = ready_notify.clone();
        tokio::spawn(async move {
            ready.notified().await;
            let port = ap.load(std::sync::atomic::Ordering::Relaxed);
            let url = format!("http://127.0.0.1:{}/ui/", port);
            tracing::info!("Opening browser: {}", url);
            let _ = open::that(&url);
        });
    }

    #[cfg(target_os = "linux")]
    {
        let has_desktop = std::env::var("DISPLAY").is_ok()
            || std::env::var("WAYLAND_DISPLAY").is_ok();
        let is_wsl = std::fs::read_to_string("/proc/version")
            .map(|v| v.contains("Microsoft") || v.contains("WSL"))
            .unwrap_or(false);

        let ap = actual_port.clone();
        let ready = ready_notify.clone();

        if has_desktop && !is_wsl && !_args.tray {
            tokio::spawn(async move {
                ready.notified().await;
                let port = ap.load(std::sync::atomic::Ordering::Relaxed);
                let url = format!("http://127.0.0.1:{}/ui/", port);
                tracing::info!("Opening browser: {}", url);
                let _ = open::that(&url);
            });
        } else if is_wsl {
            tokio::spawn(async move {
                ready.notified().await;
                let port = ap.load(std::sync::atomic::Ordering::Relaxed);
                println!();
                println!("╔══════════════════════════════════════╗");
                println!("║   OpenMate Bridge - Pair Your Phone  ║");
                println!("╚══════════════════════════════════════╝");
                println!();
                println!("  Open: http://127.0.0.1:{}/ui/", port);
                println!();
            });
        } else {
            let instance_id = config.gateway.instance_id.clone();
            let gateway_url = config.gateway.url.clone();
            tokio::spawn(async move {
                ready.notified().await;
                let client = reqwest::Client::new();
                let mut scan_token = String::new();
                let mut port_val = ap.load(std::sync::atomic::Ordering::Relaxed);

                for attempt in 1..=20 {
                    port_val = ap.load(std::sync::atomic::Ordering::Relaxed);
                    let scan_url = format!("http://127.0.0.1:{}/api/bridge/pair/scan", port_val);
                    match client.get(&scan_url).send().await {
                        Ok(resp) if resp.status().is_success() => match resp.json::<serde_json::Value>().await {
                            Ok(val) => {
                                scan_token = val["scan_token"].as_str().unwrap_or("").to_string();
                                if !scan_token.is_empty() {
                                    break;
                                }
                                tracing::warn!(
                                    "Empty scan token from {} on attempt {}",
                                    scan_url,
                                    attempt
                                );
                            }
                            Err(e) => {
                                tracing::warn!(
                                    "Failed to parse scan token response from {} on attempt {}: {}",
                                    scan_url,
                                    attempt,
                                    e
                                );
                            }
                        },
                        Ok(resp) => {
                            tracing::warn!(
                                "Scan token API returned {} from {} on attempt {}",
                                resp.status(),
                                scan_url,
                                attempt
                            );
                        }
                        Err(e) => {
                            tracing::warn!(
                                "Failed to request scan token from {} on attempt {}: {}",
                                scan_url,
                                attempt,
                                e
                            );
                        }
                    }
                    tokio::time::sleep(tokio::time::Duration::from_millis(500)).await;
                }

                if scan_token.is_empty() {
                    tracing::error!(
                        "Failed to acquire non-empty scan token after retries on port {}",
                        port_val
                    );
                    return;
                }

                let iid_b64 = openmate::auth::key::base64url_encode(
                    &openmate::auth::key::hex_to_bytes(&instance_id).unwrap_or_default()
                );
                let qr_data = format!("op:{}:{}", iid_b64, scan_token);
                tracing::info!("QR pairing ready");
                match qrcode::QrCode::new(&qr_data) {
                    Ok(code) => {
                        let qr_string = code
                            .render::<qrcode::render::unicode::Dense1x2>()
                            .quiet_zone(false)
                            .module_dimensions(1, 1)
                            .build();

                        let machine_name = hostname::get()
                            .ok()
                            .and_then(|h| h.into_string().ok())
                            .unwrap_or_else(|| "Bridge".to_string());

                        let local_ip = local_ip_address::local_ip()
                            .map(|ip| ip.to_string())
                            .unwrap_or_else(|_| "unknown".to_string());

                        println!();
                        println!("╔══════════════════════════════════════╗");
                        println!("║   OpenMate Bridge - Pair Your Phone  ║");
                        println!("╚══════════════════════════════════════╝");
                        println!();
                        println!("  Bridge: {}", machine_name);
                        println!("  IP:     {}:{}", local_ip, port_val);
                        println!();
                        for line in qr_string.lines() {
                            println!("  {}", line);
                        }
                        println!();
                        println!("  Scan this QR code with the OpenMate app to pair.");
                        println!("  Gateway: {}", gateway_url);
                        println!("  Or open: http://{}:{}/ui/", local_ip, port_val);
                        println!();
                    }
                    Err(e) => {
                        tracing::error!("Failed to generate QR code: {}", e);
                    }
                }
            });
        }
    }

    #[cfg(target_os = "windows")]
    {
        let (tx, rx) = std::sync::mpsc::channel::<openmate::tray::TrayEvent>();
        let _tray_handle = openmate::tray::spawn_tray_thread(actual_port.clone(), tx)?;

        let shutdown_signal = shutdown_tx.clone();
        let ap = actual_port.clone();
        tokio::spawn(async move {
            while let Ok(event) = rx.recv() {
                match event {
                    openmate::tray::TrayEvent::Quit => {
                        let _ = shutdown_signal.send(true);
                        break;
                    }
                    openmate::tray::TrayEvent::OpenUi => {
                        let port = ap.load(std::sync::atomic::Ordering::Relaxed);
                        let url = format!("http://127.0.0.1:{}/ui/", port);
                        let _ = open::that(&url);
                    }
                    openmate::tray::TrayEvent::ToggleAutostart => {}
                }
            }
        });
    }

    let ctrl_c_tx = shutdown_tx.clone();
    tokio::spawn(async move {
        tokio::signal::ctrl_c().await.ok();
        tracing::info!("Ctrl+C received, shutting down");
        let _ = ctrl_c_tx.send(true);
    });

    openmate::server::run_server(
        buffer,
        Some((shutdown_tx, shutdown_rx)),
        Some(actual_port.clone()),
        Some(ready_notify),
    ).await
}

#[cfg(target_os = "windows")]
fn is_already_running() -> bool {
    use windows::core::PCWSTR;
    use windows::Win32::Foundation::*;
    use windows::Win32::System::Threading::*;

    let mutex_name: Vec<u16> = "Global\\OpenMateBridge\0".encode_utf16().collect();
    unsafe {
        let handle = match CreateMutexW(None, true, PCWSTR(mutex_name.as_ptr())) {
            Ok(h) => h,
            Err(_) => return false,
        };
        let err = GetLastError();
        if err == ERROR_ALREADY_EXISTS {
            let _ = CloseHandle(handle);
            return true;
        }
        false
    }
}

#[cfg(not(target_os = "windows"))]
fn is_already_running() -> bool {
    false
}

async fn notify_existing_instance(port: u16) -> anyhow::Result<()> {
    let client = reqwest::Client::new();
    let url = format!("http://127.0.0.1:{}/api/bridge/open-ui", port);
    let resp = client.post(&url).send().await?;
    if resp.status().is_success() {
        tracing::info!("Successfully notified existing instance to open UI");
    } else {
        tracing::warn!("Failed to notify existing instance: {}", resp.status());
    }
    Ok(())
}

async fn run_approve(pin: &str) -> anyhow::Result<()> {
    let bridge_db = openmate::bridge_db::BridgeDb::open().map_err(|e| anyhow::anyhow!("{}", e))?;
    bridge_db.init_default_configs().map_err(|e| anyhow::anyhow!("{}", e))?;
    let config = openmate::config::Config::load_from_db(&bridge_db)?;
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
    let bridge_db = openmate::bridge_db::BridgeDb::open().map_err(|e| anyhow::anyhow!("{}", e))?;
    let new_key = openmate::auth::key::hex_encode(&openmate::auth::key::generate_random_bytes(32));
    bridge_db.set_config("auth.secret_key", &new_key).map_err(|e| anyhow::anyhow!("{}", e))?;
    tracing::info!(
        "Secret key reset. All tokens are now invalid. A new key will be used on next start."
    );
    Ok(())
}

#[cfg(target_os = "windows")]
fn run_install() -> anyhow::Result<()> {
    let bridge_db = openmate::bridge_db::BridgeDb::open().map_err(|e| anyhow::anyhow!("{}", e))?;
    bridge_db.init_default_configs().map_err(|e| anyhow::anyhow!("{}", e))?;
    let mut config = openmate::config::Config::load_from_db(&bridge_db)?;

    config.resolve_opencode_binary()?;
    println!("opencode binary resolved: {}", config.opencode.binary);

    config.save_to_db(&bridge_db)?;
    println!("Config saved to database");

    openmate::service_windows::install()
}

#[cfg(target_os = "linux")]
fn run_install() -> anyhow::Result<()> {
    let bridge_db = openmate::bridge_db::BridgeDb::open().map_err(|e| anyhow::anyhow!("{}", e))?;
    bridge_db.init_default_configs().map_err(|e| anyhow::anyhow!("{}", e))?;
    let mut config = openmate::config::Config::load_from_db(&bridge_db)?;

    config.resolve_opencode_binary()?;
    println!("opencode binary resolved: {}", config.opencode.binary);

    config.save_to_db(&bridge_db)?;
    println!("Config saved to database");

    openmate::service_linux::install()
}

#[cfg(not(any(target_os = "windows", target_os = "linux")))]
fn run_install() -> anyhow::Result<()> {
    anyhow::bail!("Service installation is not supported on this platform");
}

#[cfg(target_os = "windows")]
fn run_uninstall() -> anyhow::Result<()> {
    openmate::service_windows::uninstall()
}

#[cfg(target_os = "linux")]
fn run_uninstall() -> anyhow::Result<()> {
    openmate::service_linux::uninstall()
}

#[cfg(not(any(target_os = "windows", target_os = "linux")))]
fn run_uninstall() -> anyhow::Result<()> {
    anyhow::bail!("Service uninstallation is not supported on this platform");
}

#[cfg(target_os = "windows")]
fn run_service_mode() -> anyhow::Result<()> {
    openmate::service_windows::run_service()
}

#[cfg(target_os = "linux")]
async fn run_service_mode() -> anyhow::Result<()> {
    let buffer = init_capture_logging();
    openmate::server::run_server(buffer, None, None, None).await
}

#[cfg(not(any(target_os = "windows", target_os = "linux")))]
fn run_service_mode() -> anyhow::Result<()> {
    anyhow::bail!("Service mode is not supported on this platform");
}

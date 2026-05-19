#![cfg_attr(all(windows, not(test)), windows_subsystem = "windows")]

use clap::{Parser, Subcommand};
use openmate::config::Config;
use std::path::PathBuf;
use std::sync::Arc;
use tokio::sync::Notify;

#[derive(Parser, Debug)]
#[command(name = "openmate", about = "OpenMate Bridge")]
struct Args {
    #[arg(short, long)]
    config: Option<PathBuf>,

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
            init_console_logging();
            return run_service_mode();
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

    let subscriber = tracing_subscriber::registry()
        .with(env_filter)
        .with(capture_layer);

    subscriber.init();
    buffer
}

async fn run_gui_mode(args: Args) -> anyhow::Result<()> {
    let _buffer = init_capture_logging();

    if is_already_running() {
        tracing::info!("Another instance is already running, notifying it to open UI");
        let config = Config::find_and_load(args.config.clone()).unwrap_or_default();
        let _ = notify_existing_instance(config.bridge.port);
        return Ok(());
    }

    let config = Config::find_and_load(args.config)?;
    let port = config.bridge.port;

    let shutdown = Arc::new(Notify::new());
    let shutdown_clone = shutdown.clone();

    if !args.tray {
        let port_copy = port;
        tokio::spawn(async move {
            tokio::time::sleep(tokio::time::Duration::from_secs(2)).await;
            let url = format!("http://127.0.0.1:{}/ui/", port_copy);
            tracing::info!("Opening browser: {}", url);
            let _ = open::that(&url);
        });
    }

    #[cfg(target_os = "windows")]
    {
        let (tx, rx) = std::sync::mpsc::channel::<openmate::tray::TrayEvent>();
        let shutdown_for_tray = shutdown_clone.clone();
        let _tray_handle = openmate::tray::spawn_tray_thread(port, tx, shutdown_for_tray)?;

        let shutdown_signal = shutdown_clone.clone();
        tokio::spawn(async move {
            while let Ok(event) = rx.recv() {
                match event {
                    openmate::tray::TrayEvent::Quit => {
                        shutdown_signal.notify_one();
                        break;
                    }
                    openmate::tray::TrayEvent::OpenUi => {
                        let url = format!("http://127.0.0.1:{}/ui/", port);
            let _ = openmate::browser::open_browser(&url);
                    }
                    openmate::tray::TrayEvent::ToggleAutostart => {}
                }
            }
        });
    }

    let server_shutdown = shutdown.clone();
    tokio::spawn(async move {
        tokio::signal::ctrl_c().await.ok();
        tracing::info!("Ctrl+C received, shutting down");
        server_shutdown.notify_one();
    });

    openmate::server::run_server(config, Some(shutdown_clone)).await
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
    openmate::auth::key::SecretKey::delete_key_file()?;
    tracing::info!(
        "Secret key deleted. All tokens are now invalid. A new key will be generated on next start."
    );
    Ok(())
}

#[cfg(target_os = "windows")]
fn run_install() -> anyhow::Result<()> {
    let config_path = Config::find_or_create_config_path();
    let mut config = if config_path.exists() {
        Config::load_from(&config_path)?
    } else {
        Config::default()
    };

    config.resolve_opencode_binary()?;
    println!("opencode binary resolved: {}", config.opencode.binary);

    config.save_to(&config_path)?;
    println!("Config saved to {}", config_path.display());

    openmate::service_windows::install()
}

#[cfg(target_os = "linux")]
fn run_install() -> anyhow::Result<()> {
    let config_path = Config::find_or_create_config_path();
    let mut config = if config_path.exists() {
        Config::load_from(&config_path)?
    } else {
        Config::default()
    };

    config.resolve_opencode_binary()?;
    println!("opencode binary resolved: {}", config.opencode.binary);

    config.save_to(&config_path)?;
    println!("Config saved to {}", config_path.display());

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
    let config = Config::find_and_load(None)?;
    openmate::server::run_server(config, None).await
}

#[cfg(not(any(target_os = "windows", target_os = "linux")))]
fn run_service_mode() -> anyhow::Result<()> {
    anyhow::bail!("Service mode is not supported on this platform");
}

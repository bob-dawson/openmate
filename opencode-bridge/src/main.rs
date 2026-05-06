use clap::{Parser, Subcommand};
use openmate::config::Config;
use std::path::PathBuf;

#[derive(Parser, Debug)]
#[command(name = "openmate", about = "OpenMate Bridge")]
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
    Install,
    Uninstall,
    Service,
}

#[tokio::main]
async fn main() -> anyhow::Result<()> {
    tracing_subscriber::fmt()
        .with_env_filter(
            tracing_subscriber::EnvFilter::try_from_default_env()
                .unwrap_or_else(|_| tracing_subscriber::EnvFilter::new("info")),
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
        Some(Commands::Install) => {
            return run_install();
        }
        Some(Commands::Uninstall) => {
            return run_uninstall();
        }
        Some(Commands::Service) => {
            return run_service_mode();
        }
        None => {}
    }

    let config = Config::find_and_load(args.config)?;
    openmate::server::run_server(config, None).await
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

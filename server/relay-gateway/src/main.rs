use clap::Parser;
use relay_gateway::auth::SecretKey;
use relay_gateway::config::Config;
use relay_gateway::server::run_server;
use relay_gateway::state::{GatewayState, SharedState};
use std::sync::Arc;
use tracing_subscriber::EnvFilter;

#[derive(Parser)]
#[command(name = "relay-gateway", about = "OpenMate relay gateway")]
struct Cli {
    #[arg(short = 'c', long, default_value = "gateway.toml")]
    config: String,
}

#[tokio::main]
async fn main() -> anyhow::Result<()> {
    tracing_subscriber::fmt()
        .with_env_filter(EnvFilter::from_default_env().add_directive("relay_gateway=info".parse()?))
        .init();

    let cli = Cli::parse();
    let config = Config::find_and_load(&cli.config)?;
    let listen = config.listen_addr();
    tracing::info!("loaded config, will listen on {listen}");

    let secret_key = SecretKey::load_from_file(&config.auth.secret_key_path)?;
    let state: SharedState = Arc::new(GatewayState::new(config, secret_key));

    run_server(state).await
}

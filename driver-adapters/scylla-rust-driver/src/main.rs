use anyhow::Result;
use std::sync::Arc;
use tracing::info;

use driver_counterpart::{config, server, session};

#[tokio::main]
async fn main() -> Result<()> {
    tracing_subscriber::fmt()
        .with_env_filter(tracing_subscriber::EnvFilter::from_default_env())
        .compact()
        .init();

    let config = config::DriverConfig::from_env()?;
    info!(?config, "starting latte driver counterpart");

    let sessions = Arc::new(session::SessionRegistry::new(config.clone()));
    server::run(&config, sessions).await?;

    Ok(())
}

use anyhow::{Context, Result};
use std::env;
use std::path::{Path, PathBuf};

#[derive(Clone, Debug)]
pub struct DriverConfig {
    pub socket_path: PathBuf,
    pub inflight_limit: usize,
}

impl DriverConfig {
    pub fn from_env() -> Result<Self> {
        let socket_path = env::var("LATTE_DRIVER_SOCKET")
            .map(PathBuf::from)
            .unwrap_or_else(|_| PathBuf::from("/tmp/latte-driver.sock"));

        let inflight_limit = env::var("LATTE_DRIVER_INFLIGHT")
            .ok()
            .map(|raw| {
                raw.parse::<usize>()
                    .context("failed to parse LATTE_DRIVER_INFLIGHT")
            })
            .transpose()?
            .unwrap_or(512);

        Ok(Self {
            socket_path,
            inflight_limit,
        })
    }

    pub fn socket_parent(&self) -> Option<&Path> {
        self.socket_path.parent()
    }
}

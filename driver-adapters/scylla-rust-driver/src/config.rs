use anyhow::{Context, Result};
use std::env;
use std::path::{Path, PathBuf};

#[derive(Clone, Debug)]
pub struct DriverConfig {
    pub socket_path: PathBuf,
    pub contact_points: Vec<String>,
    pub keyspace: Option<String>,
    pub inflight_limit: usize,
}

impl DriverConfig {
    pub fn from_env() -> Result<Self> {
        let socket_path = env::var("LATTE_DRIVER_SOCKET")
            .map(PathBuf::from)
            .unwrap_or_else(|_| PathBuf::from("/tmp/latte-driver.sock"));

        let contact_points = env::var("LATTE_DRIVER_CONTACT_POINTS")
            .unwrap_or_else(|_| "127.0.0.1".to_string())
            .split(',')
            .map(|v| v.trim().to_string())
            .filter(|v| !v.is_empty())
            .collect::<Vec<_>>();

        if contact_points.is_empty() {
            anyhow::bail!("LATTE_DRIVER_CONTACT_POINTS cannot be empty");
        }

        let keyspace = env::var("LATTE_DRIVER_KEYSPACE").ok();

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
            contact_points,
            keyspace,
            inflight_limit,
        })
    }

    pub fn socket_parent(&self) -> Option<&Path> {
        self.socket_path.parent()
    }
}

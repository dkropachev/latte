//! Docker container management for the driver counterpart.

use anyhow::{bail, Context, Result};
use std::path::{Path, PathBuf};
use std::process::Stdio;
use tokio::process::Command;
use tokio::time::{timeout, Duration};

/// Configuration for the Docker manager.
#[derive(Debug, Clone)]
pub struct DockerConfig {
    /// Docker image to use for the driver.
    pub image: String,
    /// Full path to the Unix domain socket.
    pub socket_path: PathBuf,
    /// Contact points for the database.
    pub contact_points: Vec<String>,
    /// Optional keyspace.
    pub keyspace: Option<String>,
    /// Container name (auto-generated if not specified).
    pub container_name: Option<String>,
}

impl Default for DockerConfig {
    fn default() -> Self {
        Self {
            image: "scylladb/latte-driver:latest".to_string(),
            socket_path: PathBuf::from("/tmp/latte-driver.sock"),
            contact_points: vec!["127.0.0.1".to_string()],
            keyspace: None,
            container_name: None,
        }
    }
}

/// Manages the driver container lifecycle.
pub struct DockerManager {
    config: DockerConfig,
    container_id: Option<String>,
}

impl DockerManager {
    /// Create a new Docker manager with the given configuration.
    pub fn new(config: DockerConfig) -> Self {
        Self {
            config,
            container_id: None,
        }
    }

    /// Start the driver container.
    pub async fn start(&mut self) -> Result<()> {
        let socket_path = &self.config.socket_path;
        let socket_dir = socket_path
            .parent()
            .ok_or_else(|| anyhow::anyhow!("socket path has no parent directory"))?;
        let socket_name = socket_path
            .file_name()
            .ok_or_else(|| anyhow::anyhow!("socket path has no file name"))?
            .to_string_lossy();

        // Ensure socket directory exists
        tokio::fs::create_dir_all(socket_dir)
            .await
            .with_context(|| {
                format!(
                    "failed to create socket directory at {}",
                    socket_dir.display()
                )
            })?;

        let contact_points = self.config.contact_points.join(",");

        let container_name = self
            .config
            .container_name
            .clone()
            .unwrap_or_else(|| format!("latte-driver-{}", std::process::id()));

        let mut args = vec![
            "run".to_string(),
            "--rm".to_string(),
            "-d".to_string(),
            "--name".to_string(),
            container_name.clone(),
            "-v".to_string(),
            format!("{}:/sockets", socket_dir.display()),
            "-e".to_string(),
            format!("LATTE_DRIVER_SOCKET=/sockets/{}", socket_name),
            "-e".to_string(),
            format!("LATTE_DRIVER_CONTACT_POINTS={}", contact_points),
        ];

        if let Some(keyspace) = &self.config.keyspace {
            args.push("-e".to_string());
            args.push(format!("LATTE_DRIVER_KEYSPACE={}", keyspace));
        }

        // Add network host mode for easier database access
        args.push("--network=host".to_string());

        args.push(self.config.image.clone());

        let output = Command::new("docker")
            .args(&args)
            .stdout(Stdio::piped())
            .stderr(Stdio::piped())
            .output()
            .await
            .context("failed to run docker command")?;

        if !output.status.success() {
            let stderr = String::from_utf8_lossy(&output.stderr);
            bail!("docker run failed: {}", stderr);
        }

        let container_id = String::from_utf8_lossy(&output.stdout).trim().to_string();
        self.container_id = Some(container_id);

        // Wait for socket to appear
        self.wait_for_socket(socket_path).await?;

        Ok(())
    }

    /// Stop the driver container.
    pub async fn stop(&mut self) -> Result<()> {
        if let Some(container_id) = self.container_id.take() {
            let output = Command::new("docker")
                .args(["stop", &container_id])
                .stdout(Stdio::piped())
                .stderr(Stdio::piped())
                .output()
                .await
                .context("failed to run docker stop")?;

            if !output.status.success() {
                let stderr = String::from_utf8_lossy(&output.stderr);
                // Don't fail if container already stopped
                if !stderr.contains("No such container") {
                    bail!("docker stop failed: {}", stderr);
                }
            }
        }
        Ok(())
    }

    /// Check if the container is healthy.
    pub async fn health_check(&self) -> Result<bool> {
        let Some(container_id) = &self.container_id else {
            return Ok(false);
        };

        let output = Command::new("docker")
            .args(["inspect", "-f", "{{.State.Running}}", container_id])
            .stdout(Stdio::piped())
            .stderr(Stdio::piped())
            .output()
            .await
            .context("failed to run docker inspect")?;

        if !output.status.success() {
            return Ok(false);
        }

        let running = String::from_utf8_lossy(&output.stdout).trim() == "true";
        Ok(running)
    }

    /// Get the socket path for connecting to the driver.
    pub fn socket_path(&self) -> &PathBuf {
        &self.config.socket_path
    }

    /// Get the container ID if running.
    pub fn container_id(&self) -> Option<&str> {
        self.container_id.as_deref()
    }

    async fn wait_for_socket(&self, socket_path: &Path) -> Result<()> {
        let wait_result = timeout(Duration::from_secs(30), async {
            loop {
                if socket_path.exists() {
                    return Ok(());
                }
                tokio::time::sleep(Duration::from_millis(100)).await;
            }
        })
        .await;

        match wait_result {
            Ok(Ok(())) => Ok(()),
            Ok(Err(e)) => Err(e),
            Err(_) => bail!(
                "timeout waiting for driver socket at {}",
                socket_path.display()
            ),
        }
    }
}

impl Drop for DockerManager {
    fn drop(&mut self) {
        // Try to stop container on drop (best effort, synchronous)
        if let Some(container_id) = self.container_id.take() {
            let _ = std::process::Command::new("docker")
                .args(["stop", &container_id])
                .stdout(Stdio::null())
                .stderr(Stdio::null())
                .status();
        }
    }
}

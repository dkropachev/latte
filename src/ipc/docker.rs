//! Docker container management for the driver counterpart.

use anyhow::{bail, Context, Result};
use std::path::PathBuf;
use std::process::Stdio;
use tokio::process::Command;

/// Configuration for the Docker manager.
#[derive(Debug, Clone)]
pub struct DockerConfig {
    /// Docker image to use for the driver.
    pub image: String,
    /// Full path to the Unix domain socket.
    pub socket_path: PathBuf,
    /// Container name (auto-generated if not specified).
    pub container_name: Option<String>,
    /// Name of the environment variable for the socket path inside the container.
    /// Defaults to "LATTE_DRIVER_SOCKET".
    pub socket_env_name: String,
    /// Extra environment variables to pass to the container.
    pub extra_envs: Vec<(String, String)>,
}

impl Default for DockerConfig {
    fn default() -> Self {
        Self {
            image: "scylladb/latte-driver:latest".to_string(),
            socket_path: PathBuf::from("/tmp/latte-driver.sock"),
            container_name: None,
            socket_env_name: "LATTE_DRIVER_SOCKET".to_string(),
            extra_envs: Vec::new(),
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
            format!("{}=/sockets/{}", self.config.socket_env_name, socket_name),
        ];

        // Pass extra environment variables
        for (key, value) in &self.config.extra_envs {
            args.push("-e".to_string());
            args.push(format!("{}={}", key, value));
        }

        // Run as the current user so socket files have correct ownership/permissions
        #[cfg(unix)]
        {
            use std::os::unix::fs::MetadataExt;
            if let Ok(exe) = std::env::current_exe() {
                if let Ok(meta) = std::fs::metadata(exe) {
                    args.push("--user".to_string());
                    args.push(format!("{}:{}", meta.uid(), meta.gid()));
                }
            }
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

        Ok(())
    }

    /// Stop the driver container.
    /// Part of DockerManager public API - kept for graceful shutdown scenarios.
    #[allow(dead_code)]
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
    /// Part of DockerManager public API - kept for health monitoring scenarios.
    #[allow(dead_code)]
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
    /// Part of DockerManager public API - kept for client connection scenarios.
    #[allow(dead_code)]
    pub fn socket_path(&self) -> &PathBuf {
        &self.config.socket_path
    }

    /// Get the container ID if running.
    pub fn container_id(&self) -> Option<&str> {
        self.container_id.as_deref()
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

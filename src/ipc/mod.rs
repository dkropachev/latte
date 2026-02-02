//! IPC module for communication with the driver counterpart.
//!
//! This module enables Latte to delegate query execution to an external
//! driver instance running in a Docker container, communicating via
//! Unix domain sockets using a CQL-inspired binary protocol.

pub mod alternator_client;
pub mod alternator_protocol;
pub mod client;
pub mod docker;
pub mod protocol;
pub mod session_manager;
#[cfg(test)]
mod tests;
pub mod types;

pub use alternator_client::{AlternatorIpcClient, AlternatorSessionId};
pub use client::IpcClient;
pub use docker::{DockerConfig, DockerManager};
pub use session_manager::SessionManager;
#[allow(unused_imports)]
pub use types::{QueryResult, SessionConfig, SessionId, SessionInfo};

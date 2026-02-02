//! Session manager for managing multiple driver sessions.

use super::client::IpcClient;
use super::types::{QueryResult, SessionConfig, SessionId, SessionInfo};
use anyhow::{bail, Result};
use dashmap::DashMap;
use scylla::frame::types::Consistency;
use scylla::value::CqlValue;
use std::sync::atomic::{AtomicU64, Ordering};
use std::sync::Arc;
use std::time::Duration;

/// Manages multiple driver sessions through IPC.
pub struct SessionManager {
    client: Arc<IpcClient>,
    sessions: DashMap<SessionId, SessionInfo>,
    default_session: AtomicU64,
}

impl SessionManager {
    /// Create a new session manager with the given IPC client.
    pub fn new(client: Arc<IpcClient>) -> Self {
        Self {
            client,
            sessions: DashMap::new(),
            default_session: AtomicU64::new(0),
        }
    }

    /// Create a new session with the driver.
    pub async fn create_session(&self, config: SessionConfig) -> Result<SessionId> {
        let session_id = self.client.create_session(config).await?;

        let info = SessionInfo {
            id: session_id,
            cluster_name: None,
            db_version: None,
        };

        self.sessions.insert(session_id, info);

        // If this is the first session, make it the default
        if self.sessions.len() == 1 {
            self.default_session.store(session_id, Ordering::SeqCst);
        }

        Ok(session_id)
    }

    /// Close a session.
    pub async fn close_session(&self, session_id: SessionId) -> Result<()> {
        self.sessions.remove(&session_id);
        Ok(())
    }

    /// Get the default session ID.
    pub fn default_session(&self) -> SessionId {
        self.default_session.load(Ordering::SeqCst)
    }

    /// Set the default session ID.
    pub fn set_default_session(&self, session_id: SessionId) {
        self.default_session.store(session_id, Ordering::SeqCst);
    }

    /// Prepare a statement on the specified session.
    pub async fn prepare(&self, session_id: SessionId, key: &str, query: &str) -> Result<()> {
        self.client.prepare(session_id, key, query).await
    }

    /// Prepare a statement on the default session.
    pub async fn prepare_default(&self, key: &str, query: &str) -> Result<()> {
        let session_id = self.default_session();
        if session_id == 0 {
            bail!("no default session available");
        }
        self.prepare(session_id, key, query).await
    }

    /// Execute a prepared statement on the specified session.
    /// Returns the query result and driver-side latency.
    pub async fn execute(
        &self,
        session_id: SessionId,
        key: &str,
        values: &[CqlValue],
        consistency: Consistency,
    ) -> Result<(QueryResult, Option<Duration>)> {
        self.client
            .execute(session_id, key, values, consistency)
            .await
    }

    /// Execute a prepared statement on the default session.
    /// Returns the query result and driver-side latency.
    pub async fn execute_default(
        &self,
        key: &str,
        values: &[CqlValue],
        consistency: Consistency,
    ) -> Result<(QueryResult, Option<Duration>)> {
        let session_id = self.default_session();
        if session_id == 0 {
            bail!("no default session available");
        }
        self.execute(session_id, key, values, consistency).await
    }

    /// Execute a simple query on the specified session.
    /// Returns the query result and driver-side latency.
    pub async fn query(
        &self,
        session_id: SessionId,
        query: &str,
        consistency: Consistency,
    ) -> Result<(QueryResult, Option<Duration>)> {
        self.client.query(session_id, query, consistency).await
    }

    /// Execute a simple query on the default session.
    /// Returns the query result and driver-side latency.
    pub async fn query_default(
        &self,
        query: &str,
        consistency: Consistency,
    ) -> Result<(QueryResult, Option<Duration>)> {
        let session_id = self.default_session();
        if session_id == 0 {
            bail!("no default session available");
        }
        self.query(session_id, query, consistency).await
    }

    /// Execute a batch of prepared statements on the specified session.
    ///
    /// Each element of `statements` is a tuple of (statement_key, values).
    /// Returns the driver-side latency.
    pub async fn batch(
        &self,
        session_id: SessionId,
        statements: &[(&str, &[CqlValue])],
        consistency: Consistency,
    ) -> Result<Option<Duration>> {
        self.client.batch(session_id, statements, consistency).await
    }

    /// Execute a batch of prepared statements on the default session.
    /// Returns the driver-side latency.
    pub async fn batch_default(
        &self,
        statements: &[(&str, &[CqlValue])],
        consistency: Consistency,
    ) -> Result<Option<Duration>> {
        let session_id = self.default_session();
        if session_id == 0 {
            bail!("no default session available");
        }
        self.batch(session_id, statements, consistency).await
    }

    /// List all active sessions.
    pub fn list_sessions(&self) -> Vec<SessionInfo> {
        self.sessions.iter().map(|r| r.value().clone()).collect()
    }

    /// Get the IPC client.
    pub fn client(&self) -> &Arc<IpcClient> {
        &self.client
    }
}

//! Types for IPC communication with the driver counterpart.

use scylla::frame::types::Consistency;
use scylla::value::CqlValue;
use std::collections::HashMap;

/// Unique identifier for a remote session.
pub type SessionId = u64;

/// Configuration for creating a new session.
///
/// All fields are optional. When sent to a driver adapter:
/// - Known params: Applied by the driver
/// - Unknown params: Silently ignored (forward compatibility)
/// - Missing params: Driver uses its defaults
#[derive(Debug, Clone, Default)]
pub struct SessionConfig {
    // === Connection ===
    /// Comma-separated contact points (host:port)
    pub contact_points: Option<String>,
    /// Default keyspace
    pub keyspace: Option<String>,
    /// Authentication username
    pub username: Option<String>,
    /// Authentication password
    pub password: Option<String>,
    /// Number of connections per shard/node
    pub connections_per_shard: Option<u32>,

    // === Topology ===
    /// Preferred datacenter for DC-aware load balancing
    pub datacenter: Option<String>,
    /// Preferred rack for rack-aware routing (requires datacenter)
    pub rack: Option<String>,

    // === Timeouts ===
    /// Per-request timeout in milliseconds
    pub request_timeout_ms: Option<u64>,
    /// Connection establishment timeout in milliseconds
    pub connect_timeout_ms: Option<u64>,

    // === Query defaults ===
    /// Default consistency level (e.g., "LOCAL_QUORUM", "ONE")
    pub consistency: Option<String>,
    /// Serial consistency for LWT (e.g., "LOCAL_SERIAL", "SERIAL")
    pub serial_consistency: Option<String>,
    /// Default page size for SELECT queries
    pub default_page_size: Option<u32>,

    // === SSL/TLS ===
    /// Enable SSL/TLS
    pub ssl_enabled: Option<bool>,
    /// CA certificate (PEM content or file path)
    pub ssl_ca_cert: Option<String>,
    /// Client certificate (PEM content or file path)
    pub ssl_cert: Option<String>,
    /// Client private key (PEM content or file path)
    pub ssl_key: Option<String>,
    /// Verify server certificate
    pub ssl_verify_peer: Option<bool>,

    /// Additional driver-specific options (escape hatch)
    pub extra: HashMap<String, String>,
}

impl SessionConfig {
    pub fn new() -> Self {
        Self::default()
    }

    pub fn contact_points(mut self, points: impl Into<String>) -> Self {
        self.contact_points = Some(points.into());
        self
    }

    pub fn keyspace(mut self, keyspace: impl Into<String>) -> Self {
        self.keyspace = Some(keyspace.into());
        self
    }

    pub fn credentials(mut self, username: impl Into<String>, password: impl Into<String>) -> Self {
        self.username = Some(username.into());
        self.password = Some(password.into());
        self
    }

    pub fn connections_per_shard(mut self, count: u32) -> Self {
        self.connections_per_shard = Some(count);
        self
    }

    pub fn datacenter(mut self, dc: impl Into<String>) -> Self {
        self.datacenter = Some(dc.into());
        self
    }

    pub fn rack(mut self, rack: impl Into<String>) -> Self {
        self.rack = Some(rack.into());
        self
    }

    pub fn request_timeout_ms(mut self, timeout_ms: u64) -> Self {
        self.request_timeout_ms = Some(timeout_ms);
        self
    }

    pub fn connect_timeout_ms(mut self, timeout_ms: u64) -> Self {
        self.connect_timeout_ms = Some(timeout_ms);
        self
    }

    pub fn consistency(mut self, consistency: impl Into<String>) -> Self {
        self.consistency = Some(consistency.into());
        self
    }

    pub fn serial_consistency(mut self, serial_consistency: impl Into<String>) -> Self {
        self.serial_consistency = Some(serial_consistency.into());
        self
    }

    pub fn default_page_size(mut self, page_size: u32) -> Self {
        self.default_page_size = Some(page_size);
        self
    }

    pub fn ssl_enabled(mut self, enabled: bool) -> Self {
        self.ssl_enabled = Some(enabled);
        self
    }

    pub fn ssl_ca_cert(mut self, cert: impl Into<String>) -> Self {
        self.ssl_ca_cert = Some(cert.into());
        self
    }

    pub fn ssl_cert(mut self, cert: impl Into<String>) -> Self {
        self.ssl_cert = Some(cert.into());
        self
    }

    pub fn ssl_key(mut self, key: impl Into<String>) -> Self {
        self.ssl_key = Some(key.into());
        self
    }

    pub fn ssl_verify_peer(mut self, verify: bool) -> Self {
        self.ssl_verify_peer = Some(verify);
        self
    }

    pub fn into_params(self) -> HashMap<String, String> {
        let mut map = self.extra;

        // Connection
        if let Some(v) = self.contact_points {
            map.insert("contact_points".to_string(), v);
        }
        if let Some(v) = self.keyspace {
            map.insert("keyspace".to_string(), v);
        }
        if let Some(v) = self.username {
            map.insert("username".to_string(), v);
        }
        if let Some(v) = self.password {
            map.insert("password".to_string(), v);
        }
        if let Some(v) = self.connections_per_shard {
            map.insert("connections_per_shard".to_string(), v.to_string());
        }

        // Topology
        if let Some(v) = self.datacenter {
            map.insert("datacenter".to_string(), v);
        }
        if let Some(v) = self.rack {
            map.insert("rack".to_string(), v);
        }

        // Timeouts
        if let Some(v) = self.request_timeout_ms {
            map.insert("request_timeout_ms".to_string(), v.to_string());
        }
        if let Some(v) = self.connect_timeout_ms {
            map.insert("connect_timeout_ms".to_string(), v.to_string());
        }

        // Query defaults
        if let Some(v) = self.consistency {
            map.insert("consistency".to_string(), v);
        }
        if let Some(v) = self.serial_consistency {
            map.insert("serial_consistency".to_string(), v);
        }
        if let Some(v) = self.default_page_size {
            map.insert("default_page_size".to_string(), v.to_string());
        }

        // SSL/TLS
        if let Some(v) = self.ssl_enabled {
            map.insert("ssl_enabled".to_string(), v.to_string());
        }
        if let Some(v) = self.ssl_ca_cert {
            map.insert("ssl_ca_cert".to_string(), v);
        }
        if let Some(v) = self.ssl_cert {
            map.insert("ssl_cert".to_string(), v);
        }
        if let Some(v) = self.ssl_key {
            map.insert("ssl_key".to_string(), v);
        }
        if let Some(v) = self.ssl_verify_peer {
            map.insert("ssl_verify_peer".to_string(), v.to_string());
        }

        map
    }
}

/// Information about a connected session.
#[derive(Debug, Clone)]
pub struct SessionInfo {
    pub id: SessionId,
    pub cluster_name: Option<String>,
    pub db_version: Option<String>,
}

/// Options for query execution.
#[derive(Debug, Clone)]
pub struct QueryOptions {
    pub consistency: Consistency,
    pub page_size: Option<i32>,
}

impl Default for QueryOptions {
    fn default() -> Self {
        Self {
            consistency: Consistency::One,
            page_size: None,
        }
    }
}

/// Result of a query execution.
#[derive(Debug)]
pub enum QueryResult {
    /// No rows returned (e.g., INSERT, UPDATE, DELETE)
    Void,
    /// Rows returned with column metadata
    Rows {
        columns: Vec<ColumnInfo>,
        rows: Vec<Vec<Option<CqlValue>>>,
    },
    /// Schema change result
    SchemaChange { change_type: String },
}

/// Column metadata.
#[derive(Debug, Clone)]
pub struct ColumnInfo {
    pub keyspace: String,
    pub table: String,
    pub name: String,
    pub type_code: u16,
}

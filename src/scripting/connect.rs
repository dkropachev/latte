use crate::config::{ConnectionConf, DriverConf};
use crate::ipc::{DockerConfig, DockerManager, IpcClient, SessionConfig, SessionManager};
use crate::scripting::cass_error::{CassError, CassErrorKind};
use crate::scripting::context::Context;
use openssl::ssl::{SslContextBuilder, SslFiletype, SslMethod, SslVerifyMode};
use scylla::client::session::TlsContext;
use scylla::client::PoolSize;
use scylla::policies::load_balancing::DefaultPolicy;
use std::sync::Arc;

use scylla::client::execution_profile::ExecutionProfile;
use scylla::client::session_builder::SessionBuilder;

fn tls_context(conf: &&ConnectionConf) -> Result<Option<TlsContext>, Box<CassError>> {
    if conf.ssl {
        let mut ssl = SslContextBuilder::new(SslMethod::tls())?;
        if let Some(path) = &conf.ssl_ca_cert_file {
            ssl.set_ca_file(path)?;
        }
        if let Some(path) = &conf.ssl_cert_file {
            ssl.set_certificate_file(path, SslFiletype::PEM)?;
        }
        if let Some(path) = &conf.ssl_key_file {
            ssl.set_private_key_file(path, SslFiletype::PEM)?;
        }
        if conf.ssl_peer_verification {
            ssl.set_verify(SslVerifyMode::PEER);
        }
        Ok(Some(TlsContext::from(ssl.build())))
    } else {
        Ok(None)
    }
}

/// Configures connection to Cassandra.
pub async fn connect(conf: &ConnectionConf) -> Result<Context, CassError> {
    let mut policy_builder = DefaultPolicy::builder().token_aware(true);
    let mut datacenter: String = "".to_string();
    let mut rack: String = "".to_string();
    if let Some(dc) = &conf.datacenter {
        if let Some(current_rack) = &conf.rack {
            policy_builder = policy_builder
                .prefer_datacenter_and_rack(dc.to_owned(), current_rack.to_owned())
                .permit_dc_failover(true);
            rack = current_rack.clone();
        } else {
            policy_builder = policy_builder
                .prefer_datacenter(dc.to_owned())
                .permit_dc_failover(true);
        }
        datacenter = dc.clone();
    } else if let Some(_rack) = &conf.rack {
        panic!("Datacenter must also be defined when rack is defined");
    }
    let profile = ExecutionProfile::builder()
        .consistency(conf.consistency.consistency())
        .serial_consistency(Some(conf.serial_consistency.serial_consistency()))
        .load_balancing_policy(policy_builder.build())
        .request_timeout(Some(conf.request_timeout))
        .build();

    let scylla_session = SessionBuilder::new()
        .known_nodes(&conf.addresses)
        .pool_size(PoolSize::PerShard(conf.count))
        .user(&conf.user, &conf.password)
        .tls_context(tls_context(&conf)?)
        .default_execution_profile_handle(profile.into_handle())
        .build()
        .await
        .map_err(|e| CassError(CassErrorKind::FailedToConnect(conf.addresses.clone(), e)))?;
    Ok(Context::new(
        Some(scylla_session),
        conf.page_size.get() as u64,
        datacenter,
        rack,
        conf.retry_number,
        conf.retry_interval,
        conf.validation_strategy,
    ))
}

pub struct ClusterInfo {
    pub name: String,
    pub db_version: String,
}

/// Configures connection via IPC to the external driver counterpart.
/// Returns the Context and an optional DockerManager that must be kept alive
/// for the duration of the benchmark (dropping it stops the container).
pub async fn connect_ipc(
    conn_conf: &ConnectionConf,
    driver_conf: &DriverConf,
) -> Result<(Context, Option<DockerManager>), CassError> {
    // Start Docker container if --driver-image was specified
    let docker_manager = if let Some(image) = &driver_conf.driver_image {
        eprintln!("info: Starting driver adapter container ({})...", image);
        let docker_config = DockerConfig {
            image: image.clone(),
            socket_path: driver_conf.socket_path(),
            contact_points: conn_conf.addresses.clone(),
            keyspace: None,
            container_name: None,
        };
        let mut manager = DockerManager::new(docker_config);
        manager.start().await.map_err(|e| {
            CassError(CassErrorKind::Error(format!(
                "Failed to start driver container: {}",
                e
            )))
        })?;
        eprintln!(
            "info: Driver container started (id={})",
            manager.container_id().unwrap_or("unknown")
        );
        Some(manager)
    } else {
        None
    };

    let socket_path = driver_conf.socket_path();

    eprintln!(
        "info: Connecting to external driver at {}...",
        socket_path.display()
    );

    let client = IpcClient::connect(&socket_path).await.map_err(|e| {
        CassError(CassErrorKind::Error(format!(
            "Failed to connect to driver: {}",
            e
        )))
    })?;

    let session_manager = Arc::new(SessionManager::new(Arc::new(client)));

    // Create a session with the driver, passing all relevant connection parameters
    let mut session_config = SessionConfig::new()
        .contact_points(conn_conf.addresses.join(","))
        .connections_per_shard(conn_conf.count.get() as u32)
        .request_timeout_ms(conn_conf.request_timeout.as_millis() as u64)
        .consistency(format!("{:?}", conn_conf.consistency))
        .serial_consistency(format!("{:?}", conn_conf.serial_consistency));

    // Authentication
    if !conn_conf.user.is_empty() {
        session_config = session_config.credentials(&conn_conf.user, &conn_conf.password);
    }

    // Topology awareness
    if let Some(dc) = &conn_conf.datacenter {
        session_config = session_config.datacenter(dc.clone());
    }
    if let Some(rack) = &conn_conf.rack {
        session_config = session_config.rack(rack.clone());
    }

    // SSL/TLS
    if conn_conf.ssl {
        session_config = session_config.ssl_enabled(true);
        session_config = session_config.ssl_verify_peer(conn_conf.ssl_peer_verification);
        // Note: For SSL certs with Docker, the driver container needs access to the cert files.
        // The paths below are host paths - consider mounting them or passing PEM content.
        if let Some(path) = &conn_conf.ssl_ca_cert_file {
            session_config = session_config.ssl_ca_cert(path.display().to_string());
        }
        if let Some(path) = &conn_conf.ssl_cert_file {
            session_config = session_config.ssl_cert(path.display().to_string());
        }
        if let Some(path) = &conn_conf.ssl_key_file {
            session_config = session_config.ssl_key(path.display().to_string());
        }
    }

    let session_id = session_manager
        .create_session(session_config)
        .await
        .map_err(|e| {
            CassError(CassErrorKind::Error(format!(
                "Failed to create IPC session: {}",
                e
            )))
        })?;

    eprintln!("info: Connected via IPC (session_id={})", session_id);

    let datacenter = conn_conf.datacenter.clone().unwrap_or_default();
    let rack = conn_conf.rack.clone().unwrap_or_default();

    let context = Context::new_ipc(
        session_manager,
        session_id,
        conn_conf.page_size.get() as u64,
        datacenter,
        rack,
        conn_conf.retry_number,
        conn_conf.retry_interval,
        conn_conf.validation_strategy,
        conn_conf.consistency.consistency(),
    );

    Ok((context, docker_manager))
}

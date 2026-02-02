#include "session.h"

#include <algorithm>
#include <cctype>
#include <fstream>
#include <sstream>
#include <stdexcept>

#include "logging.h"

namespace latte {

// Session implementation
Session::Session(uint64_t id, CassSession *session, CassCluster *cluster)
    : id_(id), session_(session), cluster_(cluster) {}

Session::~Session() {
  // Free all prepared statements
  {
    std::unique_lock lock(prepared_mutex_);
    for (auto &[key, prepared] : prepared_cache_) {
      cass_prepared_free(prepared);
    }
    prepared_cache_.clear();
  }

  // Close session
  if (session_) {
    CassFuture *close_future = cass_session_close(session_);
    cass_future_wait(close_future);
    cass_future_free(close_future);
    cass_session_free(session_);
  }

  // Free cluster
  if (cluster_) {
    cass_cluster_free(cluster_);
  }
}

const CassPrepared *Session::get_prepared(const std::string &key) const {
  std::shared_lock lock(prepared_mutex_);
  auto it = prepared_cache_.find(key);
  if (it != prepared_cache_.end()) {
    return it->second;
  }
  return nullptr;
}

void Session::cache_prepared(const std::string &key,
                             const CassPrepared *prepared) {
  std::unique_lock lock(prepared_mutex_);
  prepared_cache_[key] = prepared;
}

// SessionRegistry implementation
SessionRegistry::SessionRegistry(const DriverConfig &config)
    : config_(config) {}

SessionRegistry::~SessionRegistry() {
  std::unique_lock lock(mutex_);
  sessions_.clear();
}

std::pair<uint64_t, std::string> SessionRegistry::create_session(
    const std::map<std::string, std::string> &params) {

  CassCluster *cluster = create_cluster(params);
  CassSession *cass_session = cass_session_new();

  // Get keyspace from params or config
  std::string keyspace;
  auto it = params.find("keyspace");
  if (it != params.end() && !it->second.empty()) {
    keyspace = it->second;
  } else {
    keyspace = config_.keyspace;
  }

  // Connect to cluster
  CassFuture *connect_future;
  if (!keyspace.empty()) {
    connect_future =
        cass_session_connect_keyspace(cass_session, cluster, keyspace.c_str());
  } else {
    connect_future = cass_session_connect(cass_session, cluster);
  }

  cass_future_wait(connect_future);
  CassError rc = cass_future_error_code(connect_future);

  if (rc != CASS_OK) {
    const char *message;
    size_t message_length;
    cass_future_error_message(connect_future, &message, &message_length);
    std::string error_msg(message, message_length);

    cass_future_free(connect_future);
    cass_session_free(cass_session);
    cass_cluster_free(cluster);

    return {0, "Failed to connect: " + error_msg};
  }

  cass_future_free(connect_future);

  // Create session wrapper
  uint64_t session_id = next_id_.fetch_add(1);
  auto session = std::make_shared<Session>(session_id, cass_session, cluster);

  {
    std::unique_lock lock(mutex_);
    sessions_[session_id] = session;
  }

  LOG_INFO("Created session " << session_id);
  return {session_id, ""};
}

std::shared_ptr<Session> SessionRegistry::get(uint64_t session_id) const {
  std::shared_lock lock(mutex_);
  auto it = sessions_.find(session_id);
  if (it != sessions_.end()) {
    return it->second;
  }
  return nullptr;
}

void SessionRegistry::remove(uint64_t session_id) {
  std::unique_lock lock(mutex_);
  sessions_.erase(session_id);
}

CassCluster *SessionRegistry::create_cluster(
    const std::map<std::string, std::string> &params) {

  CassCluster *cluster = cass_cluster_new();

  // Contact points
  std::string contact_points = config_.contact_points;
  auto it = params.find("contact_points");
  if (it != params.end() && !it->second.empty()) {
    contact_points = it->second;
  }
  cass_cluster_set_contact_points(cluster, contact_points.c_str());

  // Authentication
  it = params.find("username");
  auto pw_it = params.find("password");
  if (it != params.end() && pw_it != params.end() && !it->second.empty() &&
      !pw_it->second.empty()) {
    cass_cluster_set_credentials(cluster, it->second.c_str(),
                                 pw_it->second.c_str());
  }

  // Datacenter-aware load balancing
  it = params.find("datacenter");
  if (it != params.end() && !it->second.empty()) {
    cass_cluster_set_load_balance_dc_aware(cluster, it->second.c_str(), 0,
                                           cass_false);
  }

  // Request timeout
  it = params.find("request_timeout_ms");
  if (it != params.end() && !it->second.empty()) {
    try {
      unsigned long timeout = std::stoul(it->second);
      cass_cluster_set_request_timeout(cluster, static_cast<unsigned>(timeout));
    } catch (...) {
      // Ignore invalid timeout
    }
  }

  // Connections per shard (connections per host in cpp-driver)
  it = params.find("connections_per_shard");
  if (it != params.end() && !it->second.empty()) {
    try {
      unsigned int conns = static_cast<unsigned int>(std::stoul(it->second));
      cass_cluster_set_core_connections_per_host(cluster, conns);
    } catch (...) {
      // Ignore invalid value
    }
  }

  // Default consistency
  it = params.find("consistency");
  if (it != params.end() && !it->second.empty()) {
    CassConsistency consistency = parse_consistency(it->second);
    cass_cluster_set_consistency(cluster, consistency);
  }

  // Serial consistency (for LWT)
  it = params.find("serial_consistency");
  if (it != params.end() && !it->second.empty()) {
    CassConsistency serial = parse_consistency(it->second);
    cass_cluster_set_serial_consistency(cluster, serial);
  }

  // Connect timeout
  it = params.find("connect_timeout_ms");
  if (it != params.end() && !it->second.empty()) {
    try {
      unsigned long timeout = std::stoul(it->second);
      cass_cluster_set_connect_timeout(cluster, static_cast<unsigned>(timeout));
    } catch (...) {
      // Ignore invalid timeout
    }
  }

  // SSL/TLS configuration
  it = params.find("ssl_enabled");
  if (it != params.end() && (it->second == "true" || it->second == "1")) {
    CassSsl *ssl = cass_ssl_new();

    // Verify peer certificate
    auto verify_it = params.find("ssl_verify_peer");
    if (verify_it != params.end() &&
        (verify_it->second == "false" || verify_it->second == "0")) {
      cass_ssl_set_verify_flags(ssl, CASS_SSL_VERIFY_NONE);
    } else {
      cass_ssl_set_verify_flags(ssl, CASS_SSL_VERIFY_PEER_CERT);
    }

    // CA certificate
    auto ca_it = params.find("ssl_ca_cert");
    if (ca_it != params.end() && !ca_it->second.empty()) {
      // Check if it's a file path or PEM content
      if (ca_it->second.find("-----BEGIN") != std::string::npos) {
        // PEM content
        cass_ssl_add_trusted_cert(ssl, ca_it->second.c_str());
      } else {
        // File path - read the file
        std::ifstream file(ca_it->second);
        if (file) {
          std::stringstream buffer;
          buffer << file.rdbuf();
          cass_ssl_add_trusted_cert(ssl, buffer.str().c_str());
        } else {
          LOG_WARN("Could not read CA certificate file: " << ca_it->second);
        }
      }
    }

    // Client certificate
    auto cert_it = params.find("ssl_cert");
    if (cert_it != params.end() && !cert_it->second.empty()) {
      if (cert_it->second.find("-----BEGIN") != std::string::npos) {
        cass_ssl_set_cert(ssl, cert_it->second.c_str());
      } else {
        std::ifstream file(cert_it->second);
        if (file) {
          std::stringstream buffer;
          buffer << file.rdbuf();
          cass_ssl_set_cert(ssl, buffer.str().c_str());
        } else {
          LOG_WARN(
              "Could not read client certificate file: " << cert_it->second);
        }
      }
    }

    // Client private key
    auto key_it = params.find("ssl_key");
    if (key_it != params.end() && !key_it->second.empty()) {
      if (key_it->second.find("-----BEGIN") != std::string::npos) {
        cass_ssl_set_private_key(ssl, key_it->second.c_str(), nullptr);
      } else {
        std::ifstream file(key_it->second);
        if (file) {
          std::stringstream buffer;
          buffer << file.rdbuf();
          cass_ssl_set_private_key(ssl, buffer.str().c_str(), nullptr);
        } else {
          LOG_WARN("Could not read private key file: " << key_it->second);
        }
      }
    }

    cass_cluster_set_ssl(cluster, ssl);
    cass_ssl_free(ssl);
    LOG_INFO("SSL/TLS enabled");
  }

  return cluster;
}

CassConsistency parse_consistency(const std::string &s) {
  std::string upper = s;
  std::transform(upper.begin(), upper.end(), upper.begin(), ::toupper);

  if (upper == "ANY")
    return CASS_CONSISTENCY_ANY;
  if (upper == "ONE")
    return CASS_CONSISTENCY_ONE;
  if (upper == "TWO")
    return CASS_CONSISTENCY_TWO;
  if (upper == "THREE")
    return CASS_CONSISTENCY_THREE;
  if (upper == "QUORUM")
    return CASS_CONSISTENCY_QUORUM;
  if (upper == "ALL")
    return CASS_CONSISTENCY_ALL;
  if (upper == "LOCAL_QUORUM")
    return CASS_CONSISTENCY_LOCAL_QUORUM;
  if (upper == "EACH_QUORUM")
    return CASS_CONSISTENCY_EACH_QUORUM;
  if (upper == "LOCAL_ONE")
    return CASS_CONSISTENCY_LOCAL_ONE;
  if (upper == "SERIAL")
    return CASS_CONSISTENCY_SERIAL;
  if (upper == "LOCAL_SERIAL")
    return CASS_CONSISTENCY_LOCAL_SERIAL;

  return CASS_CONSISTENCY_LOCAL_QUORUM; // Default
}

} // namespace latte

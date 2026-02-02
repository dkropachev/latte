#pragma once

#include <atomic>
#include <cassandra.h>
#include <map>
#include <memory>
#include <mutex>
#include <optional>
#include <shared_mutex>
#include <string>
#include <unordered_map>
#include <vector>

#include "config.h"
#include "protocol.h"

namespace latte {

// Prepared statement info
struct PreparedStatement {
  const CassPrepared *prepared;
  std::vector<TypeCode> bind_types;
};

// A single database session with the cpp-driver
class Session {
public:
  Session(uint64_t id, CassSession *session, CassCluster *cluster);
  ~Session();

  // Non-copyable
  Session(const Session &) = delete;
  Session &operator=(const Session &) = delete;

  uint64_t id() const { return id_; }
  CassSession *raw() const { return session_; }

  // Prepared statement cache
  const CassPrepared *get_prepared(const std::string &key) const;
  void cache_prepared(const std::string &key, const CassPrepared *prepared);

private:
  uint64_t id_;
  CassSession *session_;
  CassCluster *cluster_;

  mutable std::shared_mutex prepared_mutex_;
  std::unordered_map<std::string, const CassPrepared *> prepared_cache_;
};

// Session registry managing all active sessions
class SessionRegistry {
public:
  explicit SessionRegistry(const DriverConfig &config);
  ~SessionRegistry();

  // Create a new session with parameters
  std::pair<uint64_t, std::string>
  create_session(const std::map<std::string, std::string> &params);

  // Get session by ID
  std::shared_ptr<Session> get(uint64_t session_id) const;

  // Remove session by ID
  void remove(uint64_t session_id);

private:
  CassCluster *create_cluster(const std::map<std::string, std::string> &params);

  DriverConfig config_;
  std::atomic<uint64_t> next_id_{1};

  mutable std::shared_mutex mutex_;
  std::map<uint64_t, std::shared_ptr<Session>> sessions_;
};

// Convert consistency string to CassConsistency
CassConsistency parse_consistency(const std::string &s);

} // namespace latte

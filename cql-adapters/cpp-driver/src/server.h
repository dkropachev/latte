#pragma once

#include <atomic>
#include <condition_variable>
#include <functional>
#include <memory>
#include <mutex>
#include <queue>
#include <uv.h>

#include "config.h"
#include "protocol.h"
#include "session.h"

namespace latte {

// Semaphore for limiting concurrent requests (lock-free for try_acquire/release)
class Semaphore {
public:
  explicit Semaphore(uint32_t count) : count_(count), max_count_(count) {}

  // Lock-free try_acquire using compare-exchange loop
  bool try_acquire() {
    uint32_t current = count_.load(std::memory_order_relaxed);
    while (current > 0) {
      if (count_.compare_exchange_weak(current, current - 1,
                                        std::memory_order_acquire,
                                        std::memory_order_relaxed)) {
        return true;
      }
      // current is updated by compare_exchange_weak on failure
    }
    return false;
  }

  // Blocking acquire (rarely used in async code path)
  void acquire() {
    while (!try_acquire()) {
      std::unique_lock<std::mutex> lock(mutex_);
      cv_.wait(lock, [this] { return count_.load(std::memory_order_relaxed) > 0; });
    }
  }

  // Lock-free release
  void release() {
    uint32_t prev = count_.fetch_add(1, std::memory_order_release);
    // Notify waiters if there might be any (if count was 0)
    if (prev == 0) {
      cv_.notify_one();
    }
  }

  uint32_t available() const {
    return count_.load(std::memory_order_relaxed);
  }

  uint32_t max_count() const { return max_count_; }

private:
  std::mutex mutex_;  // Only for blocking acquire
  std::condition_variable cv_;
  std::atomic<uint32_t> count_;
  uint32_t max_count_;
};

} // namespace latte

namespace latte {

// Forward declarations
class Server;
class Connection;

// Response to be written
struct Response {
  int16_t stream;
  Buffer data;
};

// Forward declaration
class Connection;

// Context for async Cassandra operations
struct AsyncContext {
  Connection *conn;
  int16_t stream;
  std::chrono::steady_clock::time_point start_time;
  CassStatement *statement;  // May be null for prepare
  CassBatch *batch;          // May be null for non-batch operations

  AsyncContext(Connection *c, int16_t s)
      : conn(c), stream(s), start_time(std::chrono::steady_clock::now()),
        statement(nullptr), batch(nullptr) {}

  ~AsyncContext() {
    if (statement) cass_statement_free(statement);
    if (batch) cass_batch_free(batch);
  }

  // Prevent copying
  AsyncContext(const AsyncContext &) = delete;
  AsyncContext &operator=(const AsyncContext &) = delete;
};

// Connection state
class Connection {
public:
  Connection(Server *server, uv_stream_t *client);
  ~Connection();

  void start();
  void close();
  void send_response(int16_t stream, Opcode opcode, Buffer body);

private:
  static void alloc_cb(uv_handle_t *handle, size_t suggested_size,
                       uv_buf_t *buf);
  static void read_cb(uv_stream_t *stream, ssize_t nread, const uv_buf_t *buf);
  static void write_cb(uv_write_t *req, int status);
  static void close_cb(uv_handle_t *handle);

  void on_data(const uint8_t *data, size_t len);
  void process_frame(Frame frame);
  void handle_create_session(int16_t stream, Buffer &body);
  void handle_query(int16_t stream, Buffer &body);
  void handle_prepare(int16_t stream, Buffer &body);
  void handle_execute(int16_t stream, Buffer &body);
  void handle_batch(int16_t stream, Buffer &body);
  void send_error(int16_t stream, ErrorCode code, const std::string &message);
  void flush_writes();

  // Async operation callbacks (called from Cassandra driver thread)
  static void query_callback(CassFuture *future, void *data);
  static void prepare_callback(CassFuture *future, void *data);
  static void execute_callback(CassFuture *future, void *data);
  static void batch_callback(CassFuture *future, void *data);

  // Process async result on libuv thread
  void process_query_result(int16_t stream, CassFuture *future,
                            std::chrono::steady_clock::time_point start_time,
                            CassStatement *statement);
  void process_prepare_result(int16_t stream, CassFuture *future,
                              const std::string &statement_key,
                              uint64_t session_id);
  void process_execute_result(int16_t stream, CassFuture *future,
                              std::chrono::steady_clock::time_point start_time,
                              CassStatement *statement);
  void process_batch_result(int16_t stream, CassFuture *future,
                            std::chrono::steady_clock::time_point start_time,
                            CassBatch *batch);

  Server *server_;
  uv_stream_t *client_;
  bool closing_ = false;

  // Read buffer for accumulating frame data (with offset tracking to avoid O(n) erase)
  std::vector<uint8_t> read_buf_;
  size_t read_buf_offset_ = 0; // Start of unprocessed data
  static constexpr size_t COMPACT_THRESHOLD = 32 * 1024; // Compact when offset exceeds this

  // Pre-allocated buffer for libuv reads (avoids per-read allocation)
  static constexpr size_t ALLOC_BUF_SIZE = 64 * 1024;
  char alloc_buf_[ALLOC_BUF_SIZE];

  // Async work items for processing Cassandra results on libuv thread
  struct AsyncWork {
    enum class Type { QUERY, PREPARE, EXECUTE, BATCH };
    Type type;
    int16_t stream;
    CassFuture *future;
    std::chrono::steady_clock::time_point start_time;
    CassStatement *statement;
    CassBatch *batch;
    std::string statement_key;  // For prepare
    uint64_t session_id;        // For prepare to cache in correct session
  };
  std::mutex async_work_mutex_;
  std::queue<AsyncWork> async_work_queue_;
  uv_async_t *async_handle_ = nullptr;
  bool async_handle_closing_ = false;  // Track if async handle is being closed
  static void async_work_cb(uv_async_t *handle);

  // Write queue (scatter-gather I/O)
  std::mutex write_mutex_;
  std::queue<Response> write_queue_;
  std::vector<Buffer> write_pending_;  // Buffers being written (scatter-gather)
  std::vector<uv_buf_t> write_bufs_;   // uv_buf_t array for writev
  static constexpr size_t MAX_WRITE_BUFS = 64;     // Max buffers per writev call
  static constexpr size_t FLUSH_THRESHOLD = 64 * 1024;  // Total bytes threshold
  bool write_in_progress_ = false;
};

// Main server
class Server {
public:
  explicit Server(const DriverConfig &config);
  ~Server();

  void run();
  void stop();

  SessionRegistry &sessions() { return sessions_; }
  uv_loop_t *loop() { return loop_; }
  uint32_t max_inflight() const { return config_.max_inflight; }
  Semaphore &inflight_semaphore() { return inflight_sem_; }

private:
  static void connection_cb(uv_stream_t *server, int status);
  static void signal_cb(uv_signal_t *handle, int signum);

  DriverConfig config_;
  SessionRegistry sessions_;
  Semaphore inflight_sem_;
  bool stopping_ = false;

  uv_loop_t *loop_ = nullptr;
  uv_pipe_t server_pipe_;
  uv_signal_t sigint_;
  uv_signal_t sigterm_;

  std::mutex conn_mutex_;
  std::vector<std::unique_ptr<Connection>> connections_;
};

// Run the server (entry point)
int run_server(const DriverConfig &config);

} // namespace latte

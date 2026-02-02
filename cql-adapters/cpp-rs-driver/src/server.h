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

// Lock-free semaphore for limiting concurrent requests
// Uses atomic compare-exchange for try_acquire/release (hot path)
class Semaphore {
public:
  explicit Semaphore(uint32_t count)
      : count_(count), max_count_(count) {}

  // Lock-free try_acquire using compare-exchange
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

  // Blocking acquire - falls back to spin-wait
  void acquire() {
    while (!try_acquire()) {
      // Spin with pause hint to reduce contention
      #if defined(__x86_64__) || defined(__i386__)
      __builtin_ia32_pause();
      #elif defined(__aarch64__)
      asm volatile("yield");
      #endif
    }
  }

  // Lock-free release using atomic increment
  void release() {
    count_.fetch_add(1, std::memory_order_release);
  }

  uint32_t available() const {
    return count_.load(std::memory_order_relaxed);
  }

  uint32_t max_count() const { return max_count_; }

private:
  std::atomic<uint32_t> count_;
  uint32_t max_count_;
};

// Thread-safe MPSC (Multiple Producer, Single Consumer) queue
// Uses mutex for simplicity and correctness
template <typename T> class MPSCQueue {
public:
  struct Node {
    // Placeholder for compatibility
  };

  MPSCQueue() = default;

  ~MPSCQueue() {
    // Drain remaining nodes
    T *node;
    while ((node = pop()) != nullptr) {
      delete node;
    }
  }

  // Push a node (called by multiple producers)
  void push(T *node) {
    std::lock_guard<std::mutex> lock(mutex_);
    queue_.push(node);
  }

  // Pop a node (called by single consumer only)
  // Returns nullptr if queue is empty
  T *pop() {
    std::lock_guard<std::mutex> lock(mutex_);
    if (queue_.empty()) {
      return nullptr;
    }
    T *node = queue_.front();
    queue_.pop();
    return node;
  }

  bool empty() const {
    std::lock_guard<std::mutex> lock(mutex_);
    return queue_.empty();
  }

private:
  mutable std::mutex mutex_;
  std::queue<T *> queue_;
};

} // namespace latte

namespace latte {

// Forward declarations
class Server;
class Connection;

// Async future callback context
// Holds all state needed to process a Cassandra future result
struct FutureContext {
  Connection *conn;
  int16_t stream;
  std::chrono::steady_clock::time_point start_time;
  CassStatement *statement;   // For QUERY/EXECUTE
  CassBatch *batch;           // For BATCH
  bool is_prepare;            // For PREPARE
  std::string statement_key;  // For PREPARE
  std::shared_ptr<Session> session; // Keep session alive

  FutureContext(Connection *c, int16_t s,
                std::chrono::steady_clock::time_point t)
      : conn(c), stream(s), start_time(t), statement(nullptr),
        batch(nullptr), is_prepare(false) {}
};

// Completed future node for async processing queue
struct CompletedFutureNode : MPSCQueue<CompletedFutureNode>::Node {
  FutureContext *ctx;
  CassFuture *future;

  CompletedFutureNode(FutureContext *c, CassFuture *f)
      : ctx(c), future(f) {}
};

// Response node for lock-free queue
struct ResponseNode : MPSCQueue<ResponseNode>::Node {
  int16_t stream;
  Buffer data;

  ResponseNode(int16_t s, Buffer d) : stream(s), data(std::move(d)) {}
};

// Legacy Response struct for compatibility
struct Response {
  int16_t stream;
  Buffer data;
};

// Connection state
class Connection {
public:
  Connection(Server *server, uv_stream_t *client);
  ~Connection();

  void start();
  void close();
  void send_response(int16_t stream, Opcode opcode, Buffer body);

  // Async future handling
  void queue_completed_future(FutureContext *ctx, CassFuture *future);

private:
  static void alloc_cb(uv_handle_t *handle, size_t suggested_size,
                       uv_buf_t *buf);
  static void read_cb(uv_stream_t *stream, ssize_t nread, const uv_buf_t *buf);
  static void write_cb(uv_write_t *req, int status);
  static void close_cb(uv_handle_t *handle);
  static void async_cb(uv_async_t *handle);
  static void future_cb(CassFuture *future, void *data);

  void on_data(const uint8_t *data, size_t len);
  void process_frame(Frame frame);
  void process_completed_futures();
  void process_completed_future(FutureContext *ctx, CassFuture *future);
  void handle_create_session(int16_t stream, Buffer &body);
  void handle_query(int16_t stream, Buffer &body);
  void handle_prepare(int16_t stream, Buffer &body);
  void handle_execute(int16_t stream, Buffer &body);
  void handle_batch(int16_t stream, Buffer &body);
  void send_error(int16_t stream, ErrorCode code, const std::string &message);
  void flush_writes();

  Server *server_;
  uv_stream_t *client_;
  bool closing_ = false;

  // Read buffer (accumulated frame data) with lazy compaction
  std::vector<uint8_t> read_buf_;
  size_t read_buf_head_ = 0; // Offset of unprocessed data
  static constexpr size_t COMPACT_THRESHOLD = 32 * 1024;

  // Helper to get effective size and data pointer
  size_t read_buf_size() const { return read_buf_.size() - read_buf_head_; }
  const uint8_t *read_buf_data() const {
    return read_buf_.data() + read_buf_head_;
  }
  void read_buf_consume(size_t n) {
    read_buf_head_ += n;
    // Compact when consumed portion is large
    if (read_buf_head_ >= COMPACT_THRESHOLD) {
      read_buf_.erase(read_buf_.begin(), read_buf_.begin() + read_buf_head_);
      read_buf_head_ = 0;
    }
  }

  // Pre-allocated buffer for libuv alloc callback (eliminates malloc/free per read)
  static constexpr size_t ALLOC_BUF_SIZE = 64 * 1024;
  char alloc_buf_[ALLOC_BUF_SIZE];

  // Write queue (lock-free MPSC)
  MPSCQueue<ResponseNode> write_queue_;
  std::mutex flush_mutex_; // Only protects flush_writes to ensure single writer
  std::vector<uint8_t> write_buf_;
  static constexpr size_t WRITE_BUF_SIZE = 64 * 1024;
  static constexpr size_t FLUSH_THRESHOLD = 32 * 1024;
  std::atomic<bool> write_in_progress_{false};

  // Async future handling (non-blocking Cassandra I/O)
  uv_async_t async_handle_;
  MPSCQueue<CompletedFutureNode> completed_futures_;
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

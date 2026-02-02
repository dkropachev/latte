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

// Semaphore for limiting concurrent requests
class Semaphore {
public:
  explicit Semaphore(uint32_t count) : count_(count), max_count_(count) {}

  bool try_acquire() {
    std::lock_guard<std::mutex> lock(mutex_);
    if (count_ > 0) {
      --count_;
      return true;
    }
    return false;
  }

  void acquire() {
    std::unique_lock<std::mutex> lock(mutex_);
    cv_.wait(lock, [this] { return count_ > 0; });
    --count_;
  }

  void release() {
    std::lock_guard<std::mutex> lock(mutex_);
    ++count_;
    cv_.notify_one();
  }

  uint32_t available() const {
    std::lock_guard<std::mutex> lock(mutex_);
    return count_;
  }

  uint32_t max_count() const { return max_count_; }

private:
  mutable std::mutex mutex_;
  std::condition_variable cv_;
  uint32_t count_;
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

  Server *server_;
  uv_stream_t *client_;
  bool closing_ = false;

  // Read buffer
  std::vector<uint8_t> read_buf_;

  // Write queue
  std::mutex write_mutex_;
  std::queue<Response> write_queue_;
  std::vector<uint8_t> write_buf_;
  static constexpr size_t WRITE_BUF_SIZE = 64 * 1024;
  static constexpr size_t FLUSH_THRESHOLD = 32 * 1024;
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

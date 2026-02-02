#include "server.h"

#include <chrono>
#include <cstring>
#include <iostream>
#include <unistd.h>

#include "logging.h"
#include "types.h"

namespace latte {

// Connection implementation
Connection::Connection(Server *server, uv_stream_t *client)
    : server_(server), client_(client) {
  client_->data = this;
  write_buf_.reserve(WRITE_BUF_SIZE);

  // Initialize async handle for cross-thread future completion notifications
  uv_async_init(server->loop(), &async_handle_, async_cb);
  async_handle_.data = this;
}

Connection::~Connection() {
  if (client_ && !closing_) {
    close();
  }
}

void Connection::start() { uv_read_start(client_, alloc_cb, read_cb); }

void Connection::close() {
  if (closing_)
    return;
  closing_ = true;
  uv_read_stop(client_);
  // Only close handles if they're not already closing
  if (!uv_is_closing(reinterpret_cast<uv_handle_t *>(&async_handle_))) {
    uv_close(reinterpret_cast<uv_handle_t *>(&async_handle_), nullptr);
  }
  if (!uv_is_closing(reinterpret_cast<uv_handle_t *>(client_))) {
    uv_close(reinterpret_cast<uv_handle_t *>(client_), close_cb);
  }
}

void Connection::alloc_cb(uv_handle_t *handle, size_t suggested_size,
                          uv_buf_t *buf) {
  auto *conn = static_cast<Connection *>(handle->data);
  (void)suggested_size; // Use our fixed-size buffer instead
  buf->base = conn->alloc_buf_;
  buf->len = ALLOC_BUF_SIZE;
}

void Connection::read_cb(uv_stream_t *stream, ssize_t nread,
                         const uv_buf_t *buf) {
  auto *conn = static_cast<Connection *>(stream->data);
  (void)buf; // Buffer is pre-allocated, no need to free

  if (nread > 0) {
    conn->on_data(reinterpret_cast<const uint8_t *>(conn->alloc_buf_),
                  static_cast<size_t>(nread));
  } else if (nread < 0) {
    if (nread != UV_EOF) {
      LOG_ERROR("Read error: " << uv_strerror(static_cast<int>(nread)));
    }
    conn->close();
  }
}

void Connection::write_cb(uv_write_t *req, int status) {
  auto *conn = static_cast<Connection *>(req->data);
  delete req;

  if (status < 0) {
    LOG_ERROR("Write error: " << uv_strerror(status));
    conn->close();
    return;
  }

  conn->write_in_progress_.store(false, std::memory_order_release);
  conn->flush_writes();
}

void Connection::close_cb(uv_handle_t *handle) {
  (void)handle->data; // Connection will be cleaned up by Server
  delete reinterpret_cast<uv_tcp_t *>(handle);
}

void Connection::async_cb(uv_async_t *handle) {
  auto *conn = static_cast<Connection *>(handle->data);
  conn->process_completed_futures();
}

void Connection::future_cb(CassFuture *future, void *data) {
  auto *ctx = static_cast<FutureContext *>(data);
  ctx->conn->queue_completed_future(ctx, future);
}

void Connection::queue_completed_future(FutureContext *ctx, CassFuture *future) {
  auto *node = new CompletedFutureNode(ctx, future);
  completed_futures_.push(node);
  uv_async_send(&async_handle_);
}

void Connection::process_completed_futures() {
  CompletedFutureNode *node;
  while ((node = completed_futures_.pop()) != nullptr) {
    process_completed_future(node->ctx, node->future);
    delete node->ctx;
    delete node;
  }
  // Check if there might be items stuck due to race condition
  // If queue is not empty after processing, schedule another callback
  if (!completed_futures_.empty()) {
    uv_async_send(&async_handle_);
  }
}

void Connection::process_completed_future(FutureContext *ctx, CassFuture *future) {
  auto end = std::chrono::steady_clock::now();
  uint64_t latency_ns =
      std::chrono::duration_cast<std::chrono::nanoseconds>(end - ctx->start_time).count();

  CassError rc = cass_future_error_code(future);

  // Handle prepare specially
  if (ctx->is_prepare) {
    if (rc != CASS_OK) {
      const char *message;
      size_t message_length;
      cass_future_error_message(future, &message, &message_length);
      std::string error_msg(message, message_length);

      cass_future_free(future);
      send_error(ctx->stream, ErrorCode::SERVER, error_msg);
    } else {
      const CassPrepared *prepared = cass_future_get_prepared(future);
      ctx->session->cache_prepared(ctx->statement_key, prepared);

      cass_future_free(future);

      Buffer response = build_prepared_result(ctx->statement_key, {});
      send_response(ctx->stream, Opcode::RESULT, std::move(response));
    }
    server_->inflight_semaphore().release();
    return;
  }

  // Handle query/execute/batch
  if (rc != CASS_OK) {
    const char *message;
    size_t message_length;
    cass_future_error_message(future, &message, &message_length);
    std::string error_msg(message, message_length);

    cass_future_free(future);
    if (ctx->statement) cass_statement_free(ctx->statement);
    if (ctx->batch) cass_batch_free(ctx->batch);

    send_error(ctx->stream, ErrorCode::SERVER, error_msg);
    server_->inflight_semaphore().release();
    return;
  }

  const CassResult *result = cass_future_get_result(future);

  Buffer response;
  if (result == nullptr || (cass_result_row_count(result) == 0 &&
      cass_result_column_count(result) == 0)) {
    response = build_void_result(latency_ns);
  } else {
    response = encode_rows_result(result, latency_ns);
  }

  if (result) cass_result_free(result);
  cass_future_free(future);
  if (ctx->statement) cass_statement_free(ctx->statement);
  if (ctx->batch) cass_batch_free(ctx->batch);

  send_response(ctx->stream, Opcode::RESULT, std::move(response));
  server_->inflight_semaphore().release();
}

void Connection::on_data(const uint8_t *data, size_t len) {
  read_buf_.insert(read_buf_.end(), data, data + len);

  while (read_buf_size() >= FRAME_HEADER_SIZE) {
    FrameHeader header = parse_frame_header(read_buf_data());

    if (header.body_length > MAX_BODY_SIZE) {
      send_error(header.stream, ErrorCode::PROTOCOL, "Body too large");
      close();
      return;
    }

    size_t total_size = FRAME_HEADER_SIZE + header.body_length;
    if (read_buf_size() < total_size) {
      break; // Need more data
    }

    // Extract frame with zero-copy view into read_buf_
    Frame frame;
    frame.header = header;
    if (header.body_length > 0) {
      // Create non-owning view into read_buf_ (no copy)
      frame.body =
          Buffer(read_buf_data() + FRAME_HEADER_SIZE, header.body_length);
    }

    // Process frame BEFORE consuming (view must remain valid)
    process_frame(std::move(frame));

    // Mark processed data as consumed (lazy compaction - amortized O(1))
    read_buf_consume(total_size);
  }
}

void Connection::process_frame(Frame frame) {
  int16_t stream = frame.header.stream;

  // Acquire semaphore for inflight limiting (except for session creation)
  // Note: For async operations (QUERY, PREPARE, EXECUTE, BATCH), the semaphore
  // is released in process_completed_future() when the async operation completes.
  if (frame.header.opcode != Opcode::CREATE_SESSION) {
    if (!server_->inflight_semaphore().try_acquire()) {
      LOG_WARN("Inflight limit reached, rejecting request on stream "
               << stream);
      send_error(stream, ErrorCode::OVERLOADED, "Too many inflight requests");
      return;
    }
  }

  try {
    switch (frame.header.opcode) {
    case Opcode::CREATE_SESSION:
      handle_create_session(stream, frame.body);
      break;
    case Opcode::QUERY:
      handle_query(stream, frame.body);
      break;
    case Opcode::PREPARE:
      handle_prepare(stream, frame.body);
      break;
    case Opcode::EXECUTE:
      handle_execute(stream, frame.body);
      break;
    case Opcode::BATCH:
      handle_batch(stream, frame.body);
      break;
    default:
      send_error(stream, ErrorCode::PROTOCOL, "Unknown opcode");
      server_->inflight_semaphore().release();
      break;
    }
  } catch (const std::exception &e) {
    LOG_ERROR("Exception handling frame: " << e.what());
    send_error(stream, ErrorCode::SERVER, e.what());
    server_->inflight_semaphore().release();
  }
}

void Connection::handle_create_session(int16_t stream, Buffer &body) {
  auto params = body.read_string_map();

  auto [session_id, error] = server_->sessions().create_session(params);

  if (session_id == 0) {
    send_error(stream, ErrorCode::SERVER, error);
    return;
  }

  Buffer response = build_session_created_response(session_id);
  send_response(stream, Opcode::SESSION_CREATED, std::move(response));
}

void Connection::handle_query(int16_t stream, Buffer &body) {
  uint64_t session_id = body.read_long();
  std::string query = body.read_long_string();
  uint16_t consistency = body.read_short();
  uint8_t flags = body.read_byte();
  (void)flags; // Currently unused

  auto session = server_->sessions().get(session_id);
  if (!session) {
    send_error(stream, ErrorCode::SERVER, "Session not found");
    server_->inflight_semaphore().release();
    return;
  }

  auto start = std::chrono::steady_clock::now();

  CassStatement *statement = cass_statement_new(query.c_str(), 0);
  cass_statement_set_consistency(statement,
                                 static_cast<CassConsistency>(consistency));

  // Create async callback context
  auto *ctx = new FutureContext(this, stream, start);
  ctx->statement = statement;
  ctx->session = session;

  CassFuture *future = cass_session_execute(session->raw(), statement);
  cass_future_set_callback(future, future_cb, ctx);
}

void Connection::handle_prepare(int16_t stream, Buffer &body) {
  uint64_t session_id = body.read_long();
  std::string query = body.read_long_string();
  std::string statement_key = body.read_string();

  auto session = server_->sessions().get(session_id);
  if (!session) {
    send_error(stream, ErrorCode::SERVER, "Session not found");
    server_->inflight_semaphore().release();
    return;
  }

  // Check if already prepared
  if (session->get_prepared(statement_key)) {
    // Already prepared, return success
    Buffer response = build_prepared_result(statement_key, {});
    send_response(stream, Opcode::RESULT, std::move(response));
    server_->inflight_semaphore().release();
    return;
  }

  auto start = std::chrono::steady_clock::now();

  // Create async callback context
  auto *ctx = new FutureContext(this, stream, start);
  ctx->is_prepare = true;
  ctx->statement_key = statement_key;
  ctx->session = session;

  CassFuture *future = cass_session_prepare(session->raw(), query.c_str());
  cass_future_set_callback(future, future_cb, ctx);
}

void Connection::handle_execute(int16_t stream, Buffer &body) {
  uint64_t session_id = body.read_long();
  std::string statement_key = body.read_string();
  uint16_t consistency = body.read_short();
  uint8_t flags = body.read_byte();

  auto session = server_->sessions().get(session_id);
  if (!session) {
    send_error(stream, ErrorCode::SERVER, "Session not found");
    server_->inflight_semaphore().release();
    return;
  }

  const CassPrepared *prepared = session->get_prepared(statement_key);
  if (!prepared) {
    send_error(stream, ErrorCode::UNPREPARED,
               "Statement not prepared: " + statement_key);
    server_->inflight_semaphore().release();
    return;
  }

  CassStatement *statement = cass_prepared_bind(prepared);
  cass_statement_set_consistency(statement,
                                 static_cast<CassConsistency>(consistency));

  // Bind values if present
  if (flags & 0x01) {
    uint16_t value_count = body.read_short();
    for (uint16_t i = 0; i < value_count; ++i) {
      TypedValue value = read_typed_value(body);
      bind_value_with_schema(statement, i, value, prepared);
    }
  }

  auto start = std::chrono::steady_clock::now();

  // Create async callback context
  auto *ctx = new FutureContext(this, stream, start);
  ctx->statement = statement;
  ctx->session = session;

  CassFuture *future = cass_session_execute(session->raw(), statement);
  cass_future_set_callback(future, future_cb, ctx);
}

void Connection::handle_batch(int16_t stream, Buffer &body) {
  uint64_t session_id = body.read_long();
  uint8_t batch_type = body.read_byte();
  uint16_t statement_count = body.read_short();

  auto session = server_->sessions().get(session_id);
  if (!session) {
    send_error(stream, ErrorCode::SERVER, "Session not found");
    server_->inflight_semaphore().release();
    return;
  }

  CassBatch *batch = cass_batch_new(static_cast<CassBatchType>(batch_type));

  for (uint16_t i = 0; i < statement_count; ++i) {
    uint8_t kind = body.read_byte();
    if (kind != 1) {
      cass_batch_free(batch);
      send_error(stream, ErrorCode::PROTOCOL,
                 "Only prepared statements supported in batch");
      server_->inflight_semaphore().release();
      return;
    }

    std::string statement_key = body.read_string();
    uint16_t value_count = body.read_short();

    const CassPrepared *prepared = session->get_prepared(statement_key);
    if (!prepared) {
      cass_batch_free(batch);
      send_error(stream, ErrorCode::UNPREPARED,
                 "Statement not prepared: " + statement_key);
      server_->inflight_semaphore().release();
      return;
    }

    CassStatement *statement = cass_prepared_bind(prepared);

    for (uint16_t j = 0; j < value_count; ++j) {
      TypedValue value = read_typed_value(body);
      bind_value_with_schema(statement, j, value, prepared);
    }

    cass_batch_add_statement(batch, statement);
    cass_statement_free(statement);
  }

  uint16_t consistency = body.read_short();
  uint8_t flags = body.read_byte();
  (void)flags;

  cass_batch_set_consistency(batch, static_cast<CassConsistency>(consistency));

  auto start = std::chrono::steady_clock::now();

  // Create async callback context
  auto *ctx = new FutureContext(this, stream, start);
  ctx->batch = batch;
  ctx->session = session;

  CassFuture *future = cass_session_execute_batch(session->raw(), batch);
  cass_future_set_callback(future, future_cb, ctx);
}

void Connection::send_error(int16_t stream, ErrorCode code,
                            const std::string &message) {
  Buffer body = build_error_response(code, message);
  send_response(stream, Opcode::ERROR, std::move(body));
}

void Connection::send_response(int16_t stream, Opcode opcode, Buffer body) {
  Buffer frame = encode_frame(stream, opcode, body);

  // Lock-free push to MPSC queue
  auto *node = new ResponseNode(stream, std::move(frame));
  write_queue_.push(node);

  flush_writes();
}

void Connection::flush_writes() {
  // Try to acquire flush lock (only one thread flushes at a time)
  std::unique_lock<std::mutex> lock(flush_mutex_, std::try_to_lock);
  if (!lock.owns_lock()) {
    return; // Another thread is flushing
  }

  if (write_in_progress_.load(std::memory_order_acquire) ||
      write_queue_.empty() || closing_) {
    return;
  }

  write_buf_.clear();

  // Batch multiple responses from lock-free queue
  ResponseNode *node;
  while (write_buf_.size() < FLUSH_THRESHOLD &&
         (node = write_queue_.pop()) != nullptr) {
    write_buf_.insert(write_buf_.end(), node->data.data(),
                      node->data.data() + node->data.size());
    delete node;
  }

  if (write_buf_.empty()) {
    return;
  }

  auto *req = new uv_write_t;
  req->data = this;

  uv_buf_t buf = uv_buf_init(reinterpret_cast<char *>(write_buf_.data()),
                             static_cast<unsigned int>(write_buf_.size()));

  write_in_progress_.store(true, std::memory_order_release);
  uv_write(req, client_, &buf, 1, write_cb);
}

// Server implementation
Server::Server(const DriverConfig &config)
    : config_(config), sessions_(config), inflight_sem_(config.max_inflight) {
  loop_ = uv_default_loop();
}

Server::~Server() { stop(); }

void Server::run() {
  // Remove existing socket file
  unlink(config_.socket_path.c_str());

  // Initialize pipe
  uv_pipe_init(loop_, &server_pipe_, 0);
  server_pipe_.data = this;

  // Bind to socket path
  int r = uv_pipe_bind(&server_pipe_, config_.socket_path.c_str());
  if (r < 0) {
    throw std::runtime_error("Failed to bind socket: " +
                             std::string(uv_strerror(r)));
  }

  // Start listening
  r = uv_listen(reinterpret_cast<uv_stream_t *>(&server_pipe_), 128,
                connection_cb);
  if (r < 0) {
    throw std::runtime_error("Failed to listen: " +
                             std::string(uv_strerror(r)));
  }

  // Setup signal handlers
  uv_signal_init(loop_, &sigint_);
  uv_signal_init(loop_, &sigterm_);
  sigint_.data = this;
  sigterm_.data = this;
  uv_signal_start(&sigint_, signal_cb, SIGINT);
  uv_signal_start(&sigterm_, signal_cb, SIGTERM);

  LOG_INFO("Listening on " << config_.socket_path << " (max inflight: "
                           << config_.max_inflight << ")");

  // Run event loop
  uv_run(loop_, UV_RUN_DEFAULT);

  // Cleanup
  unlink(config_.socket_path.c_str());
}

void Server::stop() {
  uv_signal_stop(&sigint_);
  uv_signal_stop(&sigterm_);

  {
    std::lock_guard<std::mutex> lock(conn_mutex_);
    for (auto &conn : connections_) {
      conn->close();
    }
    connections_.clear();
  }

  if (!uv_is_closing(reinterpret_cast<uv_handle_t *>(&server_pipe_))) {
    uv_close(reinterpret_cast<uv_handle_t *>(&server_pipe_), nullptr);
  }
  uv_stop(loop_);
}

void Server::connection_cb(uv_stream_t *server, int status) {
  if (status < 0) {
    LOG_ERROR("Connection error: " << uv_strerror(status));
    return;
  }

  auto *self = static_cast<Server *>(server->data);

  auto *client = new uv_pipe_t;
  uv_pipe_init(self->loop_, client, 0);

  if (uv_accept(server, reinterpret_cast<uv_stream_t *>(client)) == 0) {
    auto conn = std::make_unique<Connection>(
        self, reinterpret_cast<uv_stream_t *>(client));
    conn->start();

    std::lock_guard<std::mutex> lock(self->conn_mutex_);
    self->connections_.push_back(std::move(conn));

    LOG_INFO("New connection accepted");
  } else {
    uv_close(reinterpret_cast<uv_handle_t *>(client),
             [](uv_handle_t *h) { delete reinterpret_cast<uv_pipe_t *>(h); });
  }
}

void Server::signal_cb(uv_signal_t *handle, int signum) {
  auto *self = static_cast<Server *>(handle->data);
  LOG_INFO("Received signal " << signum << ", shutting down...");
  self->stop();
}

int run_server(const DriverConfig &config) {
  init_logging();
  try {
    Server server(config);
    server.run();
    return 0;
  } catch (const std::exception &e) {
    LOG_ERROR("Fatal error: " << e.what());
    return 1;
  }
}

} // namespace latte

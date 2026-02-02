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

  // Initialize async handle for processing Cassandra results on libuv thread
  async_handle_ = new uv_async_t;
  async_handle_->data = this;
  uv_async_init(server_->loop(), async_handle_, async_work_cb);
}

Connection::~Connection() {
  // Note: async_handle_ and client_ are closed in close() method
  // and freed by their close callbacks. We don't need to close them here
  // because close() is always called before destruction.
  // If for some reason close() wasn't called, we can't safely close handles
  // in the destructor because the event loop may have stopped.
}

void Connection::start() { uv_read_start(client_, alloc_cb, read_cb); }

void Connection::close() {
  if (closing_)
    return;
  closing_ = true;
  uv_read_stop(client_);

  // Close the async handle first
  if (async_handle_ && !async_handle_closing_) {
    async_handle_closing_ = true;
    uv_close(reinterpret_cast<uv_handle_t *>(async_handle_),
             [](uv_handle_t *h) { delete reinterpret_cast<uv_async_t *>(h); });
    async_handle_ = nullptr;
  }

  uv_close(reinterpret_cast<uv_handle_t *>(client_), close_cb);
}

void Connection::alloc_cb(uv_handle_t *handle, size_t suggested_size,
                          uv_buf_t *buf) {
  // Use pre-allocated buffer instead of allocating per-read
  auto *conn = static_cast<Connection *>(handle->data);
  (void)suggested_size; // May be larger than our buffer, but that's fine
  buf->base = conn->alloc_buf_;
  buf->len = ALLOC_BUF_SIZE;
}

void Connection::read_cb(uv_stream_t *stream, ssize_t nread,
                         const uv_buf_t *buf) {
  auto *conn = static_cast<Connection *>(stream->data);
  (void)buf; // Buffer is pre-allocated in connection, no cleanup needed

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

  conn->write_in_progress_ = false;
  conn->flush_writes();
}

void Connection::close_cb(uv_handle_t *handle) {
  (void)handle->data; // Connection will be cleaned up by Server
  delete reinterpret_cast<uv_tcp_t *>(handle);
}

void Connection::async_work_cb(uv_async_t *handle) {
  auto *conn = static_cast<Connection *>(handle->data);
  if (conn->closing_) return;

  // Process all queued async work items
  std::queue<AsyncWork> work_items;
  {
    std::lock_guard<std::mutex> lock(conn->async_work_mutex_);
    std::swap(work_items, conn->async_work_queue_);
  }

  while (!work_items.empty()) {
    AsyncWork &work = work_items.front();

    switch (work.type) {
    case AsyncWork::Type::QUERY:
      conn->process_query_result(work.stream, work.future, work.start_time,
                                 work.statement);
      break;
    case AsyncWork::Type::PREPARE:
      conn->process_prepare_result(work.stream, work.future, work.statement_key,
                                   work.session_id);
      break;
    case AsyncWork::Type::EXECUTE:
      conn->process_execute_result(work.stream, work.future, work.start_time,
                                   work.statement);
      break;
    case AsyncWork::Type::BATCH:
      conn->process_batch_result(work.stream, work.future, work.start_time,
                                 work.batch);
      break;
    }

    work_items.pop();
  }
}

void Connection::on_data(const uint8_t *data, size_t len) {
  read_buf_.insert(read_buf_.end(), data, data + len);

  // Calculate available data size (accounting for offset)
  size_t available = read_buf_.size() - read_buf_offset_;

  while (available >= FRAME_HEADER_SIZE) {
    const uint8_t *buf_start = read_buf_.data() + read_buf_offset_;
    FrameHeader header = parse_frame_header(buf_start);

    if (header.body_length > MAX_BODY_SIZE) {
      send_error(header.stream, ErrorCode::PROTOCOL, "Body too large");
      close();
      return;
    }

    size_t total_size = FRAME_HEADER_SIZE + header.body_length;
    if (available < total_size) {
      break; // Need more data
    }

    // Extract frame
    Frame frame;
    frame.header = header;
    if (header.body_length > 0) {
      frame.body =
          Buffer(std::vector<uint8_t>(buf_start + FRAME_HEADER_SIZE,
                                      buf_start + total_size));
    }

    // Advance offset instead of erasing (O(1) vs O(n))
    read_buf_offset_ += total_size;
    available -= total_size;

    // Process frame
    process_frame(std::move(frame));
  }

  // Compact buffer when offset exceeds threshold to prevent unbounded growth
  if (read_buf_offset_ >= COMPACT_THRESHOLD) {
    read_buf_.erase(read_buf_.begin(), read_buf_.begin() + read_buf_offset_);
    read_buf_offset_ = 0;
  }
}

void Connection::process_frame(Frame frame) {
  int16_t stream = frame.header.stream;

  // Acquire semaphore for inflight limiting
  // For async operations (QUERY, EXECUTE, BATCH), the semaphore is released in their callbacks
  // For sync operations (CREATE_SESSION, PREPARE), release happens here
  bool is_async_op = (frame.header.opcode == Opcode::QUERY ||
                      frame.header.opcode == Opcode::EXECUTE ||
                      frame.header.opcode == Opcode::BATCH);

  if (is_async_op) {
    if (!server_->inflight_semaphore().try_acquire()) {
      LOG_WARN("Inflight limit reached, rejecting request on stream "
               << stream);
      send_error(stream, ErrorCode::OVERLOADED, "Too many inflight requests");
      return;
    }
    // Semaphore will be released in the async callback
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
      if (is_async_op) {
        server_->inflight_semaphore().release();
      }
      break;
    }
  } catch (const std::exception &e) {
    LOG_ERROR("Exception handling frame: " << e.what());
    send_error(stream, ErrorCode::SERVER, e.what());
    if (is_async_op) {
      server_->inflight_semaphore().release();
    }
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

  CassStatement *statement = cass_statement_new(query.c_str(), 0);
  cass_statement_set_consistency(statement,
                                 static_cast<CassConsistency>(consistency));

  // Create async context
  auto *ctx = new AsyncContext(this, stream);
  ctx->statement = statement;

  CassFuture *future = cass_session_execute(session->raw(), statement);
  cass_future_set_callback(future, query_callback, ctx);
}

void Connection::query_callback(CassFuture *future, void *data) {
  auto *ctx = static_cast<AsyncContext *>(data);
  auto *conn = ctx->conn;

  // Queue work item for processing on libuv thread
  AsyncWork work;
  work.type = AsyncWork::Type::QUERY;
  work.stream = ctx->stream;
  work.future = future;
  work.start_time = ctx->start_time;
  work.statement = ctx->statement;
  ctx->statement = nullptr;  // Transfer ownership

  {
    std::lock_guard<std::mutex> lock(conn->async_work_mutex_);
    conn->async_work_queue_.push(std::move(work));
  }

  uv_async_send(conn->async_handle_);
  delete ctx;
}

void Connection::process_query_result(int16_t stream, CassFuture *future,
                                      std::chrono::steady_clock::time_point start_time,
                                      CassStatement *statement) {
  auto end = std::chrono::steady_clock::now();
  uint64_t latency_ns =
      std::chrono::duration_cast<std::chrono::nanoseconds>(end - start_time).count();

  CassError rc = cass_future_error_code(future);
  if (rc != CASS_OK) {
    const char *message;
    size_t message_length;
    cass_future_error_message(future, &message, &message_length);
    std::string error_msg(message, message_length);

    cass_future_free(future);
    cass_statement_free(statement);

    send_error(stream, ErrorCode::SERVER, error_msg);
    server_->inflight_semaphore().release();
    return;
  }

  const CassResult *result = cass_future_get_result(future);

  Buffer response;
  if (cass_result_row_count(result) == 0 &&
      cass_result_column_count(result) == 0) {
    response = build_void_result(latency_ns);
  } else {
    response = encode_rows_result(result, latency_ns);
  }

  cass_result_free(result);
  cass_future_free(future);
  cass_statement_free(statement);

  send_response(stream, Opcode::RESULT, std::move(response));
  server_->inflight_semaphore().release();
}

// Extended context for prepare operations
struct PrepareContext {
  Connection *conn;
  int16_t stream;
  std::string statement_key;
  uint64_t session_id;

  PrepareContext(Connection *c, int16_t s, std::string key, uint64_t sid)
      : conn(c), stream(s), statement_key(std::move(key)), session_id(sid) {}
};

void Connection::handle_prepare(int16_t stream, Buffer &body) {
  uint64_t session_id = body.read_long();
  std::string query = body.read_long_string();
  std::string statement_key = body.read_string();

  auto session = server_->sessions().get(session_id);
  if (!session) {
    send_error(stream, ErrorCode::SERVER, "Session not found");
    return;
  }

  // Check if already prepared
  if (session->get_prepared(statement_key)) {
    // Already prepared, return success
    Buffer response = build_prepared_result(statement_key, {});
    send_response(stream, Opcode::RESULT, std::move(response));
    return;
  }

  auto *ctx = new PrepareContext(this, stream, statement_key, session_id);

  CassFuture *future = cass_session_prepare(session->raw(), query.c_str());
  cass_future_set_callback(future, prepare_callback, ctx);
}

void Connection::prepare_callback(CassFuture *future, void *data) {
  auto *ctx = static_cast<PrepareContext *>(data);
  auto *conn = ctx->conn;

  // Queue work item for processing on libuv thread
  AsyncWork work;
  work.type = AsyncWork::Type::PREPARE;
  work.stream = ctx->stream;
  work.future = future;
  work.statement_key = std::move(ctx->statement_key);
  work.session_id = ctx->session_id;
  work.statement = nullptr;
  work.batch = nullptr;

  {
    std::lock_guard<std::mutex> lock(conn->async_work_mutex_);
    conn->async_work_queue_.push(std::move(work));
  }

  uv_async_send(conn->async_handle_);
  delete ctx;
}

void Connection::process_prepare_result(int16_t stream, CassFuture *future,
                                        const std::string &statement_key,
                                        uint64_t session_id) {
  CassError rc = cass_future_error_code(future);
  if (rc != CASS_OK) {
    const char *message;
    size_t message_length;
    cass_future_error_message(future, &message, &message_length);
    std::string error_msg(message, message_length);

    cass_future_free(future);
    send_error(stream, ErrorCode::SERVER, error_msg);
    return;
  }

  const CassPrepared *prepared = cass_future_get_prepared(future);

  // Cache the prepared statement in the session
  auto session = server_->sessions().get(session_id);
  if (session) {
    session->cache_prepared(statement_key, prepared);
  }

  cass_future_free(future);

  Buffer response = build_prepared_result(statement_key, {});
  send_response(stream, Opcode::RESULT, std::move(response));
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
      // Get expected type from prepared statement if available
      const CassDataType *expected_type =
          cass_prepared_parameter_data_type(prepared, i);
      bind_value(statement, i, value, expected_type);
    }
  }

  // Create async context
  auto *ctx = new AsyncContext(this, stream);
  ctx->statement = statement;

  CassFuture *future = cass_session_execute(session->raw(), statement);
  cass_future_set_callback(future, execute_callback, ctx);
}

void Connection::execute_callback(CassFuture *future, void *data) {
  auto *ctx = static_cast<AsyncContext *>(data);
  auto *conn = ctx->conn;

  // Queue work item for processing on libuv thread
  AsyncWork work;
  work.type = AsyncWork::Type::EXECUTE;
  work.stream = ctx->stream;
  work.future = future;
  work.start_time = ctx->start_time;
  work.statement = ctx->statement;
  ctx->statement = nullptr;  // Transfer ownership
  work.batch = nullptr;

  {
    std::lock_guard<std::mutex> lock(conn->async_work_mutex_);
    conn->async_work_queue_.push(std::move(work));
  }

  uv_async_send(conn->async_handle_);
  delete ctx;
}

void Connection::process_execute_result(int16_t stream, CassFuture *future,
                                        std::chrono::steady_clock::time_point start_time,
                                        CassStatement *statement) {
  auto end = std::chrono::steady_clock::now();
  uint64_t latency_ns =
      std::chrono::duration_cast<std::chrono::nanoseconds>(end - start_time).count();

  CassError rc = cass_future_error_code(future);
  if (rc != CASS_OK) {
    const char *message;
    size_t message_length;
    cass_future_error_message(future, &message, &message_length);
    std::string error_msg(message, message_length);

    cass_future_free(future);
    cass_statement_free(statement);

    send_error(stream, ErrorCode::SERVER, error_msg);
    server_->inflight_semaphore().release();
    return;
  }

  const CassResult *result = cass_future_get_result(future);

  Buffer response;
  if (cass_result_row_count(result) == 0 &&
      cass_result_column_count(result) == 0) {
    response = build_void_result(latency_ns);
  } else {
    response = encode_rows_result(result, latency_ns);
  }

  cass_result_free(result);
  cass_future_free(future);
  cass_statement_free(statement);

  send_response(stream, Opcode::RESULT, std::move(response));
  server_->inflight_semaphore().release();
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
      // Get expected type from prepared statement if available
      const CassDataType *expected_type =
          cass_prepared_parameter_data_type(prepared, j);
      bind_value(statement, j, value, expected_type);
    }

    cass_batch_add_statement(batch, statement);
    cass_statement_free(statement);
  }

  uint16_t consistency = body.read_short();
  uint8_t flags = body.read_byte();
  (void)flags;

  cass_batch_set_consistency(batch, static_cast<CassConsistency>(consistency));

  // Create async context
  auto *ctx = new AsyncContext(this, stream);
  ctx->batch = batch;

  CassFuture *future = cass_session_execute_batch(session->raw(), batch);
  cass_future_set_callback(future, batch_callback, ctx);
}

void Connection::batch_callback(CassFuture *future, void *data) {
  auto *ctx = static_cast<AsyncContext *>(data);
  auto *conn = ctx->conn;

  // Queue work item for processing on libuv thread
  AsyncWork work;
  work.type = AsyncWork::Type::BATCH;
  work.stream = ctx->stream;
  work.future = future;
  work.start_time = ctx->start_time;
  work.batch = ctx->batch;
  ctx->batch = nullptr;  // Transfer ownership
  work.statement = nullptr;

  {
    std::lock_guard<std::mutex> lock(conn->async_work_mutex_);
    conn->async_work_queue_.push(std::move(work));
  }

  uv_async_send(conn->async_handle_);
  delete ctx;
}

void Connection::process_batch_result(int16_t stream, CassFuture *future,
                                      std::chrono::steady_clock::time_point start_time,
                                      CassBatch *batch) {
  auto end = std::chrono::steady_clock::now();
  uint64_t latency_ns =
      std::chrono::duration_cast<std::chrono::nanoseconds>(end - start_time).count();

  CassError rc = cass_future_error_code(future);
  cass_batch_free(batch);

  if (rc != CASS_OK) {
    const char *message;
    size_t message_length;
    cass_future_error_message(future, &message, &message_length);
    std::string error_msg(message, message_length);

    cass_future_free(future);
    send_error(stream, ErrorCode::SERVER, error_msg);
    server_->inflight_semaphore().release();
    return;
  }

  cass_future_free(future);

  Buffer response = build_void_result(latency_ns);
  send_response(stream, Opcode::RESULT, std::move(response));
  server_->inflight_semaphore().release();
}

void Connection::send_error(int16_t stream, ErrorCode code,
                            const std::string &message) {
  Buffer body = build_error_response(code, message);
  send_response(stream, Opcode::ERROR, std::move(body));
}

void Connection::send_response(int16_t stream, Opcode opcode, Buffer body) {
  Buffer frame = encode_frame(stream, opcode, body);

  {
    std::lock_guard<std::mutex> lock(write_mutex_);
    write_queue_.push({stream, std::move(frame)});
  }

  flush_writes();
}

void Connection::flush_writes() {
  std::lock_guard<std::mutex> lock(write_mutex_);

  if (write_in_progress_ || write_queue_.empty() || closing_) {
    return;
  }

  // Clear previous write buffers
  write_pending_.clear();
  write_bufs_.clear();

  // Collect buffers for scatter-gather I/O (avoid copying)
  size_t total_size = 0;
  while (!write_queue_.empty() &&
         write_pending_.size() < MAX_WRITE_BUFS &&
         total_size < FLUSH_THRESHOLD) {
    Response &resp = write_queue_.front();
    total_size += resp.data.size();
    write_pending_.push_back(std::move(resp.data));
    write_queue_.pop();
  }

  if (write_pending_.empty()) {
    return;
  }

  // Build uv_buf_t array pointing to the pending buffers
  write_bufs_.reserve(write_pending_.size());
  for (auto &buf : write_pending_) {
    write_bufs_.push_back(uv_buf_init(
        reinterpret_cast<char *>(buf.data()),
        static_cast<unsigned int>(buf.size())));
  }

  auto *req = new uv_write_t;
  req->data = this;

  write_in_progress_ = true;
  uv_write(req, client_, write_bufs_.data(),
           static_cast<unsigned int>(write_bufs_.size()), write_cb);
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
  if (stopping_) return;
  stopping_ = true;

  // Close all connections (this will close their async handles too)
  {
    std::lock_guard<std::mutex> lock(conn_mutex_);
    for (auto &conn : connections_) {
      conn->close();
    }
  }

  // Close the server pipe
  if (!uv_is_closing(reinterpret_cast<uv_handle_t *>(&server_pipe_))) {
    uv_close(reinterpret_cast<uv_handle_t *>(&server_pipe_), nullptr);
  }

  // Close signal handles (check if not already closing)
  if (!uv_is_closing(reinterpret_cast<uv_handle_t *>(&sigint_))) {
    uv_close(reinterpret_cast<uv_handle_t *>(&sigint_), nullptr);
  }
  if (!uv_is_closing(reinterpret_cast<uv_handle_t *>(&sigterm_))) {
    uv_close(reinterpret_cast<uv_handle_t *>(&sigterm_), nullptr);
  }

  // The loop will exit naturally when all handles are closed
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

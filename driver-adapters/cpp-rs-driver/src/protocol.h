#pragma once

#include <cstdint>
#include <cstring>
#include <map>
#include <memory>
#include <optional>
#include <stdexcept>
#include <string>
#include <vector>

namespace latte {

// Protocol version
constexpr uint8_t REQUEST_VERSION = 0x04;
constexpr uint8_t RESPONSE_VERSION = 0x84;
constexpr size_t FRAME_HEADER_SIZE = 9;
constexpr size_t MAX_BODY_SIZE = 16 * 1024 * 1024; // 16MB

// Opcodes
enum class Opcode : uint8_t {
  ERROR = 0x00,
  QUERY = 0x07,
  RESULT = 0x08,
  PREPARE = 0x09,
  EXECUTE = 0x0A,
  BATCH = 0x0D,
  CREATE_SESSION = 0x21,
  SESSION_CREATED = 0x22,
};

// Error codes
enum class ErrorCode : int32_t {
  SERVER = 0x0000,
  PROTOCOL = 0x000A,
  OVERLOADED = 0x1001,
  UNPREPARED = 0x2500,
};

// Result kinds
enum class ResultKind : int32_t {
  VOID = 0x0001,
  ROWS = 0x0002,
  SET_KEYSPACE = 0x0003,
  PREPARED = 0x0004,
  SCHEMA_CHANGE = 0x0005,
};

// Consistency levels
enum class Consistency : uint16_t {
  ANY = 0x0000,
  ONE = 0x0001,
  TWO = 0x0002,
  THREE = 0x0003,
  QUORUM = 0x0004,
  ALL = 0x0005,
  LOCAL_QUORUM = 0x0006,
  EACH_QUORUM = 0x0007,
  LOCAL_ONE = 0x000A,
};

// CQL Type codes
enum class TypeCode : uint16_t {
  ASCII = 0x0001,
  BIGINT = 0x0002,
  BLOB = 0x0003,
  BOOLEAN = 0x0004,
  COUNTER = 0x0005,
  DECIMAL = 0x0006,
  DOUBLE = 0x0007,
  FLOAT = 0x0008,
  INT = 0x0009,
  TIMESTAMP = 0x000B,
  UUID = 0x000C,
  TEXT = 0x000D,
  VARINT = 0x000E,
  TIMEUUID = 0x000F,
  INET = 0x0010,
  DATE = 0x0011,
  TIME = 0x0012,
  SMALLINT = 0x0013,
  TINYINT = 0x0014,
  DURATION = 0x0015,
  LIST = 0x0020,
  MAP = 0x0021,
  SET = 0x0022,
  VECTOR = 0x0030,
  TUPLE = 0x0031,
  UDT = 0x0040,
};

// Batch types
enum class BatchType : uint8_t {
  LOGGED = 0,
  UNLOGGED = 1,
  COUNTER = 2,
};

// Frame header
struct FrameHeader {
  uint8_t version;
  uint8_t flags;
  int16_t stream;
  Opcode opcode;
  uint32_t body_length;
};

// Buffer for reading/writing protocol data
class Buffer {
public:
  Buffer() = default;
  explicit Buffer(size_t capacity) : data_(capacity) {}
  explicit Buffer(std::vector<uint8_t> data) : data_(std::move(data)) {}

  const uint8_t *data() const { return data_.data(); }
  uint8_t *data() { return data_.data(); }
  size_t size() const { return data_.size(); }
  bool empty() const { return data_.empty(); }

  void clear() {
    data_.clear();
    read_pos_ = 0;
  }

  void reserve(size_t capacity) { data_.reserve(capacity); }
  void resize(size_t size) { data_.resize(size); }

  // Read position management
  size_t read_pos() const { return read_pos_; }
  size_t remaining() const { return data_.size() - read_pos_; }
  void advance(size_t n) { read_pos_ += n; }
  void reset_read_pos() { read_pos_ = 0; }

  // Append data
  void append(const uint8_t *src, size_t len) {
    data_.insert(data_.end(), src, src + len);
  }

  void append(const Buffer &other) {
    data_.insert(data_.end(), other.data_.begin(), other.data_.end());
  }

  // Write primitives (big-endian)
  void write_byte(uint8_t v) { data_.push_back(v); }

  void write_short(uint16_t v) {
    data_.push_back(static_cast<uint8_t>(v >> 8));
    data_.push_back(static_cast<uint8_t>(v));
  }

  void write_int(int32_t v) {
    data_.push_back(static_cast<uint8_t>(v >> 24));
    data_.push_back(static_cast<uint8_t>(v >> 16));
    data_.push_back(static_cast<uint8_t>(v >> 8));
    data_.push_back(static_cast<uint8_t>(v));
  }

  void write_long(uint64_t v) {
    data_.push_back(static_cast<uint8_t>(v >> 56));
    data_.push_back(static_cast<uint8_t>(v >> 48));
    data_.push_back(static_cast<uint8_t>(v >> 40));
    data_.push_back(static_cast<uint8_t>(v >> 32));
    data_.push_back(static_cast<uint8_t>(v >> 24));
    data_.push_back(static_cast<uint8_t>(v >> 16));
    data_.push_back(static_cast<uint8_t>(v >> 8));
    data_.push_back(static_cast<uint8_t>(v));
  }

  void write_string(const std::string &s) {
    write_short(static_cast<uint16_t>(s.size()));
    data_.insert(data_.end(), s.begin(), s.end());
  }

  void write_long_string(const std::string &s) {
    write_int(static_cast<int32_t>(s.size()));
    data_.insert(data_.end(), s.begin(), s.end());
  }

  void write_bytes(const std::vector<uint8_t> &b) {
    write_int(static_cast<int32_t>(b.size()));
    data_.insert(data_.end(), b.begin(), b.end());
  }

  void write_bytes_nullable(const std::optional<std::vector<uint8_t>> &b) {
    if (b) {
      write_int(static_cast<int32_t>(b->size()));
      data_.insert(data_.end(), b->begin(), b->end());
    } else {
      write_int(-1);
    }
  }

  void write_short_bytes(const std::vector<uint8_t> &b) {
    write_short(static_cast<uint16_t>(b.size()));
    data_.insert(data_.end(), b.begin(), b.end());
  }

  void write_string_map(const std::map<std::string, std::string> &m) {
    write_short(static_cast<uint16_t>(m.size()));
    for (const auto &[k, v] : m) {
      write_string(k);
      write_string(v);
    }
  }

  // Read primitives (big-endian)
  uint8_t read_byte() {
    if (read_pos_ >= data_.size()) {
      throw std::runtime_error("Buffer underflow");
    }
    return data_[read_pos_++];
  }

  uint16_t read_short() {
    if (read_pos_ + 2 > data_.size()) {
      throw std::runtime_error("Buffer underflow");
    }
    uint16_t v = (static_cast<uint16_t>(data_[read_pos_]) << 8) |
                 static_cast<uint16_t>(data_[read_pos_ + 1]);
    read_pos_ += 2;
    return v;
  }

  int32_t read_int() {
    if (read_pos_ + 4 > data_.size()) {
      throw std::runtime_error("Buffer underflow");
    }
    int32_t v = (static_cast<int32_t>(data_[read_pos_]) << 24) |
                (static_cast<int32_t>(data_[read_pos_ + 1]) << 16) |
                (static_cast<int32_t>(data_[read_pos_ + 2]) << 8) |
                static_cast<int32_t>(data_[read_pos_ + 3]);
    read_pos_ += 4;
    return v;
  }

  uint64_t read_long() {
    if (read_pos_ + 8 > data_.size()) {
      throw std::runtime_error("Buffer underflow");
    }
    uint64_t v = (static_cast<uint64_t>(data_[read_pos_]) << 56) |
                 (static_cast<uint64_t>(data_[read_pos_ + 1]) << 48) |
                 (static_cast<uint64_t>(data_[read_pos_ + 2]) << 40) |
                 (static_cast<uint64_t>(data_[read_pos_ + 3]) << 32) |
                 (static_cast<uint64_t>(data_[read_pos_ + 4]) << 24) |
                 (static_cast<uint64_t>(data_[read_pos_ + 5]) << 16) |
                 (static_cast<uint64_t>(data_[read_pos_ + 6]) << 8) |
                 static_cast<uint64_t>(data_[read_pos_ + 7]);
    read_pos_ += 8;
    return v;
  }

  std::string read_string() {
    uint16_t len = read_short();
    if (read_pos_ + len > data_.size()) {
      throw std::runtime_error("Buffer underflow");
    }
    std::string s(reinterpret_cast<const char *>(&data_[read_pos_]), len);
    read_pos_ += len;
    return s;
  }

  std::string read_long_string() {
    int32_t len = read_int();
    if (len < 0) {
      return "";
    }
    if (read_pos_ + static_cast<size_t>(len) > data_.size()) {
      throw std::runtime_error("Buffer underflow");
    }
    std::string s(reinterpret_cast<const char *>(&data_[read_pos_]),
                  static_cast<size_t>(len));
    read_pos_ += static_cast<size_t>(len);
    return s;
  }

  std::optional<std::vector<uint8_t>> read_bytes() {
    int32_t len = read_int();
    if (len < 0) {
      return std::nullopt;
    }
    if (read_pos_ + static_cast<size_t>(len) > data_.size()) {
      throw std::runtime_error("Buffer underflow");
    }
    std::vector<uint8_t> b(data_.begin() + read_pos_,
                           data_.begin() + read_pos_ + len);
    read_pos_ += static_cast<size_t>(len);
    return b;
  }

  std::vector<uint8_t> read_short_bytes() {
    uint16_t len = read_short();
    if (read_pos_ + len > data_.size()) {
      throw std::runtime_error("Buffer underflow");
    }
    std::vector<uint8_t> b(data_.begin() + read_pos_,
                           data_.begin() + read_pos_ + len);
    read_pos_ += len;
    return b;
  }

  std::map<std::string, std::string> read_string_map() {
    uint16_t count = read_short();
    std::map<std::string, std::string> m;
    for (uint16_t i = 0; i < count; ++i) {
      std::string k = read_string();
      std::string v = read_string();
      m[k] = v;
    }
    return m;
  }

private:
  std::vector<uint8_t> data_;
  size_t read_pos_ = 0;
};

// Frame structure
struct Frame {
  FrameHeader header;
  Buffer body;
};

// Protocol encoding/decoding functions
FrameHeader parse_frame_header(const uint8_t *data);
Buffer encode_frame_header(const FrameHeader &header);
Buffer encode_frame(int16_t stream, Opcode opcode, const Buffer &body);

// Response builders
Buffer build_session_created_response(uint64_t session_id);
Buffer build_error_response(ErrorCode code, const std::string &message);
Buffer build_void_result(uint64_t latency_ns);
Buffer build_prepared_result(const std::string &statement_key,
                             const std::vector<uint8_t> &prepared_id);
Buffer build_set_keyspace_result(const std::string &keyspace);
Buffer build_schema_change_result(const std::string &change_type,
                                  const std::string &target,
                                  const std::string &keyspace,
                                  const std::string &name = "");

} // namespace latte

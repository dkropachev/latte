#pragma once

#include <cstdint>
#include <cstring>
#include <map>
#include <memory>
#include <optional>
#include <stdexcept>
#include <string>
#include <string_view>
#include <vector>

// Branch prediction hints for error paths
#define LATTE_LIKELY(x) __builtin_expect(!!(x), 1)
#define LATTE_UNLIKELY(x) __builtin_expect(!!(x), 0)

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
// Supports two modes:
// - Owning mode: owns the underlying vector data
// - View mode: non-owning view into external data (for zero-copy reads)
class Buffer {
public:
  Buffer() = default;
  explicit Buffer(size_t capacity) : data_(capacity) {}
  explicit Buffer(std::vector<uint8_t> data) : data_(std::move(data)) {}

  // View constructor - creates non-owning view into external data
  // Caller must ensure the pointed-to data outlives this Buffer
  Buffer(const uint8_t *view_ptr, size_t view_len)
      : view_ptr_(view_ptr), view_len_(view_len), is_view_(true) {}

  const uint8_t *data() const { return is_view_ ? view_ptr_ : data_.data(); }
  uint8_t *data() { return is_view_ ? nullptr : data_.data(); }
  size_t size() const { return is_view_ ? view_len_ : data_.size(); }
  bool empty() const { return size() == 0; }
  bool is_view() const { return is_view_; }

  void clear() {
    if (is_view_) {
      view_ptr_ = nullptr;
      view_len_ = 0;
    } else {
      data_.clear();
    }
    read_pos_ = 0;
  }

  void reserve(size_t capacity) {
    if (!is_view_)
      data_.reserve(capacity);
  }
  void resize(size_t size) {
    if (!is_view_)
      data_.resize(size);
  }

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

  // Write primitives (big-endian) - optimized with bswap+memcpy
  void write_byte(uint8_t v) { data_.push_back(v); }

  void write_short(uint16_t v) {
    uint16_t be = __builtin_bswap16(v);
    size_t pos = data_.size();
    data_.resize(pos + 2);
    std::memcpy(data_.data() + pos, &be, 2);
  }

  void write_int(int32_t v) {
    uint32_t be = __builtin_bswap32(static_cast<uint32_t>(v));
    size_t pos = data_.size();
    data_.resize(pos + 4);
    std::memcpy(data_.data() + pos, &be, 4);
  }

  void write_long(uint64_t v) {
    uint64_t be = __builtin_bswap64(v);
    size_t pos = data_.size();
    data_.resize(pos + 8);
    std::memcpy(data_.data() + pos, &be, 8);
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
    if (LATTE_UNLIKELY(read_pos_ >= size())) {
      throw std::runtime_error("Buffer underflow");
    }
    uint8_t v = read_ptr()[0];
    ++read_pos_;
    return v;
  }

  uint16_t read_short() {
    if (LATTE_UNLIKELY(read_pos_ + 2 > size())) {
      throw std::runtime_error("Buffer underflow");
    }
    uint16_t be;
    std::memcpy(&be, read_ptr(), 2);
    read_pos_ += 2;
    return __builtin_bswap16(be);
  }

  int32_t read_int() {
    if (LATTE_UNLIKELY(read_pos_ + 4 > size())) {
      throw std::runtime_error("Buffer underflow");
    }
    uint32_t be;
    std::memcpy(&be, read_ptr(), 4);
    read_pos_ += 4;
    return static_cast<int32_t>(__builtin_bswap32(be));
  }

  uint64_t read_long() {
    if (LATTE_UNLIKELY(read_pos_ + 8 > size())) {
      throw std::runtime_error("Buffer underflow");
    }
    uint64_t be;
    std::memcpy(&be, read_ptr(), 8);
    read_pos_ += 8;
    return __builtin_bswap64(be);
  }

  std::string read_string() {
    uint16_t len = read_short();
    if (LATTE_UNLIKELY(read_pos_ + len > size())) {
      throw std::runtime_error("Buffer underflow");
    }
    std::string s(reinterpret_cast<const char *>(read_ptr()), len);
    read_pos_ += len;
    return s;
  }

  // Zero-copy string read - returns view into buffer data
  // Caller must ensure buffer outlives the returned view
  std::string_view read_string_view() {
    uint16_t len = read_short();
    if (LATTE_UNLIKELY(read_pos_ + len > size())) {
      throw std::runtime_error("Buffer underflow");
    }
    std::string_view sv(reinterpret_cast<const char *>(read_ptr()), len);
    read_pos_ += len;
    return sv;
  }

  std::string read_long_string() {
    int32_t len = read_int();
    if (LATTE_UNLIKELY(len < 0)) {
      return "";
    }
    if (LATTE_UNLIKELY(read_pos_ + static_cast<size_t>(len) > size())) {
      throw std::runtime_error("Buffer underflow");
    }
    std::string s(reinterpret_cast<const char *>(read_ptr()),
                  static_cast<size_t>(len));
    read_pos_ += static_cast<size_t>(len);
    return s;
  }

  // Zero-copy long string read - returns view into buffer data
  // Returns empty view for null strings (length < 0)
  std::string_view read_long_string_view() {
    int32_t len = read_int();
    if (LATTE_UNLIKELY(len < 0)) {
      return std::string_view{};
    }
    if (LATTE_UNLIKELY(read_pos_ + static_cast<size_t>(len) > size())) {
      throw std::runtime_error("Buffer underflow");
    }
    std::string_view sv(reinterpret_cast<const char *>(read_ptr()),
                        static_cast<size_t>(len));
    read_pos_ += static_cast<size_t>(len);
    return sv;
  }

  std::optional<std::vector<uint8_t>> read_bytes() {
    int32_t len = read_int();
    if (LATTE_UNLIKELY(len < 0)) {
      return std::nullopt;
    }
    if (LATTE_UNLIKELY(read_pos_ + static_cast<size_t>(len) > size())) {
      throw std::runtime_error("Buffer underflow");
    }
    const uint8_t *p = read_ptr();
    std::vector<uint8_t> b(p, p + len);
    read_pos_ += static_cast<size_t>(len);
    return b;
  }

  std::vector<uint8_t> read_short_bytes() {
    uint16_t len = read_short();
    if (LATTE_UNLIKELY(read_pos_ + len > size())) {
      throw std::runtime_error("Buffer underflow");
    }
    const uint8_t *p = read_ptr();
    std::vector<uint8_t> b(p, p + len);
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

  // Flat vector version - avoids tree allocation overhead
  // Use when you don't need O(log n) lookup
  std::vector<std::pair<std::string, std::string>> read_string_map_flat() {
    uint16_t count = read_short();
    std::vector<std::pair<std::string, std::string>> v;
    v.reserve(count);
    for (uint16_t i = 0; i < count; ++i) {
      std::string k = read_string();
      std::string val = read_string();
      v.emplace_back(std::move(k), std::move(val));
    }
    return v;
  }

private:
  // Get pointer to data at current read position
  const uint8_t *read_ptr() const {
    return (is_view_ ? view_ptr_ : data_.data()) + read_pos_;
  }

  std::vector<uint8_t> data_;
  const uint8_t *view_ptr_ = nullptr;
  size_t view_len_ = 0;
  bool is_view_ = false;
  size_t read_pos_ = 0;
};

// Frame structure
struct Frame {
  FrameHeader header;
  Buffer body;
};

// Protocol encoding/decoding functions - inlined for performance

inline FrameHeader parse_frame_header(const uint8_t *data) {
  FrameHeader header;
  header.version = data[0];
  header.flags = data[1];
  header.stream = static_cast<int16_t>((static_cast<uint16_t>(data[2]) << 8) |
                                       static_cast<uint16_t>(data[3]));
  header.opcode = static_cast<Opcode>(data[4]);
  header.body_length = (static_cast<uint32_t>(data[5]) << 24) |
                       (static_cast<uint32_t>(data[6]) << 16) |
                       (static_cast<uint32_t>(data[7]) << 8) |
                       static_cast<uint32_t>(data[8]);
  return header;
}

inline Buffer encode_frame(int16_t stream, Opcode opcode, const Buffer &body) {
  Buffer frame;
  frame.reserve(FRAME_HEADER_SIZE + body.size());
  frame.write_byte(RESPONSE_VERSION);
  frame.write_byte(0); // flags
  frame.write_short(static_cast<uint16_t>(stream));
  frame.write_byte(static_cast<uint8_t>(opcode));
  frame.write_int(static_cast<int32_t>(body.size()));
  frame.append(body);
  return frame;
}

// Response builders - small functions inlined

inline Buffer build_session_created_response(uint64_t session_id) {
  Buffer body;
  body.reserve(8);
  body.write_long(session_id);
  return body;
}

inline Buffer build_void_result(uint64_t latency_ns) {
  Buffer body;
  body.reserve(12);
  body.write_int(static_cast<int32_t>(ResultKind::VOID));
  body.write_long(latency_ns);
  return body;
}

// Larger response builders (remain in .cpp)
Buffer build_error_response(ErrorCode code, const std::string &message);
Buffer build_prepared_result(const std::string &statement_key,
                             const std::vector<uint8_t> &prepared_id);
Buffer build_set_keyspace_result(const std::string &keyspace);
Buffer build_schema_change_result(const std::string &change_type,
                                  const std::string &target,
                                  const std::string &keyspace,
                                  const std::string &name = "");

} // namespace latte

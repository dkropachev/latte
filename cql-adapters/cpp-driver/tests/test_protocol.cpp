// Unit tests for protocol parsing and encoding
// Compile: g++ -std=c++17 -I../src tests/test_protocol.cpp ../src/protocol.cpp
// -o test_protocol

#include <cassert>
#include <cstring>
#include <iostream>

#include "protocol.h"

using namespace latte;

void test_buffer_write_read_primitives() {
  std::cout << "Testing Buffer write/read primitives..." << std::endl;

  Buffer buf;

  // Write primitives
  buf.write_byte(0x42);
  buf.write_short(0x1234);
  buf.write_int(0x12345678);
  buf.write_long(0x123456789ABCDEF0ULL);

  // Read back
  buf.reset_read_pos();
  assert(buf.read_byte() == 0x42);
  assert(buf.read_short() == 0x1234);
  assert(buf.read_int() == 0x12345678);
  assert(buf.read_long() == 0x123456789ABCDEF0ULL);

  std::cout << "  PASSED" << std::endl;
}

void test_buffer_write_read_string() {
  std::cout << "Testing Buffer write/read string..." << std::endl;

  Buffer buf;

  // Write strings
  buf.write_string("hello");
  buf.write_long_string("world");

  // Read back
  buf.reset_read_pos();
  assert(buf.read_string() == "hello");
  assert(buf.read_long_string() == "world");

  std::cout << "  PASSED" << std::endl;
}

void test_buffer_write_read_bytes() {
  std::cout << "Testing Buffer write/read bytes..." << std::endl;

  Buffer buf;

  // Write bytes
  std::vector<uint8_t> data = {0x01, 0x02, 0x03, 0x04};
  buf.write_bytes(data);
  buf.write_bytes_nullable(std::nullopt); // NULL
  buf.write_short_bytes(data);

  // Read back
  buf.reset_read_pos();
  auto read_data = buf.read_bytes();
  assert(read_data.has_value());
  assert(*read_data == data);

  auto null_data = buf.read_bytes();
  assert(!null_data.has_value()); // NULL

  auto short_data = buf.read_short_bytes();
  assert(short_data == data);

  std::cout << "  PASSED" << std::endl;
}

void test_buffer_write_read_string_map() {
  std::cout << "Testing Buffer write/read string_map..." << std::endl;

  Buffer buf;

  // Write string map
  std::map<std::string, std::string> m = {
      {"key1", "value1"},
      {"key2", "value2"},
  };
  buf.write_string_map(m);

  // Read back
  buf.reset_read_pos();
  auto read_m = buf.read_string_map();
  assert(read_m.size() == 2);
  assert(read_m["key1"] == "value1");
  assert(read_m["key2"] == "value2");

  std::cout << "  PASSED" << std::endl;
}

void test_frame_header_parse() {
  std::cout << "Testing frame header parsing..." << std::endl;

  uint8_t header_data[9] = {
      0x04,                    // version (request)
      0x00,                    // flags
      0x00, 0x01,              // stream = 1
      0x07,                    // opcode = QUERY
      0x00, 0x00, 0x00, 0x10}; // body_length = 16

  FrameHeader header = parse_frame_header(header_data);

  assert(header.version == 0x04);
  assert(header.flags == 0x00);
  assert(header.stream == 1);
  assert(header.opcode == Opcode::QUERY);
  assert(header.body_length == 16);

  std::cout << "  PASSED" << std::endl;
}

void test_frame_header_encode() {
  std::cout << "Testing frame header encoding..." << std::endl;

  FrameHeader header;
  header.version = RESPONSE_VERSION;
  header.flags = 0;
  header.stream = 42;
  header.opcode = Opcode::RESULT;
  header.body_length = 100;

  Buffer encoded = encode_frame_header(header);

  assert(encoded.size() == FRAME_HEADER_SIZE);
  assert(encoded.data()[0] == RESPONSE_VERSION);
  assert(encoded.data()[1] == 0);
  // stream = 42 = 0x002A
  assert(encoded.data()[2] == 0x00);
  assert(encoded.data()[3] == 0x2A);
  assert(encoded.data()[4] == static_cast<uint8_t>(Opcode::RESULT));
  // body_length = 100 = 0x00000064
  assert(encoded.data()[5] == 0x00);
  assert(encoded.data()[6] == 0x00);
  assert(encoded.data()[7] == 0x00);
  assert(encoded.data()[8] == 0x64);

  std::cout << "  PASSED" << std::endl;
}

void test_build_error_response() {
  std::cout << "Testing build_error_response..." << std::endl;

  Buffer body = build_error_response(ErrorCode::SERVER, "test error");

  body.reset_read_pos();
  int32_t code = body.read_int();
  std::string msg = body.read_string();

  assert(code == static_cast<int32_t>(ErrorCode::SERVER));
  assert(msg == "test error");

  std::cout << "  PASSED" << std::endl;
}

void test_build_void_result() {
  std::cout << "Testing build_void_result..." << std::endl;

  uint64_t latency = 1500000; // 1.5ms
  Buffer body = build_void_result(latency);

  body.reset_read_pos();
  int32_t kind = body.read_int();
  uint64_t lat = body.read_long();

  assert(kind == static_cast<int32_t>(ResultKind::VOID));
  assert(lat == latency);

  std::cout << "  PASSED" << std::endl;
}

void test_build_session_created_response() {
  std::cout << "Testing build_session_created_response..." << std::endl;

  uint64_t session_id = 12345;
  Buffer body = build_session_created_response(session_id);

  body.reset_read_pos();
  uint64_t read_id = body.read_long();

  assert(read_id == session_id);

  std::cout << "  PASSED" << std::endl;
}

void test_build_set_keyspace_result() {
  std::cout << "Testing build_set_keyspace_result..." << std::endl;

  Buffer body = build_set_keyspace_result("my_keyspace");

  body.reset_read_pos();
  int32_t kind = body.read_int();
  std::string ks = body.read_string();

  assert(kind == static_cast<int32_t>(ResultKind::SET_KEYSPACE));
  assert(ks == "my_keyspace");

  std::cout << "  PASSED" << std::endl;
}

void test_build_schema_change_result() {
  std::cout << "Testing build_schema_change_result..." << std::endl;

  Buffer body =
      build_schema_change_result("CREATED", "TABLE", "ks", "my_table");

  body.reset_read_pos();
  int32_t kind = body.read_int();
  std::string change_type = body.read_string();
  std::string target = body.read_string();
  std::string keyspace = body.read_string();
  std::string name = body.read_string();

  assert(kind == static_cast<int32_t>(ResultKind::SCHEMA_CHANGE));
  assert(change_type == "CREATED");
  assert(target == "TABLE");
  assert(keyspace == "ks");
  assert(name == "my_table");

  std::cout << "  PASSED" << std::endl;
}

void test_encode_frame() {
  std::cout << "Testing encode_frame..." << std::endl;

  Buffer body;
  body.write_string("test");

  Buffer frame = encode_frame(1, Opcode::RESULT, body);

  // Check header
  assert(frame.data()[0] == RESPONSE_VERSION);
  assert(frame.data()[1] == 0);
  // stream = 1
  assert(frame.data()[2] == 0x00);
  assert(frame.data()[3] == 0x01);
  assert(frame.data()[4] == static_cast<uint8_t>(Opcode::RESULT));
  // body_length = 6 (2 bytes length + 4 bytes "test")
  uint32_t body_len = (static_cast<uint32_t>(frame.data()[5]) << 24) |
                      (static_cast<uint32_t>(frame.data()[6]) << 16) |
                      (static_cast<uint32_t>(frame.data()[7]) << 8) |
                      static_cast<uint32_t>(frame.data()[8]);
  assert(body_len == 6);

  // Check body
  assert(frame.size() == FRAME_HEADER_SIZE + 6);

  std::cout << "  PASSED" << std::endl;
}

int main() {
  std::cout << "=== Protocol Unit Tests ===" << std::endl;
  std::cout << std::endl;

  test_buffer_write_read_primitives();
  test_buffer_write_read_string();
  test_buffer_write_read_bytes();
  test_buffer_write_read_string_map();
  test_frame_header_parse();
  test_frame_header_encode();
  test_build_error_response();
  test_build_void_result();
  test_build_session_created_response();
  test_build_set_keyspace_result();
  test_build_schema_change_result();
  test_encode_frame();

  std::cout << std::endl;
  std::cout << "=== All tests PASSED ===" << std::endl;
  return 0;
}

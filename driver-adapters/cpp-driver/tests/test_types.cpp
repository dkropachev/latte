// Unit tests for type handling
// Compile: g++ -std=c++17 -I../src tests/test_types.cpp -o test_types

#include <cassert>
#include <cmath>
#include <cstring>
#include <iostream>
#include <limits>

#include "protocol.h"

using namespace latte;

void test_type_code_values() {
  std::cout << "Testing TypeCode values..." << std::endl;

  // Verify type codes match CQL protocol spec
  assert(static_cast<uint16_t>(TypeCode::ASCII) == 0x0001);
  assert(static_cast<uint16_t>(TypeCode::BIGINT) == 0x0002);
  assert(static_cast<uint16_t>(TypeCode::BLOB) == 0x0003);
  assert(static_cast<uint16_t>(TypeCode::BOOLEAN) == 0x0004);
  assert(static_cast<uint16_t>(TypeCode::COUNTER) == 0x0005);
  assert(static_cast<uint16_t>(TypeCode::DECIMAL) == 0x0006);
  assert(static_cast<uint16_t>(TypeCode::DOUBLE) == 0x0007);
  assert(static_cast<uint16_t>(TypeCode::FLOAT) == 0x0008);
  assert(static_cast<uint16_t>(TypeCode::INT) == 0x0009);
  assert(static_cast<uint16_t>(TypeCode::TIMESTAMP) == 0x000B);
  assert(static_cast<uint16_t>(TypeCode::UUID) == 0x000C);
  assert(static_cast<uint16_t>(TypeCode::TEXT) == 0x000D);
  assert(static_cast<uint16_t>(TypeCode::VARINT) == 0x000E);
  assert(static_cast<uint16_t>(TypeCode::TIMEUUID) == 0x000F);
  assert(static_cast<uint16_t>(TypeCode::INET) == 0x0010);
  assert(static_cast<uint16_t>(TypeCode::DATE) == 0x0011);
  assert(static_cast<uint16_t>(TypeCode::TIME) == 0x0012);
  assert(static_cast<uint16_t>(TypeCode::SMALLINT) == 0x0013);
  assert(static_cast<uint16_t>(TypeCode::TINYINT) == 0x0014);
  assert(static_cast<uint16_t>(TypeCode::DURATION) == 0x0015);
  assert(static_cast<uint16_t>(TypeCode::LIST) == 0x0020);
  assert(static_cast<uint16_t>(TypeCode::MAP) == 0x0021);
  assert(static_cast<uint16_t>(TypeCode::SET) == 0x0022);
  assert(static_cast<uint16_t>(TypeCode::VECTOR) == 0x0030);
  assert(static_cast<uint16_t>(TypeCode::TUPLE) == 0x0031);
  assert(static_cast<uint16_t>(TypeCode::UDT) == 0x0040);

  std::cout << "  PASSED" << std::endl;
}

void test_bigint_encoding() {
  std::cout << "Testing bigint encoding..." << std::endl;

  Buffer buf;

  // Encode a bigint value
  int64_t value = 0x123456789ABCDEF0LL;
  buf.write_long(static_cast<uint64_t>(value));

  // Verify encoding is big-endian
  assert(buf.size() == 8);
  assert(buf.data()[0] == 0x12);
  assert(buf.data()[1] == 0x34);
  assert(buf.data()[2] == 0x56);
  assert(buf.data()[3] == 0x78);
  assert(buf.data()[4] == 0x9A);
  assert(buf.data()[5] == 0xBC);
  assert(buf.data()[6] == 0xDE);
  assert(buf.data()[7] == 0xF0);

  // Read back
  buf.reset_read_pos();
  uint64_t read_value = buf.read_long();
  assert(read_value == static_cast<uint64_t>(value));

  std::cout << "  PASSED" << std::endl;
}

void test_int_encoding() {
  std::cout << "Testing int encoding..." << std::endl;

  Buffer buf;

  // Encode an int value
  int32_t value = 0x12345678;
  buf.write_int(value);

  // Verify encoding is big-endian
  assert(buf.size() == 4);
  assert(buf.data()[0] == 0x12);
  assert(buf.data()[1] == 0x34);
  assert(buf.data()[2] == 0x56);
  assert(buf.data()[3] == 0x78);

  // Read back
  buf.reset_read_pos();
  int32_t read_value = buf.read_int();
  assert(read_value == value);

  std::cout << "  PASSED" << std::endl;
}

void test_negative_int_encoding() {
  std::cout << "Testing negative int encoding..." << std::endl;

  Buffer buf;

  // Encode a negative int value
  int32_t value = -12345;
  buf.write_int(value);

  // Read back
  buf.reset_read_pos();
  int32_t read_value = buf.read_int();
  assert(read_value == value);

  std::cout << "  PASSED" << std::endl;
}

void test_float_encoding() {
  std::cout << "Testing float encoding..." << std::endl;

  Buffer buf;

  // Encode a float value
  float value = 3.14159f;
  uint32_t bits;
  std::memcpy(&bits, &value, sizeof(bits));
  buf.write_int(static_cast<int32_t>(bits));

  // Read back
  buf.reset_read_pos();
  uint32_t read_bits = static_cast<uint32_t>(buf.read_int());
  float read_value;
  std::memcpy(&read_value, &read_bits, sizeof(read_value));

  assert(std::abs(read_value - value) < 0.0001f);

  std::cout << "  PASSED" << std::endl;
}

void test_double_encoding() {
  std::cout << "Testing double encoding..." << std::endl;

  Buffer buf;

  // Encode a double value
  double value = 3.141592653589793;
  uint64_t bits;
  std::memcpy(&bits, &value, sizeof(bits));
  buf.write_long(bits);

  // Read back
  buf.reset_read_pos();
  uint64_t read_bits = buf.read_long();
  double read_value;
  std::memcpy(&read_value, &read_bits, sizeof(read_value));

  assert(std::abs(read_value - value) < 0.0000001);

  std::cout << "  PASSED" << std::endl;
}

void test_text_encoding() {
  std::cout << "Testing text encoding..." << std::endl;

  Buffer buf;

  // Encode a text value
  std::string value = "Hello, World!";
  buf.write_int(static_cast<int32_t>(value.size()));
  buf.append(reinterpret_cast<const uint8_t *>(value.data()), value.size());

  // Read back
  buf.reset_read_pos();
  int32_t len = buf.read_int();
  assert(len == static_cast<int32_t>(value.size()));

  std::cout << "  PASSED" << std::endl;
}

void test_null_value() {
  std::cout << "Testing null value encoding..." << std::endl;

  Buffer buf;

  // Encode a null value (length = -1)
  buf.write_int(-1);

  // Read back
  buf.reset_read_pos();
  auto bytes = buf.read_bytes();
  assert(!bytes.has_value());

  std::cout << "  PASSED" << std::endl;
}

void test_uuid_encoding() {
  std::cout << "Testing UUID encoding..." << std::endl;

  // A well-known UUID: 550e8400-e29b-41d4-a716-446655440000
  uint8_t uuid_bytes[16] = {0x55, 0x0e, 0x84, 0x00, 0xe2, 0x9b, 0x41, 0xd4,
                            0xa7, 0x16, 0x44, 0x66, 0x55, 0x44, 0x00, 0x00};

  Buffer buf;
  buf.write_int(16);
  buf.append(uuid_bytes, 16);

  // Read back
  buf.reset_read_pos();
  auto bytes = buf.read_bytes();
  assert(bytes.has_value());
  assert(bytes->size() == 16);
  assert(std::memcmp(bytes->data(), uuid_bytes, 16) == 0);

  std::cout << "  PASSED" << std::endl;
}

void test_collection_format() {
  std::cout << "Testing collection format..." << std::endl;

  // A list of ints: [1, 2, 3]
  Buffer buf;

  // Element type: INT
  buf.write_short(static_cast<uint16_t>(TypeCode::INT));
  // Count: 3
  buf.write_int(3);
  // Element 1: 1
  buf.write_int(4);
  buf.write_int(1);
  // Element 2: 2
  buf.write_int(4);
  buf.write_int(2);
  // Element 3: 3
  buf.write_int(4);
  buf.write_int(3);

  // Read back
  buf.reset_read_pos();
  uint16_t elem_type = buf.read_short();
  assert(elem_type == static_cast<uint16_t>(TypeCode::INT));

  int32_t count = buf.read_int();
  assert(count == 3);

  // Read elements
  for (int i = 1; i <= 3; ++i) {
    auto elem_bytes = buf.read_bytes();
    assert(elem_bytes.has_value());
    assert(elem_bytes->size() == 4);

    const uint8_t *p = elem_bytes->data();
    int32_t v = (static_cast<int32_t>(p[0]) << 24) |
                (static_cast<int32_t>(p[1]) << 16) |
                (static_cast<int32_t>(p[2]) << 8) | static_cast<int32_t>(p[3]);
    assert(v == i);
  }

  std::cout << "  PASSED" << std::endl;
}

void test_consistency_values() {
  std::cout << "Testing consistency values..." << std::endl;

  // Verify consistency values match CQL protocol spec
  assert(static_cast<uint16_t>(Consistency::ANY) == 0x0000);
  assert(static_cast<uint16_t>(Consistency::ONE) == 0x0001);
  assert(static_cast<uint16_t>(Consistency::TWO) == 0x0002);
  assert(static_cast<uint16_t>(Consistency::THREE) == 0x0003);
  assert(static_cast<uint16_t>(Consistency::QUORUM) == 0x0004);
  assert(static_cast<uint16_t>(Consistency::ALL) == 0x0005);
  assert(static_cast<uint16_t>(Consistency::LOCAL_QUORUM) == 0x0006);
  assert(static_cast<uint16_t>(Consistency::EACH_QUORUM) == 0x0007);
  assert(static_cast<uint16_t>(Consistency::LOCAL_ONE) == 0x000A);

  std::cout << "  PASSED" << std::endl;
}

int main() {
  std::cout << "=== Types Unit Tests ===" << std::endl;
  std::cout << std::endl;

  test_type_code_values();
  test_bigint_encoding();
  test_int_encoding();
  test_negative_int_encoding();
  test_float_encoding();
  test_double_encoding();
  test_text_encoding();
  test_null_value();
  test_uuid_encoding();
  test_collection_format();
  test_consistency_values();

  std::cout << std::endl;
  std::cout << "=== All tests PASSED ===" << std::endl;
  return 0;
}

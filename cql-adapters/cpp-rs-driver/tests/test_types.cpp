// Unit tests for type encoding/decoding
// Note: Full tests require the Cassandra driver, so these test basic encoding logic

#include <cassert>
#include <cmath>
#include <cstring>
#include <iostream>
#include <limits>

#include "protocol.h"

using namespace latte;

void test_type_codes() {
    std::cout << "Testing TypeCode values..." << std::endl;

    // Verify TypeCode values match CQL protocol spec
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

void test_int_encoding() {
    std::cout << "Testing INT encoding..." << std::endl;

    Buffer buf;

    // Encode INT value (big-endian)
    int32_t value = 0x12345678;
    buf.write_int(value);

    // Check encoding
    assert(buf.size() == 4);
    assert(buf.data()[0] == 0x12);
    assert(buf.data()[1] == 0x34);
    assert(buf.data()[2] == 0x56);
    assert(buf.data()[3] == 0x78);

    // Read back
    buf.reset_read_pos();
    assert(buf.read_int() == value);

    std::cout << "  PASSED" << std::endl;
}

void test_bigint_encoding() {
    std::cout << "Testing BIGINT encoding..." << std::endl;

    Buffer buf;

    // Encode BIGINT value (big-endian)
    int64_t value = 0x123456789ABCDEF0LL;
    buf.write_long(static_cast<uint64_t>(value));

    // Check encoding
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
    assert(buf.read_long() == static_cast<uint64_t>(value));

    std::cout << "  PASSED" << std::endl;
}

void test_float_encoding() {
    std::cout << "Testing FLOAT encoding..." << std::endl;

    float value = 3.14159f;
    uint32_t bits;
    std::memcpy(&bits, &value, sizeof(bits));

    Buffer buf;
    buf.write_int(static_cast<int32_t>(bits));

    // Read back and convert
    buf.reset_read_pos();
    uint32_t read_bits = static_cast<uint32_t>(buf.read_int());
    float read_value;
    std::memcpy(&read_value, &read_bits, sizeof(read_value));

    assert(std::abs(read_value - value) < 0.00001f);

    std::cout << "  PASSED" << std::endl;
}

void test_double_encoding() {
    std::cout << "Testing DOUBLE encoding..." << std::endl;

    double value = 3.141592653589793;
    uint64_t bits;
    std::memcpy(&bits, &value, sizeof(bits));

    Buffer buf;
    buf.write_long(bits);

    // Read back and convert
    buf.reset_read_pos();
    uint64_t read_bits = buf.read_long();
    double read_value;
    std::memcpy(&read_value, &read_bits, sizeof(read_value));

    assert(std::abs(read_value - value) < 0.0000000000001);

    std::cout << "  PASSED" << std::endl;
}

void test_boolean_encoding() {
    std::cout << "Testing BOOLEAN encoding..." << std::endl;

    Buffer buf;
    buf.write_byte(1);  // true
    buf.write_byte(0);  // false

    buf.reset_read_pos();
    assert(buf.read_byte() == 1);
    assert(buf.read_byte() == 0);

    std::cout << "  PASSED" << std::endl;
}

void test_text_encoding() {
    std::cout << "Testing TEXT encoding..." << std::endl;

    std::string value = "Hello, World!";
    Buffer buf;
    buf.write_int(static_cast<int32_t>(value.size()));
    buf.append(reinterpret_cast<const uint8_t*>(value.data()), value.size());

    // Read back
    buf.reset_read_pos();
    int32_t len = buf.read_int();
    assert(len == static_cast<int32_t>(value.size()));
    // Read remaining bytes
    std::string read_value(reinterpret_cast<const char*>(buf.data() + buf.read_pos()), len);
    assert(read_value == value);

    std::cout << "  PASSED" << std::endl;
}

void test_uuid_encoding() {
    std::cout << "Testing UUID encoding..." << std::endl;

    // UUID: 550e8400-e29b-41d4-a716-446655440000
    uint8_t uuid_bytes[16] = {
        0x55, 0x0e, 0x84, 0x00,
        0xe2, 0x9b, 0x41, 0xd4,
        0xa7, 0x16, 0x44, 0x66,
        0x55, 0x44, 0x00, 0x00
    };

    Buffer buf;
    buf.write_int(16);
    buf.append(uuid_bytes, 16);

    // Read back
    buf.reset_read_pos();
    int32_t len = buf.read_int();
    assert(len == 16);

    for (int i = 0; i < 16; ++i) {
        uint8_t b = buf.read_byte();
        assert(b == uuid_bytes[i]);
    }

    std::cout << "  PASSED" << std::endl;
}

void test_null_encoding() {
    std::cout << "Testing NULL encoding..." << std::endl;

    Buffer buf;
    buf.write_int(-1);  // NULL marker

    buf.reset_read_pos();
    int32_t len = buf.read_int();
    assert(len == -1);

    std::cout << "  PASSED" << std::endl;
}

void test_list_encoding_format() {
    std::cout << "Testing LIST encoding format..." << std::endl;

    // List format: [short element_type] [int n_elements] [elements...]
    Buffer buf;
    buf.write_short(static_cast<uint16_t>(TypeCode::INT));  // element type
    buf.write_int(3);  // 3 elements

    // Element 1: value 10
    buf.write_int(4);  // length
    buf.write_int(10); // value

    // Element 2: value 20
    buf.write_int(4);
    buf.write_int(20);

    // Element 3: value 30
    buf.write_int(4);
    buf.write_int(30);

    // Verify
    buf.reset_read_pos();
    assert(buf.read_short() == static_cast<uint16_t>(TypeCode::INT));
    assert(buf.read_int() == 3);

    // Read elements
    assert(buf.read_int() == 4);
    assert(buf.read_int() == 10);
    assert(buf.read_int() == 4);
    assert(buf.read_int() == 20);
    assert(buf.read_int() == 4);
    assert(buf.read_int() == 30);

    std::cout << "  PASSED" << std::endl;
}

void test_map_encoding_format() {
    std::cout << "Testing MAP encoding format..." << std::endl;

    // Map format: [short key_type] [short value_type] [int n_entries] [entries...]
    Buffer buf;
    buf.write_short(static_cast<uint16_t>(TypeCode::TEXT));  // key type
    buf.write_short(static_cast<uint16_t>(TypeCode::INT));   // value type
    buf.write_int(2);  // 2 entries

    // Entry 1: "a" -> 1
    buf.write_int(1);  // key length
    buf.write_byte('a');
    buf.write_int(4);  // value length
    buf.write_int(1);  // value

    // Entry 2: "b" -> 2
    buf.write_int(1);
    buf.write_byte('b');
    buf.write_int(4);
    buf.write_int(2);

    // Verify
    buf.reset_read_pos();
    assert(buf.read_short() == static_cast<uint16_t>(TypeCode::TEXT));
    assert(buf.read_short() == static_cast<uint16_t>(TypeCode::INT));
    assert(buf.read_int() == 2);

    std::cout << "  PASSED" << std::endl;
}

void test_consistency_values() {
    std::cout << "Testing Consistency values..." << std::endl;

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
    std::cout << "=== Type Encoding Unit Tests ===" << std::endl;
    std::cout << std::endl;

    test_type_codes();
    test_int_encoding();
    test_bigint_encoding();
    test_float_encoding();
    test_double_encoding();
    test_boolean_encoding();
    test_text_encoding();
    test_uuid_encoding();
    test_null_encoding();
    test_list_encoding_format();
    test_map_encoding_format();
    test_consistency_values();

    std::cout << std::endl;
    std::cout << "=== All tests PASSED ===" << std::endl;
    return 0;
}

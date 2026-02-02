"""Tests for value encoding/decoding."""

import struct
import uuid
from datetime import date
from decimal import Decimal
from ipaddress import IPv4Address, IPv6Address

from src.protocol import TypeCode
from src.values import (
    _decode_decimal,
    _decode_varint,
    _encode_decimal,
    _encode_varint,
    _parse_date_string,
    _parse_duration_string,
    _parse_time_string,
    decode_typed_value,
    encode_value,
)


class TestPrimitiveDecoding:
    """Test decoding of primitive types."""

    def test_decode_text(self):
        data = b"hello world"
        result = decode_typed_value(TypeCode.TEXT, data)
        assert result == "hello world"

    def test_decode_ascii(self):
        data = b"ascii text"
        result = decode_typed_value(TypeCode.ASCII, data)
        assert result == "ascii text"

    def test_decode_bigint(self):
        data = struct.pack(">q", 1234567890123)
        result = decode_typed_value(TypeCode.BIGINT, data)
        assert result == 1234567890123

    def test_decode_bigint_negative(self):
        data = struct.pack(">q", -9876543210)
        result = decode_typed_value(TypeCode.BIGINT, data)
        assert result == -9876543210

    def test_decode_int(self):
        data = struct.pack(">i", 42)
        result = decode_typed_value(TypeCode.INT, data)
        assert result == 42

    def test_decode_int_negative(self):
        data = struct.pack(">i", -100)
        result = decode_typed_value(TypeCode.INT, data)
        assert result == -100

    def test_decode_smallint(self):
        data = struct.pack(">h", 1000)
        result = decode_typed_value(TypeCode.SMALLINT, data)
        assert result == 1000

    def test_decode_tinyint(self):
        data = struct.pack(">b", 127)
        result = decode_typed_value(TypeCode.TINYINT, data)
        assert result == 127

    def test_decode_boolean_true(self):
        result = decode_typed_value(TypeCode.BOOLEAN, b"\x01")
        assert result is True

    def test_decode_boolean_false(self):
        result = decode_typed_value(TypeCode.BOOLEAN, b"\x00")
        assert result is False

    def test_decode_float(self):
        data = struct.pack(">f", 3.14)
        result = decode_typed_value(TypeCode.FLOAT, data)
        assert abs(result - 3.14) < 0.001

    def test_decode_double(self):
        data = struct.pack(">d", 3.141592653589793)
        result = decode_typed_value(TypeCode.DOUBLE, data)
        assert abs(result - 3.141592653589793) < 1e-10

    def test_decode_uuid(self):
        test_uuid = uuid.uuid4()
        data = test_uuid.bytes
        result = decode_typed_value(TypeCode.UUID, data)
        assert result == test_uuid

    def test_decode_timeuuid(self):
        test_uuid = uuid.uuid1()
        data = test_uuid.bytes
        result = decode_typed_value(TypeCode.TIMEUUID, data)
        assert result == test_uuid

    def test_decode_blob(self):
        data = b"\x00\x01\x02\x03\xff"
        result = decode_typed_value(TypeCode.BLOB, data)
        assert result == data

    def test_decode_inet_v4(self):
        data = bytes([192, 168, 1, 1])
        result = decode_typed_value(TypeCode.INET, data)
        assert result == IPv4Address("192.168.1.1")

    def test_decode_inet_v6(self):
        data = bytes([0x20, 0x01, 0x0d, 0xb8, 0x85, 0xa3, 0x00, 0x00,
                      0x00, 0x00, 0x8a, 0x2e, 0x03, 0x70, 0x73, 0x34])
        result = decode_typed_value(TypeCode.INET, data)
        assert result == IPv6Address("2001:db8:85a3::8a2e:370:7334")

    def test_decode_date(self):
        # CQL date is days since epoch (centered at 2^31)
        # 2024-01-15 is 19737 days after 1970-01-01
        days = 19737 + (1 << 31)
        data = struct.pack(">I", days)
        result = decode_typed_value(TypeCode.DATE, data)
        assert result == days

    def test_decode_null(self):
        result = decode_typed_value(TypeCode.TEXT, None)
        assert result is None


class TestPrimitiveEncoding:
    """Test encoding of primitive types."""

    def test_encode_text(self):
        result = encode_value("hello", TypeCode.TEXT)
        assert result == b"hello"

    def test_encode_bigint(self):
        result = encode_value(1234567890123, TypeCode.BIGINT)
        assert result == struct.pack(">q", 1234567890123)

    def test_encode_int(self):
        result = encode_value(42, TypeCode.INT)
        assert result == struct.pack(">i", 42)

    def test_encode_smallint(self):
        result = encode_value(1000, TypeCode.SMALLINT)
        assert result == struct.pack(">h", 1000)

    def test_encode_tinyint(self):
        result = encode_value(127, TypeCode.TINYINT)
        assert result == struct.pack(">b", 127)

    def test_encode_boolean_true(self):
        result = encode_value(True, TypeCode.BOOLEAN)
        assert result == b"\x01"

    def test_encode_boolean_false(self):
        result = encode_value(False, TypeCode.BOOLEAN)
        assert result == b"\x00"

    def test_encode_float(self):
        result = encode_value(3.14, TypeCode.FLOAT)
        assert result == struct.pack(">f", 3.14)

    def test_encode_double(self):
        result = encode_value(3.14159, TypeCode.DOUBLE)
        assert result == struct.pack(">d", 3.14159)

    def test_encode_uuid(self):
        test_uuid = uuid.uuid4()
        result = encode_value(test_uuid, TypeCode.UUID)
        assert result == test_uuid.bytes

    def test_encode_blob(self):
        data = b"\x00\x01\x02"
        result = encode_value(data, TypeCode.BLOB)
        assert result == data

    def test_encode_null(self):
        result = encode_value(None, TypeCode.TEXT)
        assert result == b""


class TestVarint:
    """Test varint encoding/decoding."""

    def test_decode_varint_zero(self):
        assert _decode_varint(b"\x00") == 0

    def test_decode_varint_positive_small(self):
        assert _decode_varint(b"\x7f") == 127

    def test_decode_varint_positive_large(self):
        # 256 = 0x0100
        assert _decode_varint(b"\x01\x00") == 256

    def test_decode_varint_negative_small(self):
        # -1 = 0xff in two's complement
        assert _decode_varint(b"\xff") == -1

    def test_decode_varint_negative_large(self):
        # -128 = 0x80 in two's complement
        assert _decode_varint(b"\x80") == -128

    def test_encode_varint_zero(self):
        assert _encode_varint(0) == b"\x00"

    def test_encode_varint_positive_small(self):
        assert _encode_varint(1) == b"\x01"

    def test_encode_varint_positive_needs_sign_byte(self):
        # 128 needs a leading 0x00 to indicate positive
        assert _encode_varint(128) == b"\x00\x80"

    def test_encode_varint_negative(self):
        assert _encode_varint(-1) == b"\xff"

    def test_varint_roundtrip(self):
        for val in [0, 1, -1, 127, 128, -128, 255, -256, 1000000, -1000000]:
            encoded = _encode_varint(val)
            decoded = _decode_varint(encoded)
            assert decoded == val, f"Roundtrip failed for {val}"


class TestDecimal:
    """Test decimal encoding/decoding."""

    def test_decode_decimal_integer(self):
        # scale=0, unscaled=42
        data = struct.pack(">i", 0) + b"\x2a"  # 42 in varint
        result = _decode_decimal(data)
        assert result == Decimal("42")

    def test_decode_decimal_with_scale(self):
        # scale=2, unscaled=12345 -> 123.45
        data = struct.pack(">i", 2) + b"\x30\x39"  # 12345 in varint
        result = _decode_decimal(data)
        assert result == Decimal("123.45")

    def test_encode_decimal_roundtrip(self):
        for val in ["0", "1", "-1", "123.45", "-999.999", "0.001"]:
            dec = Decimal(val)
            encoded = _encode_decimal(dec)
            decoded = _decode_decimal(encoded)
            assert decoded == dec, f"Roundtrip failed for {val}"


class TestTypeCoercion:
    """Test type coercion during decoding."""

    def test_bigint_to_int(self):
        data = struct.pack(">q", 100)
        result = decode_typed_value(TypeCode.BIGINT, data, target_type="int")
        assert result == 100

    def test_bigint_to_smallint(self):
        data = struct.pack(">q", 1000)
        result = decode_typed_value(TypeCode.BIGINT, data, target_type="smallint")
        assert result == 1000

    def test_text_to_date(self):
        data = b"2024-01-15"
        result = decode_typed_value(TypeCode.TEXT, data, target_type="date")
        assert result == date(2024, 1, 15)

    def test_text_to_uuid(self):
        test_uuid = uuid.uuid4()
        data = str(test_uuid).encode("utf-8")
        result = decode_typed_value(TypeCode.TEXT, data, target_type="uuid")
        assert result == test_uuid

    def test_text_to_inet(self):
        data = b"192.168.1.1"
        result = decode_typed_value(TypeCode.TEXT, data, target_type="inet")
        assert result == IPv4Address("192.168.1.1")

    def test_text_to_decimal(self):
        data = b"123.456"
        result = decode_typed_value(TypeCode.TEXT, data, target_type="decimal")
        assert result == Decimal("123.456")

    def test_text_to_varint(self):
        data = b"123456789"
        result = decode_typed_value(TypeCode.TEXT, data, target_type="varint")
        assert result == 123456789

    def test_double_to_float(self):
        data = struct.pack(">d", 3.14)
        result = decode_typed_value(TypeCode.DOUBLE, data, target_type="float")
        assert abs(result - 3.14) < 0.001


class TestDateTimeParsing:
    """Test date/time string parsing."""

    def test_parse_date_string(self):
        result = _parse_date_string("2024-01-15")
        assert result == date(2024, 1, 15)

    def test_parse_date_string_invalid(self):
        result = _parse_date_string("invalid")
        assert result == date(1970, 1, 1)

    def test_parse_time_string(self):
        # 14:30:45 = (14*3600 + 30*60 + 45) * 1_000_000_000 nanos
        result = _parse_time_string("14:30:45")
        expected = (14 * 3600 + 30 * 60 + 45) * 1_000_000_000
        assert result == expected

    def test_parse_time_string_invalid(self):
        result = _parse_time_string("invalid")
        assert result == 0

    def test_parse_duration_string_simple(self):
        result = _parse_duration_string("1mo2d3h4m5s")
        assert result.months == 1
        assert result.days == 2
        # 3h4m5s = 3*3600 + 4*60 + 5 = 11045 seconds = 11045 * 1e9 nanos
        expected_nanos = (3 * 3600 + 4 * 60 + 5) * 1_000_000_000
        assert result.nanoseconds == expected_nanos

    def test_parse_duration_string_days_only(self):
        result = _parse_duration_string("10d")
        assert result.months == 0
        assert result.days == 10
        assert result.nanoseconds == 0

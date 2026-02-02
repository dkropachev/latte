"""Tests for protocol encoding/decoding."""

import struct
from io import BytesIO

from src.protocol import (
    ErrorCode,
    Frame,
    FrameHeader,
    Opcode,
    ResultKind,
    encode_frame,
    error_frame,
    read_byte,
    read_bytes,
    read_int,
    read_long,
    read_short,
    read_string,
    read_string_map,
    session_created_frame,
    void_result_frame,
    write_byte,
    write_bytes,
    write_int,
    write_long,
    write_short,
    write_string,
)


class TestPrimitiveReadWrite:
    """Test primitive type read/write functions."""

    def test_byte(self):
        buf = BytesIO()
        write_byte(buf, 0x42)
        buf.seek(0)
        assert read_byte(buf) == 0x42

    def test_short(self):
        buf = BytesIO()
        write_short(buf, 0x1234)
        buf.seek(0)
        assert read_short(buf) == 0x1234

    def test_int(self):
        buf = BytesIO()
        write_int(buf, -12345678)
        buf.seek(0)
        assert read_int(buf) == -12345678

    def test_long(self):
        buf = BytesIO()
        write_long(buf, 0x123456789ABCDEF0)
        buf.seek(0)
        assert read_long(buf) == 0x123456789ABCDEF0

    def test_string(self):
        buf = BytesIO()
        write_string(buf, "hello world")
        buf.seek(0)
        assert read_string(buf) == "hello world"

    def test_bytes_normal(self):
        buf = BytesIO()
        write_bytes(buf, b"\x01\x02\x03")
        buf.seek(0)
        assert read_bytes(buf) == b"\x01\x02\x03"

    def test_bytes_null(self):
        buf = BytesIO()
        write_bytes(buf, None)
        buf.seek(0)
        assert read_bytes(buf) is None

    def test_string_map(self):
        buf = BytesIO()
        # Write map manually
        write_short(buf, 2)  # count
        write_string(buf, "key1")
        write_string(buf, "value1")
        write_string(buf, "key2")
        write_string(buf, "value2")
        buf.seek(0)
        result = read_string_map(buf)
        assert result == {"key1": "value1", "key2": "value2"}


class TestFrameEncoding:
    """Test frame encoding."""

    def test_void_result_frame(self):
        frame = void_result_frame(123)
        assert frame.header.stream == 123
        assert frame.header.opcode == Opcode.RESULT
        # Body should contain kind=VOID (4 bytes, big-endian 1)
        assert struct.unpack(">i", frame.body[:4])[0] == ResultKind.VOID

    def test_error_frame(self):
        frame = error_frame(456, ErrorCode.SERVER, "test error")
        assert frame.header.stream == 456
        assert frame.header.opcode == Opcode.ERROR
        # Body contains error code (4 bytes) + message
        buf = BytesIO(frame.body)
        assert read_int(buf) == ErrorCode.SERVER
        assert read_string(buf) == "test error"

    def test_session_created_frame(self):
        frame = session_created_frame(789, 0x123456789ABCDEF0)
        assert frame.header.stream == 789
        assert frame.header.opcode == Opcode.SESSION_CREATED
        buf = BytesIO(frame.body)
        assert read_long(buf) == 0x123456789ABCDEF0

    def test_encode_frame(self):
        frame = Frame(
            header=FrameHeader(
                version=0x84,
                flags=0,
                stream=100,
                opcode=Opcode.RESULT,
                body_length=4,
            ),
            body=b"\x00\x00\x00\x01",  # VOID result
        )
        encoded = encode_frame(frame)
        # Header: 9 bytes, Body: 4 bytes
        assert len(encoded) == 13
        # Check header
        assert encoded[0] == 0x84  # response version
        assert encoded[1] == 0  # flags
        assert struct.unpack(">H", encoded[2:4])[0] == 100  # stream
        assert encoded[4] == Opcode.RESULT
        assert struct.unpack(">I", encoded[5:9])[0] == 4  # body length
        # Check body
        assert encoded[9:] == b"\x00\x00\x00\x01"

"""CQL binary protocol frame encoding and decoding."""

import struct
from dataclasses import dataclass
from enum import IntEnum
from io import BytesIO
from threading import local

# Protocol constants
HEADER_LENGTH = 9
VERSION_REQUEST = 0x04
VERSION_RESPONSE = 0x84
MAX_BODY_LENGTH = 16 * 1024 * 1024  # 16MB

# =============================================================================
# Performance Optimization: Pre-compiled struct objects
# =============================================================================
# Using pre-compiled Struct objects instead of struct.pack/unpack with format
# strings avoids format string parsing overhead on each call. This is a hot
# path optimization for high-throughput benchmarking.

_STRUCT_HEADER = struct.Struct(">BBHBI")  # Frame header: version, flags, stream, opcode, length
_STRUCT_BYTE = struct.Struct(">B")        # Single byte
_STRUCT_SHORT = struct.Struct(">H")       # 2-byte unsigned
_STRUCT_INT = struct.Struct(">i")         # 4-byte signed
_STRUCT_UINT = struct.Struct(">I")        # 4-byte unsigned
_STRUCT_LONG = struct.Struct(">Q")        # 8-byte unsigned
_STRUCT_SIGNED_LONG = struct.Struct(">q") # 8-byte signed
_STRUCT_FLOAT = struct.Struct(">f")       # 4-byte float
_STRUCT_DOUBLE = struct.Struct(">d")      # 8-byte double
_STRUCT_SHORT_SIGNED = struct.Struct(">h") # 2-byte signed
_STRUCT_BYTE_SIGNED = struct.Struct(">b")  # 1-byte signed

# =============================================================================
# Performance Optimization: Buffer pool for BytesIO objects
# =============================================================================
# Thread-local buffer pool reduces allocation overhead for frame encoding.
# Each thread maintains its own pool to avoid synchronization overhead.

_BUFFER_POOL_SIZE = 8  # Number of buffers to keep per thread
_thread_local = local()


def _get_buffer() -> BytesIO:
    """Get a BytesIO buffer from the thread-local pool, or create a new one."""
    if not hasattr(_thread_local, "buffer_pool"):
        _thread_local.buffer_pool = []

    pool = _thread_local.buffer_pool
    if pool:
        buf = pool.pop()
        buf.seek(0)
        buf.truncate(0)
        return buf
    return BytesIO()


def _return_buffer(buf: BytesIO) -> None:
    """Return a BytesIO buffer to the thread-local pool."""
    if not hasattr(_thread_local, "buffer_pool"):
        _thread_local.buffer_pool = []

    pool = _thread_local.buffer_pool
    if len(pool) < _BUFFER_POOL_SIZE:
        pool.append(buf)


# Pre-computed VOID result body (never changes)
_VOID_RESULT_BODY = _STRUCT_INT.pack(0x0001)  # ResultKind.VOID


class Opcode(IntEnum):
    """CQL protocol opcodes."""

    ERROR = 0x00
    STARTUP = 0x01
    READY = 0x02
    OPTIONS = 0x05
    SUPPORTED = 0x06
    QUERY = 0x07
    RESULT = 0x08
    PREPARE = 0x09
    EXECUTE = 0x0A
    BATCH = 0x0D
    AUTH_CHALLENGE = 0x0E
    AUTH_RESPONSE = 0x0F
    AUTH_SUCCESS = 0x10
    CREATE_SESSION = 0x21
    SESSION_CREATED = 0x22


class ErrorCode(IntEnum):
    """CQL error codes."""

    SERVER = 0x0000
    PROTOCOL = 0x000A
    BAD_CREDENTIALS = 0x0100
    UNAVAILABLE = 0x1000
    OVERLOADED = 0x1001
    IS_BOOTSTRAPPING = 0x1002
    TRUNCATE_ERROR = 0x1003
    WRITE_TIMEOUT = 0x1100
    READ_TIMEOUT = 0x1200
    READ_FAILURE = 0x1300
    FUNCTION_FAILURE = 0x1400
    WRITE_FAILURE = 0x1500
    SYNTAX = 0x2000
    UNAUTHORIZED = 0x2100
    INVALID = 0x2200
    CONFIG_ERROR = 0x2300
    ALREADY_EXISTS = 0x2400
    UNPREPARED = 0x2500


class ResultKind(IntEnum):
    """RESULT response kinds."""

    VOID = 0x0001
    ROWS = 0x0002
    SET_KEYSPACE = 0x0003
    PREPARED = 0x0004
    SCHEMA_CHANGE = 0x0005


class BatchType(IntEnum):
    """Batch operation types."""

    LOGGED = 0
    UNLOGGED = 1
    COUNTER = 2


class Consistency(IntEnum):
    """CQL consistency levels."""

    ANY = 0x0000
    ONE = 0x0001
    TWO = 0x0002
    THREE = 0x0003
    QUORUM = 0x0004
    ALL = 0x0005
    LOCAL_QUORUM = 0x0006
    EACH_QUORUM = 0x0007
    LOCAL_ONE = 0x000A


class TypeCode(IntEnum):
    """CQL type codes."""

    ASCII = 0x0001
    BIGINT = 0x0002
    BLOB = 0x0003
    BOOLEAN = 0x0004
    COUNTER = 0x0005
    DECIMAL = 0x0006
    DOUBLE = 0x0007
    FLOAT = 0x0008
    INT = 0x0009
    TIMESTAMP = 0x000B
    UUID = 0x000C
    TEXT = 0x000D
    VARINT = 0x000E
    TIMEUUID = 0x000F
    INET = 0x0010
    DATE = 0x0011
    TIME = 0x0012
    SMALLINT = 0x0013
    TINYINT = 0x0014
    DURATION = 0x0015
    LIST = 0x0020
    MAP = 0x0021
    SET = 0x0022
    VECTOR = 0x0030
    TUPLE = 0x0031
    PACKED_FLOAT_VECTOR_LIST = 0x0032
    UDT = 0x0040


@dataclass(slots=True)
class FrameHeader:
    """CQL protocol frame header."""

    version: int
    flags: int
    stream: int
    opcode: Opcode
    body_length: int


@dataclass(slots=True)
class Frame:
    """CQL protocol frame."""

    header: FrameHeader
    body: bytes


def read_frame(reader: BytesIO) -> Frame | None:
    """Read a complete frame from a reader.

    Returns None on clean disconnect (EOF).
    """
    header_bytes = reader.read(HEADER_LENGTH)
    if len(header_bytes) == 0:
        return None  # Clean disconnect
    if len(header_bytes) < HEADER_LENGTH:
        raise ValueError(f"Incomplete header: got {len(header_bytes)} bytes")

    version, flags, stream, opcode, body_length = _STRUCT_HEADER.unpack(header_bytes)

    if body_length > MAX_BODY_LENGTH:
        raise ValueError(f"Body too large: {body_length} bytes")

    body = b""
    if body_length > 0:
        body = reader.read(body_length)
        if len(body) < body_length:
            raise ValueError(f"Incomplete body: got {len(body)} of {body_length} bytes")

    return Frame(
        header=FrameHeader(
            version=version,
            flags=flags,
            stream=stream,
            opcode=Opcode(opcode),
            body_length=body_length,
        ),
        body=body,
    )


def encode_frame(frame: Frame) -> bytes:
    """Encode a frame to bytes using pre-compiled struct for efficiency."""
    header = _STRUCT_HEADER.pack(
        VERSION_RESPONSE,
        frame.header.flags,
        frame.header.stream & 0xFFFF,
        frame.header.opcode,
        len(frame.body),
    )
    return header + frame.body


def new_frame(stream: int, opcode: Opcode, body: bytes) -> Frame:
    """Create a new response frame."""
    return Frame(
        header=FrameHeader(
            version=VERSION_RESPONSE,
            flags=0,
            stream=stream,
            opcode=opcode,
            body_length=len(body),
        ),
        body=body,
    )


def error_frame(stream: int, code: ErrorCode, message: str) -> Frame:
    """Create an error response frame using pooled buffer."""
    buf = _get_buffer()
    try:
        write_int(buf, code)
        write_string(buf, message)
        return new_frame(stream, Opcode.ERROR, buf.getvalue())
    finally:
        _return_buffer(buf)


def session_created_frame(stream: int, session_id: int) -> Frame:
    """Create a SESSION_CREATED response frame."""
    # Direct pack for simple single-value frame (no buffer pool needed)
    body = _STRUCT_LONG.pack(session_id & 0xFFFFFFFFFFFFFFFF)
    return new_frame(stream, Opcode.SESSION_CREATED, body)


def void_result_frame(stream: int) -> Frame:
    """Create a VOID RESULT response frame using pre-computed body."""
    return new_frame(stream, Opcode.RESULT, _VOID_RESULT_BODY)


def prepared_result_frame(stream: int, statement_key: str) -> Frame:
    """Create a PREPARED RESULT response frame using pooled buffer."""
    buf = _get_buffer()
    try:
        # RESULT kind = PREPARED
        write_int(buf, ResultKind.PREPARED)

        # Echo statement key
        write_string(buf, statement_key)

        # Prepared statement ID as short bytes
        key_bytes = statement_key.encode("utf-8")
        write_short_bytes(buf, key_bytes)

        # Bind metadata: flags=0, columns_count=0
        write_int(buf, 0)
        write_int(buf, 0)

        # Result metadata: flags=0, columns_count=0
        write_int(buf, 0)
        write_int(buf, 0)

        return new_frame(stream, Opcode.RESULT, buf.getvalue())
    finally:
        _return_buffer(buf)


@dataclass(slots=True)
class ColumnMeta:
    """Column metadata for ROWS result."""

    keyspace: str
    table: str
    name: str
    type_code: int


def rows_result_frame(stream: int, columns: list[ColumnMeta], rows: list[list]) -> Frame:
    """Create a ROWS RESULT response frame using pooled buffer."""
    from .values import encode_value

    buf = _get_buffer()
    try:
        # RESULT kind = ROWS
        write_int(buf, ResultKind.ROWS)

        # Flags (no metadata ID, no paging)
        write_int(buf, 0)

        # Column count
        write_int(buf, len(columns))

        # Column metadata
        for col in columns:
            write_string(buf, col.keyspace)
            write_string(buf, col.table)
            write_string(buf, col.name)
            write_short(buf, col.type_code)

        # Row count
        write_int(buf, len(rows))

        # Row data
        for row in rows:
            for i, value in enumerate(row):
                if value is None:
                    write_int(buf, -1)  # NULL
                else:
                    encoded = encode_value(value, columns[i].type_code)
                    write_bytes(buf, encoded)

        return new_frame(stream, Opcode.RESULT, buf.getvalue())
    finally:
        _return_buffer(buf)


# Primitive type read/write functions


def read_byte(reader: BytesIO) -> int:
    """Read a single byte."""
    data = reader.read(1)
    if len(data) < 1:
        raise ValueError("Unexpected EOF reading byte")
    return data[0]


def read_short(reader: BytesIO) -> int:
    """Read a 2-byte unsigned integer using pre-compiled struct."""
    data = reader.read(2)
    if len(data) < 2:
        raise ValueError("Unexpected EOF reading short")
    return _STRUCT_SHORT.unpack(data)[0]


def read_int(reader: BytesIO) -> int:
    """Read a 4-byte signed integer using pre-compiled struct."""
    data = reader.read(4)
    if len(data) < 4:
        raise ValueError("Unexpected EOF reading int")
    return _STRUCT_INT.unpack(data)[0]


def read_long(reader: BytesIO) -> int:
    """Read an 8-byte unsigned integer using pre-compiled struct."""
    data = reader.read(8)
    if len(data) < 8:
        raise ValueError("Unexpected EOF reading long")
    return _STRUCT_LONG.unpack(data)[0]


def read_string(reader: BytesIO) -> str:
    """Read a short string (2-byte length prefix)."""
    length = read_short(reader)
    data = reader.read(length)
    if len(data) < length:
        raise ValueError("Unexpected EOF reading string")
    return data.decode("utf-8")


def read_long_string(reader: BytesIO) -> str:
    """Read a long string (4-byte length prefix)."""
    length = read_int(reader)
    if length < 0:
        raise ValueError("Invalid string length")
    data = reader.read(length)
    if len(data) < length:
        raise ValueError("Unexpected EOF reading long string")
    return data.decode("utf-8")


def read_bytes(reader: BytesIO) -> bytes | None:
    """Read bytes with 4-byte length prefix. Returns None for -1 length."""
    length = read_int(reader)
    if length < 0:
        return None
    data = reader.read(length)
    if len(data) < length:
        raise ValueError("Unexpected EOF reading bytes")
    return data


def read_short_bytes(reader: BytesIO) -> bytes:
    """Read bytes with 2-byte length prefix."""
    length = read_short(reader)
    data = reader.read(length)
    if len(data) < length:
        raise ValueError("Unexpected EOF reading short bytes")
    return data


def read_string_map(reader: BytesIO) -> dict[str, str]:
    """Read a string map."""
    count = read_short(reader)
    result = {}
    for _ in range(count):
        key = read_string(reader)
        value = read_string(reader)
        result[key] = value
    return result


def write_byte(writer: BytesIO, value: int) -> None:
    """Write a single byte."""
    writer.write(bytes([value & 0xFF]))


def write_short(writer: BytesIO, value: int) -> None:
    """Write a 2-byte unsigned integer using pre-compiled struct."""
    writer.write(_STRUCT_SHORT.pack(value & 0xFFFF))


def write_int(writer: BytesIO, value: int) -> None:
    """Write a 4-byte signed integer using pre-compiled struct."""
    writer.write(_STRUCT_INT.pack(value))


def write_long(writer: BytesIO, value: int) -> None:
    """Write an 8-byte unsigned integer using pre-compiled struct."""
    writer.write(_STRUCT_LONG.pack(value & 0xFFFFFFFFFFFFFFFF))


def write_string(writer: BytesIO, value: str) -> None:
    """Write a short string with 2-byte length prefix."""
    data = value.encode("utf-8")
    write_short(writer, len(data))
    writer.write(data)


def write_long_string(writer: BytesIO, value: str) -> None:
    """Write a long string with 4-byte length prefix."""
    data = value.encode("utf-8")
    write_int(writer, len(data))
    writer.write(data)


def write_bytes(writer: BytesIO, value: bytes | None) -> None:
    """Write bytes with 4-byte length prefix. None writes -1 length."""
    if value is None:
        write_int(writer, -1)
    else:
        write_int(writer, len(value))
        writer.write(value)


def write_short_bytes(writer: BytesIO, value: bytes) -> None:
    """Write bytes with 2-byte length prefix."""
    write_short(writer, len(value))
    writer.write(value)

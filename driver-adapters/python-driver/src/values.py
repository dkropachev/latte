"""CQL value encoding and decoding."""

import re
import struct
import uuid
from collections.abc import Mapping, Set as AbstractSet
from datetime import date, datetime
from decimal import Decimal
from functools import lru_cache
from io import BytesIO
from ipaddress import IPv4Address, IPv6Address, ip_address
from threading import local

from cassandra.util import Duration, SortedSet

from .protocol import TypeCode, read_int, read_short


# =============================================================================
# Buffer pool for value encoding (reduces allocation overhead)
# =============================================================================
_ENCODE_BUFFER_POOL_SIZE = 4
_encode_thread_local = local()


def _get_encode_buffer() -> BytesIO:
    """Get a BytesIO buffer from thread-local pool for encoding."""
    if not hasattr(_encode_thread_local, "pool"):
        _encode_thread_local.pool = []
    pool = _encode_thread_local.pool
    if pool:
        buf = pool.pop()
        buf.seek(0)
        buf.truncate(0)
        return buf
    return BytesIO()


def _return_encode_buffer(buf: BytesIO) -> None:
    """Return a BytesIO buffer to thread-local pool."""
    if not hasattr(_encode_thread_local, "pool"):
        _encode_thread_local.pool = []
    pool = _encode_thread_local.pool
    if len(pool) < _ENCODE_BUFFER_POOL_SIZE:
        pool.append(buf)


# =============================================================================
# Type parsing utilities
# =============================================================================


@lru_cache(maxsize=256)
def _normalize_type(cql_type: str) -> str:
    """Normalize a CQL type string by stripping whitespace and lowercasing.

    Results are cached to avoid repeated string operations on the same type.
    """
    return cql_type.strip().lower()


@lru_cache(maxsize=256)
def _unwrap_frozen(cql_type: str) -> str:
    """Unwrap frozen<...> wrapper from a CQL type string."""
    normalized = _normalize_type(cql_type)
    if normalized.startswith("frozen<") and normalized.endswith(">"):
        return normalized[7:-1].strip()
    return normalized


@lru_cache(maxsize=256)
def _parse_collection_element_types(cql_type: str) -> tuple[str, str | None]:
    """Parse element types from a collection type string.

    Returns (first_type, second_type) where second_type is None for non-maps.
    Results are cached to avoid repeated parsing of the same type strings.

    Examples:
        "list<int>" -> ("int", None)
        "set<text>" -> ("text", None)
        "map<text, int>" -> ("text", "int")
        "frozen<list<bigint>>" -> ("bigint", None)
    """
    # _unwrap_frozen already returns normalized (lowercased, stripped) type
    cql_type = _unwrap_frozen(cql_type)

    # Match list<type> or set<type>
    match = re.match(r"(list|set)<(.+)>$", cql_type)
    if match:
        return match.group(2).strip(), None

    # Match map<key, value>
    match = re.match(r"map<(.+)>$", cql_type)
    if match:
        inner = match.group(1)
        # Split on comma at depth 0 (not inside nested <>)
        depth = 0
        split_pos = -1
        for i, c in enumerate(inner):
            if c == "<":
                depth += 1
            elif c == ">":
                depth -= 1
            elif c == "," and depth == 0:
                split_pos = i
                break
        if split_pos > 0:
            return inner[:split_pos].strip(), inner[split_pos + 1 :].strip()

    return "", None


def _coerce_int_value(value: object, target_type: str) -> object:
    """Coerce an integer value to the target type."""
    if not isinstance(value, int):
        return value

    target_lower = _normalize_type(target_type)
    if target_lower == "int":
        # Convert to signed 32-bit int
        if value >= 0:
            return value & 0x7FFFFFFF if value <= 0x7FFFFFFF else (value & 0xFFFFFFFF) - 0x100000000
        else:
            return value | ~0xFFFFFFFF if value < -0x80000000 else value
    elif target_lower == "smallint":
        # Convert to signed 16-bit int
        val = value & 0xFFFF
        return val if val < 0x8000 else val - 0x10000
    elif target_lower == "tinyint":
        # Convert to signed 8-bit int
        val = value & 0xFF
        return val if val < 0x80 else val - 0x100
    return value


def _coerce_collection_value(value: object, target_type: str | None) -> object:
    """Recursively coerce a collection value based on target type."""
    if value is None or not target_type:
        return value

    # _unwrap_frozen already returns normalized (lowercased) type
    target_type = _unwrap_frozen(target_type)

    # Handle list coercion
    if target_type.startswith("list<") and isinstance(value, list):
        elem_type, _ = _parse_collection_element_types(target_type)
        if elem_type:
            return [_coerce_element(e, elem_type) for e in value]

    # Handle set coercion
    elif target_type.startswith("set<") and isinstance(value, (list, set)):
        elem_type, _ = _parse_collection_element_types(target_type)
        if elem_type:
            return [_coerce_element(e, elem_type) for e in value]

    # Handle map coercion
    elif target_type.startswith("map<") and isinstance(value, dict):
        key_type, val_type = _parse_collection_element_types(target_type)
        if key_type and val_type:
            return {_coerce_element(k, key_type): _coerce_element(v, val_type) for k, v in value.items()}

    return value


def _coerce_element(value: object, target_type: str) -> object:
    """Coerce a single element to the target type."""
    if value is None:
        return None

    target_lower = _normalize_type(target_type)

    # Integer coercion
    if isinstance(value, int) and target_lower in ("int", "smallint", "tinyint"):
        return _coerce_int_value(value, target_lower)

    # Float coercion
    if isinstance(value, float) and target_lower == "float":
        return float(value)

    # Recursive collection coercion
    if isinstance(value, (list, set, dict)):
        return _coerce_collection_value(value, target_type)

    return value

# =============================================================================
# Performance Optimization: Pre-compiled struct objects
# =============================================================================
# Value encoding/decoding is a hot path in benchmarking. Pre-compiled Struct
# objects avoid format string parsing overhead on each pack/unpack call.

_STRUCT_BYTE_SIGNED = struct.Struct(">b")
_STRUCT_SHORT = struct.Struct(">H")        # unsigned short for type codes
_STRUCT_SHORT_SIGNED = struct.Struct(">h")
_STRUCT_INT = struct.Struct(">i")
_STRUCT_UINT = struct.Struct(">I")
_STRUCT_LONG_SIGNED = struct.Struct(">q")
_STRUCT_LONG = struct.Struct(">Q")
_STRUCT_FLOAT = struct.Struct(">f")
_STRUCT_DOUBLE = struct.Struct(">d")


# =============================================================================
# Memoryview-based reading helpers (avoids BytesIO allocation)
# =============================================================================


def _read_short_at(data: bytes, offset: int) -> tuple[int, int]:
    """Read unsigned short at offset, return (value, new_offset)."""
    return _STRUCT_SHORT.unpack_from(data, offset)[0], offset + 2


def _read_int_at(data: bytes, offset: int) -> tuple[int, int]:
    """Read signed int at offset, return (value, new_offset)."""
    return _STRUCT_INT.unpack_from(data, offset)[0], offset + 4


# =============================================================================
# Decode helper functions for dispatch dictionary
# =============================================================================


def _decode_text(data: bytes, target_type: str | None) -> object:
    """Decode ASCII/TEXT with optional type coercion."""
    text = data.decode("utf-8")
    if target_type:
        target_lower = _normalize_type(target_type)
        if target_lower == "date":
            return _parse_date_string(text)
        elif target_lower == "time":
            return _parse_time_string(text)
        elif target_lower == "duration":
            return _parse_duration_string(text)
        elif target_lower == "inet":
            return ip_address(text)
        elif target_lower in ("uuid", "timeuuid"):
            return uuid.UUID(text)
        elif target_lower == "decimal":
            return Decimal(text)
        elif target_lower == "varint":
            return int(text)
    return text


def _decode_bigint(data: bytes, target_type: str | None) -> object:
    """Decode BIGINT/COUNTER/TIMESTAMP/TIME with optional int coercion."""
    val = _read_int_flexible(data)
    if target_type:
        target_lower = _normalize_type(target_type)
        if target_lower == "int":
            return val & 0xFFFFFFFF if val >= 0 else val | ~0xFFFFFFFF
        elif target_lower == "smallint":
            return val & 0xFFFF if val >= 0 else val | ~0xFFFF
        elif target_lower == "tinyint":
            return val & 0xFF if val >= 0 else val | ~0xFF
    return val


def _decode_blob(data: bytes, target_type: str | None) -> bytes:
    """Decode BLOB."""
    return bytes(data)


def _decode_boolean(data: bytes, target_type: str | None) -> bool:
    """Decode BOOLEAN."""
    return data[0] != 0 if data else False


def _decode_double(data: bytes, target_type: str | None) -> float:
    """Decode DOUBLE with optional float coercion."""
    if len(data) == 8:
        val = _STRUCT_DOUBLE.unpack(data)[0]
        if target_type and _normalize_type(target_type) == "float":
            return float(val)
        return val
    elif len(data) == 4:
        return _STRUCT_FLOAT.unpack(data)[0]
    return 0.0


def _decode_float(data: bytes, target_type: str | None) -> float:
    """Decode FLOAT."""
    if len(data) == 4:
        return _STRUCT_FLOAT.unpack(data)[0]
    return 0.0


def _decode_int(data: bytes, target_type: str | None) -> int:
    """Decode INT."""
    if len(data) == 4:
        return _STRUCT_INT.unpack(data)[0]
    return int(_read_int_flexible(data))


def _decode_smallint(data: bytes, target_type: str | None) -> int:
    """Decode SMALLINT."""
    if len(data) == 2:
        return _STRUCT_SHORT_SIGNED.unpack(data)[0]
    return int(_read_int_flexible(data)) & 0xFFFF


def _decode_tinyint(data: bytes, target_type: str | None) -> int:
    """Decode TINYINT."""
    if len(data) == 1:
        return _STRUCT_BYTE_SIGNED.unpack(data)[0]
    return int(_read_int_flexible(data)) & 0xFF


def _decode_uuid_value(data: bytes, target_type: str | None) -> uuid.UUID | None:
    """Decode UUID/TIMEUUID."""
    if len(data) == 16:
        return uuid.UUID(bytes=data)
    return None


def _decode_inet(data: bytes, target_type: str | None) -> IPv4Address | IPv6Address | None:
    """Decode INET."""
    if len(data) == 4:
        return IPv4Address(data)
    elif len(data) == 16:
        return IPv6Address(data)
    return None


def _decode_date(data: bytes, target_type: str | None) -> int:
    """Decode DATE."""
    if len(data) == 4:
        return _STRUCT_UINT.unpack(data)[0]
    return 0


def _decode_varint_value(data: bytes, target_type: str | None) -> int:
    """Decode VARINT."""
    return _decode_varint(data)


def _decode_decimal_value(data: bytes, target_type: str | None) -> Decimal:
    """Decode DECIMAL."""
    return _decode_decimal(data)


def _decode_list_value(data: bytes, target_type: str | None) -> list:
    """Decode LIST with optional collection coercion."""
    decoded = _decode_list(data)
    return _coerce_collection_value(decoded, target_type) if target_type else decoded


def _decode_set_value(data: bytes, target_type: str | None) -> set:
    """Decode SET with optional collection coercion."""
    decoded = _decode_set(data)
    return _coerce_collection_value(decoded, target_type) if target_type else decoded


def _decode_map_value(data: bytes, target_type: str | None) -> dict:
    """Decode MAP with optional collection coercion."""
    decoded = _decode_map(data)
    return _coerce_collection_value(decoded, target_type) if target_type else decoded


def _decode_vector_value(data: bytes, target_type: str | None) -> list[float]:
    """Decode VECTOR."""
    return _decode_vector(data)


def _decode_tuple_value(data: bytes, target_type: str | None) -> tuple:
    """Decode TUPLE."""
    return _decode_tuple(data)


def _decode_udt_value(data: bytes, target_type: str | None) -> dict:
    """Decode UDT."""
    return _decode_udt(data)


def _decode_duration_value(data: bytes, target_type: str | None) -> Duration:
    """Decode DURATION."""
    return _decode_duration(data)


def _decode_packed_float_vector_list_value(data: bytes, target_type: str | None) -> list[list[float]]:
    """Decode packed list<vector<float, N>>.

    Format: [n_elements: i32] [dimension: u16] [packed_floats: n*dim*4 bytes]
    Returns a list of float lists (vectors).
    """
    return _decode_packed_float_vector_list(data)


def _decode_default(data: bytes, target_type: str | None) -> bytes:
    """Default decoder - return as bytes."""
    return bytes(data)


# Dispatch dictionary for O(1) type code lookup
_DECODE_DISPATCH = {
    TypeCode.ASCII: _decode_text,
    TypeCode.TEXT: _decode_text,
    TypeCode.BIGINT: _decode_bigint,
    TypeCode.COUNTER: _decode_bigint,
    TypeCode.TIMESTAMP: _decode_bigint,
    TypeCode.TIME: _decode_bigint,
    TypeCode.BLOB: _decode_blob,
    TypeCode.BOOLEAN: _decode_boolean,
    TypeCode.DOUBLE: _decode_double,
    TypeCode.FLOAT: _decode_float,
    TypeCode.INT: _decode_int,
    TypeCode.SMALLINT: _decode_smallint,
    TypeCode.TINYINT: _decode_tinyint,
    TypeCode.UUID: _decode_uuid_value,
    TypeCode.TIMEUUID: _decode_uuid_value,
    TypeCode.INET: _decode_inet,
    TypeCode.DATE: _decode_date,
    TypeCode.VARINT: _decode_varint_value,
    TypeCode.DECIMAL: _decode_decimal_value,
    TypeCode.LIST: _decode_list_value,
    TypeCode.SET: _decode_set_value,
    TypeCode.MAP: _decode_map_value,
    TypeCode.VECTOR: _decode_vector_value,
    TypeCode.TUPLE: _decode_tuple_value,
    TypeCode.PACKED_FLOAT_VECTOR_LIST: _decode_packed_float_vector_list_value,
    TypeCode.UDT: _decode_udt_value,
    TypeCode.DURATION: _decode_duration_value,
}


def decode_typed_value(type_code: int, data: bytes | None, target_type: str | None = None) -> object:
    """Decode a typed value from wire format with optional type coercion.

    Latte sends values in a canonical format (e.g., all integers as BIGINT,
    all floats as DOUBLE). The adapter decodes these values and coerces them
    to the actual column type based on prepared statement metadata.

    Uses dispatch dictionary for O(1) type code lookup instead of match statement.

    Args:
        type_code: The CQL type code from the wire (how Latte sent the value)
        data: The raw bytes (None for NULL values)
        target_type: The actual column type from schema (e.g., "int", "date")

    Type Coercion Rules:
        BIGINT -> int/smallint/tinyint: Truncate with appropriate bit mask
        DOUBLE -> float: Direct cast (precision loss acceptable)
        TEXT -> date: Parse "YYYY-MM-DD" format
        TEXT -> time: Parse "HH:MM:SS" format
        TEXT -> duration: Parse "1mo2d3h4m5s" format
        TEXT -> inet: Parse IP address string
        TEXT -> uuid/timeuuid: Parse UUID string
        TEXT -> decimal: Parse decimal string
        TEXT -> varint: Parse integer string

    Returns:
        The decoded Python value, coerced to target type if specified.
    """
    if data is None:
        return None

    decoder = _DECODE_DISPATCH.get(type_code, _decode_default)
    return decoder(data, target_type)


def encode_value(value: object, type_code: int) -> bytes:
    """Encode a value to CQL wire format."""
    if value is None:
        return b""

    match type_code:
        case TypeCode.ASCII | TypeCode.TEXT:
            if isinstance(value, str):
                return value.encode("utf-8")
            return str(value).encode("utf-8")

        case TypeCode.BIGINT | TypeCode.COUNTER | TypeCode.TIMESTAMP | TypeCode.TIME:
            if isinstance(value, int):
                return _STRUCT_LONG_SIGNED.pack(value)
            elif isinstance(value, datetime):
                return _STRUCT_LONG_SIGNED.pack(int(value.timestamp() * 1000))
            return _STRUCT_LONG_SIGNED.pack(0)

        case TypeCode.INT:
            return _STRUCT_INT.pack(int(value))

        case TypeCode.SMALLINT:
            return _STRUCT_SHORT_SIGNED.pack(int(value))

        case TypeCode.TINYINT:
            return _STRUCT_BYTE_SIGNED.pack(int(value))

        case TypeCode.FLOAT:
            return _STRUCT_FLOAT.pack(float(value))

        case TypeCode.DOUBLE:
            return _STRUCT_DOUBLE.pack(float(value))

        case TypeCode.BOOLEAN:
            return b"\x01" if value else b"\x00"

        case TypeCode.UUID | TypeCode.TIMEUUID:
            if isinstance(value, uuid.UUID):
                return value.bytes
            elif isinstance(value, str):
                return uuid.UUID(value).bytes
            return b"\x00" * 16

        case TypeCode.INET:
            if isinstance(value, (IPv4Address, IPv6Address)):
                return value.packed
            elif isinstance(value, str):
                return ip_address(value).packed
            return b"\x00" * 4

        case TypeCode.DATE:
            if isinstance(value, int):
                return _STRUCT_UINT.pack(value)
            elif isinstance(value, date):
                epoch = date(1970, 1, 1)
                days = (value - epoch).days + (1 << 31)
                return _STRUCT_UINT.pack(days)
            return _STRUCT_UINT.pack(1 << 31)

        case TypeCode.BLOB:
            if isinstance(value, bytes):
                return value
            elif isinstance(value, bytearray):
                return bytes(value)
            elif isinstance(value, int):
                # Handle integers - encode as bigint
                return _STRUCT_LONG_SIGNED.pack(value)
            elif isinstance(value, str):
                return value.encode("utf-8")
            # For other types, convert to string representation
            return str(value).encode("utf-8")

        case TypeCode.VARINT:
            return _encode_varint(int(value))

        case TypeCode.DECIMAL:
            return _encode_decimal(Decimal(value) if not isinstance(value, Decimal) else value)

        case _:
            if isinstance(value, bytes):
                return value
            elif isinstance(value, str):
                return value.encode("utf-8")
            elif isinstance(value, int):
                return _STRUCT_LONG_SIGNED.pack(value)
            elif isinstance(value, float):
                return _STRUCT_DOUBLE.pack(value)
            elif isinstance(value, bool):
                return b"\x01" if value else b"\x00"
            elif isinstance(value, uuid.UUID):
                return value.bytes
            elif isinstance(value, Mapping):
                # For maps (dict, OrderedMapSerializedKey, etc.), encode with entry count and length-prefixed key-value pairs
                buf = _get_encode_buffer()
                try:
                    buf.write(_STRUCT_INT.pack(len(value)))  # entry count
                    for k, v in value.items():
                        encoded_key = encode_value(k, TypeCode.BLOB)
                        encoded_val = encode_value(v, TypeCode.BLOB)
                        buf.write(_STRUCT_INT.pack(len(encoded_key)))  # key length
                        buf.write(encoded_key)
                        buf.write(_STRUCT_INT.pack(len(encoded_val)))  # value length
                        buf.write(encoded_val)
                    return buf.getvalue()
                finally:
                    _return_encode_buffer(buf)
            elif isinstance(value, (list, tuple, set, frozenset, AbstractSet, SortedSet)):
                # For collections (list, set, SortedSet, etc.), encode with element count and length-prefixed elements
                buf = _get_encode_buffer()
                try:
                    items = list(value)
                    buf.write(_STRUCT_INT.pack(len(items)))  # element count
                    for item in items:
                        encoded_item = encode_value(item, TypeCode.BLOB)
                        buf.write(_STRUCT_INT.pack(len(encoded_item)))  # element length
                        buf.write(encoded_item)
                    return buf.getvalue()
                finally:
                    _return_encode_buffer(buf)
            # For any other type, convert to string
            return str(value).encode("utf-8")


def _read_int_flexible(data: bytes) -> int:
    """Read an integer from bytes of any standard size using pre-compiled structs."""
    match len(data):
        case 1:
            return _STRUCT_BYTE_SIGNED.unpack(data)[0]
        case 2:
            return _STRUCT_SHORT_SIGNED.unpack(data)[0]
        case 4:
            return _STRUCT_INT.unpack(data)[0]
        case 8:
            return _STRUCT_LONG_SIGNED.unpack(data)[0]
        case _:
            return 0


def _decode_varint(data: bytes) -> int:
    """Decode CQL varint (two's complement big-endian).

    CQL varints are arbitrary-precision integers encoded as:
    - Big-endian byte order (most significant byte first)
    - Two's complement representation for negative numbers
    - The high bit of the first byte indicates sign (1 = negative)

    Examples:
        0x00       ->  0
        0x01       ->  1
        0x7F       ->  127
        0x0080     ->  128 (needs leading 0x00 to avoid negative interpretation)
        0xFF       -> -1
        0xFE       -> -2
        0x80       -> -128

    Algorithm for negative numbers:
    1. Invert all bits (~data)
    2. Convert to unsigned int
    3. Add 1
    4. Negate the result
    """
    if not data:
        return 0

    # Check sign bit (high bit of first byte)
    is_negative = data[0] & 0x80 != 0

    if not is_negative:
        # Positive: direct conversion from big-endian bytes
        return int.from_bytes(data, "big", signed=False)

    # Negative: convert from two's complement
    # Two's complement to value: -(~x + 1) = -((inverted) + 1)
    inverted = bytes(~b & 0xFF for b in data)
    abs_val = int.from_bytes(inverted, "big", signed=False) + 1
    return -abs_val


def _encode_varint(value: int) -> bytes:
    """Encode an integer to CQL varint format (two's complement big-endian).

    The encoding must satisfy:
    1. Positive numbers have high bit = 0 (may need leading 0x00 byte)
    2. Negative numbers have high bit = 1 (may need leading 0xFF byte)
    3. Use minimum bytes necessary while satisfying above constraints

    Examples:
         0 -> 0x00
         1 -> 0x01
       127 -> 0x7F
       128 -> 0x0080 (not 0x80, which would be -128)
        -1 -> 0xFF
        -2 -> 0xFE
      -128 -> 0x80
      -129 -> 0xFF7F

    Algorithm for negative numbers uses two's complement:
    - value = -(2^n - twos_comp) where n = 8 * byte_length
    - So twos_comp = 2^n + value (since value is negative)
    """
    if value == 0:
        return b"\x00"

    if value > 0:
        # Calculate minimum bytes needed, plus space for sign bit
        bit_length = value.bit_length()
        byte_length = (bit_length + 8) // 8  # +1 for sign bit, rounded up
        result = value.to_bytes(byte_length, "big", signed=False)
        # If high bit is set, prepend 0x00 to indicate positive
        if result[0] & 0x80:
            result = b"\x00" + result
        return result
    else:
        # Negative: compute two's complement representation
        abs_val = abs(value)
        bit_length = abs_val.bit_length()
        byte_length = (bit_length + 8) // 8

        # Two's complement formula: 2^(8*n) - |value| = 2^(8*n) + value
        modulus = 1 << (8 * byte_length)
        twos_comp = modulus - abs_val

        result = twos_comp.to_bytes(byte_length, "big", signed=False)
        # If high bit is NOT set, prepend 0xFF to indicate negative
        if not (result[0] & 0x80):
            result = b"\xff" + result
        return result


def _decode_decimal(data: bytes) -> Decimal:
    """Decode CQL decimal: [scale: i32][unscaled: varint]."""
    if len(data) < 4:
        return Decimal(0)

    scale = _STRUCT_INT.unpack(data[0:4])[0]
    unscaled = _decode_varint(data[4:])

    return Decimal(unscaled) * (Decimal(10) ** (-scale))


def _encode_decimal(value: Decimal) -> bytes:
    """Encode a Decimal to CQL format."""
    sign, digits, exponent = value.as_tuple()

    # Build unscaled value
    unscaled = 0
    for digit in digits:
        unscaled = unscaled * 10 + digit
    if sign:
        unscaled = -unscaled

    # Scale is negative of exponent
    scale = -exponent if exponent else 0

    scale_bytes = _STRUCT_INT.pack(scale)
    varint_bytes = _encode_varint(unscaled)

    return scale_bytes + varint_bytes


def _decode_list(data: bytes) -> list:
    """Decode a CQL list using index-based reading (no BytesIO allocation)."""
    if len(data) < 6:
        return []

    offset = 0
    subtype, offset = _read_short_at(data, offset)

    # For list<vector<...>>, read vector metadata (subtype and dimension)
    vector_dim = None
    if subtype == TypeCode.VECTOR and len(data) >= 6:
        _vector_subtype, offset = _read_short_at(data, offset)
        vector_dim, offset = _read_short_at(data, offset)

    n_elements, offset = _read_int_at(data, offset)

    if n_elements <= 0:
        return []

    elements = []
    for _ in range(n_elements):
        elem_len, offset = _read_int_at(data, offset)
        if elem_len < 0:
            elements.append(None)
        else:
            elem_data = data[offset:offset + elem_len]
            offset += elem_len
            # For vector elements, data is raw floats (no header)
            if subtype == TypeCode.VECTOR and vector_dim is not None:
                elements.append(_decode_vector_raw(elem_data, vector_dim))
            else:
                elements.append(_decode_element_by_type(elem_data, subtype))

    return elements


def _decode_set(data: bytes) -> set:
    """Decode a CQL set."""
    return set(_decode_list(data))


def _decode_map(data: bytes) -> dict:
    """Decode a CQL map using index-based reading (no BytesIO allocation)."""
    if len(data) < 8:
        return {}

    offset = 0
    key_type, offset = _read_short_at(data, offset)
    value_type, offset = _read_short_at(data, offset)
    n_entries, offset = _read_int_at(data, offset)

    if n_entries <= 0:
        return {}

    result = {}
    for _ in range(n_entries):
        # Read key
        key_len, offset = _read_int_at(data, offset)
        if key_len >= 0:
            key_data = data[offset:offset + key_len]
            offset += key_len
            key = _decode_element_by_type(key_data, key_type)
        else:
            key = None

        # Read value
        val_len, offset = _read_int_at(data, offset)
        if val_len >= 0:
            val_data = data[offset:offset + val_len]
            offset += val_len
            value = _decode_element_by_type(val_data, value_type)
        else:
            value = None

        result[key] = value

    return result


def _decode_vector(data: bytes) -> list[float]:
    """Decode a CQL vector using index-based reading (no BytesIO allocation).

    Wire format: [subtype: u16][dimension: u16][data: contiguous floats]
    For vector<float, 3>, subtype=0x0008 (FLOAT), dimension=3, data=12 bytes
    """
    if len(data) < 4:
        return []

    # Read header using direct unpacking
    _subtype, _ = _read_short_at(data, 0)  # e.g., 0x0008 for float
    dimension, _ = _read_short_at(data, 2)

    # Decode raw float data
    return _decode_vector_raw(data[4:], dimension)


def _decode_vector_raw(data: bytes, dimension: int) -> list[float]:
    """Decode raw vector float data (no header).

    Used for both standalone vectors (after header is stripped) and
    vector elements inside lists (where header is in list metadata).
    """
    expected_len = dimension * 4
    if len(data) < expected_len:
        # If data is shorter than expected, decode what we have
        dimension = len(data) // 4

    floats = []
    for i in range(dimension):
        f = _STRUCT_FLOAT.unpack_from(data, i * 4)[0]
        floats.append(f)

    return floats


def _decode_packed_float_vector_list(data: bytes) -> list[list[float]]:
    """Decode packed list<vector<float, N>>.

    Format: [n_elements: i32] [dimension: u16] [packed_floats: n*dim*4 bytes]
    Returns a list of float lists (vectors).
    """
    if len(data) < 6:
        return []

    offset = 0
    n_elements, offset = _read_int_at(data, offset)
    dimension, offset = _read_short_at(data, offset)

    if n_elements <= 0:
        return []

    # Calculate expected size
    float_bytes_per_vector = dimension * 4
    expected_size = offset + n_elements * float_bytes_per_vector
    if len(data) < expected_size:
        return []

    result = []
    for _ in range(n_elements):
        vector_data = data[offset:offset + float_bytes_per_vector]
        offset += float_bytes_per_vector
        result.append(_decode_vector_raw(vector_data, dimension))

    return result


def _decode_tuple(data: bytes) -> tuple:
    """Decode a CQL tuple using index-based reading (no BytesIO allocation)."""
    if len(data) < 2:
        return ()

    offset = 0
    n_elements, offset = _read_short_at(data, offset)

    if n_elements == 0:
        return ()

    # Read element types
    elem_types = []
    for _ in range(n_elements):
        elem_type, offset = _read_short_at(data, offset)
        elem_types.append(elem_type)

    # Read element values
    elements = []
    for i in range(n_elements):
        elem_len, offset = _read_int_at(data, offset)
        if elem_len < 0:
            elements.append(None)
        else:
            elem_data = data[offset:offset + elem_len]
            offset += elem_len
            elements.append(_decode_element_by_type(elem_data, elem_types[i]))

    return tuple(elements)


def _decode_udt(data: bytes) -> dict:
    """Decode a CQL UDT using index-based reading (no BytesIO allocation).

    Returns a dict mapping field names to values. The cassandra-driver can serialize
    dicts for UDT columns by matching field names, which avoids field order issues
    between the wire format (which may be alphabetical) and the schema order.
    """
    if len(data) < 2:
        return {}

    offset = 0
    n_fields, offset = _read_short_at(data, offset)

    if n_fields == 0:
        return {}

    # Read field names and types
    fields = []
    for _ in range(n_fields):
        name_len, offset = _read_short_at(data, offset)
        name = data[offset:offset + name_len].decode("utf-8")
        offset += name_len
        field_type, offset = _read_short_at(data, offset)
        fields.append((name, field_type))

    # Read field values
    result = {}
    for name, field_type in fields:
        field_len, offset = _read_int_at(data, offset)
        if field_len < 0:
            result[name] = None
        else:
            field_data = data[offset:offset + field_len]
            offset += field_len
            result[name] = _decode_element_by_type(field_data, field_type)

    return result


def _decode_duration(data: bytes) -> Duration:
    """Decode a CQL duration using index-based reading (no BytesIO allocation)."""
    if not data:
        return Duration(0, 0, 0)

    offset = 0
    months, offset = _read_vint_at(data, offset)
    days, offset = _read_vint_at(data, offset)
    nanos, offset = _read_vint_at(data, offset)

    return Duration(months, days, nanos)


def _read_vint_at(data: bytes, offset: int) -> tuple[int, int]:
    """Read a CQL vint at offset, return (value, new_offset).

    CQL vints use a space-efficient format with zigzag encoding:
    - 0xxxxxxx: 1 byte (7 data bits)
    - 10xxxxxx: 2 bytes (6 + 8 = 14 data bits)
    - 110xxxxx: 3 bytes (5 + 16 = 21 data bits)
    - etc.

    Zigzag decoding: (raw >> 1) ^ -(raw & 1)
    """
    if offset >= len(data):
        return 0, offset

    first = data[offset]
    offset += 1

    # Count leading 1 bits to determine number of extra bytes
    if first & 0x80 == 0:
        # Single byte: 0xxxxxxx - all 7 bits are data
        raw = first
    else:
        # Multi-byte: count leading 1s to find extra byte count
        n_extra = 0
        mask = 0x80
        while first & mask:
            n_extra += 1
            mask >>= 1
            if n_extra >= 8:
                break

        # Read the extra bytes
        if offset + n_extra > len(data):
            return 0, offset

        # Extract data bits from first byte (after the leading 1s and separator 0)
        raw = first & ((1 << (8 - n_extra - 1)) - 1)

        # Append all bits from extra bytes
        for i in range(n_extra):
            raw = (raw << 8) | data[offset + i]
        offset += n_extra

    # Zigzag decode: maps 0,1,2,3,4,5... to 0,-1,1,-2,2,-3...
    return (raw >> 1) ^ -(raw & 1), offset


def _decode_element_by_type(data: bytes, type_code: int) -> object:
    """Decode an element by its type code."""
    return decode_typed_value(type_code, data)


def _parse_date_string(s: str) -> date:
    """Parse a date string like '2024-01-15'."""
    try:
        return datetime.strptime(s, "%Y-%m-%d").date()
    except ValueError:
        return date(1970, 1, 1)


def _parse_time_string(s: str) -> int:
    """Parse a time string like '14:30:45' to nanoseconds since midnight."""
    try:
        t = datetime.strptime(s, "%H:%M:%S").time()
        return (t.hour * 3600 + t.minute * 60 + t.second) * 1_000_000_000
    except ValueError:
        return 0


def _parse_duration_string(s: str) -> Duration:
    """Parse a duration string like '1mo2d3h4m5s'."""
    months = 0
    days = 0
    nanos = 0

    i = 0
    while i < len(s):
        # Find the number
        j = i
        while j < len(s) and (s[j].isdigit() or s[j] == "-"):
            j += 1

        if j == i:
            i += 1
            continue

        num = int(s[i:j])

        # Find the unit
        k = j
        while k < len(s) and s[k].isalpha():
            k += 1

        unit = s[j:k].lower()

        if unit == "mo":
            months = num
        elif unit == "d":
            days = num
        elif unit == "h":
            nanos += num * 3600 * 1_000_000_000
        elif unit == "m":
            nanos += num * 60 * 1_000_000_000
        elif unit == "s":
            nanos += num * 1_000_000_000

        i = k

    return Duration(months, days, nanos)

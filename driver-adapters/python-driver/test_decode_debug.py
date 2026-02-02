#!/usr/bin/env python3
"""Test value decoding."""

import struct
from io import BytesIO

# Add src to path
import sys
sys.path.insert(0, '.')

from src.values import decode_typed_value, _decode_map, _decode_udt, _decode_list
from src.protocol import TypeCode

# Test 1: Decode a simple map<text, int>
print("=== Test 1: map<text, int> ===")
def encode_map_text_int():
    buf = BytesIO()
    # Key type: TEXT (0x000D)
    buf.write(struct.pack('>H', 0x000D))
    # Value type: BIGINT (0x0002)
    buf.write(struct.pack('>H', 0x0002))
    # Number of entries: 2
    buf.write(struct.pack('>i', 2))

    # Entry 1: key="hello", value=123
    key1 = b'hello'
    buf.write(struct.pack('>i', len(key1)))
    buf.write(key1)
    val1 = struct.pack('>q', 123)
    buf.write(struct.pack('>i', len(val1)))
    buf.write(val1)

    # Entry 2: key="world", value=456
    key2 = b'world'
    buf.write(struct.pack('>i', len(key2)))
    buf.write(key2)
    val2 = struct.pack('>q', 456)
    buf.write(struct.pack('>i', len(val2)))
    buf.write(val2)

    return buf.getvalue()

map_data = encode_map_text_int()
decoded_map = _decode_map(map_data)
print(f"Decoded map: {decoded_map}")
print(f"Key types: {[type(k).__name__ for k in decoded_map.keys()]}")
print(f"Value types: {[type(v).__name__ for v in decoded_map.values()]}")

# Test 2: Decode a list<text>
print("\n=== Test 2: list<text> ===")
def encode_list_text():
    buf = BytesIO()
    # Subtype: TEXT (0x000D)
    buf.write(struct.pack('>H', 0x000D))
    # Number of elements: 3
    buf.write(struct.pack('>i', 3))

    for text in [b'alpha', b'beta', b'gamma']:
        buf.write(struct.pack('>i', len(text)))
        buf.write(text)

    return buf.getvalue()

list_data = encode_list_text()
decoded_list = _decode_list(list_data)
print(f"Decoded list: {decoded_list}")
print(f"Element types: {[type(e).__name__ for e in decoded_list]}")

# Test 3: Decode a UDT
print("\n=== Test 3: UDT (address) ===")
def encode_udt():
    buf = BytesIO()
    # Number of fields: 3
    buf.write(struct.pack('>H', 3))

    # Field 1: street (TEXT)
    name = b'street'
    buf.write(struct.pack('>H', len(name)))
    buf.write(name)
    buf.write(struct.pack('>H', 0x000D))  # TEXT

    # Field 2: city (TEXT)
    name = b'city'
    buf.write(struct.pack('>H', len(name)))
    buf.write(name)
    buf.write(struct.pack('>H', 0x000D))  # TEXT

    # Field 3: zip (BIGINT, will be coerced to int)
    name = b'zip'
    buf.write(struct.pack('>H', len(name)))
    buf.write(name)
    buf.write(struct.pack('>H', 0x0002))  # BIGINT

    # Field values
    val1 = b'123 Main St'
    buf.write(struct.pack('>i', len(val1)))
    buf.write(val1)

    val2 = b'Boston'
    buf.write(struct.pack('>i', len(val2)))
    buf.write(val2)

    val3 = struct.pack('>q', 12345)
    buf.write(struct.pack('>i', len(val3)))
    buf.write(val3)

    return buf.getvalue()

udt_data = encode_udt()
decoded_udt = _decode_udt(udt_data)
print(f"Decoded UDT: {decoded_udt}")
print(f"UDT type: {type(decoded_udt).__name__}")
print(f"Element types: {[type(e).__name__ for e in decoded_udt]}")

# Test 4: Decode a list<frozen<address>>
print("\n=== Test 4: list<frozen<address>> ===")
def encode_list_udt():
    buf = BytesIO()
    # Subtype: UDT (0x0040)
    buf.write(struct.pack('>H', 0x0040))
    # Number of elements: 2
    buf.write(struct.pack('>i', 2))

    # Two UDT values
    for _ in range(2):
        udt_bytes = encode_udt()
        buf.write(struct.pack('>i', len(udt_bytes)))
        buf.write(udt_bytes)

    return buf.getvalue()

list_udt_data = encode_list_udt()
decoded_list_udt = _decode_list(list_udt_data)
print(f"Decoded list of UDTs: {decoded_list_udt}")
print(f"Element types: {[type(e).__name__ for e in decoded_list_udt]}")

print("\n=== All tests completed ===")

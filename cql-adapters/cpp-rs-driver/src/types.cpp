#include "types.h"
#include "logging.h"

#include <arpa/inet.h>
#include <cmath>
#include <cstring>
#include <limits>
#include <sstream>
#include <stdexcept>

namespace latte {

// =============================================================================
// Unified Value Re-serialization
// =============================================================================
// Converts IPC wire format to Cassandra's expected format based on schema type.
// Handles all nested types (collections, UDTs, tuples) recursively.

// Forward declaration
static void reserialize_value(Buffer &out,
                              const uint8_t *data, size_t len,
                              const CassDataType *expected_type);

// Re-serialize a primitive value with type coercion
static void reserialize_primitive(Buffer &out,
                                  const uint8_t *data, size_t len,
                                  CassValueType expected_type) {
  switch (expected_type) {
  case CASS_VALUE_TYPE_TINY_INT:
    if (len == 8) {
      // BigInt -> TinyInt: take lowest byte
      out.write_int(1);
      out.write_byte(static_cast<uint8_t>(data[7]));
    } else if (len == 1) {
      out.write_int(1);
      out.write_byte(data[0]);
    } else {
      out.write_int(static_cast<int32_t>(len));
      out.append(data, len);
    }
    break;

  case CASS_VALUE_TYPE_SMALL_INT:
    if (len == 8) {
      // BigInt -> SmallInt: take lowest 2 bytes
      out.write_int(2);
      out.write_byte(data[6]);
      out.write_byte(data[7]);
    } else if (len == 2) {
      out.write_int(2);
      out.append(data, 2);
    } else {
      out.write_int(static_cast<int32_t>(len));
      out.append(data, len);
    }
    break;

  case CASS_VALUE_TYPE_INT:
    if (len == 8) {
      // BigInt -> Int: take lowest 4 bytes
      int32_t v = (static_cast<int32_t>(data[4]) << 24) |
                  (static_cast<int32_t>(data[5]) << 16) |
                  (static_cast<int32_t>(data[6]) << 8) |
                  static_cast<int32_t>(data[7]);
      out.write_int(4);
      out.write_int(v);
    } else if (len == 4) {
      out.write_int(4);
      out.append(data, 4);
    } else {
      out.write_int(static_cast<int32_t>(len));
      out.append(data, len);
    }
    break;

  case CASS_VALUE_TYPE_FLOAT:
    if (len == 8) {
      // Double -> Float
      double d;
      uint64_t bits = (static_cast<uint64_t>(data[0]) << 56) |
                      (static_cast<uint64_t>(data[1]) << 48) |
                      (static_cast<uint64_t>(data[2]) << 40) |
                      (static_cast<uint64_t>(data[3]) << 32) |
                      (static_cast<uint64_t>(data[4]) << 24) |
                      (static_cast<uint64_t>(data[5]) << 16) |
                      (static_cast<uint64_t>(data[6]) << 8) |
                      static_cast<uint64_t>(data[7]);
      std::memcpy(&d, &bits, sizeof(d));
      float f = static_cast<float>(d);
      uint32_t fbits;
      std::memcpy(&fbits, &f, sizeof(fbits));
      fbits = __builtin_bswap32(fbits);
      out.write_int(4);
      out.append(reinterpret_cast<const uint8_t*>(&fbits), 4);
    } else {
      out.write_int(static_cast<int32_t>(len));
      out.append(data, len);
    }
    break;

  default:
    // Pass through as-is
    out.write_int(static_cast<int32_t>(len));
    out.append(data, len);
    break;
  }
}

// Re-serialize a list or set
static void reserialize_list_or_set(Buffer &out,
                                    const uint8_t *data, size_t len,
                                    const CassDataType *expected_type) {
  // Minimum: 2 (elem_type) + 4 (count) = 6 bytes
  if (len < 6) {
    LOG_ERROR("reserialize_list_or_set: insufficient data len=" << len);
    out.write_int(0); // empty list
    return;
  }

  Buffer buf(std::vector<uint8_t>(data, data + len));
  (void)buf.read_short(); // skip wire elem_type
  int32_t count = buf.read_int();

  const CassDataType *elem_type = cass_data_type_sub_data_type(expected_type, 0);

  // Write count
  out.write_int(count);

  // Re-serialize each element
  for (int32_t i = 0; i < count; ++i) {
    auto elem = buf.read_bytes();
    if (!elem) {
      out.write_int(-1); // null
      continue;
    }
    reserialize_value(out, elem->data(), elem->size(), elem_type);
  }
}

// Re-serialize a map
static void reserialize_map(Buffer &out,
                            const uint8_t *data, size_t len,
                            const CassDataType *expected_type) {
  // Minimum: 2 (key_type) + 2 (val_type) + 4 (count) = 8 bytes
  if (len < 8) {
    LOG_ERROR("reserialize_map: insufficient data len=" << len);
    out.write_int(0); // empty map
    return;
  }

  Buffer buf(std::vector<uint8_t>(data, data + len));
  (void)buf.read_short(); // skip wire key_type
  (void)buf.read_short(); // skip wire val_type
  int32_t count = buf.read_int();

  const CassDataType *key_type = cass_data_type_sub_data_type(expected_type, 0);
  const CassDataType *val_type = cass_data_type_sub_data_type(expected_type, 1);

  // Write count
  out.write_int(count);

  // Re-serialize each entry
  for (int32_t i = 0; i < count; ++i) {
    auto k = buf.read_bytes();
    auto v = buf.read_bytes();

    if (k) {
      reserialize_value(out, k->data(), k->size(), key_type);
    } else {
      out.write_int(-1);
    }

    if (v) {
      reserialize_value(out, v->data(), v->size(), val_type);
    } else {
      out.write_int(-1);
    }
  }
}

// Re-serialize a UDT
// IPC format: [n_fields: u16] [field_name_len: u16][field_name][type_code: u16]... [field_values...]
// Cassandra format: [field1_len: i32][field1_data][field2_len: i32][field2_data]...
static void reserialize_udt(Buffer &out,
                            const uint8_t *data, size_t len,
                            const CassDataType *expected_type) {
  // Minimum: 2 (n_fields) = 2 bytes
  if (len < 2) {
    LOG_ERROR("reserialize_udt: insufficient data len=" << len);
    return;
  }

  Buffer buf(std::vector<uint8_t>(data, data + len));
  uint16_t n_fields = buf.read_short();

  // Sanity check - a reasonable UDT shouldn't have more than ~50 fields
  // If n_fields is huge, this data is not in IPC UDT format
  if (n_fields > 50 || (n_fields > 0 && buf.remaining() < 4)) {
    // This is not a valid UDT in IPC format
    // Just output as raw bytes (shouldn't happen normally)
    out.write_int(static_cast<int32_t>(len));
    out.append(data, len);
    return;
  }

  // Get expected field count from schema
  size_t schema_field_count = expected_type ?
      cass_data_type_sub_type_count(expected_type) : 0;

  // Read field metadata and store wire types and schema types
  std::vector<uint16_t> wire_types;
  std::vector<const CassDataType*> schema_types;
  wire_types.reserve(n_fields);
  schema_types.reserve(n_fields);

  for (uint16_t i = 0; i < n_fields; ++i) {
    if (buf.remaining() < 4) {
      return;
    }
    uint16_t name_len = buf.read_short();
    if (buf.remaining() < static_cast<size_t>(name_len) + 2) {
      return;
    }
    buf.advance(name_len);  // skip field name
    uint16_t wire_type = buf.read_short();
    wire_types.push_back(wire_type);

    // Get schema type for this field
    if (i < schema_field_count) {
      schema_types.push_back(cass_data_type_sub_data_type(expected_type, i));
    } else {
      schema_types.push_back(nullptr);
    }
  }

  // Re-serialize field values using reserialize_value which handles all types
  // correctly, including byte-length wrapping for complex types
  for (uint16_t i = 0; i < n_fields; ++i) {
    auto val = buf.read_bytes();
    if (!val) {
      out.write_int(-1); // null
      continue;
    }

    const CassDataType *schema_type = i < schema_types.size() ? schema_types[i] : nullptr;
    reserialize_value(out, val->data(), val->size(), schema_type);
  }
}

// Re-serialize a tuple
static void reserialize_tuple(Buffer &out,
                              const uint8_t *data, size_t len,
                              const CassDataType *expected_type) {
  // Minimum: 2 (n_elements) = 2 bytes
  if (len < 2) {
    LOG_ERROR("reserialize_tuple: insufficient data len=" << len);
    return;
  }

  Buffer buf(std::vector<uint8_t>(data, data + len));
  uint16_t n_elements = buf.read_short();

  // Check we have enough for type codes
  if (buf.remaining() < static_cast<size_t>(n_elements) * 2) {
    LOG_ERROR("reserialize_tuple: insufficient data for " << n_elements << " type codes");
    return;
  }

  // Skip type codes (2 bytes each)
  for (uint16_t i = 0; i < n_elements; ++i) {
    buf.read_short();
  }

  // Get expected element count from schema
  size_t schema_elem_count = expected_type ?
      cass_data_type_sub_type_count(expected_type) : 0;

  // Re-serialize element values
  for (uint16_t i = 0; i < n_elements; ++i) {
    auto val = buf.read_bytes();
    if (!val) {
      out.write_int(-1); // null
      continue;
    }

    // Get expected element type from schema
    const CassDataType *elem_type = nullptr;
    if (i < schema_elem_count) {
      elem_type = cass_data_type_sub_data_type(expected_type, i);
    }

    reserialize_value(out, val->data(), val->size(), elem_type);
  }
}

// Main unified re-serialization function
// Converts IPC wire data to Cassandra format based on expected schema type.
// Always writes [byte_length: i32][data] to `out` for each value.
static void reserialize_value(Buffer &out,
                              const uint8_t *data, size_t len,
                              const CassDataType *expected_type) {
  if (!expected_type) {
    // No schema info - pass through as-is
    out.write_int(static_cast<int32_t>(len));
    out.append(data, len);
    return;
  }

  CassValueType expected = cass_data_type_type(expected_type);

  switch (expected) {
  case CASS_VALUE_TYPE_LIST:
  case CASS_VALUE_TYPE_SET: {
    // Buffer the output so we can write byte-length prefix
    Buffer nested;
    reserialize_list_or_set(nested, data, len, expected_type);
    out.write_int(static_cast<int32_t>(nested.size()));
    out.append(nested);
    break;
  }

  case CASS_VALUE_TYPE_MAP: {
    Buffer nested;
    reserialize_map(nested, data, len, expected_type);
    out.write_int(static_cast<int32_t>(nested.size()));
    out.append(nested);
    break;
  }

  case CASS_VALUE_TYPE_UDT: {
    Buffer nested;
    reserialize_udt(nested, data, len, expected_type);
    out.write_int(static_cast<int32_t>(nested.size()));
    out.append(nested);
    break;
  }

  case CASS_VALUE_TYPE_TUPLE: {
    Buffer nested;
    reserialize_tuple(nested, data, len, expected_type);
    out.write_int(static_cast<int32_t>(nested.size()));
    out.append(nested);
    break;
  }

  default:
    // Primitive types - handle coercion (already writes [len][data])
    reserialize_primitive(out, data, len, expected);
    break;
  }
}

// =============================================================================
// Original binding functions
// =============================================================================

// Forward declarations for table-driven type binding
using BinderFunc = void (*)(CassStatement *stmt, size_t index,
                            const uint8_t *ptr, size_t len,
                            const std::vector<uint8_t> &data);

// Type binding function implementations
static void bind_tinyint(CassStatement *stmt, size_t index, const uint8_t *ptr,
                         size_t len, const std::vector<uint8_t> &) {
  if (LATTE_LIKELY(len >= 1)) {
    cass_statement_bind_int8(stmt, index, static_cast<int8_t>(ptr[0]));
  }
}

static void bind_smallint(CassStatement *stmt, size_t index, const uint8_t *ptr,
                          size_t len, const std::vector<uint8_t> &) {
  if (LATTE_LIKELY(len >= 2)) {
    int16_t v = static_cast<int16_t>((ptr[0] << 8) | ptr[1]);
    cass_statement_bind_int16(stmt, index, v);
  }
}

static void bind_int(CassStatement *stmt, size_t index, const uint8_t *ptr,
                     size_t len, const std::vector<uint8_t> &) {
  if (LATTE_LIKELY(len >= 4)) {
    int32_t v = (static_cast<int32_t>(ptr[0]) << 24) |
                (static_cast<int32_t>(ptr[1]) << 16) |
                (static_cast<int32_t>(ptr[2]) << 8) |
                static_cast<int32_t>(ptr[3]);
    cass_statement_bind_int32(stmt, index, v);
  }
}

static void bind_bigint(CassStatement *stmt, size_t index, const uint8_t *ptr,
                        size_t len, const std::vector<uint8_t> &) {
  if (LATTE_LIKELY(len >= 8)) {
    int64_t v = (static_cast<int64_t>(ptr[0]) << 56) |
                (static_cast<int64_t>(ptr[1]) << 48) |
                (static_cast<int64_t>(ptr[2]) << 40) |
                (static_cast<int64_t>(ptr[3]) << 32) |
                (static_cast<int64_t>(ptr[4]) << 24) |
                (static_cast<int64_t>(ptr[5]) << 16) |
                (static_cast<int64_t>(ptr[6]) << 8) |
                static_cast<int64_t>(ptr[7]);
    cass_statement_bind_int64(stmt, index, v);
  }
}

static void bind_float(CassStatement *stmt, size_t index, const uint8_t *ptr,
                       size_t len, const std::vector<uint8_t> &) {
  if (LATTE_LIKELY(len >= 4)) {
    uint32_t bits = (static_cast<uint32_t>(ptr[0]) << 24) |
                    (static_cast<uint32_t>(ptr[1]) << 16) |
                    (static_cast<uint32_t>(ptr[2]) << 8) |
                    static_cast<uint32_t>(ptr[3]);
    float v;
    std::memcpy(&v, &bits, sizeof(v));
    cass_statement_bind_float(stmt, index, v);
  }
}

static void bind_double(CassStatement *stmt, size_t index, const uint8_t *ptr,
                        size_t len, const std::vector<uint8_t> &) {
  if (LATTE_LIKELY(len >= 8)) {
    uint64_t bits = (static_cast<uint64_t>(ptr[0]) << 56) |
                    (static_cast<uint64_t>(ptr[1]) << 48) |
                    (static_cast<uint64_t>(ptr[2]) << 40) |
                    (static_cast<uint64_t>(ptr[3]) << 32) |
                    (static_cast<uint64_t>(ptr[4]) << 24) |
                    (static_cast<uint64_t>(ptr[5]) << 16) |
                    (static_cast<uint64_t>(ptr[6]) << 8) |
                    static_cast<uint64_t>(ptr[7]);
    double v;
    std::memcpy(&v, &bits, sizeof(v));
    cass_statement_bind_double(stmt, index, v);
  }
}

static void bind_boolean(CassStatement *stmt, size_t index, const uint8_t *ptr,
                         size_t len, const std::vector<uint8_t> &) {
  if (LATTE_LIKELY(len >= 1)) {
    cass_statement_bind_bool(stmt, index, ptr[0] ? cass_true : cass_false);
  }
}

static void bind_text(CassStatement *stmt, size_t index, const uint8_t *ptr,
                      size_t len, const std::vector<uint8_t> &) {
  cass_statement_bind_string_n(stmt, index, reinterpret_cast<const char *>(ptr),
                               len);
}

static void bind_blob(CassStatement *stmt, size_t index, const uint8_t *ptr,
                      size_t len, const std::vector<uint8_t> &) {
  cass_statement_bind_bytes(stmt, index, ptr, len);
}

static void bind_uuid(CassStatement *stmt, size_t index, const uint8_t *ptr,
                      size_t len, const std::vector<uint8_t> &) {
  if (LATTE_LIKELY(len >= 16)) {
    CassUuid uuid;
    std::memcpy(&uuid, ptr, 16);
    cass_statement_bind_uuid(stmt, index, uuid);
  }
}

static void bind_inet(CassStatement *stmt, size_t index, const uint8_t *ptr,
                      size_t len, const std::vector<uint8_t> &) {
  if (len == 4) {
    CassInet inet;
    inet.address_length = 4;
    std::memcpy(inet.address, ptr, 4);
    cass_statement_bind_inet(stmt, index, inet);
  } else if (len == 16) {
    CassInet inet;
    inet.address_length = 16;
    std::memcpy(inet.address, ptr, 16);
    cass_statement_bind_inet(stmt, index, inet);
  }
}

static void bind_date(CassStatement *stmt, size_t index, const uint8_t *ptr,
                      size_t len, const std::vector<uint8_t> &) {
  if (LATTE_LIKELY(len >= 4)) {
    uint32_t v = (static_cast<uint32_t>(ptr[0]) << 24) |
                 (static_cast<uint32_t>(ptr[1]) << 16) |
                 (static_cast<uint32_t>(ptr[2]) << 8) |
                 static_cast<uint32_t>(ptr[3]);
    cass_statement_bind_uint32(stmt, index, v);
  }
}

static void bind_decimal(CassStatement *stmt, size_t index, const uint8_t *ptr,
                         size_t len, const std::vector<uint8_t> &) {
  if (LATTE_LIKELY(len >= 4)) {
    int32_t scale = (static_cast<int32_t>(ptr[0]) << 24) |
                    (static_cast<int32_t>(ptr[1]) << 16) |
                    (static_cast<int32_t>(ptr[2]) << 8) |
                    static_cast<int32_t>(ptr[3]);
    const uint8_t *varint = ptr + 4;
    size_t varint_len = len - 4;
    cass_statement_bind_decimal(stmt, index, varint, varint_len, scale);
  }
}

static void bind_duration(CassStatement *stmt, size_t index, const uint8_t *ptr,
                          size_t len, const std::vector<uint8_t> &) {
  cass_statement_bind_bytes(stmt, index, ptr, len);
}

// Helper for collection element binding
static void append_collection_element(CassCollection *collection,
                                      TypeCode elem_type,
                                      const std::vector<uint8_t> *elem_data) {
  if (LATTE_UNLIKELY(!elem_data))
    return;

  const uint8_t *p = elem_data->data();
  size_t sz = elem_data->size();

  switch (elem_type) {
  case TypeCode::INT:
    if (sz >= 4) {
      int32_t v = (static_cast<int32_t>(p[0]) << 24) |
                  (static_cast<int32_t>(p[1]) << 16) |
                  (static_cast<int32_t>(p[2]) << 8) | static_cast<int32_t>(p[3]);
      cass_collection_append_int32(collection, v);
    }
    break;
  case TypeCode::BIGINT:
    if (sz >= 8) {
      int64_t v = (static_cast<int64_t>(p[0]) << 56) |
                  (static_cast<int64_t>(p[1]) << 48) |
                  (static_cast<int64_t>(p[2]) << 40) |
                  (static_cast<int64_t>(p[3]) << 32) |
                  (static_cast<int64_t>(p[4]) << 24) |
                  (static_cast<int64_t>(p[5]) << 16) |
                  (static_cast<int64_t>(p[6]) << 8) | static_cast<int64_t>(p[7]);
      cass_collection_append_int64(collection, v);
    }
    break;
  case TypeCode::TEXT:
  case TypeCode::ASCII:
    cass_collection_append_string_n(collection,
                                    reinterpret_cast<const char *>(p), sz);
    break;
  case TypeCode::FLOAT:
    if (sz >= 4) {
      uint32_t bits = (static_cast<uint32_t>(p[0]) << 24) |
                      (static_cast<uint32_t>(p[1]) << 16) |
                      (static_cast<uint32_t>(p[2]) << 8) |
                      static_cast<uint32_t>(p[3]);
      float v;
      std::memcpy(&v, &bits, sizeof(v));
      cass_collection_append_float(collection, v);
    }
    break;
  case TypeCode::DOUBLE:
    if (sz >= 8) {
      uint64_t bits = (static_cast<uint64_t>(p[0]) << 56) |
                      (static_cast<uint64_t>(p[1]) << 48) |
                      (static_cast<uint64_t>(p[2]) << 40) |
                      (static_cast<uint64_t>(p[3]) << 32) |
                      (static_cast<uint64_t>(p[4]) << 24) |
                      (static_cast<uint64_t>(p[5]) << 16) |
                      (static_cast<uint64_t>(p[6]) << 8) |
                      static_cast<uint64_t>(p[7]);
      double v;
      std::memcpy(&v, &bits, sizeof(v));
      cass_collection_append_double(collection, v);
    }
    break;
  default:
    cass_collection_append_bytes(collection, p, sz);
    break;
  }
}

static void bind_list_or_set(CassStatement *stmt, size_t index,
                             const uint8_t *, size_t,
                             const std::vector<uint8_t> &data, bool is_list) {
  Buffer col_buf(std::vector<uint8_t>(data.begin(), data.end()));
  TypeCode elem_type = static_cast<TypeCode>(col_buf.read_short());
  int32_t count = col_buf.read_int();

  CassCollection *collection = cass_collection_new(
      is_list ? CASS_COLLECTION_TYPE_LIST : CASS_COLLECTION_TYPE_SET,
      static_cast<size_t>(count));

  for (int32_t i = 0; i < count; ++i) {
    auto elem_data = col_buf.read_bytes();
    if (elem_data) {
      append_collection_element(collection, elem_type, &(*elem_data));
    }
  }

  cass_statement_bind_collection(stmt, index, collection);
  cass_collection_free(collection);
}

static void bind_list(CassStatement *stmt, size_t index, const uint8_t *ptr,
                      size_t len, const std::vector<uint8_t> &data) {
  bind_list_or_set(stmt, index, ptr, len, data, true);
}

static void bind_set(CassStatement *stmt, size_t index, const uint8_t *ptr,
                     size_t len, const std::vector<uint8_t> &data) {
  bind_list_or_set(stmt, index, ptr, len, data, false);
}

static void bind_map(CassStatement *stmt, size_t index, const uint8_t *,
                     size_t, const std::vector<uint8_t> &data) {
  Buffer map_buf(std::vector<uint8_t>(data.begin(), data.end()));
  TypeCode key_type = static_cast<TypeCode>(map_buf.read_short());
  TypeCode val_type = static_cast<TypeCode>(map_buf.read_short());
  int32_t count = map_buf.read_int();

  CassCollection *collection = cass_collection_new(
      CASS_COLLECTION_TYPE_MAP, static_cast<size_t>(count) * 2);

  for (int32_t i = 0; i < count; ++i) {
    auto key_data = map_buf.read_bytes();
    auto val_data = map_buf.read_bytes();

    if (key_data) {
      append_collection_element(collection, key_type, &(*key_data));
    }
    if (val_data) {
      append_collection_element(collection, val_type, &(*val_data));
    }
  }

  cass_statement_bind_collection(stmt, index, collection);
  cass_collection_free(collection);
}

static void bind_tuple(CassStatement *stmt, size_t index, const uint8_t *,
                       size_t, const std::vector<uint8_t> &data) {
  Buffer tuple_buf(std::vector<uint8_t>(data.begin(), data.end()));
  uint16_t n_elements = tuple_buf.read_short();

  std::vector<TypeCode> elem_types;
  elem_types.reserve(n_elements);
  for (uint16_t i = 0; i < n_elements; ++i) {
    elem_types.push_back(static_cast<TypeCode>(tuple_buf.read_short()));
  }

  CassTuple *tuple = cass_tuple_new(n_elements);
  for (uint16_t i = 0; i < n_elements; ++i) {
    auto elem_data = tuple_buf.read_bytes();
    if (elem_data) {
      cass_tuple_set_bytes(tuple, i, elem_data->data(), elem_data->size());
    } else {
      cass_tuple_set_null(tuple, i);
    }
  }

  cass_statement_bind_tuple(stmt, index, tuple);
  cass_tuple_free(tuple);
}

static void bind_vector(CassStatement *stmt, size_t index, const uint8_t *,
                        size_t len, const std::vector<uint8_t> &data) {
  Buffer vec_buf(std::vector<uint8_t>(data.begin(), data.end()));
  TypeCode elem_type = static_cast<TypeCode>(vec_buf.read_short());
  uint16_t dimension = vec_buf.read_short();

  // Skip the 4-byte metadata header (elem_type + dimension) - only bind actual vector data
  constexpr size_t METADATA_SIZE = 4;
  if (len <= METADATA_SIZE) {
    return;
  }
  const uint8_t *vector_data = data.data() + METADATA_SIZE;
  size_t vector_len = len - METADATA_SIZE;

  if (elem_type == TypeCode::FLOAT &&
      vector_len >= static_cast<size_t>(dimension) * sizeof(float)) {
    cass_statement_bind_bytes(stmt, index, vector_data, vector_len);
  }
}

// Table of binder functions indexed by TypeCode value
// Max TypeCode value is UDT = 0x40 (64), so we need 65 entries
static constexpr size_t BINDER_TABLE_SIZE = 65;
static const BinderFunc BINDER_TABLE[BINDER_TABLE_SIZE] = {
    nullptr,      // 0x00 - unused
    bind_text,    // 0x01 - ASCII
    bind_bigint,  // 0x02 - BIGINT
    bind_blob,    // 0x03 - BLOB
    bind_boolean, // 0x04 - BOOLEAN
    bind_bigint,  // 0x05 - COUNTER (same as BIGINT)
    bind_decimal, // 0x06 - DECIMAL
    bind_double,  // 0x07 - DOUBLE
    bind_float,   // 0x08 - FLOAT
    bind_int,     // 0x09 - INT
    nullptr,      // 0x0A - unused
    bind_bigint,  // 0x0B - TIMESTAMP (same as BIGINT)
    bind_uuid,    // 0x0C - UUID
    bind_text,    // 0x0D - TEXT
    bind_blob,    // 0x0E - VARINT (same as BLOB)
    bind_uuid,    // 0x0F - TIMEUUID (same as UUID)
    bind_inet,    // 0x10 - INET
    bind_date,    // 0x11 - DATE
    bind_bigint,  // 0x12 - TIME (same as BIGINT)
    bind_smallint,// 0x13 - SMALLINT
    bind_tinyint, // 0x14 - TINYINT
    bind_duration,// 0x15 - DURATION
    nullptr, nullptr, nullptr, nullptr, nullptr, nullptr, nullptr, nullptr,
    nullptr, nullptr, // 0x16-0x1F - unused
    bind_list,    // 0x20 - LIST
    bind_map,     // 0x21 - MAP
    bind_set,     // 0x22 - SET
    nullptr, nullptr, nullptr, nullptr, nullptr, nullptr, nullptr, nullptr,
    nullptr, nullptr, nullptr, nullptr, nullptr, // 0x23-0x2F - unused
    bind_vector,  // 0x30 - VECTOR
    bind_tuple,   // 0x31 - TUPLE
    nullptr, nullptr, nullptr, nullptr, nullptr, nullptr, nullptr, nullptr,
    nullptr, nullptr, nullptr, nullptr, nullptr, nullptr, // 0x32-0x3F - unused
    bind_blob,    // 0x40 - UDT (bind as blob)
};

// Type coercion helpers

// Coerce bigint to smaller integer types with bounds checking
static int64_t read_bigint(const uint8_t *ptr, size_t len) {
  if (len < 8)
    return 0;
  return (static_cast<int64_t>(ptr[0]) << 56) |
         (static_cast<int64_t>(ptr[1]) << 48) |
         (static_cast<int64_t>(ptr[2]) << 40) |
         (static_cast<int64_t>(ptr[3]) << 32) |
         (static_cast<int64_t>(ptr[4]) << 24) |
         (static_cast<int64_t>(ptr[5]) << 16) |
         (static_cast<int64_t>(ptr[6]) << 8) | static_cast<int64_t>(ptr[7]);
}

static int32_t coerce_bigint_to_int(int64_t v) {
  if (v > std::numeric_limits<int32_t>::max()) {
    return std::numeric_limits<int32_t>::max();
  }
  if (v < std::numeric_limits<int32_t>::min()) {
    return std::numeric_limits<int32_t>::min();
  }
  return static_cast<int32_t>(v);
}

static int16_t coerce_bigint_to_smallint(int64_t v) {
  if (v > std::numeric_limits<int16_t>::max()) {
    return std::numeric_limits<int16_t>::max();
  }
  if (v < std::numeric_limits<int16_t>::min()) {
    return std::numeric_limits<int16_t>::min();
  }
  return static_cast<int16_t>(v);
}

static int8_t coerce_bigint_to_tinyint(int64_t v) {
  if (v > std::numeric_limits<int8_t>::max()) {
    return std::numeric_limits<int8_t>::max();
  }
  if (v < std::numeric_limits<int8_t>::min()) {
    return std::numeric_limits<int8_t>::min();
  }
  return static_cast<int8_t>(v);
}

// Coerce double to float
static float coerce_double_to_float(double v) {
  if (std::isnan(v))
    return std::numeric_limits<float>::quiet_NaN();
  if (std::isinf(v))
    return v > 0 ? std::numeric_limits<float>::infinity()
                 : -std::numeric_limits<float>::infinity();
  if (v > std::numeric_limits<float>::max()) {
    return std::numeric_limits<float>::max();
  }
  if (v < std::numeric_limits<float>::lowest()) {
    return std::numeric_limits<float>::lowest();
  }
  return static_cast<float>(v);
}

// Parse date from string (YYYY-MM-DD)
static uint32_t parse_date_string(const std::string &s) {
  int year, month, day;
  if (sscanf(s.c_str(), "%d-%d-%d", &year, &month, &day) != 3) {
    return 0;
  }
  // Calculate days since epoch (1970-01-01)
  // Simplified calculation - doesn't handle all edge cases
  int days = 0;
  for (int y = 1970; y < year; ++y) {
    days += (y % 4 == 0 && (y % 100 != 0 || y % 400 == 0)) ? 366 : 365;
  }
  int month_days[] = {0, 31, 28, 31, 30, 31, 30, 31, 31, 30, 31, 30, 31};
  if (year % 4 == 0 && (year % 100 != 0 || year % 400 == 0)) {
    month_days[2] = 29;
  }
  for (int m = 1; m < month; ++m) {
    days += month_days[m];
  }
  days += day - 1;
  // CQL date is centered at 2^31 (1970-01-01 = 2^31)
  return static_cast<uint32_t>(days + (1U << 31));
}

// Parse time from string (HH:MM:SS or HH:MM:SS.nnnnnnnnn)
static int64_t parse_time_string(const std::string &s) {
  int hours, minutes, seconds;
  long nanoseconds = 0;
  if (sscanf(s.c_str(), "%d:%d:%d", &hours, &minutes, &seconds) < 3) {
    return 0;
  }
  // Check for fractional seconds
  size_t dot_pos = s.find('.');
  if (dot_pos != std::string::npos) {
    std::string frac = s.substr(dot_pos + 1);
    // Pad to 9 digits
    while (frac.length() < 9)
      frac += '0';
    nanoseconds = std::stol(frac.substr(0, 9));
  }
  return static_cast<int64_t>(hours) * 3600000000000LL +
         static_cast<int64_t>(minutes) * 60000000000LL +
         static_cast<int64_t>(seconds) * 1000000000LL + nanoseconds;
}

// Convert i64 to minimal big-endian varint bytes
static std::vector<uint8_t> bigint_to_varint_bytes(int64_t v) {
  uint8_t buf[8];
  uint64_t uv = static_cast<uint64_t>(v);
  for (int i = 7; i >= 0; --i) {
    buf[i] = static_cast<uint8_t>(uv & 0xFF);
    uv >>= 8;
  }
  // Find minimal encoding start
  size_t start = 0;
  if (buf[0] & 0x80) {
    // Negative - skip leading 0xFF bytes, keep one if next byte doesn't have sign bit
    while (start < 7 && buf[start] == 0xFF && (buf[start + 1] & 0x80)) {
      ++start;
    }
  } else {
    // Positive - skip leading 0x00 bytes, keep one if next byte has sign bit
    while (start < 7 && buf[start] == 0x00 && !(buf[start + 1] & 0x80)) {
      ++start;
    }
  }
  return std::vector<uint8_t>(buf + start, buf + 8);
}

// Parse decimal string (e.g. "123.45") into scale + varint bytes
struct DecimalParts {
  std::vector<uint8_t> varint;
  int32_t scale;
};

static DecimalParts parse_decimal_string(const std::string &s) {
  DecimalParts result;
  result.scale = 0;

  bool negative = false;
  size_t i = 0;

  if (i < s.size() && s[i] == '-') {
    negative = true;
    i++;
  } else if (i < s.size() && s[i] == '+') {
    i++;
  }

  bool found_dot = false;
  int64_t unscaled = 0;
  for (; i < s.size(); ++i) {
    if (s[i] == '.') {
      found_dot = true;
      continue;
    }
    if (s[i] >= '0' && s[i] <= '9') {
      unscaled = unscaled * 10 + (s[i] - '0');
      if (found_dot)
        result.scale++;
    }
  }
  if (negative)
    unscaled = -unscaled;

  result.varint = bigint_to_varint_bytes(unscaled);
  return result;
}

// Parse duration string (e.g. "1mo2d3h4m5s") into months, days, nanos
static bool parse_duration_string_to_parts(const std::string &s,
                                           int32_t &months, int32_t &days,
                                           int64_t &nanos) {
  months = 0;
  days = 0;
  nanos = 0;

  size_t i = 0;
  while (i < s.size()) {
    // Skip whitespace
    while (i < s.size() && s[i] == ' ')
      i++;
    if (i >= s.size())
      break;

    // Parse number
    int64_t num = 0;
    bool found_digit = false;
    bool neg = false;
    if (i < s.size() && s[i] == '-') {
      neg = true;
      i++;
    }
    while (i < s.size() && s[i] >= '0' && s[i] <= '9') {
      num = num * 10 + (s[i] - '0');
      found_digit = true;
      i++;
    }
    if (!found_digit)
      return false;
    if (neg)
      num = -num;

    // Parse unit
    if (i + 1 < s.size() && s[i] == 'm' && s[i + 1] == 'o') {
      months = static_cast<int32_t>(num);
      i += 2;
    } else if (i + 1 < s.size() && s[i] == 'm' && s[i + 1] == 's') {
      nanos += num * 1000000LL;
      i += 2;
    } else if (i + 1 < s.size() && s[i] == 'u' && s[i + 1] == 's') {
      nanos += num * 1000LL;
      i += 2;
    } else if (i + 1 < s.size() && s[i] == 'n' && s[i + 1] == 's') {
      nanos += num;
      i += 2;
    } else if (i < s.size() && s[i] == 'd') {
      days = static_cast<int32_t>(num);
      i++;
    } else if (i < s.size() && s[i] == 'h') {
      nanos += num * 3600000000000LL;
      i++;
    } else if (i < s.size() && s[i] == 'm') {
      nanos += num * 60000000000LL;
      i++;
    } else if (i < s.size() && s[i] == 's') {
      nanos += num * 1000000000LL;
      i++;
    } else {
      return false;
    }
  }
  return true;
}

// Read double from big-endian bytes
static double read_double_be(const uint8_t *ptr, size_t len) {
  if (len < 8)
    return 0.0;
  uint64_t bits = (static_cast<uint64_t>(ptr[0]) << 56) |
                  (static_cast<uint64_t>(ptr[1]) << 48) |
                  (static_cast<uint64_t>(ptr[2]) << 40) |
                  (static_cast<uint64_t>(ptr[3]) << 32) |
                  (static_cast<uint64_t>(ptr[4]) << 24) |
                  (static_cast<uint64_t>(ptr[5]) << 16) |
                  (static_cast<uint64_t>(ptr[6]) << 8) |
                  static_cast<uint64_t>(ptr[7]);
  double d;
  std::memcpy(&d, &bits, sizeof(d));
  return d;
}

// Check if a CUSTOM data type is a vector type
static bool is_vector_custom_type(const CassDataType *data_type,
                                   const char **class_name_out = nullptr,
                                   size_t *class_name_len_out = nullptr) {
  if (!data_type)
    return false;
  if (cass_data_type_type(data_type) != CASS_VALUE_TYPE_CUSTOM)
    return false;
  const char *cn;
  size_t cn_len;
  if (cass_data_type_class_name(data_type, &cn, &cn_len) != CASS_OK)
    return false;
  if (class_name_out)
    *class_name_out = cn;
  if (class_name_len_out)
    *class_name_len_out = cn_len;
  return std::string(cn, cn_len).find("VectorType") != std::string::npos;
}

TypedValue read_typed_value(Buffer &buf) {
  TypedValue value;
  value.type = static_cast<TypeCode>(buf.read_short());
  value.data = buf.read_bytes();
  return value;
}

void bind_value(CassStatement *stmt, size_t index, const TypedValue &value) {
  if (LATTE_UNLIKELY(!value.data)) {
    cass_statement_bind_null(stmt, index);
    return;
  }

  const std::vector<uint8_t> &data = *value.data;
  const uint8_t *ptr = data.data();
  size_t len = data.size();

  // Table-driven type binding for better branch prediction
  uint16_t type_idx = static_cast<uint16_t>(value.type);
  if (LATTE_LIKELY(type_idx < BINDER_TABLE_SIZE)) {
    BinderFunc binder = BINDER_TABLE[type_idx];
    if (LATTE_LIKELY(binder != nullptr)) {
      binder(stmt, index, ptr, len, data);
      return;
    }
  }

  // Fallback for unknown types - bind as bytes
  cass_statement_bind_bytes(stmt, index, ptr, len);
}

// Old reserialize functions removed - now using unified reserialize_value() above

// Helper to append collection element using schema type info
static void append_collection_element_with_type_info(
    CassCollection *collection, const CassDataType *expected_type_info,
    const std::vector<uint8_t> *elem_data) {
  if (LATTE_UNLIKELY(!elem_data))
    return;

  const uint8_t *p = elem_data->data();
  size_t sz = elem_data->size();

  CassValueType expected_type =
      expected_type_info ? cass_data_type_type(expected_type_info)
                         : CASS_VALUE_TYPE_BLOB;

  switch (expected_type) {
  case CASS_VALUE_TYPE_TINY_INT:
    if (sz == 1) {
      cass_collection_append_int8(collection, static_cast<int8_t>(p[0]));
    } else if (sz == 8) {
      cass_collection_append_int8(collection, static_cast<int8_t>(p[7]));
    }
    break;
  case CASS_VALUE_TYPE_SMALL_INT:
    if (sz == 2) {
      int16_t v = static_cast<int16_t>((p[0] << 8) | p[1]);
      cass_collection_append_int16(collection, v);
    } else if (sz == 8) {
      int16_t v = static_cast<int16_t>((p[6] << 8) | p[7]);
      cass_collection_append_int16(collection, v);
    }
    break;
  case CASS_VALUE_TYPE_INT:
    if (sz == 4) {
      int32_t v = (static_cast<int32_t>(p[0]) << 24) |
                  (static_cast<int32_t>(p[1]) << 16) |
                  (static_cast<int32_t>(p[2]) << 8) | static_cast<int32_t>(p[3]);
      cass_collection_append_int32(collection, v);
    } else if (sz == 8) {
      int32_t v = (static_cast<int32_t>(p[4]) << 24) |
                  (static_cast<int32_t>(p[5]) << 16) |
                  (static_cast<int32_t>(p[6]) << 8) | static_cast<int32_t>(p[7]);
      cass_collection_append_int32(collection, v);
    }
    break;
  case CASS_VALUE_TYPE_BIGINT:
  case CASS_VALUE_TYPE_COUNTER:
  case CASS_VALUE_TYPE_TIMESTAMP:
  case CASS_VALUE_TYPE_TIME:
    if (sz >= 8) {
      int64_t v = (static_cast<int64_t>(p[0]) << 56) |
                  (static_cast<int64_t>(p[1]) << 48) |
                  (static_cast<int64_t>(p[2]) << 40) |
                  (static_cast<int64_t>(p[3]) << 32) |
                  (static_cast<int64_t>(p[4]) << 24) |
                  (static_cast<int64_t>(p[5]) << 16) |
                  (static_cast<int64_t>(p[6]) << 8) | static_cast<int64_t>(p[7]);
      cass_collection_append_int64(collection, v);
    }
    break;
  case CASS_VALUE_TYPE_TEXT:
  case CASS_VALUE_TYPE_VARCHAR:
  case CASS_VALUE_TYPE_ASCII:
    cass_collection_append_string_n(collection,
                                    reinterpret_cast<const char *>(p), sz);
    break;
  case CASS_VALUE_TYPE_FLOAT:
    if (sz >= 4) {
      uint32_t bits = (static_cast<uint32_t>(p[0]) << 24) |
                      (static_cast<uint32_t>(p[1]) << 16) |
                      (static_cast<uint32_t>(p[2]) << 8) |
                      static_cast<uint32_t>(p[3]);
      float v;
      std::memcpy(&v, &bits, sizeof(v));
      cass_collection_append_float(collection, v);
    }
    break;
  case CASS_VALUE_TYPE_DOUBLE:
    if (sz >= 8) {
      uint64_t bits = (static_cast<uint64_t>(p[0]) << 56) |
                      (static_cast<uint64_t>(p[1]) << 48) |
                      (static_cast<uint64_t>(p[2]) << 40) |
                      (static_cast<uint64_t>(p[3]) << 32) |
                      (static_cast<uint64_t>(p[4]) << 24) |
                      (static_cast<uint64_t>(p[5]) << 16) |
                      (static_cast<uint64_t>(p[6]) << 8) |
                      static_cast<uint64_t>(p[7]);
      double v;
      std::memcpy(&v, &bits, sizeof(v));
      cass_collection_append_double(collection, v);
    }
    break;
  case CASS_VALUE_TYPE_BOOLEAN:
    if (sz >= 1) {
      cass_collection_append_bool(collection, p[0] ? cass_true : cass_false);
    }
    break;
  case CASS_VALUE_TYPE_UUID:
  case CASS_VALUE_TYPE_TIMEUUID:
    if (sz >= 16) {
      CassUuid uuid;
      std::memcpy(&uuid, p, 16);
      cass_collection_append_uuid(collection, uuid);
    }
    break;
  case CASS_VALUE_TYPE_LIST:
  case CASS_VALUE_TYPE_SET: {
    // For frozen list/set elements, re-serialize with correct types
    // IPC format: [elem_type: u16] [count: i32] [elements...]
    // Cassandra expects: [count: i32] [elements...]
    if (sz < 6) {
      LOG_DEBUG("append_collection_element: LIST/SET sz=" << sz << " < 6, passing through");
      cass_collection_append_bytes(collection, p, sz);
      break;
    }
    try {
      Buffer reser;
      reserialize_list_or_set(reser, p, sz, expected_type_info);
      cass_collection_append_bytes(collection, reser.data(), reser.size());
    } catch (const std::exception &e) {
      LOG_ERROR("append_collection_element: LIST/SET reserialize failed sz=" << sz << ": " << e.what());
      cass_collection_append_bytes(collection, p, sz);
    }
    break;
  }
  case CASS_VALUE_TYPE_MAP: {
    // For frozen map elements, re-serialize with correct types
    // IPC format: [key_type: u16] [val_type: u16] [count: i32] [entries...]
    // Cassandra expects: [count: i32] [entries...]
    if (sz < 8) {
      LOG_DEBUG("append_collection_element: MAP sz=" << sz << " < 8, passing through");
      cass_collection_append_bytes(collection, p, sz);
      break;
    }
    try {
      Buffer reser;
      reserialize_map(reser, p, sz, expected_type_info);
      cass_collection_append_bytes(collection, reser.data(), reser.size());
    } catch (const std::exception &e) {
      LOG_ERROR("append_collection_element: MAP reserialize failed sz=" << sz << ": " << e.what());
      cass_collection_append_bytes(collection, p, sz);
    }
    break;
  }
  case CASS_VALUE_TYPE_UDT: {
    // For UDT elements, re-serialize with correct types
    // IPC format: [n_fields: u16] [field_metadata...] [field_values...]
    // Cassandra expects: [field_values...]
    if (sz < 2) {
      LOG_DEBUG("append_collection_element: UDT sz=" << sz << " < 2, passing through");
      cass_collection_append_bytes(collection, p, sz);
      break;
    }
    try {
      Buffer reser;
      reserialize_udt(reser, p, sz, expected_type_info);
      cass_collection_append_bytes(collection, reser.data(), reser.size());
    } catch (const std::exception &e) {
      LOG_ERROR("append_collection_element: UDT reserialize failed sz=" << sz << ": " << e.what());
      cass_collection_append_bytes(collection, p, sz);
    }
    break;
  }
  case CASS_VALUE_TYPE_TUPLE: {
    // For tuple elements, re-serialize with correct types
    // IPC format: [n_elements: u16] [type_codes: u16 * n] [elements...]
    // Cassandra expects: [elements...]
    if (sz < 2) {
      LOG_DEBUG("append_collection_element: TUPLE sz=" << sz << " < 2, passing through");
      cass_collection_append_bytes(collection, p, sz);
      break;
    }
    try {
      Buffer reser;
      reserialize_tuple(reser, p, sz, expected_type_info);
      cass_collection_append_bytes(collection, reser.data(), reser.size());
    } catch (const std::exception &e) {
      LOG_ERROR("append_collection_element: TUPLE reserialize failed sz=" << sz << ": " << e.what());
      cass_collection_append_bytes(collection, p, sz);
    }
    break;
  }
  case CASS_VALUE_TYPE_CUSTOM:
    // CUSTOM type includes vector<float, N> elements.
    // cpp-rs-driver does not implement cass_collection_append_custom(),
    // so vector elements in collections cannot be properly bound.
    // Strip IPC vector header if present and append raw bytes.
    if (sz > 4) {
      // Strip 4-byte IPC vector header (elem_type u16 + dimension u16)
      cass_collection_append_bytes(collection, p + 4, sz - 4);
    } else {
      cass_collection_append_bytes(collection, p, sz);
    }
    break;
  default:
    cass_collection_append_bytes(collection, p, sz);
    break;
  }
}

// Bind map using schema type info from prepared statement
static void bind_map_with_schema(CassStatement *stmt, size_t index,
                                 const std::vector<uint8_t> &data,
                                 const CassDataType *map_type) {
  Buffer map_buf(std::vector<uint8_t>(data.begin(), data.end()));
  (void)map_buf.read_short(); // skip wire key_type
  (void)map_buf.read_short(); // skip wire val_type
  int32_t count = map_buf.read_int();

  // Get expected key and value types from schema
  const CassDataType *key_type_info = cass_data_type_sub_data_type(map_type, 0);
  const CassDataType *val_type_info = cass_data_type_sub_data_type(map_type, 1);

  CassCollection *collection = cass_collection_new(
      CASS_COLLECTION_TYPE_MAP, static_cast<size_t>(count) * 2);

  for (int32_t i = 0; i < count; ++i) {
    auto key_data = map_buf.read_bytes();
    auto val_data = map_buf.read_bytes();

    if (key_data) {
      append_collection_element_with_type_info(collection, key_type_info,
                                               &(*key_data));
    }
    if (val_data) {
      append_collection_element_with_type_info(collection, val_type_info,
                                               &(*val_data));
    }
  }

  cass_statement_bind_collection(stmt, index, collection);
  cass_collection_free(collection);
}

// Bind tuple using schema type info from prepared statement
static void bind_tuple_with_schema(CassStatement *stmt, size_t index,
                                   const std::vector<uint8_t> &data,
                                   const CassDataType *tuple_type) {
  Buffer tuple_buf(std::vector<uint8_t>(data.begin(), data.end()));
  uint16_t n_elements = tuple_buf.read_short();

  // Skip type codes (2 bytes each)
  for (uint16_t i = 0; i < n_elements; ++i) {
    tuple_buf.read_short();
  }

  // Get expected element count from schema
  size_t schema_elem_count = cass_data_type_sub_type_count(tuple_type);

  CassTuple *tuple = cass_tuple_new(n_elements);

  for (uint16_t i = 0; i < n_elements; ++i) {
    auto elem_data = tuple_buf.read_bytes();
    if (!elem_data) {
      cass_tuple_set_null(tuple, i);
      continue;
    }

    // Get expected element type from schema
    const CassDataType *elem_type = nullptr;
    if (i < schema_elem_count) {
      elem_type = cass_data_type_sub_data_type(tuple_type, i);
    }

    // Re-serialize the element with correct types
    if (elem_type) {
      CassValueType expected = cass_data_type_type(elem_type);
      const uint8_t *p = elem_data->data();
      size_t sz = elem_data->size();

      // Handle type coercion for primitives
      switch (expected) {
      case CASS_VALUE_TYPE_TINY_INT:
        if (sz == 8) {
          int8_t v = static_cast<int8_t>(p[7]);
          cass_tuple_set_int8(tuple, i, v);
          continue;
        }
        break;
      case CASS_VALUE_TYPE_SMALL_INT:
        if (sz == 8) {
          int16_t v = static_cast<int16_t>((p[6] << 8) | p[7]);
          cass_tuple_set_int16(tuple, i, v);
          continue;
        }
        break;
      case CASS_VALUE_TYPE_INT:
        if (sz == 8) {
          int32_t v = (static_cast<int32_t>(p[4]) << 24) |
                      (static_cast<int32_t>(p[5]) << 16) |
                      (static_cast<int32_t>(p[6]) << 8) |
                      static_cast<int32_t>(p[7]);
          cass_tuple_set_int32(tuple, i, v);
          continue;
        } else if (sz == 4) {
          int32_t v = (static_cast<int32_t>(p[0]) << 24) |
                      (static_cast<int32_t>(p[1]) << 16) |
                      (static_cast<int32_t>(p[2]) << 8) |
                      static_cast<int32_t>(p[3]);
          cass_tuple_set_int32(tuple, i, v);
          continue;
        }
        break;
      case CASS_VALUE_TYPE_BIGINT:
      case CASS_VALUE_TYPE_TIMESTAMP:
      case CASS_VALUE_TYPE_TIME:
        if (sz >= 8) {
          int64_t v = (static_cast<int64_t>(p[0]) << 56) |
                      (static_cast<int64_t>(p[1]) << 48) |
                      (static_cast<int64_t>(p[2]) << 40) |
                      (static_cast<int64_t>(p[3]) << 32) |
                      (static_cast<int64_t>(p[4]) << 24) |
                      (static_cast<int64_t>(p[5]) << 16) |
                      (static_cast<int64_t>(p[6]) << 8) |
                      static_cast<int64_t>(p[7]);
          cass_tuple_set_int64(tuple, i, v);
          continue;
        }
        break;
      case CASS_VALUE_TYPE_FLOAT:
        if (sz >= 4) {
          uint32_t bits = (static_cast<uint32_t>(p[0]) << 24) |
                          (static_cast<uint32_t>(p[1]) << 16) |
                          (static_cast<uint32_t>(p[2]) << 8) |
                          static_cast<uint32_t>(p[3]);
          float v;
          std::memcpy(&v, &bits, sizeof(v));
          cass_tuple_set_float(tuple, i, v);
          continue;
        }
        break;
      case CASS_VALUE_TYPE_DOUBLE:
        if (sz >= 8) {
          uint64_t bits = (static_cast<uint64_t>(p[0]) << 56) |
                          (static_cast<uint64_t>(p[1]) << 48) |
                          (static_cast<uint64_t>(p[2]) << 40) |
                          (static_cast<uint64_t>(p[3]) << 32) |
                          (static_cast<uint64_t>(p[4]) << 24) |
                          (static_cast<uint64_t>(p[5]) << 16) |
                          (static_cast<uint64_t>(p[6]) << 8) |
                          static_cast<uint64_t>(p[7]);
          double v;
          std::memcpy(&v, &bits, sizeof(v));
          cass_tuple_set_double(tuple, i, v);
          continue;
        }
        break;
      case CASS_VALUE_TYPE_BOOLEAN:
        if (sz >= 1) {
          cass_tuple_set_bool(tuple, i, p[0] ? cass_true : cass_false);
          continue;
        }
        break;
      case CASS_VALUE_TYPE_TEXT:
      case CASS_VALUE_TYPE_VARCHAR:
      case CASS_VALUE_TYPE_ASCII:
        cass_tuple_set_string_n(tuple, i, reinterpret_cast<const char*>(p), sz);
        continue;
      case CASS_VALUE_TYPE_UUID:
      case CASS_VALUE_TYPE_TIMEUUID:
        if (sz >= 16) {
          CassUuid uuid;
          std::memcpy(&uuid, p, 16);
          cass_tuple_set_uuid(tuple, i, uuid);
          continue;
        }
        break;
      case CASS_VALUE_TYPE_LIST:
      case CASS_VALUE_TYPE_SET: {
        // Call inner function directly (no byte-length prefix) since
        // cass_tuple_set_bytes handles its own framing
        Buffer reser;
        reserialize_list_or_set(reser, p, sz, elem_type);
        cass_tuple_set_bytes(tuple, i, reser.data(), reser.size());
        continue;
      }
      case CASS_VALUE_TYPE_MAP: {
        Buffer reser;
        reserialize_map(reser, p, sz, elem_type);
        cass_tuple_set_bytes(tuple, i, reser.data(), reser.size());
        continue;
      }
      case CASS_VALUE_TYPE_TUPLE: {
        Buffer reser;
        reserialize_tuple(reser, p, sz, elem_type);
        cass_tuple_set_bytes(tuple, i, reser.data(), reser.size());
        continue;
      }
      case CASS_VALUE_TYPE_UDT: {
        Buffer reser;
        reserialize_udt(reser, p, sz, elem_type);
        cass_tuple_set_bytes(tuple, i, reser.data(), reser.size());
        continue;
      }
      default:
        break;
      }
    }

    // Fallback: set as raw bytes
    cass_tuple_set_bytes(tuple, i, elem_data->data(), elem_data->size());
  }

  cass_statement_bind_tuple(stmt, index, tuple);
  cass_tuple_free(tuple);
}

// Bind list/set using schema type info from prepared statement
static void bind_list_or_set_with_schema(CassStatement *stmt, size_t index,
                                         const std::vector<uint8_t> &data,
                                         const CassDataType *col_type,
                                         bool is_list) {
  Buffer col_buf(std::vector<uint8_t>(data.begin(), data.end()));
  (void)col_buf.read_short(); // skip wire elem_type
  int32_t count = col_buf.read_int();

  // Get expected element type from schema
  const CassDataType *elem_type_info = cass_data_type_sub_data_type(col_type, 0);

  CassCollection *collection = cass_collection_new(
      is_list ? CASS_COLLECTION_TYPE_LIST : CASS_COLLECTION_TYPE_SET,
      static_cast<size_t>(count));

  for (int32_t i = 0; i < count; ++i) {
    auto elem_data = col_buf.read_bytes();
    if (elem_data) {
      append_collection_element_with_type_info(collection, elem_type_info,
                                               &(*elem_data));
    }
  }

  cass_statement_bind_collection(stmt, index, collection);
  cass_collection_free(collection);
}

// Bind packed float vector as a list/set collection
static void bind_vector_as_collection(CassStatement *stmt, size_t index,
                                      const std::vector<uint8_t> &data,
                                      bool is_list) {
  Buffer vec_buf(std::vector<uint8_t>(data.begin(), data.end()));
  TypeCode elem_type = static_cast<TypeCode>(vec_buf.read_short());
  uint16_t dimension = vec_buf.read_short();

  CassCollection *collection = cass_collection_new(
      is_list ? CASS_COLLECTION_TYPE_LIST : CASS_COLLECTION_TYPE_SET,
      dimension);

  if (elem_type == TypeCode::FLOAT) {
    for (uint16_t i = 0; i < dimension && vec_buf.remaining() >= 4; ++i) {
      // Read big-endian float
      uint8_t b[4];
      b[0] = vec_buf.read_byte();
      b[1] = vec_buf.read_byte();
      b[2] = vec_buf.read_byte();
      b[3] = vec_buf.read_byte();
      uint32_t bits = (static_cast<uint32_t>(b[0]) << 24) |
                      (static_cast<uint32_t>(b[1]) << 16) |
                      (static_cast<uint32_t>(b[2]) << 8) |
                      static_cast<uint32_t>(b[3]);
      float v;
      std::memcpy(&v, &bits, sizeof(v));
      cass_collection_append_float(collection, v);
    }
  }

  cass_statement_bind_collection(stmt, index, collection);
  cass_collection_free(collection);
}

// Bind UDT using schema type info from prepared statement
static void bind_udt_with_schema(CassStatement *stmt, size_t index,
                                 const std::vector<uint8_t> &data,
                                 const CassDataType *udt_type) {
  Buffer buf(std::vector<uint8_t>(data.begin(), data.end()));
  uint16_t n_fields = buf.read_short();

  // Read field metadata (names and wire type codes)
  for (uint16_t i = 0; i < n_fields; ++i) {
    uint16_t name_len = buf.read_short();
    buf.advance(name_len); // skip field name
    buf.read_short();      // skip wire type code
  }

  size_t schema_field_count = cass_data_type_sub_type_count(udt_type);

  CassUserType *udt = cass_user_type_new_from_data_type(udt_type);

  for (uint16_t i = 0; i < n_fields; ++i) {
    auto field_data = buf.read_bytes();
    if (!field_data) {
      cass_user_type_set_null(udt, i);
      continue;
    }

    const CassDataType *field_type = nullptr;
    if (i < schema_field_count) {
      field_type = cass_data_type_sub_data_type(udt_type, i);
    }

    if (field_type) {
      CassValueType expected = cass_data_type_type(field_type);
      const uint8_t *p = field_data->data();
      size_t sz = field_data->size();

      switch (expected) {
      case CASS_VALUE_TYPE_TINY_INT:
        if (sz == 8) {
          cass_user_type_set_int8(udt, i, static_cast<int8_t>(p[7]));
          continue;
        } else if (sz == 1) {
          cass_user_type_set_int8(udt, i, static_cast<int8_t>(p[0]));
          continue;
        }
        break;
      case CASS_VALUE_TYPE_SMALL_INT:
        if (sz == 8) {
          cass_user_type_set_int16(udt, i,
                                   static_cast<int16_t>((p[6] << 8) | p[7]));
          continue;
        } else if (sz == 2) {
          cass_user_type_set_int16(udt, i,
                                   static_cast<int16_t>((p[0] << 8) | p[1]));
          continue;
        }
        break;
      case CASS_VALUE_TYPE_INT:
        if (sz == 8) {
          int32_t v = (static_cast<int32_t>(p[4]) << 24) |
                      (static_cast<int32_t>(p[5]) << 16) |
                      (static_cast<int32_t>(p[6]) << 8) |
                      static_cast<int32_t>(p[7]);
          cass_user_type_set_int32(udt, i, v);
          continue;
        } else if (sz == 4) {
          int32_t v = (static_cast<int32_t>(p[0]) << 24) |
                      (static_cast<int32_t>(p[1]) << 16) |
                      (static_cast<int32_t>(p[2]) << 8) |
                      static_cast<int32_t>(p[3]);
          cass_user_type_set_int32(udt, i, v);
          continue;
        }
        break;
      case CASS_VALUE_TYPE_BIGINT:
      case CASS_VALUE_TYPE_TIMESTAMP:
      case CASS_VALUE_TYPE_TIME:
      case CASS_VALUE_TYPE_COUNTER:
        if (sz >= 8) {
          int64_t v = read_bigint(p, sz);
          cass_user_type_set_int64(udt, i, v);
          continue;
        }
        break;
      case CASS_VALUE_TYPE_FLOAT:
        if (sz == 8) {
          // Double -> Float coercion
          double d = read_double_be(p, sz);
          cass_user_type_set_float(udt, i, coerce_double_to_float(d));
          continue;
        } else if (sz >= 4) {
          uint32_t bits = (static_cast<uint32_t>(p[0]) << 24) |
                          (static_cast<uint32_t>(p[1]) << 16) |
                          (static_cast<uint32_t>(p[2]) << 8) |
                          static_cast<uint32_t>(p[3]);
          float v;
          std::memcpy(&v, &bits, sizeof(v));
          cass_user_type_set_float(udt, i, v);
          continue;
        }
        break;
      case CASS_VALUE_TYPE_DOUBLE:
        if (sz >= 8) {
          double v = read_double_be(p, sz);
          cass_user_type_set_double(udt, i, v);
          continue;
        }
        break;
      case CASS_VALUE_TYPE_BOOLEAN:
        if (sz >= 1) {
          cass_user_type_set_bool(udt, i, p[0] ? cass_true : cass_false);
          continue;
        }
        break;
      case CASS_VALUE_TYPE_TEXT:
      case CASS_VALUE_TYPE_VARCHAR:
      case CASS_VALUE_TYPE_ASCII:
        cass_user_type_set_string_n(udt, i, reinterpret_cast<const char *>(p),
                                    sz);
        continue;
      case CASS_VALUE_TYPE_UUID:
      case CASS_VALUE_TYPE_TIMEUUID:
        if (sz >= 16) {
          CassUuid uuid;
          std::memcpy(&uuid, p, 16);
          cass_user_type_set_uuid(udt, i, uuid);
          continue;
        }
        break;
      case CASS_VALUE_TYPE_INET:
        if (sz == 4 || sz == 16) {
          CassInet inet;
          inet.address_length = static_cast<cass_uint8_t>(sz);
          std::memcpy(inet.address, p, sz);
          cass_user_type_set_inet(udt, i, inet);
          continue;
        }
        break;
      case CASS_VALUE_TYPE_DATE:
        if (sz >= 4) {
          uint32_t v = (static_cast<uint32_t>(p[0]) << 24) |
                       (static_cast<uint32_t>(p[1]) << 16) |
                       (static_cast<uint32_t>(p[2]) << 8) |
                       static_cast<uint32_t>(p[3]);
          cass_user_type_set_uint32(udt, i, v);
          continue;
        }
        break;
      case CASS_VALUE_TYPE_LIST:
      case CASS_VALUE_TYPE_SET: {
        Buffer reser;
        reserialize_list_or_set(reser, p, sz, field_type);
        cass_user_type_set_bytes(udt, i, reser.data(), reser.size());
        continue;
      }
      case CASS_VALUE_TYPE_MAP: {
        Buffer reser;
        reserialize_map(reser, p, sz, field_type);
        cass_user_type_set_bytes(udt, i, reser.data(), reser.size());
        continue;
      }
      case CASS_VALUE_TYPE_TUPLE: {
        Buffer reser;
        reserialize_tuple(reser, p, sz, field_type);
        cass_user_type_set_bytes(udt, i, reser.data(), reser.size());
        continue;
      }
      case CASS_VALUE_TYPE_UDT: {
        Buffer reser;
        reserialize_udt(reser, p, sz, field_type);
        cass_user_type_set_bytes(udt, i, reser.data(), reser.size());
        continue;
      }
      default:
        break;
      }
    }

    // Fallback: set as raw bytes
    cass_user_type_set_bytes(udt, i, field_data->data(), field_data->size());
  }

  cass_statement_bind_user_type(stmt, index, udt);
  cass_user_type_free(udt);
}

void bind_value_with_schema(CassStatement *stmt, size_t index,
                            const TypedValue &value,
                            const CassPrepared *prepared) {
  if (LATTE_UNLIKELY(!value.data)) {
    cass_statement_bind_null(stmt, index);
    return;
  }

  // Get expected type from prepared statement
  const CassDataType *expected_type =
      cass_prepared_parameter_data_type(prepared, index);

  if (!expected_type) {
    // Fallback to wire type
    bind_value(stmt, index, value);
    return;
  }

  CassValueType expected_cass_type = cass_data_type_type(expected_type);

  // For collection and tuple types, use schema-aware binding
  switch (expected_cass_type) {
  case CASS_VALUE_TYPE_MAP:
    if (value.type == TypeCode::MAP) {
      bind_map_with_schema(stmt, index, *value.data, expected_type);
      return;
    }
    break;
  case CASS_VALUE_TYPE_LIST:
    if (value.type == TypeCode::LIST || value.type == TypeCode::SET) {
      bind_list_or_set_with_schema(stmt, index, *value.data, expected_type,
                                   true);
      return;
    }
    if (value.type == TypeCode::VECTOR) {
      bind_vector_as_collection(stmt, index, *value.data, true);
      return;
    }
    break;
  case CASS_VALUE_TYPE_SET:
    if (value.type == TypeCode::SET || value.type == TypeCode::LIST) {
      bind_list_or_set_with_schema(stmt, index, *value.data, expected_type,
                                   false);
      return;
    }
    if (value.type == TypeCode::VECTOR) {
      bind_vector_as_collection(stmt, index, *value.data, false);
      return;
    }
    break;
  case CASS_VALUE_TYPE_TUPLE:
    if (value.type == TypeCode::TUPLE) {
      bind_tuple_with_schema(stmt, index, *value.data, expected_type);
      return;
    }
    break;
  case CASS_VALUE_TYPE_UDT:
    if (value.type == TypeCode::UDT) {
      bind_udt_with_schema(stmt, index, *value.data, expected_type);
      return;
    }
    break;
  case CASS_VALUE_TYPE_CUSTOM:
    // CUSTOM type includes vector<float, N> columns.
    // cpp-rs-driver does not implement cass_statement_bind_custom(),
    // so vector columns cannot be bound through the C API.
    // Fall through to bind_value() which will attempt bind_bytes()
    // (this will fail type checking for prepared statements).
    break;
  default:
    break;
  }

  // Type coercion for primitive types
  const std::vector<uint8_t> &data = *value.data;
  const uint8_t *ptr = data.data();
  size_t len = data.size();

  // Wire BigInt (i64) -> schema smaller integer types
  if (value.type == TypeCode::BIGINT && len >= 8) {
    int64_t v = read_bigint(ptr, len);
    switch (expected_cass_type) {
    case CASS_VALUE_TYPE_TINY_INT:
      cass_statement_bind_int8(stmt, index, coerce_bigint_to_tinyint(v));
      return;
    case CASS_VALUE_TYPE_SMALL_INT:
      cass_statement_bind_int16(stmt, index, coerce_bigint_to_smallint(v));
      return;
    case CASS_VALUE_TYPE_INT:
      cass_statement_bind_int32(stmt, index, coerce_bigint_to_int(v));
      return;
    case CASS_VALUE_TYPE_BIGINT:
    case CASS_VALUE_TYPE_TIMESTAMP:
    case CASS_VALUE_TYPE_TIME:
    case CASS_VALUE_TYPE_COUNTER:
      cass_statement_bind_int64(stmt, index, v);
      return;
    case CASS_VALUE_TYPE_VARINT: {
      auto varint = bigint_to_varint_bytes(v);
      cass_statement_bind_bytes(stmt, index, varint.data(), varint.size());
      return;
    }
    default:
      break;
    }
  }

  // Wire Double -> schema Float
  if (value.type == TypeCode::DOUBLE && len >= 8 &&
      expected_cass_type == CASS_VALUE_TYPE_FLOAT) {
    double d = read_double_be(ptr, len);
    cass_statement_bind_float(stmt, index, coerce_double_to_float(d));
    return;
  }

  // Wire Text -> schema types that expect parsing
  if (value.type == TypeCode::TEXT) {
    std::string s(reinterpret_cast<const char *>(ptr), len);
    switch (expected_cass_type) {
    case CASS_VALUE_TYPE_DATE: {
      uint32_t date = parse_date_string(s);
      cass_statement_bind_uint32(stmt, index, date);
      return;
    }
    case CASS_VALUE_TYPE_TIME: {
      int64_t time = parse_time_string(s);
      cass_statement_bind_int64(stmt, index, time);
      return;
    }
    case CASS_VALUE_TYPE_DURATION: {
      int32_t months, days;
      int64_t nanos;
      if (parse_duration_string_to_parts(s, months, days, nanos)) {
        cass_statement_bind_duration(stmt, index, months, days, nanos);
        return;
      }
      break;
    }
    case CASS_VALUE_TYPE_INET: {
      CassInet inet;
      struct in_addr addr4;
      struct in6_addr addr6;
      if (inet_pton(AF_INET, s.c_str(), &addr4) == 1) {
        inet.address_length = 4;
        std::memcpy(inet.address, &addr4, 4);
        cass_statement_bind_inet(stmt, index, inet);
        return;
      } else if (inet_pton(AF_INET6, s.c_str(), &addr6) == 1) {
        inet.address_length = 16;
        std::memcpy(inet.address, &addr6, 16);
        cass_statement_bind_inet(stmt, index, inet);
        return;
      }
      break;
    }
    case CASS_VALUE_TYPE_UUID:
    case CASS_VALUE_TYPE_TIMEUUID: {
      CassUuid uuid;
      if (cass_uuid_from_string(s.c_str(), &uuid) == CASS_OK) {
        cass_statement_bind_uuid(stmt, index, uuid);
        return;
      }
      break;
    }
    case CASS_VALUE_TYPE_DECIMAL: {
      auto dec = parse_decimal_string(s);
      cass_statement_bind_decimal(stmt, index, dec.varint.data(),
                                  dec.varint.size(), dec.scale);
      return;
    }
    case CASS_VALUE_TYPE_ASCII:
    case CASS_VALUE_TYPE_TEXT:
    case CASS_VALUE_TYPE_VARCHAR:
      cass_statement_bind_string_n(stmt, index, s.c_str(), s.size());
      return;
    default:
      break;
    }
  }

  // Fallback to wire-type binding
  bind_value(stmt, index, value);
}

TypeCode cass_type_to_type_code(CassValueType type) {
  switch (type) {
  case CASS_VALUE_TYPE_ASCII:
    return TypeCode::ASCII;
  case CASS_VALUE_TYPE_BIGINT:
    return TypeCode::BIGINT;
  case CASS_VALUE_TYPE_BLOB:
    return TypeCode::BLOB;
  case CASS_VALUE_TYPE_BOOLEAN:
    return TypeCode::BOOLEAN;
  case CASS_VALUE_TYPE_COUNTER:
    return TypeCode::COUNTER;
  case CASS_VALUE_TYPE_DECIMAL:
    return TypeCode::DECIMAL;
  case CASS_VALUE_TYPE_DOUBLE:
    return TypeCode::DOUBLE;
  case CASS_VALUE_TYPE_FLOAT:
    return TypeCode::FLOAT;
  case CASS_VALUE_TYPE_INT:
    return TypeCode::INT;
  case CASS_VALUE_TYPE_TEXT:
    return TypeCode::TEXT;
  case CASS_VALUE_TYPE_TIMESTAMP:
    return TypeCode::TIMESTAMP;
  case CASS_VALUE_TYPE_UUID:
    return TypeCode::UUID;
  case CASS_VALUE_TYPE_VARCHAR:
    return TypeCode::TEXT;
  case CASS_VALUE_TYPE_VARINT:
    return TypeCode::VARINT;
  case CASS_VALUE_TYPE_TIMEUUID:
    return TypeCode::TIMEUUID;
  case CASS_VALUE_TYPE_INET:
    return TypeCode::INET;
  case CASS_VALUE_TYPE_DATE:
    return TypeCode::DATE;
  case CASS_VALUE_TYPE_TIME:
    return TypeCode::TIME;
  case CASS_VALUE_TYPE_SMALL_INT:
    return TypeCode::SMALLINT;
  case CASS_VALUE_TYPE_TINY_INT:
    return TypeCode::TINYINT;
  case CASS_VALUE_TYPE_DURATION:
    return TypeCode::DURATION;
  case CASS_VALUE_TYPE_LIST:
    return TypeCode::LIST;
  case CASS_VALUE_TYPE_MAP:
    return TypeCode::MAP;
  case CASS_VALUE_TYPE_SET:
    return TypeCode::SET;
  case CASS_VALUE_TYPE_TUPLE:
    return TypeCode::TUPLE;
  case CASS_VALUE_TYPE_UDT:
    return TypeCode::UDT;
  default:
    return TypeCode::BLOB;
  }
}

void encode_value(Buffer &buf, const CassValue *value, TypeCode type) {
  if (cass_value_is_null(value)) {
    buf.write_int(-1);
    return;
  }

  switch (type) {
  case TypeCode::TINYINT: {
    int8_t v;
    cass_value_get_int8(value, &v);
    buf.write_int(1);
    buf.write_byte(static_cast<uint8_t>(v));
    break;
  }

  case TypeCode::SMALLINT: {
    int16_t v;
    cass_value_get_int16(value, &v);
    buf.write_int(2);
    buf.write_short(static_cast<uint16_t>(v));
    break;
  }

  case TypeCode::INT: {
    int32_t v;
    cass_value_get_int32(value, &v);
    buf.write_int(4);
    buf.write_int(v);
    break;
  }

  case TypeCode::BIGINT:
  case TypeCode::COUNTER:
  case TypeCode::TIMESTAMP:
  case TypeCode::TIME: {
    int64_t v;
    cass_value_get_int64(value, &v);
    buf.write_int(8);
    buf.write_long(static_cast<uint64_t>(v));
    break;
  }

  case TypeCode::FLOAT: {
    float v;
    cass_value_get_float(value, &v);
    uint32_t bits;
    std::memcpy(&bits, &v, sizeof(bits));
    buf.write_int(4);
    buf.write_int(static_cast<int32_t>(bits));
    break;
  }

  case TypeCode::DOUBLE: {
    double v;
    cass_value_get_double(value, &v);
    uint64_t bits;
    std::memcpy(&bits, &v, sizeof(bits));
    buf.write_int(8);
    buf.write_long(bits);
    break;
  }

  case TypeCode::BOOLEAN: {
    cass_bool_t v;
    cass_value_get_bool(value, &v);
    buf.write_int(1);
    buf.write_byte(v ? 1 : 0);
    break;
  }

  case TypeCode::ASCII:
  case TypeCode::TEXT: {
    const char *str;
    size_t str_len;
    cass_value_get_string(value, &str, &str_len);
    buf.write_int(static_cast<int32_t>(str_len));
    buf.append(reinterpret_cast<const uint8_t *>(str), str_len);
    break;
  }

  case TypeCode::BLOB:
  case TypeCode::VARINT: {
    const cass_byte_t *bytes;
    size_t bytes_len;
    cass_value_get_bytes(value, &bytes, &bytes_len);
    buf.write_int(static_cast<int32_t>(bytes_len));
    buf.append(bytes, bytes_len);
    break;
  }

  case TypeCode::UUID:
  case TypeCode::TIMEUUID: {
    CassUuid uuid;
    cass_value_get_uuid(value, &uuid);
    buf.write_int(16);
    buf.append(reinterpret_cast<const uint8_t *>(&uuid), 16);
    break;
  }

  case TypeCode::INET: {
    CassInet inet;
    cass_value_get_inet(value, &inet);
    buf.write_int(static_cast<int32_t>(inet.address_length));
    buf.append(inet.address, inet.address_length);
    break;
  }

  case TypeCode::DATE: {
    uint32_t v;
    cass_value_get_uint32(value, &v);
    buf.write_int(4);
    buf.write_int(static_cast<int32_t>(v));
    break;
  }

  case TypeCode::DECIMAL: {
    const cass_byte_t *varint;
    size_t varint_len;
    int32_t scale;
    cass_value_get_decimal(value, &varint, &varint_len, &scale);
    buf.write_int(static_cast<int32_t>(4 + varint_len));
    buf.write_int(scale);
    buf.append(varint, varint_len);
    break;
  }

  case TypeCode::DURATION: {
    // Duration is stored as bytes
    const cass_byte_t *bytes;
    size_t bytes_len;
    cass_value_get_bytes(value, &bytes, &bytes_len);
    buf.write_int(static_cast<int32_t>(bytes_len));
    buf.append(bytes, bytes_len);
    break;
  }

  case TypeCode::LIST:
  case TypeCode::SET: {
    // Response format: [total_size: i32][n_elements: i32][elements...]
    // Each element is [length: i32][data]
    // Note: NO element type code - that's only for request format
    CassIterator *iter = cass_iterator_from_collection(value);
    std::vector<Buffer> elements;

    while (cass_iterator_next(iter)) {
      const CassValue *elem = cass_iterator_get_value(iter);
      CassValueType elem_type = cass_value_type(elem);
      TypeCode elem_code = cass_type_to_type_code(elem_type);

      Buffer elem_buf;
      encode_value(elem_buf, elem, elem_code);
      elements.push_back(std::move(elem_buf));
    }
    cass_iterator_free(iter);

    // Encode collection - response format has only count + elements
    Buffer col_buf;
    col_buf.write_int(static_cast<int32_t>(elements.size()));
    for (const auto &elem : elements) {
      col_buf.append(elem);
    }

    buf.write_int(static_cast<int32_t>(col_buf.size()));
    buf.append(col_buf);
    break;
  }

  case TypeCode::MAP: {
    // Response format: [total_size: i32][n_entries: i32][entries...]
    // Each entry is [key_len: i32][key_data][val_len: i32][val_data]
    // Note: NO key/value type codes - that's only for request format
    CassIterator *iter = cass_iterator_from_map(value);
    std::vector<std::pair<Buffer, Buffer>> entries;

    while (cass_iterator_next(iter)) {
      const CassValue *key = cass_iterator_get_map_key(iter);
      const CassValue *val = cass_iterator_get_map_value(iter);

      CassValueType key_type = cass_value_type(key);
      CassValueType val_type = cass_value_type(val);

      Buffer key_buf, val_buf;
      encode_value(key_buf, key, cass_type_to_type_code(key_type));
      encode_value(val_buf, val, cass_type_to_type_code(val_type));
      entries.emplace_back(std::move(key_buf), std::move(val_buf));
    }
    cass_iterator_free(iter);

    // Encode map - response format has only count + entries
    Buffer map_buf;
    map_buf.write_int(static_cast<int32_t>(entries.size()));
    for (const auto &[k, v] : entries) {
      map_buf.append(k);
      map_buf.append(v);
    }

    buf.write_int(static_cast<int32_t>(map_buf.size()));
    buf.append(map_buf);
    break;
  }

  case TypeCode::TUPLE: {
    // Response format: [total_size: i32][elements...]
    // Each element is [length: i32][data] or [-1] for null
    // Note: NO element count or type codes - that's only for request format
    CassIterator *iter = cass_iterator_from_tuple(value);
    std::vector<Buffer> elements;

    while (cass_iterator_next(iter)) {
      const CassValue *elem = cass_iterator_get_value(iter);
      CassValueType elem_cass_type = cass_value_type(elem);
      TypeCode elem_code = cass_type_to_type_code(elem_cass_type);

      Buffer elem_buf;
      encode_value(elem_buf, elem, elem_code);
      elements.push_back(std::move(elem_buf));
    }
    cass_iterator_free(iter);

    // Encode tuple - response format has only elements
    Buffer tuple_buf;
    for (const auto &elem_buf : elements) {
      tuple_buf.append(elem_buf);
    }

    buf.write_int(static_cast<int32_t>(tuple_buf.size()));
    buf.append(tuple_buf);
    break;
  }

  case TypeCode::UDT: {
    // Response format: [total_size: i32][field_values...]
    // Each field value is [length: i32][data] or [-1] for null
    // Note: NO field count or metadata - that's only for request format
    CassIterator *iter = cass_iterator_fields_from_user_type(value);
    if (!iter) {
      // Fallback: encode as bytes
      const cass_byte_t *bytes;
      size_t bytes_len;
      if (cass_value_get_bytes(value, &bytes, &bytes_len) == CASS_OK) {
        buf.write_int(static_cast<int32_t>(bytes_len));
        buf.append(bytes, bytes_len);
      } else {
        buf.write_int(-1);
      }
      break;
    }

    // Collect field values
    std::vector<Buffer> field_values;
    while (cass_iterator_next(iter)) {
      const CassValue *field_value = cass_iterator_get_user_type_field_value(iter);
      CassValueType field_cass_type = cass_value_type(field_value);
      TypeCode field_code = cass_type_to_type_code(field_cass_type);

      Buffer field_buf;
      encode_value(field_buf, field_value, field_code);
      field_values.push_back(std::move(field_buf));
    }
    cass_iterator_free(iter);

    // Encode UDT - response format has only field values
    Buffer udt_buf;
    for (const auto &field_buf : field_values) {
      udt_buf.append(field_buf);
    }

    buf.write_int(static_cast<int32_t>(udt_buf.size()));
    buf.append(udt_buf);
    break;
  }

  default: {
    // Fallback: encode as bytes
    const cass_byte_t *bytes;
    size_t bytes_len;
    if (cass_value_get_bytes(value, &bytes, &bytes_len) == CASS_OK) {
      buf.write_int(static_cast<int32_t>(bytes_len));
      buf.append(bytes, bytes_len);
    } else {
      buf.write_int(-1);
    }
    break;
  }
  }
}

void encode_column_metadata(Buffer &buf, const std::string &keyspace,
                            const std::string &table,
                            const std::string &column_name, TypeCode type) {
  buf.write_string(keyspace);
  buf.write_string(table);
  buf.write_string(column_name);
  buf.write_short(static_cast<uint16_t>(type));
}

Buffer encode_rows_result(const CassResult *result, uint64_t latency_ns) {
  Buffer body;

  size_t column_count = cass_result_column_count(result);
  size_t row_count = cass_result_row_count(result);

  // Estimate buffer size: header + metadata + rows
  // Header: 4 (kind) + 4 (flags) + 4 (col count) + 4 (row count) + 8 (latency)
  // Metadata: ~32 bytes per column (name + type)
  // Rows: estimate 64 bytes per cell on average
  size_t estimated_size =
      24 + (column_count * 32) + (row_count * column_count * 64) + 8;
  body.reserve(estimated_size);

  body.write_int(static_cast<int32_t>(ResultKind::ROWS));

  // Flags
  body.write_int(0);
  // Column count
  body.write_int(static_cast<int32_t>(column_count));

  // Column metadata
  for (size_t i = 0; i < column_count; ++i) {
    const char *name;
    size_t name_len;
    cass_result_column_name(result, i, &name, &name_len);

    CassValueType col_type = cass_result_column_type(result, i);
    TypeCode type_code;

    // Detect vector<float, N> columns (reported as CUSTOM by cpp-rs-driver)
    if (col_type == CASS_VALUE_TYPE_CUSTOM) {
      const CassDataType *dt = cass_result_column_data_type(result, i);
      if (is_vector_custom_type(dt)) {
        type_code = TypeCode::VECTOR;
      } else {
        type_code = cass_type_to_type_code(col_type);
      }
    } else {
      type_code = cass_type_to_type_code(col_type);
    }

    encode_column_metadata(body, "", "", std::string(name, name_len),
                           type_code);
  }

  // Row count
  body.write_int(static_cast<int32_t>(row_count));

  // Rows
  CassIterator *rows = cass_iterator_from_result(result);
  while (cass_iterator_next(rows)) {
    const CassRow *row = cass_iterator_get_row(rows);
    for (size_t i = 0; i < column_count; ++i) {
      const CassValue *value = cass_row_get_column(row, i);
      CassValueType col_type = cass_result_column_type(result, i);
      TypeCode type_code = cass_type_to_type_code(col_type);
      encode_value(body, value, type_code);
    }
  }
  cass_iterator_free(rows);

  // Append latency
  body.write_long(latency_ns);

  return body;
}

} // namespace latte

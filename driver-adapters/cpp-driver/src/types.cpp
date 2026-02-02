#include "types.h"

#include <arpa/inet.h>
#include <cmath>
#include <cstring>
#include <limits>
#include <sstream>
#include <stdexcept>
#include <unordered_map>

namespace latte {

// Type coercion helpers

// Coerce bigint to smaller integer types with bounds checking
[[maybe_unused]] static int64_t read_bigint(const uint8_t *ptr, size_t len) {
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

[[maybe_unused]] static int32_t coerce_bigint_to_int(int64_t v) {
  if (v > std::numeric_limits<int32_t>::max()) {
    return std::numeric_limits<int32_t>::max();
  }
  if (v < std::numeric_limits<int32_t>::min()) {
    return std::numeric_limits<int32_t>::min();
  }
  return static_cast<int32_t>(v);
}

[[maybe_unused]] static int16_t coerce_bigint_to_smallint(int64_t v) {
  if (v > std::numeric_limits<int16_t>::max()) {
    return std::numeric_limits<int16_t>::max();
  }
  if (v < std::numeric_limits<int16_t>::min()) {
    return std::numeric_limits<int16_t>::min();
  }
  return static_cast<int16_t>(v);
}

[[maybe_unused]] static int8_t coerce_bigint_to_tinyint(int64_t v) {
  if (v > std::numeric_limits<int8_t>::max()) {
    return std::numeric_limits<int8_t>::max();
  }
  if (v < std::numeric_limits<int8_t>::min()) {
    return std::numeric_limits<int8_t>::min();
  }
  return static_cast<int8_t>(v);
}

// Coerce double to float
[[maybe_unused]] static float coerce_double_to_float(double v) {
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
[[maybe_unused]] static uint32_t parse_date_string(const std::string &s) {
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
[[maybe_unused]] static int64_t parse_time_string(const std::string &s) {
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

TypedValue read_typed_value(Buffer &buf) {
  TypedValue value;
  value.type = static_cast<TypeCode>(buf.read_short());
  value.data = buf.read_bytes();
  return value;
}

// Forward declaration
static std::vector<uint8_t> coerce_value_bytes(const uint8_t *vp, size_t vlen,
                                                CassValueType expected_type,
                                                const CassDataType *expected_data_type);

// Re-serialize a nested collection (LIST/SET) with type coercion
// Wire format: [subtype: 2][n_elements: 4][elements...]
// CQL format:  [n_elements: 4][elements...]
static std::vector<uint8_t> reserialize_list_or_set(const uint8_t *vp, size_t vlen,
                                                    const CassDataType *expected_data_type) {
  if (vlen < 6) return std::vector<uint8_t>(vp, vp + vlen);

  // Get the expected element type from schema
  const CassDataType *elem_dt = cass_data_type_sub_data_type(expected_data_type, 0);
  CassValueType expected_elem_type = elem_dt ? cass_data_type_type(elem_dt) : CASS_VALUE_TYPE_UNKNOWN;

  // Skip subtype (2 bytes)
  size_t pos = 2;

  // Read n_elements
  int32_t n_elements = (static_cast<int32_t>(vp[pos]) << 24) |
                       (static_cast<int32_t>(vp[pos+1]) << 16) |
                       (static_cast<int32_t>(vp[pos+2]) << 8) |
                       static_cast<int32_t>(vp[pos+3]);
  pos += 4;

  // Build output buffer with CQL format
  std::vector<uint8_t> out;

  // Write n_elements
  out.push_back(static_cast<uint8_t>((n_elements >> 24) & 0xFF));
  out.push_back(static_cast<uint8_t>((n_elements >> 16) & 0xFF));
  out.push_back(static_cast<uint8_t>((n_elements >> 8) & 0xFF));
  out.push_back(static_cast<uint8_t>(n_elements & 0xFF));

  // Process each element
  for (int32_t i = 0; i < n_elements && pos + 4 <= vlen; ++i) {
    // Read element length
    int32_t elem_len = (static_cast<int32_t>(vp[pos]) << 24) |
                       (static_cast<int32_t>(vp[pos+1]) << 16) |
                       (static_cast<int32_t>(vp[pos+2]) << 8) |
                       static_cast<int32_t>(vp[pos+3]);
    pos += 4;

    if (elem_len < 0) {
      // NULL value - write -1 length
      out.push_back(0xFF); out.push_back(0xFF);
      out.push_back(0xFF); out.push_back(0xFF);
      continue;
    }

    if (pos + static_cast<size_t>(elem_len) > vlen) break;

    // Coerce the element value
    std::vector<uint8_t> coerced = coerce_value_bytes(vp + pos, elem_len, expected_elem_type, elem_dt);
    pos += elem_len;

    // Write coerced element length and data
    int32_t coerced_len = static_cast<int32_t>(coerced.size());
    out.push_back(static_cast<uint8_t>((coerced_len >> 24) & 0xFF));
    out.push_back(static_cast<uint8_t>((coerced_len >> 16) & 0xFF));
    out.push_back(static_cast<uint8_t>((coerced_len >> 8) & 0xFF));
    out.push_back(static_cast<uint8_t>(coerced_len & 0xFF));
    out.insert(out.end(), coerced.begin(), coerced.end());
  }

  return out;
}

// Re-serialize a nested MAP with type coercion
// Wire format: [key_type: 2][value_type: 2][n_entries: 4][entries...]
// CQL format:  [n_entries: 4][entries...]
static std::vector<uint8_t> reserialize_map(const uint8_t *vp, size_t vlen,
                                             const CassDataType *expected_data_type) {
  if (vlen < 8) return std::vector<uint8_t>(vp, vp + vlen);

  // Get expected key/value types from schema
  const CassDataType *key_dt = cass_data_type_sub_data_type(expected_data_type, 0);
  const CassDataType *val_dt = cass_data_type_sub_data_type(expected_data_type, 1);
  CassValueType expected_key_type = key_dt ? cass_data_type_type(key_dt) : CASS_VALUE_TYPE_UNKNOWN;
  CassValueType expected_val_type = val_dt ? cass_data_type_type(val_dt) : CASS_VALUE_TYPE_UNKNOWN;

  // Skip key_type (2) and value_type (2)
  size_t pos = 4;

  // Read n_entries
  int32_t n_entries = (static_cast<int32_t>(vp[pos]) << 24) |
                      (static_cast<int32_t>(vp[pos+1]) << 16) |
                      (static_cast<int32_t>(vp[pos+2]) << 8) |
                      static_cast<int32_t>(vp[pos+3]);
  pos += 4;

  // Build output buffer with CQL format
  std::vector<uint8_t> out;

  // Write n_entries
  out.push_back(static_cast<uint8_t>((n_entries >> 24) & 0xFF));
  out.push_back(static_cast<uint8_t>((n_entries >> 16) & 0xFF));
  out.push_back(static_cast<uint8_t>((n_entries >> 8) & 0xFF));
  out.push_back(static_cast<uint8_t>(n_entries & 0xFF));

  // Process each entry (key + value)
  for (int32_t i = 0; i < n_entries && pos + 4 <= vlen; ++i) {
    // Read key length
    int32_t key_len = (static_cast<int32_t>(vp[pos]) << 24) |
                      (static_cast<int32_t>(vp[pos+1]) << 16) |
                      (static_cast<int32_t>(vp[pos+2]) << 8) |
                      static_cast<int32_t>(vp[pos+3]);
    pos += 4;

    if (key_len < 0) {
      // NULL key - write -1 length
      out.push_back(0xFF); out.push_back(0xFF);
      out.push_back(0xFF); out.push_back(0xFF);
    } else if (pos + static_cast<size_t>(key_len) <= vlen) {
      // Coerce key
      std::vector<uint8_t> coerced_key = coerce_value_bytes(vp + pos, key_len, expected_key_type, key_dt);
      pos += key_len;

      int32_t coerced_len = static_cast<int32_t>(coerced_key.size());
      out.push_back(static_cast<uint8_t>((coerced_len >> 24) & 0xFF));
      out.push_back(static_cast<uint8_t>((coerced_len >> 16) & 0xFF));
      out.push_back(static_cast<uint8_t>((coerced_len >> 8) & 0xFF));
      out.push_back(static_cast<uint8_t>(coerced_len & 0xFF));
      out.insert(out.end(), coerced_key.begin(), coerced_key.end());
    }

    if (pos + 4 > vlen) break;

    // Read value length
    int32_t val_len = (static_cast<int32_t>(vp[pos]) << 24) |
                      (static_cast<int32_t>(vp[pos+1]) << 16) |
                      (static_cast<int32_t>(vp[pos+2]) << 8) |
                      static_cast<int32_t>(vp[pos+3]);
    pos += 4;

    if (val_len < 0) {
      // NULL value - write -1 length
      out.push_back(0xFF); out.push_back(0xFF);
      out.push_back(0xFF); out.push_back(0xFF);
    } else if (pos + static_cast<size_t>(val_len) <= vlen) {
      // Coerce value
      std::vector<uint8_t> coerced_val = coerce_value_bytes(vp + pos, val_len, expected_val_type, val_dt);
      pos += val_len;

      int32_t coerced_len = static_cast<int32_t>(coerced_val.size());
      out.push_back(static_cast<uint8_t>((coerced_len >> 24) & 0xFF));
      out.push_back(static_cast<uint8_t>((coerced_len >> 16) & 0xFF));
      out.push_back(static_cast<uint8_t>((coerced_len >> 8) & 0xFF));
      out.push_back(static_cast<uint8_t>(coerced_len & 0xFF));
      out.insert(out.end(), coerced_val.begin(), coerced_val.end());
    }
  }

  return out;
}

// Map TypeCode to CassValueType for fallback when schema lookup fails
static CassValueType type_code_to_cass_type(uint16_t type_code) {
  switch (static_cast<TypeCode>(type_code)) {
  case TypeCode::ASCII: return CASS_VALUE_TYPE_ASCII;
  case TypeCode::BIGINT: return CASS_VALUE_TYPE_BIGINT;
  case TypeCode::BLOB: return CASS_VALUE_TYPE_BLOB;
  case TypeCode::BOOLEAN: return CASS_VALUE_TYPE_BOOLEAN;
  case TypeCode::COUNTER: return CASS_VALUE_TYPE_COUNTER;
  case TypeCode::DECIMAL: return CASS_VALUE_TYPE_DECIMAL;
  case TypeCode::DOUBLE: return CASS_VALUE_TYPE_DOUBLE;
  case TypeCode::FLOAT: return CASS_VALUE_TYPE_FLOAT;
  case TypeCode::INT: return CASS_VALUE_TYPE_INT;
  case TypeCode::TEXT: return CASS_VALUE_TYPE_TEXT;
  case TypeCode::TIMESTAMP: return CASS_VALUE_TYPE_TIMESTAMP;
  case TypeCode::UUID: return CASS_VALUE_TYPE_UUID;
  case TypeCode::TIMEUUID: return CASS_VALUE_TYPE_TIMEUUID;
  case TypeCode::INET: return CASS_VALUE_TYPE_INET;
  case TypeCode::DATE: return CASS_VALUE_TYPE_DATE;
  case TypeCode::TIME: return CASS_VALUE_TYPE_TIME;
  case TypeCode::SMALLINT: return CASS_VALUE_TYPE_SMALL_INT;
  case TypeCode::TINYINT: return CASS_VALUE_TYPE_TINY_INT;
  case TypeCode::DURATION: return CASS_VALUE_TYPE_DURATION;
  case TypeCode::LIST: return CASS_VALUE_TYPE_LIST;
  case TypeCode::SET: return CASS_VALUE_TYPE_SET;
  case TypeCode::MAP: return CASS_VALUE_TYPE_MAP;
  case TypeCode::TUPLE: return CASS_VALUE_TYPE_TUPLE;
  case TypeCode::UDT: return CASS_VALUE_TYPE_UDT;
  case TypeCode::VARINT: return CASS_VALUE_TYPE_VARINT;
  default: return CASS_VALUE_TYPE_UNKNOWN;
  }
}

// Re-serialize a nested UDT, stripping field metadata, coercing field values,
// and reordering fields to match schema order.
// Wire format: [n_fields: 2][for each field: [name_len: 2][name][field_type: 2]][field_values...]
// CQL format:  [field_values...] where each field is [len: 4][data] or [-1] for null
// IMPORTANT: Fields must be output in schema definition order, not wire order!
static std::vector<uint8_t> reserialize_udt(const uint8_t *vp, size_t vlen,
                                             const CassDataType *expected_data_type = nullptr) {
  if (vlen < 2) return std::vector<uint8_t>(vp, vp + vlen);

  size_t pos = 0;

  // Read n_fields from wire format
  uint16_t n_fields = (static_cast<uint16_t>(vp[pos]) << 8) |
                      static_cast<uint16_t>(vp[pos+1]);
  pos += 2;

  // Parse field metadata and store field names, wire types, and values for lookup
  struct WireField {
    std::string name;
    uint16_t wire_type;
    std::vector<uint8_t> data;  // empty vector if NULL
    bool is_null;
  };
  std::vector<WireField> wire_fields;

  // First pass: read all field metadata
  for (uint16_t i = 0; i < n_fields; ++i) {
    WireField field;
    field.is_null = false;

    // Check bounds for name length
    if (pos + 2 > vlen) break;

    // Read field name length
    uint16_t name_len = (static_cast<uint16_t>(vp[pos]) << 8) |
                        static_cast<uint16_t>(vp[pos+1]);
    pos += 2;

    // Check bounds for field name
    if (pos + name_len > vlen) break;

    // Read field name
    field.name = std::string(reinterpret_cast<const char*>(vp + pos), name_len);
    pos += name_len;

    // Check bounds for field type
    if (pos + 2 > vlen) break;

    // Read field type code from wire format
    field.wire_type = (static_cast<uint16_t>(vp[pos]) << 8) |
                      static_cast<uint16_t>(vp[pos+1]);
    pos += 2;

    wire_fields.push_back(std::move(field));
  }

  // Second pass: read all field values
  for (size_t i = 0; i < wire_fields.size() && pos + 4 <= vlen; ++i) {
    // Read field value length
    int32_t field_len = (static_cast<int32_t>(vp[pos]) << 24) |
                        (static_cast<int32_t>(vp[pos+1]) << 16) |
                        (static_cast<int32_t>(vp[pos+2]) << 8) |
                        static_cast<int32_t>(vp[pos+3]);
    pos += 4;

    if (field_len < 0) {
      // NULL field
      wire_fields[i].is_null = true;
      continue;
    }

    // Check bounds for field data
    if (pos + static_cast<size_t>(field_len) > vlen) break;

    // Store field data
    wire_fields[i].data.assign(vp + pos, vp + pos + field_len);
    pos += field_len;
  }

  // Build a map from field name to wire field for fast lookup
  std::unordered_map<std::string, size_t> name_to_wire_idx;
  for (size_t i = 0; i < wire_fields.size(); ++i) {
    name_to_wire_idx[wire_fields[i].name] = i;
  }

  // Now output fields in schema order if we have schema info
  std::vector<uint8_t> out;

  if (expected_data_type) {
    // Get the number of fields from schema
    size_t schema_field_count = cass_data_type_sub_type_count(expected_data_type);

    for (size_t schema_idx = 0; schema_idx < schema_field_count; ++schema_idx) {
      // Get field name from schema
      const char *schema_field_name = nullptr;
      size_t schema_field_name_len = 0;
      cass_data_type_sub_type_name(expected_data_type, schema_idx,
                                    &schema_field_name, &schema_field_name_len);

      std::string field_name(schema_field_name, schema_field_name_len);

      // Find this field in wire data
      auto it = name_to_wire_idx.find(field_name);
      if (it == name_to_wire_idx.end()) {
        // Field not present in wire data - write NULL
        out.push_back(0xFF); out.push_back(0xFF);
        out.push_back(0xFF); out.push_back(0xFF);
        continue;
      }

      const WireField &wire_field = wire_fields[it->second];

      if (wire_field.is_null) {
        // NULL field - write -1 length
        out.push_back(0xFF); out.push_back(0xFF);
        out.push_back(0xFF); out.push_back(0xFF);
        continue;
      }

      // Get expected type from schema
      const CassDataType *field_dt = cass_data_type_sub_data_type(expected_data_type, schema_idx);
      CassValueType expected_field_type = field_dt ? cass_data_type_type(field_dt) : CASS_VALUE_TYPE_UNKNOWN;

      // Fall back to wire type if schema lookup failed
      if (expected_field_type == CASS_VALUE_TYPE_UNKNOWN) {
        expected_field_type = type_code_to_cass_type(wire_field.wire_type);
      }

      // Coerce the field value
      std::vector<uint8_t> coerced;
      if (expected_field_type != CASS_VALUE_TYPE_UNKNOWN) {
        coerced = coerce_value_bytes(wire_field.data.data(), wire_field.data.size(),
                                      expected_field_type, field_dt);
      } else {
        // No type info at all - use value as-is
        coerced = wire_field.data;
      }

      // Write coerced field length and data
      int32_t coerced_len = static_cast<int32_t>(coerced.size());
      out.push_back(static_cast<uint8_t>((coerced_len >> 24) & 0xFF));
      out.push_back(static_cast<uint8_t>((coerced_len >> 16) & 0xFF));
      out.push_back(static_cast<uint8_t>((coerced_len >> 8) & 0xFF));
      out.push_back(static_cast<uint8_t>(coerced_len & 0xFF));
      out.insert(out.end(), coerced.begin(), coerced.end());
    }
  } else {
    // No schema info - output in wire order (best effort)
    for (const auto &wire_field : wire_fields) {
      if (wire_field.is_null) {
        // NULL field - write -1 length
        out.push_back(0xFF); out.push_back(0xFF);
        out.push_back(0xFF); out.push_back(0xFF);
        continue;
      }

      // Use wire type for coercion
      CassValueType expected_field_type = type_code_to_cass_type(wire_field.wire_type);

      std::vector<uint8_t> coerced;
      if (expected_field_type != CASS_VALUE_TYPE_UNKNOWN) {
        coerced = coerce_value_bytes(wire_field.data.data(), wire_field.data.size(),
                                      expected_field_type, nullptr);
      } else {
        coerced = wire_field.data;
      }

      // Write field length and data
      int32_t coerced_len = static_cast<int32_t>(coerced.size());
      out.push_back(static_cast<uint8_t>((coerced_len >> 24) & 0xFF));
      out.push_back(static_cast<uint8_t>((coerced_len >> 16) & 0xFF));
      out.push_back(static_cast<uint8_t>((coerced_len >> 8) & 0xFF));
      out.push_back(static_cast<uint8_t>(coerced_len & 0xFF));
      out.insert(out.end(), coerced.begin(), coerced.end());
    }
  }

  return out;
}

// Re-serialize a nested TUPLE, stripping field type metadata and coercing values
// Wire format: [n_elements: 2][for each element: [type_code: 2]][element_values...]
// CQL format:  [element_values...] where each element is [len: 4][data] or [-1] for null
static std::vector<uint8_t> reserialize_tuple(const uint8_t *vp, size_t vlen,
                                               const CassDataType *expected_data_type = nullptr) {
  if (vlen < 2) return std::vector<uint8_t>(vp, vp + vlen);

  size_t pos = 0;

  // Read n_elements
  uint16_t n_elements = (static_cast<uint16_t>(vp[pos]) << 8) |
                        static_cast<uint16_t>(vp[pos+1]);
  pos += 2;

  // Read element type codes
  std::vector<uint16_t> wire_types;
  for (uint16_t i = 0; i < n_elements && pos + 2 <= vlen; ++i) {
    uint16_t wire_type = (static_cast<uint16_t>(vp[pos]) << 8) |
                         static_cast<uint16_t>(vp[pos+1]);
    wire_types.push_back(wire_type);
    pos += 2;
  }

  // Read and coerce element values
  std::vector<uint8_t> out;

  for (uint16_t i = 0; i < n_elements && pos + 4 <= vlen; ++i) {
    // Read element length
    int32_t elem_len = (static_cast<int32_t>(vp[pos]) << 24) |
                       (static_cast<int32_t>(vp[pos+1]) << 16) |
                       (static_cast<int32_t>(vp[pos+2]) << 8) |
                       static_cast<int32_t>(vp[pos+3]);
    pos += 4;

    if (elem_len < 0) {
      // NULL element - write -1 length
      out.push_back(0xFF); out.push_back(0xFF);
      out.push_back(0xFF); out.push_back(0xFF);
      continue;
    }

    if (pos + static_cast<size_t>(elem_len) > vlen) break;

    // Get expected type from schema if available
    CassValueType expected_elem_type = CASS_VALUE_TYPE_UNKNOWN;
    const CassDataType *elem_dt = nullptr;
    if (expected_data_type) {
      elem_dt = cass_data_type_sub_data_type(expected_data_type, i);
      if (elem_dt) {
        expected_elem_type = cass_data_type_type(elem_dt);
      }
    }

    // Fall back to wire type if schema lookup failed
    if (expected_elem_type == CASS_VALUE_TYPE_UNKNOWN && i < wire_types.size()) {
      expected_elem_type = type_code_to_cass_type(wire_types[i]);
    }

    // Coerce the element value
    std::vector<uint8_t> coerced;
    if (expected_elem_type != CASS_VALUE_TYPE_UNKNOWN) {
      coerced = coerce_value_bytes(vp + pos, elem_len, expected_elem_type, elem_dt);
    } else {
      coerced.assign(vp + pos, vp + pos + elem_len);
    }
    pos += elem_len;

    // Write coerced element length and data
    int32_t coerced_len = static_cast<int32_t>(coerced.size());
    out.push_back(static_cast<uint8_t>((coerced_len >> 24) & 0xFF));
    out.push_back(static_cast<uint8_t>((coerced_len >> 16) & 0xFF));
    out.push_back(static_cast<uint8_t>((coerced_len >> 8) & 0xFF));
    out.push_back(static_cast<uint8_t>(coerced_len & 0xFF));
    out.insert(out.end(), coerced.begin(), coerced.end());
  }

  return out;
}

// Coerce a raw value to the expected type, returning the coerced bytes
static std::vector<uint8_t> coerce_value_bytes(const uint8_t *vp, size_t vlen,
                                                CassValueType expected_type,
                                                const CassDataType *expected_data_type) {
  std::vector<uint8_t> out;

  switch (expected_type) {
  case CASS_VALUE_TYPE_TINY_INT:
    if (vlen == 1) {
      out.push_back(vp[0]);
    } else if (vlen == 8) {
      int64_t v64 = read_bigint(vp, vlen);
      out.push_back(static_cast<uint8_t>(coerce_bigint_to_tinyint(v64)));
    }
    break;
  case CASS_VALUE_TYPE_SMALL_INT:
    if (vlen == 2) {
      out.push_back(vp[0]);
      out.push_back(vp[1]);
    } else if (vlen == 8) {
      int64_t v64 = read_bigint(vp, vlen);
      int16_t v16 = coerce_bigint_to_smallint(v64);
      out.push_back(static_cast<uint8_t>((v16 >> 8) & 0xFF));
      out.push_back(static_cast<uint8_t>(v16 & 0xFF));
    }
    break;
  case CASS_VALUE_TYPE_INT:
    if (vlen == 4) {
      out.insert(out.end(), vp, vp + 4);
    } else if (vlen == 8) {
      int64_t v64 = read_bigint(vp, vlen);
      int32_t v32 = coerce_bigint_to_int(v64);
      out.push_back(static_cast<uint8_t>((v32 >> 24) & 0xFF));
      out.push_back(static_cast<uint8_t>((v32 >> 16) & 0xFF));
      out.push_back(static_cast<uint8_t>((v32 >> 8) & 0xFF));
      out.push_back(static_cast<uint8_t>(v32 & 0xFF));
    }
    break;
  case CASS_VALUE_TYPE_BIGINT:
  case CASS_VALUE_TYPE_COUNTER:
  case CASS_VALUE_TYPE_TIMESTAMP:
  case CASS_VALUE_TYPE_TIME:
    if (vlen == 8) {
      out.insert(out.end(), vp, vp + 8);
    } else if (vlen == 4) {
      // Promote int32 to int64
      int32_t v32 = (static_cast<int32_t>(vp[0]) << 24) |
                    (static_cast<int32_t>(vp[1]) << 16) |
                    (static_cast<int32_t>(vp[2]) << 8) |
                    static_cast<int32_t>(vp[3]);
      int64_t v64 = v32;
      out.push_back(static_cast<uint8_t>((v64 >> 56) & 0xFF));
      out.push_back(static_cast<uint8_t>((v64 >> 48) & 0xFF));
      out.push_back(static_cast<uint8_t>((v64 >> 40) & 0xFF));
      out.push_back(static_cast<uint8_t>((v64 >> 32) & 0xFF));
      out.push_back(static_cast<uint8_t>((v64 >> 24) & 0xFF));
      out.push_back(static_cast<uint8_t>((v64 >> 16) & 0xFF));
      out.push_back(static_cast<uint8_t>((v64 >> 8) & 0xFF));
      out.push_back(static_cast<uint8_t>(v64 & 0xFF));
    }
    break;
  case CASS_VALUE_TYPE_FLOAT:
    if (vlen == 4) {
      out.insert(out.end(), vp, vp + 4);
    } else if (vlen == 8) {
      // Double to float coercion
      uint64_t bits = read_bigint(vp, vlen);
      double d;
      std::memcpy(&d, &bits, sizeof(d));
      float f = coerce_double_to_float(d);
      uint32_t fbits;
      std::memcpy(&fbits, &f, sizeof(fbits));
      out.push_back(static_cast<uint8_t>((fbits >> 24) & 0xFF));
      out.push_back(static_cast<uint8_t>((fbits >> 16) & 0xFF));
      out.push_back(static_cast<uint8_t>((fbits >> 8) & 0xFF));
      out.push_back(static_cast<uint8_t>(fbits & 0xFF));
    }
    break;
  case CASS_VALUE_TYPE_DOUBLE:
    if (vlen == 8) {
      out.insert(out.end(), vp, vp + 8);
    }
    break;
  case CASS_VALUE_TYPE_LIST:
  case CASS_VALUE_TYPE_SET:
    // Recursively re-serialize nested list/set
    if (expected_data_type) {
      return reserialize_list_or_set(vp, vlen, expected_data_type);
    } else {
      // No schema info - just strip subtype prefix
      if (vlen > 2) {
        out.insert(out.end(), vp + 2, vp + vlen);
      }
    }
    break;
  case CASS_VALUE_TYPE_MAP:
    // Recursively re-serialize nested map
    if (expected_data_type) {
      return reserialize_map(vp, vlen, expected_data_type);
    } else {
      // No schema info - just strip type prefixes
      if (vlen > 4) {
        out.insert(out.end(), vp + 4, vp + vlen);
      }
    }
    break;
  case CASS_VALUE_TYPE_UDT:
    // Re-serialize nested UDT, stripping field metadata and coercing fields
    return reserialize_udt(vp, vlen, expected_data_type);
  case CASS_VALUE_TYPE_TUPLE:
    // Re-serialize nested tuple, stripping type codes and coercing values
    return reserialize_tuple(vp, vlen, expected_data_type);
  default:
    // For other types, return as-is
    out.insert(out.end(), vp, vp + vlen);
    break;
  }

  if (out.empty() && vlen > 0) {
    // Fallback - return original bytes
    out.insert(out.end(), vp, vp + vlen);
  }

  return out;
}

// Helper to append a value to a collection with coercion to expected type
// This version takes the full CassDataType for recursive coercion of nested collections
static void append_to_collection_coerced(CassCollection *collection,
                                         const uint8_t *vp, size_t vlen,
                                         CassValueType expected_type,
                                         const CassDataType *expected_data_type) {
  switch (expected_type) {
  case CASS_VALUE_TYPE_TINY_INT:
    if (vlen == 1) {
      cass_collection_append_int8(collection, static_cast<int8_t>(vp[0]));
    } else if (vlen == 8) {
      int64_t v64 = read_bigint(vp, vlen);
      cass_collection_append_int8(collection, coerce_bigint_to_tinyint(v64));
    }
    break;
  case CASS_VALUE_TYPE_SMALL_INT:
    if (vlen == 2) {
      int16_t v = static_cast<int16_t>((vp[0] << 8) | vp[1]);
      cass_collection_append_int16(collection, v);
    } else if (vlen == 8) {
      int64_t v64 = read_bigint(vp, vlen);
      cass_collection_append_int16(collection, coerce_bigint_to_smallint(v64));
    }
    break;
  case CASS_VALUE_TYPE_INT:
    if (vlen == 4) {
      int32_t v = (static_cast<int32_t>(vp[0]) << 24) |
                  (static_cast<int32_t>(vp[1]) << 16) |
                  (static_cast<int32_t>(vp[2]) << 8) |
                  static_cast<int32_t>(vp[3]);
      cass_collection_append_int32(collection, v);
    } else if (vlen == 8) {
      int64_t v64 = read_bigint(vp, vlen);
      cass_collection_append_int32(collection, coerce_bigint_to_int(v64));
    }
    break;
  case CASS_VALUE_TYPE_BIGINT:
  case CASS_VALUE_TYPE_COUNTER:
  case CASS_VALUE_TYPE_TIMESTAMP:
  case CASS_VALUE_TYPE_TIME:
    if (vlen == 8) {
      int64_t v = read_bigint(vp, vlen);
      cass_collection_append_int64(collection, v);
    } else if (vlen == 4) {
      int32_t v32 = (static_cast<int32_t>(vp[0]) << 24) |
                    (static_cast<int32_t>(vp[1]) << 16) |
                    (static_cast<int32_t>(vp[2]) << 8) |
                    static_cast<int32_t>(vp[3]);
      cass_collection_append_int64(collection, static_cast<int64_t>(v32));
    }
    break;
  case CASS_VALUE_TYPE_FLOAT:
    if (vlen == 4) {
      uint32_t bits = (static_cast<uint32_t>(vp[0]) << 24) |
                      (static_cast<uint32_t>(vp[1]) << 16) |
                      (static_cast<uint32_t>(vp[2]) << 8) |
                      static_cast<uint32_t>(vp[3]);
      float v;
      std::memcpy(&v, &bits, sizeof(v));
      cass_collection_append_float(collection, v);
    } else if (vlen == 8) {
      // Double to float coercion
      uint64_t bits = read_bigint(reinterpret_cast<const uint8_t*>(vp), vlen);
      double d;
      std::memcpy(&d, &bits, sizeof(d));
      cass_collection_append_float(collection, coerce_double_to_float(d));
    }
    break;
  case CASS_VALUE_TYPE_DOUBLE:
    if (vlen == 8) {
      uint64_t bits = (static_cast<uint64_t>(vp[0]) << 56) |
                      (static_cast<uint64_t>(vp[1]) << 48) |
                      (static_cast<uint64_t>(vp[2]) << 40) |
                      (static_cast<uint64_t>(vp[3]) << 32) |
                      (static_cast<uint64_t>(vp[4]) << 24) |
                      (static_cast<uint64_t>(vp[5]) << 16) |
                      (static_cast<uint64_t>(vp[6]) << 8) |
                      static_cast<uint64_t>(vp[7]);
      double v;
      std::memcpy(&v, &bits, sizeof(v));
      cass_collection_append_double(collection, v);
    }
    break;
  case CASS_VALUE_TYPE_TEXT:
  case CASS_VALUE_TYPE_ASCII:
  case CASS_VALUE_TYPE_VARCHAR:
    cass_collection_append_string_n(collection,
                                    reinterpret_cast<const char *>(vp), vlen);
    break;
  case CASS_VALUE_TYPE_UUID:
  case CASS_VALUE_TYPE_TIMEUUID:
    if (vlen >= 16) {
      CassUuid uuid;
      std::memcpy(&uuid, vp, 16);
      cass_collection_append_uuid(collection, uuid);
    }
    break;
  case CASS_VALUE_TYPE_BOOLEAN:
    if (vlen >= 1) {
      cass_collection_append_bool(collection, vp[0] ? cass_true : cass_false);
    }
    break;
  case CASS_VALUE_TYPE_LIST:
  case CASS_VALUE_TYPE_SET:
    // Frozen nested collections need recursive coercion
    if (expected_data_type) {
      std::vector<uint8_t> coerced = reserialize_list_or_set(vp, vlen, expected_data_type);
      cass_collection_append_bytes(collection, coerced.data(), coerced.size());
    } else if (vlen > 2) {
      // No schema info - just strip subtype prefix
      cass_collection_append_bytes(collection, vp + 2, vlen - 2);
    }
    break;
  case CASS_VALUE_TYPE_MAP:
    // Frozen nested maps need recursive coercion
    if (expected_data_type) {
      std::vector<uint8_t> coerced = reserialize_map(vp, vlen, expected_data_type);
      cass_collection_append_bytes(collection, coerced.data(), coerced.size());
    } else if (vlen > 4) {
      // No schema info - just strip type prefixes
      cass_collection_append_bytes(collection, vp + 4, vlen - 4);
    }
    break;
  case CASS_VALUE_TYPE_TUPLE: {
    // Frozen nested tuples need metadata stripped and values coerced
    std::vector<uint8_t> coerced = reserialize_tuple(vp, vlen, expected_data_type);
    cass_collection_append_bytes(collection, coerced.data(), coerced.size());
    break;
  }
  case CASS_VALUE_TYPE_UDT: {
    // Frozen nested UDTs need field metadata stripped and field values coerced
    std::vector<uint8_t> coerced = reserialize_udt(vp, vlen, expected_data_type);
    cass_collection_append_bytes(collection, coerced.data(), coerced.size());
    break;
  }
  case CASS_VALUE_TYPE_BLOB:
  case CASS_VALUE_TYPE_VARINT:
  default:
    cass_collection_append_bytes(collection, vp, vlen);
    break;
  }
}

void bind_value(CassStatement *stmt, size_t index, const TypedValue &value,
                const CassDataType *expected_type) {
  if (!value.data) {
    cass_statement_bind_null(stmt, index);
    return;
  }

  const std::vector<uint8_t> &data = *value.data;
  const uint8_t *ptr = data.data();
  size_t len = data.size();

  switch (value.type) {
  case TypeCode::TINYINT:
    if (len >= 1) {
      cass_statement_bind_int8(stmt, index, static_cast<int8_t>(ptr[0]));
    }
    break;

  case TypeCode::SMALLINT:
    if (len >= 2) {
      int16_t v = static_cast<int16_t>((ptr[0] << 8) | ptr[1]);
      cass_statement_bind_int16(stmt, index, v);
    }
    break;

  case TypeCode::INT:
    if (len >= 4) {
      int32_t v = (static_cast<int32_t>(ptr[0]) << 24) |
                  (static_cast<int32_t>(ptr[1]) << 16) |
                  (static_cast<int32_t>(ptr[2]) << 8) |
                  static_cast<int32_t>(ptr[3]);
      cass_statement_bind_int32(stmt, index, v);
    }
    break;

  case TypeCode::BIGINT:
  case TypeCode::COUNTER:
  case TypeCode::TIMESTAMP:
  case TypeCode::TIME:
    if (len >= 8) {
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
    break;

  case TypeCode::FLOAT:
    if (len >= 4) {
      uint32_t bits = (static_cast<uint32_t>(ptr[0]) << 24) |
                      (static_cast<uint32_t>(ptr[1]) << 16) |
                      (static_cast<uint32_t>(ptr[2]) << 8) |
                      static_cast<uint32_t>(ptr[3]);
      float v;
      std::memcpy(&v, &bits, sizeof(v));
      cass_statement_bind_float(stmt, index, v);
    }
    break;

  case TypeCode::DOUBLE:
    if (len >= 8) {
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
    break;

  case TypeCode::BOOLEAN:
    if (len >= 1) {
      cass_statement_bind_bool(stmt, index, ptr[0] ? cass_true : cass_false);
    }
    break;

  case TypeCode::ASCII:
  case TypeCode::TEXT:
    cass_statement_bind_string_n(stmt, index,
                                 reinterpret_cast<const char *>(ptr), len);
    break;

  case TypeCode::BLOB:
  case TypeCode::VARINT:
    cass_statement_bind_bytes(stmt, index, ptr, len);
    break;

  case TypeCode::UUID:
  case TypeCode::TIMEUUID:
    if (len >= 16) {
      CassUuid uuid;
      std::memcpy(&uuid, ptr, 16);
      cass_statement_bind_uuid(stmt, index, uuid);
    }
    break;

  case TypeCode::INET:
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
    break;

  case TypeCode::DATE:
    if (len >= 4) {
      uint32_t v = (static_cast<uint32_t>(ptr[0]) << 24) |
                   (static_cast<uint32_t>(ptr[1]) << 16) |
                   (static_cast<uint32_t>(ptr[2]) << 8) |
                   static_cast<uint32_t>(ptr[3]);
      cass_statement_bind_uint32(stmt, index, v);
    }
    break;

  case TypeCode::DECIMAL: {
    // Decimal: 4-byte scale + varint mantissa
    if (len >= 4) {
      int32_t scale = (static_cast<int32_t>(ptr[0]) << 24) |
                      (static_cast<int32_t>(ptr[1]) << 16) |
                      (static_cast<int32_t>(ptr[2]) << 8) |
                      static_cast<int32_t>(ptr[3]);
      const uint8_t *varint = ptr + 4;
      size_t varint_len = len - 4;
      cass_statement_bind_decimal(stmt, index, varint, varint_len, scale);
    }
    break;
  }

  case TypeCode::DURATION: {
    // Duration: 3 varints (months, days, nanoseconds)
    // For now, bind as bytes - full varint parsing needed for proper support
    cass_statement_bind_bytes(stmt, index, ptr, len);
    break;
  }

  case TypeCode::LIST:
  case TypeCode::SET: {
    // Collections need special handling
    Buffer col_buf(std::vector<uint8_t>(data.begin(), data.end()));
    [[maybe_unused]] TypeCode wire_elem_type = static_cast<TypeCode>(col_buf.read_short());
    int32_t count = col_buf.read_int();

    // Get expected element type from schema if available
    CassValueType expected_elem_type = CASS_VALUE_TYPE_UNKNOWN;
    const CassDataType *elem_dt = nullptr;
    if (expected_type) {
      elem_dt = cass_data_type_sub_data_type(expected_type, 0);
      if (elem_dt) expected_elem_type = cass_data_type_type(elem_dt);
    }

    CassCollection *collection = cass_collection_new(
        value.type == TypeCode::LIST ? CASS_COLLECTION_TYPE_LIST
                                     : CASS_COLLECTION_TYPE_SET,
        static_cast<size_t>(count));

    for (int32_t i = 0; i < count; ++i) {
      auto elem_data = col_buf.read_bytes();
      if (elem_data) {
        const uint8_t *p = elem_data->data();
        size_t plen = elem_data->size();

        if (expected_elem_type != CASS_VALUE_TYPE_UNKNOWN) {
          // Use schema-aware coercion with full data type for recursion
          append_to_collection_coerced(collection, p, plen, expected_elem_type, elem_dt);
        } else {
          // Fallback: append as bytes
          cass_collection_append_bytes(collection, p, plen);
        }
      }
    }

    cass_statement_bind_collection(stmt, index, collection);
    cass_collection_free(collection);
    break;
  }

  case TypeCode::MAP: {
    Buffer map_buf(std::vector<uint8_t>(data.begin(), data.end()));
    [[maybe_unused]] TypeCode wire_key_type = static_cast<TypeCode>(map_buf.read_short());
    [[maybe_unused]] TypeCode wire_val_type = static_cast<TypeCode>(map_buf.read_short());
    int32_t count = map_buf.read_int();

    // Get expected key/value types from schema if available
    CassValueType expected_key_type = CASS_VALUE_TYPE_UNKNOWN;
    CassValueType expected_val_type = CASS_VALUE_TYPE_UNKNOWN;
    const CassDataType *key_dt = nullptr;
    const CassDataType *val_dt = nullptr;
    if (expected_type) {
      key_dt = cass_data_type_sub_data_type(expected_type, 0);
      val_dt = cass_data_type_sub_data_type(expected_type, 1);
      if (key_dt) expected_key_type = cass_data_type_type(key_dt);
      if (val_dt) expected_val_type = cass_data_type_type(val_dt);
    }

    CassCollection *collection = cass_collection_new(
        CASS_COLLECTION_TYPE_MAP, static_cast<size_t>(count) * 2);

    for (int32_t i = 0; i < count; ++i) {
      auto key_data = map_buf.read_bytes();
      auto val_data = map_buf.read_bytes();

      // Append key with coercion to expected type
      if (key_data) {
        const uint8_t *kp = key_data->data();
        size_t klen = key_data->size();
        if (expected_key_type != CASS_VALUE_TYPE_UNKNOWN) {
          append_to_collection_coerced(collection, kp, klen, expected_key_type, key_dt);
        } else {
          // Fallback to wire type
          cass_collection_append_bytes(collection, kp, klen);
        }
      }

      // Append value with coercion to expected type
      if (val_data) {
        const uint8_t *vp = val_data->data();
        size_t vlen = val_data->size();
        if (expected_val_type != CASS_VALUE_TYPE_UNKNOWN) {
          append_to_collection_coerced(collection, vp, vlen, expected_val_type, val_dt);
        } else {
          // Fallback: try to infer based on wire type and data size
          // This handles the case when no schema info is available
          if (vlen == 8 && wire_val_type == TypeCode::BIGINT) {
            int64_t vv = read_bigint(vp, vlen);
            cass_collection_append_int64(collection, vv);
          } else if (vlen == 4) {
            int32_t vv = (static_cast<int32_t>(vp[0]) << 24) |
                         (static_cast<int32_t>(vp[1]) << 16) |
                         (static_cast<int32_t>(vp[2]) << 8) |
                         static_cast<int32_t>(vp[3]);
            cass_collection_append_int32(collection, vv);
          } else {
            cass_collection_append_bytes(collection, vp, vlen);
          }
        }
      }
    }

    cass_statement_bind_collection(stmt, index, collection);
    cass_collection_free(collection);
    break;
  }

  case TypeCode::TUPLE: {
    // Tuple wire format: [n_elements: 2][type codes...][values...]
    // Reserialize with type coercion (e.g., bigint -> int) and bind as bytes
    std::vector<uint8_t> reserialized = reserialize_tuple(ptr, len, expected_type);
    cass_statement_bind_bytes(stmt, index, reserialized.data(), reserialized.size());
    break;
  }

  case TypeCode::VECTOR: {
    // Vector wire format: [element_type: 2][dimension: 2][packed_floats...]
    // Cassandra expects just the packed float data without the header
    if (len >= 4) {
      // Skip the 4-byte header (element_type + dimension)
      const uint8_t *float_data = ptr + 4;
      size_t float_data_len = len - 4;
      cass_statement_bind_bytes(stmt, index, float_data, float_data_len);
    }
    break;
  }

  case TypeCode::UDT: {
    // UDT format: [n_fields: u16] [field_name_len: u16] [field_name] [field_type: u16] ...
    //             [field_values...]
    // Reserialize with type coercion (e.g., bigint -> int for nested fields)
    std::vector<uint8_t> reserialized = reserialize_udt(ptr, len, expected_type);
    cass_statement_bind_bytes(stmt, index, reserialized.data(), reserialized.size());
    break;
  }

  default:
    // Unknown type - bind as bytes
    cass_statement_bind_bytes(stmt, index, ptr, len);
    break;
  }
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

// Forward declarations for vector helpers
static bool is_vector_type(const CassDataType *data_type);
static void encode_vector_value(Buffer &buf, const CassValue *value);

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
    CassIterator *iter = cass_iterator_from_collection(value);

    // Check if iterator is valid (frozen collections may not support iteration)
    if (!iter) {
      // Fallback: encode as raw bytes
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

    std::vector<Buffer> elements;

    // Check if elements are vectors by examining the data type
    const CassDataType *value_data_type = cass_value_data_type(value);
    const CassDataType *elem_data_type = value_data_type ?
        cass_data_type_sub_data_type(value_data_type, 0) : nullptr;
    bool elem_is_vector = is_vector_type(elem_data_type);

    while (cass_iterator_next(iter)) {
      const CassValue *elem = cass_iterator_get_value(iter);

      Buffer elem_buf;
      if (elem_is_vector) {
        // Encode vector element as raw packed floats
        encode_vector_value(elem_buf, elem);
      } else {
        CassValueType elem_type = cass_value_type(elem);
        TypeCode elem_code = cass_type_to_type_code(elem_type);
        encode_value(elem_buf, elem, elem_code);
      }
      elements.push_back(std::move(elem_buf));
    }
    cass_iterator_free(iter);

    // Encode collection
    Buffer col_buf;
    TypeCode subtype_code = elem_is_vector ? TypeCode::VECTOR :
        cass_type_to_type_code(cass_value_primary_sub_type(value));
    col_buf.write_short(static_cast<uint16_t>(subtype_code));
    col_buf.write_int(static_cast<int32_t>(elements.size()));
    for (const auto &elem : elements) {
      col_buf.append(elem);
    }

    buf.write_int(static_cast<int32_t>(col_buf.size()));
    buf.append(col_buf);
    break;
  }

  case TypeCode::MAP: {
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

    Buffer map_buf;
    map_buf.write_short(static_cast<uint16_t>(
        cass_type_to_type_code(cass_value_primary_sub_type(value))));
    map_buf.write_short(static_cast<uint16_t>(
        cass_type_to_type_code(cass_value_secondary_sub_type(value))));
    map_buf.write_int(static_cast<int32_t>(entries.size()));
    for (const auto &[k, v] : entries) {
      map_buf.append(k);
      map_buf.append(v);
    }

    buf.write_int(static_cast<int32_t>(map_buf.size()));
    buf.append(map_buf);
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

// Check if a column data type is a vector type by examining the class name
static bool is_vector_type(const CassDataType *data_type) {
  if (!data_type) return false;

  const char *class_name = nullptr;
  size_t class_name_len = 0;
  if (cass_data_type_class_name(data_type, &class_name, &class_name_len) == CASS_OK &&
      class_name && class_name_len > 0) {
    std::string cn(class_name, class_name_len);
    // Check if it's directly a VectorType, not a collection containing vectors
    // VectorType class name starts with "org.apache.cassandra.db.marshal.VectorType"
    // ListType/SetType/MapType containing vectors would start with those types instead
    return cn.find("VectorType") == 0 ||
           cn.find("org.apache.cassandra.db.marshal.VectorType") == 0;
  }
  return false;
}

// Encode a vector value for the response
// Latte expects VECTOR (0x0030) data to be just raw packed floats
// The dimension is inferred from data.len() / 4
static void encode_vector_value(Buffer &buf, const CassValue *value) {
  if (cass_value_is_null(value)) {
    buf.write_int(-1);
    return;
  }

  const cass_byte_t *bytes;
  size_t bytes_len;
  if (cass_value_get_bytes(value, &bytes, &bytes_len) != CASS_OK) {
    buf.write_int(-1);
    return;
  }

  // Just write the raw packed floats - no header needed
  // Cassandra already returns raw packed floats
  buf.write_int(static_cast<int32_t>(bytes_len));
  buf.append(bytes, bytes_len);
}

Buffer encode_rows_result(const CassResult *result, uint64_t latency_ns) {
  Buffer body;
  body.write_int(static_cast<int32_t>(ResultKind::ROWS));

  size_t column_count = cass_result_column_count(result);
  size_t row_count = cass_result_row_count(result);

  // Pre-compute which columns are vectors
  std::vector<bool> is_vector_col(column_count, false);
  for (size_t i = 0; i < column_count; ++i) {
    const CassDataType *col_data_type = cass_result_column_data_type(result, i);
    is_vector_col[i] = is_vector_type(col_data_type);
  }

  // Flags
  body.write_int(0);
  // Column count
  body.write_int(static_cast<int32_t>(column_count));

  // Column metadata
  for (size_t i = 0; i < column_count; ++i) {
    const char *name;
    size_t name_len;
    cass_result_column_name(result, i, &name, &name_len);

    TypeCode type_code;
    if (is_vector_col[i]) {
      type_code = TypeCode::VECTOR;
    } else {
      CassValueType col_type = cass_result_column_type(result, i);
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

      if (is_vector_col[i]) {
        // Encode as vector with proper wire format
        encode_vector_value(body, value);
      } else {
        CassValueType col_type = cass_result_column_type(result, i);
        TypeCode type_code = cass_type_to_type_code(col_type);
        encode_value(body, value, type_code);
      }
    }
  }
  cass_iterator_free(rows);

  // Append latency
  body.write_long(latency_ns);

  return body;
}

} // namespace latte

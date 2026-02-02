#include "types.h"

#include <arpa/inet.h>
#include <cmath>
#include <cstring>
#include <limits>
#include <sstream>
#include <stdexcept>

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

void bind_value(CassStatement *stmt, size_t index, const TypedValue &value) {
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
    TypeCode elem_type = static_cast<TypeCode>(col_buf.read_short());
    int32_t count = col_buf.read_int();

    CassCollection *collection = cass_collection_new(
        value.type == TypeCode::LIST ? CASS_COLLECTION_TYPE_LIST
                                     : CASS_COLLECTION_TYPE_SET,
        static_cast<size_t>(count));

    for (int32_t i = 0; i < count; ++i) {
      auto elem_data = col_buf.read_bytes();
      if (elem_data) {
        // Simplified: only handle primitive types in collections
        switch (elem_type) {
        case TypeCode::INT: {
          if (elem_data->size() >= 4) {
            const uint8_t *p = elem_data->data();
            int32_t v = (static_cast<int32_t>(p[0]) << 24) |
                        (static_cast<int32_t>(p[1]) << 16) |
                        (static_cast<int32_t>(p[2]) << 8) |
                        static_cast<int32_t>(p[3]);
            cass_collection_append_int32(collection, v);
          }
          break;
        }
        case TypeCode::BIGINT: {
          if (elem_data->size() >= 8) {
            const uint8_t *p = elem_data->data();
            int64_t v = (static_cast<int64_t>(p[0]) << 56) |
                        (static_cast<int64_t>(p[1]) << 48) |
                        (static_cast<int64_t>(p[2]) << 40) |
                        (static_cast<int64_t>(p[3]) << 32) |
                        (static_cast<int64_t>(p[4]) << 24) |
                        (static_cast<int64_t>(p[5]) << 16) |
                        (static_cast<int64_t>(p[6]) << 8) |
                        static_cast<int64_t>(p[7]);
            cass_collection_append_int64(collection, v);
          }
          break;
        }
        case TypeCode::TEXT:
        case TypeCode::ASCII:
          cass_collection_append_string_n(
              collection, reinterpret_cast<const char *>(elem_data->data()),
              elem_data->size());
          break;
        case TypeCode::FLOAT: {
          if (elem_data->size() >= 4) {
            const uint8_t *p = elem_data->data();
            uint32_t bits = (static_cast<uint32_t>(p[0]) << 24) |
                            (static_cast<uint32_t>(p[1]) << 16) |
                            (static_cast<uint32_t>(p[2]) << 8) |
                            static_cast<uint32_t>(p[3]);
            float v;
            std::memcpy(&v, &bits, sizeof(v));
            cass_collection_append_float(collection, v);
          }
          break;
        }
        case TypeCode::DOUBLE: {
          if (elem_data->size() >= 8) {
            const uint8_t *p = elem_data->data();
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
        }
        default:
          // Other types: bind as bytes
          cass_collection_append_bytes(collection, elem_data->data(),
                                       elem_data->size());
          break;
        }
      }
    }

    cass_statement_bind_collection(stmt, index, collection);
    cass_collection_free(collection);
    break;
  }

  case TypeCode::MAP: {
    Buffer map_buf(std::vector<uint8_t>(data.begin(), data.end()));
    TypeCode key_type = static_cast<TypeCode>(map_buf.read_short());
    (void)map_buf.read_short(); // skip val_type, not used currently
    int32_t count = map_buf.read_int();

    CassCollection *collection = cass_collection_new(
        CASS_COLLECTION_TYPE_MAP, static_cast<size_t>(count) * 2);

    for (int32_t i = 0; i < count; ++i) {
      auto key_data = map_buf.read_bytes();
      auto val_data = map_buf.read_bytes();

      // Simplified: only handle text keys for now
      if (key_data && key_type == TypeCode::TEXT) {
        cass_collection_append_string_n(
            collection, reinterpret_cast<const char *>(key_data->data()),
            key_data->size());
      }
      if (val_data) {
        cass_collection_append_bytes(collection, val_data->data(),
                                     val_data->size());
      }
    }

    cass_statement_bind_collection(stmt, index, collection);
    cass_collection_free(collection);
    break;
  }

  case TypeCode::TUPLE: {
    // Tuple: n_elements + type codes + values
    Buffer tuple_buf(std::vector<uint8_t>(data.begin(), data.end()));
    uint16_t n_elements = tuple_buf.read_short();

    std::vector<TypeCode> elem_types;
    for (uint16_t i = 0; i < n_elements; ++i) {
      elem_types.push_back(static_cast<TypeCode>(tuple_buf.read_short()));
    }

    CassTuple *tuple = cass_tuple_new(n_elements);
    for (uint16_t i = 0; i < n_elements; ++i) {
      auto elem_data = tuple_buf.read_bytes();
      if (elem_data) {
        // Simplified: bind as bytes
        cass_tuple_set_bytes(tuple, i, elem_data->data(), elem_data->size());
      } else {
        cass_tuple_set_null(tuple, i);
      }
    }

    cass_statement_bind_tuple(stmt, index, tuple);
    cass_tuple_free(tuple);
    break;
  }

  case TypeCode::VECTOR: {
    // Vector: element_type + dimension + data
    Buffer vec_buf(std::vector<uint8_t>(data.begin(), data.end()));
    TypeCode elem_type = static_cast<TypeCode>(vec_buf.read_short());
    uint16_t dimension = vec_buf.read_short();

    // Vectors are typically float vectors
    if (elem_type == TypeCode::FLOAT &&
        len >= 4 + static_cast<size_t>(dimension) * 4) {
      // Bind as bytes - the driver handles vector encoding
      cass_statement_bind_bytes(stmt, index, ptr, len);
    }
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

    // Encode collection
    Buffer col_buf;
    col_buf.write_short(static_cast<uint16_t>(
        cass_type_to_type_code(cass_value_primary_sub_type(value))));
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

Buffer encode_rows_result(const CassResult *result, uint64_t latency_ns) {
  Buffer body;
  body.write_int(static_cast<int32_t>(ResultKind::ROWS));

  size_t column_count = cass_result_column_count(result);
  size_t row_count = cass_result_row_count(result);

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
    TypeCode type_code = cass_type_to_type_code(col_type);

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

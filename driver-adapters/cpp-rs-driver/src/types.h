#pragma once

#include <cassandra.h>
#include <optional>
#include <string>
#include <vector>

#include "protocol.h"

namespace latte {

// Value with type information for binding
struct TypedValue {
  TypeCode type;
  std::optional<std::vector<uint8_t>> data; // nullopt = NULL
};

// Read a typed value from buffer (for EXECUTE/BATCH)
TypedValue read_typed_value(Buffer &buf);

// Bind a typed value to a statement at the given index
void bind_value(CassStatement *stmt, size_t index, const TypedValue &value);

// Encode a CQL value from result row
void encode_value(Buffer &buf, const CassValue *value, TypeCode type);

// Get TypeCode from CassValueType
TypeCode cass_type_to_type_code(CassValueType type);

// Encode column metadata for ROWS result
void encode_column_metadata(Buffer &buf, const std::string &keyspace,
                            const std::string &table,
                            const std::string &column_name, TypeCode type);

// Encode ROWS result from CassResult
Buffer encode_rows_result(const CassResult *result, uint64_t latency_ns);

// Encode collection types
void encode_list(Buffer &buf, const CassValue *value);
void encode_set(Buffer &buf, const CassValue *value);
void encode_map(Buffer &buf, const CassValue *value);

// Decode collection types for binding
void bind_list(CassStatement *stmt, size_t index, Buffer &buf,
               TypeCode element_type);
void bind_set(CassStatement *stmt, size_t index, Buffer &buf,
              TypeCode element_type);
void bind_map(CassStatement *stmt, size_t index, Buffer &buf, TypeCode key_type,
              TypeCode value_type);

} // namespace latte

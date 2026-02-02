#include "protocol.h"

#include <stdexcept>

namespace latte {

// Note: parse_frame_header, encode_frame, build_session_created_response,
// and build_void_result are now inlined in protocol.h

Buffer build_error_response(ErrorCode code, const std::string &message) {
  Buffer body;
  body.reserve(4 + 2 + message.size()); // int + short + string data
  body.write_int(static_cast<int32_t>(code));
  body.write_string(message);
  return body;
}

Buffer build_prepared_result(const std::string &statement_key,
                             const std::vector<uint8_t> &prepared_id) {
  Buffer body;
  body.reserve(24 + statement_key.size() + prepared_id.size());
  body.write_int(static_cast<int32_t>(ResultKind::PREPARED));
  body.write_string(statement_key);
  body.write_short_bytes(prepared_id);
  // Metadata flags and counts (simplified)
  body.write_int(0); // bind_metadata_flags
  body.write_int(0); // bind_columns_count (we don't send detailed metadata)
  body.write_int(0); // result_metadata_flags
  body.write_int(0); // result_columns_count
  return body;
}

Buffer build_set_keyspace_result(const std::string &keyspace) {
  Buffer body;
  body.reserve(6 + keyspace.size()); // int + short + string data
  body.write_int(static_cast<int32_t>(ResultKind::SET_KEYSPACE));
  body.write_string(keyspace);
  return body;
}

Buffer build_schema_change_result(const std::string &change_type,
                                  const std::string &target,
                                  const std::string &keyspace,
                                  const std::string &name) {
  Buffer body;
  // int + 3-4 strings (2 bytes len each + data)
  body.reserve(4 + 6 + change_type.size() + target.size() + keyspace.size() +
               name.size());
  body.write_int(static_cast<int32_t>(ResultKind::SCHEMA_CHANGE));
  body.write_string(change_type); // CREATED, UPDATED, DROPPED
  body.write_string(target);      // KEYSPACE, TABLE, TYPE, FUNCTION, AGGREGATE
  body.write_string(keyspace);
  if (!name.empty()) {
    body.write_string(name);
  }
  return body;
}

} // namespace latte

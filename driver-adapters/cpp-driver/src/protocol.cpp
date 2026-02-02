#include "protocol.h"

#include <stdexcept>

namespace latte {

FrameHeader parse_frame_header(const uint8_t *data) {
  FrameHeader header;
  header.version = data[0];
  header.flags = data[1];
  header.stream = static_cast<int16_t>((static_cast<uint16_t>(data[2]) << 8) |
                                       static_cast<uint16_t>(data[3]));
  header.opcode = static_cast<Opcode>(data[4]);
  header.body_length = (static_cast<uint32_t>(data[5]) << 24) |
                       (static_cast<uint32_t>(data[6]) << 16) |
                       (static_cast<uint32_t>(data[7]) << 8) |
                       static_cast<uint32_t>(data[8]);
  return header;
}

Buffer encode_frame_header(const FrameHeader &header) {
  Buffer buf;
  buf.reserve(FRAME_HEADER_SIZE);
  buf.write_byte(header.version);
  buf.write_byte(header.flags);
  buf.write_short(static_cast<uint16_t>(header.stream));
  buf.write_byte(static_cast<uint8_t>(header.opcode));
  buf.write_int(static_cast<int32_t>(header.body_length));
  return buf;
}

Buffer encode_frame(int16_t stream, Opcode opcode, const Buffer &body) {
  FrameHeader header;
  header.version = RESPONSE_VERSION;
  header.flags = 0;
  header.stream = stream;
  header.opcode = opcode;
  header.body_length = static_cast<uint32_t>(body.size());

  Buffer frame = encode_frame_header(header);
  frame.append(body);
  return frame;
}

Buffer build_session_created_response(uint64_t session_id) {
  Buffer body;
  body.write_long(session_id);
  return body;
}

Buffer build_error_response(ErrorCode code, const std::string &message) {
  Buffer body;
  body.write_int(static_cast<int32_t>(code));
  body.write_string(message);
  return body;
}

Buffer build_void_result(uint64_t latency_ns) {
  Buffer body;
  body.write_int(static_cast<int32_t>(ResultKind::VOID));
  body.write_long(latency_ns);
  return body;
}

Buffer build_prepared_result(const std::string &statement_key,
                             const std::vector<uint8_t> &prepared_id) {
  Buffer body;
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
  body.write_int(static_cast<int32_t>(ResultKind::SET_KEYSPACE));
  body.write_string(keyspace);
  return body;
}

Buffer build_schema_change_result(const std::string &change_type,
                                  const std::string &target,
                                  const std::string &keyspace,
                                  const std::string &name) {
  Buffer body;
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

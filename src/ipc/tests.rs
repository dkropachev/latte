//! Tests for the IPC module.

#[cfg(test)]
mod tests {
    use super::super::protocol::{
        decode_error_message, decode_header, encode_request, Opcode, HEADER_LENGTH,
        IPC_PROTOCOL_VERSION, VERSION_REQUEST,
    };
    use super::super::types::{ColumnInfo, QueryResult, SessionConfig};
    use bytes::{BufMut, Bytes, BytesMut};

    #[test]
    fn test_protocol_version_constant() {
        assert_eq!(IPC_PROTOCOL_VERSION, 1);
    }

    #[test]
    fn test_encode_request_query() {
        let body = b"test body";
        let encoded = encode_request(Opcode::Query, 42, body);

        assert_eq!(encoded.len(), HEADER_LENGTH + body.len());
        assert_eq!(encoded[0], VERSION_REQUEST);
        assert_eq!(encoded[1], 0); // flags
        assert_eq!(i16::from_be_bytes([encoded[2], encoded[3]]), 42); // stream
        assert_eq!(encoded[4], Opcode::Query as u8);
        let body_len = u32::from_be_bytes([encoded[5], encoded[6], encoded[7], encoded[8]]);
        assert_eq!(body_len as usize, body.len());
    }

    #[test]
    fn test_decode_header() {
        let mut buf = BytesMut::new();
        buf.put_u8(VERSION_REQUEST);
        buf.put_u8(0); // flags
        buf.put_i16(123); // stream
        buf.put_u8(Opcode::Result as u8);
        buf.put_u32(42); // body length

        let header = decode_header(&buf).expect("should decode header");
        assert_eq!(header.version, VERSION_REQUEST);
        assert_eq!(header.flags, 0);
        assert_eq!(header.stream, 123);
        assert_eq!(header.opcode, Opcode::Result);
        assert_eq!(header.body_length, 42);
    }

    #[test]
    fn test_decode_header_too_short() {
        let buf = BytesMut::from(&[0u8; 5][..]);
        let result = decode_header(&buf);
        assert!(result.is_err());
    }

    #[test]
    fn test_decode_error_message() {
        let mut body = BytesMut::new();
        body.put_u32(0x1001); // error code
        let msg = "test error message";
        body.put_u16(msg.len() as u16);
        body.extend_from_slice(msg.as_bytes());

        let decoded = decode_error_message(&body.freeze());
        assert_eq!(decoded, msg);
    }

    #[test]
    fn test_decode_error_message_too_short() {
        let body = Bytes::from_static(&[0u8; 4]);
        let decoded = decode_error_message(&body);
        assert!(decoded.contains("too short"));
    }

    #[test]
    fn test_session_config_builder() {
        let config = SessionConfig::new()
            .contact_points("127.0.0.1:9042,127.0.0.2:9042")
            .keyspace("test_ks")
            .credentials("user", "pass");

        let params = config.into_params();
        assert_eq!(
            params.get("contact_points"),
            Some(&"127.0.0.1:9042,127.0.0.2:9042".to_string())
        );
        assert_eq!(params.get("keyspace"), Some(&"test_ks".to_string()));
        assert_eq!(params.get("username"), Some(&"user".to_string()));
        assert_eq!(params.get("password"), Some(&"pass".to_string()));
    }

    #[test]
    fn test_session_config_no_credentials() {
        let config = SessionConfig::new().contact_points("127.0.0.1:9042");

        let params = config.into_params();
        assert_eq!(
            params.get("contact_points"),
            Some(&"127.0.0.1:9042".to_string())
        );
        assert!(params.get("username").is_none());
        assert!(params.get("password").is_none());
    }

    #[test]
    fn test_query_result_void() {
        let result = QueryResult::Void;
        match result {
            QueryResult::Void => (),
            _ => panic!("expected Void"),
        }
    }

    #[test]
    fn test_column_info() {
        let col = ColumnInfo {
            keyspace: "ks".to_string(),
            table: "tbl".to_string(),
            name: "col".to_string(),
            type_code: 0x0009, // int
        };
        assert_eq!(col.keyspace, "ks");
        assert_eq!(col.table, "tbl");
        assert_eq!(col.name, "col");
        assert_eq!(col.type_code, 0x0009);
    }

    #[test]
    fn test_opcode_values() {
        assert_eq!(Opcode::Error as u8, 0x00);
        assert_eq!(Opcode::Query as u8, 0x07);
        assert_eq!(Opcode::Result as u8, 0x08);
        assert_eq!(Opcode::Prepare as u8, 0x09);
        assert_eq!(Opcode::Execute as u8, 0x0A);
        assert_eq!(Opcode::CreateSession as u8, 0x21);
        assert_eq!(Opcode::SessionCreated as u8, 0x22);
    }

    #[test]
    fn test_opcode_try_from() {
        assert_eq!(Opcode::try_from(0x07).unwrap(), Opcode::Query);
        assert_eq!(Opcode::try_from(0x08).unwrap(), Opcode::Result);
        assert!(Opcode::try_from(0xFF).is_err());
    }

    #[test]
    fn test_packed_float_vector_list_encoding() {
        use super::super::client::encode_value;
        use scylla::value::CqlValue;

        // Create a list of 3 vectors with 4 floats each
        let vectors: Vec<CqlValue> = (0..3)
            .map(|i| {
                let elements: Vec<CqlValue> = (0..4)
                    .map(|j| CqlValue::Float((i * 4 + j) as f32))
                    .collect();
                CqlValue::Vector(elements)
            })
            .collect();

        let list = CqlValue::List(vectors);

        let mut buf = BytesMut::new();
        encode_value(&list, &mut buf).expect("encoding should succeed");

        // Verify it uses packed format (type code 0x0032)
        assert_eq!(buf[0], 0x00);
        assert_eq!(buf[1], 0x32); // PACKED_FLOAT_VECTOR_LIST

        // Verify header: type(2) + length(4) + n_elements(4) + dimension(2) + floats(3*4*4)
        // Total: 2 + 4 + 4 + 2 + 48 = 60 bytes
        assert_eq!(buf.len(), 60);

        // Verify n_elements (at offset 6-9)
        let n_elements = i32::from_be_bytes([buf[6], buf[7], buf[8], buf[9]]);
        assert_eq!(n_elements, 3);

        // Verify dimension (at offset 10-11)
        let dimension = u16::from_be_bytes([buf[10], buf[11]]);
        assert_eq!(dimension, 4);

        // Verify first float value (at offset 12-15)
        let first_float = f32::from_be_bytes([buf[12], buf[13], buf[14], buf[15]]);
        assert_eq!(first_float, 0.0);

        // Verify last float value (vector 2, element 3 = index 11)
        let last_float = f32::from_be_bytes([buf[56], buf[57], buf[58], buf[59]]);
        assert_eq!(last_float, 11.0);
    }

    #[test]
    fn test_packed_format_saves_space() {
        use super::super::client::encode_value;
        use scylla::value::CqlValue;

        // Create a list of 10 vectors with 768 floats each (common embedding size)
        let vectors: Vec<CqlValue> = (0..10)
            .map(|_| {
                let elements: Vec<CqlValue> = (0..768)
                    .map(|j| CqlValue::Float(j as f32))
                    .collect();
                CqlValue::Vector(elements)
            })
            .collect();

        let list = CqlValue::List(vectors);

        let mut buf = BytesMut::new();
        encode_value(&list, &mut buf).expect("encoding should succeed");

        // Packed format size:
        // - Header: 2 (type) + 4 (length) = 6 bytes
        // - Data: 4 (n_elements) + 2 (dimension) + 10*768*4 (floats) = 30726 bytes
        // Total: 30732 bytes

        // Old format would have been:
        // - Header: 2 (type) + 4 (length) = 6 bytes
        // - Data: 2 (subtype) + 2 (vector_subtype) + 2 (dimension) + 4 (n_elements)
        //       + 10 * (4 (per-elem length) + 768*4 (floats)) = 30770 bytes
        // Total: 30776 bytes

        // We save 4 bytes per vector (the per-element length prefix)
        // For 10 vectors: 40 bytes saved
        assert_eq!(buf.len(), 30732);

        // Verify it uses packed format
        assert_eq!(buf[0], 0x00);
        assert_eq!(buf[1], 0x32);
    }
}

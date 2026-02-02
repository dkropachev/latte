//! Binary protocol for communication with Alternator driver adapters.
//!
//! This protocol is similar to but distinct from the CQL IPC protocol.
//! It uses a 12-byte header and DynamoDB-specific opcodes.

use bytes::{Buf, BufMut, Bytes, BytesMut};
use std::io;
use thiserror::Error;
use tokio::io::{AsyncRead, AsyncReadExt};

pub const HEADER_LENGTH: usize = 12;
pub const VERSION_REQUEST: u8 = 0x01;
pub const VERSION_RESPONSE: u8 = 0x81;

/// Request opcodes for Alternator adapter protocol.
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
#[repr(u8)]
pub enum RequestOpcode {
    CreateSession = 0x01,
    CloseSession = 0x02,
    GetItem = 0x10,
    PutItem = 0x11,
    DeleteItem = 0x12,
    UpdateItem = 0x13,
    Query = 0x14,
    Scan = 0x15,
    BatchGetItem = 0x20,
    BatchWriteItem = 0x21,
    Shutdown = 0xFE,
}

/// Response opcodes for Alternator adapter protocol.
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
#[repr(u8)]
pub enum ResponseOpcode {
    Error = 0x00,
    SessionCreated = 0x01,
    SessionClosed = 0x02,
    ItemResult = 0x10,
    QueryResult = 0x14,
    BatchResult = 0x20,
    ShutdownAck = 0xFE,
}

impl TryFrom<u8> for ResponseOpcode {
    type Error = AlternatorProtocolError;

    fn try_from(value: u8) -> Result<Self, AlternatorProtocolError> {
        Ok(match value {
            0x00 => ResponseOpcode::Error,
            0x01 => ResponseOpcode::SessionCreated,
            0x02 => ResponseOpcode::SessionClosed,
            0x10 => ResponseOpcode::ItemResult,
            0x14 => ResponseOpcode::QueryResult,
            0x20 => ResponseOpcode::BatchResult,
            0xFE => ResponseOpcode::ShutdownAck,
            other => return Err(AlternatorProtocolError::UnknownOpcode(other)),
        })
    }
}

#[derive(Clone, Copy, Debug)]
pub struct FrameHeader {
    pub version: u8,
    pub flags: u8,
    pub stream: i16,
    pub opcode: u8,
    pub body_length: u32,
}

#[derive(Debug, Clone)]
pub struct Frame {
    pub header: FrameHeader,
    pub body: Bytes,
}

#[derive(Debug, Error)]
pub enum AlternatorProtocolError {
    #[error("io error: {0}")]
    Io(#[from] io::Error),
    #[error("unknown opcode {0:#x}")]
    UnknownOpcode(u8),
    #[error("unexpected EOF while reading frame")]
    UnexpectedEof,
    #[error("body too large: {0}")]
    BodyTooLarge(u32),
    #[error("protocol error: {0}")]
    Protocol(String),
}

/// Error codes from the Alternator adapter.
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
#[repr(u32)]
pub enum ErrorCode {
    Unknown = 0x0000,
    Protocol = 0x0001,
    Connection = 0x0002,
    SessionNotFound = 0x0003,
    ValidationError = 0x0004,
    ConditionalCheckFailed = 0x0010,
    ResourceNotFound = 0x0011,
    ProvisionedThroughput = 0x0012,
    ItemCollectionSizeLimitExceeded = 0x0013,
    RequestLimitExceeded = 0x0014,
    InternalServer = 0x0020,
    ServiceUnavailable = 0x0021,
    Timeout = 0x0030,
}

pub fn decode_header(src: &[u8]) -> Result<FrameHeader, AlternatorProtocolError> {
    if src.len() < HEADER_LENGTH {
        return Err(AlternatorProtocolError::UnexpectedEof);
    }

    let mut head = src;
    let version = head.get_u8();
    let flags = head.get_u8();
    let stream = head.get_i16();
    let opcode = head.get_u8();
    let _reserved1 = head.get_u8(); // reserved byte 1
    let _reserved2 = head.get_u8(); // reserved byte 2
    let _reserved3 = head.get_u8(); // reserved byte 3
    let body_length = head.get_u32();

    Ok(FrameHeader {
        version,
        flags,
        stream,
        opcode,
        body_length,
    })
}

pub async fn read_frame<R: AsyncRead + Unpin>(
    reader: &mut R,
    buffer: &mut BytesMut,
) -> Result<Option<Frame>, AlternatorProtocolError> {
    loop {
        if buffer.len() >= HEADER_LENGTH {
            let header = decode_header(&buffer[..HEADER_LENGTH])?;
            let frame_length = HEADER_LENGTH + header.body_length as usize;
            if header.body_length > (16 * 1024 * 1024) {
                return Err(AlternatorProtocolError::BodyTooLarge(header.body_length));
            }

            if buffer.len() >= frame_length {
                buffer.advance(HEADER_LENGTH);
                let body = buffer.split_to(header.body_length as usize).freeze();
                return Ok(Some(Frame { header, body }));
            }
        }

        let read = reader.read_buf(buffer).await?;
        if read == 0 {
            if buffer.is_empty() {
                return Ok(None);
            }
            return Err(AlternatorProtocolError::UnexpectedEof);
        }
    }
}

/// Encode a request frame with the given opcode and body.
pub fn encode_request(opcode: RequestOpcode, stream: i16, body: &[u8]) -> BytesMut {
    let mut buf = BytesMut::with_capacity(HEADER_LENGTH + body.len());
    buf.put_u8(VERSION_REQUEST);
    buf.put_u8(0); // flags
    buf.put_i16(stream);
    buf.put_u8(opcode as u8);
    buf.put_u8(0); // reserved byte 1
    buf.put_u8(0); // reserved byte 2
    buf.put_u8(0); // reserved byte 3
    buf.put_u32(body.len() as u32);
    buf.extend_from_slice(body);
    buf
}

/// Decode an error message from an ERROR frame body.
pub fn decode_error(body: &Bytes) -> (u32, String, String) {
    if body.len() < 4 {
        return (0, "UnknownError".to_string(), "body too short".to_string());
    }
    let mut slice = body.clone();
    let error_code = slice.get_u32();

    // Read error type string
    let error_type = if slice.remaining() >= 4 {
        let len = slice.get_u32() as usize;
        if slice.remaining() >= len {
            let s = String::from_utf8_lossy(&slice[..len]).to_string();
            slice.advance(len);
            s
        } else {
            "UnknownError".to_string()
        }
    } else {
        "UnknownError".to_string()
    };

    // Read error message string
    let message = if slice.remaining() >= 4 {
        let len = slice.get_u32() as usize;
        if slice.remaining() >= len {
            String::from_utf8_lossy(&slice[..len]).to_string()
        } else {
            "unknown error".to_string()
        }
    } else {
        "unknown error".to_string()
    };

    (error_code, error_type, message)
}

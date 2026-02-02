//! CQL-like binary protocol for communication with driver adapters.
//!
//! This module mirrors the protocol implementation in cql-adapters/scylla-rust-driver/src/protocol.rs.
//! IMPORTANT: Keep this file in sync with cql-adapters/scylla-rust-driver/src/protocol.rs
//!
//! Note: Some types and functions in this module are marked with `#[allow(dead_code)]` because
//! they are part of the protocol specification but are only used on the driver adapter side,
//! not the client side. They are kept here to maintain protocol parity between both implementations.

use bytes::{Buf, BufMut, Bytes, BytesMut};
use std::io;
use thiserror::Error;
use tokio::io::{AsyncRead, AsyncReadExt, AsyncWrite, AsyncWriteExt};

pub const HEADER_LENGTH: usize = 9;
pub const VERSION_REQUEST: u8 = 0x04;
/// Response version byte - defined for protocol completeness, used by driver adapter side.
#[allow(dead_code)]
pub const VERSION_RESPONSE: u8 = 0x84;

/// Protocol version for the IPC communication.
/// Bump this when making incompatible changes to the protocol.
/// Used in protocol sync test and for version negotiation.
#[allow(dead_code)]
pub const IPC_PROTOCOL_VERSION: u16 = 1;

#[derive(Clone, Copy, Debug, PartialEq, Eq)]
#[repr(u8)]
pub enum Opcode {
    Error = 0x00,
    Startup = 0x01,
    Ready = 0x02,
    Options = 0x05,
    Supported = 0x06,
    Query = 0x07,
    Result = 0x08,
    Prepare = 0x09,
    Execute = 0x0A,
    Batch = 0x0D,
    AuthChallenge = 0x0E,
    AuthResponse = 0x0F,
    AuthSuccess = 0x10,
    CreateSession = 0x21,
    SessionCreated = 0x22,
}

impl TryFrom<u8> for Opcode {
    type Error = ProtocolError;

    fn try_from(value: u8) -> Result<Self, ProtocolError> {
        Ok(match value {
            0x00 => Opcode::Error,
            0x01 => Opcode::Startup,
            0x02 => Opcode::Ready,
            0x05 => Opcode::Options,
            0x06 => Opcode::Supported,
            0x07 => Opcode::Query,
            0x08 => Opcode::Result,
            0x09 => Opcode::Prepare,
            0x0A => Opcode::Execute,
            0x0D => Opcode::Batch,
            0x0E => Opcode::AuthChallenge,
            0x0F => Opcode::AuthResponse,
            0x10 => Opcode::AuthSuccess,
            0x21 => Opcode::CreateSession,
            0x22 => Opcode::SessionCreated,
            other => return Err(ProtocolError::UnknownOpcode(other)),
        })
    }
}

#[derive(Clone, Copy, Debug)]
#[allow(dead_code)]
pub struct FrameHeader {
    pub version: u8,
    pub flags: u8,
    pub stream: i16,
    pub opcode: Opcode,
    pub body_length: u32,
}

#[derive(Debug, Clone)]
pub struct Frame {
    pub header: FrameHeader,
    pub body: Bytes,
}

#[derive(Debug, Error)]
#[allow(dead_code)]
pub enum ProtocolError {
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

#[allow(dead_code)]
#[derive(Clone, Copy, Debug)]
#[repr(u32)]
pub enum ErrorCode {
    Server = 0x0000,
    Protocol = 0x000A,
    Overloaded = 0x1001,
    Unprepared = 0x2500,
}

pub fn decode_header(src: &[u8]) -> Result<FrameHeader, ProtocolError> {
    if src.len() < HEADER_LENGTH {
        return Err(ProtocolError::UnexpectedEof);
    }

    let mut head = src;
    let version = head.get_u8();
    let flags = head.get_u8();
    let stream = head.get_i16();
    let opcode = Opcode::try_from(head.get_u8())?;
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
) -> Result<Option<Frame>, ProtocolError> {
    loop {
        if buffer.len() >= HEADER_LENGTH {
            let header = decode_header(&buffer[..HEADER_LENGTH])?;
            let frame_length = HEADER_LENGTH + header.body_length as usize;
            if header.body_length > (16 * 1024 * 1024) {
                return Err(ProtocolError::BodyTooLarge(header.body_length));
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
            return Err(ProtocolError::UnexpectedEof);
        }
    }
}

#[allow(dead_code)]
pub async fn write_frame<W: AsyncWrite + Unpin>(
    writer: &mut W,
    frame: &Frame,
) -> Result<(), ProtocolError> {
    let mut encoded = BytesMut::with_capacity(HEADER_LENGTH + frame.body.len());
    encode_header(&mut encoded, frame);
    encoded.extend_from_slice(&frame.body);

    writer.write_all(&encoded).await?;
    writer.flush().await?;
    Ok(())
}

#[allow(dead_code)]
fn encode_header(out: &mut BytesMut, frame: &Frame) {
    out.put_u8(VERSION_REQUEST);
    out.put_u8(frame.header.flags);
    out.put_i16(frame.header.stream);
    out.put_u8(frame.header.opcode as u8);
    out.put_u32(frame.body.len() as u32);
}

/// Encode a request frame with the given opcode and body.
pub fn encode_request(opcode: Opcode, stream: i16, body: &[u8]) -> BytesMut {
    let mut buf = BytesMut::with_capacity(HEADER_LENGTH + body.len());
    buf.put_u8(VERSION_REQUEST);
    buf.put_u8(0);
    buf.put_i16(stream);
    buf.put_u8(opcode as u8);
    buf.put_u32(body.len() as u32);
    buf.extend_from_slice(body);
    buf
}

/// Decode an error message from an ERROR frame body.
pub fn decode_error_message(body: &Bytes) -> String {
    if body.len() < 6 {
        return "unknown error (body too short)".to_string();
    }
    let mut slice = body.clone();
    let _error_code = slice.get_u32();
    let msg_len = slice.get_u16() as usize;
    if slice.len() < msg_len {
        return "unknown error (message truncated)".to_string();
    }
    String::from_utf8_lossy(&slice[..msg_len]).to_string()
}

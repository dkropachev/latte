//! CQL-like binary protocol for IPC communication.
//!
//! IMPORTANT: Keep this file in sync with src/ipc/protocol.rs in the main latte crate.

use bytes::{Buf, BufMut, Bytes, BytesMut};
use thiserror::Error;
use tokio::io::{AsyncRead, AsyncReadExt, AsyncWrite, AsyncWriteExt};
use tracing::warn;

pub const HEADER_LENGTH: usize = 9;
pub const VERSION_REQUEST: u8 = 0x04;
pub const VERSION_RESPONSE: u8 = 0x84;

/// Protocol version for the IPC communication.
/// Bump this when making incompatible changes to the protocol.
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
    type Error = FrameError;

    fn try_from(value: u8) -> Result<Self, FrameError> {
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
            other => return Err(FrameError::UnknownOpcode(other)),
        })
    }
}

#[derive(Clone, Copy, Debug)]
pub struct FrameHeader {
    pub version: u8,
    pub flags: u8,
    pub stream: i16,
    pub opcode: Opcode,
    pub body_length: u32,
}

#[derive(Debug)]
pub struct Frame {
    pub header: FrameHeader,
    pub body: Bytes,
}

#[derive(Debug, Error)]
pub enum FrameError {
    #[error("io error: {0}")]
    Io(#[from] std::io::Error),
    #[error("unknown opcode {0:#x}")]
    UnknownOpcode(u8),
    #[error("unexpected EOF while reading frame")]
    UnexpectedEof,
    #[error("body too large: {0}")]
    BodyTooLarge(u32),
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

impl ErrorCode {
    pub const fn as_u32(self) -> u32 {
        self as u32
    }
}

pub fn decode_header(src: &[u8]) -> Result<FrameHeader, FrameError> {
    if src.len() < HEADER_LENGTH {
        return Err(FrameError::UnexpectedEof);
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
) -> Result<Option<Frame>, FrameError> {
    loop {
        if buffer.len() >= HEADER_LENGTH {
            let header = decode_header(&buffer[..HEADER_LENGTH])?;
            if (header.version & 0x7F) != VERSION_REQUEST {
                warn!(version = header.version, "unexpected CQL version");
            }
            let frame_length = HEADER_LENGTH + header.body_length as usize;
            if header.body_length > (16 * 1024 * 1024) {
                return Err(FrameError::BodyTooLarge(header.body_length));
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
            return Err(FrameError::UnexpectedEof);
        }
    }
}

pub async fn write_frame<W: AsyncWrite + Unpin>(
    writer: &mut W,
    frame: &Frame,
) -> Result<(), FrameError> {
    let mut encoded = BytesMut::with_capacity(HEADER_LENGTH + frame.body.len());
    encode_header(&mut encoded, frame);
    encoded.extend_from_slice(&frame.body);

    writer.write_all(&encoded).await?;
    writer.flush().await?;
    Ok(())
}

pub fn error_frame(stream: i16, code: ErrorCode, message: &str) -> Frame {
    // Pre-allocate: 4 (error code) + 2 (message len) + message
    let mut body = BytesMut::with_capacity(6 + message.len());
    body.put_u32(code.as_u32());
    body.put_u16(message.len() as u16);
    body.extend_from_slice(message.as_bytes());

    Frame {
        header: FrameHeader {
            version: VERSION_RESPONSE,
            flags: 0,
            stream,
            opcode: Opcode::Error,
            body_length: body.len() as u32,
        },
        body: body.freeze(),
    }
}

fn encode_header(out: &mut BytesMut, frame: &Frame) {
    out.put_u8(VERSION_RESPONSE);
    out.put_u8(frame.header.flags);
    out.put_i16(frame.header.stream);
    out.put_u8(frame.header.opcode as u8);
    out.put_u32(frame.body.len() as u32);
}

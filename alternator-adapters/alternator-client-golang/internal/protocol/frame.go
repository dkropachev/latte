// Package protocol implements the Latte Alternator binary protocol.
package protocol

import (
	"encoding/binary"
	"errors"
	"fmt"
	"io"
)

const (
	// HeaderSize is the size of the frame header in bytes.
	HeaderSize = 12

	// MaxBodySize is the maximum allowed body size (16MB).
	MaxBodySize = 16 * 1024 * 1024

	// Protocol versions
	VersionRequest  = 0x01
	VersionResponse = 0x81
)

// Request opcodes
const (
	OpcodeCreateSession   = 0x01
	OpcodeCloseSession    = 0x02
	OpcodeGetItem         = 0x10
	OpcodePutItem         = 0x11
	OpcodeDeleteItem      = 0x12
	OpcodeUpdateItem      = 0x13
	OpcodeQuery           = 0x14
	OpcodeScan            = 0x15
	OpcodeBatchGetItem    = 0x20
	OpcodeBatchWriteItem  = 0x21
	OpcodeTransactGet     = 0x22
	OpcodeTransactWrite   = 0x23
	OpcodeCreateTable     = 0x30
	OpcodeDeleteTable     = 0x31
	OpcodeDescribeTable   = 0x32
	OpcodeListTables      = 0x33
	OpcodeShutdown        = 0xFE
)

// Response opcodes
const (
	OpcodeError          = 0x00
	OpcodeSessionCreated = 0x01
	OpcodeSessionClosed  = 0x02
	OpcodeItemResult     = 0x10
	OpcodeQueryResult    = 0x14
	OpcodeBatchResult    = 0x20
	OpcodeTransactResult = 0x22
	OpcodeTableResult    = 0x30
	OpcodeListResult     = 0x33
	OpcodeShutdownAck    = 0xFE
)

// Error codes
const (
	ErrorUnknown               = 0x0000
	ErrorProtocol              = 0x0001
	ErrorSessionNotFound       = 0x0002
	ErrorConnection            = 0x0003
	ErrorTimeout               = 0x0004
	ErrorOverloaded            = 0x0005
	ErrorResourceNotFound      = 0x1001
	ErrorResourceInUse         = 0x1002
	ErrorValidation            = 0x1003
	ErrorConditionalCheckFailed = 0x1004
	ErrorTransactionCanceled   = 0x1005
	ErrorProvisionedThroughput = 0x1006
	ErrorItemCollectionSize    = 0x1007
	ErrorLimitExceeded         = 0x1008
	ErrorRequestLimitExceeded  = 0x1009
	ErrorInternalServer        = 0x100A
	ErrorServiceUnavailable    = 0x100B
)

// Frame represents a protocol frame with header and body.
type Frame struct {
	Version    uint8
	Flags      uint8
	StreamID   int16
	Opcode     uint8
	BodyLength uint32
	Body       []byte
}

// Header represents just the frame header (for reading).
type Header struct {
	Version    uint8
	Flags      uint8
	StreamID   int16
	Opcode     uint8
	BodyLength uint32
}

// ReadHeader reads a frame header from the reader.
func ReadHeader(r io.Reader) (*Header, error) {
	buf := make([]byte, HeaderSize)
	if _, err := io.ReadFull(r, buf); err != nil {
		return nil, err
	}

	h := &Header{
		Version:    buf[0],
		Flags:      buf[1],
		StreamID:   int16(binary.BigEndian.Uint16(buf[2:4])),
		Opcode:     buf[4],
		BodyLength: binary.BigEndian.Uint32(buf[8:12]),
	}

	if h.Version != VersionRequest {
		return nil, fmt.Errorf("invalid request version: 0x%02x", h.Version)
	}

	if h.BodyLength > MaxBodySize {
		return nil, fmt.Errorf("body length %d exceeds maximum %d", h.BodyLength, MaxBodySize)
	}

	return h, nil
}

// ReadFrame reads a complete frame (header + body) from the reader.
func ReadFrame(r io.Reader) (*Frame, error) {
	h, err := ReadHeader(r)
	if err != nil {
		return nil, err
	}

	body := make([]byte, h.BodyLength)
	if h.BodyLength > 0 {
		if _, err := io.ReadFull(r, body); err != nil {
			return nil, err
		}
	}

	return &Frame{
		Version:    h.Version,
		Flags:      h.Flags,
		StreamID:   h.StreamID,
		Opcode:     h.Opcode,
		BodyLength: h.BodyLength,
		Body:       body,
	}, nil
}

// WriteFrame writes a response frame to the writer.
func WriteFrame(w io.Writer, streamID int16, opcode uint8, body []byte) error {
	buf := make([]byte, HeaderSize+len(body))

	// Header
	buf[0] = VersionResponse
	buf[1] = 0 // Flags
	binary.BigEndian.PutUint16(buf[2:4], uint16(streamID))
	buf[4] = opcode
	buf[5] = 0 // Reserved
	buf[6] = 0 // Reserved
	buf[7] = 0 // Reserved
	binary.BigEndian.PutUint32(buf[8:12], uint32(len(body)))

	// Body
	copy(buf[HeaderSize:], body)

	_, err := w.Write(buf)
	return err
}

// WriteError writes an error response frame.
func WriteError(w io.Writer, streamID int16, code uint32, errType, message string) error {
	buf := NewBuffer(64 + len(errType) + len(message))
	buf.WriteUint32(code)
	buf.WriteString(errType)
	buf.WriteString(message)
	return WriteFrame(w, streamID, OpcodeError, buf.Bytes())
}

// ErrInvalidFrame indicates an invalid frame was received.
var ErrInvalidFrame = errors.New("invalid frame")

// Package protocol provides CQL protocol frame encoding and decoding.
//
// This package implements the binary wire format for the CQL protocol,
// including frame headers, body encoding, and result frame construction.
// It also provides buffer pooling for efficient memory usage in hot paths.
package protocol

import (
	"bufio"
	"bytes"
	"encoding/binary"
	"errors"
	"fmt"
	"io"
	"reflect"
	"sync"

	"github.com/scylladb/latte/driver-adapters/gocql/values"
)

// isNilValue checks if an interface value is nil, including typed nils.
// This handles the case where an interface holds a nil pointer/slice/map/etc.
func isNilValue(v interface{}) bool {
	if v == nil {
		return true
	}
	rv := reflect.ValueOf(v)
	switch rv.Kind() {
	case reflect.Ptr, reflect.Slice, reflect.Map, reflect.Chan, reflect.Func, reflect.Interface:
		return rv.IsNil()
	}
	return false
}

// headerPool pools frame header buffers to reduce allocations
var headerPool = sync.Pool{
	New: func() interface{} {
		return make([]byte, HeaderLength)
	},
}

// Body buffer pools for common frame sizes
var (
	bodyPool256  = sync.Pool{New: func() interface{} { return make([]byte, 256) }}
	bodyPool1K   = sync.Pool{New: func() interface{} { return make([]byte, 1024) }}
	bodyPool4K   = sync.Pool{New: func() interface{} { return make([]byte, 4096) }}
	bodyPool16K  = sync.Pool{New: func() interface{} { return make([]byte, 16384) }}
	bodyPool64K  = sync.Pool{New: func() interface{} { return make([]byte, 65536) }}
	bodyPool256K = sync.Pool{New: func() interface{} { return make([]byte, 262144) }}
)

// GetBodyBuffer returns a buffer suitable for a frame body of the given size.
// The returned buffer may be larger than requested.
// Call PutBodyBuffer when done to return it to the pool.
func GetBodyBuffer(size int) []byte {
	switch {
	case size <= 256:
		return bodyPool256.Get().([]byte)[:size]
	case size <= 1024:
		return bodyPool1K.Get().([]byte)[:size]
	case size <= 4096:
		return bodyPool4K.Get().([]byte)[:size]
	case size <= 16384:
		return bodyPool16K.Get().([]byte)[:size]
	case size <= 65536:
		return bodyPool64K.Get().([]byte)[:size]
	case size <= 262144:
		return bodyPool256K.Get().([]byte)[:size]
	default:
		// Too large for pooling, allocate directly
		return make([]byte, size)
	}
}

// PutBodyBuffer returns a body buffer to the appropriate pool.
func PutBodyBuffer(buf []byte) {
	switch cap(buf) {
	case 256:
		bodyPool256.Put(buf[:256])
	case 1024:
		bodyPool1K.Put(buf[:1024])
	case 4096:
		bodyPool4K.Put(buf[:4096])
	case 16384:
		bodyPool16K.Put(buf[:16384])
	case 65536:
		bodyPool64K.Put(buf[:65536])
	case 262144:
		bodyPool256K.Put(buf[:262144])
	// Larger buffers are not pooled
	}
}

const (
	HeaderLength    = 9
	VersionRequest  = 0x04
	VersionResponse = 0x84
	MaxBodyLength   = 16 * 1024 * 1024 // 16MB
)

var (
	ErrBodyTooLarge   = errors.New("body too large")
	ErrUnknownOpcode  = errors.New("unknown opcode")
	ErrInvalidVersion = errors.New("invalid protocol version")
)

// FrameHeader represents a CQL protocol frame header.
type FrameHeader struct {
	Version    byte
	Flags      byte
	Stream     int16
	Opcode     Opcode
	BodyLength uint32
}

// Frame represents a complete CQL protocol frame.
type Frame struct {
	Header FrameHeader
	Body   []byte
}

// ReadFrame reads a complete frame from a buffered reader.
// Note: The returned frame's Body may be from a pool. Call ReleaseFrame when done
// if you want to return it to the pool (optional - GC will handle it otherwise).
func ReadFrame(r *bufio.Reader) (*Frame, error) {
	// Read header using pooled buffer
	headerBuf := headerPool.Get().([]byte)
	if _, err := io.ReadFull(r, headerBuf); err != nil {
		headerPool.Put(headerBuf)
		if err == io.EOF {
			return nil, nil // Clean disconnect
		}
		return nil, fmt.Errorf("failed to read frame header: %w", err)
	}

	header := FrameHeader{
		Version:    headerBuf[0],
		Flags:      headerBuf[1],
		Stream:     int16(binary.BigEndian.Uint16(headerBuf[2:4])),
		Opcode:     Opcode(headerBuf[4]),
		BodyLength: binary.BigEndian.Uint32(headerBuf[5:9]),
	}

	// Return header buffer to pool immediately after parsing
	headerPool.Put(headerBuf)

	if header.BodyLength > MaxBodyLength {
		return nil, fmt.Errorf("%w: %d bytes", ErrBodyTooLarge, header.BodyLength)
	}

	// Read body using pooled buffer when possible
	var body []byte
	if header.BodyLength > 0 {
		body = GetBodyBuffer(int(header.BodyLength))
		if _, err := io.ReadFull(r, body); err != nil {
			PutBodyBuffer(body)
			return nil, fmt.Errorf("failed to read frame body: %w", err)
		}
	}

	return &Frame{
		Header: header,
		Body:   body,
	}, nil
}

// ReleaseFrame returns the frame's body buffer to the pool if it was pooled.
// This is optional - the GC will handle cleanup if not called.
func ReleaseFrame(f *Frame) {
	if f != nil && f.Body != nil {
		PutBodyBuffer(f.Body)
		f.Body = nil
	}
}

// WriteFrame writes a frame directly to a buffered writer, avoiding intermediate allocation.
func WriteFrame(w *bufio.Writer, f *Frame) error {
	// Write header directly to buffered writer using pooled buffer
	header := headerPool.Get().([]byte)
	header[0] = VersionResponse
	header[1] = f.Header.Flags
	binary.BigEndian.PutUint16(header[2:4], uint16(f.Header.Stream))
	header[4] = byte(f.Header.Opcode)
	binary.BigEndian.PutUint32(header[5:9], uint32(len(f.Body)))

	if _, err := w.Write(header); err != nil {
		headerPool.Put(header)
		return err
	}
	headerPool.Put(header)

	// Write body directly
	if len(f.Body) > 0 {
		if _, err := w.Write(f.Body); err != nil {
			return err
		}
	}
	return nil
}

// EncodeFrame encodes a frame to bytes.
// Deprecated: Use WriteFrame for better performance when writing to a buffered writer.
func EncodeFrame(f *Frame) []byte {
	buf := make([]byte, HeaderLength+len(f.Body))

	buf[0] = VersionResponse
	buf[1] = f.Header.Flags
	binary.BigEndian.PutUint16(buf[2:4], uint16(f.Header.Stream))
	buf[4] = byte(f.Header.Opcode)
	binary.BigEndian.PutUint32(buf[5:9], uint32(len(f.Body)))

	if len(f.Body) > 0 {
		copy(buf[HeaderLength:], f.Body)
	}

	return buf
}

// NewFrame creates a new response frame.
func NewFrame(stream int16, opcode Opcode, body []byte) *Frame {
	return &Frame{
		Header: FrameHeader{
			Version:    VersionResponse,
			Flags:      0,
			Stream:     stream,
			Opcode:     opcode,
			BodyLength: uint32(len(body)),
		},
		Body: body,
	}
}

// ErrorFrame creates an error response frame.
func ErrorFrame(stream int16, code ErrorCode, message string) *Frame {
	buf := new(bytes.Buffer)
	WriteInt(buf, int32(code))
	WriteString(buf, message)

	return &Frame{
		Header: FrameHeader{
			Version:    VersionResponse,
			Flags:      0,
			Stream:     stream,
			Opcode:     OpcodeError,
			BodyLength: uint32(buf.Len()),
		},
		Body: buf.Bytes(),
	}
}

// SessionCreatedFrame creates a SESSION_CREATED response frame.
func SessionCreatedFrame(stream int16, sessionID uint64) *Frame {
	buf := new(bytes.Buffer)
	WriteLong(buf, sessionID)

	return &Frame{
		Header: FrameHeader{
			Version:    VersionResponse,
			Flags:      0,
			Stream:     stream,
			Opcode:     OpcodeSessionCreated,
			BodyLength: uint32(buf.Len()),
		},
		Body: buf.Bytes(),
	}
}

// VoidResultFrame creates a VOID RESULT response frame.
func VoidResultFrame(stream int16) *Frame {
	buf := new(bytes.Buffer)
	WriteInt(buf, int32(ResultVoid))

	return &Frame{
		Header: FrameHeader{
			Version:    VersionResponse,
			Flags:      0,
			Stream:     stream,
			Opcode:     OpcodeResult,
			BodyLength: uint32(buf.Len()),
		},
		Body: buf.Bytes(),
	}
}

// PreparedResultFrame creates a PREPARED RESULT response frame.
func PreparedResultFrame(stream int16, statementKey string) *Frame {
	buf := new(bytes.Buffer)

	// RESULT kind = PREPARED (0x0004)
	WriteInt(buf, int32(ResultPrepared))

	// Echo the statement key as a [string]
	WriteString(buf, statementKey)

	// Prepared statement ID as [short bytes] - we use the key as the ID
	keyBytes := []byte(statementKey)
	WriteShortBytes(buf, keyBytes)

	// Bind metadata: flags=0, columns_count=0
	WriteInt(buf, 0) // flags
	WriteInt(buf, 0) // columns_count

	// Result metadata: flags=0, columns_count=0
	WriteInt(buf, 0) // flags
	WriteInt(buf, 0) // columns_count

	return &Frame{
		Header: FrameHeader{
			Version:    VersionResponse,
			Flags:      0,
			Stream:     stream,
			Opcode:     OpcodeResult,
			BodyLength: uint32(buf.Len()),
		},
		Body: buf.Bytes(),
	}
}

// RowsResultFrame creates a ROWS RESULT response frame.
func RowsResultFrame(stream int16, columns []ColumnMeta, rows [][]interface{}) *Frame {
	buf := new(bytes.Buffer)

	// RESULT kind = ROWS (0x0002)
	WriteInt(buf, int32(ResultRows))

	// Flags (no metadata ID, no paging)
	WriteInt(buf, 0)

	// Column count
	WriteInt(buf, int32(len(columns)))

	// Column metadata
	for _, col := range columns {
		WriteString(buf, col.Keyspace)
		WriteString(buf, col.Table)
		WriteString(buf, col.Name)
		WriteShort(buf, col.TypeCode)
	}

	// Row count
	WriteInt(buf, int32(len(rows)))

	// Row data
	for _, row := range rows {
		for i, value := range row {
			if isNilValue(value) {
				WriteInt(buf, -1) // null
			} else {
				encoded := values.EncodeValue(value, columns[i].TypeCode)
				WriteBytes(buf, encoded)
				// Return pooled buffers after writing
				values.PutBuffer(encoded)
			}
		}
	}

	return &Frame{
		Header: FrameHeader{
			Version:    VersionResponse,
			Flags:      0,
			Stream:     stream,
			Opcode:     OpcodeResult,
			BodyLength: uint32(buf.Len()),
		},
		Body: buf.Bytes(),
	}
}

// ColumnMeta holds metadata for a result column.
type ColumnMeta struct {
	Keyspace string
	Table    string
	Name     string
	TypeCode uint16
}

// Result metadata flags
const (
	ResultFlagGlobalTableSpec = 0x0001
	ResultFlagHasMorePages    = 0x0002
	ResultFlagNoMetadata      = 0x0004
)

// RowsResultFrameWithPaging creates a ROWS RESULT response frame with optional paging state.
func RowsResultFrameWithPaging(stream int16, columns []ColumnMeta, rows [][]interface{}, pageState []byte) *Frame {
	buf := new(bytes.Buffer)

	// RESULT kind = ROWS (0x0002)
	WriteInt(buf, int32(ResultRows))

	// Flags - set HAS_MORE_PAGES if pageState is present
	var flags int32 = 0
	if len(pageState) > 0 {
		flags |= ResultFlagHasMorePages
	}
	WriteInt(buf, flags)

	// Column count
	WriteInt(buf, int32(len(columns)))

	// Column metadata
	for _, col := range columns {
		WriteString(buf, col.Keyspace)
		WriteString(buf, col.Table)
		WriteString(buf, col.Name)
		WriteShort(buf, col.TypeCode)
	}

	// Page state (if present)
	if len(pageState) > 0 {
		WriteBytes(buf, pageState)
	}

	// Row count
	WriteInt(buf, int32(len(rows)))

	// Row data
	for _, row := range rows {
		for i, value := range row {
			if isNilValue(value) {
				WriteInt(buf, -1) // null
			} else {
				encoded := values.EncodeValue(value, columns[i].TypeCode)
				WriteBytes(buf, encoded)
				// Return pooled buffers after writing
				values.PutBuffer(encoded)
			}
		}
	}

	return &Frame{
		Header: FrameHeader{
			Version:    VersionResponse,
			Flags:      0,
			Stream:     stream,
			Opcode:     OpcodeResult,
			BodyLength: uint32(buf.Len()),
		},
		Body: buf.Bytes(),
	}
}

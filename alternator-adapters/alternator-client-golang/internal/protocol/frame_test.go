package protocol

import (
	"bytes"
	"encoding/binary"
	"io"
	"strings"
	"testing"
)

func TestReadHeader_ValidRequest(t *testing.T) {
	tests := []struct {
		name     string
		opcode   uint8
		streamID int16
		bodyLen  uint32
	}{
		{"CreateSession", OpcodeCreateSession, 1, 100},
		{"CloseSession", OpcodeCloseSession, 2, 8},
		{"GetItem", OpcodeGetItem, 3, 200},
		{"PutItem", OpcodePutItem, 4, 500},
		{"DeleteItem", OpcodeDeleteItem, 5, 100},
		{"UpdateItem", OpcodeUpdateItem, 6, 300},
		{"Query", OpcodeQuery, 7, 1000},
		{"Scan", OpcodeScan, 8, 1000},
		{"BatchGetItem", OpcodeBatchGetItem, 9, 2000},
		{"BatchWriteItem", OpcodeBatchWriteItem, 10, 3000},
		{"Shutdown", OpcodeShutdown, 11, 0},
		{"NegativeStreamID", OpcodeGetItem, -1, 100},
		{"MaxStreamID", OpcodeGetItem, 32767, 100},
		{"MinStreamID", OpcodeGetItem, -32768, 100},
	}

	for _, tt := range tests {
		t.Run(tt.name, func(t *testing.T) {
			buf := make([]byte, HeaderSize)
			buf[0] = VersionRequest
			buf[1] = 0 // flags
			binary.BigEndian.PutUint16(buf[2:4], uint16(tt.streamID))
			buf[4] = tt.opcode
			buf[5] = 0 // reserved
			buf[6] = 0 // reserved
			buf[7] = 0 // reserved
			binary.BigEndian.PutUint32(buf[8:12], tt.bodyLen)

			h, err := ReadHeader(bytes.NewReader(buf))
			if err != nil {
				t.Fatalf("ReadHeader: %v", err)
			}

			if h.Version != VersionRequest {
				t.Errorf("Version = 0x%02x, want 0x%02x", h.Version, VersionRequest)
			}
			if h.StreamID != tt.streamID {
				t.Errorf("StreamID = %d, want %d", h.StreamID, tt.streamID)
			}
			if h.Opcode != tt.opcode {
				t.Errorf("Opcode = 0x%02x, want 0x%02x", h.Opcode, tt.opcode)
			}
			if h.BodyLength != tt.bodyLen {
				t.Errorf("BodyLength = %d, want %d", h.BodyLength, tt.bodyLen)
			}
		})
	}
}

func TestReadHeader_InvalidVersion(t *testing.T) {
	tests := []struct {
		name    string
		version uint8
	}{
		{"ResponseVersion", VersionResponse},
		{"ZeroVersion", 0x00},
		{"RandomVersion", 0x42},
		{"HighVersion", 0xFF},
	}

	for _, tt := range tests {
		t.Run(tt.name, func(t *testing.T) {
			buf := make([]byte, HeaderSize)
			buf[0] = tt.version
			binary.BigEndian.PutUint32(buf[8:12], 0)

			_, err := ReadHeader(bytes.NewReader(buf))
			if err == nil {
				t.Error("ReadHeader should fail with invalid version")
			}
			if !strings.Contains(err.Error(), "invalid request version") {
				t.Errorf("Error = %q, want to contain 'invalid request version'", err.Error())
			}
		})
	}
}

func TestReadHeader_BodyLengthValidation(t *testing.T) {
	// Test body length exactly at max
	buf := make([]byte, HeaderSize)
	buf[0] = VersionRequest
	binary.BigEndian.PutUint32(buf[8:12], MaxBodySize)

	h, err := ReadHeader(bytes.NewReader(buf))
	if err != nil {
		t.Fatalf("ReadHeader with MaxBodySize: %v", err)
	}
	if h.BodyLength != MaxBodySize {
		t.Errorf("BodyLength = %d, want %d", h.BodyLength, MaxBodySize)
	}

	// Test body length exceeding max
	binary.BigEndian.PutUint32(buf[8:12], MaxBodySize+1)
	_, err = ReadHeader(bytes.NewReader(buf))
	if err == nil {
		t.Error("ReadHeader should fail with body length > MaxBodySize")
	}
	if !strings.Contains(err.Error(), "exceeds maximum") {
		t.Errorf("Error = %q, want to contain 'exceeds maximum'", err.Error())
	}

	// Test very large body length
	binary.BigEndian.PutUint32(buf[8:12], 0xFFFFFFFF)
	_, err = ReadHeader(bytes.NewReader(buf))
	if err == nil {
		t.Error("ReadHeader should fail with very large body length")
	}
}

func TestReadHeader_TruncatedData(t *testing.T) {
	tests := []struct {
		name string
		size int
	}{
		{"Empty", 0},
		{"1byte", 1},
		{"4bytes", 4},
		{"8bytes", 8},
		{"11bytes", 11},
	}

	for _, tt := range tests {
		t.Run(tt.name, func(t *testing.T) {
			buf := make([]byte, tt.size)
			if tt.size > 0 {
				buf[0] = VersionRequest
			}

			_, err := ReadHeader(bytes.NewReader(buf))
			if err == nil {
				t.Error("ReadHeader should fail with truncated data")
			}
		})
	}
}

func TestReadFrame_Complete(t *testing.T) {
	body := []byte("test body content")
	buf := make([]byte, HeaderSize+len(body))
	buf[0] = VersionRequest
	buf[1] = 0
	binary.BigEndian.PutUint16(buf[2:4], 42)
	buf[4] = OpcodeGetItem
	binary.BigEndian.PutUint32(buf[8:12], uint32(len(body)))
	copy(buf[HeaderSize:], body)

	frame, err := ReadFrame(bytes.NewReader(buf))
	if err != nil {
		t.Fatalf("ReadFrame: %v", err)
	}

	if frame.Version != VersionRequest {
		t.Errorf("Version = 0x%02x, want 0x%02x", frame.Version, VersionRequest)
	}
	if frame.StreamID != 42 {
		t.Errorf("StreamID = %d, want 42", frame.StreamID)
	}
	if frame.Opcode != OpcodeGetItem {
		t.Errorf("Opcode = 0x%02x, want 0x%02x", frame.Opcode, OpcodeGetItem)
	}
	if frame.BodyLength != uint32(len(body)) {
		t.Errorf("BodyLength = %d, want %d", frame.BodyLength, len(body))
	}
	if !bytes.Equal(frame.Body, body) {
		t.Errorf("Body = %q, want %q", string(frame.Body), string(body))
	}
}

func TestReadFrame_EmptyBody(t *testing.T) {
	buf := make([]byte, HeaderSize)
	buf[0] = VersionRequest
	buf[4] = OpcodeShutdown
	binary.BigEndian.PutUint32(buf[8:12], 0)

	frame, err := ReadFrame(bytes.NewReader(buf))
	if err != nil {
		t.Fatalf("ReadFrame: %v", err)
	}

	if frame.BodyLength != 0 {
		t.Errorf("BodyLength = %d, want 0", frame.BodyLength)
	}
	if len(frame.Body) != 0 {
		t.Errorf("Body = %v, want empty", frame.Body)
	}
}

func TestReadFrame_TruncatedBody(t *testing.T) {
	buf := make([]byte, HeaderSize+5) // Header says 10 bytes but only 5 present
	buf[0] = VersionRequest
	buf[4] = OpcodeGetItem
	binary.BigEndian.PutUint32(buf[8:12], 10)

	_, err := ReadFrame(bytes.NewReader(buf))
	if err != io.ErrUnexpectedEOF {
		t.Errorf("ReadFrame with truncated body: err = %v, want io.ErrUnexpectedEOF", err)
	}
}

func TestWriteFrame_Response(t *testing.T) {
	var buf bytes.Buffer
	body := []byte("response data")
	streamID := int16(123)

	err := WriteFrame(&buf, streamID, OpcodeItemResult, body)
	if err != nil {
		t.Fatalf("WriteFrame: %v", err)
	}

	data := buf.Bytes()
	if len(data) != HeaderSize+len(body) {
		t.Fatalf("WriteFrame: len = %d, want %d", len(data), HeaderSize+len(body))
	}

	// Check header
	if data[0] != VersionResponse {
		t.Errorf("Version = 0x%02x, want 0x%02x", data[0], VersionResponse)
	}
	if data[1] != 0 {
		t.Errorf("Flags = 0x%02x, want 0x00", data[1])
	}
	writtenStreamID := int16(binary.BigEndian.Uint16(data[2:4]))
	if writtenStreamID != streamID {
		t.Errorf("StreamID = %d, want %d", writtenStreamID, streamID)
	}
	if data[4] != OpcodeItemResult {
		t.Errorf("Opcode = 0x%02x, want 0x%02x", data[4], OpcodeItemResult)
	}
	writtenBodyLen := binary.BigEndian.Uint32(data[8:12])
	if writtenBodyLen != uint32(len(body)) {
		t.Errorf("BodyLength = %d, want %d", writtenBodyLen, len(body))
	}

	// Check body
	if !bytes.Equal(data[HeaderSize:], body) {
		t.Errorf("Body = %q, want %q", string(data[HeaderSize:]), string(body))
	}
}

func TestWriteFrame_AllOpcodes(t *testing.T) {
	opcodes := []struct {
		name   string
		opcode uint8
	}{
		{"Error", OpcodeError},
		{"SessionCreated", OpcodeSessionCreated},
		{"SessionClosed", OpcodeSessionClosed},
		{"ItemResult", OpcodeItemResult},
		{"QueryResult", OpcodeQueryResult},
		{"BatchResult", OpcodeBatchResult},
		{"ShutdownAck", OpcodeShutdownAck},
	}

	for _, tt := range opcodes {
		t.Run(tt.name, func(t *testing.T) {
			var buf bytes.Buffer
			err := WriteFrame(&buf, 1, tt.opcode, nil)
			if err != nil {
				t.Fatalf("WriteFrame: %v", err)
			}

			data := buf.Bytes()
			if data[4] != tt.opcode {
				t.Errorf("Opcode = 0x%02x, want 0x%02x", data[4], tt.opcode)
			}
		})
	}
}

func TestWriteFrame_StreamIDPreservation(t *testing.T) {
	tests := []int16{0, 1, -1, 32767, -32768, 100, -100}

	for _, streamID := range tests {
		t.Run("", func(t *testing.T) {
			var buf bytes.Buffer
			err := WriteFrame(&buf, streamID, OpcodeItemResult, nil)
			if err != nil {
				t.Fatalf("WriteFrame: %v", err)
			}

			data := buf.Bytes()
			written := int16(binary.BigEndian.Uint16(data[2:4]))
			if written != streamID {
				t.Errorf("StreamID = %d, want %d", written, streamID)
			}
		})
	}
}

func TestWriteError(t *testing.T) {
	var buf bytes.Buffer
	streamID := int16(42)
	code := uint32(ErrorSessionNotFound)
	errType := "SessionNotFound"
	message := "Session 123 not found"

	err := WriteError(&buf, streamID, code, errType, message)
	if err != nil {
		t.Fatalf("WriteError: %v", err)
	}

	data := buf.Bytes()

	// Check header
	if data[0] != VersionResponse {
		t.Errorf("Version = 0x%02x, want 0x%02x", data[0], VersionResponse)
	}
	if data[4] != OpcodeError {
		t.Errorf("Opcode = 0x%02x, want 0x%02x", data[4], OpcodeError)
	}

	// Parse body
	r := NewReader(data[HeaderSize:])
	readCode, _ := r.ReadUint32()
	if readCode != code {
		t.Errorf("Error code = 0x%04x, want 0x%04x", readCode, code)
	}

	readType, _ := r.ReadString()
	if readType != errType {
		t.Errorf("Error type = %q, want %q", readType, errType)
	}

	readMsg, _ := r.ReadString()
	if readMsg != message {
		t.Errorf("Error message = %q, want %q", readMsg, message)
	}
}

func TestWriteError_AllErrorCodes(t *testing.T) {
	codes := []struct {
		name string
		code uint32
	}{
		{"Unknown", ErrorUnknown},
		{"Protocol", ErrorProtocol},
		{"SessionNotFound", ErrorSessionNotFound},
		{"Connection", ErrorConnection},
		{"Timeout", ErrorTimeout},
		{"Overloaded", ErrorOverloaded},
		{"ResourceNotFound", ErrorResourceNotFound},
		{"ResourceInUse", ErrorResourceInUse},
		{"Validation", ErrorValidation},
		{"ConditionalCheckFailed", ErrorConditionalCheckFailed},
		{"TransactionCanceled", ErrorTransactionCanceled},
		{"ProvisionedThroughput", ErrorProvisionedThroughput},
		{"ItemCollectionSize", ErrorItemCollectionSize},
		{"LimitExceeded", ErrorLimitExceeded},
		{"RequestLimitExceeded", ErrorRequestLimitExceeded},
		{"InternalServer", ErrorInternalServer},
		{"ServiceUnavailable", ErrorServiceUnavailable},
	}

	for _, tt := range codes {
		t.Run(tt.name, func(t *testing.T) {
			var buf bytes.Buffer
			err := WriteError(&buf, 1, tt.code, tt.name, "test message")
			if err != nil {
				t.Fatalf("WriteError: %v", err)
			}

			data := buf.Bytes()
			r := NewReader(data[HeaderSize:])
			readCode, _ := r.ReadUint32()
			if readCode != tt.code {
				t.Errorf("Error code = 0x%04x, want 0x%04x", readCode, tt.code)
			}
		})
	}
}

func TestReadWrite_RoundTrip(t *testing.T) {
	// Write a response frame
	var buf bytes.Buffer
	body := []byte("round trip test data with some content")
	streamID := int16(999)
	opcode := uint8(OpcodeQueryResult)

	err := WriteFrame(&buf, streamID, opcode, body)
	if err != nil {
		t.Fatalf("WriteFrame: %v", err)
	}

	// Modify the version byte to request version for reading
	data := buf.Bytes()
	data[0] = VersionRequest

	// Read it back
	frame, err := ReadFrame(bytes.NewReader(data))
	if err != nil {
		t.Fatalf("ReadFrame: %v", err)
	}

	if frame.StreamID != streamID {
		t.Errorf("StreamID = %d, want %d", frame.StreamID, streamID)
	}
	if frame.Opcode != opcode {
		t.Errorf("Opcode = 0x%02x, want 0x%02x", frame.Opcode, opcode)
	}
	if !bytes.Equal(frame.Body, body) {
		t.Errorf("Body mismatch")
	}
}

func TestConstants(t *testing.T) {
	// Verify constant values match the protocol spec
	if HeaderSize != 12 {
		t.Errorf("HeaderSize = %d, want 12", HeaderSize)
	}
	if MaxBodySize != 16*1024*1024 {
		t.Errorf("MaxBodySize = %d, want 16MB", MaxBodySize)
	}
	if VersionRequest != 0x01 {
		t.Errorf("VersionRequest = 0x%02x, want 0x01", VersionRequest)
	}
	if VersionResponse != 0x81 {
		t.Errorf("VersionResponse = 0x%02x, want 0x81", VersionResponse)
	}
}

func TestOpcodeValues(t *testing.T) {
	// Verify opcode values match the protocol spec
	requestOpcodes := map[string]uint8{
		"CreateSession":   0x01,
		"CloseSession":    0x02,
		"GetItem":         0x10,
		"PutItem":         0x11,
		"DeleteItem":      0x12,
		"UpdateItem":      0x13,
		"Query":           0x14,
		"Scan":            0x15,
		"BatchGetItem":    0x20,
		"BatchWriteItem":  0x21,
		"TransactGet":     0x22,
		"TransactWrite":   0x23,
		"CreateTable":     0x30,
		"DeleteTable":     0x31,
		"DescribeTable":   0x32,
		"ListTables":      0x33,
		"Shutdown":        0xFE,
	}

	actual := map[string]uint8{
		"CreateSession":   OpcodeCreateSession,
		"CloseSession":    OpcodeCloseSession,
		"GetItem":         OpcodeGetItem,
		"PutItem":         OpcodePutItem,
		"DeleteItem":      OpcodeDeleteItem,
		"UpdateItem":      OpcodeUpdateItem,
		"Query":           OpcodeQuery,
		"Scan":            OpcodeScan,
		"BatchGetItem":    OpcodeBatchGetItem,
		"BatchWriteItem":  OpcodeBatchWriteItem,
		"TransactGet":     OpcodeTransactGet,
		"TransactWrite":   OpcodeTransactWrite,
		"CreateTable":     OpcodeCreateTable,
		"DeleteTable":     OpcodeDeleteTable,
		"DescribeTable":   OpcodeDescribeTable,
		"ListTables":      OpcodeListTables,
		"Shutdown":        OpcodeShutdown,
	}

	for name, expected := range requestOpcodes {
		if actual[name] != expected {
			t.Errorf("Opcode%s = 0x%02x, want 0x%02x", name, actual[name], expected)
		}
	}

	// Response opcodes
	responseOpcodes := map[string]uint8{
		"Error":          0x00,
		"SessionCreated": 0x01,
		"SessionClosed":  0x02,
		"ItemResult":     0x10,
		"QueryResult":    0x14,
		"BatchResult":    0x20,
		"TransactResult": 0x22,
		"TableResult":    0x30,
		"ListResult":     0x33,
		"ShutdownAck":    0xFE,
	}

	actualResponse := map[string]uint8{
		"Error":          OpcodeError,
		"SessionCreated": OpcodeSessionCreated,
		"SessionClosed":  OpcodeSessionClosed,
		"ItemResult":     OpcodeItemResult,
		"QueryResult":    OpcodeQueryResult,
		"BatchResult":    OpcodeBatchResult,
		"TransactResult": OpcodeTransactResult,
		"TableResult":    OpcodeTableResult,
		"ListResult":     OpcodeListResult,
		"ShutdownAck":    OpcodeShutdownAck,
	}

	for name, expected := range responseOpcodes {
		if actualResponse[name] != expected {
			t.Errorf("Opcode%s = 0x%02x, want 0x%02x", name, actualResponse[name], expected)
		}
	}
}

func TestErrorCodeValues(t *testing.T) {
	errorCodes := map[string]uint32{
		"Unknown":               0x0000,
		"Protocol":              0x0001,
		"SessionNotFound":       0x0002,
		"Connection":            0x0003,
		"Timeout":               0x0004,
		"Overloaded":            0x0005,
		"ResourceNotFound":      0x1001,
		"ResourceInUse":         0x1002,
		"Validation":            0x1003,
		"ConditionalCheckFailed": 0x1004,
		"TransactionCanceled":   0x1005,
		"ProvisionedThroughput": 0x1006,
		"ItemCollectionSize":    0x1007,
		"LimitExceeded":         0x1008,
		"RequestLimitExceeded":  0x1009,
		"InternalServer":        0x100A,
		"ServiceUnavailable":    0x100B,
	}

	actual := map[string]uint32{
		"Unknown":               ErrorUnknown,
		"Protocol":              ErrorProtocol,
		"SessionNotFound":       ErrorSessionNotFound,
		"Connection":            ErrorConnection,
		"Timeout":               ErrorTimeout,
		"Overloaded":            ErrorOverloaded,
		"ResourceNotFound":      ErrorResourceNotFound,
		"ResourceInUse":         ErrorResourceInUse,
		"Validation":            ErrorValidation,
		"ConditionalCheckFailed": ErrorConditionalCheckFailed,
		"TransactionCanceled":   ErrorTransactionCanceled,
		"ProvisionedThroughput": ErrorProvisionedThroughput,
		"ItemCollectionSize":    ErrorItemCollectionSize,
		"LimitExceeded":         ErrorLimitExceeded,
		"RequestLimitExceeded":  ErrorRequestLimitExceeded,
		"InternalServer":        ErrorInternalServer,
		"ServiceUnavailable":    ErrorServiceUnavailable,
	}

	for name, expected := range errorCodes {
		if actual[name] != expected {
			t.Errorf("Error%s = 0x%04x, want 0x%04x", name, actual[name], expected)
		}
	}
}

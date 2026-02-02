package handler

import (
	"bytes"
	"context"
	"encoding/binary"
	"testing"

	"github.com/aws/aws-sdk-go-v2/service/dynamodb/types"
	"github.com/scylladb/latte/alternator-adapters/alternator-client-golang/internal/encoding"
	"github.com/scylladb/latte/alternator-adapters/alternator-client-golang/internal/protocol"
	"github.com/scylladb/latte/alternator-adapters/alternator-client-golang/internal/session"
)

func TestNew(t *testing.T) {
	registry := session.NewRegistry()
	h := New(registry)
	if h == nil {
		t.Fatal("New returned nil")
	}
}

func TestHandle_UnknownOpcode(t *testing.T) {
	registry := session.NewRegistry()
	h := New(registry)

	frame := &protocol.Frame{
		StreamID: 1,
		Opcode:   0xFF, // Unknown opcode
		Body:     nil,
	}

	var buf bytes.Buffer
	err := h.Handle(context.Background(), frame, &buf)
	if err != nil {
		t.Fatalf("Handle: %v", err)
	}

	// Parse the response
	resp := buf.Bytes()
	if len(resp) < protocol.HeaderSize {
		t.Fatalf("Response too short: %d bytes", len(resp))
	}

	// Should be an ERROR response
	if resp[0] != protocol.VersionResponse {
		t.Errorf("Response version = 0x%02x, want 0x%02x", resp[0], protocol.VersionResponse)
	}
	if resp[4] != protocol.OpcodeError {
		t.Errorf("Response opcode = 0x%02x, want 0x%02x (ERROR)", resp[4], protocol.OpcodeError)
	}

	// Parse error code
	r := protocol.NewReader(resp[protocol.HeaderSize:])
	code, _ := r.ReadUint32()
	if code != protocol.ErrorProtocol {
		t.Errorf("Error code = 0x%04x, want 0x%04x (PROTOCOL)", code, protocol.ErrorProtocol)
	}
}

func TestHandle_CreateSession(t *testing.T) {
	registry := session.NewRegistry()
	h := New(registry)

	// Build CREATE_SESSION request body
	body := protocol.NewBuffer(128)
	body.WriteUint16(2) // param count
	body.WriteString("endpoint")
	body.WriteString("http://localhost:8000")
	body.WriteString("region")
	body.WriteString("us-east-1")

	frame := &protocol.Frame{
		StreamID: 42,
		Opcode:   protocol.OpcodeCreateSession,
		Body:     body.Bytes(),
	}

	var buf bytes.Buffer
	err := h.Handle(context.Background(), frame, &buf)
	if err != nil {
		t.Fatalf("Handle: %v", err)
	}

	// Parse the response
	resp := buf.Bytes()
	if len(resp) < protocol.HeaderSize {
		t.Fatalf("Response too short: %d bytes", len(resp))
	}

	// Should be SESSION_CREATED response
	if resp[4] != protocol.OpcodeSessionCreated {
		t.Errorf("Response opcode = 0x%02x, want 0x%02x (SESSION_CREATED)", resp[4], protocol.OpcodeSessionCreated)
	}

	// Stream ID should match
	streamID := int16(binary.BigEndian.Uint16(resp[2:4]))
	if streamID != 42 {
		t.Errorf("Stream ID = %d, want 42", streamID)
	}

	// Parse session ID from body
	r := protocol.NewReader(resp[protocol.HeaderSize:])
	sessionID, err := r.ReadUint64()
	if err != nil {
		t.Fatalf("Read session ID: %v", err)
	}
	if sessionID == 0 {
		t.Error("Session ID should not be 0")
	}

	// Verify session exists in registry
	if registry.Count() != 1 {
		t.Errorf("Registry count = %d, want 1", registry.Count())
	}
	_, ok := registry.Get(sessionID)
	if !ok {
		t.Error("Session not found in registry")
	}
}

func TestHandle_CloseSession(t *testing.T) {
	registry := session.NewRegistry()
	h := New(registry)
	ctx := context.Background()

	// Create a session first
	sess, _ := registry.Create(ctx, &session.Config{
		Endpoint: "http://localhost:8000",
		Region:   "us-east-1",
	})

	// Build CLOSE_SESSION request body
	body := protocol.NewBuffer(8)
	body.WriteUint64(sess.ID)

	frame := &protocol.Frame{
		StreamID: 1,
		Opcode:   protocol.OpcodeCloseSession,
		Body:     body.Bytes(),
	}

	var buf bytes.Buffer
	err := h.Handle(ctx, frame, &buf)
	if err != nil {
		t.Fatalf("Handle: %v", err)
	}

	// Should be SESSION_CLOSED response
	resp := buf.Bytes()
	if resp[4] != protocol.OpcodeSessionClosed {
		t.Errorf("Response opcode = 0x%02x, want 0x%02x (SESSION_CLOSED)", resp[4], protocol.OpcodeSessionClosed)
	}

	// Session should no longer exist
	if registry.Count() != 0 {
		t.Errorf("Registry count = %d, want 0", registry.Count())
	}
}

func TestHandle_CloseSession_NotFound(t *testing.T) {
	registry := session.NewRegistry()
	h := New(registry)

	// Build CLOSE_SESSION request with non-existent session ID
	body := protocol.NewBuffer(8)
	body.WriteUint64(99999)

	frame := &protocol.Frame{
		StreamID: 1,
		Opcode:   protocol.OpcodeCloseSession,
		Body:     body.Bytes(),
	}

	var buf bytes.Buffer
	err := h.Handle(context.Background(), frame, &buf)
	if err != nil {
		t.Fatalf("Handle: %v", err)
	}

	// Should be ERROR response with SESSION_NOT_FOUND
	resp := buf.Bytes()
	if resp[4] != protocol.OpcodeError {
		t.Errorf("Response opcode = 0x%02x, want 0x%02x (ERROR)", resp[4], protocol.OpcodeError)
	}

	r := protocol.NewReader(resp[protocol.HeaderSize:])
	code, _ := r.ReadUint32()
	if code != protocol.ErrorSessionNotFound {
		t.Errorf("Error code = 0x%04x, want 0x%04x (SESSION_NOT_FOUND)", code, protocol.ErrorSessionNotFound)
	}
}

func TestHandle_Shutdown(t *testing.T) {
	registry := session.NewRegistry()
	h := New(registry)
	ctx := context.Background()

	// Create some sessions
	registry.Create(ctx, &session.Config{Endpoint: "http://localhost:8000", Region: "us-east-1"})
	registry.Create(ctx, &session.Config{Endpoint: "http://localhost:8000", Region: "us-east-1"})

	if registry.Count() != 2 {
		t.Fatalf("Initial count = %d, want 2", registry.Count())
	}

	frame := &protocol.Frame{
		StreamID: 1,
		Opcode:   protocol.OpcodeShutdown,
		Body:     nil,
	}

	var buf bytes.Buffer
	err := h.Handle(ctx, frame, &buf)
	if err != nil {
		t.Fatalf("Handle: %v", err)
	}

	// Should be SHUTDOWN_ACK response
	resp := buf.Bytes()
	if resp[4] != protocol.OpcodeShutdownAck {
		t.Errorf("Response opcode = 0x%02x, want 0x%02x (SHUTDOWN_ACK)", resp[4], protocol.OpcodeShutdownAck)
	}

	// All sessions should be closed
	if registry.Count() != 0 {
		t.Errorf("Registry count after shutdown = %d, want 0", registry.Count())
	}
}

func TestHandle_GetItem_SessionNotFound(t *testing.T) {
	registry := session.NewRegistry()
	h := New(registry)

	// Build GET_ITEM request with non-existent session
	body := protocol.NewBuffer(256)
	body.WriteUint64(99999) // non-existent session
	body.WriteString("TestTable")
	// Key
	body.WriteUint16(1)
	body.WriteString("pk")
	body.WriteUint8(encoding.TypeString)
	body.WriteString("test")
	// Consistent read
	body.WriteBool(false)
	// Projection expression (absent)
	body.WriteByte(0x00)
	// Expression attribute names (empty)
	body.WriteUint16(0)

	frame := &protocol.Frame{
		StreamID: 1,
		Opcode:   protocol.OpcodeGetItem,
		Body:     body.Bytes(),
	}

	var buf bytes.Buffer
	err := h.Handle(context.Background(), frame, &buf)
	if err != nil {
		t.Fatalf("Handle: %v", err)
	}

	resp := buf.Bytes()
	if resp[4] != protocol.OpcodeError {
		t.Errorf("Response opcode = 0x%02x, want 0x%02x (ERROR)", resp[4], protocol.OpcodeError)
	}

	r := protocol.NewReader(resp[protocol.HeaderSize:])
	code, _ := r.ReadUint32()
	if code != protocol.ErrorSessionNotFound {
		t.Errorf("Error code = 0x%04x, want 0x%04x (SESSION_NOT_FOUND)", code, protocol.ErrorSessionNotFound)
	}
}

func TestMapReturnValue(t *testing.T) {
	tests := []struct {
		input    uint8
		expected types.ReturnValue
	}{
		{0, types.ReturnValueNone},
		{1, types.ReturnValueAllOld},
		{99, types.ReturnValueNone}, // Default
	}

	for _, tt := range tests {
		result := mapReturnValue(tt.input)
		if result != tt.expected {
			t.Errorf("mapReturnValue(%d) = %v, want %v", tt.input, result, tt.expected)
		}
	}
}

func TestMapUpdateReturnValue(t *testing.T) {
	tests := []struct {
		input    uint8
		expected types.ReturnValue
	}{
		{0, types.ReturnValueNone},
		{1, types.ReturnValueAllOld},
		{2, types.ReturnValueUpdatedOld},
		{3, types.ReturnValueAllNew},
		{4, types.ReturnValueUpdatedNew},
		{99, types.ReturnValueNone}, // Default
	}

	for _, tt := range tests {
		result := mapUpdateReturnValue(tt.input)
		if result != tt.expected {
			t.Errorf("mapUpdateReturnValue(%d) = %v, want %v", tt.input, result, tt.expected)
		}
	}
}

func TestWriteItemResult(t *testing.T) {
	// Test with no item
	t.Run("NoItem", func(t *testing.T) {
		var buf bytes.Buffer
		err := writeItemResult(&buf, 1, nil, 1000)
		if err != nil {
			t.Fatalf("writeItemResult: %v", err)
		}

		resp := buf.Bytes()
		if resp[4] != protocol.OpcodeItemResult {
			t.Errorf("Response opcode = 0x%02x, want 0x%02x", resp[4], protocol.OpcodeItemResult)
		}

		r := protocol.NewReader(resp[protocol.HeaderSize:])
		hasItem, _ := r.ReadBool()
		if hasItem {
			t.Error("has_item = true, want false")
		}
	})

	// Test with item
	t.Run("WithItem", func(t *testing.T) {
		item := map[string]types.AttributeValue{
			"id":   &types.AttributeValueMemberS{Value: "test123"},
			"name": &types.AttributeValueMemberS{Value: "Test Name"},
		}

		var buf bytes.Buffer
		err := writeItemResult(&buf, 1, item, 5000)
		if err != nil {
			t.Fatalf("writeItemResult: %v", err)
		}

		resp := buf.Bytes()
		r := protocol.NewReader(resp[protocol.HeaderSize:])
		hasItem, _ := r.ReadBool()
		if !hasItem {
			t.Error("has_item = false, want true")
		}

		// Read item
		resultItem, err := encoding.ReadItem(r)
		if err != nil {
			t.Fatalf("ReadItem: %v", err)
		}
		if len(resultItem) != 2 {
			t.Errorf("Item length = %d, want 2", len(resultItem))
		}
	})
}

func TestWriteQueryResult(t *testing.T) {
	items := []map[string]types.AttributeValue{
		{"id": &types.AttributeValueMemberS{Value: "item1"}},
		{"id": &types.AttributeValueMemberS{Value: "item2"}},
	}

	lastKey := map[string]types.AttributeValue{
		"pk": &types.AttributeValueMemberS{Value: "lastpk"},
	}

	var buf bytes.Buffer
	err := writeQueryResult(&buf, 1, items, lastKey, 2, 10000)
	if err != nil {
		t.Fatalf("writeQueryResult: %v", err)
	}

	resp := buf.Bytes()
	if resp[4] != protocol.OpcodeQueryResult {
		t.Errorf("Response opcode = 0x%02x, want 0x%02x", resp[4], protocol.OpcodeQueryResult)
	}

	r := protocol.NewReader(resp[protocol.HeaderSize:])

	// Item count
	count, _ := r.ReadUint32()
	if count != 2 {
		t.Errorf("Item count = %d, want 2", count)
	}

	// Skip items
	for i := uint32(0); i < count; i++ {
		_, err := encoding.ReadItem(r)
		if err != nil {
			t.Fatalf("ReadItem: %v", err)
		}
	}

	// LastEvaluatedKey
	lastKeyResult, err := encoding.ReadOptionalKey(r)
	if err != nil {
		t.Fatalf("ReadOptionalKey: %v", err)
	}
	if lastKeyResult == nil {
		t.Error("LastEvaluatedKey should not be nil")
	}

	// Scanned count
	scannedCount, _ := r.ReadUint32()
	if scannedCount != 2 {
		t.Errorf("Scanned count = %d, want 2", scannedCount)
	}
}

func TestWriteBatchGetResult(t *testing.T) {
	responses := map[string][]map[string]types.AttributeValue{
		"Table1": {
			{"id": &types.AttributeValueMemberS{Value: "item1"}},
			{"id": &types.AttributeValueMemberS{Value: "item2"}},
		},
		"Table2": {
			{"id": &types.AttributeValueMemberS{Value: "item3"}},
		},
	}

	var buf bytes.Buffer
	err := writeBatchGetResult(&buf, 1, responses, nil, 5000)
	if err != nil {
		t.Fatalf("writeBatchGetResult: %v", err)
	}

	resp := buf.Bytes()
	if resp[4] != protocol.OpcodeBatchResult {
		t.Errorf("Response opcode = 0x%02x, want 0x%02x", resp[4], protocol.OpcodeBatchResult)
	}

	r := protocol.NewReader(resp[protocol.HeaderSize:])

	// Table count in responses
	tableCount, _ := r.ReadUint16()
	if tableCount != 2 {
		t.Errorf("Table count = %d, want 2", tableCount)
	}
}

func TestWriteBatchWriteResult(t *testing.T) {
	// Empty unprocessed items
	var buf bytes.Buffer
	err := writeBatchWriteResult(&buf, 1, nil, 5000)
	if err != nil {
		t.Fatalf("writeBatchWriteResult: %v", err)
	}

	resp := buf.Bytes()
	if resp[4] != protocol.OpcodeBatchResult {
		t.Errorf("Response opcode = 0x%02x, want 0x%02x", resp[4], protocol.OpcodeBatchResult)
	}
}

func TestWriteProtocolError(t *testing.T) {
	var buf bytes.Buffer
	err := writeProtocolError(&buf, 42, "test error message")
	if err != nil {
		t.Fatalf("writeProtocolError: %v", err)
	}

	resp := buf.Bytes()
	if resp[4] != protocol.OpcodeError {
		t.Errorf("Response opcode = 0x%02x, want 0x%02x", resp[4], protocol.OpcodeError)
	}

	// Verify stream ID
	streamID := int16(binary.BigEndian.Uint16(resp[2:4]))
	if streamID != 42 {
		t.Errorf("Stream ID = %d, want 42", streamID)
	}

	r := protocol.NewReader(resp[protocol.HeaderSize:])
	code, _ := r.ReadUint32()
	if code != protocol.ErrorProtocol {
		t.Errorf("Error code = 0x%04x, want 0x%04x", code, protocol.ErrorProtocol)
	}

	errType, _ := r.ReadString()
	if errType != "ProtocolError" {
		t.Errorf("Error type = %q, want %q", errType, "ProtocolError")
	}

	msg, _ := r.ReadString()
	if msg != "test error message" {
		t.Errorf("Error message = %q, want %q", msg, "test error message")
	}
}

func TestWriteSessionNotFound(t *testing.T) {
	var buf bytes.Buffer
	err := writeSessionNotFound(&buf, 1, 12345)
	if err != nil {
		t.Fatalf("writeSessionNotFound: %v", err)
	}

	resp := buf.Bytes()
	r := protocol.NewReader(resp[protocol.HeaderSize:])
	code, _ := r.ReadUint32()
	if code != protocol.ErrorSessionNotFound {
		t.Errorf("Error code = 0x%04x, want 0x%04x", code, protocol.ErrorSessionNotFound)
	}

	errType, _ := r.ReadString()
	if errType != "SessionNotFound" {
		t.Errorf("Error type = %q, want %q", errType, "SessionNotFound")
	}
}

func TestHandle_AllOpcodeDispatch(t *testing.T) {
	registry := session.NewRegistry()
	h := New(registry)

	// Test that all known opcodes are dispatched (even if they fail due to missing session)
	opcodes := []struct {
		name   string
		opcode uint8
	}{
		{"CreateSession", protocol.OpcodeCreateSession},
		{"CloseSession", protocol.OpcodeCloseSession},
		{"GetItem", protocol.OpcodeGetItem},
		{"PutItem", protocol.OpcodePutItem},
		{"DeleteItem", protocol.OpcodeDeleteItem},
		{"UpdateItem", protocol.OpcodeUpdateItem},
		{"Query", protocol.OpcodeQuery},
		{"Scan", protocol.OpcodeScan},
		{"BatchGetItem", protocol.OpcodeBatchGetItem},
		{"BatchWriteItem", protocol.OpcodeBatchWriteItem},
		{"Shutdown", protocol.OpcodeShutdown},
	}

	for _, tt := range opcodes {
		t.Run(tt.name, func(t *testing.T) {
			// Build minimal request body (will likely fail due to invalid data)
			body := protocol.NewBuffer(256)
			if tt.opcode == protocol.OpcodeCreateSession {
				body.WriteUint16(0) // empty params
			} else if tt.opcode != protocol.OpcodeShutdown {
				body.WriteUint64(0) // session ID 0
			}

			frame := &protocol.Frame{
				StreamID: 1,
				Opcode:   tt.opcode,
				Body:     body.Bytes(),
			}

			var buf bytes.Buffer
			err := h.Handle(context.Background(), frame, &buf)
			if err != nil {
				t.Fatalf("Handle: %v", err)
			}

			// Should have written some response
			if buf.Len() < protocol.HeaderSize {
				t.Errorf("Response too short for opcode %s", tt.name)
			}
		})
	}
}

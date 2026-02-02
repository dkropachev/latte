//go:build integration
// +build integration

// Package handler integration tests require DynamoDB Local or Alternator running.
// Run with: go test -tags=integration -v ./internal/handler/...
//
// Start DynamoDB Local with:
//   docker run -p 8000:8000 amazon/dynamodb-local
//
// Or Alternator with:
//   docker run -p 8000:8000 scylladb/scylla --alternator-port 8000
package handler

import (
	"bytes"
	"context"
	"os"
	"testing"
	"time"

	"github.com/aws/aws-sdk-go-v2/aws"
	"github.com/aws/aws-sdk-go-v2/service/dynamodb"
	"github.com/aws/aws-sdk-go-v2/service/dynamodb/types"
	"github.com/scylladb/latte/alternator-adapters/alternator-client-golang/internal/encoding"
	"github.com/scylladb/latte/alternator-adapters/alternator-client-golang/internal/protocol"
	"github.com/scylladb/latte/alternator-adapters/alternator-client-golang/internal/session"
)

const (
	testTableName = "IntegrationTestTable"
)

func getEndpoint() string {
	if ep := os.Getenv("LATTE_TEST_ENDPOINT"); ep != "" {
		return ep
	}
	return "http://localhost:8000"
}

func setupTestTable(t *testing.T, registry *session.Registry) uint64 {
	ctx := context.Background()

	// Create session
	cfg := &session.Config{
		Endpoint: getEndpoint(),
		Region:   "us-east-1",
	}
	sess, err := registry.Create(ctx, cfg)
	if err != nil {
		t.Fatalf("Failed to create session: %v", err)
	}

	// Create table
	_, err = sess.Client.CreateTable(ctx, &dynamodb.CreateTableInput{
		TableName: aws.String(testTableName),
		KeySchema: []types.KeySchemaElement{
			{AttributeName: aws.String("pk"), KeyType: types.KeyTypeHash},
			{AttributeName: aws.String("sk"), KeyType: types.KeyTypeRange},
		},
		AttributeDefinitions: []types.AttributeDefinition{
			{AttributeName: aws.String("pk"), AttributeType: types.ScalarAttributeTypeS},
			{AttributeName: aws.String("sk"), AttributeType: types.ScalarAttributeTypeS},
		},
		BillingMode: types.BillingModePayPerRequest,
	})
	if err != nil {
		// Table might already exist, that's ok
		t.Logf("CreateTable: %v (may already exist)", err)
	}

	// Wait for table to be ready
	time.Sleep(500 * time.Millisecond)

	return sess.ID
}

func cleanupTestTable(t *testing.T, registry *session.Registry, sessionID uint64) {
	sess, ok := registry.Get(sessionID)
	if !ok {
		return
	}

	ctx := context.Background()
	_, err := sess.Client.DeleteTable(ctx, &dynamodb.DeleteTableInput{
		TableName: aws.String(testTableName),
	})
	if err != nil {
		t.Logf("DeleteTable: %v", err)
	}

	registry.Close(sessionID)
}

func TestIntegration_SessionLifecycle(t *testing.T) {
	registry := session.NewRegistry()
	h := New(registry)
	ctx := context.Background()

	// Create session
	createBody := protocol.NewBuffer(128)
	createBody.WriteUint16(2)
	createBody.WriteString("endpoint")
	createBody.WriteString(getEndpoint())
	createBody.WriteString("region")
	createBody.WriteString("us-east-1")

	createFrame := &protocol.Frame{
		StreamID: 1,
		Opcode:   protocol.OpcodeCreateSession,
		Body:     createBody.Bytes(),
	}

	var buf bytes.Buffer
	err := h.Handle(ctx, createFrame, &buf)
	if err != nil {
		t.Fatalf("Handle CreateSession: %v", err)
	}

	resp := buf.Bytes()
	if resp[4] != protocol.OpcodeSessionCreated {
		t.Fatalf("Expected SESSION_CREATED, got 0x%02x", resp[4])
	}

	// Extract session ID
	r := protocol.NewReader(resp[protocol.HeaderSize:])
	sessionID, _ := r.ReadUint64()

	if registry.Count() != 1 {
		t.Errorf("Registry count = %d, want 1", registry.Count())
	}

	// Close session
	closeBody := protocol.NewBuffer(8)
	closeBody.WriteUint64(sessionID)

	closeFrame := &protocol.Frame{
		StreamID: 2,
		Opcode:   protocol.OpcodeCloseSession,
		Body:     closeBody.Bytes(),
	}

	buf.Reset()
	err = h.Handle(ctx, closeFrame, &buf)
	if err != nil {
		t.Fatalf("Handle CloseSession: %v", err)
	}

	resp = buf.Bytes()
	if resp[4] != protocol.OpcodeSessionClosed {
		t.Fatalf("Expected SESSION_CLOSED, got 0x%02x", resp[4])
	}

	if registry.Count() != 0 {
		t.Errorf("Registry count after close = %d, want 0", registry.Count())
	}

	// Verify session is gone
	_, ok := registry.Get(sessionID)
	if ok {
		t.Error("Session should not exist after close")
	}
}

func TestIntegration_PutGetDeleteItem(t *testing.T) {
	registry := session.NewRegistry()
	h := New(registry)
	ctx := context.Background()

	sessionID := setupTestTable(t, registry)
	defer cleanupTestTable(t, registry, sessionID)

	// PUT_ITEM
	t.Run("PutItem", func(t *testing.T) {
		body := protocol.NewBuffer(256)
		body.WriteUint64(sessionID)
		body.WriteString(testTableName)

		// Item
		item := map[string]types.AttributeValue{
			"pk":   &types.AttributeValueMemberS{Value: "user1"},
			"sk":   &types.AttributeValueMemberS{Value: "profile"},
			"name": &types.AttributeValueMemberS{Value: "Test User"},
			"age":  &types.AttributeValueMemberN{Value: "30"},
		}
		encoding.WriteItem(body, item)

		// No condition expression
		body.WriteByte(0x00)
		// Return values: NONE
		body.WriteUint8(0)

		frame := &protocol.Frame{
			StreamID: 1,
			Opcode:   protocol.OpcodePutItem,
			Body:     body.Bytes(),
		}

		var buf bytes.Buffer
		err := h.Handle(ctx, frame, &buf)
		if err != nil {
			t.Fatalf("Handle PutItem: %v", err)
		}

		resp := buf.Bytes()
		if resp[4] != protocol.OpcodeItemResult {
			r := protocol.NewReader(resp[protocol.HeaderSize:])
			code, _ := r.ReadUint32()
			errType, _ := r.ReadString()
			errMsg, _ := r.ReadString()
			t.Fatalf("Expected ITEM_RESULT, got 0x%02x (error: %d %s: %s)", resp[4], code, errType, errMsg)
		}

		// Verify latency is present
		r := protocol.NewReader(resp[protocol.HeaderSize:])
		r.ReadBool()  // has_item
		r.ReadByte()  // consumed capacity (absent)
		latency, _ := r.ReadInt64()
		if latency <= 0 {
			t.Error("Latency should be > 0")
		}
	})

	// GET_ITEM
	t.Run("GetItem", func(t *testing.T) {
		body := protocol.NewBuffer(256)
		body.WriteUint64(sessionID)
		body.WriteString(testTableName)

		// Key
		body.WriteUint16(2)
		body.WriteString("pk")
		body.WriteUint8(encoding.TypeString)
		body.WriteString("user1")
		body.WriteString("sk")
		body.WriteUint8(encoding.TypeString)
		body.WriteString("profile")

		// Consistent read
		body.WriteBool(true)
		// No projection expression
		body.WriteByte(0x00)
		// No expression attribute names
		body.WriteUint16(0)

		frame := &protocol.Frame{
			StreamID: 1,
			Opcode:   protocol.OpcodeGetItem,
			Body:     body.Bytes(),
		}

		var buf bytes.Buffer
		err := h.Handle(ctx, frame, &buf)
		if err != nil {
			t.Fatalf("Handle GetItem: %v", err)
		}

		resp := buf.Bytes()
		if resp[4] != protocol.OpcodeItemResult {
			t.Fatalf("Expected ITEM_RESULT, got 0x%02x", resp[4])
		}

		r := protocol.NewReader(resp[protocol.HeaderSize:])
		hasItem, _ := r.ReadBool()
		if !hasItem {
			t.Fatal("Expected to find item")
		}

		item, err := encoding.ReadItem(r)
		if err != nil {
			t.Fatalf("ReadItem: %v", err)
		}

		// Verify item contents
		if s, ok := item["name"].(*types.AttributeValueMemberS); !ok || s.Value != "Test User" {
			t.Errorf("Item[name] = %v, want 'Test User'", item["name"])
		}
		if n, ok := item["age"].(*types.AttributeValueMemberN); !ok || n.Value != "30" {
			t.Errorf("Item[age] = %v, want '30'", item["age"])
		}
	})

	// DELETE_ITEM
	t.Run("DeleteItem", func(t *testing.T) {
		body := protocol.NewBuffer(256)
		body.WriteUint64(sessionID)
		body.WriteString(testTableName)

		// Key
		body.WriteUint16(2)
		body.WriteString("pk")
		body.WriteUint8(encoding.TypeString)
		body.WriteString("user1")
		body.WriteString("sk")
		body.WriteUint8(encoding.TypeString)
		body.WriteString("profile")

		// No condition expression
		body.WriteByte(0x00)
		// Return values: ALL_OLD
		body.WriteUint8(1)

		frame := &protocol.Frame{
			StreamID: 1,
			Opcode:   protocol.OpcodeDeleteItem,
			Body:     body.Bytes(),
		}

		var buf bytes.Buffer
		err := h.Handle(ctx, frame, &buf)
		if err != nil {
			t.Fatalf("Handle DeleteItem: %v", err)
		}

		resp := buf.Bytes()
		if resp[4] != protocol.OpcodeItemResult {
			t.Fatalf("Expected ITEM_RESULT, got 0x%02x", resp[4])
		}

		r := protocol.NewReader(resp[protocol.HeaderSize:])
		hasItem, _ := r.ReadBool()
		if !hasItem {
			t.Error("Expected ALL_OLD to return the deleted item")
		}
	})

	// Verify item is deleted
	t.Run("VerifyDeleted", func(t *testing.T) {
		body := protocol.NewBuffer(256)
		body.WriteUint64(sessionID)
		body.WriteString(testTableName)

		// Key
		body.WriteUint16(2)
		body.WriteString("pk")
		body.WriteUint8(encoding.TypeString)
		body.WriteString("user1")
		body.WriteString("sk")
		body.WriteUint8(encoding.TypeString)
		body.WriteString("profile")

		body.WriteBool(true)
		body.WriteByte(0x00)
		body.WriteUint16(0)

		frame := &protocol.Frame{
			StreamID: 1,
			Opcode:   protocol.OpcodeGetItem,
			Body:     body.Bytes(),
		}

		var buf bytes.Buffer
		h.Handle(ctx, frame, &buf)

		resp := buf.Bytes()
		r := protocol.NewReader(resp[protocol.HeaderSize:])
		hasItem, _ := r.ReadBool()
		if hasItem {
			t.Error("Item should have been deleted")
		}
	})
}

func TestIntegration_UpdateItem(t *testing.T) {
	registry := session.NewRegistry()
	h := New(registry)
	ctx := context.Background()

	sessionID := setupTestTable(t, registry)
	defer cleanupTestTable(t, registry, sessionID)

	// First put an item
	putBody := protocol.NewBuffer(256)
	putBody.WriteUint64(sessionID)
	putBody.WriteString(testTableName)
	item := map[string]types.AttributeValue{
		"pk":    &types.AttributeValueMemberS{Value: "user2"},
		"sk":    &types.AttributeValueMemberS{Value: "data"},
		"count": &types.AttributeValueMemberN{Value: "0"},
	}
	encoding.WriteItem(putBody, item)
	putBody.WriteByte(0x00)
	putBody.WriteUint8(0)

	putFrame := &protocol.Frame{
		StreamID: 1,
		Opcode:   protocol.OpcodePutItem,
		Body:     putBody.Bytes(),
	}

	var buf bytes.Buffer
	h.Handle(ctx, putFrame, &buf)

	// Now update the item
	body := protocol.NewBuffer(512)
	body.WriteUint64(sessionID)
	body.WriteString(testTableName)

	// Key
	body.WriteUint16(2)
	body.WriteString("pk")
	body.WriteUint8(encoding.TypeString)
	body.WriteString("user2")
	body.WriteString("sk")
	body.WriteUint8(encoding.TypeString)
	body.WriteString("data")

	// Update expression
	body.WriteString("SET #c = #c + :incr")

	// No condition expression
	body.WriteByte(0x00)

	// Expression attribute names
	body.WriteUint16(1)
	body.WriteString("#c")
	body.WriteString("count")

	// Expression attribute values
	body.WriteUint16(1)
	body.WriteString(":incr")
	body.WriteUint8(encoding.TypeNumber)
	body.WriteString("5")

	// Return values: ALL_NEW
	body.WriteUint8(3)

	frame := &protocol.Frame{
		StreamID: 1,
		Opcode:   protocol.OpcodeUpdateItem,
		Body:     body.Bytes(),
	}

	buf.Reset()
	err := h.Handle(ctx, frame, &buf)
	if err != nil {
		t.Fatalf("Handle UpdateItem: %v", err)
	}

	resp := buf.Bytes()
	if resp[4] != protocol.OpcodeItemResult {
		r := protocol.NewReader(resp[protocol.HeaderSize:])
		code, _ := r.ReadUint32()
		errType, _ := r.ReadString()
		errMsg, _ := r.ReadString()
		t.Fatalf("Expected ITEM_RESULT, got 0x%02x (error: %d %s: %s)", resp[4], code, errType, errMsg)
	}

	r := protocol.NewReader(resp[protocol.HeaderSize:])
	hasItem, _ := r.ReadBool()
	if !hasItem {
		t.Fatal("Expected ALL_NEW to return the updated item")
	}

	updatedItem, err := encoding.ReadItem(r)
	if err != nil {
		t.Fatalf("ReadItem: %v", err)
	}

	// Verify count was incremented
	if n, ok := updatedItem["count"].(*types.AttributeValueMemberN); !ok || n.Value != "5" {
		t.Errorf("Updated count = %v, want '5'", updatedItem["count"])
	}
}

func TestIntegration_Query(t *testing.T) {
	registry := session.NewRegistry()
	h := New(registry)
	ctx := context.Background()

	sessionID := setupTestTable(t, registry)
	defer cleanupTestTable(t, registry, sessionID)

	// Insert multiple items
	for i := 0; i < 5; i++ {
		body := protocol.NewBuffer(256)
		body.WriteUint64(sessionID)
		body.WriteString(testTableName)
		item := map[string]types.AttributeValue{
			"pk":   &types.AttributeValueMemberS{Value: "partition1"},
			"sk":   &types.AttributeValueMemberS{Value: string('a' + byte(i))},
			"data": &types.AttributeValueMemberS{Value: "item"},
		}
		encoding.WriteItem(body, item)
		body.WriteByte(0x00)
		body.WriteUint8(0)

		frame := &protocol.Frame{StreamID: 1, Opcode: protocol.OpcodePutItem, Body: body.Bytes()}
		var buf bytes.Buffer
		h.Handle(ctx, frame, &buf)
	}

	// Query
	body := protocol.NewBuffer(512)
	body.WriteUint64(sessionID)
	body.WriteString(testTableName)

	// Index name (absent)
	body.WriteByte(0x00)

	// Key condition expression
	body.WriteString("pk = :pk")

	// Filter expression (absent)
	body.WriteByte(0x00)

	// Projection expression (absent)
	body.WriteByte(0x00)

	// Expression attribute names (empty)
	body.WriteUint16(0)

	// Expression attribute values
	body.WriteUint16(1)
	body.WriteString(":pk")
	body.WriteUint8(encoding.TypeString)
	body.WriteString("partition1")

	// Limit (absent)
	body.WriteByte(0x00)

	// Consistent read
	body.WriteBool(true)

	// Scan forward
	body.WriteBool(true)

	// Exclusive start key (absent)
	body.WriteByte(0x00)

	frame := &protocol.Frame{
		StreamID: 1,
		Opcode:   protocol.OpcodeQuery,
		Body:     body.Bytes(),
	}

	var buf bytes.Buffer
	err := h.Handle(ctx, frame, &buf)
	if err != nil {
		t.Fatalf("Handle Query: %v", err)
	}

	resp := buf.Bytes()
	if resp[4] != protocol.OpcodeQueryResult {
		r := protocol.NewReader(resp[protocol.HeaderSize:])
		code, _ := r.ReadUint32()
		errType, _ := r.ReadString()
		errMsg, _ := r.ReadString()
		t.Fatalf("Expected QUERY_RESULT, got 0x%02x (error: %d %s: %s)", resp[4], code, errType, errMsg)
	}

	r := protocol.NewReader(resp[protocol.HeaderSize:])
	itemCount, _ := r.ReadUint32()
	if itemCount != 5 {
		t.Errorf("Item count = %d, want 5", itemCount)
	}
}

func TestIntegration_Scan(t *testing.T) {
	registry := session.NewRegistry()
	h := New(registry)
	ctx := context.Background()

	sessionID := setupTestTable(t, registry)
	defer cleanupTestTable(t, registry, sessionID)

	// Insert items
	for i := 0; i < 3; i++ {
		body := protocol.NewBuffer(256)
		body.WriteUint64(sessionID)
		body.WriteString(testTableName)
		item := map[string]types.AttributeValue{
			"pk":   &types.AttributeValueMemberS{Value: "scan-test"},
			"sk":   &types.AttributeValueMemberS{Value: string('a' + byte(i))},
			"type": &types.AttributeValueMemberS{Value: "scannable"},
		}
		encoding.WriteItem(body, item)
		body.WriteByte(0x00)
		body.WriteUint8(0)

		frame := &protocol.Frame{StreamID: 1, Opcode: protocol.OpcodePutItem, Body: body.Bytes()}
		var buf bytes.Buffer
		h.Handle(ctx, frame, &buf)
	}

	// Scan
	body := protocol.NewBuffer(512)
	body.WriteUint64(sessionID)
	body.WriteString(testTableName)

	// Index name (absent)
	body.WriteByte(0x00)

	// Filter expression
	body.WriteByte(0x01)
	body.WriteString("#t = :t")

	// Projection expression (absent)
	body.WriteByte(0x00)

	// Expression attribute names
	body.WriteUint16(1)
	body.WriteString("#t")
	body.WriteString("type")

	// Expression attribute values
	body.WriteUint16(1)
	body.WriteString(":t")
	body.WriteUint8(encoding.TypeString)
	body.WriteString("scannable")

	// Limit (absent)
	body.WriteByte(0x00)

	// Consistent read
	body.WriteBool(false)

	// Segment (absent)
	body.WriteByte(0x00)

	// Total segments (absent)
	body.WriteByte(0x00)

	// Exclusive start key (absent)
	body.WriteByte(0x00)

	frame := &protocol.Frame{
		StreamID: 1,
		Opcode:   protocol.OpcodeScan,
		Body:     body.Bytes(),
	}

	var buf bytes.Buffer
	err := h.Handle(ctx, frame, &buf)
	if err != nil {
		t.Fatalf("Handle Scan: %v", err)
	}

	resp := buf.Bytes()
	if resp[4] != protocol.OpcodeQueryResult {
		r := protocol.NewReader(resp[protocol.HeaderSize:])
		code, _ := r.ReadUint32()
		errType, _ := r.ReadString()
		errMsg, _ := r.ReadString()
		t.Fatalf("Expected QUERY_RESULT, got 0x%02x (error: %d %s: %s)", resp[4], code, errType, errMsg)
	}

	r := protocol.NewReader(resp[protocol.HeaderSize:])
	itemCount, _ := r.ReadUint32()
	if itemCount != 3 {
		t.Errorf("Item count = %d, want 3", itemCount)
	}
}

func TestIntegration_BatchGetItem(t *testing.T) {
	registry := session.NewRegistry()
	h := New(registry)
	ctx := context.Background()

	sessionID := setupTestTable(t, registry)
	defer cleanupTestTable(t, registry, sessionID)

	// Insert items
	for i := 0; i < 3; i++ {
		body := protocol.NewBuffer(256)
		body.WriteUint64(sessionID)
		body.WriteString(testTableName)
		item := map[string]types.AttributeValue{
			"pk": &types.AttributeValueMemberS{Value: "batch"},
			"sk": &types.AttributeValueMemberS{Value: string('a' + byte(i))},
		}
		encoding.WriteItem(body, item)
		body.WriteByte(0x00)
		body.WriteUint8(0)

		frame := &protocol.Frame{StreamID: 1, Opcode: protocol.OpcodePutItem, Body: body.Bytes()}
		var buf bytes.Buffer
		h.Handle(ctx, frame, &buf)
	}

	// BatchGetItem
	body := protocol.NewBuffer(512)
	body.WriteUint64(sessionID)

	// Table count
	body.WriteUint16(1)

	// Table name
	body.WriteString(testTableName)

	// Key count
	body.WriteUint32(2)

	// Key 1
	body.WriteUint16(2)
	body.WriteString("pk")
	body.WriteUint8(encoding.TypeString)
	body.WriteString("batch")
	body.WriteString("sk")
	body.WriteUint8(encoding.TypeString)
	body.WriteString("a")

	// Key 2
	body.WriteUint16(2)
	body.WriteString("pk")
	body.WriteUint8(encoding.TypeString)
	body.WriteString("batch")
	body.WriteString("sk")
	body.WriteUint8(encoding.TypeString)
	body.WriteString("b")

	// Consistent read
	body.WriteBool(true)

	// Projection expression (absent)
	body.WriteByte(0x00)

	// Expression attribute names (empty)
	body.WriteUint16(0)

	frame := &protocol.Frame{
		StreamID: 1,
		Opcode:   protocol.OpcodeBatchGetItem,
		Body:     body.Bytes(),
	}

	var buf bytes.Buffer
	err := h.Handle(ctx, frame, &buf)
	if err != nil {
		t.Fatalf("Handle BatchGetItem: %v", err)
	}

	resp := buf.Bytes()
	if resp[4] != protocol.OpcodeBatchResult {
		r := protocol.NewReader(resp[protocol.HeaderSize:])
		code, _ := r.ReadUint32()
		errType, _ := r.ReadString()
		errMsg, _ := r.ReadString()
		t.Fatalf("Expected BATCH_RESULT, got 0x%02x (error: %d %s: %s)", resp[4], code, errType, errMsg)
	}

	r := protocol.NewReader(resp[protocol.HeaderSize:])
	tableCount, _ := r.ReadUint16()
	if tableCount != 1 {
		t.Errorf("Table count = %d, want 1", tableCount)
	}

	tableName, _ := r.ReadString()
	if tableName != testTableName {
		t.Errorf("Table name = %q, want %q", tableName, testTableName)
	}

	itemCount, _ := r.ReadUint32()
	if itemCount != 2 {
		t.Errorf("Item count = %d, want 2", itemCount)
	}
}

func TestIntegration_BatchWriteItem(t *testing.T) {
	registry := session.NewRegistry()
	h := New(registry)
	ctx := context.Background()

	sessionID := setupTestTable(t, registry)
	defer cleanupTestTable(t, registry, sessionID)

	// BatchWriteItem with puts and deletes
	body := protocol.NewBuffer(1024)
	body.WriteUint64(sessionID)

	// Table count
	body.WriteUint16(1)

	// Table name
	body.WriteString(testTableName)

	// Request count
	body.WriteUint32(3)

	// Put request 1
	body.WriteUint8(0x01) // PutRequest
	item1 := map[string]types.AttributeValue{
		"pk": &types.AttributeValueMemberS{Value: "batchwrite"},
		"sk": &types.AttributeValueMemberS{Value: "item1"},
	}
	encoding.WriteItem(body, item1)

	// Put request 2
	body.WriteUint8(0x01) // PutRequest
	item2 := map[string]types.AttributeValue{
		"pk": &types.AttributeValueMemberS{Value: "batchwrite"},
		"sk": &types.AttributeValueMemberS{Value: "item2"},
	}
	encoding.WriteItem(body, item2)

	// Put request 3
	body.WriteUint8(0x01) // PutRequest
	item3 := map[string]types.AttributeValue{
		"pk": &types.AttributeValueMemberS{Value: "batchwrite"},
		"sk": &types.AttributeValueMemberS{Value: "item3"},
	}
	encoding.WriteItem(body, item3)

	frame := &protocol.Frame{
		StreamID: 1,
		Opcode:   protocol.OpcodeBatchWriteItem,
		Body:     body.Bytes(),
	}

	var buf bytes.Buffer
	err := h.Handle(ctx, frame, &buf)
	if err != nil {
		t.Fatalf("Handle BatchWriteItem: %v", err)
	}

	resp := buf.Bytes()
	if resp[4] != protocol.OpcodeBatchResult {
		r := protocol.NewReader(resp[protocol.HeaderSize:])
		code, _ := r.ReadUint32()
		errType, _ := r.ReadString()
		errMsg, _ := r.ReadString()
		t.Fatalf("Expected BATCH_RESULT, got 0x%02x (error: %d %s: %s)", resp[4], code, errType, errMsg)
	}

	// Verify items were written by querying
	queryBody := protocol.NewBuffer(512)
	queryBody.WriteUint64(sessionID)
	queryBody.WriteString(testTableName)
	queryBody.WriteByte(0x00) // no index
	queryBody.WriteString("pk = :pk")
	queryBody.WriteByte(0x00) // no filter
	queryBody.WriteByte(0x00) // no projection
	queryBody.WriteUint16(0)  // no attr names
	queryBody.WriteUint16(1)  // 1 attr value
	queryBody.WriteString(":pk")
	queryBody.WriteUint8(encoding.TypeString)
	queryBody.WriteString("batchwrite")
	queryBody.WriteByte(0x00) // no limit
	queryBody.WriteBool(true) // consistent
	queryBody.WriteBool(true) // scan forward
	queryBody.WriteByte(0x00) // no start key

	queryFrame := &protocol.Frame{StreamID: 1, Opcode: protocol.OpcodeQuery, Body: queryBody.Bytes()}
	buf.Reset()
	h.Handle(ctx, queryFrame, &buf)

	resp = buf.Bytes()
	r := protocol.NewReader(resp[protocol.HeaderSize:])
	itemCount, _ := r.ReadUint32()
	if itemCount != 3 {
		t.Errorf("After BatchWriteItem, query found %d items, want 3", itemCount)
	}
}

func TestIntegration_LatencyTracking(t *testing.T) {
	registry := session.NewRegistry()
	h := New(registry)
	ctx := context.Background()

	sessionID := setupTestTable(t, registry)
	defer cleanupTestTable(t, registry, sessionID)

	// Put an item and verify latency
	body := protocol.NewBuffer(256)
	body.WriteUint64(sessionID)
	body.WriteString(testTableName)
	item := map[string]types.AttributeValue{
		"pk": &types.AttributeValueMemberS{Value: "latency-test"},
		"sk": &types.AttributeValueMemberS{Value: "item"},
	}
	encoding.WriteItem(body, item)
	body.WriteByte(0x00)
	body.WriteUint8(0)

	frame := &protocol.Frame{
		StreamID: 1,
		Opcode:   protocol.OpcodePutItem,
		Body:     body.Bytes(),
	}

	var buf bytes.Buffer
	err := h.Handle(ctx, frame, &buf)
	if err != nil {
		t.Fatalf("Handle: %v", err)
	}

	resp := buf.Bytes()
	r := protocol.NewReader(resp[protocol.HeaderSize:])
	r.ReadBool() // has_item
	r.ReadByte() // consumed capacity (absent)
	latencyNs, _ := r.ReadInt64()

	if latencyNs <= 0 {
		t.Errorf("Latency = %d ns, want > 0", latencyNs)
	}

	// Reasonable upper bound (10 seconds)
	if latencyNs > 10_000_000_000 {
		t.Errorf("Latency = %d ns seems unreasonably high", latencyNs)
	}

	t.Logf("Operation latency: %d ns (%.2f ms)", latencyNs, float64(latencyNs)/1_000_000)
}

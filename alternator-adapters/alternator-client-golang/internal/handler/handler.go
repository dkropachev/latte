// Package handler implements request handlers for DynamoDB operations.
package handler

import (
	"context"
	"errors"
	"fmt"
	"io"
	"time"

	"github.com/aws/aws-sdk-go-v2/service/dynamodb"
	"github.com/aws/aws-sdk-go-v2/service/dynamodb/types"
	"github.com/scylladb/latte/alternator-adapters/alternator-client-golang/internal/encoding"
	"github.com/scylladb/latte/alternator-adapters/alternator-client-golang/internal/protocol"
	"github.com/scylladb/latte/alternator-adapters/alternator-client-golang/internal/session"
)

// Handler processes protocol requests.
type Handler struct {
	registry *session.Registry
}

// New creates a new handler.
func New(registry *session.Registry) *Handler {
	return &Handler{registry: registry}
}

// Handle processes a single request frame and writes the response.
func (h *Handler) Handle(ctx context.Context, frame *protocol.Frame, w io.Writer) error {
	switch frame.Opcode {
	case protocol.OpcodeCreateSession:
		return h.handleCreateSession(ctx, frame, w)
	case protocol.OpcodeCloseSession:
		return h.handleCloseSession(ctx, frame, w)
	case protocol.OpcodeGetItem:
		return h.handleGetItem(ctx, frame, w)
	case protocol.OpcodePutItem:
		return h.handlePutItem(ctx, frame, w)
	case protocol.OpcodeDeleteItem:
		return h.handleDeleteItem(ctx, frame, w)
	case protocol.OpcodeUpdateItem:
		return h.handleUpdateItem(ctx, frame, w)
	case protocol.OpcodeQuery:
		return h.handleQuery(ctx, frame, w)
	case protocol.OpcodeScan:
		return h.handleScan(ctx, frame, w)
	case protocol.OpcodeBatchGetItem:
		return h.handleBatchGetItem(ctx, frame, w)
	case protocol.OpcodeBatchWriteItem:
		return h.handleBatchWriteItem(ctx, frame, w)
	case protocol.OpcodeShutdown:
		return h.handleShutdown(ctx, frame, w)
	default:
		return protocol.WriteError(w, frame.StreamID, protocol.ErrorProtocol,
			"ProtocolError", fmt.Sprintf("unknown opcode: 0x%02x", frame.Opcode))
	}
}

func (h *Handler) handleCreateSession(ctx context.Context, frame *protocol.Frame, w io.Writer) error {
	r := protocol.NewReader(frame.Body)

	// Read parameters
	paramCount, err := r.ReadUint16()
	if err != nil {
		return writeProtocolError(w, frame.StreamID, "failed to read param count")
	}

	params := make(map[string]string, paramCount)
	for i := uint16(0); i < paramCount; i++ {
		key, err := r.ReadString()
		if err != nil {
			return writeProtocolError(w, frame.StreamID, "failed to read param key")
		}
		value, err := r.ReadString()
		if err != nil {
			return writeProtocolError(w, frame.StreamID, "failed to read param value")
		}
		params[key] = value
	}

	cfg := session.ParseConfig(params)
	sess, err := h.registry.Create(ctx, cfg)
	if err != nil {
		return protocol.WriteError(w, frame.StreamID, protocol.ErrorConnection,
			"ConnectionError", err.Error())
	}

	// Write SESSION_CREATED response
	buf := protocol.NewBuffer(8)
	buf.WriteUint64(sess.ID)
	return protocol.WriteFrame(w, frame.StreamID, protocol.OpcodeSessionCreated, buf.Bytes())
}

func (h *Handler) handleCloseSession(_ context.Context, frame *protocol.Frame, w io.Writer) error {
	r := protocol.NewReader(frame.Body)
	sessionID, err := r.ReadUint64()
	if err != nil {
		return writeProtocolError(w, frame.StreamID, "failed to read session_id")
	}

	if !h.registry.Close(sessionID) {
		return protocol.WriteError(w, frame.StreamID, protocol.ErrorSessionNotFound,
			"SessionNotFound", fmt.Sprintf("session %d not found", sessionID))
	}

	return protocol.WriteFrame(w, frame.StreamID, protocol.OpcodeSessionClosed, nil)
}

func (h *Handler) handleGetItem(ctx context.Context, frame *protocol.Frame, w io.Writer) error {
	r := protocol.NewReader(frame.Body)

	sessionID, err := r.ReadUint64()
	if err != nil {
		return writeProtocolError(w, frame.StreamID, "failed to read session_id")
	}

	sess, ok := h.registry.Get(sessionID)
	if !ok {
		return writeSessionNotFound(w, frame.StreamID, sessionID)
	}

	tableName, err := r.ReadString()
	if err != nil {
		return writeProtocolError(w, frame.StreamID, "failed to read table_name")
	}

	key, err := encoding.ReadKey(r)
	if err != nil {
		return writeProtocolError(w, frame.StreamID, "failed to read key")
	}

	consistentRead, err := r.ReadBool()
	if err != nil {
		return writeProtocolError(w, frame.StreamID, "failed to read consistent_read")
	}

	projectionExpr, err := r.ReadOptionalString()
	if err != nil {
		return writeProtocolError(w, frame.StreamID, "failed to read projection_expression")
	}

	exprAttrNames, err := encoding.ReadExprAttrNames(r)
	if err != nil {
		return writeProtocolError(w, frame.StreamID, "failed to read expr_attr_names")
	}

	// Build request
	input := &dynamodb.GetItemInput{
		TableName:      &tableName,
		Key:            key,
		ConsistentRead: &consistentRead,
	}
	if projectionExpr != nil {
		input.ProjectionExpression = projectionExpr
	}
	if len(exprAttrNames) > 0 {
		input.ExpressionAttributeNames = exprAttrNames
	}

	// Execute
	start := time.Now()
	result, err := sess.Client.GetItem(ctx, input)
	latencyNs := time.Since(start).Nanoseconds()

	if err != nil {
		return writeOperationError(w, frame.StreamID, err)
	}

	// Encode response
	return writeItemResult(w, frame.StreamID, result.Item, latencyNs)
}

func (h *Handler) handlePutItem(ctx context.Context, frame *protocol.Frame, w io.Writer) error {
	r := protocol.NewReader(frame.Body)

	sessionID, err := r.ReadUint64()
	if err != nil {
		return writeProtocolError(w, frame.StreamID, "failed to read session_id")
	}

	sess, ok := h.registry.Get(sessionID)
	if !ok {
		return writeSessionNotFound(w, frame.StreamID, sessionID)
	}

	tableName, err := r.ReadString()
	if err != nil {
		return writeProtocolError(w, frame.StreamID, "failed to read table_name")
	}

	item, err := encoding.ReadItem(r)
	if err != nil {
		return writeProtocolError(w, frame.StreamID, "failed to read item")
	}

	condExpr, err := encoding.ReadOptionalConditionExpression(r)
	if err != nil {
		return writeProtocolError(w, frame.StreamID, "failed to read condition_expression")
	}

	returnValues, err := r.ReadUint8()
	if err != nil {
		return writeProtocolError(w, frame.StreamID, "failed to read return_values")
	}

	// Build request
	input := &dynamodb.PutItemInput{
		TableName:    &tableName,
		Item:         item,
		ReturnValues: mapReturnValue(returnValues),
	}
	if condExpr != nil {
		input.ConditionExpression = &condExpr.Expression
		if len(condExpr.ExprAttrNames) > 0 {
			input.ExpressionAttributeNames = condExpr.ExprAttrNames
		}
		if len(condExpr.ExprAttrValues) > 0 {
			input.ExpressionAttributeValues = condExpr.ExprAttrValues
		}
	}

	// Execute
	start := time.Now()
	result, err := sess.Client.PutItem(ctx, input)
	latencyNs := time.Since(start).Nanoseconds()

	if err != nil {
		return writeOperationError(w, frame.StreamID, err)
	}

	return writeItemResult(w, frame.StreamID, result.Attributes, latencyNs)
}

func (h *Handler) handleDeleteItem(ctx context.Context, frame *protocol.Frame, w io.Writer) error {
	r := protocol.NewReader(frame.Body)

	sessionID, err := r.ReadUint64()
	if err != nil {
		return writeProtocolError(w, frame.StreamID, "failed to read session_id")
	}

	sess, ok := h.registry.Get(sessionID)
	if !ok {
		return writeSessionNotFound(w, frame.StreamID, sessionID)
	}

	tableName, err := r.ReadString()
	if err != nil {
		return writeProtocolError(w, frame.StreamID, "failed to read table_name")
	}

	key, err := encoding.ReadKey(r)
	if err != nil {
		return writeProtocolError(w, frame.StreamID, "failed to read key")
	}

	condExpr, err := encoding.ReadOptionalConditionExpression(r)
	if err != nil {
		return writeProtocolError(w, frame.StreamID, "failed to read condition_expression")
	}

	returnValues, err := r.ReadUint8()
	if err != nil {
		return writeProtocolError(w, frame.StreamID, "failed to read return_values")
	}

	// Build request
	input := &dynamodb.DeleteItemInput{
		TableName:    &tableName,
		Key:          key,
		ReturnValues: mapReturnValue(returnValues),
	}
	if condExpr != nil {
		input.ConditionExpression = &condExpr.Expression
		if len(condExpr.ExprAttrNames) > 0 {
			input.ExpressionAttributeNames = condExpr.ExprAttrNames
		}
		if len(condExpr.ExprAttrValues) > 0 {
			input.ExpressionAttributeValues = condExpr.ExprAttrValues
		}
	}

	// Execute
	start := time.Now()
	result, err := sess.Client.DeleteItem(ctx, input)
	latencyNs := time.Since(start).Nanoseconds()

	if err != nil {
		return writeOperationError(w, frame.StreamID, err)
	}

	return writeItemResult(w, frame.StreamID, result.Attributes, latencyNs)
}

func (h *Handler) handleUpdateItem(ctx context.Context, frame *protocol.Frame, w io.Writer) error {
	r := protocol.NewReader(frame.Body)

	sessionID, err := r.ReadUint64()
	if err != nil {
		return writeProtocolError(w, frame.StreamID, "failed to read session_id")
	}

	sess, ok := h.registry.Get(sessionID)
	if !ok {
		return writeSessionNotFound(w, frame.StreamID, sessionID)
	}

	tableName, err := r.ReadString()
	if err != nil {
		return writeProtocolError(w, frame.StreamID, "failed to read table_name")
	}

	key, err := encoding.ReadKey(r)
	if err != nil {
		return writeProtocolError(w, frame.StreamID, "failed to read key")
	}

	updateExpr, err := r.ReadString()
	if err != nil {
		return writeProtocolError(w, frame.StreamID, "failed to read update_expression")
	}

	condExpr, err := encoding.ReadOptionalConditionExpression(r)
	if err != nil {
		return writeProtocolError(w, frame.StreamID, "failed to read condition_expression")
	}

	exprAttrNames, err := encoding.ReadExprAttrNames(r)
	if err != nil {
		return writeProtocolError(w, frame.StreamID, "failed to read expr_attr_names")
	}

	exprAttrValues, err := encoding.ReadExprAttrValues(r)
	if err != nil {
		return writeProtocolError(w, frame.StreamID, "failed to read expr_attr_values")
	}

	returnValues, err := r.ReadUint8()
	if err != nil {
		return writeProtocolError(w, frame.StreamID, "failed to read return_values")
	}

	// Build request
	input := &dynamodb.UpdateItemInput{
		TableName:        &tableName,
		Key:              key,
		UpdateExpression: &updateExpr,
		ReturnValues:     mapUpdateReturnValue(returnValues),
	}
	if condExpr != nil {
		input.ConditionExpression = &condExpr.Expression
		// Merge expression attribute names
		if input.ExpressionAttributeNames == nil {
			input.ExpressionAttributeNames = condExpr.ExprAttrNames
		} else {
			for k, v := range condExpr.ExprAttrNames {
				input.ExpressionAttributeNames[k] = v
			}
		}
		// Merge expression attribute values
		if input.ExpressionAttributeValues == nil {
			input.ExpressionAttributeValues = condExpr.ExprAttrValues
		} else {
			for k, v := range condExpr.ExprAttrValues {
				input.ExpressionAttributeValues[k] = v
			}
		}
	}
	if len(exprAttrNames) > 0 {
		if input.ExpressionAttributeNames == nil {
			input.ExpressionAttributeNames = exprAttrNames
		} else {
			for k, v := range exprAttrNames {
				input.ExpressionAttributeNames[k] = v
			}
		}
	}
	if len(exprAttrValues) > 0 {
		if input.ExpressionAttributeValues == nil {
			input.ExpressionAttributeValues = exprAttrValues
		} else {
			for k, v := range exprAttrValues {
				input.ExpressionAttributeValues[k] = v
			}
		}
	}

	// Execute
	start := time.Now()
	result, err := sess.Client.UpdateItem(ctx, input)
	latencyNs := time.Since(start).Nanoseconds()

	if err != nil {
		return writeOperationError(w, frame.StreamID, err)
	}

	return writeItemResult(w, frame.StreamID, result.Attributes, latencyNs)
}

func (h *Handler) handleQuery(ctx context.Context, frame *protocol.Frame, w io.Writer) error {
	r := protocol.NewReader(frame.Body)

	sessionID, err := r.ReadUint64()
	if err != nil {
		return writeProtocolError(w, frame.StreamID, "failed to read session_id")
	}

	sess, ok := h.registry.Get(sessionID)
	if !ok {
		return writeSessionNotFound(w, frame.StreamID, sessionID)
	}

	tableName, err := r.ReadString()
	if err != nil {
		return writeProtocolError(w, frame.StreamID, "failed to read table_name")
	}

	indexName, err := r.ReadOptionalString()
	if err != nil {
		return writeProtocolError(w, frame.StreamID, "failed to read index_name")
	}

	keyCondExpr, err := r.ReadString()
	if err != nil {
		return writeProtocolError(w, frame.StreamID, "failed to read key_condition_expression")
	}

	filterExpr, err := r.ReadOptionalString()
	if err != nil {
		return writeProtocolError(w, frame.StreamID, "failed to read filter_expression")
	}

	projectionExpr, err := r.ReadOptionalString()
	if err != nil {
		return writeProtocolError(w, frame.StreamID, "failed to read projection_expression")
	}

	exprAttrNames, err := encoding.ReadExprAttrNames(r)
	if err != nil {
		return writeProtocolError(w, frame.StreamID, "failed to read expr_attr_names")
	}

	exprAttrValues, err := encoding.ReadExprAttrValues(r)
	if err != nil {
		return writeProtocolError(w, frame.StreamID, "failed to read expr_attr_values")
	}

	limit, err := r.ReadOptionalUint32()
	if err != nil {
		return writeProtocolError(w, frame.StreamID, "failed to read limit")
	}

	consistentRead, err := r.ReadBool()
	if err != nil {
		return writeProtocolError(w, frame.StreamID, "failed to read consistent_read")
	}

	scanForward, err := r.ReadBool()
	if err != nil {
		return writeProtocolError(w, frame.StreamID, "failed to read scan_forward")
	}

	exclusiveStartKey, err := encoding.ReadOptionalKey(r)
	if err != nil {
		return writeProtocolError(w, frame.StreamID, "failed to read exclusive_start_key")
	}

	// Build request
	input := &dynamodb.QueryInput{
		TableName:              &tableName,
		KeyConditionExpression: &keyCondExpr,
		ConsistentRead:         &consistentRead,
		ScanIndexForward:       &scanForward,
	}
	if indexName != nil {
		input.IndexName = indexName
	}
	if filterExpr != nil {
		input.FilterExpression = filterExpr
	}
	if projectionExpr != nil {
		input.ProjectionExpression = projectionExpr
	}
	if len(exprAttrNames) > 0 {
		input.ExpressionAttributeNames = exprAttrNames
	}
	if len(exprAttrValues) > 0 {
		input.ExpressionAttributeValues = exprAttrValues
	}
	if limit != nil {
		l := int32(*limit)
		input.Limit = &l
	}
	if exclusiveStartKey != nil {
		input.ExclusiveStartKey = exclusiveStartKey
	}

	// Execute
	start := time.Now()
	result, err := sess.Client.Query(ctx, input)
	latencyNs := time.Since(start).Nanoseconds()

	if err != nil {
		return writeOperationError(w, frame.StreamID, err)
	}

	return writeQueryResult(w, frame.StreamID, result.Items, result.LastEvaluatedKey, result.ScannedCount, latencyNs)
}

func (h *Handler) handleScan(ctx context.Context, frame *protocol.Frame, w io.Writer) error {
	r := protocol.NewReader(frame.Body)

	sessionID, err := r.ReadUint64()
	if err != nil {
		return writeProtocolError(w, frame.StreamID, "failed to read session_id")
	}

	sess, ok := h.registry.Get(sessionID)
	if !ok {
		return writeSessionNotFound(w, frame.StreamID, sessionID)
	}

	tableName, err := r.ReadString()
	if err != nil {
		return writeProtocolError(w, frame.StreamID, "failed to read table_name")
	}

	indexName, err := r.ReadOptionalString()
	if err != nil {
		return writeProtocolError(w, frame.StreamID, "failed to read index_name")
	}

	filterExpr, err := r.ReadOptionalString()
	if err != nil {
		return writeProtocolError(w, frame.StreamID, "failed to read filter_expression")
	}

	projectionExpr, err := r.ReadOptionalString()
	if err != nil {
		return writeProtocolError(w, frame.StreamID, "failed to read projection_expression")
	}

	exprAttrNames, err := encoding.ReadExprAttrNames(r)
	if err != nil {
		return writeProtocolError(w, frame.StreamID, "failed to read expr_attr_names")
	}

	exprAttrValues, err := encoding.ReadExprAttrValues(r)
	if err != nil {
		return writeProtocolError(w, frame.StreamID, "failed to read expr_attr_values")
	}

	limit, err := r.ReadOptionalUint32()
	if err != nil {
		return writeProtocolError(w, frame.StreamID, "failed to read limit")
	}

	consistentRead, err := r.ReadBool()
	if err != nil {
		return writeProtocolError(w, frame.StreamID, "failed to read consistent_read")
	}

	segment, err := r.ReadOptionalUint32()
	if err != nil {
		return writeProtocolError(w, frame.StreamID, "failed to read segment")
	}

	totalSegments, err := r.ReadOptionalUint32()
	if err != nil {
		return writeProtocolError(w, frame.StreamID, "failed to read total_segments")
	}

	exclusiveStartKey, err := encoding.ReadOptionalKey(r)
	if err != nil {
		return writeProtocolError(w, frame.StreamID, "failed to read exclusive_start_key")
	}

	// Build request
	input := &dynamodb.ScanInput{
		TableName:      &tableName,
		ConsistentRead: &consistentRead,
	}
	if indexName != nil {
		input.IndexName = indexName
	}
	if filterExpr != nil {
		input.FilterExpression = filterExpr
	}
	if projectionExpr != nil {
		input.ProjectionExpression = projectionExpr
	}
	if len(exprAttrNames) > 0 {
		input.ExpressionAttributeNames = exprAttrNames
	}
	if len(exprAttrValues) > 0 {
		input.ExpressionAttributeValues = exprAttrValues
	}
	if limit != nil {
		l := int32(*limit)
		input.Limit = &l
	}
	if segment != nil {
		s := int32(*segment)
		input.Segment = &s
	}
	if totalSegments != nil {
		ts := int32(*totalSegments)
		input.TotalSegments = &ts
	}
	if exclusiveStartKey != nil {
		input.ExclusiveStartKey = exclusiveStartKey
	}

	// Execute
	start := time.Now()
	result, err := sess.Client.Scan(ctx, input)
	latencyNs := time.Since(start).Nanoseconds()

	if err != nil {
		return writeOperationError(w, frame.StreamID, err)
	}

	return writeQueryResult(w, frame.StreamID, result.Items, result.LastEvaluatedKey, result.ScannedCount, latencyNs)
}

func (h *Handler) handleBatchGetItem(ctx context.Context, frame *protocol.Frame, w io.Writer) error {
	r := protocol.NewReader(frame.Body)

	sessionID, err := r.ReadUint64()
	if err != nil {
		return writeProtocolError(w, frame.StreamID, "failed to read session_id")
	}

	sess, ok := h.registry.Get(sessionID)
	if !ok {
		return writeSessionNotFound(w, frame.StreamID, sessionID)
	}

	tableCount, err := r.ReadUint16()
	if err != nil {
		return writeProtocolError(w, frame.StreamID, "failed to read table_count")
	}

	requestItems := make(map[string]types.KeysAndAttributes, tableCount)
	for i := uint16(0); i < tableCount; i++ {
		tableName, err := r.ReadString()
		if err != nil {
			return writeProtocolError(w, frame.StreamID, "failed to read table_name")
		}

		keyCount, err := r.ReadUint32()
		if err != nil {
			return writeProtocolError(w, frame.StreamID, "failed to read key_count")
		}

		keys := make([]map[string]types.AttributeValue, keyCount)
		for j := uint32(0); j < keyCount; j++ {
			key, err := encoding.ReadKey(r)
			if err != nil {
				return writeProtocolError(w, frame.StreamID, "failed to read key")
			}
			keys[j] = key
		}

		consistentRead, err := r.ReadBool()
		if err != nil {
			return writeProtocolError(w, frame.StreamID, "failed to read consistent_read")
		}

		projectionExpr, err := r.ReadOptionalString()
		if err != nil {
			return writeProtocolError(w, frame.StreamID, "failed to read projection_expression")
		}

		exprAttrNames, err := encoding.ReadExprAttrNames(r)
		if err != nil {
			return writeProtocolError(w, frame.StreamID, "failed to read expr_attr_names")
		}

		ka := types.KeysAndAttributes{
			Keys:           keys,
			ConsistentRead: &consistentRead,
		}
		if projectionExpr != nil {
			ka.ProjectionExpression = projectionExpr
		}
		if len(exprAttrNames) > 0 {
			ka.ExpressionAttributeNames = exprAttrNames
		}
		requestItems[tableName] = ka
	}

	// Execute
	start := time.Now()
	result, err := sess.Client.BatchGetItem(ctx, &dynamodb.BatchGetItemInput{
		RequestItems: requestItems,
	})
	latencyNs := time.Since(start).Nanoseconds()

	if err != nil {
		return writeOperationError(w, frame.StreamID, err)
	}

	return writeBatchGetResult(w, frame.StreamID, result.Responses, result.UnprocessedKeys, latencyNs)
}

func (h *Handler) handleBatchWriteItem(ctx context.Context, frame *protocol.Frame, w io.Writer) error {
	r := protocol.NewReader(frame.Body)

	sessionID, err := r.ReadUint64()
	if err != nil {
		return writeProtocolError(w, frame.StreamID, "failed to read session_id")
	}

	sess, ok := h.registry.Get(sessionID)
	if !ok {
		return writeSessionNotFound(w, frame.StreamID, sessionID)
	}

	tableCount, err := r.ReadUint16()
	if err != nil {
		return writeProtocolError(w, frame.StreamID, "failed to read table_count")
	}

	requestItems := make(map[string][]types.WriteRequest, tableCount)
	for i := uint16(0); i < tableCount; i++ {
		tableName, err := r.ReadString()
		if err != nil {
			return writeProtocolError(w, frame.StreamID, "failed to read table_name")
		}

		reqCount, err := r.ReadUint32()
		if err != nil {
			return writeProtocolError(w, frame.StreamID, "failed to read request_count")
		}

		requests := make([]types.WriteRequest, reqCount)
		for j := uint32(0); j < reqCount; j++ {
			reqType, err := r.ReadUint8()
			if err != nil {
				return writeProtocolError(w, frame.StreamID, "failed to read request_type")
			}

			switch reqType {
			case 0x01: // PutRequest
				item, err := encoding.ReadItem(r)
				if err != nil {
					return writeProtocolError(w, frame.StreamID, "failed to read item")
				}
				requests[j] = types.WriteRequest{
					PutRequest: &types.PutRequest{Item: item},
				}
			case 0x02: // DeleteRequest
				key, err := encoding.ReadKey(r)
				if err != nil {
					return writeProtocolError(w, frame.StreamID, "failed to read key")
				}
				requests[j] = types.WriteRequest{
					DeleteRequest: &types.DeleteRequest{Key: key},
				}
			default:
				return writeProtocolError(w, frame.StreamID, fmt.Sprintf("unknown request type: 0x%02x", reqType))
			}
		}
		requestItems[tableName] = requests
	}

	// Execute
	start := time.Now()
	result, err := sess.Client.BatchWriteItem(ctx, &dynamodb.BatchWriteItemInput{
		RequestItems: requestItems,
	})
	latencyNs := time.Since(start).Nanoseconds()

	if err != nil {
		return writeOperationError(w, frame.StreamID, err)
	}

	return writeBatchWriteResult(w, frame.StreamID, result.UnprocessedItems, latencyNs)
}

func (h *Handler) handleShutdown(_ context.Context, frame *protocol.Frame, w io.Writer) error {
	h.registry.CloseAll()
	return protocol.WriteFrame(w, frame.StreamID, protocol.OpcodeShutdownAck, nil)
}

// Helper functions

func writeProtocolError(w io.Writer, streamID int16, msg string) error {
	return protocol.WriteError(w, streamID, protocol.ErrorProtocol, "ProtocolError", msg)
}

func writeSessionNotFound(w io.Writer, streamID int16, sessionID uint64) error {
	return protocol.WriteError(w, streamID, protocol.ErrorSessionNotFound,
		"SessionNotFound", fmt.Sprintf("session %d not found", sessionID))
}

func writeOperationError(w io.Writer, streamID int16, err error) error {
	code := protocol.ErrorUnknown
	errType := "UnknownError"

	var ccfe *types.ConditionalCheckFailedException
	var rnfe *types.ResourceNotFoundException
	var pte *types.ProvisionedThroughputExceededException
	var ice *types.InternalServerError

	switch {
	case errors.As(err, &ccfe):
		code = protocol.ErrorConditionalCheckFailed
		errType = "ConditionalCheckFailedException"
	case errors.As(err, &rnfe):
		code = protocol.ErrorResourceNotFound
		errType = "ResourceNotFoundException"
	case errors.As(err, &pte):
		code = protocol.ErrorProvisionedThroughput
		errType = "ProvisionedThroughputExceededException"
	case errors.As(err, &ice):
		code = protocol.ErrorInternalServer
		errType = "InternalServerError"
	}

	return protocol.WriteError(w, streamID, uint32(code), errType, err.Error())
}

func writeItemResult(w io.Writer, streamID int16, item map[string]types.AttributeValue, latencyNs int64) error {
	buf := protocol.NewBuffer(256)

	if item == nil || len(item) == 0 {
		buf.WriteBool(false) // has_item
	} else {
		buf.WriteBool(true) // has_item
		if err := encoding.WriteItem(buf, item); err != nil {
			return err
		}
	}

	// No consumed capacity for now
	buf.WriteByte(0x00) // optional<ConsumedCapacity> = absent

	buf.WriteInt64(latencyNs)

	return protocol.WriteFrame(w, streamID, protocol.OpcodeItemResult, buf.Bytes())
}

func writeQueryResult(w io.Writer, streamID int16, items []map[string]types.AttributeValue, lastKey map[string]types.AttributeValue, scannedCount int32, latencyNs int64) error {
	buf := protocol.NewBuffer(1024)

	buf.WriteUint32(uint32(len(items)))
	for _, item := range items {
		if err := encoding.WriteItem(buf, item); err != nil {
			return err
		}
	}

	if err := encoding.WriteOptionalKey(buf, lastKey); err != nil {
		return err
	}

	buf.WriteUint32(uint32(scannedCount))

	// No consumed capacity for now
	buf.WriteByte(0x00) // optional<ConsumedCapacity> = absent

	buf.WriteInt64(latencyNs)

	return protocol.WriteFrame(w, streamID, protocol.OpcodeQueryResult, buf.Bytes())
}

func writeBatchGetResult(w io.Writer, streamID int16, responses map[string][]map[string]types.AttributeValue, unprocessedKeys map[string]types.KeysAndAttributes, latencyNs int64) error {
	buf := protocol.NewBuffer(1024)

	// Responses
	buf.WriteUint16(uint16(len(responses)))
	for tableName, items := range responses {
		buf.WriteString(tableName)
		buf.WriteUint32(uint32(len(items)))
		for _, item := range items {
			if err := encoding.WriteItem(buf, item); err != nil {
				return err
			}
		}
	}

	// Unprocessed keys
	buf.WriteUint16(uint16(len(unprocessedKeys)))
	for tableName, ka := range unprocessedKeys {
		buf.WriteString(tableName)
		buf.WriteUint32(uint32(len(ka.Keys)))
		for _, key := range ka.Keys {
			if err := encoding.WriteKey(buf, key); err != nil {
				return err
			}
		}
	}

	// No consumed capacity for now
	buf.WriteByte(0x00) // optional<ConsumedCapacity[]> = absent

	buf.WriteInt64(latencyNs)

	return protocol.WriteFrame(w, streamID, protocol.OpcodeBatchResult, buf.Bytes())
}

func writeBatchWriteResult(w io.Writer, streamID int16, unprocessedItems map[string][]types.WriteRequest, latencyNs int64) error {
	buf := protocol.NewBuffer(256)

	// Empty responses for BatchWriteItem
	buf.WriteUint16(0)

	// Empty unprocessed keys (for BatchGetItem format compatibility)
	buf.WriteUint16(0)

	// Unprocessed items
	buf.WriteUint16(uint16(len(unprocessedItems)))
	for tableName, requests := range unprocessedItems {
		buf.WriteString(tableName)
		buf.WriteUint32(uint32(len(requests)))
		for _, req := range requests {
			if req.PutRequest != nil {
				buf.WriteUint8(0x01)
				if err := encoding.WriteItem(buf, req.PutRequest.Item); err != nil {
					return err
				}
			} else if req.DeleteRequest != nil {
				buf.WriteUint8(0x02)
				if err := encoding.WriteKey(buf, req.DeleteRequest.Key); err != nil {
					return err
				}
			}
		}
	}

	// No consumed capacity for now
	buf.WriteByte(0x00)

	buf.WriteInt64(latencyNs)

	return protocol.WriteFrame(w, streamID, protocol.OpcodeBatchResult, buf.Bytes())
}

func mapReturnValue(v uint8) types.ReturnValue {
	switch v {
	case 1:
		return types.ReturnValueAllOld
	default:
		return types.ReturnValueNone
	}
}

func mapUpdateReturnValue(v uint8) types.ReturnValue {
	switch v {
	case 1:
		return types.ReturnValueAllOld
	case 2:
		return types.ReturnValueUpdatedOld
	case 3:
		return types.ReturnValueAllNew
	case 4:
		return types.ReturnValueUpdatedNew
	default:
		return types.ReturnValueNone
	}
}

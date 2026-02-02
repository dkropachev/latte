package com.scylladb.latte.alternator;

import com.scylladb.latte.alternator.encoding.AttributeValueCodec;
import com.scylladb.latte.alternator.encoding.ExpressionCodec;
import com.scylladb.latte.alternator.protocol.*;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.*;

import java.io.EOFException;
import java.util.*;

/**
 * Dispatches protocol requests to DynamoDB operations.
 */
public final class RequestHandler {
    private final SessionRegistry registry;

    public RequestHandler(SessionRegistry registry) {
        this.registry = registry;
    }

    /**
     * Handle a request frame and return the response bytes.
     */
    public byte[] handle(Frame frame) {
        try {
            return switch (frame.opcode()) {
                case Opcodes.CREATE_SESSION -> handleCreateSession(frame);
                case Opcodes.CLOSE_SESSION -> handleCloseSession(frame);
                case Opcodes.GET_ITEM -> handleGetItem(frame);
                case Opcodes.PUT_ITEM -> handlePutItem(frame);
                case Opcodes.DELETE_ITEM -> handleDeleteItem(frame);
                case Opcodes.UPDATE_ITEM -> handleUpdateItem(frame);
                case Opcodes.QUERY -> handleQuery(frame);
                case Opcodes.SCAN -> handleScan(frame);
                case Opcodes.BATCH_GET_ITEM -> handleBatchGetItem(frame);
                case Opcodes.BATCH_WRITE_ITEM -> handleBatchWriteItem(frame);
                case Opcodes.SHUTDOWN -> handleShutdown(frame);
                default -> FrameWriter.buildErrorFrame(frame.streamId(), Opcodes.ERROR_PROTOCOL,
                        "ProtocolError", String.format("unknown opcode: 0x%02x", frame.opcode()));
            };
        } catch (EOFException e) {
            return FrameWriter.buildErrorFrame(frame.streamId(), Opcodes.ERROR_PROTOCOL,
                    "ProtocolError", "malformed request body: " + e.getMessage());
        } catch (Exception e) {
            return FrameWriter.buildErrorFrame(frame.streamId(), Opcodes.ERROR_UNKNOWN,
                    "UnknownError", e.getMessage());
        }
    }

    private byte[] handleCreateSession(Frame frame) throws EOFException {
        BinaryReader r = new BinaryReader(frame.body());
        int paramCount = r.readUint16();

        Map<String, String> params = new LinkedHashMap<>(paramCount);
        for (int i = 0; i < paramCount; i++) {
            String key = r.readString();
            String value = r.readString();
            params.put(key, value);
        }

        SessionRegistry.Config cfg = SessionRegistry.parseConfig(params);
        try {
            SessionRegistry.Session session = registry.create(cfg);
            BinaryWriter buf = new BinaryWriter(8);
            buf.writeUint64(session.id());
            return FrameWriter.buildFrame(frame.streamId(), Opcodes.RESP_SESSION_CREATED, buf.toByteArray());
        } catch (Exception e) {
            return FrameWriter.buildErrorFrame(frame.streamId(), Opcodes.ERROR_CONNECTION,
                    "ConnectionError", e.getMessage());
        }
    }

    private byte[] handleCloseSession(Frame frame) throws EOFException {
        BinaryReader r = new BinaryReader(frame.body());
        long sessionId = r.readUint64();

        if (!registry.close(sessionId)) {
            return FrameWriter.buildErrorFrame(frame.streamId(), Opcodes.ERROR_SESSION_NOT_FOUND,
                    "SessionNotFound", String.format("session %d not found", sessionId));
        }

        return FrameWriter.buildFrame(frame.streamId(), Opcodes.RESP_SESSION_CLOSED, null);
    }

    private byte[] handleGetItem(Frame frame) throws EOFException {
        BinaryReader r = new BinaryReader(frame.body());
        long sessionId = r.readUint64();

        SessionRegistry.Session sess = registry.get(sessionId);
        if (sess == null) {
            return sessionNotFound(frame.streamId(), sessionId);
        }

        String tableName = r.readString();
        Map<String, AttributeValue> key = AttributeValueCodec.readKey(r);
        boolean consistentRead = r.readBool();
        String projectionExpr = r.readOptionalString();
        Map<String, String> exprAttrNames = ExpressionCodec.readExprAttrNames(r);

        var builder = GetItemRequest.builder()
                .tableName(tableName)
                .key(key)
                .consistentRead(consistentRead);
        if (projectionExpr != null) {
            builder.projectionExpression(projectionExpr);
        }
        if (exprAttrNames != null && !exprAttrNames.isEmpty()) {
            builder.expressionAttributeNames(exprAttrNames);
        }

        long start = System.nanoTime();
        GetItemResponse result;
        try {
            result = sess.client().getItem(builder.build());
        } catch (DynamoDbException e) {
            return operationError(frame.streamId(), e);
        }
        long latencyNs = System.nanoTime() - start;

        return buildItemResult(frame.streamId(), result.item(), latencyNs);
    }

    private byte[] handlePutItem(Frame frame) throws EOFException {
        BinaryReader r = new BinaryReader(frame.body());
        long sessionId = r.readUint64();

        SessionRegistry.Session sess = registry.get(sessionId);
        if (sess == null) {
            return sessionNotFound(frame.streamId(), sessionId);
        }

        String tableName = r.readString();
        Map<String, AttributeValue> item = AttributeValueCodec.readItem(r);
        ExpressionCodec.ConditionExpression condExpr = ExpressionCodec.readOptionalConditionExpression(r);
        int returnValues = r.readUint8();

        var builder = PutItemRequest.builder()
                .tableName(tableName)
                .item(item)
                .returnValues(mapReturnValue(returnValues));
        if (condExpr != null) {
            builder.conditionExpression(condExpr.expression());
            if (condExpr.exprAttrNames() != null && !condExpr.exprAttrNames().isEmpty()) {
                builder.expressionAttributeNames(condExpr.exprAttrNames());
            }
            if (condExpr.exprAttrValues() != null && !condExpr.exprAttrValues().isEmpty()) {
                builder.expressionAttributeValues(condExpr.exprAttrValues());
            }
        }

        long start = System.nanoTime();
        PutItemResponse result;
        try {
            result = sess.client().putItem(builder.build());
        } catch (DynamoDbException e) {
            return operationError(frame.streamId(), e);
        }
        long latencyNs = System.nanoTime() - start;

        return buildItemResult(frame.streamId(), result.attributes(), latencyNs);
    }

    private byte[] handleDeleteItem(Frame frame) throws EOFException {
        BinaryReader r = new BinaryReader(frame.body());
        long sessionId = r.readUint64();

        SessionRegistry.Session sess = registry.get(sessionId);
        if (sess == null) {
            return sessionNotFound(frame.streamId(), sessionId);
        }

        String tableName = r.readString();
        Map<String, AttributeValue> key = AttributeValueCodec.readKey(r);
        ExpressionCodec.ConditionExpression condExpr = ExpressionCodec.readOptionalConditionExpression(r);
        int returnValues = r.readUint8();

        var builder = DeleteItemRequest.builder()
                .tableName(tableName)
                .key(key)
                .returnValues(mapReturnValue(returnValues));
        if (condExpr != null) {
            builder.conditionExpression(condExpr.expression());
            if (condExpr.exprAttrNames() != null && !condExpr.exprAttrNames().isEmpty()) {
                builder.expressionAttributeNames(condExpr.exprAttrNames());
            }
            if (condExpr.exprAttrValues() != null && !condExpr.exprAttrValues().isEmpty()) {
                builder.expressionAttributeValues(condExpr.exprAttrValues());
            }
        }

        long start = System.nanoTime();
        DeleteItemResponse result;
        try {
            result = sess.client().deleteItem(builder.build());
        } catch (DynamoDbException e) {
            return operationError(frame.streamId(), e);
        }
        long latencyNs = System.nanoTime() - start;

        return buildItemResult(frame.streamId(), result.attributes(), latencyNs);
    }

    private byte[] handleUpdateItem(Frame frame) throws EOFException {
        BinaryReader r = new BinaryReader(frame.body());
        long sessionId = r.readUint64();

        SessionRegistry.Session sess = registry.get(sessionId);
        if (sess == null) {
            return sessionNotFound(frame.streamId(), sessionId);
        }

        String tableName = r.readString();
        Map<String, AttributeValue> key = AttributeValueCodec.readKey(r);
        String updateExpr = r.readString();
        ExpressionCodec.ConditionExpression condExpr = ExpressionCodec.readOptionalConditionExpression(r);
        Map<String, String> exprAttrNames = ExpressionCodec.readExprAttrNames(r);
        Map<String, AttributeValue> exprAttrValues = ExpressionCodec.readExprAttrValues(r);
        int returnValues = r.readUint8();

        // Merge expression attributes from condition and update
        Map<String, String> mergedNames = mergeNames(exprAttrNames, condExpr);
        Map<String, AttributeValue> mergedValues = mergeValues(exprAttrValues, condExpr);

        var builder = UpdateItemRequest.builder()
                .tableName(tableName)
                .key(key)
                .updateExpression(updateExpr)
                .returnValues(mapUpdateReturnValue(returnValues));
        if (condExpr != null) {
            builder.conditionExpression(condExpr.expression());
        }
        if (mergedNames != null && !mergedNames.isEmpty()) {
            builder.expressionAttributeNames(mergedNames);
        }
        if (mergedValues != null && !mergedValues.isEmpty()) {
            builder.expressionAttributeValues(mergedValues);
        }

        long start = System.nanoTime();
        UpdateItemResponse result;
        try {
            result = sess.client().updateItem(builder.build());
        } catch (DynamoDbException e) {
            return operationError(frame.streamId(), e);
        }
        long latencyNs = System.nanoTime() - start;

        return buildItemResult(frame.streamId(), result.attributes(), latencyNs);
    }

    private byte[] handleQuery(Frame frame) throws EOFException {
        BinaryReader r = new BinaryReader(frame.body());
        long sessionId = r.readUint64();

        SessionRegistry.Session sess = registry.get(sessionId);
        if (sess == null) {
            return sessionNotFound(frame.streamId(), sessionId);
        }

        String tableName = r.readString();
        String indexName = r.readOptionalString();
        String keyCondExpr = r.readString();
        String filterExpr = r.readOptionalString();
        String projectionExpr = r.readOptionalString();
        Map<String, String> exprAttrNames = ExpressionCodec.readExprAttrNames(r);
        Map<String, AttributeValue> exprAttrValues = ExpressionCodec.readExprAttrValues(r);
        Long limit = r.readOptionalUint32();
        boolean consistentRead = r.readBool();
        boolean scanForward = r.readBool();
        Map<String, AttributeValue> exclusiveStartKey = AttributeValueCodec.readOptionalKey(r);

        var builder = QueryRequest.builder()
                .tableName(tableName)
                .keyConditionExpression(keyCondExpr)
                .consistentRead(consistentRead)
                .scanIndexForward(scanForward);
        if (indexName != null) builder.indexName(indexName);
        if (filterExpr != null) builder.filterExpression(filterExpr);
        if (projectionExpr != null) builder.projectionExpression(projectionExpr);
        if (exprAttrNames != null && !exprAttrNames.isEmpty()) builder.expressionAttributeNames(exprAttrNames);
        if (exprAttrValues != null && !exprAttrValues.isEmpty()) builder.expressionAttributeValues(exprAttrValues);
        if (limit != null) builder.limit(limit.intValue());
        if (exclusiveStartKey != null) builder.exclusiveStartKey(exclusiveStartKey);

        long start = System.nanoTime();
        QueryResponse result;
        try {
            result = sess.client().query(builder.build());
        } catch (DynamoDbException e) {
            return operationError(frame.streamId(), e);
        }
        long latencyNs = System.nanoTime() - start;

        return buildQueryResult(frame.streamId(), result.items(), result.lastEvaluatedKey(),
                result.scannedCount(), latencyNs);
    }

    private byte[] handleScan(Frame frame) throws EOFException {
        BinaryReader r = new BinaryReader(frame.body());
        long sessionId = r.readUint64();

        SessionRegistry.Session sess = registry.get(sessionId);
        if (sess == null) {
            return sessionNotFound(frame.streamId(), sessionId);
        }

        String tableName = r.readString();
        String indexName = r.readOptionalString();
        String filterExpr = r.readOptionalString();
        String projectionExpr = r.readOptionalString();
        Map<String, String> exprAttrNames = ExpressionCodec.readExprAttrNames(r);
        Map<String, AttributeValue> exprAttrValues = ExpressionCodec.readExprAttrValues(r);
        Long limit = r.readOptionalUint32();
        boolean consistentRead = r.readBool();
        Long segment = r.readOptionalUint32();
        Long totalSegments = r.readOptionalUint32();
        Map<String, AttributeValue> exclusiveStartKey = AttributeValueCodec.readOptionalKey(r);

        var builder = ScanRequest.builder()
                .tableName(tableName)
                .consistentRead(consistentRead);
        if (indexName != null) builder.indexName(indexName);
        if (filterExpr != null) builder.filterExpression(filterExpr);
        if (projectionExpr != null) builder.projectionExpression(projectionExpr);
        if (exprAttrNames != null && !exprAttrNames.isEmpty()) builder.expressionAttributeNames(exprAttrNames);
        if (exprAttrValues != null && !exprAttrValues.isEmpty()) builder.expressionAttributeValues(exprAttrValues);
        if (limit != null) builder.limit(limit.intValue());
        if (segment != null) builder.segment(segment.intValue());
        if (totalSegments != null) builder.totalSegments(totalSegments.intValue());
        if (exclusiveStartKey != null) builder.exclusiveStartKey(exclusiveStartKey);

        long start = System.nanoTime();
        ScanResponse result;
        try {
            result = sess.client().scan(builder.build());
        } catch (DynamoDbException e) {
            return operationError(frame.streamId(), e);
        }
        long latencyNs = System.nanoTime() - start;

        return buildQueryResult(frame.streamId(), result.items(), result.lastEvaluatedKey(),
                result.scannedCount(), latencyNs);
    }

    private byte[] handleBatchGetItem(Frame frame) throws EOFException {
        BinaryReader r = new BinaryReader(frame.body());
        long sessionId = r.readUint64();

        SessionRegistry.Session sess = registry.get(sessionId);
        if (sess == null) {
            return sessionNotFound(frame.streamId(), sessionId);
        }

        int tableCount = r.readUint16();
        Map<String, KeysAndAttributes> requestItems = new LinkedHashMap<>(tableCount);
        for (int i = 0; i < tableCount; i++) {
            String tableName = r.readString();
            long keyCount = r.readUint32();
            List<Map<String, AttributeValue>> keys = new ArrayList<>((int) keyCount);
            for (long j = 0; j < keyCount; j++) {
                keys.add(AttributeValueCodec.readKey(r));
            }
            boolean consistentRead = r.readBool();
            String projectionExpr = r.readOptionalString();
            Map<String, String> exprAttrNames = ExpressionCodec.readExprAttrNames(r);

            var kaBuilder = KeysAndAttributes.builder()
                    .keys(keys)
                    .consistentRead(consistentRead);
            if (projectionExpr != null) kaBuilder.projectionExpression(projectionExpr);
            if (exprAttrNames != null && !exprAttrNames.isEmpty()) kaBuilder.expressionAttributeNames(exprAttrNames);
            requestItems.put(tableName, kaBuilder.build());
        }

        long start = System.nanoTime();
        BatchGetItemResponse result;
        try {
            result = sess.client().batchGetItem(BatchGetItemRequest.builder()
                    .requestItems(requestItems).build());
        } catch (DynamoDbException e) {
            return operationError(frame.streamId(), e);
        }
        long latencyNs = System.nanoTime() - start;

        return buildBatchGetResult(frame.streamId(), result.responses(), result.unprocessedKeys(), latencyNs);
    }

    private byte[] handleBatchWriteItem(Frame frame) throws EOFException {
        BinaryReader r = new BinaryReader(frame.body());
        long sessionId = r.readUint64();

        SessionRegistry.Session sess = registry.get(sessionId);
        if (sess == null) {
            return sessionNotFound(frame.streamId(), sessionId);
        }

        int tableCount = r.readUint16();
        Map<String, List<WriteRequest>> requestItems = new LinkedHashMap<>(tableCount);
        for (int i = 0; i < tableCount; i++) {
            String tableName = r.readString();
            long reqCount = r.readUint32();
            List<WriteRequest> requests = new ArrayList<>((int) reqCount);
            for (long j = 0; j < reqCount; j++) {
                int reqType = r.readUint8();
                switch (reqType) {
                    case 0x01 -> { // PutRequest
                        Map<String, AttributeValue> item = AttributeValueCodec.readItem(r);
                        requests.add(WriteRequest.builder()
                                .putRequest(PutRequest.builder().item(item).build()).build());
                    }
                    case 0x02 -> { // DeleteRequest
                        Map<String, AttributeValue> key = AttributeValueCodec.readKey(r);
                        requests.add(WriteRequest.builder()
                                .deleteRequest(DeleteRequest.builder().key(key).build()).build());
                    }
                    default -> {
                        return FrameWriter.buildErrorFrame(frame.streamId(), Opcodes.ERROR_PROTOCOL,
                                "ProtocolError", String.format("unknown request type: 0x%02x", reqType));
                    }
                }
            }
            requestItems.put(tableName, requests);
        }

        long start = System.nanoTime();
        BatchWriteItemResponse result;
        try {
            result = sess.client().batchWriteItem(BatchWriteItemRequest.builder()
                    .requestItems(requestItems).build());
        } catch (DynamoDbException e) {
            return operationError(frame.streamId(), e);
        }
        long latencyNs = System.nanoTime() - start;

        return buildBatchWriteResult(frame.streamId(), result.unprocessedItems(), latencyNs);
    }

    private byte[] handleShutdown(Frame frame) {
        registry.closeAll();
        return FrameWriter.buildFrame(frame.streamId(), Opcodes.RESP_SHUTDOWN_ACK, null);
    }

    // --- Response builders ---

    private byte[] buildItemResult(short streamId, Map<String, AttributeValue> item, long latencyNs) {
        BinaryWriter buf = new BinaryWriter(256);
        if (item == null || item.isEmpty()) {
            buf.writeBool(false);
        } else {
            buf.writeBool(true);
            AttributeValueCodec.writeItem(buf, item);
        }
        buf.writeByte(0x00); // optional consumed capacity = absent
        buf.writeInt64(latencyNs);
        return FrameWriter.buildFrame(streamId, Opcodes.RESP_ITEM_RESULT, buf.toByteArray());
    }

    private byte[] buildQueryResult(short streamId, List<Map<String, AttributeValue>> items,
                                     Map<String, AttributeValue> lastKey, int scannedCount, long latencyNs) {
        BinaryWriter buf = new BinaryWriter(1024);
        buf.writeUint32(items.size());
        for (Map<String, AttributeValue> item : items) {
            AttributeValueCodec.writeItem(buf, item);
        }
        if (lastKey == null || lastKey.isEmpty()) {
            AttributeValueCodec.writeOptionalKey(buf, null);
        } else {
            AttributeValueCodec.writeOptionalKey(buf, lastKey);
        }
        buf.writeUint32(scannedCount);
        buf.writeByte(0x00); // optional consumed capacity = absent
        buf.writeInt64(latencyNs);
        return FrameWriter.buildFrame(streamId, Opcodes.RESP_QUERY_RESULT, buf.toByteArray());
    }

    private byte[] buildBatchGetResult(short streamId,
                                        Map<String, List<Map<String, AttributeValue>>> responses,
                                        Map<String, KeysAndAttributes> unprocessedKeys,
                                        long latencyNs) {
        BinaryWriter buf = new BinaryWriter(1024);

        // Responses
        buf.writeUint16(responses.size());
        for (var entry : responses.entrySet()) {
            buf.writeString(entry.getKey());
            buf.writeUint32(entry.getValue().size());
            for (Map<String, AttributeValue> item : entry.getValue()) {
                AttributeValueCodec.writeItem(buf, item);
            }
        }

        // Unprocessed keys
        buf.writeUint16(unprocessedKeys.size());
        for (var entry : unprocessedKeys.entrySet()) {
            buf.writeString(entry.getKey());
            buf.writeUint32(entry.getValue().keys().size());
            for (Map<String, AttributeValue> key : entry.getValue().keys()) {
                AttributeValueCodec.writeKey(buf, key);
            }
        }

        buf.writeByte(0x00); // optional consumed capacity = absent
        buf.writeInt64(latencyNs);
        return FrameWriter.buildFrame(streamId, Opcodes.RESP_BATCH_RESULT, buf.toByteArray());
    }

    private byte[] buildBatchWriteResult(short streamId,
                                          Map<String, List<WriteRequest>> unprocessedItems,
                                          long latencyNs) {
        BinaryWriter buf = new BinaryWriter(256);

        // Empty responses for BatchWriteItem
        buf.writeUint16(0);
        // Empty unprocessed keys
        buf.writeUint16(0);

        // Unprocessed items
        buf.writeUint16(unprocessedItems.size());
        for (var entry : unprocessedItems.entrySet()) {
            buf.writeString(entry.getKey());
            buf.writeUint32(entry.getValue().size());
            for (WriteRequest req : entry.getValue()) {
                if (req.putRequest() != null) {
                    buf.writeUint8(0x01);
                    AttributeValueCodec.writeItem(buf, req.putRequest().item());
                } else if (req.deleteRequest() != null) {
                    buf.writeUint8(0x02);
                    AttributeValueCodec.writeKey(buf, req.deleteRequest().key());
                }
            }
        }

        buf.writeByte(0x00); // optional consumed capacity = absent
        buf.writeInt64(latencyNs);
        return FrameWriter.buildFrame(streamId, Opcodes.RESP_BATCH_RESULT, buf.toByteArray());
    }

    // --- Error helpers ---

    private byte[] sessionNotFound(short streamId, long sessionId) {
        return FrameWriter.buildErrorFrame(streamId, Opcodes.ERROR_SESSION_NOT_FOUND,
                "SessionNotFound", String.format("session %d not found", sessionId));
    }

    private byte[] operationError(short streamId, DynamoDbException e) {
        int code = Opcodes.ERROR_UNKNOWN;
        String errType = "UnknownError";

        if (e instanceof ConditionalCheckFailedException) {
            code = Opcodes.ERROR_CONDITIONAL_CHECK_FAILED;
            errType = "ConditionalCheckFailedException";
        } else if (e instanceof ResourceNotFoundException) {
            code = Opcodes.ERROR_RESOURCE_NOT_FOUND;
            errType = "ResourceNotFoundException";
        } else if (e instanceof ProvisionedThroughputExceededException) {
            code = Opcodes.ERROR_PROVISIONED_THROUGHPUT;
            errType = "ProvisionedThroughputExceededException";
        } else if (e instanceof InternalServerErrorException) {
            code = Opcodes.ERROR_INTERNAL_SERVER;
            errType = "InternalServerError";
        }

        return FrameWriter.buildErrorFrame(streamId, code, errType, e.getMessage());
    }

    // --- Value mapping ---

    private static ReturnValue mapReturnValue(int v) {
        return switch (v) {
            case 1 -> ReturnValue.ALL_OLD;
            default -> ReturnValue.NONE;
        };
    }

    private static ReturnValue mapUpdateReturnValue(int v) {
        return switch (v) {
            case 1 -> ReturnValue.ALL_OLD;
            case 2 -> ReturnValue.UPDATED_OLD;
            case 3 -> ReturnValue.ALL_NEW;
            case 4 -> ReturnValue.UPDATED_NEW;
            default -> ReturnValue.NONE;
        };
    }

    // --- Expression attribute merging ---

    private static Map<String, String> mergeNames(Map<String, String> names,
                                                    ExpressionCodec.ConditionExpression condExpr) {
        if (condExpr == null || condExpr.exprAttrNames() == null) return names;
        if (names == null) return condExpr.exprAttrNames();
        Map<String, String> merged = new LinkedHashMap<>(names);
        merged.putAll(condExpr.exprAttrNames());
        return merged;
    }

    private static Map<String, AttributeValue> mergeValues(Map<String, AttributeValue> values,
                                                             ExpressionCodec.ConditionExpression condExpr) {
        if (condExpr == null || condExpr.exprAttrValues() == null) return values;
        if (values == null) return condExpr.exprAttrValues();
        Map<String, AttributeValue> merged = new LinkedHashMap<>(values);
        merged.putAll(condExpr.exprAttrValues());
        return merged;
    }
}

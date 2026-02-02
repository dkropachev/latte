package com.scylladb.latte;

import com.datastax.oss.driver.api.core.ConsistencyLevel;
import com.datastax.oss.driver.api.core.cql.BatchStatement;
import com.datastax.oss.driver.api.core.cql.BatchType;
import com.datastax.oss.driver.api.core.cql.BoundStatement;
import com.datastax.oss.driver.api.core.cql.ColumnDefinition;
import com.datastax.oss.driver.api.core.cql.ColumnDefinitions;
import com.datastax.oss.driver.api.core.cql.PreparedStatement;
import com.datastax.oss.driver.api.core.cql.ResultSet;
import com.datastax.oss.driver.api.core.cql.Row;
import com.datastax.oss.driver.api.core.cql.SimpleStatement;
import com.datastax.oss.driver.api.core.data.CqlVector;
import com.datastax.oss.driver.api.core.data.TupleValue;
import com.datastax.oss.driver.api.core.data.UdtValue;
import com.datastax.oss.driver.api.core.type.DataType;
import com.datastax.oss.driver.api.core.type.DataTypes;
import com.datastax.oss.driver.api.core.type.ListType;
import com.datastax.oss.driver.api.core.type.MapType;
import com.datastax.oss.driver.api.core.type.SetType;
import com.datastax.oss.driver.api.core.type.TupleType;
import com.datastax.oss.driver.api.core.type.UserDefinedType;
import com.datastax.oss.driver.api.core.type.VectorType;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.net.InetAddress;
import java.nio.ByteBuffer;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.jspecify.annotations.NonNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Handles protocol requests and dispatches to the session manager.
 *
 * <p>This class is responsible for:
 *
 * <ul>
 *   <li>Parsing incoming protocol frames
 *   <li>Dispatching requests to appropriate handlers based on opcode
 *   <li>Building response frames with results or errors
 *   <li>Managing statement execution with proper value binding
 * </ul>
 *
 * <p>All operations include latency measurement in nanoseconds, which is returned to the client
 * for benchmarking purposes.
 */
public class RequestHandler {
  private static final Logger logger = LoggerFactory.getLogger(RequestHandler.class);

  private final SessionManager sessionManager;

  /**
   * Creates a new RequestHandler with the given session manager.
   *
   * @param sessionManager the session manager for database connections
   */
  public RequestHandler(@NonNull SessionManager sessionManager) {
    this.sessionManager = sessionManager;
  }

  /**
   * Handle a protocol frame and return the response frame.
   *
   * <p>Dispatches the request based on the opcode and returns an appropriate response. All
   * exceptions are caught and converted to error frames to ensure the protocol remains consistent.
   *
   * @param frame the incoming protocol frame to handle
   * @return the response frame bytes, never null
   */
  public byte[] handleFrame(Protocol.Frame frame) {
    try {
      return switch (frame.opcode()) {
        case Protocol.OPCODE_CREATE_SESSION -> handleCreateSession(frame);
        case Protocol.OPCODE_QUERY -> handleQuery(frame);
        case Protocol.OPCODE_PREPARE -> handlePrepare(frame);
        case Protocol.OPCODE_EXECUTE -> handleExecute(frame);
        case Protocol.OPCODE_BATCH -> handleBatch(frame);
        default -> Protocol.FrameBuilder.buildErrorFrame(
            frame.stream(), Protocol.ERROR_CODE_PROTOCOL, "Unknown opcode: " + frame.opcode());
      };
    } catch (Exception e) {
      String context = String.format("opcode=0x%02X, stream=%d", frame.opcode(), frame.stream());
      logger.error("Error handling frame [{}]", context, e);
      String message = e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
      return Protocol.FrameBuilder.buildErrorFrame(
          frame.stream(), Protocol.ERROR_CODE_SERVER, message + " [" + context + "]");
    }
  }

  private byte[] handleCreateSession(Protocol.Frame frame) {
    Protocol.BytesReader reader = new Protocol.BytesReader(frame.body());
    Map<String, String> params = reader.readStringMap();

    long sessionId = sessionManager.createSession(params);
    return Protocol.FrameBuilder.buildSessionCreatedFrame(frame.stream(), sessionId);
  }

  private byte[] handleQuery(Protocol.Frame frame) {
    Protocol.BytesReader reader = new Protocol.BytesReader(frame.body());
    long sessionId = reader.readLong();
    String query = reader.readLongString();
    short consistency = reader.readShort();
    byte flags = reader.readByte(); // reserved

    SessionManager.SessionEntry entry = sessionManager.getSession(sessionId);

    long startTime = System.nanoTime();
    SimpleStatement stmt =
        SimpleStatement.newInstance(query).setConsistencyLevel(toConsistencyLevel(consistency));
    ResultSet rs = entry.session().execute(stmt);
    // Fetch all rows to ensure we measure the complete query time.
    // The Java driver's execute() may return lazily, so we need to materialize
    // the result before measuring latency.
    List<Row> rows = rs.all();
    long latencyNs = System.nanoTime() - startTime;

    return buildRowsFrameFromRows(frame.stream(), rs.getColumnDefinitions(), rows, latencyNs);
  }

  private byte[] handlePrepare(Protocol.Frame frame) {
    Protocol.BytesReader reader = new Protocol.BytesReader(frame.body());
    long sessionId = reader.readLong();
    String query = reader.readLongString();
    String statementKey = reader.readString();

    SessionManager.SessionEntry entry = sessionManager.getSession(sessionId);

    PreparedStatement ps = entry.session().prepare(query);
    entry.putPrepared(statementKey, ps);

    if (logger.isDebugEnabled()) {
      logger.debug("Prepared statement '{}': {}", statementKey, query);
    }

    return Protocol.FrameBuilder.buildPreparedFrame(frame.stream(), statementKey);
  }

  private byte[] handleExecute(Protocol.Frame frame) {
    Protocol.BytesReader reader = new Protocol.BytesReader(frame.body());
    long sessionId = reader.readLong();
    String statementKey = reader.readString();
    short consistency = reader.readShort();
    byte flags = reader.readByte();
    boolean hasValues = (flags & 0x01) != 0;

    SessionManager.SessionEntry entry = sessionManager.getSession(sessionId);
    PreparedStatement ps = entry.getPrepared(statementKey);
    if (ps == null) {
      String errorMsg =
          String.format(
              "Statement not prepared: '%s' (sessionId=%d, availableStatements=%d)",
              statementKey, sessionId, entry.preparedStatements().size());
      return Protocol.FrameBuilder.buildErrorFrame(
          frame.stream(), Protocol.ERROR_CODE_UNPREPARED, errorMsg);
    }

    BoundStatement bound = ps.bind();
    bound = bound.setConsistencyLevel(toConsistencyLevel(consistency));

    if (hasValues) {
      int valueCount = reader.readUShort();
      ColumnDefinitions bindDefs = ps.getVariableDefinitions();

      for (int i = 0; i < valueCount && i < bindDefs.size(); i++) {
        ColumnDefinition col = bindDefs.get(i);
        short wireType = reader.readShort();
        int length = reader.readInt();

        if (length < 0) {
          bound = bound.setToNull(i);
        } else {
          byte[] data = reader.readBytes(length);
          Object value = ValueEncoder.decode(data, wireType, col.getType());
          bound = bindValue(bound, i, value, col.getType());
        }
      }
    }

    long startTime = System.nanoTime();
    ResultSet rs = entry.session().execute(bound);
    // Fetch all rows to ensure we measure the complete query time.
    // The Java driver's execute() may return lazily, so we need to materialize
    // the result before measuring latency.
    List<Row> rows = rs.all();
    long latencyNs = System.nanoTime() - startTime;

    // DEBUG: Log execute latency
    if (logger.isDebugEnabled()) {
      logger.debug("handleExecute: latencyNs={} ({}ms)", latencyNs, latencyNs / 1_000_000.0);
    }

    return buildRowsFrameFromRows(frame.stream(), rs.getColumnDefinitions(), rows, latencyNs);
  }

  private byte[] handleBatch(Protocol.Frame frame) {
    Protocol.BytesReader reader = new Protocol.BytesReader(frame.body());
    long sessionId = reader.readLong();
    byte batchType = reader.readByte();
    int statementCount = reader.readUShort();

    SessionManager.SessionEntry entry = sessionManager.getSession(sessionId);

    BatchStatement batch =
        BatchStatement.newInstance(toBatchType(batchType));

    for (int s = 0; s < statementCount; s++) {
      byte kind = reader.readByte();
      if (kind != 1) {
        String errorMsg =
            String.format(
                "Only prepared statements (kind=1) supported in batch, got kind=%d at statement index %d",
                kind, s);
        return Protocol.FrameBuilder.buildErrorFrame(
            frame.stream(), Protocol.ERROR_CODE_PROTOCOL, errorMsg);
      }

      String statementKey = reader.readString();
      int valueCount = reader.readUShort();

      PreparedStatement ps = entry.getPrepared(statementKey);
      if (ps == null) {
        String errorMsg =
            String.format(
                "Statement not prepared in batch: '%s' (sessionId=%d, statementIndex=%d/%d)",
                statementKey, sessionId, s, statementCount);
        return Protocol.FrameBuilder.buildErrorFrame(
            frame.stream(), Protocol.ERROR_CODE_UNPREPARED, errorMsg);
      }

      BoundStatement bound = ps.bind();
      ColumnDefinitions bindDefs = ps.getVariableDefinitions();

      for (int i = 0; i < valueCount && i < bindDefs.size(); i++) {
        ColumnDefinition col = bindDefs.get(i);
        short wireType = reader.readShort();
        int length = reader.readInt();

        if (length < 0) {
          bound = bound.setToNull(i);
        } else {
          byte[] data = reader.readBytes(length);
          Object value = ValueEncoder.decode(data, wireType, col.getType());
          bound = bindValue(bound, i, value, col.getType());
        }
      }

      batch = batch.add(bound);
    }

    short consistency = reader.readShort();
    byte flags = reader.readByte(); // reserved

    batch = batch.setConsistencyLevel(toConsistencyLevel(consistency));

    long startTime = System.nanoTime();
    entry.session().execute(batch);
    long latencyNs = System.nanoTime() - startTime;

    // DEBUG: Log batch latency
    if (logger.isDebugEnabled()) {
      logger.debug("handleBatch: latencyNs={} ({}ms)", latencyNs, latencyNs / 1_000_000.0);
    }

    return Protocol.FrameBuilder.buildVoidFrame(frame.stream(), latencyNs);
  }

  @SuppressWarnings("unchecked")
  private BoundStatement bindValue(BoundStatement bound, int index, Object value, DataType type) {
    if (value == null) {
      return bound.setToNull(index);
    }

    // Check for UDT - if the target is UDT but value is a map, we need special handling
    if (type instanceof UserDefinedType udtType) {
      if (value instanceof UdtValue) {
        return bound.setUdtValue(index, (UdtValue) value);
      } else if (value instanceof Map) {
        // Wire format was a map - convert to UDT
        UdtValue udt = mapToUdt((Map<String, Object>) value, udtType);
        return bound.setUdtValue(index, udt);
      }
      // If value is null or incompatible, set to null
      return bound.setToNull(index);
    }

    // Handle collections - use manual encoding for nested/complex types
    if (type instanceof ListType listType) {
      DataType elemType = listType.getElementType();
      // For nested collections or UDTs, use manual byte encoding
      if (isNestedCollection(elemType) || containsUdtType(elemType)) {
        List<?> listVal = containsUdtType(elemType)
            ? coerceListElements((List<?>) value, elemType)
            : (List<?>) value;
        byte[] encoded = ValueEncoder.encodeCollection(listVal, listType);
        return bound.setBytesUnsafe(index, ByteBuffer.wrap(encoded));
      }
      List<Object> listVal = new ArrayList<>((List<?>) value);
      return bound.setList(index, listVal, getJavaTypeForDataType(elemType));
    }

    if (type instanceof SetType setType) {
      DataType elemType = setType.getElementType();
      Set<Object> setVal;
      if (value instanceof List) {
        setVal = new HashSet<>((List<?>) value);
      } else {
        setVal = new HashSet<>((Set<?>) value);
      }
      // For nested collections or UDTs, use manual byte encoding
      if (isNestedCollection(elemType) || containsUdtType(elemType)) {
        if (containsUdtType(elemType)) {
          setVal = new HashSet<>(coerceSetElements(setVal, elemType));
        }
        byte[] encoded = ValueEncoder.encodeCollection(setVal, setType);
        return bound.setBytesUnsafe(index, ByteBuffer.wrap(encoded));
      }
      return bound.setSet(index, setVal, getJavaTypeForDataType(elemType));
    }

    if (type instanceof MapType mapType) {
      DataType keyType = mapType.getKeyType();
      DataType valType = mapType.getValueType();
      Map<Object, Object> mapVal;
      // For nested collections or UDTs, use manual byte encoding
      if (isNestedCollection(keyType) || isNestedCollection(valType) ||
          containsUdtType(keyType) || containsUdtType(valType)) {
        if (containsUdtType(keyType) || containsUdtType(valType)) {
          mapVal = new HashMap<>(coerceMapElements((Map<?, ?>) value, mapType));
        } else {
          mapVal = new HashMap<>((Map<?, ?>) value);
        }
        byte[] encoded = ValueEncoder.encodeCollection(mapVal, mapType);
        return bound.setBytesUnsafe(index, ByteBuffer.wrap(encoded));
      }
      mapVal = new HashMap<>((Map<?, ?>) value);
      return bound.setMap(index, mapVal, getJavaTypeForDataType(keyType), getJavaTypeForDataType(valType));
    }

    // Handle vectors
    if (type instanceof VectorType) {
      if (value instanceof CqlVector) {
        return bound.set(index, (CqlVector<?>) value, CqlVector.class);
      } else if (value instanceof float[]) {
        Float[] boxed = boxFloatArray((float[]) value);
        return bound.set(index, CqlVector.newInstance(boxed), CqlVector.class);
      } else if (value instanceof List) {
        List<?> list = (List<?>) value;
        Float[] arr = new Float[list.size()];
        for (int i = 0; i < list.size(); i++) {
          arr[i] = ((Number) list.get(i)).floatValue();
        }
        return bound.set(index, CqlVector.newInstance(arr), CqlVector.class);
      }
    }

    // Handle tuples
    if (type instanceof TupleType) {
      return bound.setTupleValue(index, (TupleValue) value);
    }

    // Primitive types
    if (type.equals(DataTypes.TINYINT)) {
      return bound.setByte(index, ((Number) value).byteValue());
    }
    if (type.equals(DataTypes.SMALLINT)) {
      return bound.setShort(index, ((Number) value).shortValue());
    }
    if (type.equals(DataTypes.INT)) {
      return bound.setInt(index, ((Number) value).intValue());
    }
    if (type.equals(DataTypes.BIGINT) || type.equals(DataTypes.COUNTER)) {
      return bound.setLong(index, ((Number) value).longValue());
    }
    if (type.equals(DataTypes.FLOAT)) {
      return bound.setFloat(index, ((Number) value).floatValue());
    }
    if (type.equals(DataTypes.DOUBLE)) {
      return bound.setDouble(index, ((Number) value).doubleValue());
    }
    if (type.equals(DataTypes.BOOLEAN)) {
      return bound.setBoolean(index, (Boolean) value);
    }
    if (type.equals(DataTypes.TEXT) || type.equals(DataTypes.ASCII)) {
      return bound.setString(index, (String) value);
    }
    if (type.equals(DataTypes.BLOB)) {
      if (value instanceof ByteBuffer) {
        return bound.setByteBuffer(index, (ByteBuffer) value);
      } else if (value instanceof byte[]) {
        return bound.setByteBuffer(index, ByteBuffer.wrap((byte[]) value));
      }
    }
    if (type.equals(DataTypes.UUID) || type.equals(DataTypes.TIMEUUID)) {
      return bound.setUuid(index, (UUID) value);
    }
    if (type.equals(DataTypes.TIMESTAMP)) {
      return bound.setInstant(index, (Instant) value);
    }
    if (type.equals(DataTypes.DATE)) {
      return bound.setLocalDate(index, (LocalDate) value);
    }
    if (type.equals(DataTypes.TIME)) {
      return bound.setLocalTime(index, (LocalTime) value);
    }
    if (type.equals(DataTypes.INET)) {
      return bound.setInetAddress(index, (InetAddress) value);
    }
    if (type.equals(DataTypes.VARINT)) {
      return bound.setBigInteger(index, (BigInteger) value);
    }
    if (type.equals(DataTypes.DECIMAL)) {
      return bound.setBigDecimal(index, (BigDecimal) value);
    }
    if (type.equals(DataTypes.DURATION)) {
      return bound.setCqlDuration(index, (com.datastax.oss.driver.api.core.data.CqlDuration) value);
    }

    // Fallback - try generic set
    return bound.set(index, value, Object.class);
  }

  private boolean containsUdtType(DataType type) {
    if (type instanceof UserDefinedType) {
      return true;
    }
    if (type instanceof ListType listType) {
      return containsUdtType(listType.getElementType());
    }
    if (type instanceof SetType setType) {
      return containsUdtType(setType.getElementType());
    }
    if (type instanceof MapType mapType) {
      return containsUdtType(mapType.getKeyType()) || containsUdtType(mapType.getValueType());
    }
    return false;
  }

  private boolean isNestedCollection(DataType type) {
    return type instanceof ListType || type instanceof SetType || type instanceof MapType;
  }

  @SuppressWarnings("unchecked")
  private List<?> coerceListElements(List<?> list, DataType elementType) {
    List<Object> result = new ArrayList<>(list.size());
    for (Object elem : list) {
      result.add(coerceElement(elem, elementType));
    }
    return result;
  }

  @SuppressWarnings("unchecked")
  private Set<?> coerceSetElements(Set<?> set, DataType elementType) {
    Set<Object> result = new HashSet<>(set.size());
    for (Object elem : set) {
      result.add(coerceElement(elem, elementType));
    }
    return result;
  }

  @SuppressWarnings("unchecked")
  private Map<?, ?> coerceMapElements(Map<?, ?> map, MapType mapType) {
    Map<Object, Object> result = new HashMap<>(map.size());
    for (Map.Entry<?, ?> entry : map.entrySet()) {
      Object key = coerceElement(entry.getKey(), mapType.getKeyType());
      Object value = coerceElement(entry.getValue(), mapType.getValueType());
      result.put(key, value);
    }
    return result;
  }

  @SuppressWarnings("unchecked")
  private Object coerceElement(Object value, DataType targetType) {
    if (value == null) {
      return null;
    }

    if (targetType instanceof UserDefinedType udtType) {
      if (value instanceof Map) {
        return mapToUdt((Map<String, Object>) value, udtType);
      }
      return value;
    }

    if (targetType instanceof ListType listType) {
      if (value instanceof List) {
        return coerceListElements((List<?>) value, listType.getElementType());
      }
    }

    if (targetType instanceof SetType setType) {
      if (value instanceof List) {
        return coerceSetElements(new HashSet<>((List<?>) value), setType.getElementType());
      }
      if (value instanceof Set) {
        return coerceSetElements((Set<?>) value, setType.getElementType());
      }
    }

    if (targetType instanceof MapType mapType) {
      if (value instanceof Map) {
        return coerceMapElements((Map<?, ?>) value, mapType);
      }
    }

    // Handle float array to CqlVector
    if (value instanceof float[] floatArr && targetType instanceof VectorType) {
      return CqlVector.newInstance(boxFloatArray(floatArr));
    }

    return value;
  }

  @SuppressWarnings("unchecked")
  private UdtValue mapToUdt(Map<String, Object> map, UserDefinedType udtType) {
    UdtValue udt = udtType.newValue();
    for (Map.Entry<String, Object> entry : map.entrySet()) {
      String fieldName = entry.getKey();
      Object value = entry.getValue();

      int fieldIndex = udtType.firstIndexOf(fieldName);
      if (fieldIndex < 0) {
        continue; // Unknown field, skip
      }

      DataType fieldType = udtType.getFieldTypes().get(fieldIndex);
      Object coercedValue = coerceElement(value, fieldType);

      if (coercedValue == null) {
        udt = udt.setToNull(fieldName);
      } else {
        udt = setUdtField(udt, fieldName, coercedValue, fieldType);
      }
    }
    return udt;
  }

  @SuppressWarnings("unchecked")
  private UdtValue setUdtField(UdtValue udt, String field, Object value, DataType type) {
    if (value == null) {
      return udt.setToNull(field);
    }
    if (type.equals(DataTypes.TINYINT)) {
      return udt.setByte(field, ((Number) value).byteValue());
    }
    if (type.equals(DataTypes.SMALLINT)) {
      return udt.setShort(field, ((Number) value).shortValue());
    }
    if (type.equals(DataTypes.INT)) {
      return udt.setInt(field, ((Number) value).intValue());
    }
    if (type.equals(DataTypes.BIGINT) || type.equals(DataTypes.COUNTER)) {
      return udt.setLong(field, ((Number) value).longValue());
    }
    if (type.equals(DataTypes.FLOAT)) {
      return udt.setFloat(field, ((Number) value).floatValue());
    }
    if (type.equals(DataTypes.DOUBLE)) {
      return udt.setDouble(field, ((Number) value).doubleValue());
    }
    if (type.equals(DataTypes.BOOLEAN)) {
      return udt.setBoolean(field, (Boolean) value);
    }
    if (type.equals(DataTypes.TEXT) || type.equals(DataTypes.ASCII)) {
      return udt.setString(field, (String) value);
    }
    if (type.equals(DataTypes.BLOB)) {
      return udt.setByteBuffer(field, (ByteBuffer) value);
    }
    if (type.equals(DataTypes.UUID) || type.equals(DataTypes.TIMEUUID)) {
      return udt.setUuid(field, (UUID) value);
    }
    if (type.equals(DataTypes.TIMESTAMP)) {
      return udt.setInstant(field, (Instant) value);
    }
    if (type.equals(DataTypes.DATE)) {
      return udt.setLocalDate(field, (LocalDate) value);
    }
    if (type.equals(DataTypes.TIME)) {
      return udt.setLocalTime(field, (LocalTime) value);
    }
    if (type.equals(DataTypes.INET)) {
      return udt.setInetAddress(field, (InetAddress) value);
    }
    if (type.equals(DataTypes.VARINT)) {
      return udt.setBigInteger(field, (BigInteger) value);
    }
    if (type.equals(DataTypes.DECIMAL)) {
      return udt.setBigDecimal(field, (BigDecimal) value);
    }
    if (type.equals(DataTypes.DURATION)) {
      return udt.setCqlDuration(field, (com.datastax.oss.driver.api.core.data.CqlDuration) value);
    }
    if (type instanceof ListType) {
      return udt.setList(field, (List<Object>) value, Object.class);
    }
    if (type instanceof SetType) {
      return udt.setSet(field, (Set<Object>) value, Object.class);
    }
    if (type instanceof MapType) {
      return udt.setMap(field, (Map<Object, Object>) value, Object.class, Object.class);
    }
    if (type instanceof VectorType) {
      return udt.set(field, (CqlVector<?>) value, CqlVector.class);
    }
    if (type instanceof TupleType) {
      return udt.setTupleValue(field, (TupleValue) value);
    }
    if (type instanceof UserDefinedType) {
      return udt.setUdtValue(field, (UdtValue) value);
    }

    return udt.set(field, value, Object.class);
  }

  private byte[] buildRowsFrame(short stream, ResultSet rs, long latencyNs) {
    return buildRowsFrameFromRows(stream, rs.getColumnDefinitions(), rs.all(), latencyNs);
  }

  private byte[] buildRowsFrameFromRows(
      short stream, ColumnDefinitions columns, List<Row> rows, long latencyNs) {
    // DEBUG: Log latency value being written
    if (logger.isDebugEnabled()) {
      logger.debug("buildRowsFrameFromRows: latencyNs={} ({}ms)", latencyNs, latencyNs / 1_000_000.0);
    }

    // Always return ROWS frame, even for empty results
    // The Latte client expects ROWS with row_count=0, not VOID
    Protocol.BytesWriter writer = Protocol.BytesWriterPool.acquire();
    try {
      writer.writeInt(Protocol.RESULT_KIND_ROWS);
      writer.writeInt(0); // flags
      writer.writeInt(columns.size());

      // Pre-compute type codes for O(1) access per row (optimization #7)
      int columnCount = columns.size();
      short[] typeCodes = new short[columnCount];
      DataType[] dataTypes = new DataType[columnCount];
      for (int i = 0; i < columnCount; i++) {
        ColumnDefinition col = columns.get(i);
        dataTypes[i] = col.getType();
        typeCodes[i] = ValueEncoder.getTypeCode(dataTypes[i]);
      }

      // Column metadata
      for (int i = 0; i < columnCount; i++) {
        ColumnDefinition col = columns.get(i);
        String keyspace = col.getKeyspace() != null ? col.getKeyspace().toString() : "";
        String table = col.getTable() != null ? col.getTable().toString() : "";
        writer.writeString(keyspace);
        writer.writeString(table);
        writer.writeString(col.getName().toString());
        writer.writeShort(typeCodes[i]);
      }

      // Rows
      writer.writeInt(rows.size());
      for (Row row : rows) {
        for (int i = 0; i < columnCount; i++) {
          // Must check isNull first - getObject() may return empty collections instead of null
          Object value = row.isNull(i) ? null : row.getObject(i);
          byte[] encoded = ValueEncoder.encode(value, dataTypes[i]);
          writer.writeBytesNullable(encoded);
        }
      }

      writer.writeLong(latencyNs);
      return Protocol.FrameBuilder.buildFrame(stream, Protocol.OPCODE_RESULT, writer.toByteArray());
    } finally {
      Protocol.BytesWriterPool.release(writer);
    }
  }

  private ConsistencyLevel toConsistencyLevel(short level) {
    return switch (level) {
      case Protocol.CONSISTENCY_ANY -> ConsistencyLevel.ANY;
      case Protocol.CONSISTENCY_ONE -> ConsistencyLevel.ONE;
      case Protocol.CONSISTENCY_TWO -> ConsistencyLevel.TWO;
      case Protocol.CONSISTENCY_THREE -> ConsistencyLevel.THREE;
      case Protocol.CONSISTENCY_QUORUM -> ConsistencyLevel.QUORUM;
      case Protocol.CONSISTENCY_ALL -> ConsistencyLevel.ALL;
      case Protocol.CONSISTENCY_LOCAL_QUORUM -> ConsistencyLevel.LOCAL_QUORUM;
      case Protocol.CONSISTENCY_EACH_QUORUM -> ConsistencyLevel.EACH_QUORUM;
      case Protocol.CONSISTENCY_LOCAL_ONE -> ConsistencyLevel.LOCAL_ONE;
      default -> ConsistencyLevel.ONE;
    };
  }

  private BatchType toBatchType(byte type) {
    return switch (type) {
      case 0 -> BatchType.LOGGED;
      case 1 -> BatchType.UNLOGGED;
      case 2 -> BatchType.COUNTER;
      default -> BatchType.LOGGED;
    };
  }

  private Float[] boxFloatArray(float[] array) {
    Float[] result = new Float[array.length];
    for (int i = 0; i < array.length; i++) {
      result[i] = array[i];
    }
    return result;
  }

  /** Get the Java class corresponding to a CQL DataType for codec resolution. Delegates to ValueEncoder. */
  private static <T> Class<T> getJavaTypeForDataType(DataType type) {
    return ValueEncoder.getJavaTypeForDataType(type);
  }
}

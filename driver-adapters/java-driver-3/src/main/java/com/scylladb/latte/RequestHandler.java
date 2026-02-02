package com.scylladb.latte;

import com.datastax.driver.core.BatchStatement;
import com.datastax.driver.core.BoundStatement;
import com.datastax.driver.core.ColumnDefinitions;
import com.datastax.driver.core.ConsistencyLevel;
import com.datastax.driver.core.DataType;
import com.datastax.driver.core.Duration;
import com.datastax.driver.core.PreparedStatement;
import com.datastax.driver.core.ResultSet;
import com.datastax.driver.core.ResultSetFuture;
import com.datastax.driver.core.Row;
import com.datastax.driver.core.SimpleStatement;
import com.datastax.driver.core.TupleType;
import com.datastax.driver.core.TupleValue;
import com.datastax.driver.core.UDTValue;
import com.datastax.driver.core.UserType;
import com.google.common.util.concurrent.FutureCallback;
import com.google.common.util.concurrent.Futures;
import com.google.common.util.concurrent.MoreExecutors;
import io.netty.buffer.ByteBuf;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.net.InetAddress;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.BiConsumer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Handles protocol requests and dispatches to the session manager (Driver 3.x version).
 *
 * <p>This class is responsible for:
 *
 * <ul>
 *   <li>Parsing incoming protocol frames
 *   <li>Dispatching requests to appropriate handlers based on opcode
 *   <li>Building response frames with results or errors
 *   <li>Managing statement execution with proper value binding
 * </ul>
 */
public class RequestHandler {
  private static final Logger logger = LoggerFactory.getLogger(RequestHandler.class);

  private final SessionManager sessionManager;

  public RequestHandler(SessionManager sessionManager) {
    this.sessionManager = sessionManager;
  }

  /**
   * Handle a protocol frame and return the response frame.
   *
   * @param frame the incoming protocol frame to handle
   * @return the response frame bytes, never null
   */
  public byte[] handleFrame(Protocol.Frame frame) {
    try {
      switch (frame.opcode()) {
        case Protocol.OPCODE_CREATE_SESSION:
          return handleCreateSession(frame);
        case Protocol.OPCODE_QUERY:
          return handleQuery(frame);
        case Protocol.OPCODE_PREPARE:
          return handlePrepare(frame);
        case Protocol.OPCODE_EXECUTE:
          return handleExecute(frame);
        case Protocol.OPCODE_BATCH:
          return handleBatch(frame);
        default:
          return Protocol.FrameBuilder.buildErrorFrame(
              frame.stream(), Protocol.ERROR_CODE_PROTOCOL, "Unknown opcode: " + frame.opcode());
      }
    } catch (Exception e) {
      String context = "opcode=0x" + Integer.toHexString(frame.opcode() & 0xFF) + ", stream=" + frame.stream();
      logger.error("Error handling frame [{}]", context, e);
      String message = e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
      return Protocol.FrameBuilder.buildErrorFrame(
          frame.stream(), Protocol.ERROR_CODE_SERVER, message + " [" + context + "]");
    }
  }

  /**
   * Handle a protocol frame asynchronously with zero-copy ByteBuf response.
   *
   * <p>Uses the driver's async API (executeAsync) to avoid blocking worker threads on I/O.
   * Returns a pooled ByteBuf directly for zero-copy writes to Netty channels.
   *
   * @param frame the incoming protocol frame to handle
   * @param callback callback to invoke with the response ByteBuf (caller must release) or error
   */
  public void handleFrameAsyncByteBuf(Protocol.Frame frame, BiConsumer<ByteBuf, Throwable> callback) {
    try {
      switch (frame.opcode()) {
        case Protocol.OPCODE_CREATE_SESSION:
          callback.accept(handleCreateSessionByteBuf(frame), null);
          break;
        case Protocol.OPCODE_QUERY:
          handleQueryAsyncByteBuf(frame, callback);
          break;
        case Protocol.OPCODE_PREPARE:
          callback.accept(handlePrepareByteBuf(frame), null);
          break;
        case Protocol.OPCODE_EXECUTE:
          handleExecuteAsyncByteBuf(frame, callback);
          break;
        case Protocol.OPCODE_BATCH:
          handleBatchAsyncByteBuf(frame, callback);
          break;
        default:
          callback.accept(
              Protocol.PooledFrameBuilder.buildErrorFrame(
                  frame.stream(), Protocol.ERROR_CODE_PROTOCOL, "Unknown opcode: " + frame.opcode()),
              null);
      }
    } catch (Exception e) {
      String context = "opcode=0x" + Integer.toHexString(frame.opcode() & 0xFF) + ", stream=" + frame.stream();
      logger.error("Error handling frame [{}]", context, e);
      String message = e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
      callback.accept(
          Protocol.PooledFrameBuilder.buildErrorFrame(
              frame.stream(), Protocol.ERROR_CODE_SERVER, message + " [" + context + "]"),
          null);
    }
  }

  /**
   * Handle a protocol frame asynchronously for improved throughput.
   *
   * <p>Uses the driver's async API (executeAsync) to avoid blocking worker threads on I/O.
   * This allows much higher concurrency with fewer threads.
   *
   * @param frame the incoming protocol frame to handle
   * @param callback callback to invoke with the response bytes or error
   */
  public void handleFrameAsync(Protocol.Frame frame, BiConsumer<byte[], Throwable> callback) {
    try {
      switch (frame.opcode()) {
        case Protocol.OPCODE_CREATE_SESSION:
          callback.accept(handleCreateSession(frame), null);
          break;
        case Protocol.OPCODE_QUERY:
          handleQueryAsync(frame, callback);
          break;
        case Protocol.OPCODE_PREPARE:
          callback.accept(handlePrepare(frame), null);
          break;
        case Protocol.OPCODE_EXECUTE:
          handleExecuteAsync(frame, callback);
          break;
        case Protocol.OPCODE_BATCH:
          handleBatchAsync(frame, callback);
          break;
        default:
          callback.accept(
              Protocol.FrameBuilder.buildErrorFrame(
                  frame.stream(), Protocol.ERROR_CODE_PROTOCOL, "Unknown opcode: " + frame.opcode()),
              null);
      }
    } catch (Exception e) {
      String context = "opcode=0x" + Integer.toHexString(frame.opcode() & 0xFF) + ", stream=" + frame.stream();
      logger.error("Error handling frame [{}]", context, e);
      String message = e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
      callback.accept(
          Protocol.FrameBuilder.buildErrorFrame(
              frame.stream(), Protocol.ERROR_CODE_SERVER, message + " [" + context + "]"),
          null);
    }
  }

  private byte[] handleCreateSession(Protocol.Frame frame) {
    Protocol.BytesReader reader = new Protocol.BytesReader(frame.body());
    Map<String, String> params = reader.readStringMap();

    long sessionId = sessionManager.createSession(params);
    return Protocol.FrameBuilder.buildSessionCreatedFrame(frame.stream(), sessionId);
  }

  private ByteBuf handleCreateSessionByteBuf(Protocol.Frame frame) {
    Protocol.BytesReader reader = new Protocol.BytesReader(frame.body());
    Map<String, String> params = reader.readStringMap();

    long sessionId = sessionManager.createSession(params);
    // Build directly to pooled ByteBuf
    try (Protocol.PooledBytesWriter writer = new Protocol.PooledBytesWriter(16)) {
      writer.writeLong(sessionId);
      return Protocol.PooledFrameBuilder.buildFrame(frame.stream(), Protocol.OPCODE_SESSION_CREATED, writer);
    }
  }

  private byte[] handleQuery(Protocol.Frame frame) {
    Protocol.BytesReader reader = new Protocol.BytesReader(frame.body());
    long sessionId = reader.readLong();
    String query = reader.readLongString();
    short consistency = reader.readShort();
    byte flags = reader.readByte(); // reserved

    SessionManager.SessionEntry entry = sessionManager.getSession(sessionId);

    long startTime = System.nanoTime();
    SimpleStatement stmt = new SimpleStatement(query);
    stmt.setConsistencyLevel(toConsistencyLevel(consistency));
    ResultSet rs = entry.session().execute(stmt);
    List<Row> rows = rs.all();
    long latencyNs = System.nanoTime() - startTime;

    return buildRowsFrameFromRows(frame.stream(), rs.getColumnDefinitions(), rows, latencyNs);
  }

  private void handleQueryAsync(Protocol.Frame frame, BiConsumer<byte[], Throwable> callback) {
    Protocol.BytesReader reader = new Protocol.BytesReader(frame.body());
    long sessionId = reader.readLong();
    String query = reader.readLongString();
    short consistency = reader.readShort();
    byte flags = reader.readByte(); // reserved

    SessionManager.SessionEntry entry = sessionManager.getSession(sessionId);

    long startTime = System.nanoTime();
    SimpleStatement stmt = new SimpleStatement(query);
    stmt.setConsistencyLevel(toConsistencyLevel(consistency));
    ResultSetFuture future = entry.session().executeAsync(stmt);

    Futures.addCallback(
        future,
        new FutureCallback<ResultSet>() {
          @Override
          public void onSuccess(ResultSet rs) {
            try {
              long latencyNs = System.nanoTime() - startTime;
              // Use buildRowsFrame to avoid rs.all() row materialization
              byte[] response = buildRowsFrame(frame.stream(), rs, latencyNs);
              callback.accept(response, null);
            } catch (Exception e) {
              callback.accept(null, e);
            }
          }

          @Override
          public void onFailure(Throwable t) {
            callback.accept(null, t);
          }
        },
        MoreExecutors.directExecutor());
  }

  private void handleQueryAsyncByteBuf(Protocol.Frame frame, BiConsumer<ByteBuf, Throwable> callback) {
    Protocol.BytesReader reader = new Protocol.BytesReader(frame.body());
    long sessionId = reader.readLong();
    String query = reader.readLongString();
    short consistency = reader.readShort();
    byte flags = reader.readByte(); // reserved

    SessionManager.SessionEntry entry = sessionManager.getSession(sessionId);

    long startTime = System.nanoTime();
    SimpleStatement stmt = new SimpleStatement(query);
    stmt.setConsistencyLevel(toConsistencyLevel(consistency));
    ResultSetFuture future = entry.session().executeAsync(stmt);

    Futures.addCallback(
        future,
        new FutureCallback<ResultSet>() {
          @Override
          public void onSuccess(ResultSet rs) {
            try {
              long latencyNs = System.nanoTime() - startTime;
              ByteBuf response = buildRowsFrameByteBuf(frame.stream(), rs, latencyNs);
              callback.accept(response, null);
            } catch (Exception e) {
              callback.accept(null, e);
            }
          }

          @Override
          public void onFailure(Throwable t) {
            callback.accept(null, t);
          }
        },
        MoreExecutors.directExecutor());
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

  private ByteBuf handlePrepareByteBuf(Protocol.Frame frame) {
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

    // Build directly to pooled ByteBuf
    try (Protocol.PooledBytesWriter writer = new Protocol.PooledBytesWriter(256)) {
      writer.writeInt(Protocol.RESULT_KIND_PREPARED);
      writer.writeString(statementKey);
      byte[] idBytes = statementKey.getBytes(java.nio.charset.StandardCharsets.UTF_8);
      writer.writeUShort(idBytes.length);
      writer.writeBytes(idBytes);
      writer.writeInt(0); // bind_metadata_flags
      writer.writeInt(0); // bind_columns_count
      writer.writeInt(0); // result_metadata_flags
      writer.writeInt(0); // result_columns_count
      return Protocol.PooledFrameBuilder.buildFrame(frame.stream(), Protocol.OPCODE_RESULT, writer);
    }
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
      String errorMsg = "Statement not prepared: '" + statementKey + "' (sessionId=" + sessionId
          + ", availableStatements=" + entry.preparedStatements().size() + ")";
      return Protocol.FrameBuilder.buildErrorFrame(
          frame.stream(), Protocol.ERROR_CODE_UNPREPARED, errorMsg);
    }

    BoundStatement bound = ps.bind();
    bound.setConsistencyLevel(toConsistencyLevel(consistency));

    if (hasValues) {
      int valueCount = reader.readUShort();
      ColumnDefinitions bindDefs = ps.getVariables();

      for (int i = 0; i < valueCount && i < bindDefs.size(); i++) {
        DataType colType = bindDefs.getType(i);
        short wireType = reader.readShort();
        int length = reader.readInt();

        if (length < 0) {
          bound.setToNull(i);
        } else {
          byte[] data = reader.readBytes(length);
          // Handle vectors specially - bind raw bytes directly
          if (wireType == Protocol.TYPE_VECTOR) {
            ByteBuffer vectorBytes = (ByteBuffer) ValueEncoder.decode(data, wireType, colType);
            if (vectorBytes != null) {
              bound.setBytesUnsafe(i, vectorBytes);
            } else {
              bound.setToNull(i);
            }
          } else if (wireType == Protocol.TYPE_PACKED_FLOAT_VECTOR_LIST) {
            // Packed list of vectors - encode as CQL list binary format
            @SuppressWarnings("unchecked")
            List<ByteBuffer> vectors = (List<ByteBuffer>) ValueEncoder.decode(data, wireType, colType);
            if (vectors != null) {
              ByteBuffer encoded = encodeVectorList(vectors);
              bound.setBytesUnsafe(i, encoded);
            } else {
              bound.setToNull(i);
            }
          } else {
            Object value = ValueEncoder.decode(data, wireType, colType);
            bindValue(bound, i, value, colType);
          }
        }
      }
    }

    long startTime = System.nanoTime();
    ResultSet rs = entry.session().execute(bound);
    List<Row> rows = rs.all();
    long latencyNs = System.nanoTime() - startTime;

    if (logger.isDebugEnabled()) {
      logger.debug("handleExecute: latencyNs={} ({}ms)", latencyNs, latencyNs / 1_000_000.0);
    }

    return buildRowsFrameFromRows(frame.stream(), rs.getColumnDefinitions(), rows, latencyNs);
  }

  private void handleExecuteAsync(Protocol.Frame frame, BiConsumer<byte[], Throwable> callback) {
    Protocol.BytesReader reader = new Protocol.BytesReader(frame.body());
    long sessionId = reader.readLong();
    String statementKey = reader.readString();
    short consistency = reader.readShort();
    byte flags = reader.readByte();
    boolean hasValues = (flags & 0x01) != 0;

    SessionManager.SessionEntry entry = sessionManager.getSession(sessionId);
    PreparedStatement ps = entry.getPrepared(statementKey);
    if (ps == null) {
      String errorMsg = "Statement not prepared: '" + statementKey + "' (sessionId=" + sessionId
          + ", availableStatements=" + entry.preparedStatements().size() + ")";
      callback.accept(
          Protocol.FrameBuilder.buildErrorFrame(frame.stream(), Protocol.ERROR_CODE_UNPREPARED, errorMsg),
          null);
      return;
    }

    BoundStatement bound = ps.bind();
    bound.setConsistencyLevel(toConsistencyLevel(consistency));

    if (hasValues) {
      int valueCount = reader.readUShort();
      ColumnDefinitions bindDefs = ps.getVariables();

      for (int i = 0; i < valueCount && i < bindDefs.size(); i++) {
        DataType colType = bindDefs.getType(i);
        short wireType = reader.readShort();
        int length = reader.readInt();

        if (length < 0) {
          bound.setToNull(i);
        } else {
          byte[] data = reader.readBytes(length);
          // Handle vectors specially - bind raw bytes directly
          if (wireType == Protocol.TYPE_VECTOR) {
            ByteBuffer vectorBytes = (ByteBuffer) ValueEncoder.decode(data, wireType, colType);
            if (vectorBytes != null) {
              bound.setBytesUnsafe(i, vectorBytes);
            } else {
              bound.setToNull(i);
            }
          } else if (wireType == Protocol.TYPE_PACKED_FLOAT_VECTOR_LIST) {
            // Packed list of vectors - encode as CQL list binary format
            @SuppressWarnings("unchecked")
            List<ByteBuffer> vectors = (List<ByteBuffer>) ValueEncoder.decode(data, wireType, colType);
            if (vectors != null) {
              ByteBuffer encoded = encodeVectorList(vectors);
              bound.setBytesUnsafe(i, encoded);
            } else {
              bound.setToNull(i);
            }
          } else {
            Object value = ValueEncoder.decode(data, wireType, colType);
            bindValue(bound, i, value, colType);
          }
        }
      }
    }

    long startTime = System.nanoTime();
    ResultSetFuture future = entry.session().executeAsync(bound);

    Futures.addCallback(
        future,
        new FutureCallback<ResultSet>() {
          @Override
          public void onSuccess(ResultSet rs) {
            try {
              long latencyNs = System.nanoTime() - startTime;

              if (logger.isDebugEnabled()) {
                logger.debug("handleExecuteAsync: latencyNs={} ({}ms)", latencyNs, latencyNs / 1_000_000.0);
              }

              // Use buildRowsFrame to avoid rs.all() row materialization
              byte[] response = buildRowsFrame(frame.stream(), rs, latencyNs);
              callback.accept(response, null);
            } catch (Exception e) {
              callback.accept(null, e);
            }
          }

          @Override
          public void onFailure(Throwable t) {
            callback.accept(null, t);
          }
        },
        MoreExecutors.directExecutor());
  }

  private void handleExecuteAsyncByteBuf(Protocol.Frame frame, BiConsumer<ByteBuf, Throwable> callback) {
    Protocol.BytesReader reader = new Protocol.BytesReader(frame.body());
    long sessionId = reader.readLong();
    String statementKey = reader.readString();
    short consistency = reader.readShort();
    byte flags = reader.readByte();
    boolean hasValues = (flags & 0x01) != 0;

    SessionManager.SessionEntry entry = sessionManager.getSession(sessionId);
    PreparedStatement ps = entry.getPrepared(statementKey);
    if (ps == null) {
      String errorMsg = "Statement not prepared: '" + statementKey + "' (sessionId=" + sessionId
          + ", availableStatements=" + entry.preparedStatements().size() + ")";
      callback.accept(
          Protocol.PooledFrameBuilder.buildErrorFrame(frame.stream(), Protocol.ERROR_CODE_UNPREPARED, errorMsg),
          null);
      return;
    }

    BoundStatement bound = ps.bind();
    bound.setConsistencyLevel(toConsistencyLevel(consistency));

    if (hasValues) {
      int valueCount = reader.readUShort();
      ColumnDefinitions bindDefs = ps.getVariables();

      for (int i = 0; i < valueCount && i < bindDefs.size(); i++) {
        DataType colType = bindDefs.getType(i);
        short wireType = reader.readShort();
        int length = reader.readInt();

        if (length < 0) {
          bound.setToNull(i);
        } else {
          byte[] data = reader.readBytes(length);
          if (wireType == Protocol.TYPE_VECTOR) {
            ByteBuffer vectorBytes = (ByteBuffer) ValueEncoder.decode(data, wireType, colType);
            if (vectorBytes != null) {
              bound.setBytesUnsafe(i, vectorBytes);
            } else {
              bound.setToNull(i);
            }
          } else if (wireType == Protocol.TYPE_PACKED_FLOAT_VECTOR_LIST) {
            @SuppressWarnings("unchecked")
            List<ByteBuffer> vectors = (List<ByteBuffer>) ValueEncoder.decode(data, wireType, colType);
            if (vectors != null) {
              ByteBuffer encoded = encodeVectorList(vectors);
              bound.setBytesUnsafe(i, encoded);
            } else {
              bound.setToNull(i);
            }
          } else {
            Object value = ValueEncoder.decode(data, wireType, colType);
            bindValue(bound, i, value, colType);
          }
        }
      }
    }

    long startTime = System.nanoTime();
    ResultSetFuture future = entry.session().executeAsync(bound);

    Futures.addCallback(
        future,
        new FutureCallback<ResultSet>() {
          @Override
          public void onSuccess(ResultSet rs) {
            try {
              long latencyNs = System.nanoTime() - startTime;
              if (logger.isDebugEnabled()) {
                logger.debug("handleExecuteAsyncByteBuf: latencyNs={} ({}ms)", latencyNs, latencyNs / 1_000_000.0);
              }
              ByteBuf response = buildRowsFrameByteBuf(frame.stream(), rs, latencyNs);
              callback.accept(response, null);
            } catch (Exception e) {
              callback.accept(null, e);
            }
          }

          @Override
          public void onFailure(Throwable t) {
            callback.accept(null, t);
          }
        },
        MoreExecutors.directExecutor());
  }

  private byte[] handleBatch(Protocol.Frame frame) {
    Protocol.BytesReader reader = new Protocol.BytesReader(frame.body());
    long sessionId = reader.readLong();
    byte batchType = reader.readByte();
    int statementCount = reader.readUShort();

    SessionManager.SessionEntry entry = sessionManager.getSession(sessionId);

    BatchStatement batch = new BatchStatement(toBatchType(batchType));

    for (int s = 0; s < statementCount; s++) {
      byte kind = reader.readByte();
      if (kind != 1) {
        String errorMsg = "Only prepared statements (kind=1) supported in batch, got kind=" + kind
            + " at statement index " + s;
        return Protocol.FrameBuilder.buildErrorFrame(
            frame.stream(), Protocol.ERROR_CODE_PROTOCOL, errorMsg);
      }

      String statementKey = reader.readString();
      int valueCount = reader.readUShort();

      PreparedStatement ps = entry.getPrepared(statementKey);
      if (ps == null) {
        String errorMsg = "Statement not prepared in batch: '" + statementKey + "' (sessionId=" + sessionId
            + ", statementIndex=" + s + "/" + statementCount + ")";
        return Protocol.FrameBuilder.buildErrorFrame(
            frame.stream(), Protocol.ERROR_CODE_UNPREPARED, errorMsg);
      }

      BoundStatement bound = ps.bind();
      ColumnDefinitions bindDefs = ps.getVariables();

      for (int i = 0; i < valueCount && i < bindDefs.size(); i++) {
        DataType colType = bindDefs.getType(i);
        short wireType = reader.readShort();
        int length = reader.readInt();

        if (length < 0) {
          bound.setToNull(i);
        } else {
          byte[] data = reader.readBytes(length);
          // Handle vectors specially - bind raw bytes directly
          if (wireType == Protocol.TYPE_VECTOR) {
            ByteBuffer vectorBytes = (ByteBuffer) ValueEncoder.decode(data, wireType, colType);
            if (vectorBytes != null) {
              bound.setBytesUnsafe(i, vectorBytes);
            } else {
              bound.setToNull(i);
            }
          } else if (wireType == Protocol.TYPE_PACKED_FLOAT_VECTOR_LIST) {
            // Packed list of vectors - encode as CQL list binary format
            @SuppressWarnings("unchecked")
            List<ByteBuffer> vectors = (List<ByteBuffer>) ValueEncoder.decode(data, wireType, colType);
            if (vectors != null) {
              ByteBuffer encoded = encodeVectorList(vectors);
              bound.setBytesUnsafe(i, encoded);
            } else {
              bound.setToNull(i);
            }
          } else {
            Object value = ValueEncoder.decode(data, wireType, colType);
            bindValue(bound, i, value, colType);
          }
        }
      }

      batch.add(bound);
    }

    short consistency = reader.readShort();
    byte flags = reader.readByte(); // reserved

    batch.setConsistencyLevel(toConsistencyLevel(consistency));

    long startTime = System.nanoTime();
    entry.session().execute(batch);
    long latencyNs = System.nanoTime() - startTime;

    if (logger.isDebugEnabled()) {
      logger.debug("handleBatch: latencyNs={} ({}ms)", latencyNs, latencyNs / 1_000_000.0);
    }

    return Protocol.FrameBuilder.buildVoidFrame(frame.stream(), latencyNs);
  }

  private void handleBatchAsync(Protocol.Frame frame, BiConsumer<byte[], Throwable> callback) {
    Protocol.BytesReader reader = new Protocol.BytesReader(frame.body());
    long sessionId = reader.readLong();
    byte batchType = reader.readByte();
    int statementCount = reader.readUShort();

    SessionManager.SessionEntry entry = sessionManager.getSession(sessionId);

    BatchStatement batch = new BatchStatement(toBatchType(batchType));

    for (int s = 0; s < statementCount; s++) {
      byte kind = reader.readByte();
      if (kind != 1) {
        String errorMsg = "Only prepared statements (kind=1) supported in batch, got kind=" + kind
            + " at statement index " + s;
        callback.accept(
            Protocol.FrameBuilder.buildErrorFrame(frame.stream(), Protocol.ERROR_CODE_PROTOCOL, errorMsg),
            null);
        return;
      }

      String statementKey = reader.readString();
      int valueCount = reader.readUShort();

      PreparedStatement ps = entry.getPrepared(statementKey);
      if (ps == null) {
        String errorMsg = "Statement not prepared in batch: '" + statementKey + "' (sessionId=" + sessionId
            + ", statementIndex=" + s + "/" + statementCount + ")";
        callback.accept(
            Protocol.FrameBuilder.buildErrorFrame(frame.stream(), Protocol.ERROR_CODE_UNPREPARED, errorMsg),
            null);
        return;
      }

      BoundStatement bound = ps.bind();
      ColumnDefinitions bindDefs = ps.getVariables();

      for (int i = 0; i < valueCount && i < bindDefs.size(); i++) {
        DataType colType = bindDefs.getType(i);
        short wireType = reader.readShort();
        int length = reader.readInt();

        if (length < 0) {
          bound.setToNull(i);
        } else {
          byte[] data = reader.readBytes(length);
          // Handle vectors specially - bind raw bytes directly
          if (wireType == Protocol.TYPE_VECTOR) {
            ByteBuffer vectorBytes = (ByteBuffer) ValueEncoder.decode(data, wireType, colType);
            if (vectorBytes != null) {
              bound.setBytesUnsafe(i, vectorBytes);
            } else {
              bound.setToNull(i);
            }
          } else if (wireType == Protocol.TYPE_PACKED_FLOAT_VECTOR_LIST) {
            // Packed list of vectors - encode as CQL list binary format
            @SuppressWarnings("unchecked")
            List<ByteBuffer> vectors = (List<ByteBuffer>) ValueEncoder.decode(data, wireType, colType);
            if (vectors != null) {
              ByteBuffer encoded = encodeVectorList(vectors);
              bound.setBytesUnsafe(i, encoded);
            } else {
              bound.setToNull(i);
            }
          } else {
            Object value = ValueEncoder.decode(data, wireType, colType);
            bindValue(bound, i, value, colType);
          }
        }
      }

      batch.add(bound);
    }

    short consistency = reader.readShort();
    byte flags = reader.readByte(); // reserved

    batch.setConsistencyLevel(toConsistencyLevel(consistency));

    long startTime = System.nanoTime();
    ResultSetFuture future = entry.session().executeAsync(batch);

    Futures.addCallback(
        future,
        new FutureCallback<ResultSet>() {
          @Override
          public void onSuccess(ResultSet rs) {
            long latencyNs = System.nanoTime() - startTime;

            if (logger.isDebugEnabled()) {
              logger.debug("handleBatchAsync: latencyNs={} ({}ms)", latencyNs, latencyNs / 1_000_000.0);
            }

            callback.accept(Protocol.FrameBuilder.buildVoidFrame(frame.stream(), latencyNs), null);
          }

          @Override
          public void onFailure(Throwable t) {
            callback.accept(null, t);
          }
        },
        MoreExecutors.directExecutor());
  }

  private void handleBatchAsyncByteBuf(Protocol.Frame frame, BiConsumer<ByteBuf, Throwable> callback) {
    Protocol.BytesReader reader = new Protocol.BytesReader(frame.body());
    long sessionId = reader.readLong();
    byte batchType = reader.readByte();
    int statementCount = reader.readUShort();

    SessionManager.SessionEntry entry = sessionManager.getSession(sessionId);

    BatchStatement batch = new BatchStatement(toBatchType(batchType));

    for (int s = 0; s < statementCount; s++) {
      byte kind = reader.readByte();
      if (kind != 1) {
        String errorMsg = "Only prepared statements (kind=1) supported in batch, got kind=" + kind
            + " at statement index " + s;
        callback.accept(
            Protocol.PooledFrameBuilder.buildErrorFrame(frame.stream(), Protocol.ERROR_CODE_PROTOCOL, errorMsg),
            null);
        return;
      }

      String statementKey = reader.readString();
      int valueCount = reader.readUShort();

      PreparedStatement ps = entry.getPrepared(statementKey);
      if (ps == null) {
        String errorMsg = "Statement not prepared in batch: '" + statementKey + "' (sessionId=" + sessionId
            + ", statementIndex=" + s + "/" + statementCount + ")";
        callback.accept(
            Protocol.PooledFrameBuilder.buildErrorFrame(frame.stream(), Protocol.ERROR_CODE_UNPREPARED, errorMsg),
            null);
        return;
      }

      BoundStatement bound = ps.bind();
      ColumnDefinitions bindDefs = ps.getVariables();

      for (int i = 0; i < valueCount && i < bindDefs.size(); i++) {
        DataType colType = bindDefs.getType(i);
        short wireType = reader.readShort();
        int length = reader.readInt();

        if (length < 0) {
          bound.setToNull(i);
        } else {
          byte[] data = reader.readBytes(length);
          if (wireType == Protocol.TYPE_VECTOR) {
            ByteBuffer vectorBytes = (ByteBuffer) ValueEncoder.decode(data, wireType, colType);
            if (vectorBytes != null) {
              bound.setBytesUnsafe(i, vectorBytes);
            } else {
              bound.setToNull(i);
            }
          } else if (wireType == Protocol.TYPE_PACKED_FLOAT_VECTOR_LIST) {
            @SuppressWarnings("unchecked")
            List<ByteBuffer> vectors = (List<ByteBuffer>) ValueEncoder.decode(data, wireType, colType);
            if (vectors != null) {
              ByteBuffer encoded = encodeVectorList(vectors);
              bound.setBytesUnsafe(i, encoded);
            } else {
              bound.setToNull(i);
            }
          } else {
            Object value = ValueEncoder.decode(data, wireType, colType);
            bindValue(bound, i, value, colType);
          }
        }
      }

      batch.add(bound);
    }

    short consistency = reader.readShort();
    byte flags = reader.readByte(); // reserved

    batch.setConsistencyLevel(toConsistencyLevel(consistency));

    long startTime = System.nanoTime();
    ResultSetFuture future = entry.session().executeAsync(batch);

    Futures.addCallback(
        future,
        new FutureCallback<ResultSet>() {
          @Override
          public void onSuccess(ResultSet rs) {
            long latencyNs = System.nanoTime() - startTime;
            if (logger.isDebugEnabled()) {
              logger.debug("handleBatchAsyncByteBuf: latencyNs={} ({}ms)", latencyNs, latencyNs / 1_000_000.0);
            }
            callback.accept(Protocol.PooledFrameBuilder.buildVoidFrame(frame.stream(), latencyNs), null);
          }

          @Override
          public void onFailure(Throwable t) {
            callback.accept(null, t);
          }
        },
        MoreExecutors.directExecutor());
  }

  @SuppressWarnings("unchecked")
  private void bindValue(BoundStatement bound, int index, Object value, DataType type) {
    if (value == null) {
      bound.setToNull(index);
      return;
    }

    // Check for UDT
    if (type instanceof UserType) {
      UserType udtType = (UserType) type;
      if (value instanceof UDTValue) {
        bound.setUDTValue(index, (UDTValue) value);
      } else if (value instanceof Map) {
        UDTValue udt = mapToUdt((Map<String, Object>) value, udtType);
        bound.setUDTValue(index, udt);
      } else {
        bound.setToNull(index);
      }
      return;
    }

    DataType.Name typeName = type.getName();

    // Handle collections
    if (typeName == DataType.Name.LIST) {
      List<DataType> typeArgs = type.getTypeArguments();
      DataType elemType = typeArgs.isEmpty() ? DataType.blob() : typeArgs.get(0);
      // Check if list contains vector ByteBuffers
      if (value instanceof List && !((List<?>) value).isEmpty()) {
        Object firstElem = ((List<?>) value).get(0);
        if (firstElem instanceof ByteBuffer) {
          // This is likely a list of vectors - encode as CQL list binary format
          @SuppressWarnings("unchecked")
          List<ByteBuffer> vectors = (List<ByteBuffer>) value;
          ByteBuffer encoded = encodeVectorList(vectors);
          bound.setBytesUnsafe(index, encoded);
          return;
        }
      }
      if (isNestedCollection(elemType) || containsUdtType(elemType)) {
        List<?> listVal =
            containsUdtType(elemType)
                ? coerceListElements((List<?>) value, elemType)
                : (List<?>) value;
        byte[] encoded = ValueEncoder.encodeCollection(listVal, type);
        bound.setBytesUnsafe(index, ByteBuffer.wrap(encoded));
      } else {
        bound.setList(index, new ArrayList<>((List<?>) value));
      }
      return;
    }

    if (typeName == DataType.Name.SET) {
      List<DataType> typeArgs = type.getTypeArguments();
      DataType elemType = typeArgs.isEmpty() ? DataType.blob() : typeArgs.get(0);
      Set<Object> setVal;
      if (value instanceof List) {
        setVal = new HashSet<>((List<?>) value);
      } else {
        setVal = new HashSet<>((Set<?>) value);
      }
      if (isNestedCollection(elemType) || containsUdtType(elemType)) {
        if (containsUdtType(elemType)) {
          setVal = new HashSet<>(coerceSetElements(setVal, elemType));
        }
        byte[] encoded = ValueEncoder.encodeCollection(setVal, type);
        bound.setBytesUnsafe(index, ByteBuffer.wrap(encoded));
      } else {
        bound.setSet(index, setVal);
      }
      return;
    }

    if (typeName == DataType.Name.MAP) {
      List<DataType> typeArgs = type.getTypeArguments();
      DataType keyType = typeArgs.size() > 0 ? typeArgs.get(0) : DataType.blob();
      DataType valType = typeArgs.size() > 1 ? typeArgs.get(1) : DataType.blob();
      Map<Object, Object> mapVal;
      if (isNestedCollection(keyType)
          || isNestedCollection(valType)
          || containsUdtType(keyType)
          || containsUdtType(valType)) {
        if (containsUdtType(keyType) || containsUdtType(valType)) {
          mapVal = new HashMap<>(coerceMapElements((Map<?, ?>) value, keyType, valType));
        } else {
          mapVal = new HashMap<>((Map<?, ?>) value);
        }
        byte[] encoded = ValueEncoder.encodeCollection(mapVal, type);
        bound.setBytesUnsafe(index, ByteBuffer.wrap(encoded));
      } else {
        mapVal = new HashMap<>((Map<?, ?>) value);
        bound.setMap(index, mapVal);
      }
      return;
    }

    // Handle tuples
    if (type instanceof TupleType) {
      bound.setTupleValue(index, (TupleValue) value);
      return;
    }

    // Primitive types - Driver 3.x uses mutable setters
    switch (typeName) {
      case TINYINT:
        bound.setByte(index, ((Number) value).byteValue());
        break;
      case SMALLINT:
        bound.setShort(index, ((Number) value).shortValue());
        break;
      case INT:
        bound.setInt(index, ((Number) value).intValue());
        break;
      case BIGINT:
      case COUNTER:
        bound.setLong(index, ((Number) value).longValue());
        break;
      case FLOAT:
        bound.setFloat(index, ((Number) value).floatValue());
        break;
      case DOUBLE:
        bound.setDouble(index, ((Number) value).doubleValue());
        break;
      case BOOLEAN:
        bound.setBool(index, (Boolean) value);
        break;
      case TEXT:
      case VARCHAR:
      case ASCII:
        bound.setString(index, (String) value);
        break;
      case BLOB:
        if (value instanceof ByteBuffer) {
          bound.setBytes(index, (ByteBuffer) value);
        } else if (value instanceof byte[]) {
          bound.setBytes(index, ByteBuffer.wrap((byte[]) value));
        }
        break;
      case UUID:
      case TIMEUUID:
        bound.setUUID(index, (UUID) value);
        break;
      case TIMESTAMP:
        if (value instanceof Date) {
          bound.setTimestamp(index, (Date) value);
        } else if (value instanceof java.time.Instant) {
          bound.setTimestamp(index, Date.from((java.time.Instant) value));
        }
        break;
      case DATE:
        if (value instanceof com.datastax.driver.core.LocalDate) {
          bound.setDate(index, (com.datastax.driver.core.LocalDate) value);
        }
        break;
      case TIME:
        bound.setTime(index, ((Number) value).longValue());
        break;
      case INET:
        bound.setInet(index, (InetAddress) value);
        break;
      case VARINT:
        bound.setVarint(index, (BigInteger) value);
        break;
      case DECIMAL:
        bound.setDecimal(index, (BigDecimal) value);
        break;
      case DURATION:
        bound.set(index, (Duration) value, Duration.class);
        break;
      default:
        bound.set(index, value, Object.class);
    }
  }

  private boolean containsUdtType(DataType type) {
    if (type instanceof UserType) {
      return true;
    }
    DataType.Name typeName = type.getName();
    if (typeName == DataType.Name.LIST || typeName == DataType.Name.SET) {
      List<DataType> typeArgs = type.getTypeArguments();
      if (!typeArgs.isEmpty()) {
        return containsUdtType(typeArgs.get(0));
      }
    }
    if (typeName == DataType.Name.MAP) {
      List<DataType> typeArgs = type.getTypeArguments();
      if (typeArgs.size() >= 2) {
        return containsUdtType(typeArgs.get(0)) || containsUdtType(typeArgs.get(1));
      }
    }
    return false;
  }

  private boolean isNestedCollection(DataType type) {
    DataType.Name typeName = type.getName();
    return typeName == DataType.Name.LIST
        || typeName == DataType.Name.SET
        || typeName == DataType.Name.MAP;
  }

  @SuppressWarnings("unchecked")
  private List<?> coerceListElements(List<?> list, DataType elementType) {
    // Fast path: if no UDT coercion needed, return original list
    if (!containsUdtType(elementType)) {
      return list;
    }
    List<Object> result = new ArrayList<>(list.size());
    for (Object elem : list) {
      result.add(coerceElement(elem, elementType));
    }
    return result;
  }

  @SuppressWarnings("unchecked")
  private Set<?> coerceSetElements(Set<?> set, DataType elementType) {
    // Fast path: if no UDT coercion needed, return original set
    if (!containsUdtType(elementType)) {
      return set;
    }
    Set<Object> result = new HashSet<>(set.size());
    for (Object elem : set) {
      result.add(coerceElement(elem, elementType));
    }
    return result;
  }

  @SuppressWarnings("unchecked")
  private Map<?, ?> coerceMapElements(Map<?, ?> map, DataType keyType, DataType valType) {
    // Fast path: if no UDT coercion needed, return original map
    if (!containsUdtType(keyType) && !containsUdtType(valType)) {
      return map;
    }
    Map<Object, Object> result = new HashMap<>(map.size());
    for (Map.Entry<?, ?> entry : map.entrySet()) {
      Object key = coerceElement(entry.getKey(), keyType);
      Object value = coerceElement(entry.getValue(), valType);
      result.put(key, value);
    }
    return result;
  }

  @SuppressWarnings("unchecked")
  private Object coerceElement(Object value, DataType targetType) {
    if (value == null) {
      return null;
    }

    if (targetType instanceof UserType) {
      UserType udtType = (UserType) targetType;
      if (value instanceof Map) {
        return mapToUdt((Map<String, Object>) value, udtType);
      }
      return value;
    }

    DataType.Name typeName = targetType.getName();
    if (typeName == DataType.Name.LIST) {
      if (value instanceof List) {
        List<DataType> typeArgs = targetType.getTypeArguments();
        DataType elemType = typeArgs.isEmpty() ? DataType.blob() : typeArgs.get(0);
        return coerceListElements((List<?>) value, elemType);
      }
    }

    if (typeName == DataType.Name.SET) {
      List<DataType> typeArgs = targetType.getTypeArguments();
      DataType elemType = typeArgs.isEmpty() ? DataType.blob() : typeArgs.get(0);
      if (value instanceof List) {
        return coerceSetElements(new HashSet<>((List<?>) value), elemType);
      }
      if (value instanceof Set) {
        return coerceSetElements((Set<?>) value, elemType);
      }
    }

    if (typeName == DataType.Name.MAP) {
      if (value instanceof Map) {
        List<DataType> typeArgs = targetType.getTypeArguments();
        DataType keyType = typeArgs.size() > 0 ? typeArgs.get(0) : DataType.blob();
        DataType valType = typeArgs.size() > 1 ? typeArgs.get(1) : DataType.blob();
        return coerceMapElements((Map<?, ?>) value, keyType, valType);
      }
    }

    return value;
  }

  @SuppressWarnings("unchecked")
  private UDTValue mapToUdt(Map<String, Object> map, UserType udtType) {
    UDTValue udt = udtType.newValue();
    for (Map.Entry<String, Object> entry : map.entrySet()) {
      String fieldName = entry.getKey();
      Object value = entry.getValue();

      if (!udtType.contains(fieldName)) {
        continue;
      }

      DataType fieldType = udtType.getFieldType(fieldName);
      if (fieldType == null) {
        continue;
      }
      Object coercedValue = coerceElement(value, fieldType);

      if (coercedValue == null) {
        udt.setToNull(fieldName);
      } else {
        setUdtField(udt, fieldName, coercedValue, fieldType);
      }
    }
    return udt;
  }

  @SuppressWarnings("unchecked")
  private void setUdtField(UDTValue udt, String field, Object value, DataType type) {
    if (value == null) {
      udt.setToNull(field);
      return;
    }

    DataType.Name typeName = type.getName();
    switch (typeName) {
      case TINYINT:
        udt.setByte(field, ((Number) value).byteValue());
        break;
      case SMALLINT:
        udt.setShort(field, ((Number) value).shortValue());
        break;
      case INT:
        udt.setInt(field, ((Number) value).intValue());
        break;
      case BIGINT:
      case COUNTER:
        udt.setLong(field, ((Number) value).longValue());
        break;
      case FLOAT:
        udt.setFloat(field, ((Number) value).floatValue());
        break;
      case DOUBLE:
        udt.setDouble(field, ((Number) value).doubleValue());
        break;
      case BOOLEAN:
        udt.setBool(field, (Boolean) value);
        break;
      case TEXT:
      case VARCHAR:
      case ASCII:
        udt.setString(field, (String) value);
        break;
      case BLOB:
        udt.setBytes(field, (ByteBuffer) value);
        break;
      case UUID:
      case TIMEUUID:
        udt.setUUID(field, (UUID) value);
        break;
      case TIMESTAMP:
        if (value instanceof Date) {
          udt.setTimestamp(field, (Date) value);
        }
        break;
      case DATE:
        if (value instanceof com.datastax.driver.core.LocalDate) {
          udt.setDate(field, (com.datastax.driver.core.LocalDate) value);
        }
        break;
      case TIME:
        udt.setTime(field, ((Number) value).longValue());
        break;
      case INET:
        udt.setInet(field, (InetAddress) value);
        break;
      case VARINT:
        udt.setVarint(field, (BigInteger) value);
        break;
      case DECIMAL:
        udt.setDecimal(field, (BigDecimal) value);
        break;
      case DURATION:
        udt.set(field, (Duration) value, Duration.class);
        break;
      case LIST:
        udt.setList(field, (List<Object>) value);
        break;
      case SET:
        if (value instanceof List) {
          udt.setSet(field, new HashSet<>((List<Object>) value));
        } else {
          udt.setSet(field, (Set<Object>) value);
        }
        break;
      case MAP:
        udt.setMap(field, (Map<Object, Object>) value);
        break;
      case TUPLE:
        udt.setTupleValue(field, (TupleValue) value);
        break;
      case UDT:
        udt.setUDTValue(field, (UDTValue) value);
        break;
      default:
        udt.set(field, value, Object.class);
    }
  }

  /**
   * Build a rows frame from a ResultSet, avoiding full row materialization.
   *
   * <p>Instead of calling rs.all() which loads all rows into a List, this method
   * iterates the ResultSet directly. The ResultSet handles automatic paging internally,
   * so rows are fetched on-demand as we iterate.
   *
   * @param stream the stream identifier
   * @param rs the result set to serialize
   * @param latencyNs the query execution latency in nanoseconds
   * @return the serialized frame bytes
   */
  private byte[] buildRowsFrame(short stream, ResultSet rs, long latencyNs) {
    if (logger.isDebugEnabled()) {
      logger.debug("buildRowsFrame: latencyNs={} ({}ms)", latencyNs, latencyNs / 1_000_000.0);
    }

    ColumnDefinitions columns = rs.getColumnDefinitions();
    Protocol.BytesWriter writer = Protocol.BytesWriterPool.acquire();
    try {
      writer.writeInt(Protocol.RESULT_KIND_ROWS);
      writer.writeInt(0); // flags
      writer.writeInt(columns.size());

      // Pre-compute type codes
      int columnCount = columns.size();
      short[] typeCodes = new short[columnCount];
      DataType[] dataTypes = new DataType[columnCount];
      for (int i = 0; i < columnCount; i++) {
        dataTypes[i] = columns.getType(i);
        typeCodes[i] = ValueEncoder.getTypeCode(dataTypes[i]);
      }

      // Column metadata
      for (int i = 0; i < columnCount; i++) {
        String keyspace = columns.getKeyspace(i);
        String table = columns.getTable(i);
        String name = columns.getName(i);
        writer.writeString(keyspace != null ? keyspace : "");
        writer.writeString(table != null ? table : "");
        writer.writeString(name);
        writer.writeShort(typeCodes[i]);
      }

      // Count rows and serialize in one pass
      // We buffer row data separately since we need count first in the protocol
      Protocol.BytesWriter rowWriter = new Protocol.BytesWriter(1024);
      int rowCount = 0;
      for (Row row : rs) {
        rowCount++;
        for (int i = 0; i < columnCount; i++) {
          // Must check isNull first - getObject() may return empty collections instead of null
          Object value = row.isNull(i) ? null : row.getObject(i);
          byte[] encoded = ValueEncoder.encode(value, dataTypes[i]);
          rowWriter.writeBytesNullable(encoded);
        }
      }

      // Write row count and row data
      writer.writeInt(rowCount);
      writer.writeBytes(rowWriter.toByteArray());
      writer.writeLong(latencyNs);

      return Protocol.FrameBuilder.buildFrame(stream, Protocol.OPCODE_RESULT, writer.toByteArray());
    } finally {
      Protocol.BytesWriterPool.release(writer);
    }
  }

  /**
   * Build a rows frame to a pooled ByteBuf for zero-copy response.
   *
   * @param stream the stream identifier
   * @param rs the result set to serialize
   * @param latencyNs the query execution latency in nanoseconds
   * @return a pooled ByteBuf containing the frame (caller must release)
   */
  private ByteBuf buildRowsFrameByteBuf(short stream, ResultSet rs, long latencyNs) {
    if (logger.isDebugEnabled()) {
      logger.debug("buildRowsFrameByteBuf: latencyNs={} ({}ms)", latencyNs, latencyNs / 1_000_000.0);
    }

    ColumnDefinitions columns = rs.getColumnDefinitions();

    // Pre-compute type codes
    int columnCount = columns.size();
    short[] typeCodes = new short[columnCount];
    DataType[] dataTypes = new DataType[columnCount];
    for (int i = 0; i < columnCount; i++) {
      dataTypes[i] = columns.getType(i);
      typeCodes[i] = ValueEncoder.getTypeCode(dataTypes[i]);
    }

    // Use PooledBytesWriter for the body
    try (Protocol.PooledBytesWriter writer = new Protocol.PooledBytesWriter(1024)) {
      writer.writeInt(Protocol.RESULT_KIND_ROWS);
      writer.writeInt(0); // flags
      writer.writeInt(columnCount);

      // Column metadata
      for (int i = 0; i < columnCount; i++) {
        String keyspace = columns.getKeyspace(i);
        String table = columns.getTable(i);
        String name = columns.getName(i);
        writer.writeString(keyspace != null ? keyspace : "");
        writer.writeString(table != null ? table : "");
        writer.writeString(name);
        writer.writeShort(typeCodes[i]);
      }

      // Buffer rows since we need count first
      Protocol.BytesWriter rowWriter = Protocol.BytesWriterPool.acquire();
      try {
        int rowCount = 0;
        for (Row row : rs) {
          rowCount++;
          for (int i = 0; i < columnCount; i++) {
            Object value = row.isNull(i) ? null : row.getObject(i);
            // Use encodeTo to write directly to rowWriter
            ValueEncoder.encodeTo(rowWriter, value, dataTypes[i]);
          }
        }

        // Write row count and row data
        writer.writeInt(rowCount);
        byte[] rowBytes = rowWriter.toByteArray();
        if (rowBytes.length > 0) {
          writer.writeBytes(rowBytes);
        }
      } finally {
        Protocol.BytesWriterPool.release(rowWriter);
      }

      writer.writeLong(latencyNs);
      return Protocol.PooledFrameBuilder.buildFrame(stream, Protocol.OPCODE_RESULT, writer);
    }
  }

  private byte[] buildRowsFrameFromRows(
      short stream, ColumnDefinitions columns, List<Row> rows, long latencyNs) {
    if (logger.isDebugEnabled()) {
      logger.debug(
          "buildRowsFrameFromRows: latencyNs={} ({}ms)", latencyNs, latencyNs / 1_000_000.0);
    }

    Protocol.BytesWriter writer = Protocol.BytesWriterPool.acquire();
    try {
      writer.writeInt(Protocol.RESULT_KIND_ROWS);
      writer.writeInt(0); // flags
      writer.writeInt(columns.size());

      // Pre-compute type codes
      int columnCount = columns.size();
      short[] typeCodes = new short[columnCount];
      DataType[] dataTypes = new DataType[columnCount];
      for (int i = 0; i < columnCount; i++) {
        dataTypes[i] = columns.getType(i);
        typeCodes[i] = ValueEncoder.getTypeCode(dataTypes[i]);
      }

      // Column metadata
      for (int i = 0; i < columnCount; i++) {
        String keyspace = columns.getKeyspace(i);
        String table = columns.getTable(i);
        String name = columns.getName(i);
        writer.writeString(keyspace != null ? keyspace : "");
        writer.writeString(table != null ? table : "");
        writer.writeString(name);
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
    switch (level) {
      case Protocol.CONSISTENCY_ANY:
        return ConsistencyLevel.ANY;
      case Protocol.CONSISTENCY_ONE:
        return ConsistencyLevel.ONE;
      case Protocol.CONSISTENCY_TWO:
        return ConsistencyLevel.TWO;
      case Protocol.CONSISTENCY_THREE:
        return ConsistencyLevel.THREE;
      case Protocol.CONSISTENCY_QUORUM:
        return ConsistencyLevel.QUORUM;
      case Protocol.CONSISTENCY_ALL:
        return ConsistencyLevel.ALL;
      case Protocol.CONSISTENCY_LOCAL_QUORUM:
        return ConsistencyLevel.LOCAL_QUORUM;
      case Protocol.CONSISTENCY_EACH_QUORUM:
        return ConsistencyLevel.EACH_QUORUM;
      case Protocol.CONSISTENCY_LOCAL_ONE:
        return ConsistencyLevel.LOCAL_ONE;
      default:
        return ConsistencyLevel.ONE;
    }
  }

  private BatchStatement.Type toBatchType(byte type) {
    switch (type) {
      case 0:
        return BatchStatement.Type.LOGGED;
      case 1:
        return BatchStatement.Type.UNLOGGED;
      case 2:
        return BatchStatement.Type.COUNTER;
      default:
        return BatchStatement.Type.LOGGED;
    }
  }

  /**
   * Encode a list of vectors to CQL binary list format.
   *
   * <p>CQL list format: [count: i32] then for each element: [len: i32][data: bytes]
   *
   * <p>Note: Pooling the ByteBuffer is not safe here because the result is passed to
   * the driver via setBytesUnsafe() and the driver may hold the reference. The driver
   * controls the buffer lifecycle, not this code.
   */
  private ByteBuffer encodeVectorList(List<ByteBuffer> vectors) {
    // Calculate total size: 4 (count) + for each vector: 4 (len) + vector.remaining()
    int totalSize = 4;
    for (ByteBuffer v : vectors) {
      totalSize += 4 + v.remaining();
    }

    // Allocation is necessary - driver owns the buffer after setBytesUnsafe()
    ByteBuffer result = ByteBuffer.allocate(totalSize);
    result.putInt(vectors.size());
    for (ByteBuffer v : vectors) {
      result.putInt(v.remaining());
      // Write vector bytes without modifying original buffer position
      ByteBuffer dup = v.duplicate();
      result.put(dup);
    }
    result.flip();
    return result;
  }
}

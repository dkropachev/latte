package com.scylladb.latte;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.datastax.oss.driver.api.core.CqlSession;
import com.datastax.oss.driver.api.core.cql.BoundStatement;
import com.datastax.oss.driver.api.core.cql.ColumnDefinition;
import com.datastax.oss.driver.api.core.cql.ColumnDefinitions;
import com.datastax.oss.driver.api.core.cql.PreparedStatement;
import com.datastax.oss.driver.api.core.cql.ResultSet;
import com.datastax.oss.driver.api.core.cql.Row;
import com.datastax.oss.driver.api.core.cql.SimpleStatement;
import com.datastax.oss.driver.api.core.type.DataTypes;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("RequestHandler")
class RequestHandlerTest {

  @Mock private SessionManager sessionManager;

  @Mock private CqlSession cqlSession;

  @Mock private ResultSet resultSet;

  @Mock private ColumnDefinitions columnDefinitions;

  private RequestHandler requestHandler;

  @BeforeEach
  void setUp() {
    requestHandler = new RequestHandler(sessionManager);
  }

  @Nested
  @DisplayName("handleFrame")
  class HandleFrame {

    @Test
    @DisplayName("should return error for unknown opcode")
    void unknownOpcode() {
      byte[] body = new byte[0];
      Protocol.Frame frame =
          new Protocol.Frame(Protocol.VERSION_REQUEST, (byte) 0, (short) 1, (byte) 0xFF, body);

      byte[] response = requestHandler.handleFrame(frame);

      // Parse response
      Protocol.BytesReader reader = new Protocol.BytesReader(response);
      reader.skip(Protocol.HEADER_LENGTH);
      int errorCode = reader.readInt();
      String message = reader.readString();

      assertThat(response[4]).isEqualTo(Protocol.OPCODE_ERROR);
      assertThat(errorCode).isEqualTo(Protocol.ERROR_CODE_PROTOCOL);
      assertThat(message).contains("Unknown opcode");
    }

    @Test
    @DisplayName("should handle exceptions gracefully")
    void handleException() {
      // Create a frame that will cause an exception
      byte[] body = new byte[0]; // Empty body will cause parsing error
      Protocol.Frame frame =
          new Protocol.Frame(
              Protocol.VERSION_REQUEST, (byte) 0, (short) 1, Protocol.OPCODE_QUERY, body);

      byte[] response = requestHandler.handleFrame(frame);

      // Should return error frame, not throw
      assertThat(response[4]).isEqualTo(Protocol.OPCODE_ERROR);
    }
  }

  @Nested
  @DisplayName("handleCreateSession")
  class HandleCreateSession {

    @Test
    @DisplayName("should create session and return session ID")
    void createSession() {
      when(sessionManager.createSession(any())).thenReturn(42L);

      // Build CREATE_SESSION request
      Protocol.BytesWriter bodyWriter = new Protocol.BytesWriter();
      bodyWriter.writeUShort(1); // 1 parameter
      bodyWriter.writeString("contact_points");
      bodyWriter.writeString("127.0.0.1");

      Protocol.Frame frame =
          new Protocol.Frame(
              Protocol.VERSION_REQUEST,
              (byte) 0,
              (short) 1,
              Protocol.OPCODE_CREATE_SESSION,
              bodyWriter.toByteArray());

      byte[] response = requestHandler.handleFrame(frame);

      // Verify response
      assertThat(response[4]).isEqualTo(Protocol.OPCODE_SESSION_CREATED);

      Protocol.BytesReader reader = new Protocol.BytesReader(response);
      reader.skip(Protocol.HEADER_LENGTH);
      long sessionId = reader.readLong();
      assertThat(sessionId).isEqualTo(42L);
    }
  }

  @Nested
  @DisplayName("handleQuery")
  class HandleQuery {

    @Test
    @DisplayName("should execute query and return rows")
    void executeQuery() {
      // Setup mocks
      SessionManager.SessionEntry entry =
          new SessionManager.SessionEntry(cqlSession, new ConcurrentHashMap<>());
      when(sessionManager.getSession(1L)).thenReturn(entry);
      when(cqlSession.execute(any(SimpleStatement.class))).thenReturn(resultSet);
      when(resultSet.getColumnDefinitions()).thenReturn(columnDefinitions);
      when(resultSet.all()).thenReturn(Collections.emptyList());
      when(columnDefinitions.size()).thenReturn(0);
      when(columnDefinitions.iterator()).thenReturn(Collections.emptyIterator());

      // Build QUERY request
      Protocol.BytesWriter bodyWriter = new Protocol.BytesWriter();
      bodyWriter.writeLong(1L); // session ID
      bodyWriter.writeLongString("SELECT * FROM test"); // query
      bodyWriter.writeShort(Protocol.CONSISTENCY_ONE); // consistency
      bodyWriter.writeByte(0); // flags

      Protocol.Frame frame =
          new Protocol.Frame(
              Protocol.VERSION_REQUEST,
              (byte) 0,
              (short) 1,
              Protocol.OPCODE_QUERY,
              bodyWriter.toByteArray());

      byte[] response = requestHandler.handleFrame(frame);

      // Verify response is a RESULT frame
      assertThat(response[4]).isEqualTo(Protocol.OPCODE_RESULT);

      Protocol.BytesReader reader = new Protocol.BytesReader(response);
      reader.skip(Protocol.HEADER_LENGTH);
      int resultKind = reader.readInt();
      assertThat(resultKind).isEqualTo(Protocol.RESULT_KIND_ROWS);
    }

    @Test
    @DisplayName("should return error for non-existent session")
    void nonExistentSession() {
      when(sessionManager.getSession(999L))
          .thenThrow(new Protocol.ProtocolException("Session not found: 999"));

      // Build QUERY request with non-existent session
      Protocol.BytesWriter bodyWriter = new Protocol.BytesWriter();
      bodyWriter.writeLong(999L); // non-existent session ID
      bodyWriter.writeLongString("SELECT * FROM test");
      bodyWriter.writeShort(Protocol.CONSISTENCY_ONE);
      bodyWriter.writeByte(0);

      Protocol.Frame frame =
          new Protocol.Frame(
              Protocol.VERSION_REQUEST,
              (byte) 0,
              (short) 1,
              Protocol.OPCODE_QUERY,
              bodyWriter.toByteArray());

      byte[] response = requestHandler.handleFrame(frame);

      assertThat(response[4]).isEqualTo(Protocol.OPCODE_ERROR);

      Protocol.BytesReader reader = new Protocol.BytesReader(response);
      reader.skip(Protocol.HEADER_LENGTH);
      reader.readInt(); // error code
      String message = reader.readString();
      assertThat(message).contains("Session not found");
    }
  }

  @Nested
  @DisplayName("handlePrepare")
  class HandlePrepare {

    @Mock private PreparedStatement preparedStatement;

    @Test
    @DisplayName("should prepare statement and cache it")
    void prepareStatement() {
      SessionManager.SessionEntry entry =
          new SessionManager.SessionEntry(cqlSession, new ConcurrentHashMap<>());
      when(sessionManager.getSession(1L)).thenReturn(entry);
      when(cqlSession.prepare(anyString())).thenReturn(preparedStatement);

      // Build PREPARE request
      Protocol.BytesWriter bodyWriter = new Protocol.BytesWriter();
      bodyWriter.writeLong(1L); // session ID
      bodyWriter.writeLongString("SELECT * FROM test WHERE id = ?"); // query
      bodyWriter.writeString("stmt1"); // statement key

      Protocol.Frame frame =
          new Protocol.Frame(
              Protocol.VERSION_REQUEST,
              (byte) 0,
              (short) 1,
              Protocol.OPCODE_PREPARE,
              bodyWriter.toByteArray());

      byte[] response = requestHandler.handleFrame(frame);

      // Verify response
      assertThat(response[4]).isEqualTo(Protocol.OPCODE_RESULT);

      Protocol.BytesReader reader = new Protocol.BytesReader(response);
      reader.skip(Protocol.HEADER_LENGTH);
      int resultKind = reader.readInt();
      assertThat(resultKind).isEqualTo(Protocol.RESULT_KIND_PREPARED);

      // Verify statement was cached
      assertThat(entry.getPrepared("stmt1")).isEqualTo(preparedStatement);
    }
  }

  @Nested
  @DisplayName("handleExecute")
  class HandleExecute {

    @Mock private PreparedStatement preparedStatement;
    @Mock private BoundStatement boundStatement;
    @Mock private ColumnDefinitions variableDefinitions;

    @Test
    @DisplayName("should return error for unprepared statement")
    void unpreparedStatement() {
      SessionManager.SessionEntry entry =
          new SessionManager.SessionEntry(cqlSession, new ConcurrentHashMap<>());
      when(sessionManager.getSession(1L)).thenReturn(entry);

      // Build EXECUTE request for non-existent statement
      Protocol.BytesWriter bodyWriter = new Protocol.BytesWriter();
      bodyWriter.writeLong(1L); // session ID
      bodyWriter.writeString("unknown_stmt"); // non-existent statement key
      bodyWriter.writeShort(Protocol.CONSISTENCY_ONE);
      bodyWriter.writeByte(0); // no values

      Protocol.Frame frame =
          new Protocol.Frame(
              Protocol.VERSION_REQUEST,
              (byte) 0,
              (short) 1,
              Protocol.OPCODE_EXECUTE,
              bodyWriter.toByteArray());

      byte[] response = requestHandler.handleFrame(frame);

      assertThat(response[4]).isEqualTo(Protocol.OPCODE_ERROR);

      Protocol.BytesReader reader = new Protocol.BytesReader(response);
      reader.skip(Protocol.HEADER_LENGTH);
      int errorCode = reader.readInt();
      String message = reader.readString();

      assertThat(errorCode).isEqualTo(Protocol.ERROR_CODE_UNPREPARED);
      assertThat(message).contains("Statement not prepared");
    }

    @Test
    @DisplayName("should execute prepared statement without values")
    void executeWithoutValues() {
      ConcurrentHashMap<String, PreparedStatement> cache = new ConcurrentHashMap<>();
      cache.put("stmt1", preparedStatement);
      SessionManager.SessionEntry entry = new SessionManager.SessionEntry(cqlSession, cache);

      when(sessionManager.getSession(1L)).thenReturn(entry);
      when(preparedStatement.bind()).thenReturn(boundStatement);
      when(boundStatement.setConsistencyLevel(any())).thenReturn(boundStatement);
      when(cqlSession.execute(boundStatement)).thenReturn(resultSet);
      when(resultSet.getColumnDefinitions()).thenReturn(columnDefinitions);
      when(resultSet.all()).thenReturn(Collections.emptyList());
      when(columnDefinitions.size()).thenReturn(0);
      when(columnDefinitions.iterator()).thenReturn(Collections.emptyIterator());

      // Build EXECUTE request without values
      Protocol.BytesWriter bodyWriter = new Protocol.BytesWriter();
      bodyWriter.writeLong(1L); // session ID
      bodyWriter.writeString("stmt1");
      bodyWriter.writeShort(Protocol.CONSISTENCY_ONE);
      bodyWriter.writeByte(0); // no values flag

      Protocol.Frame frame =
          new Protocol.Frame(
              Protocol.VERSION_REQUEST,
              (byte) 0,
              (short) 1,
              Protocol.OPCODE_EXECUTE,
              bodyWriter.toByteArray());

      byte[] response = requestHandler.handleFrame(frame);

      assertThat(response[4]).isEqualTo(Protocol.OPCODE_RESULT);

      Protocol.BytesReader reader = new Protocol.BytesReader(response);
      reader.skip(Protocol.HEADER_LENGTH);
      int resultKind = reader.readInt();
      assertThat(resultKind).isEqualTo(Protocol.RESULT_KIND_ROWS);
    }

    @Test
    @DisplayName("should execute prepared statement with values")
    void executeWithValues() {
      ConcurrentHashMap<String, PreparedStatement> cache = new ConcurrentHashMap<>();
      cache.put("stmt1", preparedStatement);
      SessionManager.SessionEntry entry = new SessionManager.SessionEntry(cqlSession, cache);

      ColumnDefinition colDef = mock(ColumnDefinition.class);
      when(colDef.getType()).thenReturn(DataTypes.INT);

      when(sessionManager.getSession(1L)).thenReturn(entry);
      when(preparedStatement.bind()).thenReturn(boundStatement);
      when(preparedStatement.getVariableDefinitions()).thenReturn(variableDefinitions);
      when(variableDefinitions.size()).thenReturn(1);
      when(variableDefinitions.get(0)).thenReturn(colDef);
      when(boundStatement.setConsistencyLevel(any())).thenReturn(boundStatement);
      when(boundStatement.setInt(0, 42)).thenReturn(boundStatement);
      when(cqlSession.execute(boundStatement)).thenReturn(resultSet);
      when(resultSet.getColumnDefinitions()).thenReturn(columnDefinitions);
      when(resultSet.all()).thenReturn(Collections.emptyList());
      when(columnDefinitions.size()).thenReturn(0);
      when(columnDefinitions.iterator()).thenReturn(Collections.emptyIterator());

      // Build EXECUTE request with one int value
      Protocol.BytesWriter bodyWriter = new Protocol.BytesWriter();
      bodyWriter.writeLong(1L); // session ID
      bodyWriter.writeString("stmt1");
      bodyWriter.writeShort(Protocol.CONSISTENCY_ONE);
      bodyWriter.writeByte(0x01); // has values flag
      bodyWriter.writeUShort(1); // value count

      // Write int value (42)
      bodyWriter.writeShort(Protocol.TYPE_INT);
      byte[] intBytes = {0x00, 0x00, 0x00, 0x2A}; // 42
      bodyWriter.writeInt(intBytes.length);
      bodyWriter.writeBytes(intBytes);

      Protocol.Frame frame =
          new Protocol.Frame(
              Protocol.VERSION_REQUEST,
              (byte) 0,
              (short) 1,
              Protocol.OPCODE_EXECUTE,
              bodyWriter.toByteArray());

      byte[] response = requestHandler.handleFrame(frame);

      assertThat(response[4]).isEqualTo(Protocol.OPCODE_RESULT);
    }

    @Test
    @DisplayName("should handle null values in execute")
    void executeWithNullValue() {
      ConcurrentHashMap<String, PreparedStatement> cache = new ConcurrentHashMap<>();
      cache.put("stmt1", preparedStatement);
      SessionManager.SessionEntry entry = new SessionManager.SessionEntry(cqlSession, cache);

      ColumnDefinition colDef = mock(ColumnDefinition.class);
      // Note: getType() is not called for null values since we use setToNull directly

      when(sessionManager.getSession(1L)).thenReturn(entry);
      when(preparedStatement.bind()).thenReturn(boundStatement);
      when(preparedStatement.getVariableDefinitions()).thenReturn(variableDefinitions);
      when(variableDefinitions.size()).thenReturn(1);
      when(variableDefinitions.get(0)).thenReturn(colDef);
      when(boundStatement.setConsistencyLevel(any())).thenReturn(boundStatement);
      when(boundStatement.setToNull(0)).thenReturn(boundStatement);
      when(cqlSession.execute(boundStatement)).thenReturn(resultSet);
      when(resultSet.getColumnDefinitions()).thenReturn(columnDefinitions);
      when(resultSet.all()).thenReturn(Collections.emptyList());
      when(columnDefinitions.size()).thenReturn(0);
      when(columnDefinitions.iterator()).thenReturn(Collections.emptyIterator());

      // Build EXECUTE request with null value (length = -1)
      Protocol.BytesWriter bodyWriter = new Protocol.BytesWriter();
      bodyWriter.writeLong(1L);
      bodyWriter.writeString("stmt1");
      bodyWriter.writeShort(Protocol.CONSISTENCY_ONE);
      bodyWriter.writeByte(0x01); // has values
      bodyWriter.writeUShort(1); // value count
      bodyWriter.writeShort(Protocol.TYPE_TEXT);
      bodyWriter.writeInt(-1); // null indicator

      Protocol.Frame frame =
          new Protocol.Frame(
              Protocol.VERSION_REQUEST,
              (byte) 0,
              (short) 1,
              Protocol.OPCODE_EXECUTE,
              bodyWriter.toByteArray());

      byte[] response = requestHandler.handleFrame(frame);

      assertThat(response[4]).isEqualTo(Protocol.OPCODE_RESULT);
    }
  }

  @Nested
  @DisplayName("handleBatch")
  class HandleBatch {

    @Mock private PreparedStatement preparedStatement;
    @Mock private BoundStatement boundStatement;
    @Mock private ColumnDefinitions variableDefinitions;

    @Test
    @DisplayName("should return error for unprepared statement in batch")
    void unpreparedStatementInBatch() {
      SessionManager.SessionEntry entry =
          new SessionManager.SessionEntry(cqlSession, new ConcurrentHashMap<>());
      when(sessionManager.getSession(1L)).thenReturn(entry);

      // Build BATCH request with non-existent statement
      Protocol.BytesWriter bodyWriter = new Protocol.BytesWriter();
      bodyWriter.writeLong(1L); // session ID
      bodyWriter.writeByte(0); // batch type (LOGGED)
      bodyWriter.writeUShort(1); // statement count
      bodyWriter.writeByte(1); // kind = prepared
      bodyWriter.writeString("unknown_stmt");
      bodyWriter.writeUShort(0); // no values
      bodyWriter.writeShort(Protocol.CONSISTENCY_ONE);
      bodyWriter.writeByte(0); // flags

      Protocol.Frame frame =
          new Protocol.Frame(
              Protocol.VERSION_REQUEST,
              (byte) 0,
              (short) 1,
              Protocol.OPCODE_BATCH,
              bodyWriter.toByteArray());

      byte[] response = requestHandler.handleFrame(frame);

      assertThat(response[4]).isEqualTo(Protocol.OPCODE_ERROR);

      Protocol.BytesReader reader = new Protocol.BytesReader(response);
      reader.skip(Protocol.HEADER_LENGTH);
      int errorCode = reader.readInt();
      assertThat(errorCode).isEqualTo(Protocol.ERROR_CODE_UNPREPARED);
    }

    @Test
    @DisplayName("should return error for non-prepared batch kind")
    void nonPreparedBatchKind() {
      SessionManager.SessionEntry entry =
          new SessionManager.SessionEntry(cqlSession, new ConcurrentHashMap<>());
      when(sessionManager.getSession(1L)).thenReturn(entry);

      // Build BATCH request with simple statement (kind = 0)
      Protocol.BytesWriter bodyWriter = new Protocol.BytesWriter();
      bodyWriter.writeLong(1L);
      bodyWriter.writeByte(0); // batch type
      bodyWriter.writeUShort(1); // statement count
      bodyWriter.writeByte(0); // kind = simple (not prepared)
      bodyWriter.writeLongString("INSERT INTO test (id) VALUES (1)");
      bodyWriter.writeUShort(0);
      bodyWriter.writeShort(Protocol.CONSISTENCY_ONE);
      bodyWriter.writeByte(0);

      Protocol.Frame frame =
          new Protocol.Frame(
              Protocol.VERSION_REQUEST,
              (byte) 0,
              (short) 1,
              Protocol.OPCODE_BATCH,
              bodyWriter.toByteArray());

      byte[] response = requestHandler.handleFrame(frame);

      assertThat(response[4]).isEqualTo(Protocol.OPCODE_ERROR);

      Protocol.BytesReader reader = new Protocol.BytesReader(response);
      reader.skip(Protocol.HEADER_LENGTH);
      int errorCode = reader.readInt();
      String message = reader.readString();
      assertThat(errorCode).isEqualTo(Protocol.ERROR_CODE_PROTOCOL);
      assertThat(message).contains("Only prepared statements");
    }

    @Test
    @DisplayName("should execute batch successfully")
    void executeBatch() {
      ConcurrentHashMap<String, PreparedStatement> cache = new ConcurrentHashMap<>();
      cache.put("stmt1", preparedStatement);
      SessionManager.SessionEntry entry = new SessionManager.SessionEntry(cqlSession, cache);

      when(sessionManager.getSession(1L)).thenReturn(entry);
      when(preparedStatement.bind()).thenReturn(boundStatement);
      // Note: getVariableDefinitions() is only used when valueCount > 0
      when(cqlSession.execute(any(com.datastax.oss.driver.api.core.cql.BatchStatement.class)))
          .thenReturn(resultSet);

      // Build BATCH request
      Protocol.BytesWriter bodyWriter = new Protocol.BytesWriter();
      bodyWriter.writeLong(1L);
      bodyWriter.writeByte(0); // LOGGED batch
      bodyWriter.writeUShort(1); // 1 statement
      bodyWriter.writeByte(1); // kind = prepared
      bodyWriter.writeString("stmt1");
      bodyWriter.writeUShort(0); // no values
      bodyWriter.writeShort(Protocol.CONSISTENCY_ONE);
      bodyWriter.writeByte(0);

      Protocol.Frame frame =
          new Protocol.Frame(
              Protocol.VERSION_REQUEST,
              (byte) 0,
              (short) 1,
              Protocol.OPCODE_BATCH,
              bodyWriter.toByteArray());

      byte[] response = requestHandler.handleFrame(frame);

      assertThat(response[4]).isEqualTo(Protocol.OPCODE_RESULT);

      Protocol.BytesReader reader = new Protocol.BytesReader(response);
      reader.skip(Protocol.HEADER_LENGTH);
      int resultKind = reader.readInt();
      assertThat(resultKind).isEqualTo(Protocol.RESULT_KIND_VOID);
    }
  }

  @Nested
  @DisplayName("Result Frame Building")
  class ResultFrameBuilding {

    @Test
    @DisplayName("should include latency in rows frame")
    void rowsFrameIncludesLatency() {
      SessionManager.SessionEntry entry =
          new SessionManager.SessionEntry(cqlSession, new ConcurrentHashMap<>());
      when(sessionManager.getSession(1L)).thenReturn(entry);
      when(cqlSession.execute(any(SimpleStatement.class))).thenReturn(resultSet);
      when(resultSet.getColumnDefinitions()).thenReturn(columnDefinitions);
      when(resultSet.all()).thenReturn(Collections.emptyList());
      when(columnDefinitions.size()).thenReturn(0);
      when(columnDefinitions.iterator()).thenReturn(Collections.emptyIterator());

      // Build QUERY request
      Protocol.BytesWriter bodyWriter = new Protocol.BytesWriter();
      bodyWriter.writeLong(1L);
      bodyWriter.writeLongString("SELECT 1");
      bodyWriter.writeShort(Protocol.CONSISTENCY_ONE);
      bodyWriter.writeByte(0);

      Protocol.Frame frame =
          new Protocol.Frame(
              Protocol.VERSION_REQUEST,
              (byte) 0,
              (short) 1,
              Protocol.OPCODE_QUERY,
              bodyWriter.toByteArray());

      byte[] response = requestHandler.handleFrame(frame);

      // Parse response and verify latency is present
      Protocol.BytesReader reader = new Protocol.BytesReader(response);
      reader.skip(Protocol.HEADER_LENGTH);

      int resultKind = reader.readInt();
      assertThat(resultKind).isEqualTo(Protocol.RESULT_KIND_ROWS);

      int flags = reader.readInt();
      int colCount = reader.readInt();
      assertThat(colCount).isEqualTo(0);

      int rowCount = reader.readInt();
      assertThat(rowCount).isEqualTo(0);

      // Latency should be at the end
      long latencyNs = reader.readLong();
      assertThat(latencyNs).isGreaterThan(0);
    }
  }

  @Nested
  @DisplayName("Consistency Levels")
  class ConsistencyLevels {

    @Test
    @DisplayName("should handle all consistency levels")
    void allConsistencyLevels() {
      SessionManager.SessionEntry entry =
          new SessionManager.SessionEntry(cqlSession, new ConcurrentHashMap<>());
      when(sessionManager.getSession(1L)).thenReturn(entry);
      when(cqlSession.execute(any(SimpleStatement.class))).thenReturn(resultSet);
      when(resultSet.getColumnDefinitions()).thenReturn(columnDefinitions);
      when(resultSet.all()).thenReturn(Collections.emptyList());
      when(columnDefinitions.size()).thenReturn(0);
      when(columnDefinitions.iterator()).thenReturn(Collections.emptyIterator());

      short[] consistencies = {
        Protocol.CONSISTENCY_ANY,
        Protocol.CONSISTENCY_ONE,
        Protocol.CONSISTENCY_TWO,
        Protocol.CONSISTENCY_THREE,
        Protocol.CONSISTENCY_QUORUM,
        Protocol.CONSISTENCY_ALL,
        Protocol.CONSISTENCY_LOCAL_QUORUM,
        Protocol.CONSISTENCY_EACH_QUORUM,
        Protocol.CONSISTENCY_LOCAL_ONE
      };

      for (short consistency : consistencies) {
        Protocol.BytesWriter bodyWriter = new Protocol.BytesWriter();
        bodyWriter.writeLong(1L);
        bodyWriter.writeLongString("SELECT 1");
        bodyWriter.writeShort(consistency);
        bodyWriter.writeByte(0);

        Protocol.Frame frame =
            new Protocol.Frame(
                Protocol.VERSION_REQUEST,
                (byte) 0,
                (short) 1,
                Protocol.OPCODE_QUERY,
                bodyWriter.toByteArray());

        byte[] response = requestHandler.handleFrame(frame);
        assertThat(response[4])
            .as("Consistency %d should succeed", consistency)
            .isEqualTo(Protocol.OPCODE_RESULT);
      }
    }
  }
}

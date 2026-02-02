package com.scylladb.latte;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufAllocator;
import io.netty.buffer.PooledByteBufAllocator;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;

/**
 * IPC protocol constants and frame handling for Latte driver communication.
 *
 * <p>This class defines the binary protocol used for communication between the Latte benchmarking
 * tool and driver adapters. The protocol is based on CQL native protocol v4 with extensions for
 * IPC-specific operations.
 *
 * <h2>Frame Format</h2>
 *
 * <pre>
 * +-------+-------+--------+--------+--------+---------+
 * |  ver  | flags | stream | opcode |  body  |  body   |
 * | 1byte | 1byte | 2bytes | 1byte  | length |  data   |
 * |       |       |        |        | 4bytes |  ...    |
 * +-------+-------+--------+--------+--------+---------+
 * </pre>
 *
 * <h2>Supported Operations</h2>
 *
 * <ul>
 *   <li>{@link #OPCODE_CREATE_SESSION} - Create a new database session
 *   <li>{@link #OPCODE_QUERY} - Execute a simple CQL query
 *   <li>{@link #OPCODE_PREPARE} - Prepare a CQL statement
 *   <li>{@link #OPCODE_EXECUTE} - Execute a prepared statement
 *   <li>{@link #OPCODE_BATCH} - Execute a batch of statements
 * </ul>
 */
public final class Protocol {
  public static final int IPC_PROTOCOL_VERSION = 1;
  public static final int HEADER_LENGTH = 9;
  public static final int MAX_BODY_LENGTH = 16 * 1024 * 1024; // 16MB

  public static final byte VERSION_REQUEST = 0x04;
  public static final byte VERSION_RESPONSE = (byte) 0x84;

  // Opcodes
  public static final byte OPCODE_ERROR = 0x00;
  public static final byte OPCODE_QUERY = 0x07;
  public static final byte OPCODE_RESULT = 0x08;
  public static final byte OPCODE_PREPARE = 0x09;
  public static final byte OPCODE_EXECUTE = 0x0A;
  public static final byte OPCODE_BATCH = 0x0D;
  public static final byte OPCODE_CREATE_SESSION = 0x21;
  public static final byte OPCODE_SESSION_CREATED = 0x22;

  // Result kinds
  public static final int RESULT_KIND_VOID = 0x0001;
  public static final int RESULT_KIND_ROWS = 0x0002;
  public static final int RESULT_KIND_SET_KEYSPACE = 0x0003;
  public static final int RESULT_KIND_PREPARED = 0x0004;
  public static final int RESULT_KIND_SCHEMA_CHANGE = 0x0005;

  // Error codes
  public static final int ERROR_CODE_SERVER = 0x0000;
  public static final int ERROR_CODE_PROTOCOL = 0x000A;
  public static final int ERROR_CODE_OVERLOADED = 0x1001;
  public static final int ERROR_CODE_UNPREPARED = 0x2500;

  // CQL type codes
  public static final short TYPE_ASCII = 0x0001;
  public static final short TYPE_BIGINT = 0x0002;
  public static final short TYPE_BLOB = 0x0003;
  public static final short TYPE_BOOLEAN = 0x0004;
  public static final short TYPE_COUNTER = 0x0005;
  public static final short TYPE_DECIMAL = 0x0006;
  public static final short TYPE_DOUBLE = 0x0007;
  public static final short TYPE_FLOAT = 0x0008;
  public static final short TYPE_INT = 0x0009;
  public static final short TYPE_TIMESTAMP = 0x000B;
  public static final short TYPE_UUID = 0x000C;
  public static final short TYPE_TEXT = 0x000D;
  public static final short TYPE_VARINT = 0x000E;
  public static final short TYPE_TIMEUUID = 0x000F;
  public static final short TYPE_INET = 0x0010;
  public static final short TYPE_DATE = 0x0011;
  public static final short TYPE_TIME = 0x0012;
  public static final short TYPE_SMALLINT = 0x0013;
  public static final short TYPE_TINYINT = 0x0014;
  public static final short TYPE_DURATION = 0x0015;
  public static final short TYPE_LIST = 0x0020;
  public static final short TYPE_MAP = 0x0021;
  public static final short TYPE_SET = 0x0022;
  public static final short TYPE_VECTOR = 0x0030;
  public static final short TYPE_TUPLE = 0x0031;
  /** Packed format for list&lt;vector&lt;float, N&gt;&gt; - eliminates per-element length prefixes */
  public static final short TYPE_PACKED_FLOAT_VECTOR_LIST = 0x0032;
  public static final short TYPE_UDT = 0x0040;

  // Consistency levels
  public static final short CONSISTENCY_ANY = 0x0000;
  public static final short CONSISTENCY_ONE = 0x0001;
  public static final short CONSISTENCY_TWO = 0x0002;
  public static final short CONSISTENCY_THREE = 0x0003;
  public static final short CONSISTENCY_QUORUM = 0x0004;
  public static final short CONSISTENCY_ALL = 0x0005;
  public static final short CONSISTENCY_LOCAL_QUORUM = 0x0006;
  public static final short CONSISTENCY_EACH_QUORUM = 0x0007;
  public static final short CONSISTENCY_LOCAL_ONE = 0x000A;

  private Protocol() {}

  /**
   * Represents a parsed frame from the IPC protocol.
   *
   * <p>Supports both byte[] and ByteBuf body representations for flexibility.
   * When constructed with a ByteBuf, the body is lazily materialized to byte[]
   * only when needed, avoiding unnecessary copies.
   */
  public static final class Frame {
    private final byte version;
    private final byte flags;
    private final short stream;
    private final byte opcode;
    private byte[] body;
    private final ByteBuf bodyBuf;

    public Frame(byte version, byte flags, short stream, byte opcode, byte[] body) {
      this.version = version;
      this.flags = flags;
      this.stream = stream;
      this.opcode = opcode;
      this.body = body;
      this.bodyBuf = null;
    }

    /**
     * Create a frame with a retained ByteBuf body slice.
     *
     * <p>The ByteBuf is retained and must be released after use. The body()
     * method will materialize it to a byte[] on first access.
     */
    public Frame(byte version, byte flags, short stream, byte opcode, ByteBuf bodyBuf) {
      this.version = version;
      this.flags = flags;
      this.stream = stream;
      this.opcode = opcode;
      this.body = null;
      this.bodyBuf = bodyBuf;
    }

    public byte version() {
      return version;
    }

    public byte flags() {
      return flags;
    }

    public short stream() {
      return stream;
    }

    public byte opcode() {
      return opcode;
    }

    /**
     * Get the frame body as a byte array.
     *
     * <p>If this frame was constructed with a ByteBuf, the body is materialized
     * to a byte[] on first access and cached for subsequent calls.
     */
    public byte[] body() {
      if (body == null && bodyBuf != null) {
        // Materialize ByteBuf to byte[] on first access
        body = new byte[bodyBuf.readableBytes()];
        bodyBuf.getBytes(bodyBuf.readerIndex(), body);
      }
      return body;
    }

    /**
     * Get the ByteBuf body if available, or null if this frame was created with byte[].
     *
     * <p>This allows direct access to the ByteBuf for zero-copy processing when possible.
     */
    public ByteBuf bodyBuf() {
      return bodyBuf;
    }

    /**
     * Release any retained ByteBuf resources.
     *
     * <p>Should be called when the frame is no longer needed if it was created
     * with a ByteBuf body.
     */
    public void release() {
      if (bodyBuf != null) {
        bodyBuf.release();
      }
    }
  }

  /**
   * Helper for reading binary data in big-endian format.
   *
   * <p>Wraps a byte array and provides methods for reading various data types in network byte order
   * (big-endian), as required by the CQL protocol.
   */
  public static class BytesReader {
    private ByteBuffer buffer;

    public BytesReader(byte[] data) {
      this.buffer = ByteBuffer.wrap(data);
    }

    /**
     * Reset this reader to read from new data.
     *
     * <p>This allows reusing the reader instance to avoid allocation.
     *
     * @param data the new data to read from
     */
    public void reset(byte[] data) {
      this.buffer = ByteBuffer.wrap(data);
    }

    public int position() {
      return buffer.position();
    }

    public int remaining() {
      return buffer.remaining();
    }

    public boolean hasRemaining() {
      return buffer.hasRemaining();
    }

    public byte readByte() {
      return buffer.get();
    }

    public short readShort() {
      return buffer.getShort();
    }

    public int readUShort() {
      return buffer.getShort() & 0xFFFF;
    }

    public int readInt() {
      return buffer.getInt();
    }

    public long readLong() {
      return buffer.getLong();
    }

    public byte[] readBytes(int length) {
      byte[] bytes = new byte[length];
      buffer.get(bytes);
      return bytes;
    }

    public String readString() {
      int length = readUShort();
      byte[] bytes = readBytes(length);
      return new String(bytes, StandardCharsets.UTF_8);
    }

    public String readLongString() {
      int length = readInt();
      if (length < 0) {
        throw new ProtocolException("Invalid negative string length");
      }
      byte[] bytes = readBytes(length);
      return new String(bytes, StandardCharsets.UTF_8);
    }

    public byte[] readBytesNullable() {
      int length = readInt();
      if (length < 0) {
        return null;
      }
      return readBytes(length);
    }

    public Map<String, String> readStringMap() {
      int count = readUShort();
      Map<String, String> map = new HashMap<>(count);
      for (int i = 0; i < count; i++) {
        String key = readString();
        String value = readString();
        map.put(key, value);
      }
      return map;
    }

    public void skip(int count) {
      buffer.position(buffer.position() + count);
    }
  }

  /**
   * Helper for writing binary data in big-endian format.
   *
   * <p>Provides methods for writing various data types in network byte order (big-endian), as
   * required by the CQL protocol. Uses an internal ByteArrayOutputStream for efficient buffer
   * management.
   */
  public static class BytesWriter {
    private final ByteArrayOutputStream stream;
    private final byte[] buffer = new byte[8];

    public BytesWriter() {
      this.stream = new ByteArrayOutputStream(256);
    }

    public BytesWriter(int initialCapacity) {
      this.stream = new ByteArrayOutputStream(initialCapacity);
    }

    public int size() {
      return stream.size();
    }

    public void writeByte(int value) {
      stream.write(value);
    }

    public void writeShort(short value) {
      buffer[0] = (byte) (value >> 8);
      buffer[1] = (byte) value;
      stream.write(buffer, 0, 2);
    }

    public void writeUShort(int value) {
      buffer[0] = (byte) (value >> 8);
      buffer[1] = (byte) value;
      stream.write(buffer, 0, 2);
    }

    public void writeInt(int value) {
      buffer[0] = (byte) (value >> 24);
      buffer[1] = (byte) (value >> 16);
      buffer[2] = (byte) (value >> 8);
      buffer[3] = (byte) value;
      stream.write(buffer, 0, 4);
    }

    public void writeLong(long value) {
      buffer[0] = (byte) (value >> 56);
      buffer[1] = (byte) (value >> 48);
      buffer[2] = (byte) (value >> 40);
      buffer[3] = (byte) (value >> 32);
      buffer[4] = (byte) (value >> 24);
      buffer[5] = (byte) (value >> 16);
      buffer[6] = (byte) (value >> 8);
      buffer[7] = (byte) value;
      stream.write(buffer, 0, 8);
    }

    public void writeBytes(byte[] bytes) {
      try {
        stream.write(bytes);
      } catch (IOException e) {
        throw new ProtocolException("Failed to write bytes", e);
      }
    }

    public void writeBytes(byte[] bytes, int offset, int length) {
      stream.write(bytes, offset, length);
    }

    public void writeString(String value) {
      byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
      if (bytes.length > 65535) {
        throw new ProtocolException("String too long: " + bytes.length + " bytes");
      }
      writeUShort(bytes.length);
      writeBytes(bytes);
    }

    public void writeLongString(String value) {
      byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
      writeInt(bytes.length);
      writeBytes(bytes);
    }

    public void writeBytesNullable(byte[] bytes) {
      if (bytes == null) {
        writeInt(-1);
      } else {
        writeInt(bytes.length);
        writeBytes(bytes);
      }
    }

    public byte[] toByteArray() {
      return stream.toByteArray();
    }

    /**
     * Reset this writer for reuse.
     *
     * <p>Clears the internal buffer so this writer can be reused without allocating a new instance.
     * This is useful in hot paths where many small writes occur sequentially.
     */
    public void reset() {
      stream.reset();
    }
  }

  /**
   * Thread-local cache of BytesWriter instances for hot path optimization.
   *
   * <p>Provides reusable BytesWriter instances to reduce allocation pressure in
   * performance-critical code paths. Each thread gets its own cached writer to avoid
   * synchronization overhead.
   *
   * <p>The default capacity can be configured via the {@code latte.bytesWriterPoolCapacity} system
   * property (default: 4096 bytes).
   *
   * <p>Note: This pool tracks nesting depth to handle recursive encoding (e.g., nested
   * collections). Only the outermost acquire() returns the pooled writer; nested calls get new
   * instances. The depth tracking overhead (2 ThreadLocal gets + 1 set per acquire/release) is
   * necessary for correctness and is marginal compared to the cost of allocations it prevents.
   *
   * <p>Usage:
   *
   * <pre>{@code
   * BytesWriter writer = BytesWriterPool.acquire();
   * try {
   *     writer.writeInt(value);
   *     return writer.toByteArray();
   * } finally {
   *     BytesWriterPool.release(writer);
   * }
   * }</pre>
   */
  public static class BytesWriterPool {
    /** Default capacity, configurable via system property. */
    private static final int DEFAULT_CAPACITY =
        Integer.getInteger("latte.bytesWriterPoolCapacity", 4096);

    private static final ThreadLocal<BytesWriter> CACHE =
        ThreadLocal.withInitial(() -> new BytesWriter(DEFAULT_CAPACITY));

    /** Tracks nesting depth to avoid reusing the pooled writer in nested calls. */
    private static final ThreadLocal<Integer> DEPTH = ThreadLocal.withInitial(() -> 0);

    /**
     * Acquire a BytesWriter from the pool.
     *
     * <p>If this is a nested call (e.g., encoding a collection element), a new writer is created to
     * avoid corrupting the outer call's data.
     *
     * @return a reset BytesWriter ready for use
     */
    public static BytesWriter acquire() {
      int depth = DEPTH.get();
      DEPTH.set(depth + 1);

      if (depth == 0) {
        // Outermost call - use pooled writer
        BytesWriter writer = CACHE.get();
        writer.reset();
        return writer;
      } else {
        // Nested call - create new writer to avoid corruption
        return new BytesWriter(DEFAULT_CAPACITY);
      }
    }

    /**
     * Acquire a BytesWriter with a specific initial capacity.
     *
     * <p>If the requested capacity exceeds the cached writer's capacity, a new writer is created
     * for this request only.
     *
     * @param minCapacity minimum capacity needed
     * @return a reset BytesWriter ready for use
     */
    public static BytesWriter acquire(int minCapacity) {
      if (minCapacity > DEFAULT_CAPACITY) {
        int depth = DEPTH.get();
        DEPTH.set(depth + 1);
        return new BytesWriter(minCapacity);
      }
      return acquire();
    }

    /**
     * Release a BytesWriter back to the pool.
     *
     * <p>Decrements the nesting depth counter. The actual writer stays in ThreadLocal cache.
     *
     * @param writer the writer to release
     */
    public static void release(BytesWriter writer) {
      int depth = DEPTH.get();
      if (depth > 0) {
        DEPTH.set(depth - 1);
      }
    }
  }

  /**
   * Thread-local pool of BytesReader instances for reduced allocation.
   *
   * <p>Similar to BytesWriterPool, this provides reusable BytesReader instances to reduce
   * allocation pressure in performance-critical code paths.
   *
   * <p>Usage:
   *
   * <pre>{@code
   * BytesReader reader = BytesReaderPool.acquire(data);
   * try {
   *     int value = reader.readInt();
   *     // ...
   * } finally {
   *     BytesReaderPool.release(reader);
   * }
   * }</pre>
   */
  public static class BytesReaderPool {
    private static final ThreadLocal<BytesReader> CACHE =
        ThreadLocal.withInitial(() -> new BytesReader(new byte[0]));

    /** Tracks nesting depth to avoid reusing the pooled reader in nested calls. */
    private static final ThreadLocal<Integer> DEPTH = ThreadLocal.withInitial(() -> 0);

    /**
     * Acquire a BytesReader from the pool, reset to read the given data.
     *
     * @param data the data to read
     * @return a BytesReader ready to read from the data
     */
    public static BytesReader acquire(byte[] data) {
      int depth = DEPTH.get();
      DEPTH.set(depth + 1);

      if (depth == 0) {
        // Outermost call - use pooled reader
        BytesReader reader = CACHE.get();
        reader.reset(data);
        return reader;
      } else {
        // Nested call - create new reader to avoid corruption
        return new BytesReader(data);
      }
    }

    /**
     * Release a BytesReader back to the pool.
     *
     * @param reader the reader to release
     */
    public static void release(BytesReader reader) {
      int depth = DEPTH.get();
      if (depth > 0) {
        DEPTH.set(depth - 1);
      }
    }
  }

  /** Builds protocol frames for responses. Uses pooled writers for reduced allocation. */
  public static class FrameBuilder {

    /**
     * Build a complete protocol frame with header and body.
     *
     * @param stream the stream identifier for request/response correlation
     * @param opcode the operation code for this response
     * @param body the body bytes
     * @return the complete frame bytes including header
     */
    public static byte[] buildFrame(short stream, byte opcode, byte[] body) {
      byte[] frame = new byte[HEADER_LENGTH + body.length];
      frame[0] = VERSION_RESPONSE;
      frame[1] = 0; // flags
      frame[2] = (byte) (stream >> 8);
      frame[3] = (byte) stream;
      frame[4] = opcode;
      frame[5] = (byte) (body.length >> 24);
      frame[6] = (byte) (body.length >> 16);
      frame[7] = (byte) (body.length >> 8);
      frame[8] = (byte) body.length;
      System.arraycopy(body, 0, frame, HEADER_LENGTH, body.length);
      return frame;
    }

    /**
     * Build a SESSION_CREATED response frame.
     *
     * @param stream the stream identifier
     * @param sessionId the newly created session ID
     * @return the complete frame bytes
     */
    public static byte[] buildSessionCreatedFrame(short stream, long sessionId) {
      BytesWriter writer = BytesWriterPool.acquire();
      try {
        writer.writeLong(sessionId);
        return buildFrame(stream, OPCODE_SESSION_CREATED, writer.toByteArray());
      } finally {
        BytesWriterPool.release(writer);
      }
    }

    /**
     * Build a VOID result frame (for statements that don't return rows).
     *
     * @param stream the stream identifier
     * @param latencyNs the execution latency in nanoseconds
     * @return the complete frame bytes
     */
    public static byte[] buildVoidFrame(short stream, long latencyNs) {
      BytesWriter writer = BytesWriterPool.acquire();
      try {
        writer.writeInt(RESULT_KIND_VOID);
        writer.writeLong(latencyNs);
        return buildFrame(stream, OPCODE_RESULT, writer.toByteArray());
      } finally {
        BytesWriterPool.release(writer);
      }
    }

    /**
     * Build a PREPARED result frame for a successfully prepared statement.
     *
     * @param stream the stream identifier
     * @param statementKey the key identifying the prepared statement
     * @return the complete frame bytes
     */
    public static byte[] buildPreparedFrame(short stream, String statementKey) {
      BytesWriter writer = BytesWriterPool.acquire();
      try {
        writer.writeInt(RESULT_KIND_PREPARED);
        writer.writeString(statementKey);
        // ID bytes (from key)
        byte[] idBytes = statementKey.getBytes(StandardCharsets.UTF_8);
        writer.writeUShort(idBytes.length);
        writer.writeBytes(idBytes);
        // Metadata
        writer.writeInt(0); // bind_metadata_flags
        writer.writeInt(0); // bind_columns_count
        writer.writeInt(0); // result_metadata_flags
        writer.writeInt(0); // result_columns_count
        return buildFrame(stream, OPCODE_RESULT, writer.toByteArray());
      } finally {
        BytesWriterPool.release(writer);
      }
    }

    /**
     * Build an ERROR response frame.
     *
     * @param stream the stream identifier
     * @param errorCode the error code (see ERROR_CODE_* constants)
     * @param message the error message
     * @return the complete frame bytes
     */
    public static byte[] buildErrorFrame(short stream, int errorCode, String message) {
      BytesWriter writer = BytesWriterPool.acquire();
      try {
        writer.writeInt(errorCode);
        writer.writeString(message);
        return buildFrame(stream, OPCODE_ERROR, writer.toByteArray());
      } finally {
        BytesWriterPool.release(writer);
      }
    }
  }

  public static class ProtocolException extends RuntimeException {
    public ProtocolException(String message) {
      super(message);
    }

    public ProtocolException(String message, Throwable cause) {
      super(message, cause);
    }
  }

  /**
   * Helper for writing binary data using Netty's pooled ByteBuf.
   *
   * <p>This writer uses Netty's pooled allocator for efficient memory management in high-throughput
   * scenarios. The caller is responsible for releasing the ByteBuf when done.
   *
   * <p>Usage example:
   *
   * <pre>{@code
   * PooledBytesWriter writer = new PooledBytesWriter(256);
   * try {
   *   writer.writeInt(100);
   *   writer.writeString("hello");
   *   ByteBuf buf = writer.buffer();
   *   // use the buffer...
   * } finally {
   *   writer.release();
   * }
   * }</pre>
   */
  public static class PooledBytesWriter implements AutoCloseable {
    private static final ByteBufAllocator ALLOCATOR = PooledByteBufAllocator.DEFAULT;

    /** Whether to prefer direct buffers (off-heap) for I/O efficiency. */
    private static final boolean USE_DIRECT_BUFFERS =
        Boolean.parseBoolean(System.getProperty("latte.directBuffers", "true"));

    private final ByteBuf buffer;

    /** Creates a pooled writer with default initial capacity (256 bytes). */
    public PooledBytesWriter() {
      this(256);
    }

    /**
     * Creates a pooled writer with specified initial capacity.
     *
     * <p>Uses direct (off-heap) buffers by default for better I/O performance. This can be disabled
     * by setting the system property {@code latte.directBuffers=false}.
     *
     * @param initialCapacity the initial buffer capacity in bytes
     */
    public PooledBytesWriter(int initialCapacity) {
      this.buffer =
          USE_DIRECT_BUFFERS
              ? ALLOCATOR.directBuffer(initialCapacity)
              : ALLOCATOR.heapBuffer(initialCapacity);
    }

    /** Returns the current write position. */
    public int writerIndex() {
      return buffer.writerIndex();
    }

    /** Returns the number of readable bytes. */
    public int readableBytes() {
      return buffer.readableBytes();
    }

    public void writeByte(int value) {
      buffer.writeByte(value);
    }

    public void writeShort(short value) {
      buffer.writeShort(value);
    }

    public void writeUShort(int value) {
      buffer.writeShort(value);
    }

    public void writeInt(int value) {
      buffer.writeInt(value);
    }

    public void writeLong(long value) {
      buffer.writeLong(value);
    }

    public void writeBytes(byte[] bytes) {
      buffer.writeBytes(bytes);
    }

    public void writeBytes(byte[] bytes, int offset, int length) {
      buffer.writeBytes(bytes, offset, length);
    }

    public void writeString(String value) {
      byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
      if (bytes.length > 65535) {
        throw new ProtocolException("String too long: " + bytes.length + " bytes");
      }
      writeUShort(bytes.length);
      writeBytes(bytes);
    }

    public void writeLongString(String value) {
      byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
      writeInt(bytes.length);
      writeBytes(bytes);
    }

    public void writeBytesNullable(byte[] bytes) {
      if (bytes == null) {
        writeInt(-1);
      } else {
        writeInt(bytes.length);
        writeBytes(bytes);
      }
    }

    /**
     * Returns the underlying ByteBuf.
     *
     * <p>Note: The buffer should be released after use via {@link #release()} or {@link #close()}.
     *
     * @return the ByteBuf
     */
    public ByteBuf buffer() {
      return buffer;
    }

    /**
     * Copies the buffer contents to a byte array.
     *
     * <p>This creates a copy - use {@link #buffer()} for zero-copy access.
     *
     * @return a new byte array containing the written data
     */
    public byte[] toByteArray() {
      byte[] bytes = new byte[buffer.readableBytes()];
      buffer.getBytes(buffer.readerIndex(), bytes);
      return bytes;
    }

    /** Releases the underlying buffer. */
    public void release() {
      buffer.release();
    }

    @Override
    public void close() {
      release();
    }
  }

  /**
   * Builds protocol frames directly to ByteBuf for zero-copy response handling.
   *
   * <p>Use these methods when writing responses in Netty handlers to avoid intermediate byte array
   * allocations.
   */
  public static class PooledFrameBuilder {

    /**
     * Build a complete protocol frame with header and body to a pooled ByteBuf.
     *
     * @param stream the stream identifier
     * @param opcode the operation code
     * @param body the body bytes
     * @return a pooled ByteBuf containing the complete frame (caller must release)
     */
    public static ByteBuf buildFrame(short stream, byte opcode, byte[] body) {
      ByteBuf buf = PooledByteBufAllocator.DEFAULT.buffer(HEADER_LENGTH + body.length);
      buf.writeByte(VERSION_RESPONSE);
      buf.writeByte(0); // flags
      buf.writeShort(stream);
      buf.writeByte(opcode);
      buf.writeInt(body.length);
      buf.writeBytes(body);
      return buf;
    }

    /**
     * Build a complete protocol frame from a PooledBytesWriter.
     *
     * @param stream the stream identifier
     * @param opcode the operation code
     * @param bodyWriter the writer containing the body data
     * @return a pooled ByteBuf containing the complete frame (caller must release)
     */
    public static ByteBuf buildFrame(short stream, byte opcode, PooledBytesWriter bodyWriter) {
      int bodyLength = bodyWriter.readableBytes();
      ByteBuf buf = PooledByteBufAllocator.DEFAULT.buffer(HEADER_LENGTH + bodyLength);
      buf.writeByte(VERSION_RESPONSE);
      buf.writeByte(0); // flags
      buf.writeShort(stream);
      buf.writeByte(opcode);
      buf.writeInt(bodyLength);
      buf.writeBytes(bodyWriter.buffer());
      return buf;
    }

    /**
     * Build a VOID result frame to a pooled ByteBuf.
     *
     * @param stream the stream identifier
     * @param latencyNs the execution latency in nanoseconds
     * @return a pooled ByteBuf (caller must release)
     */
    public static ByteBuf buildVoidFrame(short stream, long latencyNs) {
      ByteBuf buf = PooledByteBufAllocator.DEFAULT.buffer(HEADER_LENGTH + 12);
      buf.writeByte(VERSION_RESPONSE);
      buf.writeByte(0);
      buf.writeShort(stream);
      buf.writeByte(OPCODE_RESULT);
      buf.writeInt(12); // body length
      buf.writeInt(RESULT_KIND_VOID);
      buf.writeLong(latencyNs);
      return buf;
    }

    /**
     * Build an ERROR response frame to a pooled ByteBuf.
     *
     * @param stream the stream identifier
     * @param errorCode the error code
     * @param message the error message
     * @return a pooled ByteBuf (caller must release)
     */
    public static ByteBuf buildErrorFrame(short stream, int errorCode, String message) {
      byte[] msgBytes = message.getBytes(StandardCharsets.UTF_8);
      int bodyLength = 4 + 2 + msgBytes.length; // error code + string length + string
      ByteBuf buf = PooledByteBufAllocator.DEFAULT.buffer(HEADER_LENGTH + bodyLength);
      buf.writeByte(VERSION_RESPONSE);
      buf.writeByte(0);
      buf.writeShort(stream);
      buf.writeByte(OPCODE_ERROR);
      buf.writeInt(bodyLength);
      buf.writeInt(errorCode);
      buf.writeShort(msgBytes.length);
      buf.writeBytes(msgBytes);
      return buf;
    }
  }
}

package com.scylladb.latte;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

@DisplayName("Protocol")
class ProtocolTest {

  @Nested
  @DisplayName("Constants")
  class Constants {

    @Test
    @DisplayName("should have correct protocol version")
    void protocolVersion() {
      assertThat(Protocol.IPC_PROTOCOL_VERSION).isEqualTo(1);
    }

    @Test
    @DisplayName("should have correct header length")
    void headerLength() {
      assertThat(Protocol.HEADER_LENGTH).isEqualTo(9);
    }

    @Test
    @DisplayName("should have correct max body length")
    void maxBodyLength() {
      assertThat(Protocol.MAX_BODY_LENGTH).isEqualTo(16 * 1024 * 1024);
    }

    @Test
    @DisplayName("should have correct version bytes")
    void versionBytes() {
      assertThat(Protocol.VERSION_REQUEST).isEqualTo((byte) 0x04);
      assertThat(Protocol.VERSION_RESPONSE).isEqualTo((byte) 0x84);
    }

    @Test
    @DisplayName("should have correct opcodes")
    void opcodes() {
      assertThat(Protocol.OPCODE_ERROR).isEqualTo((byte) 0x00);
      assertThat(Protocol.OPCODE_QUERY).isEqualTo((byte) 0x07);
      assertThat(Protocol.OPCODE_RESULT).isEqualTo((byte) 0x08);
      assertThat(Protocol.OPCODE_PREPARE).isEqualTo((byte) 0x09);
      assertThat(Protocol.OPCODE_EXECUTE).isEqualTo((byte) 0x0A);
      assertThat(Protocol.OPCODE_BATCH).isEqualTo((byte) 0x0D);
      assertThat(Protocol.OPCODE_CREATE_SESSION).isEqualTo((byte) 0x21);
      assertThat(Protocol.OPCODE_SESSION_CREATED).isEqualTo((byte) 0x22);
    }

    @Test
    @DisplayName("should have correct result kinds")
    void resultKinds() {
      assertThat(Protocol.RESULT_KIND_VOID).isEqualTo(0x0001);
      assertThat(Protocol.RESULT_KIND_ROWS).isEqualTo(0x0002);
      assertThat(Protocol.RESULT_KIND_SET_KEYSPACE).isEqualTo(0x0003);
      assertThat(Protocol.RESULT_KIND_PREPARED).isEqualTo(0x0004);
      assertThat(Protocol.RESULT_KIND_SCHEMA_CHANGE).isEqualTo(0x0005);
    }

    @Test
    @DisplayName("should have correct error codes")
    void errorCodes() {
      assertThat(Protocol.ERROR_CODE_SERVER).isEqualTo(0x0000);
      assertThat(Protocol.ERROR_CODE_PROTOCOL).isEqualTo(0x000A);
      assertThat(Protocol.ERROR_CODE_OVERLOADED).isEqualTo(0x1001);
      assertThat(Protocol.ERROR_CODE_UNPREPARED).isEqualTo(0x2500);
    }

    @Test
    @DisplayName("should have correct CQL type codes")
    void typesCodes() {
      assertThat(Protocol.TYPE_ASCII).isEqualTo((short) 0x0001);
      assertThat(Protocol.TYPE_BIGINT).isEqualTo((short) 0x0002);
      assertThat(Protocol.TYPE_BLOB).isEqualTo((short) 0x0003);
      assertThat(Protocol.TYPE_BOOLEAN).isEqualTo((short) 0x0004);
      assertThat(Protocol.TYPE_COUNTER).isEqualTo((short) 0x0005);
      assertThat(Protocol.TYPE_DECIMAL).isEqualTo((short) 0x0006);
      assertThat(Protocol.TYPE_DOUBLE).isEqualTo((short) 0x0007);
      assertThat(Protocol.TYPE_FLOAT).isEqualTo((short) 0x0008);
      assertThat(Protocol.TYPE_INT).isEqualTo((short) 0x0009);
      assertThat(Protocol.TYPE_TIMESTAMP).isEqualTo((short) 0x000B);
      assertThat(Protocol.TYPE_UUID).isEqualTo((short) 0x000C);
      assertThat(Protocol.TYPE_TEXT).isEqualTo((short) 0x000D);
      assertThat(Protocol.TYPE_VARINT).isEqualTo((short) 0x000E);
      assertThat(Protocol.TYPE_TIMEUUID).isEqualTo((short) 0x000F);
      assertThat(Protocol.TYPE_INET).isEqualTo((short) 0x0010);
      assertThat(Protocol.TYPE_DATE).isEqualTo((short) 0x0011);
      assertThat(Protocol.TYPE_TIME).isEqualTo((short) 0x0012);
      assertThat(Protocol.TYPE_SMALLINT).isEqualTo((short) 0x0013);
      assertThat(Protocol.TYPE_TINYINT).isEqualTo((short) 0x0014);
      assertThat(Protocol.TYPE_DURATION).isEqualTo((short) 0x0015);
      assertThat(Protocol.TYPE_LIST).isEqualTo((short) 0x0020);
      assertThat(Protocol.TYPE_MAP).isEqualTo((short) 0x0021);
      assertThat(Protocol.TYPE_SET).isEqualTo((short) 0x0022);
      assertThat(Protocol.TYPE_VECTOR).isEqualTo((short) 0x0030);
      assertThat(Protocol.TYPE_TUPLE).isEqualTo((short) 0x0031);
      assertThat(Protocol.TYPE_UDT).isEqualTo((short) 0x0040);
    }

    @Test
    @DisplayName("should have correct consistency levels")
    void consistencyLevels() {
      assertThat(Protocol.CONSISTENCY_ANY).isEqualTo((short) 0x0000);
      assertThat(Protocol.CONSISTENCY_ONE).isEqualTo((short) 0x0001);
      assertThat(Protocol.CONSISTENCY_TWO).isEqualTo((short) 0x0002);
      assertThat(Protocol.CONSISTENCY_THREE).isEqualTo((short) 0x0003);
      assertThat(Protocol.CONSISTENCY_QUORUM).isEqualTo((short) 0x0004);
      assertThat(Protocol.CONSISTENCY_ALL).isEqualTo((short) 0x0005);
      assertThat(Protocol.CONSISTENCY_LOCAL_QUORUM).isEqualTo((short) 0x0006);
      assertThat(Protocol.CONSISTENCY_EACH_QUORUM).isEqualTo((short) 0x0007);
      assertThat(Protocol.CONSISTENCY_LOCAL_ONE).isEqualTo((short) 0x000A);
    }
  }

  @Nested
  @DisplayName("BytesReader")
  class BytesReaderTests {

    @Test
    @DisplayName("should read byte")
    void readByte() {
      byte[] data = {0x42};
      Protocol.BytesReader reader = new Protocol.BytesReader(data);
      assertThat(reader.readByte()).isEqualTo((byte) 0x42);
    }

    @Test
    @DisplayName("should read short in big-endian")
    void readShort() {
      byte[] data = {0x01, 0x02}; // 258 in big-endian
      Protocol.BytesReader reader = new Protocol.BytesReader(data);
      assertThat(reader.readShort()).isEqualTo((short) 258);
    }

    @Test
    @DisplayName("should read unsigned short")
    void readUShort() {
      byte[] data = {(byte) 0xFF, (byte) 0xFF}; // 65535 as unsigned
      Protocol.BytesReader reader = new Protocol.BytesReader(data);
      assertThat(reader.readUShort()).isEqualTo(65535);
    }

    @Test
    @DisplayName("should read int in big-endian")
    void readInt() {
      byte[] data = {0x00, 0x01, 0x00, 0x00}; // 65536 in big-endian
      Protocol.BytesReader reader = new Protocol.BytesReader(data);
      assertThat(reader.readInt()).isEqualTo(65536);
    }

    @Test
    @DisplayName("should read negative int")
    void readNegativeInt() {
      byte[] data = {(byte) 0xFF, (byte) 0xFF, (byte) 0xFF, (byte) 0xFF}; // -1
      Protocol.BytesReader reader = new Protocol.BytesReader(data);
      assertThat(reader.readInt()).isEqualTo(-1);
    }

    @Test
    @DisplayName("should read long in big-endian")
    void readLong() {
      byte[] data = {0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x64}; // 100
      Protocol.BytesReader reader = new Protocol.BytesReader(data);
      assertThat(reader.readLong()).isEqualTo(100L);
    }

    @Test
    @DisplayName("should read bytes")
    void readBytes() {
      byte[] data = {0x01, 0x02, 0x03, 0x04, 0x05};
      Protocol.BytesReader reader = new Protocol.BytesReader(data);
      assertThat(reader.readBytes(3)).isEqualTo(new byte[] {0x01, 0x02, 0x03});
      assertThat(reader.readBytes(2)).isEqualTo(new byte[] {0x04, 0x05});
    }

    @Test
    @DisplayName("should read string")
    void readString() {
      // String format: 2-byte length prefix + UTF-8 bytes
      byte[] data = {0x00, 0x05, 'H', 'e', 'l', 'l', 'o'};
      Protocol.BytesReader reader = new Protocol.BytesReader(data);
      assertThat(reader.readString()).isEqualTo("Hello");
    }

    @Test
    @DisplayName("should read empty string")
    void readEmptyString() {
      byte[] data = {0x00, 0x00};
      Protocol.BytesReader reader = new Protocol.BytesReader(data);
      assertThat(reader.readString()).isEqualTo("");
    }

    @Test
    @DisplayName("should read long string")
    void readLongString() {
      // Long string format: 4-byte length prefix + UTF-8 bytes
      byte[] data = {0x00, 0x00, 0x00, 0x05, 'H', 'e', 'l', 'l', 'o'};
      Protocol.BytesReader reader = new Protocol.BytesReader(data);
      assertThat(reader.readLongString()).isEqualTo("Hello");
    }

    @Test
    @DisplayName("should throw on negative long string length")
    void readLongStringNegativeLength() {
      byte[] data = {(byte) 0xFF, (byte) 0xFF, (byte) 0xFF, (byte) 0xFF}; // -1
      Protocol.BytesReader reader = new Protocol.BytesReader(data);
      assertThatThrownBy(reader::readLongString)
          .isInstanceOf(Protocol.ProtocolException.class)
          .hasMessageContaining("Invalid negative string length");
    }

    @Test
    @DisplayName("should read nullable bytes with value")
    void readBytesNullableWithValue() {
      byte[] data = {0x00, 0x00, 0x00, 0x03, 0x01, 0x02, 0x03};
      Protocol.BytesReader reader = new Protocol.BytesReader(data);
      assertThat(reader.readBytesNullable()).isEqualTo(new byte[] {0x01, 0x02, 0x03});
    }

    @Test
    @DisplayName("should read nullable bytes as null")
    void readBytesNullableAsNull() {
      byte[] data = {(byte) 0xFF, (byte) 0xFF, (byte) 0xFF, (byte) 0xFF}; // -1
      Protocol.BytesReader reader = new Protocol.BytesReader(data);
      assertThat(reader.readBytesNullable()).isNull();
    }

    @Test
    @DisplayName("should read string map")
    void readStringMap() {
      Protocol.BytesWriter writer = new Protocol.BytesWriter();
      writer.writeUShort(2); // 2 entries
      writer.writeString("key1");
      writer.writeString("value1");
      writer.writeString("key2");
      writer.writeString("value2");

      Protocol.BytesReader reader = new Protocol.BytesReader(writer.toByteArray());
      Map<String, String> map = reader.readStringMap();

      assertThat(map).hasSize(2);
      assertThat(map.get("key1")).isEqualTo("value1");
      assertThat(map.get("key2")).isEqualTo("value2");
    }

    @Test
    @DisplayName("should track position")
    void position() {
      byte[] data = {0x01, 0x02, 0x03, 0x04};
      Protocol.BytesReader reader = new Protocol.BytesReader(data);
      assertThat(reader.position()).isEqualTo(0);
      reader.readByte();
      assertThat(reader.position()).isEqualTo(1);
      reader.readShort();
      assertThat(reader.position()).isEqualTo(3);
    }

    @Test
    @DisplayName("should track remaining bytes")
    void remaining() {
      byte[] data = {0x01, 0x02, 0x03, 0x04};
      Protocol.BytesReader reader = new Protocol.BytesReader(data);
      assertThat(reader.remaining()).isEqualTo(4);
      reader.readByte();
      assertThat(reader.remaining()).isEqualTo(3);
    }

    @Test
    @DisplayName("should check hasRemaining")
    void hasRemaining() {
      byte[] data = {0x01};
      Protocol.BytesReader reader = new Protocol.BytesReader(data);
      assertThat(reader.hasRemaining()).isTrue();
      reader.readByte();
      assertThat(reader.hasRemaining()).isFalse();
    }

    @Test
    @DisplayName("should skip bytes")
    void skip() {
      byte[] data = {0x01, 0x02, 0x03, 0x04, 0x05};
      Protocol.BytesReader reader = new Protocol.BytesReader(data);
      reader.skip(3);
      assertThat(reader.position()).isEqualTo(3);
      assertThat(reader.readByte()).isEqualTo((byte) 0x04);
    }
  }

  @Nested
  @DisplayName("BytesWriter")
  class BytesWriterTests {

    @Test
    @DisplayName("should write byte")
    void writeByte() {
      Protocol.BytesWriter writer = new Protocol.BytesWriter();
      writer.writeByte(0x42);
      assertThat(writer.toByteArray()).isEqualTo(new byte[] {0x42});
    }

    @Test
    @DisplayName("should write short in big-endian")
    void writeShort() {
      Protocol.BytesWriter writer = new Protocol.BytesWriter();
      writer.writeShort((short) 258);
      assertThat(writer.toByteArray()).isEqualTo(new byte[] {0x01, 0x02});
    }

    @Test
    @DisplayName("should write unsigned short")
    void writeUShort() {
      Protocol.BytesWriter writer = new Protocol.BytesWriter();
      writer.writeUShort(65535);
      assertThat(writer.toByteArray()).isEqualTo(new byte[] {(byte) 0xFF, (byte) 0xFF});
    }

    @Test
    @DisplayName("should write int in big-endian")
    void writeInt() {
      Protocol.BytesWriter writer = new Protocol.BytesWriter();
      writer.writeInt(65536);
      assertThat(writer.toByteArray()).isEqualTo(new byte[] {0x00, 0x01, 0x00, 0x00});
    }

    @Test
    @DisplayName("should write long in big-endian")
    void writeLong() {
      Protocol.BytesWriter writer = new Protocol.BytesWriter();
      writer.writeLong(100L);
      assertThat(writer.toByteArray())
          .isEqualTo(new byte[] {0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x64});
    }

    @Test
    @DisplayName("should write bytes")
    void writeBytes() {
      Protocol.BytesWriter writer = new Protocol.BytesWriter();
      writer.writeBytes(new byte[] {0x01, 0x02, 0x03});
      assertThat(writer.toByteArray()).isEqualTo(new byte[] {0x01, 0x02, 0x03});
    }

    @Test
    @DisplayName("should write bytes with offset and length")
    void writeBytesWithOffset() {
      Protocol.BytesWriter writer = new Protocol.BytesWriter();
      writer.writeBytes(new byte[] {0x00, 0x01, 0x02, 0x03, 0x04}, 1, 3);
      assertThat(writer.toByteArray()).isEqualTo(new byte[] {0x01, 0x02, 0x03});
    }

    @Test
    @DisplayName("should write string")
    void writeString() {
      Protocol.BytesWriter writer = new Protocol.BytesWriter();
      writer.writeString("Hello");
      assertThat(writer.toByteArray()).isEqualTo(new byte[] {0x00, 0x05, 'H', 'e', 'l', 'l', 'o'});
    }

    @Test
    @DisplayName("should write empty string")
    void writeEmptyString() {
      Protocol.BytesWriter writer = new Protocol.BytesWriter();
      writer.writeString("");
      assertThat(writer.toByteArray()).isEqualTo(new byte[] {0x00, 0x00});
    }

    @Test
    @DisplayName("should throw on string too long")
    void writeStringTooLong() {
      Protocol.BytesWriter writer = new Protocol.BytesWriter();
      String longString = "x".repeat(65536);
      assertThatThrownBy(() -> writer.writeString(longString))
          .isInstanceOf(Protocol.ProtocolException.class)
          .hasMessageContaining("String too long");
    }

    @Test
    @DisplayName("should write long string")
    void writeLongString() {
      Protocol.BytesWriter writer = new Protocol.BytesWriter();
      writer.writeLongString("Hello");
      assertThat(writer.toByteArray())
          .isEqualTo(new byte[] {0x00, 0x00, 0x00, 0x05, 'H', 'e', 'l', 'l', 'o'});
    }

    @Test
    @DisplayName("should write nullable bytes with value")
    void writeBytesNullableWithValue() {
      Protocol.BytesWriter writer = new Protocol.BytesWriter();
      writer.writeBytesNullable(new byte[] {0x01, 0x02, 0x03});
      assertThat(writer.toByteArray())
          .isEqualTo(new byte[] {0x00, 0x00, 0x00, 0x03, 0x01, 0x02, 0x03});
    }

    @Test
    @DisplayName("should write nullable bytes as null")
    void writeBytesNullableAsNull() {
      Protocol.BytesWriter writer = new Protocol.BytesWriter();
      writer.writeBytesNullable(null);
      assertThat(writer.toByteArray())
          .isEqualTo(new byte[] {(byte) 0xFF, (byte) 0xFF, (byte) 0xFF, (byte) 0xFF});
    }

    @Test
    @DisplayName("should track size")
    void size() {
      Protocol.BytesWriter writer = new Protocol.BytesWriter();
      assertThat(writer.size()).isEqualTo(0);
      writer.writeByte(0x01);
      assertThat(writer.size()).isEqualTo(1);
      writer.writeInt(100);
      assertThat(writer.size()).isEqualTo(5);
    }

    @Test
    @DisplayName("should handle initial capacity")
    void initialCapacity() {
      Protocol.BytesWriter writer = new Protocol.BytesWriter(1024);
      writer.writeString("test");
      assertThat(writer.size()).isEqualTo(6); // 2 + 4
    }

    @Test
    @DisplayName("should reset for reuse")
    void reset() {
      Protocol.BytesWriter writer = new Protocol.BytesWriter();
      writer.writeInt(100);
      writer.writeString("test");
      assertThat(writer.size()).isGreaterThan(0);

      writer.reset();
      assertThat(writer.size()).isEqualTo(0);

      // Verify can write again after reset
      writer.writeInt(200);
      assertThat(writer.size()).isEqualTo(4);

      byte[] result = writer.toByteArray();
      Protocol.BytesReader reader = new Protocol.BytesReader(result);
      assertThat(reader.readInt()).isEqualTo(200);
    }

    @Test
    @DisplayName("should reset multiple times")
    void resetMultiple() {
      Protocol.BytesWriter writer = new Protocol.BytesWriter();

      for (int i = 0; i < 10; i++) {
        writer.writeInt(i);
        assertThat(writer.size()).isEqualTo(4);
        writer.reset();
        assertThat(writer.size()).isEqualTo(0);
      }

      writer.writeString("final");
      assertThat(writer.size()).isEqualTo(7); // 2 + 5
    }
  }

  @Nested
  @DisplayName("BytesWriterPool")
  class BytesWriterPoolTests {

    @Test
    @DisplayName("should acquire writer ready for use")
    void acquire() {
      Protocol.BytesWriter writer = Protocol.BytesWriterPool.acquire();
      assertThat(writer).isNotNull();
      assertThat(writer.size()).isEqualTo(0);

      writer.writeInt(42);
      assertThat(writer.size()).isEqualTo(4);

      Protocol.BytesWriterPool.release(writer);
    }

    @Test
    @DisplayName("should reset on acquire")
    void resetOnAcquire() {
      Protocol.BytesWriter writer1 = Protocol.BytesWriterPool.acquire();
      writer1.writeInt(100);
      writer1.writeString("data");
      int firstSize = writer1.size();
      assertThat(firstSize).isGreaterThan(0);
      Protocol.BytesWriterPool.release(writer1);

      // Same thread, should get same (or equivalent reset) writer
      Protocol.BytesWriter writer2 = Protocol.BytesWriterPool.acquire();
      assertThat(writer2.size()).isEqualTo(0);
      Protocol.BytesWriterPool.release(writer2);
    }

    @Test
    @DisplayName("should acquire with minimum capacity")
    void acquireWithCapacity() {
      Protocol.BytesWriter writer = Protocol.BytesWriterPool.acquire(512);
      assertThat(writer).isNotNull();
      assertThat(writer.size()).isEqualTo(0);

      // Write data and verify it works
      for (int i = 0; i < 100; i++) {
        writer.writeInt(i);
      }
      assertThat(writer.size()).isEqualTo(400);
    }

    @Test
    @DisplayName("should create new writer for large capacity request")
    void acquireLargeCapacity() {
      // Request larger than default capacity
      Protocol.BytesWriter writer = Protocol.BytesWriterPool.acquire(2048);
      assertThat(writer).isNotNull();
      assertThat(writer.size()).isEqualTo(0);

      // Write large amount of data
      for (int i = 0; i < 500; i++) {
        writer.writeInt(i);
      }
      assertThat(writer.size()).isEqualTo(2000);
    }

    @Test
    @DisplayName("should work correctly across multiple acquire/release cycles")
    void multipleAcquireReleaseCycles() {
      for (int cycle = 0; cycle < 100; cycle++) {
        Protocol.BytesWriter writer = Protocol.BytesWriterPool.acquire();
        assertThat(writer.size()).isEqualTo(0);

        writer.writeInt(cycle);
        writer.writeString("cycle" + cycle);

        byte[] result = writer.toByteArray();
        Protocol.BytesReader reader = new Protocol.BytesReader(result);
        assertThat(reader.readInt()).isEqualTo(cycle);
        assertThat(reader.readString()).isEqualTo("cycle" + cycle);

        Protocol.BytesWriterPool.release(writer);
      }
    }
  }

  @Nested
  @DisplayName("FrameBuilder")
  class FrameBuilderTests {

    @Test
    @DisplayName("should build frame with correct header")
    void buildFrame() {
      byte[] body = {0x01, 0x02, 0x03};
      byte[] frame = Protocol.FrameBuilder.buildFrame((short) 1, Protocol.OPCODE_RESULT, body);

      assertThat(frame.length).isEqualTo(Protocol.HEADER_LENGTH + body.length);
      assertThat(frame[0]).isEqualTo(Protocol.VERSION_RESPONSE);
      assertThat(frame[1]).isEqualTo((byte) 0); // flags
      assertThat(frame[2]).isEqualTo((byte) 0); // stream high byte
      assertThat(frame[3]).isEqualTo((byte) 1); // stream low byte
      assertThat(frame[4]).isEqualTo(Protocol.OPCODE_RESULT);
      // Body length (4 bytes, big-endian)
      assertThat(frame[5]).isEqualTo((byte) 0);
      assertThat(frame[6]).isEqualTo((byte) 0);
      assertThat(frame[7]).isEqualTo((byte) 0);
      assertThat(frame[8]).isEqualTo((byte) 3);
      // Body
      assertThat(frame[9]).isEqualTo((byte) 0x01);
      assertThat(frame[10]).isEqualTo((byte) 0x02);
      assertThat(frame[11]).isEqualTo((byte) 0x03);
    }

    @Test
    @DisplayName("should build session created frame")
    void buildSessionCreatedFrame() {
      byte[] frame = Protocol.FrameBuilder.buildSessionCreatedFrame((short) 5, 12345L);

      assertThat(frame[0]).isEqualTo(Protocol.VERSION_RESPONSE);
      assertThat(frame[4]).isEqualTo(Protocol.OPCODE_SESSION_CREATED);

      // Parse body to verify session ID
      Protocol.BytesReader reader = new Protocol.BytesReader(frame);
      reader.skip(Protocol.HEADER_LENGTH);
      assertThat(reader.readLong()).isEqualTo(12345L);
    }

    @Test
    @DisplayName("should build void frame with latency")
    void buildVoidFrame() {
      long latencyNs = 1_000_000L; // 1ms
      byte[] frame = Protocol.FrameBuilder.buildVoidFrame((short) 3, latencyNs);

      assertThat(frame[0]).isEqualTo(Protocol.VERSION_RESPONSE);
      assertThat(frame[4]).isEqualTo(Protocol.OPCODE_RESULT);

      // Parse body
      Protocol.BytesReader reader = new Protocol.BytesReader(frame);
      reader.skip(Protocol.HEADER_LENGTH);
      assertThat(reader.readInt()).isEqualTo(Protocol.RESULT_KIND_VOID);
      assertThat(reader.readLong()).isEqualTo(latencyNs);
    }

    @Test
    @DisplayName("should build prepared frame")
    void buildPreparedFrame() {
      String statementKey = "stmt1";
      byte[] frame = Protocol.FrameBuilder.buildPreparedFrame((short) 2, statementKey);

      assertThat(frame[0]).isEqualTo(Protocol.VERSION_RESPONSE);
      assertThat(frame[4]).isEqualTo(Protocol.OPCODE_RESULT);

      // Parse body
      Protocol.BytesReader reader = new Protocol.BytesReader(frame);
      reader.skip(Protocol.HEADER_LENGTH);
      assertThat(reader.readInt()).isEqualTo(Protocol.RESULT_KIND_PREPARED);
      assertThat(reader.readString()).isEqualTo(statementKey);
    }

    @Test
    @DisplayName("should build error frame")
    void buildErrorFrame() {
      String errorMessage = "Something went wrong";
      byte[] frame =
          Protocol.FrameBuilder.buildErrorFrame(
              (short) 4, Protocol.ERROR_CODE_SERVER, errorMessage);

      assertThat(frame[0]).isEqualTo(Protocol.VERSION_RESPONSE);
      assertThat(frame[4]).isEqualTo(Protocol.OPCODE_ERROR);

      // Parse body
      Protocol.BytesReader reader = new Protocol.BytesReader(frame);
      reader.skip(Protocol.HEADER_LENGTH);
      assertThat(reader.readInt()).isEqualTo(Protocol.ERROR_CODE_SERVER);
      assertThat(reader.readString()).isEqualTo(errorMessage);
    }

    @Test
    @DisplayName("should handle different stream IDs")
    void streamIds() {
      byte[] frame1 = Protocol.FrameBuilder.buildVoidFrame((short) 0, 0);
      byte[] frame2 = Protocol.FrameBuilder.buildVoidFrame((short) 255, 0);
      byte[] frame3 = Protocol.FrameBuilder.buildVoidFrame((short) 32767, 0);

      assertThat(frame1[2]).isEqualTo((byte) 0);
      assertThat(frame1[3]).isEqualTo((byte) 0);

      assertThat(frame2[2]).isEqualTo((byte) 0);
      assertThat(frame2[3]).isEqualTo((byte) 255);

      assertThat(frame3[2]).isEqualTo((byte) 0x7F);
      assertThat(frame3[3]).isEqualTo((byte) 0xFF);
    }
  }

  @Nested
  @DisplayName("Frame Record")
  class FrameTests {

    @Test
    @DisplayName("should create frame with all fields")
    void createFrame() {
      byte[] body = {0x01, 0x02};
      Protocol.Frame frame =
          new Protocol.Frame(
              Protocol.VERSION_REQUEST, (byte) 0, (short) 1, Protocol.OPCODE_QUERY, body);

      assertThat(frame.version()).isEqualTo(Protocol.VERSION_REQUEST);
      assertThat(frame.flags()).isEqualTo((byte) 0);
      assertThat(frame.stream()).isEqualTo((short) 1);
      assertThat(frame.opcode()).isEqualTo(Protocol.OPCODE_QUERY);
      assertThat(frame.body()).isEqualTo(body);
    }
  }

  @Nested
  @DisplayName("ProtocolException")
  class ProtocolExceptionTests {

    @Test
    @DisplayName("should create exception with message")
    void createWithMessage() {
      Protocol.ProtocolException ex = new Protocol.ProtocolException("Test error");
      assertThat(ex.getMessage()).isEqualTo("Test error");
    }

    @Test
    @DisplayName("should create exception with message and cause")
    void createWithMessageAndCause() {
      RuntimeException cause = new RuntimeException("Cause");
      Protocol.ProtocolException ex = new Protocol.ProtocolException("Test error", cause);
      assertThat(ex.getMessage()).isEqualTo("Test error");
      assertThat(ex.getCause()).isEqualTo(cause);
    }
  }

  @Nested
  @DisplayName("Roundtrip")
  class RoundtripTests {

    @Test
    @DisplayName("should roundtrip string through reader/writer")
    void roundtripString() {
      String original = "Hello, World! \u4e16\u754c";
      Protocol.BytesWriter writer = new Protocol.BytesWriter();
      writer.writeString(original);

      Protocol.BytesReader reader = new Protocol.BytesReader(writer.toByteArray());
      assertThat(reader.readString()).isEqualTo(original);
    }

    @Test
    @DisplayName("should roundtrip long string through reader/writer")
    void roundtripLongString() {
      String original = "x".repeat(1000);
      Protocol.BytesWriter writer = new Protocol.BytesWriter();
      writer.writeLongString(original);

      Protocol.BytesReader reader = new Protocol.BytesReader(writer.toByteArray());
      assertThat(reader.readLongString()).isEqualTo(original);
    }

    @Test
    @DisplayName("should roundtrip nullable bytes through reader/writer")
    void roundtripNullableBytes() {
      byte[] original = {0x01, 0x02, 0x03, 0x04, 0x05};
      Protocol.BytesWriter writer = new Protocol.BytesWriter();
      writer.writeBytesNullable(original);

      Protocol.BytesReader reader = new Protocol.BytesReader(writer.toByteArray());
      assertThat(reader.readBytesNullable()).isEqualTo(original);
    }

    @Test
    @DisplayName("should roundtrip null through reader/writer")
    void roundtripNull() {
      Protocol.BytesWriter writer = new Protocol.BytesWriter();
      writer.writeBytesNullable(null);

      Protocol.BytesReader reader = new Protocol.BytesReader(writer.toByteArray());
      assertThat(reader.readBytesNullable()).isNull();
    }

    @Test
    @DisplayName("should roundtrip multiple values")
    void roundtripMultipleValues() {
      Protocol.BytesWriter writer = new Protocol.BytesWriter();
      writer.writeByte(0x42);
      writer.writeShort((short) 1000);
      writer.writeInt(100000);
      writer.writeLong(10000000000L);
      writer.writeString("test");

      Protocol.BytesReader reader = new Protocol.BytesReader(writer.toByteArray());
      assertThat(reader.readByte()).isEqualTo((byte) 0x42);
      assertThat(reader.readShort()).isEqualTo((short) 1000);
      assertThat(reader.readInt()).isEqualTo(100000);
      assertThat(reader.readLong()).isEqualTo(10000000000L);
      assertThat(reader.readString()).isEqualTo("test");
    }
  }
}

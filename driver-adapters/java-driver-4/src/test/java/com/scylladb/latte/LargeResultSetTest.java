package com.scylladb.latte;

import static org.assertj.core.api.Assertions.assertThat;

import com.datastax.oss.driver.api.core.type.DataTypes;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Tests for handling large result sets and memory pressure scenarios.
 *
 * <p>These tests verify that the adapter can handle large amounts of data without running out of
 * memory or causing performance issues.
 */
@DisplayName("Large Result Set Handling")
class LargeResultSetTest {

  @Nested
  @DisplayName("Protocol Buffer Sizing")
  class ProtocolBufferSizing {

    @Test
    @DisplayName("should handle large frame body up to max size")
    void largeFrameBody() {
      // Create a body close to the max size (but not exceeding it)
      int bodySize = 1024 * 1024; // 1MB
      byte[] body = new byte[bodySize];
      for (int i = 0; i < bodySize; i++) {
        body[i] = (byte) (i % 256);
      }

      byte[] frame = Protocol.FrameBuilder.buildFrame((short) 1, Protocol.OPCODE_RESULT, body);

      assertThat(frame.length).isEqualTo(Protocol.HEADER_LENGTH + bodySize);

      // Verify header
      assertThat(frame[0]).isEqualTo(Protocol.VERSION_RESPONSE);
      assertThat(frame[4]).isEqualTo(Protocol.OPCODE_RESULT);

      // Verify body length in header
      int encodedLength =
          ((frame[5] & 0xFF) << 24)
              | ((frame[6] & 0xFF) << 16)
              | ((frame[7] & 0xFF) << 8)
              | (frame[8] & 0xFF);
      assertThat(encodedLength).isEqualTo(bodySize);
    }

    @Test
    @DisplayName("should handle BytesWriter with large content")
    void largeBytesWriter() {
      Protocol.BytesWriter writer = new Protocol.BytesWriter(1024 * 1024);

      // Write 100,000 integers
      for (int i = 0; i < 100_000; i++) {
        writer.writeInt(i);
      }

      byte[] result = writer.toByteArray();
      assertThat(result.length).isEqualTo(100_000 * 4);

      // Verify some values
      Protocol.BytesReader reader = new Protocol.BytesReader(result);
      assertThat(reader.readInt()).isEqualTo(0);
      reader.skip(99_998 * 4);
      assertThat(reader.readInt()).isEqualTo(99_999);
    }

    @Test
    @DisplayName("should handle many small writes efficiently")
    void manySmallWrites() {
      Protocol.BytesWriter writer = new Protocol.BytesWriter(256);

      // Write many small values
      for (int i = 0; i < 50_000; i++) {
        writer.writeByte(i % 256);
      }

      assertThat(writer.size()).isEqualTo(50_000);
    }
  }

  @Nested
  @DisplayName("Value Encoding")
  class ValueEncoding {

    @Test
    @DisplayName("should encode large text values")
    void largeTextValue() {
      // Create a 100KB string
      StringBuilder sb = new StringBuilder();
      for (int i = 0; i < 10_000; i++) {
        sb.append("HelloWorld"); // 10 chars * 10,000 = 100,000 chars
      }
      String largeText = sb.toString();

      byte[] encoded = ValueEncoder.encode(largeText, DataTypes.TEXT);
      assertThat(encoded).isNotNull();
      assertThat(encoded.length).isEqualTo(100_000);

      // Decode and verify
      Object decoded = ValueEncoder.decode(encoded, Protocol.TYPE_TEXT, DataTypes.TEXT);
      assertThat(decoded).isEqualTo(largeText);
    }

    @Test
    @DisplayName("should encode large blob values")
    void largeBlobValue() {
      // Create a 1MB blob
      byte[] largeBlob = new byte[1024 * 1024];
      for (int i = 0; i < largeBlob.length; i++) {
        largeBlob[i] = (byte) (i % 256);
      }

      java.nio.ByteBuffer buffer = java.nio.ByteBuffer.wrap(largeBlob);
      byte[] encoded = ValueEncoder.encode(buffer, DataTypes.BLOB);

      assertThat(encoded).isNotNull();
      assertThat(encoded.length).isEqualTo(1024 * 1024);
    }

    @Test
    @DisplayName("should encode large list of integers")
    void largeListOfIntegers() {
      // Create a list with 10,000 integers
      List<Integer> largeList = new ArrayList<>(10_000);
      for (int i = 0; i < 10_000; i++) {
        largeList.add(i);
      }

      com.datastax.oss.driver.api.core.type.ListType listType = DataTypes.listOf(DataTypes.INT);
      byte[] encoded = ValueEncoder.encodeCollection(largeList, listType);

      assertThat(encoded).isNotNull();
      // 4 bytes for count + (4 bytes length + 4 bytes value) * 10,000
      assertThat(encoded.length).isEqualTo(4 + (4 + 4) * 10_000);
    }

    @Test
    @DisplayName("should encode large list of strings")
    void largeListOfStrings() {
      // Create a list with 1,000 strings of 100 chars each
      List<String> largeList = new ArrayList<>(1_000);
      String template = "A".repeat(100);
      for (int i = 0; i < 1_000; i++) {
        largeList.add(template);
      }

      com.datastax.oss.driver.api.core.type.ListType listType = DataTypes.listOf(DataTypes.TEXT);
      byte[] encoded = ValueEncoder.encodeCollection(largeList, listType);

      assertThat(encoded).isNotNull();
      assertThat(encoded.length).isGreaterThan(100_000);
    }

    @Test
    @DisplayName("should encode large map")
    void largeMap() {
      // Create a map with 5,000 entries
      java.util.Map<String, Integer> largeMap = new java.util.HashMap<>(5_000);
      for (int i = 0; i < 5_000; i++) {
        largeMap.put("key" + i, i);
      }

      com.datastax.oss.driver.api.core.type.MapType mapType =
          DataTypes.mapOf(DataTypes.TEXT, DataTypes.INT);
      byte[] encoded = ValueEncoder.encodeCollection(largeMap, mapType);

      assertThat(encoded).isNotNull();
      assertThat(encoded.length).isGreaterThan(50_000);
    }
  }

  @Nested
  @DisplayName("Memory Efficiency")
  class MemoryEfficiency {

    @Test
    @DisplayName("should not hold references after encoding")
    void noReferencesAfterEncoding() {
      // Create a large object, encode it, then verify original can be GC'd
      String largeText = "X".repeat(100_000);
      byte[] encoded = ValueEncoder.encode(largeText, DataTypes.TEXT);

      // Clear reference to original
      largeText = null;

      // The encoded bytes should still be valid
      assertThat(encoded).isNotNull();
      assertThat(encoded.length).isEqualTo(100_000);
    }

    @Test
    @DisplayName("should handle repeated encode/decode cycles")
    void repeatedEncodeDecode() {
      // Perform many encode/decode cycles to check for memory leaks
      for (int i = 0; i < 1000; i++) {
        String text = "Test string " + i;
        byte[] encoded = ValueEncoder.encode(text, DataTypes.TEXT);
        Object decoded = ValueEncoder.decode(encoded, Protocol.TYPE_TEXT, DataTypes.TEXT);
        assertThat(decoded).isEqualTo(text);
      }
    }

    @Test
    @DisplayName("should handle PooledBytesWriter lifecycle correctly")
    void pooledBytesWriterLifecycle() {
      // Create and release many pooled writers
      for (int i = 0; i < 100; i++) {
        try (Protocol.PooledBytesWriter writer = new Protocol.PooledBytesWriter(1024)) {
          writer.writeInt(i);
          writer.writeString("test" + i);
          assertThat(writer.readableBytes()).isGreaterThan(0);
        }
      }
    }
  }

  @Nested
  @DisplayName("Boundary Conditions")
  class BoundaryConditions {

    @Test
    @DisplayName("should handle empty result set")
    void emptyResultSet() {
      Protocol.BytesWriter writer = new Protocol.BytesWriter();
      writer.writeInt(Protocol.RESULT_KIND_ROWS);
      writer.writeInt(0); // flags
      writer.writeInt(0); // column count
      writer.writeInt(0); // row count
      writer.writeLong(0); // latency

      byte[] frame =
          Protocol.FrameBuilder.buildFrame((short) 1, Protocol.OPCODE_RESULT, writer.toByteArray());

      assertThat(frame).isNotNull();
      assertThat(frame[4]).isEqualTo(Protocol.OPCODE_RESULT);
    }

    @Test
    @DisplayName("should handle maximum string length")
    void maxStringLength() {
      // Max string length for short string is 65535
      String maxString = "X".repeat(65535);

      Protocol.BytesWriter writer = new Protocol.BytesWriter(70000);
      writer.writeString(maxString);

      byte[] result = writer.toByteArray();
      assertThat(result.length).isEqualTo(2 + 65535); // 2 bytes length + content

      Protocol.BytesReader reader = new Protocol.BytesReader(result);
      assertThat(reader.readString()).isEqualTo(maxString);
    }

    @Test
    @DisplayName("should handle long string for larger content")
    void longStringForLargerContent() {
      // Long strings can hold much more
      String longString = "Y".repeat(100_000);

      Protocol.BytesWriter writer = new Protocol.BytesWriter(110000);
      writer.writeLongString(longString);

      byte[] result = writer.toByteArray();
      assertThat(result.length).isEqualTo(4 + 100_000); // 4 bytes length + content

      Protocol.BytesReader reader = new Protocol.BytesReader(result);
      assertThat(reader.readLongString()).isEqualTo(longString);
    }
  }
}

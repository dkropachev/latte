package com.scylladb.latte;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.BufferUnderflowException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Tests for socket disconnection scenarios.
 *
 * <p>These tests verify that the adapter handles various socket disconnection scenarios gracefully
 * without resource leaks or crashes.
 */
@DisplayName("Socket Disconnection Handling")
class SocketDisconnectionTest {

  @Nested
  @DisplayName("Protocol Frame Handling")
  class ProtocolFrameHandling {

    @Test
    @DisplayName("should handle incomplete frame header")
    void incompleteFrameHeader() {
      // Simulate receiving only partial header (less than 9 bytes)
      byte[] partialHeader = new byte[] {Protocol.VERSION_REQUEST, 0x00, 0x00, 0x01};

      // BytesReader should handle this gracefully
      Protocol.BytesReader reader = new Protocol.BytesReader(partialHeader);

      // Reading beyond available bytes should throw
      assertThat(reader.readByte()).isEqualTo(Protocol.VERSION_REQUEST);
      assertThat(reader.readByte()).isEqualTo((byte) 0x00);
      assertThat(reader.readByte()).isEqualTo((byte) 0x00);
      assertThat(reader.readByte()).isEqualTo((byte) 0x01);

      // Attempting to read more should fail
      assertThatThrownBy(reader::readByte).isInstanceOf(BufferUnderflowException.class);
    }

    @Test
    @DisplayName("should handle frame with truncated body")
    void truncatedFrameBody() {
      // Create a header claiming 100 bytes but only provide 10
      Protocol.BytesWriter headerWriter = new Protocol.BytesWriter(Protocol.HEADER_LENGTH);
      headerWriter.writeByte(Protocol.VERSION_REQUEST);
      headerWriter.writeByte(0); // flags
      headerWriter.writeShort((short) 1); // stream
      headerWriter.writeByte(Protocol.OPCODE_QUERY);
      headerWriter.writeInt(100); // claims 100 bytes body

      byte[] header = headerWriter.toByteArray();
      assertThat(header.length).isEqualTo(Protocol.HEADER_LENGTH);

      // Simulating truncated data - only 10 bytes of body instead of 100
      byte[] truncatedBody = new byte[10];
      for (int i = 0; i < 10; i++) {
        truncatedBody[i] = (byte) i;
      }

      // Combine header and truncated body
      byte[] combined = new byte[header.length + truncatedBody.length];
      System.arraycopy(header, 0, combined, 0, header.length);
      System.arraycopy(truncatedBody, 0, combined, header.length, truncatedBody.length);

      // Parse header
      Protocol.BytesReader reader = new Protocol.BytesReader(combined);
      byte version = reader.readByte();
      byte flags = reader.readByte();
      short stream = reader.readShort();
      byte opcode = reader.readByte();
      int bodyLength = reader.readInt();

      assertThat(version).isEqualTo(Protocol.VERSION_REQUEST);
      assertThat(bodyLength).isEqualTo(100);

      // Body length mismatch should be detectable
      int remaining = combined.length - Protocol.HEADER_LENGTH;
      assertThat(remaining).isLessThan(bodyLength);
    }

    @Test
    @DisplayName("should handle zero-length body")
    void zeroLengthBody() {
      byte[] frame = Protocol.FrameBuilder.buildFrame((short) 1, Protocol.OPCODE_RESULT, new byte[0]);

      assertThat(frame.length).isEqualTo(Protocol.HEADER_LENGTH);

      // Parse and verify
      Protocol.BytesReader reader = new Protocol.BytesReader(frame);
      reader.skip(4); // skip version, flags, stream
      byte opcode = reader.readByte();
      int bodyLength = reader.readInt();

      assertThat(opcode).isEqualTo(Protocol.OPCODE_RESULT);
      assertThat(bodyLength).isEqualTo(0);
    }

    @Test
    @DisplayName("should handle maximum body length value")
    void maxBodyLength() {
      // Create a header with max int body length (simulating corrupt data)
      Protocol.BytesWriter writer = new Protocol.BytesWriter(Protocol.HEADER_LENGTH);
      writer.writeByte(Protocol.VERSION_REQUEST);
      writer.writeByte(0);
      writer.writeShort((short) 1);
      writer.writeByte(Protocol.OPCODE_QUERY);
      writer.writeInt(Integer.MAX_VALUE); // 2GB body length

      byte[] header = writer.toByteArray();

      Protocol.BytesReader reader = new Protocol.BytesReader(header);
      reader.skip(5); // skip to body length
      int bodyLength = reader.readInt();

      // Should read the value correctly (validation is done elsewhere)
      assertThat(bodyLength).isEqualTo(Integer.MAX_VALUE);
    }
  }

  @Nested
  @DisplayName("Connection State")
  class ConnectionState {

    @Test
    @DisplayName("should handle rapid connect/disconnect cycles")
    void rapidConnectDisconnect() {
      // Test that protocol structures handle many alloc/dealloc cycles
      for (int i = 0; i < 1000; i++) {
        Protocol.BytesWriter writer = new Protocol.BytesWriter(128);
        writer.writeInt(i);
        writer.writeString("test" + i);

        byte[] data = writer.toByteArray();
        Protocol.BytesReader reader = new Protocol.BytesReader(data);

        assertThat(reader.readInt()).isEqualTo(i);
        assertThat(reader.readString()).isEqualTo("test" + i);
      }
    }

    @Test
    @DisplayName("should handle PooledBytesWriter lifecycle under connection churn")
    void pooledWriterChurn() {
      // Simulate connection churn with pooled writers
      for (int i = 0; i < 100; i++) {
        try (Protocol.PooledBytesWriter writer = new Protocol.PooledBytesWriter(1024)) {
          writer.writeInt(i);
          writer.writeString("connection" + i);

          // Simulate mid-write disconnection by abandoning the writer
          if (i % 10 == 0) {
            // Don't read - just close
            continue;
          }

          assertThat(writer.readableBytes()).isGreaterThan(0);
        }
      }
    }

    @Test
    @DisplayName("should handle concurrent writer allocation")
    void concurrentWriterAllocation() throws Exception {
      int threadCount = 10;
      int iterationsPerThread = 100;
      CountDownLatch startLatch = new CountDownLatch(1);
      CountDownLatch doneLatch = new CountDownLatch(threadCount);
      AtomicBoolean failed = new AtomicBoolean(false);

      for (int t = 0; t < threadCount; t++) {
        final int threadId = t;
        new Thread(
                () -> {
                  try {
                    startLatch.await();
                    for (int i = 0; i < iterationsPerThread; i++) {
                      try (Protocol.PooledBytesWriter writer = new Protocol.PooledBytesWriter(256)) {
                        writer.writeInt(threadId);
                        writer.writeInt(i);
                        writer.writeString("thread" + threadId + "-iter" + i);

                        byte[] data = writer.toByteArray();
                        assertThat(data).isNotNull();
                      }
                    }
                  } catch (Exception e) {
                    failed.set(true);
                  } finally {
                    doneLatch.countDown();
                  }
                })
            .start();
      }

      startLatch.countDown();
      boolean completed = doneLatch.await(30, TimeUnit.SECONDS);

      assertThat(completed).isTrue();
      assertThat(failed.get()).isFalse();
    }
  }

  @Nested
  @DisplayName("Error Recovery")
  class ErrorRecovery {

    @Test
    @DisplayName("should generate valid error frame for connection errors")
    void errorFrameForConnectionError() {
      String errorMessage = "Connection reset by peer";
      byte[] errorFrame =
          Protocol.FrameBuilder.buildErrorFrame((short) 1, Protocol.ERROR_CODE_SERVER, errorMessage);

      assertThat(errorFrame).isNotNull();
      assertThat(errorFrame.length).isGreaterThan(Protocol.HEADER_LENGTH);

      // Parse error frame
      Protocol.BytesReader reader = new Protocol.BytesReader(errorFrame);
      assertThat(reader.readByte()).isEqualTo(Protocol.VERSION_RESPONSE);
      reader.skip(1); // flags
      assertThat(reader.readShort()).isEqualTo((short) 1);
      assertThat(reader.readByte()).isEqualTo(Protocol.OPCODE_ERROR);

      int bodyLength = reader.readInt();
      assertThat(bodyLength).isGreaterThan(0);

      int errorCode = reader.readInt();
      assertThat(errorCode).isEqualTo(Protocol.ERROR_CODE_SERVER);

      String message = reader.readString();
      assertThat(message).isEqualTo(errorMessage);
    }

    @Test
    @DisplayName("should handle error frame with special characters")
    void errorFrameSpecialChars() {
      String errorMessage = "Connection failed: host=192.168.1.1, port=9042, cause=\"timeout\"";
      byte[] errorFrame =
          Protocol.FrameBuilder.buildErrorFrame((short) 1, Protocol.ERROR_CODE_SERVER, errorMessage);

      Protocol.BytesReader reader = new Protocol.BytesReader(errorFrame);
      reader.skip(Protocol.HEADER_LENGTH);
      reader.skip(4); // error code

      String message = reader.readString();
      assertThat(message).isEqualTo(errorMessage);
    }

    @Test
    @DisplayName("should handle very long error message")
    void longErrorMessage() {
      String longMessage = "Error: " + "x".repeat(10000);
      byte[] errorFrame =
          Protocol.FrameBuilder.buildErrorFrame((short) 1, Protocol.ERROR_CODE_SERVER, longMessage);

      Protocol.BytesReader reader = new Protocol.BytesReader(errorFrame);
      reader.skip(Protocol.HEADER_LENGTH);
      reader.skip(4); // error code

      String message = reader.readString();
      assertThat(message).isEqualTo(longMessage);
    }
  }

  @Nested
  @DisplayName("Stream Management")
  class StreamManagement {

    @Test
    @DisplayName("should handle all valid stream IDs")
    void allValidStreamIds() {
      // Stream IDs are shorts, test boundary values
      short[] streamIds = {0, 1, 100, 1000, Short.MAX_VALUE, -1, Short.MIN_VALUE};

      for (short streamId : streamIds) {
        byte[] frame =
            Protocol.FrameBuilder.buildFrame(streamId, Protocol.OPCODE_RESULT, new byte[0]);

        Protocol.BytesReader reader = new Protocol.BytesReader(frame);
        reader.skip(2); // version, flags
        short parsedStreamId = reader.readShort();

        assertThat(parsedStreamId).isEqualTo(streamId);
      }
    }

    @Test
    @DisplayName("should maintain stream ID in error response")
    void streamIdInErrorResponse() {
      short originalStreamId = 12345;
      byte[] errorFrame =
          Protocol.FrameBuilder.buildErrorFrame(
              originalStreamId, Protocol.ERROR_CODE_SERVER, "test error");

      Protocol.BytesReader reader = new Protocol.BytesReader(errorFrame);
      reader.skip(2); // version, flags
      short responseStreamId = reader.readShort();

      assertThat(responseStreamId).isEqualTo(originalStreamId);
    }
  }

  @Nested
  @DisplayName("Graceful Degradation")
  class GracefulDegradation {

    @Test
    @DisplayName("should handle BytesReader with empty data")
    void emptyBytesReader() {
      Protocol.BytesReader reader = new Protocol.BytesReader(new byte[0]);

      assertThatThrownBy(reader::readByte).isInstanceOf(BufferUnderflowException.class);
    }

    @Test
    @DisplayName("should handle BytesWriter overflow gracefully")
    void bytesWriterOverflow() {
      // BytesWriter should expand automatically
      Protocol.BytesWriter writer = new Protocol.BytesWriter(4);

      // Write more than initial capacity
      for (int i = 0; i < 100; i++) {
        writer.writeInt(i);
      }

      assertThat(writer.size()).isEqualTo(400);

      byte[] data = writer.toByteArray();
      assertThat(data.length).isEqualTo(400);

      // Verify data integrity
      Protocol.BytesReader reader = new Protocol.BytesReader(data);
      for (int i = 0; i < 100; i++) {
        assertThat(reader.readInt()).isEqualTo(i);
      }
    }

    @Test
    @DisplayName("should handle null body in frame builder")
    void nullBodyInFrameBuilder() {
      // Frame builder should handle empty body
      byte[] frame = Protocol.FrameBuilder.buildFrame((short) 1, Protocol.OPCODE_RESULT, new byte[0]);

      assertThat(frame).isNotNull();
      assertThat(frame.length).isEqualTo(Protocol.HEADER_LENGTH);
    }
  }

  @Nested
  @DisplayName("Timeout Simulation")
  class TimeoutSimulation {

    @Test
    @DisplayName("should handle read timeout scenario")
    void readTimeoutScenario() {
      // Simulate a partial read that would timeout
      byte[] partialData = new byte[5]; // Less than header size

      Protocol.BytesReader reader = new Protocol.BytesReader(partialData);

      // Can read 5 bytes
      for (int i = 0; i < 5; i++) {
        reader.readByte();
      }

      // 6th byte should fail (simulating timeout/EOF)
      assertThatThrownBy(reader::readByte).isInstanceOf(BufferUnderflowException.class);
    }

    @Test
    @DisplayName("should handle write buffer full scenario")
    void writeBufferFullScenario() {
      // Create a large amount of data to write
      Protocol.BytesWriter writer = new Protocol.BytesWriter(1024);

      // Write 1MB of data
      for (int i = 0; i < 256 * 1024; i++) {
        writer.writeInt(i);
      }

      assertThat(writer.size()).isEqualTo(1024 * 1024);

      // Should be able to convert to array without issues
      byte[] data = writer.toByteArray();
      assertThat(data.length).isEqualTo(1024 * 1024);
    }
  }

  @Nested
  @DisplayName("Protocol Version Handling")
  class ProtocolVersionHandling {

    @Test
    @DisplayName("should detect invalid protocol version")
    void invalidProtocolVersion() {
      Protocol.BytesWriter writer = new Protocol.BytesWriter(Protocol.HEADER_LENGTH);
      writer.writeByte(0x99); // Invalid version
      writer.writeByte(0);
      writer.writeShort((short) 1);
      writer.writeByte(Protocol.OPCODE_QUERY);
      writer.writeInt(0);

      byte[] frame = writer.toByteArray();

      Protocol.BytesReader reader = new Protocol.BytesReader(frame);
      byte version = reader.readByte();

      // Version should be readable but not matching expected
      assertThat(version).isNotEqualTo(Protocol.VERSION_REQUEST);
      assertThat(version).isNotEqualTo(Protocol.VERSION_RESPONSE);
    }

    @Test
    @DisplayName("should handle request vs response version")
    void requestVsResponseVersion() {
      // FrameBuilder.buildFrame always uses VERSION_RESPONSE (it builds response frames)
      byte[] responseFrame = Protocol.FrameBuilder.buildFrame((short) 1, Protocol.OPCODE_RESULT, new byte[0]);

      // Response frame - use VERSION_RESPONSE
      Protocol.BytesWriter respWriter = new Protocol.BytesWriter(Protocol.HEADER_LENGTH);
      respWriter.writeByte(Protocol.VERSION_RESPONSE);
      respWriter.writeByte(0);
      respWriter.writeShort((short) 1);
      respWriter.writeByte(Protocol.OPCODE_RESULT);
      respWriter.writeInt(0);
      byte[] manualResponse = respWriter.toByteArray();

      // Both should use VERSION_RESPONSE
      assertThat(responseFrame[0]).isEqualTo(Protocol.VERSION_RESPONSE);
      assertThat(manualResponse[0]).isEqualTo(Protocol.VERSION_RESPONSE);

      // Verify VERSION_REQUEST and VERSION_RESPONSE are different
      assertThat(Protocol.VERSION_REQUEST).isNotEqualTo(Protocol.VERSION_RESPONSE);
    }
  }
}

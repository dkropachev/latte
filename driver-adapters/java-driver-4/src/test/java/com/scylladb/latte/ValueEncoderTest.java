package com.scylladb.latte;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;

import com.datastax.oss.driver.api.core.data.CqlDuration;
import com.datastax.oss.driver.api.core.data.CqlVector;
import com.datastax.oss.driver.api.core.type.DataTypes;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.temporal.ChronoUnit;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

@DisplayName("ValueEncoder")
class ValueEncoderTest {

  @Nested
  @DisplayName("Primitive Type Decoding")
  class PrimitiveTypeDecoding {

    @Test
    @DisplayName("should decode tinyint")
    void decodeTinyint() {
      byte[] data = {42};
      Object result = ValueEncoder.decode(data, Protocol.TYPE_TINYINT, DataTypes.TINYINT);
      assertThat(result).isEqualTo((byte) 42);
    }

    @Test
    @DisplayName("should decode negative tinyint")
    void decodeNegativeTinyint() {
      byte[] data = {(byte) -1};
      Object result = ValueEncoder.decode(data, Protocol.TYPE_TINYINT, DataTypes.TINYINT);
      assertThat(result).isEqualTo((byte) -1);
    }

    @Test
    @DisplayName("should decode smallint")
    void decodeSmallint() {
      byte[] data = {0x01, (byte) 0x00}; // 256 in big-endian
      Object result = ValueEncoder.decode(data, Protocol.TYPE_SMALLINT, DataTypes.SMALLINT);
      assertThat(result).isEqualTo((short) 256);
    }

    @Test
    @DisplayName("should decode int")
    void decodeInt() {
      byte[] data = {0x00, 0x01, 0x00, 0x00}; // 65536 in big-endian
      Object result = ValueEncoder.decode(data, Protocol.TYPE_INT, DataTypes.INT);
      assertThat(result).isEqualTo(65536);
    }

    @Test
    @DisplayName("should decode bigint")
    void decodeBigint() {
      byte[] data = {0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x64}; // 100
      Object result = ValueEncoder.decode(data, Protocol.TYPE_BIGINT, DataTypes.BIGINT);
      assertThat(result).isEqualTo(100L);
    }

    @Test
    @DisplayName("should decode counter as bigint")
    void decodeCounter() {
      byte[] data = {0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x0A}; // 10
      Object result = ValueEncoder.decode(data, Protocol.TYPE_COUNTER, DataTypes.COUNTER);
      assertThat(result).isEqualTo(10L);
    }

    @Test
    @DisplayName("should decode float")
    void decodeFloat() {
      float expected = 3.14f;
      int bits = Float.floatToIntBits(expected);
      byte[] data = {
        (byte) (bits >> 24),
        (byte) (bits >> 16),
        (byte) (bits >> 8),
        (byte) bits
      };
      Object result = ValueEncoder.decode(data, Protocol.TYPE_FLOAT, DataTypes.FLOAT);
      assertThat((Float) result).isCloseTo(expected, within(0.001f));
    }

    @Test
    @DisplayName("should decode double")
    void decodeDouble() {
      double expected = 3.14159265359;
      long bits = Double.doubleToLongBits(expected);
      byte[] data = new byte[8];
      for (int i = 0; i < 8; i++) {
        data[i] = (byte) (bits >> (56 - i * 8));
      }
      Object result = ValueEncoder.decode(data, Protocol.TYPE_DOUBLE, DataTypes.DOUBLE);
      assertThat((Double) result).isCloseTo(expected, within(0.0000001));
    }

    @Test
    @DisplayName("should decode boolean true")
    void decodeBooleanTrue() {
      byte[] data = {1};
      Object result = ValueEncoder.decode(data, Protocol.TYPE_BOOLEAN, DataTypes.BOOLEAN);
      assertThat(result).isEqualTo(true);
    }

    @Test
    @DisplayName("should decode boolean false")
    void decodeBooleanFalse() {
      byte[] data = {0};
      Object result = ValueEncoder.decode(data, Protocol.TYPE_BOOLEAN, DataTypes.BOOLEAN);
      assertThat(result).isEqualTo(false);
    }

    @Test
    @DisplayName("should decode text")
    void decodeText() {
      byte[] data = "Hello, World!".getBytes(StandardCharsets.UTF_8);
      Object result = ValueEncoder.decode(data, Protocol.TYPE_TEXT, DataTypes.TEXT);
      assertThat(result).isEqualTo("Hello, World!");
    }

    @Test
    @DisplayName("should decode ascii")
    void decodeAscii() {
      byte[] data = "ASCII text".getBytes(StandardCharsets.UTF_8);
      Object result = ValueEncoder.decode(data, Protocol.TYPE_ASCII, DataTypes.ASCII);
      assertThat(result).isEqualTo("ASCII text");
    }

    @Test
    @DisplayName("should decode blob")
    void decodeBlob() {
      byte[] data = {0x01, 0x02, 0x03, 0x04};
      Object result = ValueEncoder.decode(data, Protocol.TYPE_BLOB, DataTypes.BLOB);
      assertThat(result).isInstanceOf(ByteBuffer.class);
      ByteBuffer bb = (ByteBuffer) result;
      byte[] bytes = new byte[bb.remaining()];
      bb.get(bytes);
      assertThat(bytes).isEqualTo(data);
    }

    @Test
    @DisplayName("should decode UUID")
    void decodeUuid() {
      UUID expected = UUID.fromString("550e8400-e29b-41d4-a716-446655440000");
      byte[] data = new byte[16];
      long msb = expected.getMostSignificantBits();
      long lsb = expected.getLeastSignificantBits();
      for (int i = 0; i < 8; i++) {
        data[i] = (byte) (msb >> (56 - i * 8));
        data[i + 8] = (byte) (lsb >> (56 - i * 8));
      }
      Object result = ValueEncoder.decode(data, Protocol.TYPE_UUID, DataTypes.UUID);
      assertThat(result).isEqualTo(expected);
    }

    @Test
    @DisplayName("should decode timeuuid")
    void decodeTimeuuid() {
      UUID expected = UUID.fromString("550e8400-e29b-11d4-a716-446655440000");
      byte[] data = new byte[16];
      long msb = expected.getMostSignificantBits();
      long lsb = expected.getLeastSignificantBits();
      for (int i = 0; i < 8; i++) {
        data[i] = (byte) (msb >> (56 - i * 8));
        data[i + 8] = (byte) (lsb >> (56 - i * 8));
      }
      Object result = ValueEncoder.decode(data, Protocol.TYPE_TIMEUUID, DataTypes.TIMEUUID);
      assertThat(result).isEqualTo(expected);
    }

    @Test
    @DisplayName("should decode timestamp")
    void decodeTimestamp() {
      long epochMillis = 1609459200000L; // 2021-01-01 00:00:00 UTC
      byte[] data = new byte[8];
      for (int i = 0; i < 8; i++) {
        data[i] = (byte) (epochMillis >> (56 - i * 8));
      }
      Object result = ValueEncoder.decode(data, Protocol.TYPE_TIMESTAMP, DataTypes.TIMESTAMP);
      assertThat(result).isEqualTo(Instant.ofEpochMilli(epochMillis));
    }

    @Test
    @DisplayName("should decode date")
    void decodeDate() {
      // CQL date: days since epoch with offset 2^31
      LocalDate expected = LocalDate.of(2024, 1, 15);
      long daysFromEpoch = expected.toEpochDay();
      int encoded = (int) (daysFromEpoch + (long) Integer.MIN_VALUE);
      byte[] data = {
        (byte) (encoded >> 24),
        (byte) (encoded >> 16),
        (byte) (encoded >> 8),
        (byte) encoded
      };
      Object result = ValueEncoder.decode(data, Protocol.TYPE_DATE, DataTypes.DATE);
      assertThat(result).isEqualTo(expected);
    }

    @Test
    @DisplayName("should decode time")
    void decodeTime() {
      long nanos = LocalTime.of(14, 30, 45, 123456789).toNanoOfDay();
      byte[] data = new byte[8];
      for (int i = 0; i < 8; i++) {
        data[i] = (byte) (nanos >> (56 - i * 8));
      }
      Object result = ValueEncoder.decode(data, Protocol.TYPE_TIME, DataTypes.TIME);
      assertThat(result).isEqualTo(LocalTime.of(14, 30, 45, 123456789));
    }

    @Test
    @DisplayName("should decode IPv4 inet")
    void decodeInetIPv4() throws UnknownHostException {
      byte[] data = {127, 0, 0, 1}; // 127.0.0.1
      Object result = ValueEncoder.decode(data, Protocol.TYPE_INET, DataTypes.INET);
      assertThat(result).isEqualTo(InetAddress.getByName("127.0.0.1"));
    }

    @Test
    @DisplayName("should decode IPv6 inet")
    void decodeInetIPv6() throws UnknownHostException {
      byte[] data = new byte[16];
      data[15] = 1; // ::1
      Object result = ValueEncoder.decode(data, Protocol.TYPE_INET, DataTypes.INET);
      assertThat(result).isEqualTo(InetAddress.getByName("::1"));
    }

    @Test
    @DisplayName("should decode varint")
    void decodeVarint() {
      BigInteger expected = new BigInteger("123456789012345678901234567890");
      byte[] data = expected.toByteArray();
      Object result = ValueEncoder.decode(data, Protocol.TYPE_VARINT, DataTypes.VARINT);
      assertThat(result).isEqualTo(expected);
    }

    @Test
    @DisplayName("should decode decimal")
    void decodeDecimal() {
      BigDecimal expected = new BigDecimal("12345.67890");
      int scale = expected.scale();
      byte[] unscaled = expected.unscaledValue().toByteArray();
      byte[] data = new byte[4 + unscaled.length];
      data[0] = (byte) (scale >> 24);
      data[1] = (byte) (scale >> 16);
      data[2] = (byte) (scale >> 8);
      data[3] = (byte) scale;
      System.arraycopy(unscaled, 0, data, 4, unscaled.length);
      Object result = ValueEncoder.decode(data, Protocol.TYPE_DECIMAL, DataTypes.DECIMAL);
      assertThat(result).isEqualTo(expected);
    }

    @Test
    @DisplayName("should decode null data as null")
    void decodeNullData() {
      Object result = ValueEncoder.decode(null, Protocol.TYPE_INT, DataTypes.INT);
      assertThat(result).isNull();
    }
  }

  @Nested
  @DisplayName("Type Coercion")
  class TypeCoercion {

    @Test
    @DisplayName("should coerce bigint to int")
    void coerceBigintToInt() {
      byte[] data = {0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x64}; // 100
      Object result = ValueEncoder.decode(data, Protocol.TYPE_BIGINT, DataTypes.INT);
      assertThat(result).isEqualTo(100);
    }

    @Test
    @DisplayName("should coerce bigint to smallint")
    void coerceBigintToSmallint() {
      byte[] data = {0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x32}; // 50
      Object result = ValueEncoder.decode(data, Protocol.TYPE_BIGINT, DataTypes.SMALLINT);
      assertThat(result).isEqualTo((short) 50);
    }

    @Test
    @DisplayName("should coerce bigint to tinyint")
    void coerceBigintToTinyint() {
      byte[] data = {0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x0A}; // 10
      Object result = ValueEncoder.decode(data, Protocol.TYPE_BIGINT, DataTypes.TINYINT);
      assertThat(result).isEqualTo((byte) 10);
    }

    @Test
    @DisplayName("should coerce bigint to varint")
    void coerceBigintToVarint() {
      byte[] data = {0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x64}; // 100
      Object result = ValueEncoder.decode(data, Protocol.TYPE_BIGINT, DataTypes.VARINT);
      assertThat(result).isEqualTo(BigInteger.valueOf(100));
    }

    @Test
    @DisplayName("should coerce bigint to decimal")
    void coerceBigintToDecimal() {
      byte[] data = {0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x64}; // 100
      Object result = ValueEncoder.decode(data, Protocol.TYPE_BIGINT, DataTypes.DECIMAL);
      assertThat(result).isEqualTo(BigDecimal.valueOf(100));
    }

    @Test
    @DisplayName("should coerce bigint to timestamp")
    void coerceBigintToTimestamp() {
      long epochMillis = 1609459200000L;
      byte[] data = new byte[8];
      for (int i = 0; i < 8; i++) {
        data[i] = (byte) (epochMillis >> (56 - i * 8));
      }
      Object result = ValueEncoder.decode(data, Protocol.TYPE_BIGINT, DataTypes.TIMESTAMP);
      assertThat(result).isEqualTo(Instant.ofEpochMilli(epochMillis));
    }

    @Test
    @DisplayName("should coerce double to float")
    void coerceDoubleToFloat() {
      double value = 3.14;
      long bits = Double.doubleToLongBits(value);
      byte[] data = new byte[8];
      for (int i = 0; i < 8; i++) {
        data[i] = (byte) (bits >> (56 - i * 8));
      }
      Object result = ValueEncoder.decode(data, Protocol.TYPE_DOUBLE, DataTypes.FLOAT);
      assertThat((Float) result).isCloseTo(3.14f, within(0.01f));
    }

    @Test
    @DisplayName("should coerce string to date")
    void coerceStringToDate() {
      byte[] data = "2024-01-15".getBytes(StandardCharsets.UTF_8);
      Object result = ValueEncoder.decode(data, Protocol.TYPE_TEXT, DataTypes.DATE);
      assertThat(result).isEqualTo(LocalDate.of(2024, 1, 15));
    }

    @Test
    @DisplayName("should coerce string to UUID")
    void coerceStringToUuid() {
      String uuidStr = "550e8400-e29b-41d4-a716-446655440000";
      byte[] data = uuidStr.getBytes(StandardCharsets.UTF_8);
      Object result = ValueEncoder.decode(data, Protocol.TYPE_TEXT, DataTypes.UUID);
      assertThat(result).isEqualTo(UUID.fromString(uuidStr));
    }

    @Test
    @DisplayName("should coerce string to decimal")
    void coerceStringToDecimal() {
      byte[] data = "123.456".getBytes(StandardCharsets.UTF_8);
      Object result = ValueEncoder.decode(data, Protocol.TYPE_TEXT, DataTypes.DECIMAL);
      assertThat(result).isEqualTo(new BigDecimal("123.456"));
    }

    @Test
    @DisplayName("should coerce string to inet")
    void coerceStringToInet() throws UnknownHostException {
      byte[] data = "192.168.1.1".getBytes(StandardCharsets.UTF_8);
      Object result = ValueEncoder.decode(data, Protocol.TYPE_TEXT, DataTypes.INET);
      assertThat(result).isEqualTo(InetAddress.getByName("192.168.1.1"));
    }

    @Test
    @DisplayName("should coerce flexible time format")
    void coerceFlexibleTimeFormat() {
      byte[] data = "5:5:5.123456789".getBytes(StandardCharsets.UTF_8);
      Object result = ValueEncoder.decode(data, Protocol.TYPE_TEXT, DataTypes.TIME);
      assertThat(result).isEqualTo(LocalTime.of(5, 5, 5, 123456789));
    }

    @Test
    @DisplayName("should coerce simple time format")
    void coerceSimpleTimeFormat() {
      byte[] data = "14:30".getBytes(StandardCharsets.UTF_8);
      Object result = ValueEncoder.decode(data, Protocol.TYPE_TEXT, DataTypes.TIME);
      assertThat(result).isEqualTo(LocalTime.of(14, 30, 0, 0));
    }
  }

  @Nested
  @DisplayName("Duration")
  class DurationTests {

    @Test
    @DisplayName("should decode duration from binary")
    void decodeDurationBinary() {
      // Create a duration using zigzag-encoded varints
      Protocol.BytesWriter writer = new Protocol.BytesWriter();
      // Months: 2 (zigzag: 4)
      writer.writeByte(4);
      // Days: 10 (zigzag: 20)
      writer.writeByte(20);
      // Nanos: 3600000000000 (1 hour in nanos, zigzag encoded)
      long nanos = 3600_000_000_000L;
      long encoded = (nanos << 1) ^ (nanos >> 63);
      while ((encoded & ~0x7FL) != 0) {
        writer.writeByte((int) ((encoded & 0x7F) | 0x80));
        encoded >>>= 7;
      }
      writer.writeByte((int) encoded);

      byte[] data = writer.toByteArray();
      Object result = ValueEncoder.decode(data, Protocol.TYPE_DURATION, DataTypes.DURATION);

      assertThat(result).isInstanceOf(CqlDuration.class);
      CqlDuration duration = (CqlDuration) result;
      assertThat(duration.getMonths()).isEqualTo(2);
      assertThat(duration.getDays()).isEqualTo(10);
      assertThat(duration.getNanoseconds()).isEqualTo(3600_000_000_000L);
    }

    @Test
    @DisplayName("should coerce string to duration")
    void coerceStringToDuration() {
      byte[] data = "1mo2d3h".getBytes(StandardCharsets.UTF_8);
      Object result = ValueEncoder.decode(data, Protocol.TYPE_TEXT, DataTypes.DURATION);

      assertThat(result).isInstanceOf(CqlDuration.class);
      CqlDuration duration = (CqlDuration) result;
      assertThat(duration.getMonths()).isEqualTo(1);
      assertThat(duration.getDays()).isEqualTo(2);
      assertThat(duration.getNanoseconds()).isEqualTo(3 * 3600_000_000_000L);
    }
  }

  @Nested
  @DisplayName("Encoding")
  class Encoding {

    @Test
    @DisplayName("should encode tinyint")
    void encodeTinyint() {
      byte[] result = ValueEncoder.encode((byte) 42, DataTypes.TINYINT);
      assertThat(result).isEqualTo(new byte[] {42});
    }

    @Test
    @DisplayName("should encode smallint")
    void encodeSmallint() {
      byte[] result = ValueEncoder.encode((short) 256, DataTypes.SMALLINT);
      assertThat(result).isEqualTo(new byte[] {0x01, 0x00});
    }

    @Test
    @DisplayName("should encode int")
    void encodeInt() {
      byte[] result = ValueEncoder.encode(65536, DataTypes.INT);
      assertThat(result).isEqualTo(new byte[] {0x00, 0x01, 0x00, 0x00});
    }

    @Test
    @DisplayName("should encode bigint")
    void encodeBigint() {
      byte[] result = ValueEncoder.encode(100L, DataTypes.BIGINT);
      assertThat(result).isEqualTo(new byte[] {0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x64});
    }

    @Test
    @DisplayName("should encode boolean true")
    void encodeBooleanTrue() {
      byte[] result = ValueEncoder.encode(true, DataTypes.BOOLEAN);
      assertThat(result).isEqualTo(new byte[] {1});
    }

    @Test
    @DisplayName("should encode boolean false")
    void encodeBooleanFalse() {
      byte[] result = ValueEncoder.encode(false, DataTypes.BOOLEAN);
      assertThat(result).isEqualTo(new byte[] {0});
    }

    @Test
    @DisplayName("should encode text")
    void encodeText() {
      byte[] result = ValueEncoder.encode("Hello", DataTypes.TEXT);
      assertThat(result).isEqualTo("Hello".getBytes(StandardCharsets.UTF_8));
    }

    @Test
    @DisplayName("should encode blob")
    void encodeBlob() {
      byte[] data = {0x01, 0x02, 0x03};
      byte[] result = ValueEncoder.encode(ByteBuffer.wrap(data), DataTypes.BLOB);
      assertThat(result).isEqualTo(data);
    }

    @Test
    @DisplayName("should encode UUID")
    void encodeUuid() {
      UUID uuid = UUID.fromString("550e8400-e29b-41d4-a716-446655440000");
      byte[] result = ValueEncoder.encode(uuid, DataTypes.UUID);
      assertThat(result.length).isEqualTo(16);

      // Decode back and verify
      Object decoded = ValueEncoder.decode(result, Protocol.TYPE_UUID, DataTypes.UUID);
      assertThat(decoded).isEqualTo(uuid);
    }

    @Test
    @DisplayName("should encode timestamp")
    void encodeTimestamp() {
      Instant instant = Instant.ofEpochMilli(1609459200000L);
      byte[] result = ValueEncoder.encode(instant, DataTypes.TIMESTAMP);
      assertThat(result.length).isEqualTo(8);

      // Decode back and verify
      Object decoded = ValueEncoder.decode(result, Protocol.TYPE_TIMESTAMP, DataTypes.TIMESTAMP);
      assertThat(decoded).isEqualTo(instant);
    }

    @Test
    @DisplayName("should encode null as null")
    void encodeNull() {
      byte[] result = ValueEncoder.encode(null, DataTypes.INT);
      assertThat(result).isNull();
    }

    @Test
    @DisplayName("should roundtrip decimal")
    void roundtripDecimal() {
      BigDecimal original = new BigDecimal("12345.67890");
      byte[] encoded = ValueEncoder.encode(original, DataTypes.DECIMAL);
      Object decoded = ValueEncoder.decode(encoded, Protocol.TYPE_DECIMAL, DataTypes.DECIMAL);
      assertThat(decoded).isEqualTo(original);
    }

    @Test
    @DisplayName("should roundtrip varint")
    void roundtripVarint() {
      BigInteger original = new BigInteger("123456789012345678901234567890");
      byte[] encoded = ValueEncoder.encode(original, DataTypes.VARINT);
      Object decoded = ValueEncoder.decode(encoded, Protocol.TYPE_VARINT, DataTypes.VARINT);
      assertThat(decoded).isEqualTo(original);
    }

    @Test
    @DisplayName("should roundtrip date")
    void roundtripDate() {
      LocalDate original = LocalDate.of(2024, 6, 15);
      byte[] encoded = ValueEncoder.encode(original, DataTypes.DATE);
      Object decoded = ValueEncoder.decode(encoded, Protocol.TYPE_DATE, DataTypes.DATE);
      assertThat(decoded).isEqualTo(original);
    }

    @Test
    @DisplayName("should roundtrip time")
    void roundtripTime() {
      LocalTime original = LocalTime.of(14, 30, 45, 123456789);
      byte[] encoded = ValueEncoder.encode(original, DataTypes.TIME);
      Object decoded = ValueEncoder.decode(encoded, Protocol.TYPE_TIME, DataTypes.TIME);
      assertThat(decoded).isEqualTo(original);
    }

    @Test
    @DisplayName("should roundtrip inet IPv4")
    void roundtripInetIPv4() throws UnknownHostException {
      InetAddress original = InetAddress.getByName("192.168.1.1");
      byte[] encoded = ValueEncoder.encode(original, DataTypes.INET);
      Object decoded = ValueEncoder.decode(encoded, Protocol.TYPE_INET, DataTypes.INET);
      assertThat(decoded).isEqualTo(original);
    }

    @Test
    @DisplayName("should roundtrip inet IPv6")
    void roundtripInetIPv6() throws UnknownHostException {
      InetAddress original = InetAddress.getByName("2001:db8::1");
      byte[] encoded = ValueEncoder.encode(original, DataTypes.INET);
      Object decoded = ValueEncoder.decode(encoded, Protocol.TYPE_INET, DataTypes.INET);
      assertThat(decoded).isEqualTo(original);
    }
  }

  @Nested
  @DisplayName("Type Codes")
  class TypeCodes {

    @Test
    @DisplayName("should return correct type code for primitives")
    void primitiveTypeCodes() {
      assertThat(ValueEncoder.getTypeCode(DataTypes.TINYINT)).isEqualTo(Protocol.TYPE_TINYINT);
      assertThat(ValueEncoder.getTypeCode(DataTypes.SMALLINT)).isEqualTo(Protocol.TYPE_SMALLINT);
      assertThat(ValueEncoder.getTypeCode(DataTypes.INT)).isEqualTo(Protocol.TYPE_INT);
      assertThat(ValueEncoder.getTypeCode(DataTypes.BIGINT)).isEqualTo(Protocol.TYPE_BIGINT);
      assertThat(ValueEncoder.getTypeCode(DataTypes.COUNTER)).isEqualTo(Protocol.TYPE_COUNTER);
      assertThat(ValueEncoder.getTypeCode(DataTypes.FLOAT)).isEqualTo(Protocol.TYPE_FLOAT);
      assertThat(ValueEncoder.getTypeCode(DataTypes.DOUBLE)).isEqualTo(Protocol.TYPE_DOUBLE);
      assertThat(ValueEncoder.getTypeCode(DataTypes.BOOLEAN)).isEqualTo(Protocol.TYPE_BOOLEAN);
      assertThat(ValueEncoder.getTypeCode(DataTypes.TEXT)).isEqualTo(Protocol.TYPE_TEXT);
      assertThat(ValueEncoder.getTypeCode(DataTypes.ASCII)).isEqualTo(Protocol.TYPE_ASCII);
      assertThat(ValueEncoder.getTypeCode(DataTypes.BLOB)).isEqualTo(Protocol.TYPE_BLOB);
      assertThat(ValueEncoder.getTypeCode(DataTypes.UUID)).isEqualTo(Protocol.TYPE_UUID);
      assertThat(ValueEncoder.getTypeCode(DataTypes.TIMEUUID)).isEqualTo(Protocol.TYPE_TIMEUUID);
      assertThat(ValueEncoder.getTypeCode(DataTypes.TIMESTAMP)).isEqualTo(Protocol.TYPE_TIMESTAMP);
      assertThat(ValueEncoder.getTypeCode(DataTypes.DATE)).isEqualTo(Protocol.TYPE_DATE);
      assertThat(ValueEncoder.getTypeCode(DataTypes.TIME)).isEqualTo(Protocol.TYPE_TIME);
      assertThat(ValueEncoder.getTypeCode(DataTypes.INET)).isEqualTo(Protocol.TYPE_INET);
      assertThat(ValueEncoder.getTypeCode(DataTypes.VARINT)).isEqualTo(Protocol.TYPE_VARINT);
      assertThat(ValueEncoder.getTypeCode(DataTypes.DECIMAL)).isEqualTo(Protocol.TYPE_DECIMAL);
      assertThat(ValueEncoder.getTypeCode(DataTypes.DURATION)).isEqualTo(Protocol.TYPE_DURATION);
    }
  }

  @Nested
  @DisplayName("Edge Cases")
  class EdgeCases {

    @Test
    @DisplayName("should handle max int value")
    void maxIntValue() {
      int value = Integer.MAX_VALUE;
      byte[] data = {
        (byte) (value >> 24),
        (byte) (value >> 16),
        (byte) (value >> 8),
        (byte) value
      };
      Object result = ValueEncoder.decode(data, Protocol.TYPE_INT, DataTypes.INT);
      assertThat(result).isEqualTo(Integer.MAX_VALUE);
    }

    @Test
    @DisplayName("should handle min int value")
    void minIntValue() {
      int value = Integer.MIN_VALUE;
      byte[] data = {
        (byte) (value >> 24),
        (byte) (value >> 16),
        (byte) (value >> 8),
        (byte) value
      };
      Object result = ValueEncoder.decode(data, Protocol.TYPE_INT, DataTypes.INT);
      assertThat(result).isEqualTo(Integer.MIN_VALUE);
    }

    @Test
    @DisplayName("should handle empty string")
    void emptyString() {
      byte[] data = new byte[0];
      Object result = ValueEncoder.decode(data, Protocol.TYPE_TEXT, DataTypes.TEXT);
      assertThat(result).isEqualTo("");
    }

    @Test
    @DisplayName("should handle unicode text")
    void unicodeText() {
      String unicode = "Hello, \u4e16\u754c! \uD83D\uDE00";
      byte[] data = unicode.getBytes(StandardCharsets.UTF_8);
      Object result = ValueEncoder.decode(data, Protocol.TYPE_TEXT, DataTypes.TEXT);
      assertThat(result).isEqualTo(unicode);
    }

    @Test
    @DisplayName("should handle epoch date")
    void epochDate() {
      LocalDate epoch = LocalDate.of(1970, 1, 1);
      byte[] encoded = ValueEncoder.encode(epoch, DataTypes.DATE);
      Object decoded = ValueEncoder.decode(encoded, Protocol.TYPE_DATE, DataTypes.DATE);
      assertThat(decoded).isEqualTo(epoch);
    }

    @Test
    @DisplayName("should handle post-epoch date")
    void postEpochDate() {
      // Post-epoch dates like 2024 should work correctly
      LocalDate date2024 = LocalDate.of(2024, 6, 15);
      byte[] encoded = ValueEncoder.encode(date2024, DataTypes.DATE);
      // Verify encoding produces 4 bytes
      assertThat(encoded.length).isEqualTo(4);
      Object decoded = ValueEncoder.decode(encoded, Protocol.TYPE_DATE, DataTypes.DATE);
      assertThat(decoded).isEqualTo(date2024);
    }

    @Test
    @DisplayName("should handle midnight time")
    void midnightTime() {
      LocalTime midnight = LocalTime.MIDNIGHT;
      byte[] encoded = ValueEncoder.encode(midnight, DataTypes.TIME);
      Object decoded = ValueEncoder.decode(encoded, Protocol.TYPE_TIME, DataTypes.TIME);
      assertThat(decoded).isEqualTo(midnight);
    }

    @Test
    @DisplayName("should handle max time")
    void maxTime() {
      LocalTime maxTime = LocalTime.of(23, 59, 59, 999999999);
      byte[] encoded = ValueEncoder.encode(maxTime, DataTypes.TIME);
      Object decoded = ValueEncoder.decode(encoded, Protocol.TYPE_TIME, DataTypes.TIME);
      assertThat(decoded).isEqualTo(maxTime);
    }

    @Test
    @DisplayName("should handle negative varint")
    void negativeVarint() {
      BigInteger negative = new BigInteger("-123456789");
      byte[] encoded = ValueEncoder.encode(negative, DataTypes.VARINT);
      Object decoded = ValueEncoder.decode(encoded, Protocol.TYPE_VARINT, DataTypes.VARINT);
      assertThat(decoded).isEqualTo(negative);
    }

    @Test
    @DisplayName("should handle negative decimal")
    void negativeDecimal() {
      BigDecimal negative = new BigDecimal("-12345.67890");
      byte[] encoded = ValueEncoder.encode(negative, DataTypes.DECIMAL);
      Object decoded = ValueEncoder.decode(encoded, Protocol.TYPE_DECIMAL, DataTypes.DECIMAL);
      assertThat(decoded).isEqualTo(negative);
    }

    @Test
    @DisplayName("should handle float special values")
    void floatSpecialValues() {
      // NaN
      byte[] nanEncoded = ValueEncoder.encode(Float.NaN, DataTypes.FLOAT);
      Object nanDecoded = ValueEncoder.decode(nanEncoded, Protocol.TYPE_FLOAT, DataTypes.FLOAT);
      assertThat(Float.isNaN((Float) nanDecoded)).isTrue();

      // Positive infinity
      byte[] posInfEncoded = ValueEncoder.encode(Float.POSITIVE_INFINITY, DataTypes.FLOAT);
      Object posInfDecoded =
          ValueEncoder.decode(posInfEncoded, Protocol.TYPE_FLOAT, DataTypes.FLOAT);
      assertThat((Float) posInfDecoded).isEqualTo(Float.POSITIVE_INFINITY);

      // Negative infinity
      byte[] negInfEncoded = ValueEncoder.encode(Float.NEGATIVE_INFINITY, DataTypes.FLOAT);
      Object negInfDecoded =
          ValueEncoder.decode(negInfEncoded, Protocol.TYPE_FLOAT, DataTypes.FLOAT);
      assertThat((Float) negInfDecoded).isEqualTo(Float.NEGATIVE_INFINITY);
    }

    @Test
    @DisplayName("should handle double special values")
    void doubleSpecialValues() {
      // NaN
      byte[] nanEncoded = ValueEncoder.encode(Double.NaN, DataTypes.DOUBLE);
      Object nanDecoded = ValueEncoder.decode(nanEncoded, Protocol.TYPE_DOUBLE, DataTypes.DOUBLE);
      assertThat(Double.isNaN((Double) nanDecoded)).isTrue();

      // Positive infinity
      byte[] posInfEncoded = ValueEncoder.encode(Double.POSITIVE_INFINITY, DataTypes.DOUBLE);
      Object posInfDecoded =
          ValueEncoder.decode(posInfEncoded, Protocol.TYPE_DOUBLE, DataTypes.DOUBLE);
      assertThat((Double) posInfDecoded).isEqualTo(Double.POSITIVE_INFINITY);

      // Negative infinity
      byte[] negInfEncoded = ValueEncoder.encode(Double.NEGATIVE_INFINITY, DataTypes.DOUBLE);
      Object negInfDecoded =
          ValueEncoder.decode(negInfEncoded, Protocol.TYPE_DOUBLE, DataTypes.DOUBLE);
      assertThat((Double) negInfDecoded).isEqualTo(Double.NEGATIVE_INFINITY);
    }
  }

  @Nested
  @DisplayName("Deeply Nested Collections")
  class DeeplyNestedCollections {

    @Test
    @DisplayName("should handle list of lists of integers (2 levels)")
    void listOfListsOfInts() {
      // Create list<list<int>>
      java.util.List<java.util.List<Integer>> nested = java.util.List.of(
          java.util.List.of(1, 2, 3),
          java.util.List.of(4, 5, 6),
          java.util.List.of(7, 8, 9)
      );

      com.datastax.oss.driver.api.core.type.ListType innerType =
          DataTypes.listOf(DataTypes.INT);
      com.datastax.oss.driver.api.core.type.ListType outerType =
          DataTypes.listOf(innerType);

      byte[] encoded = ValueEncoder.encodeCollection(nested, outerType);
      assertThat(encoded).isNotNull();
      assertThat(encoded.length).isGreaterThan(0);
    }

    @Test
    @DisplayName("should handle map of string to list of integers (2 levels)")
    void mapOfStringToListOfInts() {
      // Create map<text, list<int>>
      java.util.Map<String, java.util.List<Integer>> nested = java.util.Map.of(
          "a", java.util.List.of(1, 2, 3),
          "b", java.util.List.of(4, 5, 6)
      );

      com.datastax.oss.driver.api.core.type.ListType listType =
          DataTypes.listOf(DataTypes.INT);
      com.datastax.oss.driver.api.core.type.MapType mapType =
          DataTypes.mapOf(DataTypes.TEXT, listType);

      byte[] encoded = ValueEncoder.encodeCollection(nested, mapType);
      assertThat(encoded).isNotNull();
      assertThat(encoded.length).isGreaterThan(0);
    }

    @Test
    @DisplayName("should handle list of maps of string to int (2 levels)")
    void listOfMapsOfStringToInt() {
      // Create list<map<text, int>>
      java.util.List<java.util.Map<String, Integer>> nested = java.util.List.of(
          java.util.Map.of("x", 1, "y", 2),
          java.util.Map.of("a", 10, "b", 20)
      );

      com.datastax.oss.driver.api.core.type.MapType mapType =
          DataTypes.mapOf(DataTypes.TEXT, DataTypes.INT);
      com.datastax.oss.driver.api.core.type.ListType listType =
          DataTypes.listOf(mapType);

      byte[] encoded = ValueEncoder.encodeCollection(nested, listType);
      assertThat(encoded).isNotNull();
      assertThat(encoded.length).isGreaterThan(0);
    }

    @Test
    @DisplayName("should handle set of lists of strings (2 levels)")
    void setOfListsOfStrings() {
      // Create set<list<text>>
      java.util.Set<java.util.List<String>> nested = java.util.Set.of(
          java.util.List.of("a", "b", "c"),
          java.util.List.of("x", "y", "z")
      );

      com.datastax.oss.driver.api.core.type.ListType listType =
          DataTypes.listOf(DataTypes.TEXT);
      com.datastax.oss.driver.api.core.type.SetType setType =
          DataTypes.setOf(listType);

      byte[] encoded = ValueEncoder.encodeCollection(nested, setType);
      assertThat(encoded).isNotNull();
      assertThat(encoded.length).isGreaterThan(0);
    }

    @Test
    @DisplayName("should handle list of list of list of integers (3 levels)")
    void threeNestedLists() {
      // Create list<list<list<int>>> - 3 levels deep
      java.util.List<java.util.List<java.util.List<Integer>>> nested = java.util.List.of(
          java.util.List.of(
              java.util.List.of(1, 2),
              java.util.List.of(3, 4)
          ),
          java.util.List.of(
              java.util.List.of(5, 6),
              java.util.List.of(7, 8)
          )
      );

      com.datastax.oss.driver.api.core.type.ListType level1 =
          DataTypes.listOf(DataTypes.INT);
      com.datastax.oss.driver.api.core.type.ListType level2 =
          DataTypes.listOf(level1);
      com.datastax.oss.driver.api.core.type.ListType level3 =
          DataTypes.listOf(level2);

      byte[] encoded = ValueEncoder.encodeCollection(nested, level3);
      assertThat(encoded).isNotNull();
      assertThat(encoded.length).isGreaterThan(0);
    }

    @Test
    @DisplayName("should handle map of string to map of string to list (3 levels)")
    void mapOfMapOfList() {
      // Create map<text, map<text, list<int>>> - 3 levels deep
      java.util.Map<String, java.util.Map<String, java.util.List<Integer>>> nested =
          java.util.Map.of(
              "outer1", java.util.Map.of(
                  "inner1", java.util.List.of(1, 2, 3),
                  "inner2", java.util.List.of(4, 5, 6)
              ),
              "outer2", java.util.Map.of(
                  "inner3", java.util.List.of(7, 8, 9)
              )
          );

      com.datastax.oss.driver.api.core.type.ListType listType =
          DataTypes.listOf(DataTypes.INT);
      com.datastax.oss.driver.api.core.type.MapType innerMapType =
          DataTypes.mapOf(DataTypes.TEXT, listType);
      com.datastax.oss.driver.api.core.type.MapType outerMapType =
          DataTypes.mapOf(DataTypes.TEXT, innerMapType);

      byte[] encoded = ValueEncoder.encodeCollection(nested, outerMapType);
      assertThat(encoded).isNotNull();
      assertThat(encoded.length).isGreaterThan(0);
    }

    @Test
    @DisplayName("should handle empty nested collections")
    void emptyNestedCollections() {
      // Empty list<list<int>>
      java.util.List<java.util.List<Integer>> empty = java.util.List.of();

      com.datastax.oss.driver.api.core.type.ListType innerType =
          DataTypes.listOf(DataTypes.INT);
      com.datastax.oss.driver.api.core.type.ListType outerType =
          DataTypes.listOf(innerType);

      byte[] encoded = ValueEncoder.encodeCollection(empty, outerType);
      assertThat(encoded).isNotNull();
      // Should just have the count (4 bytes with value 0)
      assertThat(encoded.length).isEqualTo(4);
    }

    @Test
    @DisplayName("should handle nested collection with empty inner collection")
    void nestedWithEmptyInner() {
      // list<list<int>> with one empty inner list
      java.util.List<java.util.List<Integer>> nested = java.util.List.of(
          java.util.List.of(1, 2, 3),
          java.util.List.of(), // empty
          java.util.List.of(4, 5)
      );

      com.datastax.oss.driver.api.core.type.ListType innerType =
          DataTypes.listOf(DataTypes.INT);
      com.datastax.oss.driver.api.core.type.ListType outerType =
          DataTypes.listOf(innerType);

      byte[] encoded = ValueEncoder.encodeCollection(nested, outerType);
      assertThat(encoded).isNotNull();
      assertThat(encoded.length).isGreaterThan(0);
    }
  }
}

package com.scylladb.latte;

import com.datastax.driver.core.DataType;
import com.datastax.driver.core.Duration;
import com.datastax.driver.core.TupleType;
import com.datastax.driver.core.TupleValue;
import com.datastax.driver.core.UDTValue;
import com.datastax.driver.core.UserType;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Encoder and decoder for CQL values in the IPC protocol (Driver 3.x version).
 *
 * <p>This class provides bidirectional conversion between wire format bytes and Java objects for
 * all CQL data types. It handles type coercion when the wire type differs from the target type.
 *
 * <p>Supported types include:
 *
 * <ul>
 *   <li>Primitive types: tinyint, smallint, int, bigint, float, double, boolean
 *   <li>Text types: text, ascii, varchar
 *   <li>Binary types: blob
 *   <li>Temporal types: timestamp, date, time, duration
 *   <li>Identifier types: uuid, timeuuid
 *   <li>Network types: inet
 *   <li>Numeric types: varint, decimal, counter
 *   <li>Collection types: list, set, map
 *   <li>Complex types: tuple, udt
 * </ul>
 *
 * <p>Note: Vector types are NOT supported in Driver 3.x.
 */
public class ValueEncoder {
  private static final Logger logger = LoggerFactory.getLogger(ValueEncoder.class);

  // Epoch for CQL date (days since 1970-01-01, with offset 2^31)
  private static final int DATE_EPOCH_OFFSET = Integer.MIN_VALUE;

  // Static map for O(1) type code lookup
  private static final Map<DataType.Name, Short> TYPE_CODE_MAP = new HashMap<>();
  private static final Map<DataType.Name, Class<?>> JAVA_TYPE_MAP = new HashMap<>();

  static {
    // Initialize type code map
    TYPE_CODE_MAP.put(DataType.Name.TINYINT, Protocol.TYPE_TINYINT);
    TYPE_CODE_MAP.put(DataType.Name.SMALLINT, Protocol.TYPE_SMALLINT);
    TYPE_CODE_MAP.put(DataType.Name.INT, Protocol.TYPE_INT);
    TYPE_CODE_MAP.put(DataType.Name.BIGINT, Protocol.TYPE_BIGINT);
    TYPE_CODE_MAP.put(DataType.Name.COUNTER, Protocol.TYPE_COUNTER);
    TYPE_CODE_MAP.put(DataType.Name.FLOAT, Protocol.TYPE_FLOAT);
    TYPE_CODE_MAP.put(DataType.Name.DOUBLE, Protocol.TYPE_DOUBLE);
    TYPE_CODE_MAP.put(DataType.Name.BOOLEAN, Protocol.TYPE_BOOLEAN);
    TYPE_CODE_MAP.put(DataType.Name.TEXT, Protocol.TYPE_TEXT);
    TYPE_CODE_MAP.put(DataType.Name.VARCHAR, Protocol.TYPE_TEXT);
    TYPE_CODE_MAP.put(DataType.Name.ASCII, Protocol.TYPE_ASCII);
    TYPE_CODE_MAP.put(DataType.Name.BLOB, Protocol.TYPE_BLOB);
    TYPE_CODE_MAP.put(DataType.Name.UUID, Protocol.TYPE_UUID);
    TYPE_CODE_MAP.put(DataType.Name.TIMEUUID, Protocol.TYPE_TIMEUUID);
    TYPE_CODE_MAP.put(DataType.Name.TIMESTAMP, Protocol.TYPE_TIMESTAMP);
    TYPE_CODE_MAP.put(DataType.Name.DATE, Protocol.TYPE_DATE);
    TYPE_CODE_MAP.put(DataType.Name.TIME, Protocol.TYPE_TIME);
    TYPE_CODE_MAP.put(DataType.Name.INET, Protocol.TYPE_INET);
    TYPE_CODE_MAP.put(DataType.Name.VARINT, Protocol.TYPE_VARINT);
    TYPE_CODE_MAP.put(DataType.Name.DECIMAL, Protocol.TYPE_DECIMAL);
    TYPE_CODE_MAP.put(DataType.Name.DURATION, Protocol.TYPE_DURATION);
    TYPE_CODE_MAP.put(DataType.Name.LIST, Protocol.TYPE_LIST);
    TYPE_CODE_MAP.put(DataType.Name.SET, Protocol.TYPE_SET);
    TYPE_CODE_MAP.put(DataType.Name.MAP, Protocol.TYPE_MAP);
    TYPE_CODE_MAP.put(DataType.Name.TUPLE, Protocol.TYPE_TUPLE);
    TYPE_CODE_MAP.put(DataType.Name.UDT, Protocol.TYPE_UDT);

    // Initialize Java type map
    JAVA_TYPE_MAP.put(DataType.Name.TEXT, String.class);
    JAVA_TYPE_MAP.put(DataType.Name.VARCHAR, String.class);
    JAVA_TYPE_MAP.put(DataType.Name.ASCII, String.class);
    JAVA_TYPE_MAP.put(DataType.Name.INT, Integer.class);
    JAVA_TYPE_MAP.put(DataType.Name.BIGINT, Long.class);
    JAVA_TYPE_MAP.put(DataType.Name.COUNTER, Long.class);
    JAVA_TYPE_MAP.put(DataType.Name.SMALLINT, Short.class);
    JAVA_TYPE_MAP.put(DataType.Name.TINYINT, Byte.class);
    JAVA_TYPE_MAP.put(DataType.Name.FLOAT, Float.class);
    JAVA_TYPE_MAP.put(DataType.Name.DOUBLE, Double.class);
    JAVA_TYPE_MAP.put(DataType.Name.BOOLEAN, Boolean.class);
    JAVA_TYPE_MAP.put(DataType.Name.UUID, UUID.class);
    JAVA_TYPE_MAP.put(DataType.Name.TIMEUUID, UUID.class);
    JAVA_TYPE_MAP.put(DataType.Name.BLOB, ByteBuffer.class);
    JAVA_TYPE_MAP.put(DataType.Name.TIMESTAMP, Date.class);
    JAVA_TYPE_MAP.put(DataType.Name.DATE, com.datastax.driver.core.LocalDate.class);
    JAVA_TYPE_MAP.put(DataType.Name.TIME, Long.class);
    JAVA_TYPE_MAP.put(DataType.Name.INET, InetAddress.class);
    JAVA_TYPE_MAP.put(DataType.Name.VARINT, BigInteger.class);
    JAVA_TYPE_MAP.put(DataType.Name.DECIMAL, BigDecimal.class);
    JAVA_TYPE_MAP.put(DataType.Name.DURATION, Duration.class);
  }

  private ValueEncoder() {}

  /**
   * Decode a value from wire format using the target type.
   *
   * @param data the raw bytes in wire format, or null for a null value
   * @param wireType the CQL type code of the wire format
   * @param targetType the target CQL DataType to decode into
   * @return the decoded Java object, or null if data is null
   */
  public static Object decode(byte[] data, short wireType, DataType targetType) {
    if (data == null) {
      return null;
    }

    // Use pooled reader to reduce allocations
    Protocol.BytesReader reader = Protocol.BytesReaderPool.acquire(data);
    try {
      return decodeValue(reader, data.length, wireType, targetType);
    } finally {
      Protocol.BytesReaderPool.release(reader);
    }
  }

  private static Object decodeValue(
      Protocol.BytesReader reader, int length, short wireType, DataType targetType) {
    if (length < 0) {
      return null;
    }

    // Handle special cases based on wire type
    switch (wireType) {
      case Protocol.TYPE_LIST:
        return decodeList(reader, targetType);
      case Protocol.TYPE_SET:
        return decodeSet(reader, targetType);
      case Protocol.TYPE_MAP:
        return decodeMap(reader, targetType);
      case Protocol.TYPE_VECTOR:
        // Decode vector as raw CQL bytes for binding with setBytesUnsafe()
        return decodeVector(reader, length);
      case Protocol.TYPE_PACKED_FLOAT_VECTOR_LIST:
        // Decode packed list of float vectors
        return decodePackedFloatVectorList(reader);
      case Protocol.TYPE_TUPLE:
        return decodeTuple(reader, targetType);
      case Protocol.TYPE_UDT:
        return decodeUdt(reader, targetType);
      default:
        return decodePrimitive(reader, length, wireType, targetType);
    }
  }

  private static Object decodePrimitive(
      Protocol.BytesReader reader, int length, short wireType, DataType targetType) {
    Object value = decodeByWireType(reader, length, wireType);
    return coerceValue(value, wireType, targetType);
  }

  private static Object decodeByWireType(Protocol.BytesReader reader, int length, short wireType) {
    switch (wireType) {
      case Protocol.TYPE_TINYINT:
        return reader.readByte();
      case Protocol.TYPE_SMALLINT:
        return reader.readShort();
      case Protocol.TYPE_INT:
        return reader.readInt();
      case Protocol.TYPE_BIGINT:
      case Protocol.TYPE_COUNTER:
        return reader.readLong();
      case Protocol.TYPE_FLOAT:
        return Float.intBitsToFloat(reader.readInt());
      case Protocol.TYPE_DOUBLE:
        return Double.longBitsToDouble(reader.readLong());
      case Protocol.TYPE_BOOLEAN:
        return reader.readByte() != 0;
      case Protocol.TYPE_ASCII:
      case Protocol.TYPE_TEXT:
        return new String(reader.readBytes(length), StandardCharsets.UTF_8);
      case Protocol.TYPE_BLOB:
        return ByteBuffer.wrap(reader.readBytes(length));
      case Protocol.TYPE_UUID:
      case Protocol.TYPE_TIMEUUID:
        return decodeUuid(reader);
      case Protocol.TYPE_TIMESTAMP:
        // Driver 3.x uses java.util.Date for timestamps
        return new Date(reader.readLong());
      case Protocol.TYPE_DATE:
        return decodeDate(reader);
      case Protocol.TYPE_TIME:
        // Driver 3.x uses long (nanoseconds) for time
        return reader.readLong();
      case Protocol.TYPE_INET:
        return decodeInet(reader, length);
      case Protocol.TYPE_VARINT:
        return new BigInteger(reader.readBytes(length));
      case Protocol.TYPE_DECIMAL:
        return decodeDecimal(reader, length);
      case Protocol.TYPE_DURATION:
        return decodeDuration(reader);
      default:
        return ByteBuffer.wrap(reader.readBytes(length));
    }
  }

  private static UUID decodeUuid(Protocol.BytesReader reader) {
    long msb = reader.readLong();
    long lsb = reader.readLong();
    return new UUID(msb, lsb);
  }

  private static com.datastax.driver.core.LocalDate decodeDate(Protocol.BytesReader reader) {
    int days = reader.readInt();
    // CQL date is unsigned int, days since epoch with offset
    long daysFromEpoch = ((long) days) - ((long) DATE_EPOCH_OFFSET);
    // Driver 3.x uses its own LocalDate class
    return com.datastax.driver.core.LocalDate.fromDaysSinceEpoch((int) daysFromEpoch);
  }

  private static InetAddress decodeInet(Protocol.BytesReader reader, int length) {
    try {
      return InetAddress.getByAddress(reader.readBytes(length));
    } catch (UnknownHostException e) {
      throw new Protocol.ProtocolException("Invalid IP address", e);
    }
  }

  private static BigDecimal decodeDecimal(Protocol.BytesReader reader, int length) {
    int scale = reader.readInt();
    byte[] unscaledBytes = reader.readBytes(length - 4);
    BigInteger unscaled = new BigInteger(unscaledBytes);
    return new BigDecimal(unscaled, scale);
  }

  private static Duration decodeDuration(Protocol.BytesReader reader) {
    int months = (int) decodeVarInt(reader);
    int days = (int) decodeVarInt(reader);
    long nanos = decodeVarInt(reader);
    return Duration.newInstance(months, days, nanos);
  }

  private static long decodeVarInt(Protocol.BytesReader reader) {
    long value = 0;
    int shift = 0;
    byte b;
    do {
      b = reader.readByte();
      value |= (long) (b & 0x7F) << shift;
      shift += 7;
    } while ((b & 0x80) != 0);
    // Zigzag decode
    return (value >>> 1) ^ -(value & 1);
  }

  /**
   * Decode a vector from wire format.
   *
   * <p>Wire format: [elementType: u16] [dimension: u16] [floats: dim*4 bytes]
   * Returns a ByteBuffer containing the raw CQL vector bytes (just the floats).
   */
  private static ByteBuffer decodeVector(Protocol.BytesReader reader, int totalLength) {
    short elementType = reader.readShort();
    int dimension = reader.readUShort();

    if (elementType == Protocol.TYPE_FLOAT) {
      // Bulk read float bytes directly - no need for manual byte-by-byte copying
      // The wire format is already big-endian which is what CQL expects
      int byteCount = dimension * 4;
      byte[] vectorBytes = reader.readBytes(byteCount);
      return ByteBuffer.wrap(vectorBytes);
    }

    // Unknown element type - skip remaining bytes
    int remainingBytes = totalLength - 4; // already read 4 bytes (elementType + dimension)
    if (remainingBytes > 0) {
      reader.skip(remainingBytes);
    }
    return null;
  }

  /**
   * Decode a packed list of float vectors.
   *
   * <p>Format: [n_elements: i32] [dimension: u16] [packed_floats: n*dim*4 bytes]
   * Each element is returned as a ByteBuffer containing raw CQL vector bytes.
   */
  private static List<ByteBuffer> decodePackedFloatVectorList(Protocol.BytesReader reader) {
    int nElements = reader.readInt();
    int dimension = reader.readUShort();
    int vectorByteCount = dimension * 4;

    List<ByteBuffer> list = new ArrayList<>(nElements);
    for (int i = 0; i < nElements; i++) {
      // Bulk read vector bytes directly
      byte[] vectorBytes = reader.readBytes(vectorByteCount);
      list.add(ByteBuffer.wrap(vectorBytes));
    }
    return list;
  }

  private static List<?> decodeList(Protocol.BytesReader reader, DataType targetType) {
    short elementType = reader.readShort();

    // Check if elements are vectors
    short vectorElementType = 0;
    int vectorDimension = 0;
    if (elementType == Protocol.TYPE_VECTOR) {
      vectorElementType = reader.readShort();
      vectorDimension = reader.readUShort();
    }

    int count = reader.readInt();
    List<Object> list = new ArrayList<>(count);

    DataType elementTargetType = DataType.blob();
    if (targetType.getName() == DataType.Name.LIST || targetType.getName() == DataType.Name.SET) {
      List<DataType> typeArgs = targetType.getTypeArguments();
      if (!typeArgs.isEmpty()) {
        elementTargetType = typeArgs.get(0);
      }
    }

    for (int i = 0; i < count; i++) {
      int len = reader.readInt();
      if (len < 0) {
        list.add(null);
      } else if (elementType == Protocol.TYPE_VECTOR && vectorElementType == Protocol.TYPE_FLOAT) {
        // Bulk read vector element as raw CQL bytes
        int byteCount = Math.min(vectorDimension * 4, len);
        byte[] vectorBytes = reader.readBytes(byteCount);
        list.add(ByteBuffer.wrap(vectorBytes));
      } else {
        byte[] elemData = reader.readBytes(len);
        Object value = decode(elemData, elementType, elementTargetType);
        list.add(coerceCollectionElement(value, elementTargetType));
      }
    }

    return list;
  }

  private static Set<?> decodeSet(Protocol.BytesReader reader, DataType targetType) {
    short elementType = reader.readShort();
    int count = reader.readInt();
    Set<Object> set = new HashSet<>(count);

    DataType elementTargetType = DataType.blob();
    if (targetType.getName() == DataType.Name.SET) {
      List<DataType> typeArgs = targetType.getTypeArguments();
      if (!typeArgs.isEmpty()) {
        elementTargetType = typeArgs.get(0);
      }
    }

    for (int i = 0; i < count; i++) {
      int len = reader.readInt();
      if (len >= 0) {
        byte[] elemData = reader.readBytes(len);
        Object value = decode(elemData, elementType, elementTargetType);
        set.add(coerceCollectionElement(value, elementTargetType));
      }
    }

    return set;
  }

  private static Map<?, ?> decodeMap(Protocol.BytesReader reader, DataType targetType) {
    short keyType = reader.readShort();
    short valueType = reader.readShort();
    int count = reader.readInt();
    Map<Object, Object> map = new HashMap<>(count);

    DataType keyTargetType = DataType.blob();
    DataType valueTargetType = DataType.blob();
    if (targetType.getName() == DataType.Name.MAP) {
      List<DataType> typeArgs = targetType.getTypeArguments();
      if (typeArgs.size() >= 2) {
        keyTargetType = typeArgs.get(0);
        valueTargetType = typeArgs.get(1);
      }
    }

    for (int i = 0; i < count; i++) {
      int keyLen = reader.readInt();
      byte[] keyData = keyLen >= 0 ? reader.readBytes(keyLen) : null;
      int valLen = reader.readInt();
      byte[] valData = valLen >= 0 ? reader.readBytes(valLen) : null;

      Object key = keyData != null ? decode(keyData, keyType, keyTargetType) : null;
      Object value = valData != null ? decode(valData, valueType, valueTargetType) : null;

      if (key != null) {
        map.put(
            coerceCollectionElement(key, keyTargetType),
            value != null ? coerceCollectionElement(value, valueTargetType) : null);
      }
    }

    return map;
  }

  private static TupleValue decodeTuple(Protocol.BytesReader reader, DataType targetType) {
    int elementCount = reader.readUShort();
    short[] elementTypes = new short[elementCount];
    for (int i = 0; i < elementCount; i++) {
      elementTypes[i] = reader.readShort();
    }

    if (!(targetType instanceof TupleType)) {
      throw new Protocol.ProtocolException("Target type is not a tuple: " + targetType);
    }

    TupleType tupleType = (TupleType) targetType;
    TupleValue tuple = tupleType.newValue();
    List<DataType> componentTypes = tupleType.getComponentTypes();

    for (int i = 0; i < elementCount && i < componentTypes.size(); i++) {
      int len = reader.readInt();
      if (len < 0) {
        tuple.setToNull(i);
      } else {
        byte[] elemData = reader.readBytes(len);
        Object value = decode(elemData, elementTypes[i], componentTypes.get(i));
        setTupleElement(tuple, i, value, componentTypes.get(i));
      }
    }

    return tuple;
  }

  private static UDTValue decodeUdt(Protocol.BytesReader reader, DataType targetType) {
    int fieldCount = reader.readUShort();
    String[] fieldNames = new String[fieldCount];
    short[] fieldTypes = new short[fieldCount];

    for (int i = 0; i < fieldCount; i++) {
      fieldNames[i] = reader.readString();
      fieldTypes[i] = reader.readShort();
    }

    if (!(targetType instanceof UserType)) {
      logger.debug("Target type is not a UDT: {}, returning null", targetType);
      for (int i = 0; i < fieldCount; i++) {
        int len = reader.readInt();
        if (len >= 0) {
          reader.skip(len);
        }
      }
      return null;
    }

    UserType udtType = (UserType) targetType;
    UDTValue udt = udtType.newValue();

    for (int i = 0; i < fieldCount; i++) {
      int len = reader.readInt();
      if (len < 0) {
        if (udtType.contains(fieldNames[i])) {
          udt.setToNull(fieldNames[i]);
        }
      } else {
        byte[] fieldData = reader.readBytes(len);
        if (udtType.contains(fieldNames[i])) {
          DataType fieldTargetType = udtType.getFieldType(fieldNames[i]);
          if (fieldTargetType != null) {
            Object value = decode(fieldData, fieldTypes[i], fieldTargetType);
            setUdtField(udt, fieldNames[i], value, fieldTargetType);
          }
        }
      }
    }

    return udt;
  }

  @SuppressWarnings("unchecked")
  private static void setTupleElement(TupleValue tuple, int index, Object value, DataType type) {
    if (value == null) {
      tuple.setToNull(index);
      return;
    }

    DataType.Name typeName = type.getName();
    switch (typeName) {
      case TINYINT:
        tuple.setByte(index, ((Number) value).byteValue());
        break;
      case SMALLINT:
        tuple.setShort(index, ((Number) value).shortValue());
        break;
      case INT:
        tuple.setInt(index, ((Number) value).intValue());
        break;
      case BIGINT:
      case COUNTER:
        tuple.setLong(index, ((Number) value).longValue());
        break;
      case FLOAT:
        tuple.setFloat(index, ((Number) value).floatValue());
        break;
      case DOUBLE:
        tuple.setDouble(index, ((Number) value).doubleValue());
        break;
      case BOOLEAN:
        tuple.setBool(index, (Boolean) value);
        break;
      case TEXT:
      case VARCHAR:
      case ASCII:
        tuple.setString(index, (String) value);
        break;
      case BLOB:
        tuple.setBytes(index, (ByteBuffer) value);
        break;
      case UUID:
      case TIMEUUID:
        tuple.setUUID(index, (UUID) value);
        break;
      case TIMESTAMP:
        if (value instanceof Date) {
          tuple.setTimestamp(index, (Date) value);
        } else if (value instanceof Instant) {
          tuple.setTimestamp(index, Date.from((Instant) value));
        }
        break;
      case DATE:
        if (value instanceof com.datastax.driver.core.LocalDate) {
          tuple.setDate(index, (com.datastax.driver.core.LocalDate) value);
        }
        break;
      case TIME:
        tuple.setTime(index, ((Number) value).longValue());
        break;
      case INET:
        tuple.setInet(index, (InetAddress) value);
        break;
      case VARINT:
        tuple.setVarint(index, (BigInteger) value);
        break;
      case DECIMAL:
        tuple.setDecimal(index, (BigDecimal) value);
        break;
      case DURATION:
        tuple.set(index, (Duration) value, Duration.class);
        break;
      case LIST:
        tuple.setList(index, (List<Object>) value);
        break;
      case SET:
        if (value instanceof List) {
          tuple.setSet(index, new HashSet<>((List<Object>) value));
        } else {
          tuple.setSet(index, (Set<Object>) value);
        }
        break;
      case MAP:
        tuple.setMap(index, (Map<Object, Object>) value);
        break;
      case TUPLE:
        tuple.setTupleValue(index, (TupleValue) value);
        break;
      case UDT:
        tuple.setUDTValue(index, (UDTValue) value);
        break;
      default:
        // Fallback
        tuple.set(index, value, Object.class);
    }
  }

  @SuppressWarnings("unchecked")
  private static void setUdtField(UDTValue udt, String field, Object value, DataType type) {
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
        } else if (value instanceof Instant) {
          udt.setTimestamp(field, Date.from((Instant) value));
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

  /** Coerce a value from wire type to target type. */
  private static Object coerceValue(Object value, short wireType, DataType targetType) {
    if (value == null) {
      return null;
    }

    DataType.Name targetName = targetType.getName();

    // Handle integer type coercion
    if (value instanceof Long) {
      long longVal = (Long) value;
      if (targetName == DataType.Name.INT) {
        return (int) longVal;
      }
      if (targetName == DataType.Name.SMALLINT) {
        return (short) longVal;
      }
      if (targetName == DataType.Name.TINYINT) {
        return (byte) longVal;
      }
      if (targetName == DataType.Name.VARINT) {
        return BigInteger.valueOf(longVal);
      }
      if (targetName == DataType.Name.DECIMAL) {
        return BigDecimal.valueOf(longVal);
      }
      if (targetName == DataType.Name.TIMESTAMP) {
        return new Date(longVal);
      }
      if (targetName == DataType.Name.TIME) {
        return longVal; // Time is stored as nanos in 3.x
      }
    }

    // Handle double to float coercion
    if (value instanceof Double) {
      double doubleVal = (Double) value;
      if (targetName == DataType.Name.FLOAT) {
        return (float) doubleVal;
      }
    }

    // Handle string coercions
    if (value instanceof String) {
      String strVal = (String) value;
      if (targetName == DataType.Name.DATE) {
        LocalDate ld = LocalDate.parse(strVal, DateTimeFormatter.ISO_LOCAL_DATE);
        return com.datastax.driver.core.LocalDate.fromYearMonthDay(
            ld.getYear(), ld.getMonthValue(), ld.getDayOfMonth());
      }
      if (targetName == DataType.Name.TIME) {
        LocalTime lt = parseFlexibleTime(strVal);
        return lt.toNanoOfDay();
      }
      if (targetName == DataType.Name.DURATION) {
        return parseDuration(strVal);
      }
      if (targetName == DataType.Name.INET) {
        try {
          return InetAddress.getByName(strVal);
        } catch (UnknownHostException e) {
          throw new Protocol.ProtocolException("Invalid IP address: " + strVal, e);
        }
      }
      if (targetName == DataType.Name.TIMEUUID || targetName == DataType.Name.UUID) {
        return UUID.fromString(strVal);
      }
      if (targetName == DataType.Name.DECIMAL) {
        return new BigDecimal(strVal);
      }
    }

    // Handle list to set coercion
    if (value instanceof List && targetName == DataType.Name.SET) {
      return new HashSet<>((List<?>) value);
    }

    return value;
  }

  private static Object coerceCollectionElement(Object value, DataType targetType) {
    if (value == null) {
      return null;
    }

    // Handle list to set conversion
    if (value instanceof List && targetType.getName() == DataType.Name.SET) {
      return new HashSet<>((List<?>) value);
    }

    return value;
  }

  private static LocalTime parseFlexibleTime(String str) {
    String[] parts = str.split("\\.");
    String timePart = parts[0];
    String nanoPart = parts.length > 1 ? parts[1] : null;

    String[] timeParts = timePart.split(":");
    int hour = Integer.parseInt(timeParts[0]);
    int minute = timeParts.length > 1 ? Integer.parseInt(timeParts[1]) : 0;
    int second = timeParts.length > 2 ? Integer.parseInt(timeParts[2]) : 0;
    int nano = 0;

    if (nanoPart != null) {
      // Use integer math instead of String.repeat to avoid allocation
      int nanoPartLen = nanoPart.length();
      if (nanoPartLen <= 9) {
        nano = Integer.parseInt(nanoPart);
        // Multiply to scale to 9 digits (nanoseconds)
        for (int i = nanoPartLen; i < 9; i++) {
          nano *= 10;
        }
      } else {
        // Truncate to 9 digits
        nano = Integer.parseInt(nanoPart.substring(0, 9));
      }
    }

    return LocalTime.of(hour, minute, second, nano);
  }

  private static Duration parseDuration(String str) {
    int months = 0, days = 0;
    long nanos = 0;

    int i = 0;
    int len = str.length();
    StringBuilder unitBuilder = new StringBuilder(4);

    while (i < len) {
      int start = i;
      while (i < len && (Character.isDigit(str.charAt(i)) || str.charAt(i) == '-')) {
        i++;
      }
      if (i == start) {
        i++;
        continue;
      }

      long num = Long.parseLong(str.substring(start, i));
      unitBuilder.setLength(0);
      while (i < len && Character.isLetter(str.charAt(i))) {
        unitBuilder.append(Character.toLowerCase(str.charAt(i)));
        i++;
      }

      String unit = unitBuilder.toString();
      switch (unit) {
        case "mo":
          months += num;
          break;
        case "d":
          days += num;
          break;
        case "h":
          nanos += num * 3600_000_000_000L;
          break;
        case "m":
          nanos += num * 60_000_000_000L;
          break;
        case "s":
          nanos += num * 1_000_000_000L;
          break;
        case "ms":
          nanos += num * 1_000_000L;
          break;
        case "us":
          nanos += num * 1_000L;
          break;
        case "ns":
          nanos += num;
          break;
        default:
          // ignore unknown units
      }
    }

    return Duration.newInstance(months, days, nanos);
  }

  /**
   * Encode a value to wire format.
   *
   * @param value the Java object to encode, or null for a null value
   * @param type the CQL DataType determining the encoding format
   * @return the encoded bytes in wire format, or null if value is null
   */
  public static byte[] encode(Object value, DataType type) {
    if (value == null) {
      return null;
    }

    Protocol.BytesWriter writer = Protocol.BytesWriterPool.acquire();
    try {
      encodeValue(writer, value, type);
      return writer.toByteArray();
    } finally {
      Protocol.BytesWriterPool.release(writer);
    }
  }

  /**
   * Encode a value directly to a writer, avoiding intermediate byte[] allocation.
   *
   * <p>Writes the value in the standard CQL bytes format: [length: i32][data: bytes]
   * where length is -1 for null values.
   *
   * @param writer the writer to write to
   * @param value the Java object to encode, or null for a null value
   * @param type the CQL DataType determining the encoding format
   */
  public static void encodeTo(Protocol.BytesWriter writer, Object value, DataType type) {
    if (value == null) {
      writer.writeInt(-1);
      return;
    }

    // Encode to a temporary writer to get the length
    Protocol.BytesWriter tempWriter = Protocol.BytesWriterPool.acquire();
    try {
      encodeValue(tempWriter, value, type);
      byte[] encoded = tempWriter.toByteArray();
      writer.writeInt(encoded.length);
      writer.writeBytes(encoded);
    } finally {
      Protocol.BytesWriterPool.release(tempWriter);
    }
  }

  /**
   * Encode a value directly to a PooledBytesWriter for zero-copy ByteBuf responses.
   *
   * <p>Writes the value in the standard CQL bytes format: [length: i32][data: bytes]
   * where length is -1 for null values.
   *
   * @param writer the pooled writer to write to
   * @param value the Java object to encode, or null for a null value
   * @param type the CQL DataType determining the encoding format
   */
  public static void encodeTo(Protocol.PooledBytesWriter writer, Object value, DataType type) {
    if (value == null) {
      writer.writeInt(-1);
      return;
    }

    // For PooledBytesWriter, we need to know the length before writing
    // Use the regular encode method and write the result
    byte[] encoded = encode(value, type);
    if (encoded == null) {
      writer.writeInt(-1);
    } else {
      writer.writeInt(encoded.length);
      writer.writeBytes(encoded);
    }
  }

  @SuppressWarnings("unchecked")
  private static void encodeValue(Protocol.BytesWriter writer, Object value, DataType type) {
    if (value == null) {
      return;
    }

    DataType.Name typeName = type.getName();
    switch (typeName) {
      case TINYINT:
        writer.writeByte(((Number) value).byteValue());
        break;
      case SMALLINT:
        writer.writeShort(((Number) value).shortValue());
        break;
      case INT:
        writer.writeInt(((Number) value).intValue());
        break;
      case BIGINT:
      case COUNTER:
        writer.writeLong(((Number) value).longValue());
        break;
      case FLOAT:
        writer.writeInt(Float.floatToIntBits(((Number) value).floatValue()));
        break;
      case DOUBLE:
        writer.writeLong(Double.doubleToLongBits(((Number) value).doubleValue()));
        break;
      case BOOLEAN:
        writer.writeByte((Boolean) value ? 1 : 0);
        break;
      case TEXT:
      case VARCHAR:
      case ASCII:
        byte[] strBytes = ((String) value).getBytes(StandardCharsets.UTF_8);
        writer.writeBytes(strBytes);
        break;
      case BLOB:
        ByteBuffer buf = (ByteBuffer) value;
        byte[] blobBytes = new byte[buf.remaining()];
        buf.duplicate().get(blobBytes);
        writer.writeBytes(blobBytes);
        break;
      case UUID:
      case TIMEUUID:
        UUID uuid = (UUID) value;
        writer.writeLong(uuid.getMostSignificantBits());
        writer.writeLong(uuid.getLeastSignificantBits());
        break;
      case TIMESTAMP:
        if (value instanceof Date) {
          writer.writeLong(((Date) value).getTime());
        } else if (value instanceof Instant) {
          writer.writeLong(((Instant) value).toEpochMilli());
        }
        break;
      case DATE:
        if (value instanceof com.datastax.driver.core.LocalDate) {
          com.datastax.driver.core.LocalDate ld = (com.datastax.driver.core.LocalDate) value;
          long days = ld.getDaysSinceEpoch() + (long) DATE_EPOCH_OFFSET;
          writer.writeInt((int) days);
        }
        break;
      case TIME:
        writer.writeLong(((Number) value).longValue());
        break;
      case INET:
        byte[] inetBytes = ((InetAddress) value).getAddress();
        writer.writeBytes(inetBytes);
        break;
      case VARINT:
        byte[] varintBytes = ((BigInteger) value).toByteArray();
        writer.writeBytes(varintBytes);
        break;
      case DECIMAL:
        BigDecimal dec = (BigDecimal) value;
        writer.writeInt(dec.scale());
        byte[] decBytes = dec.unscaledValue().toByteArray();
        writer.writeBytes(decBytes);
        break;
      case DURATION:
        Duration dur = (Duration) value;
        encodeVarInt(writer, dur.getMonths());
        encodeVarInt(writer, dur.getDays());
        encodeVarInt(writer, dur.getNanoseconds());
        break;
      case LIST:
        List<DataType> listArgs = type.getTypeArguments();
        DataType listElemType = listArgs.isEmpty() ? DataType.blob() : listArgs.get(0);
        encodeList(writer, (List<?>) value, listElemType);
        break;
      case SET:
        List<DataType> setArgs = type.getTypeArguments();
        DataType setElemType = setArgs.isEmpty() ? DataType.blob() : setArgs.get(0);
        encodeSet(writer, (Set<?>) value, setElemType);
        break;
      case MAP:
        List<DataType> mapArgs = type.getTypeArguments();
        DataType mapKeyType = mapArgs.size() > 0 ? mapArgs.get(0) : DataType.blob();
        DataType mapValType = mapArgs.size() > 1 ? mapArgs.get(1) : DataType.blob();
        encodeMap(writer, (Map<?, ?>) value, mapKeyType, mapValType);
        break;
      case TUPLE:
        encodeTuple(writer, (TupleValue) value, (TupleType) type);
        break;
      case UDT:
        encodeUdt(writer, (UDTValue) value, (UserType) type);
        break;
      default:
        // Unknown type - skip
        break;
    }
  }

  private static void encodeList(Protocol.BytesWriter writer, List<?> list, DataType elementType) {
    writer.writeInt(list.size());
    for (Object elem : list) {
      byte[] encoded = encode(elem, elementType);
      writer.writeBytesNullable(encoded);
    }
  }

  private static void encodeSet(Protocol.BytesWriter writer, Set<?> set, DataType elementType) {
    writer.writeInt(set.size());
    for (Object elem : set) {
      byte[] encoded = encode(elem, elementType);
      writer.writeBytesNullable(encoded);
    }
  }

  private static void encodeMap(
      Protocol.BytesWriter writer, Map<?, ?> map, DataType keyType, DataType valueType) {
    writer.writeInt(map.size());
    for (Map.Entry<?, ?> entry : map.entrySet()) {
      byte[] keyEncoded = encode(entry.getKey(), keyType);
      byte[] valEncoded = encode(entry.getValue(), valueType);
      writer.writeBytesNullable(keyEncoded);
      writer.writeBytesNullable(valEncoded);
    }
  }

  private static void encodeTuple(
      Protocol.BytesWriter writer, TupleValue tuple, TupleType tupleType) {
    List<DataType> types = tupleType.getComponentTypes();
    for (int i = 0; i < types.size(); i++) {
      Object value = tuple.getObject(i);
      byte[] encoded = encode(value, types.get(i));
      writer.writeBytesNullable(encoded);
    }
  }

  private static void encodeUdt(Protocol.BytesWriter writer, UDTValue udt, UserType udtType) {
    List<String> fieldNames = new ArrayList<>(udtType.getFieldNames());
    for (int i = 0; i < fieldNames.size(); i++) {
      Object value = udt.getObject(i);
      DataType fieldType = udtType.getFieldType(fieldNames.get(i));
      byte[] encoded = encode(value, fieldType);
      writer.writeBytesNullable(encoded);
    }
  }

  private static void encodeVarInt(Protocol.BytesWriter writer, long value) {
    // Zigzag encode
    long encoded = (value << 1) ^ (value >> 63);
    while ((encoded & ~0x7FL) != 0) {
      writer.writeByte((int) ((encoded & 0x7F) | 0x80));
      encoded >>>= 7;
    }
    writer.writeByte((int) encoded);
  }

  /**
   * Encode a collection (List, Set, or Map) to CQL binary wire format.
   *
   * @param collection the collection to encode (List, Set, or Map)
   * @param type the CQL collection type
   * @return the encoded bytes in CQL binary format
   */
  public static byte[] encodeCollection(Object collection, DataType type) {
    Protocol.BytesWriter writer = Protocol.BytesWriterPool.acquire();
    try {
      DataType.Name typeName = type.getName();
      List<DataType> typeArgs = type.getTypeArguments();

      if (typeName == DataType.Name.LIST) {
        List<?> list = (List<?>) collection;
        DataType elemType = typeArgs.isEmpty() ? DataType.blob() : typeArgs.get(0);
        encodeList(writer, list, elemType);
      } else if (typeName == DataType.Name.SET) {
        Set<?> set = (Set<?>) collection;
        DataType elemType = typeArgs.isEmpty() ? DataType.blob() : typeArgs.get(0);
        encodeSet(writer, set, elemType);
      } else if (typeName == DataType.Name.MAP) {
        Map<?, ?> map = (Map<?, ?>) collection;
        DataType keyType = typeArgs.size() > 0 ? typeArgs.get(0) : DataType.blob();
        DataType valType = typeArgs.size() > 1 ? typeArgs.get(1) : DataType.blob();
        encodeMap(writer, map, keyType, valType);
      } else {
        throw new IllegalArgumentException("Not a collection type: " + type);
      }
      return writer.toByteArray();
    } finally {
      Protocol.BytesWriterPool.release(writer);
    }
  }

  /**
   * Get the protocol type code for a DataType.
   *
   * @param type the CQL DataType to get the type code for
   * @return the protocol type code, or TYPE_BLOB for unknown types
   */
  public static short getTypeCode(DataType type) {
    Short code = TYPE_CODE_MAP.get(type.getName());
    if (code != null) {
      return code;
    }
    return Protocol.TYPE_BLOB;
  }

  /**
   * Get the Java class corresponding to a CQL DataType for codec resolution.
   *
   * @param type the CQL DataType
   * @return the corresponding Java class
   */
  @SuppressWarnings("unchecked")
  static <T> Class<T> getJavaTypeForDataType(DataType type) {
    Class<?> javaType = JAVA_TYPE_MAP.get(type.getName());
    if (javaType != null) {
      return (Class<T>) javaType;
    }

    DataType.Name typeName = type.getName();
    if (typeName == DataType.Name.UDT) {
      return (Class<T>) UDTValue.class;
    }
    if (typeName == DataType.Name.TUPLE) {
      return (Class<T>) TupleValue.class;
    }
    if (typeName == DataType.Name.LIST) {
      return (Class<T>) List.class;
    }
    if (typeName == DataType.Name.SET) {
      return (Class<T>) Set.class;
    }
    if (typeName == DataType.Name.MAP) {
      return (Class<T>) Map.class;
    }

    return (Class<T>) Object.class;
  }
}

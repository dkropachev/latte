package com.scylladb.latte;

import com.datastax.oss.driver.api.core.CqlIdentifier;
import com.datastax.oss.driver.api.core.data.CqlDuration;
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
import java.net.UnknownHostException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Encoder and decoder for CQL values in the IPC protocol.
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
 *   <li>Complex types: tuple, udt, vector
 * </ul>
 */
public class ValueEncoder {
  private static final Logger logger = LoggerFactory.getLogger(ValueEncoder.class);

  // Epoch for CQL date (days since 1970-01-01, with offset 2^31)
  private static final int DATE_EPOCH_OFFSET = Integer.MIN_VALUE;

  // Static maps for O(1) type code lookup (using IdentityHashMap since DataTypes are singletons)
  private static final Map<DataType, Short> TYPE_CODE_MAP = new IdentityHashMap<>();
  private static final Map<DataType, Class<?>> JAVA_TYPE_MAP = new IdentityHashMap<>();

  static {
    // Initialize type code map
    TYPE_CODE_MAP.put(DataTypes.TINYINT, Protocol.TYPE_TINYINT);
    TYPE_CODE_MAP.put(DataTypes.SMALLINT, Protocol.TYPE_SMALLINT);
    TYPE_CODE_MAP.put(DataTypes.INT, Protocol.TYPE_INT);
    TYPE_CODE_MAP.put(DataTypes.BIGINT, Protocol.TYPE_BIGINT);
    TYPE_CODE_MAP.put(DataTypes.COUNTER, Protocol.TYPE_COUNTER);
    TYPE_CODE_MAP.put(DataTypes.FLOAT, Protocol.TYPE_FLOAT);
    TYPE_CODE_MAP.put(DataTypes.DOUBLE, Protocol.TYPE_DOUBLE);
    TYPE_CODE_MAP.put(DataTypes.BOOLEAN, Protocol.TYPE_BOOLEAN);
    TYPE_CODE_MAP.put(DataTypes.TEXT, Protocol.TYPE_TEXT);
    TYPE_CODE_MAP.put(DataTypes.ASCII, Protocol.TYPE_ASCII);
    TYPE_CODE_MAP.put(DataTypes.BLOB, Protocol.TYPE_BLOB);
    TYPE_CODE_MAP.put(DataTypes.UUID, Protocol.TYPE_UUID);
    TYPE_CODE_MAP.put(DataTypes.TIMEUUID, Protocol.TYPE_TIMEUUID);
    TYPE_CODE_MAP.put(DataTypes.TIMESTAMP, Protocol.TYPE_TIMESTAMP);
    TYPE_CODE_MAP.put(DataTypes.DATE, Protocol.TYPE_DATE);
    TYPE_CODE_MAP.put(DataTypes.TIME, Protocol.TYPE_TIME);
    TYPE_CODE_MAP.put(DataTypes.INET, Protocol.TYPE_INET);
    TYPE_CODE_MAP.put(DataTypes.VARINT, Protocol.TYPE_VARINT);
    TYPE_CODE_MAP.put(DataTypes.DECIMAL, Protocol.TYPE_DECIMAL);
    TYPE_CODE_MAP.put(DataTypes.DURATION, Protocol.TYPE_DURATION);

    // Initialize Java type map
    JAVA_TYPE_MAP.put(DataTypes.TEXT, String.class);
    JAVA_TYPE_MAP.put(DataTypes.ASCII, String.class);
    JAVA_TYPE_MAP.put(DataTypes.INT, Integer.class);
    JAVA_TYPE_MAP.put(DataTypes.BIGINT, Long.class);
    JAVA_TYPE_MAP.put(DataTypes.COUNTER, Long.class);
    JAVA_TYPE_MAP.put(DataTypes.SMALLINT, Short.class);
    JAVA_TYPE_MAP.put(DataTypes.TINYINT, Byte.class);
    JAVA_TYPE_MAP.put(DataTypes.FLOAT, Float.class);
    JAVA_TYPE_MAP.put(DataTypes.DOUBLE, Double.class);
    JAVA_TYPE_MAP.put(DataTypes.BOOLEAN, Boolean.class);
    JAVA_TYPE_MAP.put(DataTypes.UUID, UUID.class);
    JAVA_TYPE_MAP.put(DataTypes.TIMEUUID, UUID.class);
    JAVA_TYPE_MAP.put(DataTypes.BLOB, ByteBuffer.class);
    JAVA_TYPE_MAP.put(DataTypes.TIMESTAMP, Instant.class);
    JAVA_TYPE_MAP.put(DataTypes.DATE, LocalDate.class);
    JAVA_TYPE_MAP.put(DataTypes.TIME, LocalTime.class);
    JAVA_TYPE_MAP.put(DataTypes.INET, InetAddress.class);
    JAVA_TYPE_MAP.put(DataTypes.VARINT, BigInteger.class);
    JAVA_TYPE_MAP.put(DataTypes.DECIMAL, BigDecimal.class);
  }

  private ValueEncoder() {}

  /**
   * Decode a value from wire format using the target type.
   *
   * <p>This method handles type coercion when the wire type differs from the target type. For
   * example, a bigint on the wire can be coerced to int, smallint, tinyint, varint, decimal, or
   * timestamp.
   *
   * @param data the raw bytes in wire format, or null for a null value
   * @param wireType the CQL type code of the wire format (see {@link Protocol} TYPE_* constants)
   * @param targetType the target CQL DataType to decode into
   * @return the decoded Java object, or null if data is null
   * @throws Protocol.ProtocolException if the data cannot be decoded
   */
  public static @Nullable Object decode(
      byte[] data, short wireType, @NonNull DataType targetType) {
    if (data == null) {
      return null;
    }

    Protocol.BytesReader reader = new Protocol.BytesReader(data);
    return decodeValue(reader, data.length, wireType, targetType);
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
        return decodeVector(reader, targetType);
      case Protocol.TYPE_TUPLE:
        return decodeTuple(reader, targetType);
      case Protocol.TYPE_UDT:
        return decodeUdt(reader, targetType);
      case Protocol.TYPE_PACKED_FLOAT_VECTOR_LIST:
        return decodePackedFloatVectorList(reader);
      default:
        // For primitive types, decode based on the target type
        return decodePrimitive(reader, length, wireType, targetType);
    }
  }

  private static Object decodePrimitive(
      Protocol.BytesReader reader, int length, short wireType, DataType targetType) {
    // First try to decode using wire type, then coerce to target type if needed
    Object value = decodeByWireType(reader, length, wireType);

    // Apply type coercion if the target type differs
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
        return Instant.ofEpochMilli(reader.readLong());
      case Protocol.TYPE_DATE:
        return decodeDate(reader);
      case Protocol.TYPE_TIME:
        return decodeTime(reader);
      case Protocol.TYPE_INET:
        return decodeInet(reader, length);
      case Protocol.TYPE_VARINT:
        return new BigInteger(reader.readBytes(length));
      case Protocol.TYPE_DECIMAL:
        return decodeDecimal(reader, length);
      case Protocol.TYPE_DURATION:
        return decodeDuration(reader);
      default:
        // Unknown type, return as blob
        return ByteBuffer.wrap(reader.readBytes(length));
    }
  }

  private static UUID decodeUuid(Protocol.BytesReader reader) {
    long msb = reader.readLong();
    long lsb = reader.readLong();
    return new UUID(msb, lsb);
  }

  private static LocalDate decodeDate(Protocol.BytesReader reader) {
    int days = reader.readInt();
    // CQL date is unsigned int, days since epoch with offset
    long daysFromEpoch = ((long) days) - ((long) DATE_EPOCH_OFFSET);
    return LocalDate.ofEpochDay(daysFromEpoch);
  }

  private static LocalTime decodeTime(Protocol.BytesReader reader) {
    long nanos = reader.readLong();
    return LocalTime.ofNanoOfDay(nanos);
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

  private static CqlDuration decodeDuration(Protocol.BytesReader reader) {
    int months = (int) decodeVarInt(reader);
    int days = (int) decodeVarInt(reader);
    long nanos = decodeVarInt(reader);
    return CqlDuration.newInstance(months, days, nanos);
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

    DataType elementTargetType = DataTypes.BLOB;
    if (targetType instanceof ListType) {
      elementTargetType = ((ListType) targetType).getElementType();
    } else if (targetType instanceof SetType) {
      elementTargetType = ((SetType) targetType).getElementType();
    }

    for (int i = 0; i < count; i++) {
      int len = reader.readInt();
      if (len < 0) {
        list.add(null);
      } else if (elementType == Protocol.TYPE_VECTOR && vectorElementType == Protocol.TYPE_FLOAT) {
        // Vector element - decode inline without type header
        float[] vector = new float[vectorDimension];
        for (int j = 0; j < vectorDimension && j * 4 < len; j++) {
          vector[j] = Float.intBitsToFloat(reader.readInt());
        }
        list.add(CqlVector.newInstance(boxFloatArray(vector)));
      } else {
        byte[] elemData = reader.readBytes(len);
        Object value = decode(elemData, elementType, elementTargetType);
        list.add(coerceCollectionElement(value, elementTargetType));
      }
    }

    return list;
  }

  /**
   * Decode a packed list of float vectors.
   *
   * <p>Format: [n_elements: i32] [dimension: u16] [packed_floats: n*dim*4 bytes]
   * Each element is a CqlVector&lt;Float&gt; with the specified dimension.
   */
  private static List<?> decodePackedFloatVectorList(Protocol.BytesReader reader) {
    int nElements = reader.readInt();
    int dimension = reader.readUShort();

    List<CqlVector<Float>> list = new ArrayList<>(nElements);
    for (int i = 0; i < nElements; i++) {
      Float[] values = new Float[dimension];
      for (int j = 0; j < dimension; j++) {
        values[j] = Float.intBitsToFloat(reader.readInt());
      }
      list.add(CqlVector.newInstance(values));
    }
    return list;
  }

  private static Set<?> decodeSet(Protocol.BytesReader reader, DataType targetType) {
    short elementType = reader.readShort();
    int count = reader.readInt();
    Set<Object> set = new HashSet<>(count);

    DataType elementTargetType = DataTypes.BLOB;
    if (targetType instanceof SetType) {
      elementTargetType = ((SetType) targetType).getElementType();
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

    DataType keyTargetType = DataTypes.BLOB;
    DataType valueTargetType = DataTypes.BLOB;
    if (targetType instanceof MapType) {
      keyTargetType = ((MapType) targetType).getKeyType();
      valueTargetType = ((MapType) targetType).getValueType();
    }

    for (int i = 0; i < count; i++) {
      int keyLen = reader.readInt();
      byte[] keyData = keyLen >= 0 ? reader.readBytes(keyLen) : null;
      int valLen = reader.readInt();
      byte[] valData = valLen >= 0 ? reader.readBytes(valLen) : null;

      Object key = keyData != null ? decode(keyData, keyType, keyTargetType) : null;
      Object value = valData != null ? decode(valData, valueType, valueTargetType) : null;

      if (key != null) {
        map.put(coerceCollectionElement(key, keyTargetType),
                value != null ? coerceCollectionElement(value, valueTargetType) : null);
      }
    }

    return map;
  }

  private static CqlVector<?> decodeVector(Protocol.BytesReader reader, DataType targetType) {
    short elementType = reader.readShort();
    int dimension = reader.readUShort();

    if (elementType == Protocol.TYPE_FLOAT) {
      Float[] values = new Float[dimension];
      for (int i = 0; i < dimension; i++) {
        values[i] = Float.intBitsToFloat(reader.readInt());
      }
      return CqlVector.newInstance(values);
    }

    throw new Protocol.ProtocolException("Unsupported vector element type: " + elementType);
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
        tuple = tuple.setToNull(i);
      } else {
        byte[] elemData = reader.readBytes(len);
        Object value = decode(elemData, elementTypes[i], componentTypes.get(i));
        tuple = setTupleElement(tuple, i, value, componentTypes.get(i));
      }
    }

    return tuple;
  }

  private static UdtValue decodeUdt(Protocol.BytesReader reader, DataType targetType) {
    int fieldCount = reader.readUShort();
    String[] fieldNames = new String[fieldCount];
    short[] fieldTypes = new short[fieldCount];

    for (int i = 0; i < fieldCount; i++) {
      fieldNames[i] = reader.readString();
      fieldTypes[i] = reader.readShort();
    }

    if (!(targetType instanceof UserDefinedType)) {
      // If target is not UDT, we can't create the value - return null
      logger.debug("Target type is not a UDT: {}, returning null", targetType);
      // Skip the remaining data
      for (int i = 0; i < fieldCount; i++) {
        int len = reader.readInt();
        if (len >= 0) {
          reader.skip(len);
        }
      }
      return null;
    }

    UserDefinedType udtType = (UserDefinedType) targetType;
    UdtValue udt = udtType.newValue();

    for (int i = 0; i < fieldCount; i++) {
      int len = reader.readInt();
      if (len < 0) {
        // null value
        CqlIdentifier fieldId = CqlIdentifier.fromInternal(fieldNames[i]);
        if (udtType.contains(fieldId)) {
          udt = udt.setToNull(fieldNames[i]);
        }
      } else {
        byte[] fieldData = reader.readBytes(len);
        CqlIdentifier fieldId = CqlIdentifier.fromInternal(fieldNames[i]);
        if (udtType.contains(fieldId)) {
          DataType fieldTargetType = udtType.getFieldTypes().get(udtType.firstIndexOf(fieldId));
          Object value = decode(fieldData, fieldTypes[i], fieldTargetType);
          udt = setUdtField(udt, fieldNames[i], value, fieldTargetType);
        }
      }
    }

    return udt;
  }

  @SuppressWarnings("unchecked")
  private static TupleValue setTupleElement(
      TupleValue tuple, int index, Object value, DataType type) {
    if (value == null) {
      return tuple.setToNull(index);
    }
    if (type.equals(DataTypes.TINYINT)) {
      return tuple.setByte(index, ((Number) value).byteValue());
    }
    if (type.equals(DataTypes.SMALLINT)) {
      return tuple.setShort(index, ((Number) value).shortValue());
    }
    if (type.equals(DataTypes.INT)) {
      return tuple.setInt(index, ((Number) value).intValue());
    }
    if (type.equals(DataTypes.BIGINT) || type.equals(DataTypes.COUNTER)) {
      return tuple.setLong(index, ((Number) value).longValue());
    }
    if (type.equals(DataTypes.FLOAT)) {
      return tuple.setFloat(index, ((Number) value).floatValue());
    }
    if (type.equals(DataTypes.DOUBLE)) {
      return tuple.setDouble(index, ((Number) value).doubleValue());
    }
    if (type.equals(DataTypes.BOOLEAN)) {
      return tuple.setBoolean(index, (Boolean) value);
    }
    if (type.equals(DataTypes.TEXT) || type.equals(DataTypes.ASCII)) {
      return tuple.setString(index, (String) value);
    }
    if (type.equals(DataTypes.BLOB)) {
      return tuple.setByteBuffer(index, (ByteBuffer) value);
    }
    if (type.equals(DataTypes.UUID) || type.equals(DataTypes.TIMEUUID)) {
      return tuple.setUuid(index, (UUID) value);
    }
    if (type.equals(DataTypes.TIMESTAMP)) {
      return tuple.setInstant(index, (Instant) value);
    }
    if (type.equals(DataTypes.DATE)) {
      return tuple.setLocalDate(index, (LocalDate) value);
    }
    if (type.equals(DataTypes.TIME)) {
      return tuple.setLocalTime(index, (LocalTime) value);
    }
    if (type.equals(DataTypes.INET)) {
      return tuple.setInetAddress(index, (InetAddress) value);
    }
    if (type.equals(DataTypes.VARINT)) {
      return tuple.setBigInteger(index, (BigInteger) value);
    }
    if (type.equals(DataTypes.DECIMAL)) {
      return tuple.setBigDecimal(index, (BigDecimal) value);
    }
    if (type.equals(DataTypes.DURATION)) {
      return tuple.setCqlDuration(index, (CqlDuration) value);
    }
    if (type instanceof ListType listType) {
      DataType elemType = listType.getElementType();
      return tuple.setList(index, (List<Object>) value, getJavaTypeForDataType(elemType));
    }
    if (type instanceof SetType setType) {
      DataType elemType = setType.getElementType();
      if (value instanceof List) {
        return tuple.setSet(index, new HashSet<>((List<Object>) value), getJavaTypeForDataType(elemType));
      }
      return tuple.setSet(index, (Set<Object>) value, getJavaTypeForDataType(elemType));
    }
    if (type instanceof MapType mapType) {
      DataType keyType = mapType.getKeyType();
      DataType valType = mapType.getValueType();
      return tuple.setMap(index, (Map<Object, Object>) value, getJavaTypeForDataType(keyType), getJavaTypeForDataType(valType));
    }
    if (type instanceof VectorType) {
      return tuple.set(index, (CqlVector<?>) value, CqlVector.class);
    }
    if (type instanceof TupleType) {
      return tuple.setTupleValue(index, (TupleValue) value);
    }
    if (type instanceof UserDefinedType) {
      return tuple.setUdtValue(index, (UdtValue) value);
    }

    // Fallback - try generic set
    return tuple.set(index, value, Object.class);
  }

  @SuppressWarnings("unchecked")
  private static UdtValue setUdtField(UdtValue udt, String field, Object value, DataType type) {
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
      return udt.setCqlDuration(field, (CqlDuration) value);
    }
    if (type instanceof ListType listType) {
      DataType elemType = listType.getElementType();
      return udt.setList(field, (List<Object>) value, getJavaTypeForDataType(elemType));
    }
    if (type instanceof SetType setType) {
      DataType elemType = setType.getElementType();
      if (value instanceof List) {
        return udt.setSet(field, new HashSet<>((List<Object>) value), getJavaTypeForDataType(elemType));
      }
      return udt.setSet(field, (Set<Object>) value, getJavaTypeForDataType(elemType));
    }
    if (type instanceof MapType mapType) {
      DataType keyType = mapType.getKeyType();
      DataType valType = mapType.getValueType();
      return udt.setMap(field, (Map<Object, Object>) value, getJavaTypeForDataType(keyType), getJavaTypeForDataType(valType));
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

    // Fallback
    return udt.set(field, value, Object.class);
  }

  /** Coerce a value from wire type to target type. */
  private static Object coerceValue(Object value, short wireType, DataType targetType) {
    if (value == null) {
      return null;
    }

    // Handle integer type coercion
    if (value instanceof Long longVal) {
      if (targetType.equals(DataTypes.INT)) {
        return longVal.intValue();
      }
      if (targetType.equals(DataTypes.SMALLINT)) {
        return longVal.shortValue();
      }
      if (targetType.equals(DataTypes.TINYINT)) {
        return longVal.byteValue();
      }
      if (targetType.equals(DataTypes.VARINT)) {
        return BigInteger.valueOf(longVal);
      }
      if (targetType.equals(DataTypes.DECIMAL)) {
        return BigDecimal.valueOf(longVal);
      }
      if (targetType.equals(DataTypes.TIMESTAMP)) {
        return Instant.ofEpochMilli(longVal);
      }
      if (targetType.equals(DataTypes.TIME)) {
        return LocalTime.ofNanoOfDay(longVal);
      }
    }

    // Handle double to float coercion
    if (value instanceof Double doubleVal) {
      if (targetType.equals(DataTypes.FLOAT)) {
        return doubleVal.floatValue();
      }
    }

    // Handle string coercions
    if (value instanceof String strVal) {
      if (targetType.equals(DataTypes.DATE)) {
        return LocalDate.parse(strVal, DateTimeFormatter.ISO_LOCAL_DATE);
      }
      if (targetType.equals(DataTypes.TIME)) {
        return parseFlexibleTime(strVal);
      }
      if (targetType.equals(DataTypes.DURATION)) {
        return parseDuration(strVal);
      }
      if (targetType.equals(DataTypes.INET)) {
        try {
          return InetAddress.getByName(strVal);
        } catch (UnknownHostException e) {
          throw new Protocol.ProtocolException("Invalid IP address: " + strVal, e);
        }
      }
      if (targetType.equals(DataTypes.TIMEUUID) || targetType.equals(DataTypes.UUID)) {
        return UUID.fromString(strVal);
      }
      if (targetType.equals(DataTypes.DECIMAL)) {
        return new BigDecimal(strVal);
      }
    }

    // Handle list to set coercion
    if (value instanceof List && targetType instanceof SetType) {
      return new HashSet<>((List<?>) value);
    }

    return value;
  }

  private static Object coerceCollectionElement(Object value, DataType targetType) {
    if (value == null) {
      return null;
    }

    // Handle float array to CqlVector conversion
    if (value instanceof float[] floatArr) {
      return CqlVector.newInstance(boxFloatArray(floatArr));
    }

    // Handle list to set conversion
    if (value instanceof List && targetType instanceof SetType) {
      return new HashSet<>((List<?>) value);
    }

    return value;
  }

  private static LocalTime parseFlexibleTime(String str) {
    // Handle flexible time formats like "5:5:5" or "05:05:05" or "5:5:5.123456789"
    String[] parts = str.split("\\.");
    String timePart = parts[0];
    String nanoPart = parts.length > 1 ? parts[1] : null;

    String[] timeParts = timePart.split(":");
    int hour = Integer.parseInt(timeParts[0]);
    int minute = timeParts.length > 1 ? Integer.parseInt(timeParts[1]) : 0;
    int second = timeParts.length > 2 ? Integer.parseInt(timeParts[2]) : 0;
    int nano = 0;

    if (nanoPart != null) {
      // Pad or truncate to 9 digits for nanoseconds
      if (nanoPart.length() < 9) {
        nanoPart = nanoPart + "0".repeat(9 - nanoPart.length());
      } else if (nanoPart.length() > 9) {
        nanoPart = nanoPart.substring(0, 9);
      }
      nano = Integer.parseInt(nanoPart);
    }

    return LocalTime.of(hour, minute, second, nano);
  }

  private static CqlDuration parseDuration(String str) {
    // Simple duration parser for formats like "1mo2d3h4m5s" or "1h30m"
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
        case "mo" -> months += num;
        case "d" -> days += num;
        case "h" -> nanos += num * 3600_000_000_000L;
        case "m" -> nanos += num * 60_000_000_000L;
        case "s" -> nanos += num * 1_000_000_000L;
        case "ms" -> nanos += num * 1_000_000L;
        case "us" -> nanos += num * 1_000L;
        case "ns" -> nanos += num;
        default -> { /* ignore unknown units */ }
      }
    }

    return CqlDuration.newInstance(months, days, nanos);
  }

  private static Float[] boxFloatArray(float[] array) {
    Float[] result = new Float[array.length];
    for (int i = 0; i < array.length; i++) {
      result[i] = array[i];
    }
    return result;
  }

  /**
   * Encode a value to wire format.
   *
   * <p>Converts a Java object to its CQL binary representation based on the specified type.
   * Uses pooled BytesWriter for reduced allocation pressure.
   *
   * @param value the Java object to encode, or null for a null value
   * @param type the CQL DataType determining the encoding format
   * @return the encoded bytes in wire format, or null if value is null
   */
  public static byte[] encode(@Nullable Object value, @NonNull DataType type) {
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

  private static void encodeValue(Protocol.BytesWriter writer, Object value, DataType type) {
    if (value == null) {
      return;
    }

    if (type.equals(DataTypes.TINYINT)) {
      writer.writeByte(((Number) value).byteValue());
    } else if (type.equals(DataTypes.SMALLINT)) {
      writer.writeShort(((Number) value).shortValue());
    } else if (type.equals(DataTypes.INT)) {
      writer.writeInt(((Number) value).intValue());
    } else if (type.equals(DataTypes.BIGINT) || type.equals(DataTypes.COUNTER)) {
      writer.writeLong(((Number) value).longValue());
    } else if (type.equals(DataTypes.FLOAT)) {
      writer.writeInt(Float.floatToIntBits(((Number) value).floatValue()));
    } else if (type.equals(DataTypes.DOUBLE)) {
      writer.writeLong(Double.doubleToLongBits(((Number) value).doubleValue()));
    } else if (type.equals(DataTypes.BOOLEAN)) {
      writer.writeByte((Boolean) value ? 1 : 0);
    } else if (type.equals(DataTypes.TEXT) || type.equals(DataTypes.ASCII)) {
      byte[] bytes = ((String) value).getBytes(StandardCharsets.UTF_8);
      writer.writeBytes(bytes);
    } else if (type.equals(DataTypes.BLOB)) {
      ByteBuffer buf = (ByteBuffer) value;
      byte[] bytes = new byte[buf.remaining()];
      buf.duplicate().get(bytes);
      writer.writeBytes(bytes);
    } else if (type.equals(DataTypes.UUID) || type.equals(DataTypes.TIMEUUID)) {
      UUID uuid = (UUID) value;
      writer.writeLong(uuid.getMostSignificantBits());
      writer.writeLong(uuid.getLeastSignificantBits());
    } else if (type.equals(DataTypes.TIMESTAMP)) {
      writer.writeLong(((Instant) value).toEpochMilli());
    } else if (type.equals(DataTypes.DATE)) {
      long days = ((LocalDate) value).toEpochDay() + (long) DATE_EPOCH_OFFSET;
      writer.writeInt((int) days);
    } else if (type.equals(DataTypes.TIME)) {
      writer.writeLong(((LocalTime) value).toNanoOfDay());
    } else if (type.equals(DataTypes.INET)) {
      byte[] bytes = ((InetAddress) value).getAddress();
      writer.writeBytes(bytes);
    } else if (type.equals(DataTypes.VARINT)) {
      byte[] bytes = ((BigInteger) value).toByteArray();
      writer.writeBytes(bytes);
    } else if (type.equals(DataTypes.DECIMAL)) {
      BigDecimal dec = (BigDecimal) value;
      writer.writeInt(dec.scale());
      byte[] bytes = dec.unscaledValue().toByteArray();
      writer.writeBytes(bytes);
    } else if (type.equals(DataTypes.DURATION)) {
      CqlDuration dur = (CqlDuration) value;
      encodeVarInt(writer, dur.getMonths());
      encodeVarInt(writer, dur.getDays());
      encodeVarInt(writer, dur.getNanoseconds());
    } else if (type instanceof ListType listType) {
      encodeList(writer, (List<?>) value, listType.getElementType());
    } else if (type instanceof SetType setType) {
      encodeSet(writer, (Set<?>) value, setType.getElementType());
    } else if (type instanceof MapType mapType) {
      encodeMap(writer, (Map<?, ?>) value, mapType.getKeyType(), mapType.getValueType());
    } else if (type instanceof VectorType) {
      if (value instanceof CqlVector) {
        @SuppressWarnings("unchecked")
        CqlVector<Float> vec = (CqlVector<Float>) value;
        for (int i = 0; i < vec.size(); i++) {
          writer.writeInt(Float.floatToIntBits(vec.get(i)));
        }
      } else if (value instanceof float[]) {
        float[] arr = (float[]) value;
        for (float f : arr) {
          writer.writeInt(Float.floatToIntBits(f));
        }
      } else if (value instanceof List) {
        List<?> list = (List<?>) value;
        for (Object elem : list) {
          writer.writeInt(Float.floatToIntBits(((Number) elem).floatValue()));
        }
      }
    } else if (type instanceof TupleType tupleType) {
      encodeTuple(writer, (TupleValue) value, tupleType);
    } else if (type instanceof UserDefinedType udtType) {
      encodeUdt(writer, (UdtValue) value, udtType);
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

  private static void encodeUdt(Protocol.BytesWriter writer, UdtValue udt, UserDefinedType udtType) {
    for (int i = 0; i < udtType.getFieldTypes().size(); i++) {
      Object value = udt.getObject(i);
      byte[] encoded = encode(value, udtType.getFieldTypes().get(i));
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
   * <p>This method bypasses the Java driver's codec system for nested collections, providing direct
   * serialization of collection types including nested structures.
   * Uses pooled BytesWriter for reduced allocation pressure.
   *
   * @param collection the collection to encode (List, Set, or Map)
   * @param type the CQL collection type (ListType, SetType, or MapType)
   * @return the encoded bytes in CQL binary format
   * @throws IllegalArgumentException if type is not a collection type
   */
  public static byte[] encodeCollection(
      @NonNull Object collection, @NonNull DataType type) {
    Protocol.BytesWriter writer = Protocol.BytesWriterPool.acquire();
    try {
      if (type instanceof ListType listType) {
        List<?> list = (List<?>) collection;
        encodeList(writer, list, listType.getElementType());
      } else if (type instanceof SetType setType) {
        Set<?> set = (Set<?>) collection;
        encodeSet(writer, set, setType.getElementType());
      } else if (type instanceof MapType mapType) {
        Map<?, ?> map = (Map<?, ?>) collection;
        encodeMap(writer, map, mapType.getKeyType(), mapType.getValueType());
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
   * <p>Maps CQL DataType instances to their corresponding wire protocol type codes as defined in
   * the {@link Protocol} class (TYPE_* constants). Uses O(1) map lookup for primitive types.
   *
   * @param type the CQL DataType to get the type code for
   * @return the protocol type code, or TYPE_BLOB for unknown types
   */
  public static short getTypeCode(@NonNull DataType type) {
    // O(1) lookup for primitive types
    Short code = TYPE_CODE_MAP.get(type);
    if (code != null) {
      return code;
    }
    // Collection and complex types require instanceof checks
    if (type instanceof ListType) return Protocol.TYPE_LIST;
    if (type instanceof SetType) return Protocol.TYPE_SET;
    if (type instanceof MapType) return Protocol.TYPE_MAP;
    if (type instanceof VectorType) return Protocol.TYPE_VECTOR;
    if (type instanceof TupleType) return Protocol.TYPE_TUPLE;
    if (type instanceof UserDefinedType) return Protocol.TYPE_UDT;
    return Protocol.TYPE_BLOB;
  }

  /** Get the Java class corresponding to a CQL DataType for codec resolution. Uses O(1) map lookup. */
  @SuppressWarnings("unchecked")
  static <T> Class<T> getJavaTypeForDataType(DataType type) {
    // O(1) lookup for primitive types
    Class<?> javaType = JAVA_TYPE_MAP.get(type);
    if (javaType != null) {
      return (Class<T>) javaType;
    }
    // Collection and complex types require instanceof checks
    if (type instanceof UserDefinedType) {
      return (Class<T>) UdtValue.class;
    }
    if (type instanceof TupleType) {
      return (Class<T>) TupleValue.class;
    }
    if (type instanceof ListType) {
      return (Class<T>) List.class;
    }
    if (type instanceof SetType) {
      return (Class<T>) Set.class;
    }
    if (type instanceof MapType) {
      return (Class<T>) Map.class;
    }
    if (type instanceof VectorType) {
      return (Class<T>) CqlVector.class;
    }
    // Fall back to Object for unknown types
    return (Class<T>) Object.class;
  }
}

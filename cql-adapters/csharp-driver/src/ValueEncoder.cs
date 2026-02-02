using System.Buffers.Binary;
using System.Numerics;
using System.Net;
using System.Text;
using Cassandra;

namespace LatteDriver;

/// <summary>
/// Encodes .NET values to CQL binary format.
/// </summary>
public static class ValueEncoder
{
    public static byte[]? Encode(object? value, Type targetType)
    {
        if (value == null || value == DBNull.Value)
            return null;

        // Handle nullable types
        var underlying = Nullable.GetUnderlyingType(targetType) ?? targetType;

        return value switch
        {
            sbyte b => new[] { (byte)b },
            short s => EncodeShort(s),
            int i => EncodeInt(i),
            long l => EncodeLong(l),
            float f => EncodeFloat(f),
            double d => EncodeDouble(d),
            bool b => new[] { b ? (byte)1 : (byte)0 },
            string str => Encoding.UTF8.GetBytes(str),
            byte[] bytes => bytes,
            Guid g => EncodeUuid(g),
            DateTime dt => EncodeTimestamp(dt),
            DateTimeOffset dto => EncodeTimestamp(dto.UtcDateTime),
            IPAddress ip => ip.GetAddressBytes(),
            BigInteger bi => EncodeVarint(bi),
            decimal dec => EncodeDecimal(dec),
            LocalDate ld => EncodeLocalDate(ld),
            LocalTime lt => EncodeLocalTime(lt),
            Duration dur => EncodeDuration(dur),
            TimeUuid tu => EncodeUuid(tu.ToGuid()),
            CqlVector<float> vec => EncodeCqlVector(vec),
            // IDictionary must be checked before IEnumerable since dictionaries are also enumerable.
            // Use the non-generic IDictionary interface to handle all dictionary types, including
            // SortedDictionary<K,V> and Dictionary<K,V> where K/V are value types (which don't
            // match IDictionary<object, object> due to interface invariance).
            System.Collections.IDictionary dict => EncodeMapFromDict(dict),
            IEnumerable<object> list when IsListType(underlying) => EncodeList(list, underlying),
            _ => EncodeGeneric(value, underlying)
        };
    }

    private static bool IsListType(Type type)
    {
        if (!type.IsGenericType) return false;
        var genDef = type.GetGenericTypeDefinition();
        return genDef == typeof(List<>) || genDef == typeof(IList<>) ||
               genDef == typeof(HashSet<>) || genDef == typeof(SortedSet<>) || genDef == typeof(ISet<>);
    }

    private static byte[] EncodeShort(short value)
    {
        return new[] { (byte)(value >> 8), (byte)value };
    }

    private static byte[] EncodeInt(int value)
    {
        return new[] {
            (byte)(value >> 24),
            (byte)(value >> 16),
            (byte)(value >> 8),
            (byte)value
        };
    }

    private static byte[] EncodeLong(long value)
    {
        return new[] {
            (byte)(value >> 56),
            (byte)(value >> 48),
            (byte)(value >> 40),
            (byte)(value >> 32),
            (byte)(value >> 24),
            (byte)(value >> 16),
            (byte)(value >> 8),
            (byte)value
        };
    }

    private static byte[] EncodeFloat(float value)
    {
        var bytes = new byte[4];
        BinaryPrimitives.WriteSingleBigEndian(bytes, value);
        return bytes;
    }

    private static byte[] EncodeDouble(double value)
    {
        var bytes = new byte[8];
        BinaryPrimitives.WriteDoubleBigEndian(bytes, value);
        return bytes;
    }

    private static byte[] EncodeUuid(Guid value)
    {
        var bytes = value.ToByteArray();
        // Convert from .NET GUID format to UUID format
        return new[] {
            bytes[3], bytes[2], bytes[1], bytes[0],
            bytes[5], bytes[4],
            bytes[7], bytes[6],
            bytes[8], bytes[9], bytes[10], bytes[11],
            bytes[12], bytes[13], bytes[14], bytes[15]
        };
    }

    private static byte[] EncodeTimestamp(DateTime value)
    {
        var epoch = new DateTime(1970, 1, 1, 0, 0, 0, DateTimeKind.Utc);
        var ms = (long)(value.ToUniversalTime() - epoch).TotalMilliseconds;
        return EncodeLong(ms);
    }

    private static byte[] EncodeVarint(BigInteger value)
    {
        var bytes = value.ToByteArray();
        // BigInteger uses little-endian, CQL uses big-endian
        Array.Reverse(bytes);
        return bytes;
    }

    private static byte[] EncodeDecimal(decimal value)
    {
        var bits = decimal.GetBits(value);
        var scale = (bits[3] >> 16) & 0x7F;
        var sign = (bits[3] >> 31) != 0;

        // Construct the unscaled value from the three low words
        var low = (ulong)(uint)bits[0];
        var mid = (ulong)(uint)bits[1];
        var high = (ulong)(uint)bits[2];

        // Build the BigInteger: high * 2^64 + mid * 2^32 + low
        var unscaled = new BigInteger(low) +
                       (new BigInteger(mid) << 32) +
                       (new BigInteger(high) << 64);

        if (sign)
            unscaled = -unscaled;

        var unscaledBytes = EncodeVarint(unscaled);

        var result = new byte[4 + unscaledBytes.Length];
        result[0] = (byte)(scale >> 24);
        result[1] = (byte)(scale >> 16);
        result[2] = (byte)(scale >> 8);
        result[3] = (byte)scale;
        Array.Copy(unscaledBytes, 0, result, 4, unscaledBytes.Length);
        return result;
    }

    private static byte[] EncodeLocalDate(LocalDate value)
    {
        // Days since Unix epoch (Jan 1, 1970) + 2^31
        var epoch = new DateTime(1970, 1, 1, 0, 0, 0, DateTimeKind.Utc);
        var date = new DateTime(value.Year, value.Month, value.Day, 0, 0, 0, DateTimeKind.Utc);
        var days = (int)(date - epoch).TotalDays + (1 << 31);
        return EncodeInt(days);
    }

    private static byte[] EncodeLocalTime(LocalTime value)
    {
        // Nanoseconds since midnight
        var nanos = value.TotalNanoseconds;
        return EncodeLong(nanos);
    }

    private static byte[] EncodeDuration(Duration value)
    {
        using var ms = new MemoryStream();
        WriteSignedVint(ms, value.Months);
        WriteSignedVint(ms, value.Days);
        WriteSignedVint(ms, value.Nanoseconds);
        return ms.ToArray();
    }

    private static void WriteSignedVint(MemoryStream ms, long value)
    {
        // Zig-zag encode
        var zigzag = (ulong)((value << 1) ^ (value >> 63));
        WriteUnsignedVint(ms, zigzag);
    }

    private static void WriteUnsignedVint(MemoryStream ms, ulong value)
    {
        while (value >= 0x80)
        {
            ms.WriteByte((byte)(value | 0x80));
            value >>= 7;
        }
        ms.WriteByte((byte)value);
    }

    private static byte[] EncodeCqlVector(CqlVector<float> vec)
    {
        // Encode as raw contiguous big-endian floats (no header).
        // Latte infers dimension from data.len() / 4.
        var bytes = new byte[vec.Count * 4];
        for (int i = 0; i < vec.Count; i++)
        {
            BinaryPrimitives.WriteSingleBigEndian(bytes.AsSpan(i * 4, 4), vec[i]);
        }
        return bytes;
    }

    private static byte[] EncodeList(IEnumerable<object> list, Type targetType)
    {
        // Avoid allocation if already a list
        var items = list as IList<object> ?? list.ToList();
        var elementType = targetType.IsGenericType
            ? targetType.GetGenericArguments()[0]
            : typeof(object);

        using var ms = new MemoryStream();
        // Write count
        WriteInt(ms, items.Count);
        foreach (var item in items)
        {
            var encoded = Encode(item, elementType);
            if (encoded == null)
            {
                WriteInt(ms, -1);
            }
            else
            {
                WriteInt(ms, encoded.Length);
                ms.Write(encoded, 0, encoded.Length);
            }
        }
        return ms.ToArray();
    }

    private static byte[] EncodeMapFromDict(System.Collections.IDictionary dict)
    {
        using var ms = new MemoryStream();
        WriteInt(ms, dict.Count);
        foreach (System.Collections.DictionaryEntry entry in dict)
        {
            var keyType = entry.Key?.GetType() ?? typeof(object);
            var keyEncoded = Encode(entry.Key, keyType);

            if (keyEncoded == null)
            {
                WriteInt(ms, -1);
            }
            else
            {
                WriteInt(ms, keyEncoded.Length);
                ms.Write(keyEncoded, 0, keyEncoded.Length);
            }

            var valType = entry.Value?.GetType() ?? typeof(object);
            var valEncoded = entry.Value != null ? Encode(entry.Value, valType) : null;

            if (valEncoded == null)
            {
                WriteInt(ms, -1);
            }
            else
            {
                WriteInt(ms, valEncoded.Length);
                ms.Write(valEncoded, 0, valEncoded.Length);
            }
        }
        return ms.ToArray();
    }

    private static byte[] EncodeGeneric(object value, Type targetType)
    {
        // Handle common typed arrays directly to avoid boxing
        switch (value)
        {
            case int[] intArray:
                return EncodeTypedArray(intArray, EncodeInt);
            case long[] longArray:
                return EncodeTypedArray(longArray, EncodeLong);
            case short[] shortArray:
                return EncodeTypedArray(shortArray, EncodeShort);
            case float[] floatArray:
                return EncodeTypedArray(floatArray, EncodeFloat);
            case double[] doubleArray:
                return EncodeTypedArray(doubleArray, EncodeDouble);
            case string[] stringArray:
                return EncodeTypedArray(stringArray, s => Encoding.UTF8.GetBytes(s));
            case bool[] boolArray:
                return EncodeTypedArray(boolArray, b => new[] { b ? (byte)1 : (byte)0 });
        }

        // Handle dictionaries before IEnumerable to avoid encoding maps as lists
        if (value is System.Collections.IDictionary dictVal)
            return EncodeMapFromDict(dictVal);

        // Try to handle via IEnumerable for other collections
        if (value is System.Collections.IEnumerable enumerable && value is not string && value is not byte[])
        {
            // Check if it's already a typed list to avoid boxing
            if (value is IList<object> objList)
                return EncodeList(objList, targetType);

            var list = new List<object>();
            foreach (var item in enumerable)
                list.Add(item);
            return EncodeList(list, targetType);
        }

        // Fallback: convert to string
        return Encoding.UTF8.GetBytes(value.ToString() ?? "");
    }

    private static byte[] EncodeTypedArray<T>(T[] array, Func<T, byte[]> encoder)
    {
        using var ms = new MemoryStream();
        WriteInt(ms, array.Length);
        foreach (var item in array)
        {
            var encoded = encoder(item);
            WriteInt(ms, encoded.Length);
            ms.Write(encoded, 0, encoded.Length);
        }
        return ms.ToArray();
    }

    private static void WriteInt(MemoryStream ms, int value)
    {
        ms.WriteByte((byte)(value >> 24));
        ms.WriteByte((byte)(value >> 16));
        ms.WriteByte((byte)(value >> 8));
        ms.WriteByte((byte)value);
    }
}

/// <summary>
/// Decodes CQL binary format values to .NET types for prepared statement binding.
/// </summary>
public static class ValueDecoder
{
    public static object? Decode(ReadOnlySpan<byte> data, ushort typeCode, Cassandra.ColumnDesc? columnSpec = null)
    {
        if (data.IsEmpty)
            return null;

        return typeCode switch
        {
            Protocol.TypeTinyint => (sbyte)data[0],
            Protocol.TypeSmallint => DecodeShort(data),
            Protocol.TypeInt => DecodeInt(data),
            Protocol.TypeBigint => DecodeLong(data),
            Protocol.TypeFloat => DecodeFloat(data),
            Protocol.TypeDouble => DecodeDouble(data),
            Protocol.TypeBoolean => data[0] != 0,
            Protocol.TypeAscii or Protocol.TypeText => Encoding.UTF8.GetString(data),
            Protocol.TypeBlob => data.ToArray(),
            Protocol.TypeUuid => DecodeUuid(data),
            Protocol.TypeTimeuuid => DecodeTimeUuid(data),
            Protocol.TypeTimestamp => DecodeTimestamp(data),
            Protocol.TypeInet => DecodeInet(data),
            Protocol.TypeVarint => DecodeVarint(data),
            Protocol.TypeDecimal => DecodeDecimal(data),
            Protocol.TypeDate => DecodeLocalDate(data),
            Protocol.TypeTime => DecodeLocalTime(data),
            Protocol.TypeDuration => DecodeDuration(data),
            Protocol.TypeCounter => DecodeLong(data),
            Protocol.TypeList => DecodeList(data),
            Protocol.TypeSet => DecodeSet(data),
            Protocol.TypeMap => DecodeMap(data),
            Protocol.TypeTuple => DecodeTuple(data),
            Protocol.TypeVector => DecodeVector(data),
            Protocol.TypePackedFloatVectorList => DecodePackedFloatVectorList(data),
            Protocol.TypeUdt => DecodeUdt(data),
            _ => data.ToArray() // Fallback to raw bytes
        };
    }

    public static RawValue ReadRawValue(ReadOnlySpan<byte> body, ref int pos)
    {
        var typeCode = (ushort)((body[pos] << 8) | body[pos + 1]);
        pos += 2;
        var length = (body[pos] << 24) | (body[pos + 1] << 16) | (body[pos + 2] << 8) | body[pos + 3];
        pos += 4;

        if (length < 0)
            return new RawValue { TypeCode = typeCode, Data = null };

        var data = body.Slice(pos, length).ToArray();
        pos += length;
        return new RawValue { TypeCode = typeCode, Data = data };
    }

    private static short DecodeShort(ReadOnlySpan<byte> data)
    {
        return (short)((data[0] << 8) | data[1]);
    }

    private static int DecodeInt(ReadOnlySpan<byte> data)
    {
        if (data.Length == 1) return (sbyte)data[0];
        if (data.Length == 2) return DecodeShort(data);
        return (data[0] << 24) | (data[1] << 16) | (data[2] << 8) | data[3];
    }

    private static long DecodeLong(ReadOnlySpan<byte> data)
    {
        if (data.Length <= 4) return DecodeInt(data);
        return ((long)data[0] << 56) | ((long)data[1] << 48) |
               ((long)data[2] << 40) | ((long)data[3] << 32) |
               ((long)data[4] << 24) | ((long)data[5] << 16) |
               ((long)data[6] << 8) | data[7];
    }

    private static float DecodeFloat(ReadOnlySpan<byte> data)
    {
        return BinaryPrimitives.ReadSingleBigEndian(data);
    }

    private static double DecodeDouble(ReadOnlySpan<byte> data)
    {
        return BinaryPrimitives.ReadDoubleBigEndian(data);
    }

    private static Guid DecodeUuid(ReadOnlySpan<byte> data)
    {
        // Convert from UUID format to .NET GUID format
        var bytes = new byte[] {
            data[3], data[2], data[1], data[0],
            data[5], data[4],
            data[7], data[6],
            data[8], data[9], data[10], data[11],
            data[12], data[13], data[14], data[15]
        };
        return new Guid(bytes);
    }

    private static TimeUuid DecodeTimeUuid(ReadOnlySpan<byte> data)
    {
        var guid = DecodeUuid(data);
        return (TimeUuid)guid;
    }

    private static DateTimeOffset DecodeTimestamp(ReadOnlySpan<byte> data)
    {
        var ms = DecodeLong(data);
        return DateTimeOffset.FromUnixTimeMilliseconds(ms);
    }

    private static IPAddress DecodeInet(ReadOnlySpan<byte> data)
    {
        return new IPAddress(data.ToArray());
    }

    private static BigInteger DecodeVarint(ReadOnlySpan<byte> data)
    {
        var bytes = data.ToArray();
        // CQL uses big-endian, BigInteger uses little-endian
        Array.Reverse(bytes);
        return new BigInteger(bytes);
    }

    private static decimal DecodeDecimal(ReadOnlySpan<byte> data)
    {
        var scale = (data[0] << 24) | (data[1] << 16) | (data[2] << 8) | data[3];
        var unscaled = DecodeVarint(data.Slice(4));

        // Convert to decimal
        var str = unscaled.ToString();
        if (scale > 0 && str.Length > scale)
        {
            str = str.Insert(str.Length - scale, ".");
        }
        else if (scale > 0)
        {
            str = "0." + new string('0', scale - str.Length) + str.TrimStart('-');
            if (unscaled < 0) str = "-" + str;
        }

        return decimal.Parse(str, System.Globalization.CultureInfo.InvariantCulture);
    }

    private static LocalDate DecodeLocalDate(ReadOnlySpan<byte> data)
    {
        var days = DecodeInt(data);
        var epoch = new DateTime(1970, 1, 1, 0, 0, 0, DateTimeKind.Utc);
        var date = epoch.AddDays(days - (1 << 31));
        return new LocalDate(date.Year, date.Month, date.Day);
    }

    private static LocalTime DecodeLocalTime(ReadOnlySpan<byte> data)
    {
        var nanos = DecodeLong(data);
        // Convert nanoseconds to hour, minute, second, nanosecond
        var totalSeconds = nanos / 1_000_000_000L;
        var nanosPart = (int)(nanos % 1_000_000_000L);
        var hours = (int)(totalSeconds / 3600);
        var minutes = (int)((totalSeconds % 3600) / 60);
        var seconds = (int)(totalSeconds % 60);
        return new LocalTime(hours, minutes, seconds, nanosPart);
    }

    private static Duration DecodeDuration(ReadOnlySpan<byte> data)
    {
        int pos = 0;
        var months = (int)ReadSignedVint(data, ref pos);
        var days = (int)ReadSignedVint(data, ref pos);
        var nanos = ReadSignedVint(data, ref pos);
        return new Duration(months, days, nanos);
    }

    private static long ReadSignedVint(ReadOnlySpan<byte> data, ref int pos)
    {
        var unsigned = ReadUnsignedVint(data, ref pos);
        // Zig-zag decode
        return (long)((unsigned >> 1) ^ (ulong)(-(long)(unsigned & 1)));
    }

    private static ulong ReadUnsignedVint(ReadOnlySpan<byte> data, ref int pos)
    {
        ulong value = 0;
        int shift = 0;
        while (pos < data.Length)
        {
            var b = data[pos++];
            value |= ((ulong)(b & 0x7F)) << shift;
            if ((b & 0x80) == 0)
                break;
            shift += 7;
        }
        return value;
    }

    private static object DecodeList(ReadOnlySpan<byte> data)
    {
        // Read element type code
        var elementType = (ushort)((data[0] << 8) | data[1]);
        var pos = 2;

        // Check for vector<float, N> inside list
        ushort vectorElementType = 0;
        ushort vectorDimension = 0;
        if (elementType == Protocol.TypeVector)
        {
            vectorElementType = (ushort)((data[pos] << 8) | data[pos + 1]);
            pos += 2;
            vectorDimension = (ushort)((data[pos] << 8) | data[pos + 1]);
            pos += 2;
        }

        var count = (data[pos] << 24) | (data[pos + 1] << 16) | (data[pos + 2] << 8) | data[pos + 3];
        pos += 4;

        var list = new List<object?>(count);
        for (int i = 0; i < count; i++)
        {
            var len = (data[pos] << 24) | (data[pos + 1] << 16) | (data[pos + 2] << 8) | data[pos + 3];
            pos += 4;
            if (len < 0)
            {
                list.Add(null);
            }
            else
            {
                // For vectors inside lists, decode directly as float array
                // (the element data doesn't include type/dimension header)
                if (elementType == Protocol.TypeVector && vectorElementType == Protocol.TypeFloat)
                {
                    var elemData = data.Slice(pos, len);
                    var vector = new float[vectorDimension];
                    for (int j = 0; j < vectorDimension && j * 4 < len; j++)
                    {
                        vector[j] = DecodeFloat(elemData.Slice(j * 4, 4));
                    }
                    list.Add(vector);
                }
                else
                {
                    var elem = Decode(data.Slice(pos, len), elementType);
                    list.Add(elem);
                }
                pos += len;
            }
        }
        return list;
    }

    private static object DecodeSet(ReadOnlySpan<byte> data)
    {
        // Same format as list
        return DecodeList(data);
    }

    private static object DecodeMap(ReadOnlySpan<byte> data)
    {
        var keyType = (ushort)((data[0] << 8) | data[1]);
        var valueType = (ushort)((data[2] << 8) | data[3]);
        var count = (data[4] << 24) | (data[5] << 16) | (data[6] << 8) | data[7];
        var pos = 8;

        var dict = new Dictionary<object, object?>(count);
        for (int i = 0; i < count; i++)
        {
            var keyLen = (data[pos] << 24) | (data[pos + 1] << 16) | (data[pos + 2] << 8) | data[pos + 3];
            pos += 4;
            var key = keyLen >= 0 ? Decode(data.Slice(pos, keyLen), keyType) : null;
            if (keyLen >= 0) pos += keyLen;

            var valLen = (data[pos] << 24) | (data[pos + 1] << 16) | (data[pos + 2] << 8) | data[pos + 3];
            pos += 4;
            var val = valLen >= 0 ? Decode(data.Slice(pos, valLen), valueType) : null;
            if (valLen >= 0) pos += valLen;

            if (key != null)
                dict[key] = val;
        }
        return dict;
    }

    private static object DecodeTuple(ReadOnlySpan<byte> data)
    {
        var nElements = (ushort)((data[0] << 8) | data[1]);
        var pos = 2;

        // Read element types
        var types = new ushort[nElements];
        for (int i = 0; i < nElements; i++)
        {
            types[i] = (ushort)((data[pos] << 8) | data[pos + 1]);
            pos += 2;
        }

        // Read values
        var values = new object?[nElements];
        for (int i = 0; i < nElements; i++)
        {
            var len = (data[pos] << 24) | (data[pos + 1] << 16) | (data[pos + 2] << 8) | data[pos + 3];
            pos += 4;
            if (len >= 0)
            {
                values[i] = Decode(data.Slice(pos, len), types[i]);
                pos += len;
            }
        }
        return new Tuple<object?[]>(values);
    }

    private static object DecodeVector(ReadOnlySpan<byte> data)
    {
        var elementType = (ushort)((data[0] << 8) | data[1]);
        var dimension = (ushort)((data[2] << 8) | data[3]);
        var pos = 4;

        if (elementType == Protocol.TypeFloat)
        {
            var vector = new float[dimension];
            for (int i = 0; i < dimension; i++)
            {
                vector[i] = DecodeFloat(data.Slice(pos, 4));
                pos += 4;
            }
            return vector;
        }

        // Fallback for other types
        return data.Slice(4).ToArray();
    }

    private static object DecodePackedFloatVectorList(ReadOnlySpan<byte> data)
    {
        // Format: [n_elements: i32] [dimension: u16] [packed_floats: n*dim*4 bytes]
        if (data.Length < 6)
            return new List<float[]>();

        var pos = 0;
        var nElements = (data[pos] << 24) | (data[pos + 1] << 16) | (data[pos + 2] << 8) | data[pos + 3];
        pos += 4;
        var dimension = (ushort)((data[pos] << 8) | data[pos + 1]);
        pos += 2;

        if (nElements <= 0)
            return new List<float[]>();

        var floatBytesPerVector = dimension * 4;
        var result = new List<float[]>(nElements);

        for (int i = 0; i < nElements; i++)
        {
            var vector = new float[dimension];
            for (int j = 0; j < dimension; j++)
            {
                vector[j] = DecodeFloat(data.Slice(pos + j * 4, 4));
            }
            pos += floatBytesPerVector;
            result.Add(vector);
        }

        return result;
    }

    private static object DecodeUdt(ReadOnlySpan<byte> data)
    {
        var nFields = (ushort)((data[0] << 8) | data[1]);
        var pos = 2;

        // Read field definitions
        var fieldNames = new string[nFields];
        var fieldTypes = new ushort[nFields];
        for (int i = 0; i < nFields; i++)
        {
            var nameLen = (ushort)((data[pos] << 8) | data[pos + 1]);
            pos += 2;
            fieldNames[i] = Encoding.UTF8.GetString(data.Slice(pos, nameLen));
            pos += nameLen;
            fieldTypes[i] = (ushort)((data[pos] << 8) | data[pos + 1]);
            pos += 2;
        }

        // Read field values
        var dict = new Dictionary<string, object?>(nFields);
        for (int i = 0; i < nFields; i++)
        {
            var len = (data[pos] << 24) | (data[pos + 1] << 16) | (data[pos + 2] << 8) | data[pos + 3];
            pos += 4;
            if (len >= 0)
            {
                dict[fieldNames[i]] = Decode(data.Slice(pos, len), fieldTypes[i]);
                pos += len;
            }
            else
            {
                dict[fieldNames[i]] = null;
            }
        }
        return dict;
    }
}

public struct RawValue
{
    public ushort TypeCode { get; init; }
    public byte[]? Data { get; init; }

    public object? Decode(Cassandra.ColumnDesc? columnSpec = null)
    {
        if (Data == null)
            return null;
        return ValueDecoder.Decode(Data, TypeCode, columnSpec);
    }
}

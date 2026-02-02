using System.Buffers;
using System.Collections.Concurrent;
using System.Reflection;
using System.Text;
using Cassandra;
using Microsoft.Extensions.ObjectPool;

namespace LatteDriver;

/// <summary>
/// IPC protocol constants and frame handling for Latte driver communication.
/// </summary>
public static class Protocol
{
    public const int IpcProtocolVersion = 1;
    public const int HeaderLength = 9;
    public const int MaxBodyLength = 16 * 1024 * 1024; // 16MB

    public const byte VersionRequest = 0x04;
    public const byte VersionResponse = 0x84;

    // Opcodes
    public const byte OpcodeError = 0x00;
    public const byte OpcodeQuery = 0x07;
    public const byte OpcodeResult = 0x08;
    public const byte OpcodePrepare = 0x09;
    public const byte OpcodeExecute = 0x0A;
    public const byte OpcodeBatch = 0x0D;
    public const byte OpcodeCreateSession = 0x21;
    public const byte OpcodeSessionCreated = 0x22;

    // Result kinds
    public const int ResultKindVoid = 0x0001;
    public const int ResultKindRows = 0x0002;
    public const int ResultKindSetKeyspace = 0x0003;
    public const int ResultKindPrepared = 0x0004;
    public const int ResultKindSchemaChange = 0x0005;

    // Error codes
    public const int ErrorCodeServer = 0x0000;
    public const int ErrorCodeProtocol = 0x000A;
    public const int ErrorCodeOverloaded = 0x1001;
    public const int ErrorCodeUnprepared = 0x2500;

    // CQL type codes
    public const ushort TypeAscii = 0x0001;
    public const ushort TypeBigint = 0x0002;
    public const ushort TypeBlob = 0x0003;
    public const ushort TypeBoolean = 0x0004;
    public const ushort TypeCounter = 0x0005;
    public const ushort TypeDecimal = 0x0006;
    public const ushort TypeDouble = 0x0007;
    public const ushort TypeFloat = 0x0008;
    public const ushort TypeInt = 0x0009;
    public const ushort TypeTimestamp = 0x000B;
    public const ushort TypeUuid = 0x000C;
    public const ushort TypeText = 0x000D;
    public const ushort TypeVarint = 0x000E;
    public const ushort TypeTimeuuid = 0x000F;
    public const ushort TypeInet = 0x0010;
    public const ushort TypeDate = 0x0011;
    public const ushort TypeTime = 0x0012;
    public const ushort TypeSmallint = 0x0013;
    public const ushort TypeTinyint = 0x0014;
    public const ushort TypeDuration = 0x0015;
    public const ushort TypeList = 0x0020;
    public const ushort TypeMap = 0x0021;
    public const ushort TypeSet = 0x0022;
    public const ushort TypeVector = 0x0030;
    public const ushort TypeTuple = 0x0031;
    public const ushort TypePackedFloatVectorList = 0x0032;
    public const ushort TypeUdt = 0x0040;

    // Consistency levels
    public const ushort ConsistencyAny = 0x0000;
    public const ushort ConsistencyOne = 0x0001;
    public const ushort ConsistencyTwo = 0x0002;
    public const ushort ConsistencyThree = 0x0003;
    public const ushort ConsistencyQuorum = 0x0004;
    public const ushort ConsistencyAll = 0x0005;
    public const ushort ConsistencyLocalQuorum = 0x0006;
    public const ushort ConsistencyEachQuorum = 0x0007;
    public const ushort ConsistencyLocalOne = 0x000A;
}

/// <summary>
/// Represents a parsed frame from the IPC protocol.
/// </summary>
public readonly struct Frame
{
    public byte Version { get; init; }
    public byte Flags { get; init; }
    public short Stream { get; init; }
    public byte Opcode { get; init; }
    public ReadOnlyMemory<byte> Body { get; init; }
}

/// <summary>
/// Helper for reading binary data in big-endian format.
/// </summary>
public ref struct BytesReader
{
    private ReadOnlySpan<byte> _data;
    private int _pos;

    public BytesReader(ReadOnlySpan<byte> data)
    {
        _data = data;
        _pos = 0;
    }

    public BytesReader(ReadOnlyMemory<byte> data) : this(data.Span)
    {
    }

    public int Position => _pos;
    public int Remaining => _data.Length - _pos;
    public bool HasRemaining => _pos < _data.Length;

    public byte ReadByte()
    {
        if (_pos >= _data.Length)
            throw new ProtocolException("Unexpected end of data reading byte");
        return _data[_pos++];
    }

    public ushort ReadUShort()
    {
        if (_pos + 2 > _data.Length)
            throw new ProtocolException("Unexpected end of data reading ushort");
        var value = (ushort)((_data[_pos] << 8) | _data[_pos + 1]);
        _pos += 2;
        return value;
    }

    public short ReadShort()
    {
        return (short)ReadUShort();
    }

    public int ReadInt()
    {
        if (_pos + 4 > _data.Length)
            throw new ProtocolException("Unexpected end of data reading int");
        var value = (_data[_pos] << 24) | (_data[_pos + 1] << 16) | (_data[_pos + 2] << 8) | _data[_pos + 3];
        _pos += 4;
        return value;
    }

    public uint ReadUInt()
    {
        return (uint)ReadInt();
    }

    public long ReadLong()
    {
        if (_pos + 8 > _data.Length)
            throw new ProtocolException("Unexpected end of data reading long");
        long value = ((long)_data[_pos] << 56) | ((long)_data[_pos + 1] << 48) |
                     ((long)_data[_pos + 2] << 40) | ((long)_data[_pos + 3] << 32) |
                     ((long)_data[_pos + 4] << 24) | ((long)_data[_pos + 5] << 16) |
                     ((long)_data[_pos + 6] << 8) | _data[_pos + 7];
        _pos += 8;
        return value;
    }

    public ulong ReadULong()
    {
        return (ulong)ReadLong();
    }

    public ReadOnlySpan<byte> ReadBytes(int length)
    {
        if (_pos + length > _data.Length)
            throw new ProtocolException($"Unexpected end of data reading {length} bytes");
        var slice = _data.Slice(_pos, length);
        _pos += length;
        return slice;
    }

    public string ReadString()
    {
        var length = ReadUShort();
        var bytes = ReadBytes(length);
        return System.Text.Encoding.UTF8.GetString(bytes);
    }

    public string ReadLongString()
    {
        var length = ReadInt();
        if (length < 0)
            throw new ProtocolException("Invalid negative string length");
        var bytes = ReadBytes(length);
        return System.Text.Encoding.UTF8.GetString(bytes);
    }

    public byte[]? ReadBytesNullable()
    {
        var length = ReadInt();
        if (length < 0)
            return null;
        return ReadBytes(length).ToArray();
    }

    public Dictionary<string, string> ReadStringMap()
    {
        var count = ReadUShort();
        var map = new Dictionary<string, string>(count);
        for (int i = 0; i < count; i++)
        {
            var key = ReadString();
            var value = ReadString();
            map[key] = value;
        }
        return map;
    }

    public void Skip(int count)
    {
        if (_pos + count > _data.Length)
            throw new ProtocolException($"Cannot skip {count} bytes, only {Remaining} remaining");
        _pos += count;
    }
}

/// <summary>
/// Helper for writing binary data in big-endian format.
/// Uses ArrayBufferWriter for reduced allocations.
/// </summary>
public class BytesWriter
{
    private readonly ArrayBufferWriter<byte> _buffer;

    public BytesWriter(int initialCapacity = 256)
    {
        _buffer = new ArrayBufferWriter<byte>(initialCapacity);
    }

    public int Length => _buffer.WrittenCount;

    public void WriteByte(byte value)
    {
        var span = _buffer.GetSpan(1);
        span[0] = value;
        _buffer.Advance(1);
    }

    public void WriteShort(short value)
    {
        WriteUShort((ushort)value);
    }

    public void WriteUShort(ushort value)
    {
        var span = _buffer.GetSpan(2);
        span[0] = (byte)(value >> 8);
        span[1] = (byte)value;
        _buffer.Advance(2);
    }

    public void WriteInt(int value)
    {
        var span = _buffer.GetSpan(4);
        span[0] = (byte)(value >> 24);
        span[1] = (byte)(value >> 16);
        span[2] = (byte)(value >> 8);
        span[3] = (byte)value;
        _buffer.Advance(4);
    }

    public void WriteUInt(uint value)
    {
        WriteInt((int)value);
    }

    public void WriteLong(long value)
    {
        var span = _buffer.GetSpan(8);
        span[0] = (byte)(value >> 56);
        span[1] = (byte)(value >> 48);
        span[2] = (byte)(value >> 40);
        span[3] = (byte)(value >> 32);
        span[4] = (byte)(value >> 24);
        span[5] = (byte)(value >> 16);
        span[6] = (byte)(value >> 8);
        span[7] = (byte)value;
        _buffer.Advance(8);
    }

    public void WriteULong(ulong value)
    {
        WriteLong((long)value);
    }

    public void WriteBytes(ReadOnlySpan<byte> bytes)
    {
        var span = _buffer.GetSpan(bytes.Length);
        bytes.CopyTo(span);
        _buffer.Advance(bytes.Length);
    }

    public void WriteBytes(byte[] bytes)
    {
        WriteBytes(bytes.AsSpan());
    }

    public void WriteString(string value)
    {
        // Get byte count first, then write directly to buffer
        var byteCount = Encoding.UTF8.GetByteCount(value);
        if (byteCount > ushort.MaxValue)
            throw new ProtocolException($"String too long: {byteCount} bytes");
        WriteUShort((ushort)byteCount);
        var span = _buffer.GetSpan(byteCount);
        Encoding.UTF8.GetBytes(value.AsSpan(), span);
        _buffer.Advance(byteCount);
    }

    public void WriteLongString(string value)
    {
        var byteCount = Encoding.UTF8.GetByteCount(value);
        WriteInt(byteCount);
        var span = _buffer.GetSpan(byteCount);
        Encoding.UTF8.GetBytes(value.AsSpan(), span);
        _buffer.Advance(byteCount);
    }

    public void WriteBytesNullable(byte[]? bytes)
    {
        if (bytes == null)
        {
            WriteInt(-1);
        }
        else
        {
            WriteInt(bytes.Length);
            WriteBytes(bytes);
        }
    }

    public byte[] ToArray()
    {
        return _buffer.WrittenSpan.ToArray();
    }

    public void Reset()
    {
        _buffer.Clear();
    }
}

/// <summary>
/// Pool policy for BytesWriter instances.
/// </summary>
public class BytesWriterPoolPolicy : IPooledObjectPolicy<BytesWriter>
{
    private readonly int _initialCapacity;

    public BytesWriterPoolPolicy(int initialCapacity = 1024)
    {
        _initialCapacity = initialCapacity;
    }

    public BytesWriter Create() => new BytesWriter(_initialCapacity);

    public bool Return(BytesWriter obj)
    {
        obj.Reset();
        return true;
    }
}

/// <summary>
/// Shared pool for BytesWriter instances.
/// </summary>
public static class BytesWriterPool
{
    private static readonly ObjectPool<BytesWriter> SmallPool =
        new DefaultObjectPool<BytesWriter>(new BytesWriterPoolPolicy(64), 32);
    private static readonly ObjectPool<BytesWriter> MediumPool =
        new DefaultObjectPool<BytesWriter>(new BytesWriterPoolPolicy(256), 16);
    private static readonly ObjectPool<BytesWriter> LargePool =
        new DefaultObjectPool<BytesWriter>(new BytesWriterPoolPolicy(1024), 8);

    public static BytesWriter RentSmall() => SmallPool.Get();
    public static BytesWriter RentMedium() => MediumPool.Get();
    public static BytesWriter RentLarge() => LargePool.Get();

    public static void Return(BytesWriter writer)
    {
        // Return to appropriate pool based on capacity
        // (The pools will reset and reuse)
        if (writer.Length <= 64)
            SmallPool.Return(writer);
        else if (writer.Length <= 256)
            MediumPool.Return(writer);
        else
            LargePool.Return(writer);
    }
}

/// <summary>
/// Builds protocol frames for responses.
/// Uses object pooling for BytesWriter instances to reduce allocations.
/// </summary>
public static class FrameBuilder
{
    // Reflection field for accessing Row's raw CQL bytes (protected Values property).
    // Used as fallback when the C# driver can't deserialize UDT columns without POCO mapping.
    private static readonly FieldInfo? RowRawValuesField =
        typeof(Cassandra.Row).GetField("<Values>k__BackingField", BindingFlags.NonPublic | BindingFlags.Instance);

    // Cache for empty rows frame template (without stream ID and latency)
    private static readonly byte[] EmptyRowsBodyTemplate;

    static FrameBuilder()
    {
        // Pre-build empty rows body template
        var writer = new BytesWriter(16);
        writer.WriteInt(Protocol.ResultKindRows);
        writer.WriteInt(0); // flags
        writer.WriteInt(0); // columns_count
        writer.WriteInt(0); // row_count
        EmptyRowsBodyTemplate = writer.ToArray();
    }

    public static byte[] BuildFrame(short stream, byte opcode, byte[] body)
    {
        var frame = new byte[Protocol.HeaderLength + body.Length];
        frame[0] = Protocol.VersionResponse;
        frame[1] = 0; // flags
        frame[2] = (byte)(stream >> 8);
        frame[3] = (byte)stream;
        frame[4] = opcode;
        frame[5] = (byte)(body.Length >> 24);
        frame[6] = (byte)(body.Length >> 16);
        frame[7] = (byte)(body.Length >> 8);
        frame[8] = (byte)body.Length;
        Array.Copy(body, 0, frame, Protocol.HeaderLength, body.Length);
        return frame;
    }

    public static byte[] BuildSessionCreatedFrame(short stream, ulong sessionId)
    {
        var writer = BytesWriterPool.RentSmall();
        try
        {
            writer.WriteULong(sessionId);
            return BuildFrame(stream, Protocol.OpcodeSessionCreated, writer.ToArray());
        }
        finally
        {
            BytesWriterPool.Return(writer);
        }
    }

    public static byte[] BuildVoidFrame(short stream, long latencyNs)
    {
        var writer = BytesWriterPool.RentSmall();
        try
        {
            writer.WriteInt(Protocol.ResultKindVoid);
            writer.WriteLong(latencyNs);
            return BuildFrame(stream, Protocol.OpcodeResult, writer.ToArray());
        }
        finally
        {
            BytesWriterPool.Return(writer);
        }
    }

    public static byte[] BuildPreparedFrame(short stream, string statementKey)
    {
        var writer = BytesWriterPool.RentMedium();
        try
        {
            writer.WriteInt(Protocol.ResultKindPrepared);
            writer.WriteString(statementKey);
            // ID bytes (from key)
            var idBytes = System.Text.Encoding.UTF8.GetBytes(statementKey);
            writer.WriteUShort((ushort)idBytes.Length);
            writer.WriteBytes(idBytes);
            // Metadata
            writer.WriteInt(0); // bind_metadata_flags
            writer.WriteInt(0); // bind_columns_count
            writer.WriteInt(0); // result_metadata_flags
            writer.WriteInt(0); // result_columns_count
            return BuildFrame(stream, Protocol.OpcodeResult, writer.ToArray());
        }
        finally
        {
            BytesWriterPool.Return(writer);
        }
    }

    public static byte[] BuildErrorFrame(short stream, int errorCode, string message)
    {
        var writer = BytesWriterPool.RentMedium();
        try
        {
            writer.WriteInt(errorCode);
            writer.WriteString(message);
            return BuildFrame(stream, Protocol.OpcodeError, writer.ToArray());
        }
        finally
        {
            BytesWriterPool.Return(writer);
        }
    }

    public static byte[] BuildRowsFrame(short stream, Cassandra.RowSet? rows, long latencyNs)
    {
        // Fast path for empty/null rows - use pre-built template
        if (rows == null)
        {
            var writer = BytesWriterPool.RentSmall();
            try
            {
                writer.WriteBytes(EmptyRowsBodyTemplate);
                writer.WriteLong(latencyNs);
                return BuildFrame(stream, Protocol.OpcodeResult, writer.ToArray());
            }
            finally
            {
                BytesWriterPool.Return(writer);
            }
        }

        var pooledWriter = BytesWriterPool.RentLarge();
        try
        {
            pooledWriter.WriteInt(Protocol.ResultKindRows);

            var columns = rows.Columns;
            pooledWriter.WriteInt(0); // flags
            pooledWriter.WriteInt(columns.Length);

            // Column metadata
            foreach (var col in columns)
            {
                pooledWriter.WriteString(col.Keyspace ?? "");
                pooledWriter.WriteString(col.Table ?? "");
                pooledWriter.WriteString(col.Name);
                pooledWriter.WriteUShort(GetTypeCode(col.Type));
            }

            // Materialize rows FIRST before checking count
            // (RowSet is lazily loaded - calling Any() before ToList() could consume rows)
            var rowList = rows.ToList();
            pooledWriter.WriteInt(rowList.Count);

            // Pre-compute which columns need raw byte fallback (UDT columns without POCO mapping)
            byte[][]? rawValues = null;

            foreach (var row in rowList)
            {
                for (int i = 0; i < columns.Length; i++)
                {
                    var value = row.GetValue<object>(i);

                    // Fallback for UDT columns: when the C# driver can't deserialize
                    // (returns null) but raw bytes exist, pass the raw CQL bytes through.
                    if (value == null && RowRawValuesField != null)
                    {
                        rawValues ??= RowRawValuesField.GetValue(row) as byte[][];
                        if (rawValues != null && i < rawValues.Length && rawValues[i] != null)
                        {
                            pooledWriter.WriteBytesNullable(rawValues[i]);
                            continue;
                        }
                    }

                    var encoded = ValueEncoder.Encode(value, columns[i].Type);
                    pooledWriter.WriteBytesNullable(encoded);
                }
                rawValues = null; // Reset for next row
            }

            pooledWriter.WriteLong(latencyNs);
            return BuildFrame(stream, Protocol.OpcodeResult, pooledWriter.ToArray());
        }
        finally
        {
            BytesWriterPool.Return(pooledWriter);
        }
    }

    private static ushort GetTypeCode(Type type)
    {
        // Handle nullable types
        var underlying = Nullable.GetUnderlyingType(type) ?? type;

        if (underlying == typeof(sbyte)) return Protocol.TypeTinyint;
        if (underlying == typeof(short)) return Protocol.TypeSmallint;
        if (underlying == typeof(int)) return Protocol.TypeInt;
        if (underlying == typeof(long)) return Protocol.TypeBigint;
        if (underlying == typeof(float)) return Protocol.TypeFloat;
        if (underlying == typeof(double)) return Protocol.TypeDouble;
        if (underlying == typeof(bool)) return Protocol.TypeBoolean;
        if (underlying == typeof(string)) return Protocol.TypeText;
        if (underlying == typeof(byte[])) return Protocol.TypeBlob;
        if (underlying == typeof(Guid)) return Protocol.TypeUuid;
        if (underlying == typeof(DateTime)) return Protocol.TypeTimestamp;
        if (underlying == typeof(DateTimeOffset)) return Protocol.TypeTimestamp;
        if (underlying == typeof(System.Net.IPAddress)) return Protocol.TypeInet;
        if (underlying == typeof(decimal)) return Protocol.TypeDecimal;
        if (underlying == typeof(System.Numerics.BigInteger)) return Protocol.TypeVarint;
        if (underlying == typeof(Cassandra.LocalDate)) return Protocol.TypeDate;
        if (underlying == typeof(Cassandra.LocalTime)) return Protocol.TypeTime;
        if (underlying == typeof(Cassandra.Duration)) return Protocol.TypeDuration;
        if (underlying == typeof(Cassandra.TimeUuid)) return Protocol.TypeTimeuuid;

        // Collections and vectors
        if (underlying.IsGenericType)
        {
            var genDef = underlying.GetGenericTypeDefinition();
            if (genDef == typeof(Cassandra.CqlVector<>))
                return Protocol.TypeVector;
            if (genDef == typeof(List<>) || genDef == typeof(IList<>))
                return Protocol.TypeList;
            if (genDef == typeof(HashSet<>) || genDef == typeof(SortedSet<>) || genDef == typeof(ISet<>))
                return Protocol.TypeSet;
            if (genDef == typeof(Dictionary<,>) || genDef == typeof(IDictionary<,>) || genDef == typeof(SortedDictionary<,>))
                return Protocol.TypeMap;
        }

        // Default to blob for unknown types
        return Protocol.TypeBlob;
    }
}

public class ProtocolException : Exception
{
    public ProtocolException(string message) : base(message)
    {
    }

    public ProtocolException(string message, Exception inner) : base(message, inner)
    {
    }
}

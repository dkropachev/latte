using System.Collections.Concurrent;
using System.Diagnostics;
using System.Text;
using System.Text.RegularExpressions;
using Cassandra;
using Microsoft.Extensions.Logging;

namespace LatteDriver;

/// <summary>
/// Handles incoming protocol requests and produces responses.
/// </summary>
public partial class RequestHandler
{
    private readonly SessionRegistry _sessions;
    private readonly ILogger _logger;

    // Cache for scalar coercion functions: (inputType, targetTypeCode) -> coercion delegate
    private static readonly ConcurrentDictionary<(Type, ColumnTypeCode), Func<object, object?>> ScalarCoercionCache = new();

    // Pre-built coercion functions for common type conversions
    private static readonly Func<object, object?> LongToInt = v => (int)(long)v;
    private static readonly Func<object, object?> LongToSmallInt = v => (short)(long)v;
    private static readonly Func<object, object?> LongToTinyInt = v => (sbyte)(long)v;
    private static readonly Func<object, object?> LongToTimestamp = v =>
    {
        var longVal = (long)v;
        var clampedMs = Math.Clamp(longVal, -62135596800000L, 253402300799999L);
        return DateTimeOffset.FromUnixTimeMilliseconds(clampedMs);
    };
    private static readonly Func<object, object?> DoubleToFloat = v => (float)(double)v;
    private static readonly Func<object, object?> Identity = v => v;

    static RequestHandler()
    {
        // Pre-populate common scalar coercions
        ScalarCoercionCache[(typeof(long), ColumnTypeCode.Int)] = LongToInt;
        ScalarCoercionCache[(typeof(long), ColumnTypeCode.SmallInt)] = LongToSmallInt;
        ScalarCoercionCache[(typeof(long), ColumnTypeCode.TinyInt)] = LongToTinyInt;
        ScalarCoercionCache[(typeof(long), ColumnTypeCode.Timestamp)] = LongToTimestamp;
        ScalarCoercionCache[(typeof(long), ColumnTypeCode.Bigint)] = Identity;
        ScalarCoercionCache[(typeof(double), ColumnTypeCode.Float)] = DoubleToFloat;
        ScalarCoercionCache[(typeof(double), ColumnTypeCode.Double)] = Identity;
        ScalarCoercionCache[(typeof(int), ColumnTypeCode.Int)] = Identity;
        ScalarCoercionCache[(typeof(short), ColumnTypeCode.SmallInt)] = Identity;
        ScalarCoercionCache[(typeof(float), ColumnTypeCode.Float)] = Identity;
    }

    public RequestHandler(SessionRegistry sessions, ILogger<RequestHandler> logger)
    {
        _sessions = sessions;
        _logger = logger;
    }

    public async Task<byte[]> HandleFrameAsync(Frame frame)
    {
        try
        {
            return frame.Opcode switch
            {
                Protocol.OpcodeCreateSession => await HandleCreateSession(frame),
                Protocol.OpcodeQuery => await HandleQuery(frame),
                Protocol.OpcodePrepare => await HandlePrepare(frame),
                Protocol.OpcodeExecute => await HandleExecute(frame),
                Protocol.OpcodeBatch => await HandleBatch(frame),
                _ => FrameBuilder.BuildErrorFrame(frame.Stream, Protocol.ErrorCodeProtocol,
                    $"Unknown opcode: {frame.Opcode}")
            };
        }
        catch (Exception ex)
        {
            _logger.LogError(ex, "Error handling frame opcode {Opcode}", frame.Opcode);
            return FrameBuilder.BuildErrorFrame(frame.Stream, Protocol.ErrorCodeServer,
                $"Internal error: {ex.Message}");
        }
    }

    private async Task<byte[]> HandleCreateSession(Frame frame)
    {
        var body = frame.Body.Span;
        var pos = 0;
        var parameters = ReadStringMap(body, ref pos);

        _logger.LogDebug("CREATE_SESSION with {Count} parameters", parameters.Count);

        var sessionId = await _sessions.CreateSessionAsync(parameters);
        return FrameBuilder.BuildSessionCreatedFrame(frame.Stream, sessionId);
    }

    private async Task<byte[]> HandleQuery(Frame frame)
    {
        var body = frame.Body.Span;
        var pos = 0;
        var sessionId = ReadULong(body, ref pos);
        var query = ReadLongString(body, ref pos);
        var consistency = ReadUShort(body, ref pos);
        var flags = body[pos++];

        _logger.LogDebug("QUERY on session {Id}: {Query}", sessionId, query);

        var session = _sessions.Get(sessionId);
        if (session == null)
        {
            return FrameBuilder.BuildErrorFrame(frame.Stream, Protocol.ErrorCodeServer,
                $"Session {sessionId} not found");
        }

        var statement = new SimpleStatement(query);
        statement.SetConsistencyLevel(MapConsistency(consistency));

        var sw = Stopwatch.StartNew();
        var result = await session.Session.ExecuteAsync(statement);
        var latencyNs = sw.ElapsedTicks * 1_000_000_000L / Stopwatch.Frequency;

        return FrameBuilder.BuildRowsFrame(frame.Stream, result, latencyNs);
    }

    private async Task<byte[]> HandlePrepare(Frame frame)
    {
        var body = frame.Body.Span;
        var pos = 0;
        var sessionId = ReadULong(body, ref pos);
        var query = ReadLongString(body, ref pos);
        var statementKey = ReadString(body, ref pos);

        _logger.LogDebug("PREPARE on session {Id}, key '{Key}': {Query}", sessionId, statementKey, query);

        var session = _sessions.Get(sessionId);
        if (session == null)
        {
            return FrameBuilder.BuildErrorFrame(frame.Stream, Protocol.ErrorCodeServer,
                $"Session {sessionId} not found");
        }

        await session.PrepareAsync(query, statementKey);
        return FrameBuilder.BuildPreparedFrame(frame.Stream, statementKey);
    }

    private async Task<byte[]> HandleExecute(Frame frame)
    {
        var body = frame.Body.Span;
        var pos = 0;
        var sessionId = ReadULong(body, ref pos);
        var statementKey = ReadString(body, ref pos);
        var consistency = ReadUShort(body, ref pos);
        var flags = body[pos++];

        var session = _sessions.Get(sessionId);
        if (session == null)
        {
            return FrameBuilder.BuildErrorFrame(frame.Stream, Protocol.ErrorCodeServer,
                $"Session {sessionId} not found");
        }

        var cached = session.GetPrepared(statementKey);
        if (cached == null)
        {
            return FrameBuilder.BuildErrorFrame(frame.Stream, Protocol.ErrorCodeUnprepared,
                $"Statement '{statementKey}' not prepared");
        }

        object?[]? values = null;
        if ((flags & 0x01) != 0)
        {
            var valueCount = ReadUShort(body, ref pos);
            values = new object?[valueCount];
            for (int i = 0; i < valueCount; i++)
            {
                var rawValue = ValueDecoder.ReadRawValue(body, ref pos);
                var columnSpec = cached.BindColumns != null && i < cached.BindColumns.Length
                    ? cached.BindColumns[i]
                    : null;

                // Coerce the value based on target column type if available
                var decoded = rawValue.Decode(columnSpec);
                values[i] = CoerceValue(decoded, columnSpec, rawValue.TypeCode);
            }
        }

        var bound = cached.Statement.Bind(values ?? Array.Empty<object?>());
        bound.SetConsistencyLevel(MapConsistency(consistency));

        var sw = Stopwatch.StartNew();
        RowSet? result = null;
        try
        {
            result = await session.Session.ExecuteAsync(bound);
        }
        catch (Exception ex)
        {
            _logger.LogError(ex, "Execute failed for statement {Key}", statementKey);
            throw;
        }
        var latencyNs = sw.ElapsedTicks * 1_000_000_000L / Stopwatch.Frequency;

        // For INSERT/UPDATE/DELETE, return VOID result
        // Check only Columns.Length - a SELECT always has column metadata even with 0 rows,
        // while void statements (INSERT/UPDATE/DELETE) have no column metadata.
        // Avoid calling result.Any() as it can consume the lazy RowSet iterator.
        if (result != null && result.Columns.Length == 0)
        {
            return FrameBuilder.BuildVoidFrame(frame.Stream, latencyNs);
        }

        return FrameBuilder.BuildRowsFrame(frame.Stream, result, latencyNs);
    }

    private async Task<byte[]> HandleBatch(Frame frame)
    {
        var body = frame.Body.Span;
        var pos = 0;
        var sessionId = ReadULong(body, ref pos);
        var batchType = body[pos++];
        var statementCount = ReadUShort(body, ref pos);

        var session = _sessions.Get(sessionId);
        if (session == null)
        {
            return FrameBuilder.BuildErrorFrame(frame.Stream, Protocol.ErrorCodeServer,
                $"Session {sessionId} not found");
        }

        var cassandraBatchType = batchType switch
        {
            0 => BatchType.Logged,
            1 => BatchType.Unlogged,
            2 => BatchType.Counter,
            _ => BatchType.Logged
        };

        var batch = new BatchStatement().SetBatchType(cassandraBatchType);

        for (int s = 0; s < statementCount; s++)
        {
            var kind = body[pos++];
            if (kind != 1)
            {
                return FrameBuilder.BuildErrorFrame(frame.Stream, Protocol.ErrorCodeProtocol,
                    $"Only prepared statements supported in batch (kind=1), got kind={kind}");
            }

            var statementKey = ReadString(body, ref pos);
            var valueCount = ReadUShort(body, ref pos);

            var cached = session.GetPrepared(statementKey);
            if (cached == null)
            {
                return FrameBuilder.BuildErrorFrame(frame.Stream, Protocol.ErrorCodeUnprepared,
                    $"Statement '{statementKey}' not prepared");
            }

            var values = new object?[valueCount];
            for (int i = 0; i < valueCount; i++)
            {
                var rawValue = ValueDecoder.ReadRawValue(body, ref pos);
                var columnSpec = cached.BindColumns != null && i < cached.BindColumns.Length
                    ? cached.BindColumns[i]
                    : null;
                var decoded = rawValue.Decode(columnSpec);
                values[i] = CoerceValue(decoded, columnSpec, rawValue.TypeCode);
            }

            var bound = cached.Statement.Bind(values);
            batch.Add(bound);
        }

        var consistency = ReadUShort(body, ref pos);
        var batchFlags = body[pos++];

        batch.SetConsistencyLevel(MapConsistency(consistency));

        var sw = Stopwatch.StartNew();
        await session.Session.ExecuteAsync(batch);
        var latencyNs = sw.ElapsedTicks * 1_000_000_000L / Stopwatch.Frequency;

        return FrameBuilder.BuildVoidFrame(frame.Stream, latencyNs);
    }

    /// <summary>
    /// Coerces a decoded value to match the target column type if needed.
    /// Uses cached coercion functions for scalar types to avoid repeated type dispatch.
    /// </summary>
    private object? CoerceValue(object? value, Cassandra.ColumnDesc? columnSpec, ushort wireTypeCode)
    {
        if (value == null || columnSpec == null)
            return value;

        var targetType = columnSpec.TypeCode;
        var inputType = value.GetType();

        // Fast path: check scalar coercion cache first
        if (ScalarCoercionCache.TryGetValue((inputType, targetType), out var cachedCoercion))
        {
            return cachedCoercion(value);
        }

        // String to various types
        if (value is string strVal)
        {
            if (targetType == ColumnTypeCode.Date)
                return ParseLocalDate(strVal);
            if (targetType == ColumnTypeCode.Time)
                return ParseLocalTime(strVal);
            if (targetType == ColumnTypeCode.Timeuuid && Guid.TryParse(strVal, out var guid))
                return (TimeUuid)guid;
            if (targetType == ColumnTypeCode.Inet &&
                System.Net.IPAddress.TryParse(strVal, out var ip))
                return ip;
            if (targetType == ColumnTypeCode.Duration)
                return ParseDuration(strVal);
            if (targetType == ColumnTypeCode.Decimal &&
                decimal.TryParse(strVal, System.Globalization.NumberStyles.Any,
                    System.Globalization.CultureInfo.InvariantCulture, out var dec))
                return dec;
        }

        // Handle collections - coerce element types recursively
        if (value is IList<object?> list)
        {
            // Check if collection contains UDTs (which we can't handle) - skip entire collection
            if (CollectionContainsUdt(columnSpec?.TypeInfo))
            {
                return null; // Skip collections containing UDTs
            }

            if (targetType == ColumnTypeCode.Set)
            {
                var coercedSet = new HashSet<object?>(list.Count);
                for (int i = 0; i < list.Count; i++)
                    coercedSet.Add(CoerceCollectionElement(list[i], columnSpec?.TypeInfo, isKey: false));
                return coercedSet;
            }
            if (targetType == ColumnTypeCode.List)
            {
                var coercedList = new List<object?>(list.Count);
                for (int i = 0; i < list.Count; i++)
                    coercedList.Add(CoerceCollectionElement(list[i], columnSpec?.TypeInfo, isKey: false));
                return coercedList;
            }
        }

        // Handle maps - coerce key and value types recursively
        if (value is IDictionary<object, object?> dict && targetType == ColumnTypeCode.Map)
        {
            // Check if map contains UDTs - skip if so
            if (MapContainsUdt(columnSpec?.TypeInfo))
            {
                return null; // Skip maps containing UDTs
            }

            var coercedDict = new Dictionary<object, object?>();
            foreach (var kvp in dict)
            {
                var coercedKey = CoerceCollectionElement(kvp.Key, columnSpec?.TypeInfo, isKey: true) ?? kvp.Key;
                var coercedValue = CoerceCollectionElement(kvp.Value, columnSpec?.TypeInfo, isKey: false);
                coercedDict[coercedKey] = coercedValue;
            }
            return coercedDict;
        }

        // Handle float arrays for vector columns (C# driver needs CqlVector)
        if (value is float[] floatArray)
        {
            return new CqlVector<float>(floatArray);
        }

        // Handle UDT - C# driver requires POCO mapping via UdtMap, which we can't do dynamically.
        // Pass null for UDT columns as a workaround (documented limitation).
        if (value is Dictionary<string, object?> && targetType == ColumnTypeCode.Udt)
        {
            return null; // Skip UDT values - documented limitation
        }

        // Handle Tuple - need to convert our decoded tuple to proper C# Tuple
        if (value is Tuple<object?[]> tupleData && targetType == ColumnTypeCode.Tuple)
        {
            if (columnSpec?.TypeInfo is TupleColumnInfo tupleInfo)
            {
                return CreateTupleValue(tupleData.Item1, tupleInfo);
            }
        }

        return value;
    }

    /// <summary>
    /// Creates a tuple value that can be bound to a prepared statement.
    /// </summary>
    private static object CreateTupleValue(object?[] elements, TupleColumnInfo tupleInfo)
    {
        // Coerce each tuple element to the correct type
        var coercedElements = new object?[elements.Length];
        for (int i = 0; i < elements.Length && i < tupleInfo.Elements.Count; i++)
        {
            var element = elements[i];
            var elementType = tupleInfo.Elements[i];

            if (element == null)
            {
                coercedElements[i] = null;
                continue;
            }

            // Skip UDT inside tuple - not supported
            if (element is Dictionary<string, object?> && elementType.TypeCode == ColumnTypeCode.Udt)
            {
                coercedElements[i] = null;
                continue;
            }

            // Handle collections inside tuple - skip if they contain UDTs
            if (element is IList<object?> list)
            {
                if (CollectionContainsUdt(elementType.TypeInfo))
                {
                    coercedElements[i] = null;
                    continue;
                }
                if (elementType.TypeCode == ColumnTypeCode.List && elementType.TypeInfo is ListColumnInfo listInfo)
                {
                    var coercedList = new List<object?>(list.Count);
                    for (int j = 0; j < list.Count; j++)
                        coercedList.Add(CoerceTupleCollectionElement(list[j], listInfo.ValueTypeCode, listInfo.ValueTypeInfo));
                    coercedElements[i] = coercedList;
                    continue;
                }
                if (elementType.TypeCode == ColumnTypeCode.Set && elementType.TypeInfo is SetColumnInfo setInfo)
                {
                    var coercedSet = new HashSet<object?>(list.Count);
                    for (int j = 0; j < list.Count; j++)
                        coercedSet.Add(CoerceTupleCollectionElement(list[j], setInfo.KeyTypeCode, setInfo.KeyTypeInfo));
                    coercedElements[i] = coercedSet;
                    continue;
                }
            }

            if (element is IDictionary<object, object?> mapVal && elementType.TypeCode == ColumnTypeCode.Map)
            {
                if (MapContainsUdt(elementType.TypeInfo))
                {
                    coercedElements[i] = null;
                    continue;
                }
                if (elementType.TypeInfo is MapColumnInfo mapInfo)
                {
                    var coercedDict = new Dictionary<object, object?>();
                    foreach (var kvp in mapVal)
                    {
                        var coercedKey = CoerceTupleCollectionElement(kvp.Key, mapInfo.KeyTypeCode, mapInfo.KeyTypeInfo) ?? kvp.Key;
                        var coercedValue = CoerceTupleCollectionElement(kvp.Value, mapInfo.ValueTypeCode, mapInfo.ValueTypeInfo);
                        coercedDict[coercedKey] = coercedValue;
                    }
                    coercedElements[i] = coercedDict;
                    continue;
                }
            }

            // Handle numeric coercion
            if (element is long longVal)
            {
                coercedElements[i] = elementType.TypeCode switch
                {
                    ColumnTypeCode.Int => (int)longVal,
                    ColumnTypeCode.SmallInt => (short)longVal,
                    ColumnTypeCode.TinyInt => (sbyte)longVal,
                    ColumnTypeCode.Bigint => longVal,
                    _ => element
                };
                continue;
            }

            if (element is double doubleVal && elementType.TypeCode == ColumnTypeCode.Float)
            {
                coercedElements[i] = (float)doubleVal;
                continue;
            }

            coercedElements[i] = element;
        }

        // Create proper Tuple type based on element count
        return coercedElements.Length switch
        {
            1 => Tuple.Create(coercedElements[0]),
            2 => Tuple.Create(coercedElements[0], coercedElements[1]),
            3 => Tuple.Create(coercedElements[0], coercedElements[1], coercedElements[2]),
            4 => Tuple.Create(coercedElements[0], coercedElements[1], coercedElements[2], coercedElements[3]),
            5 => Tuple.Create(coercedElements[0], coercedElements[1], coercedElements[2], coercedElements[3], coercedElements[4]),
            6 => Tuple.Create(coercedElements[0], coercedElements[1], coercedElements[2], coercedElements[3], coercedElements[4], coercedElements[5]),
            7 => Tuple.Create(coercedElements[0], coercedElements[1], coercedElements[2], coercedElements[3], coercedElements[4], coercedElements[5], coercedElements[6]),
            _ => Tuple.Create(coercedElements[0], coercedElements[1], coercedElements[2], coercedElements[3], coercedElements[4], coercedElements[5], coercedElements[6], coercedElements[7..])
        };
    }

    /// <summary>
    /// Coerces collection elements for tuple fields.
    /// </summary>
    private static object? CoerceTupleCollectionElement(object? value, ColumnTypeCode targetType, IColumnInfo? typeInfo)
    {
        if (value == null)
            return null;

        // Skip UDTs in collections - not supported
        if (value is Dictionary<string, object?> && targetType == ColumnTypeCode.Udt)
        {
            return null;
        }

        // Handle numeric coercion
        if (value is long longVal)
        {
            return targetType switch
            {
                ColumnTypeCode.Int => (int)longVal,
                ColumnTypeCode.SmallInt => (short)longVal,
                ColumnTypeCode.TinyInt => (sbyte)longVal,
                _ => value
            };
        }

        if (value is double doubleVal && targetType == ColumnTypeCode.Float)
        {
            return (float)doubleVal;
        }

        return value;
    }

    /// <summary>
    /// Checks if a collection type contains UDT elements.
    /// </summary>
    private static bool CollectionContainsUdt(IColumnInfo? typeInfo)
    {
        return typeInfo switch
        {
            ListColumnInfo listInfo => listInfo.ValueTypeCode == ColumnTypeCode.Udt ||
                                       CollectionContainsUdt(listInfo.ValueTypeInfo),
            SetColumnInfo setInfo => setInfo.KeyTypeCode == ColumnTypeCode.Udt ||
                                     CollectionContainsUdt(setInfo.KeyTypeInfo),
            _ => false
        };
    }

    /// <summary>
    /// Checks if a map type contains UDT keys or values.
    /// </summary>
    private static bool MapContainsUdt(IColumnInfo? typeInfo)
    {
        if (typeInfo is not MapColumnInfo mapInfo)
            return false;

        return mapInfo.KeyTypeCode == ColumnTypeCode.Udt ||
               mapInfo.ValueTypeCode == ColumnTypeCode.Udt ||
               CollectionContainsUdt(mapInfo.KeyTypeInfo) ||
               CollectionContainsUdt(mapInfo.ValueTypeInfo) ||
               MapContainsUdt(mapInfo.KeyTypeInfo) ||
               MapContainsUdt(mapInfo.ValueTypeInfo);
    }

    /// <summary>
    /// Coerces a collection element value recursively.
    /// </summary>
    private static object? CoerceCollectionElement(object? value, IColumnInfo? typeInfo, bool isKey)
    {
        if (value == null)
            return null;

        ColumnTypeCode targetType;
        IColumnInfo? nestedTypeInfo = null;

        switch (typeInfo)
        {
            case ListColumnInfo listInfo:
                targetType = listInfo.ValueTypeCode;
                nestedTypeInfo = listInfo.ValueTypeInfo;
                break;
            case SetColumnInfo setInfo:
                targetType = setInfo.KeyTypeCode;
                nestedTypeInfo = setInfo.KeyTypeInfo;
                break;
            case MapColumnInfo mapInfo:
                targetType = isKey ? mapInfo.KeyTypeCode : mapInfo.ValueTypeCode;
                nestedTypeInfo = isKey ? mapInfo.KeyTypeInfo : mapInfo.ValueTypeInfo;
                break;
            default:
                return value;
        }

        // Skip UDT in collection - not supported (C# driver requires POCO mapping)
        if (value is Dictionary<string, object?> && targetType == ColumnTypeCode.Udt)
        {
            return null;
        }

        // Handle vectors in collections - convert float[] to CqlVector<float>
        if (value is float[] vectorArray)
        {
            return new CqlVector<float>(vectorArray);
        }

        // Coerce scalar types
        if (value is long longVal)
        {
            return targetType switch
            {
                ColumnTypeCode.Int => (int)longVal,
                ColumnTypeCode.SmallInt => (short)longVal,
                ColumnTypeCode.TinyInt => (sbyte)longVal,
                _ => value
            };
        }

        if (value is double doubleVal && targetType == ColumnTypeCode.Float)
        {
            return (float)doubleVal;
        }

        // Handle nested collections
        if (value is IList<object?> nestedList)
        {
            if (targetType == ColumnTypeCode.Set)
            {
                var coercedSet = new HashSet<object?>(nestedList.Count);
                for (int i = 0; i < nestedList.Count; i++)
                    coercedSet.Add(CoerceCollectionElement(nestedList[i], nestedTypeInfo, isKey: false));
                return coercedSet;
            }
            if (targetType == ColumnTypeCode.List)
            {
                var coercedList = new List<object?>(nestedList.Count);
                for (int i = 0; i < nestedList.Count; i++)
                    coercedList.Add(CoerceCollectionElement(nestedList[i], nestedTypeInfo, isKey: false));
                return coercedList;
            }
        }

        if (value is IDictionary<object, object?> nestedDict && targetType == ColumnTypeCode.Map)
        {
            var coercedDict = new Dictionary<object, object?>();
            foreach (var kvp in nestedDict)
            {
                var coercedKey = CoerceCollectionElement(kvp.Key, nestedTypeInfo, isKey: true) ?? kvp.Key;
                var coercedVal = CoerceCollectionElement(kvp.Value, nestedTypeInfo, isKey: false);
                coercedDict[coercedKey] = coercedVal;
            }
            return coercedDict;
        }

        return value;
    }

    private static LocalDate? ParseLocalDate(string value)
    {
        if (DateTime.TryParse(value, System.Globalization.CultureInfo.InvariantCulture,
            System.Globalization.DateTimeStyles.None, out var dt))
        {
            return new LocalDate(dt.Year, dt.Month, dt.Day);
        }
        return null;
    }

    private static LocalTime? ParseLocalTime(string value)
    {
        if (TimeSpan.TryParse(value, System.Globalization.CultureInfo.InvariantCulture, out var ts))
        {
            return new LocalTime(ts.Hours, ts.Minutes, ts.Seconds, (int)(ts.Ticks % TimeSpan.TicksPerSecond * 100));
        }
        return null;
    }

    [GeneratedRegex(@"^(?:(\d+)y)?(?:(\d+)mo)?(?:(\d+)w)?(?:(\d+)d)?(?:(\d+)h)?(?:(\d+)m)?(?:(\d+)s)?(?:(\d+)ms)?(?:(\d+)us)?(?:(\d+)ns)?$")]
    private static partial Regex DurationRegex();

    private static Duration ParseDuration(string value)
    {
        // Parse duration strings like "1mo2d3h4m5s"
        int months = 0, days = 0;
        long nanos = 0;

        var match = DurationRegex().Match(value);

        if (match.Success)
        {
            if (match.Groups[1].Success)
                months += int.Parse(match.Groups[1].Value) * 12;
            if (match.Groups[2].Success)
                months += int.Parse(match.Groups[2].Value);
            if (match.Groups[3].Success)
                days += int.Parse(match.Groups[3].Value) * 7;
            if (match.Groups[4].Success)
                days += int.Parse(match.Groups[4].Value);
            if (match.Groups[5].Success)
                nanos += long.Parse(match.Groups[5].Value) * 3600_000_000_000L;
            if (match.Groups[6].Success)
                nanos += long.Parse(match.Groups[6].Value) * 60_000_000_000L;
            if (match.Groups[7].Success)
                nanos += long.Parse(match.Groups[7].Value) * 1_000_000_000L;
            if (match.Groups[8].Success)
                nanos += long.Parse(match.Groups[8].Value) * 1_000_000L;
            if (match.Groups[9].Success)
                nanos += long.Parse(match.Groups[9].Value) * 1_000L;
            if (match.Groups[10].Success)
                nanos += long.Parse(match.Groups[10].Value);
        }

        return new Duration(months, days, nanos);
    }

    private static ConsistencyLevel MapConsistency(ushort value)
    {
        return value switch
        {
            Protocol.ConsistencyAny => ConsistencyLevel.Any,
            Protocol.ConsistencyOne => ConsistencyLevel.One,
            Protocol.ConsistencyTwo => ConsistencyLevel.Two,
            Protocol.ConsistencyThree => ConsistencyLevel.Three,
            Protocol.ConsistencyQuorum => ConsistencyLevel.Quorum,
            Protocol.ConsistencyAll => ConsistencyLevel.All,
            Protocol.ConsistencyLocalQuorum => ConsistencyLevel.LocalQuorum,
            Protocol.ConsistencyEachQuorum => ConsistencyLevel.EachQuorum,
            Protocol.ConsistencyLocalOne => ConsistencyLevel.LocalOne,
            _ => ConsistencyLevel.LocalQuorum
        };
    }

    // Binary reading helpers - work with spans to avoid allocations
    private static ushort ReadUShort(ReadOnlySpan<byte> data, ref int pos)
    {
        var value = (ushort)((data[pos] << 8) | data[pos + 1]);
        pos += 2;
        return value;
    }

    private static ulong ReadULong(ReadOnlySpan<byte> data, ref int pos)
    {
        ulong value = ((ulong)data[pos] << 56) | ((ulong)data[pos + 1] << 48) |
                      ((ulong)data[pos + 2] << 40) | ((ulong)data[pos + 3] << 32) |
                      ((ulong)data[pos + 4] << 24) | ((ulong)data[pos + 5] << 16) |
                      ((ulong)data[pos + 6] << 8) | data[pos + 7];
        pos += 8;
        return value;
    }

    private static string ReadString(ReadOnlySpan<byte> data, ref int pos)
    {
        var length = ReadUShort(data, ref pos);
        var str = Encoding.UTF8.GetString(data.Slice(pos, length));
        pos += length;
        return str;
    }

    private static string ReadLongString(ReadOnlySpan<byte> data, ref int pos)
    {
        var length = (data[pos] << 24) | (data[pos + 1] << 16) | (data[pos + 2] << 8) | data[pos + 3];
        pos += 4;
        var str = Encoding.UTF8.GetString(data.Slice(pos, length));
        pos += length;
        return str;
    }

    private static Dictionary<string, string> ReadStringMap(ReadOnlySpan<byte> data, ref int pos)
    {
        var count = ReadUShort(data, ref pos);
        var map = new Dictionary<string, string>(count);
        for (int i = 0; i < count; i++)
        {
            var key = ReadString(data, ref pos);
            var value = ReadString(data, ref pos);
            map[key] = value;
        }
        return map;
    }
}

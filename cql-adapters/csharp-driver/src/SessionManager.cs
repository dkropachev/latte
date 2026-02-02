using System.Collections.Concurrent;
using Cassandra;
using Microsoft.Extensions.Logging;

namespace LatteDriver;

/// <summary>
/// Caches prepared statements for a driver session.
/// </summary>
public class CachedPrepared
{
    public PreparedStatement Statement { get; }
    public ColumnDesc[]? BindColumns { get; }

    public CachedPrepared(PreparedStatement statement)
    {
        Statement = statement;
        BindColumns = statement.Variables?.Columns;
    }
}

/// <summary>
/// Wraps a Cassandra session with prepared statement caching.
/// </summary>
public class DriverSession : IDisposable
{
    private readonly ISession _session;
    private readonly ConcurrentDictionary<string, CachedPrepared> _preparedCache = new();
    private readonly ILogger _logger;
    private bool _disposed;

    public DriverSession(ISession session, ILogger logger)
    {
        _session = session;
        _logger = logger;
    }

    public ISession Session => _session;

    public CachedPrepared? GetPrepared(string statementKey)
    {
        _preparedCache.TryGetValue(statementKey, out var cached);
        return cached;
    }

    public async Task<CachedPrepared> PrepareAsync(string query, string statementKey)
    {
        var prepared = await _session.PrepareAsync(query);
        var cached = new CachedPrepared(prepared);
        _preparedCache[statementKey] = cached;
        _logger.LogDebug("Prepared statement '{Key}' for query: {Query}", statementKey, query);
        return cached;
    }

    public void Dispose()
    {
        if (_disposed) return;
        _disposed = true;
        _session.Dispose();
    }
}

/// <summary>
/// Manages driver sessions and their lifecycle.
/// </summary>
public class SessionRegistry : IDisposable
{
    private readonly ConcurrentDictionary<ulong, DriverSession> _sessions = new();
    private ulong _nextSessionId = 1;
    private readonly ILoggerFactory _loggerFactory;
    private readonly ILogger _logger;
    private bool _disposed;

    public SessionRegistry(ILoggerFactory loggerFactory)
    {
        _loggerFactory = loggerFactory;
        _logger = loggerFactory.CreateLogger<SessionRegistry>();
    }

    public DriverSession? Get(ulong sessionId)
    {
        _sessions.TryGetValue(sessionId, out var session);
        return session;
    }

    public async Task<ulong> CreateSessionAsync(Dictionary<string, string> parameters)
    {
        var sessionId = Interlocked.Increment(ref _nextSessionId);

        // Parse parameters
        var contactPoints = parameters.GetValueOrDefault("contact_points", "");
        var keyspace = parameters.GetValueOrDefault("keyspace");
        var username = parameters.GetValueOrDefault("username");
        var password = parameters.GetValueOrDefault("password");
        var datacenter = parameters.GetValueOrDefault("datacenter");
        var connectionsPerHost = 2;
        if (parameters.TryGetValue("connections_per_shard", out var connStr) && int.TryParse(connStr, out var connVal))
            connectionsPerHost = connVal;

        int requestTimeoutMs = 12000; // 12 seconds default
        if (parameters.TryGetValue("request_timeout_ms", out var timeoutStr) && int.TryParse(timeoutStr, out var timeoutVal))
            requestTimeoutMs = timeoutVal;

        _logger.LogInformation("Creating session {Id} to {ContactPoints} (keyspace: {Keyspace})",
            sessionId, contactPoints, keyspace ?? "none");

        // Build cluster
        var builder = Cluster.Builder();

        // Parse and add contact points
        foreach (var cp in contactPoints.Split(',', StringSplitOptions.RemoveEmptyEntries | StringSplitOptions.TrimEntries))
        {
            var parts = cp.Split(':');
            var host = parts[0];
            var port = parts.Length > 1 && int.TryParse(parts[1], out var p) ? p : 9042;
            builder.AddContactPoint(host).WithPort(port);
        }

        // Authentication
        if (!string.IsNullOrEmpty(username))
        {
            builder.WithCredentials(username, password ?? "");
        }

        // Connection pool
        builder.WithPoolingOptions(
            new PoolingOptions()
                .SetCoreConnectionsPerHost(HostDistance.Local, connectionsPerHost)
                .SetMaxConnectionsPerHost(HostDistance.Local, connectionsPerHost * 2));

        // Socket options
        builder.WithSocketOptions(
            new SocketOptions()
                .SetReadTimeoutMillis(requestTimeoutMs)
                .SetConnectTimeoutMillis(requestTimeoutMs));

        // Query options
        var consistency = ConsistencyLevel.LocalQuorum;
        if (parameters.TryGetValue("consistency", out var consStr))
        {
            consistency = ParseConsistency(consStr);
        }
        builder.WithQueryOptions(new QueryOptions().SetConsistencyLevel(consistency));

        // Load balancing
        ILoadBalancingPolicy loadBalancing;
        if (!string.IsNullOrEmpty(datacenter))
        {
            loadBalancing = new TokenAwarePolicy(new DCAwareRoundRobinPolicy(datacenter));
        }
        else
        {
            loadBalancing = new TokenAwarePolicy(new RoundRobinPolicy());
        }
        builder.WithLoadBalancingPolicy(loadBalancing);

        // Build and connect
        var cluster = builder.Build();
        ISession session;
        if (!string.IsNullOrEmpty(keyspace))
        {
            session = await cluster.ConnectAsync(keyspace);
        }
        else
        {
            session = await cluster.ConnectAsync();
        }

        var driverSession = new DriverSession(session, _loggerFactory.CreateLogger<DriverSession>());
        _sessions[sessionId] = driverSession;

        _logger.LogInformation("Session {Id} created successfully", sessionId);
        return sessionId;
    }

    private static ConsistencyLevel ParseConsistency(string value)
    {
        if (value.Equals("ANY", StringComparison.OrdinalIgnoreCase))
            return ConsistencyLevel.Any;
        if (value.Equals("ONE", StringComparison.OrdinalIgnoreCase))
            return ConsistencyLevel.One;
        if (value.Equals("TWO", StringComparison.OrdinalIgnoreCase))
            return ConsistencyLevel.Two;
        if (value.Equals("THREE", StringComparison.OrdinalIgnoreCase))
            return ConsistencyLevel.Three;
        if (value.Equals("QUORUM", StringComparison.OrdinalIgnoreCase))
            return ConsistencyLevel.Quorum;
        if (value.Equals("ALL", StringComparison.OrdinalIgnoreCase))
            return ConsistencyLevel.All;
        if (value.Equals("LOCAL_QUORUM", StringComparison.OrdinalIgnoreCase))
            return ConsistencyLevel.LocalQuorum;
        if (value.Equals("EACH_QUORUM", StringComparison.OrdinalIgnoreCase))
            return ConsistencyLevel.EachQuorum;
        if (value.Equals("LOCAL_ONE", StringComparison.OrdinalIgnoreCase))
            return ConsistencyLevel.LocalOne;
        if (value.Equals("SERIAL", StringComparison.OrdinalIgnoreCase))
            return ConsistencyLevel.Serial;
        if (value.Equals("LOCAL_SERIAL", StringComparison.OrdinalIgnoreCase))
            return ConsistencyLevel.LocalSerial;
        return ConsistencyLevel.LocalQuorum;
    }

    public void Dispose()
    {
        if (_disposed) return;
        _disposed = true;

        foreach (var session in _sessions.Values)
        {
            session.Dispose();
        }
        _sessions.Clear();
    }
}

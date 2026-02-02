package com.scylladb.latte;

import com.datastax.oss.driver.api.core.CqlSession;
import com.datastax.oss.driver.api.core.CqlSessionBuilder;
import com.datastax.oss.driver.api.core.config.DefaultDriverOption;
import com.datastax.oss.driver.api.core.config.DriverConfigLoader;
import com.datastax.oss.driver.api.core.cql.PreparedStatement;
import java.net.InetSocketAddress;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Manages database sessions and prepared statement caches.
 *
 * <p>This class is responsible for:
 *
 * <ul>
 *   <li>Creating and configuring CQL sessions based on connection parameters
 *   <li>Assigning unique session IDs for client reference
 *   <li>Caching prepared statements per session
 *   <li>Providing session lifecycle management (create, get, close)
 * </ul>
 *
 * <p>Sessions are stored in a thread-safe map and can be accessed by their unique session ID.
 *
 * <h2>Configuration Parameters</h2>
 *
 * <p>The following parameters can be passed to {@link #createSession(Map)}:
 *
 * <ul>
 *   <li>{@code contact_points} - Comma-separated list of host:port pairs
 *   <li>{@code keyspace} - Default keyspace for the session
 *   <li>{@code datacenter} - Local datacenter for load balancing
 *   <li>{@code username} / {@code password} - Authentication credentials
 *   <li>{@code request_timeout_ms} - Request timeout in milliseconds
 *   <li>{@code connect_timeout_ms} - Connection timeout in milliseconds
 *   <li>{@code connections_per_shard} - Pool size per shard
 *   <li>{@code speculative_execution} - Enable speculative execution ("true"/"false")
 *   <li>{@code speculative_execution_max} - Max speculative executions (default: 2)
 *   <li>{@code speculative_execution_delay_ms} - Delay before speculative execution in ms (default: 500)
 * </ul>
 *
 * <h2>Speculative Execution</h2>
 *
 * <p>When enabled, speculative execution sends the same request to multiple nodes simultaneously,
 * using the first response received. This can significantly reduce tail latencies at the cost of
 * additional load on the cluster.
 *
 * <p>Example configuration:
 * <pre>
 * speculative_execution=true
 * speculative_execution_max=2
 * speculative_execution_delay_ms=100
 * </pre>
 */
public class SessionManager {
  private static final Logger logger = LoggerFactory.getLogger(SessionManager.class);

  private final AtomicLong sessionIdGenerator = new AtomicLong(1);
  private final ConcurrentHashMap<Long, SessionEntry> sessions = new ConcurrentHashMap<>();
  private final String defaultContactPoints;

  /**
   * Creates a new SessionManager with the specified default contact points.
   *
   * @param defaultContactPoints comma-separated list of host:port pairs to use when clients don't
   *     specify contact_points
   */
  public SessionManager(@NonNull String defaultContactPoints) {
    this.defaultContactPoints = defaultContactPoints;
  }

  /**
   * Create a new session with the given parameters.
   *
   * @param params connection parameters (see class documentation for supported keys)
   * @return the unique session ID for this session
   * @throws RuntimeException if the session cannot be created
   */
  public long createSession(@NonNull Map<String, String> params) {
    long sessionId = sessionIdGenerator.getAndIncrement();

    // Get contact points from params or use default
    String contactPoints = params.getOrDefault("contact_points", defaultContactPoints);

    CqlSessionBuilder builder = CqlSession.builder();

    // Parse contact points
    for (String cp : contactPoints.split(",")) {
      cp = cp.trim();
      if (cp.isEmpty()) continue;

      String host;
      int port = 9042;
      if (cp.contains(":")) {
        String[] parts = cp.split(":");
        host = parts[0];
        port = Integer.parseInt(parts[1]);
      } else {
        host = cp;
      }
      builder.addContactPoint(new InetSocketAddress(host, port));
    }

    // Configure driver options
    var configBuilder =
        DriverConfigLoader.programmaticBuilder()
            .withDuration(DefaultDriverOption.REQUEST_TIMEOUT, getRequestTimeout(params))
            .withDuration(DefaultDriverOption.CONNECTION_CONNECT_TIMEOUT, getConnectTimeout(params))
            .withInt(
                DefaultDriverOption.CONNECTION_POOL_LOCAL_SIZE,
                getConnectionsPerShard(params))
            .withInt(
                DefaultDriverOption.CONNECTION_POOL_REMOTE_SIZE,
                getConnectionsPerShard(params));

    // Configure speculative execution if enabled
    if (isSpeculativeExecutionEnabled(params)) {
      int maxSpeculative = getSpeculativeExecutionMax(params);
      Duration speculativeDelay = getSpeculativeExecutionDelay(params);

      configBuilder
          .withClass(
              DefaultDriverOption.SPECULATIVE_EXECUTION_POLICY_CLASS,
              com.datastax.oss.driver.internal.core.specex.ConstantSpeculativeExecutionPolicy.class)
          .withInt(DefaultDriverOption.SPECULATIVE_EXECUTION_MAX, maxSpeculative)
          .withDuration(DefaultDriverOption.SPECULATIVE_EXECUTION_DELAY, speculativeDelay);

      logger.info(
          "Speculative execution enabled: max={}, delay={}ms",
          maxSpeculative,
          speculativeDelay.toMillis());
    }

    DriverConfigLoader configLoader = configBuilder.build();

    builder.withConfigLoader(configLoader);

    // Set keyspace if provided
    String keyspace = params.get("keyspace");
    if (keyspace != null && !keyspace.isEmpty()) {
      builder.withKeyspace(keyspace);
    }

    // Set datacenter if provided
    String datacenter = params.get("datacenter");
    if (datacenter != null && !datacenter.isEmpty()) {
      builder.withLocalDatacenter(datacenter);
    } else {
      // Use a default datacenter for local development
      builder.withLocalDatacenter("datacenter1");
    }

    // Set authentication if provided
    String username = params.get("username");
    String password = params.get("password");
    if (username != null && password != null) {
      builder.withAuthCredentials(username, password);
    }

    CqlSession session = builder.build();
    sessions.put(sessionId, new SessionEntry(session));

    logger.info(
        "Created session {} with contact points: {}, keyspace: {}",
        sessionId,
        contactPoints,
        keyspace);

    return sessionId;
  }

  /**
   * Get a session by ID.
   *
   * @param sessionId the session ID returned from {@link #createSession(Map)}
   * @return the session entry containing the CQL session and prepared statement cache
   * @throws Protocol.ProtocolException if the session does not exist
   */
  public @NonNull SessionEntry getSession(long sessionId) {
    SessionEntry entry = sessions.get(sessionId);
    if (entry == null) {
      // Simple error message to avoid allocation in hot path; detailed info available via logging
      throw new Protocol.ProtocolException("Session not found: " + sessionId);
    }
    return entry;
  }

  /** Close a session. */
  public void closeSession(long sessionId) {
    SessionEntry entry = sessions.remove(sessionId);
    if (entry != null) {
      entry.session().close();
      logger.info("Closed session {}", sessionId);
    }
  }

  /** Close all sessions. */
  public void closeAll() {
    for (Map.Entry<Long, SessionEntry> entry : sessions.entrySet()) {
      entry.getValue().session().close();
    }
    sessions.clear();
    logger.info("Closed all sessions");
  }

  private Duration getRequestTimeout(Map<String, String> params) {
    String value = params.get("request_timeout_ms");
    if (value != null) {
      return Duration.ofMillis(Long.parseLong(value));
    }
    return Duration.ofSeconds(12);
  }

  private Duration getConnectTimeout(Map<String, String> params) {
    String value = params.get("connect_timeout_ms");
    if (value != null) {
      return Duration.ofMillis(Long.parseLong(value));
    }
    return Duration.ofSeconds(5);
  }

  private int getConnectionsPerShard(Map<String, String> params) {
    String value = params.get("connections_per_shard");
    if (value != null) {
      return Integer.parseInt(value);
    }
    return 1;
  }

  private boolean isSpeculativeExecutionEnabled(Map<String, String> params) {
    String value = params.get("speculative_execution");
    return "true".equalsIgnoreCase(value);
  }

  private int getSpeculativeExecutionMax(Map<String, String> params) {
    String value = params.get("speculative_execution_max");
    if (value != null) {
      return Integer.parseInt(value);
    }
    return 2; // Default: up to 2 speculative executions
  }

  private Duration getSpeculativeExecutionDelay(Map<String, String> params) {
    String value = params.get("speculative_execution_delay_ms");
    if (value != null) {
      return Duration.ofMillis(Long.parseLong(value));
    }
    return Duration.ofMillis(500); // Default: 500ms before speculative execution
  }

  /**
   * A session entry with its prepared statement cache.
   *
   * @param session the CQL session for executing queries
   * @param preparedStatements cache of prepared statements keyed by statement key
   */
  public record SessionEntry(
      @NonNull CqlSession session,
      @NonNull ConcurrentHashMap<String, PreparedStatement> preparedStatements) {

    /**
     * Creates a session entry with an empty prepared statement cache.
     *
     * @param session the CQL session
     */
    public SessionEntry(@NonNull CqlSession session) {
      this(session, new ConcurrentHashMap<>());
    }

    /**
     * Get a prepared statement from the cache.
     *
     * @param key the statement key
     * @return the prepared statement, or null if not found
     */
    public @Nullable PreparedStatement getPrepared(@NonNull String key) {
      return preparedStatements.get(key);
    }

    /**
     * Cache a prepared statement.
     *
     * @param key the statement key
     * @param ps the prepared statement to cache
     */
    public void putPrepared(@NonNull String key, @NonNull PreparedStatement ps) {
      preparedStatements.put(key, ps);
    }
  }
}

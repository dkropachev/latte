package com.scylladb.latte;

import com.datastax.driver.core.Cluster;
import com.datastax.driver.core.PoolingOptions;
import com.datastax.driver.core.PreparedStatement;
import com.datastax.driver.core.Session;
import com.datastax.driver.core.SocketOptions;
import com.datastax.driver.core.policies.DCAwareRoundRobinPolicy;
import com.datastax.driver.core.policies.ConstantSpeculativeExecutionPolicy;
import java.net.InetSocketAddress;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Manages database sessions and prepared statement caches for Java driver 3.x.
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
 *   <li>{@code connections_per_shard} - Pool size per host
 *   <li>{@code speculative_execution} - Enable speculative execution ("true"/"false")
 *   <li>{@code speculative_execution_max} - Max speculative executions (default: 2)
 *   <li>{@code speculative_execution_delay_ms} - Delay before speculative execution in ms (default:
 *       500)
 * </ul>
 *
 * <h2>Speculative Execution</h2>
 *
 * <p>When enabled, speculative execution sends the same request to multiple nodes simultaneously,
 * using the first response received. This can significantly reduce tail latencies at the cost of
 * additional load on the cluster.
 *
 * <p>Example configuration:
 *
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

  public SessionManager() {
  }

  /**
   * Create a new session with the given parameters.
   *
   * @param params connection parameters (see class documentation for supported keys)
   * @return the unique session ID for this session
   * @throws RuntimeException if the session cannot be created
   */
  public long createSession(Map<String, String> params) {
    long sessionId = sessionIdGenerator.getAndIncrement();

    // Get contact points from params
    String contactPoints = params.getOrDefault("contact_points", "");

    Cluster.Builder builder = Cluster.builder();

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
      builder.addContactPointsWithPorts(new InetSocketAddress(host, port));
    }

    // Configure socket options (timeouts)
    SocketOptions socketOptions = new SocketOptions();
    socketOptions.setConnectTimeoutMillis(getConnectTimeoutMs(params));
    socketOptions.setReadTimeoutMillis(getRequestTimeoutMs(params));
    builder.withSocketOptions(socketOptions);

    // Configure pooling options
    int connectionsPerHost = getConnectionsPerShard(params);
    PoolingOptions poolingOptions = new PoolingOptions();
    poolingOptions.setCoreConnectionsPerHost(
        com.datastax.driver.core.HostDistance.LOCAL, connectionsPerHost);
    poolingOptions.setMaxConnectionsPerHost(
        com.datastax.driver.core.HostDistance.LOCAL, connectionsPerHost);
    poolingOptions.setCoreConnectionsPerHost(
        com.datastax.driver.core.HostDistance.REMOTE, connectionsPerHost);
    poolingOptions.setMaxConnectionsPerHost(
        com.datastax.driver.core.HostDistance.REMOTE, connectionsPerHost);
    builder.withPoolingOptions(poolingOptions);

    // Configure load balancing policy with datacenter
    String datacenter = params.get("datacenter");
    if (datacenter != null && !datacenter.isEmpty()) {
      builder.withLoadBalancingPolicy(
          DCAwareRoundRobinPolicy.builder().withLocalDc(datacenter).build());
    } else {
      // Use a default datacenter for local development
      builder.withLoadBalancingPolicy(
          DCAwareRoundRobinPolicy.builder().withLocalDc("datacenter1").build());
    }

    // Configure speculative execution if enabled
    if (isSpeculativeExecutionEnabled(params)) {
      int maxSpeculative = getSpeculativeExecutionMax(params);
      int speculativeDelayMs = getSpeculativeExecutionDelayMs(params);

      builder.withSpeculativeExecutionPolicy(
          new ConstantSpeculativeExecutionPolicy(speculativeDelayMs, maxSpeculative));

      logger.info(
          "Speculative execution enabled: max={}, delay={}ms", maxSpeculative, speculativeDelayMs);
    }

    // Set authentication if provided
    String username = params.get("username");
    String password = params.get("password");
    if (username != null && password != null) {
      builder.withCredentials(username, password);
    }

    Cluster cluster = builder.build();

    // Connect to keyspace if provided
    String keyspace = params.get("keyspace");
    Session session;
    if (keyspace != null && !keyspace.isEmpty()) {
      session = cluster.connect(keyspace);
    } else {
      session = cluster.connect();
    }

    sessions.put(sessionId, new SessionEntry(cluster, session));

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
  public SessionEntry getSession(long sessionId) {
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
      entry.cluster().close();
      logger.info("Closed session {}", sessionId);
    }
  }

  /** Close all sessions. */
  public void closeAll() {
    for (Map.Entry<Long, SessionEntry> entry : sessions.entrySet()) {
      entry.getValue().session().close();
      entry.getValue().cluster().close();
    }
    sessions.clear();
    logger.info("Closed all sessions");
  }

  private int getRequestTimeoutMs(Map<String, String> params) {
    String value = params.get("request_timeout_ms");
    if (value != null) {
      return Integer.parseInt(value);
    }
    return 12000; // 12 seconds default
  }

  private int getConnectTimeoutMs(Map<String, String> params) {
    String value = params.get("connect_timeout_ms");
    if (value != null) {
      return Integer.parseInt(value);
    }
    return 5000; // 5 seconds default
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

  private int getSpeculativeExecutionDelayMs(Map<String, String> params) {
    String value = params.get("speculative_execution_delay_ms");
    if (value != null) {
      return Integer.parseInt(value);
    }
    return 500; // Default: 500ms before speculative execution
  }

  /**
   * A session entry with its cluster, session, and prepared statement cache.
   *
   * <p>Note: In driver 3.x, we need to keep both Cluster and Session to properly manage lifecycle.
   */
  public static final class SessionEntry {
    private final Cluster cluster;
    private final Session session;
    private final ConcurrentHashMap<String, PreparedStatement> preparedStatements;

    /**
     * Creates a session entry with an empty prepared statement cache.
     *
     * @param cluster the cluster
     * @param session the session
     */
    public SessionEntry(Cluster cluster, Session session) {
      this.cluster = cluster;
      this.session = session;
      this.preparedStatements = new ConcurrentHashMap<>();
    }

    public Cluster cluster() {
      return cluster;
    }

    public Session session() {
      return session;
    }

    public ConcurrentHashMap<String, PreparedStatement> preparedStatements() {
      return preparedStatements;
    }

    /**
     * Get a prepared statement from the cache.
     *
     * @param key the statement key
     * @return the prepared statement, or null if not found
     */
    public PreparedStatement getPrepared(String key) {
      return preparedStatements.get(key);
    }

    /**
     * Cache a prepared statement.
     *
     * @param key the statement key
     * @param ps the prepared statement to cache
     */
    public void putPrepared(String key, PreparedStatement ps) {
      preparedStatements.put(key, ps);
    }
  }
}

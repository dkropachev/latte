package com.scylladb.latte.alternator;

import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.http.apache.ApacheHttpClient;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;

import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Manages DynamoDB client sessions.
 */
public final class SessionRegistry {

    public record Session(
        long id,
        DynamoDbClient client,
        String endpoint,
        String region,
        Instant created
    ) {}

    public record Config(
        String endpoint,
        String region,
        String accessKeyId,
        String secretAccessKey,
        String sessionToken,
        int maxConnections,
        int requestTimeoutMs,
        int connectTimeoutMs,
        String retryMode,
        int maxRetries,
        boolean rackAwareness,
        String routingScope,
        boolean compression,
        int poolSize,
        boolean useLoadBalancing,
        String rack,
        String datacenter
    ) {}

    private final ConcurrentHashMap<Long, Session> sessions = new ConcurrentHashMap<>();
    private final AtomicLong nextId = new AtomicLong(0);
    private final int maxInflight;

    public SessionRegistry() {
        this(512);
    }

    public SessionRegistry(int maxInflight) {
        this.maxInflight = maxInflight;
    }

    public static Config parseConfig(Map<String, String> params) {
        String endpoint = params.getOrDefault("endpoint", "http://localhost:8000");
        String region = params.getOrDefault("region", "us-east-1");
        String accessKeyId = params.getOrDefault("access_key_id", "");
        String secretAccessKey = params.getOrDefault("secret_access_key", "");
        String sessionToken = params.getOrDefault("session_token", "");
        int maxConnections = parseIntOrDefault(params.get("max_connections"), 100);
        int requestTimeoutMs = parseIntOrDefault(params.get("request_timeout_ms"), 5000);
        int connectTimeoutMs = parseIntOrDefault(params.get("connect_timeout_ms"), 3000);
        String retryMode = params.getOrDefault("retry_mode", "none");
        int maxRetries = parseIntOrDefault(params.get("max_retries"), 0);
        boolean rackAwareness = "true".equals(params.get("rack_awareness"));
        String routingScope = params.getOrDefault("routing_scope", "");
        boolean compression = "true".equals(params.get("compression")) || "gzip".equals(params.get("compression"));
        int poolSize = parseIntOrDefault(params.get("pool_size"), 100);
        boolean useLoadBalancing = "true".equals(params.get("load_balancing"));
        String rack = params.getOrDefault("rack", "");
        String datacenter = params.getOrDefault("datacenter", "");

        return new Config(endpoint, region, accessKeyId, secretAccessKey, sessionToken,
                maxConnections, requestTimeoutMs, connectTimeoutMs, retryMode, maxRetries,
                rackAwareness, routingScope, compression, poolSize, useLoadBalancing, rack, datacenter);
    }

    public Session create(Config cfg) {
        // Ensure maxConnections is at least the adapter's inflight limit to avoid
        // connection pool exhaustion when many concurrent requests are in-flight
        Config effectiveCfg = cfg.maxConnections < maxInflight
                ? new Config(cfg.endpoint, cfg.region, cfg.accessKeyId, cfg.secretAccessKey,
                    cfg.sessionToken, maxInflight, cfg.requestTimeoutMs, cfg.connectTimeoutMs,
                    cfg.retryMode, cfg.maxRetries, cfg.rackAwareness, cfg.routingScope,
                    cfg.compression, cfg.poolSize, cfg.useLoadBalancing, cfg.rack, cfg.datacenter)
                : cfg;

        DynamoDbClient client;

        if (effectiveCfg.useLoadBalancing || effectiveCfg.rackAwareness || !effectiveCfg.datacenter.isEmpty() || !effectiveCfg.rack.isEmpty()) {
            // Try alternator load balancing client
            client = createAlternatorClient(effectiveCfg);
            if (client == null) {
                client = createStandardClient(effectiveCfg);
            }
        } else {
            client = createStandardClient(effectiveCfg);
        }

        long id = nextId.incrementAndGet();
        Session session = new Session(id, client, effectiveCfg.endpoint, effectiveCfg.region, Instant.now());
        sessions.put(id, session);
        return session;
    }

    private DynamoDbClient createAlternatorClient(Config cfg) {
        try {
            var builder = com.scylladb.alternator.AlternatorDynamoDbClient.builder()
                    .endpointOverride(URI.create(cfg.endpoint))
                    .region(Region.of(cfg.region));

            if (!cfg.accessKeyId.isEmpty() && !cfg.secretAccessKey.isEmpty()) {
                builder.credentialsProvider(StaticCredentialsProvider.create(
                        AwsBasicCredentials.create(cfg.accessKeyId, cfg.secretAccessKey)));
            }

            // Configure routing scope based on rack/datacenter settings
            if (!cfg.rack.isEmpty() && !cfg.datacenter.isEmpty()) {
                builder.withRoutingScope(
                        com.scylladb.alternator.routing.RackScope.of(
                                cfg.datacenter, cfg.rack,
                                com.scylladb.alternator.routing.ClusterScope.create()));
            } else if (!cfg.datacenter.isEmpty()) {
                builder.withRoutingScope(
                        com.scylladb.alternator.routing.DatacenterScope.of(
                                cfg.datacenter,
                                com.scylladb.alternator.routing.ClusterScope.create()));
            }

            if (cfg.compression) {
                builder.withCompressionAlgorithm(
                        com.scylladb.alternator.RequestCompressionAlgorithm.GZIP);
            }

            return builder.build();
        } catch (Exception e) {
            System.err.println("Failed to create alternator client, falling back to standard: " + e.getMessage());
            return null;
        }
    }

    private DynamoDbClient createStandardClient(Config cfg) {
        String accessKey = cfg.accessKeyId.isEmpty() ? "test" : cfg.accessKeyId;
        String secretKey = cfg.secretAccessKey.isEmpty() ? "test" : cfg.secretAccessKey;

        var httpClient = ApacheHttpClient.builder()
                .maxConnections(cfg.maxConnections)
                .connectionTimeout(Duration.ofMillis(cfg.connectTimeoutMs))
                .socketTimeout(Duration.ofMillis(cfg.requestTimeoutMs))
                .connectionAcquisitionTimeout(Duration.ofMillis(cfg.requestTimeoutMs))
                .build();

        var clientBuilder = DynamoDbClient.builder()
                .endpointOverride(URI.create(cfg.endpoint))
                .region(Region.of(cfg.region))
                .credentialsProvider(StaticCredentialsProvider.create(
                        AwsBasicCredentials.create(accessKey, secretKey)))
                .httpClient(httpClient);

        // Configure retry policy: "none" disables SDK-level retries (adapter handles errors directly)
        if ("none".equals(cfg.retryMode) || cfg.maxRetries == 0) {
            clientBuilder.overrideConfiguration(c -> c.retryStrategy(s -> s.maxAttempts(1)));
        } else {
            clientBuilder.overrideConfiguration(c -> c.retryStrategy(s -> s.maxAttempts(cfg.maxRetries + 1)));
        }

        return clientBuilder.build();
    }

    public Session get(long id) {
        return sessions.get(id);
    }

    public boolean close(long id) {
        Session session = sessions.remove(id);
        if (session != null) {
            session.client.close();
            return true;
        }
        return false;
    }

    public void closeAll() {
        sessions.forEach((id, session) -> {
            sessions.remove(id);
            session.client.close();
        });
    }

    public int count() {
        return sessions.size();
    }

    private static int parseIntOrDefault(String value, int defaultValue) {
        if (value == null || value.isEmpty()) {
            return defaultValue;
        }
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException e) {
            return defaultValue;
        }
    }
}

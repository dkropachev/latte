package com.scylladb.latte.alternator;

import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class SessionRegistryTest {

    @Test
    void testParseConfigDefaults() {
        SessionRegistry.Config cfg = SessionRegistry.parseConfig(Map.of());
        assertEquals("http://localhost:8000", cfg.endpoint());
        assertEquals("us-east-1", cfg.region());
        assertEquals("", cfg.accessKeyId());
        assertEquals("", cfg.secretAccessKey());
        assertEquals(100, cfg.maxConnections());
        assertEquals(5000, cfg.requestTimeoutMs());
        assertEquals(3000, cfg.connectTimeoutMs());
        assertEquals("none", cfg.retryMode());
        assertEquals(0, cfg.maxRetries());
        assertFalse(cfg.rackAwareness());
        assertFalse(cfg.useLoadBalancing());
    }

    @Test
    void testParseConfigWithValues() {
        Map<String, String> params = Map.of(
                "endpoint", "http://custom:9000",
                "region", "eu-west-1",
                "access_key_id", "mykey",
                "secret_access_key", "mysecret",
                "max_connections", "200",
                "request_timeout_ms", "10000",
                "rack_awareness", "true",
                "load_balancing", "true"
        );
        SessionRegistry.Config cfg = SessionRegistry.parseConfig(params);
        assertEquals("http://custom:9000", cfg.endpoint());
        assertEquals("eu-west-1", cfg.region());
        assertEquals("mykey", cfg.accessKeyId());
        assertEquals("mysecret", cfg.secretAccessKey());
        assertEquals(200, cfg.maxConnections());
        assertEquals(10000, cfg.requestTimeoutMs());
        assertTrue(cfg.rackAwareness());
        assertTrue(cfg.useLoadBalancing());
    }

    @Test
    void testParseConfigInvalidIntegers() {
        Map<String, String> params = Map.of(
                "max_connections", "not_a_number",
                "request_timeout_ms", ""
        );
        SessionRegistry.Config cfg = SessionRegistry.parseConfig(params);
        assertEquals(100, cfg.maxConnections()); // default
        assertEquals(5000, cfg.requestTimeoutMs()); // default
    }

    @Test
    void testCreateAndGetSession() {
        SessionRegistry registry = new SessionRegistry();
        SessionRegistry.Config cfg = SessionRegistry.parseConfig(Map.of());
        SessionRegistry.Session session = registry.create(cfg);

        assertNotNull(session);
        assertTrue(session.id() > 0);
        assertNotNull(session.client());

        SessionRegistry.Session retrieved = registry.get(session.id());
        assertNotNull(retrieved);
        assertEquals(session.id(), retrieved.id());

        registry.close(session.id());
    }

    @Test
    void testGetNonexistentSession() {
        SessionRegistry registry = new SessionRegistry();
        assertNull(registry.get(999));
    }

    @Test
    void testCloseSession() {
        SessionRegistry registry = new SessionRegistry();
        SessionRegistry.Config cfg = SessionRegistry.parseConfig(Map.of());
        SessionRegistry.Session session = registry.create(cfg);

        assertTrue(registry.close(session.id()));
        assertNull(registry.get(session.id()));
        assertFalse(registry.close(session.id())); // already closed
    }

    @Test
    void testCloseAll() {
        SessionRegistry registry = new SessionRegistry();
        SessionRegistry.Config cfg = SessionRegistry.parseConfig(Map.of());
        registry.create(cfg);
        registry.create(cfg);
        registry.create(cfg);

        assertEquals(3, registry.count());
        registry.closeAll();
        assertEquals(0, registry.count());
    }

    @Test
    void testSessionIdsAreUnique() {
        SessionRegistry registry = new SessionRegistry();
        SessionRegistry.Config cfg = SessionRegistry.parseConfig(Map.of());

        ConcurrentHashMap<Long, Boolean> ids = new ConcurrentHashMap<>();
        for (int i = 0; i < 100; i++) {
            SessionRegistry.Session session = registry.create(cfg);
            assertNull(ids.put(session.id(), true), "Duplicate session ID: " + session.id());
        }

        registry.closeAll();
    }

    @Test
    void testConcurrentAccess() throws Exception {
        SessionRegistry registry = new SessionRegistry();
        SessionRegistry.Config cfg = SessionRegistry.parseConfig(Map.of());
        int threads = 10;
        int sessionsPerThread = 10;

        CountDownLatch latch = new CountDownLatch(threads);
        AtomicInteger errors = new AtomicInteger(0);

        for (int t = 0; t < threads; t++) {
            Thread.ofVirtual().start(() -> {
                try {
                    for (int i = 0; i < sessionsPerThread; i++) {
                        SessionRegistry.Session session = registry.create(cfg);
                        if (registry.get(session.id()) == null) {
                            errors.incrementAndGet();
                        }
                        registry.close(session.id());
                    }
                } catch (Exception e) {
                    errors.incrementAndGet();
                } finally {
                    latch.countDown();
                }
            });
        }

        latch.await();
        assertEquals(0, errors.get());
        assertEquals(0, registry.count());
    }
}

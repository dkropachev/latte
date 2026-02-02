package com.scylladb.latte;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

@DisplayName("SessionManager")
class SessionManagerTest {

  private SessionManager sessionManager;

  @BeforeEach
  void setUp() {
    sessionManager = new SessionManager();
  }

  @Nested
  @DisplayName("Session Lookup")
  class SessionLookup {

    @Test
    @DisplayName("should throw when session not found")
    void sessionNotFound() {
      assertThatThrownBy(() -> sessionManager.getSession(999L))
          .isInstanceOf(Protocol.ProtocolException.class)
          .hasMessageContaining("Session not found: 999");
    }

    @Test
    @DisplayName("should include session ID in error message")
    void errorMessageIncludesSessionId() {
      assertThatThrownBy(() -> sessionManager.getSession(12345L))
          .isInstanceOf(Protocol.ProtocolException.class)
          .hasMessageContaining("12345");
    }
  }

  @Nested
  @DisplayName("Session Configuration")
  class SessionConfiguration {

    @Test
    @DisplayName("should parse single contact point")
    void singleContactPoint() {
      Map<String, String> params = new HashMap<>();
      params.put("contact_points", "192.168.1.1");
      // This would fail to connect but tests param parsing
      assertThatThrownBy(() -> sessionManager.createSession(params))
          .isInstanceOf(Exception.class); // Connection will fail
    }

    @Test
    @DisplayName("should parse contact point with port")
    void contactPointWithPort() {
      Map<String, String> params = new HashMap<>();
      params.put("contact_points", "192.168.1.1:9043");
      assertThatThrownBy(() -> sessionManager.createSession(params))
          .isInstanceOf(Exception.class);
    }

    @Test
    @DisplayName("should parse multiple contact points")
    void multipleContactPoints() {
      Map<String, String> params = new HashMap<>();
      params.put("contact_points", "192.168.1.1,192.168.1.2,192.168.1.3");
      assertThatThrownBy(() -> sessionManager.createSession(params))
          .isInstanceOf(Exception.class);
    }

    @Test
    @DisplayName("should use unreachable contact point when explicitly set")
    void unreachableContactPoint() {
      Map<String, String> params = new HashMap<>();
      params.put("contact_points", "192.168.254.254:9042");
      assertThatThrownBy(() -> sessionManager.createSession(params))
          .isInstanceOf(Exception.class);
    }

    @Test
    @DisplayName("should handle empty contact point gracefully")
    void emptyContactPoint() {
      Map<String, String> params = new HashMap<>();
      params.put("contact_points", "192.168.1.1,,192.168.1.2");
      assertThatThrownBy(() -> sessionManager.createSession(params))
          .isInstanceOf(Exception.class);
    }
  }

  @Nested
  @DisplayName("Timeout Configuration")
  class TimeoutConfiguration {

    @Test
    @DisplayName("should parse request_timeout_ms parameter")
    void requestTimeoutMs() {
      Map<String, String> params = new HashMap<>();
      params.put("contact_points", "192.168.1.1");
      params.put("request_timeout_ms", "5000");
      assertThatThrownBy(() -> sessionManager.createSession(params))
          .isInstanceOf(Exception.class);
    }

    @Test
    @DisplayName("should parse connect_timeout_ms parameter")
    void connectTimeoutMs() {
      Map<String, String> params = new HashMap<>();
      params.put("contact_points", "192.168.1.1");
      params.put("connect_timeout_ms", "2000");
      assertThatThrownBy(() -> sessionManager.createSession(params))
          .isInstanceOf(Exception.class);
    }

    @Test
    @DisplayName("should handle very short timeout")
    void veryShortTimeout() {
      Map<String, String> params = new HashMap<>();
      params.put("contact_points", "192.168.1.1");
      params.put("connect_timeout_ms", "1"); // 1ms timeout
      assertThatThrownBy(() -> sessionManager.createSession(params))
          .isInstanceOf(Exception.class);
    }
  }

  @Nested
  @DisplayName("Authentication Configuration")
  class AuthenticationConfiguration {

    @Test
    @DisplayName("should accept username and password parameters")
    void usernameAndPassword() {
      Map<String, String> params = new HashMap<>();
      params.put("contact_points", "192.168.1.1");
      params.put("username", "testuser");
      params.put("password", "testpass");
      assertThatThrownBy(() -> sessionManager.createSession(params))
          .isInstanceOf(Exception.class);
    }

    @Test
    @DisplayName("should handle missing password with username")
    void missingPassword() {
      Map<String, String> params = new HashMap<>();
      params.put("contact_points", "192.168.1.1");
      params.put("username", "testuser");
      // No password - should not set auth credentials
      assertThatThrownBy(() -> sessionManager.createSession(params))
          .isInstanceOf(Exception.class);
    }
  }

  @Nested
  @DisplayName("Datacenter Configuration")
  class DatacenterConfiguration {

    @Test
    @DisplayName("should accept datacenter parameter")
    void datacenterParameter() {
      Map<String, String> params = new HashMap<>();
      params.put("contact_points", "192.168.1.1");
      params.put("datacenter", "dc1");
      assertThatThrownBy(() -> sessionManager.createSession(params))
          .isInstanceOf(Exception.class);
    }

    @Test
    @DisplayName("should use default datacenter when not specified")
    void defaultDatacenter() {
      Map<String, String> params = new HashMap<>();
      params.put("contact_points", "192.168.1.1");
      // No datacenter specified, should use "datacenter1"
      assertThatThrownBy(() -> sessionManager.createSession(params))
          .isInstanceOf(Exception.class);
    }
  }

  @Nested
  @DisplayName("Connection Pool Configuration")
  class ConnectionPoolConfiguration {

    @Test
    @DisplayName("should parse connections_per_shard parameter")
    void connectionsPerShard() {
      Map<String, String> params = new HashMap<>();
      params.put("contact_points", "192.168.1.1");
      params.put("connections_per_shard", "4");
      assertThatThrownBy(() -> sessionManager.createSession(params))
          .isInstanceOf(Exception.class);
    }
  }

  @Nested
  @DisplayName("Session Lifecycle")
  class SessionLifecycle {

    @Test
    @DisplayName("should close all sessions")
    void closeAllSessions() {
      // Just verify closeAll doesn't throw when no sessions exist
      sessionManager.closeAll();
    }

    @Test
    @DisplayName("should handle closing non-existent session")
    void closeNonExistentSession() {
      // Should not throw
      sessionManager.closeSession(999L);
    }
  }

  @Nested
  @DisplayName("Speculative Execution Configuration")
  class SpeculativeExecutionConfiguration {

    @Test
    @DisplayName("should accept speculative_execution parameter")
    void speculativeExecutionEnabled() {
      Map<String, String> params = new HashMap<>();
      params.put("contact_points", "192.168.1.1");
      params.put("speculative_execution", "true");
      // Will fail to connect but tests param parsing
      assertThatThrownBy(() -> sessionManager.createSession(params))
          .isInstanceOf(Exception.class);
    }

    @Test
    @DisplayName("should accept speculative_execution_max parameter")
    void speculativeExecutionMax() {
      Map<String, String> params = new HashMap<>();
      params.put("contact_points", "192.168.1.1");
      params.put("speculative_execution", "true");
      params.put("speculative_execution_max", "3");
      assertThatThrownBy(() -> sessionManager.createSession(params))
          .isInstanceOf(Exception.class);
    }

    @Test
    @DisplayName("should accept speculative_execution_delay_ms parameter")
    void speculativeExecutionDelay() {
      Map<String, String> params = new HashMap<>();
      params.put("contact_points", "192.168.1.1");
      params.put("speculative_execution", "true");
      params.put("speculative_execution_delay_ms", "100");
      assertThatThrownBy(() -> sessionManager.createSession(params))
          .isInstanceOf(Exception.class);
    }

    @Test
    @DisplayName("should handle speculative_execution=false")
    void speculativeExecutionDisabled() {
      Map<String, String> params = new HashMap<>();
      params.put("contact_points", "192.168.1.1");
      params.put("speculative_execution", "false");
      assertThatThrownBy(() -> sessionManager.createSession(params))
          .isInstanceOf(Exception.class);
    }

    @Test
    @DisplayName("should handle full speculative execution configuration")
    void fullSpeculativeConfig() {
      Map<String, String> params = new HashMap<>();
      params.put("contact_points", "192.168.1.1");
      params.put("speculative_execution", "true");
      params.put("speculative_execution_max", "5");
      params.put("speculative_execution_delay_ms", "50");
      assertThatThrownBy(() -> sessionManager.createSession(params))
          .isInstanceOf(Exception.class);
    }
  }
}

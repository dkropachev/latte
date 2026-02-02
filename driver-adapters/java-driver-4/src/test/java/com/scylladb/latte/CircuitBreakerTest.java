package com.scylladb.latte;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

@DisplayName("CircuitBreaker")
class CircuitBreakerTest {

  private CircuitBreaker breaker;

  @BeforeEach
  void setUp() {
    // Fast timeouts for testing: 3 failures to open, 2 successes to close, 100ms open duration
    breaker = new CircuitBreaker("test", 3, 2, 100, 2);
  }

  @Nested
  @DisplayName("Initial State")
  class InitialState {

    @Test
    @DisplayName("should start in closed state")
    void startsClosed() {
      assertThat(breaker.getState()).isEqualTo(CircuitBreaker.State.CLOSED);
      assertThat(breaker.isClosed()).isTrue();
      assertThat(breaker.isOpen()).isFalse();
      assertThat(breaker.isHalfOpen()).isFalse();
    }

    @Test
    @DisplayName("should allow requests when closed")
    void allowsRequestsWhenClosed() {
      assertThat(breaker.allowRequest()).isTrue();
    }

    @Test
    @DisplayName("should have zero metrics initially")
    void zeroMetrics() {
      assertThat(breaker.getTotalCalls()).isEqualTo(0);
      assertThat(breaker.getSuccessfulCalls()).isEqualTo(0);
      assertThat(breaker.getFailedCalls()).isEqualTo(0);
      assertThat(breaker.getRejectedCalls()).isEqualTo(0);
    }
  }

  @Nested
  @DisplayName("Successful Operations")
  class SuccessfulOperations {

    @Test
    @DisplayName("should execute and return result")
    void executeAndReturn() throws Exception {
      String result = breaker.execute(() -> "success");
      assertThat(result).isEqualTo("success");
    }

    @Test
    @DisplayName("should track successful calls")
    void trackSuccessfulCalls() throws Exception {
      breaker.execute(() -> "a");
      breaker.execute(() -> "b");
      breaker.execute(() -> "c");

      assertThat(breaker.getTotalCalls()).isEqualTo(3);
      assertThat(breaker.getSuccessfulCalls()).isEqualTo(3);
      assertThat(breaker.getFailedCalls()).isEqualTo(0);
    }

    @Test
    @DisplayName("should reset consecutive failures on success")
    void resetFailuresOnSuccess() throws Exception {
      // Cause 2 failures (not enough to open)
      for (int i = 0; i < 2; i++) {
        try {
          breaker.execute(() -> {
            throw new RuntimeException("fail");
          });
        } catch (Exception ignored) {
        }
      }
      assertThat(breaker.getConsecutiveFailures()).isEqualTo(2);

      // Success should reset
      breaker.execute(() -> "success");
      assertThat(breaker.getConsecutiveFailures()).isEqualTo(0);
    }
  }

  @Nested
  @DisplayName("Failed Operations")
  class FailedOperations {

    @Test
    @DisplayName("should propagate exceptions")
    void propagateExceptions() {
      assertThatThrownBy(() -> breaker.execute(() -> {
        throw new RuntimeException("test error");
      }))
          .isInstanceOf(RuntimeException.class)
          .hasMessage("test error");
    }

    @Test
    @DisplayName("should track failed calls")
    void trackFailedCalls() {
      for (int i = 0; i < 2; i++) {
        try {
          breaker.execute(() -> {
            throw new RuntimeException("fail");
          });
        } catch (Exception ignored) {
        }
      }

      assertThat(breaker.getTotalCalls()).isEqualTo(2);
      assertThat(breaker.getFailedCalls()).isEqualTo(2);
      assertThat(breaker.getSuccessfulCalls()).isEqualTo(0);
    }

    @Test
    @DisplayName("should open after threshold failures")
    void openAfterThreshold() {
      // 3 failures should open the circuit
      for (int i = 0; i < 3; i++) {
        try {
          breaker.execute(() -> {
            throw new RuntimeException("fail");
          });
        } catch (Exception ignored) {
        }
      }

      assertThat(breaker.getState()).isEqualTo(CircuitBreaker.State.OPEN);
      assertThat(breaker.isOpen()).isTrue();
    }
  }

  @Nested
  @DisplayName("Open State")
  class OpenState {

    @BeforeEach
    void openCircuit() {
      // Open the circuit with failures
      for (int i = 0; i < 3; i++) {
        try {
          breaker.execute(() -> {
            throw new RuntimeException("fail");
          });
        } catch (Exception ignored) {
        }
      }
    }

    @Test
    @DisplayName("should reject requests when open")
    void rejectWhenOpen() {
      assertThat(breaker.allowRequest()).isFalse();
    }

    @Test
    @DisplayName("should throw CircuitBreakerOpenException")
    void throwOpenException() {
      assertThatThrownBy(() -> breaker.execute(() -> "test"))
          .isInstanceOf(CircuitBreaker.CircuitBreakerOpenException.class)
          .hasMessageContaining("test")
          .hasMessageContaining("OPEN");
    }

    @Test
    @DisplayName("should track rejected calls")
    void trackRejectedCalls() {
      try {
        breaker.execute(() -> "test");
      } catch (Exception ignored) {
      }
      try {
        breaker.execute(() -> "test");
      } catch (Exception ignored) {
      }

      assertThat(breaker.getRejectedCalls()).isEqualTo(2);
    }

    @Test
    @DisplayName("should report remaining open time")
    void reportRemainingTime() {
      long remaining = breaker.getRemainingOpenTimeMs();
      assertThat(remaining).isGreaterThan(0);
      assertThat(remaining).isLessThanOrEqualTo(100);
    }

    @Test
    @DisplayName("should transition to half-open after timeout")
    void transitionToHalfOpen() throws Exception {
      // Wait for open duration
      Thread.sleep(150);

      // Next request check should transition to half-open
      assertThat(breaker.allowRequest()).isTrue();
      assertThat(breaker.getState()).isEqualTo(CircuitBreaker.State.HALF_OPEN);
    }
  }

  @Nested
  @DisplayName("Half-Open State")
  class HalfOpenState {

    @BeforeEach
    void transitionToHalfOpen() throws Exception {
      // Open the circuit
      for (int i = 0; i < 3; i++) {
        try {
          breaker.execute(() -> {
            throw new RuntimeException("fail");
          });
        } catch (Exception ignored) {
        }
      }
      // Wait for transition
      Thread.sleep(150);
      breaker.allowRequest(); // Trigger transition
    }

    @Test
    @DisplayName("should be in half-open state")
    void inHalfOpenState() {
      assertThat(breaker.getState()).isEqualTo(CircuitBreaker.State.HALF_OPEN);
      assertThat(breaker.isHalfOpen()).isTrue();
    }

    @Test
    @DisplayName("should close after success threshold")
    void closeAfterSuccesses() throws Exception {
      // 2 successes should close
      breaker.execute(() -> "success1");
      breaker.execute(() -> "success2");

      assertThat(breaker.getState()).isEqualTo(CircuitBreaker.State.CLOSED);
      assertThat(breaker.isClosed()).isTrue();
    }

    @Test
    @DisplayName("should reopen on failure")
    void reopenOnFailure() throws Exception {
      // First success
      breaker.execute(() -> "success");

      // Then failure - should reopen
      try {
        breaker.execute(() -> {
          throw new RuntimeException("fail");
        });
      } catch (Exception ignored) {
      }

      assertThat(breaker.getState()).isEqualTo(CircuitBreaker.State.OPEN);
    }

    @Test
    @DisplayName("should limit concurrent attempts")
    void limitConcurrentAttempts() {
      // halfOpenMaxAttempts is 2, and BeforeEach already consumed 1 by triggering transition
      // So we only have 1 more attempt available
      assertThat(breaker.allowRequest()).isTrue();  // 2nd attempt
      assertThat(breaker.allowRequest()).isFalse(); // 3rd attempt should be rejected
    }
  }

  @Nested
  @DisplayName("Void Operations")
  class VoidOperations {

    @Test
    @DisplayName("should execute void operation")
    void executeVoid() throws Exception {
      AtomicInteger counter = new AtomicInteger(0);
      breaker.executeVoid(counter::incrementAndGet);
      assertThat(counter.get()).isEqualTo(1);
    }

    @Test
    @DisplayName("should track void operation metrics")
    void trackVoidMetrics() throws Exception {
      breaker.executeVoid(() -> {});
      breaker.executeVoid(() -> {});

      assertThat(breaker.getTotalCalls()).isEqualTo(2);
      assertThat(breaker.getSuccessfulCalls()).isEqualTo(2);
    }
  }

  @Nested
  @DisplayName("Force State Changes")
  class ForceStateChanges {

    @Test
    @DisplayName("should force close")
    void forceClose() {
      // Open the circuit
      for (int i = 0; i < 3; i++) {
        try {
          breaker.execute(() -> {
            throw new RuntimeException("fail");
          });
        } catch (Exception ignored) {
        }
      }
      assertThat(breaker.isOpen()).isTrue();

      breaker.forceClose();
      assertThat(breaker.isClosed()).isTrue();
      assertThat(breaker.getConsecutiveFailures()).isEqualTo(0);
    }

    @Test
    @DisplayName("should force open")
    void forceOpen() {
      assertThat(breaker.isClosed()).isTrue();

      breaker.forceOpen();
      assertThat(breaker.isOpen()).isTrue();
    }
  }

  @Nested
  @DisplayName("Metrics")
  class Metrics {

    @Test
    @DisplayName("should reset metrics")
    void resetMetrics() throws Exception {
      breaker.execute(() -> "success");
      try {
        breaker.execute(() -> {
          throw new RuntimeException("fail");
        });
      } catch (Exception ignored) {
      }

      assertThat(breaker.getTotalCalls()).isGreaterThan(0);

      breaker.resetMetrics();

      assertThat(breaker.getTotalCalls()).isEqualTo(0);
      assertThat(breaker.getSuccessfulCalls()).isEqualTo(0);
      assertThat(breaker.getFailedCalls()).isEqualTo(0);
      assertThat(breaker.getRejectedCalls()).isEqualTo(0);
    }
  }

  @Nested
  @DisplayName("Configuration")
  class Configuration {

    @Test
    @DisplayName("should use default values")
    void defaultValues() {
      CircuitBreaker defaultBreaker = new CircuitBreaker("default");
      assertThat(defaultBreaker.getFailureThreshold()).isEqualTo(5);
      assertThat(defaultBreaker.getSuccessThreshold()).isEqualTo(3);
      assertThat(defaultBreaker.getOpenDurationMs()).isEqualTo(30_000);
      assertThat(defaultBreaker.getHalfOpenMaxAttempts()).isEqualTo(3);
    }

    @Test
    @DisplayName("should use custom values")
    void customValues() {
      CircuitBreaker custom = new CircuitBreaker("custom", 10, 5, 60_000, 4);
      assertThat(custom.getFailureThreshold()).isEqualTo(10);
      assertThat(custom.getSuccessThreshold()).isEqualTo(5);
      assertThat(custom.getOpenDurationMs()).isEqualTo(60_000);
      assertThat(custom.getHalfOpenMaxAttempts()).isEqualTo(4);
    }

    @Test
    @DisplayName("should enforce minimum values")
    void minimumValues() {
      CircuitBreaker minBreaker = new CircuitBreaker("min", 0, 0, -100, 0);
      assertThat(minBreaker.getFailureThreshold()).isGreaterThanOrEqualTo(1);
      assertThat(minBreaker.getSuccessThreshold()).isGreaterThanOrEqualTo(1);
      assertThat(minBreaker.getOpenDurationMs()).isGreaterThanOrEqualTo(0);
      assertThat(minBreaker.getHalfOpenMaxAttempts()).isGreaterThanOrEqualTo(1);
    }

    @Test
    @DisplayName("should return name")
    void getName() {
      assertThat(breaker.getName()).isEqualTo("test");
    }
  }

  @Nested
  @DisplayName("Exception Details")
  class ExceptionDetails {

    @Test
    @DisplayName("should include circuit name in exception")
    void exceptionIncludesName() {
      breaker.forceOpen();

      try {
        breaker.execute(() -> "test");
      } catch (CircuitBreaker.CircuitBreakerOpenException e) {
        assertThat(e.getCircuitName()).isEqualTo("test");
        assertThat(e.getState()).isEqualTo(CircuitBreaker.State.OPEN);
        assertThat(e.getRemainingOpenTimeMs()).isGreaterThanOrEqualTo(0);
      } catch (Exception e) {
        throw new AssertionError("Expected CircuitBreakerOpenException", e);
      }
    }
  }

  @Nested
  @DisplayName("Concurrency")
  class Concurrency {

    @Test
    @DisplayName("should handle concurrent operations")
    void concurrentOperations() throws Exception {
      CircuitBreaker concurrentBreaker = new CircuitBreaker("concurrent", 100, 10, 1000, 50);
      int threadCount = 10;
      int opsPerThread = 100;
      CountDownLatch startLatch = new CountDownLatch(1);
      CountDownLatch doneLatch = new CountDownLatch(threadCount);
      AtomicInteger errors = new AtomicInteger(0);

      for (int t = 0; t < threadCount; t++) {
        new Thread(() -> {
          try {
            startLatch.await();
            for (int i = 0; i < opsPerThread; i++) {
              try {
                concurrentBreaker.execute(() -> "success");
              } catch (Exception e) {
                errors.incrementAndGet();
              }
            }
          } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
          } finally {
            doneLatch.countDown();
          }
        }).start();
      }

      startLatch.countDown();
      boolean completed = doneLatch.await(30, TimeUnit.SECONDS);

      assertThat(completed).isTrue();
      assertThat(errors.get()).isEqualTo(0);
      assertThat(concurrentBreaker.getTotalCalls()).isEqualTo(threadCount * opsPerThread);
      assertThat(concurrentBreaker.isClosed()).isTrue();
    }
  }
}

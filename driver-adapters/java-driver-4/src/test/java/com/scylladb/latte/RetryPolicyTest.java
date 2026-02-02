package com.scylladb.latte;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

@DisplayName("RetryPolicy")
class RetryPolicyTest {

  private RetryPolicy retryPolicy;

  @BeforeEach
  void setUp() {
    // Use minimal delays for fast tests
    retryPolicy = new RetryPolicy(3, 1, 10, 2.0, 0.0);
  }

  @Nested
  @DisplayName("execute")
  class Execute {

    @Test
    @DisplayName("should return result on first try success")
    void successOnFirstTry() throws Exception {
      String result = retryPolicy.execute(() -> "success", "test");
      assertThat(result).isEqualTo("success");
    }

    @Test
    @DisplayName("should retry on retryable exception and succeed")
    void retryAndSucceed() throws Exception {
      AtomicInteger attempts = new AtomicInteger(0);

      String result =
          retryPolicy.execute(
              () -> {
                if (attempts.incrementAndGet() < 3) {
                  throw new RuntimeException("Connection refused");
                }
                return "success";
              },
              "test");

      assertThat(result).isEqualTo("success");
      assertThat(attempts.get()).isEqualTo(3);
    }

    @Test
    @DisplayName("should throw after max retries exhausted")
    void exhaustRetries() {
      AtomicInteger attempts = new AtomicInteger(0);

      assertThatThrownBy(
              () ->
                  retryPolicy.execute(
                      () -> {
                        attempts.incrementAndGet();
                        throw new RuntimeException("Connection refused");
                      },
                      "test"))
          .isInstanceOf(RuntimeException.class)
          .hasMessageContaining("Connection refused");

      // Initial attempt + 3 retries = 4 total attempts
      assertThat(attempts.get()).isEqualTo(4);
    }

    @Test
    @DisplayName("should not retry non-retryable exceptions")
    void noRetryForNonRetryable() {
      AtomicInteger attempts = new AtomicInteger(0);

      assertThatThrownBy(
              () ->
                  retryPolicy.execute(
                      () -> {
                        attempts.incrementAndGet();
                        throw new IllegalArgumentException("Invalid argument");
                      },
                      "test"))
          .isInstanceOf(IllegalArgumentException.class);

      assertThat(attempts.get()).isEqualTo(1);
    }
  }

  @Nested
  @DisplayName("executeVoid")
  class ExecuteVoid {

    @Test
    @DisplayName("should complete on first try success")
    void successOnFirstTry() throws Exception {
      AtomicInteger counter = new AtomicInteger(0);
      retryPolicy.executeVoid(counter::incrementAndGet, "test");
      assertThat(counter.get()).isEqualTo(1);
    }

    @Test
    @DisplayName("should retry void operation")
    void retryVoidOperation() throws Exception {
      AtomicInteger attempts = new AtomicInteger(0);

      retryPolicy.executeVoid(
          () -> {
            if (attempts.incrementAndGet() < 2) {
              throw new RuntimeException("Connection reset");
            }
          },
          "test");

      assertThat(attempts.get()).isEqualTo(2);
    }
  }

  @Nested
  @DisplayName("isRetryable")
  class IsRetryable {

    @Test
    @DisplayName("should return true for connection refused")
    void connectionRefused() {
      assertThat(retryPolicy.isRetryable(new RuntimeException("Connection refused")))
          .isTrue();
    }

    @Test
    @DisplayName("should return true for connection reset")
    void connectionReset() {
      assertThat(retryPolicy.isRetryable(new RuntimeException("Connection reset by peer")))
          .isTrue();
    }

    @Test
    @DisplayName("should return true for connection timeout")
    void connectionTimeout() {
      assertThat(retryPolicy.isRetryable(new RuntimeException("Connection timed out")))
          .isTrue();
    }

    @Test
    @DisplayName("should return true for connect timeout")
    void connectTimeout() {
      assertThat(retryPolicy.isRetryable(new RuntimeException("Connect timed out")))
          .isTrue();
    }

    @Test
    @DisplayName("should return true for no host available")
    void noHostAvailable() {
      assertThat(retryPolicy.isRetryable(new RuntimeException("No host available")))
          .isTrue();
    }

    @Test
    @DisplayName("should return true for all hosts failed")
    void allHostsFailed() {
      assertThat(retryPolicy.isRetryable(new RuntimeException("All hosts failed")))
          .isTrue();
    }

    @Test
    @DisplayName("should return true for overloaded")
    void overloaded() {
      assertThat(retryPolicy.isRetryable(new RuntimeException("Server overloaded")))
          .isTrue();
    }

    @Test
    @DisplayName("should return true for network unreachable")
    void networkUnreachable() {
      assertThat(retryPolicy.isRetryable(new RuntimeException("Network is unreachable")))
          .isTrue();
    }

    @Test
    @DisplayName("should return false for null exception")
    void nullException() {
      assertThat(retryPolicy.isRetryable(null)).isFalse();
    }

    @Test
    @DisplayName("should return false for generic exception")
    void genericException() {
      assertThat(retryPolicy.isRetryable(new RuntimeException("Some error")))
          .isFalse();
    }

    @Test
    @DisplayName("should return false for illegal argument")
    void illegalArgument() {
      assertThat(retryPolicy.isRetryable(new IllegalArgumentException("Bad input")))
          .isFalse();
    }

    @Test
    @DisplayName("should check cause chain")
    void checkCauseChain() {
      RuntimeException wrapper =
          new RuntimeException("Wrapper", new RuntimeException("Connection refused"));
      assertThat(retryPolicy.isRetryable(wrapper)).isTrue();
    }

    @Test
    @DisplayName("should handle case insensitive matching")
    void caseInsensitive() {
      assertThat(retryPolicy.isRetryable(new RuntimeException("CONNECTION REFUSED")))
          .isTrue();
    }
  }

  @Nested
  @DisplayName("calculateDelay")
  class CalculateDelay {

    @Test
    @DisplayName("should return initial delay for first attempt")
    void firstAttempt() {
      RetryPolicy policy = new RetryPolicy(3, 100, 10000, 2.0, 0.0);
      assertThat(policy.calculateDelay(0)).isEqualTo(100);
    }

    @Test
    @DisplayName("should apply exponential backoff")
    void exponentialBackoff() {
      RetryPolicy policy = new RetryPolicy(3, 100, 10000, 2.0, 0.0);
      assertThat(policy.calculateDelay(0)).isEqualTo(100);
      assertThat(policy.calculateDelay(1)).isEqualTo(200);
      assertThat(policy.calculateDelay(2)).isEqualTo(400);
      assertThat(policy.calculateDelay(3)).isEqualTo(800);
    }

    @Test
    @DisplayName("should cap at max delay")
    void maxDelayCap() {
      RetryPolicy policy = new RetryPolicy(10, 100, 500, 2.0, 0.0);
      // 100 * 2^3 = 800, but capped at 500
      assertThat(policy.calculateDelay(3)).isEqualTo(500);
      assertThat(policy.calculateDelay(10)).isEqualTo(500);
    }

    @Test
    @DisplayName("should add jitter")
    void withJitter() {
      RetryPolicy policy = new RetryPolicy(3, 100, 10000, 2.0, 0.5);
      // With 50% jitter, delay should be in range [50, 150] for first attempt
      long delay = policy.calculateDelay(0);
      assertThat(delay).isBetween(50L, 150L);
    }

    @Test
    @DisplayName("should return at least 1ms")
    void minimumDelay() {
      RetryPolicy policy = new RetryPolicy(3, 0, 10, 1.0, 0.0);
      assertThat(policy.calculateDelay(0)).isGreaterThanOrEqualTo(1);
    }
  }

  @Nested
  @DisplayName("Configuration")
  class Configuration {

    @Test
    @DisplayName("should use default values")
    void defaultValues() {
      RetryPolicy policy = new RetryPolicy();
      assertThat(policy.getMaxRetries()).isEqualTo(3);
      assertThat(policy.getInitialDelayMs()).isEqualTo(100);
      assertThat(policy.getMaxDelayMs()).isEqualTo(10_000);
      assertThat(policy.getMultiplier()).isEqualTo(2.0);
      assertThat(policy.getJitter()).isEqualTo(0.2);
    }

    @Test
    @DisplayName("should use custom values")
    void customValues() {
      RetryPolicy policy = new RetryPolicy(5, 200, 5000, 1.5, 0.1);
      assertThat(policy.getMaxRetries()).isEqualTo(5);
      assertThat(policy.getInitialDelayMs()).isEqualTo(200);
      assertThat(policy.getMaxDelayMs()).isEqualTo(5000);
      assertThat(policy.getMultiplier()).isEqualTo(1.5);
      assertThat(policy.getJitter()).isEqualTo(0.1);
    }
  }
}

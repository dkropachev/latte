package com.scylladb.latte;

import java.util.concurrent.ThreadLocalRandom;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Retry policy for transient connection failures.
 *
 * <p>Implements exponential backoff with jitter for retrying failed operations. This helps prevent
 * thundering herd problems when many clients retry simultaneously.
 *
 * <h2>Default Configuration</h2>
 *
 * <ul>
 *   <li>Max retries: 3
 *   <li>Initial delay: 100ms
 *   <li>Max delay: 10 seconds
 *   <li>Multiplier: 2.0
 *   <li>Jitter: 0.2 (20%)
 * </ul>
 */
public class RetryPolicy {
  private static final Logger logger = LoggerFactory.getLogger(RetryPolicy.class);

  private final int maxRetries;
  private final long initialDelayMs;
  private final long maxDelayMs;
  private final double multiplier;
  private final double jitter;

  /** Creates a retry policy with default settings. */
  public RetryPolicy() {
    this(3, 100, 10_000, 2.0, 0.2);
  }

  /**
   * Creates a retry policy with custom settings.
   *
   * @param maxRetries maximum number of retry attempts
   * @param initialDelayMs initial delay between retries in milliseconds
   * @param maxDelayMs maximum delay between retries in milliseconds
   * @param multiplier exponential backoff multiplier
   * @param jitter jitter factor (0.0 to 1.0) to add randomness to delays
   */
  public RetryPolicy(
      int maxRetries, long initialDelayMs, long maxDelayMs, double multiplier, double jitter) {
    this.maxRetries = maxRetries;
    this.initialDelayMs = initialDelayMs;
    this.maxDelayMs = maxDelayMs;
    this.multiplier = multiplier;
    this.jitter = jitter;
  }

  /**
   * Execute an operation with retry logic.
   *
   * @param <T> the return type of the operation
   * @param operation the operation to execute
   * @param operationName name of the operation for logging
   * @return the result of the operation
   * @throws Exception if all retries fail
   */
  public <T> T execute(RetryableOperation<T> operation, String operationName) throws Exception {
    Exception lastException = null;
    int attempt = 0;

    while (attempt <= maxRetries) {
      try {
        return operation.execute();
      } catch (Exception e) {
        lastException = e;

        if (!isRetryable(e)) {
          logger.debug("Non-retryable exception for {}: {}", operationName, e.getMessage());
          throw e;
        }

        if (attempt >= maxRetries) {
          logger.warn(
              "All {} retries exhausted for {}: {}",
              maxRetries,
              operationName,
              e.getMessage());
          throw e;
        }

        long delay = calculateDelay(attempt);
        logger.debug(
            "Retry {} of {} for {} after {}ms: {}",
            attempt + 1,
            maxRetries,
            operationName,
            delay,
            e.getMessage());

        try {
          Thread.sleep(delay);
        } catch (InterruptedException ie) {
          Thread.currentThread().interrupt();
          throw e;
        }

        attempt++;
      }
    }

    throw lastException;
  }

  /**
   * Execute a void operation with retry logic.
   *
   * @param operation the operation to execute
   * @param operationName name of the operation for logging
   * @throws Exception if all retries fail
   */
  public void executeVoid(RetryableVoidOperation operation, String operationName)
      throws Exception {
    execute(
        () -> {
          operation.execute();
          return null;
        },
        operationName);
  }

  /**
   * Determine if an exception is retryable.
   *
   * <p>The following exceptions are considered retryable:
   *
   * <ul>
   *   <li>Connection timeouts
   *   <li>Connection refused
   *   <li>Temporary network errors
   *   <li>Overloaded server errors
   * </ul>
   *
   * @param e the exception to check
   * @return true if the operation should be retried
   */
  public boolean isRetryable(Exception e) {
    if (e == null) {
      return false;
    }

    String message = e.getMessage();
    if (message == null) {
      message = "";
    }
    message = message.toLowerCase();

    // Connection-related retryable errors
    if (message.contains("connection refused")
        || message.contains("connection reset")
        || message.contains("connection timed out")
        || message.contains("connect timed out")
        || message.contains("no host available")
        || message.contains("all hosts failed")
        || message.contains("host is down")
        || message.contains("network is unreachable")) {
      return true;
    }

    // Server overload errors
    if (message.contains("overloaded") || message.contains("too many requests")) {
      return true;
    }

    // Check for specific driver exceptions
    String className = e.getClass().getName().toLowerCase();
    if (className.contains("allnodesfailedexception")
        || className.contains("nohostavailableexception")
        || className.contains("transportexception")
        || className.contains("connectionexception")) {
      return true;
    }

    // Check cause chain
    Throwable cause = e.getCause();
    if (cause instanceof Exception && cause != e) {
      return isRetryable((Exception) cause);
    }

    return false;
  }

  /**
   * Calculate delay for a given attempt using exponential backoff with jitter.
   *
   * @param attempt the current attempt number (0-based)
   * @return the delay in milliseconds
   */
  long calculateDelay(int attempt) {
    // Exponential backoff
    double delay = initialDelayMs * Math.pow(multiplier, attempt);

    // Cap at max delay
    delay = Math.min(delay, maxDelayMs);

    // Add jitter
    if (jitter > 0) {
      double jitterAmount = delay * jitter;
      delay = delay + (ThreadLocalRandom.current().nextDouble() * 2 - 1) * jitterAmount;
    }

    return Math.max(1, (long) delay);
  }

  /** Functional interface for retryable operations that return a value. */
  @FunctionalInterface
  public interface RetryableOperation<T> {
    T execute() throws Exception;
  }

  /** Functional interface for retryable operations that return void. */
  @FunctionalInterface
  public interface RetryableVoidOperation {
    void execute() throws Exception;
  }

  // Getters for configuration values

  public int getMaxRetries() {
    return maxRetries;
  }

  public long getInitialDelayMs() {
    return initialDelayMs;
  }

  public long getMaxDelayMs() {
    return maxDelayMs;
  }

  public double getMultiplier() {
    return multiplier;
  }

  public double getJitter() {
    return jitter;
  }
}

package com.scylladb.latte;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import org.jspecify.annotations.NonNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Circuit breaker implementation for cascading failure protection.
 *
 * <p>The circuit breaker prevents cascading failures by monitoring operation failures and
 * temporarily blocking operations when the failure rate exceeds a threshold. This gives the
 * downstream system time to recover.
 *
 * <h2>States</h2>
 *
 * <ul>
 *   <li><b>CLOSED</b>: Normal operation. Requests pass through and are monitored.
 *   <li><b>OPEN</b>: Circuit is broken. Requests are immediately rejected without attempting the
 *       operation.
 *   <li><b>HALF_OPEN</b>: Testing state. A limited number of requests are allowed through to test
 *       if the system has recovered.
 * </ul>
 *
 * <h2>Configuration</h2>
 *
 * <ul>
 *   <li><b>failureThreshold</b>: Number of consecutive failures before opening the circuit
 *   <li><b>successThreshold</b>: Number of consecutive successes in half-open state before closing
 *   <li><b>openDurationMs</b>: How long the circuit stays open before transitioning to half-open
 *   <li><b>halfOpenMaxAttempts</b>: Maximum concurrent attempts allowed in half-open state
 * </ul>
 *
 * <h2>Usage</h2>
 *
 * <pre>{@code
 * CircuitBreaker breaker = new CircuitBreaker("database", 5, 3, 30000);
 *
 * try {
 *     breaker.execute(() -> {
 *         // Perform operation
 *         return databaseCall();
 *     });
 * } catch (CircuitBreakerOpenException e) {
 *     // Circuit is open, handle gracefully
 * }
 * }</pre>
 */
public class CircuitBreaker {

  private static final Logger logger = LoggerFactory.getLogger(CircuitBreaker.class);

  /** Possible states of the circuit breaker. */
  public enum State {
    /** Normal operation - requests pass through. */
    CLOSED,
    /** Circuit broken - requests are rejected. */
    OPEN,
    /** Testing recovery - limited requests allowed. */
    HALF_OPEN
  }

  private final String name;
  private final int failureThreshold;
  private final int successThreshold;
  private final long openDurationMs;
  private final int halfOpenMaxAttempts;

  private final AtomicReference<State> state = new AtomicReference<>(State.CLOSED);
  private final AtomicInteger consecutiveFailures = new AtomicInteger(0);
  private final AtomicInteger consecutiveSuccesses = new AtomicInteger(0);
  private final AtomicLong openedAt = new AtomicLong(0);
  private final AtomicInteger halfOpenAttempts = new AtomicInteger(0);

  // Metrics
  private final AtomicLong totalCalls = new AtomicLong(0);
  private final AtomicLong successfulCalls = new AtomicLong(0);
  private final AtomicLong failedCalls = new AtomicLong(0);
  private final AtomicLong rejectedCalls = new AtomicLong(0);

  /**
   * Create a circuit breaker with default configuration.
   *
   * <p>Defaults: 5 failures to open, 3 successes to close, 30 second open duration.
   *
   * @param name the name of this circuit breaker (for logging)
   */
  public CircuitBreaker(@NonNull String name) {
    this(name, 5, 3, 30_000, 3);
  }

  /**
   * Create a circuit breaker with custom configuration.
   *
   * @param name the name of this circuit breaker (for logging)
   * @param failureThreshold consecutive failures before opening the circuit
   * @param successThreshold consecutive successes in half-open before closing
   * @param openDurationMs how long the circuit stays open in milliseconds
   */
  public CircuitBreaker(
      @NonNull String name, int failureThreshold, int successThreshold, long openDurationMs) {
    this(name, failureThreshold, successThreshold, openDurationMs, 3);
  }

  /**
   * Create a circuit breaker with full configuration.
   *
   * @param name the name of this circuit breaker (for logging)
   * @param failureThreshold consecutive failures before opening the circuit
   * @param successThreshold consecutive successes in half-open before closing
   * @param openDurationMs how long the circuit stays open in milliseconds
   * @param halfOpenMaxAttempts maximum concurrent attempts in half-open state
   */
  public CircuitBreaker(
      @NonNull String name,
      int failureThreshold,
      int successThreshold,
      long openDurationMs,
      int halfOpenMaxAttempts) {
    this.name = name;
    this.failureThreshold = Math.max(1, failureThreshold);
    this.successThreshold = Math.max(1, successThreshold);
    this.openDurationMs = Math.max(0, openDurationMs);
    this.halfOpenMaxAttempts = Math.max(1, halfOpenMaxAttempts);
  }

  /**
   * Execute an operation through the circuit breaker.
   *
   * @param <T> the return type
   * @param operation the operation to execute
   * @return the result of the operation
   * @throws CircuitBreakerOpenException if the circuit is open and rejecting requests
   * @throws Exception if the operation throws
   */
  public <T> T execute(@NonNull Operation<T> operation) throws Exception {
    if (!allowRequest()) {
      rejectedCalls.incrementAndGet();
      throw new CircuitBreakerOpenException(name, getState(), getRemainingOpenTimeMs());
    }

    totalCalls.incrementAndGet();

    try {
      T result = operation.execute();
      onSuccess();
      successfulCalls.incrementAndGet();
      return result;
    } catch (Exception e) {
      onFailure(e);
      failedCalls.incrementAndGet();
      throw e;
    }
  }

  /**
   * Execute a void operation through the circuit breaker.
   *
   * @param operation the operation to execute
   * @throws CircuitBreakerOpenException if the circuit is open and rejecting requests
   * @throws Exception if the operation throws
   */
  public void executeVoid(@NonNull VoidOperation operation) throws Exception {
    execute(
        () -> {
          operation.execute();
          return null;
        });
  }

  /**
   * Check if a request should be allowed through.
   *
   * @return true if the request should be attempted
   */
  public boolean allowRequest() {
    State currentState = state.get();

    switch (currentState) {
      case CLOSED:
        return true;

      case OPEN:
        // Check if we should transition to half-open
        if (shouldTransitionToHalfOpen()) {
          if (state.compareAndSet(State.OPEN, State.HALF_OPEN)) {
            logger.info("[{}] Circuit breaker transitioning to HALF_OPEN", name);
            halfOpenAttempts.set(0);
            consecutiveSuccesses.set(0);
          }
          return allowRequest(); // Re-check after state change
        }
        return false;

      case HALF_OPEN:
        // Allow limited attempts in half-open state
        int attempts = halfOpenAttempts.incrementAndGet();
        if (attempts > halfOpenMaxAttempts) {
          halfOpenAttempts.decrementAndGet();
          return false;
        }
        return true;

      default:
        return false;
    }
  }

  private boolean shouldTransitionToHalfOpen() {
    long elapsed = System.currentTimeMillis() - openedAt.get();
    return elapsed >= openDurationMs;
  }

  private void onSuccess() {
    State currentState = state.get();

    if (currentState == State.HALF_OPEN) {
      int successes = consecutiveSuccesses.incrementAndGet();
      halfOpenAttempts.decrementAndGet();

      if (successes >= successThreshold) {
        if (state.compareAndSet(State.HALF_OPEN, State.CLOSED)) {
          logger.info("[{}] Circuit breaker CLOSED after {} successes", name, successes);
          consecutiveFailures.set(0);
          consecutiveSuccesses.set(0);
        }
      }
    } else if (currentState == State.CLOSED) {
      consecutiveFailures.set(0);
    }
  }

  private void onFailure(Exception e) {
    State currentState = state.get();

    if (currentState == State.HALF_OPEN) {
      halfOpenAttempts.decrementAndGet();
      // Any failure in half-open state reopens the circuit
      if (state.compareAndSet(State.HALF_OPEN, State.OPEN)) {
        openedAt.set(System.currentTimeMillis());
        consecutiveSuccesses.set(0);
        logger.warn("[{}] Circuit breaker REOPENED after failure in half-open: {}", name, e.getMessage());
      }
    } else if (currentState == State.CLOSED) {
      int failures = consecutiveFailures.incrementAndGet();
      if (failures >= failureThreshold) {
        if (state.compareAndSet(State.CLOSED, State.OPEN)) {
          openedAt.set(System.currentTimeMillis());
          logger.warn(
              "[{}] Circuit breaker OPENED after {} consecutive failures", name, failures);
        }
      }
    }
  }

  /**
   * Get the current state of the circuit breaker.
   *
   * @return the current state
   */
  public @NonNull State getState() {
    return state.get();
  }

  /**
   * Check if the circuit is currently open (rejecting requests).
   *
   * @return true if the circuit is open
   */
  public boolean isOpen() {
    return state.get() == State.OPEN;
  }

  /**
   * Check if the circuit is currently closed (allowing requests).
   *
   * @return true if the circuit is closed
   */
  public boolean isClosed() {
    return state.get() == State.CLOSED;
  }

  /**
   * Check if the circuit is currently half-open (testing recovery).
   *
   * @return true if the circuit is half-open
   */
  public boolean isHalfOpen() {
    return state.get() == State.HALF_OPEN;
  }

  /**
   * Get the remaining time until the circuit transitions from open to half-open.
   *
   * @return remaining time in milliseconds, or 0 if not in open state
   */
  public long getRemainingOpenTimeMs() {
    if (state.get() != State.OPEN) {
      return 0;
    }
    long elapsed = System.currentTimeMillis() - openedAt.get();
    return Math.max(0, openDurationMs - elapsed);
  }

  /**
   * Force the circuit to close (for testing or manual intervention).
   *
   * <p>Use with caution - this bypasses the normal state machine.
   */
  public void forceClose() {
    state.set(State.CLOSED);
    consecutiveFailures.set(0);
    consecutiveSuccesses.set(0);
    logger.info("[{}] Circuit breaker force closed", name);
  }

  /**
   * Force the circuit to open (for testing or manual intervention).
   *
   * <p>Use with caution - this bypasses the normal state machine.
   */
  public void forceOpen() {
    state.set(State.OPEN);
    openedAt.set(System.currentTimeMillis());
    logger.info("[{}] Circuit breaker force opened", name);
  }

  /**
   * Reset all metrics.
   *
   * <p>This does not affect the circuit state.
   */
  public void resetMetrics() {
    totalCalls.set(0);
    successfulCalls.set(0);
    failedCalls.set(0);
    rejectedCalls.set(0);
  }

  // Getters for configuration
  public @NonNull String getName() {
    return name;
  }

  public int getFailureThreshold() {
    return failureThreshold;
  }

  public int getSuccessThreshold() {
    return successThreshold;
  }

  public long getOpenDurationMs() {
    return openDurationMs;
  }

  public int getHalfOpenMaxAttempts() {
    return halfOpenMaxAttempts;
  }

  // Getters for metrics
  public long getTotalCalls() {
    return totalCalls.get();
  }

  public long getSuccessfulCalls() {
    return successfulCalls.get();
  }

  public long getFailedCalls() {
    return failedCalls.get();
  }

  public long getRejectedCalls() {
    return rejectedCalls.get();
  }

  public int getConsecutiveFailures() {
    return consecutiveFailures.get();
  }

  public int getConsecutiveSuccesses() {
    return consecutiveSuccesses.get();
  }

  /** Functional interface for operations that return a value. */
  @FunctionalInterface
  public interface Operation<T> {
    T execute() throws Exception;
  }

  /** Functional interface for void operations. */
  @FunctionalInterface
  public interface VoidOperation {
    void execute() throws Exception;
  }

  /** Exception thrown when the circuit breaker is open and rejecting requests. */
  public static class CircuitBreakerOpenException extends RuntimeException {
    private final String circuitName;
    private final State state;
    private final long remainingOpenTimeMs;

    public CircuitBreakerOpenException(String circuitName, State state, long remainingOpenTimeMs) {
      super(
          String.format(
              "Circuit breaker '%s' is %s (remaining open time: %dms)",
              circuitName, state, remainingOpenTimeMs));
      this.circuitName = circuitName;
      this.state = state;
      this.remainingOpenTimeMs = remainingOpenTimeMs;
    }

    public String getCircuitName() {
      return circuitName;
    }

    public State getState() {
      return state;
    }

    public long getRemainingOpenTimeMs() {
      return remainingOpenTimeMs;
    }
  }
}

package com.scylladb.latte;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Main entry point for the Latte Java Driver Adapter. */
public class LatteDriver {
  private static final Logger logger = LoggerFactory.getLogger(LatteDriver.class);

  private static final String DEFAULT_SOCKET_PATH = "/tmp/latte-driver.sock";
  private static final String DEFAULT_CONTACT_POINTS = "127.0.0.1";
  private static final int DEFAULT_INFLIGHT = 512;
  private static final int DEFAULT_WORKER_THREADS = 64;
  private static final int DEFAULT_DRAIN_TIMEOUT = 30;

  public static void main(String[] args) {
    String socketPath = getEnv("LATTE_DRIVER_SOCKET", DEFAULT_SOCKET_PATH);
    String contactPoints = getEnv("LATTE_DRIVER_CONTACT_POINTS", DEFAULT_CONTACT_POINTS);
    int maxInflight = Integer.parseInt(getEnv("LATTE_DRIVER_INFLIGHT", String.valueOf(DEFAULT_INFLIGHT)));
    int workerThreads = Integer.parseInt(getEnv("LATTE_WORKER_THREADS", String.valueOf(DEFAULT_WORKER_THREADS)));
    int drainTimeout = Integer.parseInt(getEnv("LATTE_DRAIN_TIMEOUT", String.valueOf(DEFAULT_DRAIN_TIMEOUT)));

    logger.info("Latte Java Driver Adapter starting...");
    logger.info("  Socket: {}", socketPath);
    logger.info("  Contact points: {}", contactPoints);
    logger.info("  Max inflight: {}", maxInflight);
    logger.info("  Worker threads: {}", workerThreads);
    logger.info("  Drain timeout: {}s", drainTimeout);
    logger.info("  Driver: ScyllaDB Java Driver 4.19.0.4");

    SessionManager sessionManager = new SessionManager(contactPoints);
    RequestHandler requestHandler = new RequestHandler(sessionManager);
    Server server = new Server(socketPath, requestHandler, maxInflight, drainTimeout, workerThreads);

    // Register shutdown hook
    Runtime.getRuntime()
        .addShutdownHook(
            new Thread(
                () -> {
                  logger.info("Shutting down...");
                  server.stop();
                  sessionManager.closeAll();
                }));

    try {
      server.start();
    } catch (Exception e) {
      logger.error("Server failed", e);
      System.exit(1);
    }
  }

  private static String getEnv(String name, String defaultValue) {
    String value = System.getenv(name);
    return value != null ? value : defaultValue;
  }
}

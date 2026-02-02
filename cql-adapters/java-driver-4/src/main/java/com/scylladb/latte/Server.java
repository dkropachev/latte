package com.scylladb.latte;

import io.netty.bootstrap.ServerBootstrap;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelOption;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.epoll.EpollDomainSocketChannel;
import io.netty.channel.epoll.EpollEventLoopGroup;
import io.netty.channel.epoll.EpollServerDomainSocketChannel;
import io.netty.channel.unix.DomainSocketAddress;
import io.netty.handler.codec.ByteToMessageDecoder;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Unix domain socket server for the Latte IPC protocol using Netty.
 *
 * <p>This server handles incoming connections over a Unix domain socket and processes CQL requests
 * using the configured RequestHandler. It supports graceful shutdown with draining of in-flight
 * requests.
 *
 * <h2>Request Handling Architecture</h2>
 *
 * <p>The server uses an <b>executor-based</b> request handling model:
 *
 * <ol>
 *   <li>Netty I/O threads decode incoming frames
 *   <li>Frames are submitted to a fixed-size thread pool executor
 *   <li>Worker threads execute requests synchronously using the Java driver's blocking API
 *   <li>Responses are written back to the Netty channel from the worker thread
 * </ol>
 *
 * <p><b>Alternative: Async Completion Model</b>
 *
 * <p>An alternative approach would use the Java driver's async API (CompletableFuture-based):
 *
 * <ul>
 *   <li>Pros: Better thread utilization, no thread blocking, scales better for high concurrency
 *   <li>Cons: More complex error handling, callback chains, harder to debug
 * </ul>
 *
 * <p>The current executor-based approach was chosen for:
 *
 * <ul>
 *   <li>Simplicity and maintainability
 *   <li>Predictable resource usage (fixed thread count)
 *   <li>Easier debugging with synchronous stack traces
 *   <li>Sufficient throughput for most benchmarking scenarios
 * </ul>
 *
 * <p>The thread pool size can be tuned via {@code LATTE_WORKER_THREADS} environment variable.
 */
public class Server {
  private static final Logger logger = LoggerFactory.getLogger(Server.class);

  /** Default timeout for graceful shutdown draining (in seconds). */
  private static final int DEFAULT_DRAIN_TIMEOUT_SECONDS = 30;

  /** Default number of worker threads for request processing. */
  private static final int DEFAULT_WORKER_THREADS = 64;

  private final String socketPath;
  private final RequestHandler requestHandler;
  private final int maxInflight;
  private final int drainTimeoutSeconds;
  private final int workerThreads;
  private final AtomicInteger inflightRequests = new AtomicInteger(0);

  private EventLoopGroup bossGroup;
  private EventLoopGroup workerGroup;
  private ExecutorService requestExecutor;
  private ChannelFuture channelFuture;
  private volatile boolean shuttingDown = false;

  /**
   * Creates a new server with default settings.
   *
   * @param socketPath path to the Unix domain socket
   * @param requestHandler handler for processing requests
   * @param maxInflight maximum number of in-flight requests
   */
  public Server(String socketPath, RequestHandler requestHandler, int maxInflight) {
    this(socketPath, requestHandler, maxInflight, DEFAULT_DRAIN_TIMEOUT_SECONDS, DEFAULT_WORKER_THREADS);
  }

  /**
   * Creates a new server with custom drain timeout.
   *
   * @param socketPath path to the Unix domain socket
   * @param requestHandler handler for processing requests
   * @param maxInflight maximum number of in-flight requests
   * @param drainTimeoutSeconds timeout for waiting on in-flight requests during shutdown
   */
  public Server(
      String socketPath, RequestHandler requestHandler, int maxInflight, int drainTimeoutSeconds) {
    this(socketPath, requestHandler, maxInflight, drainTimeoutSeconds, DEFAULT_WORKER_THREADS);
  }

  /**
   * Creates a new server with full configuration.
   *
   * @param socketPath path to the Unix domain socket
   * @param requestHandler handler for processing requests
   * @param maxInflight maximum number of in-flight requests
   * @param drainTimeoutSeconds timeout for waiting on in-flight requests during shutdown
   * @param workerThreads number of worker threads for request processing (0 = auto based on maxInflight)
   */
  public Server(
      String socketPath,
      RequestHandler requestHandler,
      int maxInflight,
      int drainTimeoutSeconds,
      int workerThreads) {
    this.socketPath = socketPath;
    this.requestHandler = requestHandler;
    this.maxInflight = maxInflight;
    this.drainTimeoutSeconds = drainTimeoutSeconds;
    this.workerThreads = workerThreads > 0 ? workerThreads : Math.min(maxInflight, DEFAULT_WORKER_THREADS);
  }

  /**
   * Returns the current number of in-flight requests.
   *
   * @return number of requests currently being processed
   */
  public int getInflightCount() {
    return inflightRequests.get();
  }

  /** Start the server and listen for connections. */
  public void start() throws IOException, InterruptedException {
    Path path = Path.of(socketPath);

    // Remove existing socket file if present
    Files.deleteIfExists(path);

    // Create parent directories if needed
    Path parent = path.getParent();
    if (parent != null) {
      Files.createDirectories(parent);
    }

    // Create executor for request processing
    requestExecutor = Executors.newFixedThreadPool(workerThreads);
    logger.info("Request executor started with {} threads", workerThreads);

    // Create Netty event loop groups
    bossGroup = new EpollEventLoopGroup(1);
    workerGroup = new EpollEventLoopGroup();

    ServerBootstrap bootstrap = new ServerBootstrap();
    bootstrap
        .group(bossGroup, workerGroup)
        .channel(EpollServerDomainSocketChannel.class)
        .childHandler(
            new ChannelInitializer<EpollDomainSocketChannel>() {
              @Override
              protected void initChannel(EpollDomainSocketChannel ch) {
                ch.pipeline()
                    .addLast(new FrameDecoder())
                    .addLast(new RequestHandlerAdapter(requestHandler, requestExecutor, inflightRequests));
              }
            });
        // Note: SO_KEEPALIVE is not applicable to Unix domain sockets

    channelFuture = bootstrap.bind(new DomainSocketAddress(socketPath)).sync();
    logger.info("Server listening on {}", socketPath);

    // Wait until the server socket is closed
    channelFuture.channel().closeFuture().sync();
  }

  /**
   * Stop the server gracefully.
   *
   * <p>This method will:
   *
   * <ol>
   *   <li>Stop accepting new connections
   *   <li>Wait for in-flight requests to complete (up to drain timeout)
   *   <li>Shutdown the executor and event loops
   *   <li>Clean up the socket file
   * </ol>
   */
  public void stop() {
    shuttingDown = true;
    logger.info("Initiating graceful shutdown...");

    try {
      // Stop accepting new connections
      if (channelFuture != null) {
        channelFuture.channel().close().sync();
        logger.info("Stopped accepting new connections");
      }

      // Wait for in-flight requests to drain
      int inflight = inflightRequests.get();
      if (inflight > 0) {
        logger.info("Waiting for {} in-flight requests to complete...", inflight);
        long deadline = System.currentTimeMillis() + (drainTimeoutSeconds * 1000L);

        while (inflightRequests.get() > 0 && System.currentTimeMillis() < deadline) {
          Thread.sleep(100);
        }

        int remaining = inflightRequests.get();
        if (remaining > 0) {
          logger.warn(
              "Drain timeout reached with {} requests still in-flight, proceeding with shutdown",
              remaining);
        } else {
          logger.info("All in-flight requests completed");
        }
      }

      // Shutdown executor gracefully
      if (requestExecutor != null) {
        requestExecutor.shutdown();
        if (!requestExecutor.awaitTermination(5, TimeUnit.SECONDS)) {
          logger.warn("Executor did not terminate gracefully, forcing shutdown");
          requestExecutor.shutdownNow();
        }
      }

      // Shutdown Netty event loops
      if (workerGroup != null) {
        workerGroup.shutdownGracefully(0, 5, TimeUnit.SECONDS).sync();
      }
      if (bossGroup != null) {
        bossGroup.shutdownGracefully(0, 5, TimeUnit.SECONDS).sync();
      }

      // Clean up socket file
      Files.deleteIfExists(Path.of(socketPath));

    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      logger.warn("Shutdown interrupted");
    } catch (Exception e) {
      logger.error("Error during graceful shutdown", e);
    }

    logger.info("Server stopped");
  }

  /**
   * Check if the server is shutting down.
   *
   * @return true if shutdown has been initiated
   */
  public boolean isShuttingDown() {
    return shuttingDown;
  }

  /** Decoder that reads IPC protocol frames from the byte stream. */
  private static class FrameDecoder extends ByteToMessageDecoder {
    @Override
    protected void decode(ChannelHandlerContext ctx, ByteBuf in, List<Object> out) {
      // Need at least the header
      if (in.readableBytes() < Protocol.HEADER_LENGTH) {
        return;
      }

      in.markReaderIndex();

      byte version = in.readByte();
      byte flags = in.readByte();
      short stream = in.readShort();
      byte opcode = in.readByte();
      int bodyLength = in.readInt();

      if (bodyLength < 0 || bodyLength > Protocol.MAX_BODY_LENGTH) {
        logger.error("Invalid body length: {}", bodyLength);
        ctx.close();
        return;
      }

      // Check if we have the full body
      if (in.readableBytes() < bodyLength) {
        in.resetReaderIndex();
        return;
      }

      // Read body
      byte[] body = new byte[bodyLength];
      in.readBytes(body);

      // Create frame and pass to next handler
      Protocol.Frame frame = new Protocol.Frame(version, flags, stream, opcode, body);
      out.add(frame);
    }
  }

  /** Handler that processes frames and writes responses. */
  private static class RequestHandlerAdapter extends ChannelInboundHandlerAdapter {
    private final RequestHandler requestHandler;
    private final ExecutorService executor;
    private final AtomicInteger inflightRequests;

    RequestHandlerAdapter(
        RequestHandler requestHandler,
        ExecutorService executor,
        AtomicInteger inflightRequests) {
      this.requestHandler = requestHandler;
      this.executor = executor;
      this.inflightRequests = inflightRequests;
    }

    @Override
    public void channelActive(ChannelHandlerContext ctx) {
      logger.info("Client connected: {}", ctx.channel().remoteAddress());
    }

    @Override
    public void channelInactive(ChannelHandlerContext ctx) {
      logger.info("Client disconnected: {}", ctx.channel().remoteAddress());
    }

    @Override
    public void channelRead(ChannelHandlerContext ctx, Object msg) {
      Protocol.Frame frame = (Protocol.Frame) msg;
      if (logger.isDebugEnabled()) {
        logger.debug("Received frame: stream={}, opcode={}", frame.stream(), frame.opcode());
      }

      // Track in-flight request
      inflightRequests.incrementAndGet();

      // Process request in executor to not block the event loop
      executor.submit(
          () -> {
            try {
              byte[] response = requestHandler.handleFrame(frame);
              if (logger.isDebugEnabled()) {
                logger.debug(
                    "Sending response: {} bytes for stream={}", response.length, frame.stream());
              }

              // Write response back - Netty handles thread-safety
              ByteBuf buf = Unpooled.wrappedBuffer(response);
              ctx.writeAndFlush(buf);
            } catch (Exception e) {
              logger.error("Error handling request for stream={}", frame.stream(), e);
              byte[] errorFrame =
                  Protocol.FrameBuilder.buildErrorFrame(
                      frame.stream(), Protocol.ERROR_CODE_SERVER, e.getMessage());
              ByteBuf buf = Unpooled.wrappedBuffer(errorFrame);
              ctx.writeAndFlush(buf);
            } finally {
              // Decrement in-flight count when request completes
              inflightRequests.decrementAndGet();
            }
          });
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
      logger.error("Channel exception", cause);
      ctx.close();
    }
  }
}

package com.scylladb.latte;

import io.netty.bootstrap.ServerBootstrap;
import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.epoll.EpollDomainSocketChannel;
import io.netty.channel.epoll.EpollEventLoopGroup;
import io.netty.channel.epoll.EpollServerDomainSocketChannel;
import io.netty.channel.unix.DomainSocketAddress;
import io.netty.handler.codec.ByteToMessageDecoder;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
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
 * <p>The server uses an <b>async completion model</b> with the Java driver's async API:
 *
 * <ol>
 *   <li>Netty I/O threads decode incoming frames
 *   <li>Frames are processed using {@code session.executeAsync()} which returns immediately
 *   <li>When the database responds, a callback writes the response to the Netty channel
 * </ol>
 *
 * <p>This approach provides:
 *
 * <ul>
 *   <li>Better thread utilization - no threads blocked waiting for I/O
 *   <li>Higher concurrency - can handle thousands of concurrent requests with few threads
 *   <li>Lower memory usage - no dedicated thread pool needed
 *   <li>Better CPU utilization under high load
 * </ul>
 *
 * <p>The async model uses ListenableFuture callbacks from the Cassandra Java Driver 3.x,
 * which are executed on the driver's internal I/O threads via {@code directExecutor()}.
 */
public class Server {
  private static final Logger logger = LoggerFactory.getLogger(Server.class);

  /** Default timeout for graceful shutdown draining (in seconds). */
  private static final int DEFAULT_DRAIN_TIMEOUT_SECONDS = 30;

  private final String socketPath;
  private final RequestHandler requestHandler;
  private final int maxInflight;
  private final int drainTimeoutSeconds;
  private final int workerThreads;
  private final AtomicInteger inflightRequests = new AtomicInteger(0);

  private EventLoopGroup bossGroup;
  private EventLoopGroup workerGroup;
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
    this(socketPath, requestHandler, maxInflight, DEFAULT_DRAIN_TIMEOUT_SECONDS, 0);
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
    this(socketPath, requestHandler, maxInflight, drainTimeoutSeconds, 0);
  }

  /**
   * Creates a new server with custom drain timeout and worker threads.
   *
   * <p>Note: This server uses the driver's async API (executeAsync) for database operations,
   * allowing much higher concurrency without a dedicated thread pool. Requests are handled
   * directly on Netty's I/O threads with async callbacks.
   *
   * @param socketPath path to the Unix domain socket
   * @param requestHandler handler for processing requests
   * @param maxInflight maximum number of in-flight requests
   * @param drainTimeoutSeconds timeout for waiting on in-flight requests during shutdown
   * @param workerThreads number of worker threads (0 for default)
   */
  public Server(
      String socketPath, RequestHandler requestHandler, int maxInflight, int drainTimeoutSeconds, int workerThreads) {
    this.socketPath = socketPath;
    this.requestHandler = requestHandler;
    this.maxInflight = maxInflight;
    this.drainTimeoutSeconds = drainTimeoutSeconds;
    this.workerThreads = workerThreads;
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
    Path path = Paths.get(socketPath);

    // Remove existing socket file if present
    Files.deleteIfExists(path);

    // Create parent directories if needed
    Path parent = path.getParent();
    if (parent != null) {
      Files.createDirectories(parent);
    }

    // Create Netty event loop groups
    bossGroup = new EpollEventLoopGroup(1);
    workerGroup = workerThreads > 0 ? new EpollEventLoopGroup(workerThreads) : new EpollEventLoopGroup();

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
                    .addLast(
                        new RequestHandlerAdapter(requestHandler, inflightRequests, maxInflight));
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

      // Shutdown Netty event loops
      if (workerGroup != null) {
        workerGroup.shutdownGracefully(0, 5, TimeUnit.SECONDS).sync();
      }
      if (bossGroup != null) {
        bossGroup.shutdownGracefully(0, 5, TimeUnit.SECONDS).sync();
      }

      // Clean up socket file
      Files.deleteIfExists(Paths.get(socketPath));

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

      // Read body using retained slice to avoid copying
      // The slice is released when the frame is processed
      ByteBuf bodySlice = in.readRetainedSlice(bodyLength);

      // Create frame with ByteBuf body - avoids immediate byte[] allocation
      Protocol.Frame frame = new Protocol.Frame(version, flags, stream, opcode, bodySlice);
      out.add(frame);
    }
  }

  /** Handler that processes frames and writes responses. */
  private static class RequestHandlerAdapter extends ChannelInboundHandlerAdapter {
    private final RequestHandler requestHandler;
    private final AtomicInteger inflightRequests;
    private final int maxInflight;

    RequestHandlerAdapter(
        RequestHandler requestHandler,
        AtomicInteger inflightRequests,
        int maxInflight) {
      this.requestHandler = requestHandler;
      this.inflightRequests = inflightRequests;
      this.maxInflight = maxInflight;
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

      // Enforce backpressure - reject requests when at capacity
      int currentInflight = inflightRequests.get();
      if (maxInflight > 0 && currentInflight >= maxInflight) {
        if (logger.isDebugEnabled()) {
          logger.debug("Rejecting request due to backpressure: inflight={}, max={}", currentInflight, maxInflight);
        }
        // Release the frame's ByteBuf body before sending error
        frame.release();
        ByteBuf errorBuf = Protocol.PooledFrameBuilder.buildErrorFrame(
            frame.stream(), Protocol.ERROR_CODE_OVERLOADED,
            "Server overloaded: " + currentInflight + " requests in flight (max " + maxInflight + ")");
        ctx.writeAndFlush(errorBuf);
        return;
      }

      // Track in-flight request
      inflightRequests.incrementAndGet();

      // Process request asynchronously using driver's async API with zero-copy ByteBuf response
      // This avoids blocking threads on I/O and intermediate byte array allocations
      requestHandler.handleFrameAsyncByteBuf(
          frame,
          (response, error) -> {
            try {
              if (error != null) {
                logger.error("Error handling request for stream={}", frame.stream(), error);
                String errorMsg = error.getMessage() != null ? error.getMessage() : error.getClass().getSimpleName();
                // Use pooled ByteBuf for error response
                ByteBuf errorBuf = Protocol.PooledFrameBuilder.buildErrorFrame(
                    frame.stream(), Protocol.ERROR_CODE_SERVER, errorMsg);
                ctx.writeAndFlush(errorBuf);
              } else {
                if (logger.isDebugEnabled()) {
                  logger.debug(
                      "Sending response: {} bytes for stream={}", response.readableBytes(), frame.stream());
                }
                // Response is already a pooled ByteBuf, write directly
                ctx.writeAndFlush(response);
              }
            } finally {
              // Release the frame's ByteBuf body if any
              frame.release();
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

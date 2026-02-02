package com.scylladb.latte.alternator;

import java.io.IOException;
import java.net.StandardProtocolFamily;
import java.net.UnixDomainSocketAddress;
import java.nio.channels.SocketChannel;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Entry point for the Latte Alternator Java adapter.
 * Connects to a Unix domain socket created by Latte and handles requests.
 */
public final class Main {
    private static final String DEFAULT_SOCKET_PATH = "/tmp/latte-alternator.sock";
    private static final int DEFAULT_INFLIGHT = 512;
    private static final long CONNECT_TIMEOUT_MS = 30_000;
    private static final long CONNECT_RETRY_INTERVAL_MS = 100;

    public static void main(String[] args) {
        String socketPath = System.getenv("LATTE_ALTERNATOR_SOCKET");
        if (socketPath == null || socketPath.isEmpty()) {
            socketPath = DEFAULT_SOCKET_PATH;
        }

        int maxInflight = DEFAULT_INFLIGHT;
        String inflightEnv = System.getenv("LATTE_ALTERNATOR_INFLIGHT");
        if (inflightEnv != null && !inflightEnv.isEmpty()) {
            try {
                int n = Integer.parseInt(inflightEnv);
                if (n > 0) maxInflight = n;
            } catch (NumberFormatException ignored) {}
        }

        System.out.println("Starting alternator-client-java adapter");
        System.out.println("Socket: " + socketPath);
        System.out.println("Max inflight: " + maxInflight);

        SessionRegistry registry = new SessionRegistry(maxInflight);
        RequestHandler handler = new RequestHandler(registry);

        AtomicBoolean shutdownRequested = new AtomicBoolean(false);

        // Handle SIGINT/SIGTERM
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            System.out.println("Received shutdown signal");
            shutdownRequested.set(true);
            registry.closeAll();
        }));

        // Connect to latte host in a loop (reconnect after each connection closes)
        while (!shutdownRequested.get()) {
            try {
                runClient(socketPath, maxInflight, handler, shutdownRequested);
            } catch (Exception e) {
                if (shutdownRequested.get()) {
                    break;
                }
                System.err.println("Client error: " + e.getMessage());
                e.printStackTrace();
                System.exit(1);
            }
            if (!shutdownRequested.get()) {
                System.out.println("Connection closed, waiting for next latte command...");
            }
        }
    }

    private static void runClient(String socketPath, int maxInflight, RequestHandler handler,
                                   AtomicBoolean shutdownRequested) throws IOException, InterruptedException {
        Path path = Path.of(socketPath);
        UnixDomainSocketAddress address = UnixDomainSocketAddress.of(path);

        System.out.println("Connecting to latte host at " + socketPath);

        // Retry connecting for up to 30 seconds
        long deadline = System.currentTimeMillis() + CONNECT_TIMEOUT_MS;
        SocketChannel channel = null;
        while (channel == null) {
            if (shutdownRequested.get()) return;
            try {
                channel = SocketChannel.open(StandardProtocolFamily.UNIX);
                channel.connect(address);
            } catch (IOException e) {
                if (channel != null) {
                    try { channel.close(); } catch (IOException ignored) {}
                    channel = null;
                }
                if (System.currentTimeMillis() >= deadline) {
                    throw new IOException("Timeout connecting to latte socket at " + socketPath + ": " + e.getMessage(), e);
                }
                Thread.sleep(CONNECT_RETRY_INTERVAL_MS);
            }
        }

        System.out.println("Connected to latte host");

        new ConnectionHandler(channel, handler, maxInflight, shutdownRequested).run();
    }
}

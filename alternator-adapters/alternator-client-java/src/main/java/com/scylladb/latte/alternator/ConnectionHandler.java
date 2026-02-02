package com.scylladb.latte.alternator;

import com.scylladb.latte.alternator.protocol.Frame;
import com.scylladb.latte.alternator.protocol.FrameReader;
import com.scylladb.latte.alternator.protocol.Opcodes;

import java.io.*;
import java.nio.channels.Channels;
import java.nio.channels.SocketChannel;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Handles a single connection: reads frames, dispatches to handler, writes responses.
 */
public final class ConnectionHandler implements Runnable {
    private static final int BUFFER_SIZE = 64 * 1024;

    private final SocketChannel channel;
    private final RequestHandler handler;
    private final int maxInflight;
    private final AtomicBoolean shutdownRequested;

    public ConnectionHandler(SocketChannel channel, RequestHandler handler, int maxInflight,
                             AtomicBoolean shutdownRequested) {
        this.channel = channel;
        this.handler = handler;
        this.maxInflight = maxInflight;
        this.shutdownRequested = shutdownRequested;
    }

    @Override
    public void run() {
        try (channel) {
            System.out.println("New connection");

            var in = new DataInputStream(new BufferedInputStream(Channels.newInputStream(channel), BUFFER_SIZE));
            var out = new BufferedOutputStream(Channels.newOutputStream(channel), BUFFER_SIZE);
            var frameReader = new FrameReader(in);

            var sem = new Semaphore(maxInflight);
            var respQueue = new LinkedBlockingQueue<byte[]>(maxInflight);
            var writerDone = new AtomicBoolean(false);

            // Writer thread - drains response queue and writes to socket
            Thread writerThread = Thread.ofVirtual().name("writer").start(() -> {
                try {
                    while (!writerDone.get() || !respQueue.isEmpty()) {
                        byte[] resp = respQueue.poll(10, java.util.concurrent.TimeUnit.MILLISECONDS);
                        if (resp != null) {
                            out.write(resp);
                            // Flush when queue is empty
                            if (respQueue.isEmpty()) {
                                out.flush();
                            }
                        }
                    }
                    out.flush();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } catch (IOException e) {
                    System.err.println("Writer error: " + e.getMessage());
                }
            });

            // Read frames and dispatch
            try {
                while (!shutdownRequested.get()) {
                    Frame frame;
                    try {
                        frame = frameReader.readFrame();
                    } catch (EOFException e) {
                        break;
                    }

                    // Handle shutdown synchronously
                    if (frame.opcode() == Opcodes.SHUTDOWN) {
                        byte[] resp = handler.handle(frame);
                        respQueue.put(resp);
                        shutdownRequested.set(true);
                        break;
                    }

                    // Acquire semaphore (blocking backpressure)
                    sem.acquire();

                    Frame f = frame;
                    Thread.ofVirtual().name("handler").start(() -> {
                        try {
                            byte[] resp = handler.handle(f);
                            respQueue.put(resp);
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                        } finally {
                            sem.release();
                        }
                    });
                }
            } catch (IOException e) {
                if (!shutdownRequested.get()) {
                    System.err.println("Read error: " + e.getMessage());
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }

            // Wait for in-flight handlers to complete
            sem.acquire(maxInflight);
            sem.release(maxInflight);

            writerDone.set(true);
            writerThread.join();

            System.out.println("Connection closed");
        } catch (Exception e) {
            System.err.println("Connection error: " + e.getMessage());
        }
    }
}

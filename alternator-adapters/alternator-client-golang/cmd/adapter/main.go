// Package main implements the Latte Alternator adapter using alternator-client-golang.
package main

import (
	"bufio"
	"context"
	"fmt"
	"io"
	"log"
	"net"
	"os"
	"os/signal"
	"strconv"
	"sync"
	"syscall"
	"time"

	"github.com/scylladb/latte/alternator-adapters/alternator-client-golang/internal/handler"
	"github.com/scylladb/latte/alternator-adapters/alternator-client-golang/internal/protocol"
	"github.com/scylladb/latte/alternator-adapters/alternator-client-golang/internal/session"
)

const (
	defaultSocketPath = "/tmp/latte-alternator.sock"
	defaultInflight   = 512
)

func main() {
	log.SetFlags(log.LstdFlags | log.Lshortfile)

	// Get configuration from environment
	socketPath := os.Getenv("LATTE_ALTERNATOR_SOCKET")
	if socketPath == "" {
		socketPath = defaultSocketPath
	}

	maxInflight := defaultInflight
	if v := os.Getenv("LATTE_ALTERNATOR_INFLIGHT"); v != "" {
		if n, err := strconv.Atoi(v); err == nil && n > 0 {
			maxInflight = n
		}
	}

	log.Printf("Starting alternator-client-golang adapter")
	log.Printf("Socket: %s", socketPath)
	log.Printf("Max inflight: %d", maxInflight)

	// Create session registry
	registry := session.NewRegistry()

	// Create request handler
	h := handler.New(registry)

	// Setup graceful shutdown
	ctx, cancel := context.WithCancel(context.Background())
	defer cancel()

	sigChan := make(chan os.Signal, 1)
	signal.Notify(sigChan, syscall.SIGINT, syscall.SIGTERM)

	go func() {
		<-sigChan
		log.Println("Received shutdown signal")
		cancel()
	}()

	// Connect to latte host in a loop (reconnect after each connection closes)
	for {
		err := runClient(ctx, socketPath, maxInflight, h)
		if ctx.Err() != nil {
			break
		}
		if err != nil {
			log.Fatalf("Client error: %v", err)
		}
		log.Printf("Connection closed, waiting for next latte command...")
	}
}

func runClient(ctx context.Context, socketPath string, maxInflight int, h *handler.Handler) error {
	log.Printf("Connecting to latte host at %s", socketPath)

	// Retry connecting for up to 30 seconds
	deadline := time.Now().Add(30 * time.Second)
	var conn net.Conn
	for {
		var err error
		conn, err = net.Dial("unix", socketPath)
		if err == nil {
			break
		}
		if time.Now().After(deadline) {
			return fmt.Errorf("timeout connecting to latte socket at %s: %w", socketPath, err)
		}
		select {
		case <-ctx.Done():
			return ctx.Err()
		case <-time.After(100 * time.Millisecond):
		}
	}

	log.Printf("Connected to latte host")
	handleConnection(ctx, conn, maxInflight, h)
	return nil
}

func handleConnection(ctx context.Context, conn net.Conn, maxInflight int, h *handler.Handler) {
	defer conn.Close()
	log.Printf("New connection from %s", conn.RemoteAddr())

	// Create buffered reader and writer
	reader := bufio.NewReaderSize(conn, 64*1024)

	// Create semaphore for inflight limiting
	sem := make(chan struct{}, maxInflight)

	// Response writer channel
	respChan := make(chan responseItem, maxInflight)

	// Writer goroutine
	writerDone := make(chan struct{})
	go func() {
		defer close(writerDone)
		writer := bufio.NewWriterSize(conn, 64*1024)
		for resp := range respChan {
			if _, err := writer.Write(resp.data); err != nil {
				log.Printf("Write error: %v", err)
				return
			}
			// Flush if channel is empty
			if len(respChan) == 0 {
				if err := writer.Flush(); err != nil {
					log.Printf("Flush error: %v", err)
					return
				}
			}
		}
		writer.Flush()
	}()

	// Read frames
	var wg sync.WaitGroup
	for {
		select {
		case <-ctx.Done():
			goto cleanup
		default:
		}

		frame, err := protocol.ReadFrame(reader)
		if err != nil {
			if err != io.EOF {
				log.Printf("Read error: %v", err)
			}
			goto cleanup
		}

		// Check for shutdown
		if frame.Opcode == protocol.OpcodeShutdown {
			// Handle shutdown synchronously
			respBuf := &responseBuffer{}
			if err := h.Handle(ctx, frame, respBuf); err != nil {
				log.Printf("Shutdown handler error: %v", err)
			}
			respChan <- responseItem{data: respBuf.Bytes()}
			goto cleanup
		}

		// Acquire semaphore (blocking)
		sem <- struct{}{}

		wg.Add(1)
		go func(f *protocol.Frame) {
			defer wg.Done()
			defer func() { <-sem }()

			respBuf := &responseBuffer{}
			if err := h.Handle(ctx, f, respBuf); err != nil {
				log.Printf("Handler error: %v", err)
				return
			}

			select {
			case respChan <- responseItem{data: respBuf.Bytes()}:
			case <-ctx.Done():
			}
		}(frame)
	}

cleanup:
	// Wait for all handlers to complete
	wg.Wait()
	close(respChan)
	<-writerDone
	log.Println("Connection closed")
}

type responseItem struct {
	data []byte
}

type responseBuffer struct {
	data []byte
}

func (b *responseBuffer) Write(p []byte) (n int, err error) {
	b.data = append(b.data, p...)
	return len(p), nil
}

func (b *responseBuffer) Bytes() []byte {
	return b.data
}

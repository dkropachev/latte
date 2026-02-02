package server

import (
	"bufio"
	"context"
	"encoding/binary"
	"fmt"
	"net"
	"os"
	"path/filepath"
	"runtime"
	"sync"
	"time"

	"github.com/rs/zerolog/log"
	"github.com/scylladb/latte/driver-adapters/gocql/config"
	"github.com/scylladb/latte/driver-adapters/gocql/protocol"
	"github.com/scylladb/latte/driver-adapters/gocql/session"
)

// Server handles connections from Latte over Unix domain sockets.
type Server struct {
	config   *config.Config
	registry *session.Registry
}

// New creates a new server instance.
func New(cfg *config.Config, registry *session.Registry) *Server {
	return &Server{
		config:   cfg,
		registry: registry,
	}
}

// Run starts the server and blocks until context is cancelled.
func (s *Server) Run(ctx context.Context) error {
	// Create parent directory if needed
	dir := filepath.Dir(s.config.SocketPath)
	if err := os.MkdirAll(dir, 0755); err != nil {
		return fmt.Errorf("failed to create socket directory: %w", err)
	}

	// Remove stale socket if exists
	if err := os.Remove(s.config.SocketPath); err != nil && !os.IsNotExist(err) {
		return fmt.Errorf("failed to remove stale socket: %w", err)
	}

	// Create Unix socket listener
	listener, err := net.Listen("unix", s.config.SocketPath)
	if err != nil {
		return fmt.Errorf("failed to bind unix socket: %w", err)
	}
	defer listener.Close()

	// Set socket permissions to allow all users to connect (0666)
	if err := os.Chmod(s.config.SocketPath, 0666); err != nil {
		return fmt.Errorf("failed to set socket permissions: %w", err)
	}

	log.Info().
		Str("path", s.config.SocketPath).
		Int("inflight_limit", s.config.InflightLimit).
		Strs("contact_points", s.config.ContactPoints).
		Msg("driver adapter listening for host")

	// Accept connections
	go func() {
		<-ctx.Done()
		listener.Close()
	}()

	for {
		conn, err := listener.Accept()
		if err != nil {
			select {
			case <-ctx.Done():
				return nil
			default:
				log.Warn().Err(err).Msg("failed to accept connection")
				continue
			}
		}

		go s.handleConnection(conn)
	}
}

func (s *Server) handleConnection(conn net.Conn) {
	defer conn.Close()

	log.Info().Msg("latte host connected")

	// Create response channel
	responseChan := make(chan *protocol.Frame, max(s.config.InflightLimit, 64))

	// Create request channel for worker pool
	requestChan := make(chan *protocol.Frame, s.config.InflightLimit)

	// Calculate worker count: use more workers for higher inflight limits
	// but cap at a reasonable multiple of CPU cores
	numWorkers := min(s.config.InflightLimit, runtime.NumCPU()*4)
	if numWorkers < 4 {
		numWorkers = 4
	}

	// Wait group for workers
	var workerWg sync.WaitGroup

	// Start fixed worker pool
	for i := 0; i < numWorkers; i++ {
		workerWg.Add(1)
		go func() {
			defer workerWg.Done()
			for frame := range requestChan {
				response := s.dispatch(frame)
				responseChan <- response
			}
		}()
	}

	// Start writer goroutine
	writerDone := make(chan struct{})
	go func() {
		defer close(writerDone)
		writerLoop(conn, responseChan)
	}()

	// Reader loop
	reader := bufio.NewReaderSize(conn, 64*1024) // Increased from 8KB to 64KB

	for {
		frame, err := protocol.ReadFrame(reader)
		if err != nil {
			log.Warn().Err(err).Msg("failed to read frame")
			break
		}
		if frame == nil {
			log.Info().Msg("latte host disconnected")
			break
		}

		// Send to worker pool (blocks if all workers are busy and channel is full)
		requestChan <- frame
	}

	// Close request channel to signal workers to exit
	close(requestChan)

	// Wait for all workers to complete
	workerWg.Wait()

	// Close response channel to signal writer to exit
	close(responseChan)

	// Wait for writer to finish
	<-writerDone
}

func (s *Server) dispatch(frame *protocol.Frame) *protocol.Frame {
	switch frame.Header.Opcode {
	case protocol.OpcodeCreateSession:
		return s.handleCreateSession(frame)
	case protocol.OpcodeQuery:
		return s.handleQuery(frame)
	case protocol.OpcodePrepare:
		return s.handlePrepare(frame)
	case protocol.OpcodeExecute:
		return s.handleExecute(frame)
	case protocol.OpcodeBatch:
		return s.handleBatch(frame)
	default:
		return protocol.ErrorFrame(frame.Header.Stream, protocol.ErrorProtocol,
			fmt.Sprintf("unsupported opcode: %#x", frame.Header.Opcode))
	}
}

func (s *Server) handleCreateSession(frame *protocol.Frame) *protocol.Frame {
	params, err := session.ParseCreateSessionFrame(frame.Body)
	if err != nil {
		return protocol.ErrorFrame(frame.Header.Stream, protocol.ErrorProtocol,
			fmt.Sprintf("failed to parse CREATE_SESSION: %v", err))
	}

	sessionID, err := s.registry.CreateSession(params)
	if err != nil {
		return protocol.ErrorFrame(frame.Header.Stream, protocol.ErrorServer,
			fmt.Sprintf("failed to create session: %v", err))
	}

	return protocol.SessionCreatedFrame(frame.Header.Stream, sessionID)
}

func (s *Server) handleQuery(frame *protocol.Frame) *protocol.Frame {
	sessionID, query, consistency, err := session.ParseQueryFrame(frame.Body)
	if err != nil {
		return protocol.ErrorFrame(frame.Header.Stream, protocol.ErrorProtocol,
			fmt.Sprintf("failed to parse QUERY: %v", err))
	}

	sess := s.registry.Get(sessionID)
	if sess == nil {
		return protocol.ErrorFrame(frame.Header.Stream, protocol.ErrorProtocol,
			fmt.Sprintf("unknown session id %d", sessionID))
	}

	// Latency is measured inside the session method (driver execution only)
	result, latency, err := sess.ExecuteQuery(frame.Header.Stream, sessionID, query, consistency)

	if err != nil {
		return protocol.ErrorFrame(frame.Header.Stream, protocol.ErrorServer, err.Error())
	}

	return appendLatencyToFrame(result, latency)
}

func (s *Server) handlePrepare(frame *protocol.Frame) *protocol.Frame {
	sessionID, query, statementKey, err := session.ParsePrepareFrame(frame.Body)
	if err != nil {
		return protocol.ErrorFrame(frame.Header.Stream, protocol.ErrorProtocol,
			fmt.Sprintf("failed to parse PREPARE: %v", err))
	}

	sess := s.registry.Get(sessionID)
	if sess == nil {
		return protocol.ErrorFrame(frame.Header.Stream, protocol.ErrorProtocol,
			fmt.Sprintf("unknown session id %d", sessionID))
	}

	result, err := sess.Prepare(frame.Header.Stream, query, statementKey)
	if err != nil {
		return protocol.ErrorFrame(frame.Header.Stream, protocol.ErrorServer, err.Error())
	}

	return result
}

func (s *Server) handleExecute(frame *protocol.Frame) *protocol.Frame {
	sessionID, statementKey, consistency, rawValues, err := session.ParseExecuteFrame(frame.Body)
	if err != nil {
		return protocol.ErrorFrame(frame.Header.Stream, protocol.ErrorProtocol,
			fmt.Sprintf("failed to parse EXECUTE: %v", err))
	}

	sess := s.registry.Get(sessionID)
	if sess == nil {
		return protocol.ErrorFrame(frame.Header.Stream, protocol.ErrorProtocol,
			fmt.Sprintf("unknown session id %d", sessionID))
	}

	// Latency is measured inside the session method (driver execution only)
	result, latency, err := sess.Execute(frame.Header.Stream, statementKey, consistency, rawValues)

	if err != nil {
		return protocol.ErrorFrame(frame.Header.Stream, protocol.ErrorServer, err.Error())
	}

	return appendLatencyToFrame(result, latency)
}

func (s *Server) handleBatch(frame *protocol.Frame) *protocol.Frame {
	sessionID, batchType, statements, consistency, err := session.ParseBatchFrame(frame.Body)
	if err != nil {
		return protocol.ErrorFrame(frame.Header.Stream, protocol.ErrorProtocol,
			fmt.Sprintf("failed to parse BATCH: %v", err))
	}

	sess := s.registry.Get(sessionID)
	if sess == nil {
		return protocol.ErrorFrame(frame.Header.Stream, protocol.ErrorProtocol,
			fmt.Sprintf("unknown session id %d", sessionID))
	}

	// Latency is measured inside the session method (driver execution only)
	result, latency, err := sess.ExecuteBatch(frame.Header.Stream, batchType, statements, consistency)

	if err != nil {
		return protocol.ErrorFrame(frame.Header.Stream, protocol.ErrorServer, err.Error())
	}

	return appendLatencyToFrame(result, latency)
}

// appendLatencyToFrame appends the driver-side latency (8 bytes, u64 nanoseconds)
// to the end of the response frame body.
func appendLatencyToFrame(frame *protocol.Frame, latency time.Duration) *protocol.Frame {
	latencyNs := uint64(latency.Nanoseconds())
	latencyBytes := make([]byte, 8)
	binary.BigEndian.PutUint64(latencyBytes, latencyNs)

	newBody := make([]byte, len(frame.Body)+8)
	copy(newBody, frame.Body)
	copy(newBody[len(frame.Body):], latencyBytes)

	return &protocol.Frame{
		Header: protocol.FrameHeader{
			Version:    frame.Header.Version,
			Flags:      frame.Header.Flags,
			Stream:     frame.Header.Stream,
			Opcode:     frame.Header.Opcode,
			BodyLength: uint32(len(newBody)),
		},
		Body: newBody,
	}
}

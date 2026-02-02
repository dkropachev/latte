package server

import (
	"bufio"
	"net"
	"time"

	"github.com/rs/zerolog/log"
	"github.com/scylladb/latte/driver-adapters/gocql/protocol"
)

const (
	writeBufferSize = 64 * 1024
	flushThreshold  = 32 * 1024
	flushInterval   = 1 * time.Millisecond // Time-based flush for low latency
)

// writerLoop handles writing response frames to the connection.
// It batches responses when possible for better throughput while
// maintaining low latency through time-based flushing.
func writerLoop(conn net.Conn, responses <-chan *protocol.Frame) {
	writer := bufio.NewWriterSize(conn, writeBufferSize)
	ticker := time.NewTicker(flushInterval)
	defer ticker.Stop()

	for {
		select {
		case frame, ok := <-responses:
			if !ok {
				// Channel closed, flush and exit
				if writer.Buffered() > 0 {
					if err := writer.Flush(); err != nil {
						log.Warn().Err(err).Msg("failed to flush on close")
					}
				}
				return
			}

			if err := protocol.WriteFrame(writer, frame); err != nil {
				log.Warn().Err(err).Msg("failed to write response frame")
				return
			}

			// Flush if buffer is getting large
			if writer.Buffered() >= flushThreshold {
				if err := writer.Flush(); err != nil {
					log.Warn().Err(err).Msg("failed to flush")
					return
				}
			}

		case <-ticker.C:
			// Time-based flush for low latency when there's buffered data
			if writer.Buffered() > 0 {
				if err := writer.Flush(); err != nil {
					log.Warn().Err(err).Msg("failed to flush on tick")
					return
				}
			}
		}
	}
}

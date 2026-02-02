package config

import (
	"fmt"
	"os"
	"strconv"
)

// Config holds the driver adapter configuration.
type Config struct {
	SocketPath    string
	InflightLimit int
	PprofAddr     string
}

// FromEnv reads configuration from environment variables.
func FromEnv() (*Config, error) {
	socketPath := os.Getenv("LATTE_DRIVER_SOCKET")
	if socketPath == "" {
		socketPath = "/tmp/latte-driver.sock"
	}

	inflightLimit := 512
	if raw := os.Getenv("LATTE_DRIVER_INFLIGHT"); raw != "" {
		parsed, err := strconv.Atoi(raw)
		if err != nil {
			return nil, fmt.Errorf("failed to parse LATTE_DRIVER_INFLIGHT: %w", err)
		}
		if parsed <= 0 {
			return nil, fmt.Errorf("LATTE_DRIVER_INFLIGHT must be positive, got %d", parsed)
		}
		if parsed > 100000 {
			return nil, fmt.Errorf("LATTE_DRIVER_INFLIGHT too large, got %d (max 100000)", parsed)
		}
		inflightLimit = parsed
	}

	pprofAddr := os.Getenv("LATTE_DRIVER_PPROF")

	return &Config{
		SocketPath:    socketPath,
		InflightLimit: inflightLimit,
		PprofAddr:     pprofAddr,
	}, nil
}


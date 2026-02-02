package main

import (
	"context"
	"net/http"
	_ "net/http/pprof"
	"os"
	"os/signal"
	"syscall"

	"github.com/rs/zerolog"
	"github.com/rs/zerolog/log"
	"github.com/scylladb/latte/cql-adapters/gocql/config"
	"github.com/scylladb/latte/cql-adapters/gocql/server"
	"github.com/scylladb/latte/cql-adapters/gocql/session"
)

func main() {
	// Initialize zerolog with console output at Info level
	zerolog.TimeFieldFormat = zerolog.TimeFormatUnix
	zerolog.SetGlobalLevel(zerolog.InfoLevel)
	log.Logger = log.Output(zerolog.ConsoleWriter{Out: os.Stderr})

	cfg, err := config.FromEnv()
	if err != nil {
		log.Fatal().Err(err).Msg("failed to load config")
	}

	// Start pprof server for PGO profile collection (if enabled)
	if cfg.PprofAddr != "" {
		go func() {
			log.Info().Str("addr", cfg.PprofAddr).Msg("starting pprof server for PGO")
			if err := http.ListenAndServe(cfg.PprofAddr, nil); err != nil {
				log.Warn().Err(err).Msg("pprof server error")
			}
		}()
	}

	log.Info().
		Str("socket", cfg.SocketPath).
		Int("inflight_limit", cfg.InflightLimit).
		Msg("starting latte gocql driver adapter")

	registry := session.NewRegistry(cfg)
	srv := server.New(cfg, registry)

	ctx, cancel := signal.NotifyContext(context.Background(), os.Interrupt, syscall.SIGTERM)
	defer cancel()

	if err := srv.Run(ctx); err != nil {
		log.Fatal().Err(err).Msg("server error")
	}
}

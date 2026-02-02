package session

import (
	"sync"
	"sync/atomic"

	"github.com/scylladb/latte/cql-adapters/gocql/config"
)

// Registry manages multiple database sessions.
type Registry struct {
	mu       sync.RWMutex
	sessions map[uint64]*Session
	nextID   atomic.Uint64
	config   *config.Config
}

// NewRegistry creates a new session registry.
func NewRegistry(cfg *config.Config) *Registry {
	r := &Registry{
		sessions: make(map[uint64]*Session),
		config:   cfg,
	}
	r.nextID.Store(1)
	return r
}

// CreateSession creates a new database session with the given parameters.
func (r *Registry) CreateSession(params map[string]string) (uint64, error) {
	session, err := ConnectWithParams(r.config, params)
	if err != nil {
		return 0, err
	}

	id := r.nextID.Add(1) - 1

	r.mu.Lock()
	r.sessions[id] = session
	r.mu.Unlock()

	return id, nil
}

// Get retrieves a session by ID (lock-free read).
func (r *Registry) Get(sessionID uint64) *Session {
	r.mu.RLock()
	defer r.mu.RUnlock()
	return r.sessions[sessionID]
}

// Close closes all sessions.
func (r *Registry) Close() {
	r.mu.Lock()
	defer r.mu.Unlock()

	for _, s := range r.sessions {
		s.Close()
	}
	r.sessions = make(map[uint64]*Session)
}

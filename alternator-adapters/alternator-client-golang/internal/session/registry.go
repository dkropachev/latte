// Package session manages DynamoDB client sessions.
package session

import (
	"context"
	"net/http"
	"strconv"
	"sync"
	"sync/atomic"
	"time"

	"github.com/aws/aws-sdk-go-v2/aws"
	"github.com/aws/aws-sdk-go-v2/credentials"
	"github.com/aws/aws-sdk-go-v2/service/dynamodb"
	alternator "github.com/scylladb/alternator-client-golang/sdkv2"
)

// Session represents a DynamoDB client session.
type Session struct {
	ID       uint64
	Client   *dynamodb.Client
	Endpoint string
	Region   string
	Created  time.Time
}

// Registry manages multiple sessions.
type Registry struct {
	sessions sync.Map // map[uint64]*Session
	nextID   atomic.Uint64
}

// NewRegistry creates a new session registry.
func NewRegistry() *Registry {
	return &Registry{}
}

// Config holds session configuration parameters.
type Config struct {
	Endpoint         string
	Region           string
	AccessKeyID      string
	SecretAccessKey  string
	SessionToken     string
	MaxConnections   int
	RequestTimeoutMs int
	ConnectTimeoutMs int
	RetryMode        string
	MaxRetries       int
	// Alternator-specific options
	RackAwareness bool
	RoutingScope  string // "datacenter", "rack", "cluster"
	Compression   bool
	PoolSize      int
	// Load balancing
	UseLoadBalancing bool
	Rack             string
	Datacenter       string
}

// ParseConfig parses session parameters from a map.
func ParseConfig(params map[string]string) *Config {
	cfg := &Config{
		Endpoint:         "http://localhost:8000",
		Region:           "us-east-1",
		MaxConnections:   100,
		RequestTimeoutMs: 5000,
		ConnectTimeoutMs: 3000,
		RetryMode:        "none",
		MaxRetries:       0,
		PoolSize:         100,
	}

	if v, ok := params["endpoint"]; ok {
		cfg.Endpoint = v
	}
	if v, ok := params["region"]; ok {
		cfg.Region = v
	}
	if v, ok := params["access_key_id"]; ok {
		cfg.AccessKeyID = v
	}
	if v, ok := params["secret_access_key"]; ok {
		cfg.SecretAccessKey = v
	}
	if v, ok := params["session_token"]; ok {
		cfg.SessionToken = v
	}
	if v, ok := params["max_connections"]; ok {
		if n, err := strconv.Atoi(v); err == nil {
			cfg.MaxConnections = n
		}
	}
	if v, ok := params["request_timeout_ms"]; ok {
		if n, err := strconv.Atoi(v); err == nil {
			cfg.RequestTimeoutMs = n
		}
	}
	if v, ok := params["connect_timeout_ms"]; ok {
		if n, err := strconv.Atoi(v); err == nil {
			cfg.ConnectTimeoutMs = n
		}
	}
	if v, ok := params["retry_mode"]; ok {
		cfg.RetryMode = v
	}
	if v, ok := params["max_retries"]; ok {
		if n, err := strconv.Atoi(v); err == nil {
			cfg.MaxRetries = n
		}
	}
	// Alternator-specific
	if v, ok := params["rack_awareness"]; ok {
		cfg.RackAwareness = v == "true"
	}
	if v, ok := params["routing_scope"]; ok {
		cfg.RoutingScope = v
	}
	if v, ok := params["compression"]; ok {
		cfg.Compression = v == "true" || v == "gzip"
	}
	if v, ok := params["pool_size"]; ok {
		if n, err := strconv.Atoi(v); err == nil {
			cfg.PoolSize = n
		}
	}
	if v, ok := params["load_balancing"]; ok {
		cfg.UseLoadBalancing = v == "true"
	}
	if v, ok := params["rack"]; ok {
		cfg.Rack = v
	}
	if v, ok := params["datacenter"]; ok {
		cfg.Datacenter = v
	}

	return cfg
}

// Create creates a new session with the given configuration.
func (r *Registry) Create(_ context.Context, cfg *Config) (*Session, error) {
	var client *dynamodb.Client

	// Use alternator-client-golang for load balancing
	if cfg.UseLoadBalancing || cfg.RackAwareness || cfg.Datacenter != "" || cfg.Rack != "" {
		opts := []alternator.Option{
			alternator.WithAWSRegion(cfg.Region),
		}

		if cfg.AccessKeyID != "" && cfg.SecretAccessKey != "" {
			opts = append(opts, alternator.WithCredentials(
				cfg.AccessKeyID,
				cfg.SecretAccessKey,
			))
		}

		if cfg.Datacenter != "" {
			opts = append(opts, alternator.WithDatacenter(cfg.Datacenter))
		}

		if cfg.Rack != "" {
			opts = append(opts, alternator.WithRack(cfg.Rack))
		}

		// Parse initial nodes from endpoint
		nodes := []string{cfg.Endpoint}

		helper, err := alternator.NewHelper(nodes, opts...)
		if err != nil {
			// Fall back to standard client
			client = createStandardClient(cfg)
		} else {
			client, err = helper.NewDynamoDB()
			if err != nil {
				// Fall back to standard client
				client = createStandardClient(cfg)
			}
		}
	} else {
		client = createStandardClient(cfg)
	}

	// Generate session ID
	id := r.nextID.Add(1)

	session := &Session{
		ID:       id,
		Client:   client,
		Endpoint: cfg.Endpoint,
		Region:   cfg.Region,
		Created:  time.Now(),
	}

	r.sessions.Store(id, session)
	return session, nil
}

func createStandardClient(cfg *Config) *dynamodb.Client {
	// Create HTTP transport with connection pooling
	transport := &http.Transport{
		MaxIdleConns:        cfg.MaxConnections,
		MaxIdleConnsPerHost: cfg.MaxConnections,
		MaxConnsPerHost:     cfg.MaxConnections,
		IdleConnTimeout:     90 * time.Second,
	}

	httpClient := &http.Client{
		Transport: transport,
		Timeout:   time.Duration(cfg.RequestTimeoutMs) * time.Millisecond,
	}

	// Create DynamoDB client with BaseEndpoint for custom endpoints
	return dynamodb.New(dynamodb.Options{
		Region:       cfg.Region,
		HTTPClient:   httpClient,
		Credentials:  getCredentials(cfg),
		BaseEndpoint: aws.String(cfg.Endpoint),
	})
}

// Get retrieves a session by ID.
func (r *Registry) Get(id uint64) (*Session, bool) {
	v, ok := r.sessions.Load(id)
	if !ok {
		return nil, false
	}
	return v.(*Session), true
}

// Close closes and removes a session.
func (r *Registry) Close(id uint64) bool {
	_, ok := r.sessions.LoadAndDelete(id)
	return ok
}

// CloseAll closes all sessions.
func (r *Registry) CloseAll() {
	r.sessions.Range(func(key, _ any) bool {
		r.sessions.Delete(key)
		return true
	})
}

// Count returns the number of active sessions.
func (r *Registry) Count() int {
	count := 0
	r.sessions.Range(func(_, _ any) bool {
		count++
		return true
	})
	return count
}

func getCredentials(cfg *Config) aws.CredentialsProvider {
	if cfg.AccessKeyID != "" && cfg.SecretAccessKey != "" {
		return credentials.NewStaticCredentialsProvider(
			cfg.AccessKeyID,
			cfg.SecretAccessKey,
			cfg.SessionToken,
		)
	}
	// Return anonymous credentials for local testing
	return credentials.NewStaticCredentialsProvider("test", "test", "")
}

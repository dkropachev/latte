package session

import (
	"context"
	"testing"
)

func TestNewRegistry(t *testing.T) {
	r := NewRegistry()
	if r == nil {
		t.Fatal("NewRegistry returned nil")
	}
	if r.Count() != 0 {
		t.Errorf("Count = %d, want 0", r.Count())
	}
}

func TestParseConfig_Defaults(t *testing.T) {
	cfg := ParseConfig(nil)

	if cfg.Endpoint != "http://localhost:8000" {
		t.Errorf("Endpoint = %q, want %q", cfg.Endpoint, "http://localhost:8000")
	}
	if cfg.Region != "us-east-1" {
		t.Errorf("Region = %q, want %q", cfg.Region, "us-east-1")
	}
	if cfg.MaxConnections != 100 {
		t.Errorf("MaxConnections = %d, want 100", cfg.MaxConnections)
	}
	if cfg.RequestTimeoutMs != 5000 {
		t.Errorf("RequestTimeoutMs = %d, want 5000", cfg.RequestTimeoutMs)
	}
	if cfg.ConnectTimeoutMs != 3000 {
		t.Errorf("ConnectTimeoutMs = %d, want 3000", cfg.ConnectTimeoutMs)
	}
	if cfg.RetryMode != "none" {
		t.Errorf("RetryMode = %q, want %q", cfg.RetryMode, "none")
	}
	if cfg.MaxRetries != 0 {
		t.Errorf("MaxRetries = %d, want 0", cfg.MaxRetries)
	}
	if cfg.PoolSize != 100 {
		t.Errorf("PoolSize = %d, want 100", cfg.PoolSize)
	}
}

func TestParseConfig_AllParams(t *testing.T) {
	params := map[string]string{
		"endpoint":           "http://scylla:9000",
		"region":             "eu-west-1",
		"access_key_id":      "AKIATEST",
		"secret_access_key":  "secret123",
		"session_token":      "token456",
		"max_connections":    "200",
		"request_timeout_ms": "10000",
		"connect_timeout_ms": "5000",
		"retry_mode":         "standard",
		"max_retries":        "3",
		"rack_awareness":     "true",
		"routing_scope":      "datacenter",
		"compression":        "gzip",
		"pool_size":          "50",
		"load_balancing":     "true",
		"rack":               "rack1",
		"datacenter":         "dc1",
	}

	cfg := ParseConfig(params)

	if cfg.Endpoint != "http://scylla:9000" {
		t.Errorf("Endpoint = %q, want %q", cfg.Endpoint, "http://scylla:9000")
	}
	if cfg.Region != "eu-west-1" {
		t.Errorf("Region = %q, want %q", cfg.Region, "eu-west-1")
	}
	if cfg.AccessKeyID != "AKIATEST" {
		t.Errorf("AccessKeyID = %q, want %q", cfg.AccessKeyID, "AKIATEST")
	}
	if cfg.SecretAccessKey != "secret123" {
		t.Errorf("SecretAccessKey = %q, want %q", cfg.SecretAccessKey, "secret123")
	}
	if cfg.SessionToken != "token456" {
		t.Errorf("SessionToken = %q, want %q", cfg.SessionToken, "token456")
	}
	if cfg.MaxConnections != 200 {
		t.Errorf("MaxConnections = %d, want 200", cfg.MaxConnections)
	}
	if cfg.RequestTimeoutMs != 10000 {
		t.Errorf("RequestTimeoutMs = %d, want 10000", cfg.RequestTimeoutMs)
	}
	if cfg.ConnectTimeoutMs != 5000 {
		t.Errorf("ConnectTimeoutMs = %d, want 5000", cfg.ConnectTimeoutMs)
	}
	if cfg.RetryMode != "standard" {
		t.Errorf("RetryMode = %q, want %q", cfg.RetryMode, "standard")
	}
	if cfg.MaxRetries != 3 {
		t.Errorf("MaxRetries = %d, want 3", cfg.MaxRetries)
	}
	if !cfg.RackAwareness {
		t.Error("RackAwareness = false, want true")
	}
	if cfg.RoutingScope != "datacenter" {
		t.Errorf("RoutingScope = %q, want %q", cfg.RoutingScope, "datacenter")
	}
	if !cfg.Compression {
		t.Error("Compression = false, want true")
	}
	if cfg.PoolSize != 50 {
		t.Errorf("PoolSize = %d, want 50", cfg.PoolSize)
	}
	if !cfg.UseLoadBalancing {
		t.Error("UseLoadBalancing = false, want true")
	}
	if cfg.Rack != "rack1" {
		t.Errorf("Rack = %q, want %q", cfg.Rack, "rack1")
	}
	if cfg.Datacenter != "dc1" {
		t.Errorf("Datacenter = %q, want %q", cfg.Datacenter, "dc1")
	}
}

func TestParseConfig_InvalidNumbers(t *testing.T) {
	params := map[string]string{
		"max_connections":    "invalid",
		"request_timeout_ms": "not_a_number",
		"connect_timeout_ms": "abc",
		"max_retries":        "xyz",
		"pool_size":          "---",
	}

	cfg := ParseConfig(params)

	// Should keep defaults on invalid numbers
	if cfg.MaxConnections != 100 {
		t.Errorf("MaxConnections = %d, want 100 (default)", cfg.MaxConnections)
	}
	if cfg.RequestTimeoutMs != 5000 {
		t.Errorf("RequestTimeoutMs = %d, want 5000 (default)", cfg.RequestTimeoutMs)
	}
	if cfg.ConnectTimeoutMs != 3000 {
		t.Errorf("ConnectTimeoutMs = %d, want 3000 (default)", cfg.ConnectTimeoutMs)
	}
	if cfg.MaxRetries != 0 {
		t.Errorf("MaxRetries = %d, want 0 (default)", cfg.MaxRetries)
	}
	if cfg.PoolSize != 100 {
		t.Errorf("PoolSize = %d, want 100 (default)", cfg.PoolSize)
	}
}

func TestParseConfig_CompressionValues(t *testing.T) {
	tests := []struct {
		value    string
		expected bool
	}{
		{"true", true},
		{"gzip", true},
		{"false", false},
		{"none", false},
		{"", false},
	}

	for _, tt := range tests {
		t.Run(tt.value, func(t *testing.T) {
			params := map[string]string{"compression": tt.value}
			cfg := ParseConfig(params)
			if cfg.Compression != tt.expected {
				t.Errorf("Compression = %v, want %v", cfg.Compression, tt.expected)
			}
		})
	}
}

func TestRegistry_CreateAndGet(t *testing.T) {
	r := NewRegistry()
	ctx := context.Background()

	cfg := &Config{
		Endpoint: "http://localhost:8000",
		Region:   "us-east-1",
	}

	sess, err := r.Create(ctx, cfg)
	if err != nil {
		t.Fatalf("Create: %v", err)
	}

	if sess.ID == 0 {
		t.Error("Session ID should not be 0")
	}
	if sess.Endpoint != cfg.Endpoint {
		t.Errorf("Endpoint = %q, want %q", sess.Endpoint, cfg.Endpoint)
	}
	if sess.Region != cfg.Region {
		t.Errorf("Region = %q, want %q", sess.Region, cfg.Region)
	}
	if sess.Client == nil {
		t.Error("Client should not be nil")
	}
	if sess.Created.IsZero() {
		t.Error("Created time should be set")
	}

	// Get the session
	retrieved, ok := r.Get(sess.ID)
	if !ok {
		t.Error("Get returned false for existing session")
	}
	if retrieved.ID != sess.ID {
		t.Errorf("Retrieved ID = %d, want %d", retrieved.ID, sess.ID)
	}

	// Count should be 1
	if r.Count() != 1 {
		t.Errorf("Count = %d, want 1", r.Count())
	}
}

func TestRegistry_GetNonExistent(t *testing.T) {
	r := NewRegistry()

	_, ok := r.Get(12345)
	if ok {
		t.Error("Get should return false for non-existent session")
	}
}

func TestRegistry_Close(t *testing.T) {
	r := NewRegistry()
	ctx := context.Background()

	cfg := &Config{
		Endpoint: "http://localhost:8000",
		Region:   "us-east-1",
	}

	sess, _ := r.Create(ctx, cfg)
	id := sess.ID

	// Close the session
	ok := r.Close(id)
	if !ok {
		t.Error("Close returned false for existing session")
	}

	// Should not exist anymore
	_, ok = r.Get(id)
	if ok {
		t.Error("Session should not exist after Close")
	}

	// Count should be 0
	if r.Count() != 0 {
		t.Errorf("Count = %d, want 0", r.Count())
	}

	// Close again should return false
	ok = r.Close(id)
	if ok {
		t.Error("Close should return false for already-closed session")
	}
}

func TestRegistry_CloseAll(t *testing.T) {
	r := NewRegistry()
	ctx := context.Background()

	cfg := &Config{
		Endpoint: "http://localhost:8000",
		Region:   "us-east-1",
	}

	// Create multiple sessions
	s1, _ := r.Create(ctx, cfg)
	s2, _ := r.Create(ctx, cfg)
	s3, _ := r.Create(ctx, cfg)

	if r.Count() != 3 {
		t.Errorf("Count = %d, want 3", r.Count())
	}

	// Close all
	r.CloseAll()

	if r.Count() != 0 {
		t.Errorf("Count after CloseAll = %d, want 0", r.Count())
	}

	// Verify all sessions are gone
	_, ok := r.Get(s1.ID)
	if ok {
		t.Error("Session 1 should not exist after CloseAll")
	}
	_, ok = r.Get(s2.ID)
	if ok {
		t.Error("Session 2 should not exist after CloseAll")
	}
	_, ok = r.Get(s3.ID)
	if ok {
		t.Error("Session 3 should not exist after CloseAll")
	}
}

func TestRegistry_UniqueIDs(t *testing.T) {
	r := NewRegistry()
	ctx := context.Background()

	cfg := &Config{
		Endpoint: "http://localhost:8000",
		Region:   "us-east-1",
	}

	// Create multiple sessions and verify IDs are unique and sequential
	ids := make(map[uint64]bool)
	for i := 0; i < 100; i++ {
		sess, err := r.Create(ctx, cfg)
		if err != nil {
			t.Fatalf("Create: %v", err)
		}
		if ids[sess.ID] {
			t.Errorf("Duplicate session ID: %d", sess.ID)
		}
		ids[sess.ID] = true
	}

	if r.Count() != 100 {
		t.Errorf("Count = %d, want 100", r.Count())
	}
}

func TestRegistry_ConcurrentAccess(t *testing.T) {
	r := NewRegistry()
	ctx := context.Background()

	cfg := &Config{
		Endpoint: "http://localhost:8000",
		Region:   "us-east-1",
	}

	// Create sessions concurrently
	done := make(chan uint64, 100)
	for i := 0; i < 100; i++ {
		go func() {
			sess, err := r.Create(ctx, cfg)
			if err != nil {
				t.Errorf("Create: %v", err)
				done <- 0
				return
			}
			done <- sess.ID
		}()
	}

	// Collect all IDs
	ids := make(map[uint64]bool)
	for i := 0; i < 100; i++ {
		id := <-done
		if id != 0 {
			if ids[id] {
				t.Errorf("Duplicate session ID from concurrent create: %d", id)
			}
			ids[id] = true
		}
	}

	if r.Count() != 100 {
		t.Errorf("Count = %d, want 100", r.Count())
	}
}

func TestCreateStandardClient(t *testing.T) {
	cfg := &Config{
		Endpoint:         "http://localhost:8000",
		Region:           "us-east-1",
		MaxConnections:   50,
		RequestTimeoutMs: 10000,
	}

	client := createStandardClient(cfg)
	if client == nil {
		t.Fatal("createStandardClient returned nil")
	}
}

func TestGetCredentials_WithCredentials(t *testing.T) {
	cfg := &Config{
		AccessKeyID:     "AKIATEST",
		SecretAccessKey: "secret123",
		SessionToken:    "token456",
	}

	creds := getCredentials(cfg)
	if creds == nil {
		t.Fatal("getCredentials returned nil")
	}
}

func TestGetCredentials_Anonymous(t *testing.T) {
	cfg := &Config{
		AccessKeyID:     "",
		SecretAccessKey: "",
	}

	creds := getCredentials(cfg)
	if creds == nil {
		t.Fatal("getCredentials returned nil for anonymous")
	}
}


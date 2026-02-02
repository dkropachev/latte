// Package session provides the gocql-based session implementation for the Latte driver adapter.
//
// This package wraps the gocql driver to provide a CQL protocol-compatible interface
// for executing queries, prepared statements, and batches. It handles:
//   - Connection management with SSL/TLS, authentication, and load balancing
//   - Prepared statement caching and tuple expansion for gocql compatibility
//   - Type conversion between wire format and Go types
//   - Paging support for large result sets
//   - Error mapping for better client-side error handling
//
// Example usage:
//
//	cfg := &config.Config{ContactPoints: []string{"127.0.0.1"}}
//	session, err := session.Connect(cfg)
//	if err != nil {
//	    log.Fatal(err)
//	}
//	defer session.Close()
package session

import (
	"bytes"
	"crypto/tls"
	"crypto/x509"
	"encoding/binary"
	"fmt"
	"io"
	"math"
	"math/big"
	"net"
	"os"
	"reflect"
	"regexp"
	"strconv"
	"strings"
	"time"

	"github.com/gocql/gocql"
	"github.com/rs/zerolog/log"
	"github.com/scylladb/latte/cql-adapters/gocql/config"
	"github.com/scylladb/latte/cql-adapters/gocql/protocol"
	"github.com/scylladb/latte/cql-adapters/gocql/values"
	"gopkg.in/inf.v0"
)

// Session wraps a gocql session with a prepared statement cache.
type Session struct {
	inner           *gocql.Session
	prepared        *PreparedCache
	defaultPageSize int // 0 means no paging
}

// Connect creates a new session with the default config.
func Connect(cfg *config.Config) (*Session, error) {
	return ConnectWithParams(cfg, nil)
}

// ConnectWithParams creates a new session with custom parameters.
// All parameters are optional and unknown parameters are silently ignored for forward compatibility.
func ConnectWithParams(cfg *config.Config, params map[string]string) (*Session, error) {
	var contactPoints []string
	if cp, ok := params["contact_points"]; ok && cp != "" {
		contactPoints = parseContactPoints(cp)
	}

	if len(contactPoints) == 0 {
		return nil, fmt.Errorf("contact points are required to create a session")
	}

	cluster := gocql.NewCluster(contactPoints...)

	// === Datacenter/Rack-aware load balancing ===
	datacenter := params["datacenter"]
	rack := params["rack"]

	// Validate: rack requires datacenter (gocql doesn't support rack-only preference)
	if rack != "" && datacenter == "" {
		return nil, fmt.Errorf("rack parameter requires datacenter to be specified")
	}

	if datacenter != "" {
		if rack != "" {
			// DC + Rack aware: use DCAwareRoundRobinPolicy with rack preference
			// Note: gocql doesn't have built-in rack awareness, so we just use DC-aware
			// and log a warning that rack is ignored
			log.Warn().Str("rack", rack).Msg("gocql does not support rack-aware routing; using datacenter-aware only")
			cluster.PoolConfig.HostSelectionPolicy = gocql.TokenAwareHostPolicy(
				gocql.DCAwareRoundRobinPolicy(datacenter),
			)
		} else {
			// DC-aware only
			cluster.PoolConfig.HostSelectionPolicy = gocql.TokenAwareHostPolicy(
				gocql.DCAwareRoundRobinPolicy(datacenter),
			)
		}
	} else {
		// Default: token-aware with round-robin
		cluster.PoolConfig.HostSelectionPolicy = gocql.TokenAwareHostPolicy(
			gocql.RoundRobinHostPolicy(),
		)
	}

	// === Keyspace ===
	keyspace := params["keyspace"]
	if keyspace != "" {
		cluster.Keyspace = keyspace
	}

	// === Authentication ===
	username := params["username"]
	password := params["password"]
	if username != "" && password != "" {
		cluster.Authenticator = gocql.PasswordAuthenticator{
			Username: username,
			Password: password,
		}
	} else if username != "" {
		log.Warn().Msg("username provided without password, authentication disabled")
	} else if password != "" {
		log.Warn().Msg("password provided without username, authentication disabled")
	}

	// === Connection pool size ===
	if numConnsStr, ok := params["connections_per_shard"]; ok && numConnsStr != "" {
		numConns, err := strconv.Atoi(numConnsStr)
		if err != nil {
			log.Warn().Str("connections_per_shard", numConnsStr).Err(err).Msg("failed to parse connections_per_shard, ignoring")
		} else if numConns <= 0 {
			log.Warn().Str("connections_per_shard", numConnsStr).Msg("connections_per_shard must be positive, ignoring")
		} else {
			cluster.NumConns = numConns
		}
	}

	// === Request timeout ===
	if timeoutStr, ok := params["request_timeout_ms"]; ok && timeoutStr != "" {
		if timeoutMs, err := strconv.ParseInt(timeoutStr, 10, 64); err == nil && timeoutMs > 0 {
			cluster.Timeout = time.Duration(timeoutMs) * time.Millisecond
		}
	}

	// === Connect timeout ===
	if connectTimeoutStr, ok := params["connect_timeout_ms"]; ok && connectTimeoutStr != "" {
		if timeoutMs, err := strconv.ParseInt(connectTimeoutStr, 10, 64); err == nil && timeoutMs > 0 {
			cluster.ConnectTimeout = time.Duration(timeoutMs) * time.Millisecond
		}
	}

	// === Default consistency ===
	if consistencyStr, ok := params["consistency"]; ok && consistencyStr != "" {
		if consistency := parseConsistency(consistencyStr); consistency != gocql.Any {
			cluster.Consistency = consistency
		}
	}

	// === Serial consistency (for LWT) ===
	if serialConsistencyStr, ok := params["serial_consistency"]; ok && serialConsistencyStr != "" {
		if serialConsistency := parseSerialConsistency(serialConsistencyStr); serialConsistency != gocql.Serial {
			cluster.SerialConsistency = serialConsistency
		}
	}

	// === SSL/TLS Configuration ===
	if sslEnabled := params["ssl_enabled"]; sslEnabled == "true" || sslEnabled == "1" {
		sslOpts, err := buildSslOptions(params)
		if err != nil {
			return nil, fmt.Errorf("failed to configure SSL/TLS: %w", err)
		}
		cluster.SslOpts = sslOpts
		log.Info().Msg("SSL/TLS enabled for cluster connection")
	}

	// === Default page size ===
	var defaultPageSize int
	if pageSizeStr, ok := params["default_page_size"]; ok && pageSizeStr != "" {
		pageSize, err := strconv.Atoi(pageSizeStr)
		if err != nil {
			log.Warn().Str("default_page_size", pageSizeStr).Err(err).Msg("failed to parse default_page_size, ignoring")
		} else if pageSize <= 0 {
			log.Warn().Str("default_page_size", pageSizeStr).Msg("default_page_size must be positive, ignoring")
		} else {
			defaultPageSize = pageSize
			cluster.PageSize = pageSize
			log.Info().Int("page_size", pageSize).Msg("paging enabled")
		}
	}

	// === Speculative Execution ===
	// Note: gocql doesn't have built-in speculative execution support.
	// This would require custom implementation with query observers.
	if specExecPolicy := params["speculative_execution_policy"]; specExecPolicy != "" && specExecPolicy != "none" && specExecPolicy != "disabled" {
		log.Warn().Str("policy", specExecPolicy).Msg("speculative_execution_policy is not supported by gocql, ignoring")
	}

	// === Retry Policy ===
	if retryPolicy := params["retry_policy"]; retryPolicy != "" {
		policy := parseRetryPolicy(retryPolicy, params)
		if policy != nil {
			cluster.RetryPolicy = policy
			log.Info().Str("policy", retryPolicy).Msg("retry policy configured")
		}
	}

	// === Compression ===
	if compression := params["compression"]; compression != "" {
		switch strings.ToLower(compression) {
		case "snappy":
			cluster.Compressor = gocql.SnappyCompressor{}
			log.Info().Msg("Snappy compression enabled")
		case "lz4":
			// LZ4 is not supported in this gocql version
			log.Warn().Msg("LZ4 compression is not supported, falling back to snappy")
			cluster.Compressor = gocql.SnappyCompressor{}
		case "none", "disabled", "":
			// No compression
		default:
			log.Warn().Str("compression", compression).Msg("unknown compression type, ignoring")
		}
	}

	// === Host Filtering ===
	// Note: gocql's DataCenterHostFilter only accepts a single datacenter.
	// For multiple datacenters, use allowed_hosts with specific host IPs instead.
	if allowedDC := params["allowed_datacenter"]; allowedDC != "" {
		cluster.HostFilter = gocql.DataCentreHostFilter(allowedDC)
		log.Info().Str("datacenter", allowedDC).Msg("host filtering enabled for datacenter")
	}
	if allowedHosts := params["allowed_hosts"]; allowedHosts != "" {
		hostList := parseContactPoints(allowedHosts)
		cluster.HostFilter = gocql.WhiteListHostFilter(hostList...)
		log.Info().Strs("hosts", hostList).Msg("host filtering enabled for specific hosts")
	}

	session, err := cluster.CreateSession()
	if err != nil {
		return nil, fmt.Errorf("failed to create gocql session: %w", err)
	}

	log.Info().
		Strs("contact_points", contactPoints).
		Str("keyspace", keyspace).
		Str("datacenter", datacenter).
		Str("rack", rack).
		Msg("connected to cluster")

	return &Session{
		inner:           session,
		prepared:        NewPreparedCache(),
		defaultPageSize: defaultPageSize,
	}, nil
}

// parseConsistency parses a consistency level string into gocql.Consistency.
// Returns gocql.Any for unrecognized values (which will be treated as default).
// Note: SERIAL and LOCAL_SERIAL are not valid here - use parseSerialConsistency instead.
func parseConsistency(s string) gocql.Consistency {
	switch strings.ToUpper(s) {
	case "ANY":
		return gocql.Any
	case "ONE", "1":
		return gocql.One
	case "TWO", "2":
		return gocql.Two
	case "THREE", "3":
		return gocql.Three
	case "QUORUM":
		return gocql.Quorum
	case "ALL":
		return gocql.All
	case "LOCAL_ONE", "LOCALONE":
		return gocql.LocalOne
	case "LOCAL_QUORUM", "LOCALQUORUM":
		return gocql.LocalQuorum
	case "EACH_QUORUM", "EACHQUORUM":
		return gocql.EachQuorum
	default:
		return gocql.Any // Signal unknown
	}
}

// parseSerialConsistency parses a serial consistency string.
func parseSerialConsistency(s string) gocql.SerialConsistency {
	switch strings.ToUpper(s) {
	case "SERIAL":
		return gocql.Serial
	case "LOCAL_SERIAL", "LOCALSERIAL":
		return gocql.LocalSerial
	default:
		return gocql.Serial // Default
	}
}

// buildSslOptions creates gocql SSL options from session parameters.
// Supported parameters:
//   - ssl_ca_cert: Path to CA certificate file (PEM format)
//   - ssl_cert: Path to client certificate file (PEM format)
//   - ssl_key: Path to client private key file (PEM format)
//   - ssl_verify_peer: Whether to verify the server certificate ("true" or "false")
func buildSslOptions(params map[string]string) (*gocql.SslOptions, error) {
	sslOpts := &gocql.SslOptions{
		EnableHostVerification: false, // Default to false, controlled by ssl_verify_peer
	}

	// Configure peer verification
	if verifyPeer := params["ssl_verify_peer"]; verifyPeer == "true" || verifyPeer == "1" {
		sslOpts.EnableHostVerification = true
	}

	// Load CA certificate if provided
	caCertPath := params["ssl_ca_cert"]
	if caCertPath != "" {
		caCert, err := os.ReadFile(caCertPath)
		if err != nil {
			return nil, fmt.Errorf("failed to read CA certificate from %s: %w", caCertPath, err)
		}
		caCertPool := x509.NewCertPool()
		if !caCertPool.AppendCertsFromPEM(caCert) {
			return nil, fmt.Errorf("failed to parse CA certificate from %s", caCertPath)
		}
		sslOpts.CaPath = caCertPath
	}

	// Load client certificate and key if provided
	certPath := params["ssl_cert"]
	keyPath := params["ssl_key"]

	if certPath != "" && keyPath != "" {
		cert, err := tls.LoadX509KeyPair(certPath, keyPath)
		if err != nil {
			return nil, fmt.Errorf("failed to load client certificate/key: %w", err)
		}
		sslOpts.CertPath = certPath
		sslOpts.KeyPath = keyPath
		// Verify the certificate loaded correctly
		_ = cert
		log.Debug().Str("cert", certPath).Str("key", keyPath).Msg("loaded client certificate")
	} else if certPath != "" {
		return nil, fmt.Errorf("ssl_cert requires ssl_key to be specified")
	} else if keyPath != "" {
		return nil, fmt.Errorf("ssl_key requires ssl_cert to be specified")
	}

	return sslOpts, nil
}

// parseRetryPolicy creates a gocql retry policy from session parameters.
// Supported policies:
//   - "simple": SimpleRetryPolicy with configurable retry_count (default: 3)
//   - "downgrading": DowngradingConsistencyRetryPolicy
//   - "exponential": ExponentialBackoffRetryPolicy with configurable parameters
func parseRetryPolicy(policy string, params map[string]string) gocql.RetryPolicy {
	switch strings.ToLower(policy) {
	case "simple":
		numRetries := 3 // default
		if retryCountStr, ok := params["retry_count"]; ok {
			if val, err := strconv.Atoi(retryCountStr); err == nil && val >= 0 {
				numRetries = val
			}
		}
		return &gocql.SimpleRetryPolicy{NumRetries: numRetries}

	case "downgrading":
		return &gocql.DowngradingConsistencyRetryPolicy{}

	case "exponential":
		numRetries := 3
		minDelay := 100 * time.Millisecond
		maxDelay := 10 * time.Second

		if retryCountStr, ok := params["retry_count"]; ok {
			if val, err := strconv.Atoi(retryCountStr); err == nil && val >= 0 {
				numRetries = val
			}
		}
		if minDelayStr, ok := params["retry_min_delay_ms"]; ok {
			if val, err := strconv.Atoi(minDelayStr); err == nil && val > 0 {
				minDelay = time.Duration(val) * time.Millisecond
			}
		}
		if maxDelayStr, ok := params["retry_max_delay_ms"]; ok {
			if val, err := strconv.Atoi(maxDelayStr); err == nil && val > 0 {
				maxDelay = time.Duration(val) * time.Millisecond
			}
		}
		return &gocql.ExponentialBackoffRetryPolicy{
			NumRetries: numRetries,
			Min:        minDelay,
			Max:        maxDelay,
		}

	case "none", "disabled":
		return nil

	default:
		log.Warn().Str("policy", policy).Msg("unknown retry_policy, ignoring")
		return nil
	}
}

// Close closes the underlying gocql session.
func (s *Session) Close() {
	s.inner.Close()
}

// WaitSchemaAgreement waits for schema agreement across the cluster.
// This should be called after DDL operations (CREATE, ALTER, DROP) to ensure
// the schema change has propagated to all nodes.
// Returns an error if schema agreement cannot be reached within the timeout.
func (s *Session) WaitSchemaAgreement() error {
	return s.inner.AwaitSchemaAgreement(s.inner.Query("SELECT * FROM system.local").Context())
}

// mapGocqlError maps a gocql error to an appropriate protocol error code.
// This provides more specific error information to clients.
func mapGocqlError(err error) protocol.ErrorCode {
	if err == nil {
		return protocol.ErrorServer
	}

	errStr := strings.ToLower(err.Error())

	// Check for specific error patterns
	switch {
	case strings.Contains(errStr, "unavailable"):
		return protocol.ErrorUnavailable
	case strings.Contains(errStr, "timeout"):
		if strings.Contains(errStr, "write") {
			return protocol.ErrorWriteTimeout
		}
		if strings.Contains(errStr, "read") {
			return protocol.ErrorReadTimeout
		}
		return protocol.ErrorReadTimeout // Default timeout to read
	case strings.Contains(errStr, "overloaded"):
		return protocol.ErrorOverloaded
	case strings.Contains(errStr, "bootstrapping"):
		return protocol.ErrorIsBootstrapping
	case strings.Contains(errStr, "syntax"):
		return protocol.ErrorSyntax
	case strings.Contains(errStr, "unauthorized") || strings.Contains(errStr, "permission"):
		return protocol.ErrorUnauthorized
	case strings.Contains(errStr, "invalid"):
		return protocol.ErrorInvalid
	case strings.Contains(errStr, "already exists"):
		return protocol.ErrorAlreadyExists
	case strings.Contains(errStr, "not prepared") || strings.Contains(errStr, "unprepared"):
		return protocol.ErrorUnprepared
	case strings.Contains(errStr, "authentication") || strings.Contains(errStr, "credentials"):
		return protocol.ErrorBadCredentials
	default:
		return protocol.ErrorServer
	}
}

// ExecuteQuery executes an unprepared query.
// Returns the response frame, driver-side latency, and any error.
// Latency measures only the driver execution time, excluding frame building.
func (s *Session) ExecuteQuery(stream int16, sessionID uint64, query string, consistency protocol.Consistency) (*protocol.Frame, time.Duration, error) {
	q := s.inner.Query(query)
	q.Consistency(toGocqlConsistency(consistency))

	// Measure only the driver execution time
	start := time.Now()
	iter := q.Iter()
	latency := time.Since(start)

	frame, err := s.buildRowsFrame(stream, iter)
	return frame, latency, err
}

// Prepare prepares a statement and caches it.
func (s *Session) Prepare(stream int16, query, statementKey string) (*protocol.Frame, error) {
	// Convert named parameters to positional parameters for gocql compatibility
	// gocql doesn't support named parameters natively (need gocqlx for that)
	positionalQuery := convertNamedToPositional(query)

	// Try to get bind types and tuple element counts by querying the schema
	bindTypes, tupleElementCounts := s.getBindTypesFromSchema(query)

	// IMPORTANT: gocql handles tuples differently - each tuple element is a separate bind variable.
	// We need to expand the query to use (?, ?, ?) for each tuple column.
	// Also expand the bindTypes to match.
	positionalQuery, tupleExpansions := expandTuplesInQuery(positionalQuery, bindTypes)

	// Get column metadata by preparing and inspecting
	q := s.inner.Query(positionalQuery)

	// Cache the prepared statement with the POSITIONAL query
	// Note: We store the original bind count before tuple expansion
	// The actual tuple expansion happens at execute time
	originalBindCount := strings.Count(positionalQuery, "?")
	cached := &CachedPrepared{
		Query:              positionalQuery,
		BindTypes:          bindTypes,
		TupleExpansions:    tupleExpansions,
		OriginalBindCount:  originalBindCount,
		TupleElementCounts: tupleElementCounts,
	}
	s.prepared.Put(statementKey, cached)

	q.Release()

	return protocol.PreparedResultFrame(stream, statementKey), nil
}

// getBindTypesFromSchema extracts bind variable column types and tuple element counts by querying system_schema.
// This is needed because gocql doesn't expose bind metadata from prepared statements.
func (s *Session) getBindTypesFromSchema(query string) ([]ColumnType, []int) {
	keyspace, table, columns := parseQueryColumns(query)
	if keyspace == "" || table == "" || len(columns) == 0 {
		return nil, nil
	}

	// Query system_schema.columns for the column types
	schemaQuery := `SELECT column_name, type FROM system_schema.columns WHERE keyspace_name = ? AND table_name = ?`
	iter := s.inner.Query(schemaQuery, keyspace, table).Iter()

	columnTypeMap := make(map[string]string)
	var colName, colType string
	for iter.Scan(&colName, &colType) {
		columnTypeMap[colName] = colType
	}
	if err := iter.Close(); err != nil {
		log.Warn().Err(err).Msg("failed to query schema for bind types")
		return nil, nil
	}

	// Build bind types and tuple element counts in the order they appear in the query
	bindTypes := make([]ColumnType, len(columns))
	tupleElementCounts := make([]int, len(columns))
	for i, col := range columns {
		if cqlType, ok := columnTypeMap[col]; ok {
			bindTypes[i] = cqlTypeStringToColumnType(cqlType)
			tupleElementCounts[i] = countTupleElements(cqlType)
		} else {
			bindTypes[i] = ColTypeUnknown
			tupleElementCounts[i] = 0
		}
	}

	return bindTypes, tupleElementCounts
}

// ColumnType represents CQL column types for bind variable handling.
type ColumnType int

const (
	ColTypeUnknown ColumnType = iota
	ColTypeFloat
	ColTypeDouble
	ColTypeVarint
	ColTypeDecimal
	ColTypeDate
	ColTypeTime
	ColTypeDuration
	ColTypeTimestamp
	ColTypeInet
	ColTypeTimeuuid
	ColTypeUUID
	ColTypeCounter
	ColTypeInt
	ColTypeSmallInt
	ColTypeTinyInt
	ColTypeBigInt
	ColTypeText
	ColTypeAscii
	ColTypeBlob
	ColTypeBoolean
	ColTypeList
	ColTypeSet
	ColTypeMap
	ColTypeVector
	ColTypeTuple
	ColTypeUDT
)

// countTupleElements counts the number of elements in a tuple type string.
// E.g., "tuple<int, text, boolean>" returns 3.
// Returns 0 for non-tuple types.
func countTupleElements(cqlType string) int {
	cqlType = strings.ToLower(strings.TrimSpace(cqlType))

	// Handle frozen wrappers
	if strings.HasPrefix(cqlType, "frozen<") {
		inner := cqlType[7 : len(cqlType)-1]
		return countTupleElements(inner)
	}

	if !strings.HasPrefix(cqlType, "tuple<") {
		return 0
	}

	// Extract the content between tuple< and >
	inner := cqlType[6 : len(cqlType)-1] // strip "tuple<" and ">"
	if inner == "" {
		return 0
	}

	// Count top-level commas (not nested)
	count := 1 // Start at 1 because there's one more element than commas
	depth := 0
	for _, c := range inner {
		switch c {
		case '<':
			depth++
		case '>':
			depth--
		case ',':
			if depth == 0 {
				count++
			}
		}
	}
	return count
}

func cqlTypeStringToColumnType(cqlType string) ColumnType {
	// Normalize to lowercase
	cqlType = strings.ToLower(strings.TrimSpace(cqlType))

	// Check for complex types with angle brackets (e.g., list<int>, map<text, int>)
	if strings.HasPrefix(cqlType, "list<") {
		return ColTypeList
	}
	if strings.HasPrefix(cqlType, "set<") {
		return ColTypeSet
	}
	if strings.HasPrefix(cqlType, "map<") {
		return ColTypeMap
	}
	if strings.HasPrefix(cqlType, "vector<") {
		return ColTypeVector
	}
	if strings.HasPrefix(cqlType, "tuple<") {
		return ColTypeTuple
	}
	if strings.HasPrefix(cqlType, "frozen<") {
		// Frozen types - extract inner type
		inner := cqlType[7 : len(cqlType)-1] // strip "frozen<" and ">"
		return cqlTypeStringToColumnType(inner)
	}

	switch cqlType {
	case "float":
		return ColTypeFloat
	case "double":
		return ColTypeDouble
	case "varint":
		return ColTypeVarint
	case "decimal":
		return ColTypeDecimal
	case "date":
		return ColTypeDate
	case "time":
		return ColTypeTime
	case "duration":
		return ColTypeDuration
	case "timestamp":
		return ColTypeTimestamp
	case "inet":
		return ColTypeInet
	case "timeuuid":
		return ColTypeTimeuuid
	case "uuid":
		return ColTypeUUID
	case "counter":
		return ColTypeCounter
	case "int":
		return ColTypeInt
	case "smallint":
		return ColTypeSmallInt
	case "tinyint":
		return ColTypeTinyInt
	case "bigint":
		return ColTypeBigInt
	case "text", "varchar":
		return ColTypeText
	case "ascii":
		return ColTypeAscii
	case "blob":
		return ColTypeBlob
	case "boolean":
		return ColTypeBoolean
	default:
		// If it's not a recognized type, it must be a UDT (user-defined type).
		// UDTs can be either:
		// - Simple name like "address" (when in the same keyspace)
		// - Qualified name like "keyspace.address"
		// Since we've already checked all primitive types, treat unknown types as UDTs.
		return ColTypeUDT
	}
}

// Regex patterns for query parsing
// These patterns support:
// - Quoted identifiers: "my table", "column name"
// - Unquoted identifiers: my_table, column_name
// - Optional keyspace prefix: keyspace.table or just table
// - Case-insensitive keywords
var (
	// Identifier pattern: either quoted "..." or unquoted word
	identPattern = `(?:"[^"]+"|[a-zA-Z_][a-zA-Z0-9_]*)`
	// Table reference: optional keyspace.table or just table
	tableRefPattern = `(` + identPattern + `)(?:\.(` + identPattern + `))?`

	// Match INSERT INTO [keyspace.]table (cols) VALUES (?)
	insertRegex = regexp.MustCompile(`(?i)INSERT\s+INTO\s+` + tableRefPattern + `\s*\(\s*([^)]+)\s*\)\s*VALUES`)
	// Match UPDATE [keyspace.]table SET col=? WHERE ...
	updateRegex = regexp.MustCompile(`(?i)UPDATE\s+` + tableRefPattern + `\s+SET\s+(.+?)\s+WHERE\s+(.+)`)
	// Match SELECT ... FROM [keyspace.]table WHERE col=?
	selectRegex = regexp.MustCompile(`(?i)SELECT\s+.+?\s+FROM\s+` + tableRefPattern + `(?:\s+WHERE\s+(.+))?`)
	// Match DELETE FROM [keyspace.]table WHERE ...
	deleteRegex = regexp.MustCompile(`(?i)DELETE\s+(?:.+?\s+)?FROM\s+` + tableRefPattern + `(?:\s+WHERE\s+(.+))?`)
)

// parseQueryColumns extracts keyspace, table, and column names used as bind variables.
func parseQueryColumns(query string) (keyspace, table string, columns []string) {
	query = strings.TrimSpace(query)

	// Try INSERT: matches are [full, keyspace_or_table, table_or_empty, columns]
	if matches := insertRegex.FindStringSubmatch(query); len(matches) >= 4 {
		keyspace, table = extractKeyspaceTable(matches[1], matches[2])
		colsStr := matches[3]
		columns = parseColumnList(colsStr)
		return
	}

	// Try UPDATE: matches are [full, keyspace_or_table, table_or_empty, set_clause, where_clause]
	if matches := updateRegex.FindStringSubmatch(query); len(matches) >= 5 {
		keyspace, table = extractKeyspaceTable(matches[1], matches[2])
		setClause := matches[3]
		whereClause := matches[4]
		columns = parseSetAndWhereColumns(setClause, whereClause)
		return
	}

	// Try SELECT: matches are [full, keyspace_or_table, table_or_empty, where_clause]
	if matches := selectRegex.FindStringSubmatch(query); len(matches) >= 3 {
		keyspace, table = extractKeyspaceTable(matches[1], matches[2])
		if len(matches) >= 4 && matches[3] != "" {
			columns = parseWhereColumns(matches[3])
		}
		return
	}

	// Try DELETE: matches are [full, keyspace_or_table, table_or_empty, where_clause]
	if matches := deleteRegex.FindStringSubmatch(query); len(matches) >= 3 {
		keyspace, table = extractKeyspaceTable(matches[1], matches[2])
		if len(matches) >= 4 && matches[3] != "" {
			columns = parseWhereColumns(matches[3])
		}
		return
	}

	return "", "", nil
}

// extractKeyspaceTable extracts keyspace and table from regex matches.
// If match2 is empty, match1 is the table name (no keyspace specified).
// If match2 is non-empty, match1 is keyspace and match2 is table.
// Also strips quotes from quoted identifiers.
func extractKeyspaceTable(match1, match2 string) (keyspace, table string) {
	match1 = stripQuotes(match1)
	match2 = stripQuotes(match2)

	if match2 == "" {
		// No keyspace specified, just table
		return "", match1
	}
	return match1, match2
}

// stripQuotes removes surrounding double quotes from an identifier.
func stripQuotes(s string) string {
	if len(s) >= 2 && s[0] == '"' && s[len(s)-1] == '"' {
		return s[1 : len(s)-1]
	}
	return s
}

func parseColumnList(colsStr string) []string {
	var columns []string
	for _, col := range strings.Split(colsStr, ",") {
		col = strings.TrimSpace(col)
		if col != "" {
			columns = append(columns, col)
		}
	}
	return columns
}

func parseSetAndWhereColumns(setClause, whereClause string) []string {
	var columns []string

	// Parse SET col=?, col2=?
	for _, part := range strings.Split(setClause, ",") {
		if idx := strings.Index(part, "="); idx >= 0 {
			col := strings.TrimSpace(part[:idx])
			if col != "" {
				columns = append(columns, col)
			}
		}
	}

	// Parse WHERE col=? AND col2=?
	columns = append(columns, parseWhereColumns(whereClause)...)

	return columns
}

func parseWhereColumns(whereClause string) []string {
	var columns []string
	// Split by AND (case insensitive)
	parts := regexp.MustCompile(`(?i)\s+AND\s+`).Split(whereClause, -1)
	for _, part := range parts {
		// Find column name before = or IN
		part = strings.TrimSpace(part)
		if idx := strings.Index(part, "="); idx >= 0 {
			col := strings.TrimSpace(part[:idx])
			if col != "" {
				columns = append(columns, col)
			}
		} else if idx := strings.Index(strings.ToUpper(part), " IN "); idx >= 0 {
			col := strings.TrimSpace(part[:idx])
			if col != "" {
				columns = append(columns, col)
			}
		}
	}
	return columns
}

// slowQueryThreshold defines the threshold for logging slow queries.
const slowQueryThreshold = 100 * time.Millisecond

// Execute executes a prepared statement with values.
// Returns the response frame, driver-side latency, and any error.
// Latency measures only the driver execution time, excluding value decoding and frame building.
func (s *Session) Execute(stream int16, statementKey string, consistency protocol.Consistency, typedValues []TypedValue) (*protocol.Frame, time.Duration, error) {
	cached, ok := s.prepared.Get(statementKey)
	if !ok {
		log.Debug().Str("statement", statementKey).Msg("execute: statement not prepared")
		return protocol.ErrorFrame(stream, protocol.ErrorUnprepared,
			fmt.Sprintf("statement '%s' not prepared", statementKey)), 0, nil
	}

	log.Debug().
		Str("statement", statementKey).
		Int("value_count", len(typedValues)).
		Uint16("consistency", uint16(consistency)).
		Msg("execute: starting")

	// Convert typed values to Go values using the type codes and bind types
	bindValues := make([]interface{}, len(typedValues))
	for i, tv := range typedValues {
		var bindType ColumnType
		if i < len(cached.BindTypes) {
			bindType = cached.BindTypes[i]
		}
		bindValues[i] = decodeTypedValueWithBindType(tv, bindType)
	}

	// Expand tuples in the query at execute time - gocql requires each tuple element
	// to be a separate bind variable, e.g., (?, ?, ?) instead of ?
	expandedQuery, expandedValues := expandQueryForTuples(cached.Query, bindValues, cached.BindTypes, cached.TupleElementCounts)

	q := s.inner.Query(expandedQuery, expandedValues...)
	q.Consistency(toGocqlConsistency(consistency))

	// Measure only the driver execution time
	start := time.Now()
	iter := q.Iter()
	latency := time.Since(start)

	frame, err := s.buildRowsFrame(stream, iter)

	if latency > slowQueryThreshold {
		log.Warn().
			Str("statement", statementKey).
			Dur("elapsed", latency).
			Msg("slow query detected")
	} else {
		log.Debug().
			Str("statement", statementKey).
			Dur("elapsed", latency).
			Msg("execute: completed")
	}

	return frame, latency, err
}

// ExecuteBatch executes a batch of prepared statements.
// Returns the response frame, driver-side latency, and any error.
// Latency measures only the driver execution time, excluding batch building and frame building.
func (s *Session) ExecuteBatch(stream int16, batchType protocol.BatchType, statements []BatchStatement, consistency protocol.Consistency) (*protocol.Frame, time.Duration, error) {
	log.Debug().
		Int("statement_count", len(statements)).
		Uint8("batch_type", uint8(batchType)).
		Uint16("consistency", uint16(consistency)).
		Msg("batch: starting")

	batch := s.inner.NewBatch(toGocqlBatchType(batchType))
	batch.Cons = toGocqlConsistency(consistency)

	for _, stmt := range statements {
		cached, ok := s.prepared.Get(stmt.StatementKey)
		if !ok {
			log.Debug().Str("statement", stmt.StatementKey).Msg("batch: statement not prepared")
			return protocol.ErrorFrame(stream, protocol.ErrorUnprepared,
				fmt.Sprintf("statement '%s' not prepared", stmt.StatementKey)), 0, nil
		}

		// Convert typed values to Go values using the type codes and bind types
		bindValues := make([]interface{}, len(stmt.TypedValues))
		for i, tv := range stmt.TypedValues {
			var bindType ColumnType
			if i < len(cached.BindTypes) {
				bindType = cached.BindTypes[i]
			}
			bindValues[i] = decodeTypedValueWithBindType(tv, bindType)
		}

		// Expand tuples for gocql
		expandedQuery, expandedValues := expandQueryForTuples(cached.Query, bindValues, cached.BindTypes, cached.TupleElementCounts)
		batch.Query(expandedQuery, expandedValues...)
	}

	// Measure only the driver execution time
	start := time.Now()
	err := s.inner.ExecuteBatch(batch)
	latency := time.Since(start)

	if err != nil {
		log.Debug().Err(err).Msg("batch: failed")
		return protocol.ErrorFrame(stream, mapGocqlError(err), err.Error()), latency, nil
	}

	if latency > slowQueryThreshold {
		log.Warn().
			Int("statement_count", len(statements)).
			Dur("elapsed", latency).
			Msg("slow batch detected")
	} else {
		log.Debug().
			Int("statement_count", len(statements)).
			Dur("elapsed", latency).
			Msg("batch: completed")
	}

	return protocol.VoidResultFrame(stream), latency, nil
}

// BatchStatement represents a single statement in a batch.
type BatchStatement struct {
	StatementKey string
	TypedValues  []TypedValue
}

func (s *Session) buildRowsFrame(stream int16, iter *gocql.Iter) (*protocol.Frame, error) {
	// Get column info before iterating
	colInfos := iter.Columns()
	if len(colInfos) == 0 {
		// Non-SELECT query (INSERT/UPDATE/DELETE)
		if err := iter.Close(); err != nil {
			return protocol.ErrorFrame(stream, mapGocqlError(err), err.Error()), nil
		}
		return protocol.VoidResultFrame(stream), nil
	}

	// Build column metadata
	numCols := len(colInfos)
	columns := make([]protocol.ColumnMeta, numCols)
	colNames := make([]string, numCols)
	for i, col := range colInfos {
		typeCode := uint16(values.TypeCodeFromGocql(col.TypeInfo.Type()))
		// gocql represents vectors as TypeCustom; detect and map to TypeVector (0x0030)
		if col.TypeInfo.Type() == gocql.TypeCustom {
			if _, ok := col.TypeInfo.(gocql.VectorType); ok {
				typeCode = TypeVector
			}
		}
		columns[i] = protocol.ColumnMeta{
			Keyspace: col.Keyspace,
			Table:    col.Table,
			Name:     col.Name,
			TypeCode: typeCode,
		}
		colNames[i] = col.Name
	}

	// Pre-allocate rows slice with reasonable initial capacity
	rows := make([][]interface{}, 0, 64)

	// Always use Scan with typed destinations to correctly distinguish NULL from zero-values.
	// MapScan loses NULL information for primitive types (e.g., NULL tinyint becomes int8(0)).

	// gocql expands tuples in Scan: each tuple element gets its own scan destination.
	// Calculate total scan destinations needed and track tuple expansion info.
	totalDest := 0
	tupleElemCounts := make([]int, numCols) // 0 = not a tuple, >0 = number of tuple elements
	tupleElemTypeInfos := make([][]gocql.TypeInfo, numCols)
	for i, col := range colInfos {
		if tupleTypeInfo, ok := col.TypeInfo.(gocql.TupleTypeInfo); ok {
			n := len(tupleTypeInfo.Elems)
			tupleElemCounts[i] = n
			tupleElemTypeInfos[i] = make([]gocql.TypeInfo, n)
			for j, elem := range tupleTypeInfo.Elems {
				tupleElemTypeInfos[i][j] = elem
			}
			totalDest += n
		} else {
			totalDest++
		}
	}

	for {
		// Create expanded destination slice
		dest := make([]interface{}, totalDest)
		destIdx := 0
		for i, col := range colInfos {
			if tupleElemCounts[i] > 0 {
				for _, elemTypeInfo := range tupleElemTypeInfos[i] {
					dest[destIdx] = createScanDestFromTypeInfo(elemTypeInfo)
					destIdx++
				}
			} else {
				dest[destIdx] = createScanDestFromTypeInfo(col.TypeInfo)
				destIdx++
			}
		}

		if !iter.Scan(dest...) {
			break
		}

		// Extract values, reassembling tuples from expanded scan destinations
		rowValues := make([]interface{}, numCols)
		destIdx = 0
		for i := range colInfos {
			if tupleElemCounts[i] > 0 {
				tupleVals := make([]interface{}, tupleElemCounts[i])
				allNil := true
				for j := 0; j < tupleElemCounts[i]; j++ {
					tupleVals[j] = extractScanValue(dest[destIdx])
					if tupleVals[j] != nil {
						allNil = false
					}
					destIdx++
				}
				if allNil {
					rowValues[i] = nil // NULL tuple
				} else {
					rowValues[i] = tupleVals
				}
			} else {
				rowValues[i] = extractScanValue(dest[destIdx])
				destIdx++
			}
		}
		rows = append(rows, rowValues)
	}

	// Get page state before closing the iterator (for paging support)
	pageState := iter.PageState()

	if err := iter.Close(); err != nil {
		return protocol.ErrorFrame(stream, mapGocqlError(err), err.Error()), nil
	}

	return protocol.RowsResultFrameWithPaging(stream, columns, rows, pageState), nil
}

// consistencyLookup provides O(1) lookup from protocol.Consistency to gocql.Consistency.
// Index corresponds to protocol.Consistency value (0-10, with 8,9 unused).
var consistencyLookup = [11]gocql.Consistency{
	0:  gocql.Any,         // ConsistencyAny
	1:  gocql.One,         // ConsistencyOne
	2:  gocql.Two,         // ConsistencyTwo
	3:  gocql.Three,       // ConsistencyThree
	4:  gocql.Quorum,      // ConsistencyQuorum
	5:  gocql.All,         // ConsistencyAll
	6:  gocql.LocalQuorum, // ConsistencyLocalQuorum
	7:  gocql.EachQuorum,  // ConsistencyEachQuorum
	8:  gocql.One,         // unused, default to One
	9:  gocql.One,         // unused, default to One
	10: gocql.LocalOne,    // ConsistencyLocalOne
}

func toGocqlConsistency(c protocol.Consistency) gocql.Consistency {
	if int(c) < len(consistencyLookup) {
		return consistencyLookup[c]
	}
	return gocql.One
}

func toGocqlBatchType(bt protocol.BatchType) gocql.BatchType {
	switch bt {
	case protocol.BatchLogged:
		return gocql.LoggedBatch
	case protocol.BatchUnlogged:
		return gocql.UnloggedBatch
	case protocol.BatchCounter:
		return gocql.CounterBatch
	default:
		return gocql.LoggedBatch
	}
}

func parseContactPoints(raw string) []string {
	var points []string
	for _, p := range strings.Split(raw, ",") {
		p = strings.TrimSpace(p)
		if p != "" {
			points = append(points, p)
		}
	}
	return points
}

// Query flags constants
const (
	QueryFlagValues          byte = 0x01
	QueryFlagSkipMetadata    byte = 0x02
	QueryFlagPageSize        byte = 0x04
	QueryFlagWithPagingState byte = 0x08
	QueryFlagSerialConsist   byte = 0x10
	QueryFlagDefaultTimestamp byte = 0x20
	QueryFlagNamesForValues  byte = 0x40
)

// QueryOptions holds parsed query options from the QUERY frame.
type QueryOptions struct {
	SkipMetadata       bool
	PageSize           int32
	PagingState        []byte
	SerialConsistency  protocol.Consistency
	DefaultTimestamp   int64
	HasValues          bool
	Values             []TypedValue
}

// ParseQueryFrame parses a QUERY request frame body.
func ParseQueryFrame(body []byte) (sessionID uint64, query string, consistency protocol.Consistency, err error) {
	_, _, _, _, opts, err := ParseQueryFrameWithOptions(body)
	if err != nil {
		return 0, "", 0, err
	}
	// For backward compatibility, just ignore options
	_ = opts
	return parseQueryFrameBasic(body)
}

// parseQueryFrameBasic is the old implementation for backward compatibility.
func parseQueryFrameBasic(body []byte) (sessionID uint64, query string, consistency protocol.Consistency, err error) {
	r := bytes.NewReader(body)

	sessionID, err = protocol.ReadLong(r)
	if err != nil {
		return 0, "", 0, fmt.Errorf("failed to read session id: %w", err)
	}

	query, err = protocol.ReadLongString(r)
	if err != nil {
		return 0, "", 0, fmt.Errorf("failed to read query: %w", err)
	}

	rawConsistency, err := protocol.ReadShort(r)
	if err != nil {
		return 0, "", 0, fmt.Errorf("failed to read consistency: %w", err)
	}
	consistency = protocol.Consistency(rawConsistency)

	// Read flags (no longer require 0)
	_, err = protocol.ReadByte(r)
	if err != nil {
		return 0, "", 0, fmt.Errorf("failed to read flags: %w", err)
	}

	return sessionID, query, consistency, nil
}

// ParseQueryFrameWithOptions parses a QUERY request frame body with full options support.
func ParseQueryFrameWithOptions(body []byte) (sessionID uint64, query string, consistency protocol.Consistency, flags byte, opts QueryOptions, err error) {
	r := bytes.NewReader(body)

	sessionID, err = protocol.ReadLong(r)
	if err != nil {
		err = fmt.Errorf("failed to read session id: %w", err)
		return
	}

	query, err = protocol.ReadLongString(r)
	if err != nil {
		err = fmt.Errorf("failed to read query: %w", err)
		return
	}

	rawConsistency, err := protocol.ReadShort(r)
	if err != nil {
		err = fmt.Errorf("failed to read consistency: %w", err)
		return
	}
	consistency = protocol.Consistency(rawConsistency)

	// Read flags
	flags, err = protocol.ReadByte(r)
	if err != nil {
		err = fmt.Errorf("failed to read flags: %w", err)
		return
	}

	// Parse flag-dependent fields
	if flags&QueryFlagValues != 0 {
		opts.HasValues = true
		opts.Values, err = decodeRawValues(r)
		if err != nil {
			return
		}
	}

	if flags&QueryFlagSkipMetadata != 0 {
		opts.SkipMetadata = true
	}

	if flags&QueryFlagPageSize != 0 {
		pageSize, readErr := protocol.ReadInt(r)
		if readErr != nil {
			err = fmt.Errorf("failed to read page size: %w", readErr)
			return
		}
		opts.PageSize = pageSize
	}

	if flags&QueryFlagWithPagingState != 0 {
		opts.PagingState, err = protocol.ReadBytes(r)
		if err != nil {
			err = fmt.Errorf("failed to read paging state: %w", err)
			return
		}
	}

	if flags&QueryFlagSerialConsist != 0 {
		serialConsist, readErr := protocol.ReadShort(r)
		if readErr != nil {
			err = fmt.Errorf("failed to read serial consistency: %w", readErr)
			return
		}
		opts.SerialConsistency = protocol.Consistency(serialConsist)
	}

	if flags&QueryFlagDefaultTimestamp != 0 {
		ts, readErr := protocol.ReadLong(r)
		if readErr != nil {
			err = fmt.Errorf("failed to read default timestamp: %w", readErr)
			return
		}
		opts.DefaultTimestamp = int64(ts)
	}

	return
}

// ParsePrepareFrame parses a PREPARE request frame body.
func ParsePrepareFrame(body []byte) (sessionID uint64, query, statementKey string, err error) {
	r := bytes.NewReader(body)

	sessionID, err = protocol.ReadLong(r)
	if err != nil {
		return 0, "", "", fmt.Errorf("failed to read session id: %w", err)
	}

	query, err = protocol.ReadLongString(r)
	if err != nil {
		return 0, "", "", fmt.Errorf("failed to read query: %w", err)
	}

	statementKey, err = protocol.ReadString(r)
	if err != nil {
		return 0, "", "", fmt.Errorf("failed to read statement key: %w", err)
	}

	return sessionID, query, statementKey, nil
}

// Execute flags constants (same as Query flags)
const (
	ExecuteFlagValues          byte = 0x01
	ExecuteFlagSkipMetadata    byte = 0x02
	ExecuteFlagPageSize        byte = 0x04
	ExecuteFlagWithPagingState byte = 0x08
	ExecuteFlagSerialConsist   byte = 0x10
	ExecuteFlagDefaultTimestamp byte = 0x20
)

// ExecuteOptions holds parsed execute options from the EXECUTE frame.
type ExecuteOptions struct {
	SkipMetadata      bool
	PageSize          int32
	PagingState       []byte
	SerialConsistency protocol.Consistency
	DefaultTimestamp  int64
	HasSerialConsist  bool
	HasTimestamp      bool
}

// ParseExecuteFrame parses an EXECUTE request frame body.
func ParseExecuteFrame(body []byte) (sessionID uint64, statementKey string, consistency protocol.Consistency, typedValues []TypedValue, err error) {
	sessionID, statementKey, consistency, typedValues, _, err = ParseExecuteFrameWithOptions(body)
	return
}

// ParseExecuteFrameWithOptions parses an EXECUTE request frame body with full options support.
// This supports serial consistency for LWT queries.
func ParseExecuteFrameWithOptions(body []byte) (sessionID uint64, statementKey string, consistency protocol.Consistency, typedValues []TypedValue, opts ExecuteOptions, err error) {
	r := bytes.NewReader(body)

	sessionID, err = protocol.ReadLong(r)
	if err != nil {
		err = fmt.Errorf("failed to read session id: %w", err)
		return
	}

	statementKey, err = protocol.ReadString(r)
	if err != nil {
		err = fmt.Errorf("failed to read statement key: %w", err)
		return
	}

	rawConsistency, readErr := protocol.ReadShort(r)
	if readErr != nil {
		err = fmt.Errorf("failed to read consistency: %w", readErr)
		return
	}
	consistency = protocol.Consistency(rawConsistency)

	flags, readErr := protocol.ReadByte(r)
	if readErr != nil {
		err = fmt.Errorf("failed to read flags: %w", readErr)
		return
	}

	// Parse flag-dependent fields
	if flags&ExecuteFlagValues != 0 {
		typedValues, err = decodeRawValues(r)
		if err != nil {
			return
		}
	}

	if flags&ExecuteFlagSkipMetadata != 0 {
		opts.SkipMetadata = true
	}

	if flags&ExecuteFlagPageSize != 0 {
		pageSize, readErr := protocol.ReadInt(r)
		if readErr != nil {
			err = fmt.Errorf("failed to read page size: %w", readErr)
			return
		}
		opts.PageSize = pageSize
	}

	if flags&ExecuteFlagWithPagingState != 0 {
		opts.PagingState, err = protocol.ReadBytes(r)
		if err != nil {
			err = fmt.Errorf("failed to read paging state: %w", err)
			return
		}
	}

	if flags&ExecuteFlagSerialConsist != 0 {
		serialConsist, readErr := protocol.ReadShort(r)
		if readErr != nil {
			err = fmt.Errorf("failed to read serial consistency: %w", readErr)
			return
		}
		opts.SerialConsistency = protocol.Consistency(serialConsist)
		opts.HasSerialConsist = true
	}

	if flags&ExecuteFlagDefaultTimestamp != 0 {
		ts, readErr := protocol.ReadLong(r)
		if readErr != nil {
			err = fmt.Errorf("failed to read default timestamp: %w", readErr)
			return
		}
		opts.DefaultTimestamp = int64(ts)
		opts.HasTimestamp = true
	}

	return
}

// Batch flags constants
const (
	BatchFlagSerialConsist    byte = 0x10
	BatchFlagDefaultTimestamp byte = 0x20
)

// BatchOptions holds parsed batch options from the BATCH frame.
type BatchOptions struct {
	SerialConsistency protocol.Consistency
	DefaultTimestamp  int64
	HasSerialConsist  bool
	HasTimestamp      bool
}

// ParseBatchFrame parses a BATCH request frame body.
func ParseBatchFrame(body []byte) (sessionID uint64, batchType protocol.BatchType, statements []BatchStatement, consistency protocol.Consistency, err error) {
	sessionID, batchType, statements, consistency, _, err = ParseBatchFrameWithOptions(body)
	return
}

// ParseBatchFrameWithOptions parses a BATCH request frame body with full options support.
func ParseBatchFrameWithOptions(body []byte) (sessionID uint64, batchType protocol.BatchType, statements []BatchStatement, consistency protocol.Consistency, opts BatchOptions, err error) {
	r := bytes.NewReader(body)

	sessionID, err = protocol.ReadLong(r)
	if err != nil {
		err = fmt.Errorf("failed to read session id: %w", err)
		return
	}

	bt, readErr := protocol.ReadByte(r)
	if readErr != nil {
		err = fmt.Errorf("failed to read batch type: %w", readErr)
		return
	}
	batchType = protocol.BatchType(bt)

	stmtCount, readErr := protocol.ReadShort(r)
	if readErr != nil {
		err = fmt.Errorf("failed to read statement count: %w", readErr)
		return
	}

	statements = make([]BatchStatement, stmtCount)
	for i := uint16(0); i < stmtCount; i++ {
		kind, readErr := protocol.ReadByte(r)
		if readErr != nil {
			err = fmt.Errorf("failed to read statement kind: %w", readErr)
			return
		}
		if kind != 1 {
			err = fmt.Errorf("only prepared statements (kind=1) are supported in batch, got kind=%d", kind)
			return
		}

		statementKey, readErr := protocol.ReadString(r)
		if readErr != nil {
			err = fmt.Errorf("failed to read statement key: %w", readErr)
			return
		}

		typedValues, readErr := decodeRawValues(r)
		if readErr != nil {
			err = readErr
			return
		}

		statements[i] = BatchStatement{
			StatementKey: statementKey,
			TypedValues:  typedValues,
		}
	}

	rawConsistency, readErr := protocol.ReadShort(r)
	if readErr != nil {
		err = fmt.Errorf("failed to read consistency: %w", readErr)
		return
	}
	consistency = protocol.Consistency(rawConsistency)

	// Read and parse batch flags
	flags, readErr := protocol.ReadByte(r)
	if readErr != nil {
		// EOF is acceptable here - flags byte may be omitted
		if readErr != io.EOF && readErr != io.ErrUnexpectedEOF {
			err = fmt.Errorf("failed to read flags: %w", readErr)
			return
		}
		// No flags present, return with defaults
		return
	}

	// Parse flag-dependent fields
	if flags&BatchFlagSerialConsist != 0 {
		serialConsist, readErr := protocol.ReadShort(r)
		if readErr != nil {
			err = fmt.Errorf("failed to read serial consistency: %w", readErr)
			return
		}
		opts.SerialConsistency = protocol.Consistency(serialConsist)
		opts.HasSerialConsist = true
	}

	if flags&BatchFlagDefaultTimestamp != 0 {
		ts, readErr := protocol.ReadLong(r)
		if readErr != nil {
			err = fmt.Errorf("failed to read default timestamp: %w", readErr)
			return
		}
		opts.DefaultTimestamp = int64(ts)
		opts.HasTimestamp = true
	}

	return
}

// ParseCreateSessionFrame parses a CREATE_SESSION request frame body.
func ParseCreateSessionFrame(body []byte) (map[string]string, error) {
	r := bytes.NewReader(body)
	return protocol.ReadStringMap(r)
}

// TypedValue holds a value with its CQL type code
type TypedValue struct {
	TypeCode uint16
	Data     []byte // nil means NULL
}

// CQL type codes
const (
	TypeAscii     uint16 = 0x0001
	TypeBigInt    uint16 = 0x0002
	TypeBlob      uint16 = 0x0003
	TypeBoolean   uint16 = 0x0004
	TypeCounter   uint16 = 0x0005
	TypeDecimal   uint16 = 0x0006
	TypeDouble    uint16 = 0x0007
	TypeFloat     uint16 = 0x0008
	TypeInt       uint16 = 0x0009
	TypeTimestamp uint16 = 0x000B
	TypeUUID      uint16 = 0x000C
	TypeText      uint16 = 0x000D
	TypeVarint    uint16 = 0x000E
	TypeTimeuuid  uint16 = 0x000F
	TypeInet      uint16 = 0x0010
	TypeDate      uint16 = 0x0011
	TypeTime      uint16 = 0x0012
	TypeSmallInt  uint16 = 0x0013
	TypeTinyInt   uint16 = 0x0014
	TypeDuration  uint16 = 0x0015
	TypeList                 uint16 = 0x0020
	TypeMap                  uint16 = 0x0021
	TypeSet                  uint16 = 0x0022
	TypeVector               uint16 = 0x0030
	TypeTuple                uint16 = 0x0031
	TypePackedFloatVectorList uint16 = 0x0032
	TypeUDT                  uint16 = 0x0040
)

func decodeRawValues(r *bytes.Reader) ([]TypedValue, error) {
	count, err := protocol.ReadShort(r)
	if err != nil {
		return nil, fmt.Errorf("failed to read value count: %w", err)
	}

	values := make([]TypedValue, count)
	for i := uint16(0); i < count; i++ {
		// New format: [type_code: u16] [length: i32] [data: bytes]
		typeCode, err := protocol.ReadShort(r)
		if err != nil {
			return nil, fmt.Errorf("failed to read type code for value %d: %w", i, err)
		}

		val, err := protocol.ReadBytes(r)
		if err != nil {
			return nil, fmt.Errorf("failed to read value %d: %w", i, err)
		}
		values[i] = TypedValue{TypeCode: typeCode, Data: val}
	}

	return values, nil
}

// decodeTypedValueWithBindType converts bytes to a Go value using the CQL type code
// and optional bind type from schema lookup.
func decodeTypedValueWithBindType(tv TypedValue, bindType ColumnType) interface{} {
	// For NULL values, check if the bind type is a custom type (UDT)
	// gocql doesn't handle nil well for custom types, so we need to return
	// a typed value with nil data that will marshal to NULL
	// Note: With protocol v5, gocql handles nil []float32 for vectors correctly
	if tv.Data == nil {
		// Check bindType first (from schema lookup), then fall back to TypeCode
		switch bindType {
		case ColTypeVector:
			return []float32(nil) // Native gocql vector support handles nil
		case ColTypeUDT:
			return UDTValue{Data: nil} // Will marshal to NULL
		default:
			// Also check TypeCode for cases where schema lookup didn't identify the type
			switch tv.TypeCode {
			case TypeVector:
				return []float32(nil)
			case TypeUDT:
				return UDTValue{Data: nil}
			default:
				return nil
			}
		}
	}

	switch tv.TypeCode {
	case TypeVarint:
		// Wire format varint - decode from binary
		return values.DecodeVarint(tv.Data)

	case TypeDecimal:
		// Wire format decimal - decode from binary
		return values.DecodeDecimal(tv.Data)

	case TypeAscii, TypeText:
		// Text from wire - check if target column needs conversion
		s := string(tv.Data)
		switch bindType {
		case ColTypeDecimal:
			// gocql expects *inf.Dec for decimal (string fallback)
			return parseDecimalString(s)
		case ColTypeVarint:
			// gocql expects *big.Int for varint (string fallback)
			return parseVarintString(s)
		case ColTypeDate:
			// gocql expects time.Time for date
			return parseDateString(s)
		case ColTypeTime:
			// gocql expects int64 (nanoseconds) for time
			return parseTimeString(s)
		case ColTypeDuration:
			// gocql expects gocql.Duration for duration
			return parseDurationString(s)
		case ColTypeInet:
			// Parse IP address string
			return net.ParseIP(s)
		case ColTypeTimeuuid:
			// Parse UUID string
			uuid, _ := gocql.ParseUUID(s)
			return uuid
		case ColTypeUUID:
			uuid, _ := gocql.ParseUUID(s)
			return uuid
		default:
			return s
		}

	case TypeBigInt, TypeCounter, TypeTimestamp, TypeTime:
		val := readIntFlexible(tv.Data)
		switch bindType {
		case ColTypeVarint:
			return big.NewInt(val)
		case ColTypeDecimal:
			return inf.NewDec(val, 0)
		case ColTypeInt:
			return int32(val)
		case ColTypeSmallInt:
			return int16(val)
		case ColTypeTinyInt:
			return int8(val)
		default:
			return val
		}

	case TypeBlob:
		result := make([]byte, len(tv.Data))
		copy(result, tv.Data)
		return result

	case TypeBoolean:
		if len(tv.Data) > 0 {
			return tv.Data[0] != 0
		}
		return false

	case TypeDouble:
		// Latte sends Float as Double (f64). Use bind type to determine target.
		if len(tv.Data) == 8 {
			f64val := math.Float64frombits(binary.BigEndian.Uint64(tv.Data))
			if bindType == ColTypeFloat {
				return float32(f64val)
			}
			return f64val // Keep as float64 for Double columns
		}
		return float64(0.0)

	case TypeFloat:
		if len(tv.Data) == 4 {
			return math.Float32frombits(binary.BigEndian.Uint32(tv.Data))
		}
		return float32(0.0)

	case TypeInt:
		if len(tv.Data) == 4 {
			return int32(binary.BigEndian.Uint32(tv.Data))
		}
		val := readIntFlexible(tv.Data)
		return int32(val)

	case TypeSmallInt:
		if len(tv.Data) == 2 {
			return int16(binary.BigEndian.Uint16(tv.Data))
		}
		val := readIntFlexible(tv.Data)
		return int16(val)

	case TypeTinyInt:
		if len(tv.Data) == 1 {
			return int8(tv.Data[0])
		}
		val := readIntFlexible(tv.Data)
		return int8(val)

	case TypeUUID, TypeTimeuuid:
		if len(tv.Data) == 16 {
			var uuid gocql.UUID
			copy(uuid[:], tv.Data)
			return uuid
		}
		return nil

	case TypeInet:
		switch len(tv.Data) {
		case 4:
			return net.IP(tv.Data).To4()
		case 16:
			return net.IP(tv.Data).To16()
		}
		return nil

	case TypeDate:
		if len(tv.Data) == 4 {
			return binary.BigEndian.Uint32(tv.Data)
		}
		return uint32(0)

	case TypeList:
		return decodeListValue(tv.Data)

	case TypeSet:
		return decodeSetValue(tv.Data)

	case TypeMap:
		return decodeMapValue(tv.Data)

	case TypeVector:
		return decodeVectorAsFloat32Slice(tv.Data)

	case TypeTuple:
		return decodeTupleValue(tv.Data)

	case TypeUDT:
		return decodeUDTValue(tv.Data)

	case TypePackedFloatVectorList:
		// Packed format for list<vector<float, N>>
		// Format: [n_elements: i32] [dimension: u16] [packed_floats: n*dim*4 bytes]
		return decodePackedFloatVectorList(tv.Data)

	default:
		// Unknown type - return as blob
		result := make([]byte, len(tv.Data))
		copy(result, tv.Data)
		return result
	}
}

func readIntFlexible(data []byte) int64 {
	switch len(data) {
	case 1:
		return int64(int8(data[0]))
	case 2:
		return int64(int16(binary.BigEndian.Uint16(data)))
	case 4:
		return int64(int32(binary.BigEndian.Uint32(data)))
	case 8:
		return int64(binary.BigEndian.Uint64(data))
	default:
		return 0
	}
}

// parseDecimalString parses a decimal string like "123.45" into *inf.Dec
func parseDecimalString(s string) *inf.Dec {
	dec := new(inf.Dec)
	if _, ok := dec.SetString(s); !ok {
		return inf.NewDec(0, 0)
	}
	return dec
}

// parseVarintString parses a big integer string into *big.Int
func parseVarintString(s string) *big.Int {
	i := new(big.Int)
	if _, ok := i.SetString(s, 10); !ok {
		return big.NewInt(0)
	}
	return i
}

// parseDateString parses a date string like "2024-01-15" into time.Time
func parseDateString(s string) time.Time {
	t, err := time.Parse("2006-01-02", s)
	if err != nil {
		return time.Time{}
	}
	return t
}

// parseTimeString parses a time string like "14:30:45" into nanoseconds since midnight
func parseTimeString(s string) int64 {
	// Try common formats
	formats := []string{"15:04:05", "15:4:5", "15:04:05.000000000"}
	for _, format := range formats {
		if t, err := time.Parse(format, s); err == nil {
			return int64(t.Hour())*3600*1e9 + int64(t.Minute())*60*1e9 + int64(t.Second())*1e9 + int64(t.Nanosecond())
		}
	}
	return 0
}

// parseDurationString parses a duration string like "1mo2d3h4m5s" into gocql.Duration
func parseDurationString(s string) gocql.Duration {
	var months, days int32
	var nanos int64

	// Parse months
	if idx := strings.Index(s, "mo"); idx >= 0 {
		if v, err := strconv.Atoi(s[:idx]); err == nil {
			months = int32(v)
		}
		s = s[idx+2:]
	}

	// Parse days
	if idx := strings.Index(s, "d"); idx >= 0 {
		if v, err := strconv.Atoi(s[:idx]); err == nil {
			days = int32(v)
		}
		s = s[idx+1:]
	}

	// Parse hours
	if idx := strings.Index(s, "h"); idx >= 0 {
		if v, err := strconv.Atoi(s[:idx]); err == nil {
			nanos += int64(v) * 3600 * 1e9
		}
		s = s[idx+1:]
	}

	// Parse minutes
	if idx := strings.Index(s, "m"); idx >= 0 {
		if v, err := strconv.Atoi(s[:idx]); err == nil {
			nanos += int64(v) * 60 * 1e9
		}
		s = s[idx+1:]
	}

	// Parse seconds
	if idx := strings.Index(s, "s"); idx >= 0 {
		if v, err := strconv.Atoi(s[:idx]); err == nil {
			nanos += int64(v) * 1e9
		}
	}

	return gocql.Duration{
		Months:      months,
		Days:        days,
		Nanoseconds: nanos,
	}
}

// decodeListValue decodes a List value from wire format.
// Format: [subtype: u16] [optional vector metadata if subtype=Vector] [n_elements: i32] [elements...]
// Each element: [length: i32] [data: bytes]
func decodeListValue(data []byte) []interface{} {
	if len(data) < 6 {
		return nil
	}

	subtype := binary.BigEndian.Uint16(data[0:2])
	offset := 2

	// If subtype is Vector, skip vector metadata (elem_type: u16, dimension: u16)
	if subtype == TypeVector && len(data) >= 6 {
		offset += 4 // skip vector_elem_type and dimension
	}

	if len(data) < offset+4 {
		return nil
	}

	nElements := int32(binary.BigEndian.Uint32(data[offset : offset+4]))
	offset += 4

	if nElements <= 0 {
		return []interface{}{}
	}

	elements := make([]interface{}, 0, nElements)
	for i := int32(0); i < nElements; i++ {
		if len(data) < offset+4 {
			break
		}
		elemLen := int32(binary.BigEndian.Uint32(data[offset : offset+4]))
		offset += 4

		if elemLen < 0 {
			elements = append(elements, nil)
			continue
		}

		if len(data) < offset+int(elemLen) {
			break
		}
		elemData := data[offset : offset+int(elemLen)]
		offset += int(elemLen)

		elements = append(elements, decodeElementByType(elemData, subtype))
	}

	return elements
}

// decodeSetValue decodes a Set value from wire format.
// Format is same as List: [subtype: u16] [n_elements: i32] [elements...]
func decodeSetValue(data []byte) []interface{} {
	if len(data) < 6 {
		return nil
	}

	subtype := binary.BigEndian.Uint16(data[0:2])
	offset := 2

	nElements := int32(binary.BigEndian.Uint32(data[offset : offset+4]))
	offset += 4

	if nElements <= 0 {
		return []interface{}{}
	}

	elements := make([]interface{}, 0, nElements)
	for i := int32(0); i < nElements; i++ {
		if len(data) < offset+4 {
			break
		}
		elemLen := int32(binary.BigEndian.Uint32(data[offset : offset+4]))
		offset += 4

		if elemLen < 0 {
			elements = append(elements, nil)
			continue
		}

		if len(data) < offset+int(elemLen) {
			break
		}
		elemData := data[offset : offset+int(elemLen)]
		offset += int(elemLen)

		elements = append(elements, decodeElementByType(elemData, subtype))
	}

	return elements
}

// decodeMapValue decodes a Map value from wire format.
// Format: [key_type: u16] [value_type: u16] [n_entries: i32] [entries...]
// Each entry: [key_length: i32] [key_data] [value_length: i32] [value_data]
func decodeMapValue(data []byte) map[interface{}]interface{} {
	if len(data) < 8 {
		return nil
	}

	keyType := binary.BigEndian.Uint16(data[0:2])
	valueType := binary.BigEndian.Uint16(data[2:4])
	nEntries := int32(binary.BigEndian.Uint32(data[4:8]))
	offset := 8

	if nEntries <= 0 {
		return map[interface{}]interface{}{}
	}

	result := make(map[interface{}]interface{}, nEntries)
	for i := int32(0); i < nEntries; i++ {
		// Read key
		if len(data) < offset+4 {
			break
		}
		keyLen := int32(binary.BigEndian.Uint32(data[offset : offset+4]))
		offset += 4

		var key interface{}
		if keyLen >= 0 && len(data) >= offset+int(keyLen) {
			keyData := data[offset : offset+int(keyLen)]
			offset += int(keyLen)
			key = decodeElementByType(keyData, keyType)
		} else if keyLen >= 0 {
			break
		}

		// Read value
		if len(data) < offset+4 {
			break
		}
		valueLen := int32(binary.BigEndian.Uint32(data[offset : offset+4]))
		offset += 4

		var value interface{}
		if valueLen >= 0 && len(data) >= offset+int(valueLen) {
			valueData := data[offset : offset+int(valueLen)]
			offset += int(valueLen)
			value = decodeElementByType(valueData, valueType)
		} else if valueLen >= 0 {
			break
		}

		result[key] = value
	}

	return result
}

// Note: With protocol v5, gocql has native vector support.
// Vectors are represented as []float32 slices which gocql marshals natively.

// UDTValue wraps UDT data and implements gocql.Marshaler.
// This is needed to properly handle NULL UDT values in gocql.
type UDTValue struct {
	Data map[string]interface{}
}

// MarshalCQL implements gocql.Marshaler for UDTValue.
func (u UDTValue) MarshalCQL(info gocql.TypeInfo) ([]byte, error) {
	// If Data is nil, return nil to indicate NULL
	if u.Data == nil {
		return nil, nil
	}
	// For non-null UDT, delegate to gocql's default marshaling
	return gocql.Marshal(info, u.Data)
}

// GenericUDT implements gocql.UDTUnmarshaler for scanning UDTs into a map.
type GenericUDT struct {
	Fields map[string]interface{}
}

// UnmarshalUDT implements gocql.UDTUnmarshaler.
func (u *GenericUDT) UnmarshalUDT(name string, info gocql.TypeInfo, data []byte) error {
	if u.Fields == nil {
		u.Fields = make(map[string]interface{})
	}
	if data == nil {
		u.Fields[name] = nil
		return nil
	}
	dest := createScanDestFromTypeInfo(info)
	if err := gocql.Unmarshal(info, data, dest); err != nil {
		return err
	}
	u.Fields[name] = extractScanValue(dest)
	return nil
}

// decodeVectorAsFloat32Slice decodes a Vector value from wire format to []float32.
// Format: [subtype: u16] [dimension: u16] [data: bytes] (contiguous floats)
// Returns []float32 which gocql marshals natively.
func decodeVectorAsFloat32Slice(data []byte) []float32 {
	if len(data) < 4 {
		return nil
	}

	// Skip header (subtype: u16, dimension: u16) and decode float bytes
	floatData := data[4:]
	numFloats := len(floatData) / 4
	result := make([]float32, numFloats)
	for i := 0; i < numFloats; i++ {
		result[i] = math.Float32frombits(binary.BigEndian.Uint32(floatData[i*4 : i*4+4]))
	}
	return result
}

// decodeRawVectorAsFloat32Slice decodes a raw vector value (no header, just float bytes).
// This is used for vector elements inside collections where the encoding
// doesn't include the subtype/dimension header per element.
// Returns []float32 which gocql marshals natively with protocol v5.
func decodeRawVectorAsFloat32Slice(data []byte) []float32 {
	numFloats := len(data) / 4
	result := make([]float32, numFloats)
	for i := 0; i < numFloats; i++ {
		result[i] = math.Float32frombits(binary.BigEndian.Uint32(data[i*4 : i*4+4]))
	}
	return result
}

// decodePackedFloatVectorList decodes a packed list<vector<float, N>> value.
// Format: [n_elements: i32] [dimension: u16] [packed_floats: n*dim*4 bytes]
// Returns a slice of []float32 for each vector (native gocql vector support).
func decodePackedFloatVectorList(data []byte) [][]float32 {
	if len(data) < 6 {
		return nil
	}

	nElements := int32(binary.BigEndian.Uint32(data[0:4]))
	dimension := int(binary.BigEndian.Uint16(data[4:6]))
	offset := 6

	if nElements <= 0 {
		return [][]float32{}
	}

	// Calculate expected data size
	floatBytesPerVector := dimension * 4
	expectedSize := offset + int(nElements)*floatBytesPerVector
	if len(data) < expectedSize {
		return nil
	}

	result := make([][]float32, nElements)
	for i := int32(0); i < nElements; i++ {
		// Decode float bytes for this vector
		vector := make([]float32, dimension)
		for j := 0; j < dimension; j++ {
			vector[j] = math.Float32frombits(binary.BigEndian.Uint32(data[offset : offset+4]))
			offset += 4
		}
		result[i] = vector
	}

	return result
}

// decodeTupleValue decodes a Tuple value from wire format.
// Format: [n_elements: u16] [element_types: u16 * n] [elements...]
// Each element: [length: i32] [data: bytes] (or length=-1 for null)
func decodeTupleValue(data []byte) []interface{} {
	if len(data) < 2 {
		return nil
	}

	nElements := binary.BigEndian.Uint16(data[0:2])
	offset := 2

	if nElements == 0 {
		return []interface{}{}
	}

	// Read element types
	elemTypes := make([]uint16, nElements)
	for i := uint16(0); i < nElements; i++ {
		if len(data) < offset+2 {
			return nil
		}
		elemTypes[i] = binary.BigEndian.Uint16(data[offset : offset+2])
		offset += 2
	}

	// Read element values
	elements := make([]interface{}, nElements)
	for i := uint16(0); i < nElements; i++ {
		if len(data) < offset+4 {
			break
		}
		elemLen := int32(binary.BigEndian.Uint32(data[offset : offset+4]))
		offset += 4

		if elemLen < 0 {
			elements[i] = nil
			continue
		}

		if len(data) < offset+int(elemLen) {
			break
		}
		elemData := data[offset : offset+int(elemLen)]
		offset += int(elemLen)

		elements[i] = decodeElementByType(elemData, elemTypes[i])
	}

	return elements
}

// decodeUDTValue decodes a UDT value from wire format.
// Format: [n_fields: u16] [for each field: [name_len: u16] [name] [field_type: u16]] [field_values...]
// Each field value: [length: i32] [data: bytes] (or length=-1 for null)
// Returns UDTValue which implements gocql.Marshaler.
func decodeUDTValue(data []byte) UDTValue {
	if len(data) < 2 {
		return UDTValue{Data: nil}
	}

	nFields := binary.BigEndian.Uint16(data[0:2])
	offset := 2

	if nFields == 0 {
		return UDTValue{Data: map[string]interface{}{}}
	}

	// Read field names and types
	fieldNames := make([]string, nFields)
	fieldTypes := make([]uint16, nFields)

	for i := uint16(0); i < nFields; i++ {
		// Read field name
		if len(data) < offset+2 {
			return UDTValue{Data: nil}
		}
		nameLen := int(binary.BigEndian.Uint16(data[offset : offset+2]))
		offset += 2

		if len(data) < offset+nameLen {
			return UDTValue{Data: nil}
		}
		fieldNames[i] = string(data[offset : offset+nameLen])
		offset += nameLen

		// Read field type
		if len(data) < offset+2 {
			return UDTValue{Data: nil}
		}
		fieldTypes[i] = binary.BigEndian.Uint16(data[offset : offset+2])
		offset += 2
	}

	// Read field values
	result := make(map[string]interface{}, nFields)
	for i := uint16(0); i < nFields; i++ {
		if len(data) < offset+4 {
			break
		}
		fieldLen := int32(binary.BigEndian.Uint32(data[offset : offset+4]))
		offset += 4

		if fieldLen < 0 {
			result[fieldNames[i]] = nil
			continue
		}

		if len(data) < offset+int(fieldLen) {
			break
		}
		fieldData := data[offset : offset+int(fieldLen)]
		offset += int(fieldLen)

		result[fieldNames[i]] = decodeElementByType(fieldData, fieldTypes[i])
	}

	return UDTValue{Data: result}
}

// decodeElementByType decodes a raw element by its type code.
func decodeElementByType(data []byte, typeCode uint16) interface{} {
	switch typeCode {
	case TypeAscii, TypeText:
		return string(data)

	case TypeInt:
		if len(data) == 4 {
			return int32(binary.BigEndian.Uint32(data))
		}
		return int32(readIntFlexible(data))

	case TypeBigInt, TypeCounter, TypeTimestamp, TypeTime:
		return readIntFlexible(data)

	case TypeSmallInt:
		if len(data) == 2 {
			return int16(binary.BigEndian.Uint16(data))
		}
		return int16(readIntFlexible(data))

	case TypeTinyInt:
		if len(data) == 1 {
			return int8(data[0])
		}
		return int8(readIntFlexible(data))

	case TypeFloat:
		if len(data) == 4 {
			return math.Float32frombits(binary.BigEndian.Uint32(data))
		}
		return float32(0)

	case TypeDouble:
		if len(data) == 8 {
			return math.Float64frombits(binary.BigEndian.Uint64(data))
		}
		return float64(0)

	case TypeBoolean:
		if len(data) > 0 {
			return data[0] != 0
		}
		return false

	case TypeUUID, TypeTimeuuid:
		if len(data) == 16 {
			var uuid gocql.UUID
			copy(uuid[:], data)
			return uuid
		}
		return nil

	case TypeInet:
		switch len(data) {
		case 4:
			return net.IP(data).To4()
		case 16:
			return net.IP(data).To16()
		}
		return nil

	case TypeDate:
		if len(data) == 4 {
			return binary.BigEndian.Uint32(data)
		}
		return uint32(0)

	case TypeBlob:
		result := make([]byte, len(data))
		copy(result, data)
		return result

	case TypeList:
		return decodeListValue(data)

	case TypeSet:
		return decodeSetValue(data)

	case TypeMap:
		return decodeMapValue(data)

	case TypeVector:
		// For collection elements, vector data is raw floats without header
		return decodeRawVectorAsFloat32Slice(data)

	case TypeTuple:
		return decodeTupleValue(data)

	case TypeUDT:
		return decodeUDTValue(data)

	default:
		// Unknown type - return as blob
		result := make([]byte, len(data))
		copy(result, data)
		return result
	}
}

// convertNamedToPositional converts named parameters (:name) to positional parameters (?)
// This works around gocql's issues with complex named parameter patterns
func convertNamedToPositional(query string) string {
	var result strings.Builder
	result.Grow(len(query))
	inString := false
	i := 0
	for i < len(query) {
		if query[i] == '\'' && (i == 0 || query[i-1] != '\\') {
			inString = !inString
			result.WriteByte(query[i])
			i++
			continue
		}
		if !inString && query[i] == ':' && i+1 < len(query) {
			next := query[i+1]
			if (next >= 'a' && next <= 'z') || (next >= 'A' && next <= 'Z') || next == '_' {
				// Replace :name with ?
				result.WriteByte('?')
				// Skip the parameter name
				i++ // skip ':'
				for i < len(query) {
					c := query[i]
					if !((c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || (c >= '0' && c <= '9') || c == '_') {
						break
					}
					i++
				}
				continue
			}
		}
		result.WriteByte(query[i])
		i++
	}
	return result.String()
}

// expandTuplesInQuery expands tuple bind variables in a query for gocql compatibility.
// gocql requires each tuple element to be a separate bind variable.
// Input query: INSERT INTO t(a, tuple_col, b) VALUES(?, ?, ?)
// Output query: INSERT INTO t(a, tuple_col, b) VALUES(?, (?, ?, ?), ?)  (if tuple has 3 elements)
// Returns the expanded query and the list of tuple expansions.
func expandTuplesInQuery(query string, bindTypes []ColumnType) (string, []TupleExpansion) {
	var expansions []TupleExpansion

	// Count the number of ? in the query
	questionCount := strings.Count(query, "?")
	if questionCount == 0 || len(bindTypes) == 0 {
		return query, nil
	}

	// Find which positions are tuples
	for i, bt := range bindTypes {
		if bt == ColTypeTuple && i < questionCount {
			// We don't know the element count at prepare time from schema alone
			// We'll need to handle this at execute time when we see the actual values
			expansions = append(expansions, TupleExpansion{
				OriginalIndex: i,
				ElementCount:  0, // Will be determined at execute time
			})
		}
	}

	// If no tuples, return query unchanged
	if len(expansions) == 0 {
		return query, nil
	}

	// Note: We can't expand the query at prepare time because we don't know
	// the number of tuple elements. The actual expansion needs to happen at
	// execute time when we have the values.
	return query, expansions
}

// expandQueryForTuples expands the query at execute time based on actual tuple values.
// It also flattens the values slice to include individual tuple elements.
// tupleElementCounts provides the expected element count for each tuple column (from schema).
func expandQueryForTuples(query string, values []interface{}, bindTypes []ColumnType, tupleElementCounts []int) (string, []interface{}) {
	if len(values) == 0 {
		return query, values
	}

	// Check if any positions are tuples
	hasTuples := false
	for i := range values {
		if i < len(bindTypes) && bindTypes[i] == ColTypeTuple {
			hasTuples = true
			break
		}
	}

	if !hasTuples {
		return query, values
	}

	// Build expanded query and flattened values
	var expandedQuery strings.Builder
	expandedQuery.Grow(len(query) + 100)

	expandedValues := make([]interface{}, 0, len(values)+20)
	valueIdx := 0

	inString := false
	for i := 0; i < len(query); i++ {
		c := query[i]

		// Track string literals
		if c == '\'' && (i == 0 || query[i-1] != '\\') {
			inString = !inString
			expandedQuery.WriteByte(c)
			continue
		}

		if !inString && c == '?' && valueIdx < len(values) {
			// Check if this position is a tuple
			if valueIdx < len(bindTypes) && bindTypes[valueIdx] == ColTypeTuple {
				// Get the tuple value
				tupleElems, ok := values[valueIdx].([]interface{})

				// Determine element count from actual value or schema
				var elemCount int
				if ok && len(tupleElems) > 0 {
					elemCount = len(tupleElems)
				} else if valueIdx < len(tupleElementCounts) && tupleElementCounts[valueIdx] > 0 {
					// Use schema-derived element count for NULL tuples
					elemCount = tupleElementCounts[valueIdx]
				}

				if elemCount > 0 {
					// Expand to (?, ?, ...)
					expandedQuery.WriteByte('(')
					for j := 0; j < elemCount; j++ {
						if j > 0 {
							expandedQuery.WriteString(", ")
						}
						expandedQuery.WriteByte('?')

						// Get element value or nil for NULL tuples
						if ok && j < len(tupleElems) {
							expandedValues = append(expandedValues, tupleElems[j])
						} else {
							expandedValues = append(expandedValues, nil)
						}
					}
					expandedQuery.WriteByte(')')
					valueIdx++
					continue
				}
			}
			// Regular value (or unknown tuple - pass as is)
			expandedQuery.WriteByte('?')
			expandedValues = append(expandedValues, values[valueIdx])
			valueIdx++
			continue
		}

		expandedQuery.WriteByte(c)
	}

	return expandedQuery.String(), expandedValues
}

// Unused but keeping for potential float handling
var _ = math.Float32frombits

// createScanDestFromTypeInfo creates an appropriate scan destination pointer for a gocql TypeInfo.
// We need to create typed destinations because gocql's Scan requires concrete types
// to properly unmarshal values.
func createScanDestFromTypeInfo(ti gocql.TypeInfo) interface{} {
	switch ti.Type() {
	case gocql.TypeTinyInt:
		var v *int8
		return &v
	case gocql.TypeSmallInt:
		var v *int16
		return &v
	case gocql.TypeInt:
		var v *int32
		return &v
	case gocql.TypeBigInt, gocql.TypeCounter, gocql.TypeTimestamp, gocql.TypeTime:
		var v *int64
		return &v
	case gocql.TypeFloat:
		var v *float32
		return &v
	case gocql.TypeDouble:
		var v *float64
		return &v
	case gocql.TypeBoolean:
		var v *bool
		return &v
	case gocql.TypeVarchar, gocql.TypeText, gocql.TypeAscii:
		var v *string
		return &v
	case gocql.TypeUUID, gocql.TypeTimeUUID:
		var v *gocql.UUID
		return &v
	case gocql.TypeBlob:
		var v *[]byte
		return &v
	case gocql.TypeDate:
		var v *time.Time
		return &v
	case gocql.TypeDuration:
		var v *gocql.Duration
		return &v
	case gocql.TypeInet:
		var v *net.IP
		return &v
	case gocql.TypeVarint:
		var v *big.Int
		return &v
	case gocql.TypeDecimal:
		var v *inf.Dec
		return &v
	case gocql.TypeList, gocql.TypeSet, gocql.TypeMap:
		// Use gocql's NewWithError to create properly typed collection destinations.
		// gocql can't unmarshal typed elements (int, etc.) into []interface{}.
		val, err := ti.NewWithError()
		if err != nil {
			var v interface{}
			return &v
		}
		return val
	case gocql.TypeTuple:
		var v []interface{}
		return &v
	case gocql.TypeUDT:
		return &GenericUDT{}
	case gocql.TypeCustom:
		// gocql's VectorType unmarshals to []T where T matches the vector element type
		var v []float32
		return &v
	default:
		var v interface{}
		return &v
	}
}

// extractScanValue extracts the value from a scan destination pointer.
func extractScanValue(dest interface{}) interface{} {
	switch v := dest.(type) {
	case *interface{}:
		return *v
	case **int8:
		if *v == nil {
			return nil
		}
		return **v
	case **int16:
		if *v == nil {
			return nil
		}
		return **v
	case **int32:
		if *v == nil {
			return nil
		}
		return **v
	case **int64:
		if *v == nil {
			return nil
		}
		return **v
	case **float32:
		if *v == nil {
			return nil
		}
		return **v
	case **float64:
		if *v == nil {
			return nil
		}
		return **v
	case **bool:
		if *v == nil {
			return nil
		}
		return **v
	case **string:
		if *v == nil {
			return nil
		}
		return **v
	case **gocql.UUID:
		if *v == nil {
			return nil
		}
		return **v
	case **[]byte:
		if *v == nil {
			return nil
		}
		return **v
	case *[]interface{}:
		if *v == nil {
			return nil
		}
		return *v
	case *map[string]interface{}:
		if *v == nil {
			return nil
		}
		return *v
	case *[]float32:
		if *v == nil {
			return nil
		}
		return *v
	case **time.Time:
		if *v == nil {
			return nil
		}
		return **v
	case **gocql.Duration:
		if *v == nil {
			return nil
		}
		return **v
	case **net.IP:
		if *v == nil {
			return nil
		}
		return **v
	case **big.Int:
		if *v == nil {
			return nil
		}
		return *v
	case **inf.Dec:
		if *v == nil {
			return nil
		}
		return *v
	case *GenericUDT:
		if v.Fields == nil {
			return nil
		}
		return v.Fields
	default:
		// Handle dynamically-typed collection pointers from NewWithError()
		// (e.g., *[]int, *[]string, *map[string]int, *map[string]map[string]interface{})
		rv := reflect.ValueOf(dest)
		if rv.Kind() == reflect.Ptr {
			rv = rv.Elem()
			switch rv.Kind() {
			case reflect.Slice:
				if rv.IsNil() {
					return nil
				}
				return rv.Interface()
			case reflect.Map:
				if rv.IsNil() {
					return nil
				}
				return rv.Interface()
			}
		}
		return nil
	}
}

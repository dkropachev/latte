package session

import (
	"container/list"
	"sync"
)

// DefaultCacheMaxSize is the default maximum number of prepared statements to cache.
const DefaultCacheMaxSize = 10000

// TupleExpansion tracks how a single bind position expands for gocql tuple handling.
// gocql requires each tuple element to be a separate bind variable.
type TupleExpansion struct {
	OriginalIndex int   // Index in the original parameter list
	ElementCount  int   // Number of elements in the tuple
	ElementTypes  []uint16 // Type codes for each element (from IPC wire format)
}

// CachedPrepared holds a prepared statement and its bind type metadata.
type CachedPrepared struct {
	Query              string
	BindTypes          []ColumnType
	TupleExpansions    []TupleExpansion // Tracks which positions are tuples and their sizes
	OriginalBindCount  int              // Original number of bind parameters before tuple expansion
	TupleElementCounts []int            // Element counts for tuple columns (0 for non-tuples, parallel to BindTypes)
}

// lruEntry holds a cache entry with its key for LRU tracking.
type lruEntry struct {
	key   string
	value *CachedPrepared
}

// PreparedCache caches prepared statements by key with LRU eviction.
type PreparedCache struct {
	mu      sync.Mutex
	cache   map[string]*list.Element
	lruList *list.List
	maxSize int
}

// NewPreparedCache creates a new prepared statement cache with default max size.
func NewPreparedCache() *PreparedCache {
	return NewPreparedCacheWithSize(DefaultCacheMaxSize)
}

// NewPreparedCacheWithSize creates a new prepared statement cache with specified max size.
func NewPreparedCacheWithSize(maxSize int) *PreparedCache {
	if maxSize <= 0 {
		maxSize = DefaultCacheMaxSize
	}
	return &PreparedCache{
		cache:   make(map[string]*list.Element),
		lruList: list.New(),
		maxSize: maxSize,
	}
}

// Get retrieves a cached prepared statement by key and moves it to the front (most recently used).
func (c *PreparedCache) Get(key string) (*CachedPrepared, bool) {
	c.mu.Lock()
	defer c.mu.Unlock()

	if elem, ok := c.cache[key]; ok {
		// Move to front (most recently used)
		c.lruList.MoveToFront(elem)
		return elem.Value.(*lruEntry).value, true
	}
	return nil, false
}

// Put stores a prepared statement in the cache, evicting the least recently used if at capacity.
func (c *PreparedCache) Put(key string, prepared *CachedPrepared) {
	c.mu.Lock()
	defer c.mu.Unlock()

	// Check if key already exists
	if elem, ok := c.cache[key]; ok {
		// Update existing entry and move to front
		elem.Value.(*lruEntry).value = prepared
		c.lruList.MoveToFront(elem)
		return
	}

	// Evict least recently used if at capacity
	for c.lruList.Len() >= c.maxSize {
		oldest := c.lruList.Back()
		if oldest != nil {
			entry := oldest.Value.(*lruEntry)
			delete(c.cache, entry.key)
			c.lruList.Remove(oldest)
		}
	}

	// Add new entry at front
	entry := &lruEntry{key: key, value: prepared}
	elem := c.lruList.PushFront(entry)
	c.cache[key] = elem
}

// Len returns the current number of cached statements.
func (c *PreparedCache) Len() int {
	c.mu.Lock()
	defer c.mu.Unlock()
	return c.lruList.Len()
}

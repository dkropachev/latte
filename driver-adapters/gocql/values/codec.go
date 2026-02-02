package values

import (
	"encoding/binary"
	"fmt"
	"math"
	"math/big"
	"net"
	"sync"
	"time"

	"github.com/gocql/gocql"
	"gopkg.in/inf.v0"
)

// Buffer pools for common sizes to reduce allocations in hot paths
var (
	buf2Pool   = sync.Pool{New: func() interface{} { return make([]byte, 2) }}
	buf4Pool   = sync.Pool{New: func() interface{} { return make([]byte, 4) }}
	buf8Pool   = sync.Pool{New: func() interface{} { return make([]byte, 8) }}
	buf16Pool  = sync.Pool{New: func() interface{} { return make([]byte, 16) }}
	buf32Pool  = sync.Pool{New: func() interface{} { return make([]byte, 32) }}
	buf64Pool  = sync.Pool{New: func() interface{} { return make([]byte, 64) }}
	buf128Pool = sync.Pool{New: func() interface{} { return make([]byte, 128) }}
	buf256Pool = sync.Pool{New: func() interface{} { return make([]byte, 256) }}
)

// getBuffer2 gets a 2-byte buffer from the pool
func getBuffer2() []byte { return buf2Pool.Get().([]byte) }

// getBuffer4 gets a 4-byte buffer from the pool
func getBuffer4() []byte { return buf4Pool.Get().([]byte) }

// getBuffer8 gets an 8-byte buffer from the pool
func getBuffer8() []byte { return buf8Pool.Get().([]byte) }

// getBuffer16 gets a 16-byte buffer from the pool (for UUIDs)
func getBuffer16() []byte { return buf16Pool.Get().([]byte) }

// getBuffer32 gets a 32-byte buffer from the pool
func getBuffer32() []byte { return buf32Pool.Get().([]byte) }

// getBuffer64 gets a 64-byte buffer from the pool
func getBuffer64() []byte { return buf64Pool.Get().([]byte) }

// getBuffer128 gets a 128-byte buffer from the pool
func getBuffer128() []byte { return buf128Pool.Get().([]byte) }

// getBuffer256 gets a 256-byte buffer from the pool
func getBuffer256() []byte { return buf256Pool.Get().([]byte) }

// GetPooledBuffer returns a buffer of at least the requested size from a pool.
// Returns nil if no suitable pool is available (caller should allocate directly).
// The returned buffer may be larger than requested.
func GetPooledBuffer(size int) []byte {
	switch {
	case size <= 2:
		return getBuffer2()[:size]
	case size <= 4:
		return getBuffer4()[:size]
	case size <= 8:
		return getBuffer8()[:size]
	case size <= 16:
		return getBuffer16()[:size]
	case size <= 32:
		return getBuffer32()[:size]
	case size <= 64:
		return getBuffer64()[:size]
	case size <= 128:
		return getBuffer128()[:size]
	case size <= 256:
		return getBuffer256()[:size]
	default:
		return nil // Caller should allocate
	}
}

// PutBuffer returns a buffer to the appropriate pool based on its capacity.
// Call this after the encoded value has been written to the wire.
func PutBuffer(buf []byte) {
	switch cap(buf) {
	case 2:
		buf2Pool.Put(buf[:2])
	case 4:
		buf4Pool.Put(buf[:4])
	case 8:
		buf8Pool.Put(buf[:8])
	case 16:
		buf16Pool.Put(buf[:16])
	case 32:
		buf32Pool.Put(buf[:32])
	case 64:
		buf64Pool.Put(buf[:64])
	case 128:
		buf128Pool.Put(buf[:128])
	case 256:
		buf256Pool.Put(buf[:256])
	}
}

// TypeCode represents CQL type identifiers.
type TypeCode uint16

const (
	TypeAscii     TypeCode = 0x0001
	TypeBigInt    TypeCode = 0x0002
	TypeBlob      TypeCode = 0x0003
	TypeBoolean   TypeCode = 0x0004
	TypeCounter   TypeCode = 0x0005
	TypeDecimal   TypeCode = 0x0006
	TypeDouble    TypeCode = 0x0007
	TypeFloat     TypeCode = 0x0008
	TypeInt       TypeCode = 0x0009
	TypeVarint    TypeCode = 0x000E
	TypeTimestamp TypeCode = 0x000B
	TypeUUID      TypeCode = 0x000C
	TypeText      TypeCode = 0x000D
	TypeVarchar   TypeCode = 0x000D // Same as Text
	TypeTimeUUID  TypeCode = 0x000F
	TypeInet      TypeCode = 0x0010
	TypeDate      TypeCode = 0x0011
	TypeTime      TypeCode = 0x0012
	TypeSmallInt  TypeCode = 0x0013
	TypeTinyInt   TypeCode = 0x0014
)

// DecodeValue converts raw CQL wire bytes to a Go value based on type metadata.
func DecodeValue(data []byte, typeInfo gocql.TypeInfo) (interface{}, error) {
	if data == nil {
		return nil, nil
	}

	switch typeInfo.Type() {
	case gocql.TypeInt:
		// Accept any integer size and convert to int32
		val, err := readIntFlexible(data)
		if err != nil {
			return nil, err
		}
		return int32(val), nil

	case gocql.TypeBigInt, gocql.TypeCounter:
		// Accept any integer size and convert to int64
		val, err := readIntFlexible(data)
		if err != nil {
			return nil, err
		}
		return val, nil

	case gocql.TypeText, gocql.TypeVarchar, gocql.TypeAscii:
		return string(data), nil

	case gocql.TypeBoolean:
		if len(data) < 1 {
			return nil, fmt.Errorf("invalid boolean value length: %d", len(data))
		}
		return data[0] != 0, nil

	case gocql.TypeFloat:
		// Accept 4-byte float or 8-byte double (convert to float32)
		if len(data) == 4 {
			bits := binary.BigEndian.Uint32(data)
			return math.Float32frombits(bits), nil
		} else if len(data) == 8 {
			bits := binary.BigEndian.Uint64(data)
			return float32(math.Float64frombits(bits)), nil
		}
		return nil, fmt.Errorf("invalid float value length: %d", len(data))

	case gocql.TypeDouble:
		// Accept 4-byte float or 8-byte double
		if len(data) == 4 {
			bits := binary.BigEndian.Uint32(data)
			return float64(math.Float32frombits(bits)), nil
		} else if len(data) == 8 {
			bits := binary.BigEndian.Uint64(data)
			return math.Float64frombits(bits), nil
		}
		return nil, fmt.Errorf("invalid double value length: %d", len(data))

	case gocql.TypeUUID, gocql.TypeTimeUUID:
		if len(data) == 16 {
			var uuid gocql.UUID
			copy(uuid[:], data)
			return uuid, nil
		}
		// Try parsing as string UUID
		uuid, err := gocql.ParseUUID(string(data))
		if err != nil {
			return nil, fmt.Errorf("invalid uuid: %w", err)
		}
		return uuid, nil

	case gocql.TypeBlob:
		result := make([]byte, len(data))
		copy(result, data)
		return result, nil

	case gocql.TypeSmallInt:
		// Accept any integer size and convert to int16
		val, err := readIntFlexible(data)
		if err != nil {
			return nil, err
		}
		return int16(val), nil

	case gocql.TypeTinyInt:
		// Accept any integer size and convert to int8
		val, err := readIntFlexible(data)
		if err != nil {
			return nil, err
		}
		return int8(val), nil

	case gocql.TypeTimestamp:
		// Accept 8-byte or flexible integer (milliseconds since epoch)
		val, err := readIntFlexible(data)
		if err != nil {
			return nil, err
		}
		return time.UnixMilli(val), nil

	case gocql.TypeDate:
		if len(data) == 4 {
			// Date is days since epoch (1970-01-01), stored as unsigned 32-bit with center at 2^31
			days := binary.BigEndian.Uint32(data)
			return days, nil
		}
		// Try parsing as string "YYYY-MM-DD"
		return parseDateString(string(data))

	case gocql.TypeTime:
		if len(data) == 8 {
			nanos := int64(binary.BigEndian.Uint64(data))
			return nanos, nil
		}
		// Try parsing as string "HH:MM:SS" or "H:M:S"
		return parseTimeString(string(data))

	case gocql.TypeInet:
		switch len(data) {
		case 4:
			return net.IP(data).To4(), nil
		case 16:
			return net.IP(data).To16(), nil
		default:
			// Try parsing as string IP address
			ip := net.ParseIP(string(data))
			if ip == nil {
				return nil, fmt.Errorf("invalid inet value: %s", string(data))
			}
			return ip, nil
		}

	default:
		// Fallback to blob for unsupported types
		result := make([]byte, len(data))
		copy(result, data)
		return result, nil
	}
}

// EncodeValue converts a Go value to CQL wire bytes.
// Note: For pooled buffers (int16, int32, int64, float32, float64, time.Time),
// call PutBuffer() after the value has been written to the wire to return the buffer to the pool.
func EncodeValue(value interface{}, typeCode uint16) []byte {
	if value == nil {
		return nil
	}

	switch v := value.(type) {
	case int32:
		buf := getBuffer4()
		binary.BigEndian.PutUint32(buf, uint32(v))
		return buf

	case int64:
		buf := getBuffer8()
		binary.BigEndian.PutUint64(buf, uint64(v))
		return buf

	case string:
		// For inet type, parse the IP address string and encode as binary
		if typeCode == uint16(TypeInet) {
			if len(v) == 0 {
				return nil
			}
			ip := net.ParseIP(v)
			if ip == nil {
				return nil
			}
			if v4 := ip.To4(); v4 != nil {
				return v4
			}
			return ip.To16()
		}
		return []byte(v)

	case bool:
		if v {
			return []byte{1}
		}
		return []byte{0}

	case float32:
		buf := getBuffer4()
		binary.BigEndian.PutUint32(buf, math.Float32bits(v))
		return buf

	case float64:
		buf := getBuffer8()
		binary.BigEndian.PutUint64(buf, math.Float64bits(v))
		return buf

	case gocql.UUID:
		return v[:]

	case []byte:
		// For inet type, empty/nil bytes should be null
		if typeCode == uint16(TypeInet) && len(v) == 0 {
			return nil
		}
		return v

	case int16:
		buf := getBuffer2()
		binary.BigEndian.PutUint16(buf, uint16(v))
		return buf

	case int8:
		return []byte{byte(v)}

	case time.Time:
		// Check if this is a date or timestamp based on type code
		if typeCode == uint16(TypeDate) {
			// Date is stored as 4-byte unsigned integer (days since epoch with 2^31 offset)
			buf := getBuffer4()
			epoch := time.Date(1970, 1, 1, 0, 0, 0, 0, time.UTC)
			days := int64(v.Sub(epoch).Hours() / 24)
			binary.BigEndian.PutUint32(buf, uint32(days+(1<<31)))
			return buf
		}
		// Timestamp is stored as 8-byte milliseconds since epoch
		buf := getBuffer8()
		binary.BigEndian.PutUint64(buf, uint64(v.UnixMilli()))
		return buf

	case net.IP:
		// Handle nil/empty net.IP as null
		if len(v) == 0 {
			return nil
		}
		if v4 := v.To4(); v4 != nil {
			return v4
		}
		return v.To16()

	case *big.Int:
		return EncodeVarint(v)

	case *inf.Dec:
		return EncodeDecimal(v)

	default:
		// Try to handle *int, *int64, etc. pointers
		return handlePointerValue(value, typeCode)
	}
}

func handlePointerValue(value interface{}, typeCode uint16) []byte {
	switch v := value.(type) {
	case *int32:
		if v == nil {
			return nil
		}
		return EncodeValue(*v, typeCode)
	case *int64:
		if v == nil {
			return nil
		}
		return EncodeValue(*v, typeCode)
	case *string:
		if v == nil {
			return nil
		}
		return EncodeValue(*v, typeCode)
	case *bool:
		if v == nil {
			return nil
		}
		return EncodeValue(*v, typeCode)
	case *float32:
		if v == nil {
			return nil
		}
		return EncodeValue(*v, typeCode)
	case *float64:
		if v == nil {
			return nil
		}
		return EncodeValue(*v, typeCode)
	default:
		return nil
	}
}

// TypeCodeFromGocql converts a gocql.Type to our TypeCode.
func TypeCodeFromGocql(t gocql.Type) TypeCode {
	switch t {
	case gocql.TypeAscii:
		return TypeAscii
	case gocql.TypeBigInt:
		return TypeBigInt
	case gocql.TypeBlob:
		return TypeBlob
	case gocql.TypeBoolean:
		return TypeBoolean
	case gocql.TypeCounter:
		return TypeCounter
	case gocql.TypeDouble:
		return TypeDouble
	case gocql.TypeFloat:
		return TypeFloat
	case gocql.TypeInt:
		return TypeInt
	case gocql.TypeTimestamp:
		return TypeTimestamp
	case gocql.TypeUUID:
		return TypeUUID
	case gocql.TypeText, gocql.TypeVarchar:
		return TypeText
	case gocql.TypeTimeUUID:
		return TypeTimeUUID
	case gocql.TypeInet:
		return TypeInet
	case gocql.TypeDate:
		return TypeDate
	case gocql.TypeTime:
		return TypeTime
	case gocql.TypeSmallInt:
		return TypeSmallInt
	case gocql.TypeTinyInt:
		return TypeTinyInt
	default:
		return TypeBlob // Fallback
	}
}

// DecodeBindValues decodes a slice of raw values using prepared statement metadata.
func DecodeBindValues(rawValues [][]byte, types []gocql.TypeInfo) ([]interface{}, error) {
	if len(rawValues) != len(types) {
		return nil, fmt.Errorf("value count mismatch: %d values but %d bind markers", len(rawValues), len(types))
	}

	values := make([]interface{}, len(rawValues))
	for i, raw := range rawValues {
		if raw == nil {
			values[i] = nil
			continue
		}
		val, err := DecodeValue(raw, types[i])
		if err != nil {
			return nil, fmt.Errorf("failed to decode value %d: %w", i, err)
		}
		values[i] = val
	}
	return values, nil
}

// readIntFlexible reads an integer from bytes of any standard size (1, 2, 4, or 8 bytes)
func readIntFlexible(data []byte) (int64, error) {
	switch len(data) {
	case 1:
		return int64(int8(data[0])), nil
	case 2:
		return int64(int16(binary.BigEndian.Uint16(data))), nil
	case 4:
		return int64(int32(binary.BigEndian.Uint32(data))), nil
	case 8:
		return int64(binary.BigEndian.Uint64(data)), nil
	default:
		return 0, fmt.Errorf("invalid integer value length: %d", len(data))
	}
}

// parseDateString parses a date string like "2024-01-15" to CQL date format
func parseDateString(s string) (uint32, error) {
	t, err := time.Parse("2006-01-02", s)
	if err != nil {
		return 0, fmt.Errorf("invalid date string '%s': %w", s, err)
	}
	// CQL date is days since epoch with 2^31 offset
	epoch := time.Date(1970, 1, 1, 0, 0, 0, 0, time.UTC)
	days := int64(t.Sub(epoch).Hours() / 24)
	return uint32(days + (1 << 31)), nil
}

// parseTimeString parses a time string like "12:30:45" or "1:2:3" to nanoseconds since midnight
func parseTimeString(s string) (int64, error) {
	var hours, minutes, seconds int
	parts := 0
	for i, part := range splitString(s, ':') {
		val := 0
		for _, c := range part {
			if c < '0' || c > '9' {
				return 0, fmt.Errorf("invalid time string '%s'", s)
			}
			val = val*10 + int(c-'0')
		}
		switch i {
		case 0:
			hours = val
		case 1:
			minutes = val
		case 2:
			seconds = val
		}
		parts++
	}
	if parts < 2 {
		return 0, fmt.Errorf("invalid time string '%s': expected H:M or H:M:S", s)
	}
	if hours >= 24 || minutes >= 60 || seconds >= 60 {
		return 0, fmt.Errorf("time out of range in '%s'", s)
	}
	totalSeconds := int64(hours)*3600 + int64(minutes)*60 + int64(seconds)
	return totalSeconds * 1_000_000_000, nil
}

// splitString splits a string by a separator (simple implementation to avoid strings package)
func splitString(s string, sep byte) []string {
	var result []string
	start := 0
	for i := 0; i < len(s); i++ {
		if s[i] == sep {
			result = append(result, s[start:i])
			start = i + 1
		}
	}
	result = append(result, s[start:])
	return result
}

// EncodeVarint encodes a *big.Int to CQL varint wire format.
// The encoding is a signed, big-endian two's complement representation.
func EncodeVarint(v *big.Int) []byte {
	if v == nil {
		return nil
	}

	// For zero, return single zero byte
	if v.Sign() == 0 {
		return []byte{0}
	}

	// Get two's complement bytes
	absBytes := v.Bytes() // This gives the absolute value in big-endian

	if v.Sign() > 0 {
		// For positive numbers, ensure the high bit is 0
		// If the high bit is set, prepend a 0x00 byte
		if len(absBytes) > 0 && absBytes[0]&0x80 != 0 {
			result := make([]byte, len(absBytes)+1)
			copy(result[1:], absBytes)
			return result
		}
		return absBytes
	}

	// For negative numbers, compute two's complement
	// Two's complement of -n is ~(n-1) or equivalently 2^k - n for k bits
	// We need to find the minimum number of bytes that can represent the value

	// For -1, we need 0xFF (1 byte)
	// For -128, we need 0x80 (1 byte)
	// For -129, we need 0xFF7F (2 bytes)

	// Calculate the minimum bytes needed
	// For negative n, the two's complement representation uses the formula:
	// bits needed = floor(log2(|n|)) + 2 (for sign bit)
	// But we work in bytes, so we find the smallest k such that -2^(8k-1) <= n

	absVal := new(big.Int).Abs(v)

	// Find the number of bytes needed
	// -1 needs 1 byte (can represent -128 to 127, so -1 fits)
	// -128 needs 1 byte (exactly at boundary)
	// -129 needs 2 bytes (can represent -32768 to 32767)
	numBytes := 1
	boundary := big.NewInt(128) // 2^7
	for absVal.Cmp(boundary) > 0 {
		numBytes++
		boundary.Lsh(boundary, 8) // multiply by 256
	}

	// Create the two's complement representation
	// For negative numbers: result = 2^(8*numBytes) - |v|
	modulus := new(big.Int).Lsh(big.NewInt(1), uint(8*numBytes))
	result := new(big.Int).Sub(modulus, absVal)

	// Convert to bytes, padding with leading 0xFF if needed
	resultBytes := result.Bytes()

	// Ensure we have exactly numBytes
	if len(resultBytes) < numBytes {
		padded := make([]byte, numBytes)
		for i := 0; i < numBytes-len(resultBytes); i++ {
			padded[i] = 0xFF
		}
		copy(padded[numBytes-len(resultBytes):], resultBytes)
		return padded
	}

	return resultBytes
}

// DecodeVarint decodes CQL varint wire format to *big.Int.
func DecodeVarint(data []byte) *big.Int {
	if len(data) == 0 {
		return big.NewInt(0)
	}

	// Check sign (high bit of first byte)
	isNegative := data[0]&0x80 != 0

	if !isNegative {
		// Positive number - simply interpret as big-endian unsigned
		return new(big.Int).SetBytes(data)
	}

	// Negative number - convert from two's complement
	// ~x + 1 = -x, so x = ~(-x - 1)
	// We invert all bits and add 1 to get the absolute value
	inverted := make([]byte, len(data))
	for i, b := range data {
		inverted[i] = ^b
	}
	abs := new(big.Int).SetBytes(inverted)
	abs.Add(abs, big.NewInt(1))
	return abs.Neg(abs)
}

// EncodeDecimal encodes an *inf.Dec to CQL decimal wire format.
// The encoding is: [scale: int32] [unscaled: varint]
func EncodeDecimal(d *inf.Dec) []byte {
	if d == nil {
		return nil
	}

	// Get scale (negated because inf.Dec uses the opposite convention)
	scale := int32(-d.Scale())

	// Get the unscaled value
	unscaled := d.UnscaledBig()

	// Encode scale as 4-byte big-endian int32
	scaleBuf := make([]byte, 4)
	binary.BigEndian.PutUint32(scaleBuf, uint32(scale))

	// Encode unscaled value as varint
	varintBuf := EncodeVarint(unscaled)

	// Concatenate scale + varint
	result := make([]byte, 4+len(varintBuf))
	copy(result[0:4], scaleBuf)
	copy(result[4:], varintBuf)

	return result
}

// DecodeDecimal decodes CQL decimal wire format to *inf.Dec.
func DecodeDecimal(data []byte) *inf.Dec {
	if len(data) < 4 {
		return inf.NewDec(0, 0)
	}

	// Read scale (4 bytes, big-endian int32)
	scale := int32(binary.BigEndian.Uint32(data[0:4]))

	// Read unscaled value (varint)
	unscaled := DecodeVarint(data[4:])

	// Create inf.Dec with the decoded values
	// Note: inf.Dec scale is the negation of CQL scale
	return inf.NewDecBig(unscaled, inf.Scale(-scale))
}

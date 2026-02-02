package values

import (
	"bytes"
	"math"
	"math/big"
	"net"
	"testing"
	"time"

	"github.com/gocql/gocql"
	"gopkg.in/inf.v0"
)

func TestEncodeDecodeInt32(t *testing.T) {
	tests := []int32{0, 1, -1, 127, -128, 32767, -32768, 2147483647, -2147483648}
	for _, v := range tests {
		encoded := EncodeValue(v, uint16(TypeInt))
		if len(encoded) != 4 {
			t.Errorf("EncodeValue(%d) length = %d, want 4", v, len(encoded))
		}
		PutBuffer(encoded)
	}
}

func TestEncodeDecodeInt64(t *testing.T) {
	tests := []int64{0, 1, -1, 9223372036854775807, -9223372036854775808}
	for _, v := range tests {
		encoded := EncodeValue(v, uint16(TypeBigInt))
		if len(encoded) != 8 {
			t.Errorf("EncodeValue(%d) length = %d, want 8", v, len(encoded))
		}
		PutBuffer(encoded)
	}
}

func TestEncodeDecodeFloat32(t *testing.T) {
	tests := []float32{0, 1.5, -1.5, math.MaxFloat32, math.SmallestNonzeroFloat32}
	for _, v := range tests {
		encoded := EncodeValue(v, uint16(TypeFloat))
		if len(encoded) != 4 {
			t.Errorf("EncodeValue(%f) length = %d, want 4", v, len(encoded))
		}
		PutBuffer(encoded)
	}
}

func TestEncodeDecodeFloat64(t *testing.T) {
	tests := []float64{0, 1.5, -1.5, math.MaxFloat64, math.SmallestNonzeroFloat64}
	for _, v := range tests {
		encoded := EncodeValue(v, uint16(TypeDouble))
		if len(encoded) != 8 {
			t.Errorf("EncodeValue(%f) length = %d, want 8", v, len(encoded))
		}
		PutBuffer(encoded)
	}
}

func TestEncodeDecodeString(t *testing.T) {
	tests := []string{"", "hello", "世界", "a longer string with spaces"}
	for _, v := range tests {
		encoded := EncodeValue(v, uint16(TypeText))
		if string(encoded) != v {
			t.Errorf("EncodeValue(%q) = %q, want %q", v, string(encoded), v)
		}
	}
}

func TestEncodeDecodeBool(t *testing.T) {
	trueEncoded := EncodeValue(true, uint16(TypeBoolean))
	if len(trueEncoded) != 1 || trueEncoded[0] != 1 {
		t.Errorf("EncodeValue(true) = %v, want [1]", trueEncoded)
	}

	falseEncoded := EncodeValue(false, uint16(TypeBoolean))
	if len(falseEncoded) != 1 || falseEncoded[0] != 0 {
		t.Errorf("EncodeValue(false) = %v, want [0]", falseEncoded)
	}
}

func TestEncodeDecodeUUID(t *testing.T) {
	uuid, _ := gocql.ParseUUID("550e8400-e29b-41d4-a716-446655440000")
	encoded := EncodeValue(uuid, uint16(TypeUUID))
	if len(encoded) != 16 {
		t.Errorf("EncodeValue(uuid) length = %d, want 16", len(encoded))
	}
	if !bytes.Equal(encoded, uuid[:]) {
		t.Errorf("EncodeValue(uuid) = %v, want %v", encoded, uuid[:])
	}
}

func TestEncodeDecodeIP(t *testing.T) {
	ipv4 := net.ParseIP("192.168.1.1").To4()
	encoded := EncodeValue(ipv4, uint16(TypeInet))
	if len(encoded) != 4 {
		t.Errorf("EncodeValue(ipv4) length = %d, want 4", len(encoded))
	}

	ipv6 := net.ParseIP("::1")
	encoded = EncodeValue(ipv6, uint16(TypeInet))
	if len(encoded) != 16 {
		t.Errorf("EncodeValue(ipv6) length = %d, want 16", len(encoded))
	}
}

func TestEncodeDecodeTimestamp(t *testing.T) {
	now := time.Now()
	encoded := EncodeValue(now, uint16(TypeTimestamp))
	if len(encoded) != 8 {
		t.Errorf("EncodeValue(time) length = %d, want 8", len(encoded))
	}
	PutBuffer(encoded)
}

func TestEncodeDecodeNil(t *testing.T) {
	encoded := EncodeValue(nil, uint16(TypeInt))
	if encoded != nil {
		t.Errorf("EncodeValue(nil) = %v, want nil", encoded)
	}
}

func TestEncodeDecodeInt16(t *testing.T) {
	tests := []int16{0, 1, -1, 32767, -32768}
	for _, v := range tests {
		encoded := EncodeValue(v, uint16(TypeSmallInt))
		if len(encoded) != 2 {
			t.Errorf("EncodeValue(%d) length = %d, want 2", v, len(encoded))
		}
		PutBuffer(encoded)
	}
}

func TestEncodeDecodeInt8(t *testing.T) {
	tests := []int8{0, 1, -1, 127, -128}
	for _, v := range tests {
		encoded := EncodeValue(v, uint16(TypeTinyInt))
		if len(encoded) != 1 {
			t.Errorf("EncodeValue(%d) length = %d, want 1", v, len(encoded))
		}
	}
}

func TestEncodeDecodeVarint(t *testing.T) {
	tests := []*big.Int{
		big.NewInt(0),
		big.NewInt(1),
		big.NewInt(-1),
		big.NewInt(127),
		big.NewInt(128),
		big.NewInt(-128),
		big.NewInt(-129),
		new(big.Int).Exp(big.NewInt(2), big.NewInt(100), nil), // 2^100
	}

	for _, v := range tests {
		encoded := EncodeValue(v, uint16(TypeVarint))
		decoded := DecodeVarint(encoded)
		if decoded.Cmp(v) != 0 {
			t.Errorf("DecodeVarint(EncodeValue(%v)) = %v, want %v", v, decoded, v)
		}
	}
}

func TestEncodeDecodeDecimal(t *testing.T) {
	tests := []*inf.Dec{
		inf.NewDec(0, 0),
		inf.NewDec(1, 0),
		inf.NewDec(-1, 0),
		inf.NewDec(12345, 2),  // 123.45
		inf.NewDec(-12345, 2), // -123.45
		inf.NewDec(1, 10),     // 0.0000000001
	}

	for _, v := range tests {
		encoded := EncodeValue(v, uint16(TypeDecimal))
		decoded := DecodeDecimal(encoded)
		if decoded.Cmp(v) != 0 {
			t.Errorf("DecodeDecimal(EncodeValue(%v)) = %v, want %v", v, decoded, v)
		}
	}
}

func TestVarintEdgeCases(t *testing.T) {
	// Test nil
	if encoded := EncodeVarint(nil); encoded != nil {
		t.Errorf("EncodeVarint(nil) = %v, want nil", encoded)
	}

	// Test empty data
	decoded := DecodeVarint([]byte{})
	if decoded.Cmp(big.NewInt(0)) != 0 {
		t.Errorf("DecodeVarint([]) = %v, want 0", decoded)
	}

	// Test specific byte sequences
	tests := []struct {
		bytes    []byte
		expected *big.Int
	}{
		{[]byte{0x00}, big.NewInt(0)},
		{[]byte{0x01}, big.NewInt(1)},
		{[]byte{0x7F}, big.NewInt(127)},
		{[]byte{0x00, 0x80}, big.NewInt(128)},
		{[]byte{0xFF}, big.NewInt(-1)},
		{[]byte{0x80}, big.NewInt(-128)},
		{[]byte{0xFF, 0x7F}, big.NewInt(-129)},
	}

	for _, tt := range tests {
		decoded := DecodeVarint(tt.bytes)
		if decoded.Cmp(tt.expected) != 0 {
			t.Errorf("DecodeVarint(%v) = %v, want %v", tt.bytes, decoded, tt.expected)
		}
	}
}

func TestDecimalEdgeCases(t *testing.T) {
	// Test nil
	if encoded := EncodeDecimal(nil); encoded != nil {
		t.Errorf("EncodeDecimal(nil) = %v, want nil", encoded)
	}

	// Test short data
	decoded := DecodeDecimal([]byte{0, 0, 0})
	if decoded.Cmp(inf.NewDec(0, 0)) != 0 {
		t.Errorf("DecodeDecimal(short) = %v, want 0", decoded)
	}
}

func TestDecodeValueTypes(t *testing.T) {
	// Test int decoding
	data := []byte{0, 0, 0, 42}
	typeInfo := gocql.NewNativeType(0, gocql.TypeInt)
	val, err := DecodeValue(data, typeInfo)
	if err != nil {
		t.Fatalf("DecodeValue int failed: %v", err)
	}
	if i, ok := val.(int32); !ok || i != 42 {
		t.Errorf("DecodeValue int = %v, want 42", val)
	}

	// Test bigint decoding
	data = []byte{0, 0, 0, 0, 0, 0, 0, 100}
	typeInfo = gocql.NewNativeType(0, gocql.TypeBigInt)
	val, err = DecodeValue(data, typeInfo)
	if err != nil {
		t.Fatalf("DecodeValue bigint failed: %v", err)
	}
	if i, ok := val.(int64); !ok || i != 100 {
		t.Errorf("DecodeValue bigint = %v, want 100", val)
	}

	// Test text decoding
	data = []byte("hello world")
	typeInfo = gocql.NewNativeType(0, gocql.TypeText)
	val, err = DecodeValue(data, typeInfo)
	if err != nil {
		t.Fatalf("DecodeValue text failed: %v", err)
	}
	if s, ok := val.(string); !ok || s != "hello world" {
		t.Errorf("DecodeValue text = %v, want \"hello world\"", val)
	}

	// Test boolean decoding
	data = []byte{1}
	typeInfo = gocql.NewNativeType(0, gocql.TypeBoolean)
	val, err = DecodeValue(data, typeInfo)
	if err != nil {
		t.Fatalf("DecodeValue boolean failed: %v", err)
	}
	if b, ok := val.(bool); !ok || !b {
		t.Errorf("DecodeValue boolean = %v, want true", val)
	}

	// Test nil
	val, err = DecodeValue(nil, typeInfo)
	if err != nil {
		t.Fatalf("DecodeValue nil failed: %v", err)
	}
	if val != nil {
		t.Errorf("DecodeValue nil = %v, want nil", val)
	}
}

func TestTypeCodeFromGocql(t *testing.T) {
	tests := []struct {
		gocqlType gocql.Type
		expected  TypeCode
	}{
		{gocql.TypeInt, TypeInt},
		{gocql.TypeBigInt, TypeBigInt},
		{gocql.TypeText, TypeText},
		{gocql.TypeVarchar, TypeText},
		{gocql.TypeBoolean, TypeBoolean},
		{gocql.TypeFloat, TypeFloat},
		{gocql.TypeDouble, TypeDouble},
		{gocql.TypeTimestamp, TypeTimestamp},
		{gocql.TypeUUID, TypeUUID},
		{gocql.TypeTimeUUID, TypeTimeUUID},
		{gocql.TypeInet, TypeInet},
		{gocql.TypeDate, TypeDate},
		{gocql.TypeTime, TypeTime},
		{gocql.TypeSmallInt, TypeSmallInt},
		{gocql.TypeTinyInt, TypeTinyInt},
		{gocql.TypeBlob, TypeBlob},
		{gocql.TypeAscii, TypeAscii},
		{gocql.TypeCounter, TypeCounter},
	}

	for _, tt := range tests {
		result := TypeCodeFromGocql(tt.gocqlType)
		if result != tt.expected {
			t.Errorf("TypeCodeFromGocql(%v) = %v, want %v", tt.gocqlType, result, tt.expected)
		}
	}
}

func TestBufferPooling(t *testing.T) {
	// Get and put buffers multiple times to exercise the pool
	for i := 0; i < 100; i++ {
		b2 := getBuffer2()
		if len(b2) != 2 {
			t.Errorf("getBuffer2() length = %d, want 2", len(b2))
		}
		PutBuffer(b2)

		b4 := getBuffer4()
		if len(b4) != 4 {
			t.Errorf("getBuffer4() length = %d, want 4", len(b4))
		}
		PutBuffer(b4)

		b8 := getBuffer8()
		if len(b8) != 8 {
			t.Errorf("getBuffer8() length = %d, want 8", len(b8))
		}
		PutBuffer(b8)
	}
}

func BenchmarkEncodeInt32(b *testing.B) {
	for i := 0; i < b.N; i++ {
		encoded := EncodeValue(int32(12345), uint16(TypeInt))
		PutBuffer(encoded)
	}
}

func BenchmarkEncodeInt64(b *testing.B) {
	for i := 0; i < b.N; i++ {
		encoded := EncodeValue(int64(123456789), uint16(TypeBigInt))
		PutBuffer(encoded)
	}
}

func BenchmarkEncodeString(b *testing.B) {
	s := "hello world"
	for i := 0; i < b.N; i++ {
		_ = EncodeValue(s, uint16(TypeText))
	}
}

func BenchmarkEncodeVarint(b *testing.B) {
	v := big.NewInt(123456789)
	for i := 0; i < b.N; i++ {
		_ = EncodeVarint(v)
	}
}

func BenchmarkEncodeDecimal(b *testing.B) {
	v := inf.NewDec(12345, 2)
	for i := 0; i < b.N; i++ {
		_ = EncodeDecimal(v)
	}
}

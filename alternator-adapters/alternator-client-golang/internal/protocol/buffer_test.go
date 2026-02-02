package protocol

import (
	"io"
	"testing"
)

func TestBuffer_WriteUint8(t *testing.T) {
	buf := NewBuffer(16)
	buf.WriteUint8(0x42)
	buf.WriteUint8(0xFF)
	buf.WriteUint8(0x00)

	if got := buf.Len(); got != 3 {
		t.Errorf("Len() = %d, want 3", got)
	}

	data := buf.Bytes()
	if data[0] != 0x42 || data[1] != 0xFF || data[2] != 0x00 {
		t.Errorf("Bytes() = %v, want [0x42, 0xFF, 0x00]", data)
	}
}

func TestBuffer_WriteUint16(t *testing.T) {
	buf := NewBuffer(16)
	buf.WriteUint16(0x1234)

	data := buf.Bytes()
	if len(data) != 2 || data[0] != 0x12 || data[1] != 0x34 {
		t.Errorf("WriteUint16(0x1234) = %v, want [0x12, 0x34]", data)
	}
}

func TestBuffer_WriteUint32(t *testing.T) {
	buf := NewBuffer(16)
	buf.WriteUint32(0x12345678)

	data := buf.Bytes()
	expected := []byte{0x12, 0x34, 0x56, 0x78}
	if len(data) != 4 {
		t.Fatalf("WriteUint32: len = %d, want 4", len(data))
	}
	for i, b := range expected {
		if data[i] != b {
			t.Errorf("WriteUint32: byte[%d] = 0x%02x, want 0x%02x", i, data[i], b)
		}
	}
}

func TestBuffer_WriteUint64(t *testing.T) {
	buf := NewBuffer(16)
	buf.WriteUint64(0x123456789ABCDEF0)

	data := buf.Bytes()
	expected := []byte{0x12, 0x34, 0x56, 0x78, 0x9A, 0xBC, 0xDE, 0xF0}
	if len(data) != 8 {
		t.Fatalf("WriteUint64: len = %d, want 8", len(data))
	}
	for i, b := range expected {
		if data[i] != b {
			t.Errorf("WriteUint64: byte[%d] = 0x%02x, want 0x%02x", i, data[i], b)
		}
	}
}

func TestBuffer_WriteInt64(t *testing.T) {
	buf := NewBuffer(16)
	buf.WriteInt64(-1) // 0xFFFFFFFFFFFFFFFF

	data := buf.Bytes()
	if len(data) != 8 {
		t.Fatalf("WriteInt64: len = %d, want 8", len(data))
	}
	for i, b := range data {
		if b != 0xFF {
			t.Errorf("WriteInt64(-1): byte[%d] = 0x%02x, want 0xFF", i, b)
		}
	}
}

func TestBuffer_WriteBool(t *testing.T) {
	buf := NewBuffer(16)
	buf.WriteBool(true)
	buf.WriteBool(false)

	data := buf.Bytes()
	if len(data) != 2 || data[0] != 0x01 || data[1] != 0x00 {
		t.Errorf("WriteBool: %v, want [0x01, 0x00]", data)
	}
}

func TestBuffer_WriteBytes(t *testing.T) {
	buf := NewBuffer(32)
	buf.WriteBytes([]byte("hello"))

	data := buf.Bytes()
	// Length prefix (4 bytes) + data (5 bytes)
	if len(data) != 9 {
		t.Fatalf("WriteBytes: len = %d, want 9", len(data))
	}
	// Length should be 5 in big-endian
	if data[0] != 0 || data[1] != 0 || data[2] != 0 || data[3] != 5 {
		t.Errorf("WriteBytes: length prefix = %v, want [0, 0, 0, 5]", data[:4])
	}
	if string(data[4:]) != "hello" {
		t.Errorf("WriteBytes: data = %q, want %q", string(data[4:]), "hello")
	}
}

func TestBuffer_WriteString(t *testing.T) {
	buf := NewBuffer(32)
	buf.WriteString("world")

	data := buf.Bytes()
	if len(data) != 9 {
		t.Fatalf("WriteString: len = %d, want 9", len(data))
	}
	if string(data[4:]) != "world" {
		t.Errorf("WriteString: data = %q, want %q", string(data[4:]), "world")
	}
}

func TestBuffer_WriteOptionalString(t *testing.T) {
	// Test absent
	buf := NewBuffer(16)
	buf.WriteOptionalString(nil)
	data := buf.Bytes()
	if len(data) != 1 || data[0] != 0x00 {
		t.Errorf("WriteOptionalString(nil) = %v, want [0x00]", data)
	}

	// Test present
	buf.Reset()
	s := "test"
	buf.WriteOptionalString(&s)
	data = buf.Bytes()
	if len(data) != 9 { // 1 (present) + 4 (length) + 4 (data)
		t.Fatalf("WriteOptionalString: len = %d, want 9", len(data))
	}
	if data[0] != 0x01 {
		t.Errorf("WriteOptionalString: present byte = 0x%02x, want 0x01", data[0])
	}
}

func TestBuffer_WriteOptionalUint32(t *testing.T) {
	// Test absent
	buf := NewBuffer(16)
	buf.WriteOptionalUint32(nil)
	data := buf.Bytes()
	if len(data) != 1 || data[0] != 0x00 {
		t.Errorf("WriteOptionalUint32(nil) = %v, want [0x00]", data)
	}

	// Test present
	buf.Reset()
	v := uint32(42)
	buf.WriteOptionalUint32(&v)
	data = buf.Bytes()
	if len(data) != 5 { // 1 (present) + 4 (value)
		t.Fatalf("WriteOptionalUint32: len = %d, want 5", len(data))
	}
	if data[0] != 0x01 {
		t.Errorf("WriteOptionalUint32: present byte = 0x%02x, want 0x01", data[0])
	}
}

func TestBuffer_WriteRaw(t *testing.T) {
	buf := NewBuffer(16)
	buf.WriteRaw([]byte{0x01, 0x02, 0x03})

	data := buf.Bytes()
	if len(data) != 3 {
		t.Fatalf("WriteRaw: len = %d, want 3", len(data))
	}
	if data[0] != 0x01 || data[1] != 0x02 || data[2] != 0x03 {
		t.Errorf("WriteRaw: %v, want [0x01, 0x02, 0x03]", data)
	}
}

func TestBuffer_Reset(t *testing.T) {
	buf := NewBuffer(16)
	buf.WriteUint32(0x12345678)
	buf.Reset()

	if buf.Len() != 0 {
		t.Errorf("Len() after Reset = %d, want 0", buf.Len())
	}
	if len(buf.Bytes()) != 0 {
		t.Errorf("Bytes() after Reset = %v, want []", buf.Bytes())
	}
}

// Reader tests

func TestReader_ReadUint8(t *testing.T) {
	r := NewReader([]byte{0x42, 0xFF})

	v, err := r.ReadUint8()
	if err != nil {
		t.Fatalf("ReadUint8: %v", err)
	}
	if v != 0x42 {
		t.Errorf("ReadUint8 = 0x%02x, want 0x42", v)
	}

	v, err = r.ReadUint8()
	if err != nil {
		t.Fatalf("ReadUint8: %v", err)
	}
	if v != 0xFF {
		t.Errorf("ReadUint8 = 0x%02x, want 0xFF", v)
	}

	// Should fail on EOF
	_, err = r.ReadUint8()
	if err != io.ErrUnexpectedEOF {
		t.Errorf("ReadUint8 at EOF: err = %v, want io.ErrUnexpectedEOF", err)
	}
}

func TestReader_ReadUint16(t *testing.T) {
	r := NewReader([]byte{0x12, 0x34})

	v, err := r.ReadUint16()
	if err != nil {
		t.Fatalf("ReadUint16: %v", err)
	}
	if v != 0x1234 {
		t.Errorf("ReadUint16 = 0x%04x, want 0x1234", v)
	}

	// Should fail on insufficient data
	r = NewReader([]byte{0x12})
	_, err = r.ReadUint16()
	if err != io.ErrUnexpectedEOF {
		t.Errorf("ReadUint16 with 1 byte: err = %v, want io.ErrUnexpectedEOF", err)
	}
}

func TestReader_ReadUint32(t *testing.T) {
	r := NewReader([]byte{0x12, 0x34, 0x56, 0x78})

	v, err := r.ReadUint32()
	if err != nil {
		t.Fatalf("ReadUint32: %v", err)
	}
	if v != 0x12345678 {
		t.Errorf("ReadUint32 = 0x%08x, want 0x12345678", v)
	}

	// Should fail on insufficient data
	r = NewReader([]byte{0x12, 0x34, 0x56})
	_, err = r.ReadUint32()
	if err != io.ErrUnexpectedEOF {
		t.Errorf("ReadUint32 with 3 bytes: err = %v, want io.ErrUnexpectedEOF", err)
	}
}

func TestReader_ReadUint64(t *testing.T) {
	r := NewReader([]byte{0x12, 0x34, 0x56, 0x78, 0x9A, 0xBC, 0xDE, 0xF0})

	v, err := r.ReadUint64()
	if err != nil {
		t.Fatalf("ReadUint64: %v", err)
	}
	if v != 0x123456789ABCDEF0 {
		t.Errorf("ReadUint64 = 0x%016x, want 0x123456789ABCDEF0", v)
	}

	// Should fail on insufficient data
	r = NewReader([]byte{0x12, 0x34, 0x56, 0x78, 0x9A, 0xBC, 0xDE})
	_, err = r.ReadUint64()
	if err != io.ErrUnexpectedEOF {
		t.Errorf("ReadUint64 with 7 bytes: err = %v, want io.ErrUnexpectedEOF", err)
	}
}

func TestReader_ReadInt64(t *testing.T) {
	// Test negative number
	r := NewReader([]byte{0xFF, 0xFF, 0xFF, 0xFF, 0xFF, 0xFF, 0xFF, 0xFF})

	v, err := r.ReadInt64()
	if err != nil {
		t.Fatalf("ReadInt64: %v", err)
	}
	if v != -1 {
		t.Errorf("ReadInt64 = %d, want -1", v)
	}
}

func TestReader_ReadBool(t *testing.T) {
	r := NewReader([]byte{0x00, 0x01, 0x42})

	v, err := r.ReadBool()
	if err != nil {
		t.Fatalf("ReadBool: %v", err)
	}
	if v != false {
		t.Errorf("ReadBool(0x00) = %v, want false", v)
	}

	v, err = r.ReadBool()
	if err != nil {
		t.Fatalf("ReadBool: %v", err)
	}
	if v != true {
		t.Errorf("ReadBool(0x01) = %v, want true", v)
	}

	// Any non-zero value should be true
	v, err = r.ReadBool()
	if err != nil {
		t.Fatalf("ReadBool: %v", err)
	}
	if v != true {
		t.Errorf("ReadBool(0x42) = %v, want true", v)
	}
}

func TestReader_ReadBytes(t *testing.T) {
	// "hello" with length prefix
	data := []byte{0x00, 0x00, 0x00, 0x05, 'h', 'e', 'l', 'l', 'o'}
	r := NewReader(data)

	v, err := r.ReadBytes()
	if err != nil {
		t.Fatalf("ReadBytes: %v", err)
	}
	if string(v) != "hello" {
		t.Errorf("ReadBytes = %q, want %q", string(v), "hello")
	}

	// Test empty bytes
	r = NewReader([]byte{0x00, 0x00, 0x00, 0x00})
	v, err = r.ReadBytes()
	if err != nil {
		t.Fatalf("ReadBytes(empty): %v", err)
	}
	if len(v) != 0 {
		t.Errorf("ReadBytes(empty) = %v, want []", v)
	}

	// Test truncated data
	r = NewReader([]byte{0x00, 0x00, 0x00, 0x10, 'a', 'b'}) // claims 16 bytes but only has 2
	_, err = r.ReadBytes()
	if err != io.ErrUnexpectedEOF {
		t.Errorf("ReadBytes(truncated): err = %v, want io.ErrUnexpectedEOF", err)
	}
}

func TestReader_ReadString(t *testing.T) {
	// "world" with length prefix
	data := []byte{0x00, 0x00, 0x00, 0x05, 'w', 'o', 'r', 'l', 'd'}
	r := NewReader(data)

	v, err := r.ReadString()
	if err != nil {
		t.Fatalf("ReadString: %v", err)
	}
	if v != "world" {
		t.Errorf("ReadString = %q, want %q", v, "world")
	}
}

func TestReader_ReadOptionalString(t *testing.T) {
	// Test absent
	r := NewReader([]byte{0x00})
	v, err := r.ReadOptionalString()
	if err != nil {
		t.Fatalf("ReadOptionalString(absent): %v", err)
	}
	if v != nil {
		t.Errorf("ReadOptionalString(absent) = %v, want nil", v)
	}

	// Test present
	data := []byte{0x01, 0x00, 0x00, 0x00, 0x04, 't', 'e', 's', 't'}
	r = NewReader(data)
	v, err = r.ReadOptionalString()
	if err != nil {
		t.Fatalf("ReadOptionalString(present): %v", err)
	}
	if v == nil {
		t.Fatal("ReadOptionalString(present) = nil, want non-nil")
	}
	if *v != "test" {
		t.Errorf("ReadOptionalString(present) = %q, want %q", *v, "test")
	}
}

func TestReader_ReadOptionalUint32(t *testing.T) {
	// Test absent
	r := NewReader([]byte{0x00})
	v, err := r.ReadOptionalUint32()
	if err != nil {
		t.Fatalf("ReadOptionalUint32(absent): %v", err)
	}
	if v != nil {
		t.Errorf("ReadOptionalUint32(absent) = %v, want nil", v)
	}

	// Test present
	data := []byte{0x01, 0x00, 0x00, 0x00, 0x2A} // 42
	r = NewReader(data)
	v, err = r.ReadOptionalUint32()
	if err != nil {
		t.Fatalf("ReadOptionalUint32(present): %v", err)
	}
	if v == nil {
		t.Fatal("ReadOptionalUint32(present) = nil, want non-nil")
	}
	if *v != 42 {
		t.Errorf("ReadOptionalUint32(present) = %d, want 42", *v)
	}
}

func TestReader_Remaining(t *testing.T) {
	r := NewReader([]byte{0x01, 0x02, 0x03, 0x04})

	if r.Remaining() != 4 {
		t.Errorf("Remaining() = %d, want 4", r.Remaining())
	}

	r.ReadUint16()
	if r.Remaining() != 2 {
		t.Errorf("Remaining() after ReadUint16 = %d, want 2", r.Remaining())
	}

	r.ReadUint16()
	if r.Remaining() != 0 {
		t.Errorf("Remaining() at end = %d, want 0", r.Remaining())
	}
}

// Round-trip tests

func TestBuffer_Reader_RoundTrip(t *testing.T) {
	// Write various values
	buf := NewBuffer(128)
	buf.WriteUint8(0x42)
	buf.WriteUint16(0x1234)
	buf.WriteUint32(0x12345678)
	buf.WriteUint64(0x123456789ABCDEF0)
	buf.WriteInt64(-12345)
	buf.WriteBool(true)
	buf.WriteBool(false)
	buf.WriteString("hello world")
	buf.WriteBytes([]byte{0xDE, 0xAD, 0xBE, 0xEF})

	s := "optional"
	buf.WriteOptionalString(&s)
	buf.WriteOptionalString(nil)

	v := uint32(999)
	buf.WriteOptionalUint32(&v)
	buf.WriteOptionalUint32(nil)

	// Read them back
	r := NewReader(buf.Bytes())

	if u8, _ := r.ReadUint8(); u8 != 0x42 {
		t.Errorf("round-trip uint8 = 0x%02x, want 0x42", u8)
	}
	if u16, _ := r.ReadUint16(); u16 != 0x1234 {
		t.Errorf("round-trip uint16 = 0x%04x, want 0x1234", u16)
	}
	if u32, _ := r.ReadUint32(); u32 != 0x12345678 {
		t.Errorf("round-trip uint32 = 0x%08x, want 0x12345678", u32)
	}
	if u64, _ := r.ReadUint64(); u64 != 0x123456789ABCDEF0 {
		t.Errorf("round-trip uint64 = 0x%016x, want 0x123456789ABCDEF0", u64)
	}
	if i64, _ := r.ReadInt64(); i64 != -12345 {
		t.Errorf("round-trip int64 = %d, want -12345", i64)
	}
	if b, _ := r.ReadBool(); !b {
		t.Error("round-trip bool = false, want true")
	}
	if b, _ := r.ReadBool(); b {
		t.Error("round-trip bool = true, want false")
	}
	if str, _ := r.ReadString(); str != "hello world" {
		t.Errorf("round-trip string = %q, want %q", str, "hello world")
	}
	if bytes, _ := r.ReadBytes(); len(bytes) != 4 || bytes[0] != 0xDE {
		t.Errorf("round-trip bytes = %v, want [0xDE, 0xAD, 0xBE, 0xEF]", bytes)
	}
	if opt, _ := r.ReadOptionalString(); opt == nil || *opt != "optional" {
		t.Errorf("round-trip optional string = %v, want %q", opt, "optional")
	}
	if opt, _ := r.ReadOptionalString(); opt != nil {
		t.Errorf("round-trip nil optional string = %v, want nil", opt)
	}
	if opt, _ := r.ReadOptionalUint32(); opt == nil || *opt != 999 {
		t.Errorf("round-trip optional uint32 = %v, want 999", opt)
	}
	if opt, _ := r.ReadOptionalUint32(); opt != nil {
		t.Errorf("round-trip nil optional uint32 = %v, want nil", opt)
	}

	if r.Remaining() != 0 {
		t.Errorf("Remaining() = %d, want 0", r.Remaining())
	}
}

func TestBuffer_LargeData(t *testing.T) {
	// Test with data > 64KB
	largeData := make([]byte, 100000)
	for i := range largeData {
		largeData[i] = byte(i % 256)
	}

	buf := NewBuffer(len(largeData) + 10)
	buf.WriteBytes(largeData)

	r := NewReader(buf.Bytes())
	result, err := r.ReadBytes()
	if err != nil {
		t.Fatalf("ReadBytes(large): %v", err)
	}
	if len(result) != len(largeData) {
		t.Errorf("ReadBytes(large): len = %d, want %d", len(result), len(largeData))
	}
	for i := 0; i < len(largeData); i++ {
		if result[i] != largeData[i] {
			t.Errorf("ReadBytes(large): mismatch at index %d", i)
			break
		}
	}
}

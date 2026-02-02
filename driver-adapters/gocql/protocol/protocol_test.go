package protocol

import (
	"bytes"
	"testing"
)

func TestReadWriteShort(t *testing.T) {
	tests := []uint16{0, 1, 255, 256, 65535}
	for _, v := range tests {
		buf := new(bytes.Buffer)
		if err := WriteShort(buf, v); err != nil {
			t.Fatalf("WriteShort(%d) failed: %v", v, err)
		}
		result, err := ReadShort(bytes.NewReader(buf.Bytes()))
		if err != nil {
			t.Fatalf("ReadShort() failed: %v", err)
		}
		if result != v {
			t.Errorf("ReadShort() = %d, want %d", result, v)
		}
	}
}

func TestReadWriteInt(t *testing.T) {
	tests := []int32{0, 1, -1, 127, -128, 2147483647, -2147483648}
	for _, v := range tests {
		buf := new(bytes.Buffer)
		if err := WriteInt(buf, v); err != nil {
			t.Fatalf("WriteInt(%d) failed: %v", v, err)
		}
		result, err := ReadInt(bytes.NewReader(buf.Bytes()))
		if err != nil {
			t.Fatalf("ReadInt() failed: %v", err)
		}
		if result != v {
			t.Errorf("ReadInt() = %d, want %d", result, v)
		}
	}
}

func TestReadWriteLong(t *testing.T) {
	tests := []uint64{0, 1, 255, 18446744073709551615}
	for _, v := range tests {
		buf := new(bytes.Buffer)
		if err := WriteLong(buf, v); err != nil {
			t.Fatalf("WriteLong(%d) failed: %v", v, err)
		}
		result, err := ReadLong(bytes.NewReader(buf.Bytes()))
		if err != nil {
			t.Fatalf("ReadLong() failed: %v", err)
		}
		if result != v {
			t.Errorf("ReadLong() = %d, want %d", result, v)
		}
	}
}

func TestReadWriteString(t *testing.T) {
	tests := []string{"", "hello", "世界", "a longer string with spaces and 日本語"}
	for _, v := range tests {
		buf := new(bytes.Buffer)
		if err := WriteString(buf, v); err != nil {
			t.Fatalf("WriteString(%q) failed: %v", v, err)
		}
		result, err := ReadString(bytes.NewReader(buf.Bytes()))
		if err != nil {
			t.Fatalf("ReadString() failed: %v", err)
		}
		if result != v {
			t.Errorf("ReadString() = %q, want %q", result, v)
		}
	}
}

func TestReadWriteLongString(t *testing.T) {
	tests := []string{"", "hello", "世界", string(make([]byte, 100000))}
	for i, v := range tests {
		buf := new(bytes.Buffer)
		if err := WriteLongString(buf, v); err != nil {
			t.Fatalf("WriteLongString test %d failed: %v", i, err)
		}
		result, err := ReadLongString(bytes.NewReader(buf.Bytes()))
		if err != nil {
			t.Fatalf("ReadLongString() test %d failed: %v", i, err)
		}
		if result != v {
			t.Errorf("ReadLongString() test %d: length mismatch got %d, want %d", i, len(result), len(v))
		}
	}
}

func TestReadWriteBytes(t *testing.T) {
	tests := [][]byte{
		nil,
		{},
		{0},
		{1, 2, 3},
		make([]byte, 1000),
	}
	for i, v := range tests {
		buf := new(bytes.Buffer)
		if err := WriteBytes(buf, v); err != nil {
			t.Fatalf("WriteBytes test %d failed: %v", i, err)
		}
		result, err := ReadBytes(bytes.NewReader(buf.Bytes()))
		if err != nil {
			t.Fatalf("ReadBytes() test %d failed: %v", i, err)
		}
		if v == nil {
			if result != nil {
				t.Errorf("ReadBytes() test %d = %v, want nil", i, result)
			}
		} else if !bytes.Equal(result, v) {
			t.Errorf("ReadBytes() test %d mismatch", i)
		}
	}
}

func TestReadWriteShortBytes(t *testing.T) {
	tests := [][]byte{
		{},
		{0},
		{1, 2, 3},
		make([]byte, 1000),
	}
	for i, v := range tests {
		buf := new(bytes.Buffer)
		if err := WriteShortBytes(buf, v); err != nil {
			t.Fatalf("WriteShortBytes test %d failed: %v", i, err)
		}
		result, err := ReadShortBytes(bytes.NewReader(buf.Bytes()))
		if err != nil {
			t.Fatalf("ReadShortBytes() test %d failed: %v", i, err)
		}
		if !bytes.Equal(result, v) {
			t.Errorf("ReadShortBytes() test %d mismatch", i)
		}
	}
}

func TestReadWriteByte(t *testing.T) {
	tests := []byte{0, 1, 127, 128, 255}
	for _, v := range tests {
		buf := new(bytes.Buffer)
		if err := WriteByte(buf, v); err != nil {
			t.Fatalf("WriteByte(%d) failed: %v", v, err)
		}
		result, err := ReadByte(bytes.NewReader(buf.Bytes()))
		if err != nil {
			t.Fatalf("ReadByte() failed: %v", err)
		}
		if result != v {
			t.Errorf("ReadByte() = %d, want %d", result, v)
		}
	}
}

func TestReadStringMap(t *testing.T) {
	// Create a string map frame
	buf := new(bytes.Buffer)
	WriteShort(buf, 2) // 2 entries
	WriteString(buf, "key1")
	WriteString(buf, "value1")
	WriteString(buf, "key2")
	WriteString(buf, "value2")

	result, err := ReadStringMap(bytes.NewReader(buf.Bytes()))
	if err != nil {
		t.Fatalf("ReadStringMap() failed: %v", err)
	}
	if len(result) != 2 {
		t.Errorf("ReadStringMap() length = %d, want 2", len(result))
	}
	if result["key1"] != "value1" {
		t.Errorf("ReadStringMap()[\"key1\"] = %q, want \"value1\"", result["key1"])
	}
	if result["key2"] != "value2" {
		t.Errorf("ReadStringMap()[\"key2\"] = %q, want \"value2\"", result["key2"])
	}
}

func TestErrorFrameCreation(t *testing.T) {
	frame := ErrorFrame(42, ErrorServer, "test error message")
	if frame.Header.Opcode != OpcodeError {
		t.Errorf("ErrorFrame opcode = %v, want Error", frame.Header.Opcode)
	}
	if frame.Header.Stream != 42 {
		t.Errorf("ErrorFrame stream = %d, want 42", frame.Header.Stream)
	}
}

func TestVoidResultFrameCreation(t *testing.T) {
	frame := VoidResultFrame(1)
	if frame.Header.Opcode != OpcodeResult {
		t.Errorf("VoidResultFrame opcode = %v, want Result", frame.Header.Opcode)
	}
	if frame.Header.Stream != 1 {
		t.Errorf("VoidResultFrame stream = %d, want 1", frame.Header.Stream)
	}
}

func TestPreparedResultFrameCreation(t *testing.T) {
	frame := PreparedResultFrame(5, "test_key")
	if frame.Header.Opcode != OpcodeResult {
		t.Errorf("PreparedResultFrame opcode = %v, want Result", frame.Header.Opcode)
	}
	if frame.Header.Stream != 5 {
		t.Errorf("PreparedResultFrame stream = %d, want 5", frame.Header.Stream)
	}
}

func TestSessionCreatedFrameCreation(t *testing.T) {
	frame := SessionCreatedFrame(10, 12345)
	if frame.Header.Opcode != OpcodeSessionCreated {
		t.Errorf("SessionCreatedFrame opcode = %v, want SessionCreated", frame.Header.Opcode)
	}
	if frame.Header.Stream != 10 {
		t.Errorf("SessionCreatedFrame stream = %d, want 10", frame.Header.Stream)
	}
}

func TestRowsResultFrameCreation(t *testing.T) {
	columns := []ColumnMeta{
		{Keyspace: "ks", Table: "tbl", Name: "id", TypeCode: 0x0009},
		{Keyspace: "ks", Table: "tbl", Name: "name", TypeCode: 0x000D},
	}
	rows := [][]interface{}{
		{int32(1), "Alice"},
		{int32(2), "Bob"},
	}
	frame := RowsResultFrame(1, columns, rows)
	if frame.Header.Opcode != OpcodeResult {
		t.Errorf("RowsResultFrame opcode = %v, want Result", frame.Header.Opcode)
	}
}

func TestRowsResultFrameWithPaging(t *testing.T) {
	columns := []ColumnMeta{
		{Keyspace: "ks", Table: "tbl", Name: "id", TypeCode: 0x0009},
	}
	rows := [][]interface{}{
		{int32(1)},
	}
	pageState := []byte{1, 2, 3, 4}
	frame := RowsResultFrameWithPaging(1, columns, rows, pageState)
	if frame.Header.Opcode != OpcodeResult {
		t.Errorf("RowsResultFrameWithPaging opcode = %v, want Result", frame.Header.Opcode)
	}
	// Check that flags indicate more pages
	// The flags are at offset 4 (after result kind)
	if len(frame.Body) < 8 {
		t.Fatalf("RowsResultFrameWithPaging body too short: %d", len(frame.Body))
	}
}

func TestNewFrame(t *testing.T) {
	frame := NewFrame(42, OpcodeResult, []byte{1, 2, 3})
	if frame.Header.Stream != 42 {
		t.Errorf("NewFrame stream = %d, want 42", frame.Header.Stream)
	}
	if frame.Header.Opcode != OpcodeResult {
		t.Errorf("NewFrame opcode = %v, want Result", frame.Header.Opcode)
	}
	if !bytes.Equal(frame.Body, []byte{1, 2, 3}) {
		t.Errorf("NewFrame body = %v, want [1, 2, 3]", frame.Body)
	}
}

func TestEncodeFrame(t *testing.T) {
	frame := NewFrame(1, OpcodeResult, []byte{0xDE, 0xAD, 0xBE, 0xEF})
	encoded := EncodeFrame(frame)

	// Check header length
	if len(encoded) != HeaderLength+4 {
		t.Errorf("EncodeFrame length = %d, want %d", len(encoded), HeaderLength+4)
	}

	// Check version
	if encoded[0] != VersionResponse {
		t.Errorf("EncodeFrame version = %#x, want %#x", encoded[0], VersionResponse)
	}

	// Check opcode
	if encoded[4] != byte(OpcodeResult) {
		t.Errorf("EncodeFrame opcode = %#x, want %#x", encoded[4], byte(OpcodeResult))
	}
}

func TestResultFlagConstants(t *testing.T) {
	if ResultFlagGlobalTableSpec != 0x0001 {
		t.Errorf("ResultFlagGlobalTableSpec = %#x, want 0x0001", ResultFlagGlobalTableSpec)
	}
	if ResultFlagHasMorePages != 0x0002 {
		t.Errorf("ResultFlagHasMorePages = %#x, want 0x0002", ResultFlagHasMorePages)
	}
	if ResultFlagNoMetadata != 0x0004 {
		t.Errorf("ResultFlagNoMetadata = %#x, want 0x0004", ResultFlagNoMetadata)
	}
}

func TestOpcodeConstants(t *testing.T) {
	tests := []struct {
		opcode   Opcode
		expected byte
	}{
		{OpcodeError, 0x00},
		{OpcodeStartup, 0x01},
		{OpcodeReady, 0x02},
		{OpcodeOptions, 0x05},
		{OpcodeSupported, 0x06},
		{OpcodeQuery, 0x07},
		{OpcodeResult, 0x08},
		{OpcodePrepare, 0x09},
		{OpcodeExecute, 0x0A},
		{OpcodeBatch, 0x0D},
	}

	for _, tt := range tests {
		if byte(tt.opcode) != tt.expected {
			t.Errorf("Opcode %v = %#x, want %#x", tt.opcode, byte(tt.opcode), tt.expected)
		}
	}
}

func TestConsistencyConstants(t *testing.T) {
	tests := []struct {
		consistency Consistency
		expected    uint16
	}{
		{ConsistencyAny, 0x0000},
		{ConsistencyOne, 0x0001},
		{ConsistencyTwo, 0x0002},
		{ConsistencyThree, 0x0003},
		{ConsistencyQuorum, 0x0004},
		{ConsistencyAll, 0x0005},
		{ConsistencyLocalQuorum, 0x0006},
		{ConsistencyEachQuorum, 0x0007},
		{ConsistencyLocalOne, 0x000A},
	}

	for _, tt := range tests {
		if uint16(tt.consistency) != tt.expected {
			t.Errorf("Consistency %v = %#x, want %#x", tt.consistency, uint16(tt.consistency), tt.expected)
		}
	}
}

func TestResultKindConstants(t *testing.T) {
	tests := []struct {
		kind     ResultKind
		expected int32
	}{
		{ResultVoid, 0x0001},
		{ResultRows, 0x0002},
		{ResultSetKeyspace, 0x0003},
		{ResultPrepared, 0x0004},
		{ResultSchemaChange, 0x0005},
	}

	for _, tt := range tests {
		if int32(tt.kind) != tt.expected {
			t.Errorf("ResultKind %v = %#x, want %#x", tt.kind, int32(tt.kind), tt.expected)
		}
	}
}

func TestBatchTypeConstants(t *testing.T) {
	tests := []struct {
		batchType BatchType
		expected  byte
	}{
		{BatchLogged, 0},
		{BatchUnlogged, 1},
		{BatchCounter, 2},
	}

	for _, tt := range tests {
		if byte(tt.batchType) != tt.expected {
			t.Errorf("BatchType %v = %d, want %d", tt.batchType, byte(tt.batchType), tt.expected)
		}
	}
}

func BenchmarkReadInt(b *testing.B) {
	data := []byte{0x00, 0x00, 0x30, 0x39} // 12345
	for i := 0; i < b.N; i++ {
		ReadInt(bytes.NewReader(data))
	}
}

func BenchmarkWriteInt(b *testing.B) {
	buf := new(bytes.Buffer)
	for i := 0; i < b.N; i++ {
		buf.Reset()
		WriteInt(buf, 12345)
	}
}

func BenchmarkReadString(b *testing.B) {
	buf := new(bytes.Buffer)
	WriteString(buf, "hello world")
	data := buf.Bytes()
	for i := 0; i < b.N; i++ {
		ReadString(bytes.NewReader(data))
	}
}

func BenchmarkEncodeFrame(b *testing.B) {
	frame := NewFrame(1, OpcodeResult, make([]byte, 100))
	for i := 0; i < b.N; i++ {
		EncodeFrame(frame)
	}
}

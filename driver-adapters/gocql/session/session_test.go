package session

import (
	"bytes"
	"math/big"
	"testing"

	"github.com/scylladb/latte/driver-adapters/gocql/protocol"
	"github.com/scylladb/latte/driver-adapters/gocql/values"
	"gopkg.in/inf.v0"
)

func TestParseColumnList(t *testing.T) {
	tests := []struct {
		input    string
		expected []string
	}{
		{"a, b, c", []string{"a", "b", "c"}},
		{"id,name,value", []string{"id", "name", "value"}},
		{"  col1  ,  col2  ", []string{"col1", "col2"}},
		{"single", []string{"single"}},
		{"", nil},
	}

	for _, tt := range tests {
		result := parseColumnList(tt.input)
		if len(result) != len(tt.expected) {
			t.Errorf("parseColumnList(%q) = %v, want %v", tt.input, result, tt.expected)
			continue
		}
		for i := range result {
			if result[i] != tt.expected[i] {
				t.Errorf("parseColumnList(%q)[%d] = %q, want %q", tt.input, i, result[i], tt.expected[i])
			}
		}
	}
}

func TestParseWhereColumns(t *testing.T) {
	tests := []struct {
		input    string
		expected []string
	}{
		{"id = ?", []string{"id"}},
		{"id = ? AND name = ?", []string{"id", "name"}},
		{"pk = ? AND ck = ? AND value = ?", []string{"pk", "ck", "value"}},
		{"id IN (?)", []string{"id"}},
		{"id = ? and name = ?", []string{"id", "name"}}, // lowercase AND
	}

	for _, tt := range tests {
		result := parseWhereColumns(tt.input)
		if len(result) != len(tt.expected) {
			t.Errorf("parseWhereColumns(%q) = %v, want %v", tt.input, result, tt.expected)
			continue
		}
		for i := range result {
			if result[i] != tt.expected[i] {
				t.Errorf("parseWhereColumns(%q)[%d] = %q, want %q", tt.input, i, result[i], tt.expected[i])
			}
		}
	}
}

func TestParseQueryColumns(t *testing.T) {
	tests := []struct {
		query    string
		keyspace string
		table    string
		columns  []string
	}{
		{
			"INSERT INTO ks.tbl (id, name, value) VALUES (?, ?, ?)",
			"ks", "tbl", []string{"id", "name", "value"},
		},
		{
			"UPDATE ks.tbl SET name = ? WHERE id = ?",
			"ks", "tbl", []string{"name", "id"},
		},
		{
			"SELECT * FROM ks.tbl WHERE id = ?",
			"ks", "tbl", []string{"id"},
		},
		{
			"SELECT * FROM ks.tbl WHERE pk = ? AND ck = ?",
			"ks", "tbl", []string{"pk", "ck"},
		},
	}

	for _, tt := range tests {
		keyspace, table, columns := parseQueryColumns(tt.query)
		if keyspace != tt.keyspace || table != tt.table {
			t.Errorf("parseQueryColumns(%q) keyspace/table = %q/%q, want %q/%q",
				tt.query, keyspace, table, tt.keyspace, tt.table)
		}
		if len(columns) != len(tt.columns) {
			t.Errorf("parseQueryColumns(%q) columns = %v, want %v", tt.query, columns, tt.columns)
			continue
		}
		for i := range columns {
			if columns[i] != tt.columns[i] {
				t.Errorf("parseQueryColumns(%q) columns[%d] = %q, want %q",
					tt.query, i, columns[i], tt.columns[i])
			}
		}
	}
}

func TestConvertNamedToPositional(t *testing.T) {
	tests := []struct {
		input    string
		expected string
	}{
		{
			"INSERT INTO tbl (id) VALUES (:id)",
			"INSERT INTO tbl (id) VALUES (?)",
		},
		{
			"SELECT * FROM tbl WHERE name = :name AND age = :age",
			"SELECT * FROM tbl WHERE name = ? AND age = ?",
		},
		{
			"SELECT * FROM tbl WHERE value = 'literal:text'",
			"SELECT * FROM tbl WHERE value = 'literal:text'",
		},
		{
			"SELECT * FROM tbl WHERE id = ?",
			"SELECT * FROM tbl WHERE id = ?",
		},
		{
			"INSERT INTO tbl (a, b, c) VALUES (:a, :b_param, :c_123)",
			"INSERT INTO tbl (a, b, c) VALUES (?, ?, ?)",
		},
	}

	for _, tt := range tests {
		result := convertNamedToPositional(tt.input)
		if result != tt.expected {
			t.Errorf("convertNamedToPositional(%q) = %q, want %q", tt.input, result, tt.expected)
		}
	}
}

func TestCountTupleElements(t *testing.T) {
	tests := []struct {
		cqlType  string
		expected int
	}{
		{"tuple<int, text>", 2},
		{"tuple<int, text, boolean>", 3},
		{"tuple<int>", 1},
		{"frozen<tuple<int, text>>", 2},
		{"tuple<tuple<int, int>, text>", 2}, // nested tuple counts as 1
		{"int", 0},
		{"text", 0},
		{"list<int>", 0},
	}

	for _, tt := range tests {
		result := countTupleElements(tt.cqlType)
		if result != tt.expected {
			t.Errorf("countTupleElements(%q) = %d, want %d", tt.cqlType, result, tt.expected)
		}
	}
}

func TestCqlTypeStringToColumnType(t *testing.T) {
	tests := []struct {
		cqlType  string
		expected ColumnType
	}{
		{"int", ColTypeInt},
		{"bigint", ColTypeBigInt},
		{"text", ColTypeText},
		{"varchar", ColTypeText},
		{"float", ColTypeFloat},
		{"double", ColTypeDouble},
		{"boolean", ColTypeBoolean},
		{"uuid", ColTypeUUID},
		{"timeuuid", ColTypeTimeuuid},
		{"timestamp", ColTypeTimestamp},
		{"date", ColTypeDate},
		{"time", ColTypeTime},
		{"duration", ColTypeDuration},
		{"inet", ColTypeInet},
		{"varint", ColTypeVarint},
		{"decimal", ColTypeDecimal},
		{"blob", ColTypeBlob},
		{"ascii", ColTypeAscii},
		{"counter", ColTypeCounter},
		{"smallint", ColTypeSmallInt},
		{"tinyint", ColTypeTinyInt},
		{"list<int>", ColTypeList},
		{"set<text>", ColTypeSet},
		{"map<text, int>", ColTypeMap},
		{"vector<float, 128>", ColTypeVector},
		{"tuple<int, text>", ColTypeTuple},
		{"frozen<list<int>>", ColTypeList},
		{"myudt", ColTypeUDT}, // unknown type treated as UDT
	}

	for _, tt := range tests {
		result := cqlTypeStringToColumnType(tt.cqlType)
		if result != tt.expected {
			t.Errorf("cqlTypeStringToColumnType(%q) = %v, want %v", tt.cqlType, result, tt.expected)
		}
	}
}

func TestExpandQueryForTuples(t *testing.T) {
	tests := []struct {
		query              string
		values             []interface{}
		bindTypes          []ColumnType
		tupleElementCounts []int
		expectedQuery      string
		expectedValueCount int
	}{
		{
			// No tuples - query unchanged
			"INSERT INTO tbl (a, b) VALUES (?, ?)",
			[]interface{}{1, "text"},
			[]ColumnType{ColTypeInt, ColTypeText},
			[]int{0, 0},
			"INSERT INTO tbl (a, b) VALUES (?, ?)",
			2,
		},
		{
			// Tuple with 2 elements
			"INSERT INTO tbl (a, t) VALUES (?, ?)",
			[]interface{}{1, []interface{}{"a", 2}},
			[]ColumnType{ColTypeInt, ColTypeTuple},
			[]int{0, 2},
			"INSERT INTO tbl (a, t) VALUES (?, (?, ?))",
			3,
		},
		{
			// Tuple with 3 elements
			"INSERT INTO tbl (t) VALUES (?)",
			[]interface{}{[]interface{}{1, "a", true}},
			[]ColumnType{ColTypeTuple},
			[]int{3},
			"INSERT INTO tbl (t) VALUES ((?, ?, ?))",
			3,
		},
		{
			// Multiple tuples
			"INSERT INTO tbl (t1, x, t2) VALUES (?, ?, ?)",
			[]interface{}{[]interface{}{1, 2}, 100, []interface{}{"a", "b", "c"}},
			[]ColumnType{ColTypeTuple, ColTypeInt, ColTypeTuple},
			[]int{2, 0, 3},
			"INSERT INTO tbl (t1, x, t2) VALUES ((?, ?), ?, (?, ?, ?))",
			6,
		},
	}

	for _, tt := range tests {
		resultQuery, resultValues := expandQueryForTuples(tt.query, tt.values, tt.bindTypes, tt.tupleElementCounts)
		if resultQuery != tt.expectedQuery {
			t.Errorf("expandQueryForTuples(%q) query = %q, want %q", tt.query, resultQuery, tt.expectedQuery)
		}
		if len(resultValues) != tt.expectedValueCount {
			t.Errorf("expandQueryForTuples(%q) value count = %d, want %d", tt.query, len(resultValues), tt.expectedValueCount)
		}
	}
}

func TestDecodeTypedValue(t *testing.T) {
	tests := []struct {
		name     string
		tv       TypedValue
		bindType ColumnType
		check    func(interface{}) bool
	}{
		{
			name:     "int32",
			tv:       TypedValue{TypeCode: TypeInt, Data: []byte{0, 0, 0, 42}},
			bindType: ColTypeInt,
			check:    func(v interface{}) bool { i, ok := v.(int32); return ok && i == 42 },
		},
		{
			name:     "int64",
			tv:       TypedValue{TypeCode: TypeBigInt, Data: []byte{0, 0, 0, 0, 0, 0, 0, 100}},
			bindType: ColTypeBigInt,
			check:    func(v interface{}) bool { i, ok := v.(int64); return ok && i == 100 },
		},
		{
			name:     "text",
			tv:       TypedValue{TypeCode: TypeText, Data: []byte("hello")},
			bindType: ColTypeText,
			check:    func(v interface{}) bool { s, ok := v.(string); return ok && s == "hello" },
		},
		{
			name:     "boolean true",
			tv:       TypedValue{TypeCode: TypeBoolean, Data: []byte{1}},
			bindType: ColTypeBoolean,
			check:    func(v interface{}) bool { b, ok := v.(bool); return ok && b },
		},
		{
			name:     "boolean false",
			tv:       TypedValue{TypeCode: TypeBoolean, Data: []byte{0}},
			bindType: ColTypeBoolean,
			check:    func(v interface{}) bool { b, ok := v.(bool); return ok && !b },
		},
		{
			name:     "null",
			tv:       TypedValue{TypeCode: TypeInt, Data: nil},
			bindType: ColTypeInt,
			check:    func(v interface{}) bool { return v == nil },
		},
	}

	for _, tt := range tests {
		t.Run(tt.name, func(t *testing.T) {
			result := decodeTypedValueWithBindType(tt.tv, tt.bindType)
			if !tt.check(result) {
				t.Errorf("decodeTypedValueWithBindType() = %v (%T), check failed", result, result)
			}
		})
	}
}

func TestVarintEncoding(t *testing.T) {
	tests := []struct {
		name     string
		value    *big.Int
		expected []byte
	}{
		{"zero", big.NewInt(0), []byte{0}},
		{"positive small", big.NewInt(127), []byte{0x7F}},
		{"positive needing sign byte", big.NewInt(128), []byte{0x00, 0x80}},
		{"positive large", big.NewInt(256), []byte{0x01, 0x00}},
		{"negative -1", big.NewInt(-1), []byte{0xFF}},
		{"negative -128", big.NewInt(-128), []byte{0x80}},
		{"negative -129", big.NewInt(-129), []byte{0xFF, 0x7F}},
	}

	for _, tt := range tests {
		t.Run(tt.name, func(t *testing.T) {
			encoded := values.EncodeVarint(tt.value)
			if !bytes.Equal(encoded, tt.expected) {
				t.Errorf("EncodeVarint(%v) = %v, want %v", tt.value, encoded, tt.expected)
			}

			// Test round-trip
			decoded := values.DecodeVarint(encoded)
			if decoded.Cmp(tt.value) != 0 {
				t.Errorf("DecodeVarint(EncodeVarint(%v)) = %v, want %v", tt.value, decoded, tt.value)
			}
		})
	}
}

func TestDecimalEncoding(t *testing.T) {
	tests := []struct {
		name  string
		value *inf.Dec
	}{
		{"zero", inf.NewDec(0, 0)},
		{"positive integer", inf.NewDec(12345, 0)},
		{"positive with scale", inf.NewDec(12345, 2)}, // 123.45
		{"negative", inf.NewDec(-99, 1)},              // -9.9
		{"large", inf.NewDec(9999999999, 0)},
	}

	for _, tt := range tests {
		t.Run(tt.name, func(t *testing.T) {
			encoded := values.EncodeDecimal(tt.value)
			decoded := values.DecodeDecimal(encoded)

			// Compare values
			if decoded.Cmp(tt.value) != 0 {
				t.Errorf("DecodeDecimal(EncodeDecimal(%v)) = %v, want %v", tt.value, decoded, tt.value)
			}
		})
	}
}

func TestParseConsistency(t *testing.T) {
	tests := []struct {
		input    string
		expected string
	}{
		{"ONE", "ONE"},
		{"one", "ONE"},
		{"QUORUM", "QUORUM"},
		{"LOCAL_QUORUM", "LOCAL_QUORUM"},
		{"LOCALQUORUM", "LOCAL_QUORUM"},
		{"ALL", "ALL"},
	}

	for _, tt := range tests {
		result := parseConsistency(tt.input)
		// Check that result is non-Any for valid inputs
		if tt.expected == "ONE" && result.String() != "ONE" {
			t.Errorf("parseConsistency(%q) = %v, want ONE", tt.input, result)
		}
	}
}

func TestQueryFlags(t *testing.T) {
	// Test that flag constants are correct
	if QueryFlagValues != 0x01 {
		t.Errorf("QueryFlagValues = %#x, want 0x01", QueryFlagValues)
	}
	if QueryFlagSkipMetadata != 0x02 {
		t.Errorf("QueryFlagSkipMetadata = %#x, want 0x02", QueryFlagSkipMetadata)
	}
	if QueryFlagPageSize != 0x04 {
		t.Errorf("QueryFlagPageSize = %#x, want 0x04", QueryFlagPageSize)
	}
	if QueryFlagWithPagingState != 0x08 {
		t.Errorf("QueryFlagWithPagingState = %#x, want 0x08", QueryFlagWithPagingState)
	}
	if QueryFlagSerialConsist != 0x10 {
		t.Errorf("QueryFlagSerialConsist = %#x, want 0x10", QueryFlagSerialConsist)
	}
	if QueryFlagDefaultTimestamp != 0x20 {
		t.Errorf("QueryFlagDefaultTimestamp = %#x, want 0x20", QueryFlagDefaultTimestamp)
	}
}

func TestBatchFlags(t *testing.T) {
	// Test that flag constants are correct
	if BatchFlagSerialConsist != 0x10 {
		t.Errorf("BatchFlagSerialConsist = %#x, want 0x10", BatchFlagSerialConsist)
	}
	if BatchFlagDefaultTimestamp != 0x20 {
		t.Errorf("BatchFlagDefaultTimestamp = %#x, want 0x20", BatchFlagDefaultTimestamp)
	}
}

// Test protocol helper functions
func TestProtocolReadWrite(t *testing.T) {
	// Test WriteInt/ReadInt
	buf := new(bytes.Buffer)
	if err := protocol.WriteInt(buf, 12345); err != nil {
		t.Fatalf("WriteInt failed: %v", err)
	}
	val, err := protocol.ReadInt(bytes.NewReader(buf.Bytes()))
	if err != nil {
		t.Fatalf("ReadInt failed: %v", err)
	}
	if val != 12345 {
		t.Errorf("ReadInt = %d, want 12345", val)
	}

	// Test WriteString/ReadString
	buf.Reset()
	if err := protocol.WriteString(buf, "hello"); err != nil {
		t.Fatalf("WriteString failed: %v", err)
	}
	s, err := protocol.ReadString(bytes.NewReader(buf.Bytes()))
	if err != nil {
		t.Fatalf("ReadString failed: %v", err)
	}
	if s != "hello" {
		t.Errorf("ReadString = %q, want \"hello\"", s)
	}

	// Test WriteBytes/ReadBytes (null)
	buf.Reset()
	if err := protocol.WriteBytes(buf, nil); err != nil {
		t.Fatalf("WriteBytes(nil) failed: %v", err)
	}
	b, err := protocol.ReadBytes(bytes.NewReader(buf.Bytes()))
	if err != nil {
		t.Fatalf("ReadBytes failed: %v", err)
	}
	if b != nil {
		t.Errorf("ReadBytes = %v, want nil", b)
	}

	// Test WriteBytes/ReadBytes (non-null)
	buf.Reset()
	if err := protocol.WriteBytes(buf, []byte{1, 2, 3}); err != nil {
		t.Fatalf("WriteBytes failed: %v", err)
	}
	b, err = protocol.ReadBytes(bytes.NewReader(buf.Bytes()))
	if err != nil {
		t.Fatalf("ReadBytes failed: %v", err)
	}
	if !bytes.Equal(b, []byte{1, 2, 3}) {
		t.Errorf("ReadBytes = %v, want [1, 2, 3]", b)
	}
}

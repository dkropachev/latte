package encoding

import (
	"bytes"
	"strings"
	"testing"

	"github.com/aws/aws-sdk-go-v2/service/dynamodb/types"
	"github.com/scylladb/latte/alternator-adapters/alternator-client-golang/internal/protocol"
)

func TestWriteReadAttributeValue_Null(t *testing.T) {
	buf := protocol.NewBuffer(16)
	av := &types.AttributeValueMemberNULL{Value: true}

	if err := WriteAttributeValue(buf, av); err != nil {
		t.Fatalf("WriteAttributeValue: %v", err)
	}

	r := protocol.NewReader(buf.Bytes())
	result, err := ReadAttributeValue(r)
	if err != nil {
		t.Fatalf("ReadAttributeValue: %v", err)
	}

	if _, ok := result.(*types.AttributeValueMemberNULL); !ok {
		t.Errorf("Expected NULL type, got %T", result)
	}
}

func TestWriteReadAttributeValue_Bool(t *testing.T) {
	tests := []bool{true, false}

	for _, expected := range tests {
		buf := protocol.NewBuffer(16)
		av := &types.AttributeValueMemberBOOL{Value: expected}

		if err := WriteAttributeValue(buf, av); err != nil {
			t.Fatalf("WriteAttributeValue: %v", err)
		}

		r := protocol.NewReader(buf.Bytes())
		result, err := ReadAttributeValue(r)
		if err != nil {
			t.Fatalf("ReadAttributeValue: %v", err)
		}

		boolVal, ok := result.(*types.AttributeValueMemberBOOL)
		if !ok {
			t.Fatalf("Expected BOOL type, got %T", result)
		}
		if boolVal.Value != expected {
			t.Errorf("Value = %v, want %v", boolVal.Value, expected)
		}
	}
}

func TestWriteReadAttributeValue_Number(t *testing.T) {
	tests := []string{"0", "123", "-456", "3.14159", "-0.001", "1e10", "9999999999999999"}

	for _, expected := range tests {
		buf := protocol.NewBuffer(64)
		av := &types.AttributeValueMemberN{Value: expected}

		if err := WriteAttributeValue(buf, av); err != nil {
			t.Fatalf("WriteAttributeValue: %v", err)
		}

		r := protocol.NewReader(buf.Bytes())
		result, err := ReadAttributeValue(r)
		if err != nil {
			t.Fatalf("ReadAttributeValue: %v", err)
		}

		numVal, ok := result.(*types.AttributeValueMemberN)
		if !ok {
			t.Fatalf("Expected N type, got %T", result)
		}
		if numVal.Value != expected {
			t.Errorf("Value = %q, want %q", numVal.Value, expected)
		}
	}
}

func TestWriteReadAttributeValue_String(t *testing.T) {
	tests := []string{
		"",
		"hello",
		"Hello, 世界!",
		strings.Repeat("a", 1000),
		"line1\nline2\ttab",
		"\x00null\x00byte",
	}

	for _, expected := range tests {
		buf := protocol.NewBuffer(len(expected) + 16)
		av := &types.AttributeValueMemberS{Value: expected}

		if err := WriteAttributeValue(buf, av); err != nil {
			t.Fatalf("WriteAttributeValue: %v", err)
		}

		r := protocol.NewReader(buf.Bytes())
		result, err := ReadAttributeValue(r)
		if err != nil {
			t.Fatalf("ReadAttributeValue: %v", err)
		}

		strVal, ok := result.(*types.AttributeValueMemberS)
		if !ok {
			t.Fatalf("Expected S type, got %T", result)
		}
		if strVal.Value != expected {
			t.Errorf("Value = %q, want %q", strVal.Value, expected)
		}
	}
}

func TestWriteReadAttributeValue_LargeString(t *testing.T) {
	// Test with string > 64KB
	expected := strings.Repeat("x", 100000)
	buf := protocol.NewBuffer(len(expected) + 16)
	av := &types.AttributeValueMemberS{Value: expected}

	if err := WriteAttributeValue(buf, av); err != nil {
		t.Fatalf("WriteAttributeValue: %v", err)
	}

	r := protocol.NewReader(buf.Bytes())
	result, err := ReadAttributeValue(r)
	if err != nil {
		t.Fatalf("ReadAttributeValue: %v", err)
	}

	strVal, ok := result.(*types.AttributeValueMemberS)
	if !ok {
		t.Fatalf("Expected S type, got %T", result)
	}
	if strVal.Value != expected {
		t.Errorf("Large string length = %d, want %d", len(strVal.Value), len(expected))
	}
}

func TestWriteReadAttributeValue_Binary(t *testing.T) {
	tests := [][]byte{
		{},
		{0x00},
		{0xDE, 0xAD, 0xBE, 0xEF},
		bytes.Repeat([]byte{0xFF}, 1000),
	}

	for _, expected := range tests {
		buf := protocol.NewBuffer(len(expected) + 16)
		av := &types.AttributeValueMemberB{Value: expected}

		if err := WriteAttributeValue(buf, av); err != nil {
			t.Fatalf("WriteAttributeValue: %v", err)
		}

		r := protocol.NewReader(buf.Bytes())
		result, err := ReadAttributeValue(r)
		if err != nil {
			t.Fatalf("ReadAttributeValue: %v", err)
		}

		binVal, ok := result.(*types.AttributeValueMemberB)
		if !ok {
			t.Fatalf("Expected B type, got %T", result)
		}
		if !bytes.Equal(binVal.Value, expected) {
			t.Errorf("Binary mismatch: got %v, want %v", binVal.Value, expected)
		}
	}
}

func TestWriteReadAttributeValue_StringSet(t *testing.T) {
	tests := [][]string{
		{},
		{"one"},
		{"a", "b", "c"},
		{"Hello", "世界", "🎉"},
	}

	for _, expected := range tests {
		buf := protocol.NewBuffer(256)
		av := &types.AttributeValueMemberSS{Value: expected}

		if err := WriteAttributeValue(buf, av); err != nil {
			t.Fatalf("WriteAttributeValue: %v", err)
		}

		r := protocol.NewReader(buf.Bytes())
		result, err := ReadAttributeValue(r)
		if err != nil {
			t.Fatalf("ReadAttributeValue: %v", err)
		}

		ssVal, ok := result.(*types.AttributeValueMemberSS)
		if !ok {
			t.Fatalf("Expected SS type, got %T", result)
		}
		if len(ssVal.Value) != len(expected) {
			t.Errorf("StringSet length = %d, want %d", len(ssVal.Value), len(expected))
		}
		for i, s := range expected {
			if ssVal.Value[i] != s {
				t.Errorf("StringSet[%d] = %q, want %q", i, ssVal.Value[i], s)
			}
		}
	}
}

func TestWriteReadAttributeValue_NumberSet(t *testing.T) {
	tests := [][]string{
		{},
		{"0"},
		{"1", "2", "3"},
		{"-1", "0", "1", "3.14"},
	}

	for _, expected := range tests {
		buf := protocol.NewBuffer(256)
		av := &types.AttributeValueMemberNS{Value: expected}

		if err := WriteAttributeValue(buf, av); err != nil {
			t.Fatalf("WriteAttributeValue: %v", err)
		}

		r := protocol.NewReader(buf.Bytes())
		result, err := ReadAttributeValue(r)
		if err != nil {
			t.Fatalf("ReadAttributeValue: %v", err)
		}

		nsVal, ok := result.(*types.AttributeValueMemberNS)
		if !ok {
			t.Fatalf("Expected NS type, got %T", result)
		}
		if len(nsVal.Value) != len(expected) {
			t.Errorf("NumberSet length = %d, want %d", len(nsVal.Value), len(expected))
		}
		for i, n := range expected {
			if nsVal.Value[i] != n {
				t.Errorf("NumberSet[%d] = %q, want %q", i, nsVal.Value[i], n)
			}
		}
	}
}

func TestWriteReadAttributeValue_BinarySet(t *testing.T) {
	tests := [][][]byte{
		{},
		{{0x00}},
		{{0x01}, {0x02}, {0x03}},
		{{0xDE, 0xAD}, {0xBE, 0xEF}},
	}

	for _, expected := range tests {
		buf := protocol.NewBuffer(256)
		av := &types.AttributeValueMemberBS{Value: expected}

		if err := WriteAttributeValue(buf, av); err != nil {
			t.Fatalf("WriteAttributeValue: %v", err)
		}

		r := protocol.NewReader(buf.Bytes())
		result, err := ReadAttributeValue(r)
		if err != nil {
			t.Fatalf("ReadAttributeValue: %v", err)
		}

		bsVal, ok := result.(*types.AttributeValueMemberBS)
		if !ok {
			t.Fatalf("Expected BS type, got %T", result)
		}
		if len(bsVal.Value) != len(expected) {
			t.Errorf("BinarySet length = %d, want %d", len(bsVal.Value), len(expected))
		}
		for i, b := range expected {
			if !bytes.Equal(bsVal.Value[i], b) {
				t.Errorf("BinarySet[%d] mismatch", i)
			}
		}
	}
}

func TestWriteReadAttributeValue_List(t *testing.T) {
	// Empty list
	t.Run("Empty", func(t *testing.T) {
		buf := protocol.NewBuffer(16)
		av := &types.AttributeValueMemberL{Value: []types.AttributeValue{}}

		if err := WriteAttributeValue(buf, av); err != nil {
			t.Fatalf("WriteAttributeValue: %v", err)
		}

		r := protocol.NewReader(buf.Bytes())
		result, err := ReadAttributeValue(r)
		if err != nil {
			t.Fatalf("ReadAttributeValue: %v", err)
		}

		listVal, ok := result.(*types.AttributeValueMemberL)
		if !ok {
			t.Fatalf("Expected L type, got %T", result)
		}
		if len(listVal.Value) != 0 {
			t.Errorf("List length = %d, want 0", len(listVal.Value))
		}
	})

	// Mixed types
	t.Run("Mixed", func(t *testing.T) {
		buf := protocol.NewBuffer(256)
		av := &types.AttributeValueMemberL{
			Value: []types.AttributeValue{
				&types.AttributeValueMemberS{Value: "hello"},
				&types.AttributeValueMemberN{Value: "42"},
				&types.AttributeValueMemberBOOL{Value: true},
				&types.AttributeValueMemberNULL{Value: true},
			},
		}

		if err := WriteAttributeValue(buf, av); err != nil {
			t.Fatalf("WriteAttributeValue: %v", err)
		}

		r := protocol.NewReader(buf.Bytes())
		result, err := ReadAttributeValue(r)
		if err != nil {
			t.Fatalf("ReadAttributeValue: %v", err)
		}

		listVal, ok := result.(*types.AttributeValueMemberL)
		if !ok {
			t.Fatalf("Expected L type, got %T", result)
		}
		if len(listVal.Value) != 4 {
			t.Fatalf("List length = %d, want 4", len(listVal.Value))
		}

		// Verify types
		if _, ok := listVal.Value[0].(*types.AttributeValueMemberS); !ok {
			t.Errorf("List[0] expected S, got %T", listVal.Value[0])
		}
		if _, ok := listVal.Value[1].(*types.AttributeValueMemberN); !ok {
			t.Errorf("List[1] expected N, got %T", listVal.Value[1])
		}
		if _, ok := listVal.Value[2].(*types.AttributeValueMemberBOOL); !ok {
			t.Errorf("List[2] expected BOOL, got %T", listVal.Value[2])
		}
		if _, ok := listVal.Value[3].(*types.AttributeValueMemberNULL); !ok {
			t.Errorf("List[3] expected NULL, got %T", listVal.Value[3])
		}
	})
}

func TestWriteReadAttributeValue_Map(t *testing.T) {
	// Empty map
	t.Run("Empty", func(t *testing.T) {
		buf := protocol.NewBuffer(16)
		av := &types.AttributeValueMemberM{Value: map[string]types.AttributeValue{}}

		if err := WriteAttributeValue(buf, av); err != nil {
			t.Fatalf("WriteAttributeValue: %v", err)
		}

		r := protocol.NewReader(buf.Bytes())
		result, err := ReadAttributeValue(r)
		if err != nil {
			t.Fatalf("ReadAttributeValue: %v", err)
		}

		mapVal, ok := result.(*types.AttributeValueMemberM)
		if !ok {
			t.Fatalf("Expected M type, got %T", result)
		}
		if len(mapVal.Value) != 0 {
			t.Errorf("Map length = %d, want 0", len(mapVal.Value))
		}
	})

	// Non-empty map
	t.Run("NonEmpty", func(t *testing.T) {
		buf := protocol.NewBuffer(256)
		av := &types.AttributeValueMemberM{
			Value: map[string]types.AttributeValue{
				"name":    &types.AttributeValueMemberS{Value: "John"},
				"age":     &types.AttributeValueMemberN{Value: "30"},
				"active":  &types.AttributeValueMemberBOOL{Value: true},
			},
		}

		if err := WriteAttributeValue(buf, av); err != nil {
			t.Fatalf("WriteAttributeValue: %v", err)
		}

		r := protocol.NewReader(buf.Bytes())
		result, err := ReadAttributeValue(r)
		if err != nil {
			t.Fatalf("ReadAttributeValue: %v", err)
		}

		mapVal, ok := result.(*types.AttributeValueMemberM)
		if !ok {
			t.Fatalf("Expected M type, got %T", result)
		}
		if len(mapVal.Value) != 3 {
			t.Errorf("Map length = %d, want 3", len(mapVal.Value))
		}

		if s, ok := mapVal.Value["name"].(*types.AttributeValueMemberS); !ok || s.Value != "John" {
			t.Errorf("Map[name] incorrect")
		}
		if n, ok := mapVal.Value["age"].(*types.AttributeValueMemberN); !ok || n.Value != "30" {
			t.Errorf("Map[age] incorrect")
		}
		if b, ok := mapVal.Value["active"].(*types.AttributeValueMemberBOOL); !ok || !b.Value {
			t.Errorf("Map[active] incorrect")
		}
	})
}

func TestWriteReadAttributeValue_DeeplyNested(t *testing.T) {
	// Create a deeply nested structure (10+ levels)
	depth := 12
	var innermost types.AttributeValue = &types.AttributeValueMemberS{Value: "deep"}

	for i := 0; i < depth; i++ {
		if i%2 == 0 {
			innermost = &types.AttributeValueMemberL{
				Value: []types.AttributeValue{innermost},
			}
		} else {
			innermost = &types.AttributeValueMemberM{
				Value: map[string]types.AttributeValue{"nested": innermost},
			}
		}
	}

	buf := protocol.NewBuffer(1024)
	if err := WriteAttributeValue(buf, innermost); err != nil {
		t.Fatalf("WriteAttributeValue: %v", err)
	}

	r := protocol.NewReader(buf.Bytes())
	result, err := ReadAttributeValue(r)
	if err != nil {
		t.Fatalf("ReadAttributeValue: %v", err)
	}

	// Navigate down to verify the structure
	current := result
	for i := depth - 1; i >= 0; i-- {
		if i%2 == 0 {
			listVal, ok := current.(*types.AttributeValueMemberL)
			if !ok {
				t.Fatalf("Expected L at depth %d, got %T", i, current)
			}
			if len(listVal.Value) != 1 {
				t.Fatalf("List length at depth %d = %d, want 1", i, len(listVal.Value))
			}
			current = listVal.Value[0]
		} else {
			mapVal, ok := current.(*types.AttributeValueMemberM)
			if !ok {
				t.Fatalf("Expected M at depth %d, got %T", i, current)
			}
			nested, exists := mapVal.Value["nested"]
			if !exists {
				t.Fatalf("Missing 'nested' key at depth %d", i)
			}
			current = nested
		}
	}

	// Verify the innermost value
	strVal, ok := current.(*types.AttributeValueMemberS)
	if !ok {
		t.Fatalf("Innermost expected S, got %T", current)
	}
	if strVal.Value != "deep" {
		t.Errorf("Innermost value = %q, want %q", strVal.Value, "deep")
	}
}

func TestWriteReadKey(t *testing.T) {
	tests := []struct {
		name string
		key  map[string]types.AttributeValue
	}{
		{
			name: "SinglePartition",
			key: map[string]types.AttributeValue{
				"pk": &types.AttributeValueMemberS{Value: "user123"},
			},
		},
		{
			name: "CompositeKey",
			key: map[string]types.AttributeValue{
				"pk": &types.AttributeValueMemberS{Value: "user123"},
				"sk": &types.AttributeValueMemberN{Value: "1704067200"},
			},
		},
	}

	for _, tt := range tests {
		t.Run(tt.name, func(t *testing.T) {
			buf := protocol.NewBuffer(256)
			if err := WriteKey(buf, tt.key); err != nil {
				t.Fatalf("WriteKey: %v", err)
			}

			r := protocol.NewReader(buf.Bytes())
			result, err := ReadKey(r)
			if err != nil {
				t.Fatalf("ReadKey: %v", err)
			}

			if len(result) != len(tt.key) {
				t.Errorf("Key length = %d, want %d", len(result), len(tt.key))
			}

			for k, v := range tt.key {
				resultVal, exists := result[k]
				if !exists {
					t.Errorf("Key %q not found in result", k)
					continue
				}

				switch expected := v.(type) {
				case *types.AttributeValueMemberS:
					actual, ok := resultVal.(*types.AttributeValueMemberS)
					if !ok || actual.Value != expected.Value {
						t.Errorf("Key %q: value mismatch", k)
					}
				case *types.AttributeValueMemberN:
					actual, ok := resultVal.(*types.AttributeValueMemberN)
					if !ok || actual.Value != expected.Value {
						t.Errorf("Key %q: value mismatch", k)
					}
				}
			}
		})
	}
}

func TestWriteReadItem(t *testing.T) {
	item := map[string]types.AttributeValue{
		"id":       &types.AttributeValueMemberS{Value: "item123"},
		"count":    &types.AttributeValueMemberN{Value: "42"},
		"active":   &types.AttributeValueMemberBOOL{Value: true},
		"data":     &types.AttributeValueMemberB{Value: []byte{0x01, 0x02, 0x03}},
		"tags":     &types.AttributeValueMemberSS{Value: []string{"a", "b"}},
		"metadata": &types.AttributeValueMemberM{Value: map[string]types.AttributeValue{
			"created": &types.AttributeValueMemberS{Value: "2024-01-01"},
		}},
	}

	buf := protocol.NewBuffer(1024)
	if err := WriteItem(buf, item); err != nil {
		t.Fatalf("WriteItem: %v", err)
	}

	r := protocol.NewReader(buf.Bytes())
	result, err := ReadItem(r)
	if err != nil {
		t.Fatalf("ReadItem: %v", err)
	}

	if len(result) != len(item) {
		t.Errorf("Item length = %d, want %d", len(result), len(item))
	}

	// Verify id
	if s, ok := result["id"].(*types.AttributeValueMemberS); !ok || s.Value != "item123" {
		t.Errorf("Item[id] incorrect")
	}

	// Verify count
	if n, ok := result["count"].(*types.AttributeValueMemberN); !ok || n.Value != "42" {
		t.Errorf("Item[count] incorrect")
	}

	// Verify nested metadata
	if m, ok := result["metadata"].(*types.AttributeValueMemberM); ok {
		if created, ok := m.Value["created"].(*types.AttributeValueMemberS); !ok || created.Value != "2024-01-01" {
			t.Errorf("Item[metadata][created] incorrect")
		}
	} else {
		t.Errorf("Item[metadata] expected M type")
	}
}

func TestWriteReadOptionalKey(t *testing.T) {
	// Test nil key
	t.Run("Nil", func(t *testing.T) {
		buf := protocol.NewBuffer(16)
		if err := WriteOptionalKey(buf, nil); err != nil {
			t.Fatalf("WriteOptionalKey: %v", err)
		}

		r := protocol.NewReader(buf.Bytes())
		result, err := ReadOptionalKey(r)
		if err != nil {
			t.Fatalf("ReadOptionalKey: %v", err)
		}

		if result != nil {
			t.Errorf("Expected nil, got %v", result)
		}
	})

	// Test present key
	t.Run("Present", func(t *testing.T) {
		key := map[string]types.AttributeValue{
			"pk": &types.AttributeValueMemberS{Value: "test"},
		}

		buf := protocol.NewBuffer(64)
		if err := WriteOptionalKey(buf, key); err != nil {
			t.Fatalf("WriteOptionalKey: %v", err)
		}

		r := protocol.NewReader(buf.Bytes())
		result, err := ReadOptionalKey(r)
		if err != nil {
			t.Fatalf("ReadOptionalKey: %v", err)
		}

		if result == nil {
			t.Fatal("Expected non-nil key")
		}
		if len(result) != 1 {
			t.Errorf("Key length = %d, want 1", len(result))
		}
	})
}

func TestWriteReadOptionalItem(t *testing.T) {
	// Test nil item
	t.Run("Nil", func(t *testing.T) {
		buf := protocol.NewBuffer(16)
		if err := WriteOptionalItem(buf, nil); err != nil {
			t.Fatalf("WriteOptionalItem: %v", err)
		}

		data := buf.Bytes()
		if len(data) != 1 || data[0] != 0x00 {
			t.Errorf("Expected [0x00], got %v", data)
		}
	})

	// Test present item
	t.Run("Present", func(t *testing.T) {
		item := map[string]types.AttributeValue{
			"id": &types.AttributeValueMemberS{Value: "test"},
		}

		buf := protocol.NewBuffer(64)
		if err := WriteOptionalItem(buf, item); err != nil {
			t.Fatalf("WriteOptionalItem: %v", err)
		}

		data := buf.Bytes()
		if data[0] != 0x01 {
			t.Errorf("Expected present byte 0x01, got 0x%02x", data[0])
		}
	})
}

func TestReadExprAttrNames(t *testing.T) {
	// Build test data: u16 count + pairs of strings
	buf := protocol.NewBuffer(128)
	buf.WriteUint16(2)
	buf.WriteString("#n")
	buf.WriteString("name")
	buf.WriteString("#a")
	buf.WriteString("age")

	r := protocol.NewReader(buf.Bytes())
	result, err := ReadExprAttrNames(r)
	if err != nil {
		t.Fatalf("ReadExprAttrNames: %v", err)
	}

	if len(result) != 2 {
		t.Errorf("Length = %d, want 2", len(result))
	}
	if result["#n"] != "name" {
		t.Errorf("#n = %q, want %q", result["#n"], "name")
	}
	if result["#a"] != "age" {
		t.Errorf("#a = %q, want %q", result["#a"], "age")
	}
}

func TestReadExprAttrNames_Empty(t *testing.T) {
	buf := protocol.NewBuffer(16)
	buf.WriteUint16(0)

	r := protocol.NewReader(buf.Bytes())
	result, err := ReadExprAttrNames(r)
	if err != nil {
		t.Fatalf("ReadExprAttrNames: %v", err)
	}

	if result != nil {
		t.Errorf("Expected nil for empty, got %v", result)
	}
}

func TestReadExprAttrValues(t *testing.T) {
	buf := protocol.NewBuffer(128)
	buf.WriteUint16(2)
	buf.WriteString(":v")
	WriteAttributeValue(buf, &types.AttributeValueMemberS{Value: "John"})
	buf.WriteString(":n")
	WriteAttributeValue(buf, &types.AttributeValueMemberN{Value: "30"})

	r := protocol.NewReader(buf.Bytes())
	result, err := ReadExprAttrValues(r)
	if err != nil {
		t.Fatalf("ReadExprAttrValues: %v", err)
	}

	if len(result) != 2 {
		t.Errorf("Length = %d, want 2", len(result))
	}

	if s, ok := result[":v"].(*types.AttributeValueMemberS); !ok || s.Value != "John" {
		t.Errorf(":v incorrect")
	}
	if n, ok := result[":n"].(*types.AttributeValueMemberN); !ok || n.Value != "30" {
		t.Errorf(":n incorrect")
	}
}

func TestReadOptionalConditionExpression(t *testing.T) {
	// Test absent
	t.Run("Absent", func(t *testing.T) {
		buf := protocol.NewBuffer(16)
		buf.WriteByte(0x00)

		r := protocol.NewReader(buf.Bytes())
		result, err := ReadOptionalConditionExpression(r)
		if err != nil {
			t.Fatalf("ReadOptionalConditionExpression: %v", err)
		}

		if result != nil {
			t.Errorf("Expected nil, got %v", result)
		}
	})

	// Test present
	t.Run("Present", func(t *testing.T) {
		buf := protocol.NewBuffer(256)
		buf.WriteByte(0x01)
		buf.WriteString("attribute_exists(#n)")
		// ExprAttrNames
		buf.WriteUint16(1)
		buf.WriteString("#n")
		buf.WriteString("name")
		// ExprAttrValues
		buf.WriteUint16(0)

		r := protocol.NewReader(buf.Bytes())
		result, err := ReadOptionalConditionExpression(r)
		if err != nil {
			t.Fatalf("ReadOptionalConditionExpression: %v", err)
		}

		if result == nil {
			t.Fatal("Expected non-nil ConditionExpression")
		}
		if result.Expression != "attribute_exists(#n)" {
			t.Errorf("Expression = %q, want %q", result.Expression, "attribute_exists(#n)")
		}
		if result.ExprAttrNames["#n"] != "name" {
			t.Errorf("ExprAttrNames[#n] = %q, want %q", result.ExprAttrNames["#n"], "name")
		}
	})
}

func TestTypeTagValues(t *testing.T) {
	// Verify type tag constants match spec
	expected := map[string]uint8{
		"NULL":      0x00,
		"BOOL":      0x01,
		"NUMBER":    0x02,
		"STRING":    0x03,
		"BINARY":    0x04,
		"STRINGSET": 0x05,
		"NUMBERSET": 0x06,
		"BINARYSET": 0x07,
		"LIST":      0x08,
		"MAP":       0x09,
	}

	actual := map[string]uint8{
		"NULL":      TypeNull,
		"BOOL":      TypeBool,
		"NUMBER":    TypeNumber,
		"STRING":    TypeString,
		"BINARY":    TypeBinary,
		"STRINGSET": TypeStringSet,
		"NUMBERSET": TypeNumberSet,
		"BINARYSET": TypeBinarySet,
		"LIST":      TypeList,
		"MAP":       TypeMap,
	}

	for name, exp := range expected {
		if actual[name] != exp {
			t.Errorf("Type%s = 0x%02x, want 0x%02x", name, actual[name], exp)
		}
	}
}

func TestReadAttributeValue_UnknownTypeTag(t *testing.T) {
	buf := protocol.NewBuffer(16)
	buf.WriteUint8(0xFF) // Unknown type tag

	r := protocol.NewReader(buf.Bytes())
	_, err := ReadAttributeValue(r)
	if err == nil {
		t.Error("Expected error for unknown type tag")
	}
	if !strings.Contains(err.Error(), "unknown AttributeValue type tag") {
		t.Errorf("Error = %q, want to contain 'unknown AttributeValue type tag'", err.Error())
	}
}

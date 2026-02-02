// Package encoding handles DynamoDB AttributeValue encoding/decoding.
package encoding

import (
	"fmt"

	"github.com/aws/aws-sdk-go-v2/service/dynamodb/types"
	"github.com/scylladb/latte/alternator-adapters/alternator-client-golang/internal/protocol"
)

// AttributeValue type tags
const (
	TypeNull      = 0x00
	TypeBool      = 0x01
	TypeNumber    = 0x02
	TypeString    = 0x03
	TypeBinary    = 0x04
	TypeStringSet = 0x05
	TypeNumberSet = 0x06
	TypeBinarySet = 0x07
	TypeList      = 0x08
	TypeMap       = 0x09
)

// WriteAttributeValue encodes a DynamoDB AttributeValue to the buffer.
func WriteAttributeValue(buf *protocol.Buffer, av types.AttributeValue) error {
	switch v := av.(type) {
	case *types.AttributeValueMemberNULL:
		buf.WriteUint8(TypeNull)

	case *types.AttributeValueMemberBOOL:
		buf.WriteUint8(TypeBool)
		buf.WriteBool(v.Value)

	case *types.AttributeValueMemberN:
		buf.WriteUint8(TypeNumber)
		buf.WriteString(v.Value)

	case *types.AttributeValueMemberS:
		buf.WriteUint8(TypeString)
		buf.WriteString(v.Value)

	case *types.AttributeValueMemberB:
		buf.WriteUint8(TypeBinary)
		buf.WriteBytes(v.Value)

	case *types.AttributeValueMemberSS:
		buf.WriteUint8(TypeStringSet)
		buf.WriteUint32(uint32(len(v.Value)))
		for _, s := range v.Value {
			buf.WriteString(s)
		}

	case *types.AttributeValueMemberNS:
		buf.WriteUint8(TypeNumberSet)
		buf.WriteUint32(uint32(len(v.Value)))
		for _, n := range v.Value {
			buf.WriteString(n)
		}

	case *types.AttributeValueMemberBS:
		buf.WriteUint8(TypeBinarySet)
		buf.WriteUint32(uint32(len(v.Value)))
		for _, b := range v.Value {
			buf.WriteBytes(b)
		}

	case *types.AttributeValueMemberL:
		buf.WriteUint8(TypeList)
		buf.WriteUint32(uint32(len(v.Value)))
		for _, item := range v.Value {
			if err := WriteAttributeValue(buf, item); err != nil {
				return err
			}
		}

	case *types.AttributeValueMemberM:
		buf.WriteUint8(TypeMap)
		buf.WriteUint32(uint32(len(v.Value)))
		for k, val := range v.Value {
			buf.WriteString(k)
			if err := WriteAttributeValue(buf, val); err != nil {
				return err
			}
		}

	default:
		return fmt.Errorf("unsupported AttributeValue type: %T", av)
	}

	return nil
}

// ReadAttributeValue decodes a DynamoDB AttributeValue from the reader.
func ReadAttributeValue(r *protocol.Reader) (types.AttributeValue, error) {
	typeTag, err := r.ReadUint8()
	if err != nil {
		return nil, err
	}

	switch typeTag {
	case TypeNull:
		return &types.AttributeValueMemberNULL{Value: true}, nil

	case TypeBool:
		v, err := r.ReadBool()
		if err != nil {
			return nil, err
		}
		return &types.AttributeValueMemberBOOL{Value: v}, nil

	case TypeNumber:
		v, err := r.ReadString()
		if err != nil {
			return nil, err
		}
		return &types.AttributeValueMemberN{Value: v}, nil

	case TypeString:
		v, err := r.ReadString()
		if err != nil {
			return nil, err
		}
		return &types.AttributeValueMemberS{Value: v}, nil

	case TypeBinary:
		v, err := r.ReadBytes()
		if err != nil {
			return nil, err
		}
		return &types.AttributeValueMemberB{Value: v}, nil

	case TypeStringSet:
		count, err := r.ReadUint32()
		if err != nil {
			return nil, err
		}
		ss := make([]string, count)
		for i := uint32(0); i < count; i++ {
			ss[i], err = r.ReadString()
			if err != nil {
				return nil, err
			}
		}
		return &types.AttributeValueMemberSS{Value: ss}, nil

	case TypeNumberSet:
		count, err := r.ReadUint32()
		if err != nil {
			return nil, err
		}
		ns := make([]string, count)
		for i := uint32(0); i < count; i++ {
			ns[i], err = r.ReadString()
			if err != nil {
				return nil, err
			}
		}
		return &types.AttributeValueMemberNS{Value: ns}, nil

	case TypeBinarySet:
		count, err := r.ReadUint32()
		if err != nil {
			return nil, err
		}
		bs := make([][]byte, count)
		for i := uint32(0); i < count; i++ {
			bs[i], err = r.ReadBytes()
			if err != nil {
				return nil, err
			}
		}
		return &types.AttributeValueMemberBS{Value: bs}, nil

	case TypeList:
		count, err := r.ReadUint32()
		if err != nil {
			return nil, err
		}
		list := make([]types.AttributeValue, count)
		for i := uint32(0); i < count; i++ {
			list[i], err = ReadAttributeValue(r)
			if err != nil {
				return nil, err
			}
		}
		return &types.AttributeValueMemberL{Value: list}, nil

	case TypeMap:
		count, err := r.ReadUint32()
		if err != nil {
			return nil, err
		}
		m := make(map[string]types.AttributeValue, count)
		for i := uint32(0); i < count; i++ {
			key, err := r.ReadString()
			if err != nil {
				return nil, err
			}
			val, err := ReadAttributeValue(r)
			if err != nil {
				return nil, err
			}
			m[key] = val
		}
		return &types.AttributeValueMemberM{Value: m}, nil

	default:
		return nil, fmt.Errorf("unknown AttributeValue type tag: 0x%02x", typeTag)
	}
}

// WriteKey encodes a DynamoDB key (map with u16 count).
func WriteKey(buf *protocol.Buffer, key map[string]types.AttributeValue) error {
	buf.WriteUint16(uint16(len(key)))
	for k, v := range key {
		buf.WriteString(k)
		if err := WriteAttributeValue(buf, v); err != nil {
			return err
		}
	}
	return nil
}

// ReadKey decodes a DynamoDB key (map with u16 count).
func ReadKey(r *protocol.Reader) (map[string]types.AttributeValue, error) {
	count, err := r.ReadUint16()
	if err != nil {
		return nil, err
	}
	key := make(map[string]types.AttributeValue, count)
	for i := uint16(0); i < count; i++ {
		name, err := r.ReadString()
		if err != nil {
			return nil, err
		}
		val, err := ReadAttributeValue(r)
		if err != nil {
			return nil, err
		}
		key[name] = val
	}
	return key, nil
}

// WriteItem encodes a DynamoDB item (map with u32 count).
func WriteItem(buf *protocol.Buffer, item map[string]types.AttributeValue) error {
	buf.WriteUint32(uint32(len(item)))
	for k, v := range item {
		buf.WriteString(k)
		if err := WriteAttributeValue(buf, v); err != nil {
			return err
		}
	}
	return nil
}

// ReadItem decodes a DynamoDB item (map with u32 count).
func ReadItem(r *protocol.Reader) (map[string]types.AttributeValue, error) {
	count, err := r.ReadUint32()
	if err != nil {
		return nil, err
	}
	item := make(map[string]types.AttributeValue, count)
	for i := uint32(0); i < count; i++ {
		name, err := r.ReadString()
		if err != nil {
			return nil, err
		}
		val, err := ReadAttributeValue(r)
		if err != nil {
			return nil, err
		}
		item[name] = val
	}
	return item, nil
}

// WriteOptionalKey encodes an optional key.
func WriteOptionalKey(buf *protocol.Buffer, key map[string]types.AttributeValue) error {
	if key == nil {
		buf.WriteByte(0x00)
		return nil
	}
	buf.WriteByte(0x01)
	return WriteKey(buf, key)
}

// ReadOptionalKey decodes an optional key.
func ReadOptionalKey(r *protocol.Reader) (map[string]types.AttributeValue, error) {
	present, err := r.ReadByte()
	if err != nil {
		return nil, err
	}
	if present == 0x00 {
		return nil, nil
	}
	return ReadKey(r)
}

// WriteOptionalItem encodes an optional item.
func WriteOptionalItem(buf *protocol.Buffer, item map[string]types.AttributeValue) error {
	if item == nil {
		buf.WriteByte(0x00)
		return nil
	}
	buf.WriteByte(0x01)
	return WriteItem(buf, item)
}

// ReadExprAttrNames reads expression attribute names (u16 count + pairs).
func ReadExprAttrNames(r *protocol.Reader) (map[string]string, error) {
	count, err := r.ReadUint16()
	if err != nil {
		return nil, err
	}
	if count == 0 {
		return nil, nil
	}
	names := make(map[string]string, count)
	for i := uint16(0); i < count; i++ {
		placeholder, err := r.ReadString()
		if err != nil {
			return nil, err
		}
		attrName, err := r.ReadString()
		if err != nil {
			return nil, err
		}
		names[placeholder] = attrName
	}
	return names, nil
}

// ReadExprAttrValues reads expression attribute values (u16 count + pairs).
func ReadExprAttrValues(r *protocol.Reader) (map[string]types.AttributeValue, error) {
	count, err := r.ReadUint16()
	if err != nil {
		return nil, err
	}
	if count == 0 {
		return nil, nil
	}
	values := make(map[string]types.AttributeValue, count)
	for i := uint16(0); i < count; i++ {
		placeholder, err := r.ReadString()
		if err != nil {
			return nil, err
		}
		val, err := ReadAttributeValue(r)
		if err != nil {
			return nil, err
		}
		values[placeholder] = val
	}
	return values, nil
}

// ConditionExpression holds a condition expression with attribute names and values.
type ConditionExpression struct {
	Expression      string
	ExprAttrNames   map[string]string
	ExprAttrValues  map[string]types.AttributeValue
}

// ReadOptionalConditionExpression reads an optional condition expression.
func ReadOptionalConditionExpression(r *protocol.Reader) (*ConditionExpression, error) {
	present, err := r.ReadByte()
	if err != nil {
		return nil, err
	}
	if present == 0x00 {
		return nil, nil
	}

	expr, err := r.ReadString()
	if err != nil {
		return nil, err
	}
	names, err := ReadExprAttrNames(r)
	if err != nil {
		return nil, err
	}
	values, err := ReadExprAttrValues(r)
	if err != nil {
		return nil, err
	}

	return &ConditionExpression{
		Expression:     expr,
		ExprAttrNames:  names,
		ExprAttrValues: values,
	}, nil
}

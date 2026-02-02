package protocol

import (
	"encoding/binary"
	"errors"
	"fmt"
	"io"
	"math"
)

var (
	ErrUnexpectedEOF = errors.New("unexpected EOF")
	ErrStringTooLong = errors.New("string too long for short length")
)

// ReadShort reads a 2-byte big-endian unsigned integer.
func ReadShort(r io.Reader) (uint16, error) {
	var buf [2]byte
	if _, err := io.ReadFull(r, buf[:]); err != nil {
		return 0, err
	}
	return binary.BigEndian.Uint16(buf[:]), nil
}

// ReadInt reads a 4-byte big-endian signed integer.
func ReadInt(r io.Reader) (int32, error) {
	var buf [4]byte
	if _, err := io.ReadFull(r, buf[:]); err != nil {
		return 0, err
	}
	return int32(binary.BigEndian.Uint32(buf[:])), nil
}

// ReadLong reads an 8-byte big-endian unsigned integer.
func ReadLong(r io.Reader) (uint64, error) {
	var buf [8]byte
	if _, err := io.ReadFull(r, buf[:]); err != nil {
		return 0, err
	}
	return binary.BigEndian.Uint64(buf[:]), nil
}

// ReadString reads a [short] length prefixed UTF-8 string.
func ReadString(r io.Reader) (string, error) {
	length, err := ReadShort(r)
	if err != nil {
		return "", err
	}
	if length == 0 {
		return "", nil
	}
	buf := make([]byte, length)
	if _, err := io.ReadFull(r, buf); err != nil {
		return "", err
	}
	return string(buf), nil
}

// ReadLongString reads a [int] length prefixed UTF-8 string.
func ReadLongString(r io.Reader) (string, error) {
	length, err := ReadInt(r)
	if err != nil {
		return "", err
	}
	if length <= 0 {
		return "", nil
	}
	buf := make([]byte, length)
	if _, err := io.ReadFull(r, buf); err != nil {
		return "", err
	}
	return string(buf), nil
}

// ReadBytes reads a [int] length prefixed byte slice.
// Returns nil if length is negative (null).
func ReadBytes(r io.Reader) ([]byte, error) {
	length, err := ReadInt(r)
	if err != nil {
		return nil, err
	}
	if length < 0 {
		return nil, nil // null value
	}
	if length == 0 {
		return []byte{}, nil
	}
	buf := make([]byte, length)
	if _, err := io.ReadFull(r, buf); err != nil {
		return nil, err
	}
	return buf, nil
}

// ReadShortBytes reads a [short] length prefixed byte slice.
func ReadShortBytes(r io.Reader) ([]byte, error) {
	length, err := ReadShort(r)
	if err != nil {
		return nil, err
	}
	if length == 0 {
		return []byte{}, nil
	}
	buf := make([]byte, length)
	if _, err := io.ReadFull(r, buf); err != nil {
		return nil, err
	}
	return buf, nil
}

// ReadStringMap reads a [short] count prefixed map of [string] keys to [string] values.
func ReadStringMap(r io.Reader) (map[string]string, error) {
	count, err := ReadShort(r)
	if err != nil {
		return nil, err
	}
	m := make(map[string]string, count)
	for i := uint16(0); i < count; i++ {
		key, err := ReadString(r)
		if err != nil {
			return nil, err
		}
		value, err := ReadString(r)
		if err != nil {
			return nil, err
		}
		m[key] = value
	}
	return m, nil
}

// ReadByte reads a single byte.
func ReadByte(r io.Reader) (byte, error) {
	var buf [1]byte
	if _, err := io.ReadFull(r, buf[:]); err != nil {
		return 0, err
	}
	return buf[0], nil
}

// WriteShort writes a 2-byte big-endian unsigned integer.
func WriteShort(w io.Writer, v uint16) error {
	var buf [2]byte
	binary.BigEndian.PutUint16(buf[:], v)
	_, err := w.Write(buf[:])
	return err
}

// WriteInt writes a 4-byte big-endian signed integer.
func WriteInt(w io.Writer, v int32) error {
	var buf [4]byte
	binary.BigEndian.PutUint32(buf[:], uint32(v))
	_, err := w.Write(buf[:])
	return err
}

// WriteLong writes an 8-byte big-endian unsigned integer.
func WriteLong(w io.Writer, v uint64) error {
	var buf [8]byte
	binary.BigEndian.PutUint64(buf[:], v)
	_, err := w.Write(buf[:])
	return err
}

// WriteString writes a [short] length prefixed UTF-8 string.
func WriteString(w io.Writer, s string) error {
	if len(s) > 65535 {
		return ErrStringTooLong
	}
	if err := WriteShort(w, uint16(len(s))); err != nil {
		return err
	}
	if len(s) > 0 {
		_, err := w.Write([]byte(s))
		return err
	}
	return nil
}

// WriteLongString writes a [int] length prefixed UTF-8 string.
func WriteLongString(w io.Writer, s string) error {
	if len(s) > math.MaxInt32 {
		return fmt.Errorf("string too long for CQL protocol: %d bytes (max %d)", len(s), math.MaxInt32)
	}
	if err := WriteInt(w, int32(len(s))); err != nil {
		return err
	}
	if len(s) > 0 {
		_, err := w.Write([]byte(s))
		return err
	}
	return nil
}

// WriteBytes writes a [int] length prefixed byte slice.
// Writes -1 length for nil (null).
func WriteBytes(w io.Writer, b []byte) error {
	if b == nil {
		return WriteInt(w, -1)
	}
	if err := WriteInt(w, int32(len(b))); err != nil {
		return err
	}
	if len(b) > 0 {
		_, err := w.Write(b)
		return err
	}
	return nil
}

// WriteShortBytes writes a [short] length prefixed byte slice.
func WriteShortBytes(w io.Writer, b []byte) error {
	if err := WriteShort(w, uint16(len(b))); err != nil {
		return err
	}
	if len(b) > 0 {
		_, err := w.Write(b)
		return err
	}
	return nil
}

// WriteByte writes a single byte.
func WriteByte(w io.Writer, b byte) error {
	_, err := w.Write([]byte{b})
	return err
}

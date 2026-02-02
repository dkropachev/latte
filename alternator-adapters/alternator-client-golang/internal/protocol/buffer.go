package protocol

import (
	"encoding/binary"
	"errors"
	"io"
)

// Buffer is a helper for encoding protocol messages.
type Buffer struct {
	data []byte
}

// NewBuffer creates a new buffer with the given initial capacity.
func NewBuffer(capacity int) *Buffer {
	return &Buffer{data: make([]byte, 0, capacity)}
}

// Bytes returns the buffer contents.
func (b *Buffer) Bytes() []byte {
	return b.data
}

// Len returns the current length of the buffer.
func (b *Buffer) Len() int {
	return len(b.data)
}

// Reset clears the buffer.
func (b *Buffer) Reset() {
	b.data = b.data[:0]
}

// WriteByte writes a single byte.
func (b *Buffer) WriteByte(v byte) {
	b.data = append(b.data, v)
}

// WriteUint8 writes a uint8.
func (b *Buffer) WriteUint8(v uint8) {
	b.data = append(b.data, v)
}

// WriteUint16 writes a uint16 in big-endian.
func (b *Buffer) WriteUint16(v uint16) {
	b.data = append(b.data, byte(v>>8), byte(v))
}

// WriteUint32 writes a uint32 in big-endian.
func (b *Buffer) WriteUint32(v uint32) {
	b.data = append(b.data, byte(v>>24), byte(v>>16), byte(v>>8), byte(v))
}

// WriteUint64 writes a uint64 in big-endian.
func (b *Buffer) WriteUint64(v uint64) {
	b.data = append(b.data,
		byte(v>>56), byte(v>>48), byte(v>>40), byte(v>>32),
		byte(v>>24), byte(v>>16), byte(v>>8), byte(v))
}

// WriteInt64 writes an int64 in big-endian.
func (b *Buffer) WriteInt64(v int64) {
	b.WriteUint64(uint64(v))
}

// WriteBool writes a boolean as a single byte.
func (b *Buffer) WriteBool(v bool) {
	if v {
		b.data = append(b.data, 0x01)
	} else {
		b.data = append(b.data, 0x00)
	}
}

// WriteBytes writes a byte slice with u32 length prefix.
func (b *Buffer) WriteBytes(v []byte) {
	b.WriteUint32(uint32(len(v)))
	b.data = append(b.data, v...)
}

// WriteString writes a string with u32 length prefix.
func (b *Buffer) WriteString(v string) {
	b.WriteBytes([]byte(v))
}

// WriteOptionalString writes an optional string (0x00 absent, 0x01 + string present).
func (b *Buffer) WriteOptionalString(v *string) {
	if v == nil {
		b.WriteByte(0x00)
	} else {
		b.WriteByte(0x01)
		b.WriteString(*v)
	}
}

// WriteOptionalUint32 writes an optional uint32.
func (b *Buffer) WriteOptionalUint32(v *uint32) {
	if v == nil {
		b.WriteByte(0x00)
	} else {
		b.WriteByte(0x01)
		b.WriteUint32(*v)
	}
}

// WriteRaw writes raw bytes without length prefix.
func (b *Buffer) WriteRaw(v []byte) {
	b.data = append(b.data, v...)
}

// Reader is a helper for decoding protocol messages.
type Reader struct {
	data []byte
	pos  int
}

// NewReader creates a new reader from a byte slice.
func NewReader(data []byte) *Reader {
	return &Reader{data: data, pos: 0}
}

// Remaining returns the number of unread bytes.
func (r *Reader) Remaining() int {
	return len(r.data) - r.pos
}

// ReadByte reads a single byte.
func (r *Reader) ReadByte() (byte, error) {
	if r.pos >= len(r.data) {
		return 0, io.ErrUnexpectedEOF
	}
	v := r.data[r.pos]
	r.pos++
	return v, nil
}

// ReadUint8 reads a uint8.
func (r *Reader) ReadUint8() (uint8, error) {
	return r.ReadByte()
}

// ReadUint16 reads a uint16 in big-endian.
func (r *Reader) ReadUint16() (uint16, error) {
	if r.pos+2 > len(r.data) {
		return 0, io.ErrUnexpectedEOF
	}
	v := binary.BigEndian.Uint16(r.data[r.pos:])
	r.pos += 2
	return v, nil
}

// ReadUint32 reads a uint32 in big-endian.
func (r *Reader) ReadUint32() (uint32, error) {
	if r.pos+4 > len(r.data) {
		return 0, io.ErrUnexpectedEOF
	}
	v := binary.BigEndian.Uint32(r.data[r.pos:])
	r.pos += 4
	return v, nil
}

// ReadUint64 reads a uint64 in big-endian.
func (r *Reader) ReadUint64() (uint64, error) {
	if r.pos+8 > len(r.data) {
		return 0, io.ErrUnexpectedEOF
	}
	v := binary.BigEndian.Uint64(r.data[r.pos:])
	r.pos += 8
	return v, nil
}

// ReadInt64 reads an int64 in big-endian.
func (r *Reader) ReadInt64() (int64, error) {
	v, err := r.ReadUint64()
	return int64(v), err
}

// ReadBool reads a boolean.
func (r *Reader) ReadBool() (bool, error) {
	b, err := r.ReadByte()
	if err != nil {
		return false, err
	}
	return b != 0x00, nil
}

// ReadBytes reads a byte slice with u32 length prefix.
func (r *Reader) ReadBytes() ([]byte, error) {
	length, err := r.ReadUint32()
	if err != nil {
		return nil, err
	}
	if r.pos+int(length) > len(r.data) {
		return nil, io.ErrUnexpectedEOF
	}
	v := make([]byte, length)
	copy(v, r.data[r.pos:r.pos+int(length)])
	r.pos += int(length)
	return v, nil
}

// ReadString reads a string with u32 length prefix.
func (r *Reader) ReadString() (string, error) {
	b, err := r.ReadBytes()
	if err != nil {
		return "", err
	}
	return string(b), nil
}

// ReadOptionalString reads an optional string.
func (r *Reader) ReadOptionalString() (*string, error) {
	present, err := r.ReadByte()
	if err != nil {
		return nil, err
	}
	if present == 0x00 {
		return nil, nil
	}
	s, err := r.ReadString()
	if err != nil {
		return nil, err
	}
	return &s, nil
}

// ReadOptionalUint32 reads an optional uint32.
func (r *Reader) ReadOptionalUint32() (*uint32, error) {
	present, err := r.ReadByte()
	if err != nil {
		return nil, err
	}
	if present == 0x00 {
		return nil, nil
	}
	v, err := r.ReadUint32()
	if err != nil {
		return nil, err
	}
	return &v, nil
}

// ErrBufferTooSmall indicates the buffer is too small for the operation.
var ErrBufferTooSmall = errors.New("buffer too small")

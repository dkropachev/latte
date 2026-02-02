package com.scylladb.latte.alternator.protocol;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;

/**
 * Writes big-endian primitives to a growable byte buffer.
 */
public final class BinaryWriter {
    private final ByteArrayOutputStream out;

    public BinaryWriter(int initialCapacity) {
        this.out = new ByteArrayOutputStream(initialCapacity);
    }

    public BinaryWriter() {
        this(256);
    }

    public byte[] toByteArray() {
        return out.toByteArray();
    }

    public int size() {
        return out.size();
    }

    public void writeByte(int v) {
        out.write(v);
    }

    public void writeUint8(int v) {
        out.write(v & 0xFF);
    }

    public void writeUint16(int v) {
        out.write((v >> 8) & 0xFF);
        out.write(v & 0xFF);
    }

    public void writeUint32(long v) {
        out.write((int) ((v >> 24) & 0xFF));
        out.write((int) ((v >> 16) & 0xFF));
        out.write((int) ((v >> 8) & 0xFF));
        out.write((int) (v & 0xFF));
    }

    public void writeUint64(long v) {
        out.write((int) ((v >> 56) & 0xFF));
        out.write((int) ((v >> 48) & 0xFF));
        out.write((int) ((v >> 40) & 0xFF));
        out.write((int) ((v >> 32) & 0xFF));
        out.write((int) ((v >> 24) & 0xFF));
        out.write((int) ((v >> 16) & 0xFF));
        out.write((int) ((v >> 8) & 0xFF));
        out.write((int) (v & 0xFF));
    }

    public void writeInt64(long v) {
        writeUint64(v);
    }

    public void writeBool(boolean v) {
        out.write(v ? 0x01 : 0x00);
    }

    public void writeBytes(byte[] v) {
        writeUint32(v.length);
        out.write(v, 0, v.length);
    }

    public void writeString(String v) {
        writeBytes(v.getBytes(StandardCharsets.UTF_8));
    }

    public void writeOptionalString(String v) {
        if (v == null) {
            writeByte(0x00);
        } else {
            writeByte(0x01);
            writeString(v);
        }
    }

    public void writeOptionalUint32(Long v) {
        if (v == null) {
            writeByte(0x00);
        } else {
            writeByte(0x01);
            writeUint32(v);
        }
    }

    public void writeRaw(byte[] v) {
        out.write(v, 0, v.length);
    }
}

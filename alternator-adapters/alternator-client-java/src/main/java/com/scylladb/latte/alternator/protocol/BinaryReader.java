package com.scylladb.latte.alternator.protocol;

import java.io.EOFException;
import java.nio.charset.StandardCharsets;

/**
 * Reads big-endian primitives from a byte array with position cursor.
 */
public final class BinaryReader {
    private final byte[] data;
    private int pos;

    public BinaryReader(byte[] data) {
        this.data = data;
        this.pos = 0;
    }

    public int remaining() {
        return data.length - pos;
    }

    public byte readByte() throws EOFException {
        if (pos >= data.length) {
            throw new EOFException("unexpected end of data");
        }
        return data[pos++];
    }

    public int readUint8() throws EOFException {
        return readByte() & 0xFF;
    }

    public int readUint16() throws EOFException {
        if (pos + 2 > data.length) {
            throw new EOFException("unexpected end of data");
        }
        int v = ((data[pos] & 0xFF) << 8) | (data[pos + 1] & 0xFF);
        pos += 2;
        return v;
    }

    public long readUint32() throws EOFException {
        if (pos + 4 > data.length) {
            throw new EOFException("unexpected end of data");
        }
        long v = ((long)(data[pos] & 0xFF) << 24)
               | ((long)(data[pos + 1] & 0xFF) << 16)
               | ((long)(data[pos + 2] & 0xFF) << 8)
               | ((long)(data[pos + 3] & 0xFF));
        pos += 4;
        return v;
    }

    public long readUint64() throws EOFException {
        if (pos + 8 > data.length) {
            throw new EOFException("unexpected end of data");
        }
        long v = ((long)(data[pos] & 0xFF) << 56)
               | ((long)(data[pos + 1] & 0xFF) << 48)
               | ((long)(data[pos + 2] & 0xFF) << 40)
               | ((long)(data[pos + 3] & 0xFF) << 32)
               | ((long)(data[pos + 4] & 0xFF) << 24)
               | ((long)(data[pos + 5] & 0xFF) << 16)
               | ((long)(data[pos + 6] & 0xFF) << 8)
               | ((long)(data[pos + 7] & 0xFF));
        pos += 8;
        return v;
    }

    public long readInt64() throws EOFException {
        return readUint64();
    }

    public boolean readBool() throws EOFException {
        return readByte() != 0x00;
    }

    public byte[] readBytes() throws EOFException {
        long length = readUint32();
        if (pos + length > data.length) {
            throw new EOFException("unexpected end of data");
        }
        byte[] v = new byte[(int) length];
        System.arraycopy(data, pos, v, 0, (int) length);
        pos += (int) length;
        return v;
    }

    public String readString() throws EOFException {
        byte[] b = readBytes();
        return new String(b, StandardCharsets.UTF_8);
    }

    public String readOptionalString() throws EOFException {
        byte present = readByte();
        if (present == 0x00) {
            return null;
        }
        return readString();
    }

    public Long readOptionalUint32() throws EOFException {
        byte present = readByte();
        if (present == 0x00) {
            return null;
        }
        return readUint32();
    }
}

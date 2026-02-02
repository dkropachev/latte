package com.scylladb.latte.alternator.protocol;

import java.io.DataInputStream;
import java.io.EOFException;
import java.io.IOException;

/**
 * Reads protocol frames from a DataInputStream.
 */
public final class FrameReader {
    private final DataInputStream in;

    public FrameReader(DataInputStream in) {
        this.in = in;
    }

    public Frame readFrame() throws IOException {
        // Read 12-byte header
        byte[] header = new byte[Opcodes.HEADER_SIZE];
        in.readFully(header);

        byte version = header[0];
        byte flags = header[1];
        short streamId = (short) (((header[2] & 0xFF) << 8) | (header[3] & 0xFF));
        byte opcode = header[4];
        // bytes 5-7 reserved
        int bodyLength = ((header[8] & 0xFF) << 24)
                       | ((header[9] & 0xFF) << 16)
                       | ((header[10] & 0xFF) << 8)
                       | (header[11] & 0xFF);

        if (version != Opcodes.VERSION_REQUEST) {
            throw new IOException(String.format("invalid request version: 0x%02x", version));
        }

        if (bodyLength < 0 || bodyLength > Opcodes.MAX_BODY_SIZE) {
            throw new IOException(String.format("body length %d exceeds maximum %d", Integer.toUnsignedLong(bodyLength), Opcodes.MAX_BODY_SIZE));
        }

        byte[] body = new byte[bodyLength];
        if (bodyLength > 0) {
            in.readFully(body);
        }

        return new Frame(version, flags, streamId, opcode, bodyLength, body);
    }
}

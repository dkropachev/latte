package com.scylladb.latte.alternator.protocol;

/**
 * Builds response frames (header + body) and error frames.
 */
public final class FrameWriter {
    private FrameWriter() {}

    /**
     * Builds a complete response frame as a byte array.
     */
    public static byte[] buildFrame(short streamId, byte opcode, byte[] body) {
        int bodyLen = (body != null) ? body.length : 0;
        byte[] frame = new byte[Opcodes.HEADER_SIZE + bodyLen];

        // Header
        frame[0] = Opcodes.VERSION_RESPONSE;
        frame[1] = 0; // flags
        frame[2] = (byte) ((streamId >> 8) & 0xFF);
        frame[3] = (byte) (streamId & 0xFF);
        frame[4] = opcode;
        frame[5] = 0; // reserved
        frame[6] = 0; // reserved
        frame[7] = 0; // reserved
        frame[8] = (byte) ((bodyLen >> 24) & 0xFF);
        frame[9] = (byte) ((bodyLen >> 16) & 0xFF);
        frame[10] = (byte) ((bodyLen >> 8) & 0xFF);
        frame[11] = (byte) (bodyLen & 0xFF);

        // Body
        if (body != null && bodyLen > 0) {
            System.arraycopy(body, 0, frame, Opcodes.HEADER_SIZE, bodyLen);
        }

        return frame;
    }

    /**
     * Builds an error response frame.
     */
    public static byte[] buildErrorFrame(short streamId, int errorCode, String errorType, String message) {
        BinaryWriter buf = new BinaryWriter(64 + errorType.length() + message.length());
        buf.writeUint32(errorCode);
        buf.writeString(errorType);
        buf.writeString(message);
        return buildFrame(streamId, Opcodes.RESP_ERROR, buf.toByteArray());
    }
}

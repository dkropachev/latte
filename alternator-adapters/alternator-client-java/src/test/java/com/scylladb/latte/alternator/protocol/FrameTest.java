package com.scylladb.latte.alternator.protocol;

import org.junit.jupiter.api.Test;

import java.io.*;

import static org.junit.jupiter.api.Assertions.*;

class FrameTest {

    @Test
    void testReadFrameValidRequest() throws Exception {
        byte[] body = {0x01, 0x02, 0x03};
        byte[] raw = buildRequestFrame((short) 1, Opcodes.GET_ITEM, body);

        DataInputStream in = new DataInputStream(new ByteArrayInputStream(raw));
        FrameReader reader = new FrameReader(in);
        Frame frame = reader.readFrame();

        assertEquals(Opcodes.VERSION_REQUEST, frame.version());
        assertEquals(0, frame.flags());
        assertEquals(1, frame.streamId());
        assertEquals(Opcodes.GET_ITEM, frame.opcode());
        assertEquals(3, frame.bodyLength());
        assertArrayEquals(body, frame.body());
    }

    @Test
    void testReadFrameEmptyBody() throws Exception {
        byte[] raw = buildRequestFrame((short) 42, Opcodes.SHUTDOWN, new byte[0]);

        DataInputStream in = new DataInputStream(new ByteArrayInputStream(raw));
        FrameReader reader = new FrameReader(in);
        Frame frame = reader.readFrame();

        assertEquals(42, frame.streamId());
        assertEquals(Opcodes.SHUTDOWN, frame.opcode());
        assertEquals(0, frame.bodyLength());
        assertArrayEquals(new byte[0], frame.body());
    }

    @Test
    void testReadFrameInvalidVersion() {
        byte[] raw = buildRequestFrame((short) 1, Opcodes.GET_ITEM, new byte[0]);
        raw[0] = 0x02; // Invalid version

        DataInputStream in = new DataInputStream(new ByteArrayInputStream(raw));
        FrameReader reader = new FrameReader(in);
        assertThrows(IOException.class, reader::readFrame);
    }

    @Test
    void testReadFrameNegativeStreamId() throws Exception {
        byte[] raw = buildRequestFrame((short) -1, Opcodes.CREATE_SESSION, new byte[0]);

        DataInputStream in = new DataInputStream(new ByteArrayInputStream(raw));
        FrameReader reader = new FrameReader(in);
        Frame frame = reader.readFrame();

        assertEquals(-1, frame.streamId());
    }

    @Test
    void testBuildResponseFrame() {
        byte[] body = {0x0A, 0x0B};
        byte[] frame = FrameWriter.buildFrame((short) 5, Opcodes.RESP_ITEM_RESULT, body);

        assertEquals(Opcodes.HEADER_SIZE + 2, frame.length);
        assertEquals(Opcodes.VERSION_RESPONSE, frame[0]);
        assertEquals(0, frame[1]); // flags
        assertEquals(0, frame[2]); // streamId high
        assertEquals(5, frame[3]); // streamId low
        assertEquals(Opcodes.RESP_ITEM_RESULT, frame[4]);
        assertEquals(0, frame[5]); // reserved
        assertEquals(0, frame[6]); // reserved
        assertEquals(0, frame[7]); // reserved
        assertEquals(0, frame[8]); // body length high
        assertEquals(0, frame[9]);
        assertEquals(0, frame[10]);
        assertEquals(2, frame[11]); // body length low
        assertEquals(0x0A, frame[12]);
        assertEquals(0x0B, frame[13]);
    }

    @Test
    void testBuildResponseFrameNullBody() {
        byte[] frame = FrameWriter.buildFrame((short) 1, Opcodes.RESP_SESSION_CLOSED, null);
        assertEquals(Opcodes.HEADER_SIZE, frame.length);
        assertEquals(0, frame[8]); // body length = 0
        assertEquals(0, frame[9]);
        assertEquals(0, frame[10]);
        assertEquals(0, frame[11]);
    }

    @Test
    void testBuildErrorFrame() {
        byte[] frame = FrameWriter.buildErrorFrame((short) 3, Opcodes.ERROR_SESSION_NOT_FOUND,
                "SessionNotFound", "session 1 not found");

        assertEquals(Opcodes.VERSION_RESPONSE, frame[0]);
        assertEquals(Opcodes.RESP_ERROR, frame[4]);

        // Parse body
        byte[] body = new byte[frame.length - Opcodes.HEADER_SIZE];
        System.arraycopy(frame, Opcodes.HEADER_SIZE, body, 0, body.length);

        BinaryReader r = new BinaryReader(body);
        try {
            assertEquals(Opcodes.ERROR_SESSION_NOT_FOUND, r.readUint32());
            assertEquals("SessionNotFound", r.readString());
            assertEquals("session 1 not found", r.readString());
        } catch (EOFException e) {
            fail("Unexpected EOF: " + e.getMessage());
        }
    }

    @Test
    void testStreamIdPreservation() throws Exception {
        short[] streamIds = {0, 1, -1, Short.MAX_VALUE, Short.MIN_VALUE, 256, 512};
        for (short id : streamIds) {
            byte[] raw = buildRequestFrame(id, Opcodes.GET_ITEM, new byte[0]);
            DataInputStream in = new DataInputStream(new ByteArrayInputStream(raw));
            Frame frame = new FrameReader(in).readFrame();
            assertEquals(id, frame.streamId(), "Stream ID mismatch for " + id);
        }
    }

    @Test
    void testAllOpcodes() throws Exception {
        byte[] opcodes = {
            Opcodes.CREATE_SESSION, Opcodes.CLOSE_SESSION,
            Opcodes.GET_ITEM, Opcodes.PUT_ITEM, Opcodes.DELETE_ITEM, Opcodes.UPDATE_ITEM,
            Opcodes.QUERY, Opcodes.SCAN,
            Opcodes.BATCH_GET_ITEM, Opcodes.BATCH_WRITE_ITEM,
            Opcodes.SHUTDOWN
        };
        for (byte opcode : opcodes) {
            byte[] raw = buildRequestFrame((short) 1, opcode, new byte[0]);
            DataInputStream in = new DataInputStream(new ByteArrayInputStream(raw));
            Frame frame = new FrameReader(in).readFrame();
            assertEquals(opcode, frame.opcode());
        }
    }

    @Test
    void testEofOnIncompleteHeader() {
        byte[] partial = new byte[6]; // Less than HEADER_SIZE
        partial[0] = Opcodes.VERSION_REQUEST;
        DataInputStream in = new DataInputStream(new ByteArrayInputStream(partial));
        FrameReader reader = new FrameReader(in);
        assertThrows(EOFException.class, reader::readFrame);
    }

    private byte[] buildRequestFrame(short streamId, byte opcode, byte[] body) {
        byte[] frame = new byte[Opcodes.HEADER_SIZE + body.length];
        frame[0] = Opcodes.VERSION_REQUEST;
        frame[1] = 0; // flags
        frame[2] = (byte) ((streamId >> 8) & 0xFF);
        frame[3] = (byte) (streamId & 0xFF);
        frame[4] = opcode;
        frame[5] = 0; // reserved
        frame[6] = 0;
        frame[7] = 0;
        frame[8] = (byte) ((body.length >> 24) & 0xFF);
        frame[9] = (byte) ((body.length >> 16) & 0xFF);
        frame[10] = (byte) ((body.length >> 8) & 0xFF);
        frame[11] = (byte) (body.length & 0xFF);
        System.arraycopy(body, 0, frame, Opcodes.HEADER_SIZE, body.length);
        return frame;
    }
}

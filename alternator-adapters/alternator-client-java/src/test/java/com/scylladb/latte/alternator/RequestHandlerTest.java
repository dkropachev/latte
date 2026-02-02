package com.scylladb.latte.alternator;

import com.scylladb.latte.alternator.encoding.AttributeValueCodec;
import com.scylladb.latte.alternator.protocol.*;
import org.junit.jupiter.api.Test;

import java.io.EOFException;

import static org.junit.jupiter.api.Assertions.*;

class RequestHandlerTest {

    @Test
    void testUnknownOpcode() {
        SessionRegistry registry = new SessionRegistry();
        RequestHandler handler = new RequestHandler(registry);

        Frame frame = new Frame(Opcodes.VERSION_REQUEST, (byte) 0, (short) 1, (byte) 0x99, 0, new byte[0]);
        byte[] resp = handler.handle(frame);

        assertErrorResponse(resp, (short) 1, Opcodes.ERROR_PROTOCOL);
    }

    @Test
    void testCreateAndCloseSession() throws Exception {
        SessionRegistry registry = new SessionRegistry();
        RequestHandler handler = new RequestHandler(registry);

        // Create session with parameters for DynamoDB Local
        BinaryWriter body = new BinaryWriter();
        body.writeUint16(2);
        body.writeString("endpoint");
        body.writeString("http://localhost:8000");
        body.writeString("region");
        body.writeString("us-east-1");

        Frame createFrame = new Frame(Opcodes.VERSION_REQUEST, (byte) 0, (short) 1,
                Opcodes.CREATE_SESSION, body.size(), body.toByteArray());
        byte[] createResp = handler.handle(createFrame);

        // Verify session created response
        assertEquals(Opcodes.VERSION_RESPONSE, createResp[0]);
        assertEquals(Opcodes.RESP_SESSION_CREATED, createResp[4]);

        // Extract session ID
        byte[] createBody = extractBody(createResp);
        BinaryReader r = new BinaryReader(createBody);
        long sessionId = r.readUint64();
        assertTrue(sessionId > 0);
        assertEquals(1, registry.count());

        // Close session
        BinaryWriter closeBody = new BinaryWriter();
        closeBody.writeUint64(sessionId);
        Frame closeFrame = new Frame(Opcodes.VERSION_REQUEST, (byte) 0, (short) 2,
                Opcodes.CLOSE_SESSION, closeBody.size(), closeBody.toByteArray());
        byte[] closeResp = handler.handle(closeFrame);

        assertEquals(Opcodes.RESP_SESSION_CLOSED, closeResp[4]);
        assertEquals(0, registry.count());
    }

    @Test
    void testCloseNonexistentSession() {
        SessionRegistry registry = new SessionRegistry();
        RequestHandler handler = new RequestHandler(registry);

        BinaryWriter body = new BinaryWriter();
        body.writeUint64(999);
        Frame frame = new Frame(Opcodes.VERSION_REQUEST, (byte) 0, (short) 1,
                Opcodes.CLOSE_SESSION, body.size(), body.toByteArray());
        byte[] resp = handler.handle(frame);

        assertErrorResponse(resp, (short) 1, Opcodes.ERROR_SESSION_NOT_FOUND);
    }

    @Test
    void testGetItemSessionNotFound() {
        SessionRegistry registry = new SessionRegistry();
        RequestHandler handler = new RequestHandler(registry);

        BinaryWriter body = new BinaryWriter();
        body.writeUint64(999); // non-existent session
        body.writeString("test_table");
        // key
        body.writeUint16(1);
        body.writeString("pk");
        body.writeUint8(AttributeValueCodec.TYPE_STRING);
        body.writeString("key1");
        body.writeBool(false); // consistent read
        body.writeByte(0x00); // no projection
        body.writeUint16(0); // no expr attr names

        Frame frame = new Frame(Opcodes.VERSION_REQUEST, (byte) 0, (short) 5,
                Opcodes.GET_ITEM, body.size(), body.toByteArray());
        byte[] resp = handler.handle(frame);

        assertErrorResponse(resp, (short) 5, Opcodes.ERROR_SESSION_NOT_FOUND);
    }

    @Test
    void testShutdown() {
        SessionRegistry registry = new SessionRegistry();
        RequestHandler handler = new RequestHandler(registry);

        Frame frame = new Frame(Opcodes.VERSION_REQUEST, (byte) 0, (short) 1,
                Opcodes.SHUTDOWN, 0, new byte[0]);
        byte[] resp = handler.handle(frame);

        assertEquals(Opcodes.VERSION_RESPONSE, resp[0]);
        assertEquals(Opcodes.RESP_SHUTDOWN_ACK, resp[4]);
    }

    @Test
    void testMalformedBody() {
        SessionRegistry registry = new SessionRegistry();
        RequestHandler handler = new RequestHandler(registry);

        // GET_ITEM with empty body (should fail to read session_id)
        Frame frame = new Frame(Opcodes.VERSION_REQUEST, (byte) 0, (short) 1,
                Opcodes.GET_ITEM, 0, new byte[0]);
        byte[] resp = handler.handle(frame);

        assertErrorResponse(resp, (short) 1, Opcodes.ERROR_PROTOCOL);
    }

    @Test
    void testStreamIdPreservedInResponse() {
        SessionRegistry registry = new SessionRegistry();
        RequestHandler handler = new RequestHandler(registry);

        short streamId = 12345;
        Frame frame = new Frame(Opcodes.VERSION_REQUEST, (byte) 0, streamId,
                (byte) 0x99, 0, new byte[0]);
        byte[] resp = handler.handle(frame);

        short respStreamId = (short) (((resp[2] & 0xFF) << 8) | (resp[3] & 0xFF));
        assertEquals(streamId, respStreamId);
    }

    private void assertErrorResponse(byte[] resp, short expectedStreamId, int expectedErrorCode) {
        assertEquals(Opcodes.VERSION_RESPONSE, resp[0]);
        assertEquals(Opcodes.RESP_ERROR, resp[4]);

        short streamId = (short) (((resp[2] & 0xFF) << 8) | (resp[3] & 0xFF));
        assertEquals(expectedStreamId, streamId);

        byte[] body = extractBody(resp);
        try {
            BinaryReader r = new BinaryReader(body);
            long errorCode = r.readUint32();
            assertEquals(expectedErrorCode, errorCode);
        } catch (EOFException e) {
            fail("Unexpected EOF reading error response body");
        }
    }

    private byte[] extractBody(byte[] frame) {
        int bodyLen = ((frame[8] & 0xFF) << 24) | ((frame[9] & 0xFF) << 16)
                | ((frame[10] & 0xFF) << 8) | (frame[11] & 0xFF);
        byte[] body = new byte[bodyLen];
        System.arraycopy(frame, Opcodes.HEADER_SIZE, body, 0, bodyLen);
        return body;
    }
}

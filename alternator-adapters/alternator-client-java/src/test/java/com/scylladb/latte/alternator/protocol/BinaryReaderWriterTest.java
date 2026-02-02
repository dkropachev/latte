package com.scylladb.latte.alternator.protocol;

import org.junit.jupiter.api.Test;

import java.io.EOFException;

import static org.junit.jupiter.api.Assertions.*;

class BinaryReaderWriterTest {

    @Test
    void testByte() throws Exception {
        BinaryWriter w = new BinaryWriter();
        w.writeByte(0x00);
        w.writeByte(0xFF);
        w.writeByte(0x42);

        BinaryReader r = new BinaryReader(w.toByteArray());
        assertEquals(0x00, r.readByte());
        assertEquals((byte) 0xFF, r.readByte());
        assertEquals(0x42, r.readByte());
        assertEquals(0, r.remaining());
    }

    @Test
    void testUint8() throws Exception {
        BinaryWriter w = new BinaryWriter();
        w.writeUint8(0);
        w.writeUint8(127);
        w.writeUint8(255);

        BinaryReader r = new BinaryReader(w.toByteArray());
        assertEquals(0, r.readUint8());
        assertEquals(127, r.readUint8());
        assertEquals(255, r.readUint8());
    }

    @Test
    void testUint16() throws Exception {
        BinaryWriter w = new BinaryWriter();
        w.writeUint16(0);
        w.writeUint16(256);
        w.writeUint16(65535);

        BinaryReader r = new BinaryReader(w.toByteArray());
        assertEquals(0, r.readUint16());
        assertEquals(256, r.readUint16());
        assertEquals(65535, r.readUint16());
    }

    @Test
    void testUint32() throws Exception {
        BinaryWriter w = new BinaryWriter();
        w.writeUint32(0);
        w.writeUint32(1);
        w.writeUint32(0xFFFFFFFFL);

        BinaryReader r = new BinaryReader(w.toByteArray());
        assertEquals(0, r.readUint32());
        assertEquals(1, r.readUint32());
        assertEquals(0xFFFFFFFFL, r.readUint32());
    }

    @Test
    void testUint64() throws Exception {
        BinaryWriter w = new BinaryWriter();
        w.writeUint64(0);
        w.writeUint64(1);
        w.writeUint64(Long.MAX_VALUE);
        w.writeUint64(-1L); // 0xFFFFFFFFFFFFFFFF

        BinaryReader r = new BinaryReader(w.toByteArray());
        assertEquals(0, r.readUint64());
        assertEquals(1, r.readUint64());
        assertEquals(Long.MAX_VALUE, r.readUint64());
        assertEquals(-1L, r.readUint64());
    }

    @Test
    void testInt64() throws Exception {
        BinaryWriter w = new BinaryWriter();
        w.writeInt64(0);
        w.writeInt64(-1);
        w.writeInt64(Long.MIN_VALUE);
        w.writeInt64(Long.MAX_VALUE);

        BinaryReader r = new BinaryReader(w.toByteArray());
        assertEquals(0, r.readInt64());
        assertEquals(-1, r.readInt64());
        assertEquals(Long.MIN_VALUE, r.readInt64());
        assertEquals(Long.MAX_VALUE, r.readInt64());
    }

    @Test
    void testBool() throws Exception {
        BinaryWriter w = new BinaryWriter();
        w.writeBool(false);
        w.writeBool(true);

        BinaryReader r = new BinaryReader(w.toByteArray());
        assertFalse(r.readBool());
        assertTrue(r.readBool());
    }

    @Test
    void testBytes() throws Exception {
        byte[] data = {1, 2, 3, 4, 5};
        BinaryWriter w = new BinaryWriter();
        w.writeBytes(data);
        w.writeBytes(new byte[0]);

        BinaryReader r = new BinaryReader(w.toByteArray());
        assertArrayEquals(data, r.readBytes());
        assertArrayEquals(new byte[0], r.readBytes());
    }

    @Test
    void testString() throws Exception {
        BinaryWriter w = new BinaryWriter();
        w.writeString("hello");
        w.writeString("");
        w.writeString("unicode: \u00e9\u00e8\u00ea");

        BinaryReader r = new BinaryReader(w.toByteArray());
        assertEquals("hello", r.readString());
        assertEquals("", r.readString());
        assertEquals("unicode: \u00e9\u00e8\u00ea", r.readString());
    }

    @Test
    void testOptionalStringPresent() throws Exception {
        BinaryWriter w = new BinaryWriter();
        w.writeOptionalString("test");

        BinaryReader r = new BinaryReader(w.toByteArray());
        assertEquals("test", r.readOptionalString());
    }

    @Test
    void testOptionalStringAbsent() throws Exception {
        BinaryWriter w = new BinaryWriter();
        w.writeOptionalString(null);

        BinaryReader r = new BinaryReader(w.toByteArray());
        assertNull(r.readOptionalString());
    }

    @Test
    void testOptionalUint32Present() throws Exception {
        BinaryWriter w = new BinaryWriter();
        w.writeOptionalUint32(42L);

        BinaryReader r = new BinaryReader(w.toByteArray());
        assertEquals(42L, r.readOptionalUint32());
    }

    @Test
    void testOptionalUint32Absent() throws Exception {
        BinaryWriter w = new BinaryWriter();
        w.writeOptionalUint32(null);

        BinaryReader r = new BinaryReader(w.toByteArray());
        assertNull(r.readOptionalUint32());
    }

    @Test
    void testEofHandling() {
        BinaryReader r = new BinaryReader(new byte[0]);
        assertThrows(EOFException.class, r::readByte);
        assertThrows(EOFException.class, r::readUint16);
        assertThrows(EOFException.class, r::readUint32);
        assertThrows(EOFException.class, r::readUint64);
    }

    @Test
    void testPartialReadEof() {
        BinaryReader r = new BinaryReader(new byte[]{0x01});
        assertThrows(EOFException.class, r::readUint16);
    }

    @Test
    void testLargeData() throws Exception {
        byte[] largeData = new byte[100_000];
        for (int i = 0; i < largeData.length; i++) {
            largeData[i] = (byte) (i & 0xFF);
        }

        BinaryWriter w = new BinaryWriter();
        w.writeBytes(largeData);

        BinaryReader r = new BinaryReader(w.toByteArray());
        assertArrayEquals(largeData, r.readBytes());
    }

    @Test
    void testRemaining() throws Exception {
        BinaryWriter w = new BinaryWriter();
        w.writeUint8(1);
        w.writeUint16(2);
        w.writeUint32(3);

        BinaryReader r = new BinaryReader(w.toByteArray());
        assertEquals(7, r.remaining());
        r.readUint8();
        assertEquals(6, r.remaining());
        r.readUint16();
        assertEquals(4, r.remaining());
        r.readUint32();
        assertEquals(0, r.remaining());
    }
}

package com.scylladb.latte.alternator.encoding;

import com.scylladb.latte.alternator.protocol.BinaryReader;
import com.scylladb.latte.alternator.protocol.BinaryWriter;
import software.amazon.awssdk.core.SdkBytes;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;

import java.io.EOFException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Encodes/decodes DynamoDB AttributeValue to/from the binary protocol format.
 */
public final class AttributeValueCodec {
    private AttributeValueCodec() {}

    // Type tags
    public static final int TYPE_NULL = 0x00;
    public static final int TYPE_BOOL = 0x01;
    public static final int TYPE_NUMBER = 0x02;
    public static final int TYPE_STRING = 0x03;
    public static final int TYPE_BINARY = 0x04;
    public static final int TYPE_STRING_SET = 0x05;
    public static final int TYPE_NUMBER_SET = 0x06;
    public static final int TYPE_BINARY_SET = 0x07;
    public static final int TYPE_LIST = 0x08;
    public static final int TYPE_MAP = 0x09;

    // --- Write methods ---

    public static void writeAttributeValue(BinaryWriter buf, AttributeValue av) {
        if (av.nul() != null && av.nul()) {
            buf.writeUint8(TYPE_NULL);
        } else if (av.bool() != null) {
            buf.writeUint8(TYPE_BOOL);
            buf.writeBool(av.bool());
        } else if (av.n() != null) {
            buf.writeUint8(TYPE_NUMBER);
            buf.writeString(av.n());
        } else if (av.s() != null) {
            buf.writeUint8(TYPE_STRING);
            buf.writeString(av.s());
        } else if (av.b() != null) {
            buf.writeUint8(TYPE_BINARY);
            buf.writeBytes(av.b().asByteArray());
        } else if (av.hasSs() && av.ss() != null) {
            buf.writeUint8(TYPE_STRING_SET);
            buf.writeUint32(av.ss().size());
            for (String s : av.ss()) {
                buf.writeString(s);
            }
        } else if (av.hasNs() && av.ns() != null) {
            buf.writeUint8(TYPE_NUMBER_SET);
            buf.writeUint32(av.ns().size());
            for (String n : av.ns()) {
                buf.writeString(n);
            }
        } else if (av.hasBs() && av.bs() != null) {
            buf.writeUint8(TYPE_BINARY_SET);
            buf.writeUint32(av.bs().size());
            for (SdkBytes b : av.bs()) {
                buf.writeBytes(b.asByteArray());
            }
        } else if (av.hasL() && av.l() != null) {
            buf.writeUint8(TYPE_LIST);
            buf.writeUint32(av.l().size());
            for (AttributeValue item : av.l()) {
                writeAttributeValue(buf, item);
            }
        } else if (av.hasM() && av.m() != null) {
            buf.writeUint8(TYPE_MAP);
            buf.writeUint32(av.m().size());
            for (Map.Entry<String, AttributeValue> entry : av.m().entrySet()) {
                buf.writeString(entry.getKey());
                writeAttributeValue(buf, entry.getValue());
            }
        } else {
            // Default to NULL
            buf.writeUint8(TYPE_NULL);
        }
    }

    public static void writeKey(BinaryWriter buf, Map<String, AttributeValue> key) {
        buf.writeUint16(key.size());
        for (Map.Entry<String, AttributeValue> entry : key.entrySet()) {
            buf.writeString(entry.getKey());
            writeAttributeValue(buf, entry.getValue());
        }
    }

    public static void writeItem(BinaryWriter buf, Map<String, AttributeValue> item) {
        buf.writeUint32(item.size());
        for (Map.Entry<String, AttributeValue> entry : item.entrySet()) {
            buf.writeString(entry.getKey());
            writeAttributeValue(buf, entry.getValue());
        }
    }

    public static void writeOptionalKey(BinaryWriter buf, Map<String, AttributeValue> key) {
        if (key == null || key.isEmpty()) {
            buf.writeByte(0x00);
        } else {
            buf.writeByte(0x01);
            writeKey(buf, key);
        }
    }

    public static void writeOptionalItem(BinaryWriter buf, Map<String, AttributeValue> item) {
        if (item == null || item.isEmpty()) {
            buf.writeByte(0x00);
        } else {
            buf.writeByte(0x01);
            writeItem(buf, item);
        }
    }

    // --- Read methods ---

    public static AttributeValue readAttributeValue(BinaryReader r) throws EOFException {
        int typeTag = r.readUint8();
        return switch (typeTag) {
            case TYPE_NULL -> AttributeValue.builder().nul(true).build();
            case TYPE_BOOL -> AttributeValue.builder().bool(r.readBool()).build();
            case TYPE_NUMBER -> AttributeValue.builder().n(r.readString()).build();
            case TYPE_STRING -> AttributeValue.builder().s(r.readString()).build();
            case TYPE_BINARY -> AttributeValue.builder().b(SdkBytes.fromByteArray(r.readBytes())).build();
            case TYPE_STRING_SET -> {
                long count = r.readUint32();
                List<String> ss = new ArrayList<>((int) count);
                for (long i = 0; i < count; i++) {
                    ss.add(r.readString());
                }
                yield AttributeValue.builder().ss(ss).build();
            }
            case TYPE_NUMBER_SET -> {
                long count = r.readUint32();
                List<String> ns = new ArrayList<>((int) count);
                for (long i = 0; i < count; i++) {
                    ns.add(r.readString());
                }
                yield AttributeValue.builder().ns(ns).build();
            }
            case TYPE_BINARY_SET -> {
                long count = r.readUint32();
                List<SdkBytes> bs = new ArrayList<>((int) count);
                for (long i = 0; i < count; i++) {
                    bs.add(SdkBytes.fromByteArray(r.readBytes()));
                }
                yield AttributeValue.builder().bs(bs).build();
            }
            case TYPE_LIST -> {
                long count = r.readUint32();
                List<AttributeValue> list = new ArrayList<>((int) count);
                for (long i = 0; i < count; i++) {
                    list.add(readAttributeValue(r));
                }
                yield AttributeValue.builder().l(list).build();
            }
            case TYPE_MAP -> {
                long count = r.readUint32();
                Map<String, AttributeValue> map = new LinkedHashMap<>((int) count);
                for (long i = 0; i < count; i++) {
                    String key = r.readString();
                    AttributeValue val = readAttributeValue(r);
                    map.put(key, val);
                }
                yield AttributeValue.builder().m(map).build();
            }
            default -> throw new EOFException(String.format("unknown AttributeValue type tag: 0x%02x", typeTag));
        };
    }

    public static Map<String, AttributeValue> readKey(BinaryReader r) throws EOFException {
        int count = r.readUint16();
        Map<String, AttributeValue> key = new LinkedHashMap<>(count);
        for (int i = 0; i < count; i++) {
            String name = r.readString();
            AttributeValue val = readAttributeValue(r);
            key.put(name, val);
        }
        return key;
    }

    public static Map<String, AttributeValue> readItem(BinaryReader r) throws EOFException {
        long count = r.readUint32();
        Map<String, AttributeValue> item = new LinkedHashMap<>((int) count);
        for (long i = 0; i < count; i++) {
            String name = r.readString();
            AttributeValue val = readAttributeValue(r);
            item.put(name, val);
        }
        return item;
    }

    public static Map<String, AttributeValue> readOptionalKey(BinaryReader r) throws EOFException {
        byte present = r.readByte();
        if (present == 0x00) {
            return null;
        }
        return readKey(r);
    }
}

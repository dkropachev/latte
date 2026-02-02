package com.scylladb.latte.alternator.encoding;

import com.scylladb.latte.alternator.protocol.BinaryReader;
import com.scylladb.latte.alternator.protocol.BinaryWriter;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.core.SdkBytes;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class AttributeValueCodecTest {

    @Test
    void testNullType() throws Exception {
        AttributeValue av = AttributeValue.builder().nul(true).build();
        AttributeValue decoded = roundTrip(av);
        assertTrue(decoded.nul());
    }

    @Test
    void testBoolTrue() throws Exception {
        AttributeValue av = AttributeValue.builder().bool(true).build();
        AttributeValue decoded = roundTrip(av);
        assertTrue(decoded.bool());
    }

    @Test
    void testBoolFalse() throws Exception {
        AttributeValue av = AttributeValue.builder().bool(false).build();
        AttributeValue decoded = roundTrip(av);
        assertFalse(decoded.bool());
    }

    @Test
    void testNumber() throws Exception {
        AttributeValue av = AttributeValue.builder().n("42.5").build();
        AttributeValue decoded = roundTrip(av);
        assertEquals("42.5", decoded.n());
    }

    @Test
    void testString() throws Exception {
        AttributeValue av = AttributeValue.builder().s("hello world").build();
        AttributeValue decoded = roundTrip(av);
        assertEquals("hello world", decoded.s());
    }

    @Test
    void testBinary() throws Exception {
        byte[] data = {1, 2, 3, 4, 5};
        AttributeValue av = AttributeValue.builder().b(SdkBytes.fromByteArray(data)).build();
        AttributeValue decoded = roundTrip(av);
        assertArrayEquals(data, decoded.b().asByteArray());
    }

    @Test
    void testStringSet() throws Exception {
        AttributeValue av = AttributeValue.builder().ss("a", "b", "c").build();
        AttributeValue decoded = roundTrip(av);
        assertTrue(decoded.hasSs());
        assertEquals(List.of("a", "b", "c"), decoded.ss());
    }

    @Test
    void testNumberSet() throws Exception {
        AttributeValue av = AttributeValue.builder().ns("1", "2", "3").build();
        AttributeValue decoded = roundTrip(av);
        assertTrue(decoded.hasNs());
        assertEquals(List.of("1", "2", "3"), decoded.ns());
    }

    @Test
    void testBinarySet() throws Exception {
        SdkBytes b1 = SdkBytes.fromByteArray(new byte[]{1, 2});
        SdkBytes b2 = SdkBytes.fromByteArray(new byte[]{3, 4});
        AttributeValue av = AttributeValue.builder().bs(b1, b2).build();
        AttributeValue decoded = roundTrip(av);
        assertTrue(decoded.hasBs());
        assertEquals(2, decoded.bs().size());
        assertArrayEquals(new byte[]{1, 2}, decoded.bs().get(0).asByteArray());
        assertArrayEquals(new byte[]{3, 4}, decoded.bs().get(1).asByteArray());
    }

    @Test
    void testList() throws Exception {
        AttributeValue av = AttributeValue.builder().l(
                AttributeValue.builder().s("one").build(),
                AttributeValue.builder().n("2").build(),
                AttributeValue.builder().bool(true).build()
        ).build();
        AttributeValue decoded = roundTrip(av);
        assertTrue(decoded.hasL());
        assertEquals(3, decoded.l().size());
        assertEquals("one", decoded.l().get(0).s());
        assertEquals("2", decoded.l().get(1).n());
        assertTrue(decoded.l().get(2).bool());
    }

    @Test
    void testMap() throws Exception {
        Map<String, AttributeValue> map = new LinkedHashMap<>();
        map.put("name", AttributeValue.builder().s("Alice").build());
        map.put("age", AttributeValue.builder().n("30").build());
        AttributeValue av = AttributeValue.builder().m(map).build();
        AttributeValue decoded = roundTrip(av);
        assertTrue(decoded.hasM());
        assertEquals("Alice", decoded.m().get("name").s());
        assertEquals("30", decoded.m().get("age").n());
    }

    @Test
    void testNestedStructure() throws Exception {
        // Create a deeply nested structure
        Map<String, AttributeValue> inner = new LinkedHashMap<>();
        inner.put("key", AttributeValue.builder().s("value").build());

        AttributeValue nested = AttributeValue.builder().m(
                Map.of("level1", AttributeValue.builder().m(
                        Map.of("level2", AttributeValue.builder().l(
                                AttributeValue.builder().m(inner).build()
                        ).build())
                ).build())
        ).build();

        AttributeValue decoded = roundTrip(nested);
        assertEquals("value",
                decoded.m().get("level1").m().get("level2").l().get(0).m().get("key").s());
    }

    @Test
    void testKey() throws Exception {
        Map<String, AttributeValue> key = new LinkedHashMap<>();
        key.put("pk", AttributeValue.builder().s("partition1").build());
        key.put("sk", AttributeValue.builder().n("100").build());

        BinaryWriter w = new BinaryWriter();
        AttributeValueCodec.writeKey(w, key);

        BinaryReader r = new BinaryReader(w.toByteArray());
        Map<String, AttributeValue> decoded = AttributeValueCodec.readKey(r);

        assertEquals(2, decoded.size());
        assertEquals("partition1", decoded.get("pk").s());
        assertEquals("100", decoded.get("sk").n());
    }

    @Test
    void testItem() throws Exception {
        Map<String, AttributeValue> item = new LinkedHashMap<>();
        item.put("id", AttributeValue.builder().s("123").build());
        item.put("data", AttributeValue.builder().b(SdkBytes.fromByteArray(new byte[]{1, 2, 3})).build());
        item.put("active", AttributeValue.builder().bool(true).build());

        BinaryWriter w = new BinaryWriter();
        AttributeValueCodec.writeItem(w, item);

        BinaryReader r = new BinaryReader(w.toByteArray());
        Map<String, AttributeValue> decoded = AttributeValueCodec.readItem(r);

        assertEquals(3, decoded.size());
        assertEquals("123", decoded.get("id").s());
        assertArrayEquals(new byte[]{1, 2, 3}, decoded.get("data").b().asByteArray());
        assertTrue(decoded.get("active").bool());
    }

    @Test
    void testOptionalKeyPresent() throws Exception {
        Map<String, AttributeValue> key = new LinkedHashMap<>();
        key.put("pk", AttributeValue.builder().s("test").build());

        BinaryWriter w = new BinaryWriter();
        AttributeValueCodec.writeOptionalKey(w, key);

        BinaryReader r = new BinaryReader(w.toByteArray());
        Map<String, AttributeValue> decoded = AttributeValueCodec.readOptionalKey(r);

        assertNotNull(decoded);
        assertEquals("test", decoded.get("pk").s());
    }

    @Test
    void testOptionalKeyAbsent() throws Exception {
        BinaryWriter w = new BinaryWriter();
        AttributeValueCodec.writeOptionalKey(w, null);

        BinaryReader r = new BinaryReader(w.toByteArray());
        assertNull(AttributeValueCodec.readOptionalKey(r));
    }

    @Test
    void testExprAttrNames() throws Exception {
        BinaryWriter w = new BinaryWriter();
        w.writeUint16(2);
        w.writeString("#n");
        w.writeString("name");
        w.writeString("#a");
        w.writeString("age");

        BinaryReader r = new BinaryReader(w.toByteArray());
        Map<String, String> names = ExpressionCodec.readExprAttrNames(r);

        assertNotNull(names);
        assertEquals(2, names.size());
        assertEquals("name", names.get("#n"));
        assertEquals("age", names.get("#a"));
    }

    @Test
    void testExprAttrNamesEmpty() throws Exception {
        BinaryWriter w = new BinaryWriter();
        w.writeUint16(0);

        BinaryReader r = new BinaryReader(w.toByteArray());
        assertNull(ExpressionCodec.readExprAttrNames(r));
    }

    @Test
    void testExprAttrValues() throws Exception {
        BinaryWriter w = new BinaryWriter();
        w.writeUint16(1);
        w.writeString(":v");
        w.writeUint8(AttributeValueCodec.TYPE_STRING);
        w.writeString("test_value");

        BinaryReader r = new BinaryReader(w.toByteArray());
        Map<String, AttributeValue> values = ExpressionCodec.readExprAttrValues(r);

        assertNotNull(values);
        assertEquals(1, values.size());
        assertEquals("test_value", values.get(":v").s());
    }

    @Test
    void testConditionExpression() throws Exception {
        BinaryWriter w = new BinaryWriter();
        w.writeByte(0x01); // present
        w.writeString("attribute_exists(#n)");
        // expr attr names
        w.writeUint16(1);
        w.writeString("#n");
        w.writeString("name");
        // expr attr values
        w.writeUint16(0);

        BinaryReader r = new BinaryReader(w.toByteArray());
        ExpressionCodec.ConditionExpression ce = ExpressionCodec.readOptionalConditionExpression(r);

        assertNotNull(ce);
        assertEquals("attribute_exists(#n)", ce.expression());
        assertEquals("name", ce.exprAttrNames().get("#n"));
        assertNull(ce.exprAttrValues());
    }

    @Test
    void testConditionExpressionAbsent() throws Exception {
        BinaryWriter w = new BinaryWriter();
        w.writeByte(0x00); // absent

        BinaryReader r = new BinaryReader(w.toByteArray());
        assertNull(ExpressionCodec.readOptionalConditionExpression(r));
    }

    @Test
    void testEmptyString() throws Exception {
        AttributeValue av = AttributeValue.builder().s("").build();
        AttributeValue decoded = roundTrip(av);
        assertEquals("", decoded.s());
    }

    @Test
    void testLargeNumber() throws Exception {
        String largeNum = "99999999999999999999999999.99999999999999999999";
        AttributeValue av = AttributeValue.builder().n(largeNum).build();
        AttributeValue decoded = roundTrip(av);
        assertEquals(largeNum, decoded.n());
    }

    private AttributeValue roundTrip(AttributeValue av) throws Exception {
        BinaryWriter w = new BinaryWriter();
        AttributeValueCodec.writeAttributeValue(w, av);
        BinaryReader r = new BinaryReader(w.toByteArray());
        return AttributeValueCodec.readAttributeValue(r);
    }
}

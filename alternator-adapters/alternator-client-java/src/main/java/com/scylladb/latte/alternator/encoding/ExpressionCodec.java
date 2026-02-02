package com.scylladb.latte.alternator.encoding;

import com.scylladb.latte.alternator.protocol.BinaryReader;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;

import java.io.EOFException;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Reads expression attribute names/values maps and optional ConditionExpression.
 */
public final class ExpressionCodec {
    private ExpressionCodec() {}

    public static Map<String, String> readExprAttrNames(BinaryReader r) throws EOFException {
        int count = r.readUint16();
        if (count == 0) {
            return null;
        }
        Map<String, String> names = new LinkedHashMap<>(count);
        for (int i = 0; i < count; i++) {
            String placeholder = r.readString();
            String attrName = r.readString();
            names.put(placeholder, attrName);
        }
        return names;
    }

    public static Map<String, AttributeValue> readExprAttrValues(BinaryReader r) throws EOFException {
        int count = r.readUint16();
        if (count == 0) {
            return null;
        }
        Map<String, AttributeValue> values = new LinkedHashMap<>(count);
        for (int i = 0; i < count; i++) {
            String placeholder = r.readString();
            AttributeValue val = AttributeValueCodec.readAttributeValue(r);
            values.put(placeholder, val);
        }
        return values;
    }

    /**
     * Holds a condition expression with attribute names and values.
     */
    public record ConditionExpression(
        String expression,
        Map<String, String> exprAttrNames,
        Map<String, AttributeValue> exprAttrValues
    ) {}

    public static ConditionExpression readOptionalConditionExpression(BinaryReader r) throws EOFException {
        byte present = r.readByte();
        if (present == 0x00) {
            return null;
        }
        String expr = r.readString();
        Map<String, String> names = readExprAttrNames(r);
        Map<String, AttributeValue> values = readExprAttrValues(r);
        return new ConditionExpression(expr, names, values);
    }
}

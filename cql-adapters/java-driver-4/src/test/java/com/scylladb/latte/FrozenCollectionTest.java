package com.scylladb.latte;

import static org.assertj.core.api.Assertions.assertThat;

import com.datastax.oss.driver.api.core.type.DataTypes;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Tests for frozen collection handling.
 *
 * <p>In CQL, the "frozen" modifier is a schema-level concept that makes collections immutable and
 * stored as a single serialized blob. At the protocol level, frozen and non-frozen collections have
 * identical binary encoding. The difference is only in how the database treats them for updates and
 * storage.
 *
 * <p>These tests verify that the encoding is correct for collections that would typically be frozen
 * when nested (CQL requires frozen for nested collections).
 *
 * <p>Note: Full UDT (User Defined Type) testing with frozen collections requires integration tests
 * with a real database, as UserDefinedType metadata cannot be easily mocked.
 */
@DisplayName("Frozen Collection Handling")
class FrozenCollectionTest {

  @Nested
  @DisplayName("Frozen List Encoding")
  class FrozenListEncoding {

    @Test
    @DisplayName("should encode frozen<list<int>> identically to list<int>")
    void frozenListEncodingSameAsNonFrozen() {
      List<Integer> data = List.of(1, 2, 3, 4, 5);

      // Both frozen and non-frozen lists have the same encoding
      // The "frozen" modifier only affects storage and update semantics
      byte[] encoded = ValueEncoder.encodeCollection(data, DataTypes.listOf(DataTypes.INT));

      assertThat(encoded).isNotNull();

      // Verify structure: 4 bytes count + (4 bytes length + 4 bytes value) * 5
      assertThat(encoded.length).isEqualTo(4 + (4 + 4) * 5);

      // Parse and verify count
      Protocol.BytesReader reader = new Protocol.BytesReader(encoded);
      int count = reader.readInt();
      assertThat(count).isEqualTo(5);

      // Verify each element
      for (int i = 1; i <= 5; i++) {
        int length = reader.readInt();
        assertThat(length).isEqualTo(4);
        int value = reader.readInt();
        assertThat(value).isEqualTo(i);
      }
    }

    @Test
    @DisplayName("should encode frozen<list<frozen<list<int>>>> correctly")
    void frozenNestedListEncoding() {
      List<List<Integer>> nested =
          List.of(List.of(1, 2), List.of(3, 4, 5), List.of(6));

      var innerType = DataTypes.listOf(DataTypes.INT);
      var outerType = DataTypes.listOf(innerType);

      byte[] encoded = ValueEncoder.encodeCollection(nested, outerType);

      assertThat(encoded).isNotNull();

      // Parse outer list
      Protocol.BytesReader reader = new Protocol.BytesReader(encoded);
      int outerCount = reader.readInt();
      assertThat(outerCount).isEqualTo(3);

      // Verify first inner list has 2 elements
      int firstInnerLength = reader.readInt();
      assertThat(firstInnerLength).isGreaterThan(0);
      // Inner list: 4 bytes count + (4 + 4) * 2 = 20 bytes
      assertThat(firstInnerLength).isEqualTo(20);
    }
  }

  @Nested
  @DisplayName("Frozen Set Encoding")
  class FrozenSetEncoding {

    @Test
    @DisplayName("should encode frozen<set<text>> correctly")
    void frozenSetOfText() {
      Set<String> data = Set.of("apple", "banana", "cherry");

      byte[] encoded = ValueEncoder.encodeCollection(data, DataTypes.setOf(DataTypes.TEXT));

      assertThat(encoded).isNotNull();

      Protocol.BytesReader reader = new Protocol.BytesReader(encoded);
      int count = reader.readInt();
      assertThat(count).isEqualTo(3);
    }

    @Test
    @DisplayName("should encode frozen<set<frozen<set<int>>>> correctly")
    void frozenNestedSetEncoding() {
      Set<Set<Integer>> nested =
          Set.of(Set.of(1, 2, 3), Set.of(10, 20), Set.of(100));

      var innerType = DataTypes.setOf(DataTypes.INT);
      var outerType = DataTypes.setOf(innerType);

      byte[] encoded = ValueEncoder.encodeCollection(nested, outerType);

      assertThat(encoded).isNotNull();

      Protocol.BytesReader reader = new Protocol.BytesReader(encoded);
      int outerCount = reader.readInt();
      assertThat(outerCount).isEqualTo(3);
    }
  }

  @Nested
  @DisplayName("Frozen Map Encoding")
  class FrozenMapEncoding {

    @Test
    @DisplayName("should encode frozen<map<text, int>> correctly")
    void frozenMapOfTextToInt() {
      Map<String, Integer> data = Map.of("a", 1, "b", 2, "c", 3);

      byte[] encoded =
          ValueEncoder.encodeCollection(data, DataTypes.mapOf(DataTypes.TEXT, DataTypes.INT));

      assertThat(encoded).isNotNull();

      Protocol.BytesReader reader = new Protocol.BytesReader(encoded);
      int count = reader.readInt();
      assertThat(count).isEqualTo(3);
    }

    @Test
    @DisplayName("should encode frozen<map<text, frozen<list<int>>>> correctly")
    void frozenMapWithNestedList() {
      Map<String, List<Integer>> data =
          Map.of(
              "first", List.of(1, 2, 3),
              "second", List.of(4, 5));

      var listType = DataTypes.listOf(DataTypes.INT);
      var mapType = DataTypes.mapOf(DataTypes.TEXT, listType);

      byte[] encoded = ValueEncoder.encodeCollection(data, mapType);

      assertThat(encoded).isNotNull();

      Protocol.BytesReader reader = new Protocol.BytesReader(encoded);
      int count = reader.readInt();
      assertThat(count).isEqualTo(2);
    }

    @Test
    @DisplayName("should encode frozen<map<frozen<list<int>>, text>> with complex keys")
    void frozenMapWithComplexKeys() {
      // Maps with complex keys (like frozen lists) are valid in CQL
      Map<List<Integer>, String> data =
          Map.of(
              List.of(1, 2, 3), "first",
              List.of(4, 5), "second");

      var keyType = DataTypes.listOf(DataTypes.INT);
      var mapType = DataTypes.mapOf(keyType, DataTypes.TEXT);

      byte[] encoded = ValueEncoder.encodeCollection(data, mapType);

      assertThat(encoded).isNotNull();

      Protocol.BytesReader reader = new Protocol.BytesReader(encoded);
      int count = reader.readInt();
      assertThat(count).isEqualTo(2);
    }
  }

  @Nested
  @DisplayName("Deeply Frozen Collections")
  class DeeplyFrozenCollections {

    @Test
    @DisplayName("should encode 4-level nested frozen collections")
    void fourLevelNesting() {
      // frozen<list<frozen<map<text, frozen<list<frozen<set<int>>>>>>>>
      List<Map<String, List<Set<Integer>>>> data =
          List.of(
              Map.of(
                  "a", List.of(Set.of(1, 2), Set.of(3)),
                  "b", List.of(Set.of(4, 5, 6))),
              Map.of("c", List.of(Set.of(7, 8, 9, 10))));

      var setType = DataTypes.setOf(DataTypes.INT);
      var listOfSetType = DataTypes.listOf(setType);
      var mapType = DataTypes.mapOf(DataTypes.TEXT, listOfSetType);
      var outerListType = DataTypes.listOf(mapType);

      byte[] encoded = ValueEncoder.encodeCollection(data, outerListType);

      assertThat(encoded).isNotNull();

      Protocol.BytesReader reader = new Protocol.BytesReader(encoded);
      int outerCount = reader.readInt();
      assertThat(outerCount).isEqualTo(2);
    }

    @Test
    @DisplayName("should encode mixed frozen collection types")
    void mixedFrozenTypes() {
      // frozen<map<text, frozen<list<frozen<map<text, int>>>>>>
      Map<String, List<Map<String, Integer>>> data =
          Map.of(
              "outer1",
                  List.of(
                      Map.of("a", 1, "b", 2),
                      Map.of("c", 3)),
              "outer2",
                  List.of(Map.of("x", 10, "y", 20, "z", 30)));

      var innerMapType = DataTypes.mapOf(DataTypes.TEXT, DataTypes.INT);
      var listType = DataTypes.listOf(innerMapType);
      var outerMapType = DataTypes.mapOf(DataTypes.TEXT, listType);

      byte[] encoded = ValueEncoder.encodeCollection(data, outerMapType);

      assertThat(encoded).isNotNull();
      assertThat(encoded.length).isGreaterThan(0);
    }
  }

  @Nested
  @DisplayName("Frozen Collection Edge Cases")
  class FrozenEdgeCases {

    @Test
    @DisplayName("should handle empty frozen collection")
    void emptyFrozenCollection() {
      List<List<Integer>> empty = List.of();

      var innerType = DataTypes.listOf(DataTypes.INT);
      var outerType = DataTypes.listOf(innerType);

      byte[] encoded = ValueEncoder.encodeCollection(empty, outerType);

      assertThat(encoded).isNotNull();
      assertThat(encoded.length).isEqualTo(4); // Just the count (0)

      Protocol.BytesReader reader = new Protocol.BytesReader(encoded);
      assertThat(reader.readInt()).isEqualTo(0);
    }

    @Test
    @DisplayName("should handle frozen collection with single element")
    void singleElementFrozen() {
      List<Set<String>> single = List.of(Set.of("only"));

      var setType = DataTypes.setOf(DataTypes.TEXT);
      var listType = DataTypes.listOf(setType);

      byte[] encoded = ValueEncoder.encodeCollection(single, listType);

      assertThat(encoded).isNotNull();

      Protocol.BytesReader reader = new Protocol.BytesReader(encoded);
      assertThat(reader.readInt()).isEqualTo(1);
    }

    @Test
    @DisplayName("should handle frozen collection with null-like empty strings")
    void frozenWithEmptyStrings() {
      List<String> data = List.of("", "non-empty", "");

      byte[] encoded = ValueEncoder.encodeCollection(data, DataTypes.listOf(DataTypes.TEXT));

      assertThat(encoded).isNotNull();

      Protocol.BytesReader reader = new Protocol.BytesReader(encoded);
      int count = reader.readInt();
      assertThat(count).isEqualTo(3);

      // First element: empty string
      int len1 = reader.readInt();
      assertThat(len1).isEqualTo(0);

      // Second element: "non-empty"
      int len2 = reader.readInt();
      assertThat(len2).isEqualTo(9);
      reader.skip(9);

      // Third element: empty string
      int len3 = reader.readInt();
      assertThat(len3).isEqualTo(0);
    }

    @Test
    @DisplayName("should handle large frozen collection")
    void largeFrozenCollection() {
      // Create a frozen list with 1000 nested lists
      List<List<Integer>> large = new java.util.ArrayList<>(1000);
      for (int i = 0; i < 1000; i++) {
        large.add(List.of(i, i + 1, i + 2));
      }

      var innerType = DataTypes.listOf(DataTypes.INT);
      var outerType = DataTypes.listOf(innerType);

      byte[] encoded = ValueEncoder.encodeCollection(large, outerType);

      assertThat(encoded).isNotNull();

      Protocol.BytesReader reader = new Protocol.BytesReader(encoded);
      int count = reader.readInt();
      assertThat(count).isEqualTo(1000);
    }
  }

  @Nested
  @DisplayName("UDT Notes")
  class UdtNotes {

    /**
     * Note: User Defined Type (UDT) testing with frozen collections requires integration tests.
     *
     * <p>In CQL, UDTs inside collections must be frozen:
     *
     * <pre>
     * CREATE TYPE address (street text, city text, zip int);
     * CREATE TABLE users (
     *   id uuid PRIMARY KEY,
     *   addresses frozen<list<frozen<address>>>
     * );
     * </pre>
     *
     * <p>The encoding for UDTs follows the same pattern as other values - each field is encoded
     * with a 4-byte length prefix followed by the field data. The ValueEncoder.encodeUdt() method
     * handles this encoding.
     *
     * <p>Full UDT testing requires a real database connection to obtain UserDefinedType metadata,
     * which cannot be easily mocked. See integration tests for comprehensive UDT validation.
     */
    @Test
    @DisplayName("UDT encoding documentation - see class Javadoc")
    void udtEncodingDocumentation() {
      // This test exists to document UDT encoding behavior
      // Actual UDT testing requires integration tests

      // Verify that UDT type code is defined
      assertThat(Protocol.TYPE_UDT).isEqualTo((short) 0x0040);

      // Verify UDT encoding method exists (compile-time check)
      assertThat(ValueEncoder.class.getDeclaredMethods())
          .anyMatch(m -> m.getName().equals("encode"));
    }
  }
}

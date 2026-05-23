package io.github.flameyossnowy.uniform.json.codegen;

import io.github.flameyossnowy.uniform.json.ReflectionConfig;
import io.github.flameyossnowy.uniform.json.codegen.fixtures.SimplePojo;
import io.github.flameyossnowy.uniform.json.codegen.fixtures.NestedPojo;
import io.github.flameyossnowy.uniform.json.features.JsonReadFeature;
import io.github.flameyossnowy.uniform.json.features.JsonWriteFeature;
import io.github.flameyossnowy.uniform.json.parser.lowlevel.MapJsonCursor;
import io.github.flameyossnowy.uniform.json.dom.*;
import io.github.flameyossnowy.uniform.json.JsonAdapter;
import io.github.flameyossnowy.uniform.json.JsonConfig;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

public class MapJsonCursorTypeTest {

    private JsonAdapter adapter;

    @BeforeEach
    void setUp() {
        adapter = new JsonAdapter(new JsonConfig(
            false, 2,
            EnumSet.noneOf(JsonReadFeature.class),
            EnumSet.noneOf(JsonWriteFeature.class),
            ReflectionConfig.DEFAULT
        ));
    }

    /**
     * Returns a cursor already positioned on the first field of the given map.
     * Validates that enterObject() and nextField() both succeed.
     */
    private MapJsonCursor cursorOf(Map<String, Object> map) {
        MapJsonCursor cursor = new MapJsonCursor(map);
        assertTrue(cursor.enterObject(), "enterObject() must succeed");
        assertTrue(cursor.nextField(),   "at least one field must be present");
        return cursor;
    }

    /**
     * Returns a cursor already positioned on the first element of the given list.
     */
    private MapJsonCursor arrayCursorOf(List<Object> list) {
        MapJsonCursor cursor = new MapJsonCursor(list);
        assertTrue(cursor.enterArray(),   "enterArray() must succeed");
        assertTrue(cursor.nextElement(),  "at least one element must be present");
        return cursor;
    }

    @Nested
    @DisplayName("Raw type preservation through cursor accessors")
    class RawStorageTypes {

        @Test @DisplayName("Byte stored as Byte -> fieldValueAsByte() exact, no widening")
        void bytePreserved() {
            MapJsonCursor c = cursorOf(Map.of("v", (byte) 100));
            byte result = c.fieldValueAsByte();
            assertEquals((byte) 100, result);
            assertEquals(Byte.class, ((Byte) result).getClass());
        }

        @Test @DisplayName("Short stored as Short -> fieldValueAsShort() exact, not widened to int")
        void shortPreserved() {
            MapJsonCursor c = cursorOf(Map.of("v", (short) 32000));
            short result = c.fieldValueAsShort();
            assertEquals((short) 32000, result);
            assertEquals(Short.class, ((Short) result).getClass());
        }

        @Test @DisplayName("Integer stored as Integer -> fieldValueAsInt() exact")
        void intPreserved() {
            MapJsonCursor c = cursorOf(Map.of("v", 123456));
            int result = c.fieldValueAsInt();
            assertEquals(123456, result);
            assertEquals(Integer.class, ((Integer) result).getClass());
        }

        @Test @DisplayName("Long stored as Long -> fieldValueAsLong() exact, not truncated to int")
        void longPreserved() {
            MapJsonCursor c = cursorOf(Map.of("v", Long.MAX_VALUE));
            long result = c.fieldValueAsLong();
            assertEquals(Long.MAX_VALUE, result);
            assertEquals(Long.class, ((Long) result).getClass());
        }

        @Test @DisplayName("Float stored as Float -> fieldValueAsFloat() exact, not widened to double")
        void floatPreserved() {
            MapJsonCursor c = cursorOf(Map.of("v", 1.5f));
            float result = c.fieldValueAsFloat();
            assertEquals(1.5f, result, 0.0f);
            assertEquals(Float.class, ((Float) result).getClass());
        }

        @Test @DisplayName("Double stored as Double -> fieldValueAsDouble() exact")
        void doublePreserved() {
            MapJsonCursor c = cursorOf(Map.of("v", Math.PI));
            double result = c.fieldValueAsDouble();
            assertEquals(Math.PI, result, 0.0);
            assertEquals(Double.class, ((Double) result).getClass());
        }

        @Test @DisplayName("Boolean true stored as Boolean -> fieldValueAsBoolean() exact")
        void booleanTruePreserved() {
            MapJsonCursor c = cursorOf(Map.of("v", true));
            boolean result = c.fieldValueAsBoolean();
            assertTrue(result);
            assertEquals(Boolean.class, ((Boolean) result).getClass());
        }

        @Test @DisplayName("Boolean false stored as Boolean -> fieldValueAsBoolean() exact")
        void booleanFalsePreserved() {
            MapJsonCursor c = cursorOf(Map.of("v", false));
            boolean result = c.fieldValueAsBoolean();
            assertFalse(result);
            assertEquals(Boolean.class, ((Boolean) result).getClass());
        }

        @Test @DisplayName("Char stored as Character -> fieldValueAsUnquotedString() single char")
        void charPreserved() {
            // char has no dedicated accessor - verify it round-trips through toString
            // and that the stored box type is Character, not String or Integer
            MapJsonCursor c = cursorOf(Map.of("v", 'Z'));
            String result = c.fieldValueAsUnquotedString();
            assertEquals("Z", result);
            assertEquals(1, result.length());
            // Also verify the JsonValue mapping preserves it as a single-char JsonString
            JsonValue jv = c.fieldValueAsJsonValue();
            assertEquals(JsonString.class, jv.getClass());
            assertEquals("Z", ((JsonString) jv).value());
        }
    }

    @Nested
    @DisplayName("fieldValueAsJsonValue() produces correct JsonValue subclass")
    class JsonValueMapping {

        @Test @DisplayName("Integer -> JsonInteger")
        void integerToJsonInteger() {
            MapJsonCursor c = cursorOf(Map.of("v", 42));
            JsonValue jv = c.fieldValueAsJsonValue();
            assertEquals(JsonInteger.class, jv.getClass());
            assertEquals(42, ((JsonInteger) jv).intValue());
        }

        @Test @DisplayName("Long -> JsonLong")
        void longToJsonLong() {
            MapJsonCursor c = cursorOf(Map.of("v", Long.MAX_VALUE));
            JsonValue jv = c.fieldValueAsJsonValue();
            assertEquals(JsonLong.class, jv.getClass());
            assertEquals(Long.MAX_VALUE, ((JsonLong) jv).longValue());
        }

        @Test @DisplayName("Short -> JsonShort")
        void shortToJsonShort() {
            MapJsonCursor c = cursorOf(Map.of("v", (short) 32767));
            JsonValue jv = c.fieldValueAsJsonValue();
            assertEquals(JsonShort.class, jv.getClass());
            assertEquals((short) 32767, ((JsonShort) jv).shortValue());
        }

        @Test @DisplayName("Byte -> JsonByte")
        void byteToJsonByte() {
            MapJsonCursor c = cursorOf(Map.of("v", (byte) 127));
            JsonValue jv = c.fieldValueAsJsonValue();
            assertEquals(JsonByte.class, jv.getClass());
            assertEquals((byte) 127, ((JsonByte) jv).byteValue());
        }

        @Test @DisplayName("Double -> JsonDouble")
        void doubleToJsonDouble() {
            MapJsonCursor c = cursorOf(Map.of("v", 3.14));
            JsonValue jv = c.fieldValueAsJsonValue();
            assertEquals(JsonDouble.class, jv.getClass());
            assertEquals(3.14, ((JsonDouble) jv).doubleValue(), 1e-9);
        }

        @Test @DisplayName("Float -> JsonFloat")
        void floatToJsonFloat() {
            MapJsonCursor c = cursorOf(Map.of("v", 1.5f));
            JsonValue jv = c.fieldValueAsJsonValue();
            assertEquals(JsonFloat.class, jv.getClass());
            assertEquals(1.5f, ((JsonFloat) jv).floatValue(), 1e-6f);
        }

        @Test @DisplayName("Boolean true -> JsonBoolean")
        void booleanToJsonBoolean() {
            MapJsonCursor c = cursorOf(Map.of("v", true));
            JsonValue jv = c.fieldValueAsJsonValue();
            assertEquals(JsonBoolean.class, jv.getClass());
            assertTrue(((JsonBoolean) jv).value());
        }

        @Test @DisplayName("String -> JsonString")
        void stringToJsonString() {
            MapJsonCursor c = cursorOf(Map.of("v", "hello"));
            JsonValue jv = c.fieldValueAsJsonValue();
            assertEquals(JsonString.class, jv.getClass());
            assertEquals("hello", ((JsonString) jv).value());
        }

        @Test @DisplayName("null -> JsonNull")
        void nullToJsonNull() {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("v", null);
            MapJsonCursor c = cursorOf(m);
            JsonValue jv = c.fieldValueAsJsonValue();
            assertSame(JsonNull.INSTANCE, jv);
        }

        @Test @DisplayName("Map -> JsonObject")
        void mapToJsonObject() {
            Map<String, Object> nested = new LinkedHashMap<>();
            nested.put("id", 7);
            MapJsonCursor c = cursorOf(Map.of("v", nested));
            JsonValue jv = c.fieldValueAsJsonValue();
            assertEquals(JsonObject.class, jv.getClass());
            assertEquals(7, ((JsonObject) jv).getInt("id"));
        }

        @Test @DisplayName("List -> JsonArray")
        void listToJsonArray() {
            MapJsonCursor c = cursorOf(Map.of("v", List.of(1, 2, 3)));
            JsonValue jv = c.fieldValueAsJsonValue();
            assertEquals(JsonArray.class, jv.getClass());
            assertEquals(3, ((JsonArray) jv).size());
        }

        @Test @DisplayName("Already-a-JsonValue passthrough - class unchanged")
        void jsonValuePassthrough() {
            JsonInteger existing = new JsonInteger(99);
            MapJsonCursor c = cursorOf(Map.of("v", existing));
            JsonValue jv = c.fieldValueAsJsonValue();
            assertSame(existing, jv, "pre-boxed JsonValue must be returned as-is");
            assertEquals(JsonInteger.class, jv.getClass());
        }
    }

    @Nested
    @DisplayName("Scalar accessor coercions (field values)")
    class FieldAccessorCoercions {

        @Test @DisplayName("Integer -> asInt exact")
        void intExact() {
            assertEquals(42, cursorOf(Map.of("v", 42)).fieldValueAsInt());
        }

        @Test @DisplayName("Long -> asLong exact")
        void longExact() {
            assertEquals(Long.MAX_VALUE, cursorOf(Map.of("v", Long.MAX_VALUE)).fieldValueAsLong());
        }

        @Test @DisplayName("Short -> asShort exact")
        void shortExact() {
            assertEquals((short) 200, cursorOf(Map.of("v", (short) 200)).fieldValueAsShort());
        }

        @Test @DisplayName("Byte -> asByte exact")
        void byteExact() {
            assertEquals((byte) 127, cursorOf(Map.of("v", (byte) 127)).fieldValueAsByte());
        }

        @Test @DisplayName("Double -> asDouble exact")
        void doubleExact() {
            assertEquals(Math.PI, cursorOf(Map.of("v", Math.PI)).fieldValueAsDouble(), 1e-15);
        }

        @Test @DisplayName("Float -> asFloat exact")
        void floatExact() {
            assertEquals(1.5f, cursorOf(Map.of("v", 1.5f)).fieldValueAsFloat(), 1e-6f);
        }

        @Test @DisplayName("Float -> asDouble widens without catastrophic loss")
        void floatWidensToDouble() {
            double result = cursorOf(Map.of("v", 1.5f)).fieldValueAsDouble();
            assertEquals(1.5, result, 1e-6);
        }

        @Test @DisplayName("Integer -> asDouble widens exactly")
        void intWidensToDouble() {
            assertEquals(42.0, cursorOf(Map.of("v", 42)).fieldValueAsDouble(), 0.0);
        }

        @Test @DisplayName("Boolean true -> asBoolean true")
        void boolTrue() {
            assertTrue(cursorOf(Map.of("v", true)).fieldValueAsBoolean());
        }

        @Test @DisplayName("Boolean false -> asBoolean false")
        void boolFalse() {
            assertFalse(cursorOf(Map.of("v", false)).fieldValueAsBoolean());
        }

        @Test @DisplayName("Integer 1 -> asBoolean true")
        void intOneToTrue() {
            assertTrue(cursorOf(Map.of("v", 1)).fieldValueAsBoolean());
        }

        @Test @DisplayName("Integer 0 -> asBoolean false")
        void intZeroToFalse() {
            assertFalse(cursorOf(Map.of("v", 0)).fieldValueAsBoolean());
        }

        @Test @DisplayName("String 'true' -> asBoolean true (case-insensitive)")
        void stringTrueToBoolean() {
            assertTrue(cursorOf(Map.of("v", "TRUE")).fieldValueAsBoolean());
        }

        @Test @DisplayName("String '1' -> asBoolean true")
        void stringOneToBoolean() {
            assertTrue(cursorOf(Map.of("v", "1")).fieldValueAsBoolean());
        }

        @Test @DisplayName("String 'yes' -> asBoolean true (case-insensitive)")
        void stringYesToBoolean() {
            assertTrue(cursorOf(Map.of("v", "YES")).fieldValueAsBoolean());
        }

        @Test @DisplayName("String 'false' -> asBoolean false")
        void stringFalseToBoolean() {
            assertFalse(cursorOf(Map.of("v", "false")).fieldValueAsBoolean());
        }

        @Test @DisplayName("null -> asInt 0")
        void nullToInt() {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("v", null);
            assertEquals(0, cursorOf(m).fieldValueAsInt());
        }

        @Test @DisplayName("null -> asLong 0")
        void nullToLong() {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("v", null);
            assertEquals(0L, cursorOf(m).fieldValueAsLong());
        }

        @Test @DisplayName("null -> asDouble 0.0")
        void nullToDouble() {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("v", null);
            assertEquals(0.0, cursorOf(m).fieldValueAsDouble(), 0.0);
        }

        @Test @DisplayName("null -> asBoolean false")
        void nullToBoolean() {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("v", null);
            assertFalse(cursorOf(m).fieldValueAsBoolean());
        }

        @Test @DisplayName("null -> asUnquotedString empty string")
        void nullToString() {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("v", null);
            assertEquals("", cursorOf(m).fieldValueAsUnquotedString());
        }

        @Test @DisplayName("String 'not_a_number' -> asInt throws NumberFormatException")
        void badStringToIntThrows() {
            assertThrows(NumberFormatException.class,
                () -> cursorOf(Map.of("v", "not_a_number")).fieldValueAsInt());
        }
    }

    @Nested
    @DisplayName("Scalar accessor coercions (array element values)")
    class ElementAccessorCoercions {

        @Test @DisplayName("Integer element -> elementValueAsInt exact")
        void intElement() {
            assertEquals(7, arrayCursorOf(List.of(7)).elementValueAsInt());
        }

        @Test @DisplayName("Long element -> elementValueAsLong exact")
        void longElement() {
            assertEquals(Long.MIN_VALUE, arrayCursorOf(List.of(Long.MIN_VALUE)).elementValueAsLong());
        }

        @Test @DisplayName("Short element -> elementValueAsShort exact")
        void shortElement() {
            assertEquals((short) -1, arrayCursorOf(List.of((short) -1)).elementValueAsShort());
        }

        @Test @DisplayName("Byte element -> elementValueAsByte exact")
        void byteElement() {
            assertEquals((byte) -128, arrayCursorOf(List.of((byte) -128)).elementValueAsByte());
        }

        @Test @DisplayName("Double element -> elementValueAsDouble exact")
        void doubleElement() {
            assertEquals(2.718, arrayCursorOf(List.of(2.718)).elementValueAsDouble(), 1e-9);
        }

        @Test @DisplayName("Float element -> elementValueAsFloat exact")
        void floatElement() {
            assertEquals(0.5f, arrayCursorOf(List.of(0.5f)).elementValueAsFloat(), 1e-6f);
        }

        @Test @DisplayName("null element -> elementIsNull() true")
        void nullElement() {
            List<Object> list = new java.util.ArrayList<>();
            list.add(null);
            assertTrue(arrayCursorOf(list).elementIsNull());
        }

        @Test @DisplayName("null element -> elementValueAsInt 0")
        void nullElementToInt() {
            List<Object> list = new java.util.ArrayList<>();
            list.add(null);
            assertEquals(0, arrayCursorOf(list).elementValueAsInt());
        }

        @Test @DisplayName("elementValueAsJsonValue() class matches for each numeric type")
        void elementJsonValueClasses() {
            record Case(Object input, Class<? extends JsonValue> expected) {}
            List<Case> cases = List.of(
                new Case((byte)  1,           JsonByte.class),
                new Case((short) 2,           JsonShort.class),
                new Case(3,                   JsonInteger.class),
                new Case(4L,                  JsonLong.class),
                new Case(5.0f,                JsonFloat.class),
                new Case(6.0,                 JsonDouble.class),
                new Case(true,                JsonBoolean.class),
                new Case("hello",             JsonString.class)
            );

            for (Case tc : cases) {
                List<Object> list = new java.util.ArrayList<>();
                list.add(tc.input());
                MapJsonCursor c = new MapJsonCursor(list);
                assertTrue(c.enterArray());
                assertTrue(c.nextElement());
                JsonValue jv = c.elementValueAsJsonValue();
                assertEquals(tc.expected(), jv.getClass(),
                    "Wrong JsonValue class for input type " + tc.input().getClass().getSimpleName());
            }
        }
    }

    @Nested
    @DisplayName("Sub-cursor type dispatch")
    class SubCursorTypes {

        @Test @DisplayName("Nested Map -> fieldValueCursor() enters as object")
        void nestedMapCursor() {
            Map<String, Object> inner = new LinkedHashMap<>();
            inner.put("x", 99);
            MapJsonCursor c = cursorOf(Map.of("v", inner));
            MapJsonCursor sub = (MapJsonCursor) c.fieldValueCursor();
            assertTrue(sub.enterObject());
            assertTrue(sub.nextField());
            assertEquals("x", sub.fieldNameAsString());
            assertEquals(99, sub.fieldValueAsInt());
        }

        @Test @DisplayName("Nested List -> fieldValueCursor() enters as array")
        void nestedListCursor() {
            MapJsonCursor c = cursorOf(Map.of("v", List.of(10, 20, 30)));
            MapJsonCursor sub = (MapJsonCursor) c.fieldValueCursor();
            assertTrue(sub.enterArray());
            assertTrue(sub.nextElement());
            assertEquals(10, sub.elementValueAsInt());
        }

        @Test @DisplayName("Scalar value -> fieldValueCursor() is scalar sub-cursor")
        void scalarSubCursor() {
            MapJsonCursor c = cursorOf(Map.of("v", 42));
            // scalar sub-cursor: enterObject and enterArray both false
            MapJsonCursor sub = (MapJsonCursor) c.fieldValueCursor();
            assertFalse(sub.enterArray(),  "scalar cursor must not enter as array");
        }
    }

    // =========================================================
    // 6. JsonAdapter.readValue(Map, Class) integration
    // =========================================================

    @Nested
    @DisplayName("JsonAdapter.readValue(Map, Class) integration")
    class AdapterMapIntegration {

        @Test @DisplayName("SimplePojo from integer-typed map values")
        void simplePojoFromMap() {
            Map<String, Object> map = new LinkedHashMap<>();
            map.put("id",   42);
            map.put("name", "wildered");
            SimplePojo pojo = adapter.readValue(map, SimplePojo.class);
            assertNotNull(pojo);
            assertEquals(42,         pojo.id);
            assertEquals("wildered", pojo.name);
        }

        @Test @DisplayName("SimplePojo from Long id - narrowed to int field")
        void simplePojoFromLongId() {
            Map<String, Object> map = new LinkedHashMap<>();
            map.put("id",   (long) 7);
            map.put("name", "x");
            SimplePojo pojo = adapter.readValue(map, SimplePojo.class);
            assertEquals(7, pojo.id);
        }

        @Test @DisplayName("SimplePojo from String id - parsed to int")
        void simplePojoFromStringId() {
            Map<String, Object> map = new LinkedHashMap<>();
            map.put("id",   "55");
            map.put("name", "y");
            SimplePojo pojo = adapter.readValue(map, SimplePojo.class);
            assertEquals(55, pojo.id);
        }

        @Test @DisplayName("NestedPojo from nested Map value")
        void nestedPojoFromMap() {
            Map<String, Object> child = new LinkedHashMap<>();
            child.put("id",   3);
            child.put("name", "c");
            Map<String, Object> outer = new LinkedHashMap<>();
            outer.put("child", child);
            NestedPojo pojo = adapter.readValue(outer, NestedPojo.class);
            assertNotNull(pojo.child);
            assertEquals(3,   pojo.child.id);
            assertEquals("c", pojo.child.name);
        }

        @Test @DisplayName("Unknown fields in map are silently skipped")
        void unknownFieldsSkipped() {
            Map<String, Object> map = new LinkedHashMap<>();
            map.put("id",          1);
            map.put("name",        "a");
            map.put("unknown_key", "should be ignored");
            SimplePojo pojo = adapter.readValue(map, SimplePojo.class);
            assertNotNull(pojo);
            assertEquals(1, pojo.id);
        }

        @Test @DisplayName("Empty map produces zero-value POJO")
        void emptyMapToZeroPojo() {
            SimplePojo pojo = adapter.readValue(new LinkedHashMap<>(), SimplePojo.class);
            assertNotNull(pojo);
            assertEquals(0,    pojo.id);
            assertNull(pojo.name);
        }
    }

    @Nested
    @DisplayName("fieldNameHash() FNV-1a consistency")
    class FieldNameHash {

        /** Replicates the FNV-1a algorithm from MapJsonCursor for ASCII keys. */
        private int fnv1a(String key) {
            int h = 0x811c9dc5;
            for (int i = 0; i < key.length(); i++) {
                h ^= (key.charAt(i) & 0xFF);
                h *= 0x01000193;
            }
            return h;
        }

        @Test @DisplayName("ASCII key hash matches reference FNV-1a")
        void asciiHash() {
            MapJsonCursor c = cursorOf(Map.of("id", 1));
            assertEquals(fnv1a("id"), c.fieldNameHash());
        }

        @Test @DisplayName("Different keys produce different hashes")
        void differentKeysDistinct() {
            MapJsonCursor c1 = cursorOf(Map.of("id",   1));
            MapJsonCursor c2 = cursorOf(Map.of("name", 1));
            assertNotEquals(c1.fieldNameHash(), c2.fieldNameHash());
        }

        @Test @DisplayName("Same key always produces same hash (stability)")
        void hashIsStable() {
            MapJsonCursor c = cursorOf(Map.of("radius", 1));
            int h1 = c.fieldNameHash();
            int h2 = c.fieldNameHash();
            assertEquals(h1, h2);
        }
    }
}
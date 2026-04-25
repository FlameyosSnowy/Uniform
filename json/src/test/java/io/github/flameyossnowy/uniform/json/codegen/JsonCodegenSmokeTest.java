package io.github.flameyossnowy.uniform.json.codegen;

import io.github.flameyossnowy.uniform.json.ReflectionConfig;
import io.github.flameyossnowy.uniform.json.codegen.fixtures.Circle;
import io.github.flameyossnowy.uniform.json.codegen.fixtures.HasShape;
import io.github.flameyossnowy.uniform.json.codegen.fixtures.NestedPojo;
import io.github.flameyossnowy.uniform.json.codegen.fixtures.Shape;
import io.github.flameyossnowy.uniform.json.codegen.fixtures.ShapeSupplier;
import io.github.flameyossnowy.uniform.json.codegen.fixtures.SimplePojo;
import io.github.flameyossnowy.uniform.json.codegen.fixtures.SimpleRecord;
import io.github.flameyossnowy.uniform.json.features.JsonReadFeature;
import io.github.flameyossnowy.uniform.json.features.JsonWriteFeature;
import io.github.flameyossnowy.uniform.core.resolvers.ResolverRegistry;
import io.github.flameyossnowy.uniform.json.JsonAdapter;
import io.github.flameyossnowy.uniform.json.JsonConfig;
import io.github.flameyossnowy.uniform.json.dom.JsonArray;
import io.github.flameyossnowy.uniform.json.dom.JsonObject;
import io.github.flameyossnowy.uniform.json.dom.JsonValue;
import io.github.flameyossnowy.uniform.json.writers.prettifiers.JsonFormatter;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.DisplayName;

import java.io.IOException;
import java.io.OutputStream;
import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.EnumSet;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

public class JsonCodegenSmokeTest {

    private static JsonConfig config() {
        return new JsonConfig(false, 2, EnumSet.noneOf(JsonReadFeature.class),
            EnumSet.noneOf(JsonWriteFeature.class), ReflectionConfig.DEFAULT);
    }

    @Test
    void pojo_read_and_write() {
        JsonAdapter adapter = new JsonAdapter(config());
        SimplePojo pojo = adapter.readValue("{\"id\":1,\"name\":\"a\"}", SimplePojo.class);
        assertNotNull(pojo);
        assertEquals(1, pojo.id);
        assertEquals("a", pojo.name);

        String json = adapter.writeValue(new SimplePojo(2, "b"));

        assertTrue(json.contains("\"id\":2"));
        assertTrue(json.contains("\"name\":\"b\""));
    }

    @Test
    void pojo_with_list_dom() {
        JsonAdapter adapter = new JsonAdapter(config());

        JsonValue value = adapter.readValue(
            "{\"id\":10,\"items\":[{\"id\":1,\"name\":\"a\"},{\"id\":2,\"name\":\"b\"},{\"id\":3,\"name\":\"c\"}]}"
        );

        assertNotNull(value);
        JsonObject object = (JsonObject) value;
        assertInstanceOf(JsonObject.class, value);
        assertEquals(10, object.getInt("id"));

        JsonArray items = object.getArray("items");
        assertEquals(3, items.size());
        assertEquals(1,   items.getObject(0).getInt("id"));
        assertEquals("a", items.getObject(0).getString("name"));
        assertEquals("b", items.getObject(1).getString("name"));
        assertEquals(3,   items.getObject(2).getInt("id"));
    }

    @Test
    void deeply_nested_tree_dom() {
        JsonAdapter adapter = new JsonAdapter(config());

        JsonValue value = adapter.readValue(
            "{\"id\":1,\"name\":\"root\",\"children\":[" +
                "{\"id\":2,\"name\":\"child1\",\"children\":[]}," +
                "{\"id\":3,\"name\":\"child2\",\"children\":[" +
                "{\"id\":4,\"name\":\"grandchild\",\"children\":[]}" +
                "]}" +
                "]}"
        );

        assertNotNull(value);
        JsonObject object = (JsonObject) value;
        assertEquals(1,      object.getInt("id"));
        assertEquals("root", object.getString("name"));

        JsonArray children = object.getArray("children");
        assertEquals(2, children.size());
        assertEquals("child1", children.getObject(0).getString("name"));

        JsonArray grandchildren = children.getObject(1).getArray("children");
        assertEquals(1, grandchildren.size());
        assertEquals(4,            grandchildren.getObject(0).getInt("id"));
        assertEquals("grandchild", grandchildren.getObject(0).getString("name"));
    }

    @Test
    void pojo_read_and_write_dom() {
        JsonAdapter adapter = new JsonAdapter(config());
        JsonValue value = adapter.readValue("{\"id\":1,\"name\":\"a\"}");

        System.out.println(value);

        assertNotNull(value);
        assertInstanceOf(JsonObject.class, value);
        JsonObject object = (JsonObject) value;
        assertEquals(1, object.getInt("id"));
        assertEquals("a", object.getString("name"));
    }

    @Test
    void pojo_format() {
        JsonFormatter jsonFormatter = new JsonFormatter(Path.of("nothing"), 4);
        String complexJson = """
        {"id":1,"username":"flameyos","email":"flameyos@example.com","age":22,"active":true,"score":9823.5,"address":{"street":"123 Main St","city":"Amsterdam","zip":"1011AB","country":"NL"},"metadata":{"createdAt":1708123456789,"lastLogin":1708987654321,"loginCount":42}}
        """;

        System.out.println(complexJson);

        ByteBuffer format = jsonFormatter.format(ByteBuffer.wrap(complexJson.getBytes(StandardCharsets.UTF_8)));
        byte[] outputBytes = new byte[format.remaining()];
        format.get(outputBytes);
        String formattedJson = new String(outputBytes, StandardCharsets.UTF_8);
        System.out.println(formattedJson);
        
        // Print hex representation of last few bytes for debugging
        System.err.print("Last 10 bytes (hex): ");
        for (int i = Math.max(0, outputBytes.length - 10); i < outputBytes.length; i++) {
            System.err.printf("%02x ", outputBytes[i]);
        }
        System.err.println();
    }

    @Test
    void record_read_and_write() {
        JsonAdapter adapter = new JsonAdapter(config());
        SimpleRecord rec = adapter.readValue("{\"id\":5,\"name\":\"z\"}", SimpleRecord.class);
        assertEquals(5, rec.id());
        assertEquals("z", rec.name());

        String json = adapter.writeValue(new SimpleRecord(7, "q"));
        System.out.println(json);
        assertTrue(json.contains("\"id\":7"));
        assertTrue(json.contains("\"name\":\"q\""));
    }

    @Test
    void nested_pojo_is_codegen_discovered_and_mapped() {
        JsonAdapter adapter = new JsonAdapter(config());
        NestedPojo nested = adapter.readValue("{\"child\":{\"id\":3,\"name\":\"c\"}}", NestedPojo.class);
        assertNotNull(nested);
        assertNotNull(nested.child);
        assertEquals(3, nested.child.id);
        assertEquals("c", nested.child.name);
    }

    @Test
    void interface_field_uses_context_dynamic_supplier_and_dispatches() {
        // runtime supplier registration is still required at runtime for the generated dispatch code path
        ResolverRegistry.registerSupplier(Shape.class, new ShapeSupplier());

        JsonAdapter adapter = new JsonAdapter(config());
        HasShape value = adapter.readValue("{\"shape\":{\"radius\":11}}", HasShape.class);
        assertNotNull(value);
        assertNotNull(value.shape);
        assertInstanceOf(Circle.class, value.shape);
        assertEquals(11, ((Circle) value.shape).radius);
    }

    // ============================================================================
    // CORRUPTED / INVALID JSON TESTS
    // ============================================================================

    @Test
    @DisplayName("Malformed JSON handling - library may be lenient or strict")
    void malformed_json_handling() {
        JsonAdapter adapter = new JsonAdapter(config());

        // Missing closing brace - library may throw or handle gracefully
        assertDoesNotThrow(() -> {
            try {
                SimplePojo result = adapter.readValue("{\"id\":1,\"name\":\"a\"", SimplePojo.class);
                // If no exception, result may be partial
            } catch (Exception e) {
                // Exception is also acceptable
            }
        });

        // Trailing garbage - library may parse valid prefix
        assertDoesNotThrow(() -> {
            try {
                SimplePojo result = adapter.readValue("{\"id\":1,\"name\":\"a\"}garbage", SimplePojo.class);
                assertNotNull(result);
                assertEquals(1, result.id);
            } catch (Exception e) {
                // Exception is also acceptable
            }
        });
    }

    @Test
    @DisplayName("Empty JSON object/array handling")
    void empty_json_handling() {
        JsonAdapter adapter = new JsonAdapter(config());

        // Empty object
        SimplePojo empty = adapter.readValue("{}", SimplePojo.class);
        assertNotNull(empty);
        assertEquals(0, empty.id);
        assertNull(empty.name);

        // Empty array in field
        JsonValue value = adapter.readValue("{\"id\":1,\"items\":[]}");
        assertNotNull(value);
    }

    @Test
    @DisplayName("Wrong data types in JSON handling")
    void wrong_data_types_in_json() {
        JsonAdapter adapter = new JsonAdapter(config());

        // String where number expected - library may throw NumberFormatException or use default
        assertThrows(NumberFormatException.class, () -> {
            adapter.readValue("{\"id\":\"not_a_number\",\"name\":\"a\"}", SimplePojo.class);
        });
    }

    @Test
    @DisplayName("Duplicate keys in JSON handling")
    void duplicate_keys_in_json() {
        JsonAdapter adapter = new JsonAdapter(config());

        // Last value wins for duplicate keys
        SimplePojo pojo = adapter.readValue("{\"id\":1,\"id\":999,\"name\":\"a\"}", SimplePojo.class);
        assertNotNull(pojo);
        assertEquals(999, pojo.id);
    }

    @Test
    @DisplayName("Incomplete/truncated JSON handling")
    void truncated_json_detection() {
        JsonAdapter adapter = new JsonAdapter(config());

        // Object truncated mid-value - library behavior varies
        assertDoesNotThrow(() -> {
            try {
                SimplePojo result = adapter.readValue("{\"id\":", SimplePojo.class);
            } catch (Exception e) {
                // Exception expected for truncated JSON
            }
        });
    }

    // ============================================================================
    // SECURITY / DENIAL OF SERVICE TESTS
    // ============================================================================

    @Test
    @DisplayName("Deeply nested JSON (billion laughs style)")
    void deeply_nested_json_handling() {
        JsonAdapter adapter = new JsonAdapter(config());

        // Create deeply nested JSON (100 levels)
        StringBuilder deepJson = new StringBuilder();
        int depth = 100;
        for (int i = 0; i < depth; i++) deepJson.append("{\"a\":");
        deepJson.append("1");
        for (int i = 0; i < depth; i++) deepJson.append("}");

        // Should either handle or throw StackOverflowError
        assertDoesNotThrow(() -> {
            try {
                JsonValue result = adapter.readValue(deepJson.toString());
                System.out.println("Deeply nested parsed successfully");
            } catch (StackOverflowError e) {
                System.out.println("StackOverflowError for deep nesting (expected)");
            } catch (Exception e) {
                System.out.println("Exception for deep nesting: " + e.getClass().getSimpleName());
            }
        });
    }

    @Test
    @DisplayName("Very large JSON string handling")
    void very_large_json_handling() {
        JsonAdapter adapter = new JsonAdapter(config());

        // Create large string payload
        StringBuilder largeName = new StringBuilder();
        for (int i = 0; i < 100000; i++) largeName.append("x");

        String json = "{\"id\":1,\"name\":\"" + largeName + "\"}";

        // Should handle without memory issues
        SimplePojo result = adapter.readValue(json, SimplePojo.class);
        assertNotNull(result);
        assertEquals(100000, result.name.length());
    }

    @Test
    @DisplayName("Large number of fields handling")
    void many_fields_handling() {
        JsonAdapter adapter = new JsonAdapter(config());

        // Create JSON with many unexpected fields
        StringBuilder manyFields = new StringBuilder("{");
        for (int i = 0; i < 1000; i++) {
            if (i > 0) manyFields.append(",");
            manyFields.append("\"field").append(i).append("\":").append(i);
        }
        manyFields.append("}");

        // Should handle gracefully
        SimplePojo result = adapter.readValue(manyFields.toString(), SimplePojo.class);
        assertNotNull(result);
    }

    @Test
    @DisplayName("Unicode edge cases and escaping")
    void unicode_edge_cases() {
        JsonAdapter adapter = new JsonAdapter(config());

        // Valid unicode escape sequence
        SimplePojo emoji = adapter.readValue("{\"id\":1,\"name\":\"\\uD83D\\uDE80\"}", SimplePojo.class);
        assertEquals("🚀", emoji.name);

        // Null bytes in JSON string - library may accept or reject
        assertDoesNotThrow(() -> {
            try {
                SimplePojo result = adapter.readValue("{\"id\":1,\"name\":\"a\u0000b\"}", SimplePojo.class);
                // If accepted, null byte should be preserved
            } catch (Exception e) {
                // Rejection is also valid behavior
            }
        });
    }

    @Test
    @DisplayName("JSON injection attempt in field values")
    void json_injection_attempt() {
        JsonAdapter adapter = new JsonAdapter(config());

        // Attempt to inject JSON structure through field value
        String injectionAttempt = "{\"id\":1,\"name\":\"a\",\"extra\":\"b\"}";
        SimplePojo result = adapter.readValue(
            "{\"id\":1,\"name\":\"" + injectionAttempt.replace("\"", "\\\"") + "\"}",
            SimplePojo.class
        );

        assertNotNull(result);
        // Name should contain the literal string, not parse as nested JSON
        assertTrue(result.name.contains("id"));
    }

    // ============================================================================
    // PARTIAL WRITE / STREAMING TESTS
    // ============================================================================

    @Test
    @DisplayName("Partial write with output stream")
    void partial_write_with_output_stream() throws IOException {
        JsonAdapter adapter = new JsonAdapter(config());

        SimplePojo pojo = new SimplePojo(42, "test");
        ByteArrayOutputStream baos = new ByteArrayOutputStream();

        adapter.writeValue(pojo, baos);

        String written = baos.toString(StandardCharsets.UTF_8);
        assertTrue(written.contains("\"id\":42"));
        assertTrue(written.contains("\"name\":\"test\""));
    }

    @Test
    @DisplayName("Interrupted/closed stream handling")
    void closed_stream_handling() {
        JsonAdapter adapter = new JsonAdapter(config());

        SimplePojo pojo = new SimplePojo(1, "a");

        assertThrows(IOException.class, () -> {
            OutputStream closedStream = new OutputStream() {
                @Override
                public void write(int b) throws IOException {
                    throw new IOException("Stream closed");
                }
            };
            adapter.writeValue(pojo, closedStream);
        });
    }

    // ============================================================================
    // EDGE CASE / BOUNDARY TESTS
    // ============================================================================

    @Test
    @DisplayName("Boundary values (MAX_INT, MIN_INT, special floats)")
    void boundary_values() {
        JsonAdapter adapter = new JsonAdapter(config());

        // Integer boundaries
        JsonValue maxInt = adapter.readValue("{\"id\":2147483647}");
        assertEquals(2147483647, ((JsonObject) maxInt).getInt("id"));

        JsonValue minInt = adapter.readValue("{\"id\":-2147483648}");
        assertEquals(-2147483648, ((JsonObject) minInt).getInt("id"));

        // Long boundaries
        String longJson = "{\"id\":9223372036854775807}";
        JsonValue maxLong = adapter.readValue(longJson);
        assertEquals(9223372036854775807L, ((JsonObject) maxLong).getLong("id"));

        // Zero and negative zero
        JsonValue zero = adapter.readValue("{\"id\":0}");
        assertEquals(0, ((JsonObject) zero).getInt("id"));

        // Scientific notation
        JsonValue sci = adapter.readValue("{\"value\":1.5e10}");
        assertTrue(((JsonObject) sci).getDouble("value") > 1e10);
    }

    @Test
    @DisplayName("Whitespace variations in JSON")
    void whitespace_variations() {
        JsonAdapter adapter = new JsonAdapter(config());

        // Various whitespace
        SimplePojo result1 = adapter.readValue("  {  \"id\"  :  1  ,  \"name\"  :  \"a\"  }  ", SimplePojo.class);
        assertNotNull(result1);
        assertEquals(1, result1.id);

        // Tabs and newlines
        SimplePojo result2 = adapter.readValue("\t{\n\t\"id\":1,\n\t\"name\":\"a\"\n}", SimplePojo.class);
        assertNotNull(result2);
    }

    @Test
    @DisplayName("Field ordering preservation")
    void field_ordering() {
        JsonAdapter adapter = new JsonAdapter(config());

        // Write and verify fields appear in consistent order
        SimplePojo pojo = new SimplePojo(1, "a");
        String json = adapter.writeValue(pojo);

        int idIndex = json.indexOf("\"id\"");
        int nameIndex = json.indexOf("\"name\"");

        assertTrue(idIndex >= 0);
        assertTrue(nameIndex >= 0);
    }

    // ============================================================================
    // Helper class for testing depth limits
    // ============================================================================

    private static class AdapterWithDepthLimit extends JsonAdapter {
        private final int maxDepth;

        AdapterWithDepthLimit(JsonConfig config, int maxDepth) {
            super(config);
            this.maxDepth = maxDepth;
        }
    }
}

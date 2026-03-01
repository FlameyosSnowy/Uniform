package me.flame.uniform.json.codegen;

import me.flame.uniform.core.resolvers.ResolverRegistry;
import me.flame.uniform.json.JsonAdapter;
import me.flame.uniform.json.JsonConfig;
import me.flame.uniform.json.codegen.fixtures.*;
import me.flame.uniform.json.dom.JsonArray;
import me.flame.uniform.json.dom.JsonObject;
import me.flame.uniform.json.writers.prettifiers.JsonFormatter;

import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.EnumSet;

import static org.junit.jupiter.api.Assertions.*;

public class JsonCodegenSmokeTest {

    private static JsonConfig config() {
        return new JsonConfig(false, 2, EnumSet.noneOf(me.flame.uniform.json.features.JsonReadFeature.class),
            EnumSet.noneOf(me.flame.uniform.json.features.JsonWriteFeature.class));
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

        JsonObject value = adapter.readValue(
            "{\"id\":10,\"items\":[{\"id\":1,\"name\":\"a\"},{\"id\":2,\"name\":\"b\"},{\"id\":3,\"name\":\"c\"}]}"
        );

        assertNotNull(value);
        assertInstanceOf(JsonObject.class, value);
        assertEquals(10, value.getInt("id"));

        JsonArray items = value.getArray("items");
        assertEquals(3, items.size());
        assertEquals(1,   items.getObject(0).getInt("id"));
        assertEquals("a", items.getObject(0).getString("name"));
        assertEquals("b", items.getObject(1).getString("name"));
        assertEquals(3,   items.getObject(2).getInt("id"));
    }

    @Test
    void deeply_nested_tree_dom() {
        JsonAdapter adapter = new JsonAdapter(config());

        JsonObject value = adapter.readValue(
            "{\"id\":1,\"name\":\"root\",\"children\":[" +
                "{\"id\":2,\"name\":\"child1\",\"children\":[]}," +
                "{\"id\":3,\"name\":\"child2\",\"children\":[" +
                "{\"id\":4,\"name\":\"grandchild\",\"children\":[]}" +
                "]}" +
                "]}"
        );

        assertNotNull(value);
        assertEquals(1,      value.getInt("id"));
        assertEquals("root", value.getString("name"));

        JsonArray children = value.getArray("children");
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
        JsonObject value = adapter.readValue("{\"id\":1,\"name\":\"a\"}");

        System.out.println(value);

        assertNotNull(value);
        assertInstanceOf(JsonObject.class, value);
        assertEquals(1, value.getInt("id"));
        assertEquals("a", value.getString("name"));
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
}

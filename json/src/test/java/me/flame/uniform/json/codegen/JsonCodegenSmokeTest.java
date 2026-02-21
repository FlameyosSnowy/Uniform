package me.flame.uniform.json.codegen;

import me.flame.uniform.core.resolvers.ResolverRegistry;
import me.flame.uniform.json.JsonAdapter;
import me.flame.uniform.json.JsonConfig;
import me.flame.uniform.json.codegen.fixtures.*;
import org.junit.jupiter.api.Test;

import java.util.EnumSet;

import static org.junit.jupiter.api.Assertions.*;

public class JsonCodegenSmokeTest {

    private static JsonConfig config() {
        return new JsonConfig(false, 2, EnumSet.noneOf(me.flame.uniform.json.features.JsonReadFeature.class),
            EnumSet.noneOf(me.flame.uniform.json.features.JsonWriteFeature.class));
    }

    @Test
    void pojo_read_and_write() {
        JsonAdapter<SimplePojo> adapter = new JsonAdapter<>(SimplePojo.class, config());
        SimplePojo pojo = adapter.readValue("{\"id\":1,\"name\":\"a\"}");
        assertNotNull(pojo);
        assertEquals(1, pojo.id);
        assertEquals("a", pojo.name);

        String json = adapter.writeValue(new SimplePojo(2, "b"));
        assertTrue(json.contains("\"id\":2"));
        assertTrue(json.contains("\"name\":\"b\""));
    }

    @Test
    void record_read_and_write() {
        JsonAdapter<SimpleRecord> adapter = new JsonAdapter<>(SimpleRecord.class, config());
        SimpleRecord rec = adapter.readValue("{\"id\":5,\"name\":\"z\"}");
        assertEquals(5, rec.id());
        assertEquals("z", rec.name());

        String json = adapter.writeValue(new SimpleRecord(7, "q"));
        assertTrue(json.contains("\"id\":7"));
        assertTrue(json.contains("\"name\":\"q\""));
    }

    @Test
    void nested_pojo_is_codegen_discovered_and_mapped() {
        JsonAdapter<NestedPojo> adapter = new JsonAdapter<>(NestedPojo.class, config());
        NestedPojo nested = adapter.readValue("{\"child\":{\"id\":3,\"name\":\"c\"}}");
        assertNotNull(nested);
        assertNotNull(nested.child);
        assertEquals(3, nested.child.id);
        assertEquals("c", nested.child.name);
    }

    @Test
    void interface_field_uses_context_dynamic_supplier_and_dispatches() {
        // runtime supplier registration is still required at runtime for the generated dispatch code path
        ResolverRegistry.registerSupplier(Shape.class, new ShapeSupplier());

        JsonAdapter<HasShape> adapter = new JsonAdapter<>(HasShape.class, config());
        HasShape value = adapter.readValue("{\"shape\":{\"radius\":11}}");
        assertNotNull(value);
        assertNotNull(value.shape);
        assertInstanceOf(Circle.class, value.shape);
        assertEquals(11, ((Circle) value.shape).radius);
    }
}

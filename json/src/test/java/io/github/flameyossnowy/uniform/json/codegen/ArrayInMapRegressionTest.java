package io.github.flameyossnowy.uniform.json.codegen;

import io.github.flameyossnowy.uniform.core.annotations.SerializedObject;
import io.github.flameyossnowy.uniform.json.JsonAdapter;
import io.github.flameyossnowy.uniform.json.JsonConfig;
import io.github.flameyossnowy.uniform.json.features.JsonReadFeature;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

public class ArrayInMapRegressionTest {

    @SerializedObject
    public static class Product {
        public int id;
        public Map<String, String[]> info;

        @Override
        public String toString() {
            return "Product{id=" + id + ", info=" + info + '}';
        }
    }

    private static JsonAdapter adapter() {
        JsonConfig config = JsonAdapter.configBuilder()
                .addReadFeatures(JsonReadFeature.ALLOW_JAVA_COMMENTS)
                .build();
        return new JsonAdapter(config);
    }

    @Test
    @DisplayName("Writing Map<String, String[]> no longer tries to build a reflection writer for String[]")
    void write_map_of_string_arrays() {
        Map<String, String[]> map = new HashMap<>();
        map.put("infoA", new String[]{"aa", "bb"});
        map.put("infoB", new String[]{"cc", "dd"});

        String json = adapter().writeValue(map);

        assertEquals("{\"infoA\":[\"aa\",\"bb\"],\"infoB\":[\"cc\",\"dd\"]}", json);
    }

    @Test
    @DisplayName("Reading a generated type with a Map<String, String[]> field no longer tries to build a reflection reader for String[]")
    void read_object_with_map_of_string_arrays() {
        Product product = adapter().readValue(
                "{\"id\":123,\"info\":{\"infoA\":[\"aa\",\"bb\"]}}", Product.class);

        assertNotNull(product);
        assertEquals(123, product.id);
        assertNotNull(product.info);
        assertEquals(1, product.info.size());
        assertArrayEquals(new String[]{"aa", "bb"}, product.info.get("infoA"));
    }

    @Test
    @DisplayName("Round trip of a generated type with Map<String, String[]>")
    void round_trip_object_with_map_of_string_arrays() {
        JsonAdapter adapter = adapter();

        Product original = new Product();
        original.id = 7;
        original.info = new HashMap<>();
        original.info.put("infoA", new String[]{"aa", "bb"});
        original.info.put("infoB", new String[]{"cc"});

        Product copy = adapter.readValue(adapter.writeValue(original), Product.class);

        assertEquals(7, copy.id);
        assertEquals(original.info.keySet(), copy.info.keySet());
        assertArrayEquals(original.info.get("infoA"), copy.info.get("infoA"));
        assertArrayEquals(original.info.get("infoB"), copy.info.get("infoB"));
    }
}
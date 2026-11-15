package io.github.flameyossnowy.uniform.json.codegen;

import io.github.flameyossnowy.uniform.core.annotations.SerializedObject;
import io.github.flameyossnowy.uniform.json.JsonAdapter;
import io.github.flameyossnowy.uniform.json.exceptions.JsonException;
import io.github.flameyossnowy.uniform.json.features.JsonReadFeature;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

public class CompetingAdaptersReaderTest {

    @SerializedObject
    public static class Child {
        public int value;
    }

    @SerializedObject
    public static class DirectEnvelope {
        public Child child;
    }

    @SerializedObject
    public static class ListEnvelope {
        public List<Child> children;
    }

    @SerializedObject
    public static class MapEnvelope {
        public Map<String, Child> children;
    }

    private static JsonAdapter permissiveAdapter() {
        return new JsonAdapter(JsonAdapter.configBuilder().build());
    }

    private static JsonAdapter strictAdapter() {
        return new JsonAdapter(JsonAdapter.configBuilder()
                .addReadFeatures(JsonReadFeature.STRICT_DUPLICATE_DETECTION)
                .build());
    }

    @Test
    @DisplayName("Competing adapters retain independent reader configurations")
    void competing_adapters_direct_nested_reading() {
        JsonAdapter permissive = permissiveAdapter();
        JsonAdapter strict = strictAdapter();

        permissive.readValue(
                "{\"child\":{\"value\":1}}", DirectEnvelope.class);

        strict.readValue(
                "{\"child\":{\"value\":1}}", DirectEnvelope.class);

        String duplicate = "{\"child\":{\"value\":1,\"value\":2}}";

        assertEquals(2, permissive.readValue(
                duplicate, DirectEnvelope.class).child.value);

        assertThrows(JsonException.class, () ->
                strict.readValue(duplicate, DirectEnvelope.class));

        assertEquals(2, permissive.readValue(
                duplicate, DirectEnvelope.class).child.value);
    }

    @Test
    @DisplayName("Competing adapters retain independent configurations inside lists")
    void competing_adapters_list_element_reading() {
        JsonAdapter permissive = permissiveAdapter();
        JsonAdapter strict = strictAdapter();

        String valid = "{\"children\":[{\"value\":1}]}";

        permissive.readValue(valid, ListEnvelope.class);
        strict.readValue(valid, ListEnvelope.class);

        String duplicate =
                "{\"children\":[{\"value\":1,\"value\":2}]}";

        ListEnvelope result = permissive.readValue(
                duplicate, ListEnvelope.class);

        assertEquals(2, result.children.getFirst().value);

        assertThrows(JsonException.class, () ->
                strict.readValue(duplicate, ListEnvelope.class));
    }

    @Test
    @DisplayName("Competing adapters retain independent configurations inside map values")
    void competing_adapters_map_value_reading() {
        JsonAdapter permissive = permissiveAdapter();
        JsonAdapter strict = strictAdapter();

        String valid = "{\"children\":{\"first\":{\"value\":1}}}";

        permissive.readValue(valid, MapEnvelope.class);
        strict.readValue(valid, MapEnvelope.class);

        String duplicate =
                "{\"children\":{\"first\":{\"value\":1,\"value\":2}}}";

        MapEnvelope result = permissive.readValue(
                duplicate, MapEnvelope.class);

        assertEquals(2, result.children.get("first").value);

        assertThrows(JsonException.class, () ->
                strict.readValue(duplicate, MapEnvelope.class));
    }

    @Test
    @DisplayName("Concurrent adapters do not leak reader configuration")
    void concurrent_adapters_keep_configuration_isolated() throws Exception {
        JsonAdapter permissive = permissiveAdapter();
        JsonAdapter strict = strictAdapter();

        String duplicate =
                "{\"child\":{\"value\":1,\"value\":2}}";

        permissive.readValue(
                "{\"child\":{\"value\":1}}", DirectEnvelope.class);

        strict.readValue(
                "{\"child\":{\"value\":1}}", DirectEnvelope.class);

        var executor = java.util.concurrent.Executors.newFixedThreadPool(2);

        try {
            var permissiveTask = executor.submit(() -> {
                for (int i = 0; i < 1_000; i++) {
                    DirectEnvelope result =
                            permissive.readValue(duplicate, DirectEnvelope.class);

                    assertEquals(2, result.child.value);
                }
            });

            var strictTask = executor.submit(() -> {
                for (int i = 0; i < 1_000; i++) {
                    assertThrows(JsonException.class,
                            () -> strict.readValue(duplicate, DirectEnvelope.class));
                }
            });

            permissiveTask.get();
            strictTask.get();
        } finally {
            executor.shutdownNow();
        }
    }
}
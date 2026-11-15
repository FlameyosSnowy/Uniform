package io.github.flameyossnowy.uniform.json.codegen;

import io.github.flameyossnowy.uniform.core.annotations.SerializedObject;
import io.github.flameyossnowy.uniform.json.JsonAdapter;
import io.github.flameyossnowy.uniform.json.features.JsonWriteFeature;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

public class CompetingAdaptersWriterTest {

    @SerializedObject
    public static class Child {
        public String name = "child";
        public String optional;
    }

    @SerializedObject
    public static class Envelope {
        public Child child;
        public List<Child> children;
        public Map<String, Child> indexed;
    }

    private static JsonAdapter omitNullsAdapter() {
        return new JsonAdapter(JsonAdapter.configBuilder().build());
    }

    private static JsonAdapter includeNullsAdapter() {
        return new JsonAdapter(JsonAdapter.configBuilder()
                .addWriteFeatures(JsonWriteFeature.WRITE_NULL_MAP_VALUES)
                .build());
    }

    private static Envelope createEnvelope() {
        Envelope envelope = new Envelope();

        envelope.child = new Child();
        envelope.children = List.of(new Child());
        envelope.indexed = Map.of("first", new Child());

        return envelope;
    }

    private static long countOccurrences(String text, String needle) {
        return (text.length() - text.replace(needle, "").length())
                / needle.length();
    }

    @Test
    @DisplayName("Competing adapters retain independent writer configurations")
    void competing_adapters_nested_writing() {
        JsonAdapter omitNulls = omitNullsAdapter();
        JsonAdapter includeNulls = includeNullsAdapter();

        Envelope envelope = createEnvelope();

        omitNulls.writeValue(envelope);
        includeNulls.writeValue(envelope);

        String omitted = omitNulls.writeValue(envelope);
        String included = includeNulls.writeValue(envelope);

        assertEquals(0, countOccurrences(
                omitted, "\"optional\":null"));

        assertEquals(3, countOccurrences(
                included, "\"optional\":null"));

        String omittedAgain = omitNulls.writeValue(envelope);

        assertEquals(0, countOccurrences(
                omittedAgain, "\"optional\":null"));
    }
}
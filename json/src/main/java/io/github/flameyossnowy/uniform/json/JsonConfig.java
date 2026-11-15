package io.github.flameyossnowy.uniform.json;

import io.github.flameyossnowy.uniform.json.features.JsonReadFeature;
import io.github.flameyossnowy.uniform.json.features.JsonWriteFeature;

import java.sql.Ref;
import java.util.EnumSet;

public record JsonConfig(
    boolean asyncWrites,
    int indentSize,
    EnumSet<JsonReadFeature> readFeatures,
    EnumSet<JsonWriteFeature> writeFeatures,
    ReflectionConfig reflectionConfig
) {
    private static final JsonConfig DEFAULTS = new JsonConfig(false, 4, EnumSet.noneOf(JsonReadFeature.class), EnumSet.noneOf(JsonWriteFeature.class), ReflectionConfig.DEFAULT);

    public static JsonConfig defaults() {
        return DEFAULTS;
    }
    
    public JsonConfig {
        if (readFeatures == null) {
            readFeatures = EnumSet.noneOf(JsonReadFeature.class);
        }
        if (writeFeatures == null) {
            writeFeatures = EnumSet.noneOf(JsonWriteFeature.class);
        }
    }
    
    public boolean hasReadFeature(JsonReadFeature feature) {
        return readFeatures.contains(feature);
    }
    
    public boolean hasWriteFeature(JsonWriteFeature feature) {
        return writeFeatures.contains(feature);
    }
}

package me.flame.uniform.json;

import me.flame.uniform.json.features.JsonReadFeature;
import me.flame.uniform.json.features.JsonWriteFeature;
import me.flame.uniform.core.resolvers.TypeResolver;
import me.flame.uniform.core.resolvers.ContextDynamicTypeSupplier;

import java.util.EnumSet;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public record JsonConfig(
    boolean asyncWrites,
    int indentSize,
    EnumSet<JsonReadFeature> readFeatures,
    EnumSet<JsonWriteFeature> writeFeatures
) {
    
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

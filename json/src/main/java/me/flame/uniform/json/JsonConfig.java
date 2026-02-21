package me.flame.uniform.json;

import me.flame.uniform.json.features.JsonReadFeature;
import me.flame.uniform.json.features.JsonWriteFeature;

import java.util.EnumSet;

public record JsonConfig(boolean asyncWrites, int indentSize, EnumSet<JsonReadFeature> readFeatures,
                         EnumSet<JsonWriteFeature> writeFeatures) {
}

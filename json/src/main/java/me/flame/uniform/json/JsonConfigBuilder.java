package me.flame.uniform.json;

import me.flame.uniform.json.features.JsonReadFeature;
import me.flame.uniform.json.features.JsonWriteFeature;

import java.util.Arrays;
import java.util.Collection;
import java.util.EnumSet;

public class JsonConfigBuilder {
    private boolean asyncWrites = false;
    private int indentSize = 4;
    private EnumSet<JsonReadFeature> readFeatures;
    private EnumSet<JsonWriteFeature> writeFeatures;

    public JsonConfigBuilder setAsyncWrites(boolean asyncWrites) {
        this.asyncWrites = asyncWrites;
        return this;
    }

    public JsonConfigBuilder setIndentSize(int indentSize) {
        this.indentSize = indentSize;
        return this;
    }

    public JsonConfigBuilder setReadFeatures(EnumSet<JsonReadFeature> readFeatures) {
        this.readFeatures = readFeatures;
        return this;
    }

    public JsonConfigBuilder setWriteFeatures(EnumSet<JsonWriteFeature> writeFeatures) {
        this.writeFeatures = writeFeatures;
        return this;
    }

    public JsonConfigBuilder addReadFeatures(Collection<JsonReadFeature> readFeatures) {
        this.readFeatures.addAll(readFeatures);
        return this;
    }

    public JsonConfigBuilder addWriteFeatures(Collection<JsonWriteFeature> writeFeatures) {
        this.writeFeatures.addAll(writeFeatures);
        return this;
    }

    public JsonConfigBuilder addReadFeatures(JsonReadFeature... readFeatures) {
        this.readFeatures.addAll(Arrays.asList(readFeatures));
        return this;
    }

    public JsonConfigBuilder addWriteFeatures(JsonWriteFeature... writeFeatures) {
        this.writeFeatures.addAll(Arrays.asList(writeFeatures));
        return this;
    }

    public JsonConfig build() {
        return new JsonConfig(asyncWrites, indentSize, readFeatures, writeFeatures);
    }
}
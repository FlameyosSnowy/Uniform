package io.github.flameyossnowy.uniform.json;

import io.github.flameyossnowy.uniform.core.resolvers.ResolverRegistry;
import io.github.flameyossnowy.uniform.json.features.JsonReadFeature;
import io.github.flameyossnowy.uniform.json.features.JsonWriteFeature;
import io.github.flameyossnowy.uniform.core.resolvers.ContextDynamicTypeSupplier;
import io.github.flameyossnowy.uniform.json.resolvers.CoreTypeResolver;
import io.github.flameyossnowy.uniform.json.resolvers.CoreTypeResolverRegistry;

import java.util.Arrays;
import java.util.Collection;
import java.util.EnumSet;

public class JsonConfigBuilder {
    private boolean asyncWrites = false;
    private int indentSize = 4;
    private EnumSet<JsonReadFeature> readFeatures = EnumSet.noneOf(JsonReadFeature.class);
    private EnumSet<JsonWriteFeature> writeFeatures = EnumSet.noneOf(JsonWriteFeature.class);

    public JsonConfigBuilder setAsyncWrites(boolean asyncWrites) {
        this.asyncWrites = asyncWrites;
        return this;
    }

    public JsonConfigBuilder setIndentSize(int indentSize) {
        this.indentSize = indentSize;
        return this;
    }

    public JsonConfigBuilder setReadFeatures(EnumSet<JsonReadFeature> readFeatures) {
        this.readFeatures = readFeatures != null ? readFeatures : EnumSet.noneOf(JsonReadFeature.class);
        return this;
    }

    public JsonConfigBuilder setWriteFeatures(EnumSet<JsonWriteFeature> writeFeatures) {
        this.writeFeatures = writeFeatures != null ? writeFeatures : EnumSet.noneOf(JsonWriteFeature.class);
        return this;
    }

    public JsonConfigBuilder addReadFeatures(Collection<JsonReadFeature> readFeatures) {
        if (readFeatures != null) {
            this.readFeatures.addAll(readFeatures);
        }
        return this;
    }

    public JsonConfigBuilder addWriteFeatures(Collection<JsonWriteFeature> writeFeatures) {
        if (writeFeatures != null) {
            this.writeFeatures.addAll(writeFeatures);
        }
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

    public <T> JsonConfigBuilder addTypeResolver(CoreTypeResolver<? extends T> resolver) {
        CoreTypeResolverRegistry.INSTANCE.register(resolver);
        return this;
    }

    public <T> JsonConfigBuilder addTypeSupplier(Class<T> type, ContextDynamicTypeSupplier<? extends T> supplier) {
        ResolverRegistry.registerSupplier(type, supplier);
        return this;
    }
    
    public JsonConfigBuilder withDefaultReadFeatures() {
        for (JsonReadFeature feature : JsonReadFeature.ALL) {
            if (feature.isDefaultValue()) {
                this.readFeatures.add(feature);
            }
        }
        return this;
    }
    
    public JsonConfigBuilder withDefaultWriteFeatures() {
        for (JsonWriteFeature feature : JsonWriteFeature.ALL) {
            if (feature.isDefaultValue()) {
                this.writeFeatures.add(feature);
            }
        }
        return this;
    }

    public JsonConfig build() {
        return new JsonConfig(asyncWrites, indentSize, readFeatures, writeFeatures);
    }
}
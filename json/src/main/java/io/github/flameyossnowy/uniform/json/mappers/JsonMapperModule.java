package io.github.flameyossnowy.uniform.json.mappers;

import io.github.flameyossnowy.uniform.json.JsonConfig;

public interface JsonMapperModule {
    void register(JsonMapperRegistry registry);

    default void register(JsonMapperRegistry registry, JsonConfig config) {
        register(registry);
    }
}
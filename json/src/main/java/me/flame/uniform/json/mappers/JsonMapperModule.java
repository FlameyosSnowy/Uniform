package me.flame.uniform.json.mappers;

import me.flame.uniform.json.JsonConfig;

public interface JsonMapperModule {
    void register(JsonMapperRegistry registry);

    default void register(JsonMapperRegistry registry, JsonConfig config) {
        register(registry);
    }
}
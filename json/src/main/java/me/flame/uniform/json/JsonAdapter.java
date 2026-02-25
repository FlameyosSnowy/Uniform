package me.flame.uniform.json;

import me.flame.uniform.json.mappers.JsonMapper;
import me.flame.uniform.json.mappers.JsonMapperRegistry;
import me.flame.uniform.json.mappers.JsonWriterMapper;
import me.flame.uniform.json.parser.lowlevel.JsonCursors;
import me.flame.uniform.json.parser.lowlevel.JsonCursor;
import me.flame.uniform.json.writers.JsonWriter;
import me.flame.uniform.json.writers.JsonWriterFactory;
import me.flame.uniform.json.writers.JsonWriterOptions;

import org.jetbrains.annotations.NotNull;

import java.nio.charset.StandardCharsets;

public class JsonAdapter {
    private final JsonConfig config;

    public JsonAdapter(JsonConfig config) {
        this.config = config;
        JsonMapperRegistry.applyConfig(config);
    }

    public static JsonConfigBuilder builder() {
        return new JsonConfigBuilder();
    }

    @SuppressWarnings("unchecked")
    public <T> T readValue(@NotNull String json, Class<T> type) {
        byte[] bytes = json.getBytes(StandardCharsets.UTF_8);
        return readValue(bytes, type);
    }

    @SuppressWarnings("unchecked")
    public <T> T readValue(byte[] bytes, Class<T> type) {
        JsonMapper<T> mapper = (JsonMapper<T>) JsonMapperRegistry.getReader(type);
        JsonCursor cursor = JsonCursors.create(bytes);
        return mapper.map(cursor);
    }

    @SuppressWarnings("unchecked")
    public <T> @NotNull String writeValue(@NotNull T value) {
        JsonWriterMapper<T> writerMapper = (JsonWriterMapper<T>) JsonMapperRegistry.getWriter(value.getClass());
        return writerMapper.write(value);
    }

    public JsonWriter createWriter(JsonWriterOptions... options) {
        return JsonWriterFactory.builder(config)
            .addOptions(options)
            .build();
    }

    public JsonConfig getConfig() {
        return config;
    }
}
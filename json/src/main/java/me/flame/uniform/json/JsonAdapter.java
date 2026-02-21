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

public class JsonAdapter<T> {
    private final Class<T> elementType;
    private final JsonConfig config;
    private final JsonMapper<T> relatedMapper;
    private final JsonWriterMapper<T> relatedWriterMapper;

    public JsonAdapter(Class<T> elementType, JsonConfig config) {
        this.elementType = elementType;
        this.config = config;

        //noinspection unchecked
        this.relatedMapper = (JsonMapper<T>) JsonMapperRegistry.getReader(elementType);

        //noinspection unchecked
        this.relatedWriterMapper = (JsonWriterMapper<T>) JsonMapperRegistry.getWriter(elementType);
    }

    public T readValue(@NotNull String json) {
        byte[] bytes = json.getBytes(StandardCharsets.UTF_8);
        return readValue(bytes);
    }

    public T readValue(@NotNull byte[] bytes) {
        JsonCursor cursor = JsonCursors.create(bytes);
        return this.relatedMapper.map(cursor);
    }

    public @NotNull String writeValue(@NotNull T value) {
        return this.relatedWriterMapper.write(value);
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
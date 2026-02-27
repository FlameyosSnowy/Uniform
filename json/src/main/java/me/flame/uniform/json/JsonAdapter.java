package me.flame.uniform.json;

import me.flame.uniform.json.exceptions.JsonException;
import me.flame.uniform.json.mappers.JsonMapper;
import me.flame.uniform.json.mappers.JsonMapperRegistry;
import me.flame.uniform.json.mappers.JsonWriterMapper;
import me.flame.uniform.json.parser.lowlevel.JsonCursors;
import me.flame.uniform.json.parser.lowlevel.JsonCursor;
import me.flame.uniform.json.parser.lowlevel.MapJsonCursor;
import me.flame.uniform.json.writers.JsonWriter;
import me.flame.uniform.json.writers.JsonWriterFactory;
import me.flame.uniform.json.writers.JsonWriterOptions;

import org.jetbrains.annotations.NotNull;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.ForkJoinPool;

@SuppressWarnings("unchecked")
public record JsonAdapter(JsonConfig config, Executor executor) {
    public JsonAdapter(JsonConfig config) {
        this(config, ForkJoinPool.commonPool());
    }

    /**
     * Creates a {@code JsonAdapter} with a custom executor for all async operations.
     *
     * @param config   the JSON configuration to apply
     * @param executor the executor used to run async read/write tasks
     */
    public JsonAdapter(JsonConfig config, Executor executor) {
        this.config = config;
        this.executor = executor;
        JsonMapperRegistry.applyConfig(config);
    }

    public static JsonConfigBuilder builder() {
        return new JsonConfigBuilder();
    }

    public <T> T readValue(@NotNull String json, Class<T> type) {
        byte[] bytes = json.getBytes(StandardCharsets.UTF_8);
        return readValue(bytes, type);
    }

    public <T> T readValue(byte[] bytes, Class<T> type) {
        JsonMapper<T> mapper = (JsonMapper<T>) JsonMapperRegistry.getReader(type);
        JsonCursor cursor = JsonCursors.createNormal(bytes);
        return mapper.map(cursor);
    }

    public <T> T readValue(InputStream inputStream, Class<T> type) {
        JsonMapper<T> mapper = (JsonMapper<T>) JsonMapperRegistry.getReader(type);
        try {
            JsonCursor cursor = JsonCursors.createNormal(inputStream.readAllBytes());
            return mapper.map(cursor);
        } catch (IOException e) {
            throw JsonException.io(e);
        }
    }

    public <T> T readValue(Map<String, Object> map, Class<T> type) {
        JsonMapper<T> mapper = (JsonMapper<T>) JsonMapperRegistry.getReader(type);
        MapJsonCursor cursor = JsonCursors.createMap(map);
        return mapper.map(cursor);
    }

    /**
     * Asynchronously parses {@code json} into an instance of {@code type}.
     *
     * <p>The UTF-8 encoding of the string is performed on the calling thread
     * before dispatch so that the string's char[] is not retained by the async task.
     */
    public <T> CompletableFuture<T> readValueAsync(@NotNull String json, Class<T> type) {
        // Encode eagerly on the calling thread - avoids capturing the full String
        // in the lambda and lets the GC collect it sooner.
        byte[] bytes = json.getBytes(StandardCharsets.UTF_8);
        return readValueAsync(bytes, type);
    }

    /**
     * Asynchronously parses {@code bytes} into an instance of {@code type}.
     *
     * <p>The byte array is read directly without copying; callers must not
     * mutate it while the future is pending.
     */
    public <T> CompletableFuture<T> readValueAsync(byte[] bytes, Class<T> type) {
        return CompletableFuture.supplyAsync(() -> readValue(bytes, type), executor);
    }

    /**
     * Asynchronously reads all bytes from {@code inputStream} and parses
     * them into an instance of {@code type}.
     *
     * <p>The stream is read and closed entirely within the async task so
     * that blocking I/O does not occur on the calling thread.
     */
    public <T> CompletableFuture<T> readValueAsync(InputStream inputStream, Class<T> type) {
        return CompletableFuture.supplyAsync(() -> readValue(inputStream, type), executor);
    }

    /**
     * Asynchronously converts {@code map} into an instance of {@code type}
     * using a {@link MapJsonCursor}.
     *
     * <p>The map must not be mutated while the future is pending.
     */
    public <T> CompletableFuture<T> readValueAsync(Map<String, Object> map, Class<T> type) {
        return CompletableFuture.supplyAsync(() -> readValue(map, type), executor);
    }

    public <T> @NotNull String writeValue(@NotNull T value) {
        JsonWriterMapper<T> writerMapper = (JsonWriterMapper<T>) JsonMapperRegistry.getWriter(value.getClass());
        return writerMapper.write(value);
    }

    /**
     * Asynchronously serializes {@code value} to a JSON string.
     *
     * <p>{@code value} must not be mutated while the future is pending.
     */
    public <T> CompletableFuture<String> writeValueAsync(@NotNull T value) {
        return CompletableFuture.supplyAsync(() -> writeValue(value), executor);
    }

    public JsonWriter createWriter(JsonWriterOptions... options) {
        return JsonWriterFactory.builder(config)
            .addOptions(options)
            .build();
    }
}
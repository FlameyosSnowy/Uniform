package me.flame.uniform.json;

import me.flame.uniform.json.dom.JsonArray;
import me.flame.uniform.json.dom.JsonObject;
import me.flame.uniform.json.dom.JsonValue;
import me.flame.uniform.json.exceptions.JsonException;
import me.flame.uniform.json.mappers.JsonMapper;
import me.flame.uniform.json.mappers.JsonMapperRegistry;
import me.flame.uniform.json.mappers.JsonWriterMapper;
import me.flame.uniform.json.parser.JsonCursors;
import me.flame.uniform.json.parser.lowlevel.JsonCursor;
import me.flame.uniform.json.parser.lowlevel.JsonDomCursor;
import me.flame.uniform.json.parser.lowlevel.MapJsonCursor;
import me.flame.uniform.json.resolvers.CoreTypeResolver;
import me.flame.uniform.json.resolvers.CoreTypeResolverRegistry;
import me.flame.uniform.json.writers.JsonDomBuilder;
import me.flame.uniform.json.writers.JsonDomWriter;
import me.flame.uniform.json.writers.JsonWriter;
import me.flame.uniform.json.writers.JsonWriterFactory;
import me.flame.uniform.json.writers.JsonWriterOptions;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.ForkJoinPool;

@SuppressWarnings({ "unchecked", "unused", "MethodMayBeStatic" })
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

    public static JsonConfigBuilder configBuilder() {
        return new JsonConfigBuilder();
    }

    public <T> T readValue(@NotNull String json, Class<T> type) {
        byte[] bytes = json.getBytes(StandardCharsets.UTF_8);
        return readValue(bytes, type);
    }

    public JsonValue readValue(@NotNull String json) {
        byte[] bytes = json.getBytes(StandardCharsets.UTF_8);
        return readValue(bytes);
    }

    public JsonValue readValue(byte[] bytes) {
        JsonCursor cursor = JsonCursors.createNormal(bytes, config);
        return cursor.parseValue();
    }

    public <T> T readValue(byte[] bytes, Class<T> type) {
        JsonMapper<T> mapper = (JsonMapper<T>) JsonMapperRegistry.getReader(type);
        JsonCursor cursor = JsonCursors.createNormal(bytes, config);
        return mapper.map(cursor);
    }

    public <T> T readValue(InputStream inputStream, Class<T> type) {
        JsonMapper<T> mapper = (JsonMapper<T>) JsonMapperRegistry.getReader(type);
        try {
            JsonCursor cursor = JsonCursors.createNormal(inputStream.readAllBytes(), config);
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


    public <T> JsonValue readValue(InputStream inputStream) {
        try {
            JsonCursor cursor = JsonCursors.createNormal(inputStream.readAllBytes(), config);
            return cursor.parseValue();
        } catch (IOException e) {
            throw JsonException.io(e);
        }
    }

    /**
     * Asynchronously parses {@code inputStream} into an instance of {@link JsonValue}.
     *
     * <p>The UTF-8 encoding of the string is performed on the calling thread
     * before dispatch so that the string's char[] is not retained by the async task.
     */
    public <T> CompletableFuture<JsonValue> readValueAsync(InputStream inputStream) {
        return CompletableFuture.supplyAsync(() -> readValue(inputStream));
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
        if (writerMapper == null)
            throw new IllegalStateException("No JsonWriterMapper registered for " + value.getClass().getName()
                + ". Ensure the class is annotated with @SerializedObject and was processed by the annotation processor.");
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

    public @NotNull String writeValue(@NotNull JsonValue value) {
        return JsonDomWriter.write(value);
    }

    /**
     * Converts a POJO to a {@link JsonObject} DOM tree by driving the registered
     * {@link me.flame.uniform.json.mappers.JsonWriterMapper} for {@code T} against
     * a {@link JsonDomBuilder} instead of a string writer.
     *
     * @param entity the object to convert - must not be {@code null}
     * @param <T>    the entity type; a {@code JsonWriterMapper<T>} must be registered
     * @return the entity represented as a {@link JsonObject}
     * @throws IllegalStateException if no writer is registered for the entity's type,
     *                               or if the mapper produces a non-object root value
     */
    @SuppressWarnings("unchecked")
    public <T> @NotNull JsonObject valueToTree(@NotNull T entity) {
        JsonWriterMapper<T> writer = (JsonWriterMapper<T>) JsonMapperRegistry.getWriter(entity.getClass());
        if (writer == null)
            throw new IllegalStateException("No JsonWriterMapper registered for " + entity.getClass().getName()
                + ". Ensure the class is annotated with @SerializedObject and was processed by the annotation processor.");

        JsonDomBuilder builder = new JsonDomBuilder();
        writer.writeTo(builder, entity);
        return builder.result();
    }

    /**
     * Converts any supported {@link JsonValue} to an instance of {@code type}.
     *
     * <p>Resolution order:
     * <ol>
     *   <li>Consult {@link CoreTypeResolverRegistry#INSTANCE} — covers all primitives,
     *       wrappers, {@link String}, {@link java.math.BigInteger}, {@link java.math.BigDecimal},
     *       {@link java.util.UUID}, {@link java.net.URI}/{@link java.net.URL},
     *       {@link java.nio.file.Path}, all {@code java.time.*} types, and any
     *       custom {@link CoreTypeResolver} registered by the caller.</li>
     *   <li>Fall through to the mapper registry for {@code @SerializedObject}-annotated
     *       POJOs — {@code tree} must be a {@link JsonObject} in that case.</li>
     * </ol>
     *
     * <p>For collections use {@link #treeToList}, {@link #treeToSet},
     * {@link #treeToQueue}, or {@link #treeToMap} — generic element types are erased
     * at runtime so they need their own overloads.
     *
     * @param tree the DOM node to convert
     * @param type the target class
     * @param <T>  the target type
     * @return a fully populated instance of {@code T}, or {@code null} if {@code tree}
     *         is {@link me.flame.uniform.json.dom.JsonNull}
     * @throws IllegalStateException    if no resolver or mapper is registered for {@code type}
     * @throws IllegalArgumentException if the node cannot be converted to {@code type}
     */
    @SuppressWarnings("unchecked")
    public <T> @Nullable T treeToValue(@NotNull JsonValue tree, @NotNull Class<T> type) {
        CoreTypeResolver<T> coreResolver = CoreTypeResolverRegistry.INSTANCE.resolve(type);
        if (coreResolver != null) {
            T result = coreResolver.deserialize(tree);
            return result != null ? result : defaultForType(type);
        }

        if (!(tree instanceof JsonObject obj))
            throw new IllegalStateException(
                "Cannot map " + tree.getClass().getSimpleName() + " to " + type.getName()
                    + " — expected a JsonObject. If this is a custom type, register a "
                    + "CoreTypeResolver via CoreTypeResolverRegistry.INSTANCE.register(...).");

        JsonMapper<T> mapper = (JsonMapper<T>) JsonMapperRegistry.getReader(type);
        if (mapper == null)
            throw new IllegalStateException("No JsonMapper registered for " + type.getName()
                + ". Ensure the class is annotated with @SerializedObject and was processed "
                + "by the annotation processor, or register a CoreTypeResolver for it.");

        return mapper.map(new JsonDomCursor(obj));
    }

    /**
     * Converts a {@link JsonArray} to a {@link java.util.List} whose elements are
     * each converted to {@code elementType} via {@link #treeToValue}.
     */
    public <E> @NotNull List<E> treeToList(
        @NotNull JsonArray array,
        @NotNull Class<E> elementType) {
        List<E> result = new ArrayList<>(array.size());
        for (int i = 0; i < array.size(); i++) {
            result.add(treeToValue(array.getRaw(i), elementType));
        }
        return result;
    }

    /**
     * Converts a {@link JsonArray} to an insertion-ordered {@link java.util.Set}
     * whose elements are each converted to {@code elementType} via {@link #treeToValue}.
     */
    public <E> @NotNull Set<E> treeToSet(
        @NotNull JsonArray array,
        @NotNull Class<E> elementType) {
        Set<E> result = new LinkedHashSet<>(array.size() * 2);
        for (int i = 0; i < array.size(); i++) {
            result.add(treeToValue(array.getRaw(i), elementType));
        }
        return result;
    }

    /**
     * Converts a {@link JsonArray} to a {@link java.util.Queue} whose elements are
     * each converted to {@code elementType} via {@link #treeToValue}.
     */
    public <E> @NotNull Queue<E> treeToQueue(
        @NotNull JsonArray array,
        @NotNull Class<E> elementType) {
        java.util.ArrayDeque<E> result = new ArrayDeque<>(array.size());
        for (int i = 0; i < array.size(); i++) {
            result.add(treeToValue(array.getRaw(i), elementType));
        }
        return result;
    }

    /**
     * Converts a {@link JsonObject} to a {@link java.util.Map} with {@link String}
     * keys and values converted to {@code valueType} via {@link #treeToValue}.
     */
    public <V> @NotNull Map<String, V> treeToMap(
        @NotNull JsonObject obj,
        @NotNull Class<V> valueType) {
        Map<String, V> result = new LinkedHashMap<>(obj.size() * 2);
        for (Map.Entry<String, JsonValue> entry : obj) {
            result.put(entry.getKey(), treeToValue(entry.getValue(), valueType));
        }
        return result;
    }

    /**
     * Returns the zero/false/null default for primitive types when a resolver
     * returns {@code null} (e.g. the DOM node was {@code JsonNull}).
     * For reference types, {@code null} is returned as-is.
     */
    @SuppressWarnings("unchecked")
    @Nullable
    private static <T> T defaultForType(@NotNull Class<T> type) {
        if (type == int.class     || type == Integer.class)  return (T) (Integer) 0;
        if (type == long.class    || type == Long.class)     return (T) (Long)    0L;
        if (type == double.class  || type == Double.class)   return (T) (Double)  0.0;
        if (type == float.class   || type == Float.class)    return (T) (Float)   0.0f;
        if (type == short.class   || type == Short.class)    return (T) (Short)   (short) 0;
        if (type == byte.class    || type == Byte.class)     return (T) (Byte)    (byte)  0;
        if (type == boolean.class || type == Boolean.class)  return (T) Boolean.FALSE;
        if (type == char.class    || type == Character.class)return (T) (Character) '\0';
        return null;
    }


    /**
     * Asynchronously serializes {@code value} to a JSON string.
     *
     * <p>{@code value} must not be mutated while the future is pending.
     */
    public <T> CompletableFuture<String> writeValueAsync(@NotNull JsonValue value) {
        return CompletableFuture.supplyAsync(() -> writeValue(value), executor);
    }

    public JsonWriter createWriter(JsonWriterOptions... options) {
        return JsonWriterFactory.builder(config)
            .addOptions(options)
            .build();
    }
}
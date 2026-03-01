package me.flame.uniform.json.dom;

import me.flame.uniform.json.exceptions.JsonMissingKeyException;
import me.flame.uniform.json.exceptions.JsonTypeException;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Collections;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * A JSON object ({@code { "key": value, … }}).
 *
 * <p>All typed {@code get*(String)} accessors throw {@link JsonTypeException} when the stored
 * value has the wrong type, and {@link JsonMissingKeyException} when the key is absent.
 * Use {@link #contains(String)} or {@link #getRaw(String)} when you need to handle optional keys.
 */
@SuppressWarnings("unused")
public final class JsonObject implements JsonValue, Iterable<Map.Entry<String, JsonValue>> {

    private final Map<String, JsonValue> map = new LinkedHashMap<>();

    // -------------------------------------------------------------------------
    // Mutation
    // -------------------------------------------------------------------------

    /** Associates {@code key} with {@code value}, replacing any previous mapping. */
    public JsonObject put(@NotNull String key, @NotNull JsonValue value) {
        map.put(key, value);
        return this;
    }

    /** Convenience overloads so callers don't have to box primitives manually. */
    public JsonObject put(@NotNull String key, String value) {
        return put(key, value == null ? JsonNull.INSTANCE : new JsonString(value));
    }

    public JsonObject put(@NotNull String key, long value) {
        return put(key, new JsonLong(value));
    }

    public JsonObject put(@NotNull String key, int value) {
        return put(key, new JsonInteger(value));
    }

    public JsonObject put(@NotNull String key, double value) {
        return put(key, new JsonDouble(value));
    }

    public JsonObject put(@NotNull String key, float value) {
        return put(key, new JsonFloat(value));
    }

    public JsonObject put(@NotNull String key, short value) {
        return put(key, new JsonShort(value));
    }

    public JsonObject put(@NotNull String key, byte value) {
        return put(key, new JsonByte(value));
    }

    public JsonObject put(@NotNull String key, boolean value) {
        return put(key, JsonBoolean.of(value));
    }

    /** Removes the mapping for {@code key}, if present. */
    public JsonObject remove(@NotNull String key) {
        map.remove(key);
        return this;
    }

    /**
     * Returns the raw {@link JsonValue} for {@code key}, or {@code null} if absent.
     * Prefer the typed accessors for normal use; this is for optional-key handling.
     */
    public @Nullable JsonValue getRaw(@NotNull String key) {
        return map.get(key);
    }

    public @NotNull String getString(@NotNull String key) {
        JsonValue v = require(key);
        if (v instanceof JsonString s) return s.value();
        throw new JsonTypeException(key, "String", v);
    }

    public int getInt(@NotNull String key) {
        JsonValue v = require(key);
        if (v instanceof JsonNumber n) return n.intValue();
        throw new JsonTypeException(key, "JsonNumber", v);
    }

    public long getLong(@NotNull String key) {
        JsonValue v = require(key);
        if (v instanceof JsonNumber n) return n.longValue();
        throw new JsonTypeException(key, "JsonNumber", v);
    }

    public double getDouble(@NotNull String key) {
        JsonValue v = require(key);
        if (v instanceof JsonNumber n) return n.doubleValue();
        throw new JsonTypeException(key, "JsonNumber", v);
    }

    public float getFloat(@NotNull String key) {
        JsonValue v = require(key);
        if (v instanceof JsonNumber n) return n.floatValue();
        throw new JsonTypeException(key, "JsonNumber", v);
    }

    public short getShort(@NotNull String key) {
        JsonValue v = require(key);
        if (v instanceof JsonNumber n) return n.shortValue();
        throw new JsonTypeException(key, "JsonNumber", v);
    }

    public byte getByte(@NotNull String key) {
        JsonValue v = require(key);
        if (v instanceof JsonNumber n) return n.byteValue();
        throw new JsonTypeException(key, "JsonNumber", v);
    }

    public boolean getBoolean(@NotNull String key) {
        JsonValue v = require(key);
        if (v instanceof JsonBoolean b) return b.value();
        throw new JsonTypeException(key, "Boolean", v);
    }

    public @NotNull JsonObject getObject(@NotNull String key) {
        JsonValue v = require(key);
        if (v instanceof JsonObject o) return o;
        throw new JsonTypeException(key, "JsonObject", v);
    }

    public @NotNull JsonArray getArray(@NotNull String key) {
        JsonValue v = require(key);
        if (v instanceof JsonArray a) return a;
        throw new JsonTypeException(key, "JsonArray", v);
    }

    // -------------------------------------------------------------------------
    // Query
    // -------------------------------------------------------------------------

    public boolean contains(@NotNull String key) {
        return map.containsKey(key);
    }

    public int size() {
        return map.size();
    }

    public boolean isEmpty() {
        return map.isEmpty();
    }

    public @NotNull Set<String> keys() {
        return Collections.unmodifiableSet(map.keySet());
    }

    @Override
    public @NotNull Iterator<Map.Entry<String, JsonValue>> iterator() {
        return Collections.unmodifiableMap(map).entrySet().iterator();
    }

    private @NotNull JsonValue require(@NotNull String key) {
        JsonValue v = map.get(key);
        if (v == null) throw new JsonMissingKeyException(key);
        return v;
    }

    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder("{");
        boolean first = true;
        for (Map.Entry<String, JsonValue> e : map.entrySet()) {
            if (!first) sb.append(", ");
            sb.append('"').append(e.getKey()).append("\": ").append(e.getValue());
            first = false;
        }
        return sb.append('}').toString();
    }
}

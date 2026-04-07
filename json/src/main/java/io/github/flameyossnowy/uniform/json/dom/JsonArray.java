package io.github.flameyossnowy.uniform.json.dom;

import io.github.flameyossnowy.uniform.json.exceptions.JsonTypeException;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.function.Consumer;

/**
 * A JSON array ({@code [ value, … ]}).
 *
 * <p>Typed {@code get*(int)} accessors throw {@link JsonTypeException} on a type mismatch and
 * {@link IndexOutOfBoundsException} when the index is out of range.
 */
@SuppressWarnings("unused")
public final class JsonArray implements JsonValue, Iterable<JsonValue> {

    private final List<JsonValue> values = new ArrayList<>();

    public JsonArray add(@NotNull JsonValue value) {
        values.add(value);
        return this;
    }

    /** Convenience overloads. */
    public JsonArray add(String value) {
        return add(value == null ? JsonNull.INSTANCE : new JsonString(value));
    }

    public JsonArray add(long value)   { return add(new JsonLong(value)); }
    public JsonArray add(int value)    { return add(new JsonInteger(value)); }
    public JsonArray add(double value) { return add(new JsonDouble(value)); }
    public JsonArray add(float value)  { return add(new JsonFloat(value)); }
    public JsonArray add(short value)  { return add(new JsonShort(value)); }
    public JsonArray add(byte value)   { return add(new JsonByte(value)); }

    public JsonArray add(boolean value) {
        return add(JsonBoolean.of(value));
    }

    public JsonValue remove(int index) {
        return values.remove(index);
    }

    /** Returns the raw {@link JsonValue} at {@code index}. */
    public @NotNull JsonValue getRaw(int index) {
        return values.get(index);
    }

    public @NotNull String getString(int index) {
        JsonValue v = values.get(index);
        if (v instanceof JsonString s) return s.value();
        throw new JsonTypeException(index, "String", v);
    }

    public int getInt(int index) {
        JsonValue v = values.get(index);
        if (v instanceof JsonNumber n) return n.intValue();
        throw new JsonTypeException(index, "JsonNumber", v);
    }

    public long getLong(int index) {
        JsonValue v = values.get(index);
        if (v instanceof JsonNumber n) return n.longValue();
        throw new JsonTypeException(index, "JsonNumber", v);
    }

    public double getDouble(int index) {
        JsonValue v = values.get(index);
        if (v instanceof JsonNumber n) return n.doubleValue();
        throw new JsonTypeException(index, "JsonNumber", v);
    }

    public float getFloat(int index) {
        JsonValue v = values.get(index);
        if (v instanceof JsonNumber n) return n.floatValue();
        throw new JsonTypeException(index, "JsonNumber", v);
    }

    public short getShort(int index) {
        JsonValue v = values.get(index);
        if (v instanceof JsonNumber n) return n.shortValue();
        throw new JsonTypeException(index, "JsonNumber", v);
    }

    public byte getByte(int index) {
        JsonValue v = values.get(index);
        if (v instanceof JsonNumber n) return n.byteValue();
        throw new JsonTypeException(index, "JsonNumber", v);
    }

    public boolean getBoolean(int index) {
        JsonValue v = values.get(index);
        if (v instanceof JsonBoolean(boolean value)) return value;
        throw new JsonTypeException(index, "Boolean", v);
    }

    public @NotNull JsonObject getObject(int index) {
        JsonValue v = values.get(index);
        if (v instanceof JsonObject o) return o;
        throw new JsonTypeException(index, "JsonObject", v);
    }

    public @NotNull JsonArray getArray(int index) {
        JsonValue v = values.get(index);
        if (v instanceof JsonArray a) return a;
        throw new JsonTypeException(index, "JsonArray", v);
    }

    public int size() {
        return values.size();
    }

    public boolean isEmpty() {
        return values.isEmpty();
    }

    @Override
    public @NotNull Iterator<JsonValue> iterator() {
        return Collections.unmodifiableList(values).iterator();
    }

    @Override
    public void forEach(Consumer<? super JsonValue> consumer) {
        values.forEach(consumer);
    }

    @Override
    public String toString() {
        return values.toString();
    }
}

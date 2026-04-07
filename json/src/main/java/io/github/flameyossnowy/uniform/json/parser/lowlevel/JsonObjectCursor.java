package io.github.flameyossnowy.uniform.json.parser.lowlevel;

import io.github.flameyossnowy.uniform.json.dom.*;
import io.github.flameyossnowy.uniform.json.parser.JsonReadCursor;
import org.jetbrains.annotations.NotNull;

import java.nio.charset.StandardCharsets;
import java.util.Iterator;
import java.util.Map;

/**
 * A {@link JsonReadCursor} backed by an in-memory {@link JsonObject} (or any
 * {@code Map<String, JsonValue>}) rather than a raw JSON byte array.
 *
 * <p>Mirrors {@link MapJsonCursor} exactly, but works directly with the typed
 * {@link JsonValue} DOM so no runtime {@code instanceof} chains or string
 * round-trips are needed for coercion — each leaf type is already the correct
 * Java representation.
 *
 * <h3>Supported value types</h3>
 * <ul>
 *   <li>{@link JsonString}  — string accessors return {@link JsonString#value()}</li>
 *   <li>{@link JsonNumber}  — numeric accessors delegate to the appropriate
 *       {@code byteValue()} / {@code intValue()} / … method</li>
 *   <li>{@link JsonBoolean} — boolean accessor returns {@link JsonBoolean#value()}</li>
 *   <li>{@link JsonNull}    — numeric accessors return {@code 0}, boolean returns
 *       {@code false}, string returns {@code ""}, sub-cursors return an empty cursor</li>
 *   <li>{@link JsonObject}  — navigated via {@link #enterObject()} on a sub-cursor</li>
 *   <li>{@link JsonArray}   — navigated via {@link #enterArray()} on a sub-cursor</li>
 * </ul>
 *
 * <h3>Thread safety</h3>
 * <p>Not thread-safe; create one cursor per thread / call site.
 */
public final class JsonObjectCursor implements JsonReadCursor {

    private enum Mode { OBJECT, ARRAY, SCALAR }

    private final Mode mode;

    // OBJECT mode
    private Iterator<Map.Entry<String, JsonValue>> entryIterator;
    private String    currentKey;
    private JsonValue currentValue;

    // ARRAY mode
    private Iterator<JsonValue> elementIterator;
    private JsonValue currentElement;

    // SCALAR mode
    private final JsonValue scalarValue;

    private boolean entered;

    /** Top-level cursor over a {@link JsonObject}. Call {@link #enterObject()} first. */
    public JsonObjectCursor(@NotNull JsonObject object) {
        this.mode          = Mode.OBJECT;
        this.entryIterator = object.getMutableMap().entrySet().iterator();
        this.scalarValue   = null;
        this.entered       = false;
    }

    /** Top-level cursor over a {@link JsonArray}. Call {@link #enterArray()} first. */
    public JsonObjectCursor(@NotNull JsonArray array) {
        this.mode            = Mode.ARRAY;
        this.elementIterator = array.iterator();
        this.scalarValue     = null;
        this.entered         = false;
    }

    /** Private scalar sub-cursor wrapping a single leaf {@link JsonValue}. */
    private JsonObjectCursor(JsonValue scalar) {
        this.mode        = Mode.SCALAR;
        this.scalarValue = scalar;
        this.entered     = false;
    }

    @Override
    public boolean enterObject() {
        if (entered) return false;

        if (mode == Mode.SCALAR) {
            if (!(scalarValue instanceof JsonObject obj)) return false;
            entryIterator = obj.getMutableMap().entrySet().iterator();
            entered = true;
            return true;
        }

        if (mode != Mode.OBJECT) return false;
        entered = true;
        return true;
    }

    @Override
    public boolean nextField() {
        if (!entered || entryIterator == null || !entryIterator.hasNext()) return false;
        Map.Entry<String, JsonValue> entry = entryIterator.next();
        currentKey   = entry.getKey();
        currentValue = entry.getValue();
        return true;
    }

    @Override
    public boolean enterArray() {
        if (entered) return false;

        if (mode == Mode.SCALAR) {
            if (!(scalarValue instanceof JsonArray arr)) return false;
            elementIterator = arr.iterator();
            entered = true;
            return true;
        }

        if (mode != Mode.ARRAY) return false;
        entered = true;
        return true;
    }

    @Override
    public boolean nextElement() {
        if (!entered || elementIterator == null || !elementIterator.hasNext()) return false;
        currentElement = elementIterator.next();
        return true;
    }

    @Override
    public void skipFieldValue() {
        if (currentValue == null) return;
        currentValue = null;
    }

    @Override
    public @NotNull ByteSlice fieldName() {
        String key = currentKey != null ? currentKey : "";
        byte[] bytes = key.getBytes(StandardCharsets.UTF_8);
        return new ByteSlice(bytes, 0, bytes.length);
    }

    @Override
    public @NotNull String fieldNameAsString() {
        return currentKey != null ? currentKey : "";
    }

    @Override
    public int fieldNameHash() {
        String key = currentKey != null ? currentKey : "";
        int h = 0x811c9dc5;
        for (int i = 0; i < key.length(); i++) {
            char c = key.charAt(i);
            if (c > 0x7F) {
                byte[] bytes = key.getBytes(StandardCharsets.UTF_8);
                for (byte b : bytes) {
                    h ^= (b & 0xFF);
                    h *= 0x01000193;
                }
                return h;
            }
            h ^= (c & 0xFF);
            h *= 0x01000193;
        }
        return h;
    }

    @Override
    public boolean fieldNameEquals(@NotNull String expected) {
        return expected.equals(currentKey);
    }

    @Override public @NotNull ByteSlice fieldValue()               { return toByteSlice(currentValue); }
    @Override public int                fieldValueAsInt()          { return toInt(currentValue); }
    @Override public long               fieldValueAsLong()         { return toLong(currentValue); }
    @Override public double             fieldValueAsDouble()       { return toDouble(currentValue); }
    @Override public float              fieldValueAsFloat()        { return toFloat(currentValue); }
    @Override public short              fieldValueAsShort()        { return toShort(currentValue); }
    @Override public byte               fieldValueAsByte()         { return toByte(currentValue); }
    @Override public boolean            fieldValueAsBoolean()      { return toBoolean(currentValue); }
    @Override public @NotNull String    fieldValueAsUnquotedString() { return toStr(currentValue); }
    @Override public @NotNull JsonReadCursor fieldValueCursor()    { return subCursor(currentValue); }

    public @NotNull JsonValue fieldValueAsJsonValue() {
        return currentValue != null ? currentValue : JsonNull.INSTANCE;
    }

    @Override public @NotNull ByteSlice elementValue()               { return toByteSlice(currentElement); }
    @Override public int                elementValueAsInt()          { return toInt(currentElement); }
    @Override public long               elementValueAsLong()         { return toLong(currentElement); }
    @Override public double             elementValueAsDouble()       { return toDouble(currentElement); }
    @Override public float              elementValueAsFloat()        { return toFloat(currentElement); }
    @Override public short              elementValueAsShort()        { return toShort(currentElement); }
    @Override public byte               elementValueAsByte()         { return toByte(currentElement); }
    @Override public boolean            elementValueAsBoolean()      { return toBoolean(currentElement); }
    @Override public @NotNull String    elementValueAsUnquotedString() { return toStr(currentElement); }
    @Override public @NotNull JsonReadCursor elementValueCursor()    { return subCursor(currentElement); }

    @Override
    public boolean elementIsNull() {
        return currentElement == null;
    }

    public @NotNull JsonValue elementValueAsJsonValue() {
        return currentElement != null ? currentElement : JsonNull.INSTANCE;
    }

    private static JsonObjectCursor subCursor(JsonValue v) {
        return switch (v) {
            case JsonObject obj -> new JsonObjectCursor(obj);
            case JsonArray arr -> new JsonObjectCursor(arr);
            case null, default -> new JsonObjectCursor(v);
        };
    }

    private static int toInt(JsonValue v) {
        return switch (v) {
            case JsonNumber n -> n.intValue();
            case JsonBoolean b -> b.value() ? 1 : 0;
            case JsonString s -> Integer.parseInt(s.value());
            case null, default -> 0;
        };
    }

    private static long toLong(JsonValue v) {
        return switch (v) {
            case JsonNumber n -> n.longValue();
            case JsonBoolean b -> b.value() ? 1L : 0L;
            case JsonString s -> Long.parseLong(s.value());
            case null, default -> 0L;
        };
    }

    private static double toDouble(JsonValue v) {
        return switch (v) {
            case JsonNumber n -> n.doubleValue();
            case JsonBoolean b -> b.value() ? 1.0 : 0.0;
            case JsonString s -> Double.parseDouble(s.value());
            case null, default -> 0.0;
        };
    }

    private static float toFloat(JsonValue v) {
        return switch (v) {
            case JsonNumber n -> n.floatValue();
            case JsonBoolean b -> b.value() ? 1f : 0f;
            case JsonString s -> Float.parseFloat(s.value());
            case null, default -> 0f;
        };
    }

    private static short toShort(JsonValue v) {
        return switch (v) {
            case JsonNumber n -> n.shortValue();
            case JsonBoolean b -> (short) (b.value() ? 1 : 0);
            case JsonString s -> Short.parseShort(s.value());
            case null, default -> 0;
        };
    }

    private static byte toByte(JsonValue v) {
        return switch (v) {
            case JsonNumber n -> n.byteValue();
            case JsonBoolean b -> (byte) (b.value() ? 1 : 0);
            case JsonString s -> Byte.parseByte(s.value());
            case null, default -> 0;
        };
    }

    private static boolean toBoolean(JsonValue v) {
        switch (v) {
            case JsonBoolean b -> {
                return b.value();
            }
            case JsonNumber n -> {
                return n.intValue() != 0;
            }
            case JsonString s -> {
                String raw = s.value();
                return "true".equalsIgnoreCase(raw) || "1".equals(raw) || "yes".equalsIgnoreCase(raw);
            }
            case null, default -> {
            }
        }
        return false;
    }

    private static @NotNull String toStr(JsonValue v) {
        return switch (v) {
            case null -> "";
            case JsonNull _ -> "";
            case JsonString s -> s.value();
            default -> v.toString();
        };
    }

    private static @NotNull ByteSlice toByteSlice(JsonValue v) {
        byte[] bytes = toStr(v).getBytes(StandardCharsets.UTF_8);
        return new ByteSlice(bytes, 0, bytes.length);
    }
}
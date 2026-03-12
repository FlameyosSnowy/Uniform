package io.github.flameyossnowy.uniform.json.parser.lowlevel;

import io.github.flameyossnowy.uniform.json.dom.JsonArray;
import io.github.flameyossnowy.uniform.json.dom.JsonBoolean;
import io.github.flameyossnowy.uniform.json.dom.JsonByte;
import io.github.flameyossnowy.uniform.json.dom.JsonDouble;
import io.github.flameyossnowy.uniform.json.dom.JsonFloat;
import io.github.flameyossnowy.uniform.json.dom.JsonInteger;
import io.github.flameyossnowy.uniform.json.dom.JsonLong;
import io.github.flameyossnowy.uniform.json.dom.JsonNull;
import io.github.flameyossnowy.uniform.json.dom.JsonObject;
import io.github.flameyossnowy.uniform.json.dom.JsonShort;
import io.github.flameyossnowy.uniform.json.dom.JsonString;
import io.github.flameyossnowy.uniform.json.dom.JsonValue;
import io.github.flameyossnowy.uniform.json.parser.JsonReadCursor;
import org.jetbrains.annotations.NotNull;

import java.nio.charset.StandardCharsets;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

/**
 * MapJsonCursor: A {@link JsonReadCursor} backed by an in-memory
 * {@code Map<String, Object>} rather than a raw JSON byte array.
 *
 * <p>This allows the same codegen-produced mapper classes that consume
 * {@link JsonCursor} to also deserialize from already-parsed object graphs
 * (e.g. values produced by a dynamic parser, a test fixture builder, or
 * an intermediate representation) with zero extra glue code.
 *
 * <h3>Supported value types in the map</h3>
 * <ul>
 *   <li>{@link String} - returned directly by the string accessors</li>
 *   <li>{@link Number} ({@link Integer}, {@link Long}, {@link Double}, etc.)</li>
 *   <li>{@link Boolean}</li>
 *   <li>{@link Map}{@code <String, Object>} - navigated via {@link #enterObject()}</li>
 *   <li>{@link List}{@code <Object>} - navigated via {@link #enterArray()}</li>
 *   <li>{@code null} - numeric accessors return 0, boolean returns false,
 *       string returns {@code ""}, cursors return an empty cursor</li>
 * </ul>
 *
 * <h3>Thread safety</h3>
 * <p>Instances are <em>not</em> thread-safe; create one cursor per thread/call.
 */
public final class MapJsonCursor implements JsonReadCursor {

    // ── Cursor mode ──────────────────────────────────────────────────────────
    private enum Mode { OBJECT, ARRAY, SCALAR }

    private final Mode mode;

    // ── OBJECT mode state ────────────────────────────────────────────────────
    /** Ordered entry iterator for the current map level. */
    private Iterator<Map.Entry<String, Object>> entryIterator;
    private String currentKey;
    private Object currentValue;

    // ── ARRAY mode state ─────────────────────────────────────────────────────
    private Iterator<Object> elementIterator;
    private Object currentElement;

    // ── SCALAR mode state (sub-cursor for a single value) ────────────────────
    private final Object scalarValue;

    // ── Shared: has enterObject / enterArray been called yet? ────────────────
    private boolean entered;

    // =========================================================
    // Constructors
    // =========================================================

    /**
     * Creates a top-level cursor over a JSON object map.
     * Call {@link #enterObject()} before iterating fields.
     */
    public MapJsonCursor(@NotNull Map<String, Object> map) {
        this.mode        = Mode.OBJECT;
        this.entryIterator = map.entrySet().iterator();
        this.scalarValue = null;
        this.entered     = false;
    }

    /**
     * Creates a top-level cursor over a JSON array list.
     * Call {@link #enterArray()} before iterating elements.
     */
    public MapJsonCursor(@NotNull List<Object> list) {
        this.mode            = Mode.ARRAY;
        this.elementIterator = list.iterator();
        this.scalarValue     = null;
        this.entered         = false;
    }

    /** Private scalar sub-cursor - wraps a single leaf value. */
    private MapJsonCursor(Object scalarValue) {
        this.mode        = Mode.SCALAR;
        this.scalarValue = scalarValue;
        this.entered     = false;
    }

    // =========================================================
    // Object / Array navigation
    // =========================================================

    @Override
    public boolean enterObject() {
        if (entered) return false;

        // When used as a sub-cursor the value is already set; validate it's a Map.
        if (mode == Mode.SCALAR) {
            if (!(scalarValue instanceof Map<?, ?> rawMap)) return false;
            @SuppressWarnings("unchecked")
            Map<String, Object> map = (Map<String, Object>) rawMap;
            entryIterator = map.entrySet().iterator();
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
        Map.Entry<String, Object> entry = entryIterator.next();
        currentKey   = entry.getKey();
        currentValue = entry.getValue();
        return true;
    }

    @Override
    public boolean enterArray() {
        if (entered) return false;

        if (mode == Mode.SCALAR) {
            if (!(scalarValue instanceof List<?> rawList)) return false;
            @SuppressWarnings("unchecked")
            List<Object> list = (List<Object>) rawList;
            elementIterator = list.iterator();
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

    // =========================================================
    // Field name access
    // =========================================================

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
        // FNV-1a over the UTF-8 bytes of the key - identical algorithm to JsonCursor
        // so generated switch-on-hash dispatch works without modification.
        String key = currentKey != null ? currentKey : "";
        int h = 0x811c9dc5;
        for (int i = 0; i < key.length(); i++) {
            char c = key.charAt(i);
            if (c > 0x7F) {
                // Non-ASCII: hash raw UTF-8 bytes
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

    // =========================================================
    // Field value access - scalar
    // =========================================================

    @Override
    public @NotNull ByteSlice fieldValue() {
        return toByteSlice(currentValue);
    }

    @Override public int     fieldValueAsInt()     { return toInt(currentValue); }
    @Override public long    fieldValueAsLong()    { return toLong(currentValue); }
    @Override public double  fieldValueAsDouble()  { return toDouble(currentValue); }
    @Override public float   fieldValueAsFloat()   { return (float) toDouble(currentValue); }
    @Override public short   fieldValueAsShort()   { return (short) toInt(currentValue); }
    @Override public byte    fieldValueAsByte()    { return (byte)  toInt(currentValue); }
    @Override public boolean fieldValueAsBoolean() { return toBoolean(currentValue); }

    @Override
    public @NotNull String fieldValueAsUnquotedString() {
        return toString(currentValue);
    }

    @Override
    public @NotNull JsonReadCursor fieldValueCursor() {
        return subCursorFor(currentValue);
    }

    // =========================================================
    // Element value access - scalar
    // =========================================================

    @Override
    public @NotNull ByteSlice elementValue() {
        return toByteSlice(currentElement);
    }

    @Override public int     elementValueAsInt()     { return toInt(currentElement); }
    @Override public long    elementValueAsLong()    { return toLong(currentElement); }
    @Override public double  elementValueAsDouble()  { return toDouble(currentElement); }
    @Override public float   elementValueAsFloat()   { return (float) toDouble(currentElement); }
    @Override public short   elementValueAsShort()   { return (short) toInt(currentElement); }
    @Override public byte    elementValueAsByte()    { return (byte)  toInt(currentElement); }
    @Override public boolean elementValueAsBoolean() { return toBoolean(currentElement); }

    @Override
    public @NotNull String elementValueAsUnquotedString() {
        return toString(currentElement);
    }

    @Override
    public @NotNull JsonReadCursor elementValueCursor() {
        return subCursorFor(currentElement);
    }

    // =========================================================
    // Sub-cursor factory
    // =========================================================

    /**
     * Creates the appropriate sub-cursor for {@code value}:
     * <ul>
     *   <li>Map  -> object cursor (caller must call {@link #enterObject()})</li>
     *   <li>List -> array cursor  (caller must call {@link #enterArray()})</li>
     *   <li>anything else -> scalar cursor</li>
     * </ul>
     */
    private static MapJsonCursor subCursorFor(Object value) {
        if (value instanceof Map<?, ?> rawMap) {
            @SuppressWarnings("unchecked")
            Map<String, Object> map = (Map<String, Object>) rawMap;
            return new MapJsonCursor(map);
        }
        if (value instanceof List<?> rawList) {
            @SuppressWarnings("unchecked")
            List<Object> list = (List<Object>) rawList;
            return new MapJsonCursor(list);
        }
        return new MapJsonCursor(value);
    }

    // =========================================================
    // Value coercion helpers
    // =========================================================

    private static int toInt(Object v) {
        if (v instanceof Number number) {
            return number.intValue();
        } else if (v instanceof String s) {
            return Integer.parseInt(s);
        } else if (v instanceof Boolean b) {
            return b ? 1 : 0;
        }
        return 0;
    }

    private static long toLong(Object v) {
        if (v instanceof Number number) {
            return number.longValue();
        } else if (v instanceof String s) {
            return Long.parseLong(s);
        } else if (v instanceof Boolean b) {
            return b ? 1L : 0L;
        }
        return 0L;
    }

    private static double toDouble(Object v) {
        if (v instanceof Number number) {
            return number.doubleValue();
        } else if (v instanceof String s) {
            return Double.parseDouble(s);
        } else if (v instanceof Boolean b) {
            return b ? 1.0 : 0.0;
        }
        return 0.0;
    }

    private static boolean toBoolean(Object v) {
        if (v instanceof Boolean b) {
            return b;
        } else if (v instanceof Number number) {
            return number.intValue() != 0;
        } else if (v instanceof String s) {
            return "true".equalsIgnoreCase(s) || "1".equals(s) || "yes".equalsIgnoreCase(s);
        }
        return false;
    }

    private static @NotNull String toString(Object v) {
        if (v == null) return "";
        return v.toString();
    }

    private static @NotNull ByteSlice toByteSlice(Object v) {
        byte[] bytes = toString(v).getBytes(StandardCharsets.UTF_8);
        return new ByteSlice(bytes, 0, bytes.length);
    }


    public @NotNull JsonValue fieldValueAsJsonValue() {
        return toJsonValue(currentValue);
    }

    public @NotNull JsonValue elementValueAsJsonValue() {
        return toJsonValue(currentElement);
    }

    private static @NotNull JsonValue toJsonValue(Object v) {
        if (v == null) {
            return JsonNull.INSTANCE;
        } else if (v instanceof JsonValue jv) {
            return jv;
        } else if (v instanceof Boolean b) {
            return JsonBoolean.of(b);
        } else if (v instanceof Byte b) {
            return new JsonByte(b);
        } else if (v instanceof Short s) {
            return new JsonShort(s);
        } else if (v instanceof Integer i) {
            return new JsonInteger(i);
        } else if (v instanceof Long l) {
            return new JsonLong(l);
        } else if (v instanceof Float f) {
            return new JsonFloat(f);
        } else if (v instanceof Double d) {
            return new JsonDouble(d);
        } else if (v instanceof Number n) {
            return new JsonDouble(n.doubleValue());
            // BigDecimal etc.
        } else if (v instanceof String s) {
            return new JsonString(s);
        } else if (v instanceof Map<?, ?> rawMap) {
            return mapToJsonObject(rawMap);
        } else if (v instanceof List<?> rawList) {
            return listToJsonArray(rawList);
        }
        return new JsonString(v.toString());
    }

    @SuppressWarnings("unchecked")
    private static @NotNull JsonObject mapToJsonObject(Map<?, ?> rawMap) {
        Map<String, Object> map = (Map<String, Object>) rawMap;
        JsonObject obj = new JsonObject();
        for (Map.Entry<String, Object> entry : map.entrySet()) {
            obj.put(entry.getKey(), toJsonValue(entry.getValue()));
        }
        return obj;
    }

    @SuppressWarnings("unchecked")
    private static @NotNull JsonArray listToJsonArray(List<?> rawList) {
        List<Object> list = (List<Object>) rawList;
        JsonArray arr = new JsonArray();
        for (Object element : list) {
            arr.add(toJsonValue(element));
        }
        return arr;
    }
}
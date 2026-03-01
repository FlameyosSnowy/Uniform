package me.flame.uniform.json.parser.lowlevel;

import me.flame.uniform.json.dom.JsonArray;
import me.flame.uniform.json.dom.JsonBoolean;
import me.flame.uniform.json.dom.JsonNull;
import me.flame.uniform.json.dom.JsonNumber;
import me.flame.uniform.json.dom.JsonObject;
import me.flame.uniform.json.dom.JsonString;
import me.flame.uniform.json.dom.JsonValue;
import me.flame.uniform.json.parser.JsonReadCursor;

import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * A {@link JsonReadCursor} that walks a {@link JsonValue} DOM tree instead of a raw
 * byte array. Passed to existing {@link me.flame.uniform.json.mappers.JsonMapper}
 * instances by {@code JsonAdapter.treeToValue(JsonObject, Class)} so that the
 * generated reader code works identically whether the source is a JSON string or an
 * already-parsed DOM.
 *
 * <p>Object iteration mirrors {@link JsonCursor}: {@link #enterObject()} opens the
 * context, then {@link #nextField()} advances through entries one at a time, exposing
 * the current key via {@link #fieldNameAsString()} / {@link #fieldNameEquals(String)}
 * and the current value via the {@code fieldValueAs*()} family.
 *
 * <p>Array iteration works the same way via {@link #enterArray()} +
 * {@link #nextElement()} + {@code elementValueAs*()}.
 *
 * <p>All {@code fieldValueAs*()} / {@code elementValueAs*()} methods delegate to
 * #coerceToX(JsonValue) helpers that handle the full
 * {@link JsonNumber} sealed hierarchy, {@link JsonString}, {@link JsonBoolean}, and
 * {@link JsonNull} - matching the tolerance of {@link JsonCursor}'s own converters.
 */
public final class JsonDomCursor implements JsonReadCursor {
    //private enum Mode { OBJECT, ARRAY, SCALAR }

    /** The DOM node this cursor was constructed over. */
    private final JsonValue node;

    // Object-iteration state
    private List<Map.Entry<String, JsonValue>> objectEntries;
    private int                                objectIndex = -1;

    // Array-iteration state
    private int arrayIndex = -1;

    /**
     * Creates a cursor over {@code node}.
     * The mode is inferred from the node type: objects -> OBJECT, arrays -> ARRAY,
     * everything else -> SCALAR.
     */
    public JsonDomCursor(@NotNull JsonValue node) {
        this.node = node;
    }

    @Override
    public boolean enterObject() {
        if (!(node instanceof JsonObject obj)) return false;
        // Snapshot the entry list once so nextField() has stable indices.
        objectEntries = new ArrayList<>(obj.size());
        for (Map.Entry<String, JsonValue> e : obj) objectEntries.add(e);
        objectIndex = -1;
        return true;
    }

    @Override
    public boolean nextField() {
        if (objectEntries == null) return false;
        objectIndex++;
        return objectIndex < objectEntries.size();
    }

    @Override
    public @NotNull ByteSlice fieldName() {
        byte[] bytes = currentKey().getBytes(java.nio.charset.StandardCharsets.UTF_8);
        return new ByteSlice(bytes, 0, bytes.length);
    }

    @Override
    public @NotNull String fieldNameAsString() {
        return currentKey();
    }

    @Override
    public int fieldNameHash() {
        // FNV-1a
        int h = 0x811c9dc5;
        String key = currentKey();
        for (int i = 0; i < key.length(); i++) {
            char c = key.charAt(i);
            if (c > 0x7F) {
                // Non-ASCII: hash the UTF-8 bytes
                byte[] utf8 = key.getBytes(java.nio.charset.StandardCharsets.UTF_8);
                h = 0x811c9dc5;
                for (byte b : utf8) { h ^= (b & 0xFF); h *= 0x01000193; }
                return h;
            }
            h ^= (c & 0xFF);
            h *= 0x01000193;
        }
        return h;
    }

    @Override
    public boolean fieldNameEquals(@NotNull String expected) {
        return currentKey().equals(expected);
    }

    // -------------------------------------------------------------------------
    // Field value - raw + typed accessors
    // -------------------------------------------------------------------------

    @Override
    public @NotNull ByteSlice fieldValue() {
        byte[] bytes = currentFieldValue().toString().getBytes(java.nio.charset.StandardCharsets.UTF_8);
        return new ByteSlice(bytes, 0, bytes.length);
    }

    @Override public int     fieldValueAsInt()              { return coerceToInt(currentFieldValue()); }
    @Override public long    fieldValueAsLong()             { return coerceToLong(currentFieldValue()); }
    @Override public double  fieldValueAsDouble()           { return coerceToDouble(currentFieldValue()); }
    @Override public float   fieldValueAsFloat()            { return (float) coerceToDouble(currentFieldValue()); }
    @Override public short   fieldValueAsShort()            { return (short) coerceToInt(currentFieldValue()); }
    @Override public byte    fieldValueAsByte()             { return (byte)  coerceToInt(currentFieldValue()); }
    @Override public boolean fieldValueAsBoolean()          { return coerceToBool(currentFieldValue()); }
    @Override public @NotNull String fieldValueAsUnquotedString() { return coerceToString(currentFieldValue()); }

    @Override
    public @NotNull JsonDomCursor fieldValueCursor() {
        return new JsonDomCursor(currentFieldValue());
    }

    // -------------------------------------------------------------------------
    // Array navigation
    // -------------------------------------------------------------------------

    @Override
    public boolean enterArray() {
        if (!(node instanceof JsonArray)) return false;
        arrayIndex = -1;
        return true;
    }

    @Override
    public boolean nextElement() {
        if (!(node instanceof JsonArray arr)) return false;
        arrayIndex++;
        return arrayIndex < arr.size();
    }

    @Override
    public @NotNull ByteSlice elementValue() {
        byte[] bytes = currentElement().toString().getBytes(java.nio.charset.StandardCharsets.UTF_8);
        return new ByteSlice(bytes, 0, bytes.length);
    }

    @Override public int     elementValueAsInt()                  { return coerceToInt(currentElement()); }
    @Override public long    elementValueAsLong()                 { return coerceToLong(currentElement()); }
    @Override public double  elementValueAsDouble()               { return coerceToDouble(currentElement()); }
    @Override public float   elementValueAsFloat()                { return (float) coerceToDouble(currentElement()); }
    @Override public short   elementValueAsShort()                { return (short) coerceToInt(currentElement()); }
    @Override public byte    elementValueAsByte()                 { return (byte)  coerceToInt(currentElement()); }
    @Override public boolean elementValueAsBoolean()              { return coerceToBool(currentElement()); }
    @Override public @NotNull String elementValueAsUnquotedString() { return coerceToString(currentElement()); }

    @Override
    public @NotNull JsonDomCursor elementValueCursor() {
        return new JsonDomCursor(currentElement());
    }

    // -------------------------------------------------------------------------
    // Current key / value helpers
    // -------------------------------------------------------------------------

    private @NotNull String currentKey() {
        if (objectEntries == null || objectIndex < 0 || objectIndex >= objectEntries.size())
            throw new IllegalStateException("No current field - call nextField() first");
        return objectEntries.get(objectIndex).getKey();
    }

    private @NotNull JsonValue currentFieldValue() {
        if (objectEntries == null || objectIndex < 0 || objectIndex >= objectEntries.size())
            throw new IllegalStateException("No current field - call nextField() first");
        JsonValue v = objectEntries.get(objectIndex).getValue();
        return v != null ? v : JsonNull.INSTANCE;
    }

    private @NotNull JsonValue currentElement() {
        if (!(node instanceof JsonArray arr))
            throw new IllegalStateException("Not in array context");
        if (arrayIndex < 0 || arrayIndex >= arr.size())
            throw new IllegalStateException("No current element - call nextElement() first");
        JsonValue v = arr.getRaw(arrayIndex);
        return v;
    }

    private static int coerceToInt(@NotNull JsonValue v) {
        if (v instanceof JsonNumber n) {
            return n.intValue();
        } else if (v instanceof JsonString s) {
            return Integer.parseInt(s.value());
        } else if (v instanceof JsonBoolean b) {
            return b.value() ? 1 : 0;
        }
        return 0;
    }

    private static long coerceToLong(@NotNull JsonValue v) {
        if (v instanceof JsonNumber n) {
            return n.longValue();
        } else if (v instanceof JsonString s) {
            return Long.parseLong(s.value());
        } else if (v instanceof JsonBoolean b) {
            return b.value() ? 1L : 0L;
        }
        return 0L;
    }

    private static double coerceToDouble(@NotNull JsonValue v) {
        if (v instanceof JsonNumber n) {
            return n.doubleValue();
        } else if (v instanceof JsonString s) {
            return Double.parseDouble(s.value());
        } else if (v instanceof JsonBoolean b) {
            return b.value() ? 1.0 : 0.0;
        }
        return 0.0;
    }

    private static boolean coerceToBool(@NotNull JsonValue v) {
        if (v instanceof JsonBoolean b) {
            return b.value();
        } else if (v instanceof JsonNumber n) {
            return n.intValue() != 0;
        } else if (v instanceof JsonString s) {
            return Boolean.parseBoolean(s.value());
        }
        return false;
    }

    private static @NotNull String coerceToString(@NotNull JsonValue v) {
        if (v instanceof JsonString s) {
            return s.value();
        } else if (v instanceof JsonNull) {
            return "";
        }
        return v.toString();
    }
}
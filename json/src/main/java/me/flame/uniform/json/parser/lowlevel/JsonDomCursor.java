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

import static me.flame.uniform.json.resolvers.CoreTypeResolverRegistry.*;

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

    @Override public int     fieldValueAsInt()              { return coerceInt(currentFieldValue()); }
    @Override public long    fieldValueAsLong()             { return coerceLong(currentFieldValue()); }
    @Override public double  fieldValueAsDouble()           { return coerceDouble(currentFieldValue()); }
    @Override public float   fieldValueAsFloat()            { return (float) coerceDouble(currentFieldValue()); }
    @Override public short   fieldValueAsShort()            { return (short) coerceInt(currentFieldValue()); }
    @Override public byte    fieldValueAsByte()             { return (byte)  coerceInt(currentFieldValue()); }
    @Override public boolean fieldValueAsBoolean()          { return coerceBool(currentFieldValue()); }
    @Override public @NotNull String fieldValueAsUnquotedString() { return coerceString(currentFieldValue()); }

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

    @Override public int     elementValueAsInt()                  { return coerceInt(currentElement()); }
    @Override public long    elementValueAsLong()                 { return coerceLong(currentElement()); }
    @Override public double  elementValueAsDouble()               { return coerceDouble(currentElement()); }
    @Override public float   elementValueAsFloat()                { return (float) coerceDouble(currentElement()); }
    @Override public short   elementValueAsShort()                { return (short) coerceInt(currentElement()); }
    @Override public byte    elementValueAsByte()                 { return (byte)  coerceInt(currentElement()); }
    @Override public boolean elementValueAsBoolean()              { return coerceBool(currentElement()); }
    @Override public @NotNull String elementValueAsUnquotedString() { return coerceString(currentElement()); }

    @Override
    public @NotNull JsonDomCursor elementValueCursor() {
        return new JsonDomCursor(currentElement());
    }

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

    public @NotNull JsonValue currentElement() {
        if (!(node instanceof JsonArray arr))
            throw new IllegalStateException("Not in array context");
        if (arrayIndex < 0 || arrayIndex >= arr.size())
            throw new IllegalStateException("No current element - call nextElement() first");
        JsonValue v = arr.getRaw(arrayIndex);
        return v;
    }
}
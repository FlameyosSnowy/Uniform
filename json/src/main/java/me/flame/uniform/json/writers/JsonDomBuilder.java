package me.flame.uniform.json.writers;

import me.flame.uniform.json.dom.JsonArray;
import me.flame.uniform.json.dom.JsonBoolean;
import me.flame.uniform.json.dom.JsonDouble;
import me.flame.uniform.json.dom.JsonLong;
import me.flame.uniform.json.dom.JsonNull;
import me.flame.uniform.json.dom.JsonObject;
import me.flame.uniform.json.dom.JsonString;
import me.flame.uniform.json.dom.JsonValue;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayDeque;
import java.util.Deque;

/**
 * A {@link JsonStringWriter} subclass that builds a {@link JsonValue} DOM tree
 * instead of serialising to a string.
 *
 * <p>It is passed directly to an existing {@link me.flame.uniform.json.mappers.JsonWriterMapper},
 * which drives it with the same {@code beginObject}/{@code name}/{@code value}/…
 * calls it would use for string output. The result is retrieved via {@link #result()}.
 *
 * <h3>State machine</h3>
 * A {@link Deque} of {@link Frame} objects tracks the current container.
 * Each frame is either an object-in-progress (holding the pending key and a
 * {@link JsonObject}) or an array-in-progress (holding a {@link JsonArray}).
 * Scalars emitted while a frame is active are appended to the current container;
 * the root scalar case (no frame) sets {@link #root} directly.
 */
public final class JsonDomBuilder extends JsonStringWriter {

    // -------------------------------------------------------------------------
    // Frame — tracks current container context
    // -------------------------------------------------------------------------

    private sealed interface Frame permits ObjectFrame, ArrayFrame {}

    private static final class ObjectFrame implements Frame {
        final JsonObject obj = new JsonObject();
        @Nullable String pendingKey = null;
    }

    private static final class ArrayFrame implements Frame {
        final JsonArray arr = new JsonArray();
    }

    // -------------------------------------------------------------------------
    // State
    // -------------------------------------------------------------------------

    private final Deque<Frame> stack = new ArrayDeque<>();
    private @Nullable JsonValue root = null;

    public JsonDomBuilder() {
        super();
    }

    // -------------------------------------------------------------------------
    // Result
    // -------------------------------------------------------------------------

    /**
     * Returns the built {@link JsonObject} after the mapper has finished writing.
     *
     * @throws IllegalStateException if the root value was not a JSON object, or
     *                               if writing is still in progress
     */
    public @NotNull JsonObject result() {
        if (!stack.isEmpty())
            throw new IllegalStateException("JSON writing is still in progress (" + stack.size() + " open containers)");
        if (root == null)
            throw new IllegalStateException("No value was written");
        if (!(root instanceof JsonObject obj))
            throw new IllegalStateException("Root value is not a JsonObject: " + root.getClass().getSimpleName());
        return obj;
    }

    // -------------------------------------------------------------------------
    // Container lifecycle
    // -------------------------------------------------------------------------

    @Override
    public JsonStringWriter beginObject() {
        stack.push(new ObjectFrame());
        return this;
    }

    @Override
    public JsonStringWriter endObject() {
        Frame frame = stack.pop();
        if (!(frame instanceof ObjectFrame of))
            throw new IllegalStateException("endObject() called but current frame is an array");
        append(of.obj);
        return this;
    }

    @Override
    public JsonStringWriter beginArray() {
        stack.push(new ArrayFrame());
        return this;
    }

    @Override
    public JsonStringWriter endArray() {
        Frame frame = stack.pop();
        if (!(frame instanceof ArrayFrame af))
            throw new IllegalStateException("endArray() called but current frame is an object");
        append(af.arr);
        return this;
    }

    // -------------------------------------------------------------------------
    // Key
    // -------------------------------------------------------------------------

    @Override
    public JsonStringWriter name(String key) {
        pendingKey(key);
        return this;
    }

    @Override
    public JsonStringWriter nameAscii(String key) {
        pendingKey(key);
        return this;
    }

    private void pendingKey(String key) {
        if (!(stack.peek() instanceof ObjectFrame of))
            throw new IllegalStateException("name() called outside of an object context");
        of.pendingKey = key;
    }

    // -------------------------------------------------------------------------
    // Scalar values
    // -------------------------------------------------------------------------

    @Override
    public JsonStringWriter value(String v) {
        append(v == null ? JsonNull.INSTANCE : new JsonString(v));
        return this;
    }

    @Override
    public JsonStringWriter value(long v) {
        append(new JsonLong(v));
        return this;
    }

    @Override
    public JsonStringWriter value(double v) {
        append(new JsonDouble(v));
        return this;
    }

    @Override
    public JsonStringWriter value(boolean v) {
        append(JsonBoolean.of(v));
        return this;
    }

    @Override
    public JsonStringWriter nullValue() {
        append(JsonNull.INSTANCE);
        return this;
    }

    // -------------------------------------------------------------------------
    // Array-specific overloads (arrayValue / arrayNullValue)
    // These mirror JsonStringWriter's array-context variants.
    // -------------------------------------------------------------------------

    @Override
    public JsonStringWriter arrayValue(long v) {
        append(new JsonLong(v));
        return this;
    }

    @Override
    public JsonStringWriter arrayValue(double v) {
        append(new JsonDouble(v));
        return this;
    }

    @Override
    public JsonStringWriter arrayValue(boolean v) {
        append(JsonBoolean.of(v));
        return this;
    }

    @Override
    public JsonStringWriter arrayValue(Number v) {
        append(v == null ? JsonNull.INSTANCE : new JsonDouble(v.doubleValue()));
        return this;
    }

    @Override
    public JsonStringWriter arrayNullValue() {
        append(JsonNull.INSTANCE);
        return this;
    }

    private void append(JsonValue value) {
        Frame top = stack.peek();

        if (top == null) {
            // Root value
            root = value;
            return;
        }

        if (top instanceof ObjectFrame of) {
            if (of.pendingKey == null)
                throw new IllegalStateException("value() called in object context without a preceding name()");
            of.obj.put(of.pendingKey, value);
            of.pendingKey = null;
        } else if (top instanceof ArrayFrame af) {
            af.arr.add(value);
        }
    }

    // -------------------------------------------------------------------------
    // Suppress string output — this builder never produces a string
    // -------------------------------------------------------------------------

    @Override
    public String toString() {
        throw new UnsupportedOperationException(
            "JsonDomBuilder does not produce a string — call result() instead");
    }
}
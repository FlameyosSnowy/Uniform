package me.flame.uniform.json;

import me.flame.uniform.json.dom.JsonArray;
import me.flame.uniform.json.dom.JsonBoolean;
import me.flame.uniform.json.dom.JsonByte;
import me.flame.uniform.json.dom.JsonDouble;
import me.flame.uniform.json.dom.JsonFloat;
import me.flame.uniform.json.dom.JsonInteger;
import me.flame.uniform.json.dom.JsonLong;
import me.flame.uniform.json.dom.JsonNull;
import me.flame.uniform.json.dom.JsonNumber;
import me.flame.uniform.json.dom.JsonObject;
import me.flame.uniform.json.dom.JsonShort;
import me.flame.uniform.json.dom.JsonString;
import me.flame.uniform.json.dom.JsonValue;
import me.flame.uniform.json.writers.JsonStringWriter;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Serialises a {@link JsonValue} DOM tree directly to a JSON string,
 * bypassing the mapper/codegen registry entirely.
 *
 * <p>Uses an iterative DFS with an explicit {@link ArrayDeque} work-stack instead
 * of recursion, which eliminates per-call stack frame overhead, lets the JIT
 * inline the single loop body freely, and removes any risk of
 * {@link StackOverflowError} on deeply nested documents.
 *
 * <h3>Work-item protocol</h3>
 * <ul>
 *   <li>{@link WriteValue}       - emit a scalar, or open a container and push its children</li>
 *   <li>{@link WriteObjectEntry} - emit {@code out.name(key)} then expand the value</li>
 *   <li>{@link CloseObject}      - emit {@code out.endObject()}</li>
 *   <li>{@link CloseArray}       - emit {@code out.endArray()}</li>
 * </ul>
 *
 * Because the stack is LIFO, children of a container are pushed in <em>reverse</em>
 * order so they are processed in their original order when popped.
 */
final class JsonDomWriter {

    private JsonDomWriter() {}

    // -------------------------------------------------------------------------
    // Work-item sealed hierarchy
    // -------------------------------------------------------------------------

    private sealed interface Task permits WriteValue, WriteObjectEntry, CloseObject, CloseArray {}

    private record WriteValue(JsonValue value)             implements Task {}
    private record WriteObjectEntry(String key, JsonValue value) implements Task {}
    private record CloseObject()                           implements Task {}
    private record CloseArray()                            implements Task {}

    // Singletons - no allocation on every close
    private static final CloseObject CLOSE_OBJECT = new CloseObject();
    private static final CloseArray  CLOSE_ARRAY  = new CloseArray();

    // -------------------------------------------------------------------------
    // Public API
    // -------------------------------------------------------------------------

    /**
     * Serialises {@code value} and returns the resulting JSON string.
     *
     * @param value  the DOM node to serialise - must not be {@code null}
     * @return a well-formed JSON string
     */
    static @NotNull String write(@NotNull JsonValue value) {
        JsonStringWriter out   = new JsonStringWriter();
        Deque<Task>      stack = new ArrayDeque<>(32);

        stack.push(new WriteValue(value));

        while (!stack.isEmpty()) {
            Task pop = stack.pop();
            if (pop instanceof WriteValue) {
                expand(((WriteValue) pop).value, stack, out);
            } else if (pop instanceof WriteObjectEntry) {
                out.name(((WriteObjectEntry) pop).key);
                expand(((WriteObjectEntry) pop).value, stack, out);
            } else if (pop instanceof CloseObject) {
                out.endObject();
            } else if (pop instanceof CloseArray) {
                out.endArray();
            }
        }

        return out.toString();
    }

    // -------------------------------------------------------------------------
    // Expand: either emit a scalar immediately, or open a container and
    // schedule its children + close sentinel onto the stack.
    // -------------------------------------------------------------------------

    @SuppressWarnings("ObjectAllocationInLoop")
    private static void expand(JsonValue value, Deque<Task> stack, JsonStringWriter out) {
        if (Objects.requireNonNull(value) instanceof JsonNull) {
            out.nullValue();
        } else if (value instanceof JsonBoolean b) {
            out.value(b.value());
        } else if (value instanceof JsonNumber n) {
            writeNumber(n, out);
        } else if (value instanceof JsonString s) {
            out.value(s.value());
        } else if (value instanceof JsonObject obj) {
            out.beginObject();
            // Collect entries so we can push in reverse (LIFO -> original order).
            // Use the map's entry set directly - no extra allocation beyond the list.
            List<Map.Entry<String, JsonValue>> entries = new ArrayList<>(obj.size());
            for (Map.Entry<String, JsonValue> e : obj) entries.add(e);

            stack.push(CLOSE_OBJECT);
            for (int i = entries.size() - 1; i >= 0; i--) {
                Map.Entry<String, JsonValue> e = entries.get(i);
                stack.push(new WriteObjectEntry(e.getKey(), e.getValue()));
            }
        } else if (value instanceof JsonArray arr) {
            out.beginArray();
            stack.push(CLOSE_ARRAY);
            // Push elements in reverse so first element is processed first.
            for (int i = arr.size() - 1; i >= 0; i--) {
                stack.push(new WriteValue(arr.getRaw(i)));
            }
        }
    }

    // -------------------------------------------------------------------------
    // Numeric dispatch - picks the tightest overload to avoid precision loss
    // -------------------------------------------------------------------------

    private static void writeNumber(JsonNumber n, JsonStringWriter out) {
        if (Objects.requireNonNull(n) instanceof JsonByte b) {
            out.value(b.longValue());
        } else if (n instanceof JsonShort s) {
            out.value(s.longValue());
        } else if (n instanceof JsonInteger i) {
            out.value(i.longValue());
        } else if (n instanceof JsonLong l) {
            out.value(l.longValue());
        } else if (n instanceof JsonFloat f) {
            out.value(f.doubleValue());
        } else if (n instanceof JsonDouble d) {
            out.value(d.doubleValue());
        }
    }
}
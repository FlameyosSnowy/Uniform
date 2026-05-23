package io.github.flameyossnowy.uniform.json.writers;

import io.github.flameyossnowy.uniform.json.dom.JsonArray;
import io.github.flameyossnowy.uniform.json.dom.JsonBoolean;
import io.github.flameyossnowy.uniform.json.dom.JsonByte;
import io.github.flameyossnowy.uniform.json.dom.JsonDouble;
import io.github.flameyossnowy.uniform.json.dom.JsonFloat;
import io.github.flameyossnowy.uniform.json.dom.JsonInteger;
import io.github.flameyossnowy.uniform.json.dom.JsonLong;
import io.github.flameyossnowy.uniform.json.dom.JsonNull;
import io.github.flameyossnowy.uniform.json.dom.JsonNumber;
import io.github.flameyossnowy.uniform.json.dom.JsonObject;
import io.github.flameyossnowy.uniform.json.dom.JsonShort;
import io.github.flameyossnowy.uniform.json.dom.JsonString;
import io.github.flameyossnowy.uniform.json.dom.JsonValue;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Serializes a {@link JsonValue} DOM tree directly to a JSON string,
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
public final class JsonDomWriter {
    private JsonDomWriter() {}

    private sealed interface Task permits WriteValue, WriteObjectEntry, CloseObject, CloseArray {}

    private record WriteValue(JsonValue value)             implements Task {}
    private record WriteObjectEntry(String key, JsonValue value) implements Task {}
    private record CloseObject()                           implements Task {}
    private record CloseArray()                            implements Task {}

    private static final CloseObject CLOSE_OBJECT = new CloseObject();
    private static final CloseArray  CLOSE_ARRAY  = new CloseArray();

    /**
     * Serialises {@code value} and returns the resulting JSON string.
     *
     * @param value  the DOM node to serialize - must not be {@code null}
     * @return a well-formed JSON string
     */
    public static @NotNull String write(@NotNull JsonValue value) {
        JsonStringWriter out   = new JsonStringWriter();
        Deque<Task>      stack = new ArrayDeque<>();

        stack.push(new WriteValue(value));

        while (!stack.isEmpty()) {
            Task pop = stack.pop();
            switch (pop) {
                case WriteValue writeValue -> expand(writeValue.value, stack, out);
                case WriteObjectEntry writeObjectEntry -> {
                    out.name(writeObjectEntry.key);
                    expand(writeObjectEntry.value, stack, out);
                }
                case CloseObject _ -> out.endObject();
                case CloseArray _ -> out.endArray();
                default -> {}
            }
        }

        return out.toString();
    }

    @SuppressWarnings("ObjectAllocationInLoop")
    private static void expand(JsonValue value, Deque<Task> stack, JsonStringWriter out) {
        Objects.requireNonNull(value);
        switch (value) {
            case JsonNull _ -> out.nullValue();
            case JsonBoolean b -> out.value(b.value());
            case JsonNumber n -> writeNumber(n, out);
            case JsonString s -> out.value(s.value());
            case JsonObject obj -> {
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
            }
            case JsonArray arr -> {
                out.beginArray();
                stack.push(CLOSE_ARRAY);
                // Push elements in reverse so first element is processed first.
                for (int i = arr.size() - 1; i >= 0; i--) {
                    stack.push(new WriteValue(arr.getRaw(i)));
                }
            }
            default -> {
            }
        }
    }

    private static void writeNumber(JsonNumber n, JsonStringWriter out) {
        Objects.requireNonNull(n);
        switch (n) {
            case JsonByte b -> out.value(b.longValue());
            case JsonShort s -> out.value(s.longValue());
            case JsonInteger i -> out.value(i.longValue());
            case JsonLong l -> out.value(l.longValue());
            case JsonFloat f -> out.value(f.floatValue());
            case JsonDouble d -> out.value(d.doubleValue());
            default -> {}
        }
    }
}
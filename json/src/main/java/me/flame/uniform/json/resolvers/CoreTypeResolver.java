package me.flame.uniform.json.resolvers;

import me.flame.uniform.json.dom.JsonValue;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * Bidirectional codec between a Java type {@code T} and a {@link JsonValue} DOM node.
 *
 * <p>Implementations are registered with {@link CoreTypeResolverRegistry} and
 * consulted by:
 * <ul>
 *   <li>{@code JsonAdapter.treeToValue(JsonValue, Class)}  — calls {@link #deserialize}</li>
 *   <li>{@code JsonAdapter.valueToTree(T)}                 — calls {@link #serialize}</li>
 * </ul>
 *
 * <h3>Implementing a resolver</h3>
 * <pre>{@code
 * public final class UriResolver implements CoreTypeResolver<URI> {
 *
 *     @Override public Class<URI> getType() { return URI.class; }
 *
 *     @Override public @Nullable URI deserialize(@NotNull JsonValue value) {
 *         if (value instanceof JsonNull) return null;
 *         if (value instanceof JsonString s) return URI.create(s.value());
 *         throw new IllegalArgumentException("Expected string, got " + value.getClass().getSimpleName());
 *     }
 *
 *     @Override public @NotNull JsonValue serialize(@NotNull URI value) {
 *         return new JsonString(value.toString());
 *     }
 * }
 * }</pre>
 *
 * @param <T> the Java type this resolver handles
 */
public interface CoreTypeResolver<T> {

    /**
     * Returns the Java type this resolver handles.
     * Used as the registration key in {@link CoreTypeResolverRegistry}.
     */
    @NotNull Class<T> getType();

    /**
     * Converts a {@link JsonValue} DOM node to an instance of {@code T}.
     *
     * @param value the DOM node — never {@code null} (but may be {@link me.flame.uniform.json.dom.JsonNull})
     * @return the converted value, or {@code null} if {@code value} is JSON null
     * @throws IllegalArgumentException if the node type is incompatible with {@code T}
     */
    @Nullable T deserialize(@NotNull JsonValue value);

    /**
     * Converts an instance of {@code T} to a {@link JsonValue} DOM node.
     *
     * @param value the value to convert — never {@code null}
     * @return the DOM representation
     */
    @NotNull JsonValue serialize(@NotNull T value);
}
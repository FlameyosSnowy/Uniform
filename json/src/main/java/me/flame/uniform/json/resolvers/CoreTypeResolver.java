package me.flame.uniform.json.resolvers;

import me.flame.uniform.json.dom.JsonValue;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * Converts a {@link JsonValue} DOM node to an instance of {@code T}.
 *
 * <p>Implementations are registered with {@link CoreTypeResolverRegistry} and
 * consulted by {@code JsonAdapter.treeToValue(JsonValue, Class)} before falling
 * through to the mapper registry for {@code @SerializedObject} POJOs.
 *
 * @param <T> the Java type this resolver produces
 */
public interface CoreTypeResolver<T> {

    /**
     * Returns the Java type this resolver handles.
     * Used as the registration key in {@link CoreTypeResolverRegistry}.
     */
    @NotNull Class<T> getType();

    /**
     * Converts {@code value} to an instance of {@code T}.
     *
     * @param value the DOM node — never {@code null}
     * @return the converted value, or {@code null} if the node represents JSON null
     * @throws me.flame.uniform.json.exceptions.JsonException if conversion fails
     */
    @Nullable T resolve(@NotNull JsonValue value);
}
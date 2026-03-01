package me.flame.uniform.json.dom;

/**
 * Sealed base type for all JSON values.
 * <p>
 * Typed accessors live on the concrete subtypes ({@link JsonObject}, {@link JsonArray},
 * {@link JsonString}, {@link JsonNumber}, {@link JsonBoolean}).  This interface only
 * exposes behaviour that is meaningful for <em>every</em> JSON value.
 */
public sealed interface JsonValue
        permits JsonString, JsonNumber, JsonBoolean, JsonNull, JsonObject, JsonArray {

    /** Returns {@code true} if and only if this value represents a JSON {@code null}. */
    default boolean isNull() {
        return false;
    }

    /**
     * Serialises this value back to a JSON string.
     * Each subtype overrides {@link Object#toString()} to provide this.
     */
    String toString();
}

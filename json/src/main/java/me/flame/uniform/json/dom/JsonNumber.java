package me.flame.uniform.json.dom;

/**
 * Sealed interface representing any JSON numeric value.
 * <p>
 * Concrete subtypes ({@link JsonByte}, {@link JsonShort}, {@link JsonInteger},
 * {@link JsonLong}, {@link JsonFloat}, {@link JsonDouble}) carry the exact Java
 * primitive type chosen at construction time, but every subtype can be widened to
 * any of the numeric accessors below.
 */
public sealed interface JsonNumber extends JsonValue
        permits JsonByte, JsonShort, JsonInteger, JsonLong, JsonFloat, JsonDouble {

    byte byteValue();
    short shortValue();
    int intValue();
    long longValue();
    float floatValue();
    double doubleValue();
}

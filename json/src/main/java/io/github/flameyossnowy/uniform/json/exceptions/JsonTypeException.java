package io.github.flameyossnowy.uniform.json.exceptions;

import io.github.flameyossnowy.uniform.json.dom.JsonBoolean;
import io.github.flameyossnowy.uniform.json.dom.JsonObject;
import io.github.flameyossnowy.uniform.json.dom.JsonValue;

/**
 * Thrown when a {@link JsonValue} is accessed with the wrong type.
 * <p>
 * For example, calling {@link JsonObject#getString(String)} on a key whose value is a
 * {@link JsonBoolean} will throw this exception.
 */
public final class JsonTypeException extends RuntimeException {

    public JsonTypeException(String key, String expectedType, JsonValue actual) {
        super("Key \"" + key + "\": expected " + expectedType
                + " but found " + actual.getClass().getSimpleName()
                + " (" + actual + ")");
    }

    public JsonTypeException(int index, String expectedType, JsonValue actual) {
        super("Index [" + index + "]: expected " + expectedType
                + " but found " + actual.getClass().getSimpleName()
                + " (" + actual + ")");
    }
}
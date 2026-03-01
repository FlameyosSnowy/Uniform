package me.flame.uniform.json.exceptions;

import me.flame.uniform.json.dom.JsonObject;

/**
 * Thrown when a key is requested from a {@link JsonObject} that does not contain it.
 * <p>
 * Use {@link JsonObject#contains(String)} or {@link JsonObject#getRaw(String)} to check for
 * key existence before calling a typed accessor if the key is optional.
 */
public final class JsonMissingKeyException extends RuntimeException {

    public JsonMissingKeyException(String key) {
        super("No such key: \"" + key + "\"");
    }
}
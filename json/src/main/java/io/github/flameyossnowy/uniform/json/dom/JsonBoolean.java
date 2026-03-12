package io.github.flameyossnowy.uniform.json.dom;

import org.jetbrains.annotations.NotNull;

public record JsonBoolean(boolean value) implements JsonValue {
    public static final JsonBoolean TRUE  = new JsonBoolean(true);
    public static final JsonBoolean FALSE = new JsonBoolean(false);

    public static JsonBoolean of(boolean value) {
        return value ? TRUE : FALSE;
    }

    @Override
    public @NotNull String toString() {
        return Boolean.toString(value);
    }
}
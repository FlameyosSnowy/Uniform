package me.flame.uniform.json.exceptions;

import org.jetbrains.annotations.NotNull;

import java.util.List;

public class Exceptions {
    @NotNull
    public static JsonException mergeExceptions(String message, @NotNull List<? extends Throwable> errors) {
        if (errors.isEmpty()) {
            return new JsonException(message);
        }

        JsonException merged = new JsonException(message);

        for (Throwable t : errors) {
            merged.addSuppressed(t);
        }

        return merged;
    }
}
